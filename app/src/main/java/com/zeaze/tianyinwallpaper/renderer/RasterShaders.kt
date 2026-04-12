package com.zeaze.tianyinwallpaper.renderer

/**
 * GLSL 着色器代码集合
 * 将所有 shader 源码集中管理，便于维护和复用
 */
object RasterShaders {

    // ── 顶点着色器 ──

    /** 单纹理顶点着色器 */
    const val SINGLE_VERTEX = """
        attribute vec4 aPos;
        attribute vec2 aTex;
        varying vec2 vTex;
        uniform mat4 uMVP;
        uniform mat4 uST;
        void main() {
            gl_Position = uMVP * aPos;
            vTex = (uST * vec4(aTex, 0.0, 1.0)).xy;
        }
    """

    /** 双纹理过渡顶点着色器 */
    const val TRANSITION_VERTEX = """
        attribute vec4 aPos;
        attribute vec2 aTex;
        varying vec2 vTexA;
        varying vec2 vTexB;
        uniform mat4 uMVP;
        uniform mat4 uSTA;
        uniform mat4 uSTB;
        void main() {
            gl_Position = uMVP * aPos;
            vTexA = (uSTA * vec4(aTex, 0.0, 1.0)).xy;
            vTexB = (uSTB * vec4(aTex, 0.0, 1.0)).xy;
        }
    """

    // ── 片段着色器 ──

    /** 单纹理片段着色器 */
    const val SINGLE_FRAGMENT = """
        precision mediump float;
        varying vec2 vTex;
        uniform sampler2D sTex;
        void main() {
            gl_FragColor = texture2D(sTex, vTex);
        }
    """

    /** 标准扫描线过渡片段着色器 */
    const val STANDARD_FRAGMENT = """
        precision mediump float;
        varying vec2 vTexA;
        varying vec2 vTexB;
        uniform sampler2D sTexA;
        uniform sampler2D sTexB;
        uniform float uProgress;
        uniform float uDirection;
        uniform float uEdgeSoftness;
        uniform float uScreenWidth;
        uniform float uFadeAlpha;

        void main() {
            vec4 colorA = texture2D(sTexA, vTexA);
            vec4 colorB = texture2D(sTexB, vTexB);

            float coord = gl_FragCoord.x / uScreenWidth;
            float blend;

            if (uDirection > 0.0) {
                float edge = 1.0 - uProgress;
                blend = smoothstep(edge - uEdgeSoftness, edge + uEdgeSoftness, coord);
                gl_FragColor = mix(colorA, colorB, blend);
            } else {
                float edge = uProgress;
                blend = smoothstep(edge - uEdgeSoftness, edge + uEdgeSoftness, coord);
                gl_FragColor = mix(colorB, colorA, blend);
            }
            gl_FragColor.a = uFadeAlpha;
        }
    """

    /**
     * 条纹玻璃 + 扫描线过渡组合片段着色器
     *
     * 在标准扫描线过渡的基础上，在扫描线附近区域叠加条纹玻璃折射效果。
     * 使用 Corrugated（瓦楞/正弦波）截面，与原 AGSL 版本保持一致。
     *
     * 坐标体系说明：
     * - vTexA / vTexB 是经裁剪/缩放变换后的归一化纹理坐标 [0,1]
     * - uAmplitude / uWavelength 是归一化坐标单位下的偏移量和波长
     * - 折射偏移直接加在纹理坐标上
     *
     * Uniforms:
     * - uProgress        扫描线进度 [0,1]
     * - uDirection       扫描方向 (>0 从左到右, <0 从右到左)
     * - uEdgeSoftness    边缘柔化程度
     * - uScreenWidth     屏幕宽度（像素）
     * - uScreenHeight    屏幕高度（像素）
     * - uAmplitude       折射振幅（归一化坐标单位）
     * - uWavelength      条纹波长（归一化坐标单位）
     * - uHighlightStrength 高光强度
     * - uShadowStrength  阴影强度
     * - uColorSaturation 色彩饱和度
     * - uColorContrast   色彩对比度
     * - uGlassFullWidth  禁用透明遮罩（1.0=全屏玻璃，0.0=仅扫描线附近）
     */
    // ── 玻璃效果共用代码块 ──

