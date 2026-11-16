package com.android.bb.system;

import android.os.IBinder;

import java.util.HashMap;
import java.util.Map;

import com.android.bb.BbCore;
import com.android.bb.system.accounts.BAccountManagerService;
import com.android.bb.system.am.BActivityManagerService;
import com.android.bb.system.am.BJobManagerService;
import com.android.bb.system.location.BLocationManagerService;
import com.android.bb.system.notification.BNotificationManagerService;
import com.android.bb.system.os.BStorageManagerService;
import com.android.bb.system.pm.BPackageManagerService;

import com.android.bb.system.spindle.BSpindleService;
import com.android.bb.system.user.BUserManagerService;


public class ServiceManager {
    private static ServiceManager sServiceManager = null;
    public static final String ACTIVITY_MANAGER = "activity_manager";
    public static final String JOB_MANAGER = "job_manager";
    public static final String PACKAGE_MANAGER = "package_manager";
    public static final String STORAGE_MANAGER = "storage_manager";
    public static final String USER_MANAGER = "user_manager";

    public static final String ACCOUNT_MANAGER = "account_manager";
    public static final String LOCATION_MANAGER = "location_manager";
    public static final String NOTIFICATION_MANAGER = "notification_manager";
    public static final String SPINDLE = "spindle";

    private final Map<String, IBinder> mCaches = new HashMap<>();

    public static ServiceManager get() {
        if (sServiceManager == null) {
            synchronized (ServiceManager.class) {
                if (sServiceManager == null) {
                    sServiceManager = new ServiceManager();
                }
            }
        }
        return sServiceManager;
    }

    public static IBinder getService(String name) {
        return get().getServiceInternal(name);
    }

    private ServiceManager() {
        mCaches.put(ACTIVITY_MANAGER, BActivityManagerService.get());
        mCaches.put(JOB_MANAGER, BJobManagerService.get());
        mCaches.put(PACKAGE_MANAGER, BPackageManagerService.get());
        mCaches.put(STORAGE_MANAGER, BStorageManagerService.get());
        mCaches.put(USER_MANAGER, BUserManagerService.get());

        mCaches.put(ACCOUNT_MANAGER, BAccountManagerService.get());
        mCaches.put(LOCATION_MANAGER, BLocationManagerService.get());
        mCaches.put(NOTIFICATION_MANAGER, BNotificationManagerService.get());
        mCaches.put(SPINDLE, BSpindleService.get());
    }

    public IBinder getServiceInternal(String name) {
        return mCaches.get(name);
    }

    public static void initBlackManager() {
        BbCore.get().getService(ACTIVITY_MANAGER);
        BbCore.get().getService(JOB_MANAGER);
        BbCore.get().getService(PACKAGE_MANAGER);
        BbCore.get().getService(STORAGE_MANAGER);
        BbCore.get().getService(USER_MANAGER);

        BbCore.get().getService(ACCOUNT_MANAGER);
        BbCore.get().getService(LOCATION_MANAGER);
        BbCore.get().getService(NOTIFICATION_MANAGER);
        if (BSpindleService.ENABLED) {
            BbCore.get().getService(SPINDLE);
        }
    }
}
