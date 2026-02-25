package com.zeaze.tianyinwallpaper.model

data class TianYinWallpaperModel(
    var type: Int = 0,
    var uuid: String? = null,
    var imgPath: String? = null,
    var videoPath: String? = null,
    var imgUri: String? = null,
    var videoUri: String? = null,
    var startTime: Int = -1,
    var endTime: Int = -1,
    var loop: Boolean = true
)
