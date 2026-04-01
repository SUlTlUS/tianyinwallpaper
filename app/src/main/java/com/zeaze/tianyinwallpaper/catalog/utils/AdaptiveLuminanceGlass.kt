package com.zeaze.tianyinwallpaper.catalog.utils

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.util.lerp
import com.zeaze.tianyinwallpaper.backdrop.backdrops.LayerBackdrop
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import kotlin.math.sign

/**
 * 线性插值两种颜色
 */
private fun lerpColor(start: Color, stop: Color, fraction: Float): Color {
    return Color(
        red = lerp(start.red, stop.red, fraction),
        green = lerp(start.green, stop.green, fraction),
        blue = lerp(start.blue, stop.blue, fraction),
        alpha = lerp(start.alpha, stop.alpha, fraction)
    )
}

@Immutable
data class AdaptiveLuminanceGlassState(
    val luminance: Float,
    val normalizedLuminance: Float,
    val contentColor: Color,
    val brightness: Float,
    val contrast: Float,
    val saturation: Float,
    val blurRadius: Float,
    val hasLiveSample: Boolean,
    val debugStatus: String,
    val consecutiveFailures: Int
)

val LocalAdaptiveLuminanceGlassState = compositionLocalOf<AdaptiveLuminanceGlassState?> { null }

/**
 * 多区域 luminance 计算结果
 */
@Immutable
data class MultiRegionLuminanceResult(
    val regionLuminances: Map<String, Float>,
    val hasError: Boolean,
    val errorMessage: String?
)

/**
 * 多位置 luminance 采样器状态
 */
class MultiRegionLuminanceSamplerState {
    var result by mutableStateOf(MultiRegionLuminanceResult(emptyMap(), true, "Init"))
        private set

    internal fun updateResult(result: MultiRegionLuminanceResult) {
        this.result = result
    }

    fun getLuminance(regionKey: String): Float? = result.regionLuminances[regionKey]
}

/**
 * 从 ImageBitmap 计算指定区域的 luminance（IO 线程）
 */
private suspend fun computeRegionLuminances(
    imageBitmap: ImageBitmap,
    regions: Map<String, Rect>
): MultiRegionLuminanceResult = withContext(Dispatchers.Default) {
    if (imageBitmap.width <= 0 || imageBitmap.height <= 0) {
        return@withContext MultiRegionLuminanceResult(emptyMap(), true, "EmptyLayer")
    }

    runCatching {
        val pixelMap = imageBitmap.toPixelMap()
        val width = pixelMap.width
        val height = pixelMap.height

        val regionLuminances = mutableMapOf<String, Float>()

        regions.forEach { (key, region) ->
            val left = (region.left * width).toInt().coerceIn(0, width - 1)
            val right = (region.right * width).toInt().coerceIn(0, width - 1)
            val top = (region.top * height).toInt().coerceIn(0, height - 1)
            val bottom = (region.bottom * height).toInt().coerceIn(0, height - 1)

            if (left >= right || top >= bottom) {
                regionLuminances[key] = 0.5f
                return@forEach
            }

            // 在区域内采样 5x5 网格
            var sum = 0f
            var count = 0
            for (row in 0 until 5) {
                val y = top + ((row + 0.5f) / 5f * (bottom - top)).toInt()
                for (col in 0 until 5) {
                    val x = left + ((col + 0.5f) / 5f * (right - left)).toInt()
                    if (x in 0 until width && y in 0 until height) {
                        sum += pixelMap[x, y].luminance()
                        count += 1
                    }
                }
            }
            regionLuminances[key] = if (count > 0) sum / count else 0.5f
        }

        MultiRegionLuminanceResult(regionLuminances, false, null)
    }.getOrElse {
        MultiRegionLuminanceResult(emptyMap(), true, "PixelErr")
    }
}

