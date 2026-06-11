package com.zeaze.tianyinwallpaper.catalog.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.isSpecified
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.kyant.shapes.Capsule
import com.zeaze.tianyinwallpaper.backdrop.Backdrop
import com.zeaze.tianyinwallpaper.backdrop.BackdropEffectScope
import com.zeaze.tianyinwallpaper.backdrop.drawBackdrop
import com.zeaze.tianyinwallpaper.backdrop.effects.LiquidMetaballGeometry
import com.zeaze.tianyinwallpaper.backdrop.effects.blur
import com.zeaze.tianyinwallpaper.backdrop.effects.lens
import com.zeaze.tianyinwallpaper.backdrop.effects.liquidMetaballMask
import com.zeaze.tianyinwallpaper.backdrop.effects.vibrancy

@Composable
fun LiquidMetaballSurface(
    backdrop: Backdrop,
    geometry: BackdropEffectScope.() -> LiquidMetaballGeometry,
    modifier: Modifier = Modifier,
    shape: () -> Shape = { Capsule() },
    smoothness: Dp = 28.dp,
    opacity: Float = 1f,
    neckOnly: Boolean = false,
    blurRadius: Dp = 8.dp,
    lensRadius: Dp = 24.dp,
    surfaceColor: Color = Color.Unspecified,
    chromaticAberration: Boolean = true,
    content: @Composable BoxScope.() -> Unit = {}
) {
    Box(
        modifier = modifier,
        contentAlignment = Alignment.Center
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .drawBackdrop(
                    backdrop = backdrop,
                    shape = shape,
                    effects = {
                        vibrancy()
                        blur(blurRadius.toPx())
                        lens(
                            lensRadius.toPx(),
                            lensRadius.toPx(),
                            chromaticAberration = chromaticAberration
                        )
                        liquidMetaballMask(
                            geometry = geometry(),
                            smoothness = smoothness.toPx(),
                            opacity = opacity,
                            neckOnly = neckOnly
                        )
                    },
                    highlight = null,
                    shadow = null,
                    onDrawSurface = {
                        if (surfaceColor.isSpecified) {
                            drawRect(surfaceColor)
                        }
                    }
                )
        )
        content()
    }
}
