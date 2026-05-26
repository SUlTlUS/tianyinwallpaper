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

    // ── 玻璃效果参数 ──
    var stripedWavelength: Float = 32f,      // 条纹波长 (dp, 8 ~ 80)
    var stripedAmplitude: Float = 16f,       // 条纹振幅 (dp, 2 ~ 40)
    var narrowWavelength: Float = 12f,       // 窄波波长 (dp, 仅棱镜模式, 4 ~ 40)
    var narrowAmplitude: Float = 6f,         // 窄波振幅 (dp, 仅棱镜模式, 1 ~ 20)
    var glassAnimEnabled: Boolean = true,    // 玻璃动画开关（扫描线移动时条纹滚动）
    var glassBandWidth: Float = 0.3f,        // 玻璃折射区域宽度 (0.05 ~ 1.0)
    var deadZoneEnabled: Boolean = true,     // 死区开关（关闭后倾斜始终响应，无淡出效果）
    var clockColorMode: Int = 0
) {
    companion object {
        const val TYPE_STATIC = 0
        const val TYPE_DYNAMIC = 1
        
        // 效果类型常量
        const val EFFECT_STANDARD = 0       // 标准扫描线
        const val EFFECT_CORRUGATED_GLASS = 3  // 波纹玻璃
        const val EFFECT_REEDED_GLASS = 4   // 长虹玻璃
        const val EFFECT_PRISM_GLASS = 5    // 棱镜玻璃
    }
}

