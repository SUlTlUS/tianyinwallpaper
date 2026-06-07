#version 450

layout(location = 0) in vec3 inPosition;
layout(location = 1) in vec4 inColor;

layout(push_constant) uniform PushConstants {
    vec2 fillScale;
    vec2 tilt;
    vec2 centerOffset;
    float strength;
    float focusDepth;
    float farDepth;
    float sceneCenterX;
    float sceneCenterY;
    float sceneCenterZ;
    float sceneRadius;
    float cameraZoom;
    float pointScale;
    float opacity;
    float minPointSize;
    float maxPointSize;
} pc;

layout(location = 0) out vec4 vColor;

void main() {
    float radius = max(pc.sceneRadius, 0.001);
    float zoom = max(pc.cameraZoom, 0.001);
    float depth = max(inPosition.z, 0.001);
    float focusDepth = pc.focusDepth + pc.farDepth * 0.0;
    vec2 parallax = pc.tilt * pc.strength * (depth - focusDepth);
    vec2 centered = (inPosition.xy - vec2(pc.sceneCenterX, pc.sceneCenterY)) / radius;
    centered += parallax + pc.centerOffset;

    gl_Position = vec4(centered.x * pc.fillScale.x / zoom, -centered.y * pc.fillScale.y / zoom, 0.0, 1.0);
    gl_PointSize = clamp(pc.pointScale / max(depth, 0.02), pc.minPointSize, pc.maxPointSize);
    float alpha = clamp(inColor.a * pc.opacity, 0.0, 1.0);
    vColor = vec4(inColor.rgb * alpha, alpha);
}
