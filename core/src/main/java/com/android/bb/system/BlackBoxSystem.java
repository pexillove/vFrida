package com.android.bb.system;

import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.util.Log;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import com.android.bb.BbCore;
import com.android.bb.env.AppSystemEnv;
import com.android.bb.env.BEnvironment;
import com.android.bb.system.accounts.BAccountManagerService;
import com.android.bb.system.am.BActivityManagerService;
import com.android.bb.system.am.BJobManagerService;
import com.android.bb.system.location.BLocationManagerService;
import com.android.bb.system.notification.BNotificationManagerService;
import com.android.bb.system.os.BStorageManagerService;
import com.android.bb.system.pm.BPackageInstallerService;
import com.android.bb.system.pm.BPackageManagerService;

import com.android.bb.system.spindle.BSpindleService;
import com.android.bb.system.user.BUserHandle;
import com.android.bb.system.user.BUserManagerService;
import com.android.bb.parcel.pm.InstallOption;
import com.android.bb.utils.FileUtils;


public class BlackBoxSystem {
    private static BlackBoxSystem sBlackBoxSystem;
    private final List<ISystemService> mServices = new ArrayList<>();
    private final static AtomicBoolean isStartup = new AtomicBoolean(false);

    public static BlackBoxSystem getSystem() {
        if (sBlackBoxSystem == null) {
            synchronized (BlackBoxSystem.class) {
                if (sBlackBoxSystem == null) {
                    sBlackBoxSystem = new BlackBoxSystem();
                }
            }
        }
        return sBlackBoxSystem;
    }

    public void startup() {
        if (isStartup.getAndSet(true))
            return;
        BEnvironment.load();

        mServices.add(BPackageManagerService.get());
        mServices.add(BUserManagerService.get());
        mServices.add(BActivityManagerService.get());
        mServices.add(BJobManagerService.get());
        mServices.add(BStorageManagerService.get());
        mServices.add(BPackageInstallerService.get());

        mServices.add(BProcessManagerService.get());
        mServices.add(BAccountManagerService.get());
        mServices.add(BLocationManagerService.get());
        mServices.add(BNotificationManagerService.get());
        mServices.add(BSpindleService.get());

        for (ISystemService service : mServices) {
            service.systemReady();
        }

        List<String> preInstallPackages = AppSystemEnv.getPreInstallPackages();
        for (String preInstallPackage : preInstallPackages) {
            try {
                if (!BPackageManagerService.get().isInstalled(preInstallPackage, BUserHandle.USER_ALL)) {
                    PackageInfo packageInfo = BbCore.getContext().getPackageManager().getPackageInfo(preInstallPackage, 0);
                    BPackageManagerService.get().installPackageAsUser(packageInfo.applicationInfo.sourceDir, InstallOption.installBySystem(), BUserHandle.USER_ALL);
                }
            } catch (PackageManager.NameNotFoundException ignored) {
            }
        }
    }
}
