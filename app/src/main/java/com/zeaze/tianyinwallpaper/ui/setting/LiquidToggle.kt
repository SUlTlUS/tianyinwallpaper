package com.zeaze.tianyinwallpaper.ui.setting

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.lerp as lerpFloat
import com.kyant.backdrop.backdrops.LayerBackdrop
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.effects.lens
import com.kyant.backdrop.effects.vibrancy
import com.kyant.backdrop.highlight.Highlight

@Composable
fun LiquidToggle(
    selected: () -> Boolean,
    onSelect: (Boolean) -> Unit,
    backdrop: LayerBackdrop?,
    modifier: Modifier = Modifier,
    enableLiquidGlass: Boolean = true
) {
    val isLightTheme = !isSystemInDarkTheme()
    val accentColor = if (isLightTheme) Color(0xFF34C759) else Color(0xFF30D158)
    val trackColor = if (isLightTheme) Color(0xFF787878).copy(0.2f) else Color(0xFF787880).copy(0.36f)
    val fraction = animateFloatAsState(if (selected()) 1f else 0f, label = "liquidToggle").value
    val isLtr = LocalLayoutDirection.current == LayoutDirection.Ltr
    val trackBackdrop = if (enableLiquidGlass) (backdrop ?: rememberLayerBackdrop()) else null

    Box(
        modifier = modifier
            .clip(RoundedCornerShape(percent = 50))
            .toggleable(value = selected(), role = Role.Switch, onValueChange = onSelect),
        contentAlignment = Alignment.CenterStart
    ) {
        // Track
        Box(
            Modifier
                .size(64.dp, 28.dp)
                .let { if (enableLiquidGlass && trackBackdrop != null) it.layerBackdrop(trackBackdrop) else it }
                .clip(RoundedCornerShape(percent = 50))
                .drawBehind {
                    drawRect(lerp(trackColor, accentColor, fraction))
                }
        )

        // Thumb
        Box(
            Modifier
                .graphicsLayer {
                    val padding = 2.dp.toPx()
                    val totalDragDistance = 20.dp.toPx() // 64 - 40 - 2 - 2 = 20
                    translationX =
                        if (isLtr) lerpFloat(padding, padding + totalDragDistance, fraction)
                        else lerpFloat(-padding, -(padding + totalDragDistance), fraction)
                }
                .let {
                    if (enableLiquidGlass && trackBackdrop != null) {
                        it.drawBackdrop(
                            backdrop = trackBackdrop,
                            shape = { RoundedCornerShape(percent = 50) },
                            effects = {
                                vibrancy()
                                blur(8.dp.toPx())
                                lens(20.dp.toPx(), 40.dp.toPx(), chromaticAberration = true)
                            },
                            highlight = { Highlight.Default },
                            onDrawSurface = {
                                drawRect(Color.White.copy(alpha = 0.1f))
                            }
                        )
                    } else {
                        it
                            .clip(RoundedCornerShape(percent = 50))
                            .drawBehind {
                                drawRect(Color.White.copy(alpha = 0.8f))
                            }
                    }
                }
                .size(40.dp, 24.dp)
        )
    }
}
