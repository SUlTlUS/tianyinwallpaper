package com.zeaze.tianyinwallpaper.update
import androidx.annotation.Keep
@Keep
data class UpdateInfo(
    var code: Int = 0,
    var name: String = "",
    var filename: String = "",
    var url: String = "",
    var time: Long = 0,
    var des: String = "",
    var size: Long = 0,
    var md5: String = ""
)
