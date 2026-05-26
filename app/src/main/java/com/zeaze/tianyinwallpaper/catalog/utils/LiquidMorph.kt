package com.zeaze.tianyinwallpaper.catalog.utils

import android.content.Context
import android.provider.Settings
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import kotlin.math.abs

enum class MorphPhase {
    Idle,
    Detaching,
    Travelling,
    Arriving,
    Settled
}

data class LiquidMorphState(
    val pathT: Float,
    val sizeT: Float,
    val currentDx: Float,
    val currentDy: Float,
    val pushDx: Float,
    val pushDy: Float,
    val anchorScale: Float,
    val blend: Float,
    val containerScale: Float,
    val phase: MorphPhase
)

object LiquidMorphPhysics {
    const val closeVelocityHint = -2.5f

    private const val backOutAmplitude = 2.5f
    private const val anchorEaseDuration = 0.4f
    private const val blendMultiplier = 150f
    private const val maxBlend = 28f

    fun compute(
        rawValue: Float,
        finalDx: Float,
        finalDy: Float,
        horizontalOffset: Float = 0f,
        verticalOffset: Float = 0f
    ): LiquidMorphState {
        val clampedValue = rawValue.coerceIn(0f, 1f)
        val closeUndershoot = if (rawValue < 0f) rawValue else 0f
        val pathT = backOut(clampedValue) + closeUndershoot
        val sizeT = linearToEaseOut(clampedValue) + closeUndershoot
        val pushDx = if (rawValue < 0f) (finalDx + horizontalOffset) * rawValue else 0f
        val pushDy = if (rawValue < 0f) (finalDy + verticalOffset) * rawValue else 0f
        val anchorScale = (1f - clampedValue / anchorEaseDuration).coerceIn(0f, 1f)
        val blend = (abs(pathT - sizeT) * blendMultiplier).coerceIn(0f, maxBlend)
        val containerScale = when {
            rawValue > 1f -> 1f + (rawValue - 1f) * 0.10f
            rawValue < 0f -> 1f + rawValue * 0.55f
            else -> 1f
        }

        return LiquidMorphState(
            pathT = pathT,
            sizeT = sizeT,
            currentDx = finalDx * pathT,
            currentDy = finalDy * pathT,
            pushDx = pushDx,
            pushDy = pushDy,
            anchorScale = anchorScale,
            blend = blend,
            containerScale = containerScale,
            phase = derivePhase(rawValue, clampedValue)
        )
    }

    private fun backOut(t: Float): Float {
        val shifted = t - 1f
        return shifted * shifted * ((backOutAmplitude + 1f) * shifted + backOutAmplitude) + 1f
    }

    private fun linearToEaseOut(t: Float): Float {
        val inverse = 1f - t
        return 1f - inverse * inverse
    }

    private fun derivePhase(rawValue: Float, clampedValue: Float): MorphPhase = when {
        rawValue < 0f -> MorphPhase.Detaching
        clampedValue < 0.001f -> MorphPhase.Idle
        clampedValue < 0.4f -> MorphPhase.Detaching
        clampedValue < 0.8f -> MorphPhase.Travelling
        clampedValue < 0.999f -> MorphPhase.Arriving
        else -> MorphPhase.Settled
    }
}

@Stable
class LiquidMorphController internal constructor(
    val animation: Animatable<Float, AnimationVector1D>,
    private val disableAnimations: () -> Boolean
) {
    var isClosing: Boolean = false
        private set
    var hasHandedOff: Boolean = false
        private set

    val value: Float
        get() = animation.value

    val velocity: Float
        get() = animation.velocity

    val isShowing: Boolean
        get() = value > 0.001f || isClosing

    suspend fun open() {
        isClosing = false
        hasHandedOff = false
        if (disableAnimations()) {
            animation.snapTo(1f)
            return
        }
        animation.animateTo(
            targetValue = 1f,
            animationSpec = spring(
                dampingRatio = 0.73f,
                stiffness = 120f
            )
        )
    }

    suspend fun close() {
        isClosing = true
        if (disableAnimations()) {
            hasHandedOff = true
            animation.snapTo(0f)
            isClosing = false
            return
        }
        animation.animateTo(
            targetValue = 0f,
            animationSpec = spring(
                dampingRatio = 0.73f,
                stiffness = 120f
            ),
            initialVelocity = LiquidMorphPhysics.closeVelocityHint
        )
        hasHandedOff = true
        isClosing = false
    }

    fun updateHandoff() {
        if (isClosing && value <= 0f && !hasHandedOff) {
            hasHandedOff = true
        }
    }
}

@Composable
fun rememberLiquidMorphController(
    initialValue: Float = 0f
): LiquidMorphController {
    val context = LocalContext.current
    return remember {
        LiquidMorphController(
            animation = Animatable(initialValue),
            disableAnimations = { context.areSystemAnimationsDisabled() }
        )
    }
}

private fun Context.areSystemAnimationsDisabled(): Boolean {
    return runCatching {
        Settings.Global.getFloat(contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
    }.getOrDefault(false)
}
