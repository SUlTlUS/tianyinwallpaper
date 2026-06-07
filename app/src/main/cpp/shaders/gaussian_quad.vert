#version 450

layout(location = 0) in vec2 inCorner;
layout(location = 1) in vec3 inPosition;
layout(location = 2) in vec4 inColor;
layout(location = 3) in vec3 inScale;
layout(location = 4) in vec4 inRotation;

layout(push_constant) uniform PushConstants {
    vec2 surfaceSize;
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
    float defaultCameraDistance;
    float tanHalfFov;
    float cameraZoom;
    float focusDepthOffset;
    float pointScale;
    float quadExtent;
} pc;

layout(location = 0) out vec4 vColor;
layout(location = 1) out vec2 vLocal;
layout(location = 2) out float vAaFactor;

struct CameraFrame {
    vec3 position;
    vec3 right;
    vec3 up;
    vec3 forward;
};

mat3 quatToMat3(vec4 q) {
    float x2 = q.x + q.x;
    float y2 = q.y + q.y;
    float z2 = q.z + q.z;
    float xx = q.x * x2;
    float xy = q.x * y2;
    float xz = q.x * z2;
    float yy = q.y * y2;
    float yz = q.y * z2;
    float zz = q.z * z2;
    float wx = q.w * x2;
    float wy = q.w * y2;
    float wz = q.w * z2;
    return mat3(
        1.0 - yy - zz, xy + wz, xz - wy,
        xy - wz, 1.0 - xx - zz, yz + wx,
        xz + wy, yz - wx, 1.0 - xx - yy
    );
}

CameraFrame wallpaperCamera() {
    float radius = max(pc.sceneRadius, 0.001);
    vec3 target = vec3(
        pc.sceneCenterX + pc.centerOffset.x * radius,
        pc.sceneCenterY + pc.centerOffset.y * radius,
        pc.sceneCenterZ + radius * pc.focusDepthOffset
    );
    float frameDistance = max(pc.defaultCameraDistance, radius * 0.02);
    float distance = max(frameDistance / max(pc.cameraZoom, 0.6), radius * 0.02);
    vec2 tangent = vec2(pc.tilt.x, -pc.tilt.y) * frameDistance * max(pc.strength, 0.02) * 2.4;
    float maxTangent = distance * 0.75;
    float tangentLength = length(tangent);
    if (tangentLength > maxTangent && tangentLength > 0.0001) {
        tangent *= maxTangent / tangentLength;
        tangentLength = maxTangent;
    }
    float frontDepth = sqrt(max(distance * distance - tangentLength * tangentLength, distance * distance * 0.25));
    vec3 position = target + vec3(tangent.x, tangent.y, -frontDepth);
    vec3 forward = normalize(target - position);
    vec3 right = normalize(vec3(forward.z, 0.0, -forward.x));
    vec3 up = normalize(cross(forward, right));
    CameraFrame frame;
    frame.position = position;
    frame.right = right;
    frame.up = up;
    frame.forward = forward;
    return frame;
}

void main() {
    CameraFrame camera = wallpaperCamera();
    vec3 rel = inPosition - camera.position;
    float viewX = dot(rel, camera.right);
    float viewY = dot(rel, camera.up);
    float rawZ = dot(rel, camera.forward);
    float z = max(rawZ, 0.02);
    float focalPixels = 0.5 * pc.surfaceSize.y / max(pc.tanHalfFov, 0.001);
    vec2 center = vec2(
        (viewX / z) * (2.0 * focalPixels / pc.surfaceSize.x),
        -(viewY / z) * (2.0 * focalPixels / pc.surfaceSize.y)
    );

    vec3 scale = max(inScale, vec3(0.0001));
    mat3 rot = quatToMat3(normalize(inRotation));
    vec3 m0 = rot[0] * scale.x;
    vec3 m1 = rot[1] * scale.y;
    vec3 m2 = rot[2] * scale.z;

    vec3 jx = (camera.right * z - camera.forward * viewX) * (focalPixels / (z * z));
    vec3 jy = -(camera.up * z - camera.forward * viewY) * (focalPixels / (z * z));
    vec3 covJx = m0 * dot(m0, jx) + m1 * dot(m1, jx) + m2 * dot(m2, jx);
    vec3 covJy = m0 * dot(m0, jy) + m1 * dot(m1, jy) + m2 * dot(m2, jy);
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
