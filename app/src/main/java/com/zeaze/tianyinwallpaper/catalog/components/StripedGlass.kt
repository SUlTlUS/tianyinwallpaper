package com.zeaze.tianyinwallpaper.catalog.components

import android.graphics.RenderEffect
import android.os.Build
import androidx.annotation.FloatRange
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.isSpecified
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.zeaze.tianyinwallpaper.backdrop.Backdrop
import com.zeaze.tianyinwallpaper.backdrop.BackdropEffectScope
import com.zeaze.tianyinwallpaper.backdrop.drawPlainBackdrop
import com.zeaze.tianyinwallpaper.backdrop.effects.blur
import com.zeaze.tianyinwallpaper.backdrop.highlight.effect
import org.intellij.lang.annotations.Language
import kotlin.math.PI

private const val TWO_PI = (PI * 2.0).toFloat()

private enum class WaveVector(val x: Float, val y: Float) {
    Horizontal(0f, 1f),
    Vertical(1f, 0f)
}

enum class StripedDirection {
    Horizontal,
    Vertical
}

/**
 * 玻璃的物理截面类型
 */
enum class GlassProfile {
    /** 长虹玻璃：等宽的半圆柱体，交界处有接缝 */
    Reeded,
    /** 瓦楞玻璃：标准的连续平滑正弦波浪 */
    Corrugated,
    /** 棱镜复合玻璃：一个宽波间隔一个窄波，交替排列，层次感极强 */
    Prism
}

@Composable
fun rememberStripedPhase(
    enabled: Boolean,
    periodMillis: Int = 2400,
    isReverse: Boolean = false
): Float {
    if (!enabled) return 0f

    var phase by remember { mutableFloatStateOf(0f) }
    val currentPeriod by rememberUpdatedState(periodMillis)
    val currentReverse by rememberUpdatedState(isReverse)

    LaunchedEffect(Unit) {
        var lastTime = withFrameMillis { it }
        while (true) {
            val currentTime = withFrameMillis { it }
            val delta = currentTime - lastTime
            lastTime = currentTime
            val p = currentPeriod
            if (p > 0) {
                val step = (delta / p.toFloat()) * TWO_PI
                phase = if (currentReverse) {
                    (phase - step) % TWO_PI
                } else {
                    (phase + step) % TWO_PI
                }
                if (phase < 0) phase += TWO_PI
            }
        }
    }
    return phase
}

/**
 * 综合型条纹玻璃 (Striped Glass) 组件
 *
 * @param profile           玻璃截面类型（Reeded / Corrugated / Prism）
 * @param amplitude         【宽波/主波】折射振幅（背景偏移像素数，建议 8.dp ~ 24.dp）
 * @param wavelength        【宽波/主波】宽度（像素周期）
 * @param narrowAmplitude   【仅 Prism 模式】窄波的折射振幅（建议比主波小）
 * @param narrowWavelength  【仅 Prism 模式】窄波的宽度（建议比主波窄）
 * @param blurRadius        基础背景模糊半径
 * @param chromaticStrength 色差强度 [0.0, 0.3] (RGB 色散边缘)
 * @param highlightStrength 高光强度 [0.0, 1.0]
 * @param shadowStrength    阴影强度 [0.0, 1.0]
 * @param colorSaturation   色彩饱和度，默认 1.2
 * @param colorContrast     色彩对比度，默认 1.1
 */
@Composable
fun StripedGlass(
    backdrop: Backdrop,
    modifier: Modifier = Modifier,
    shape: Shape,
    profile: GlassProfile = GlassProfile.Prism, // 默认使用 Prism 展示高级效果
    amplitude: Dp = 16.dp,
    wavelength: Dp = 32.dp,
    narrowAmplitude: Dp = 6.dp,
    narrowWavelength: Dp = 12.dp,
    phase: Float = 0f,
    direction: StripedDirection = StripedDirection.Vertical,
    blurRadius: Dp = 6.dp,
    @FloatRange(from = 0.0, to = 0.3) chromaticStrength: Float = 0.08f,
    @FloatRange(from = 0.0, to = 1.0) highlightStrength: Float = 0.4f,
    @FloatRange(from = 0.0, to = 1.0) shadowStrength: Float = 0.25f,
    @FloatRange(from = 0.0) colorSaturation: Float = 1.2f,
    @FloatRange(from = 0.0) colorContrast: Float = 1.1f,
    tint: Color = Color.Unspecified,
    onDrawSurface: (DrawScope.() -> Unit)? = null,
    content: @Composable BoxScope.() -> Unit
) {
    Box(
        modifier = modifier.stripedGlass(
            backdrop = backdrop,
            shape = { shape },
            profile = profile,
            amplitude = amplitude,
            wavelength = wavelength,
            narrowAmplitude = narrowAmplitude,
            narrowWavelength = narrowWavelength,
            phase = phase,
            direction = direction,
            blurRadius = blurRadius,
            chromaticStrength = chromaticStrength,
            highlightStrength = highlightStrength,
            shadowStrength = shadowStrength,
            colorSaturation = colorSaturation,
            colorContrast = colorContrast,
            tint = tint,
            onDrawSurface = onDrawSurface
        ),
        content = content
    )
}

