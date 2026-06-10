package com.zeaze.tianyinwallpaper.catalog.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.zeaze.tianyinwallpaper.backdrop.Backdrop
import com.zeaze.tianyinwallpaper.backdrop.drawBackdrop
import com.zeaze.tianyinwallpaper.backdrop.effects.blur
import com.zeaze.tianyinwallpaper.backdrop.effects.lens
import com.zeaze.tianyinwallpaper.backdrop.effects.metaballMask
import com.zeaze.tianyinwallpaper.backdrop.effects.vibrancy
import com.zeaze.tianyinwallpaper.catalog.utils.LiquidMorphController
import com.zeaze.tianyinwallpaper.catalog.utils.LiquidMorphPhysics
import kotlin.math.max

/**
 * AGSL 版融球 mask 层。
 *
 * 参考 liquid_glass_widgets：两个 blob 不是单独画出来的 UI，融合区由 SDF smooth union 生成。
 * 这个组件只画两个玻璃体之间的 metaball neck，不复制 Tab 或加号按钮本体。
 */
@Composable
fun LiquidMetaballBridge(
    backdrop: Backdrop?,
    isLightTheme: Boolean,
    modifier: Modifier = Modifier,
    alpha: Float = 0.45f,
    bridgeWidth: Dp = 56.dp,
    bridgeHeight: Dp = 64.dp,
    surfaceColor: Color = Color.Transparent,
    fallbackColor: Color = Color.Transparent,
    blurRadius: Dp = 8.dp,
    lensRadius: Dp = 20.dp,
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
                    shape = { RectangleShape },
                    effects = {
                        val w = size.width
                        val h = size.height
                        val radius = h * 0.50f
                        val overlap = radius * 0.58f
                        vibrancy()
                        blur(blurRadius.toPx())
                        lens(lensRadius.toPx(), lensRadius.toPx(), chromaticAberration = true)
                        metaballMask(
                            centerAX = -overlap,
                            centerAY = h / 2f,
                            radiusAX = radius,
                            radiusAY = radius,
                            centerBX = w + overlap,
                            centerBY = h / 2f,
                            radiusBX = radius,
                            radiusBY = radius,
                            smoothness = h * 0.42f,
                            opacity = 1f
                        )
                    }
                )
            )
        } else {
            // 低版本没有 RuntimeShader，宁可不画，也不要退回成一根假桥或多余圆形。
            Box(bridgeModifier)
        }
    }
}

/**
 * 兼容 liquid_glass_widgets 的 two-blob API 名称，但不要直接绘制 Blob A/B。
 * 调用方保留真实 trigger / target 控件；这里仅根据 controller 的 blend 状态绘制中间 shader neck。
 */
@Suppress("UNUSED_PARAMETER")
@Composable
fun LiquidTwoBlobMetaballMorph(
    controller: LiquidMorphController,
    backdrop: Backdrop?,
    isLightTheme: Boolean,
    anchorWidth: Dp,
    anchorHeight: Dp,
    targetWidth: Dp,
    targetHeight: Dp,
    finalOffsetX: Dp,
    finalOffsetY: Dp,
    modifier: Modifier = Modifier,
    anchorShape: Shape = RectangleShape,
    targetShape: Shape = RectangleShape,
    surfaceColor: Color = Color.Transparent,
    blurRadius: Dp = 8.dp,
    lensRadius: Dp = 20.dp,
    anchorContent: @Composable () -> Unit = {},
    targetContent: @Composable () -> Unit = {}
) {
    if (!controller.isShowing) return

    val state = LiquidMorphPhysics.compute(
        rawValue = controller.value,
        finalDx = 0f,
        finalDy = 0f
    )
    val intensity = (state.blend / 28f).coerceIn(0f, 1f)
    val sizeT = state.sizeT.coerceIn(0f, 1f)

    LiquidMetaballBridge(
        backdrop = backdrop,
        isLightTheme = isLightTheme,
        modifier = modifier,
        alpha = max(0.20f, intensity * 0.55f),
        bridgeWidth = lerpDp(32.dp, 64.dp, intensity),
        bridgeHeight = lerpDp(
            minOf(anchorHeight, targetHeight) * 0.72f,
            minOf(anchorHeight, targetHeight),
            sizeT
        ),
        blurRadius = blurRadius,
        lensRadius = lensRadius,
        scaleX = 1f + intensity * 0.08f,
        scaleY = 1f - intensity * 0.05f
    )
}

private fun lerpDp(start: Dp, stop: Dp, fraction: Float): Dp {
    return start + (stop - start) * fraction.coerceIn(0f, 1f)
}
