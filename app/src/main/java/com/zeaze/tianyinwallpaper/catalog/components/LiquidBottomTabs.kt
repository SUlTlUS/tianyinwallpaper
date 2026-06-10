package com.zeaze.tianyinwallpaper.catalog.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.EaseOut
import androidx.compose.animation.core.spring
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
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
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.util.fastCoerceIn
import androidx.compose.ui.util.fastRoundToInt
import androidx.compose.ui.util.lerp
import com.kyant.shapes.Capsule
import com.zeaze.tianyinwallpaper.backdrop.Backdrop
import com.zeaze.tianyinwallpaper.backdrop.backdrops.layerBackdrop
import com.zeaze.tianyinwallpaper.backdrop.backdrops.rememberCombinedBackdrop
import com.zeaze.tianyinwallpaper.backdrop.backdrops.rememberLayerBackdrop
import com.zeaze.tianyinwallpaper.backdrop.drawBackdrop
import com.zeaze.tianyinwallpaper.backdrop.effects.blur
import com.zeaze.tianyinwallpaper.backdrop.effects.lens
import com.zeaze.tianyinwallpaper.backdrop.effects.vibrancy
import com.zeaze.tianyinwallpaper.backdrop.highlight.Highlight
import com.zeaze.tianyinwallpaper.backdrop.shadow.InnerShadow
import com.zeaze.tianyinwallpaper.backdrop.shadow.Shadow
import com.zeaze.tianyinwallpaper.catalog.utils.DampedDragAnimation
import com.zeaze.tianyinwallpaper.catalog.utils.InteractiveHighlight
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.sign

