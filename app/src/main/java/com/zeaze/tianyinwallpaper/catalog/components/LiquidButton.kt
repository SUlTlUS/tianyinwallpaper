package com.zeaze.tianyinwallpaper.catalog.components

import androidx.annotation.DrawableRes
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.RowScope
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.zeaze.tianyinwallpaper.backdrop.Backdrop
import com.zeaze.tianyinwallpaper.catalog.utils.AdaptiveLuminanceGlassState

/**
 * 天音液态玻璃按钮公开入口。
 *
 * 默认行为保持原组件不变。只有显式传入 [enableMetaballDrag] 时，才在原按钮外增加
 * 拖拽、弹簧回弹和动态 SDF neck；按钮本体仍由 [LiquidButtonCore] 绘制。
 */
@Composable
fun LiquidButton(
    onClick: () -> Unit,
    backdrop: Backdrop,
    modifier: Modifier = Modifier,
    isInteractive: Boolean = true,
    tint: Color = Color.Unspecified,
    surfaceColor: Color = Color.Unspecified,
    luminanceState: AdaptiveLuminanceGlassState? = null,
    style: LiquidButtonStyle = LiquidButtonStyle.Default,
    buttonHeight: Dp = style.height,
    contentPadding: PaddingValues = PaddingValues(horizontal = style.horizontalPadding),
    enableMetaballDrag: Boolean = false,
    metaballGapToAnchor: Dp = 8.dp,
    @DrawableRes iconRes: Int? = null,
    iconContentDescription: String? = null,
    iconSize: Dp = 20.dp,
    iconTint: Color = Color.Unspecified,
    iconOffsetX: Dp = 0.dp,
    iconOffsetY: Dp = 0.dp,
    content: (@Composable RowScope.() -> Unit)? = null
) {
    LiquidButtonCore(
        onClick = onClick,
        backdrop = backdrop,
        modifier = modifier,
        isInteractive = isInteractive,
        tint = tint,
        surfaceColor = surfaceColor,
        luminanceState = luminanceState,
        style = style,
        buttonHeight = buttonHeight,
        contentPadding = contentPadding,
        iconRes = iconRes,
        iconContentDescription = iconContentDescription,
        iconSize = iconSize,
        iconTint = iconTint,
        iconOffsetX = iconOffsetX,
        iconOffsetY = iconOffsetY,
        content = content
    )
}