    /**
     * 玻璃效果的扫描线过渡公共头部
     * 包含：uniform 声明、mirror1 辅助函数、扫描线过渡计算、玻璃区域遮罩
     */
    private const val GLASS_COMMON_HEADER = """
        precision mediump float;
        varying vec2 vTexA;
        varying vec2 vTexB;
        uniform sampler2D sTexA;
        uniform sampler2D sTexB;
        uniform float uProgress;
        uniform float uDirection;
        uniform float uEdgeSoftness;
        uniform float uScreenWidth;
        uniform float uScreenHeight;
        uniform float uAmplitude;
        uniform float uWavelength;
        uniform float uHighlightStrength;
        uniform float uShadowStrength;
        uniform float uColorSaturation;
        uniform float uColorContrast;
        uniform float uGlassFullWidth;
        uniform float uGlassBandWidth;
        uniform float uPhase;
        uniform float uFadeAlpha;

        const float PI = 3.14159265;
        const float TWO_PI = 6.28318530;

        float mirror1(float c) {
            float mc = mod(c, 2.0);
            if (mc < 0.0) mc += 2.0;
            return mc > 1.0 ? 2.0 - mc : mc;
        }

        // 双纹理采样混合（根据方向交换 A/B）
        vec3 sampleBlended(vec2 texCoord, float blend) {
            float rA = texture2D(sTexA, texCoord).r;
            float gA = texture2D(sTexA, texCoord).g;
            float bA = texture2D(sTexA, texCoord).b;
            float rB = texture2D(sTexB, texCoord).r;
            float gB = texture2D(sTexB, texCoord).g;
            float bB = texture2D(sTexB, texCoord).b;
            return (uDirection > 0.0)
                ? vec3(mix(rA, rB, blend), mix(gA, gB, blend), mix(bA, bB, blend))
                : vec3(mix(rB, rA, blend), mix(gB, gA, blend), mix(bB, bA, blend));
        }

        float sampleAlphaBlended(vec2 texCoord, float blend) {
            float aA = texture2D(sTexA, texCoord).a;
            float aB = texture2D(sTexB, texCoord).a;
            return (uDirection > 0.0) ? mix(aA, aB, blend) : mix(aB, aA, blend);
        }

        // 色彩补偿 + 光影后处理
        vec3 applyColorAndLight(vec3 c, float wave, float slope) {
            c = (c - vec3(0.5)) * uColorContrast + vec3(0.5);
            float lum = dot(c, vec3(0.299, 0.587, 0.114));
            c = mix(vec3(lum), c, uColorSaturation);
            c = clamp(c, 0.0, 1.0);

            float peak = pow(max(0.0, wave), 3.0) * uHighlightStrength;
            float valley = max(0.0, -wave);
            float edge = abs(slope) * (uShadowStrength * 0.3);
            c = c * mix(1.0, 1.0 - uShadowStrength, valley);
            c = c - vec3(edge);
            c = clamp(c + vec3(peak), 0.0, 1.0);
            c = c * 0.92 + 0.08;
            return c;
        }
    """

    /** 玻璃效果的扫描线过渡 + 遮罩入口（不含折射核心） */
    private const val GLASS_COMMON_BODY = """
        void main() {
            vec4 colorA = texture2D(sTexA, vTexA);
            vec4 colorB = texture2D(sTexB, vTexB);

            float coord = gl_FragCoord.x / uScreenWidth;
            float blend;

            if (uDirection > 0.0) {
                float edge = 1.0 - uProgress;
                blend = smoothstep(edge - uEdgeSoftness, edge + uEdgeSoftness, coord);
            } else {
                float edge = uProgress;
                blend = smoothstep(edge - uEdgeSoftness, edge + uEdgeSoftness, coord);
            }

            vec4 baseColor = (uDirection > 0.0)
                ? mix(colorA, colorB, blend)
                : mix(colorB, colorA, blend);

            float lineX = (uDirection > 0.0) ? (1.0 - uProgress) : uProgress;
            float bandWidth = uGlassBandWidth;
            float distFromLine = abs(coord - lineX);
            float glassAlpha = mix(
                smoothstep(bandWidth, bandWidth * 0.1, distFromLine),
                1.0,
                uGlassFullWidth
            );

            if (glassAlpha > 0.01) {
                vec2 baseTex = (uDirection > 0.0)
                    ? mix(vTexA, vTexB, blend)
                    : mix(vTexB, vTexA, blend);
    """

