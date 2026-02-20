package com.zeaze.tianyinwallpaper.ui.setting

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.toggleable
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.lerp as lerpFloat
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.effects.lens
import com.kyant.backdrop.shadow.Shadow
import com.kyant.shapes.Capsule

@Composable
fun LiquidToggle(
    selected: Boolean,
    onSelect: (Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    val isLightTheme = !isSystemInDarkTheme()
    val accentColor = if (isLightTheme) Color(0xFF34C759) else Color(0xFF30D158)
    val trackColor = if (isLightTheme) Color(0xFF787878).copy(0.2f) else Color(0xFF787880).copy(0.36f)
    val fraction = animateFloatAsState(if (selected) 1f else 0f, label = "liquidToggle").value
    val isLtr = LocalLayoutDirection.current == LayoutDirection.Ltr
    val dragWidth = with(LocalDensity.current) { 20.dp.toPx() }
    val trackBackdrop = rememberLayerBackdrop()

    Box(
        modifier = modifier
            .toggleable(value = selected, role = Role.Switch, onValueChange = onSelect),
        contentAlignment = Alignment.CenterStart
    ) {
        Box(
            Modifier
                .layerBackdrop(trackBackdrop)
                .clip(Capsule())
                .drawBehind {
                    drawRect(lerp(trackColor, accentColor, fraction))
                }
                .size(64.dp, 28.dp)
        )

        Box(
            Modifier
                .graphicsLayer {
                    val padding = 2.dp.toPx()
                    translationX =
                        if (isLtr) lerpFloat(padding, padding + dragWidth, fraction)
                        else lerpFloat(-padding, -(padding + dragWidth), fraction)
                }
                .drawBackdrop(
                    backdrop = rememberBackdrop(trackBackdrop) { drawBackdrop() },
                    shape = { Capsule() },
                    effects = {
                        blur(8.dp.toPx())
                        lens(2.dp.toPx(), 4.dp.toPx(), chromaticAberration = true)
                    },
                    shadow = {
                        Shadow(radius = 4.dp, color = Color.Black.copy(alpha = 0.05f))
                    },
                    onDrawSurface = {
                        drawRect(Color.White)
                    }
                )
                .size(40.dp, 24.dp)
        )
    }
}
