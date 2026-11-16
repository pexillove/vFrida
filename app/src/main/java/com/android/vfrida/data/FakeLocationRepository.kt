package com.android.vfrida.data

import android.content.pm.ApplicationInfo
import android.util.Log
import androidx.lifecycle.MutableLiveData
import com.android.bb.BPackageManager
import com.android.bb.BbCore
import com.android.bb.parcel.location.BLocation
import com.android.bb.BLocationManager
import com.android.vfrida.bean.FakeLocationBean


class FakeLocationRepository {
    val TAG: String = "FakeLocationRepository"

    fun setPattern(userId: Int, pkg: String, pattern: Int) {
        BLocationManager.get().setPattern(userId, pkg, pattern)
    }

    private fun getPattern(userId: Int, pkg: String): Int {
        return BLocationManager.get().getPattern(userId, pkg)
    }

    private fun getLocation(userId: Int, pkg: String): BLocation? {
        return BLocationManager.get().getLocation(userId, pkg)
    }

    fun setLocation(userId: Int, pkg: String, location: BLocation) {
        BLocationManager.get().setLocation(userId, pkg, location)
    }

    fun getInstalledAppList(
        userID: Int,
        appsFakeLiveData: MutableLiveData<List<FakeLocationBean>>
    ) {
        val installedList = mutableListOf<FakeLocationBean>()
        val installedApplications: List<ApplicationInfo> =
            BPackageManager.get().getInstalledApplications(0, userID)
        
        for (installedApplication in installedApplications) {








            val info = FakeLocationBean(
                userID,
                installedApplication.loadLabel(BbCore.getContext().getPackageManager()).toString(),
                installedApplication.loadIcon(BbCore.getContext().getPackageManager()),
                installedApplication.packageName,
                getPattern(userID, installedApplication.packageName),
                getLocation(userID, installedApplication.packageName)
            )
            installedList.add(info)
        }

        Log.d(TAG, installedList.joinToString(","))
        appsFakeLiveData.postValue(installedList)
    }
}