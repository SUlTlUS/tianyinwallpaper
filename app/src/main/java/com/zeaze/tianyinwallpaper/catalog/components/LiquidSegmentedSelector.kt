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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.util.fastCoerceIn
import androidx.compose.ui.util.fastRoundToInt
import androidx.compose.ui.util.lerp
import com.zeaze.tianyinwallpaper.catalog.utils.DampedDragAnimation
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
    modifier: Modifier = Modifier
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

    BoxWithConstraints(modifier = modifier, contentAlignment = Alignment.CenterStart) {
        val density = LocalDensity.current
        val horizontalInsetPx = with(density) { trackPadding.toPx() }
        val availableWidth = (constraints.maxWidth.toFloat() - horizontalInsetPx * 2f).coerceAtLeast(0f)
        val tabWidth = (availableWidth / tabsCount).coerceAtLeast(0f)
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

        CompositionLocalProvider(
            LocalLiquidBottomTabScale provides {
                lerp(1f, 1.15f, dampedDragAnimation.pressProgress)
            }
        ) {
            Row(
                Modifier
                    .clip(trackShape)
                    .background(trackColor)
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

        Box(
            Modifier
                .padding(horizontal = trackPadding)
                .graphicsLayer {
                    val maxX = (availableWidth - tabWidth).coerceAtLeast(0f)
                    translationX = (dampedDragAnimation.value * tabWidth).coerceIn(0f, maxX)
                }
                .then(dampedDragAnimation.modifier)
                .graphicsLayer {
                    val velocity = dampedDragAnimation.velocity / 10f
                    scaleX = dampedDragAnimation.scaleX / (1f - (velocity * 0.75f).fastCoerceIn(-0.2f, 0.2f))
                    scaleY = dampedDragAnimation.scaleY * (1f - (velocity * 0.25f).fastCoerceIn(-0.2f, 0.2f))
                }
                .clip(thumbShape)
                .background(accentColor.copy(alpha = 0.24f))
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



