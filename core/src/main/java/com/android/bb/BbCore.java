package com.android.bb;

import android.app.ActivityManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.pm.PackageInfo;
import android.graphics.Color;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Process;
import android.widget.Toast;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import black.android.os.BRUserHandle;
import org.lsposed.hiddenapibypass.HiddenApiBypass;
import com.android.bb.app.BActivityThread;
import com.android.bb.env.BEnvironment;
import com.android.bb.system.DaemonService;
import com.android.bb.system.ServiceManager;

import com.android.bb.hook.content.ContentProviderDelegate;
import com.android.bb.hook.HookManager;
import com.android.bb.proxy.ProxyManifest;
import com.android.bb.utils.FileUtils;
import com.android.bb.utils.ShellUtils;
import com.android.bb.utils.Slog;
import com.android.bb.utils.SimpleCrashFix;
import com.android.bb.utils.compat.BundleCompat;

import com.android.bb.utils.provider.ProviderCall;
import com.android.bb.utils.StackTraceFilter;
import com.android.bb.utils.SocialMediaAppCrashPrevention;
import com.android.bb.utils.DexCrashPrevention;
import com.android.bb.utils.NativeCrashPrevention;
import com.android.bb.utils.CrashMonitor;
import com.android.bb.utils.LogSender;

public class BbCore {
    public static final String TAG = BbCore.class.getSimpleName();

    private static final BbCore sBbCore = new BbCore();
    private static Context sContext;
    private static final int DAEMON_MAX_RETRIES = 3;
    private static final String[] CORE_SERVICES = {
            ServiceManager.ACTIVITY_MANAGER,
            ServiceManager.PACKAGE_MANAGER,
            ServiceManager.STORAGE_MANAGER,
            ServiceManager.USER_MANAGER,
            ServiceManager.JOB_MANAGER,
            ServiceManager.ACCOUNT_MANAGER,
            ServiceManager.LOCATION_MANAGER,
            ServiceManager.NOTIFICATION_MANAGER
    };

    static {
        try {
            SimpleCrashFix.installSimpleFix();
            Slog.d(TAG, "Simple crash fix installed at class loading time");

            StackTraceFilter.install();
            Slog.d(TAG, "Stack trace filter installed at class loading time");

            SocialMediaAppCrashPrevention.initialize();
            Slog.d(TAG, "Social media app crash prevention initialized at class loading time");

            DexCrashPrevention.initialize();
            Slog.d(TAG, "DEX crash prevention initialized at class loading time");

            NativeCrashPrevention.initialize();
            Slog.d(TAG, "Native crash prevention initialized at class loading time");

            CrashMonitor.initialize();
            Slog.d(TAG, "Comprehensive crash monitoring initialized at class loading time");
        } catch (Exception e) {
            Slog.w(TAG, "Failed to install simple crash fix or stack trace filter at class loading: " + e.getMessage());
        }
    }

    private ProcessType mProcessType;
    private final Map<String, IBinder> mServices = new HashMap<>();
    private final Handler mHandler = new Handler(Looper.getMainLooper());
    private final int mHostUid = Process.myUid();
    private final int mHostUserId = BRUserHandle.get().myUserId();

    private boolean mServicesInitialized = false;
    private final List<Runnable> mServiceAvailableCallbacks = new ArrayList<>();
    private final Object mServiceCallbackLock = new Object();

    public static BbCore get() {
        return sBbCore;
    }

    public Handler getHandler() {
        return mHandler;
    }

    public static int getHostUid() {
        return get().mHostUid;
    }

    public static int getHostUserId() {
        return get().mHostUserId;
    }

    public static Context getContext() {
        return sContext;
    }

