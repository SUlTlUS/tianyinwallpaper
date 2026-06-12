package com.zeaze.tianyinwallpaper.renderer

import android.content.Context
import android.util.Log
import android.view.Surface
import com.zeaze.tianyinwallpaper.utils.GaussianPlyLoader
import com.zeaze.tianyinwallpaper.utils.GaussianSogLoader

interface NativeGaussianRenderer {
    fun start(surface: Surface)
    fun stop()
    fun stopAndWait(timeoutMs: Long = 500)
    fun resize(width: Int, height: Int)
    fun loadGaussians(scene: GaussianPlyLoader.GaussianScene)
    fun loadSogGaussians(scenes: List<GaussianSogLoader.SogGpuScene>): Boolean = false
    fun loadSogGaussians(scene: GaussianSogLoader.SogGpuScene): Boolean = loadSogGaussians(listOf(scene))
    fun updateTilt(x: Float, y: Float)
    fun updateParams(parallaxStrength: Float, blurStrength: Float)
    fun updateGaussianParams(params: DepthGLRenderer.GaussianRenderParams)
    fun resetCamera()
    fun showLoading(enabled: Boolean)
    fun requestRender()
    fun setRenderingEnabled(enabled: Boolean)
}

object NativeGaussianRendererFactory {
    private const val TAG = "NativeGaussianRenderer"

    fun create(context: Context): NativeGaussianRenderer {
        return if (VulkanGaussianRenderer.isSupported(context)) {
            Log.d(TAG, "using Vulkan Gaussian renderer")
            VulkanGaussianRenderer(context.applicationContext)
        } else {
            Log.d(TAG, "using GLES Gaussian renderer")
            DepthGLRenderer()
        }
    }
}
