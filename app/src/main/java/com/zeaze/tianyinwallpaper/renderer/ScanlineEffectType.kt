package com.zeaze.tianyinwallpaper.renderer

/**
 * 扫描线效果类型
 * 支持多种光栅透镜效果模拟
 */
enum class ScanlineEffectType(val id: Int, val displayName: String) {
    /**
     * 标准扫描线效果 - 平滑过渡的垂直扫描线
     */
    STANDARD(0, "标准"),
    
    /**
     * 马赛克效果 - 棋盘格马赛克过渡
     */
    MOSAIC(1, "马赛克"),
    
    /**
     * 光栅透镜效果 - 模拟真实光栅透镜的条纹效果
     */
    LENTICULAR(2, "光栅透镜");

    companion object {
        fun fromId(id: Int): ScanlineEffectType = entries.find { it.id == id } ?: STANDARD
    }
}