/**
 * 创建共享的多区域 luminance 采样器
 * 一次性计算所有区域的 luminance，避免重复采样
 * 
 * @param enabled 是否启用
 * @param sampleLayer 采样图层
 * @param regions 需要采样的区域 Map<区域Key, 区域Rect>
 * @param sampleIntervalMs 采样间隔
 */
@Composable
fun rememberMultiRegionLuminanceSampler(
    enabled: Boolean,
    sampleLayer: GraphicsLayer,
    regions: Map<String, Rect>,
    sampleIntervalMs: Long = 80L
): MultiRegionLuminanceSamplerState {
    val state = remember { MultiRegionLuminanceSamplerState() }

    LaunchedEffect(enabled, sampleLayer, regions, sampleIntervalMs) {
        if (!enabled) {
            state.updateResult(MultiRegionLuminanceResult(emptyMap(), true, "Disabled"))
            return@LaunchedEffect
        }

        var consecutiveFailures = 0
        while (isActive) {
            // GPU 采样在主线程
            val bitmapResult = runCatching { sampleLayer.toImageBitmap() }
            val bitmap = bitmapResult.getOrNull()

            if (bitmap != null && bitmap.width > 0 && bitmap.height > 0) {
                // 像素计算在 IO 线程
                val result = computeRegionLuminances(bitmap, regions)
                state.updateResult(result)
                consecutiveFailures = 0
            } else {
                state.updateResult(MultiRegionLuminanceResult(emptyMap(), true, "SnapshotErr"))
                consecutiveFailures++
            }

            val nextDelay = if (consecutiveFailures < 4) sampleIntervalMs
            else (sampleIntervalMs * 2).coerceAtMost(800L)
            delay(nextDelay)
        }
    }

    return state
}

/**
 * 从共享采样器读取特定区域的 luminance 状态
 * 无额外协程，直接读取预计算结果
 */
@Composable
fun rememberRegionLuminanceState(
    samplerState: MultiRegionLuminanceSamplerState,
    regionKey: String,
    enabled: Boolean = true
): AdaptiveLuminanceGlassState {
    val luminanceAnimation = remember { Animatable(0.5f) }
    val contentColorAnimation = remember { Animatable(0f) }
    var hasLiveSample by remember { mutableStateOf(false) }
    var debugStatus by remember { mutableStateOf("Init") }

    // 直接读取采样器状态并触发动画
    val rawLuminance = samplerState.getLuminance(regionKey)
    val hasError = samplerState.result.hasError

    LaunchedEffect(rawLuminance, hasError, enabled) {
        if (!enabled) {
            hasLiveSample = false
            return@LaunchedEffect
        }

        if (rawLuminance != null && !hasError) {
            hasLiveSample = true
            debugStatus = "Live"
            // 基于速度计算动画时长：变化量越大，时间越长
            val distance = kotlin.math.abs(rawLuminance - luminanceAnimation.value)
            val durationMs = (distance * 500f).toInt().coerceIn(16, 300) // 速度 = 0.002/frame
            luminanceAnimation.animateTo(
                rawLuminance,
                animationSpec = tween(
                    durationMillis = durationMs,
                    easing = FastOutSlowInEasing
                )
            )
        } else {
            debugStatus = samplerState.result.errorMessage ?: "NoSample"
        }
    }

    val luminance = luminanceAnimation.value
    // 二次曲线映射：luminance 0~1 -> normalized -1~1，曲线更平滑
    val normalized = (luminance * 2f - 1f).let { sign(it) * it * it }
    
    // 字体颜色：离散切换（只有黑或白），但切换过程平滑
    val targetContentTransition = if (luminance > 0.5f) 1f else 0f
    LaunchedEffect(targetContentTransition) {
        contentColorAnimation.animateTo(
            targetContentTransition,
            animationSpec = tween(durationMillis = 150, easing = FastOutSlowInEasing)
        )
    }
    val contentColor = lerpColor(Color.White, Color.Black, contentColorAnimation.value)
    
    // 亮度调整：亮背景增亮，暗背景减暗
    val brightness = if (normalized > 0f) {
        lerp(0.1f, 0.3f, normalized)
    } else {
        lerp(0.1f, -0.2f, -normalized)
    }

    // 对比度调整：亮背景降低对比度（柔和效果），但不能太低
    val contrast = if (normalized > 0f) {
        lerp(1f, 0.5f, normalized)
    } else {
        1f
    }
    
    // 饱和度：固定为 1.5f，增强色彩表现
    val saturation = 1.5f
    
    // 模糊半径调整：亮背景增加模糊，暗背景减少模糊
    val blurRadius = if (normalized > 0f) {
        lerp(8f, 16f, normalized)
    } else {
        lerp(8f, 2f, -normalized)
    }

    return AdaptiveLuminanceGlassState(
        luminance = luminance,
        normalizedLuminance = normalized,
        contentColor = contentColor,
        brightness = brightness,
        contrast = contrast,
        saturation = saturation,
        blurRadius = blurRadius,
        hasLiveSample = hasLiveSample,
        debugStatus = debugStatus,
        consecutiveFailures = if (hasError) 1 else 0
    )
}

