package com.zeaze.tianyinwallpaper.renderer

import android.content.Context
import android.graphics.Bitmap
import android.view.MotionEvent
import android.view.Surface
import com.zeaze.tianyinwallpaper.utils.GaussianPlyLoader
import com.zeaze.tianyinwallpaper.utils.GaussianSogLoader
import com.zeaze.tianyinwallpaper.utils.SuperSplatWebParams

data class GaussianRenderParams(
    val splatScale: Float = 1f,
    val globalOpacity: Float = 1f,
    val alphaFalloff: Float = 1f,
    val minPointSize: Float = 1f,
    val maxPointSize: Float = 64f,
    val cameraZoom: Float = 1f,
    val centerOffsetX: Float = 0f,
    val centerOffsetY: Float = 0f,
    val focusDepthOffset: Float = 0f,
    val useLayerCache: Boolean = true
)

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
    fun setWebCameraDefaultsListener(listener: ((Float, Float) -> Unit)?) = Unit
    fun setBackdropFrameListener(listener: ((Bitmap) -> Unit)?) = Unit
    fun updateTilt(x: Float, y: Float)
    fun updateParams(parallaxStrength: Float, blurStrength: Float)
    fun updateGaussianParams(params: GaussianRenderParams)
    fun resetCamera()
    fun showLoading(enabled: Boolean)
    fun requestRender()
    fun setRenderingEnabled(enabled: Boolean)
}

enum class NativeGaussianBackendMode {
    WEB
}

object NativeGaussianRendererFactory {
    fun create(
        context: Context,
        mode: NativeGaussianBackendMode = NativeGaussianBackendMode.WEB
    ): NativeGaussianRenderer {
        return when (mode) {
            NativeGaussianBackendMode.WEB -> WebGaussianWallpaperRenderer(context)
        }
    }
}
