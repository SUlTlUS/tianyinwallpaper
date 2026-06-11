package com.zeaze.tianyinwallpaper.backdrop.effects

import com.zeaze.tianyinwallpaper.backdrop.BackdropEffectScope
import com.zeaze.tianyinwallpaper.backdrop.LiquidMetaballMaskShaderString

enum class LiquidMetaballShapeKind(val shaderValue: Float) {
    Ellipse(0f),
    RoundedRect(1f)
}

data class LiquidMetaballBlob(
    val centerX: Float,
    val centerY: Float,
    val halfWidth: Float,
    val halfHeight: Float,
    val cornerRadius: Float,
    val shapeKind: LiquidMetaballShapeKind
) {
    companion object {
        fun ellipse(
            centerX: Float,
            centerY: Float,
            radiusX: Float,
            radiusY: Float = radiusX
        ): LiquidMetaballBlob {
            return LiquidMetaballBlob(
                centerX = centerX,
                centerY = centerY,
                halfWidth = radiusX,
                halfHeight = radiusY,
                cornerRadius = 0f,
                shapeKind = LiquidMetaballShapeKind.Ellipse
            )
        }

        fun roundedRect(
            centerX: Float,
            centerY: Float,
            halfWidth: Float,
            halfHeight: Float,
            cornerRadius: Float
        ): LiquidMetaballBlob {
            return LiquidMetaballBlob(
                centerX = centerX,
                centerY = centerY,
                halfWidth = halfWidth,
                halfHeight = halfHeight,
                cornerRadius = cornerRadius,
                shapeKind = LiquidMetaballShapeKind.RoundedRect
            )
        }
    }
}

data class LiquidMetaballGeometry(
    val anchor: LiquidMetaballBlob,
    val body: LiquidMetaballBlob
)

fun BackdropEffectScope.liquidMetaballMask(
    geometry: LiquidMetaballGeometry,
    smoothness: Float,
    opacity: Float = 1f,
    neckOnly: Boolean = false
) {
    val anchor = geometry.anchor
    val body = geometry.body
    runtimeShaderEffect(
        key = "LiquidMetaballMask",
        shaderString = LiquidMetaballMaskShaderString,
        uniformShaderName = "content"
    ) {
        setFloatUniform("size", size.width, size.height)
        setFloatUniform("centerA", anchor.centerX, anchor.centerY)
        setFloatUniform("halfSizeA", anchor.halfWidth.coerceAtLeast(1f), anchor.halfHeight.coerceAtLeast(1f))
        setFloatUniform("cornerA", anchor.cornerRadius.coerceAtLeast(0f))
        setFloatUniform("shapeA", anchor.shapeKind.shaderValue)
        setFloatUniform("centerB", body.centerX, body.centerY)
        setFloatUniform("halfSizeB", body.halfWidth.coerceAtLeast(1f), body.halfHeight.coerceAtLeast(1f))
        setFloatUniform("cornerB", body.cornerRadius.coerceAtLeast(0f))
        setFloatUniform("shapeB", body.shapeKind.shaderValue)
        setFloatUniform("smoothness", smoothness.coerceAtLeast(0.001f))
        setFloatUniform("opacity", opacity.coerceIn(0f, 1f))
        setFloatUniform("neckOnly", if (neckOnly) 1f else 0f)
    }
}
