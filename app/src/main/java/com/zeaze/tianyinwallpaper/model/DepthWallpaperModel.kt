package com.zeaze.tianyinwallpaper.model

import com.alibaba.fastjson.annotation.JSONField

data class DepthWallpaperModel(
    var id: String = "",
    var gaussianUri: String = "",
    var gaussianRenderMode: String = "native",
    var displayName: String = "",
    var createdAt: Long = 0L,
    var sensorSensitivity: Float = 4.5f,
    var parallaxStrength: Float = 0.045f,
    var cameraZoom: Float = 1f,
    var centerOffsetX: Float = 0f,
    var centerOffsetY: Float = 0f,
    var focusDepth: Float = 0.25f,
    var gaussianMaxSplats: Int = 800_000,
    var blurStrength: Float = 0f
) {
    @JSONField(serialize = false, deserialize = false)
    fun isGaussian(): Boolean = gaussianUri.isNotBlank()

    @JSONField(serialize = false, deserialize = false)
    fun useWebGaussianRenderer(): Boolean = gaussianRenderMode == "web"
}
