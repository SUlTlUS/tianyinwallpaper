package com.zeaze.tianyinwallpaper.backdrop

const val LiquidMetaballMaskShaderString = """
uniform shader content;
uniform float2 size;
uniform float2 centerA;
uniform float2 halfSizeA;
uniform float cornerA;
uniform float shapeA;
uniform float2 centerB;
uniform float2 halfSizeB;
uniform float cornerB;
uniform float shapeB;
uniform float smoothness;
uniform float opacity;
uniform float neckOnly;

float sdEllipse(float2 p, float2 c, float2 halfSize) {
    float2 r = max(halfSize, float2(1.0, 1.0));
    float2 q = (p - c) / r;
    return (length(q) - 1.0) * min(r.x, r.y);
}

float sdRoundRect(float2 p, float2 c, float2 halfSize, float radius) {
    float r = min(radius, min(halfSize.x, halfSize.y));
    float2 q = abs(p - c) - halfSize + float2(r, r);
    return min(max(q.x, q.y), 0.0) + length(max(q, float2(0.0, 0.0))) - r;
}

float shapeDistance(float shape, float2 p, float2 center, float2 halfSize, float corner) {
    if (shape < 0.5) {
        return sdEllipse(p, center, halfSize);
    }
    return sdRoundRect(p, center, halfSize, corner);
}

float smoothUnion(float d1, float d2, float k) {
    float h = clamp(0.5 + 0.5 * (d2 - d1) / max(k, 0.001), 0.0, 1.0);
    return mix(d2, d1, h) - k * h * (1.0 - h);
}

half4 main(float2 coord) {
    float dA = shapeDistance(shapeA, coord, centerA, halfSizeA, cornerA);
    float dB = shapeDistance(shapeB, coord, centerB, halfSizeB, cornerB);
    float dUnion = smoothUnion(dA, dB, smoothness);

    float edge = max(1.0, min(size.x, size.y) * 0.015);
    float maskA = 1.0 - smoothstep(-edge, edge, dA);
    float maskB = 1.0 - smoothstep(-edge, edge, dB);
    float unionMask = 1.0 - smoothstep(-edge, edge, dUnion);
    float bodyMask = max(maskA, maskB);
    float bridgeMask = clamp(unionMask - bodyMask, 0.0, 1.0);
    float mask = mix(unionMask, bridgeMask, clamp(neckOnly, 0.0, 1.0));

    half4 color = content.eval(coord);
    color.a *= half(mask * opacity);
    return color;
}
"""
