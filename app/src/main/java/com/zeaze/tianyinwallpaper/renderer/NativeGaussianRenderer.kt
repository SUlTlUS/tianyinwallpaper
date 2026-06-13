package com.zeaze.tianyinwallpaper.renderer

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import android.view.MotionEvent
import android.view.Surface
import com.zeaze.tianyinwallpaper.utils.GaussianPlyLoader
import com.zeaze.tianyinwallpaper.utils.GaussianSogLoader
import com.zeaze.tianyinwallpaper.utils.SuperSplatWebParams

interface NativeGaussianRenderer {
    fun start(surface: Surface)
    fun stop()
    fun stopAndWait(timeoutMs: Long = 500)
    fun resize(width: Int, height: Int)
    fun loadGaussians(scene: GaussianPlyLoader.GaussianScene)
    fun loadSogGaussians(scenes: List<GaussianSogLoader.SogGpuScene>): Boolean = false
    fun loadSogGaussians(scene: GaussianSogLoader.SogGpuScene): Boolean = loadSogGaussians(listOf(scene))
    fun loadWebGaussians(uriString: String, params: SuperSplatWebParams): Boolean = false
    fun dispatchTouchEvent(event: MotionEvent): Boolean = false
    fun setWebLoadingListener(listener: ((Boolean) -> Unit)?) = Unit
    fun setWebCenterOffsetListener(listener: ((Float, Float) -> Unit)?) = Unit
    fun setBackdropFrameListener(listener: ((Bitmap) -> Unit)?) = Unit
    fun updateTilt(x: Float, y: Float)
    fun updateParams(parallaxStrength: Float, blurStrength: Float)
    fun updateGaussianParams(params: DepthGLRenderer.GaussianRenderParams)
    fun resetCamera()
    fun showLoading(enabled: Boolean)
    fun requestRender()
    fun setRenderingEnabled(enabled: Boolean)
}

enum class NativeGaussianBackendMode {
    GLES,
    VULKAN,
    WEB,
    AUTO
}

object NativeGaussianRendererFactory {
    private const val TAG = "NativeGaussianRenderer"

    fun create(
        context: Context,
        mode: NativeGaussianBackendMode = NativeGaussianBackendMode.GLES
    ): NativeGaussianRenderer {
        return when (mode) {
            NativeGaussianBackendMode.GLES -> {
                Log.d(TAG, "using GLES Gaussian renderer mode=$mode")
                DepthGLRenderer()
            }
            NativeGaussianBackendMode.VULKAN -> {
                if (VulkanGaussianRenderer.isSupported(context)) {
                    Log.d(TAG, "using Vulkan Gaussian renderer mode=$mode")
                    VulkanGaussianRenderer(context.applicationContext)
                } else {
                    Log.w(TAG, "Vulkan requested but unsupported; using GLES Gaussian renderer")
                    DepthGLRenderer()
                }
            }
            NativeGaussianBackendMode.WEB -> {
                Log.d(TAG, "using WebView Gaussian renderer mode=$mode")
                WebGaussianWallpaperRenderer(context)
            }
            NativeGaussianBackendMode.AUTO -> {
                if (VulkanGaussianRenderer.isSupported(context)) {
                    Log.d(TAG, "using Vulkan Gaussian renderer mode=$mode")
                    VulkanGaussianRenderer(context.applicationContext)
                } else {
                    Log.d(TAG, "using GLES Gaussian renderer mode=$mode")
                    DepthGLRenderer()
                }
            }
        }
    }
}
