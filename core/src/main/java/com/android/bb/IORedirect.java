package com.android.bb;

import android.content.Context;
import android.os.Process;
import android.text.TextUtils;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

import com.android.bb.app.BActivityThread;
import com.android.bb.env.BEnvironment;
import com.android.bb.BConfig;
import com.android.bb.BbCore;
import com.android.bb.utils.FileUtils;
import com.android.bb.utils.Slog;
import com.android.bb.utils.TrieTree;

public class IORedirect {
    public static final String TAG = IORedirect.class.getSimpleName();

    private static final String[] HIDE_ROOT_PATHS = {
            "/system/app/Superuser.apk",
            "/sbin/su",
            "/system/bin/su",
            "/system/xbin/su",
            "/data/local/xbin/su",
            "/data/local/bin/su",
            "/system/sd/xbin/su",
            "/system/bin/failsafe/su",
            "/data/local/su",
            "/su/bin/su",
    };

    private static final IORedirect sIORedirect = new IORedirect();
    private static final TrieTree sRedirectTree = new TrieTree();
    private static final TrieTree sBlackTree = new TrieTree();
    private final Map<String, String> mRedirectMap = new LinkedHashMap<>();
    /** Reads of /proc while deciding a cmdline spoof must see the kernel file. */
    private static final ThreadLocal<Integer> sProcBypass = ThreadLocal.withInitial(() -> 0);

    public static IORedirect get() {
        return sIORedirect;
    }

    public void addRedirect(String origPath, String redirectPath) {
        if (TextUtils.isEmpty(origPath) || TextUtils.isEmpty(redirectPath) || mRedirectMap.containsKey(origPath)) {
            return;
        }
        sRedirectTree.add(origPath);
        mRedirectMap.put(origPath, redirectPath);
        if (!new File(redirectPath).exists()) {
            FileUtils.mkdirs(redirectPath);
        }
    }

    public void addBlackRedirect(String path) {
        if (!TextUtils.isEmpty(path)) {
            sBlackTree.add(path);
        }
    }

    public String redirectPath(String path) {
        if (TextUtils.isEmpty(path) || BEnvironment.isVirtualPath(path)) {
            return path;
        }
        if (sProcBypass.get() > 0) {
            return path;
        }
        String siblingCmdline = siblingCmdline(path);
        if (siblingCmdline != null) {
            return siblingCmdline;
        }
        String black = sBlackTree.search(path);
        if (!TextUtils.isEmpty(black)) {
            return black;
        }
        return applyRedirect(path, mRedirectMap);
    }

    public File redirectPath(File path) {
        return path == null ? null : new File(redirectPath(path.getAbsolutePath()));
    }

    public void enableRedirect(String packageName) {
        Context context = BbCore.getContext();
        if (packageName == null || context == null) {
            return;
        }

        Map<String, String> rule = new LinkedHashMap<>();
        Set<String> blackRule = new HashSet<>();
        try {
            int systemUserId = BbCore.getHostUserId();
            int userId = BActivityThread.getUserId();
            String physicalData = BEnvironment.getDataDir(packageName, userId).getAbsolutePath();
            String physicalLib = BEnvironment.getAppLibDir(packageName).getAbsolutePath();
            String physicalDe = BEnvironment.getDeDataDir(packageName, userId).getAbsolutePath();

            rule.put("/data/data/" + packageName + "/lib", physicalLib);
            rule.put("/data/user/" + systemUserId + "/" + packageName + "/lib", physicalLib);
            // Visible nativeLibraryDir (/data/app/<pkg>-1/lib) even when base.apk was not copied.
            rule.put(BEnvironment.getVisibleNativeLibraryDir(packageName), physicalLib);

            rule.put("/data/data/" + packageName, physicalData);
            rule.put("/data/user/" + systemUserId + "/" + packageName, physicalData);

            File baseApk = BEnvironment.getBaseApkDir(packageName);
            File appDir = BEnvironment.getAppDir(packageName);
            if (baseApk.isFile() || appDir.isDirectory()) {
                rule.put(BEnvironment.getVisibleApkDir(packageName), appDir.getAbsolutePath());
            }

            FileUtils.mkdirs(physicalDe);
            rule.put(BEnvironment.getVisibleDeDataDir(packageName, systemUserId), physicalDe);

            File profilesRoot = new File(BEnvironment.getVirtualRoot(), "profiles");
            FileUtils.mkdirs(profilesRoot.getAbsolutePath());
            rule.put("/data/misc/profiles", profilesRoot.getAbsolutePath());

            File profilesCurDir = new File(profilesRoot, "cur/" + userId + "/" + packageName);
            File profilesRefDir = new File(profilesRoot, "ref/" + userId + "/" + packageName);
            FileUtils.mkdirs(profilesCurDir.getAbsolutePath());
            FileUtils.mkdirs(profilesRefDir.getAbsolutePath());
            rule.put("/data/misc/profiles/cur/" + userId + "/" + packageName, profilesCurDir.getAbsolutePath());
            rule.put("/data/misc/profiles/ref/" + userId + "/" + packageName, profilesRefDir.getAbsolutePath());

            if (context.getExternalCacheDir() != null) {
                String external = BEnvironment.getExternalUserDir(userId).getAbsolutePath();
                rule.put("/sdcard", external);
                rule.put("/storage/emulated/" + systemUserId, external);
                blackRule.add("/sdcard/Pictures");
                blackRule.add("/storage/emulated/" + systemUserId + "/Pictures");
            }
            hideRoot(rule);
            proc(rule);
        } catch (Exception e) {
            Slog.w(TAG, "enableRedirect failed for " + packageName, e);
        }

        for (Map.Entry<String, String> entry : rule.entrySet()) {
            addRedirect(entry.getKey(), entry.getValue());
        }
        for (String path : blackRule) {
            addBlackRedirect(path);
        }
        BNative.setupRedirect(rule);
        if (BConfig.get().getBoolean(BConfig.KEY_SECCOMP_PATH_REDIRECT, false)
                && !BNative.enableSeccompIo()) {
            Slog.w(TAG, "enableSeccompIo failed");
        }
    }

