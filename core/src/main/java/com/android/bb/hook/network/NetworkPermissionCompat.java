package com.android.bb.hook.network;

import java.lang.reflect.Method;

import com.android.bb.hook.IInjector;
import com.android.bb.utils.Slog;

public class NetworkPermissionCompat implements IInjector {
    private static final String TAG = NetworkPermissionCompat.class.getSimpleName();

    private static volatile boolean sInstalled;

    @Override
    public void inject() {
        install();
    }

    @Override
    public boolean isBadEnv() {
        return !sInstalled;
    }

    public static void install() {
        if (sInstalled) {
            return;
        }
        synchronized (NetworkPermissionCompat.class) {
            if (sInstalled) {
                return;
            }
            try {
                Class<?> permissionManager = Class.forName("android.permission.PermissionManager");
                Method disablePermissionCache = permissionManager.getDeclaredMethod("disablePermissionCache");
                disablePermissionCache.setAccessible(true);
                disablePermissionCache.invoke(null);
                sInstalled = true;
                Slog.d(TAG, "disabled framework permission cache");
            } catch (Throwable e) {
                Slog.w(TAG, "install failed: " + e.getMessage(), e);
            }
        }
    }
}
