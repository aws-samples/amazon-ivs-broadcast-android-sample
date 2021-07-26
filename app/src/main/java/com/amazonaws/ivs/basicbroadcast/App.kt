package com.amazonaws.ivs.basicbroadcast

import android.app.Application
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import com.amazonaws.ivs.basicbroadcast.injection.DaggerInjectionComponent
import com.amazonaws.ivs.basicbroadcast.injection.InjectionComponent
import com.amazonaws.ivs.basicbroadcast.injection.InjectionModule

class App : Application(), ViewModelStoreOwner {

    private val appViewModelStore: ViewModelStore by lazy {
        ViewModelStore()
    }

    override fun onCreate() {
        super.onCreate()

        component = DaggerInjectionComponent.builder().injectionModule(InjectionModule(this)).build()
    }

    companion object {
        lateinit var component: InjectionComponent
            private set
    }

    override fun getViewModelStore(): ViewModelStore = appViewModelStore
}
