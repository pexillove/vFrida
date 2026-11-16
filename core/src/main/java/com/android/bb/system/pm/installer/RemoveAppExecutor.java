package com.android.bb.system.pm.installer;

import com.android.bb.env.BEnvironment;
import com.android.bb.system.pm.BPackageSettings;
import com.android.bb.parcel.pm.InstallOption;
import com.android.bb.utils.FileUtils;


public class RemoveAppExecutor implements Executor {
    @Override
    public int exec(BPackageSettings ps, InstallOption option, int userId) {
        FileUtils.deleteDir(BEnvironment.getAppDir(ps.pkg.packageName));
        return 0;
    }
}
