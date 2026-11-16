package com.android.vfrida.util

import com.android.vfrida.data.AppsRepository
import com.android.vfrida.data.FakeLocationRepository

import com.android.vfrida.view.apps.AppsFactory
import com.android.vfrida.view.fake.FakeLocationFactory
import com.android.vfrida.view.list.ListFactory



object InjectionUtil {

    private val appsRepository = AppsRepository()



    private val fakeLocationRepository = FakeLocationRepository()

    fun getAppsFactory() : AppsFactory {
        return AppsFactory(appsRepository)
    }

    fun getListFactory(): ListFactory {
        return ListFactory(appsRepository)
    }

    fun getFakeLocationFactory():FakeLocationFactory{
        return FakeLocationFactory(fakeLocationRepository)
    }
}
