package com.zeaze.tianyinwallpaper.renderer

import android.app.Presentation
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.SurfaceTexture
import android.graphics.drawable.ColorDrawable
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.net.Uri
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLSurface
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.opengl.Matrix
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Display
import android.view.MotionEvent
import android.view.Surface
import android.view.View
import android.view.ViewGroup
import android.webkit.WebView
import com.zeaze.tianyinwallpaper.utils.GaussianPlyLoader
import com.zeaze.tianyinwallpaper.utils.GaussianSogLoader
import com.zeaze.tianyinwallpaper.utils.SuperSplatWebController
import com.zeaze.tianyinwallpaper.utils.SuperSplatWebParams
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class WebGaussianWallpaperRenderer(
    private val context: Context
) : NativeGaussianRenderer {
    private val appContext = context.applicationContext
    private val mainHandler = Handler(Looper.getMainLooper())
    private val displayManager = appContext.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
    private val controller = SuperSplatWebController(appContext)
    private val lifecycleLock = Object()
    private val renderSignal = Object()
    private val messageQueue = ConcurrentLinkedQueue<RenderMessage>()
    private val isRunning = AtomicBoolean(false)
    private val isSurfaceValid = AtomicBoolean(false)
    private val renderQueued = AtomicBoolean(false)

    @Volatile private var renderingEnabled = false
    @Volatile private var surfaceWidth = 1
    @Volatile private var surfaceHeight = 1
    @Volatile private var pendingUriString: String? = null
    @Volatile private var pendingParams: SuperSplatWebParams? = null
    @Volatile private var backgroundLoadingUri: String? = null
    @Volatile private var resolvedBackgroundUri: String? = null
    @Volatile private var resolvedBackgroundColor: GaussianSogLoader.SogBackgroundColor? = null

    private var eglThread: EglRenderThread? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var presentation: Presentation? = null
    private var webView: WebView? = null
    private var webOutputSurface: Surface? = null
    private var virtualDisplayWidth = 0
    private var virtualDisplayHeight = 0
    @Volatile private var webLoadingListener: ((Boolean) -> Unit)? = null
    @Volatile private var webCenterOffsetListener: ((Float, Float) -> Unit)? = null
    @Volatile private var backdropFrameListener: ((Bitmap) -> Unit)? = null

    init {
        controller.onRenderRequested = { requestRender() }
        controller.onLoadingChanged = { loading ->
            Log.d(TAG, "web loading=$loading uri=$pendingUriString")
            webLoadingListener?.invoke(loading)
        }
        controller.onCenterOffsetChange = { x, y -> webCenterOffsetListener?.invoke(x, y) }
    }

    override fun start(surface: Surface) {
        synchronized(lifecycleLock) {
            val thread = eglThread
            if (isRunning.get() && isSurfaceValid.get() && thread?.wallpaperSurface === surface) {
                requestRender()
                return
            }
            if (isRunning.get() || thread?.isAlive == true) {
                stopAndWaitLocked(RENDER_THREAD_STOP_TIMEOUT_MS)
            }
            isSurfaceValid.set(true)
            isRunning.set(true)
            val nextThread = EglRenderThread(surface, surfaceWidth, surfaceHeight)
            eglThread = nextThread
            nextThread.start()
            val initOk = nextThread.waitForInit(RENDER_THREAD_INIT_TIMEOUT_MS)
            Log.d(TAG, "start initOk=$initOk size=${surfaceWidth}x$surfaceHeight")
            if (!initOk) {
                stopAndWaitLocked(RENDER_THREAD_STOP_TIMEOUT_MS)
            }
        }
    }

    override fun stop() {
        synchronized(lifecycleLock) {
            stopAndWaitLocked(RENDER_THREAD_STOP_TIMEOUT_MS)
        }
    }

    override fun stopAndWait(timeoutMs: Long) {
        synchronized(lifecycleLock) {
            stopAndWaitLocked(timeoutMs)
        }
    }

    override fun resize(width: Int, height: Int) {
        surfaceWidth = width.coerceAtLeast(1)
        surfaceHeight = height.coerceAtLeast(1)
        messageQueue.offer(RenderMessage.Resize(surfaceWidth, surfaceHeight))
        signalRenderThread()
        runOnMain(wait = false) {
            ensureWebPipelineOnMain()
        }
    }

    override fun loadGaussians(scene: GaussianPlyLoader.GaussianScene) = Unit

    override fun loadSogGaussians(scenes: List<GaussianSogLoader.SogGpuScene>): Boolean = false

    override fun loadWebGaussians(uriString: String, params: SuperSplatWebParams): Boolean {
        if (uriString.isBlank()) return false
        pendingUriString = uriString
        val cachedBackground = resolvedBackgroundColor.takeIf { resolvedBackgroundUri == uriString }
        pendingParams = cachedBackground?.let { color ->
            params.copy(
                backgroundRed = color.red,
                backgroundGreen = color.green,
                backgroundBlue = color.blue
            )
        } ?: params
        resolveBackgroundColor(uriString)
        runOnMain(wait = false) {
            ensureWebPipelineOnMain()
            loadPendingModelOnMain()
        }
        requestRender()
        return true
    }

    private fun resolveBackgroundColor(uriString: String) {
        if (resolvedBackgroundUri == uriString && resolvedBackgroundColor != null) return
        if (backgroundLoadingUri == uriString) return
        backgroundLoadingUri = uriString
        Thread({
            val color = runCatching {
                GaussianSogLoader.loadBackgroundColorOrThrow(appContext, uriString)
            }.onFailure {
                Log.w(TAG, "automatic web background failed uri=$uriString", it)
            }.getOrNull()
            if (backgroundLoadingUri == uriString) backgroundLoadingUri = null
            if (color == null || pendingUriString != uriString) return@Thread
            resolvedBackgroundUri = uriString
            resolvedBackgroundColor = color
            val current = pendingParams ?: return@Thread
            val next = current.copy(
                backgroundRed = color.red,
                backgroundGreen = color.green,
                backgroundBlue = color.blue
            )
            pendingParams = next
            controller.setParams(next)
            requestRender()
            Log.d(TAG, "automatic web background=${color.red},${color.green},${color.blue}")
        }, "WebGaussianBackground").start()
    }

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        val target = webView ?: return false
        val copy = MotionEvent.obtain(event)
        return try {
            target.dispatchTouchEvent(copy)
        } finally {
            copy.recycle()
        }
    }

    override fun setWebLoadingListener(listener: ((Boolean) -> Unit)?) {
        webLoadingListener = listener
    }

    override fun setWebCenterOffsetListener(listener: ((Float, Float) -> Unit)?) {
        webCenterOffsetListener = listener
    }

    override fun setBackdropFrameListener(listener: ((Bitmap) -> Unit)?) {
        backdropFrameListener = listener
        if (listener != null) requestRender()
    }

    override fun updateTilt(x: Float, y: Float) {
        controller.setTilt(x, y)
    }

    override fun updateParams(parallaxStrength: Float, blurStrength: Float) {
        val current = pendingParams ?: return
        val next = current.copy(parallaxStrength = parallaxStrength)
        pendingParams = next
        controller.setParams(next)
    }

    override fun updateGaussianParams(params: DepthGLRenderer.GaussianRenderParams) {
        val current = pendingParams ?: return
        val next = current.copy(
            cameraZoom = params.cameraZoom,
            centerOffsetX = params.centerOffsetX,
            centerOffsetY = params.centerOffsetY,
            focusDepth = params.focusDepthOffset
        )
        pendingParams = next
        controller.setParams(next)
    }

    override fun resetCamera() {
        controller.resetSensorBaseline = true
        controller.resetCamera()
    }

    override fun showLoading(enabled: Boolean) {
        Log.d(TAG, "showLoading enabled=$enabled")
    }

    override fun requestRender() {
        if (renderQueued.getAndSet(true)) return
        messageQueue.offer(RenderMessage.Render)
        signalRenderThread()
    }

    override fun setRenderingEnabled(enabled: Boolean) {
        renderingEnabled = enabled
        runOnMain(wait = false) {
            webView?.let {
                if (enabled) {
                    it.onResume()
                } else {
                    it.onPause()
                }
            }
        }
        if (enabled) requestRender()
    }

    private fun stopAndWaitLocked(timeoutMs: Long) {
        isSurfaceValid.set(false)
        val wasRunning = isRunning.getAndSet(false)
        messageQueue.clear()
        renderQueued.set(false)
        signalRenderThread()
        releaseWebPipeline(wait = Looper.myLooper() != Looper.getMainLooper(), timeoutMs = timeoutMs)
        val oldThread = eglThread
        oldThread?.finishAndWait(timeoutMs)
        if (oldThread?.isAlive == true) {
            Log.w(TAG, "render thread did not stop within ${timeoutMs}ms")
        }
        eglThread = null
        if (wasRunning) {
            Log.d(TAG, "stopped")
        }
    }

    private fun ensureWebPipelineOnMain() {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            mainHandler.post { ensureWebPipelineOnMain() }
            return
        }
        if (!isRunning.get() || !isSurfaceValid.get()) return
        val texture = eglThread?.webSurfaceTexture ?: return
        val width = surfaceWidth.coerceAtLeast(1)
        val height = surfaceHeight.coerceAtLeast(1)
        if (virtualDisplay != null && virtualDisplayWidth == width && virtualDisplayHeight == height) {
            loadPendingModelOnMain()
            return
        }

        releaseWebObjectsOnMain()
        texture.setDefaultBufferSize(width, height)
        val outputSurface = Surface(texture)
        val flags = DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY or
            DisplayManager.VIRTUAL_DISPLAY_FLAG_PRESENTATION
        val densityDpi = appContext.resources.displayMetrics.densityDpi
        val display = displayManager.createVirtualDisplay(
            "TianyinWebGaussian",
            width,
            height,
            densityDpi,
            outputSurface,
            flags
        ) ?: run {
            outputSurface.release()
            Log.w(TAG, "createVirtualDisplay failed size=${width}x$height")
            return
        }
        val targetDisplay = display.display ?: run {
            display.release()
            outputSurface.release()
            Log.w(TAG, "virtual display has no display")
            return
        }
        val view = controller.createWebView(context).apply {
            setLayerType(View.LAYER_TYPE_HARDWARE, null)
            visibility = View.VISIBLE
            alpha = 1f
            isFocusable = true
            isFocusableInTouchMode = true
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }
        val nextPresentation = WebSplatPresentation(context, targetDisplay, view)
        runCatching {
            nextPresentation.show()
        }.onFailure {
            controller.destroy()
            display.release()
            outputSurface.release()
            Log.w(TAG, "show presentation failed", it)
            return
        }
        virtualDisplay = display
        presentation = nextPresentation
        webView = view
        webOutputSurface = outputSurface
        virtualDisplayWidth = width
        virtualDisplayHeight = height
        controller.attachWebView(view)
        view.onResume()
        view.resumeTimers()
        view.requestFocus()
        view.post {
            view.requestLayout()
            view.invalidate()
        }
        Log.d(TAG, "created virtual display ${width}x$height")
        loadPendingModelOnMain()
    }

    private fun loadPendingModelOnMain() {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            mainHandler.post { loadPendingModelOnMain() }
            return
        }
        val uriString = pendingUriString ?: return
        val params = pendingParams ?: return
        val view = webView ?: return
        controller.modelUri = Uri.parse(uriString)
        controller.pendingParams = params
        controller.resetSensorBaseline = true
        if (renderingEnabled) {
            view.onResume()
        } else {
            view.onPause()
        }
        controller.loadModelIfNeeded(uriString)
        controller.setParams(params)
    }

    private fun releaseWebPipeline(wait: Boolean, timeoutMs: Long) {
        runOnMain(wait = wait, timeoutMs = timeoutMs) {
            releaseWebObjectsOnMain()
        }
    }

    private fun releaseWebObjectsOnMain() {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            mainHandler.post { releaseWebObjectsOnMain() }
            return
        }
        runCatching { presentation?.dismiss() }
        presentation = null
        runCatching { controller.destroy() }
        webView = null
        runCatching { virtualDisplay?.release() }
        virtualDisplay = null
        runCatching { webOutputSurface?.release() }
        webOutputSurface = null
        virtualDisplayWidth = 0
        virtualDisplayHeight = 0
    }

    private fun runOnMain(
        wait: Boolean,
        timeoutMs: Long = MAIN_THREAD_WAIT_TIMEOUT_MS,
        action: () -> Unit
    ) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            action()
            return
        }
        if (!wait) {
            mainHandler.post(action)
            return
        }
        val latch = CountDownLatch(1)
        mainHandler.post {
            try {
                action()
            } finally {
                latch.countDown()
            }
        }
        if (!latch.await(timeoutMs, TimeUnit.MILLISECONDS)) {
            Log.w(TAG, "main thread task timed out after ${timeoutMs}ms")
        }
    }

    private fun signalRenderThread() {
        synchronized(renderSignal) {
            renderSignal.notifyAll()
        }
    }

    private sealed class RenderMessage {
        data class Resize(val width: Int, val height: Int) : RenderMessage()
        object Render : RenderMessage()
    }

    private inner class EglRenderThread(
        val wallpaperSurface: Surface,
        initialWidth: Int,
        initialHeight: Int
    ) : Thread("WebGaussianEGL") {
        private var display: EGLDisplay = EGL14.EGL_NO_DISPLAY
        private var eglContext: EGLContext = EGL14.EGL_NO_CONTEXT
        private var eglSurface: EGLSurface = EGL14.EGL_NO_SURFACE
        private var program = 0
        private var oesTextureId = 0
        private var captureFramebufferId = 0
        private var captureTextureId = 0
        private var captureWidth = 0
        private var captureHeight = 0
        private var captureBuffer: ByteBuffer? = null
        private var capturePixels = IntArray(0)
        private var lastBackdropCaptureNs = 0L
        private var backdropCaptureCount = 0
        private val initLatch = CountDownLatch(1)
        private val frameAvailable = AtomicBoolean(false)
        private var hasFrame = false
        private var width = initialWidth.coerceAtLeast(1)
        private var height = initialHeight.coerceAtLeast(1)
        private var swapCount = 0
        private val transformMatrix = FloatArray(16)
        private val vertexBuffer: FloatBuffer
        private val texCoordBuffer: FloatBuffer

        @Volatile var webSurfaceTexture: SurfaceTexture? = null
            private set

        init {
            val vertexData = floatArrayOf(-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f)
            vertexBuffer = ByteBuffer.allocateDirect(vertexData.size * 4)
                .order(ByteOrder.nativeOrder())
                .asFloatBuffer()
                .put(vertexData)
            vertexBuffer.position(0)
            val texCoordData = floatArrayOf(0f, 0f, 1f, 0f, 0f, 1f, 1f, 1f)
            texCoordBuffer = ByteBuffer.allocateDirect(texCoordData.size * 4)
                .order(ByteOrder.nativeOrder())
                .asFloatBuffer()
                .put(texCoordData)
            texCoordBuffer.position(0)
            Matrix.setIdentityM(transformMatrix, 0)
        }

        override fun run() {
            val initOk = initEGL() && initGL()
            initLatch.countDown()
            if (!initOk) {
                destroyEGL()
                return
            }

            while (isRunning.get() && isSurfaceValid.get()) {
                try {
                    synchronized(renderSignal) {
                        while (
                            isRunning.get() &&
                            isSurfaceValid.get() &&
                            messageQueue.isEmpty() &&
                            !frameAvailable.get()
                        ) {
                            renderSignal.wait()
                        }
                    }
                } catch (_: InterruptedException) {
                    if (!isRunning.get() || !isSurfaceValid.get()) break
                }

                var shouldDraw = frameAvailable.get()
                var message = messageQueue.poll()
                while (message != null) {
                    when (message) {
                        is RenderMessage.Resize -> {
                            width = message.width.coerceAtLeast(1)
                            height = message.height.coerceAtLeast(1)
                            webSurfaceTexture?.setDefaultBufferSize(width, height)
                            shouldDraw = true
                        }
                        RenderMessage.Render -> {
                            renderQueued.set(false)
                            shouldDraw = true
                        }
                    }
                    message = messageQueue.poll()
                }
                if (shouldDraw) drawFrame()
            }
            destroyEGL()
        }

        fun waitForInit(timeoutMs: Long): Boolean {
            return runCatching { initLatch.await(timeoutMs, TimeUnit.MILLISECONDS) }
                .getOrDefault(false) && program != 0 && webSurfaceTexture != null
        }

        fun finishAndWait(timeoutMs: Long) {
            isRunning.set(false)
            isSurfaceValid.set(false)
            interrupt()
            runCatching { join(timeoutMs) }
        }

        private fun initEGL(): Boolean {
            display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
            if (display == EGL14.EGL_NO_DISPLAY) {
                Log.e(TAG, "eglGetDisplay failed")
                return false
            }
            val version = IntArray(2)
            if (!EGL14.eglInitialize(display, version, 0, version, 1)) {
                Log.e(TAG, "eglInitialize failed")
                return false
            }
            val attribs = intArrayOf(
                EGL14.EGL_RED_SIZE, 8,
                EGL14.EGL_GREEN_SIZE, 8,
                EGL14.EGL_BLUE_SIZE, 8,
                EGL14.EGL_ALPHA_SIZE, 8,
                EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
                EGL14.EGL_NONE
            )
            val configs = arrayOfNulls<EGLConfig>(1)
            val numConfigs = IntArray(1)
            if (!EGL14.eglChooseConfig(display, attribs, 0, configs, 0, 1, numConfigs, 0) || numConfigs[0] == 0) {
                Log.e(TAG, "eglChooseConfig failed")
                return false
            }
            eglContext = EGL14.eglCreateContext(
                display,
                configs[0],
                EGL14.EGL_NO_CONTEXT,
                intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE),
                0
            )
            if (eglContext == EGL14.EGL_NO_CONTEXT) {
                Log.e(TAG, "eglCreateContext failed")
                return false
            }
            eglSurface = EGL14.eglCreateWindowSurface(
                display,
                configs[0],
                wallpaperSurface,
                intArrayOf(EGL14.EGL_NONE),
                0
            )
            if (eglSurface == EGL14.EGL_NO_SURFACE) {
                Log.e(TAG, "eglCreateWindowSurface failed")
                return false
            }
            if (!EGL14.eglMakeCurrent(display, eglSurface, eglSurface, eglContext)) {
                Log.e(TAG, "eglMakeCurrent failed")
                return false
            }
            return true
        }

        private fun initGL(): Boolean {
            program = createProgram(VERTEX_SHADER, FRAGMENT_SHADER)
            if (program == 0) return false

            val textures = IntArray(1)
            GLES20.glGenTextures(1, textures, 0)
            oesTextureId = textures[0]
            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, oesTextureId)
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)

            webSurfaceTexture = SurfaceTexture(oesTextureId).apply {
                setDefaultBufferSize(width, height)
                setOnFrameAvailableListener {
                    frameAvailable.set(true)
                    requestRender()
                }
            }
            Log.d(TAG, "initGL texture=$oesTextureId size=${width}x$height")
            return true
        }

        private fun drawFrame() {
            if (display == EGL14.EGL_NO_DISPLAY || eglSurface == EGL14.EGL_NO_SURFACE) return
            val texture = webSurfaceTexture ?: return
            var consumedWebFrame = false
            if (frameAvailable.getAndSet(false)) {
                runCatching {
                    texture.updateTexImage()
                    texture.getTransformMatrix(transformMatrix)
                    hasFrame = true
                    consumedWebFrame = true
                }.onFailure {
                    Log.w(TAG, "updateTexImage failed", it)
                }
            }
            if (!renderingEnabled && hasFrame) return

            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
            GLES20.glViewport(0, 0, width, height)
            GLES20.glClearColor(0f, 0f, 0f, 1f)
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
            if (hasFrame && program != 0) {
                drawOesTexture()
            }
            if (isSurfaceValid.get()) {
                EGL14.eglSwapBuffers(display, eglSurface)
                swapCount++
                if (swapCount == 1 || swapCount % 120 == 0) {
                    Log.d(TAG, "swapped web frame count=$swapCount hasFrame=$hasFrame")
                }
            }
            if (consumedWebFrame) {
                captureBackdropFrameIfNeeded()
            }
        }

        private fun drawOesTexture() {
            GLES20.glUseProgram(program)
            val posLoc = GLES20.glGetAttribLocation(program, "aPos")
            val texLoc = GLES20.glGetAttribLocation(program, "aTex")
            vertexBuffer.position(0)
            texCoordBuffer.position(0)
            GLES20.glEnableVertexAttribArray(posLoc)
            GLES20.glVertexAttribPointer(posLoc, 2, GLES20.GL_FLOAT, false, 0, vertexBuffer)
            GLES20.glEnableVertexAttribArray(texLoc)
            GLES20.glVertexAttribPointer(texLoc, 2, GLES20.GL_FLOAT, false, 0, texCoordBuffer)
            GLES20.glUniformMatrix4fv(GLES20.glGetUniformLocation(program, "uST"), 1, false, transformMatrix, 0)
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, oesTextureId)
            GLES20.glUniform1i(GLES20.glGetUniformLocation(program, "sTex"), 0)
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
            GLES20.glDisableVertexAttribArray(posLoc)
            GLES20.glDisableVertexAttribArray(texLoc)
        }

        private fun captureBackdropFrameIfNeeded() {
            if (!hasFrame || backdropFrameListener == null) return
            val now = System.nanoTime()
            if (lastBackdropCaptureNs != 0L && now - lastBackdropCaptureNs < BACKDROP_CAPTURE_INTERVAL_NS) return

            val targetWidth = width.coerceAtMost(BACKDROP_CAPTURE_MAX_WIDTH).coerceAtLeast(1)
            val targetHeight = (height.toLong() * targetWidth / width.coerceAtLeast(1))
                .toInt()
                .coerceAtLeast(1)
            if (!ensureCaptureTarget(targetWidth, targetHeight)) return

            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, captureFramebufferId)
            GLES20.glViewport(0, 0, captureWidth, captureHeight)
            GLES20.glClearColor(0f, 0f, 0f, 1f)
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
            drawOesTexture()

            val rgba = captureBuffer ?: return
            rgba.clear()
            GLES20.glReadPixels(
                0,
                0,
                captureWidth,
                captureHeight,
                GLES20.GL_RGBA,
                GLES20.GL_UNSIGNED_BYTE,
                rgba
            )
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
            lastBackdropCaptureNs = now

            val pixels = capturePixels
            for (sourceY in 0 until captureHeight) {
                val targetY = captureHeight - 1 - sourceY
                for (x in 0 until captureWidth) {
                    val sourceIndex = (sourceY * captureWidth + x) * 4
                    val red = rgba.get(sourceIndex).toInt() and 0xff
                    val green = rgba.get(sourceIndex + 1).toInt() and 0xff
                    val blue = rgba.get(sourceIndex + 2).toInt() and 0xff
                    val alpha = rgba.get(sourceIndex + 3).toInt() and 0xff
                    pixels[targetY * captureWidth + x] =
                        (alpha shl 24) or (red shl 16) or (green shl 8) or blue
                }
            }
            val bitmap = Bitmap.createBitmap(
                pixels,
                captureWidth,
                captureHeight,
                Bitmap.Config.ARGB_8888
            )
            backdropCaptureCount++
            if (backdropCaptureCount == 1 || backdropCaptureCount % 120 == 0) {
                Log.d(
                    TAG,
                    "captured backdrop frame count=$backdropCaptureCount size=${captureWidth}x$captureHeight"
                )
            }
            mainHandler.post {
                backdropFrameListener?.invoke(bitmap)
            }
        }

        private fun ensureCaptureTarget(targetWidth: Int, targetHeight: Int): Boolean {
            if (
                captureFramebufferId != 0 &&
                captureTextureId != 0 &&
                captureWidth == targetWidth &&
                captureHeight == targetHeight
            ) {
                return true
            }
            releaseCaptureTarget()

            val textures = IntArray(1)
            GLES20.glGenTextures(1, textures, 0)
            captureTextureId = textures[0]
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, captureTextureId)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
            GLES20.glTexImage2D(
                GLES20.GL_TEXTURE_2D,
                0,
                GLES20.GL_RGBA,
                targetWidth,
                targetHeight,
                0,
                GLES20.GL_RGBA,
                GLES20.GL_UNSIGNED_BYTE,
                null
            )

            val framebuffers = IntArray(1)
            GLES20.glGenFramebuffers(1, framebuffers, 0)
            captureFramebufferId = framebuffers[0]
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, captureFramebufferId)
            GLES20.glFramebufferTexture2D(
                GLES20.GL_FRAMEBUFFER,
                GLES20.GL_COLOR_ATTACHMENT0,
                GLES20.GL_TEXTURE_2D,
                captureTextureId,
                0
            )
            val complete = GLES20.glCheckFramebufferStatus(GLES20.GL_FRAMEBUFFER) ==
                GLES20.GL_FRAMEBUFFER_COMPLETE
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
            if (!complete) {
                Log.w(TAG, "backdrop framebuffer incomplete")
                releaseCaptureTarget()
                return false
            }
            captureWidth = targetWidth
            captureHeight = targetHeight
            captureBuffer = ByteBuffer.allocateDirect(targetWidth * targetHeight * 4)
                .order(ByteOrder.nativeOrder())
            capturePixels = IntArray(targetWidth * targetHeight)
            return true
        }

        private fun releaseCaptureTarget() {
            if (captureFramebufferId != 0) {
                GLES20.glDeleteFramebuffers(1, intArrayOf(captureFramebufferId), 0)
                captureFramebufferId = 0
            }
            if (captureTextureId != 0) {
                GLES20.glDeleteTextures(1, intArrayOf(captureTextureId), 0)
                captureTextureId = 0
            }
            captureWidth = 0
            captureHeight = 0
            captureBuffer = null
            capturePixels = IntArray(0)
        }

        private fun destroyEGL() {
            webSurfaceTexture?.setOnFrameAvailableListener(null)
            webSurfaceTexture?.release()
            webSurfaceTexture = null
            releaseCaptureTarget()
            if (oesTextureId != 0) {
                GLES20.glDeleteTextures(1, intArrayOf(oesTextureId), 0)
                oesTextureId = 0
            }
            if (program != 0) {
                GLES20.glDeleteProgram(program)
                program = 0
            }
            if (display != EGL14.EGL_NO_DISPLAY) {
                EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
                if (eglSurface != EGL14.EGL_NO_SURFACE) {
                    EGL14.eglDestroySurface(display, eglSurface)
                    eglSurface = EGL14.EGL_NO_SURFACE
                }
                if (eglContext != EGL14.EGL_NO_CONTEXT) {
                    EGL14.eglDestroyContext(display, eglContext)
                    eglContext = EGL14.EGL_NO_CONTEXT
                }
                EGL14.eglTerminate(display)
                display = EGL14.EGL_NO_DISPLAY
            }
        }

        private fun createProgram(vertexSource: String, fragmentSource: String): Int {
            val vertexShader = compileShader(GLES20.GL_VERTEX_SHADER, vertexSource)
            val fragmentShader = compileShader(GLES20.GL_FRAGMENT_SHADER, fragmentSource)
            if (vertexShader == 0 || fragmentShader == 0) return 0
            val shaderProgram = GLES20.glCreateProgram()
            GLES20.glAttachShader(shaderProgram, vertexShader)
            GLES20.glAttachShader(shaderProgram, fragmentShader)
            GLES20.glLinkProgram(shaderProgram)
            val linkStatus = IntArray(1)
            GLES20.glGetProgramiv(shaderProgram, GLES20.GL_LINK_STATUS, linkStatus, 0)
            GLES20.glDeleteShader(vertexShader)
            GLES20.glDeleteShader(fragmentShader)
            if (linkStatus[0] == 0) {
                Log.e(TAG, "program link failed: ${GLES20.glGetProgramInfoLog(shaderProgram)}")
                GLES20.glDeleteProgram(shaderProgram)
                return 0
            }
            return shaderProgram
        }

        private fun compileShader(type: Int, source: String): Int {
            val shader = GLES20.glCreateShader(type)
            GLES20.glShaderSource(shader, source)
            GLES20.glCompileShader(shader)
            val compileStatus = IntArray(1)
            GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, compileStatus, 0)
            if (compileStatus[0] == 0) {
                Log.e(TAG, "shader compile failed: ${GLES20.glGetShaderInfoLog(shader)}")
                GLES20.glDeleteShader(shader)
                return 0
            }
            return shader
        }
    }

    private class WebSplatPresentation(
        context: Context,
        display: Display,
        private val view: WebView
    ) : Presentation(context, display) {
        override fun onCreate(savedInstanceState: Bundle?) {
            super.onCreate(savedInstanceState)
            window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            setContentView(
                view,
                ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
            )
        }
    }

    private companion object {
        private const val TAG = "WebGaussianWallpaperRenderer"
        private const val RENDER_THREAD_INIT_TIMEOUT_MS = 3_000L
        private const val RENDER_THREAD_STOP_TIMEOUT_MS = 500L
        private const val MAIN_THREAD_WAIT_TIMEOUT_MS = 500L
        private const val BACKDROP_CAPTURE_MAX_WIDTH = 160
        private const val BACKDROP_CAPTURE_INTERVAL_NS = 16_000_000L
        private const val VERTEX_SHADER =
            "attribute vec4 aPos; attribute vec2 aTex; varying vec2 vTex; uniform mat4 uST; " +
                "void main(){ gl_Position = aPos; vTex = (uST * vec4(aTex, 0.0, 1.0)).xy; }"
        private const val FRAGMENT_SHADER =
            "#extension GL_OES_EGL_image_external : require\n" +
                "precision mediump float; varying vec2 vTex; uniform samplerExternalOES sTex; " +
                "void main(){ gl_FragColor = texture2D(sTex, vTex); }"
    }
}
