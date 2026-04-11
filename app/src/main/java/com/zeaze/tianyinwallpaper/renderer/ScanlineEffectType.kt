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
     * 波纹玻璃效果 - Corrugated 正弦波截面
     */
    CORRUGATED_GLASS(3, "波纹玻璃"),

    /**
     * 长虹玻璃效果 - Reeded 等宽半圆柱阵列
     */
    REEDED_GLASS(4, "长虹玻璃"),

    /**
     * 棱镜玻璃效果 - Prism 宽窄复合交替阵列
     */
    PRISM_GLASS(5, "棱镜玻璃");

    companion object {
        fun fromId(id: Int): ScanlineEffectType = entries.find { it.id == id } ?: STANDARD
    }
}
