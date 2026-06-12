package com.zeaze.tianyinwallpaper.renderer

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.Surface
import com.zeaze.tianyinwallpaper.utils.GaussianPlyLoader
import com.zeaze.tianyinwallpaper.utils.GaussianSogLoader
import java.nio.Buffer
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.sin

class VulkanGaussianRenderer(
    private val appContext: Context
) : NativeGaussianRenderer {
    private val fallback = DepthGLRenderer()
    private val lock = Any()
    private val renderThread = HandlerThread("TianyinVulkanRenderer").apply { start() }
    private val renderHandler = Handler(renderThread.looper)
    private val budgetHandler = renderHandler
    private val renderTaskQueued = AtomicBoolean(false)
    private val tiltTaskQueued = AtomicBoolean(false)

    private var nativeHandle = 0L
    private var currentSurface: Surface? = null
    private var width = 1
    private var height = 1
    private var activeBackend = Backend.Stopped

    private var renderingEnabled = true
    private var loadingVisible = false
    private var latestScene: GaussianPlyLoader.GaussianScene? = null
    private var latestSogScenes: List<GaussianSogLoader.SogGpuScene>? = null
    private var latestTiltX = 0f
    private var latestTiltY = 0f
    @Volatile private var pendingTiltX = 0f
    @Volatile private var pendingTiltY = 0f
    private var latestParallaxStrength = 0f
    private var latestBlurStrength = 0f
    private var latestGaussianParams = DepthGLRenderer.GaussianRenderParams()
    private var targetSplatBudget = 0
    private var currentSplatBudget = 0
    private var adaptiveSplatBudget = 0
    private var lastSplatBudgetStepMs = 0L
    private var lastCameraChangeMs = 0L
    private var slowRenderFrames = 0
    private var fastRenderFrames = 0
    private var renderPerfFrames = 0
    private var renderPerfTotalMs = 0L
    private var renderPerfMaxMs = 0L
    private var lastRenderPerfLogMs = 0L
    private val splatBudgetRunnable = Runnable {
        synchronized(lock) {
            if (activeBackend == Backend.Vulkan && advanceSplatBudgetLocked()) {
                renderVulkanSceneLocked()
            }
            scheduleSplatBudgetStepLocked()
        }
    }

    override fun start(surface: Surface) {
        runOnRenderThread {
            synchronized(lock) {
                if (currentSurface === surface && surface.isValid && activeBackend != Backend.Stopped) {
                    when (activeBackend) {
                        Backend.Vulkan -> if (hasVulkanSceneLocked()) renderVulkanSceneLocked() else renderVulkanLoadingLocked()
                        Backend.Gles -> fallback.requestRender()
                        Backend.Stopped -> Unit
                    }
                    return@synchronized
                }
                currentSurface = surface
                if (!tryStartVulkanLocked(surface)) {
                    startFallbackLocked("Vulkan start failed")
                    return@synchronized
                }
                latestSogScenes?.let {
                    if (uploadSogScenesToVulkanLocked(it)) {
                        renderVulkanSceneLocked()
                    } else {
                        startFallbackLocked("Vulkan pending SOG scene upload failed")
                    }
                } ?: latestScene?.let {
                    if (uploadSceneToVulkanLocked(it)) {
                        renderVulkanSceneLocked()
                    } else {
                        switchToFallbackLocked("Vulkan pending scene upload failed", it)
                    }
                } ?: renderVulkanLoadingLocked()
            }
        }
    }

    override fun stop() {
        runOnRenderThread {
            synchronized(lock) {
                stopVulkanLocked()
                budgetHandler.removeCallbacks(splatBudgetRunnable)
                renderTaskQueued.set(false)
                tiltTaskQueued.set(false)
                fallback.stop()
                currentSurface = null
                activeBackend = Backend.Stopped
            }
        }
    }

    override fun stopAndWait(timeoutMs: Long) {
        runOnRenderThreadAndWait(timeoutMs) {
            synchronized(lock) {
                stopVulkanLocked()
                budgetHandler.removeCallbacks(splatBudgetRunnable)
                renderTaskQueued.set(false)
                tiltTaskQueued.set(false)
                fallback.stopAndWait(timeoutMs)
                currentSurface = null
                activeBackend = Backend.Stopped
            }
        }
    }

    override fun resize(width: Int, height: Int) {
        runOnRenderThread {
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
    }

    override fun loadGaussians(scene: GaussianPlyLoader.GaussianScene) {
        runOnRenderThread {
            synchronized(lock) {
                latestScene = scene
                latestSogScenes = null
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
    }

    override fun loadSogGaussians(scenes: List<GaussianSogLoader.SogGpuScene>): Boolean {
        if (scenes.isEmpty()) return false
        var uploaded = false
        runOnRenderThreadAndWait(UPLOAD_WAIT_TIMEOUT_MS) {
            synchronized(lock) {
                when (activeBackend) {
                    Backend.Vulkan -> {
                        uploaded = uploadSogScenesToVulkanLocked(scenes)
                        if (uploaded) {
                            latestSogScenes = scenes
                            latestScene = null
                            renderVulkanSceneLocked()
                        }
                    }
                    Backend.Stopped -> {
                        latestSogScenes = scenes
                        latestScene = null
                        uploaded = true
                    }
                    Backend.Gles -> {
                        uploaded = false
                    }
                }
            }
        }
        return uploaded
    }

    override fun updateTilt(x: Float, y: Float) {
        pendingTiltX = x
        pendingTiltY = y
        if (tiltTaskQueued.getAndSet(true)) return
        renderHandler.post {
            synchronized(lock) {
                tiltTaskQueued.set(false)
                latestTiltX = pendingTiltX
                latestTiltY = pendingTiltY
                lastCameraChangeMs = SystemClock.elapsedRealtime()
                if (activeBackend == Backend.Gles) {
                    fallback.updateTilt(latestTiltX, latestTiltY)
                } else if (activeBackend == Backend.Vulkan) {
                    queueVulkanRenderLocked()
                }
            }
        }
    }

    override fun updateParams(parallaxStrength: Float, blurStrength: Float) {
        runOnRenderThread {
            synchronized(lock) {
                latestParallaxStrength = parallaxStrength
                latestBlurStrength = blurStrength
                lastCameraChangeMs = SystemClock.elapsedRealtime()
                if (activeBackend == Backend.Gles) {
                    fallback.updateParams(parallaxStrength, blurStrength)
                } else if (activeBackend == Backend.Vulkan) {
                    queueVulkanRenderLocked()
                }
            }
        }
    }

    override fun updateGaussianParams(params: DepthGLRenderer.GaussianRenderParams) {
        runOnRenderThread {
            synchronized(lock) {
                latestGaussianParams = params
                lastCameraChangeMs = SystemClock.elapsedRealtime()
                if (activeBackend == Backend.Gles) {
                    fallback.updateGaussianParams(params)
                } else if (activeBackend == Backend.Vulkan) {
                    queueVulkanRenderLocked()
                }
            }
        }
    }

    override fun resetCamera() {
        runOnRenderThread {
            synchronized(lock) {
                latestTiltX = 0f
                latestTiltY = 0f
                pendingTiltX = 0f
                pendingTiltY = 0f
                lastCameraChangeMs = SystemClock.elapsedRealtime()
                if (activeBackend == Backend.Gles) {
                    fallback.resetCamera()
                } else if (activeBackend == Backend.Vulkan) {
                    queueVulkanRenderLocked()
                }
            }
        }
    }

    override fun showLoading(enabled: Boolean) {
        runOnRenderThread {
            synchronized(lock) {
                loadingVisible = enabled
                if (activeBackend == Backend.Gles) {
                    fallback.showLoading(enabled)
                } else if (activeBackend == Backend.Vulkan) {
                    renderVulkanLoadingLocked()
                }
            }
        }
    }

    override fun requestRender() {
        runOnRenderThread {
            synchronized(lock) {
                when (activeBackend) {
                    Backend.Vulkan -> queueVulkanRenderLocked()
                    Backend.Gles -> fallback.requestRender()
                    Backend.Stopped -> Unit
                }
            }
        }
    }

    override fun setRenderingEnabled(enabled: Boolean) {
        runOnRenderThread {
            synchronized(lock) {
                renderingEnabled = enabled
                if (activeBackend == Backend.Gles) {
                    fallback.setRenderingEnabled(enabled)
                } else if (activeBackend == Backend.Vulkan && enabled) {
                    queueVulkanRenderLocked()
                }
            }
        }
    }

    private fun runOnRenderThread(block: () -> Unit) {
        if (Looper.myLooper() == renderThread.looper) {
            block()
        } else {
            renderHandler.post { block() }
        }
    }

    private fun runOnRenderThreadAndWait(timeoutMs: Long, block: () -> Unit) {
        if (Looper.myLooper() == renderThread.looper) {
            block()
            return
        }
        val latch = CountDownLatch(1)
        renderHandler.post {
            try {
                block()
            } finally {
                latch.countDown()
            }
        }
        if (!latch.await(timeoutMs.coerceAtLeast(1L), TimeUnit.MILLISECONDS)) {
            Log.w(TAG, "Vulkan render thread stop timed out after ${timeoutMs}ms")
        }
    }

    private fun queueVulkanRenderLocked() {
        if (renderTaskQueued.getAndSet(true)) return
        renderHandler.post {
            synchronized(lock) {
                renderTaskQueued.set(false)
                if (activeBackend != Backend.Vulkan) return@synchronized
                syncVulkanRenderStateLocked()
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
        val uploadStartMs = SystemClock.elapsedRealtime()
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
        val uploadElapsedMs = SystemClock.elapsedRealtime() - uploadStartMs
        Log.d(TAG, "native Vulkan scene upload uploaded=$uploaded count=${scene.count} elapsedMs=$uploadElapsedMs")
        return uploaded
    }

    private fun uploadSogScenesToVulkanLocked(scenes: List<GaussianSogLoader.SogGpuScene>): Boolean {
        if (nativeHandle == 0L) return false
        if (scenes.isEmpty()) return false
        syncVulkanRenderStateLocked()
        val totalCount = scenes.sumOf { it.count }
        val countWeight = totalCount.toFloat().coerceAtLeast(1f)
        fun weighted(value: (GaussianSogLoader.SogGpuScene) -> Float): Float {
            return scenes.sumOf { (value(it) * it.count).toDouble() }.toFloat() / countWeight
        }
        val uploadStartMs = SystemClock.elapsedRealtime()
        val uploaded = runCatching {
            nativeUploadSogScene(
                nativeHandle,
                Array<Buffer>(scenes.size) { scenes[it].meansL.duplicate().apply { position(0) } },
                Array<Buffer>(scenes.size) { scenes[it].meansU.duplicate().apply { position(0) } },
                Array<Buffer>(scenes.size) { scenes[it].scales.duplicate().apply { position(0) } },
                Array<Buffer>(scenes.size) { scenes[it].sh0.duplicate().apply { position(0) } },
                Array<Buffer>(scenes.size) { scenes[it].quats.duplicate().apply { position(0) } },
                Array<Buffer>(scenes.size) { scenes[it].scaleCodebook.duplicate().apply { position(0) } },
                Array<Buffer>(scenes.size) { scenes[it].sh0Codebook.duplicate().apply { position(0) } },
                Array<Buffer>(scenes.size) { scenes[it].meansMinMax.duplicate().apply { position(0) } },
                IntArray(scenes.size) { scenes[it].count },
                FloatArray(scenes.size * 4) { index ->
                    val scene = scenes[index / 4]
                    when (index % 4) {
                        0 -> scene.chunkCenterX
                        1 -> scene.chunkCenterY
                        2 -> scene.chunkCenterZ
                        else -> scene.chunkRadius
                    }
                },
                scenes.maxOf { it.imageWidth },
                scenes.maxOf { it.imageHeight },
                weighted { it.focusDepth },
                weighted { it.parallaxAnchorDepth },
                weighted { it.backgroundR },
                weighted { it.backgroundG },
                weighted { it.backgroundB },
                weighted { it.sceneCenterX },
                weighted { it.sceneCenterY },
                weighted { it.sceneCenterZ },
                scenes.maxOf { it.sceneRadius },
                scenes.maxOf { it.defaultCameraDistance }
            )
        }.onFailure {
            Log.w(TAG, "native Vulkan SOG scene upload failed", it)
        }.getOrDefault(false)
        val uploadElapsedMs = SystemClock.elapsedRealtime() - uploadStartMs
        Log.d(
            TAG,
            "native Vulkan SOG scene upload uploaded=$uploaded chunks=${scenes.size} " +
                "count=$totalCount elapsedMs=$uploadElapsedMs"
        )
        if (uploaded) {
            resetSplatBudgetLocked(totalCount, totalCount)
        }
        return uploaded
    }

    private fun hasVulkanSceneLocked(): Boolean {
        return latestScene != null || latestSogScenes != null
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
        if (!hasVulkanSceneLocked()) {
            renderVulkanLoadingLocked()
            return
        }
        if (!renderingEnabled || nativeHandle == 0L) return
        val sceneCount = latestSogScenes?.sumOf { it.count } ?: latestScene?.count ?: 0
        val drawCount = if (latestSogScenes != null) {
            sceneCount
        } else {
            currentSplatBudget
                .coerceAtMost(effectiveTargetSplatBudgetLocked())
                .coerceIn(0, sceneCount)
        }
        if (drawCount <= 0) return
        val renderStartMs = SystemClock.elapsedRealtime()
        val ok = runCatching { nativeRenderScene(nativeHandle, drawCount) }
            .onFailure { Log.w(TAG, "native Vulkan scene render failed", it) }
            .getOrDefault(false)
        val renderElapsedMs = SystemClock.elapsedRealtime() - renderStartMs
        if (!ok) {
            latestScene?.let { switchToFallbackLocked("Vulkan scene render failed", it) }
        } else {
            recordRenderPerfLocked(drawCount, renderElapsedMs)
            scheduleSplatBudgetStepLocked()
        }
    }

    private fun recordRenderPerfLocked(drawCount: Int, elapsedMs: Long) {
        updateAdaptiveBudgetLocked(drawCount, elapsedMs)
        renderPerfFrames += 1
        renderPerfTotalMs += elapsedMs
        renderPerfMaxMs = maxOf(renderPerfMaxMs, elapsedMs)
        val now = SystemClock.elapsedRealtime()
        if (now - lastRenderPerfLogMs < PERF_LOG_INTERVAL_MS) return
        val avgMs = if (renderPerfFrames > 0) renderPerfTotalMs.toFloat() / renderPerfFrames else 0f
        Log.d(
            TAG,
            "Vulkan perf drawCount=$drawCount budget=$currentSplatBudget/$targetSplatBudget " +
                "adaptive=${if (adaptiveSplatBudget > 0) adaptiveSplatBudget else targetSplatBudget} " +
                "frames=$renderPerfFrames avgMs=${String.format("%.2f", avgMs)} maxMs=$renderPerfMaxMs"
        )
        renderPerfFrames = 0
        renderPerfTotalMs = 0L
        renderPerfMaxMs = 0L
        lastRenderPerfLogMs = now
    }

    private fun updateAdaptiveBudgetLocked(drawCount: Int, elapsedMs: Long) {
        if (latestSogScenes != null) return
        if (targetSplatBudget <= ADAPTIVE_MIN_SPLAT_BUDGET) return
        when {
            elapsedMs >= ADAPTIVE_SLOW_FRAME_MS && drawCount > ADAPTIVE_MIN_SPLAT_BUDGET -> {
                slowRenderFrames += 1
                fastRenderFrames = 0
                if (slowRenderFrames >= ADAPTIVE_SLOW_FRAME_COUNT) {
                    val nextBudget = (drawCount - ADAPTIVE_SPLAT_STEP)
                        .coerceAtLeast(ADAPTIVE_MIN_SPLAT_BUDGET)
                        .coerceAtMost(targetSplatBudget)
                    if (adaptiveSplatBudget == 0 || nextBudget < adaptiveSplatBudget) {
                        adaptiveSplatBudget = nextBudget
                        currentSplatBudget = currentSplatBudget.coerceAtMost(adaptiveSplatBudget)
                        budgetHandler.removeCallbacks(splatBudgetRunnable)
                        Log.d(
                            TAG,
                            "Vulkan adaptive budget down elapsedMs=$elapsedMs budget=$adaptiveSplatBudget/$targetSplatBudget"
                        )
                    }
                    slowRenderFrames = 0
                }
            }
            elapsedMs <= ADAPTIVE_FAST_FRAME_MS -> {
                fastRenderFrames += 1
                slowRenderFrames = 0
                if (adaptiveSplatBudget > 0 && fastRenderFrames >= ADAPTIVE_FAST_FRAME_COUNT) {
                    adaptiveSplatBudget = (adaptiveSplatBudget + ADAPTIVE_SPLAT_STEP)
                        .coerceAtMost(targetSplatBudget)
                    if (adaptiveSplatBudget >= targetSplatBudget) {
                        adaptiveSplatBudget = 0
                    }
                    fastRenderFrames = 0
                    Log.d(
                        TAG,
                        "Vulkan adaptive budget up elapsedMs=$elapsedMs budget=${effectiveTargetSplatBudgetLocked()}/$targetSplatBudget"
                    )
                    scheduleSplatBudgetStepLocked()
                }
            }
            else -> {
                slowRenderFrames = 0
                fastRenderFrames = 0
            }
        }
    }

    private fun resetSplatBudgetLocked(scene: GaussianPlyLoader.GaussianScene) {
        resetSplatBudgetLocked(scene.count)
    }

    private fun resetSplatBudgetLocked(count: Int, minimumInitialCount: Int = 0) {
        targetSplatBudget = count.coerceAtLeast(0)
        adaptiveSplatBudget = 0
        currentSplatBudget = if (targetSplatBudget <= SPLAT_BUDGET_INITIAL) {
            targetSplatBudget
        } else {
            maxOf(SPLAT_BUDGET_INITIAL, minimumInitialCount).coerceAtMost(targetSplatBudget)
        }
        lastSplatBudgetStepMs = SystemClock.elapsedRealtime()
        budgetHandler.removeCallbacks(splatBudgetRunnable)
        renderPerfFrames = 0
        renderPerfTotalMs = 0L
        renderPerfMaxMs = 0L
        lastRenderPerfLogMs = 0L
        slowRenderFrames = 0
        fastRenderFrames = 0
    }

    private fun effectiveTargetSplatBudgetLocked(): Int {
        val adaptive = adaptiveSplatBudget.takeIf { it > 0 } ?: targetSplatBudget
        return adaptive.coerceIn(0, targetSplatBudget)
    }

    private fun advanceSplatBudgetLocked(): Boolean {
        val effectiveTarget = effectiveTargetSplatBudgetLocked()
        if (currentSplatBudget >= effectiveTarget) return false
        val now = SystemClock.elapsedRealtime()
        val idleDelayMs = lastCameraChangeMs + SPLAT_BUDGET_IDLE_DELAY_MS - now
        if (idleDelayMs > 0L) return false
        if (now - lastSplatBudgetStepMs < SPLAT_BUDGET_STEP_INTERVAL_MS) return false
        currentSplatBudget = (currentSplatBudget + SPLAT_BUDGET_STEP).coerceAtMost(effectiveTarget)
        lastSplatBudgetStepMs = now
        Log.d(
            TAG,
            "Vulkan splat budget step ready=${currentSplatBudget >= effectiveTarget} " +
                "loading=${effectiveTarget - currentSplatBudget} budget=$currentSplatBudget/$targetSplatBudget " +
                "adaptive=$effectiveTarget"
        )
        return true
    }

    private fun scheduleSplatBudgetStepLocked() {
        val effectiveTarget = effectiveTargetSplatBudgetLocked()
        if (activeBackend != Backend.Vulkan || currentSplatBudget >= effectiveTarget) return
        val now = SystemClock.elapsedRealtime()
        val delay = maxOf(
            lastSplatBudgetStepMs + SPLAT_BUDGET_STEP_INTERVAL_MS - now,
            lastCameraChangeMs + SPLAT_BUDGET_IDLE_DELAY_MS - now,
            0L
        )
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

    private external fun nativeUploadSogScene(
        handle: Long,
        meansL: Array<Buffer>,
        meansU: Array<Buffer>,
        scales: Array<Buffer>,
        sh0: Array<Buffer>,
        quats: Array<Buffer>,
        scaleCodebook: Array<Buffer>,
        sh0Codebook: Array<Buffer>,
        meansMinMax: Array<Buffer>,
        counts: IntArray,
        chunkBounds: FloatArray,
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
        private const val SPLAT_BUDGET_INITIAL = 250_000
        private const val SPLAT_BUDGET_STEP = 100_000
        private const val SPLAT_BUDGET_STEP_INTERVAL_MS = 180L
        private const val SPLAT_BUDGET_IDLE_DELAY_MS = 700L
        private const val ADAPTIVE_MIN_SPLAT_BUDGET = 200_000
        private const val ADAPTIVE_SPLAT_STEP = 100_000
        private const val ADAPTIVE_SLOW_FRAME_MS = 34L
        private const val ADAPTIVE_FAST_FRAME_MS = 20L
        private const val ADAPTIVE_SLOW_FRAME_COUNT = 2
        private const val ADAPTIVE_FAST_FRAME_COUNT = 12
        private const val UPLOAD_WAIT_TIMEOUT_MS = 5_000L
        private const val PERF_LOG_INTERVAL_MS = 3_000L

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
