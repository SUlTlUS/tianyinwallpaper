package com.zeaze.tianyinwallpaper.catalog.components

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Tianyin 的液态玻璃外观参数集中放在这里。
 *
 * 上游 AndroidLiquidGlass 的 catalog 组件只是示例组件，后续同步上游时优先替换结构代码，
 * 不要把这些颜色、尺寸、降级策略散落回组件内部。
 */
@Immutable
data class LiquidBottomTabsStyle(
    val accentColor: Color,
    val containerColor: Color,
    val fallbackContainerColor: Color,
    val trackHeight: Dp = 64.dp,
    val indicatorHeight: Dp = 56.dp,
    val trackHorizontalPadding: Dp = 4.dp,
    val trackInnerPadding: Dp = 4.dp,
    val elasticPanelOffset: Dp = 4.dp,
    val trackPressedExpansion: Dp = 16.dp,
    val hiddenContentPressedScale: Float = 1.2f,
    val trackBlurRadius: Dp = 8.dp,
    val trackLensRadius: Dp = 24.dp,
    val indicatorLensWidth: Dp = 10.dp,
    val indicatorLensHeight: Dp = 14.dp,
    val indicatorInnerShadowRadius: Dp = 8.dp
) {
    companion object {
        fun default(isLightTheme: Boolean): LiquidBottomTabsStyle {
            return LiquidBottomTabsStyle(
                accentColor = if (isLightTheme) Color(0xFF0088FF) else Color(0xFF0091FF),
                containerColor = if (isLightTheme) Color(0xFFFAFAFA).copy(0.4f) else Color(0xFF121212).copy(0.4f),
                fallbackContainerColor = if (isLightTheme) Color(0xCCFAFAFA) else Color(0xCC121212)
            )
        }
    }
}

@Immutable
data class LiquidButtonStyle(
    val height: Dp = 48.dp,
    val horizontalPadding: Dp = 16.dp,
    val contentSpacing: Dp = 8.dp,
    val blurRadius: Dp = 2.dp,
    val lensRadiusX: Dp = 12.dp,
    val lensRadiusY: Dp = 24.dp,
    val pressedExpansion: Dp = 4.dp,
    val dragInitialDerivative: Float = 0.05f
) {
    companion object {
        val Default = LiquidButtonStyle()
    }
}