@Composable
fun LiquidBottomTabs(
    selectedTabIndex: () -> Int,
    onTabSelected: (index: Int) -> Unit,
    backdrop: Backdrop?,
    tabsCount: Int,
    isLightTheme: Boolean,
    modifier: Modifier = Modifier,
    style: LiquidBottomTabsStyle = LiquidBottomTabsStyle.default(isLightTheme),
    content: @Composable RowScope.() -> Unit
) {
    val safeTabsCount = tabsCount.coerceAtLeast(1)
    val tabsBackdrop = if (backdrop != null) rememberLayerBackdrop() else null

    BoxWithConstraints(
        modifier,
        contentAlignment = Alignment.CenterStart
    ) {
        val density = LocalDensity.current
        val tabWidth = with(density) {
            (constraints.maxWidth.toFloat() - style.trackHorizontalPadding.toPx() * 2f) / safeTabsCount
        }

        val offsetAnimation = remember { Animatable(0f) }
        val panelOffset by remember(density, style) {
            derivedStateOf {
                val fraction = if (constraints.maxWidth == 0) {
                    0f
                } else {
                    (offsetAnimation.value / constraints.maxWidth).fastCoerceIn(-1f, 1f)
                }
                with(density) {
                    style.elasticPanelOffset.toPx() * fraction.sign * EaseOut.transform(abs(fraction))
                }
            }
        }

        val isLtr = LocalLayoutDirection.current == LayoutDirection.Ltr
        val animationScope = rememberCoroutineScope()
        var currentIndex by remember {
            mutableIntStateOf(selectedTabIndex().fastCoerceIn(0, safeTabsCount - 1))
        }
        var pendingSelectionIndex by remember {
            mutableIntStateOf(-1)
        }
        val dampedDragAnimation = remember(animationScope, safeTabsCount, tabWidth, isLtr) {
            DampedDragAnimation(
                animationScope = animationScope,
                initialValue = selectedTabIndex().fastCoerceIn(0, safeTabsCount - 1).toFloat(),
                valueRange = 0f..(safeTabsCount - 1).toFloat(),
                visibilityThreshold = 0.001f,
                initialScale = 1f,
                pressedScale = 78f / 56f,
                onDragStarted = {},
                onDragStopped = {
                    val targetIndex = targetValue.fastRoundToInt().fastCoerceIn(0, safeTabsCount - 1)
                    if (currentIndex != targetIndex) {
                        currentIndex = targetIndex
                        pendingSelectionIndex = targetIndex
                        animateToValue(targetIndex.toFloat())
                        onTabSelected(targetIndex)
                    } else {
                        animateToValue(targetIndex.toFloat())
                    }
                    animationScope.launch {
                        offsetAnimation.animateTo(
                            0f,
                            spring(1f, 300f, 0.5f)
                        )
                    }
                },
                onDrag = { _, dragAmount ->
                    updateValue(
                        (targetValue + dragAmount.x / tabWidth * if (isLtr) 1f else -1f)
                            .fastCoerceIn(0f, (safeTabsCount - 1).toFloat())
                    )
                    animationScope.launch {
                        offsetAnimation.snapTo(offsetAnimation.value + dragAmount.x)
                    }
                }
            )
        }
        LaunchedEffect(selectedTabIndex, safeTabsCount) {
            snapshotFlow { selectedTabIndex() }
                .collectLatest { index ->
                    val targetIndex = index.fastCoerceIn(0, safeTabsCount - 1)
                    if (pendingSelectionIndex != -1) {
                        if (targetIndex == pendingSelectionIndex) {
                            pendingSelectionIndex = -1
                        } else {
                            return@collectLatest
                        }
                    }
                    if (targetIndex != currentIndex) {
                        currentIndex = targetIndex
                        dampedDragAnimation.animateToValue(targetIndex.toFloat())
                    }
                }
        }

        val interactiveHighlight = remember(animationScope, isLtr, tabWidth) {
            InteractiveHighlight(
                animationScope = animationScope,
                position = { size, _ ->
                    Offset(
                        if (isLtr) (dampedDragAnimation.value + 0.5f) * tabWidth + panelOffset
                        else size.width - (dampedDragAnimation.value + 0.5f) * tabWidth + panelOffset,
                        size.height / 2f
                    )
                }
            )
        }

        // ── 背景轨道 ──
        if (backdrop != null) {
            Row(
                Modifier
                    .graphicsLayer {
                        translationX = panelOffset
                    }
                    .drawBackdrop(
                        backdrop = backdrop,
                        shape = { Capsule() },
                        effects = {
                            vibrancy()
                            blur(style.trackBlurRadius.toPx())
                            lens(style.trackLensRadius.toPx(), style.trackLensRadius.toPx())
                        },
                        layerBlock = {
                            val progress = dampedDragAnimation.pressProgress
                            val scale = lerp(1f, 1f + style.trackPressedExpansion.toPx() / size.width, progress)
                            scaleX = scale
                            scaleY = scale
                        },
                        onDrawSurface = { drawRect(style.containerColor) }
                    )
                    .then(interactiveHighlight.modifier)
                    .height(style.trackHeight)
                    .fillMaxWidth()
                    .padding(style.trackInnerPadding),
                verticalAlignment = Alignment.CenterVertically,
                content = content
            )
        } else {
            Row(
                Modifier
                    .graphicsLayer {
                        translationX = panelOffset
                        val progress = dampedDragAnimation.pressProgress
                        val scale = lerp(1f, 1f + style.trackPressedExpansion.toPx() / size.width, progress)
                        scaleX = scale
                        scaleY = scale
                    }
                    .clip(Capsule())
                    .then(interactiveHighlight.modifier)
                    .drawBehind { drawRect(style.fallbackContainerColor) }
                    .height(style.trackHeight)
                    .fillMaxWidth()
                    .padding(style.trackInnerPadding),
                verticalAlignment = Alignment.CenterVertically,
                content = content
            )
        }

        // ── 隐藏的 scale 参考层 ──
        CompositionLocalProvider(
            LocalLiquidBottomTabScale provides {
                lerp(1f, style.hiddenContentPressedScale, dampedDragAnimation.pressProgress)
            }
        ) {
            if (backdrop != null && tabsBackdrop != null) {
                Row(
                    Modifier
                        .clearAndSetSemantics {}
                        .alpha(0f)
                        .layerBackdrop(tabsBackdrop)
                        .graphicsLayer {
                            translationX = panelOffset
                        }
                        .drawBackdrop(
                            backdrop = backdrop,
                            shape = { Capsule() },
                            effects = {
                                val progress = dampedDragAnimation.pressProgress
                                vibrancy()
                                blur(style.trackBlurRadius.toPx())
                                lens(
                                    style.trackLensRadius.toPx() * progress,
                                    style.trackLensRadius.toPx() * progress
                                )
                            },
                            highlight = {
                                val progress = dampedDragAnimation.pressProgress
                                Highlight.Default.copy(alpha = progress)
                            },
                            onDrawSurface = { drawRect(style.containerColor) }
                        )
                        .then(interactiveHighlight.modifier)
                        .height(style.indicatorHeight)
                        .fillMaxWidth()
                        .padding(horizontal = style.trackHorizontalPadding)
                        .graphicsLayer(colorFilter = ColorFilter.tint(style.accentColor)),
                    verticalAlignment = Alignment.CenterVertically,
                    content = content
                )
            } else {
                Row(
                    Modifier
                        .clearAndSetSemantics {}
                        .alpha(0f)
                        .graphicsLayer {
                            translationX = panelOffset
                        }
                        .then(interactiveHighlight.modifier)
                        .height(style.indicatorHeight)
                        .fillMaxWidth()
                        .padding(horizontal = style.trackHorizontalPadding)
                        .graphicsLayer(colorFilter = ColorFilter.tint(style.accentColor)),
                    verticalAlignment = Alignment.CenterVertically,
                    content = content
                )
            }
        }

        // ── 选中标签指示器 ──
        if (backdrop != null && tabsBackdrop != null) {
            Box(
                Modifier
                    .padding(horizontal = style.trackHorizontalPadding)
                    .graphicsLayer {
                        translationX =
                            if (isLtr) dampedDragAnimation.value * tabWidth + panelOffset
                            else size.width - (dampedDragAnimation.value + 1f) * tabWidth + panelOffset
                    }
                    .then(interactiveHighlight.gestureModifier)
                    .then(dampedDragAnimation.modifier)
                    .drawBackdrop(
                        backdrop = rememberCombinedBackdrop(backdrop, tabsBackdrop),
                        shape = { Capsule() },
                        effects = {
                            val progress = dampedDragAnimation.pressProgress
                            lens(
                                style.indicatorLensWidth.toPx() * progress,
                                style.indicatorLensHeight.toPx() * progress,
                                chromaticAberration = true
                            )
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
                            InnerShadow(
                                radius = style.indicatorInnerShadowRadius * progress,
                                alpha = progress
                            )
                        },
                        layerBlock = {
                            scaleX = dampedDragAnimation.scaleX
                            scaleY = dampedDragAnimation.scaleY
                            val velocity = dampedDragAnimation.velocity / 10f
                            scaleX /= 1f - (velocity * 0.75f).fastCoerceIn(-0.2f, 0.2f)
                            scaleY *= 1f - (velocity * 0.25f).fastCoerceIn(-0.2f, 0.2f)
                        },
                        onDrawSurface = {
                            val progress = dampedDragAnimation.pressProgress
                            drawRect(
                                if (isLightTheme) Color.Black.copy(0.1f)
                                else Color.White.copy(0.1f),
                                alpha = 1f - progress
                            )
                            drawRect(Color.Black.copy(alpha = 0.03f * progress))
                        }
                    )
                    .height(style.indicatorHeight)
                    .fillMaxWidth(1f / safeTabsCount)
            )
        } else {
            Box(
                Modifier
                    .padding(horizontal = style.trackHorizontalPadding)
                    .graphicsLayer {
                        translationX =
                            if (isLtr) dampedDragAnimation.value * tabWidth + panelOffset
                            else size.width - (dampedDragAnimation.value + 1f) * tabWidth + panelOffset
                        scaleX = dampedDragAnimation.scaleX
                        scaleY = dampedDragAnimation.scaleY
                        val velocity = dampedDragAnimation.velocity / 10f
                        scaleX /= 1f - (velocity * 0.75f).fastCoerceIn(-0.2f, 0.2f)
                        scaleY *= 1f - (velocity * 0.25f).fastCoerceIn(-0.2f, 0.2f)
                    }
                    .then(interactiveHighlight.gestureModifier)
                    .then(dampedDragAnimation.modifier)
                    .clip(Capsule())
                    .height(style.indicatorHeight)
                    .fillMaxWidth(1f / safeTabsCount)
            )
        }
    }
}