    /** 玻璃效果的公共结尾 */
    private const val GLASS_COMMON_FOOTER = """
                vec4 glassResult = vec4(refractedColor, refAlpha);
                vec4 transitionResult = mix(baseColor, glassResult, glassAlpha);
                gl_FragColor = vec4(transitionResult.rgb, uFadeAlpha);
            } else {
                gl_FragColor = vec4(baseColor.rgb, uFadeAlpha);
            }
        }
    """

    // ── 具体玻璃截面 shader ──

    /**
     * 波纹玻璃 (Corrugated) — 标准正弦波截面
     * 对应 AGSL GlassProfile.Corrugated
     */
    const val CORRUGATED_GLASS_FRAGMENT = GLASS_COMMON_HEADER + GLASS_COMMON_BODY + """
                // Corrugated（正弦波）折射计算
                float phaseNorm = uPhase / TWO_PI;
                float waveInput = baseTex.x * (2.0 * PI / uWavelength) + uPhase;
                float wave = cos(waveInput);
                float slope = sin(waveInput);
                float offset = wave * uAmplitude;

                float chromatic = 0.08;
                vec2 sR = vec2(mirror1(baseTex.x + offset * (1.0 + chromatic)), baseTex.y);
                vec2 sG = vec2(mirror1(baseTex.x + offset),                     baseTex.y);
                vec2 sB = vec2(mirror1(baseTex.x + offset * (1.0 - chromatic)), baseTex.y);

                vec3 refractedColor = vec3(
                    sampleBlended(sR, blend).r,
                    sampleBlended(sG, blend).g,
                    sampleBlended(sB, blend).b
                );
                float refAlpha = sampleAlphaBlended(sG, blend);

                refractedColor = applyColorAndLight(refractedColor, wave, slope);
    """ + GLASS_COMMON_FOOTER

    /**
     * 长虹玻璃 (Reeded) — 等宽半圆柱阵列
     * 对应 AGSL GlassProfile.Reeded
     *
     * 额外 Uniforms:
     * - 无（使用公共 uAmplitude / uWavelength）
     */
    const val REEDED_GLASS_FRAGMENT = GLASS_COMMON_HEADER + GLASS_COMMON_BODY + """
                // Reeded（长虹/半圆柱）折射计算
                float phaseNorm = uPhase / TWO_PI;
                float localPos = fract(baseTex.x / uWavelength + phaseNorm);
                float localX = localPos * 2.0 - 1.0;

                float x = clamp(localX, -0.995, 0.995);
                float z = sqrt(1.0 - x * x);
                float offset = x * uAmplitude;  // 法线 x 分量 * 振幅

                // 计算光影参数
                float wave = z;   // z 分量作为高度（波峰）
                float slope = x;  // x 分量作为斜率

                float chromatic = 0.08;
                vec2 sR = vec2(mirror1(baseTex.x + offset * (1.0 + chromatic)), baseTex.y);
                vec2 sG = vec2(mirror1(baseTex.x + offset),                     baseTex.y);
                vec2 sB = vec2(mirror1(baseTex.x + offset * (1.0 - chromatic)), baseTex.y);

                vec3 refractedColor = vec3(
                    sampleBlended(sR, blend).r,
                    sampleBlended(sG, blend).g,
                    sampleBlended(sB, blend).b
                );
                float refAlpha = sampleAlphaBlended(sG, blend);

                // 色彩补偿
                refractedColor = (refractedColor - vec3(0.5)) * uColorContrast + vec3(0.5);
                float lum = dot(refractedColor, vec3(0.299, 0.587, 0.114));
                refractedColor = mix(vec3(lum), refractedColor, uColorSaturation);
                refractedColor = clamp(refractedColor, 0.0, 1.0);

                // Reeded 光影：接缝处环境光遮蔽 + 高光
                float edgeDist = abs(localX);
                float ao = smoothstep(1.0, 0.8, edgeDist);
                float edgeDarken = smoothstep(0.7, 1.0, edgeDist) * (uShadowStrength * 0.6);
                vec3 norm = normalize(vec3(x, 0.0, z));
                vec3 lightDir = normalize(vec3(0.4, 0.0, 0.9));
                float specPower = pow(max(dot(norm, lightDir), 0.0), 12.0);
                float highlight = specPower * uHighlightStrength;

                refractedColor = refractedColor * mix(1.0 - uShadowStrength, 1.0, ao);
                refractedColor = refractedColor - vec3(edgeDarken);
                refractedColor = clamp(refractedColor + vec3(highlight), 0.0, 1.0);
                refractedColor = refractedColor * 0.92 + 0.08;
    """ + GLASS_COMMON_FOOTER

