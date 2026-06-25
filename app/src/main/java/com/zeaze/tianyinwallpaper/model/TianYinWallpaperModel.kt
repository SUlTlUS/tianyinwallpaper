package com.zeaze.tianyinwallpaper.model

data class TianYinWallpaperModel(
    var type: Int = 0,
    var uuid: String? = null,
    var imgPath: String? = null,
    var videoPath: String? = null,
    var imgUri: String? = null,
    var videoUri: String? = null,
    var createdAt: Long = 0L,
    var startTime: Int = -1,
    var endTime: Int = -1,
    var loop: Boolean = true,
    var independentTime: Boolean = false,
    // 变换参数：缩放和位置
    var scale: Float = 1f,
    var offsetX: Float = 0f,
    var offsetY: Float = 0f,
    var rotation: Float = 0f,
    var brightness: Float = 0f,
    var volume: Float = 0f,
)
