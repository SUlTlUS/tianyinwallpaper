package com.zeaze.tianyinwallpaper.renderer

import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLSurface
import android.opengl.GLES20
import android.opengl.GLES30
import android.opengl.Matrix
import android.os.SystemClock
import android.util.Log
import android.view.Surface
import com.zeaze.tianyinwallpaper.utils.GaussianPlyLoader
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt

class DepthGLRenderer : NativeGaussianRenderer {
    private var eglThread: EglRenderThread? = null
    private val isRunning = AtomicBoolean(false)
    private val isSurfaceValid = AtomicBoolean(false)
    private val surfaceGeneration = AtomicInteger(0)
    private val lifecycleLock = Object()
    private val renderSignal = Object()
    @Volatile private var renderingEnabled = true
    @Volatile private var pendingTiltX = 0f
    @Volatile private var pendingTiltY = 0f

    private val messageQueue = ConcurrentLinkedQueue<RenderMessage>()
    private val renderQueued = AtomicBoolean(false)
    private val tiltQueued = AtomicBoolean(false)

    data class GaussianRenderParams(
        val splatScale: Float = 1.0f,
        val globalOpacity: Float = 1.0f,
        val alphaFalloff: Float = 1.0f,
        val minPointSize: Float = 0.5f,
        val maxPointSize: Float = 120f,
        val cameraZoom: Float = 1f,
        val centerOffsetX: Float = 0f,
        val centerOffsetY: Float = 0f,
        val focusDepthOffset: Float = 0.25f,
        val useLayerCache: Boolean = true
    )

    private sealed class RenderMessage {
        data class SetSurfaceSize(val width: Int, val height: Int) : RenderMessage()
        data class LoadGaussians(val scene: GaussianPlyLoader.GaussianScene) : RenderMessage()
        data class SetParams(val parallaxStrength: Float, val blurStrength: Float) : RenderMessage()
        data class SetGaussianParams(val params: GaussianRenderParams) : RenderMessage()
        data class SetLoading(val enabled: Boolean) : RenderMessage()
        object Render : RenderMessage()
    }

    private data class GaussianLayerTexture(
        val textureId: Int,
        val layer: GaussianPlyLoader.GaussianDepthLayer
    )

    private data class GaussianVboSet(
        val scene: GaussianPlyLoader.GaussianScene,
        val positionBuffer: Int,
        val colorBuffer: Int,
        val covarianceBuffer: Int,
        val count: Int
    )

    override fun start(surface: Surface) {
        synchronized(lifecycleLock) {
            Log.d(TAG, "start running=${isRunning.get()} surfaceValid=${isSurfaceValid.get()} threadAlive=${eglThread?.isAlive == true}")
            if (isRunning.get() && isSurfaceValid.get() && eglThread?.isAlive == true) {
                signalRenderThread()
                return
            }
            if (isRunning.get() || eglThread?.isAlive == true) {
                stopAndWaitLocked(RENDER_THREAD_STOP_TIMEOUT_MS)
            }
            val generation = surfaceGeneration.incrementAndGet()
            isSurfaceValid.set(true)
            isRunning.set(true)
            eglThread = EglRenderThread(surface, generation)
            eglThread?.start()
        }
    }

    override fun stop() {
        synchronized(lifecycleLock) {
            Log.d(TAG, "stop running=${isRunning.get()} queue=${messageQueue.size}")
            surfaceGeneration.incrementAndGet()
            isSurfaceValid.set(false)
            if (!isRunning.getAndSet(false) && eglThread == null) return
            messageQueue.clear()
            renderQueued.set(false)
            tiltQueued.set(false)
            signalRenderThread()
            eglThread?.finish()
            eglThread = null
        }
    }

    override fun stopAndWait(timeoutMs: Long) {
        synchronized(lifecycleLock) {
            stopAndWaitLocked(timeoutMs)
        }
    }

    private fun stopAndWaitLocked(timeoutMs: Long) {
        Log.d(TAG, "stopAndWait running=${isRunning.get()} queue=${messageQueue.size} threadAlive=${eglThread?.isAlive == true}")
        surfaceGeneration.incrementAndGet()
        isSurfaceValid.set(false)
        if (!isRunning.getAndSet(false) && eglThread == null) return
        messageQueue.clear()
        renderQueued.set(false)
        tiltQueued.set(false)
        signalRenderThread()
        val oldThread = eglThread
        oldThread?.finishAndWait(timeoutMs)
        if (oldThread?.isAlive == true) {
            Log.w(TAG, "render thread did not stop within ${timeoutMs}ms; invalidated by generation=${surfaceGeneration.get()}")
        }
        eglThread = null
    }

    override fun resize(width: Int, height: Int) {
        Log.d(TAG, "resize ${width}x$height")
        messageQueue.offer(RenderMessage.SetSurfaceSize(width, height))
        signalRenderThread()
    }

    override fun loadGaussians(scene: GaussianPlyLoader.GaussianScene) {
        Log.d(
            TAG,
            "loadGaussians count=${scene.count} image=${scene.imageWidth}x${scene.imageHeight} " +
                "visible=${scene.screenVisibleSplatCount} aux=${scene.auxiliarySplatCount} " +
                "anchor=${scene.parallaxAnchorDepth} anchorSplats=${scene.parallaxAnchorSplatCount}"
        )
        messageQueue.offer(RenderMessage.LoadGaussians(scene))
        requestRender()
    }

    override fun updateTilt(x: Float, y: Float) {
        var nextX = x.coerceIn(-1f, 1f)
        var nextY = y.coerceIn(-1f, 1f)
        val tiltLengthSq = nextX * nextX + nextY * nextY
        val maxTiltLengthSq = MAX_VIEW_TILT * MAX_VIEW_TILT
        if (tiltLengthSq > maxTiltLengthSq) {
            val scale = MAX_VIEW_TILT / sqrt(tiltLengthSq)
            nextX *= scale
            nextY *= scale
        }
        pendingTiltX = nextX
        pendingTiltY = nextY
        tiltQueued.set(true)
        requestRender()
    }

    override fun updateParams(parallaxStrength: Float, blurStrength: Float) {
        messageQueue.offer(
            RenderMessage.SetParams(
                parallaxStrength.coerceIn(0f, 0.12f),
                blurStrength.coerceIn(0f, 0.02f)
            )
        )
        requestRender()
    }

    override fun updateGaussianParams(params: GaussianRenderParams) {
        messageQueue.offer(
            RenderMessage.SetGaussianParams(
                params.copy(
                    splatScale = params.splatScale.coerceIn(0.25f, 3f),
                    globalOpacity = params.globalOpacity.coerceIn(0f, 3f),
                    alphaFalloff = params.alphaFalloff.coerceIn(0.5f, 9f),
                    minPointSize = params.minPointSize.coerceIn(0.5f, 8f),
                    maxPointSize = params.maxPointSize.coerceIn(8f, 160f),
                    cameraZoom = params.cameraZoom.coerceIn(0.6f, 10f),
                    centerOffsetX = params.centerOffsetX.coerceIn(-2.5f, 2.5f),
                    centerOffsetY = params.centerOffsetY.coerceIn(-2.5f, 2.5f),
                    focusDepthOffset = params.focusDepthOffset.coerceIn(-1f, 1f)
                )
            )
        )
        requestRender()
    }

    override fun resetCamera() {
        pendingTiltX = 0f
        pendingTiltY = 0f
        tiltQueued.set(true)
        requestRender()
    }

    override fun showLoading(enabled: Boolean) {
        messageQueue.offer(RenderMessage.SetLoading(enabled))
        requestRender()
    }

    override fun requestRender() {
        if (renderQueued.compareAndSet(false, true)) {
            messageQueue.offer(RenderMessage.Render)
        }
        signalRenderThread()
    }

    override fun setRenderingEnabled(enabled: Boolean) {
        Log.d(TAG, "setRenderingEnabled $enabled queue=${messageQueue.size}")
        renderingEnabled = enabled
        if (enabled) renderQueued.set(false)
        signalRenderThread()
        if (enabled) requestRender()
    }

    private fun signalRenderThread() {
        synchronized(renderSignal) {
            renderSignal.notifyAll()
        }
    }