// ========== 保留原有的单区域 API ==========

@Composable
fun ProvideAdaptiveLuminanceGlass(
    enabled: Boolean,
    layerBackdrop: LayerBackdrop?,
    sampleIntervalMs: Long = 80L,
    content: @Composable () -> Unit
) {
    val state =
        if (enabled && layerBackdrop != null) {
            rememberAdaptiveLuminanceGlassState(
                enabled = true,
                sampleLayer = layerBackdrop.graphicsLayer,
                sampleIntervalMs = sampleIntervalMs
            )
        } else {
            null
        }

    CompositionLocalProvider(LocalAdaptiveLuminanceGlassState provides state) {
        content()
    }
}

@Composable
fun rememberAdaptiveLuminanceGlassState(
    enabled: Boolean,
    sampleLayer: GraphicsLayer,
    sampleIntervalMs: Long = 80L
): AdaptiveLuminanceGlassState {
    val luminanceAnimation = remember { Animatable(0.5f) }
    val contentColorAnimation = remember { Animatable(0f) }
    var hasLiveSample by remember { mutableStateOf(false) }
    var debugStatus by remember { mutableStateOf("Init") }
    var consecutiveFailures by remember { mutableStateOf(0) }

    LaunchedEffect(enabled, sampleLayer, sampleIntervalMs) {
        if (!enabled) {
            hasLiveSample = false
            return@LaunchedEffect
        }
        while (isActive) {
            val sampled = sampleAverageLuminance(sampleLayer)
            if (sampled.luminance != null) {
                hasLiveSample = true
                debugStatus = "Live"
                consecutiveFailures = 0
            } else {
                debugStatus = sampled.reason
                consecutiveFailures += 1
            }
            val averageLuminance = sampled.luminance ?: luminanceAnimation.value
            // 基于速度计算动画时长：变化量越大，时间越长
            val distance = kotlin.math.abs(averageLuminance - luminanceAnimation.value)
            val durationMs = (distance * 500f).toInt().coerceIn(16, 300)
            luminanceAnimation.animateTo(
                averageLuminance,
                animationSpec = tween(
                    durationMillis = durationMs,
                    easing = FastOutSlowInEasing
                )
            )
            val nextDelayMs =
                if (consecutiveFailures < 4) sampleIntervalMs
                else (sampleIntervalMs * 2).coerceAtMost(600L)
            delay(nextDelayMs)
        }
    }

    val luminance = luminanceAnimation.value
    // 二次曲线映射：luminance 0~1 -> normalized -1~1，曲线更平滑
    val normalized = (luminance * 2f - 1f).let { sign(it) * it * it }
    
    // 字体颜色：离散切换（只有黑或白），但切换过程平滑
    val targetContentTransition = if (luminance > 0.5f) 1f else 0f
    LaunchedEffect(targetContentTransition) {
        contentColorAnimation.animateTo(
            targetContentTransition,
            animationSpec = tween(durationMillis = 150, easing = FastOutSlowInEasing)
        )
    }
    val contentColor = lerpColor(Color.White, Color.Black, contentColorAnimation.value)
    
    // 亮度调整：亮背景增亮，暗背景减暗
    val brightness = if (normalized > 0f) {
        lerp(0.1f, 0.3f, normalized)
    } else {
        lerp(0.1f, -0.2f, -normalized)
    }

    // 对比度调整：亮背景降低对比度（柔和效果），但不能太低
    val contrast = if (normalized > 0f) {
        lerp(1f, 0.5f, normalized)
    } else {
        1f
    }
    
    // 饱和度：固定为 1.5f，增强色彩表现
    val saturation = 1.5f
    
    // 模糊半径调整：亮背景增加模糊，暗背景减少模糊
    val blurRadius = if (normalized > 0f) {
        lerp(8f, 16f, normalized)
    } else {
        lerp(8f, 2f, -normalized)
    }

    return AdaptiveLuminanceGlassState(
        luminance = luminance,
        normalizedLuminance = normalized,
        contentColor = contentColor,
        brightness = brightness,
        contrast = contrast,
        saturation = saturation,
        blurRadius = blurRadius,
        hasLiveSample = hasLiveSample,
        debugStatus = debugStatus,
        consecutiveFailures = consecutiveFailures
    )
}