/**
 * 通用的渐变遮罩 Modifier
 * 能够让组件的某一部分平滑地消失（变透明）
 */
fun Modifier.fadeMask(brush: Brush): Modifier = this
    // 强制开启离屏渲染缓冲，这是使用 BlendMode 擦除像素的前提条件
    .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
    .drawWithContent {
        // 先画出原本的组件内容（例如玻璃）
        drawContent()
        // 使用画笔的 Alpha 通道进行擦除（DstIn: 只保留与画笔相交且有透明度的部分）
        drawRect(brush = brush, blendMode = BlendMode.DstIn)
    }

fun Modifier.stripedGlass(
    backdrop: Backdrop,
    shape: () -> Shape,
    profile: GlassProfile = GlassProfile.Prism,
    amplitude: Dp = 16.dp,
    wavelength: Dp = 32.dp,
    narrowAmplitude: Dp = 6.dp,
    narrowWavelength: Dp = 12.dp,
    phase: Float = 0f,
    direction: StripedDirection = StripedDirection.Vertical,
    blurRadius: Dp = 6.dp,
    @FloatRange(from = 0.0, to = 0.3) chromaticStrength: Float = 0.08f,
    @FloatRange(from = 0.0, to = 1.0) highlightStrength: Float = 0.4f,
    @FloatRange(from = 0.0, to = 1.0) shadowStrength: Float = 0.25f,
    @FloatRange(from = 0.0) colorSaturation: Float = 1.2f,
    @FloatRange(from = 0.0) colorContrast: Float = 1.1f,
    tint: Color = Color.Unspecified,
    onDrawSurface: (DrawScope.() -> Unit)? = null
): Modifier {
    return this.drawPlainBackdrop(
        backdrop = backdrop,
        shape = shape,
        effects = {
            val blurRadiusPx = blurRadius.toPx()
            if (blurRadiusPx > 0f) {
                blur(blurRadiusPx)
            }
            stripedRefraction(
                profile = profile,
                amplitudePx = amplitude.toPx(),
                wavelengthPx = wavelength.toPx(),
                narrowAmplitudePx = narrowAmplitude.toPx(),
                narrowWavelengthPx = narrowWavelength.toPx(),
                phase = phase,
                direction = direction,
                chromaticStrength = chromaticStrength,
                highlightStrength = highlightStrength,
                shadowStrength = shadowStrength,
                colorSaturation = colorSaturation,
                colorContrast = colorContrast
            )
        },
        onDrawSurface = {
            if (tint.isSpecified) {
                drawRect(tint)
            }
            onDrawSurface?.invoke(this)
        }
    )
}

private fun BackdropEffectScope.stripedRefraction(
    profile: GlassProfile,
    @FloatRange(from = 0.0) amplitudePx: Float,
    @FloatRange(from = 1.0) wavelengthPx: Float,
    @FloatRange(from = 0.0) narrowAmplitudePx: Float,
    @FloatRange(from = 1.0) narrowWavelengthPx: Float,
    phase: Float,
    direction: StripedDirection,
    @FloatRange(from = 0.0, to = 0.3) chromaticStrength: Float,
    @FloatRange(from = 0.0, to = 1.0) highlightStrength: Float,
    @FloatRange(from = 0.0, to = 1.0) shadowStrength: Float,
    @FloatRange(from = 0.0) colorSaturation: Float,
    @FloatRange(from = 0.0) colorContrast: Float
) {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
    if (amplitudePx <= 0f) return

    val clampedWavelength = wavelengthPx.coerceAtLeast(1f)
    val clampedNarrowWavelength = narrowWavelengthPx.coerceAtLeast(1f)
    val waveVector = when (direction) {
        StripedDirection.Horizontal -> WaveVector.Horizontal
        StripedDirection.Vertical -> WaveVector.Vertical
    }

    val shaderString = when (profile) {
        GlassProfile.Reeded -> ReededRefractionShaderString
        GlassProfile.Corrugated -> CorrugatedRefractionShaderString
        GlassProfile.Prism -> PrismRefractionShaderString
    }

    val shaderName = "${profile.name}Refraction"

    val shader = obtainRuntimeShader(shaderName, shaderString).apply {
        setFloatUniform("size", size.width, size.height)
        setFloatUniform("amplitude", amplitudePx)
        setFloatUniform("wavelength", clampedWavelength)
        setFloatUniform("phase", phase)
        setFloatUniform("waveAxis", waveVector.x, waveVector.y)
        setFloatUniform("chromaticStrength", chromaticStrength)
        setFloatUniform("highlightStrength", highlightStrength)
        setFloatUniform("shadowStrength", shadowStrength)
        setFloatUniform("colorSaturation", colorSaturation)
        setFloatUniform("colorContrast", colorContrast)

        // 只有 Prism 模式会用到这两个变量
        if (profile == GlassProfile.Prism) {
            setFloatUniform("narrowAmp", narrowAmplitudePx)
            setFloatUniform("narrowWave", clampedNarrowWavelength)
        }
    }

    effect(RenderEffect.createRuntimeShaderEffect(shader, "content"))
}

