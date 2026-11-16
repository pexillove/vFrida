package com.android.bb.proxy;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.RemoteException;

import com.android.bb.BbCore;
import com.android.bb.parcel.am.PendingResultData;
import com.android.bb.proxy.record.ProxyBroadcastRecord;
import com.android.bb.BActivityManager;


public class ProxyBroadcastReceiver extends BroadcastReceiver {
    public static final String TAG = ProxyBroadcastReceiver.class.getSimpleName();

    @Override
    public void onReceive(Context context, Intent intent) {
        intent.setExtrasClassLoader(context.getClassLoader());
        ProxyBroadcastRecord record = ProxyBroadcastRecord.create(intent);
        if (record.mIntent == null) {
            return;
        }
        PendingResult pendingResult = goAsync();
        try {
            BActivityManager.get().scheduleBroadcastReceiver(record.mIntent, new PendingResultData(pendingResult), record.mUserId);
        } catch (RemoteException e) {
            pendingResult.finish();
        }
    }
}