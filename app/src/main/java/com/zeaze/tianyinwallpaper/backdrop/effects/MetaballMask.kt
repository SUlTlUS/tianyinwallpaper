package com.zeaze.tianyinwallpaper.backdrop.effects

import com.zeaze.tianyinwallpaper.backdrop.BackdropEffectScope
import com.zeaze.tianyinwallpaper.backdrop.MetaballMaskShaderString

fun BackdropEffectScope.metaballMask(
    centerAX: Float,
    centerAY: Float,
    radiusAX: Float,
    radiusAY: Float,
    centerBX: Float,
    centerBY: Float,
    radiusBX: Float,
    radiusBY: Float,
    smoothness: Float,
    opacity: Float = 1f,
    neckOnly: Boolean = false
) {
    runtimeShaderEffect(
        key = "MetaballMask",
        shaderString = MetaballMaskShaderString,
        uniformShaderName = "content"
    ) {
        setFloatUniform("size", size.width, size.height)
        setFloatUniform("centerA", centerAX, centerAY)
        setFloatUniform("radiusA", radiusAX, radiusAY)
        setFloatUniform("centerB", centerBX, centerBY)
        setFloatUniform("radiusB", radiusBX, radiusBY)
        setFloatUniform("smoothness", smoothness.coerceAtLeast(0.001f))
        setFloatUniform("opacity", opacity.coerceIn(0f, 1f))
        setFloatUniform("neckOnly", if (neckOnly) 1f else 0f)
    }
}
