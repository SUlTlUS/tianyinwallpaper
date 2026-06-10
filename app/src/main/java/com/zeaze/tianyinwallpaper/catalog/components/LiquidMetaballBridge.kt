package com.zeaze.tianyinwallpaper.catalog.components

import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
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
 * 简化版融球桥：用一条可拉伸的胶囊把两个玻璃形体视觉上黏起来。
 *
 * 真正的 SDF/metaball shader 可以后续替换到这里；调用方只依赖这个组件，
 * 不需要把融球逻辑塞进 LiquidButton 或 LiquidBottomTabs 内部。
 */
@Composable
fun LiquidMetaballBridge(
    backdrop: Backdrop?,
    isLightTheme: Boolean,
    modifier: Modifier = Modifier,
    alpha: Float = 1f,
    surfaceColor: Color = if (isLightTheme) Color.Black.copy(0.08f) else Color.White.copy(0.10f),
    fallbackColor: Color = if (isLightTheme) Color(0x44FFFFFF) else Color(0x442A2A2E),
    blurRadius: Dp = 6.dp,
    lensRadius: Dp = 18.dp
) {
    val resolvedAlpha = alpha.coerceIn(0f, 1f)
    val baseModifier = modifier.graphicsLayer {
        this.alpha = resolvedAlpha
    }

    if (backdrop != null) {
        Box(
            baseModifier.drawBackdrop(
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
            baseModifier
                .clip(Capsule())
                .drawBehind { drawRect(fallbackColor) }
        )
    }
}