    private static String applyRedirect(String path, Map<String, String> rule) {
        String key = sRedirectTree.search(path);
        if (TextUtils.isEmpty(key)) {
            return path;
        }
        String dest = rule.get(key);
        if (dest != null && path.startsWith(key)) {
            return dest + path.substring(key.length());
        }
        return path;
    }

    private static void hideRoot(Map<String, String> rule) {
        for (String path : HIDE_ROOT_PATHS) {
            rule.put(path, path + "-fake");
        }
    }

    private void proc(Map<String, String> rule) {
        int appPid = BActivityThread.getAppPid();
        int pid = Process.myPid();
        File procDir = BEnvironment.getProcDir(appPid);

        File cmdlineFile = new File(procDir, "cmdline");
        putProcAlias(rule, pid, "cmdline", cmdlineFile.getAbsolutePath());
        // Same-uid host processes (main, :black, :pN) would otherwise show up as a
        // second package next to the guest cmdline, which is the multi-app uid check.
        aliasSiblingCmdlines(rule, cmdlineFile);

        File mapsOut = new File(procDir, "maps");
        if (writeFilteredMaps(mapsOut)) {
            putProcAlias(rule, pid, "maps", mapsOut.getAbsolutePath());
        }

        // Guest processes are children of the host, not zygote. Spoof PPid so VA
        // scanners that walk parent cmdline see zygote instead of the host package.
        int zygotePid = findZygotePid();
        if (zygotePid > 0) {
            File statusOut = new File(procDir, "status");
            if (writeSpoofedStatus(statusOut, zygotePid)) {
                putProcAlias(rule, pid, "status", statusOut.getAbsolutePath());
            }
        }
    }

    private static void putProcAlias(Map<String, String> rule, int pid, String name, String dest) {
        rule.put("/proc/" + pid + "/" + name, dest);
        rule.put("/proc/self/" + name, dest);
    }

    /**
     * Point every other process that shares the host uid and whose kernel cmdline is the
     * host package at this guest's cmdline. A scanner that counts distinct packages under
     * one uid then sees only the guest.
     */
    private static void aliasSiblingCmdlines(Map<String, String> rule, File cmdlineFile) {
        if (!cmdlineFile.isFile()) {
            return;
        }
        String dest = cmdlineFile.getAbsolutePath();
        File[] procs = new File("/proc").listFiles();
        if (procs == null) {
            return;
        }
        int self = Process.myPid();
        beginProcBypass();
        try {
            for (File dir : procs) {
                int pid = parsePid(dir.getName());
                if (pid <= 0 || pid == self || !dir.isDirectory()) {
                    continue;
                }
                if (siblingCmdline("/proc/" + pid + "/cmdline") == null) {
                    continue;
                }
                rule.put("/proc/" + pid + "/cmdline", dest);
            }
        } finally {
            endProcBypass();
        }
    }

