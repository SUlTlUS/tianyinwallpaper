package com.zeaze.tianyinwallpaper.backdrop

const val BottomBarMetaballMaskShaderString = """
uniform shader content;
uniform float2 size;
uniform float gap;
uniform float actionSize;
uniform float smoothness;
uniform float opacity;

float sdRoundRect(float2 p, float2 halfSize, float radius) {
    float2 q = abs(p) - halfSize + float2(radius, radius);
    return min(max(q.x, q.y), 0.0) + length(max(q, float2(0.0, 0.0))) - radius;
}

float smoothUnion(float d1, float d2, float k) {
    float h = clamp(0.5 + 0.5 * (d2 - d1) / max(k, 0.001), 0.0, 1.0);
    return mix(d2, d1, h) - k * h * (1.0 - h);
}

half4 main(float2 coord) {
    float h = size.y;
    float tabWidth = max(1.0, size.x - actionSize - gap);
    float pillRadius = h * 0.5;
    float buttonRadius = actionSize * 0.5;

    float2 tabCenter = float2(tabWidth * 0.5, h * 0.5);
    float2 tabHalfSize = float2(tabWidth * 0.5, h * 0.5);
    float dTab = sdRoundRect(coord - tabCenter, tabHalfSize, pillRadius);

    float2 buttonCenter = float2(size.x - buttonRadius, h * 0.5);
    float dButton = length(coord - buttonCenter) - buttonRadius;

    float d = smoothUnion(dTab, dButton, smoothness);
    float edge = max(1.0, h * 0.015);
    float mask = 1.0 - smoothstep(-edge, edge, d);

    half4 color = content.eval(coord);
    color.a *= half(mask * opacity);
    return color;
}
"""
