package com.zeaze.tianyinwallpaper.model

import com.alibaba.fastjson.annotation.JSONField

data class DepthWallpaperModel(
    var id: String = "",
    var imageUri: String = "",
    var gaussianUri: String = "",
    var meshUri: String = "",
    var displayName: String = "",
    var createdAt: Long = 0L,
    var sensorSensitivity: Float = 4.5f,
    var parallaxStrength: Float = 0.045f,
    var blurStrength: Float = 0.004f
) {
    @JSONField(serialize = false, deserialize = false)
    fun isGaussian(): Boolean = gaussianUri.isNotBlank()

    @JSONField(serialize = false, deserialize = false)
    fun isMesh(): Boolean = meshUri.isNotBlank()
}
