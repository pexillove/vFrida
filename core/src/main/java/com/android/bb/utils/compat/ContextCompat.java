package com.android.bb.utils.compat;

import android.content.Context;
import android.content.ContextWrapper;
import android.util.*;

import black.android.app.BRContextImpl;
import black.android.app.BRContextImplKitkat;
import black.android.content.AttributionSourceStateContext;
import black.android.content.BRAttributionSource;
import black.android.content.BRAttributionSourceState;
import black.android.content.BRContentResolver;
import com.android.bb.BbCore;
import com.android.bb.app.BActivityThread;
import com.android.bb.utils.Slog;
import com.android.bb.BConfig;


public class ContextCompat {
    public static final String TAG = ContextCompat.class.getSimpleName();

    public static void fixAttributionSourceState(Object obj, int uid) {
        Object mAttributionSourceState;
        if (obj != null && BRAttributionSource.get(obj)._check_mAttributionSourceState() != null) {
            mAttributionSourceState = BRAttributionSource.get(obj).mAttributionSourceState();

            AttributionSourceStateContext attributionSourceStateContext = BRAttributionSourceState.get(mAttributionSourceState);
            attributionSourceStateContext._set_packageName(BConfig.get().getString(BConfig.KEY_HOST_PACKAGE, null));
            attributionSourceStateContext._set_uid(uid);
            fixAttributionSourceState(BRAttributionSource.get(obj).getNext(), uid);
        }
    }

    public static void fix(Context context) {
        try {
            
            if (context == null) {
                Slog.w(TAG, "Context is null, skipping ContextCompat.fix");
                return;
            }
            
            int deep = 0;
            while (context instanceof ContextWrapper) {
                context = ((ContextWrapper) context).getBaseContext();
                deep++;
                if (deep >= 10) {
                    return;
                }
            }
            
            
            if (context == null) {
                Slog.w(TAG, "Base context is null after unwrapping, skipping ContextCompat.fix");
                return;
            }
            
            BRContextImpl.get(context)._set_mPackageManager(null);
            try {
                context.getPackageManager();
            } catch (Throwable e) {
                e.printStackTrace();
            }

            BRContextImpl.get(context)._set_mBasePackageName(BConfig.get().getString(BConfig.KEY_HOST_PACKAGE, null));
            BRContextImplKitkat.get(context)._set_mOpPackageName(BConfig.get().getString(BConfig.KEY_HOST_PACKAGE, null));
            
            try {
                BRContentResolver.get(context.getContentResolver())._set_mPackageName(BConfig.get().getString(BConfig.KEY_HOST_PACKAGE, null));
            } catch (Exception e) {
                Slog.w(TAG, "Failed to fix content resolver: " + e.getMessage());
            }

            if (BuildCompat.isS()) {
                try {
                    
                    
                    fixAttributionSourceState(BRContextImpl.get(context).getAttributionSource(), BbCore.getHostUid());
                } catch (Exception e) {
                    Slog.w(TAG, "Failed to fix attribution source state: " + e.getMessage());
                }
            }
        } catch (Exception e) {
            Slog.e(TAG, "Error in ContextCompat.fix: " + e.getMessage());
            e.printStackTrace();
        }
    }
}
