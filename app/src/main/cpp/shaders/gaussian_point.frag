#version 450

layout(location = 0) in vec4 vColor;
layout(location = 0) out vec4 outColor;

void main() {
    vec2 p = gl_PointCoord * 2.0 - 1.0;
    float d2 = dot(p, p);
    if (d2 > 1.0) {
        discard;
    }
    float alpha = exp(-d2 * 2.25) * vColor.a;
    if (alpha < 0.01) {
        discard;
    }
    outColor = vec4(vColor.rgb * (alpha / max(vColor.a, 0.001)), alpha);
}
