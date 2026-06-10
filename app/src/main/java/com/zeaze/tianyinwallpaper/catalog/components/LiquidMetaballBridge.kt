package com.zeaze.tianyinwallpaper.catalog.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.kyant.shapes.Capsule
import com.zeaze.tianyinwallpaper.backdrop.Backdrop
import com.zeaze.tianyinwallpaper.backdrop.drawBackdrop
import com.zeaze.tianyinwallpaper.backdrop.effects.blur
import com.zeaze.tianyinwallpaper.backdrop.effects.lens
import com.zeaze.tianyinwallpaper.backdrop.effects.vibrancy

/**
 * 简化版融球桥：用一条横向胶囊把两个玻璃形体视觉上黏起来。
 *
 * 注意：调用方可以只给这个组件一个很窄的 gap 宽度，例如 8.dp。内部会用 [requiredWidth]
 * 向左右溢出绘制，让桥接层盖进左右两个玻璃体内部，而不是在中间画成一根竖条。
 * 真正的 SDF/metaball shader 后续可以替换到这里；调用方不需要改 LiquidButton 或 LiquidBottomTabs。
 */
@Composable
fun LiquidMetaballBridge(
    backdrop: Backdrop?,
    isLightTheme: Boolean,
    modifier: Modifier = Modifier,
    alpha: Float = 0.55f,
    bridgeWidth: Dp = 38.dp,
    bridgeHeight: Dp = 34.dp,
    surfaceColor: Color = if (isLightTheme) Color.White.copy(0.12f) else Color.White.copy(0.04f),
    fallbackColor: Color = if (isLightTheme) Color.White.copy(0.18f) else Color.White.copy(0.06f),
    blurRadius: Dp = 6.dp,
    lensRadius: Dp = 18.dp,
    scaleX: Float = 1f,
    scaleY: Float = 1f
) {
    val resolvedAlpha = alpha.coerceIn(0f, 1f)
    val bridgeModifier = Modifier
        .requiredWidth(bridgeWidth)
        .height(bridgeHeight)
        .graphicsLayer {
            this.alpha = resolvedAlpha
            this.scaleX = scaleX
            this.scaleY = scaleY
        }

    Box(
        modifier = modifier,
        contentAlignment = Alignment.Center
    ) {
        if (backdrop != null) {
            Box(
                bridgeModifier.drawBackdrop(
                    backdrop = backdrop,
                    shape = { Capsule() },
                    effects = {
                        vibrancy()
                        blur(blurRadius.toPx())
                        lens(lensRadius.toPx(), lensRadius.toPx(), chromaticAberration = true)
                    },
                    onDrawSurface = {
                        drawRect(surfaceColor)
                    }
                )
            )
        } else {
            Box(
                bridgeModifier
                    .clip(Capsule())
                    .drawBehind { drawRect(fallbackColor) }
            )
        }
    }
}