    /** Guest cmdline file for a same-uid host process, or null when the path should stay. */
    private static String siblingCmdline(String path) {
        int pid = procEntryPid(path, "cmdline");
        if (pid <= 0 || pid == Process.myPid()) {
            return null;
        }
        String hostPkg = BConfig.get().getString(BConfig.KEY_HOST_PACKAGE, null);
        if (TextUtils.isEmpty(hostPkg)) {
            return null;
        }
        int appPid = BActivityThread.getAppPid();
        if (appPid < 0) {
            return null;
        }
        File cmdlineFile = new File(BEnvironment.getProcDir(appPid), "cmdline");
        if (!cmdlineFile.isFile()) {
            return null;
        }
        beginProcBypass();
        try {
            if (statusUid(pid) != BbCore.getHostUid()) {
                return null;
            }
            if (!isHostProcessCmd(readCmdline(pid), hostPkg)) {
                return null;
            }
        } finally {
            endProcBypass();
        }
        return cmdlineFile.getAbsolutePath();
    }

    private static void beginProcBypass() {
        sProcBypass.set(sProcBypass.get() + 1);
    }

    private static void endProcBypass() {
        sProcBypass.set(sProcBypass.get() - 1);
    }

    private static int procEntryPid(String path, String name) {
        String prefix = "/proc/";
        String suffix = "/" + name;
        if (path == null || !path.startsWith(prefix) || !path.endsWith(suffix)) {
            return -1;
        }
        int pidEnd = path.length() - suffix.length();
        if (path.indexOf('/', prefix.length()) != pidEnd) {
            return -1;
        }
        return parsePid(path.substring(prefix.length(), pidEnd));
    }

    private static int parsePid(String text) {
        if (text == null || text.isEmpty()) {
            return -1;
        }
        for (int i = 0; i < text.length(); i++) {
            if (!Character.isDigit(text.charAt(i))) {
                return -1;
            }
        }
        try {
            return Integer.parseInt(text);
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    private static boolean isHostProcessCmd(String token, String hostPkg) {
        return hostPkg.equals(token) || token.startsWith(hostPkg + ":");
    }

    private static int statusUid(int pid) {
        try (BufferedReader reader = new BufferedReader(new FileReader("/proc/" + pid + "/status"))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (!line.startsWith("Uid:")) {
                    continue;
                }
                String[] parts = line.trim().split("\\s+");
                if (parts.length < 2) {
                    return -1;
                }
                try {
                    return Integer.parseInt(parts[1]);
                } catch (NumberFormatException e) {
                    return -1;
                }
            }
        } catch (IOException ignored) {
        }
        return -1;
    }

    private static String readCmdline(int pid) {
        byte[] buf = new byte[256];
        try (FileInputStream in = new FileInputStream("/proc/" + pid + "/cmdline")) {
            int n = in.read(buf);
            if (n <= 0) {
                return "";
            }
            int end = 0;
            while (end < n && buf[end] != 0) {
                end++;
            }
            return new String(buf, 0, end, StandardCharsets.UTF_8);
        } catch (IOException e) {
            return "";
        }
    }

    private static int findZygotePid() {
        File[] procs = new File("/proc").listFiles();
        if (procs == null) {
            return -1;
        }
        int zygote = -1;
        int zygote64 = -1;
        for (File f : procs) {
            String name = f.getName();
            if (name.isEmpty() || !Character.isDigit(name.charAt(0))) {
                continue;
            }
            try (BufferedReader reader = new BufferedReader(new FileReader(new File(f, "cmdline")))) {
                String cmd = reader.readLine();
                if (cmd == null) {
                    continue;
                }
                int nul = cmd.indexOf('\0');
                if (nul >= 0) {
                    cmd = cmd.substring(0, nul);
                }
                cmd = cmd.trim();
                if ("zygote64".equals(cmd)) {
                    zygote64 = Integer.parseInt(name);
                } else if ("zygote".equals(cmd)) {
                    zygote = Integer.parseInt(name);
                }
            } catch (Throwable ignored) {
            }
        }
        return zygote64 > 0 ? zygote64 : zygote;
    }

    private static boolean writeSpoofedStatus(File out, int fakePpid) {
        try (BufferedReader reader = new BufferedReader(new FileReader("/proc/self/status"));
             BufferedWriter writer = new BufferedWriter(new FileWriter(out))) {
            String line;
            while ((line = reader.readLine()) != null) {
                writer.write(line.startsWith("PPid:") ? "PPid:\t" + fakePpid : line);
                writer.newLine();
            }
            return true;
        } catch (Throwable e) {
            return false;
        }
    }

    private static boolean writeFilteredMaps(File out) {
        try (BufferedReader reader = new BufferedReader(new FileReader("/proc/self/maps"));
             BufferedWriter writer = new BufferedWriter(new FileWriter(out))) {
            String line;
            while ((line = reader.readLine()) != null) {
                String lower = line.toLowerCase();
                if (lower.contains("sandhook") || lower.contains("blackbox") || lower.contains("libb.so")) {
                    continue;
                }
                writer.write(line);
                writer.newLine();
            }
            return true;
        } catch (Throwable e) {
            return false;
        }
    }
}
