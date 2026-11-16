package com.android.bb.proxy;

import java.util.Locale;

import com.android.bb.BConfig;


public class ProxyManifest {
    public static final int FREE_COUNT = 50;

    public static boolean isProxy(String msg) {
        return getBindProvider().equals(msg) || msg.contains("proxy_content_provider_");
    }

    public static String getBindProvider() {
        return BConfig.get().getString(BConfig.KEY_HOST_PACKAGE, null) + ".blackbox.SystemCallProvider";
    }

    public static String getProxyAuthorities(int index) {
        return String.format(Locale.CHINA, "%s.proxy_content_provider_%d", BConfig.get().getString(BConfig.KEY_HOST_PACKAGE, null), index);
    }

    public static String getProxyPendingActivity(int index) {
        return String.format(Locale.CHINA, "com.android.bb.proxy.ProxyPendingActivity$P%d", index);
    }

    public static String getProxyActivity(int index) {
        return String.format(Locale.CHINA, "com.android.bb.proxy.ProxyActivity$P%d", index);
    }

    public static String TransparentProxyActivity(int index) {
        return String.format(Locale.CHINA, "com.android.bb.proxy.TransparentProxyActivity$P%d", index);
    }

    public static String getProxyService(int index) {
        return String.format(Locale.CHINA, "com.android.bb.proxy.ProxyService$P%d", index);
    }

    public static String getProxyJobService(int index) {
        return String.format(Locale.CHINA, "com.android.bb.proxy.ProxyJobService$P%d", index);
    }

    public static String getProxyFileProvider() {
        return BConfig.get().getString(BConfig.KEY_HOST_PACKAGE, null) + ".blackbox.FileProvider";
    }

    public static String getProxyReceiver() {
        return BConfig.get().getString(BConfig.KEY_HOST_PACKAGE, null) + ".stub_receiver";
    }

    public static String getProcessName(int bPid) {
        return BConfig.get().getString(BConfig.KEY_HOST_PACKAGE, null) + ":p" + bPid;
    }
}