private data class SampleResult(
    val luminance: Float?,
    val reason: String
)

private suspend fun sampleAverageLuminance(sampleLayer: GraphicsLayer): SampleResult {
    val imageBitmap =
        runCatching {
            sampleLayer.toImageBitmap()
        }.getOrElse {
            return SampleResult(null, "SnapshotErr")
        }

    if (imageBitmap.width <= 0 || imageBitmap.height <= 0) {
        return SampleResult(null, "EmptyLayer")
    }

    return runCatching {
        val pixelMap = imageBitmap.toPixelMap()
        val width = pixelMap.width
        val height = pixelMap.height
        if (width <= 0 || height <= 0) {
            return SampleResult(null, "EmptyPixels")
        }

        var sum = 0f
        var count = 0
        for (row in 0 until 5) {
            val y = ((row + 0.5f) / 5f * (height - 1)).toInt().coerceIn(0, height - 1)
            for (col in 0 until 5) {
                val x = ((col + 0.5f) / 5f * (width - 1)).toInt().coerceIn(0, width - 1)
                sum += pixelMap[x, y].luminance()
                count += 1
            }
        }

        if (count == 0) {
            SampleResult(null, "NoSamples")
        } else {
            SampleResult(sum / count, "Live")
        }
    }.getOrElse {
        SampleResult(null, "PixelErr")
    }
}

/**
 * 动态区域 luminance 采样（支持拖动测试面板等场景）
 * 区域变化时会自动重新采样
 */
