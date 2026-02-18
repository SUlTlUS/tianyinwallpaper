package com.zeaze.tianyinwallpaper

import android.app.Application
import android.content.Context
import androidx.multidex.MultiDex
import com.pgyer.pgyersdk.PgyerSDKManager

class App : Application() {
    override fun onCreate() {
        super.onCreate()
    }

    override fun attachBaseContext(base: Context) {
        super.attachBaseContext(base)
        initPgyerSDK(this)
        MultiDex.install(this)
    }

    companion object {
        const val TIANYIN = "tianyin"

        private fun initPgyerSDK(application: App) {
            PgyerSDKManager.Init()
                .setContext(application)
                .start()
        }
    }
}
