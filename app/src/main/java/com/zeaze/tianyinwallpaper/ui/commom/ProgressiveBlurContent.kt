package com.zeaze.tianyinwallpaper.ui.commom

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.background
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.zeaze.tianyinwallpaper.backdrop.Backdrop
import com.zeaze.tianyinwallpaper.backdrop.drawPlainBackdrop
import com.zeaze.tianyinwallpaper.backdrop.effects.blur
import com.zeaze.tianyinwallpaper.backdrop.effects.runtimeShaderEffect

@Composable
fun ProgressiveBlurContent(
    modifier: Modifier = Modifier,
    backdrop: Backdrop? = null
) {
    val isLightTheme = !isSystemInDarkTheme()
    val contentColor = if (isLightTheme) Color.Black else Color.White
    val tintColor = if (isLightTheme) Color.White else Color(0xFF808080)
    val fallbackColor = if (isLightTheme) Color.White.copy(alpha = 0.75f) else Color(0xFF202020).copy(alpha = 0.7f)

    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Box(
            modifier = (if (backdrop != null) {
                Modifier.drawPlainBackdrop(
                    backdrop = backdrop,
                    shape = { RectangleShape },
                    effects = {
                        blur(4.dp.toPx())
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                            runtimeShaderEffect(
                                key = "AlphaMask",
                                shaderString = """
uniform shader content;

uniform float2 size;
/*layout(color) uniform half4 tint;
uniform float tintIntensity;*/

half4 main(float2 coord) {
    /*float blurAlpha = smoothstep(size.y, size.y * 0.5, coord.y);
    float tintAlpha = smoothstep(size.y, size.y * 0.5, coord.y);
    return mix(content.eval(coord) * blurAlpha, tint * tintAlpha, tintIntensity);*/
    float alpha = smoothstep(size.y, size.y * 0.5, coord.y);
    return content.eval(coord) * alpha;
}
"""
                                ,
                                uniformShaderName = "content"
                            ) {
                                setFloatUniform("size", size.width, size.height)
                                //setColorUniform("tint", tintColor)
                                //setFloatUniform("tintIntensity", 0.8f)
                            }
                        }
                    }
                )
            } else {
                Modifier.background(
                    Brush.verticalGradient(
                        colors = listOf(fallbackColor, fallbackColor.copy(alpha = 0f))
                    )
                )
            })
                .height(128.dp)
                .fillMaxWidth(),
            contentAlignment = Alignment.Center
        ) {
            /*BasicText(
                text = "alpha-masked progressive blur",
                style = TextStyle(contentColor, 16.sp)
            )*/
        }
    }
}

