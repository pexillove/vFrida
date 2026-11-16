package com.android.vfrida.view.list

import androidx.lifecycle.MutableLiveData
import com.android.vfrida.bean.InstalledAppBean
import com.android.vfrida.data.AppsRepository
import com.android.vfrida.view.base.BaseViewModel


class ListViewModel(private val repo: AppsRepository) : BaseViewModel() {

    val appsLiveData = MutableLiveData<List<InstalledAppBean>>()

    val loadingLiveData = MutableLiveData<Boolean>()

    fun previewInstalledList() {
        repo.preloadInstallList()
    }

    fun getInstallAppList(userID: Int) {
        launchOnUI { repo.getInstalledAppList(userID, loadingLiveData, appsLiveData) }
    }
}
