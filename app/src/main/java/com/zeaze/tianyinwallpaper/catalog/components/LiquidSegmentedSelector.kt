package com.zeaze.tianyinwallpaper.catalog.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.util.fastCoerceIn
import androidx.compose.ui.util.fastRoundToInt
import androidx.compose.ui.util.lerp
import androidx.compose.foundation.layout.offset
import androidx.compose.ui.unit.IntOffset
import com.kyant.shapes.Capsule
import com.kyant.shapes.RoundedRectangle
import com.zeaze.tianyinwallpaper.backdrop.drawBackdrop
import com.zeaze.tianyinwallpaper.backdrop.backdrops.rememberCanvasBackdrop
import com.zeaze.tianyinwallpaper.backdrop.backdrops.rememberLayerBackdrop
import com.zeaze.tianyinwallpaper.backdrop.backdrops.layerBackdrop
import com.zeaze.tianyinwallpaper.backdrop.backdrops.rememberCombinedBackdrop
import com.zeaze.tianyinwallpaper.backdrop.effects.blur
import com.zeaze.tianyinwallpaper.backdrop.effects.colorControls
import com.zeaze.tianyinwallpaper.backdrop.effects.lens
import com.zeaze.tianyinwallpaper.backdrop.effects.vibrancy
import com.zeaze.tianyinwallpaper.backdrop.highlight.Highlight
import com.zeaze.tianyinwallpaper.backdrop.shadow.InnerShadow
import com.zeaze.tianyinwallpaper.backdrop.shadow.Shadow
import com.zeaze.tianyinwallpaper.catalog.utils.DampedDragAnimation
import com.zeaze.tianyinwallpaper.catalog.utils.InteractiveHighlight
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.drop


/** Reusable rounded segmented selector with drag deformation effect. */
data class LiquidSegmentedOption<T>(
    val value: T,
    val label: String
)

