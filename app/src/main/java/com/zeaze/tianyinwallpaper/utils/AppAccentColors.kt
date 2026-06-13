package com.zeaze.tianyinwallpaper.utils

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color

@Immutable
data class AppAccentColor(
    val key: String,
    val label: String,
    val light: Color,
    val dark: Color
) {
    fun resolve(useDarkTheme: Boolean): Color = if (useDarkTheme) dark else light
}

object AppAccentColors {
    const val PREF_KEY = "appAccentColor"
    const val DEFAULT_KEY = "blue"

    val presets = listOf(
        AppAccentColor("blue", "蓝色", Color(0xFF0088FF), Color(0xFF0091FF)),
        AppAccentColor("cyan", "青色", Color(0xFF008FA8), Color(0xFF32C5D2)),
        AppAccentColor("green", "绿色", Color(0xFF1E9B55), Color(0xFF34C759)),
        AppAccentColor("orange", "橙色", Color(0xFFE87817), Color(0xFFFF9F0A)),
        AppAccentColor("red", "红色", Color(0xFFDC3F45), Color(0xFFFF453A)),
        AppAccentColor("pink", "粉色", Color(0xFFD83D83), Color(0xFFFF375F)),
        AppAccentColor("purple", "紫色", Color(0xFF7651D9), Color(0xFFAF72E8))
    )

    fun find(key: String?): AppAccentColor {
        return presets.firstOrNull { it.key == key } ?: presets.first()
    }

    fun resolve(key: String?, useDarkTheme: Boolean): Color = find(key).resolve(useDarkTheme)
}
