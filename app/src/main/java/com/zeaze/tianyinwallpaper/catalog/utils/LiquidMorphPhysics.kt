package com.zeaze.tianyinwallpaper.catalog.utils

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.animation.core.spring
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin

/**
 * 独立的液态形变控制器，参考 liquid_glass_widgets 的 Liquid Morph Engine 架构。
 *
 * 这里不绑定具体组件：按钮、菜单、底部栏都只消费 [LiquidMorphState]，
 * 后续如果换成真正 SDF/metaball shader，只需要替换渲染层。
 */
class LiquidMorphController internal constructor(
    private val scope: CoroutineScope,
    private val speed: MorphSpeed = MorphSpeed.Normal
) {
    private val animatable = Animatable(0f)

    var hasHandedOff by mutableStateOf(true)
        private set

    val value: Float
        get() = animatable.value

    val velocity: Float
        get() = animatable.velocity

    val isShowing: Boolean
        get() = animatable.value > 0.001f || animatable.isRunning

    fun open() {
        hasHandedOff = false
        scope.launch {
            animatable.animateTo(
                targetValue = 1f,
                animationSpec = spring(
                    dampingRatio = LiquidMorphPhysics.DAMPING_RATIO,
                    stiffness = speed.stiffness
                )
            )
        }
    }

    fun close() {
        scope.launch {
            animatable.animateTo(
                targetValue = 0f,
                animationSpec = spring(
                    dampingRatio = LiquidMorphPhysics.DAMPING_RATIO,
                    stiffness = speed.stiffness
                ),
                initialVelocity = LiquidMorphPhysics.CLOSE_VELOCITY_HINT
            ) {
                if (value <= 0f) {
                    hasHandedOff = true
                }
            }
            hasHandedOff = true
        }
    }

    fun snapToClosed() {
        scope.launch {
            animatable.snapTo(0f)
            hasHandedOff = true
        }
    }

    fun computeState(
        finalDx: Float = 0f,
        finalDy: Float = 0f,
        horizontalOffset: Float = 0f,
        verticalOffset: Float = 0f
    ): LiquidMorphState {
        return LiquidMorphPhysics.compute(
            rawValue = value,
            velocity = velocity,
            finalDx = finalDx,
            finalDy = finalDy,
            horizontalOffset = horizontalOffset,
            verticalOffset = verticalOffset
        )
    }
}

@Composable
fun rememberLiquidMorphController(
    speed: MorphSpeed = MorphSpeed.Normal
): LiquidMorphController {
    val scope = rememberCoroutineScope()
    return remember(scope, speed) {
        LiquidMorphController(scope = scope, speed = speed)
    }
}

data class LiquidMorphState(
    val rawValue: Float,
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

enum class MorphPhase {
    Idle,
    Detaching,
    Travelling,
    Arriving,
    Settled
}

enum class MorphSpeed(val stiffness: Float) {
    Slow(60f),
    Normal(120f),
    Fast(200f),
    Instant(500f)
}

object LiquidMorphPhysics {
    const val DAMPING_RATIO = 0.73f
    const val CLOSE_VELOCITY_HINT = -2.5f
    private const val MAX_BLEND_RADIUS = 28f

    fun compute(
        rawValue: Float,
        velocity: Float = 0f,
        finalDx: Float = 0f,
        finalDy: Float = 0f,
        horizontalOffset: Float = 0f,
        verticalOffset: Float = 0f
    ): LiquidMorphState {
        val clamped = rawValue.coerceIn(0f, 1f)
        val pathT = backOut(clamped)
        val sizeT = easeOut(clamped)
        val anchorScale = (1f - clamped / 0.4f).coerceIn(0f, 1f)
        val bridgeAmount = sin(clamped * PI.toFloat()).coerceIn(0f, 1f)
        val blend = MAX_BLEND_RADIUS * bridgeAmount
        val closingPush = if (rawValue < 0f || velocity < -0.25f) {
            (-velocity * 0.02f).coerceIn(0f, 0.12f)
        } else {
            0f
        }
        val containerScale = (1f + (rawValue - 1f).coerceAtLeast(0f) * 0.08f - closingPush * 0.5f)
            .coerceIn(0.92f, 1.08f)

        return LiquidMorphState(
            rawValue = rawValue,
            pathT = pathT,
            sizeT = sizeT,
            currentDx = finalDx * pathT + horizontalOffset,
            currentDy = finalDy * pathT + verticalOffset,
            pushDx = -finalDx * closingPush,
            pushDy = -finalDy * closingPush,
            anchorScale = anchorScale,
            blend = blend,
            containerScale = containerScale,
            phase = phaseFor(rawValue)
        )
    }

    private fun phaseFor(rawValue: Float): MorphPhase {
        return when {
            rawValue < 0.001f -> MorphPhase.Idle
            rawValue < 0.4f -> MorphPhase.Detaching
            rawValue < 0.8f -> MorphPhase.Travelling
            rawValue < 1f -> MorphPhase.Arriving
            else -> MorphPhase.Settled
        }
    }

    private fun easeOut(t: Float): Float {
        val x = t.coerceIn(0f, 1f)
        return 1f - (1f - x) * (1f - x)
    }

    private fun backOut(t: Float, amplitude: Float = 2.5f): Float {
        val x = t.coerceIn(0f, 1f) - 1f
        return x * x * ((amplitude + 1f) * x + amplitude) + 1f
    }
}
