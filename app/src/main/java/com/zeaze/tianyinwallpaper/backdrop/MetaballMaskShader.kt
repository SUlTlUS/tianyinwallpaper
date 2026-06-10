package com.zeaze.tianyinwallpaper.backdrop

const val MetaballMaskShaderString = """
uniform shader content;
uniform float2 size;
uniform float2 centerA;
uniform float2 radiusA;
uniform float2 centerB;
uniform float2 radiusB;
uniform float smoothness;
uniform float opacity;
uniform float neckOnly;

float sdEllipse(float2 p, float2 c, float2 r) {
    float2 q = (p - c) / max(r, float2(1.0, 1.0));
    return (length(q) - 1.0) * min(r.x, r.y);
}

float smoothUnion(float d1, float d2, float k) {
    float h = clamp(0.5 + 0.5 * (d2 - d1) / max(k, 0.001), 0.0, 1.0);
    return mix(d2, d1, h) - k * h * (1.0 - h);
}

half4 main(float2 coord) {
    float dA = sdEllipse(coord, centerA, radiusA);
    float dB = sdEllipse(coord, centerB, radiusB);
    float dUnion = smoothUnion(dA, dB, smoothness);

    float edge = max(1.25, min(size.x, size.y) * 0.01);
    float maskA = 1.0 - smoothstep(-edge, edge, dA);
    float maskB = 1.0 - smoothstep(-edge, edge, dB);
    float unionMask = 1.0 - smoothstep(-edge, edge, dUnion);

    // 当原来的 LiquidButton / LiquidBottomTabs 仍负责绘制两个玻璃本体时，
    // 这里只保留 smooth-union 新增出来的 neck 区域，避免叠出灰圆或重复玻璃。
    float bodyMask = max(maskA, maskB);
    float neckMask = clamp(unionMask - bodyMask, 0.0, 1.0);
    float mask = mix(unionMask, neckMask, clamp(neckOnly, 0.0, 1.0));

    half4 color = content.eval(coord);
    color.a *= half(mask * opacity);
    return color;
}
"""
