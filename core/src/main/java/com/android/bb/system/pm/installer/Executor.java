package com.android.bb.system.pm.installer;

import com.android.bb.system.pm.BPackageSettings;
import com.android.bb.parcel.pm.InstallOption;


public interface Executor {
    public static final String TAG = Executor.class.getSimpleName();

    int exec(BPackageSettings ps, InstallOption option, int userId);
}