@Composable
fun rememberDynamicRegionLuminanceState(
    enabled: Boolean,
    sampleLayer: GraphicsLayer,
    region: Rect,  // 归一化区域 [0,1]
    sampleIntervalMs: Long = 80L
): AdaptiveLuminanceGlassState {
    val luminanceAnimation = remember { Animatable(0.5f) }
    val contentColorAnimation = remember { Animatable(0f) }
    var hasLiveSample by remember { mutableStateOf(false) }
    var debugStatus by remember { mutableStateOf("Init") }

    LaunchedEffect(enabled, sampleLayer, region, sampleIntervalMs) {
        if (!enabled) {
            hasLiveSample = false
            return@LaunchedEffect
        }
        while (isActive) {
            val sampled = sampleRegionLuminance(sampleLayer, region)
            if (sampled.luminance != null) {
                hasLiveSample = true
                debugStatus = "Live"
            } else {
                debugStatus = sampled.reason
            }
            val targetLuminance = sampled.luminance ?: luminanceAnimation.value
            val distance = kotlin.math.abs(targetLuminance - luminanceAnimation.value)
            val durationMs = (distance * 500f).toInt().coerceIn(16, 300)
            luminanceAnimation.animateTo(
                targetLuminance,
                animationSpec = tween(
                    durationMillis = durationMs,
                    easing = FastOutSlowInEasing
                )
            )
            delay(sampleIntervalMs)
        }
    }

    val luminance = luminanceAnimation.value
    val normalized = (luminance * 2f - 1f).let { sign(it) * it * it }

    val targetContentTransition = if (luminance > 0.5f) 1f else 0f
    LaunchedEffect(targetContentTransition) {
        contentColorAnimation.animateTo(
            targetContentTransition,
            animationSpec = tween(durationMillis = 150, easing = FastOutSlowInEasing)
        )
    }
    val contentColor = lerpColor(Color.White, Color.Black, contentColorAnimation.value)

    // 亮度调整：亮背景增亮，暗背景减暗
    val brightness = if (normalized > 0f) {
        lerp(0.1f, 0.3f, normalized)
    } else {
        lerp(0.1f, -0.2f, -normalized)
    }

    // 对比度调整：亮背景降低对比度（柔和效果），但不能太低
    val contrast = if (normalized > 0f) {
        lerp(1f, 0.5f, normalized)
    } else {
        1f
    }

    val saturation = 1.5f

    val blurRadius = if (normalized > 0f) {
        lerp(8f, 16f, normalized)
    } else {
        lerp(8f, 2f, -normalized)
    }

    return AdaptiveLuminanceGlassState(
        luminance = luminance,
        normalizedLuminance = normalized,
        contentColor = contentColor,
        brightness = brightness,
        contrast = contrast,
        saturation = saturation,
        blurRadius = blurRadius,
        hasLiveSample = hasLiveSample,
        debugStatus = debugStatus,
        consecutiveFailures = 0
    )
}

/**
 * 采样指定区域的 luminance
 */
private suspend fun sampleRegionLuminance(
    sampleLayer: GraphicsLayer,
    region: Rect
): SampleResult {
    val imageBitmap = runCatching {
        sampleLayer.toImageBitmap()
    }.getOrElse {
        return SampleResult(null, "SnapshotErr")
    }

    if (imageBitmap.width <= 0 || imageBitmap.height <= 0) {
        return SampleResult(null, "EmptyLayer")
    }

    return runCatching {
        val pixelMap = imageBitmap.toPixelMap()
        val width = pixelMap.width
        val height = pixelMap.height

        if (width <= 0 || height <= 0) {
            return SampleResult(null, "EmptyPixels")
        }

        // 计算实际像素区域
        val left = (region.left * width).toInt().coerceIn(0, width - 1)
        val right = (region.right * width).toInt().coerceIn(0, width - 1)
        val top = (region.top * height).toInt().coerceIn(0, height - 1)
        val bottom = (region.bottom * height).toInt().coerceIn(0, height - 1)

        if (left >= right || top >= bottom) {
            return SampleResult(null, "InvalidRegion")
        }

        // 在区域内采样 5x5 网格
        var sum = 0f
        var count = 0
        for (row in 0 until 5) {
            val y = top + ((row + 0.5f) / 5f * (bottom - top)).toInt()
            for (col in 0 until 5) {
                val x = left + ((col + 0.5f) / 5f * (right - left)).toInt()
                if (x in 0 until width && y in 0 until height) {
                    sum += pixelMap[x, y].luminance()
                    count += 1
                }
            }
        }

        if (count == 0) {
            SampleResult(null, "NoSamples")
        } else {
            SampleResult(sum / count, "Live")
        }
    }.getOrElse {
        SampleResult(null, "PixelErr")
    }
}
