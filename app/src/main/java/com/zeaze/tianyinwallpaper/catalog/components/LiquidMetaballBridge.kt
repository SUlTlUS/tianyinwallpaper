package com.zeaze.tianyinwallpaper.catalog.components

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.zeaze.tianyinwallpaper.backdrop.Backdrop
import com.zeaze.tianyinwallpaper.catalog.utils.LiquidMorphController

/**
 * 这个文件只保留 API 兼容，不再绘制独立 bridge。
 *
 * 参考 liquid_glass_widgets 的正确结构是：把多个 glass blob 放进同一个 shader/mask group，
 * 由 group 的 SDF/metaball shader 统一融合；不能在两个控件中间插入第三个 Composable。
 *
 * 之前的实现会出现竖条/灰圆，原因就是 bridge 本身成为了一个独立玻璃体。
 */
@Suppress("UNUSED_PARAMETER")
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
) = Unit

/**
 * 兼容旧调用的空实现。
 *
 * 完整融球应在 bottom bar / floating menu 的容器层实现，而不是在这里画额外 blob。
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
) = Unit