    private inner class EglRenderThread(
        private val surface: Surface,
        private val generation: Int
    ) : Thread("DepthGLRenderer") {
        private var display: EGLDisplay = EGL14.EGL_NO_DISPLAY
        private var context: EGLContext = EGL14.EGL_NO_CONTEXT
        private var eglSurface: EGLSurface = EGL14.EGL_NO_SURFACE

        private var gaussianProg: Int = 0
        private var gaussianQuadProg: Int = 0
        private var gaussianLayerProg: Int = 0
        private var gaussianQuadCornerBuffer: Int = 0
        private var vBuf: FloatBuffer
        private var tBuf: FloatBuffer
        private var tFlipBuf: FloatBuffer
        private var gaussianScene: GaussianPlyLoader.GaussianScene? = null
        private var gaussianVboSet: GaussianVboSet? = null

        private var sW: Int = 1
        private var sH: Int = 1
        private var cW: Int = 1
        private var cH: Int = 1
        private var tiltX = 0f
        private var tiltY = 0f
        private var parallaxStrength = 0.045f
        private var blurStrength = 0f
        private var gaussianParams = GaussianRenderParams()
        private var gaussianLayerTextures: List<GaussianLayerTexture> = emptyList()
        private var gaussianLayerScene: GaussianPlyLoader.GaussianScene? = null
        private var gaussianLayerParams: GaussianRenderParams? = null
        private var gaussianLayerBuildFailedScene: GaussianPlyLoader.GaussianScene? = null
        private var gaussianLayerTextureWidth = 1
        private var gaussianLayerTextureHeight = 1
        private var currentSplatBudget = 0
        private var targetSplatBudget = 0
        private var loadingVisible = false
        private var lastSplatBudgetStepMs = 0L
        private var drawCount = 0L
        private var lastDrawLogMs = 0L
        private var lastNoContentLogMs = 0L
        private var lastQueueLogMs = 0L
        private var lastGlErrorLogMs = 0L
        private var isGles3 = false

        init {
            val vData = floatArrayOf(-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f)
            vBuf = ByteBuffer.allocateDirect(vData.size * 4)
                .order(ByteOrder.nativeOrder())
                .asFloatBuffer()
                .put(vData)
            vBuf.position(0)

            val tData = floatArrayOf(0f, 0f, 1f, 0f, 0f, 1f, 1f, 1f)
            tBuf = ByteBuffer.allocateDirect(tData.size * 4)
                .order(ByteOrder.nativeOrder())
                .asFloatBuffer()
                .put(tData)
            tBuf.position(0)

            val flippedTData = floatArrayOf(0f, 1f, 1f, 1f, 0f, 0f, 1f, 0f)
            tFlipBuf = ByteBuffer.allocateDirect(flippedTData.size * 4)
                .order(ByteOrder.nativeOrder())
                .asFloatBuffer()
                .put(flippedTData)
            tFlipBuf.position(0)
        }

        override fun run() {
            Log.d(TAG, "thread run start generation=$generation")
            if (!initEGL()) {
                Log.e(TAG, "Failed to init EGL")
                return
            }
            initGL()
            Log.d(TAG, "thread initGL done generation=$generation")

            while (isThreadActive()) {
                if (!renderingEnabled) {
                    try {
                        synchronized(renderSignal) {
                            while (isThreadActive() && !renderingEnabled) {
                                renderSignal.wait()
                            }
                        }
                    } catch (_: InterruptedException) {
                        if (!isThreadActive()) break
                    }
                }

                var timedRenderDue = false
                try {
                    synchronized(renderSignal) {
                        while (
                            isThreadActive() &&
                            renderingEnabled &&
                            messageQueue.isEmpty()
                        ) {
                            val timedDelayMs = nextTimedRenderDelayMs()
                            if (timedDelayMs <= 0L) {
                                timedRenderDue = true
                                break
                            }
                            if (timedDelayMs == Long.MAX_VALUE) {
                                renderSignal.wait()
                            } else {
                                renderSignal.wait(timedDelayMs)
                            }
                        }
                    }
                } catch (_: InterruptedException) {
                    if (!isThreadActive()) break
                }

                var needsDraw = timedRenderDue
                var processedMessages = 0
                while (isThreadActive()) {
                    val message = messageQueue.poll() ?: break
                    processedMessages++
                    when (message) {
                        is RenderMessage.SetSurfaceSize -> {
                            sW = message.width.coerceAtLeast(1)
                            sH = message.height.coerceAtLeast(1)
                        }
                        is RenderMessage.LoadGaussians -> {
                            loadingVisible = false
                            deleteGaussianLayerTextures()
                            deleteGaussianBuffers()
                            val retainedScene = uploadGaussianBuffers(message.scene)
                            gaussianScene = retainedScene
                            resetSplatBudget(retainedScene)
                            cW = retainedScene.imageWidth.coerceAtLeast(1)
                            cH = retainedScene.imageHeight.coerceAtLeast(1)
                            needsDraw = true
                            Log.d(
                                TAG,
                                "thread loaded gaussians count=${retainedScene.count} " +
                                    "budget=$currentSplatBudget/$targetSplatBudget " +
                                    "loading=${targetSplatBudget - currentSplatBudget}"
                            )
                        }
                        is RenderMessage.SetParams -> {
                            parallaxStrength = message.parallaxStrength
                            blurStrength = message.blurStrength
                            needsDraw = true
                        }
                        is RenderMessage.SetGaussianParams -> {
                            if (gaussianParams != message.params) {
                                gaussianParams = message.params
                                deleteGaussianLayerTextures()
                            }
                            needsDraw = true
                        }
                        is RenderMessage.SetLoading -> {
                            if (loadingVisible != message.enabled) {
                                loadingVisible = message.enabled
                            }
                            needsDraw = true
                        }
                        RenderMessage.Render -> {
                            renderQueued.set(false)
                            needsDraw = true
                        }
                    }
                }
                val now = SystemClock.elapsedRealtime()
                if (processedMessages > 40 && now - lastQueueLogMs > 1_000L) {
                    lastQueueLogMs = now
                    Log.w(TAG, "processed many messages count=$processedMessages remaining=${messageQueue.size} renderQueued=${renderQueued.get()} tiltQueued=${tiltQueued.get()}")
                }

                if (!isThreadActive()) break
                if (consumePendingTilt()) {
                    needsDraw = true
                }
                if (timedRenderDue && advanceSplatBudgetIfNeeded()) {
                    needsDraw = true
                }
                if (needsDraw) {
                    draw()
                }
            }

            Log.d(
                TAG,
                "thread exiting generation=$generation currentGeneration=${surfaceGeneration.get()} " +
                    "running=${isRunning.get()} surfaceValid=${isSurfaceValid.get()}"
            )
            destroyEGL()
        }

        private fun isThreadActive(): Boolean {
            return isRunning.get() &&
                isSurfaceValid.get() &&
                generation == surfaceGeneration.get()
        }

        private fun initEGL(): Boolean {
            display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
            if (display == EGL14.EGL_NO_DISPLAY) return false
            val version = IntArray(2)
            if (!EGL14.eglInitialize(display, version, 0, version, 1)) return false

            val gles3Attr = intArrayOf(
                EGL14.EGL_RED_SIZE, 8,
                EGL14.EGL_GREEN_SIZE, 8,
                EGL14.EGL_BLUE_SIZE, 8,
                EGL14.EGL_ALPHA_SIZE, 8,
                EGL14.EGL_DEPTH_SIZE, 16,
                EGL14.EGL_RENDERABLE_TYPE, EGL_OPENGL_ES3_BIT_KHR,
                EGL14.EGL_NONE
            )
            val attr = intArrayOf(
                EGL14.EGL_RED_SIZE, 8,
                EGL14.EGL_GREEN_SIZE, 8,
                EGL14.EGL_BLUE_SIZE, 8,
                EGL14.EGL_ALPHA_SIZE, 8,
                EGL14.EGL_DEPTH_SIZE, 16,
                EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
                EGL14.EGL_NONE
            )
            val configs = arrayOfNulls<EGLConfig>(1)
            val numConfigs = IntArray(1)
            var contextVersion = 3
            var choseConfig = EGL14.eglChooseConfig(display, gles3Attr, 0, configs, 0, 1, numConfigs, 0) &&
                numConfigs[0] > 0
            if (!choseConfig) {
                contextVersion = 2
                choseConfig = EGL14.eglChooseConfig(display, attr, 0, configs, 0, 1, numConfigs, 0)
            }
            if (!choseConfig) {
                return false
            }
            if (numConfigs[0] == 0) return false

            context = EGL14.eglCreateContext(
                display,
                configs[0],
                EGL14.EGL_NO_CONTEXT,
                intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, contextVersion, EGL14.EGL_NONE),
                0
            )
            if (context == EGL14.EGL_NO_CONTEXT && contextVersion == 3) {
                contextVersion = 2
                EGL14.eglChooseConfig(display, attr, 0, configs, 0, 1, numConfigs, 0)
                context = EGL14.eglCreateContext(
                    display,
                    configs[0],
                    EGL14.EGL_NO_CONTEXT,
                    intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE),
                    0
                )
            }
            if (context == EGL14.EGL_NO_CONTEXT) return false
            isGles3 = contextVersion >= 3

            eglSurface = EGL14.eglCreateWindowSurface(
                display,
                configs[0],
                surface,
                intArrayOf(EGL14.EGL_NONE),
                0
            )
            if (eglSurface == EGL14.EGL_NO_SURFACE) return false