// ────────────────────────────────────────────────────────────────────────────
// Shader 共通辅助函数 (在编译时组合)
// ────────────────────────────────────────────────────────────────────────────
@Language("AGSL")
private const val ShaderCommon = """
uniform shader content;
uniform float2 size;
uniform float amplitude;
uniform float wavelength;
uniform float phase;
uniform float2 waveAxis;
uniform float chromaticStrength;
uniform float highlightStrength;
uniform float shadowStrength;
uniform float colorSaturation;
uniform float colorContrast;

const float PI = 3.14159265;

// 完美的镜像防越界算法，解决边缘拉长和蓝线色差问题
float2 mirror(float2 c, float2 s) {
    float2 maxC = s - float2(1.0);
    return maxC - abs(maxC - abs(c));
}
"""

// ============================================================================
// Shader 1: Reeded Glass (长虹/等宽半圆柱阵列)
// ============================================================================
@Language("AGSL")
private const val ReededRefractionShaderString = ShaderCommon + """
half4 main(float2 coord) {
    float pos = dot(coord, waveAxis);
    float phaseNorm = phase / (2.0 * PI);
    
    float localPos = fract((pos / wavelength) + phaseNorm);
    float localX = localPos * 2.0 - 1.0;
    
    float x = clamp(localX, -0.995, 0.995);
    float z = sqrt(1.0 - x * x);
    float3 normal = normalize(float3(x, 0.0, z));
    
    float offset = normal.x * amplitude;
    
    float rOffset = offset * (1.0 + chromaticStrength);
    float gOffset = offset;
    float bOffset = offset * (1.0 - chromaticStrength);
    
    float2 coordR = mirror(coord + waveAxis * rOffset, size);
    float2 coordG = mirror(coord + waveAxis * gOffset, size);
    float2 coordB = mirror(coord + waveAxis * bOffset, size);
    
    half4 color = half4(
        content.eval(coordR).r,
        content.eval(coordG).g,
        content.eval(coordB).b,
        content.eval(coordG).a
    );

    color.rgb = (color.rgb - half3(0.5)) * colorContrast + half3(0.5);
    float luminance = dot(color.rgb, half3(0.299, 0.587, 0.114));
    color.rgb = mix(half3(luminance), color.rgb, colorSaturation);
    color.rgb = clamp(color.rgb, half3(0.0), half3(1.0));
    
    float edgeDist = abs(localX);
    float ao = smoothstep(1.0, 0.8, edgeDist);
    float edgeDarken = smoothstep(0.7, 1.0, edgeDist) * (shadowStrength * 0.6);
    
    float3 lightDir = normalize(float3(0.4, 0.0, 0.9)); 
    float specPower = pow(max(dot(normal, lightDir), 0.0), 12.0);
    float highlight = specPower * highlightStrength;
    
    color.rgb = color.rgb * mix(1.0 - shadowStrength, 1.0, ao);
    color.rgb = color.rgb - half3(edgeDarken);
    color.rgb = clamp(color.rgb + half3(highlight), half3(0.0), half3(1.0));
    
    return color;
}
"""

