package com.android.bb.hook.guest;

import android.app.Application;

import com.android.bb.app.BActivityThread;
import com.android.bb.env.BEnvironment;
import com.android.bb.utils.FileUtils;
import com.android.bb.utils.Slog;
import com.android.lppi.XC_MethodHook;
import com.android.lppi.XposedHelpers;

import java.io.File;
import java.io.FileWriter;

/**
 * Dumps the KTW device fingerprint that com.achievo.vipshop uploads.
 *
 * The acsdk classes and endpoints are renamed every release, so anchor on
 * okhttp3.OkHttpClient.newCall(Request): a third-party class that is never
 * obfuscated. The fingerprint body is always {"data":"<base64>"} and larger
 * than 10 KB, while event reports use {"content":...} and config requests use
 * {"param":...}, so the body shape alone is enough to pick it out.
 *
 * The captured body is appended to the virtual /sdcard/Download/ktw_fp_dump.txt,
 * which vip_decode_fp.py already pulls and decrypts.
 */
public final class VipHook extends AppHook {

    private static final String PACKAGE = "com.achievo.vipshop";
    private static final String DUMP_FILE = "ktw_fp_dump.txt";
    private static final int MIN_BODY_LENGTH = 5000;
    private static final String DATA_MARK = "\"data\":\"";

    private Class<?> bufferClass;

    @Override
    public String packageName() {
        return PACKAGE;
    }

    @Override
    protected boolean enabled() {
        return true;
    }

    @Override
    protected void onHook(Application application) {
        ClassLoader loader = application.getClassLoader();
        Class<?> client = XposedHelpers.findClassIfExists("okhttp3.OkHttpClient", loader);
        Class<?> request = XposedHelpers.findClassIfExists("okhttp3.Request", loader);
        bufferClass = XposedHelpers.findClassIfExists("okio.Buffer", loader);
        if (client == null || request == null || bufferClass == null) {
            Slog.w(tag(), "okhttp classes not found: client=" + client + ", request=" + request
                    + ", buffer=" + bufferClass);
            return;
        }
        try {
            XposedHelpers.findAndHookMethod(client, "newCall", request, new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    try {
                        capture(param.args[0]);
                    } catch (Throwable t) {
                        Slog.w(tag(), "capture failed", t);
                    }
                }
            });
            Slog.i(tag(), "hooked OkHttpClient.newCall(Request)");
        } catch (Throwable t) {
            Slog.e(tag(), "hook newCall failed", t);
        }
    }

    private void capture(Object request) {
        Object body = XposedHelpers.callMethod(request, "body");
        if (body == null) {
            return;
        }
        long length = -1;
        try {
            Object value = XposedHelpers.callMethod(body, "contentLength");
            if (value instanceof Number) {
                length = ((Number) value).longValue();
            }
        } catch (Throwable ignored) {
        }
        if (length >= 0 && length <= MIN_BODY_LENGTH) {
            return;
        }

        Object buffer = XposedHelpers.newInstance(bufferClass);
        XposedHelpers.callMethod(body, "writeTo", buffer);
        Object utf8 = XposedHelpers.callMethod(buffer, "readUtf8");
        if (!(utf8 instanceof String)) {
            return;
        }
        String text = (String) utf8;
        if (text.indexOf(DATA_MARK) < 0 || text.length() <= MIN_BODY_LENGTH) {
            return;
        }

        String url = "";
        try {
            url = String.valueOf(XposedHelpers.callMethod(request, "url"));
        } catch (Throwable ignored) {
        }
        Slog.i(tag(), "ktw body captured, len=" + text.length() + ", url=" + url);
        dump(text);
    }

    private void dump(String text) {
        String wrapped = "\n===DUMP_START===\n" + text + "\n===DUMP_END===\n";
        for (File file : dumpTargets()) {
            try {
                FileUtils.mkdirs(file.getParentFile());
                try (FileWriter writer = new FileWriter(file, true)) {
                    writer.write(wrapped);
                }
                Slog.i(tag(), "dumped " + text.length() + " chars -> " + file);
                return;
            } catch (Throwable t) {
                Slog.w(tag(), "write failed " + file, t);
            }
        }
    }

    private File[] dumpTargets() {
        int userId = BActivityThread.getUserId();
        return new File[]{
                new File(BEnvironment.getExternalUserDir(userId), "Download/" + DUMP_FILE),
                new File(BEnvironment.getExternalDataDir(PACKAGE, userId), DUMP_FILE),
                new File(BEnvironment.getDataDir(PACKAGE, userId), DUMP_FILE),
        };
    }
}
