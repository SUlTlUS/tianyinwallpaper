package com.zeaze.tianyinwallpaper.catalog.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.kyant.shapes.Capsule
import com.zeaze.tianyinwallpaper.backdrop.Backdrop
import com.zeaze.tianyinwallpaper.backdrop.drawBackdrop
import com.zeaze.tianyinwallpaper.backdrop.effects.blur
import com.zeaze.tianyinwallpaper.backdrop.effects.lens
import com.zeaze.tianyinwallpaper.backdrop.effects.vibrancy
import com.zeaze.tianyinwallpaper.catalog.utils.LiquidMorphController
import com.zeaze.tianyinwallpaper.catalog.utils.LiquidMorphPhysics
import kotlin.math.max

/**
 * 兼容旧调用的空壳。
 *
 * 不再画“桥”。参考 liquid_glass_widgets 的做法，融球不是单独塞一个连接件，
 * 而是由两个 blob 在同一个 morph/blend 系统里融合。继续绘制显式桥接几何，
 * 就会变成中间那根难看的竖条。
 */
@Suppress("UNUSED_PARAMETER")
@Composable
fun LiquidMetaballBridge(
    backdrop: Backdrop?,
    isLightTheme: Boolean,
    modifier: Modifier = Modifier,
    alpha: Float = 0f,
    bridgeWidth: Dp = 0.dp,
    bridgeHeight: Dp = 0.dp,
    surfaceColor: Color = Color.Transparent,
    fallbackColor: Color = Color.Transparent,
    blurRadius: Dp = 0.dp,
    lensRadius: Dp = 0.dp,
    scaleX: Float = 1f,
    scaleY: Float = 1f
) {
    Box(modifier = modifier)
}

/**
 * 参考 liquid_glass_widgets 的 Two-Blob Morph 架构：
 *
 * - Blob A：锚点 ghost，打开前 40% 缩小消失；
 * - Blob B：从锚点中心沿 J-curve 移动并从锚点尺寸长到目标尺寸；
 * - 融合强度由 LiquidMorphPhysics.compute() 的 blend 计算。
 *
 * 当前 Android 版还没有接入完整 SDF/metaball RuntimeShader，所以这里先保持“两 blob 同源运动”结构，
 * 不再画独立脖子。后续只需要把两个 blob 放进真正的 shader blend group，不需要改调用方。
 */
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
    anchorShape: Shape = Capsule(),
    targetShape: Shape = Capsule(),
    surfaceColor: Color = if (isLightTheme) Color.Black.copy(0.08f) else Color.White.copy(0.10f),
    blurRadius: Dp = 8.dp,
    lensRadius: Dp = 20.dp,
    anchorContent: @Composable () -> Unit = {},
    targetContent: @Composable () -> Unit = {}
) {
    if (!controller.isShowing) return

    val density = LocalDensity.current
    val finalDxPx = with(density) { finalOffsetX.toPx() }
    val finalDyPx = with(density) { finalOffsetY.toPx() }
    val state = LiquidMorphPhysics.compute(
        rawValue = controller.value,
        finalDx = finalDxPx,
        finalDy = finalDyPx
    )
    val sizeT = state.sizeT.coerceIn(0f, 1f)
    val bodyWidth = lerpDp(anchorWidth, targetWidth, sizeT)
    val bodyHeight = lerpDp(anchorHeight, targetHeight, sizeT)
    val bodyAlpha = max(sizeT, 0.001f)

    Box(
        modifier = modifier,
        contentAlignment = Alignment.Center
    ) {
        if (!controller.hasHandedOff && state.anchorScale > 0.001f) {
            LiquidBlobSurface(
                backdrop = backdrop,
                shape = anchorShape,
                surfaceColor = surfaceColor,
                blurRadius = blurRadius,
                lensRadius = lensRadius,
                modifier = Modifier
                    .size(anchorWidth, anchorHeight)
                    .graphicsLayer {
                        translationX = state.pushDx
                        translationY = state.pushDy
                        scaleX = state.anchorScale
                        scaleY = state.anchorScale
                    },
                content = anchorContent
            )
        }

        LiquidBlobSurface(
            backdrop = backdrop,
            shape = targetShape,
            surfaceColor = surfaceColor,
            blurRadius = blurRadius,
            lensRadius = lensRadius,
            modifier = Modifier
                .size(bodyWidth, bodyHeight)
                .graphicsLayer {
                    translationX = state.currentDx
                    translationY = state.currentDy
                    scaleX = state.containerScale
                    scaleY = state.containerScale
                    alpha = bodyAlpha
                },
            content = targetContent
        )
    }
}

@Composable
private fun LiquidBlobSurface(
    backdrop: Backdrop?,
    shape: Shape,
    surfaceColor: Color,
    blurRadius: Dp,
    lensRadius: Dp,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    Box(
        modifier = if (backdrop != null) {
            modifier.drawBackdrop(
                backdrop = backdrop,
                shape = { shape },
                effects = {
                    vibrancy()
                    blur(blurRadius.toPx())
                    lens(lensRadius.toPx(), lensRadius.toPx(), chromaticAberration = true)
                },
                onDrawSurface = {
                    drawRect(surfaceColor)
                }
            )
        } else {
            modifier
        },
        contentAlignment = Alignment.Center
    ) {
        content()
    }
}

private fun lerpDp(start: Dp, stop: Dp, fraction: Float): Dp {
    return start + (stop - start) * fraction
}
