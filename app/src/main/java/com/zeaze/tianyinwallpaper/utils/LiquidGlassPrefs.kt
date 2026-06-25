package com.zeaze.tianyinwallpaper.utils

import android.content.Context
import android.os.Build
import com.zeaze.tianyinwallpaper.App

object LiquidGlassPrefs {
    const val PREF_ENABLE_LIQUID_GLASS = "enableLiquidGlass"

    val isSupported: Boolean
        get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU

    fun isSwitchEnabled(context: Context): Boolean {
        return context.getSharedPreferences(App.TIANYIN, Context.MODE_PRIVATE)
            .getBoolean(PREF_ENABLE_LIQUID_GLASS, true)
    }

    fun isEnabled(context: Context): Boolean = isSupported && isSwitchEnabled(context)
}
