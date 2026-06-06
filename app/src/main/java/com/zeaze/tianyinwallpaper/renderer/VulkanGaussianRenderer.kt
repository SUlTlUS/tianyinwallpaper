package com.zeaze.tianyinwallpaper.renderer

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import android.view.Surface
import com.zeaze.tianyinwallpaper.utils.GaussianPlyLoader

class VulkanGaussianRenderer(
    private val appContext: Context
) : NativeGaussianRenderer {
    private val fallback = DepthGLRenderer()

    override fun start(surface: Surface) {
        val surfaceReady = runCatching { nativeProbeSurface(surface) }
            .onFailure { Log.w(TAG, "Vulkan surface probe failed", it) }
            .getOrDefault(false)
        Log.d(TAG, "Vulkan surface probe ready=$surfaceReady; rendering delegated to GLES fallback")
        fallback.start(surface)
    }

    override fun stop() {
        fallback.stop()
    }

    override fun stopAndWait(timeoutMs: Long) {
        fallback.stopAndWait(timeoutMs)
    }

    override fun resize(width: Int, height: Int) {
        fallback.resize(width, height)
    }

    override fun loadGaussians(scene: GaussianPlyLoader.GaussianScene) {
        Log.d(TAG, "Vulkan pipeline pending; delegating scene count=${scene.count} to GLES fallback")
        fallback.loadGaussians(scene)
    }

    override fun updateTilt(x: Float, y: Float) {
        fallback.updateTilt(x, y)
    }

    override fun updateParams(parallaxStrength: Float, blurStrength: Float) {
        fallback.updateParams(parallaxStrength, blurStrength)
    }

    override fun updateGaussianParams(params: DepthGLRenderer.GaussianRenderParams) {
        fallback.updateGaussianParams(params)
    }

    override fun resetCamera() {
        fallback.resetCamera()
    }

    override fun requestRender() {
        fallback.requestRender()
    }

    override fun setRenderingEnabled(enabled: Boolean) {
        fallback.setRenderingEnabled(enabled)
    }

    companion object {
        private const val TAG = "VulkanGaussianRenderer"
        private const val LIB_NAME = "tianyin_gaussian_vulkan"

        @Volatile private var nativeLibraryLoaded = false
        @Volatile private var nativeLibraryLoadAttempted = false

        fun isSupported(context: Context): Boolean {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return false
            val hasFeature = context.packageManager.hasSystemFeature(PackageManager.FEATURE_VULKAN_HARDWARE_LEVEL)
            if (!hasFeature) {
                Log.d(TAG, "Vulkan feature missing")
                return false
            }
            if (!loadNativeLibrary()) return false
            return runCatching { nativeIsVulkanAvailable() }
                .onFailure { Log.w(TAG, "Vulkan availability probe failed", it) }
                .getOrDefault(false)
        }

        private fun loadNativeLibrary(): Boolean {
            if (nativeLibraryLoaded) return true
            synchronized(this) {
                if (nativeLibraryLoaded) return true
                if (nativeLibraryLoadAttempted) return false
                nativeLibraryLoadAttempted = true
                return runCatching {
                    System.loadLibrary(LIB_NAME)
                    nativeLibraryLoaded = true
                    true
                }.onFailure {
                    Log.w(TAG, "failed to load $LIB_NAME", it)
                }.getOrDefault(false)
            }
        }

        private external fun nativeIsVulkanAvailable(): Boolean
        private external fun nativeProbeSurface(surface: Surface): Boolean
    }
}
