package com.zeaze.tianyinwallpaper.renderer

import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLSurface
import android.opengl.GLES20
import android.opengl.Matrix
import android.os.SystemClock
import android.util.Log
import android.view.Surface
import com.zeaze.tianyinwallpaper.utils.DepthImageProcessor
import com.zeaze.tianyinwallpaper.utils.GaussianPlyLoader
import com.zeaze.tianyinwallpaper.utils.PhotoMeshPlyLoader
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.nio.ShortBuffer
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sqrt

class DepthGLRenderer {
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
        val splatScale: Float = 1f,
        val globalOpacity: Float = 1f,
        val alphaFalloff: Float = 1f,
        val minPointSize: Float = 0.5f,
        val maxPointSize: Float = 160f
    )

    private sealed class RenderMessage {
        data class SetSurfaceSize(val width: Int, val height: Int) : RenderMessage()
        data class LoadTextures(val textures: DepthImageProcessor.TextureSet) : RenderMessage()
        data class LoadLayeredTextures(val textures: DepthImageProcessor.LayeredTextureSet) : RenderMessage()
        data class LoadGaussians(val scene: GaussianPlyLoader.GaussianScene) : RenderMessage()
        data class LoadMesh(val scene: PhotoMeshPlyLoader.MeshScene) : RenderMessage()
        data class LoadMeshLods(val lods: PhotoMeshPlyLoader.MeshLodSet) : RenderMessage()
        data class SetParams(val parallaxStrength: Float, val blurStrength: Float) : RenderMessage()
        data class SetGaussianParams(val params: GaussianRenderParams) : RenderMessage()
        object Render : RenderMessage()
    }

    private data class DepthLayerTexture(
        val textureId: Int,
        val depth: Float
    )

    private data class GaussianLayerTexture(
        val textureId: Int,
        val layer: GaussianPlyLoader.GaussianDepthLayer
    )

    private data class GaussianVboSet(
        val scene: GaussianPlyLoader.GaussianScene,
        val positionBuffer: Int,
        val colorBuffer: Int,
        val scaleBuffer: Int,
        val count: Int
    )

    private data class MeshVboChunk(
        val vertexBuffer: Int,
        val indexBuffer: Int,
        val vertexCount: Int,
        val indexCount: Int
    )

    private data class MeshVboSet(
        val scene: PhotoMeshPlyLoader.MeshScene,
        val chunks: List<MeshVboChunk>
    )

    fun start(surface: Surface) {
        synchronized(lifecycleLock) {
            Log.d(TAG, "start running=${isRunning.get()} surfaceValid=${isSurfaceValid.get()} threadAlive=${eglThread?.isAlive == true}")
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

    fun stop() {
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

    fun stopAndWait(timeoutMs: Long = 500) {
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

    fun resize(width: Int, height: Int) {
        Log.d(TAG, "resize ${width}x$height")
        messageQueue.offer(RenderMessage.SetSurfaceSize(width, height))
        signalRenderThread()
    }

    fun loadTextures(textures: DepthImageProcessor.TextureSet) {
        Log.d(TAG, "loadTextures color=${textures.color.width}x${textures.color.height} depth=${textures.depth.width}x${textures.depth.height}")
        messageQueue.offer(RenderMessage.LoadTextures(textures))
        requestRender()
    }

    fun loadLayeredTextures(textures: DepthImageProcessor.LayeredTextureSet) {
        Log.d(
            TAG,
            "loadLayeredTextures count=${textures.layers.size} image=${textures.imageWidth}x${textures.imageHeight}"
        )
        messageQueue.offer(RenderMessage.LoadLayeredTextures(textures))
        requestRender()
    }

    fun loadGaussians(scene: GaussianPlyLoader.GaussianScene) {
        Log.d(
            TAG,
            "loadGaussians count=${scene.count} image=${scene.imageWidth}x${scene.imageHeight} " +
                "visible=${scene.screenVisibleSplatCount} aux=${scene.auxiliarySplatCount} " +
                "anchor=${scene.parallaxAnchorDepth} anchorSplats=${scene.parallaxAnchorSplatCount}"
        )
        messageQueue.offer(RenderMessage.LoadGaussians(scene))
        requestRender()
    }

    fun loadMesh(scene: PhotoMeshPlyLoader.MeshScene) {
        Log.d(
            TAG,
            "loadMesh faces=${scene.faceCount}/${scene.sourceFaceCount} chunks=${scene.chunks.size} " +
                "image=${scene.imageWidth}x${scene.imageHeight} depth=${scene.nearDepth}/${scene.farDepth}"
        )
        messageQueue.offer(RenderMessage.LoadMesh(scene))
        requestRender()
    }

    fun loadMeshLods(lods: PhotoMeshPlyLoader.MeshLodSet) {
        Log.d(
            TAG,
            "loadMeshLods faces=${lods.scenes.joinToString { "${it.faceCount}/${it.sourceFaceCount}" }} " +
                "full=${lods.full.faceCount} motion=${lods.motion.faceCount} low=${lods.low.faceCount}"
        )
        messageQueue.offer(RenderMessage.LoadMeshLods(lods))
        requestRender()
    }

    fun updateTilt(x: Float, y: Float) {
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

    fun updateParams(parallaxStrength: Float, blurStrength: Float) {
        messageQueue.offer(
            RenderMessage.SetParams(
                parallaxStrength.coerceIn(0.001f, 0.075f),
                blurStrength.coerceIn(0f, 0.02f)
            )
        )
        requestRender()
    }

    fun updateGaussianParams(params: GaussianRenderParams) {
        messageQueue.offer(
            RenderMessage.SetGaussianParams(
                params.copy(
                    splatScale = params.splatScale.coerceIn(0.25f, 3f),
                    globalOpacity = params.globalOpacity.coerceIn(0f, 3f),
                    alphaFalloff = params.alphaFalloff.coerceIn(1f, 9f),
                    minPointSize = params.minPointSize.coerceIn(0.5f, 8f),
                    maxPointSize = params.maxPointSize.coerceIn(8f, 160f)
                )
            )
        )
        requestRender()
    }

    fun requestRender() {
        if (renderQueued.compareAndSet(false, true)) {
            messageQueue.offer(RenderMessage.Render)
        }
        signalRenderThread()
    }

    fun setRenderingEnabled(enabled: Boolean) {
        Log.d(TAG, "setRenderingEnabled $enabled queue=${messageQueue.size}")
        renderingEnabled = enabled
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

        private var prog: Int = 0
        private var gaussianProg: Int = 0
        private var gaussianLayerProg: Int = 0
        private var meshProg: Int = 0
        private var colorTexId: Int = 0
        private var depthTexId: Int = 0
        private var vBuf: FloatBuffer
        private var tBuf: FloatBuffer
        private var tFlipBuf: FloatBuffer
        private var gaussianScene: GaussianPlyLoader.GaussianScene? = null
        private var gaussianVboSet: GaussianVboSet? = null
        private var meshScene: PhotoMeshPlyLoader.MeshScene? = null
        private var meshVboSet: MeshVboSet? = null
        private var meshVboSets: List<MeshVboSet> = emptyList()

        private var sW: Int = 1
        private var sH: Int = 1
        private var cW: Int = 1
        private var cH: Int = 1
        private var hasTexture = false
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
        private var depthLayerTextures: List<DepthLayerTexture> = emptyList()
        private var drawCount = 0L
        private var lastDrawLogMs = 0L
        private var lastNoContentLogMs = 0L
        private var lastQueueLogMs = 0L
        private var lastGlErrorLogMs = 0L
        private var meshMotionUntilMs = 0L
        private var meshHighQualityRenderAtMs = 0L

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
                                meshHighQualityRenderAtMs = 0L
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
                        is RenderMessage.LoadTextures -> {
                            cW = message.textures.color.width.coerceAtLeast(1)
                            cH = message.textures.color.height.coerceAtLeast(1)
                            uploadTexture(colorTexId, message.textures.color)
                            uploadTexture(depthTexId, message.textures.depth)
                            deleteDepthLayerTextures()
                            deleteGaussianLayerTextures()
                            deleteGaussianBuffers()
                            deleteMeshBuffers()
                            gaussianScene = null
                            meshScene = null
                            hasTexture = true
                            needsDraw = true
                            Log.d(TAG, "thread loaded textures ${cW}x$cH")
                        }
                        is RenderMessage.LoadLayeredTextures -> {
                            cW = message.textures.imageWidth.coerceAtLeast(1)
                            cH = message.textures.imageHeight.coerceAtLeast(1)
                            uploadDepthLayerTextures(message.textures)
                            deleteGaussianLayerTextures()
                            deleteGaussianBuffers()
                            deleteMeshBuffers()
                            gaussianScene = null
                            meshScene = null
                            hasTexture = false
                            needsDraw = true
                            Log.d(TAG, "thread loaded depth layers count=${depthLayerTextures.size} ${cW}x$cH")
                        }
                        is RenderMessage.LoadGaussians -> {
                            deleteDepthLayerTextures()
                            deleteGaussianLayerTextures()
                            deleteGaussianBuffers()
                            deleteMeshBuffers()
                            gaussianScene = message.scene
                            meshScene = null
                            uploadGaussianBuffers(message.scene)
                            hasTexture = false
                            cW = message.scene.imageWidth.coerceAtLeast(1)
                            cH = message.scene.imageHeight.coerceAtLeast(1)
                            needsDraw = true
                            Log.d(TAG, "thread loaded gaussians count=${message.scene.count}")
                        }
                        is RenderMessage.LoadMesh -> {
                            deleteDepthLayerTextures()
                            deleteGaussianLayerTextures()
                            deleteGaussianBuffers()
                            deleteMeshBuffers()
                            gaussianScene = null
                            meshScene = message.scene
                            uploadMeshBuffers(message.scene)
                            hasTexture = false
                            cW = message.scene.imageWidth.coerceAtLeast(1)
                            cH = message.scene.imageHeight.coerceAtLeast(1)
                            meshMotionUntilMs = 0L
                            meshHighQualityRenderAtMs = 0L
                            needsDraw = true
                            Log.d(TAG, "thread loaded mesh faces=${message.scene.faceCount} chunks=${message.scene.chunks.size}")
                        }
                        is RenderMessage.LoadMeshLods -> {
                            deleteDepthLayerTextures()
                            deleteGaussianLayerTextures()
                            deleteGaussianBuffers()
                            deleteMeshBuffers()
                            gaussianScene = null
                            meshScene = message.lods.full
                            uploadMeshBuffers(message.lods.scenes)
                            hasTexture = false
                            cW = message.lods.full.imageWidth.coerceAtLeast(1)
                            cH = message.lods.full.imageHeight.coerceAtLeast(1)
                            meshMotionUntilMs = 0L
                            meshHighQualityRenderAtMs = 0L
                            needsDraw = true
                            Log.d(TAG, "thread loaded mesh lods=${message.lods.scenes.joinToString { it.faceCount.toString() }}")
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
            if (!EGL14.eglChooseConfig(display, attr, 0, configs, 0, 1, numConfigs, 0)) {
                return false
            }
            if (numConfigs[0] == 0) return false

            context = EGL14.eglCreateContext(
                display,
                configs[0],
                EGL14.EGL_NO_CONTEXT,
                intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE),
                0
            )
            if (context == EGL14.EGL_NO_CONTEXT) return false

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
            prog = createProg(VERTEX_SHADER, FRAGMENT_SHADER)
            gaussianProg = createProg(GAUSSIAN_VERTEX_SHADER, GAUSSIAN_FRAGMENT_SHADER)
            gaussianLayerProg = createProg(GAUSSIAN_LAYER_VERTEX_SHADER, GAUSSIAN_LAYER_FRAGMENT_SHADER)
            meshProg = createProg(MESH_VERTEX_SHADER, MESH_FRAGMENT_SHADER)
            val textures = IntArray(2)
            GLES20.glGenTextures(2, textures, 0)
            colorTexId = textures[0]
            depthTexId = textures[1]
            configureTexture(colorTexId)
            configureTexture(depthTexId)
        }

        private fun configureTexture(textureId: Int) {
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textureId)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        }

        private fun uploadTexture(textureId: Int, texture: DepthImageProcessor.TextureBitmap) {
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textureId)
            texture.rgba.position(0)
            GLES20.glTexImage2D(
                GLES20.GL_TEXTURE_2D,
                0,
                GLES20.GL_RGBA,
                texture.width,
                texture.height,
                0,
                GLES20.GL_RGBA,
                GLES20.GL_UNSIGNED_BYTE,
                texture.rgba
            )
        }

        private fun uploadDepthLayerTextures(textures: DepthImageProcessor.LayeredTextureSet) {
            deleteDepthLayerTextures()
            if (textures.layers.isEmpty()) return
            val textureIds = IntArray(textures.layers.size)
            GLES20.glGenTextures(textureIds.size, textureIds, 0)
            if (textureIds.any { it == 0 }) {
                Log.w(TAG, "glGenTextures failed for depth layers")
                GLES20.glDeleteTextures(textureIds.size, textureIds, 0)
                return
            }

            val uploadedLayers = ArrayList<DepthLayerTexture>(textures.layers.size)
            for ((index, layer) in textures.layers.withIndex()) {
                val textureId = textureIds[index]
                configureTexture(textureId)
                layer.texture.rgba.position(0)
                GLES20.glTexImage2D(
                    GLES20.GL_TEXTURE_2D,
                    0,
                    GLES20.GL_RGBA,
                    layer.texture.width,
                    layer.texture.height,
                    0,
                    GLES20.GL_RGBA,
                    GLES20.GL_UNSIGNED_BYTE,
                    layer.texture.rgba
                )
                uploadedLayers += DepthLayerTexture(
                    textureId = textureId,
                    depth = layer.depth.coerceIn(0f, 1f)
                )
            }
            depthLayerTextures = uploadedLayers
            logGlError("depth layer upload")
        }

        private fun consumePendingTilt(): Boolean {
            if (!tiltQueued.getAndSet(false)) return false
            val nextX = pendingTiltX
            val nextY = pendingTiltY
            if (abs(tiltX - nextX) <= 0.001f && abs(tiltY - nextY) <= 0.001f) return false
            tiltX = nextX
            tiltY = nextY
            if (meshVboSets.size > 1) {
                val now = SystemClock.elapsedRealtime()
                meshMotionUntilMs = now + MESH_MOTION_LOD_HOLD_MS
                meshHighQualityRenderAtMs = meshMotionUntilMs
            }
            return true
        }

        private fun nextTimedRenderDelayMs(): Long {
            val target = meshHighQualityRenderAtMs
            if (target <= 0L) return Long.MAX_VALUE
            return (target - SystemClock.elapsedRealtime()).coerceAtLeast(0L)
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
                drawGaussianScene(scene)
                if (isThreadActive()) {
                    val swapped = EGL14.eglSwapBuffers(display, eglSurface)
                    logDraw("gaussian", swapped)
                }
                return
            }

            val mesh = activeMeshVboSet()
            if (mesh != null) {
                drawMeshScene(mesh)
                if (isThreadActive()) {
                    val swapped = EGL14.eglSwapBuffers(display, eglSurface)
                    logDraw("mesh", swapped)
                }
                return
            }

            if (depthLayerTextures.isNotEmpty()) {
                drawDepthLayers()
                if (isThreadActive()) {
                    val swapped = EGL14.eglSwapBuffers(display, eglSurface)
                    logDraw("layers", swapped)
                }
                return
            }

            if (!hasTexture) {
                logNoContent("skip draw: no texture")
                return
            }

            GLES20.glViewport(0, 0, sW, sH)
            GLES20.glClearColor(0f, 0f, 0f, 1f)
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
            GLES20.glUseProgram(prog)

            val mvp = FloatArray(16)
            Matrix.setIdentityM(mvp, 0)
            val cAsp = cW.toFloat() / cH.toFloat()
            val sAsp = if (sH > 0) sW.toFloat() / sH.toFloat() else 9f / 16f
            if (cAsp > sAsp) {
                Matrix.scaleM(mvp, 0, cAsp / sAsp, 1f, 1f)
            } else {
                Matrix.scaleM(mvp, 0, 1f, sAsp / cAsp, 1f)
            }

            val st = FloatArray(16)
            Matrix.setIdentityM(st, 0)
            Matrix.translateM(st, 0, 0f, 1f, 0f)
            Matrix.scaleM(st, 0, 1f, -1f, 1f)

            val aPos = GLES20.glGetAttribLocation(prog, "aPos")
            val aTex = GLES20.glGetAttribLocation(prog, "aTex")
            vBuf.position(0)
            tBuf.position(0)
            GLES20.glEnableVertexAttribArray(aPos)
            GLES20.glVertexAttribPointer(aPos, 2, GLES20.GL_FLOAT, false, 8, vBuf)
            GLES20.glEnableVertexAttribArray(aTex)
            GLES20.glVertexAttribPointer(aTex, 2, GLES20.GL_FLOAT, false, 8, tBuf)

            GLES20.glUniformMatrix4fv(GLES20.glGetUniformLocation(prog, "uMVP"), 1, false, mvp, 0)
            GLES20.glUniformMatrix4fv(GLES20.glGetUniformLocation(prog, "uST"), 1, false, st, 0)
            GLES20.glUniform2f(GLES20.glGetUniformLocation(prog, "uTilt"), tiltX, tiltY)
            GLES20.glUniform1f(GLES20.glGetUniformLocation(prog, "uStrength"), parallaxStrength)
            GLES20.glUniform1f(GLES20.glGetUniformLocation(prog, "uBlur"), blurStrength)
            GLES20.glUniform2f(
                GLES20.glGetUniformLocation(prog, "uTexelSize"),
                1f / cW.coerceAtLeast(1).toFloat(),
                1f / cH.coerceAtLeast(1).toFloat()
            )

            GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, colorTexId)
            GLES20.glUniform1i(GLES20.glGetUniformLocation(prog, "sColor"), 0)

            GLES20.glActiveTexture(GLES20.GL_TEXTURE1)
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, depthTexId)
            GLES20.glUniform1i(GLES20.glGetUniformLocation(prog, "sDepth"), 1)

            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)

            if (isThreadActive()) {
                val swapped = EGL14.eglSwapBuffers(display, eglSurface)
                logDraw("texture", swapped)
            }
        }

        private fun drawDepthLayers() {
            if (gaussianLayerProg == 0) {
                logNoContent("skip depth layer draw: program invalid")
                return
            }
            GLES20.glViewport(0, 0, sW, sH)
            GLES20.glClearColor(0f, 0f, 0f, 1f)
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
            GLES20.glDisable(GLES20.GL_DEPTH_TEST)
            GLES20.glDisable(GLES20.GL_DITHER)
            GLES20.glEnable(GLES20.GL_BLEND)
            GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA)
            GLES20.glUseProgram(gaussianLayerProg)

            val imageAspect = cW.toFloat() / cH.coerceAtLeast(1).toFloat()
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

            val aPos = GLES20.glGetAttribLocation(gaussianLayerProg, "aPos")
            val aTex = GLES20.glGetAttribLocation(gaussianLayerProg, "aTex")
            vBuf.position(0)
            tFlipBuf.position(0)
            GLES20.glEnableVertexAttribArray(aPos)
            GLES20.glVertexAttribPointer(aPos, 2, GLES20.GL_FLOAT, false, 8, vBuf)
            GLES20.glEnableVertexAttribArray(aTex)
            GLES20.glVertexAttribPointer(aTex, 2, GLES20.GL_FLOAT, false, 8, tFlipBuf)
            GLES20.glUniform1f(GLES20.glGetUniformLocation(gaussianLayerProg, "uOpacity"), 1f)
            GLES20.glUniform1i(GLES20.glGetUniformLocation(gaussianLayerProg, "sLayer"), 0)

            val mvp = FloatArray(16)
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
            for (layer in depthLayerTextures) {
                val depth = layer.depth.coerceIn(0f, 1f)
                val motion = depth * depth * (3f - 2f * depth)
                val coverScale = 1f + motion * parallaxStrength * 2.25f
                val offsetX = -tiltX * parallaxStrength * motion * 4.1f
                val offsetY = tiltY * parallaxStrength * motion * 4.1f

                Matrix.setIdentityM(mvp, 0)
                Matrix.scaleM(mvp, 0, fillX * coverScale, fillY * coverScale, 1f)
                GLES20.glUniformMatrix4fv(GLES20.glGetUniformLocation(gaussianLayerProg, "uMVP"), 1, false, mvp, 0)
                GLES20.glUniform2f(GLES20.glGetUniformLocation(gaussianLayerProg, "uOffset"), offsetX, offsetY)
                GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, layer.textureId)
                GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
            }

            GLES20.glDisableVertexAttribArray(aPos)
            GLES20.glDisableVertexAttribArray(aTex)
            GLES20.glDisable(GLES20.GL_BLEND)
            logGlError("depth layer draw")
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
                Log.d(
                    TAG,
                    "draw #$drawCount mode=$mode swapped=$swapped size=${sW}x$sH content=${cW}x$cH " +
                        "tilt=(${String.format("%.3f", tiltX)}, ${String.format("%.3f", tiltY)}) " +
                        "queue=${messageQueue.size} renderQueued=${renderQueued.get()} tiltQueued=${tiltQueued.get()}"
                )
                if (!swapped) {
                    Log.w(TAG, "eglSwapBuffers failed error=${EGL14.eglGetError()}")
                }
            }
        }

        private fun drawGaussianScene(scene: GaussianPlyLoader.GaussianScene) {
            if (ENABLE_GAUSSIAN_LAYER_CACHE && scene.depthLayers.isNotEmpty() && ensureGaussianLayerTextures(scene)) {
                drawGaussianLayers(scene)
            } else {
                drawGaussianPoints(scene)
            }
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
                ).coerceIn(0.6f, 40f)

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

        private fun activeMeshVboSet(): MeshVboSet? {
            val sets = if (meshVboSets.isNotEmpty()) {
                meshVboSets
            } else {
                meshVboSet?.let { listOf(it) }.orEmpty()
            }
            if (sets.isEmpty()) return null
            if (sets.size == 1) return sets[0]
            val ordered = sets.sortedBy { it.scene.faceCount }
            val now = SystemClock.elapsedRealtime()
            if (now >= meshMotionUntilMs) return ordered.last()
            val tiltLength = sqrt(tiltX * tiltX + tiltY * tiltY)
            return if (tiltLength > 0.46f && ordered.size >= 3) {
                ordered.first()
            } else {
                ordered.getOrElse(ordered.lastIndex / 2) { ordered.first() }
            }
        }

        private fun drawMeshScene(vboSet: MeshVboSet) {
            val scene = vboSet.scene
            if (meshProg == 0) {
                logNoContent("skip mesh draw: program=$meshProg")
                return
            }

            GLES20.glViewport(0, 0, sW, sH)
            GLES20.glClearColor(scene.backgroundR, scene.backgroundG, scene.backgroundB, 1f)
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT or GLES20.GL_DEPTH_BUFFER_BIT)
            GLES20.glEnable(GLES20.GL_DEPTH_TEST)
            GLES20.glDepthMask(true)
            GLES20.glDepthFunc(GLES20.GL_LEQUAL)
            GLES20.glEnable(GLES20.GL_POLYGON_OFFSET_FILL)
            GLES20.glPolygonOffset(0.75f, 1.0f)
            GLES20.glDisable(GLES20.GL_BLEND)
            GLES20.glDisable(GLES20.GL_DITHER)
            GLES20.glUseProgram(meshProg)

            val projection = FloatArray(16)
            val screenAspect = sW.toFloat() / sH.coerceAtLeast(1).toFloat()
            val near = max(0.05f, scene.nearDepth * 0.45f)
            val far = max(scene.farDepth * 1.35f, near + 5f)
            val projectionVFovRad = meshProjectionVerticalFovRad(scene, screenAspect)
            Matrix.perspectiveM(
                projection,
                0,
                Math.toDegrees(projectionVFovRad.toDouble()).toFloat().coerceIn(15f, 150f),
                screenAspect,
                near,
                far
            )

            val view = FloatArray(16)
            Matrix.setIdentityM(view, 0)
            val cameraScale = max(scene.parallaxAnchorDepth, scene.farDepth) * parallaxStrength * 1.15f
            Matrix.translateM(view, 0, -tiltX * cameraScale, tiltY * cameraScale, 0f)

            val model = FloatArray(16)
            Matrix.setIdentityM(model, 0)
            Matrix.rotateM(model, 0, 180f, 1f, 0f, 0f)

            val modelView = FloatArray(16)
            val mvp = FloatArray(16)
            Matrix.multiplyMM(modelView, 0, view, 0, model, 0)
            Matrix.multiplyMM(mvp, 0, projection, 0, modelView, 0)

            val aPos = GLES20.glGetAttribLocation(meshProg, "aPos")
            val aColor = GLES20.glGetAttribLocation(meshProg, "aColor")
            if (aPos < 0 || aColor < 0) {
                logNoContent("skip mesh draw: missing attributes pos=$aPos color=$aColor")
                GLES20.glDisable(GLES20.GL_POLYGON_OFFSET_FILL)
                GLES20.glDisable(GLES20.GL_DEPTH_TEST)
                return
            }
            GLES20.glUniformMatrix4fv(GLES20.glGetUniformLocation(meshProg, "uMVP"), 1, false, mvp, 0)
            GLES20.glEnableVertexAttribArray(aPos)
            GLES20.glEnableVertexAttribArray(aColor)

            vboSet.chunks.forEach { chunk ->
                GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, chunk.vertexBuffer)
                GLES20.glVertexAttribPointer(aPos, 3, GLES20.GL_FLOAT, false, MESH_VERTEX_STRIDE_BYTES, 0)
                GLES20.glVertexAttribPointer(aColor, 4, GLES20.GL_FLOAT, false, MESH_VERTEX_STRIDE_BYTES, MESH_COLOR_OFFSET_BYTES)
                GLES20.glBindBuffer(GLES20.GL_ELEMENT_ARRAY_BUFFER, chunk.indexBuffer)
                GLES20.glDrawElements(GLES20.GL_TRIANGLES, chunk.indexCount, GLES20.GL_UNSIGNED_SHORT, 0)
            }

            GLES20.glDisableVertexAttribArray(aPos)
            GLES20.glDisableVertexAttribArray(aColor)
            GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, 0)
            GLES20.glBindBuffer(GLES20.GL_ELEMENT_ARRAY_BUFFER, 0)
            GLES20.glDisable(GLES20.GL_POLYGON_OFFSET_FILL)
            GLES20.glDisable(GLES20.GL_DEPTH_TEST)
            logGlError("mesh draw")
        }

        private fun meshProjectionVerticalFovRad(
            scene: PhotoMeshPlyLoader.MeshScene,
            screenAspect: Float
        ): Float {
            val safeScreenAspect = screenAspect.coerceAtLeast(0.01f)
            val tanHalfH = Math.tan((scene.hFovRad * 0.5f).toDouble()).toFloat()
            val tanHalfV = Math.tan((scene.vFovRad * 0.5f).toDouble()).toFloat()
            val fovAspect = if (tanHalfV > 0.0001f) {
                tanHalfH / tanHalfV
            } else {
                scene.imageWidth.toFloat() / scene.imageHeight.coerceAtLeast(1).toFloat()
            }
            return if (safeScreenAspect > fovAspect && tanHalfH > 0.0001f) {
                (2.0 * Math.atan((tanHalfH / safeScreenAspect).toDouble())).toFloat()
            } else {
                scene.vFovRad
            }.coerceIn(0.2f, 2.7f)
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
            val pointScale = (textureScale * GAUSSIAN_SPLAT_SCALE * gaussianParams.splatScale).coerceIn(0.6f, 40f)
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

        private fun uploadGaussianBuffers(scene: GaussianPlyLoader.GaussianScene) {
            drainGlErrors("before gaussian VBO upload")
            val ids = IntArray(3)
            GLES20.glGenBuffers(3, ids, 0)
            if (ids.any { it == 0 }) {
                Log.w(TAG, "glGenBuffers failed for gaussian scene")
                GLES20.glDeleteBuffers(3, ids, 0)
                gaussianVboSet = null
                return
            }

            val uploaded =
                uploadFloatBuffer(ids[0], scene.positions, scene.count * 3 * FLOAT_SIZE_BYTES, "positions") &&
                    uploadFloatBuffer(ids[1], scene.colors, scene.count * 4 * FLOAT_SIZE_BYTES, "colors") &&
                    uploadFloatBuffer(ids[2], scene.scales, scene.count * 3 * FLOAT_SIZE_BYTES, "scales")
            GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, 0)
            scene.positions.position(0)
            scene.colors.position(0)
            scene.scales.position(0)
            if (!uploaded) {
                GLES20.glDeleteBuffers(3, ids, 0)
                gaussianVboSet = null
                Log.w(TAG, "gaussian VBO upload failed; fallback to client FloatBuffer rendering")
                return
            }
            gaussianVboSet = GaussianVboSet(
                scene = scene,
                positionBuffer = ids[0],
                colorBuffer = ids[1],
                scaleBuffer = ids[2],
                count = scene.count
            )
            Log.d(TAG, "uploaded gaussian VBO count=${scene.count}")
        }

        private fun uploadMeshBuffers(scene: PhotoMeshPlyLoader.MeshScene) {
            uploadMeshBuffers(listOf(scene))
        }

        private fun uploadMeshBuffers(scenes: List<PhotoMeshPlyLoader.MeshScene>) {
            drainGlErrors("before mesh VBO upload")
            val uploadedSets = ArrayList<MeshVboSet>(scenes.size)
            for (scene in scenes) {
                uploadMeshVboSet(scene)?.let { uploadedSets += it }
            }
            meshVboSets = uploadedSets.sortedBy { it.scene.faceCount }
            meshVboSet = meshVboSets.firstOrNull()
            Log.d(
                TAG,
                "uploaded mesh VBO sets=${meshVboSets.map { it.scene.faceCount }} " +
                    "chunks=${meshVboSets.sumOf { it.chunks.size }}"
            )
        }

        private fun uploadMeshVboSet(scene: PhotoMeshPlyLoader.MeshScene): MeshVboSet? {
            val uploadedChunks = ArrayList<MeshVboChunk>(scene.chunks.size)
            for ((index, chunk) in scene.chunks.withIndex()) {
                val ids = IntArray(2)
                GLES20.glGenBuffers(2, ids, 0)
                if (ids.any { it == 0 }) {
                    Log.w(TAG, "glGenBuffers failed for mesh chunk=$index")
                    GLES20.glDeleteBuffers(2, ids, 0)
                    continue
                }
                val uploaded =
                    uploadFloatBuffer(
                        ids[0],
                        chunk.vertices,
                        chunk.vertexCount * MESH_VERTEX_STRIDE_BYTES,
                        "mesh vertices"
                    ) &&
                        uploadShortElementBuffer(ids[1], chunk.indices, chunk.indexCount * SHORT_SIZE_BYTES, "mesh indices")
                GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, 0)
                GLES20.glBindBuffer(GLES20.GL_ELEMENT_ARRAY_BUFFER, 0)
                chunk.vertices.position(0)
                chunk.indices.position(0)
                if (!uploaded) {
                    GLES20.glDeleteBuffers(2, ids, 0)
                    Log.w(TAG, "mesh VBO upload failed for chunk=$index")
                    continue
                }
                uploadedChunks += MeshVboChunk(
                    vertexBuffer = ids[0],
                    indexBuffer = ids[1],
                    vertexCount = chunk.vertexCount,
                    indexCount = chunk.indexCount
                )
            }
            val vboSet = if (uploadedChunks.isNotEmpty()) {
                MeshVboSet(scene = scene, chunks = uploadedChunks)
            } else {
                null
            }
            Log.d(TAG, "uploaded mesh VBO chunks=${uploadedChunks.size}/${scene.chunks.size} faces=${scene.faceCount}")
            return vboSet
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

        private fun uploadShortElementBuffer(
            bufferId: Int,
            buffer: ShortBuffer,
            sizeBytes: Int,
            label: String
        ): Boolean {
            buffer.position(0)
            GLES20.glBindBuffer(GLES20.GL_ELEMENT_ARRAY_BUFFER, bufferId)
            GLES20.glBufferData(GLES20.GL_ELEMENT_ARRAY_BUFFER, sizeBytes, buffer, GLES20.GL_STATIC_DRAW)
            val error = GLES20.glGetError()
            if (error != GLES20.GL_NO_ERROR) {
                Log.w(TAG, "glBufferData failed for $label size=$sizeBytes error=0x${error.toString(16)}")
                return false
            }
            return true
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

            val vbo = gaussianVboSet?.takeIf { it.scene === scene && it.count == scene.count }
            if (vbo != null) {
                GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, vbo.positionBuffer)
                GLES20.glVertexAttribPointer(aPos, 3, GLES20.GL_FLOAT, false, 0, 0)
                GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, vbo.colorBuffer)
                GLES20.glVertexAttribPointer(aColor, 4, GLES20.GL_FLOAT, false, 0, 0)
                GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, vbo.scaleBuffer)
                GLES20.glVertexAttribPointer(aScale, 3, GLES20.GL_FLOAT, false, 0, 0)
                GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, 0)
                return true
            }

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

        private fun deleteDepthLayerTextures() {
            if (depthLayerTextures.isNotEmpty()) {
                val ids = depthLayerTextures.map { it.textureId }.toIntArray()
                GLES20.glDeleteTextures(ids.size, ids, 0)
            }
            depthLayerTextures = emptyList()
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
                val ids = intArrayOf(vbo.positionBuffer, vbo.colorBuffer, vbo.scaleBuffer)
                GLES20.glDeleteBuffers(ids.size, ids, 0)
            }
            gaussianVboSet = null
        }

        private fun deleteMeshBuffers() {
            val chunks = if (meshVboSets.isNotEmpty()) {
                meshVboSets.flatMap { it.chunks }
            } else {
                meshVboSet?.chunks.orEmpty()
            }
            if (chunks.isNotEmpty()) {
                val ids = IntArray(chunks.size * 2)
                chunks.forEachIndexed { index, chunk ->
                    val base = index * 2
                    ids[base] = chunk.vertexBuffer
                    ids[base + 1] = chunk.indexBuffer
                }
                GLES20.glDeleteBuffers(ids.size, ids, 0)
            }
            meshVboSet = null
            meshVboSets = emptyList()
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
                deleteDepthLayerTextures()
                deleteGaussianLayerTextures()
                deleteGaussianBuffers()
                deleteMeshBuffers()
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
        private const val RENDER_THREAD_STOP_TIMEOUT_MS = 1_500L
        private const val DRAW_LOG_INTERVAL_MS = 1_000L
        private const val MAX_VIEW_TILT = 0.72f
        private const val MESH_MOTION_LOD_HOLD_MS = 280L
        private const val GAUSSIAN_SPLAT_SCALE = 5.2f
        private const val GAUSSIAN_LAYER_TEXTURE_MAX_SIZE = 768
        private const val FLOAT_SIZE_BYTES = 4
        private const val SHORT_SIZE_BYTES = 2
        private const val MESH_VERTEX_STRIDE_FLOATS = 7
        private const val MESH_VERTEX_STRIDE_BYTES = MESH_VERTEX_STRIDE_FLOATS * FLOAT_SIZE_BYTES
        private const val MESH_COLOR_OFFSET_BYTES = 3 * FLOAT_SIZE_BYTES
        private const val ENABLE_GAUSSIAN_LAYER_CACHE = false

        private const val VERTEX_SHADER = """
            attribute vec4 aPos;
            attribute vec2 aTex;
            varying vec2 vTex;
            uniform mat4 uMVP;
            uniform mat4 uST;
            void main() {
                gl_Position = uMVP * aPos;
                vTex = (uST * vec4(aTex, 0.0, 1.0)).xy;
            }
        """

        private const val FRAGMENT_SHADER = """
            precision mediump float;
            varying vec2 vTex;
            uniform sampler2D sColor;
            uniform sampler2D sDepth;
            uniform vec2 uTilt;
            uniform float uStrength;
            uniform float uBlur;
            uniform vec2 uTexelSize;

            vec2 clampUv(vec2 uv) {
                return clamp(uv, vec2(0.0), vec2(1.0));
            }

            float readDepth(vec2 uv) {
                return clamp(texture2D(sDepth, clampUv(uv)).r, 0.0, 1.0);
            }

            float filteredDepth(vec2 uv) {
                vec2 x = vec2(uTexelSize.x * 1.5, 0.0);
                vec2 y = vec2(0.0, uTexelSize.y * 1.5);
                float depth = readDepth(uv) * 0.50;
                depth += readDepth(uv + x) * 0.125;
                depth += readDepth(uv - x) * 0.125;
                depth += readDepth(uv + y) * 0.125;
                depth += readDepth(uv - y) * 0.125;
                return smoothstep(0.02, 0.98, depth);
            }

            float depthEdge(vec2 uv) {
                float center = readDepth(uv);
                float dx = max(
                    abs(center - readDepth(uv + vec2(uTexelSize.x * 3.0, 0.0))),
                    abs(center - readDepth(uv - vec2(uTexelSize.x * 3.0, 0.0)))
                );
                float dy = max(
                    abs(center - readDepth(uv + vec2(0.0, uTexelSize.y * 3.0))),
                    abs(center - readDepth(uv - vec2(0.0, uTexelSize.y * 3.0)))
                );
                return smoothstep(0.018, 0.12, max(dx, dy));
            }

            float depthMotion(float depth) {
                float shapedDepth = pow(clamp(depth, 0.0, 1.0), 1.28);
                return shapedDepth * 0.72 - 0.10;
            }

            void main() {
                vec2 uv = clampUv(vTex);
                vec2 tilt = uTilt * uStrength;
                float centerDepth = filteredDepth(uv);
                float centerEdge = depthEdge(uv);

                float edgeDamping = mix(1.0, 0.05, centerEdge);
                vec2 warpedUv = clampUv(uv - tilt * depthMotion(centerDepth) * edgeDamping);
                float warpedDepth = filteredDepth(warpedUv);
                float mismatch = smoothstep(0.045, 0.20, abs(warpedDepth - centerDepth));
                float repair = clamp(max(centerEdge * 0.96, mismatch * 0.80), 0.0, 1.0);
                vec2 stableUv = mix(warpedUv, uv, repair);
                vec4 sharp = texture2D(sColor, stableUv);

                float farAmount = 1.0 - centerDepth;
                float blurRadius = uBlur * farAmount * (1.0 - centerEdge * 0.75) *
                    (1.0 + length(uTilt) * 0.25);
                vec4 blurred = sharp * 0.40;
                blurred += texture2D(sColor, clampUv(stableUv + vec2( blurRadius, 0.0))) * 0.15;
                blurred += texture2D(sColor, clampUv(stableUv + vec2(-blurRadius, 0.0))) * 0.15;
                blurred += texture2D(sColor, clampUv(stableUv + vec2(0.0,  blurRadius))) * 0.15;
                blurred += texture2D(sColor, clampUv(stableUv + vec2(0.0, -blurRadius))) * 0.15;

                float blurMix = farAmount * (1.0 - centerEdge * 0.85);
                vec3 color = mix(sharp.rgb, blurred.rgb, blurMix);
                gl_FragColor = vec4(color, sharp.a);
            }
        """

        private const val MESH_VERTEX_SHADER = """
            attribute vec3 aPos;
            attribute vec4 aColor;
            uniform mat4 uMVP;
            varying vec4 vColor;

            void main() {
                gl_Position = uMVP * vec4(aPos, 1.0);
                vColor = aColor;
            }
        """

        private const val MESH_FRAGMENT_SHADER = """
            precision mediump float;
            varying vec4 vColor;

            void main() {
                gl_FragColor = vec4(vColor.rgb, 1.0);
            }
        """

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
            uniform float uPointScale;
            uniform float uMinPointSize;
            uniform float uMaxPointSize;
            varying vec4 vColor;
            varying vec3 vShape;

            void main() {
                vec3 p = aPos;
                float z = max(p.z, 0.02);
                float farZ = max(uFarDepth, z + 0.001);
                float parallax = max((1.0 / z) - (1.0 / farZ), 0.0);
                vec2 camera = vec2(uTilt.x, -uTilt.y) * uStrength * farZ * 1.65;
                float projectedX = (p.x / z) - camera.x * parallax;
                float projectedY = (p.y / z) - camera.y * parallax;
                float x = projectedX * (2.0 * uFocal / uImageSize.x) * uFillScale.x;
                float y = -projectedY * (2.0 * uFocal / uImageSize.y) * uFillScale.y;
                gl_Position = vec4(x, y, 0.0, 1.0);

                float majorScale = max(max(aScale.x, aScale.y), 0.0006);
                float pixelSize = majorScale * uFocal / z * uPointScale;
                gl_PointSize = clamp(pixelSize, uMinPointSize, uMaxPointSize);
                vColor = aColor;
                vShape = vec3(1.0, 1.0, 0.0);
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
                if (radius2 > 2.25) {
                    discard;
                }
                float alpha = vColor.a * uOpacity * exp(-radius2 * uAlphaFalloff);
                if (alpha < 0.008) {
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