    /**
     * 棱镜玻璃 (Prism) — 宽窄复合交替阵列
     * 对应 AGSL GlassProfile.Prism
     *
     * 额外 Uniforms:
     * - uNarrowAmplitude   窄波振幅（归一化坐标单位）
     * - uNarrowWavelength  窄波波长（归一化坐标单位）
     */
    const val PRISM_GLASS_FRAGMENT = """
        precision mediump float;
        varying vec2 vTexA;
        varying vec2 vTexB;
        uniform sampler2D sTexA;
        uniform sampler2D sTexB;
        uniform float uProgress;
        uniform float uDirection;
        uniform float uEdgeSoftness;
        uniform float uScreenWidth;
        uniform float uScreenHeight;
        uniform float uAmplitude;
        uniform float uWavelength;
        uniform float uHighlightStrength;
        uniform float uShadowStrength;
        uniform float uColorSaturation;
        uniform float uColorContrast;
        uniform float uGlassFullWidth;
        uniform float uGlassBandWidth;
        uniform float uPhase;
        uniform float uFadeAlpha;
        uniform float uNarrowAmplitude;
        uniform float uNarrowWavelength;

        const float PI = 3.14159265;
        const float TWO_PI = 6.28318530;

        float mirror1(float c) {
            float mc = mod(c, 2.0);
            if (mc < 0.0) mc += 2.0;
            return mc > 1.0 ? 2.0 - mc : mc;
        }

        vec3 sampleBlended(vec2 texCoord, float blend) {
            float rA = texture2D(sTexA, texCoord).r;
            float gA = texture2D(sTexA, texCoord).g;
            float bA = texture2D(sTexA, texCoord).b;
            float rB = texture2D(sTexB, texCoord).r;
            float gB = texture2D(sTexB, texCoord).g;
            float bB = texture2D(sTexB, texCoord).b;
            return (uDirection > 0.0)
                ? vec3(mix(rA, rB, blend), mix(gA, gB, blend), mix(bA, bB, blend))
                : vec3(mix(rB, rA, blend), mix(gB, gA, blend), mix(bB, bA, blend));
        }

        float sampleAlphaBlended(vec2 texCoord, float blend) {
            float aA = texture2D(sTexA, texCoord).a;
            float aB = texture2D(sTexB, texCoord).a;
            return (uDirection > 0.0) ? mix(aA, aB, blend) : mix(aB, aA, blend);
        }

        void main() {
            vec4 colorA = texture2D(sTexA, vTexA);
            vec4 colorB = texture2D(sTexB, vTexB);

            float coord = gl_FragCoord.x / uScreenWidth;
            float blend;

            if (uDirection > 0.0) {
                float edge = 1.0 - uProgress;
                blend = smoothstep(edge - uEdgeSoftness, edge + uEdgeSoftness, coord);
            } else {
                float edge = uProgress;
                blend = smoothstep(edge - uEdgeSoftness, edge + uEdgeSoftness, coord);
            }

            vec4 baseColor = (uDirection > 0.0)
                ? mix(colorA, colorB, blend)
                : mix(colorB, colorA, blend);

            float lineX = (uDirection > 0.0) ? (1.0 - uProgress) : uProgress;
            float bandWidth = uGlassBandWidth;
            float distFromLine = abs(coord - lineX);
            float glassAlpha = mix(
                smoothstep(bandWidth, bandWidth * 0.1, distFromLine),
                1.0,
                uGlassFullWidth
            );

            if (glassAlpha > 0.01) {
                vec2 baseTex = (uDirection > 0.0)
                    ? mix(vTexA, vTexB, blend)
                    : mix(vTexB, vTexA, blend);

                // Prism：宽波与窄波组合成一个大周期
                float phaseNorm = uPhase / TWO_PI;
                float totalL = uWavelength + uNarrowWavelength;
                float localPos = fract(baseTex.x / totalL + phaseNorm) * totalL;

                float localX;
                float currentAmp;

                if (localPos < uWavelength) {
                    localX = (localPos / uWavelength) * 2.0 - 1.0;
                    currentAmp = uAmplitude;
                } else {
                    localX = ((localPos - uWavelength) / uNarrowWavelength) * 2.0 - 1.0;
                    currentAmp = uNarrowAmplitude;
                }

                float x = clamp(localX, -0.995, 0.995);
                float z = sqrt(1.0 - x * x);
                float offset = x * currentAmp;

                float chromatic = 0.08;
                vec2 sR = vec2(mirror1(baseTex.x + offset * (1.0 + chromatic)), baseTex.y);
                vec2 sG = vec2(mirror1(baseTex.x + offset),                     baseTex.y);
                vec2 sB = vec2(mirror1(baseTex.x + offset * (1.0 - chromatic)), baseTex.y);

                vec3 refractedColor = vec3(
                    sampleBlended(sR, blend).r,
                    sampleBlended(sG, blend).g,
                    sampleBlended(sB, blend).b
                );
                float refAlpha = sampleAlphaBlended(sG, blend);

                // 色彩补偿
                refractedColor = (refractedColor - vec3(0.5)) * uColorContrast + vec3(0.5);
                float lum = dot(refractedColor, vec3(0.299, 0.587, 0.114));
                refractedColor = mix(vec3(lum), refractedColor, uColorSaturation);
                refractedColor = clamp(refractedColor, 0.0, 1.0);

                // Prism 光影：与 Reeded 相同的接缝遮蔽 + 高光
                float edgeDist = abs(localX);
                float ao = smoothstep(1.0, 0.8, edgeDist);
                float edgeDarken = smoothstep(0.7, 1.0, edgeDist) * (uShadowStrength * 0.6);
                vec3 norm = normalize(vec3(x, 0.0, z));
                vec3 lightDir = normalize(vec3(0.4, 0.0, 0.9));
                float specPower = pow(max(dot(norm, lightDir), 0.0), 12.0);
                float highlight = specPower * uHighlightStrength;

                refractedColor = refractedColor * mix(1.0 - uShadowStrength, 1.0, ao);
                refractedColor = refractedColor - vec3(edgeDarken);
                refractedColor = clamp(refractedColor + vec3(highlight), 0.0, 1.0);
                refractedColor = refractedColor * 0.92 + 0.08;

                vec4 glassResult = vec4(refractedColor, refAlpha);
                vec4 transitionResult = mix(baseColor, glassResult, glassAlpha);
                gl_FragColor = vec4(transitionResult.rgb, uFadeAlpha);
            } else {
                gl_FragColor = vec4(baseColor.rgb, uFadeAlpha);
            }
        }
    """

    /**
     * 条纹玻璃 + 扫描线过渡组合片段着色器（旧版，保留兼容）
     * @deprecated 使用 CORRUGATED_GLASS_FRAGMENT 替代
     */
    const val STRIPED_GLASS_FRAGMENT = CORRUGATED_GLASS_FRAGMENT
}
