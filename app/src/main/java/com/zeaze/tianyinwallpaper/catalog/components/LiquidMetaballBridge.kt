package com.zeaze.tianyinwallpaper.catalog.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.foundation.shape.GenericShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.zeaze.tianyinwallpaper.backdrop.Backdrop
import com.zeaze.tianyinwallpaper.backdrop.drawBackdrop
import com.zeaze.tianyinwallpaper.backdrop.effects.blur
import com.zeaze.tianyinwallpaper.backdrop.effects.lens
import com.zeaze.tianyinwallpaper.backdrop.effects.vibrancy
import com.zeaze.tianyinwallpaper.catalog.utils.LiquidMorphController
import com.zeaze.tianyinwallpaper.catalog.utils.LiquidMorphPhysics
import kotlin.math.max

/**
 * 融球 neck/mask 层。
 *
 * 参考 liquid_glass_widgets 的核心规则：Blob A / Blob B 是概念对象，真正的融合由
 * SDF/metaball shader 生成 neck；不能在 UI 上再画一个可见圆，否则就会变成“按钮旁边多一个圆”。
 *
 * 当前 Android 版本先用一个曲线 neck Shape 模拟 SDF mask：只绘制两个玻璃体之间的融合区域，
 * 不复制左/右两个 blob 本体。后续可把这个 Shape 替换为 AGSL SDF shader。
 */
@Composable
fun LiquidMetaballBridge(
    backdrop: Backdrop?,
    isLightTheme: Boolean,
    modifier: Modifier = Modifier,
    alpha: Float = 0.32f,
    bridgeWidth: Dp = 34.dp,
    bridgeHeight: Dp = 46.dp,
    surfaceColor: Color = if (isLightTheme) Color.White.copy(0.10f) else Color.White.copy(0.045f),
    fallbackColor: Color = if (isLightTheme) Color.White.copy(0.16f) else Color.White.copy(0.055f),
    blurRadius: Dp = 6.dp,
    lensRadius: Dp = 18.dp,
    scaleX: Float = 1f,
    scaleY: Float = 1f
) {
    val resolvedAlpha = alpha.coerceIn(0f, 1f)
    val neckShape = metaballNeckShape(
        tension = 1f,
        thickness = 0.58f
    )
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
                    shape = { neckShape },
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
                    .clip(neckShape)
                    .drawBehind { drawRect(fallbackColor) }
            )
        }
    }
}

/**
 * 兼容 liquid_glass_widgets 的 two-blob API 名称，但不要直接绘制 Blob A/B。
 *
 * 调用方应该自己保留真实 trigger / target 控件；这里仅根据 controller 的 blend 状态绘制中间 neck。
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
    anchorShape: Shape = metaballNeckShape(),
    targetShape: Shape = metaballNeckShape(),
    surfaceColor: Color = if (isLightTheme) Color.White.copy(0.10f) else Color.White.copy(0.045f),
    blurRadius: Dp = 6.dp,
    lensRadius: Dp = 18.dp,
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
        alpha = max(0.18f, intensity * 0.42f),
        bridgeWidth = lerpDp(18.dp, 42.dp, intensity),
        bridgeHeight = lerpDp(
            minOf(anchorHeight, targetHeight) * 0.46f,
            minOf(anchorHeight, targetHeight) * 0.72f,
            sizeT
        ),
        surfaceColor = surfaceColor,
        blurRadius = blurRadius,
        lensRadius = lensRadius,
        scaleX = 1f + intensity * 0.08f,
        scaleY = 1f - intensity * 0.05f
    )
}

private fun metaballNeckShape(
    tension: Float = 1f,
    thickness: Float = 0.58f
): Shape {
    return GenericShape { size, _ ->
        val width = size.width
        val height = size.height
        val centerY = height / 2f
        val halfNeck = height * thickness.coerceIn(0.2f, 1f) / 2f
        val top = centerY - halfNeck
        val bottom = centerY + halfNeck
        val bow = height * 0.20f * tension.coerceIn(0f, 2f)

        moveTo(0f, top)
        cubicTo(
            width * 0.28f,
            top - bow,
            width * 0.72f,
            top - bow,
            width,
            top
        )
        lineTo(width, bottom)
        cubicTo(
            width * 0.72f,
            bottom + bow,
            width * 0.28f,
            bottom + bow,
            0f,
            bottom
        )
        close()
    }
}

private fun lerpDp(start: Dp, stop: Dp, fraction: Float): Dp {
    return start + (stop - start) * fraction.coerceIn(0f, 1f)
}