// ============================================================================
// Shader 2: Corrugated Glass (瓦楞/标准正弦波)
// ============================================================================
@Language("AGSL")
private const val CorrugatedRefractionShaderString = ShaderCommon + """
half4 main(float2 coord) {
    float waveInput = dot(coord, waveAxis) * (2.0 * PI / wavelength) + phase;
    
    float wave = cos(waveInput);
    float slope = sin(waveInput);

    float offset = wave * amplitude;

    float rOffset = offset * (1.0 + chromaticStrength);
    float gOffset = offset;
    float bOffset = offset * (1.0 - chromaticStrength);

    float2 coordR = mirror(coord + waveAxis * rOffset, size);
    float2 coordG = mirror(coord + waveAxis * gOffset, size);
    float2 coordB = mirror(coord + waveAxis * bOffset, size);

    half4 color = half4(
        content.eval(coordR).r,
        content.eval(coordG).g,
        content.eval(coordB).b,
        content.eval(coordG).a
    );

    color.rgb = (color.rgb - half3(0.5)) * colorContrast + half3(0.5);
    float luminance = dot(color.rgb, half3(0.299, 0.587, 0.114));
    color.rgb = mix(half3(luminance), color.rgb, colorSaturation);
    color.rgb = clamp(color.rgb, half3(0.0), half3(1.0));

    float peakHighlight = pow(max(0.0, wave), 3.0) * highlightStrength;
    float valleyDepth = max(0.0, -wave);
    float edgeDarken = abs(slope) * (shadowStrength * 0.3);

    color.rgb = color.rgb * mix(1.0, 1.0 - shadowStrength, valleyDepth);
    color.rgb = color.rgb - half3(edgeDarken);
    color.rgb = clamp(color.rgb + half3(peakHighlight), half3(0.0), half3(1.0));

    return color;
}
"""

// ============================================================================
// Shader 3: Prism Glass (棱镜/宽窄复合交替阵列) - 高级感最强
// ============================================================================
@Language("AGSL")
private const val PrismRefractionShaderString = ShaderCommon + """
uniform float narrowAmp;
uniform float narrowWave;

half4 main(float2 coord) {
    float pos = dot(coord, waveAxis);
    float phaseNorm = phase / (2.0 * PI);
    
    // 宽波与窄波组合成一个大周期 L
    float totalL = wavelength + narrowWave;
    
    // 获取当前坐标在大周期内的相对位置 [0, totalL)
    float localPos = fract((pos / totalL) + phaseNorm) * totalL;
    
    float localX;
    float currentAmp;
    
    // 动态判断当前落在【宽波】还是【窄波】的区间
    if (localPos < wavelength) {
        // 在宽波范围内，归一化到 [-1, 1]
        localX = (localPos / wavelength) * 2.0 - 1.0;
        currentAmp = amplitude;
    } else {
        // 在窄波范围内，归一化到 [-1, 1]
        localX = ((localPos - wavelength) / narrowWave) * 2.0 - 1.0;
        currentAmp = narrowAmp;
    }
    
    // 无论宽窄，统一应用物理半圆柱体法线计算
    float x = clamp(localX, -0.995, 0.995);
    float z = sqrt(1.0 - x * x);
    float3 normal = normalize(float3(x, 0.0, z));
    
    // 动态应用当前的振幅
    float offset = normal.x * currentAmp;
    
    float rOffset = offset * (1.0 + chromaticStrength);
    float gOffset = offset;
    float bOffset = offset * (1.0 - chromaticStrength);
    
    float2 coordR = mirror(coord + waveAxis * rOffset, size);
    float2 coordG = mirror(coord + waveAxis * gOffset, size);
    float2 coordB = mirror(coord + waveAxis * bOffset, size);
    
    half4 color = half4(
        content.eval(coordR).r,
        content.eval(coordG).g,
        content.eval(coordB).b,
        content.eval(coordG).a
    );

    // 色彩补偿
    color.rgb = (color.rgb - half3(0.5)) * colorContrast + half3(0.5);
    float luminance = dot(color.rgb, half3(0.299, 0.587, 0.114));
    color.rgb = mix(half3(luminance), color.rgb, colorSaturation);
    color.rgb = clamp(color.rgb, half3(0.0), half3(1.0));
    
    // 光影完美无缝连接
    // 当 localX 逼近 -1 或 1 时（无论是宽波边缘还是窄波边缘），都会自然产生深槽阴影
    float edgeDist = abs(localX);
    float ao = smoothstep(1.0, 0.8, edgeDist);
    float edgeDarken = smoothstep(0.7, 1.0, edgeDist) * (shadowStrength * 0.6);
    
    float3 lightDir = normalize(float3(0.4, 0.0, 0.9)); 
    float specPower = pow(max(dot(normal, lightDir), 0.0), 12.0);
    float highlight = specPower * highlightStrength;
    
    color.rgb = color.rgb * mix(1.0 - shadowStrength, 1.0, ao);
    color.rgb = color.rgb - half3(edgeDarken);
    color.rgb = clamp(color.rgb + half3(highlight), half3(0.0), half3(1.0));
    
    return color;
}
"""