@Composable
fun <T> LiquidSegmentedSelector(
    options: List<LiquidSegmentedOption<T>>,
    selectedValue: () -> T,
    onValueSelected: (T) -> Unit,
    isLightTheme: Boolean,
    modifier: Modifier = Modifier,
    /**
     * Enable liquid glass effects on both the track and the thumb indicator.
     *
     * Track  → uses a self-contained [rememberCanvasBackdrop] (safe, no outer layer).
     * Thumb  → uses a sibling [rememberLayerBackdrop] that captures the track Row,
     *          so the indicator blurs the labels and track tint beneath it.
     *          No circular reference; track and thumb are sibling nodes.
     */
    enableLiquidGlass: Boolean = false
) {
    if (options.isEmpty()) return

    val tabsCount = options.size
    val accentColor = if (isLightTheme) Color(0xFF0088FF) else Color(0xFF0091FF)
    val selectedTextColor = if (isLightTheme) Color(0xFF1A1A1F) else Color(0xFFF5F5FA)
    val unselectedTextColor = if (isLightTheme) Color(0xFF2A2A33).copy(alpha = 0.7f) else Color(0xFFF5F5FA).copy(alpha = 0.72f)
    val trackColor = if (isLightTheme) Color(0xFFF7F8FA) else Color(0xFF2A2A2E)
    val trackShape = RoundedCornerShape(21.dp)
    val thumbShape = RoundedCornerShape(18.dp)
    val trackPadding = 4.dp

    // ── Backdrop sources ─────────────────────────────────────────────────────
    // Track: canvas-based, self-contained — no outer RenderNode sampling.
    val trackCanvasBackdrop = rememberCanvasBackdrop { drawRect(trackColor) }

    val tabsBackdrop = if (enableLiquidGlass) rememberLayerBackdrop() else null

    BoxWithConstraints(modifier = modifier, contentAlignment = Alignment.CenterStart) {
        val density = LocalDensity.current
        val horizontalInsetPx = with(density) { trackPadding.toPx() }
        val availableWidth = (constraints.maxWidth.toFloat() - horizontalInsetPx * 2f).coerceAtLeast(0f)
        val tabWidth = (availableWidth / tabsCount).coerceAtLeast(0f)
        val maxX = (availableWidth - tabWidth).coerceAtLeast(0f)
        val animationScope = rememberCoroutineScope()

        var currentIndex by remember {
            mutableIntStateOf(options.indexOfFirst { it.value == selectedValue() }.coerceAtLeast(0))
        }

        val dampedDragAnimation = remember(animationScope) {
            DampedDragAnimation(
                animationScope = animationScope,
                initialValue = currentIndex.toFloat(),
                valueRange = 0f..(tabsCount - 1).toFloat(),
                visibilityThreshold = 0.001f,
                initialScale = 1f,
                pressedScale = 78f / 56f,
                onDragStarted = {},
                onDragStopped = {
                    val targetIndex = targetValue.fastRoundToInt().fastCoerceIn(0, tabsCount - 1)
                    currentIndex = targetIndex
                    animateToValue(targetIndex.toFloat())
                },
                onDrag = { _, dragAmount ->
                    val delta = if (tabWidth > 0f) dragAmount.x / tabWidth else 0f
                    updateValue(
                        (targetValue + delta)
                            .fastCoerceIn(0f, (tabsCount - 1).toFloat())
                    )
                }
            )
        }

        LaunchedEffect(selectedValue, options) {
            snapshotFlow { selectedValue() }
                .collectLatest { selected ->
                    val nextIndex = options.indexOfFirst { it.value == selected }.coerceAtLeast(0)
                    if (nextIndex != currentIndex) {
                        currentIndex = nextIndex
                    }
                }
        }
        LaunchedEffect(dampedDragAnimation, options) {
            snapshotFlow { currentIndex }
                .drop(1)
                .collectLatest { index ->
                    dampedDragAnimation.animateToValue(index.toFloat())
                    onValueSelected(options[index].value)
                }
        }

        val isLtr = LocalLayoutDirection.current == LayoutDirection.Ltr
        val interactiveHighlight = remember(animationScope) {
            InteractiveHighlight(
                animationScope = animationScope,
                position = { size, _ ->
                    Offset(
                        if (isLtr) (dampedDragAnimation.value + 0.5f) * tabWidth
                        else size.width - (dampedDragAnimation.value + 0.5f) * tabWidth,
                        size.height / 2f
                    )
                }
            )
        }

        // ── Track ─────────────────────────────────────────────────────────────
        val trackModifier = Modifier
            .clip(trackShape)
            .background(trackColor)

        CompositionLocalProvider(
            LocalLiquidBottomTabScale provides {
                lerp(1f, 1.15f, dampedDragAnimation.pressProgress)
            }
        ) {
            Box(modifier = trackModifier) {
                // Use a separate layer for InteractiveHighlight so its blend mode
                // does not clear or erase the Track's background color.
                if (isLightTheme) {
                    Box(
                        Modifier
                            .matchParentSize()
                            .alpha(0.5f) // Adjust alpha for light theme if needed for better contrast
                            .then(interactiveHighlight.modifier)
                    )
                } else {
                    Box(Modifier.matchParentSize().then(interactiveHighlight.modifier))
                }

                Row(
                    modifier = Modifier
                        .height(56.dp)
                        .fillMaxWidth()
                        .padding(trackPadding),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    options.forEachIndexed { index, option ->
                        SegmentedTab(
                            label = option.label,
                            color = if (index == currentIndex) selectedTextColor else unselectedTextColor,
                            onClick = { currentIndex = index }
                        )
                    }
                }
            }

            if (enableLiquidGlass && tabsBackdrop != null) {
                Row(
                    Modifier
                        .alpha(0f)
                        .layerBackdrop(tabsBackdrop)
                        .height(56.dp)
                        .fillMaxWidth()
                        .padding(horizontal = trackPadding)
                        .then(interactiveHighlight.modifier)
                        .clearAndSetSemantics {},
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    options.forEachIndexed { index, option ->
                        SegmentedTab(
                            label = option.label,
                            color = selectedTextColor,
                            onClick = { currentIndex = index }
                        )
                    }
                }
            }
        }

        // ── Thumb indicator ───────────────────────────────────────────────────
        val thumbModifier = if (enableLiquidGlass && tabsBackdrop != null) {
            Modifier.drawBackdrop(
                backdrop = rememberCombinedBackdrop(trackCanvasBackdrop, tabsBackdrop),
                shape = { RoundedRectangle(18f.dp) },
                layerBlock = {
                    val velocity = dampedDragAnimation.velocity / 10f
                    scaleX = dampedDragAnimation.scaleX / (1f - (velocity * 0.75f).fastCoerceIn(-0.2f, 0.2f))
                    scaleY = dampedDragAnimation.scaleY * (1f - (velocity * 0.25f).fastCoerceIn(-0.2f, 0.2f))
                },
                effects = {
                    val progress = dampedDragAnimation.pressProgress
                    lens(6f.dp.toPx() * lerp(1f, 1.5f, progress), 18f.dp.toPx(), chromaticAberration = true)
                },
                highlight = {
                    val progress = dampedDragAnimation.pressProgress
                    Highlight.Default.copy(alpha = progress)
                },
                shadow = {
                    val progress = dampedDragAnimation.pressProgress
                    Shadow(alpha = progress)
                },
                innerShadow = {
                    val progress = dampedDragAnimation.pressProgress
                    InnerShadow(radius = 8f.dp * progress, alpha = progress)
                },
                onDrawSurface = {
                    if (isLightTheme) {
                        drawRect(Color.Black.copy(alpha = 0.05f))
                    } else {
                        drawRect(Color.White.copy(alpha = 0.08f))
                    }
                }
            )
        } else {
            Modifier
                .graphicsLayer {
                    val velocity = dampedDragAnimation.velocity / 10f
                    scaleX = dampedDragAnimation.scaleX / (1f - (velocity * 0.75f).fastCoerceIn(-0.2f, 0.2f))
                    scaleY = dampedDragAnimation.scaleY * (1f - (velocity * 0.25f).fastCoerceIn(-0.2f, 0.2f))
                }
                .clip(thumbShape)
                .background(if (isLightTheme) Color.Black.copy(alpha = 0.05f) else Color.White.copy(alpha = 0.08f))
        }

        Box(
            Modifier
                .padding(horizontal = trackPadding)
                .offset {
                    IntOffset(
                        (dampedDragAnimation.value * tabWidth).coerceIn(0f, maxX).fastRoundToInt(),
                        0
                    )
                }
                .then(interactiveHighlight.gestureModifier)
                .then(dampedDragAnimation.modifier)
                .then(thumbModifier)
                .height(48.dp)
                .width(with(density) { tabWidth.toDp() })
        )
    }
}

@Composable
private fun RowScope.SegmentedTab(
    label: String,
    color: Color,
    onClick: () -> Unit
) {
    LiquidBottomTab(onClick = onClick) {
        androidx.compose.foundation.text.BasicText(
            text = label,
            style = TextStyle(color = color, fontSize = 14.sp)
        )
    }
}