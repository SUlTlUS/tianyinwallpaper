package com.zeaze.tianyinwallpaper.service.raster

import android.graphics.Rect
import android.util.Size

/**
 * 视频光栅渲染参数Bean
 * 参考自 vivo RasterVideoPreRenderParamBean
 */
data class RasterVideoPreRenderParamBean(
    var texIds: Int = 0,
    var texImageSize: Size? = null,
    var inImageRect: Rect? = null,
    var outImageRect: Rect? = null,
    var inScreenSize: Size? = null,
    var outScreenSize: Size? = null,
    var videoPath: String? = null,
    var videoFirstFrame: String? = null,
    var videoFrameNum: Int = 0,
    var videoFrameRate: Int = 30,
    var videoTime: Long = 0L,
    var videoResFrom: String? = null
)
