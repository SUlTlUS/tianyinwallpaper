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
    float d = smoothUnion(dA, dB, smoothness);

    float edge = max(1.25, min(size.x, size.y) * 0.01);
    float mask = 1.0 - smoothstep(-edge, edge, d);

    half4 color = content.eval(coord);
    color.a *= half(mask * opacity);
    return color;
}
"""
