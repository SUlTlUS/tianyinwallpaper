package com.zeaze.tianyinwallpaper.catalog.components

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateOffsetAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.consume
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.kyant.shapes.Capsule
import com.zeaze.tianyinwallpaper.backdrop.Backdrop
import com.zeaze.tianyinwallpaper.backdrop.drawBackdrop
import com.zeaze.tianyinwallpaper.backdrop.effects.blur
import com.zeaze.tianyinwallpaper.backdrop.effects.lens
import com.zeaze.tianyinwallpaper.backdrop.effects.metaballMask
import com.zeaze.tianyinwallpaper.backdrop.effects.vibrancy
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.roundToInt

/**
 * 用原来的 [LiquidButton] 做真实按钮，只在拖拽时额外绘制一层动态 SDF 融球 neck。
 *
 * 参考 liquid_glass_widgets 的做法：
 * - 真实按钮仍然是原来的玻璃组件；
 * - 融球强度由按钮相对 anchor 的距离实时决定；
 * - 不在静止状态画固定桥，也不复制一个灰圆或竖条；
 * - 松手后按钮弹回，neck 随距离收缩并消失。
 */
@Composable
fun LiquidDraggableMetaballButton(
    onClick: () -> Unit,
    backdrop: Backdrop,
    isLightTheme: Boolean,
    modifier: Modifier = Modifier,
    actionSize: Dp = 64.dp,
    gapToAnchor: Dp = 8.dp,
    maxDragLeft: Dp = 56.dp,
    maxDragRight: Dp = 88.dp,
    maxDragVertical: Dp = 28.dp,
    blurRadius: Dp = 8.dp,
    lensRadius: Dp = 24.dp,
    content: @Composable RowScope.() -> Unit
) {
    val density = LocalDensity.current
    var isDragging by remember { mutableStateOf(false) }
    var rawDrag by remember { mutableStateOf(Offset.Zero) }
    val dragOffset by animateOffsetAsState(
        targetValue = rawDrag,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessMediumLow
        ),
        label = "LiquidDraggableMetaballButtonOffset"
    )

    val gapPx = with(density) { gapToAnchor.toPx() }
    val actionPx = with(density) { actionSize.toPx() }
    val leftReachPx = with(density) { (actionSize + gapToAnchor + 22.dp).toPx() }
    val rightReachPx = with(density) { maxDragRight.toPx() }
    val breakDistancePx = with(density) { 96.dp.toPx() }
    val separationPx = hypot(gapPx + dragOffset.x, dragOffset.y)
    val dragLengthPx = hypot(dragOffset.x, dragOffset.y)
    val connection = if (isDragging || dragLengthPx > 0.5f) {
        (1f - separationPx / breakDistancePx).coerceIn(0f, 1f)
    } else {
        0f
    }

    val stretch = (dragLengthPx / breakDistancePx).coerceIn(0f, 1f)
    val buttonScaleX = 1f + stretch * 0.08f
    val buttonScaleY = 1f - stretch * 0.04f

    Box(
        modifier = modifier.size(actionSize),
        contentAlignment = Alignment.Center
    ) {
        if (connection > 0.01f) {
            DynamicMetaballNeck(
                backdrop = backdrop,
                isLightTheme = isLightTheme,
                actionSize = actionSize,
                width = with(density) { (leftReachPx + actionPx + rightReachPx).toDp() },
                offsetX = with(density) { (-leftReachPx).toDp() },
                gapPx = gapPx,
                leftReachPx = leftReachPx,
                dragOffset = dragOffset,
                alpha = connection * 0.58f,
                smoothness = actionPx * (0.14f + connection * 0.52f),
                blurRadius = blurRadius,
                lensRadius = lensRadius
            )
        }

        LiquidButton(
            onClick = onClick,
            backdrop = backdrop,
            modifier = Modifier
                .size(actionSize)
                .offset {
                    IntOffset(
                        dragOffset.x.roundToInt(),
                        dragOffset.y.roundToInt()
                    )
                }
                .graphicsLayer {
                    scaleX = buttonScaleX
                    scaleY = buttonScaleY
                }
                .pointerInput(actionSize, gapToAnchor) {
                    val maxLeftPx = maxDragLeft.toPx()
                    val maxRightPx = maxDragRight.toPx()
                    val maxVerticalPx = maxDragVertical.toPx()
                    detectDragGestures(
                        onDragStart = {
                            isDragging = true
                        },
                        onDragEnd = {
                            isDragging = false
                            rawDrag = Offset.Zero
                        },
                        onDragCancel = {
                            isDragging = false
                            rawDrag = Offset.Zero
                        },
                        onDrag = { change, dragAmount ->
                            change.consume()
                            rawDrag = Offset(
                                x = (rawDrag.x + dragAmount.x).coerceIn(-maxLeftPx, maxRightPx),
                                y = (rawDrag.y + dragAmount.y).coerceIn(-maxVerticalPx, maxVerticalPx)
                            )
                        }
                    )
                },
            surfaceColor = Color.Unspecified,
            buttonHeight = actionSize,
            contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp),
            content = content
        )
    }
}

@Composable
private fun DynamicMetaballNeck(
    backdrop: Backdrop,
    isLightTheme: Boolean,
    actionSize: Dp,
    width: Dp,
    offsetX: Dp,
    gapPx: Float,
    leftReachPx: Float,
    dragOffset: Offset,
    alpha: Float,
    smoothness: Float,
    blurRadius: Dp,
    lensRadius: Dp
) {
    val radius = with(LocalDensity.current) { actionSize.toPx() } / 2f
    Box(
        modifier = Modifier
            .offset(x = offsetX)
            .requiredWidth(width)
            .height(actionSize)
            .graphicsLayer { this.alpha = alpha.coerceIn(0f, 1f) }
            .drawBackdrop(
                backdrop = backdrop,
                shape = { Capsule() },
                effects = {
                    val anchorCenterX = leftReachPx - gapPx - radius
                    val anchorCenterY = size.height / 2f
                    val buttonCenterX = leftReachPx + radius + dragOffset.x
                    val buttonCenterY = size.height / 2f + dragOffset.y
                    vibrancy()
                    blur(blurRadius.toPx())
                    lens(lensRadius.toPx(), lensRadius.toPx(), chromaticAberration = true)
                    metaballMask(
                        centerAX = anchorCenterX,
                        centerAY = anchorCenterY,
                        radiusAX = radius,
                        radiusAY = radius,
                        centerBX = buttonCenterX,
                        centerBY = buttonCenterY,
                        radiusBX = radius,
                        radiusBY = radius,
                        smoothness = smoothness,
                        opacity = 1f
                    )
                }
            )
    )
}
