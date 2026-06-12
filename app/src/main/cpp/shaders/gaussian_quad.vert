#version 450

layout(location = 0) in vec2 inCorner;
layout(location = 1) in vec3 inPosition;
layout(location = 2) in vec4 inColor;
layout(location = 3) in vec3 inCovarianceA;
layout(location = 4) in vec3 inCovarianceB;

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

layout(location = 0) out vec4 vColor;
layout(location = 1) out vec2 vLocal;
layout(location = 2) out float vAaFactor;

void main() {
    vec3 rel = inPosition - pc.cameraPosition.xyz;
    float viewX = dot(rel, pc.cameraRight.xyz);
    float viewY = dot(rel, pc.cameraUp.xyz);
    float rawZ = dot(rel, pc.cameraForward.xyz);
    float z = max(rawZ, 0.02);
    float focalPixels = 0.5 * pc.surfaceSize.y / max(pc.tanHalfFov, 0.001);
    vec2 center = vec2(
        (viewX / z) * (2.0 * focalPixels / pc.surfaceSize.x),
        -(viewY / z) * (2.0 * focalPixels / pc.surfaceSize.y)
    );

    vec3 jx = (pc.cameraRight.xyz * z - pc.cameraForward.xyz * viewX) * (focalPixels / (z * z));
    vec3 jy = -(pc.cameraUp.xyz * z - pc.cameraForward.xyz * viewY) * (focalPixels / (z * z));
    vec3 covJx = vec3(
        dot(inCovarianceA, jx),
        inCovarianceA.y * jx.x + inCovarianceB.x * jx.y + inCovarianceB.y * jx.z,
        inCovarianceA.z * jx.x + inCovarianceB.y * jx.y + inCovarianceB.z * jx.z
    );
    vec3 covJy = vec3(
        dot(inCovarianceA, jy),
        inCovarianceA.y * jy.x + inCovarianceB.x * jy.y + inCovarianceB.y * jy.z,
        inCovarianceA.z * jy.x + inCovarianceB.y * jy.y + inCovarianceB.z * jy.z
    );
    float pointScale2 = pc.pointScale * pc.pointScale;
    float rawCovXX = dot(jx, covJx) * pointScale2;
    float rawCovXY = dot(jx, covJy) * pointScale2;
    float rawCovYY = dot(jy, covJy) * pointScale2;
    float detOrig = max(rawCovXX * rawCovYY - rawCovXY * rawCovXY, 0.0);
    float covXX = rawCovXX + 0.3;
    float covXY = rawCovXY;
    float covYY = rawCovYY + 0.3;
    float detBlur = max(covXX * covYY - covXY * covXY, 0.000001);
    float mid = 0.5 * (covXX + covYY);
    float diff = 0.5 * (covXX - covYY);
    float radius = sqrt(max(diff * diff + covXY * covXY, 0.0));
    float lambda1 = max(mid + radius, 1.0);
    float lambda2 = max(mid - radius, 1.0);
    float majorPixels = 2.0 * sqrt(2.0 * lambda1);
    float minorPixels = 2.0 * sqrt(2.0 * lambda2);
    vec2 majorAxis = normalize(vec2(covXY, lambda1 - covXX));
    if (abs(majorAxis.x) + abs(majorAxis.y) < 0.0001) {
        majorAxis = vec2(1.0, 0.0);
    }
    vec2 minorAxis = vec2(-majorAxis.y, majorAxis.x);
    vec2 local = inCorner * pc.quadExtent;
    vec2 pixelOffset = majorAxis * local.x * majorPixels + minorAxis * local.y * minorPixels;
    vec2 clipOffset = pixelOffset * vec2(2.0 / pc.surfaceSize.x, 2.0 / pc.surfaceSize.y);
    vec2 clipPosition = center + clipOffset;
    gl_Position = vec4(clipPosition.x, -clipPosition.y, 0.0, 1.0);
    vColor = vec4(inColor.rgb, inColor.a * step(0.02, rawZ));
    vLocal = local;
    vAaFactor = clamp(sqrt(max(detOrig / detBlur, 0.0)), 0.0, 1.0);
}
