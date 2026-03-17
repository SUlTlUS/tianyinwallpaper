package com.zeaze.tianyinwallpaper.model

/**
 * 光栅组合数据模型
 * 支持静态图集光栅和动态视频光栅
 */
data class RasterGroupModel(
    var id: String = "",
    var type: Int = TYPE_STATIC,
    var imageUris: List<String> = emptyList(),
    var videoUri: String? = null,
    var createdAt: Long = 0L,
    
    // ── 传感器参数 ──
    var sensorWidth: Float = 1.5f,           // 传感器灵敏度 (1.0 ~ 9.0)
    
    // ── 过渡效果参数 ──
    var transitionBand: Float = 0.55f,       // 过渡区域宽度 (0.1 ~ 1.0)
    var edgeSoftness: Float = 0.25f,         // 边缘柔化程度 (0.01 ~ 0.5)
    
    // ── 效果类型 ──
    var effectType: Int = EFFECT_STANDARD,   // 扫描线效果类型
    
    // ── 马赛克效果参数 ──
    var mosaicSize: Float = 0.05f,           // 马赛克格子大小 (0.01 ~ 0.2)
    var mosaicSoftness: Float = 0.02f,       // 马赛克过渡软边 (0.0 ~ 0.1)
    
    // ── 光栅透镜效果参数 ──
    var lenticularPitch: Float = 0.03f,      // 光栅条纹间距 (0.01 ~ 0.1)
    var lenticularAngle: Float = 0f          // 光栅倾斜角度 (弧度)
) {
    companion object {
        const val TYPE_STATIC = 0
        const val TYPE_DYNAMIC = 1
        
        // 效果类型常量
        const val EFFECT_STANDARD = 0     // 标准扫描线
        const val EFFECT_MOSAIC = 1       // 马赛克
        const val EFFECT_LENTICULAR = 2   // 光栅透镜
    }
}

