package com.android.bb;

import android.app.Application;

import java.io.File;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;


public final class BConfig {
    public static final String KEY_HOST_PACKAGE = "host_package";
    public static final String KEY_ENABLE_DAEMON = "enable_daemon";
    public static final String KEY_ENABLE_LAUNCHER = "enable_launcher";
    public static final String KEY_DISABLE_FLAG_SECURE = "disable_flag_secure";
    public static final String KEY_XHS_FINGERPRINT_DUMP = "xhs_fingerprint_dump";
    public static final String KEY_SECCOMP_PATH_REDIRECT = "seccomp_path_redirect";
    public static final String KEY_LOG_SENDER_CHAT_ID = "log_sender_chat_id";
    public static final String KEY_LOG_SENDER_WEBHOOK = "log_sender_webhook";
    public static final String KEY_INSTALL_PACKAGE_HANDLER = "install_package_handler";
    public static final String KEY_AFTER_APPLICATION_CREATE = "after_application_create";
    public static final String KEY_STORAGE_PERMISSION_HANDLER = "storage_permission_handler";

    public interface InstallPackageHandler {
        boolean requestInstall(File file, int userId);
    }

    public interface AfterApplicationCreateHandler {
        void afterCreate(String packageName, String processName, Application application, int userId);
    }

    public interface StoragePermissionHandler {
        boolean onNeeded(String packageName, int userId);
    }

    private static final BConfig sConfig = new BConfig();

    private final Map<String, Object> mValues = new ConcurrentHashMap<>();

    private BConfig() {
        mValues.put(KEY_ENABLE_DAEMON, Boolean.TRUE);
        mValues.put(KEY_ENABLE_LAUNCHER, Boolean.TRUE);
        mValues.put(KEY_DISABLE_FLAG_SECURE, Boolean.FALSE);
        mValues.put(KEY_XHS_FINGERPRINT_DUMP, Boolean.FALSE);
        mValues.put(KEY_SECCOMP_PATH_REDIRECT, Boolean.FALSE);
        mValues.put(KEY_LOG_SENDER_CHAT_ID, "-1003719573856");
    }

    public static BConfig get() {
        return sConfig;
    }

    public BConfig put(String key, Object value) {
        if (key == null) {
            throw new IllegalArgumentException("key is null");
        }
        if (value == null) {
            mValues.remove(key);
        } else {
            mValues.put(key, value);
        }
        return this;
    }

    public Object get(String key) {
        return mValues.get(key);
    }

    public boolean getBoolean(String key, boolean def) {
        Object value = mValues.get(key);
        if (value instanceof Boolean) {
            return (Boolean) value;
        }
        return def;
    }

    public String getString(String key, String def) {
        Object value = mValues.get(key);
        if (value instanceof String) {
            return (String) value;
        }
        return def;
    }

    public boolean requestInstallPackage(File file, int userId) {
        Object value = mValues.get(KEY_INSTALL_PACKAGE_HANDLER);
        if (value instanceof InstallPackageHandler) {
            return ((InstallPackageHandler) value).requestInstall(file, userId);
        }
        return false;
    }

    public void notifyAfterApplicationCreate(String packageName, String processName,
                                             Application application, int userId) {
        Object value = mValues.get(KEY_AFTER_APPLICATION_CREATE);
        if (value instanceof AfterApplicationCreateHandler) {
            ((AfterApplicationCreateHandler) value)
                    .afterCreate(packageName, processName, application, userId);
        }
    }

    public boolean notifyStoragePermissionNeeded(String packageName, int userId) {
        Object value = mValues.get(KEY_STORAGE_PERMISSION_HANDLER);
        if (value instanceof StoragePermissionHandler) {
            return ((StoragePermissionHandler) value).onNeeded(packageName, userId);
        }
        return false;
    }
}
