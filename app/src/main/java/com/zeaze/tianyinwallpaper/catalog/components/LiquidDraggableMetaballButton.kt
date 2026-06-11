package com.zeaze.tianyinwallpaper.catalog.components

import androidx.annotation.DrawableRes
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateOffsetAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
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
import com.zeaze.tianyinwallpaper.catalog.utils.AdaptiveLuminanceGlassState
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.roundToInt

/**
 * 在原 [LiquidButtonCore] 外增加拖拽和动态 SDF neck。
 * 原按钮的 Backdrop、tint、亮度联动和内容 API 均保持不变。
 */
@Composable
internal fun LiquidDraggableMetaballButton(
    onClick: () -> Unit,
    backdrop: Backdrop,
    modifier: Modifier,
    tint: Color,
    surfaceColor: Color,
    luminanceState: AdaptiveLuminanceGlassState?,
    style: LiquidButtonStyle,
    buttonHeight: Dp,
    contentPadding: PaddingValues,
    @DrawableRes iconRes: Int?,
    iconContentDescription: String?,
    iconSize: Dp,
    iconTint: Color,
    iconOffsetX: Dp,
    iconOffsetY: Dp,
    content: (@Composable RowScope.() -> Unit)?,
    gapToAnchor: Dp = 8.dp,
    maxDragLeft: Dp = 56.dp,
    maxDragRight: Dp = 72.dp,
    maxDragVertical: Dp = 28.dp
) {
    val density = LocalDensity.current
    var dragging by remember { mutableStateOf(false) }
    var dragTarget by remember { mutableStateOf(Offset.Zero) }
    val dragOffset by animateOffsetAsState(
        targetValue = dragTarget,
        animationSpec = spring(
            dampingRatio = 0.73f,
            stiffness = Spring.StiffnessMediumLow
        ),
        label = "LiquidButtonMetaballDrag"
    )

    val maxLeftPx = with(density) { maxDragLeft.toPx() }
    val maxRightPx = with(density) { maxDragRight.toPx() }
    val maxVerticalPx = with(density) { maxDragVertical.toPx() }
    val approachPx = with(density) { 52.dp.toPx() }
    val verticalBreakPx = with(density) { 44.dp.toPx() }
    val dragLength = hypot(dragOffset.x, dragOffset.y)

    val horizontalApproach = (-dragOffset.x / approachPx).coerceIn(0f, 1f)
    val verticalFactor = (1f - abs(dragOffset.y) / verticalBreakPx).coerceIn(0f, 1f)
    val connection = if (dragging || dragLength > 0.5f) {
        horizontalApproach * verticalFactor
    } else 0f
    val stretch = (dragLength / maxRightPx).coerceIn(0f, 1f)

    Box(
        modifier = modifier,
        contentAlignment = Alignment.Center
    ) {
        if (connection > 0.001f) {
            DynamicMetaballNeck(
                backdrop = backdrop,
                buttonHeight = buttonHeight,
                gapToAnchor = gapToAnchor,
                dragOffset = dragOffset,
                connection = connection,
                style = style
            )
        }

        LiquidButtonCore(
            onClick = onClick,
            backdrop = backdrop,
            modifier = Modifier
                .size(buttonHeight)
                .offset {
                    IntOffset(dragOffset.x.roundToInt(), dragOffset.y.roundToInt())
                }
                .graphicsLayer {
                    scaleX = 1f + stretch * 0.09f
                    scaleY = 1f - stretch * 0.045f
                }
                .pointerInput(buttonHeight) {
                    detectDragGestures(
                        onDragStart = { dragging = true },
                        onDragEnd = {
                            dragging = false
                            dragTarget = Offset.Zero
                        },
                        onDragCancel = {
                            dragging = false
                            dragTarget = Offset.Zero
                        },
                        onDrag = { change, amount ->
                            change.consume()
                            dragTarget = Offset(
                                x = (dragTarget.x + amount.x).coerceIn(-maxLeftPx, maxRightPx),
                                y = (dragTarget.y + amount.y).coerceIn(-maxVerticalPx, maxVerticalPx)
                            )
                        }
                    )
                },
            isInteractive = false,
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
}

@Composable
private fun DynamicMetaballNeck(
    backdrop: Backdrop,
    buttonHeight: Dp,
    gapToAnchor: Dp,
    dragOffset: Offset,
    connection: Float,
    style: LiquidButtonStyle
) {
    val density = LocalDensity.current
    val radius = with(density) { buttonHeight.toPx() / 2f }
    val gap = with(density) { gapToAnchor.toPx() }
    val canvasWidth = with(density) { 224.dp.toPx() }
    val canvasHeight = with(density) { 120.dp.toPx() }
    val anchorCenterX = radius
    val buttonCenterAtRestX = radius * 3f + gap
    val centerY = canvasHeight / 2f

    Box(
        modifier = Modifier
            .offset(x = 8.dp)
            .requiredWidth(224.dp)
            .height(120.dp)
            .graphicsLayer { alpha = connection.coerceIn(0f, 1f) }
            .drawBackdrop(
                backdrop = backdrop,
                shape = { Capsule() },
                effects = {
                    vibrancy()
                    blur(style.blurRadius.toPx())
                    lens(
                        style.lensRadiusX.toPx(),
                        style.lensRadiusY.toPx(),
                        chromaticAberration = true
                    )
                    metaballMask(
                        centerAX = anchorCenterX,
                        centerAY = centerY,
                        radiusAX = radius,
                        radiusAY = radius,
                        centerBX = buttonCenterAtRestX + dragOffset.x,
                        centerBY = centerY + dragOffset.y,
                        radiusBX = radius,
                        radiusBY = radius,
                        smoothness = radius * (0.18f + connection * 0.95f),
                        opacity = 1f,
                        neckOnly = true
                    )
                }
            )
    )
}
