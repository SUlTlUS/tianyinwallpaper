#version 450

layout(location = 0) in vec4 vColor;
layout(location = 1) in vec2 vLocal;
layout(location = 2) in float vAaFactor;
layout(location = 0) out vec4 outColor;

layout(push_constant) uniform PushConstants {
    vec4 surfaceSize;
    vec4 cameraPosition;
    vec4 cameraRight;
    vec4 cameraUp;
    vec4 cameraForward;
    float tanHalfFov;
    float pointScale;
    float quadExtent;
    float opacity;
    float alphaFalloff;
} pc;

const float ALPHA_CLIP = 0.0039215686;

float normExp(float radius2, float falloff) {
    float k = max(4.0 * falloff, 0.1);
    float edge = exp(-k);
    return (exp(-k * radius2) - edge) / max(1.0 - edge, 0.0001);
}

void main() {
    float radius2 = dot(vLocal, vLocal);
    float extent = max(pc.quadExtent, 1.0);
    if (radius2 > extent * extent) {
        discard;
    }
    float falloff = max(pc.alphaFalloff, 0.1);
    float alpha = vColor.a * vAaFactor * pc.opacity * normExp(radius2, falloff);
    if (alpha < ALPHA_CLIP) {
        discard;
    }
    outColor = vec4(vColor.rgb * alpha, alpha);
}
