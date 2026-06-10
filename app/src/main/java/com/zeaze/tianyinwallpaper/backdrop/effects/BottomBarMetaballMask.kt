package com.zeaze.tianyinwallpaper.backdrop.effects

import android.graphics.RenderEffect
import android.os.Build
import com.zeaze.tianyinwallpaper.backdrop.BackdropEffectScope
import com.zeaze.tianyinwallpaper.backdrop.BottomBarMetaballMaskShaderString
import com.zeaze.tianyinwallpaper.backdrop.highlight.effect

fun BackdropEffectScope.bottomBarMetaballMask(
    gap: Float,
    actionSize: Float,
    smoothness: Float,
    opacity: Float = 1f
) {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return

    val shader = obtainRuntimeShader("BottomBarMetaballMask", BottomBarMetaballMaskShaderString).apply {
        setFloatUniform("size", size.width, size.height)
        setFloatUniform("gap", gap.coerceAtLeast(0f))
        setFloatUniform("actionSize", actionSize.coerceAtLeast(1f))
        setFloatUniform("smoothness", smoothness.coerceAtLeast(0.001f))
        setFloatUniform("opacity", opacity.coerceIn(0f, 1f))
    }
    effect(RenderEffect.createRuntimeShaderEffect(shader, "content"))
}