    public boolean areServicesAvailable() {
        if (mServicesInitialized) {
            return true;
        }
        try {
            if (isMainProcess() && !isBlackProcessRunning()) {
                Slog.w(TAG, "Black process not running, starting it...");
                ensureDaemonRunning(true);
            }
            for (String serviceName : CORE_SERVICES) {
                try {
                    getServiceInternal(serviceName);
                } catch (Exception e) {
                    Slog.w(TAG, "Failed to initialize service: " + serviceName, e);
                }
            }
            mServicesInitialized = true;
            Slog.d(TAG, "Services initialized");
            notifyServiceAvailableCallbacks();
            return true;
        } catch (Exception e) {
            Slog.e(TAG, "Failed to initialize services: " + e.getMessage());
            mServicesInitialized = false;
            return false;
        }
    }

    public IBinder getService(String name) {
        if (!areServicesAvailable()) {
            Slog.w(TAG, "Services not available, skipping service request: " + name);
            return null;
        }
        IBinder binder = getServiceInternal(name);
        if (binder == null && isMainProcess() && !isBlackProcessRunning()) {
            Slog.w(TAG, "Main process trying to access service " + name + " but black process not running, starting it...");
            ensureDaemonRunning(true);
            try {
                Thread.sleep(1000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            binder = getServiceInternal(name);
        }
        return binder;
    }

    private IBinder getServiceInternal(String name) {
        IBinder binder = mServices.get(name);
        if (binder != null && binder.isBinderAlive()) {
            return binder;
        }
        try {
            Bundle bundle = new Bundle();
            bundle.putString("_B_|_server_name_", name);
            Bundle vm = ProviderCall.callSafely(ProxyManifest.getBindProvider(), "VM", null, bundle);
            if (vm == null) {
                Slog.w(TAG, "Provider call returned null for service: " + name);
                return null;
            }
            binder = BundleCompat.getBinder(vm, "_B_|_server_");
            Slog.d(TAG, "getService: " + name + ", " + binder);
            if (binder != null) {
                mServices.put(name, binder);
            } else {
                Slog.w(TAG, "Failed to get binder for service: " + name);
            }
            return binder;
        } catch (Exception e) {
            Slog.e(TAG, "Error getting service: " + name + ": " + e.getMessage());
            return null;
        }
    }

    private boolean isBlackProcessRunning() {
        try {
            Bundle testBundle = new Bundle();
            testBundle.putString("_B_|_server_name_", "test");
            try {
                Bundle result = ProviderCall.callSafely(ProxyManifest.getBindProvider(), "VM", null, testBundle);
                if (result != null) {
                    Slog.d(TAG, "Black process is running - SystemCallProvider accessible");
                    return true;
                }
            } catch (Exception e) {
                Slog.w(TAG, "Provider call failed: " + e.getMessage());
            }

            String authority = ProxyManifest.getBindProvider();
            if (authority != null && !authority.isEmpty()) {
                android.content.pm.ProviderInfo providerInfo = getContext().getPackageManager()
                        .resolveContentProvider(authority, 0);
                if (providerInfo != null) {
                    Slog.d(TAG, "Provider exists but call failed - black process may be starting");
                    return false;
                }
            }

            ActivityManager am = (ActivityManager) getContext().getSystemService(Context.ACTIVITY_SERVICE);
            if (am != null) {
                for (ActivityManager.RunningServiceInfo service : am.getRunningServices(Integer.MAX_VALUE)) {
                    if (service.service.getClassName().contains("DaemonService")) {
                        Slog.d(TAG, "DaemonService is running - black process active");
                        return true;
                    }
                }
            }
            Slog.d(TAG, "Black process is not running");
            return false;
        } catch (Exception e) {
            Slog.w(TAG, "Error checking black process status: " + e.getMessage());
            return false;
        }
    }

    private static String hostPkg() {
        return BConfig.get().getString(BConfig.KEY_HOST_PACKAGE, null);
    }

    private Intent daemonIntent(Context context) {
        Intent intent = new Intent();
        intent.setClass(context, DaemonService.class);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        return intent;
    }

    private void startDaemonService(Context context) {
        Intent intent = daemonIntent(context);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            try {
                context.startForegroundService(intent);
                return;
            } catch (SecurityException e) {
                if (e.getMessage() == null || !e.getMessage().contains("MissingForegroundServiceTypeException")) {
                    throw e;
                }
                Slog.w(TAG, "Foreground service type missing, falling back to regular service");
            }
        }
        context.startService(intent);
    }

    private void ensureDaemonRunning(boolean requireMainProcess) {
        if (!canStartDaemon(requireMainProcess)) {
            if (getContext() == null) {
                mHandler.postDelayed(() -> ensureDaemonRunning(requireMainProcess), 5000);
            }
            return;
        }
        startDaemonWithRetry(requireMainProcess, 0);
    }

    private boolean canStartDaemon(boolean requireMainProcess) {
        if (getContext() == null) {
            Slog.w(TAG, "Context is null, delaying daemon start");
            return false;
        }
        try {
            getContext().getPackageName();
        } catch (Exception e) {
            Slog.w(TAG, "Package name access failed, process state invalid: " + e.getMessage());
            return false;
        }
        if (requireMainProcess) {
            if (!isMainProcess()) {
                Slog.w(TAG, "Not in main process, skipping service start");
                return false;
            }
            return !isBlackProcessRunning();
        }
        return isServerProcess() && BConfig.get().getBoolean(BConfig.KEY_ENABLE_DAEMON, true);
    }

    private void startDaemonWithRetry(boolean requireMainProcess, int retry) {
        if (retry >= DAEMON_MAX_RETRIES) {
            tryAlternativeDaemonStart();
            return;
        }
        if (retry >= 2) {
            mHandler.postDelayed(() -> {
                try {
                    startDaemonService(getContext());
                    Slog.d(TAG, "Delayed DaemonService start successful");
                    if (requireMainProcess) {
                        scheduleProviderCheck();
                    }
                } catch (Exception e) {
                    Slog.e(TAG, "Delayed DaemonService start failed: " + e.getMessage());
                    tryAlternativeDaemonStart();
                }
            }, 1000L * retry);
            return;
        }
        try {
            startDaemonService(getContext());
            Slog.d(TAG, "Started DaemonService");
            if (requireMainProcess) {
                scheduleProviderCheck();
            }
        } catch (Exception e) {
            String message = e.getMessage();
            if (message != null && message.contains("process is bad") && retry < DAEMON_MAX_RETRIES - 1) {
                Slog.w(TAG, "Process is bad, scheduling recovery");
                mHandler.postDelayed(() -> {
                    if (canStartDaemon(requireMainProcess)) {
                        startDaemonWithRetry(requireMainProcess, retry + 1);
                    }
                }, 2000);
                return;
            }
            Slog.e(TAG, "Failed to start DaemonService: " + message, e);
            startDaemonWithRetry(requireMainProcess, retry + 1);
        }
    }

    private void tryAlternativeDaemonStart() {
        Slog.w(TAG, "Trying alternative daemon startup");
        Context context = getContext();
        if (context == null) {
            return;
        }
        try {
            Context appContext = context.getApplicationContext();
            if (appContext != null) {
                startDaemonService(appContext);
                Slog.d(TAG, "Alternative context startup successful");
                return;
            }
        } catch (Exception e) {
            Slog.w(TAG, "Alternative context startup failed: " + e.getMessage());
        }
        Slog.e(TAG, "All alternative startup methods failed");
    }

    private void scheduleProviderCheck() {
        mHandler.postDelayed(() -> {
            try {
                Bundle testBundle = new Bundle();
                testBundle.putString("_B_|_server_name_", "test");
                Bundle result = ProviderCall.callSafely(ProxyManifest.getBindProvider(), "VM", null, testBundle);
                if (result != null) {
                    Slog.d(TAG, "Black process started successfully, SystemCallProvider is accessible");
                } else {
                    Slog.w(TAG, "Black process started but SystemCallProvider is not accessible yet");
                }
            } catch (Exception e) {
                Slog.w(TAG, "SystemCallProvider not accessible yet: " + e.getMessage());
            }
        }, 1000);
    }

    public void ensureBlackProcessInitialized() {
        if (!isMainProcess() || isBlackProcessRunning()) {
            return;
        }
        Slog.w(TAG, "Ensuring black process is initialized...");
        ensureDaemonRunning(true);
        int retryCount = 0;
        while (retryCount < 5 && !isBlackProcessRunning()) {
            try {
                Thread.sleep(500);
                retryCount++;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        if (isBlackProcessRunning()) {
            Slog.d(TAG, "Black process initialized successfully");
        } else {
            Slog.w(TAG, "Black process failed to initialize");
        }
    }

    public void doAttachBaseContext(Context context) {
        try {
            setEssentialProperties();
        } catch (Exception e) {
            Slog.w(TAG, "Failed to set essential properties: " + e.getMessage());
        }

        sContext = context;
        SimpleCrashFix.installSimpleFix();
        initNotificationManager();

        String processName = getProcessName(getContext());
        if (processName.equals(hostPkg())) {
            mProcessType = ProcessType.Main;
            startLogcat();
        } else if (processName.endsWith(getContext().getString(R.string.black_box_service_name))) {
            mProcessType = ProcessType.Server;
        } else {
            mProcessType = ProcessType.BAppClient;
        }
        if (isBlackProcess()) {
            BEnvironment.load();
        }
        anchorServerProcess();
        if (isServerProcess()) {
            ensureDaemonRunning(false);
        }
        HookManager.get().init();
    }

    public void doCreate() {
        SimpleCrashFix.installSimpleFix();
        long startTime = System.currentTimeMillis();
        try {
            ensureBlackProcessInitialized();
            if (System.currentTimeMillis() - startTime > 10000) {
                Slog.w(TAG, "Initialization timeout exceeded, proceeding with fallback services");
            }
            ensureProperInitialization();
            if (isBlackProcess()) {
                ContentProviderDelegate.init();
            }
            if (!isServerProcess()) {
                try {
                    ServiceManager.initBlackManager();
                } catch (Exception e) {
                    Slog.w(TAG, "Failed to initialize ServiceManager, continuing with fallback: " + e.getMessage());
                }
                BPackageManager.get().resetTransactionThrottler();
            }
            Slog.d(TAG, "BlackBox initialization completed in " + (System.currentTimeMillis() - startTime) + "ms");
        } catch (Exception e) {
            Slog.e(TAG, "BlackBox initialization failed after " + (System.currentTimeMillis() - startTime) + "ms", e);
            try {
                if (!isServerProcess()) {
                    ServiceManager.initBlackManager();
                }
            } catch (Exception fallbackEx) {
                Slog.e(TAG, "Fallback initialization also failed", fallbackEx);
            }
        }
        if (isMainProcess()) {
            BNative.spindleAgent(hostPkg());
        }
    }

    private enum ProcessType {
        Server,
        BAppClient,
        Main,
    }

    public boolean isBlackProcess() {
        return mProcessType == ProcessType.BAppClient;
    }

    public boolean isMainProcess() {
        return mProcessType == ProcessType.Main;
    }

    public boolean isServerProcess() {
        return mProcessType == ProcessType.Server;
    }

    private static final ServiceConnection sServerAnchor = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            Slog.d(TAG, "server anchor connected");
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            Slog.w(TAG, "server anchor disconnected");
        }
    };

    /**
     * Keep :black at least as important as this process. A cached :black is frozen,
     * and the next getPackageInfo fails even though the parcel is small.
     */
    private void anchorServerProcess() {
        if (isServerProcess() || getContext() == null) {
            return;
        }
        try {
            Intent intent = new Intent();
            intent.setComponent(new ComponentName(hostPkg(), DaemonService.class.getName()));
            getContext().bindService(intent, sServerAnchor,
                    Context.BIND_AUTO_CREATE | Context.BIND_IMPORTANT | Context.BIND_ABOVE_CLIENT);
        } catch (Throwable t) {
            Slog.w(TAG, "server anchor: " + t.getMessage());
        }
    }

    private void startLogcat() {
        new Thread(() -> {
            Context context = getContext();
            String fileName = context.getPackageName() + "_logcat.txt";
            logDeviceInfo();
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    android.content.ContentValues values = new android.content.ContentValues();
                    values.put(android.provider.MediaStore.Downloads.DISPLAY_NAME, fileName);
                    values.put(android.provider.MediaStore.Downloads.MIME_TYPE, "text/plain");
                    values.put(android.provider.MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/logs");
                    android.net.Uri uri = context.getContentResolver().insert(android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI, values);
                    if (uri != null) {
                        try (OutputStream out = context.getContentResolver().openOutputStream(uri)) {
                            ShellUtils.execCommand("logcat -c", false);
                            java.lang.Process process = Runtime.getRuntime().exec("logcat");
                            try (InputStream in = process.getInputStream()) {
                                byte[] buffer = new byte[4096];
                                int len;
                                while ((len = in.read(buffer)) != -1) {
                                    out.write(buffer, 0, len);
                                }
                            }
                        }
                    }
                } else {
                    File documentsDir = new File(context.getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS), "logs");
                    if (!documentsDir.exists()) {
                        documentsDir.mkdirs();
                    }
                    File logFile = new File(documentsDir, fileName);
                    FileUtils.deleteDir(logFile);
                    ShellUtils.execCommand("logcat -c", false);
                    ShellUtils.execCommand("logcat -f " + logFile.getAbsolutePath(), false);
                }
            } catch (Exception e) {
                Slog.e(TAG, "Failed to save logcat: " + e.getMessage());
            }
        }).start();
    }

    private void logDeviceInfo() {
        for (String line : getDeviceInfoString().split("\n")) {
            Slog.i(TAG, line);
        }
    }

    private static String getProcessName(Context context) {
        int pid = Process.myPid();
        try {
            ActivityManager am = (ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
            List<ActivityManager.RunningAppProcessInfo> processes = am.getRunningAppProcesses();
            if (processes != null) {
                for (ActivityManager.RunningAppProcessInfo info : processes) {
                    if (info.pid == pid) {
                        return info.processName;
                    }
                }
            }
        } catch (Exception e) {
            Slog.w(TAG, "Failed to get process name", e);
        }
        throw new RuntimeException("processName = null");
    }

    private void initNotificationManager() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return;
        }
        NotificationManager nm = (NotificationManager) getContext().getSystemService(Context.NOTIFICATION_SERVICE);
        String channelId = getContext().getPackageName() + ".blackbox_core";
        NotificationChannel channel = new NotificationChannel(channelId, "blackbox_core", NotificationManager.IMPORTANCE_HIGH);
        channel.enableLights(true);
        channel.setLightColor(Color.RED);
        channel.setShowBadge(true);
        channel.setLockscreenVisibility(Notification.VISIBILITY_PUBLIC);
        nm.createNotificationChannel(channel);
    }

    private void setEssentialProperties() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                HiddenApiBypass.setHiddenApiExemptions("");
            }
        } catch (Throwable t) {
            Slog.w(TAG, "HiddenApiBypass failed: " + t.getMessage());
        }
        try {
            BNative.disableResourceLoading();
        } catch (Exception e) {
            Slog.w(TAG, "Failed to call native resource disabling: " + e.getMessage());
        }
        System.setProperty("android.view.WindowManager.IGNORE_WINDOW_LEAKS", "true");
        System.setProperty("android.app.Activity.IGNORE_WINDOW_LEAKS", "true");
        System.setProperty("android.view.WindowManager.SUPPRESS_WINDOW_LEAK_WARNINGS", "true");
    }

    private static void ensureProperInitialization() {
        try {
            BNative.init(Build.VERSION.SDK_INT);
        } catch (Exception e) {
            Slog.w(TAG, "BNative initialization failed: " + e.getMessage());
        }
        try {
            ServiceManager.initBlackManager();
        } catch (Exception e) {
            Slog.w(TAG, "ServiceManager initialization failed: " + e.getMessage());
        }
        try {
            BActivityThread.hookActivityThread();
        } catch (Exception e) {
            Slog.w(TAG, "BActivityThread hooks initialization failed: " + e.getMessage());
        }
    }

    public void addServiceAvailableCallback(Runnable callback) {
        synchronized (mServiceCallbackLock) {
            if (mServicesInitialized) {
                callback.run();
            } else {
                mServiceAvailableCallbacks.add(callback);
            }
        }
    }

    private void notifyServiceAvailableCallbacks() {
        synchronized (mServiceCallbackLock) {
            if (mServiceAvailableCallbacks.isEmpty()) {
                return;
            }
            Slog.d(TAG, "Notifying " + mServiceAvailableCallbacks.size() + " callbacks that services are available");
            for (Runnable callback : mServiceAvailableCallbacks) {
                try {
                    callback.run();
                } catch (Exception e) {
                    Slog.e(TAG, "Error in service available callback", e);
                }
            }
            mServiceAvailableCallbacks.clear();
        }
    }

    public boolean isBlackBoxApp(String packageName) {
        return packageName != null && !packageName.isEmpty() && packageName.equals(hostPkg());
    }

    public interface LogSendListener {
        void onSuccess();
        void onFailure(String error);
    }

    public void sendLogs(String caption, boolean async) {
        sendLogs(caption, async, null);
    }

    public void sendLogs(String caption, boolean async, LogSendListener listener) {
        String chatId = BConfig.get().getString(BConfig.KEY_LOG_SENDER_CHAT_ID, null);
        if (chatId == null || chatId.isEmpty()) {
            return;
        }
        Runnable sendTask = () -> {
            File tempLog = null;
            try {
                tempLog = File.createTempFile("crash_log_", ".txt", getContext().getCacheDir());
                String deviceInfo = getDeviceInfoString();
                try (FileOutputStream fos = new FileOutputStream(tempLog)) {
                    String header = "Caption: " + caption + "\n\n" + deviceInfo + "\n\n--- LOGCAT ---\n";
                    fos.write(header.getBytes("UTF-8"));
                    java.lang.Process process = Runtime.getRuntime().exec("logcat -d -v threadtime");
                    try (InputStream in = process.getInputStream()) {
                        byte[] buffer = new byte[8192];
                        int len;
                        while ((len = in.read(buffer)) != -1) {
                            fos.write(buffer, 0, len);
                        }
                    }
                }
                String error = LogSender.send(chatId, tempLog, deviceInfo);
                if (error != null) {
                    Slog.e(TAG, "Log upload failed: " + error);
                    postLogResult(false, error, listener);
                } else {
                    postLogResult(true, null, listener);
                }
            } catch (Exception e) {
                Slog.e(TAG, "Failed to send logs: " + e.getMessage());
                mHandler.post(() -> {
                    if (listener != null) {
                        listener.onFailure(e.getMessage());
                    }
                });
            } finally {
                if (tempLog != null) {
                    tempLog.delete();
                }
            }
        };
        if (async) {
            new Thread(sendTask).start();
        } else {
            sendTask.run();
        }
    }

    private void postLogResult(boolean success, String error, LogSendListener listener) {
        mHandler.post(() -> {
            try {
                Toast.makeText(getContext(),
                        success ? "Log Upload Success" : "Log Upload Failed: " + error,
                        success ? Toast.LENGTH_SHORT : Toast.LENGTH_LONG).show();
            } catch (Exception ignored) {
            }
            if (listener != null) {
                if (success) {
                    listener.onSuccess();
                } else {
                    listener.onFailure(error);
                }
            }
        });
        Context context = getContext();
        if (context == null) {
            return;
        }
        NotificationManager nm = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm == null) {
            return;
        }
        String channelId = context.getPackageName() + ".blackbox_core";
        Notification.Builder builder = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? new Notification.Builder(context, channelId)
                : new Notification.Builder(context);
        builder.setSmallIcon(success ? android.R.drawable.stat_sys_upload_done : android.R.drawable.stat_notify_error)
                .setContentTitle(success ? "BlackBox Log Upload" : "BlackBox Log Upload Failed")
                .setContentText(success ? "Logs sent successfully" : error)
                .setAutoCancel(true);
        nm.notify(9999, builder.build());
    }

    private String getDeviceInfoString() {
        StringBuilder sb = new StringBuilder();
        try {
            sb.append("DEVICE INFORMATION\n");
            sb.append("Android Version: ").append(Build.VERSION.RELEASE).append("\n");
            sb.append("SDK Level: ").append(Build.VERSION.SDK_INT).append("\n");
            sb.append("Build ID: ").append(Build.ID).append("\n");
            sb.append("Build Display: ").append(Build.DISPLAY).append("\n");
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                sb.append("Security Patch: ").append(Build.VERSION.SECURITY_PATCH).append("\n");
            }
            sb.append("Manufacturer: ").append(Build.MANUFACTURER).append("\n");
            sb.append("Brand: ").append(Build.BRAND).append("\n");
            sb.append("Model: ").append(Build.MODEL).append("\n");
            sb.append("Device: ").append(Build.DEVICE).append("\n");
            sb.append("Product: ").append(Build.PRODUCT).append("\n");
            sb.append("Board: ").append(Build.BOARD).append("\n");
            sb.append("Hardware: ").append(Build.HARDWARE).append("\n");
            sb.append("Supported ABIs: ").append(String.join(", ", Build.SUPPORTED_ABIS)).append("\n");
            if (Build.SUPPORTED_32_BIT_ABIS.length > 0) {
                sb.append("32-bit ABIs: ").append(String.join(", ", Build.SUPPORTED_32_BIT_ABIS)).append("\n");
            }
            if (Build.SUPPORTED_64_BIT_ABIS.length > 0) {
                sb.append("64-bit ABIs: ").append(String.join(", ", Build.SUPPORTED_64_BIT_ABIS)).append("\n");
            }
            sb.append("Fingerprint: ").append(Build.FINGERPRINT).append("\n");
            sb.append("Type: ").append(Build.TYPE).append("\n");
            sb.append("Tags: ").append(Build.TAGS).append("\n");
            try {
                Runtime runtime = Runtime.getRuntime();
                long maxMem = runtime.maxMemory() / (1024 * 1024);
                long totalMem = runtime.totalMemory() / (1024 * 1024);
                long freeMem = runtime.freeMemory() / (1024 * 1024);
                sb.append("Max Heap: ").append(maxMem).append(" MB\n");
                sb.append("Total Heap: ").append(totalMem).append(" MB\n");
                sb.append("Free Heap: ").append(freeMem).append(" MB\n");
                sb.append("Used Heap: ").append(totalMem - freeMem).append(" MB\n");
            } catch (Exception e) {
                sb.append("Memory info unavailable: ").append(e.getMessage()).append("\n");
            }
            try {
                Context context = getContext();
                if (context != null) {
                    sb.append("Package: ").append(context.getPackageName()).append("\n");
                    PackageInfo pInfo = context.getPackageManager().getPackageInfo(context.getPackageName(), 0);
                    sb.append("App Version: ").append(pInfo.versionName).append("\n");
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                        sb.append("Version Code: ").append(pInfo.getLongVersionCode()).append("\n");
                    } else {
                        sb.append("Version Code: ").append(pInfo.versionCode).append("\n");
                    }
                }
            } catch (Exception e) {
                sb.append("App info unavailable: ").append(e.getMessage()).append("\n");
            }
            sb.append("Timestamp: ").append(new SimpleDateFormat("yyyy-MM-dd HH:mm:ss Z", Locale.getDefault()).format(new Date())).append("\n");
        } catch (Exception e) {
            sb.append("Failed to build device info: ").append(e.getMessage());
        }
        return sb.toString();
    }
}
