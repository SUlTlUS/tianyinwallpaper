package com.zeaze.tianyinwallpaper.model

import com.alibaba.fastjson.annotation.JSONField

data class DepthWallpaperModel(
    var id: String = "",
    var gaussianUri: String = "",
    var gaussianRenderMode: String = "web",
    var sourceGenerationRecordId: String = "",
    var displayName: String = "",
    var createdAt: Long = 0L,
    var sensorSensitivity: Float = DEFAULT_SOG_SENSOR_SENSITIVITY,
    var parallaxStrength: Float = DEFAULT_SOG_PARALLAX_STRENGTH,
    var cameraZoom: Float = DEFAULT_SOG_CAMERA_ZOOM,
    var cameraDefaultDistance: Float = 0f,
    var cameraDefaultFov: Float = 0f,
    var cameraCalibrationVersion: Int = 0,
    var centerOffsetX: Float = 0f,
    var centerOffsetY: Float = 0f,
    var focusDepth: Float = DEFAULT_SOG_FOCUS_DEPTH,
    var cameraFov: Float = 60f,
    var webPerformanceMode: Boolean = true,
    var gaussianMaxSplats: Int = 800_000,
    var blurStrength: Float = 0f
) {
    @JSONField(serialize = false, deserialize = false)
    fun isGaussian(): Boolean = gaussianUri.isNotBlank()

    companion object {
        const val DEFAULT_SOG_SENSOR_SENSITIVITY = 4.0f
        const val DEFAULT_SOG_PARALLAX_STRENGTH = 0.080f
        const val DEFAULT_SOG_CAMERA_ZOOM = 1.12f
        const val DEFAULT_SOG_FOCUS_DEPTH = 30f
        const val ORIGIN_CAMERA_CALIBRATION_VERSION = 2
    }

    @JSONField(serialize = false, deserialize = false)
    fun useWebGaussianRenderer(): Boolean = true
}