            return EGL14.eglMakeCurrent(display, eglSurface, eglSurface, context)
        }

        private fun initGL() {
            gaussianProg = createProg(GAUSSIAN_VERTEX_SHADER, GAUSSIAN_FRAGMENT_SHADER)
            gaussianQuadProg = if (isGles3) createProg(GAUSSIAN_QUAD_VERTEX_SHADER, GAUSSIAN_QUAD_FRAGMENT_SHADER) else 0
            gaussianLayerProg = createProg(GAUSSIAN_LAYER_VERTEX_SHADER, GAUSSIAN_LAYER_FRAGMENT_SHADER)
            if (isGles3) {
                gaussianQuadCornerBuffer = createGaussianQuadCornerBuffer()
            }
            Log.d(TAG, "initGL gles3=$isGles3 gaussianQuadProg=$gaussianQuadProg cornerBuffer=$gaussianQuadCornerBuffer")
        }

        private fun configureTexture(textureId: Int) {
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textureId)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        }

        private fun consumePendingTilt(): Boolean {
            if (!tiltQueued.getAndSet(false)) return false
            val nextX = pendingTiltX
            val nextY = pendingTiltY
            if (abs(tiltX - nextX) <= 0.001f && abs(tiltY - nextY) <= 0.001f) return false
            tiltX = nextX
            tiltY = nextY
            return true
        }

        private fun nextTimedRenderDelayMs(): Long {
            if (loadingVisible && gaussianScene == null) return LOADING_FRAME_INTERVAL_MS
            if (gaussianScene == null || currentSplatBudget >= targetSplatBudget) {
                return Long.MAX_VALUE
            }
            val now = SystemClock.elapsedRealtime()
            return (lastSplatBudgetStepMs + SPLAT_BUDGET_STEP_INTERVAL_MS - now).coerceAtLeast(0L)
        }

        private fun draw() {
            if (!isThreadActive()) {
                logNoContent("skip draw: surface invalid")
                return
            }
            if (display == EGL14.EGL_NO_DISPLAY || eglSurface == EGL14.EGL_NO_SURFACE) {
                logNoContent("skip draw: EGL invalid display=$display surface=$eglSurface")
                return
            }
            if (!EGL14.eglMakeCurrent(display, eglSurface, eglSurface, context)) {
                Log.w(TAG, "eglMakeCurrent failed error=${EGL14.eglGetError()}")
                return
            }

            val scene = gaussianScene
            if (scene != null) {
                val drawMode = drawGaussianScene(scene)
                if (isThreadActive()) {
                    val swapped = EGL14.eglSwapBuffers(display, eglSurface)
                    logDraw(drawMode, swapped)
                }
                return
            }

            if (loadingVisible) {
                drawLoadingFrame()
                if (isThreadActive()) {
                    val swapped = EGL14.eglSwapBuffers(display, eglSurface)
                    logDraw("gaussian_loading", swapped)
                }
                return
            }

            logNoContent("skip draw: no gaussian scene")
        }

        private fun drawLoadingFrame() {
            GLES20.glViewport(0, 0, sW, sH)
            val t = (SystemClock.elapsedRealtime() % 1_200L) / 1_200f
            val pulse = ((sin(t * TWO_PI) + 1f) * 0.5f).coerceIn(0f, 1f)
            GLES20.glClearColor(
                0.015f + pulse * 0.025f,
                0.018f + pulse * 0.035f,
                0.028f + pulse * 0.060f,
                1f
            )
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)

            val dotCount = 5
            val baseSize = (minOf(sW, sH) * 0.018f).toInt().coerceIn(10, 28)
            val gap = (baseSize * 1.55f).toInt()
            val totalWidth = (dotCount - 1) * gap + baseSize
            val startX = (sW - totalWidth) / 2
            val centerY = sH / 2

            GLES20.glEnable(GLES20.GL_SCISSOR_TEST)
            for (i in 0 until dotCount) {
                val phase = (t + i * 0.12f) % 1f
                val wave = ((sin(phase * TWO_PI) + 1f) * 0.5f).coerceIn(0f, 1f)
                val size = (baseSize * (0.65f + wave * 0.55f)).toInt().coerceAtLeast(6)
                val alpha = 0.35f + wave * 0.55f
                val x = startX + i * gap + (baseSize - size) / 2
                val y = centerY - size / 2
                GLES20.glScissor(x, y, size, size)
                GLES20.glClearColor(
                    0.34f + alpha * 0.22f,
                    0.50f + alpha * 0.28f,
                    0.95f,
                    1f
                )
                GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
            }
            GLES20.glDisable(GLES20.GL_SCISSOR_TEST)
        }

        private fun logNoContent(message: String) {
            val now = SystemClock.elapsedRealtime()
            if (now - lastNoContentLogMs > 1_000L) {
                lastNoContentLogMs = now
                Log.w(TAG, "$message running=${isRunning.get()} rendering=$renderingEnabled queue=${messageQueue.size}")
            }
        }

        private fun logDraw(mode: String, swapped: Boolean) {
            drawCount++
            val now = SystemClock.elapsedRealtime()
            if (!swapped || now - lastDrawLogMs > DRAW_LOG_INTERVAL_MS) {
                lastDrawLogMs = now
                val loading = (targetSplatBudget - currentSplatBudget).coerceAtLeast(0)
                Log.d(
                    TAG,
                    "draw #$drawCount mode=$mode swapped=$swapped size=${sW}x$sH content=${cW}x$cH " +
                        "ready=${loading == 0} loading=$loading budget=$currentSplatBudget/$targetSplatBudget " +
                        "tilt=(${String.format("%.3f", tiltX)}, ${String.format("%.3f", tiltY)}) " +
                        "queue=${messageQueue.size} renderQueued=${renderQueued.get()} tiltQueued=${tiltQueued.get()}"
                )
                if (!swapped) {
                    Log.w(TAG, "eglSwapBuffers failed error=${EGL14.eglGetError()}")
                }
            }
        }

        private fun drawGaussianScene(scene: GaussianPlyLoader.GaussianScene): String {
            return if (ENABLE_GAUSSIAN_LAYER_CACHE && gaussianParams.useLayerCache && scene.depthLayers.isNotEmpty() && ensureGaussianLayerTextures(scene)) {
                drawGaussianLayers(scene)
                "gaussian_layers"
            } else if (isGles3 && gaussianQuadProg != 0 && gaussianQuadCornerBuffer != 0 && gaussianVboSet != null) {
                drawGaussianQuads(scene)
                "gaussian_quads"
            } else {
                drawGaussianUnavailable(scene)
                "gaussian_unavailable"
            }
        }

        private fun drawGaussianQuads(scene: GaussianPlyLoader.GaussianScene) {
            val vbo = gaussianVboSet?.takeIf { it.scene === scene && it.count == scene.count } ?: run {
                drawGaussianUnavailable(scene, "missing gaussian VBO for quad renderer")
                return
            }
            val drawSplatCount = currentSplatBudget.coerceIn(0, scene.count)
            if (drawSplatCount <= 0) {
                drawGaussianUnavailable(scene, "splat budget is zero")
                return
            }
            GLES20.glViewport(0, 0, sW, sH)
            GLES20.glClearColor(scene.backgroundR, scene.backgroundG, scene.backgroundB, 1f)
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
            GLES20.glDisable(GLES20.GL_DEPTH_TEST)
            GLES20.glDisable(GLES20.GL_DITHER)
            GLES20.glEnable(GLES20.GL_BLEND)
            GLES20.glBlendFunc(GLES20.GL_ONE, GLES20.GL_ONE_MINUS_SRC_ALPHA)
            GLES20.glUseProgram(gaussianQuadProg)

            if (!bindGaussianQuadVertexAttributes(vbo)) {
                drawGaussianUnavailable(scene, "failed to bind gaussian quad attributes")
                return
            }

            GLES20.glUniform2f(
                GLES20.glGetUniformLocation(gaussianQuadProg, "uSurfaceSize"),
                sW.coerceAtLeast(1).toFloat(),
                sH.coerceAtLeast(1).toFloat()
            )
            uploadGaussianCameraUniforms(scene)
            GLES20.glUniform1f(GLES20.glGetUniformLocation(gaussianQuadProg, "uTanHalfFov"), GAUSSIAN_TAN_HALF_FOV)
            GLES20.glUniform1f(
                GLES20.glGetUniformLocation(gaussianQuadProg, "uPointScale"),
                gaussianParams.splatScale.coerceIn(0.25f, 3f)
            )
            GLES20.glUniform1f(GLES20.glGetUniformLocation(gaussianQuadProg, "uOpacity"), gaussianParams.globalOpacity)
            GLES20.glUniform1f(GLES20.glGetUniformLocation(gaussianQuadProg, "uAlphaFalloff"), gaussianParams.alphaFalloff)
            GLES20.glUniform1f(GLES20.glGetUniformLocation(gaussianQuadProg, "uQuadExtent"), GAUSSIAN_QUAD_EXTENT)

            GLES30.glDrawArraysInstanced(GLES20.GL_TRIANGLE_STRIP, 0, GAUSSIAN_QUAD_VERTEX_COUNT, drawSplatCount)
            logGlError("gaussian draw quads")

            disableGaussianQuadVertexAttributes()
            GLES20.glDisable(GLES20.GL_BLEND)
        }

        private fun resetSplatBudget(scene: GaussianPlyLoader.GaussianScene) {
            targetSplatBudget = scene.count.coerceAtLeast(0)
            currentSplatBudget = if (targetSplatBudget <= SPLAT_BUDGET_INITIAL) {
                targetSplatBudget
            } else {
                SPLAT_BUDGET_INITIAL
            }
            lastSplatBudgetStepMs = SystemClock.elapsedRealtime()
        }

        private fun advanceSplatBudgetIfNeeded(): Boolean {
            if (currentSplatBudget >= targetSplatBudget) return false
            val now = SystemClock.elapsedRealtime()
            if (now - lastSplatBudgetStepMs < SPLAT_BUDGET_STEP_INTERVAL_MS) return false
            currentSplatBudget = (currentSplatBudget + SPLAT_BUDGET_STEP)
                .coerceAtMost(targetSplatBudget)
            lastSplatBudgetStepMs = now
            Log.d(
                TAG,
                "splat budget step ready=${currentSplatBudget >= targetSplatBudget} " +
                    "loading=${targetSplatBudget - currentSplatBudget} " +
                    "budget=$currentSplatBudget/$targetSplatBudget"
            )
            return true
        }

        private fun drawGaussianUnavailable(scene: GaussianPlyLoader.GaussianScene, reason: String = "quad renderer unavailable") {
            GLES20.glViewport(0, 0, sW, sH)
            GLES20.glClearColor(scene.backgroundR, scene.backgroundG, scene.backgroundB, 1f)
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
            logNoContent(
                "skip gaussian draw: $reason gles3=$isGles3 quadProg=$gaussianQuadProg " +
                    "cornerBuffer=$gaussianQuadCornerBuffer vbo=${gaussianVboSet != null}"
            )
        }

        private fun drawGaussianLayers(scene: GaussianPlyLoader.GaussianScene) {
            GLES20.glViewport(0, 0, sW, sH)
            GLES20.glClearColor(scene.backgroundR, scene.backgroundG, scene.backgroundB, 1f)
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
            GLES20.glDisable(GLES20.GL_DEPTH_TEST)
            GLES20.glDisable(GLES20.GL_DITHER)
            GLES20.glEnable(GLES20.GL_BLEND)
            GLES20.glBlendFunc(GLES20.GL_ONE, GLES20.GL_ONE_MINUS_SRC_ALPHA)
            GLES20.glUseProgram(gaussianLayerProg)

            val imageAspect = scene.imageWidth.toFloat() / scene.imageHeight.coerceAtLeast(1).toFloat()
            val screenAspect = sW.toFloat() / sH.coerceAtLeast(1).toFloat()
            val fillX: Float
            val fillY: Float
            if (imageAspect > screenAspect) {
                fillX = imageAspect / screenAspect
                fillY = 1f
            } else {
                fillX = 1f
                fillY = screenAspect / imageAspect
            }

            val mvp = FloatArray(16)
            Matrix.setIdentityM(mvp, 0)
            Matrix.scaleM(mvp, 0, fillX, fillY, 1f)

            val aPos = GLES20.glGetAttribLocation(gaussianLayerProg, "aPos")
            val aTex = GLES20.glGetAttribLocation(gaussianLayerProg, "aTex")
            vBuf.position(0)
            tBuf.position(0)
            GLES20.glEnableVertexAttribArray(aPos)
            GLES20.glVertexAttribPointer(aPos, 2, GLES20.GL_FLOAT, false, 8, vBuf)
            GLES20.glEnableVertexAttribArray(aTex)
            GLES20.glVertexAttribPointer(aTex, 2, GLES20.GL_FLOAT, false, 8, tBuf)
            GLES20.glUniformMatrix4fv(GLES20.glGetUniformLocation(gaussianLayerProg, "uMVP"), 1, false, mvp, 0)
            GLES20.glUniform1f(GLES20.glGetUniformLocation(gaussianLayerProg, "uOpacity"), 1f)
            GLES20.glUniform1i(GLES20.glGetUniformLocation(gaussianLayerProg, "sLayer"), 0)

            GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
            gaussianLayerTextures.forEach { texture ->
                GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texture.textureId)
                val offset = layerParallaxOffset(scene, texture.layer, fillX, fillY)
                GLES20.glUniform2f(GLES20.glGetUniformLocation(gaussianLayerProg, "uOffset"), offset[0], offset[1])
                GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
            }

            GLES20.glDisableVertexAttribArray(aPos)
            GLES20.glDisableVertexAttribArray(aTex)
            GLES20.glDisable(GLES20.GL_BLEND)
        }

        private fun drawGaussianPoints(scene: GaussianPlyLoader.GaussianScene) {
            if (gaussianProg == 0) {
                logNoContent("skip gaussian draw: program invalid")
                return
            }
            GLES20.glViewport(0, 0, sW, sH)
            GLES20.glClearColor(scene.backgroundR, scene.backgroundG, scene.backgroundB, 1f)
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
            GLES20.glDisable(GLES20.GL_DEPTH_TEST)
            GLES20.glDisable(GLES20.GL_DITHER)
            GLES20.glEnable(GLES20.GL_BLEND)
            GLES20.glBlendFunc(GLES20.GL_ONE, GLES20.GL_ONE_MINUS_SRC_ALPHA)
            GLES20.glUseProgram(gaussianProg)

            bindGaussianVertexAttributes(gaussianProg, scene, 0)

            val imageAspect = scene.imageWidth.toFloat() / scene.imageHeight.coerceAtLeast(1).toFloat()
            val screenAspect = sW.toFloat() / sH.coerceAtLeast(1).toFloat()
            val fillX: Float
            val fillY: Float
            if (imageAspect > screenAspect) {
                fillX = imageAspect / screenAspect
                fillY = 1f
            } else {
                fillX = 1f
                fillY = screenAspect / imageAspect
            }
            val pointScale = (
                max(
                    sW.toFloat() / scene.imageWidth.coerceAtLeast(1).toFloat(),
                    sH.toFloat() / scene.imageHeight.coerceAtLeast(1).toFloat()
                ) * GAUSSIAN_SPLAT_SCALE * gaussianParams.splatScale
                ).coerceIn(0.35f, 28f)

            GLES20.glUniform1f(GLES20.glGetUniformLocation(gaussianProg, "uFocal"), scene.focalLengthPx)
            GLES20.glUniform2f(
                GLES20.glGetUniformLocation(gaussianProg, "uImageSize"),
                scene.imageWidth.toFloat(),
                scene.imageHeight.toFloat()
            )
            GLES20.glUniform2f(GLES20.glGetUniformLocation(gaussianProg, "uFillScale"), fillX, fillY)
            GLES20.glUniform2f(GLES20.glGetUniformLocation(gaussianProg, "uTilt"), tiltX, tiltY)
            GLES20.glUniform1f(GLES20.glGetUniformLocation(gaussianProg, "uStrength"), parallaxStrength)
            GLES20.glUniform1f(GLES20.glGetUniformLocation(gaussianProg, "uFocusDepth"), scene.focusDepth)
            GLES20.glUniform1f(GLES20.glGetUniformLocation(gaussianProg, "uFarDepth"), scene.parallaxAnchorDepth)
            GLES20.glUniform1f(GLES20.glGetUniformLocation(gaussianProg, "uCameraZoom"), gaussianParams.cameraZoom)
            GLES20.glUniform2f(
                GLES20.glGetUniformLocation(gaussianProg, "uCenterOffset"),
                gaussianParams.centerOffsetX,
                gaussianParams.centerOffsetY
            )
            GLES20.glUniform1f(GLES20.glGetUniformLocation(gaussianProg, "uFocusDepthOffset"), gaussianParams.focusDepthOffset)
            GLES20.glUniform1f(GLES20.glGetUniformLocation(gaussianProg, "uPointScale"), pointScale)
            GLES20.glUniform1f(GLES20.glGetUniformLocation(gaussianProg, "uOpacity"), gaussianParams.globalOpacity)
            GLES20.glUniform1f(GLES20.glGetUniformLocation(gaussianProg, "uAlphaFalloff"), gaussianParams.alphaFalloff)
            GLES20.glUniform1f(GLES20.glGetUniformLocation(gaussianProg, "uMinPointSize"), gaussianParams.minPointSize)
            GLES20.glUniform1f(GLES20.glGetUniformLocation(gaussianProg, "uMaxPointSize"), gaussianParams.maxPointSize)
            GLES20.glDrawArrays(GLES20.GL_POINTS, 0, scene.count)
            logGlError("gaussian draw points")

            disableGaussianVertexAttributes(gaussianProg)
            GLES20.glDisable(GLES20.GL_BLEND)
        }

        private fun ensureGaussianLayerTextures(scene: GaussianPlyLoader.GaussianScene): Boolean {
            if (
                gaussianLayerTextures.isNotEmpty() &&
                gaussianLayerScene === scene &&
                gaussianLayerParams == gaussianParams
            ) {
                return true
            }
            if (gaussianLayerBuildFailedScene === scene && gaussianLayerParams == gaussianParams) {
                return false
            }

            deleteGaussianLayerTextures()
            if (scene.depthLayers.isEmpty()) return false

            val size = computeLayerTextureSize(scene)
            gaussianLayerTextureWidth = size.first
            gaussianLayerTextureHeight = size.second

            val textureIds = IntArray(scene.depthLayers.size)
            GLES20.glGenTextures(textureIds.size, textureIds, 0)
            val framebuffer = IntArray(1)
            GLES20.glGenFramebuffers(1, framebuffer, 0)

            val builtTextures = ArrayList<GaussianLayerTexture>(scene.depthLayers.size)
            var success = true
            for (index in scene.depthLayers.indices) {
                val layer = scene.depthLayers[index]
                val textureId = textureIds[index]
                GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textureId)
                configureTexture(textureId)
                GLES20.glTexImage2D(
                    GLES20.GL_TEXTURE_2D,
                    0,
                    GLES20.GL_RGBA,
                    gaussianLayerTextureWidth,
                    gaussianLayerTextureHeight,
                    0,
                    GLES20.GL_RGBA,
                    GLES20.GL_UNSIGNED_BYTE,
                    null
                )
                GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, framebuffer[0])
                GLES20.glFramebufferTexture2D(
                    GLES20.GL_FRAMEBUFFER,
                    GLES20.GL_COLOR_ATTACHMENT0,
                    GLES20.GL_TEXTURE_2D,
                    textureId,
                    0
                )
                val status = GLES20.glCheckFramebufferStatus(GLES20.GL_FRAMEBUFFER)
                if (status != GLES20.GL_FRAMEBUFFER_COMPLETE) {
                    Log.w(TAG, "layer framebuffer incomplete status=$status")
                    success = false
                    break
                }
                GLES20.glViewport(0, 0, gaussianLayerTextureWidth, gaussianLayerTextureHeight)
                GLES20.glClearColor(0f, 0f, 0f, 0f)
                GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
                drawGaussianPointRangeForLayerBake(scene, layer)
                builtTextures += GaussianLayerTexture(textureId, layer)
            }

            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
            GLES20.glDeleteFramebuffers(1, framebuffer, 0)

            if (!success) {
                GLES20.glDeleteTextures(textureIds.size, textureIds, 0)
                gaussianLayerBuildFailedScene = scene
                gaussianLayerParams = gaussianParams
                gaussianLayerTextures = emptyList()
                return false
            }

            gaussianLayerTextures = builtTextures
            gaussianLayerScene = scene
            gaussianLayerParams = gaussianParams
            gaussianLayerBuildFailedScene = null
            Log.d(
                TAG,
                "built gaussian layers count=${builtTextures.size} texture=${gaussianLayerTextureWidth}x$gaussianLayerTextureHeight"
            )
            return gaussianLayerTextures.isNotEmpty()
        }

        private fun drawGaussianPointRangeForLayerBake(
            scene: GaussianPlyLoader.GaussianScene,
            layer: GaussianPlyLoader.GaussianDepthLayer
        ) {
            GLES20.glDisable(GLES20.GL_DEPTH_TEST)
            GLES20.glDisable(GLES20.GL_DITHER)
            GLES20.glEnable(GLES20.GL_BLEND)
            GLES20.glBlendFunc(GLES20.GL_ONE, GLES20.GL_ONE_MINUS_SRC_ALPHA)
            GLES20.glUseProgram(gaussianProg)

            val usingVbo = bindGaussianVertexAttributes(gaussianProg, scene, layer.start)

            val textureScale = gaussianLayerTextureWidth.toFloat() / scene.imageWidth.coerceAtLeast(1).toFloat()
            val pointScale = (textureScale * GAUSSIAN_SPLAT_SCALE * gaussianParams.splatScale).coerceIn(0.35f, 28f)
            GLES20.glUniform1f(GLES20.glGetUniformLocation(gaussianProg, "uFocal"), scene.focalLengthPx)
            GLES20.glUniform2f(
                GLES20.glGetUniformLocation(gaussianProg, "uImageSize"),
                scene.imageWidth.toFloat(),
                scene.imageHeight.toFloat()
            )
            GLES20.glUniform2f(GLES20.glGetUniformLocation(gaussianProg, "uFillScale"), 1f, 1f)
            GLES20.glUniform2f(GLES20.glGetUniformLocation(gaussianProg, "uTilt"), 0f, 0f)
            GLES20.glUniform1f(GLES20.glGetUniformLocation(gaussianProg, "uStrength"), 0f)
            GLES20.glUniform1f(GLES20.glGetUniformLocation(gaussianProg, "uFocusDepth"), scene.focusDepth)
            GLES20.glUniform1f(GLES20.glGetUniformLocation(gaussianProg, "uFarDepth"), scene.parallaxAnchorDepth)
            GLES20.glUniform1f(GLES20.glGetUniformLocation(gaussianProg, "uCameraZoom"), 1f)
            GLES20.glUniform2f(GLES20.glGetUniformLocation(gaussianProg, "uCenterOffset"), 0f, 0f)
            GLES20.glUniform1f(GLES20.glGetUniformLocation(gaussianProg, "uFocusDepthOffset"), 0.25f)
            GLES20.glUniform1f(GLES20.glGetUniformLocation(gaussianProg, "uPointScale"), pointScale)
            GLES20.glUniform1f(GLES20.glGetUniformLocation(gaussianProg, "uOpacity"), gaussianParams.globalOpacity)
            GLES20.glUniform1f(GLES20.glGetUniformLocation(gaussianProg, "uAlphaFalloff"), gaussianParams.alphaFalloff)
            GLES20.glUniform1f(GLES20.glGetUniformLocation(gaussianProg, "uMinPointSize"), gaussianParams.minPointSize)
            GLES20.glUniform1f(GLES20.glGetUniformLocation(gaussianProg, "uMaxPointSize"), gaussianParams.maxPointSize)
            GLES20.glDrawArrays(GLES20.GL_POINTS, if (usingVbo) layer.start else 0, layer.count)

            disableGaussianVertexAttributes(gaussianProg)
            GLES20.glDisable(GLES20.GL_BLEND)
            scene.positions.position(0)
            scene.colors.position(0)
            scene.scales.position(0)
        }

        private fun uploadGaussianCameraUniforms(scene: GaussianPlyLoader.GaussianScene) {
            val radius = max(scene.sceneRadius, 0.001f)
            val targetX = scene.sceneCenterX + gaussianParams.centerOffsetX * radius
            val targetY = scene.sceneCenterY + gaussianParams.centerOffsetY * radius
            val targetZ = scene.sceneCenterZ + radius * gaussianParams.focusDepthOffset
            val frameDistance = max(scene.defaultCameraDistance, radius * 0.02f)
            val distance = max(frameDistance / max(gaussianParams.cameraZoom, 0.6f), radius * 0.02f)
            var tangentX = tiltX * frameDistance * max(parallaxStrength, 0.02f) * 2.4f
            var tangentY = -tiltY * frameDistance * max(parallaxStrength, 0.02f) * 2.4f
            val maxTangent = distance * 0.75f
            var tangentLength = sqrt(tangentX * tangentX + tangentY * tangentY)
            if (tangentLength > maxTangent && tangentLength > 0.0001f) {
                val scale = maxTangent / tangentLength
                tangentX *= scale
                tangentY *= scale
                tangentLength = maxTangent
            }
            val frontDepth = sqrt(max(distance * distance - tangentLength * tangentLength, distance * distance * 0.25f))
            val positionX = targetX + tangentX
            val positionY = targetY + tangentY
            val positionZ = targetZ - frontDepth

            var forwardX = targetX - positionX
            var forwardY = targetY - positionY
            var forwardZ = targetZ - positionZ
            val forwardLength = sqrt(forwardX * forwardX + forwardY * forwardY + forwardZ * forwardZ).coerceAtLeast(0.0001f)
            forwardX /= forwardLength
            forwardY /= forwardLength
            forwardZ /= forwardLength

            var rightX = forwardZ
            val rightY = 0f
            var rightZ = -forwardX
            val rightLength = sqrt(rightX * rightX + rightZ * rightZ).coerceAtLeast(0.0001f)
            rightX /= rightLength
            rightZ /= rightLength

            var upX = forwardY * rightZ
            var upY = forwardZ * rightX - forwardX * rightZ
            var upZ = -forwardY * rightX
            val upLength = sqrt(upX * upX + upY * upY + upZ * upZ).coerceAtLeast(0.0001f)
            upX /= upLength
            upY /= upLength
            upZ /= upLength

            GLES20.glUniform3f(GLES20.glGetUniformLocation(gaussianQuadProg, "uCameraPosition"), positionX, positionY, positionZ)
            GLES20.glUniform3f(GLES20.glGetUniformLocation(gaussianQuadProg, "uCameraRight"), rightX, rightY, rightZ)
            GLES20.glUniform3f(GLES20.glGetUniformLocation(gaussianQuadProg, "uCameraUp"), upX, upY, upZ)
            GLES20.glUniform3f(GLES20.glGetUniformLocation(gaussianQuadProg, "uCameraForward"), forwardX, forwardY, forwardZ)
        }

        private fun buildGaussianCovarianceBuffer(scene: GaussianPlyLoader.GaussianScene): FloatBuffer? {
            val rotations = scene.rotations?.duplicate()?.apply { position(0) } ?: return null
            val scales = scene.scales.duplicate().apply { position(0) }
            if (scales.remaining() < scene.count * 3 || rotations.remaining() < scene.count * 4) return null
            val covariance = ByteBuffer
                .allocateDirect(scene.count * COVARIANCE_FLOAT_COUNT * FLOAT_SIZE_BYTES)
                .order(ByteOrder.nativeOrder())
                .asFloatBuffer()
            repeat(scene.count) {
                val sx = max(scales.get(), 0.0001f)
                val sy = max(scales.get(), 0.0001f)
                val sz = max(scales.get(), 0.0001f)
                var qx = rotations.get()
                var qy = rotations.get()
                var qz = rotations.get()
                var qw = rotations.get()
                val quatLength = sqrt(qx * qx + qy * qy + qz * qz + qw * qw)
                if (quatLength > 0.000001f) {
                    qx /= quatLength
                    qy /= quatLength
                    qz /= quatLength
                    qw /= quatLength
                } else {
                    qx = 0f
                    qy = 0f
                    qz = 0f
                    qw = 1f
                }

                val x2 = qx + qx
                val y2 = qy + qy
                val z2 = qz + qz
                val xx = qx * x2
                val xy = qx * y2
                val xz = qx * z2
                val yy = qy * y2
                val yz = qy * z2
                val zz = qz * z2
                val wx = qw * x2
                val wy = qw * y2
                val wz = qw * z2
                val m0x = 1f - yy - zz
                val m0y = xy + wz
                val m0z = xz - wy
                val m1x = xy - wz
                val m1y = 1f - xx - zz
                val m1z = yz + wx
                val m2x = xz + wy
                val m2y = yz - wx
                val m2z = 1f - xx - yy
                val sx2 = sx * sx
                val sy2 = sy * sy
                val sz2 = sz * sz
                covariance.put(m0x * m0x * sx2 + m1x * m1x * sy2 + m2x * m2x * sz2)
                covariance.put(m0x * m0y * sx2 + m1x * m1y * sy2 + m2x * m2y * sz2)
                covariance.put(m0x * m0z * sx2 + m1x * m1z * sy2 + m2x * m2z * sz2)
                covariance.put(m0y * m0y * sx2 + m1y * m1y * sy2 + m2y * m2y * sz2)
                covariance.put(m0y * m0z * sx2 + m1y * m1z * sy2 + m2y * m2z * sz2)
                covariance.put(m0z * m0z * sx2 + m1z * m1z * sy2 + m2z * m2z * sz2)
            }
            covariance.position(0)
            return covariance
        }

        private fun uploadGaussianBuffers(scene: GaussianPlyLoader.GaussianScene): GaussianPlyLoader.GaussianScene {
            drainGlErrors("before gaussian VBO upload")
            val covariance = buildGaussianCovarianceBuffer(scene)
            if (covariance == null) {
                Log.w(TAG, "gaussian quad renderer requires rotation buffer")
                gaussianVboSet = null
                return scene
            }
            val ids = IntArray(3)
            GLES20.glGenBuffers(ids.size, ids, 0)
            if (ids.any { it == 0 }) {
                Log.w(TAG, "glGenBuffers failed for gaussian scene")
                GLES20.glDeleteBuffers(ids.size, ids, 0)
                gaussianVboSet = null
                return scene
            }

            val uploaded =
                uploadFloatBuffer(ids[0], scene.positions, scene.count * 3 * FLOAT_SIZE_BYTES, "positions") &&
                    uploadFloatBuffer(ids[1], scene.colors, scene.count * 4 * FLOAT_SIZE_BYTES, "colors") &&
                    uploadFloatBuffer(ids[2], covariance, scene.count * COVARIANCE_FLOAT_COUNT * FLOAT_SIZE_BYTES, "covariance")
            GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, 0)
            scene.positions.position(0)
            scene.colors.position(0)
            scene.scales.position(0)
            scene.rotations?.position(0)
            if (!uploaded) {
                GLES20.glDeleteBuffers(ids.size, ids, 0)
                gaussianVboSet = null
                Log.w(TAG, "gaussian VBO upload failed; quad renderer will not draw without VBO")
                return scene
            }
            val retainedScene = if (ENABLE_GAUSSIAN_LAYER_CACHE && gaussianParams.useLayerCache) {
                scene
            } else {
                scene.withoutCpuSplatBuffers()
            }
            gaussianVboSet = GaussianVboSet(
                scene = retainedScene,
                positionBuffer = ids[0],
                colorBuffer = ids[1],
                covarianceBuffer = ids[2],
                count = retainedScene.count
            )
            Log.d(TAG, "uploaded gaussian VBO count=${scene.count}")
            return retainedScene
        }

        private fun GaussianPlyLoader.GaussianScene.withoutCpuSplatBuffers(): GaussianPlyLoader.GaussianScene {
            val empty = FloatBuffer.allocate(0)
            return copy(
                positions = empty,
                colors = empty.duplicate(),
                scales = empty.duplicate(),
                rotations = empty.duplicate()
            )
        }

        private fun uploadFloatBuffer(
            bufferId: Int,
            buffer: FloatBuffer,
            sizeBytes: Int,
            label: String
        ): Boolean {
            buffer.position(0)
            GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, bufferId)
            GLES20.glBufferData(GLES20.GL_ARRAY_BUFFER, sizeBytes, buffer, GLES20.GL_STATIC_DRAW)
            val error = GLES20.glGetError()
            if (error != GLES20.GL_NO_ERROR) {
                Log.w(TAG, "glBufferData failed for $label size=$sizeBytes error=0x${error.toString(16)}")
                return false
            }
            return true
        }

        private fun createGaussianQuadCornerBuffer(): Int {
            val data = floatArrayOf(
                -1f, -1f,
                1f, -1f,
                -1f, 1f,
                1f, 1f
            )
            val buffer = ByteBuffer.allocateDirect(data.size * FLOAT_SIZE_BYTES)
                .order(ByteOrder.nativeOrder())
                .asFloatBuffer()
                .put(data)
            buffer.position(0)
            val ids = IntArray(1)
            GLES20.glGenBuffers(1, ids, 0)
            if (ids[0] == 0) return 0
            return if (uploadFloatBuffer(ids[0], buffer, data.size * FLOAT_SIZE_BYTES, "gaussian quad corners")) {
                ids[0]
            } else {
                GLES20.glDeleteBuffers(1, ids, 0)
                0
            }
        }

        private fun bindGaussianQuadVertexAttributes(vbo: GaussianVboSet): Boolean {
            val aCorner = GLES20.glGetAttribLocation(gaussianQuadProg, "aCorner")
            val aPos = GLES20.glGetAttribLocation(gaussianQuadProg, "aPos")
            val aColor = GLES20.glGetAttribLocation(gaussianQuadProg, "aColor")
            val aCovarianceA = GLES20.glGetAttribLocation(gaussianQuadProg, "aCovarianceA")
            val aCovarianceB = GLES20.glGetAttribLocation(gaussianQuadProg, "aCovarianceB")
            if (aCorner < 0 || aPos < 0 || aColor < 0 || aCovarianceA < 0 || aCovarianceB < 0) {
                Log.w(TAG, "missing gaussian quad attribute corner=$aCorner pos=$aPos color=$aColor covariance=($aCovarianceA,$aCovarianceB)")
                return false
            }

            GLES20.glEnableVertexAttribArray(aCorner)
            GLES20.glEnableVertexAttribArray(aPos)
            GLES20.glEnableVertexAttribArray(aColor)
            GLES20.glEnableVertexAttribArray(aCovarianceA)
            GLES20.glEnableVertexAttribArray(aCovarianceB)

            GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, gaussianQuadCornerBuffer)
            GLES20.glVertexAttribPointer(aCorner, 2, GLES20.GL_FLOAT, false, 0, 0)
            GLES30.glVertexAttribDivisor(aCorner, 0)

            GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, vbo.positionBuffer)
            GLES20.glVertexAttribPointer(aPos, 3, GLES20.GL_FLOAT, false, 0, 0)
            GLES30.glVertexAttribDivisor(aPos, 1)

            GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, vbo.colorBuffer)
            GLES20.glVertexAttribPointer(aColor, 4, GLES20.GL_FLOAT, false, 0, 0)
            GLES30.glVertexAttribDivisor(aColor, 1)

            GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, vbo.covarianceBuffer)
            val covarianceStride = COVARIANCE_FLOAT_COUNT * FLOAT_SIZE_BYTES
            GLES20.glVertexAttribPointer(aCovarianceA, 3, GLES20.GL_FLOAT, false, covarianceStride, 0)
            GLES30.glVertexAttribDivisor(aCovarianceA, 1)
            GLES20.glVertexAttribPointer(aCovarianceB, 3, GLES20.GL_FLOAT, false, covarianceStride, 3 * FLOAT_SIZE_BYTES)
            GLES30.glVertexAttribDivisor(aCovarianceB, 1)

            GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, 0)
            return true
        }

        private fun disableGaussianQuadVertexAttributes() {
            listOf("aCorner", "aPos", "aColor", "aCovarianceA", "aCovarianceB").forEach { name ->
                val location = GLES20.glGetAttribLocation(gaussianQuadProg, name)
                if (location >= 0) {
                    GLES30.glVertexAttribDivisor(location, 0)
                    GLES20.glDisableVertexAttribArray(location)
                }
            }
            GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, 0)
        }

        private fun bindGaussianVertexAttributes(
            program: Int,
            scene: GaussianPlyLoader.GaussianScene,
            start: Int
        ): Boolean {
            val aPos = GLES20.glGetAttribLocation(program, "aPos")
            val aColor = GLES20.glGetAttribLocation(program, "aColor")
            val aScale = GLES20.glGetAttribLocation(program, "aScale")
            if (aPos < 0 || aColor < 0 || aScale < 0) {
                Log.w(TAG, "missing gaussian attribute pos=$aPos color=$aColor scale=$aScale")
                return false
            }
            GLES20.glEnableVertexAttribArray(aPos)
            GLES20.glEnableVertexAttribArray(aColor)
            GLES20.glEnableVertexAttribArray(aScale)

            GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, 0)
            scene.positions.position(start * 3)
            scene.colors.position(start * 4)
            scene.scales.position(start * 3)
            GLES20.glVertexAttribPointer(aPos, 3, GLES20.GL_FLOAT, false, 0, scene.positions)
            GLES20.glVertexAttribPointer(aColor, 4, GLES20.GL_FLOAT, false, 0, scene.colors)
            GLES20.glVertexAttribPointer(aScale, 3, GLES20.GL_FLOAT, false, 0, scene.scales)
            return false
        }

        private fun disableGaussianVertexAttributes(program: Int) {
            GLES20.glGetAttribLocation(program, "aPos")
                .takeIf { it >= 0 }
                ?.let { GLES20.glDisableVertexAttribArray(it) }
            GLES20.glGetAttribLocation(program, "aColor")
                .takeIf { it >= 0 }
                ?.let { GLES20.glDisableVertexAttribArray(it) }
            GLES20.glGetAttribLocation(program, "aScale")
                .takeIf { it >= 0 }
                ?.let { GLES20.glDisableVertexAttribArray(it) }
            GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, 0)
        }

        private fun drainGlErrors(label: String) {
            var error = GLES20.glGetError()
            var drained = false
            while (error != GLES20.GL_NO_ERROR) {
                drained = true
                Log.w(TAG, "$label drained glError=0x${error.toString(16)}")
                error = GLES20.glGetError()
            }
            if (drained) {
                lastGlErrorLogMs = SystemClock.elapsedRealtime()
            }
        }

        private fun logGlError(label: String) {
            val error = GLES20.glGetError()
            if (error == GLES20.GL_NO_ERROR) return
            val now = SystemClock.elapsedRealtime()
            if (now - lastGlErrorLogMs > 1_000L) {
                lastGlErrorLogMs = now
                Log.w(TAG, "$label glError=0x${error.toString(16)}")
            }
        }

        private fun layerParallaxOffset(
            scene: GaussianPlyLoader.GaussianScene,
            layer: GaussianPlyLoader.GaussianDepthLayer,
            fillX: Float,
            fillY: Float
        ): FloatArray {
            val z = layer.depth.coerceAtLeast(0.02f)
            val farZ = max(scene.parallaxAnchorDepth, z + 0.001f)
            val parallax = max((1f / z) - (1f / farZ), 0f)
            val cameraX = tiltX * parallaxStrength * farZ * 1.65f
            val cameraY = -tiltY * parallaxStrength * farZ * 1.65f
            val offsetX = -cameraX * parallax *
                (2f * scene.focalLengthPx / scene.imageWidth.coerceAtLeast(1).toFloat()) * fillX
            val offsetY = cameraY * parallax *
                (2f * scene.focalLengthPx / scene.imageHeight.coerceAtLeast(1).toFloat()) * fillY
            return floatArrayOf(offsetX, offsetY)
        }

        private fun computeLayerTextureSize(scene: GaussianPlyLoader.GaussianScene): Pair<Int, Int> {
            val maxTextureSize = IntArray(1)
            GLES20.glGetIntegerv(GLES20.GL_MAX_TEXTURE_SIZE, maxTextureSize, 0)
            val limit = minOf(
                GAUSSIAN_LAYER_TEXTURE_MAX_SIZE,
                maxTextureSize[0].takeIf { it > 0 } ?: GAUSSIAN_LAYER_TEXTURE_MAX_SIZE
            ).coerceAtLeast(256)
            val imageWidth = scene.imageWidth.coerceAtLeast(1)
            val imageHeight = scene.imageHeight.coerceAtLeast(1)
            val imageAspect = imageWidth.toFloat() / imageHeight.toFloat()
            return if (imageAspect >= 1f) {
                val width = limit
                val height = (limit / imageAspect).toInt().coerceAtLeast(1)
                width to height
            } else {
                val height = limit
                val width = (limit * imageAspect).toInt().coerceAtLeast(1)
                width to height
            }
        }

        private fun deleteGaussianLayerTextures() {
            if (gaussianLayerTextures.isNotEmpty()) {
                val ids = gaussianLayerTextures.map { it.textureId }.toIntArray()
                GLES20.glDeleteTextures(ids.size, ids, 0)
            }
            gaussianLayerTextures = emptyList()
            gaussianLayerScene = null
            gaussianLayerParams = null
            gaussianLayerBuildFailedScene = null
            gaussianLayerTextureWidth = 1
            gaussianLayerTextureHeight = 1
        }

        private fun deleteGaussianBuffers() {
            gaussianVboSet?.let { vbo ->
                val ids = intArrayOf(vbo.positionBuffer, vbo.colorBuffer, vbo.covarianceBuffer)
                GLES20.glDeleteBuffers(ids.size, ids, 0)
            }
            gaussianVboSet = null
        }

        private fun deleteGaussianQuadCornerBuffer() {
            if (gaussianQuadCornerBuffer != 0) {
                val ids = intArrayOf(gaussianQuadCornerBuffer)
                GLES20.glDeleteBuffers(1, ids, 0)
                gaussianQuadCornerBuffer = 0
            }
        }

        private fun createProg(vertex: String, fragment: String): Int {
            val vs = GLES20.glCreateShader(GLES20.GL_VERTEX_SHADER)
            GLES20.glShaderSource(vs, vertex)
            GLES20.glCompileShader(vs)
            logShaderError(vs, "vertex")

            val fs = GLES20.glCreateShader(GLES20.GL_FRAGMENT_SHADER)
            GLES20.glShaderSource(fs, fragment)
            GLES20.glCompileShader(fs)
            logShaderError(fs, "fragment")

            val p = GLES20.glCreateProgram()
            GLES20.glAttachShader(p, vs)
            GLES20.glAttachShader(p, fs)
            GLES20.glLinkProgram(p)

            val status = IntArray(1)
            GLES20.glGetProgramiv(p, GLES20.GL_LINK_STATUS, status, 0)
            if (status[0] == GLES20.GL_FALSE) {
                Log.e(TAG, "Program link error: ${GLES20.glGetProgramInfoLog(p)}")
            }
            return p
        }

        private fun logShaderError(shader: Int, label: String) {
            val status = IntArray(1)
            GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, status, 0)
            if (status[0] == GLES20.GL_FALSE) {
                Log.e(TAG, "$label shader compile error: ${GLES20.glGetShaderInfoLog(shader)}")
            }
        }

        private fun destroyEGL() {
            if (display != EGL14.EGL_NO_DISPLAY) {
                deleteGaussianLayerTextures()
                deleteGaussianBuffers()
                deleteGaussianQuadCornerBuffer()
                EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
                if (eglSurface != EGL14.EGL_NO_SURFACE) {
                    EGL14.eglDestroySurface(display, eglSurface)
                    eglSurface = EGL14.EGL_NO_SURFACE
                }
                if (context != EGL14.EGL_NO_CONTEXT) {
                    EGL14.eglDestroyContext(display, context)
                    context = EGL14.EGL_NO_CONTEXT
                }
                EGL14.eglTerminate(display)
                display = EGL14.EGL_NO_DISPLAY
            }
        }

        fun finish() {
            isRunning.set(false)
            interrupt()
            try {
                join(1000)
            } catch (_: InterruptedException) {
            }
        }

        fun finishAndWait(timeoutMs: Long) {
            isRunning.set(false)
            isSurfaceValid.set(false)
            messageQueue.clear()
            renderQueued.set(false)
            tiltQueued.set(false)
            interrupt()
            try {
                join(timeoutMs)
            } catch (_: InterruptedException) {
            }
        }
    }

    companion object {
        private const val TAG = "DepthGLRenderer"
        private const val EGL_OPENGL_ES3_BIT_KHR = 0x00000040
        private const val RENDER_THREAD_STOP_TIMEOUT_MS = 1_500L
        private const val DRAW_LOG_INTERVAL_MS = 1_000L
        private const val LOADING_FRAME_INTERVAL_MS = 120L
        private const val MAX_VIEW_TILT = 0.72f
        private const val TWO_PI = 6.2831855f
        private const val GAUSSIAN_SPLAT_SCALE = 1.0f
        private const val GAUSSIAN_QUAD_EXTENT = 1.0f
        private const val GAUSSIAN_QUAD_VERTEX_COUNT = 4
        private const val COVARIANCE_FLOAT_COUNT = 6
        private const val GAUSSIAN_LAYER_TEXTURE_MAX_SIZE = 768
        private const val GAUSSIAN_TAN_HALF_FOV = 0.57735026f
        private const val FLOAT_SIZE_BYTES = 4
        private const val ENABLE_GAUSSIAN_LAYER_CACHE = false
        private const val SPLAT_BUDGET_INITIAL = 500_000
        private const val SPLAT_BUDGET_STEP = 250_000
        private const val SPLAT_BUDGET_STEP_INTERVAL_MS = 120L

        private const val GAUSSIAN_VERTEX_SHADER = """
            attribute vec3 aPos;
            attribute vec4 aColor;
            attribute vec3 aScale;
            uniform float uFocal;
            uniform vec2 uImageSize;
            uniform vec2 uFillScale;
            uniform vec2 uTilt;
            uniform float uStrength;
            uniform float uFocusDepth;
            uniform float uFarDepth;
            uniform vec3 uSceneCenter;
            uniform float uSceneRadius;
            uniform float uDefaultCameraDistance;
            uniform float uTanHalfFov;
            uniform float uCameraZoom;
            uniform vec2 uCenterOffset;
            uniform float uFocusDepthOffset;
            uniform float uPointScale;
            uniform float uMinPointSize;
            uniform float uMaxPointSize;
            varying vec4 vColor;
            varying vec3 vShape;

            struct CameraFrame {
                vec3 position;
                vec3 right;
                vec3 up;
                vec3 forward;
            };

            CameraFrame wallpaperCamera() {
                float radius = max(uSceneRadius, 0.001);
                vec3 target = vec3(
                    uSceneCenter.x + uCenterOffset.x * radius,
                    uSceneCenter.y + uCenterOffset.y * radius,
                    uSceneCenter.z + radius * uFocusDepthOffset
                );
                float frameDistance = max(uDefaultCameraDistance, radius * 0.02);
                float distance = max(frameDistance / max(uCameraZoom, 0.6), radius * 0.02);
                vec2 tangent = vec2(uTilt.x, -uTilt.y) * frameDistance * max(uStrength, 0.02) * 2.4;
                float maxTangent = distance * 0.75;
                float tangentLength = length(tangent);
                if (tangentLength > maxTangent && tangentLength > 0.0001) {
                    tangent *= maxTangent / tangentLength;
                    tangentLength = maxTangent;
                }
                float frontDepth = sqrt(max(distance * distance - tangentLength * tangentLength, distance * distance * 0.25));
                vec3 position = target + vec3(tangent.x, tangent.y, -frontDepth);
                vec3 forward = normalize(target - position);
                vec3 right = normalize(vec3(forward.z, 0.0, -forward.x));
                vec3 up = normalize(cross(forward, right));
                CameraFrame frame;
                frame.position = position;
                frame.right = right;
                frame.up = up;
                frame.forward = forward;
                return frame;
            }

            void main() {
                CameraFrame camera = wallpaperCamera();
                vec3 rel = aPos - camera.position;
                float viewX = dot(rel, camera.right);
                float viewY = dot(rel, camera.up);
                float z = max(dot(rel, camera.forward), 0.02);
                float x = (viewX / z) * (2.0 * uFocal / uImageSize.x) * uFillScale.x;
                float y = -(viewY / z) * (2.0 * uFocal / uImageSize.y) * uFillScale.y;
                gl_Position = vec4(x, y, 0.0, 1.0);

                float sx = max(aScale.x, 0.0001);
                float sy = max(aScale.y, 0.0001);
                float majorScale = max(max(sx, sy), 0.0006);
                float minorScale = max(min(sx, sy), 0.0001);
                float pixelSize = majorScale * uFocal / z * uPointScale * 1.12;
                gl_PointSize = clamp(pixelSize, uMinPointSize, uMaxPointSize);
                vColor = aColor;
                vShape = vec3(1.0, clamp(minorScale / majorScale, 0.18, 1.0), aScale.z);
            }
        """

        private const val GAUSSIAN_FRAGMENT_SHADER = """
            #ifdef GL_FRAGMENT_PRECISION_HIGH
            precision highp float;
            #else
            precision mediump float;
            #endif
            varying vec4 vColor;
            varying vec3 vShape;
            uniform float uOpacity;
            uniform float uAlphaFalloff;

            void main() {
                vec2 p = gl_PointCoord * 2.0 - 1.0;
                float c = cos(vShape.z);
                float s = sin(vShape.z);
                vec2 rotated = vec2(c * p.x + s * p.y, -s * p.x + c * p.y);
                vec2 shaped = rotated / vShape.xy;
                float radius2 = dot(shaped, shaped);
                if (radius2 > 1.44) {
                    discard;
                }
                float alpha = vColor.a * uOpacity * exp(-radius2 * uAlphaFalloff);
                if (alpha < 0.016) {
                    discard;
                }
                gl_FragColor = vec4(vColor.rgb * alpha, alpha);
            }
        """

        private const val GAUSSIAN_QUAD_VERTEX_SHADER = """
            attribute vec2 aCorner;
            attribute vec3 aPos;
            attribute vec4 aColor;
            attribute vec3 aCovarianceA;
            attribute vec3 aCovarianceB;
            uniform vec2 uSurfaceSize;
            uniform vec3 uCameraPosition;
            uniform vec3 uCameraRight;
            uniform vec3 uCameraUp;
            uniform vec3 uCameraForward;
            uniform float uTanHalfFov;
            uniform float uPointScale;
            uniform float uQuadExtent;
            varying vec4 vColor;
            varying vec2 vLocal;
            varying float vAaFactor;

            void main() {
                vec3 rel = aPos - uCameraPosition;
                float viewX = dot(rel, uCameraRight);
                float viewY = dot(rel, uCameraUp);
                float rawZ = dot(rel, uCameraForward);
                float z = max(rawZ, 0.02);
                float focalPixels = 0.5 * uSurfaceSize.y / max(uTanHalfFov, 0.001);
                vec2 center = vec2(
                    (viewX / z) * (2.0 * focalPixels / uSurfaceSize.x),
                    -(viewY / z) * (2.0 * focalPixels / uSurfaceSize.y)
                );

                vec3 jx = (uCameraRight * z - uCameraForward * viewX) * (focalPixels / (z * z));
                vec3 jy = -(uCameraUp * z - uCameraForward * viewY) * (focalPixels / (z * z));
                vec3 covJx = vec3(
                    dot(aCovarianceA, jx),
                    aCovarianceA.y * jx.x + aCovarianceB.x * jx.y + aCovarianceB.y * jx.z,
                    aCovarianceA.z * jx.x + aCovarianceB.y * jx.y + aCovarianceB.z * jx.z
                );
                vec3 covJy = vec3(
                    dot(aCovarianceA, jy),
                    aCovarianceA.y * jy.x + aCovarianceB.x * jy.y + aCovarianceB.y * jy.z,
                    aCovarianceA.z * jy.x + aCovarianceB.y * jy.y + aCovarianceB.z * jy.z
                );
                float pointScale2 = uPointScale * uPointScale;
                float rawCovXX = dot(jx, covJx) * pointScale2;
                float rawCovXY = dot(jx, covJy) * pointScale2;
                float rawCovYY = dot(jy, covJy) * pointScale2;
                float detOrig = max(rawCovXX * rawCovYY - rawCovXY * rawCovXY, 0.0);
                float covXX = rawCovXX + 0.3;
                float covXY = rawCovXY;
                float covYY = rawCovYY + 0.3;
                float detBlur = max(covXX * covYY - covXY * covXY, 0.000001);
                float mid = 0.5 * (covXX + covYY);
                float diff = 0.5 * (covXX - covYY);
                float radius = sqrt(max(diff * diff + covXY * covXY, 0.0));
                float lambda1 = max(mid + radius, 1.0);
                float lambda2 = max(mid - radius, 1.0);
                float majorPixels = 2.0 * sqrt(2.0 * lambda1);
                float minorPixels = 2.0 * sqrt(2.0 * lambda2);
                vec2 majorAxis = normalize(vec2(covXY, lambda1 - covXX));
                if (abs(majorAxis.x) + abs(majorAxis.y) < 0.0001) {
                    majorAxis = vec2(1.0, 0.0);
                }
                vec2 minorAxis = vec2(-majorAxis.y, majorAxis.x);
                vec2 local = aCorner * uQuadExtent;
                vec2 pixelOffset = majorAxis * local.x * majorPixels + minorAxis * local.y * minorPixels;
                vec2 clipOffset = pixelOffset * vec2(2.0 / uSurfaceSize.x, 2.0 / uSurfaceSize.y);
                gl_Position = vec4(center + clipOffset, 0.0, 1.0);
                vColor = vec4(aColor.rgb, aColor.a * step(0.02, rawZ));
                vLocal = local;
                vAaFactor = clamp(sqrt(max(detOrig / detBlur, 0.0)), 0.0, 1.0);
            }
        """

        private const val GAUSSIAN_QUAD_FRAGMENT_SHADER = """
            #ifdef GL_FRAGMENT_PRECISION_HIGH
            precision highp float;
            #else
            precision mediump float;
            #endif
            varying vec4 vColor;
            varying vec2 vLocal;
            varying float vAaFactor;
            uniform float uOpacity;
            uniform float uAlphaFalloff;
            uniform float uQuadExtent;
            const float ALPHA_CLIP = 0.0039215686;

            float normExp(float radius2, float falloff) {
                float k = max(4.0 * falloff, 0.1);
                float edge = exp(-k);
                return (exp(-k * radius2) - edge) / max(1.0 - edge, 0.0001);
            }

            void main() {
                float radius2 = dot(vLocal, vLocal);
                float extent = max(uQuadExtent, 1.0);
                if (radius2 > extent * extent) {
                    discard;
                }
                float falloff = max(uAlphaFalloff, 0.1);
                float alpha = vColor.a * vAaFactor * uOpacity * normExp(radius2, falloff);
                if (alpha < ALPHA_CLIP) {
                    discard;
                }
                gl_FragColor = vec4(vColor.rgb * alpha, alpha);
            }
        """

        private const val GAUSSIAN_LAYER_VERTEX_SHADER = """
            attribute vec4 aPos;
            attribute vec2 aTex;
            uniform mat4 uMVP;
            uniform vec2 uOffset;
            varying vec2 vTex;

            void main() {
                vec4 pos = uMVP * aPos;
                pos.xy += uOffset;
                gl_Position = pos;
                vTex = aTex;
            }
        """

        private const val GAUSSIAN_LAYER_FRAGMENT_SHADER = """
            precision mediump float;
            varying vec2 vTex;
            uniform sampler2D sLayer;
            uniform float uOpacity;

            void main() {
                gl_FragColor = texture2D(sLayer, vTex) * uOpacity;
            }
        """
    }
}
