package com.zeaze.tianyinwallpaper.service.raster

import android.util.Size

/**
 * 视频光栅效果配置
 * 参考自 vivo RVEffectConfigure
 */
class RVEffectConfigure {
    var textureId: Int = 0
    var imageSize: Size? = null
    var videoPath: String? = null
    var imagePath: String? = null
    var videoFramesCount: Int = 0
    var videoDuration: Long = 0L
    var resourceFrom: String? = null
    var isFoldDevice: Boolean = false
    var isSmallScreen: Boolean = false
    
    // 屏幕尺寸相关
    private var imageDesignScreenWidth: Int = 1080
    private var imageDesignScreenHeight: Int = 1920
    private var imageDesignScreenWidthFoldLarger: Int = 1080
    private var imageDesignScreenHeightFoldLarger: Int = 1920
    private var imageDesignScreenWidthFoldSmaller: Int = 720
    private var imageDesignScreenHeightFoldSmaller: Int = 1280
    
    // Dump信息用于缩放和位置
    var dumpInfo: RVDumpInfo? = null
    var dumpInfoSecond: RVDumpInfo? = null
    
    data class RVDumpInfo(
        var scaleX: Float = 1f,
        var scaleY: Float = 1f,
        var posX: Float = 0f,
        var posY: Float = 0f
    )
    
    fun getImageDesignScreenWidth(screenType: Int = SCREEN_TYPE_NORMAL): Int {
        return when (screenType) {
            SCREEN_TYPE_FOLD_LARGER -> imageDesignScreenWidthFoldLarger
            SCREEN_TYPE_FOLD_SMALLER -> imageDesignScreenWidthFoldSmaller
            else -> imageDesignScreenWidth
        }
    }
    
    fun getImageDesignScreenHeight(screenType: Int = SCREEN_TYPE_NORMAL): Int {
        return when (screenType) {
            SCREEN_TYPE_FOLD_LARGER -> imageDesignScreenHeightFoldLarger
            SCREEN_TYPE_FOLD_SMALLER -> imageDesignScreenHeightFoldSmaller
            else -> imageDesignScreenHeight
        }
    }
    
    fun setImageDesignScreenWidth(width: Int, screenType: Int = SCREEN_TYPE_NORMAL) {
        when (screenType) {
            SCREEN_TYPE_FOLD_LARGER -> imageDesignScreenWidthFoldLarger = width
            SCREEN_TYPE_FOLD_SMALLER -> imageDesignScreenWidthFoldSmaller = width
            else -> imageDesignScreenWidth = width
        }
    }
    
    fun setImageDesignScreenHeight(height: Int, screenType: Int = SCREEN_TYPE_NORMAL) {
        when (screenType) {
            SCREEN_TYPE_FOLD_LARGER -> imageDesignScreenHeightFoldLarger = height
            SCREEN_TYPE_FOLD_SMALLER -> imageDesignScreenHeightFoldSmaller = height
            else -> imageDesignScreenHeight = height
        }
    }
    
    fun updateRasterParams(duration: Long) {
        videoDuration = duration
    }
    
    companion object {
        const val SCREEN_TYPE_NORMAL = 0
        const val SCREEN_TYPE_FOLD_LARGER = 1
        const val SCREEN_TYPE_FOLD_SMALLER = 2
    }
}
