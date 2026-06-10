package com.zeaze.tianyinwallpaper.backdrop.effects

import android.graphics.RenderEffect
import android.os.Build
import com.zeaze.tianyinwallpaper.backdrop.BackdropEffectScope
import com.zeaze.tianyinwallpaper.backdrop.MetaballMaskShaderString
import com.zeaze.tianyinwallpaper.backdrop.highlight.effect

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
    opacity: Float = 1f
) {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return

    val shader = obtainRuntimeShader("MetaballMask", MetaballMaskShaderString).apply {
        setFloatUniform("size", size.width, size.height)
        setFloatUniform("centerA", centerAX, centerAY)
        setFloatUniform("radiusA", radiusAX, radiusAY)
        setFloatUniform("centerB", centerBX, centerBY)
        setFloatUniform("radiusB", radiusBX, radiusBY)
        setFloatUniform("smoothness", smoothness.coerceAtLeast(0.001f))
        setFloatUniform("opacity", opacity.coerceIn(0f, 1f))
    }
    effect(RenderEffect.createRuntimeShaderEffect(shader, "content"))
}
