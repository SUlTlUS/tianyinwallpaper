package com.zeaze.tianyinwallpaper.ui.common

import androidx.compose.material.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

data class GroupedPageColors(
    val pageBackground: Color,
    val groupBackground: Color,
    val subtleGroupBackground: Color,
    val textColor: Color,
    val secondaryTextColor: Color
)

@Composable
fun rememberGroupedPageColors(isDark: Boolean): GroupedPageColors {
    val onBackground = MaterialTheme.colors.onBackground
    return if (isDark) {
        GroupedPageColors(
            pageBackground = Color(0xFF0A0A0C),
            groupBackground = Color(0xFF1C1C20).copy(alpha = 0.94f),
            subtleGroupBackground = Color(0xFF25252A).copy(alpha = 0.88f),
            textColor = Color.White,
            secondaryTextColor = Color.White.copy(alpha = 0.68f)
        )
    } else {
        GroupedPageColors(
            pageBackground = Color.White,
            groupBackground = Color(0xFFF2F3F7),
            subtleGroupBackground = Color(0xFFE8EBF2),
            textColor = Color.Black,
            secondaryTextColor = onBackground.copy(alpha = 0.62f)
        )
    }
}
