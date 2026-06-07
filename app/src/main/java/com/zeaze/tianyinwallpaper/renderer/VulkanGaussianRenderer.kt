package com.zeaze.tianyinwallpaper.renderer

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.Surface
import com.zeaze.tianyinwallpaper.utils.GaussianPlyLoader
import java.nio.Buffer
import kotlin.math.sin

class VulkanGaussianRenderer(
    private val appContext: Context
) : NativeGaussianRenderer {
    private val fallback = DepthGLRenderer()
    private val lock = Any()
    private val budgetHandler = Handler(Looper.getMainLooper())

    private var nativeHandle = 0L
    private var currentSurface: Surface? = null
    private var width = 1
    private var height = 1
    private var activeBackend = Backend.Stopped

    private var renderingEnabled = true
    private var loadingVisible = false
    private var latestScene: GaussianPlyLoader.GaussianScene? = null
    private var latestTiltX = 0f
    private var latestTiltY = 0f
    private var latestParallaxStrength = 0f
    private var latestBlurStrength = 0f
    private var latestGaussianParams = DepthGLRenderer.GaussianRenderParams()
    private var targetSplatBudget = 0
    private var currentSplatBudget = 0
    private var lastSplatBudgetStepMs = 0L
    private val splatBudgetRunnable = Runnable {
        synchronized(lock) {
            if (activeBackend == Backend.Vulkan && advanceSplatBudgetLocked()) {
                renderVulkanSceneLocked()
            }
            scheduleSplatBudgetStepLocked()
        }
    }

    override fun start(surface: Surface) {
        synchronized(lock) {
            currentSurface = surface
            if (!tryStartVulkanLocked(surface)) {
                startFallbackLocked("Vulkan start failed")
                return
            }
            latestScene?.let {
                if (uploadSceneToVulkanLocked(it)) {
                    renderVulkanSceneLocked()
                } else {
                    switchToFallbackLocked("Vulkan pending scene upload failed", it)
                }
            } ?: renderVulkanLoadingLocked()
        }
    }

    override fun stop() {
        synchronized(lock) {
            stopVulkanLocked()
            budgetHandler.removeCallbacks(splatBudgetRunnable)
            fallback.stop()
            currentSurface = null
            activeBackend = Backend.Stopped
        }
    }

    override fun stopAndWait(timeoutMs: Long) {
        synchronized(lock) {
            stopVulkanLocked()
            budgetHandler.removeCallbacks(splatBudgetRunnable)
            fallback.stopAndWait(timeoutMs)
            currentSurface = null
            activeBackend = Backend.Stopped
        }
    }

    override fun resize(width: Int, height: Int) {
        synchronized(lock) {
            this.width = width.coerceAtLeast(1)
            this.height = height.coerceAtLeast(1)
            when (activeBackend) {
                Backend.Vulkan -> {
                    if (!nativeResizeRenderer(nativeHandle, this.width, this.height)) {
                        startFallbackLocked("Vulkan resize failed")
                    } else {
                        renderVulkanSceneLocked()
                    }
                }
                Backend.Gles -> fallback.resize(this.width, this.height)
                Backend.Stopped -> Unit
            }
        }
    }

    override fun loadGaussians(scene: GaussianPlyLoader.GaussianScene) {
        synchronized(lock) {
            latestScene = scene
            if (activeBackend == Backend.Vulkan) {
                if (uploadSceneToVulkanLocked(scene)) {
                    renderVulkanSceneLocked()
                } else {
                    switchToFallbackLocked("Vulkan scene upload failed", scene)
                }
            } else if (activeBackend == Backend.Gles) {
                fallback.loadGaussians(scene)
            }
        }
    }

    override fun updateTilt(x: Float, y: Float) {
        synchronized(lock) {
            latestTiltX = x
            latestTiltY = y
            if (activeBackend == Backend.Gles) {
                fallback.updateTilt(x, y)
            } else if (activeBackend == Backend.Vulkan) {
                syncVulkanRenderStateLocked()
                renderVulkanSceneLocked()
            }
        }
    }

    override fun updateParams(parallaxStrength: Float, blurStrength: Float) {
        synchronized(lock) {
            latestParallaxStrength = parallaxStrength
            latestBlurStrength = blurStrength
            if (activeBackend == Backend.Gles) {
                fallback.updateParams(parallaxStrength, blurStrength)
            } else if (activeBackend == Backend.Vulkan) {
                syncVulkanRenderStateLocked()
                renderVulkanSceneLocked()
            }
        }
    }

    override fun updateGaussianParams(params: DepthGLRenderer.GaussianRenderParams) {
        synchronized(lock) {
            latestGaussianParams = params
            if (activeBackend == Backend.Gles) {
                fallback.updateGaussianParams(params)
            } else if (activeBackend == Backend.Vulkan) {
                syncVulkanRenderStateLocked()
                renderVulkanSceneLocked()
            }
        }
    }

    override fun resetCamera() {
        synchronized(lock) {
            latestTiltX = 0f
            latestTiltY = 0f
            if (activeBackend == Backend.Gles) {
                fallback.resetCamera()
            } else if (activeBackend == Backend.Vulkan) {
                syncVulkanRenderStateLocked()
                renderVulkanSceneLocked()
            }
        }
    }

    override fun showLoading(enabled: Boolean) {
        synchronized(lock) {
            loadingVisible = enabled
            if (activeBackend == Backend.Gles) {
                fallback.showLoading(enabled)
            } else if (activeBackend == Backend.Vulkan) {
                renderVulkanLoadingLocked()
            }
        }
    }

    override fun requestRender() {
        synchronized(lock) {
            when (activeBackend) {
                Backend.Vulkan -> renderVulkanSceneLocked()
                Backend.Gles -> fallback.requestRender()
                Backend.Stopped -> Unit
            }
        }
    }

    override fun setRenderingEnabled(enabled: Boolean) {
        synchronized(lock) {
            renderingEnabled = enabled
            if (activeBackend == Backend.Gles) {
                fallback.setRenderingEnabled(enabled)
            } else if (activeBackend == Backend.Vulkan && enabled) {
                renderVulkanSceneLocked()
            }
        }
    }

    private fun tryStartVulkanLocked(surface: Surface): Boolean {
        if (!loadNativeLibrary()) return false
        stopVulkanLocked()
        val handle = nativeCreateRenderer()
        if (handle == 0L) return false
        nativeHandle = handle
        val ok = runCatching { nativeStartRenderer(handle, surface) }
            .onFailure { Log.w(TAG, "native Vulkan start failed", it) }
            .getOrDefault(false)
        if (!ok) {
            stopVulkanLocked()
            return false
        }
        activeBackend = Backend.Vulkan
        Log.d(TAG, "Vulkan backend started")
        return true
    }

    private fun startFallbackLocked(reason: String) {
        val surface = currentSurface ?: return
        stopVulkanLocked()
        if (activeBackend != Backend.Gles) {
            Log.d(TAG, "$reason; starting GLES fallback")
            fallback.start(surface)
            fallback.resize(width, height)
            fallback.setRenderingEnabled(renderingEnabled)
            fallback.updateParams(latestParallaxStrength, latestBlurStrength)
            fallback.updateGaussianParams(latestGaussianParams)
            fallback.updateTilt(latestTiltX, latestTiltY)
            fallback.showLoading(loadingVisible)
            latestScene?.let { fallback.loadGaussians(it) }
            activeBackend = Backend.Gles
        }
    }

    private fun switchToFallbackLocked(reason: String, scene: GaussianPlyLoader.GaussianScene) {
        Log.d(TAG, "$reason; scene count=${scene.count}")
        latestScene = scene
        startFallbackLocked(reason)
    }

    private fun uploadSceneToVulkanLocked(scene: GaussianPlyLoader.GaussianScene): Boolean {
        if (nativeHandle == 0L) return false
        syncVulkanRenderStateLocked()
        resetSplatBudgetLocked(scene)
        val positions = scene.positions.duplicate().apply { position(0) }
        val colors = scene.colors.duplicate().apply { position(0) }
        val scales = scene.scales.duplicate().apply { position(0) }
        val rotations = scene.rotations?.duplicate()?.apply { position(0) }
        val uploaded = runCatching {
            nativeUploadScene(
                nativeHandle,
                positions,
                colors,
                scales,
                rotations,
                scene.count,
                scene.imageWidth,
                scene.imageHeight,
                scene.focusDepth,
                scene.parallaxAnchorDepth,
                scene.backgroundR,
                scene.backgroundG,
                scene.backgroundB,
                scene.sceneCenterX,
                scene.sceneCenterY,
                scene.sceneCenterZ,
                scene.sceneRadius,
                scene.defaultCameraDistance
            )
        }.onFailure {
            Log.w(TAG, "native Vulkan scene upload failed", it)
        }.getOrDefault(false)
        Log.d(TAG, "native Vulkan scene upload uploaded=$uploaded count=${scene.count}")
        return uploaded
    }

    private fun syncVulkanRenderStateLocked() {
        if (nativeHandle == 0L) return
        nativeUpdateRenderState(
            nativeHandle,
            latestTiltX,
            latestTiltY,
            latestParallaxStrength,
            latestGaussianParams.cameraZoom,
            latestGaussianParams.centerOffsetX,
            latestGaussianParams.centerOffsetY,
            latestGaussianParams.focusDepthOffset,
            latestGaussianParams.splatScale,
            latestGaussianParams.globalOpacity,
            latestGaussianParams.alphaFalloff
        )
    }

    private fun renderVulkanSceneLocked() {
        if (latestScene == null) {
            renderVulkanLoadingLocked()
            return
        }
        if (!renderingEnabled || nativeHandle == 0L) return
        val drawCount = currentSplatBudget.coerceIn(0, latestScene?.count ?: 0)
        if (drawCount <= 0) return
        val ok = runCatching { nativeRenderScene(nativeHandle, drawCount) }
            .onFailure { Log.w(TAG, "native Vulkan scene render failed", it) }
            .getOrDefault(false)
        if (!ok) {
            latestScene?.let { switchToFallbackLocked("Vulkan scene render failed", it) }
        } else {
            scheduleSplatBudgetStepLocked()
        }
    }

    private fun resetSplatBudgetLocked(scene: GaussianPlyLoader.GaussianScene) {
        targetSplatBudget = scene.count.coerceAtLeast(0)
        currentSplatBudget = if (targetSplatBudget <= SPLAT_BUDGET_INITIAL) {
            targetSplatBudget
        } else {
            SPLAT_BUDGET_INITIAL
        }
        lastSplatBudgetStepMs = SystemClock.elapsedRealtime()
        budgetHandler.removeCallbacks(splatBudgetRunnable)
    }

    private fun advanceSplatBudgetLocked(): Boolean {
        if (currentSplatBudget >= targetSplatBudget) return false
        val now = SystemClock.elapsedRealtime()
        if (now - lastSplatBudgetStepMs < SPLAT_BUDGET_STEP_INTERVAL_MS) return false
        currentSplatBudget = (currentSplatBudget + SPLAT_BUDGET_STEP).coerceAtMost(targetSplatBudget)
        lastSplatBudgetStepMs = now
        Log.d(
            TAG,
            "Vulkan splat budget step ready=${currentSplatBudget >= targetSplatBudget} " +
                "loading=${targetSplatBudget - currentSplatBudget} budget=$currentSplatBudget/$targetSplatBudget"
        )
        return true
    }

    private fun scheduleSplatBudgetStepLocked() {
        if (activeBackend != Backend.Vulkan || currentSplatBudget >= targetSplatBudget) return
        val now = SystemClock.elapsedRealtime()
        val delay = (lastSplatBudgetStepMs + SPLAT_BUDGET_STEP_INTERVAL_MS - now).coerceAtLeast(0L)
        budgetHandler.removeCallbacks(splatBudgetRunnable)
        budgetHandler.postDelayed(splatBudgetRunnable, delay)
    }

    private fun stopVulkanLocked() {
        if (nativeHandle != 0L) {
            budgetHandler.removeCallbacks(splatBudgetRunnable)
            runCatching { nativeStopRenderer(nativeHandle) }
                .onFailure { Log.w(TAG, "native Vulkan stop failed", it) }
            runCatching { nativeDestroyRenderer(nativeHandle) }
                .onFailure { Log.w(TAG, "native Vulkan destroy failed", it) }
            nativeHandle = 0L
        }
        if (activeBackend == Backend.Vulkan) {
            activeBackend = Backend.Stopped
        }
    }

    private fun renderVulkanLoadingLocked() {
        if (!renderingEnabled || nativeHandle == 0L) return
        val t = (SystemClock.elapsedRealtime() % 1_200L) / 1_200f
        val pulse = ((sin((t * Math.PI * 2.0)).toFloat() + 1f) * 0.5f).coerceIn(0f, 1f)
        val r = if (loadingVisible) 0.015f + pulse * 0.025f else 0.015f
        val g = if (loadingVisible) 0.018f + pulse * 0.035f else 0.018f
        val b = if (loadingVisible) 0.028f + pulse * 0.060f else 0.028f
        val ok = runCatching { nativeRenderClear(nativeHandle, r, g, b) }
            .onFailure { Log.w(TAG, "native Vulkan clear failed", it) }
            .getOrDefault(false)
        if (!ok) {
            startFallbackLocked("Vulkan clear render failed")
        }
    }

    private enum class Backend {
        Stopped,
        Vulkan,
        Gles
    }

    private external fun nativeCreateRenderer(): Long
    private external fun nativeDestroyRenderer(handle: Long)
    private external fun nativeStartRenderer(handle: Long, surface: Surface): Boolean
    private external fun nativeStopRenderer(handle: Long)
    private external fun nativeResizeRenderer(handle: Long, width: Int, height: Int): Boolean
    private external fun nativeRenderClear(handle: Long, r: Float, g: Float, b: Float): Boolean
    private external fun nativeRenderScene(handle: Long, drawCount: Int): Boolean
    private external fun nativeUpdateRenderState(
        handle: Long,
        tiltX: Float,
        tiltY: Float,
        parallaxStrength: Float,
        cameraZoom: Float,
        centerOffsetX: Float,
        centerOffsetY: Float,
        focusDepthOffset: Float,
        splatScale: Float,
        opacity: Float,
        alphaFalloff: Float
    )
    private external fun nativeUploadScene(
        handle: Long,
        positions: Buffer,
        colors: Buffer,
        scales: Buffer,
        rotations: Buffer?,
        count: Int,
        imageWidth: Int,
        imageHeight: Int,
        focusDepth: Float,
        farDepth: Float,
        backgroundR: Float,
        backgroundG: Float,
        backgroundB: Float,
        sceneCenterX: Float,
        sceneCenterY: Float,
        sceneCenterZ: Float,
        sceneRadius: Float,
        defaultCameraDistance: Float
    ): Boolean

    companion object {
        private const val TAG = "VulkanGaussianRenderer"
        private const val LIB_NAME = "tianyin_gaussian_vulkan"
        private const val SPLAT_BUDGET_INITIAL = 500_000
        private const val SPLAT_BUDGET_STEP = 250_000
        private const val SPLAT_BUDGET_STEP_INTERVAL_MS = 120L

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
