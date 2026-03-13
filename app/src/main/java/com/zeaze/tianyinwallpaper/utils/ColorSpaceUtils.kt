package com.zeaze.tianyinwallpaper.utils

import android.content.Context
import android.provider.Settings
import android.util.Log

/**
 * 色彩空间转换工具类
 * 参考自 vivo 项目中的色彩空间处理
 */
object ColorSpaceUtils {
    private const val TAG = "ColorSpaceUtils"

    // SRGB → Display P3 的 3×3 矩阵
    private val SRGB_TO_DP3_MATRIX = floatArrayOf(
        0.8225f, 0.0335f, 0.0174f,
        0.1775f, 0.9669f, 0.0723f,
        0.0000f, -0.0004f, 0.9104f
    )

    // SRGB 解码参数
    private const val GAMMA_SRGB = 2.4f
    private const val A_SRGB = 0.94786733f
    private const val B_SRGB = 0.052132703f
    private const val C_SRGB = 0.07739938f
    private const val D_SRGB = 0.04045f

    // Display P3 编码参数
    private const val GAMMA_DP3 = 0.41666666f
    private const val A_DP3 = 1.1371188f
    private const val B_DP3 = -0.0f
    private const val C_DP3 = 12.92f
    private const val D_DP3 = 0.003130805f
    private const val E_DP3 = -0.05499994f

    // 动画时长缩放缓存
    private var cachedAnimationDurationScale = 1.0f
    private var animationDurationScaleChecked = false

    /**
     * 将 sRGB 颜色转换为 Display P3 颜色
     */
    fun srgbToDisplayP3(color: FloatArray): FloatArray {
        val result = color.copyOf()

        // 1. 将 sRGB 解码到线性空间（去 gamma）
        result[0] = decodeSRGB(result[0])
        result[1] = decodeSRGB(result[1])
        result[2] = decodeSRGB(result[2])

        // 2. 线性 sRGB → 线性 Display P3 的矩阵转换
        val linear = floatArrayOf(
            SRGB_TO_DP3_MATRIX[0] * result[0] + SRGB_TO_DP3_MATRIX[1] * result[1] + SRGB_TO_DP3_MATRIX[2] * result[2],
            SRGB_TO_DP3_MATRIX[3] * result[0] + SRGB_TO_DP3_MATRIX[4] * result[1] + SRGB_TO_DP3_MATRIX[5] * result[2],
            SRGB_TO_DP3_MATRIX[6] * result[0] + SRGB_TO_DP3_MATRIX[7] * result[1] + SRGB_TO_DP3_MATRIX[8] * result[2]
        )

        // 3. 转换到非线性 Display P3
        result[0] = encodeDisplayP3(linear[0])
        result[1] = encodeDisplayP3(linear[1])
        result[2] = encodeDisplayP3(linear[2])

        return result
    }

    /**
     * sRGB 解码（去 gamma）
     */
    private fun decodeSRGB(x: Float): Float {
        return if (x < D_SRGB) {
            C_SRGB * x
        } else {
            Math.pow((A_SRGB * x + B_SRGB).toDouble(), GAMMA_SRGB.toDouble()).toFloat()
        }
    }

    /**
     * Display P3 编码（加 gamma）
     */
    private fun encodeDisplayP3(x: Float): Float {
        return if (x < D_DP3) {
            C_DP3 * x + E_DP3
        } else {
            Math.pow((A_DP3 * x + B_DP3).toDouble(), GAMMA_DP3.toDouble()).toFloat() + E_DP3
        }
    }

    /**
     * 获取系统动画时长缩放
     */
    fun getAnimationDurationScale(context: Context): Float {
        if (animationDurationScaleChecked) {
            return cachedAnimationDurationScale
        }
        try {
            cachedAnimationDurationScale = Settings.Global.getFloat(
                context.contentResolver,
                "animator_duration_scale"
            )
            Log.d(TAG, "animator_duration_scale: $cachedAnimationDurationScale")
        } catch (e: Exception) {
            Log.e(TAG, "getAnimationDurationScale failed", e)
            cachedAnimationDurationScale = 1.0f
        }
        animationDurationScaleChecked = true
        return cachedAnimationDurationScale
    }

    /**
     * 设置系统动画时长缩放（需要系统权限）
     */
    fun setAnimationDurationScale(context: Context, scale: Float) {
        try {
            Settings.Global.putFloat(
                context.contentResolver,
                "animator_duration_scale",
                scale
            )
            Log.d(TAG, "setAnimationDurationScale: $scale")
        } catch (e: Exception) {
            Log.e(TAG, "setAnimationDurationScale failed", e)
        }
    }

    /**
     * 重置动画时长缩放检查缓存
     */
    fun resetAnimationDurationScaleCache() {
        animationDurationScaleChecked = false
    }
}
