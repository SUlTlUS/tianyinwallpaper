package com.zeaze.tianyinwallpaper.utils

import android.annotation.TargetApi
import android.app.WallpaperColors
import android.content.Context
import android.graphics.Color
import android.os.Build
import com.zeaze.tianyinwallpaper.App

object WallpaperClockColorMode {
    const val FOLLOW_GLOBAL = 0
    const val LIGHT_CLOCK = 1
    const val DARK_CLOCK = 2

    const val PREF_GLOBAL_MODE = "lockscreen_clock_color_global_mode"

    fun globalMode(context: Context): Int {
        val pref = context.getSharedPreferences(App.TIANYIN, Context.MODE_PRIVATE)
        return sanitizeConcreteMode(pref.getInt(PREF_GLOBAL_MODE, LIGHT_CLOCK))
    }

    fun resolve(context: Context, localMode: Int): Int {
        return if (localMode == FOLLOW_GLOBAL) globalMode(context) else sanitizeConcreteMode(localMode)
    }

    fun label(mode: Int): String {
        return when (mode) {
            FOLLOW_GLOBAL -> "跟随全局"
            LIGHT_CLOCK -> "浅色时钟"
            DARK_CLOCK -> "深色时钟"
            else -> "浅色时钟"
        }
    }

    private fun sanitizeConcreteMode(mode: Int): Int {
        return when (mode) {
            LIGHT_CLOCK, DARK_CLOCK -> mode
            else -> LIGHT_CLOCK
        }
    }

    @TargetApi(Build.VERSION_CODES.O_MR1)
    fun wallpaperColorsFor(context: Context, localMode: Int): WallpaperColors {
        return wallpaperColorsForResolvedMode(resolve(context, localMode))
    }

    @TargetApi(Build.VERSION_CODES.O_MR1)
    fun wallpaperColorsForResolvedMode(mode: Int): WallpaperColors {
        return if (sanitizeConcreteMode(mode) == DARK_CLOCK) {
            WallpaperColors(
                Color.valueOf(Color.WHITE),
                null,
                null,
                WallpaperColors.HINT_SUPPORTS_DARK_TEXT
            )
        } else {
            WallpaperColors(
                Color.valueOf(Color.BLACK),
                null,
                null,
                0
            )
        }
    }
}
