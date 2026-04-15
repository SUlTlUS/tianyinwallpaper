package com.zeaze.tianyinwallpaper.renderer

import android.graphics.Bitmap
import android.graphics.SurfaceTexture
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLSurface
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.opengl.GLUtils
import android.opengl.Matrix
import android.util.Log
import android.view.Surface
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 简单 GL 渲染器 - 用于视频/图片壁纸
 * 支持视频纹理和图片纹理的渲染
 */
class SimpleGLRenderer {

    // ── 状态 ──
    private var contentWidth: Int = 1
    private var contentHeight: Int = 1
    private var xOffset: Float = 0.5f
    private var userScale: Float = 1f
    private var userOffsetX: Float = 0f
    private var userOffsetY: Float = 0f
    private var userRotation: Float = 0f
    private var isVideoMode: Boolean = false

    private var brightness = 0f
    private var eglThread: EglRenderThread? = null
    private val isRunning = AtomicBoolean(false)
    private val isSurfaceValid = AtomicBoolean(false)
    private val renderSignal = Object()

    // ── 消息队列 ──
    private val messageQueue = ConcurrentLinkedQueue<RenderMessage>()

    private sealed class RenderMessage {
        data class SetContentSize(val width: Int, val height: Int) : RenderMessage()
        data class SetSurfaceSize(val width: Int, val height: Int) : RenderMessage()
        data class SetXOffset(val offset: Float) : RenderMessage()
        data class SetUserTransform(val scale: Float, val offsetX: Float, val offsetY: Float, val rotation: Float = 0f) : RenderMessage()
        data class LoadBitmap(val bitmap: Bitmap) : RenderMessage()
        data class SetVideoMode(val isVideo: Boolean) : RenderMessage()
        data class SetBrightness(val brightness: Float) : RenderMessage()
        object Render : RenderMessage()
        object UpdateVideoFrame : RenderMessage()
    }

    // ── 公共接口 ──

    /**
     * 启动渲染器
     */
    fun start(surface: Surface, width: Int = 0, height: Int = 0) {
        Log.d(TAG, "start: width=$width, height=$height")
        if (isRunning.get()) {
            stopAndWait(300)
        }
        isSurfaceValid.set(true)
        isRunning.set(true)
        eglThread = EglRenderThread(surface, width, height)
        eglThread?.start()
        // 等待 GL 初始化完成
        val initOk = eglThread?.waitForInit(3000) ?: false
        Log.d(TAG, "start: initOk=$initOk, videoST=${eglThread?.videoST != null}")
    }

    /**
     * 停止渲染器
     */
    fun stop() {
        isSurfaceValid.set(false)
        if (!isRunning.getAndSet(false)) return
        messageQueue.clear()
        signalRenderThread()
        eglThread?.finish()
        eglThread = null
    }

    /**
     * 停止渲染器并等待完成
     */
    fun stopAndWait(timeoutMs: Long = 500) {
        isSurfaceValid.set(false)
        if (!isRunning.getAndSet(false)) return
        messageQueue.clear()
        signalRenderThread()
        eglThread?.finishAndWait(timeoutMs)
        eglThread = null
    }

    /**
     * 设置内容尺寸
     */
    fun setContentSize(width: Int, height: Int) {
        contentWidth = if (width > 0) width else 1
        contentHeight = if (height > 0) height else 1
        messageQueue.offer(RenderMessage.SetContentSize(contentWidth, contentHeight))
    }

    /**
     * 设置 Surface 尺寸
     */
    fun setSurfaceSize(width: Int, height: Int) {
        messageQueue.offer(RenderMessage.SetSurfaceSize(width, height))
    }

    /**
     * 设置 X 偏移（用于壁纸滚动）
     */
    fun setXOffset(offset: Float) {
        xOffset = offset
        messageQueue.offer(RenderMessage.SetXOffset(offset))
    }

    /**
     * 设置用户预览变换（缩放、位移、旋转）
     */
    fun setUserTransform(scale: Float, offsetX: Float, offsetY: Float, rotation: Float = 0f) {
        userScale = scale
        userOffsetX = offsetX
        userOffsetY = offsetY
        userRotation = rotation
        messageQueue.offer(RenderMessage.SetUserTransform(scale, offsetX, offsetY, rotation))
    }

    /**
     * 加载图片
     */
    fun loadBitmap(bitmap: Bitmap) {
        isVideoMode = false
        messageQueue.offer(RenderMessage.LoadBitmap(bitmap))
        messageQueue.offer(RenderMessage.Render)
        signalRenderThread()
    }

    /**
     * 设置视频模式
     */
    fun setVideoMode(isVideo: Boolean) {
        isVideoMode = isVideo
        messageQueue.offer(RenderMessage.SetVideoMode(isVideo))
    }

    /**
     * 设置亮度
     */
    fun setBrightness(b: Float) {
        messageQueue.offer(RenderMessage.SetBrightness(b))
        messageQueue.offer(RenderMessage.Render)
        signalRenderThread()
    }

    /**
     * 更新视频帧
     */
    fun updateVideoFrame() {
        messageQueue.offer(RenderMessage.UpdateVideoFrame)
        signalRenderThread()
    }

    /**
     * 请求渲染
     */
    fun requestRender() {
        messageQueue.offer(RenderMessage.Render)
        signalRenderThread()
    }

    /**
     * 获取视频 SurfaceTexture
     */
    val videoSurfaceTexture: SurfaceTexture?
        get() = eglThread?.videoST

    /**
     * 设置帧可用监听器
     */
    fun setOnFrameAvailableListener(listener: SurfaceTexture.OnFrameAvailableListener?) {
        eglThread?.videoST?.setOnFrameAvailableListener(listener)
    }

    private fun signalRenderThread() {
        synchronized(renderSignal) {
            renderSignal.notifyAll()
        }
    }

    // ── EGL 渲染线程 ──

    private inner class EglRenderThread(
        private val surface: Surface,
        initialWidth: Int = 0,
        initialHeight: Int = 0
    ) : Thread("SimpleGLRenderer") {
        private var display: EGLDisplay = EGL14.EGL_NO_DISPLAY
        private var context: EGLContext = EGL14.EGL_NO_CONTEXT
        private var eglSurface: EGLSurface = EGL14.EGL_NO_SURFACE

        private var vTexId: Int = 0
        private var iTexId: Int = 0
        private var vProg: Int = 0
        private var iProg: Int = 0
        private var vBuf: FloatBuffer
        private var tBuf: FloatBuffer

        var videoST: SurfaceTexture? = null
            private set

        private val initLatch = CountDownLatch(1)

        private var sW: Int = if (initialWidth > 0) initialWidth else 1
        private var sH: Int = if (initialHeight > 0) initialHeight else 1
        private var cW: Int = 1
        private var cH: Int = 1
        private var currentXOffset: Float = 0.5f
        private var currentUserScale: Float = 1f
        private var currentUserOffsetX: Float = 0f
        private var currentUserOffsetY: Float = 0f
        private var currentUserRotation: Float = 0f
        private var currentIsVideo: Boolean = false
        private val videoSTMatrix = FloatArray(16)
        private val imageMatrix = FloatArray(16)
        private var frameAvailable = false
        private var currentBrightness: Float = 0f

        init {
            val vData = floatArrayOf(-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f)
            vBuf = ByteBuffer.allocateDirect(vData.size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer().put(vData)
            vBuf.position(0)
            val tData = floatArrayOf(0f, 0f, 1f, 0f, 0f, 1f, 1f, 1f)
            tBuf = ByteBuffer.allocateDirect(tData.size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer().put(tData)
            tBuf.position(0)

            Matrix.setIdentityM(imageMatrix, 0)
            Matrix.translateM(imageMatrix, 0, 0f, 1f, 0f)
            Matrix.scaleM(imageMatrix, 0, 1f, -1f, 1f)
            Matrix.setIdentityM(videoSTMatrix, 0)
        }

        override fun run() {
            if (!initEGL()) {
                Log.e(TAG, "Failed to init EGL")
                initLatch.countDown()
                return
            }
            initGL()
            initLatch.countDown()

            while (isRunning.get() && isSurfaceValid.get()) {
                try {
                    synchronized(renderSignal) {
                        while (isRunning.get() && isSurfaceValid.get() && messageQueue.isEmpty()) {
                            renderSignal.wait()
                        }
                    }
                } catch (_: InterruptedException) {
                    if (!isRunning.get() || !isSurfaceValid.get()) break
                }

                var needsDraw = false
                while (isSurfaceValid.get()) {
                    val msg = messageQueue.poll() ?: break
                    if (!isSurfaceValid.get()) break
                    when (msg) {
                        is RenderMessage.SetContentSize -> {
                            cW = msg.width
                            cH = msg.height
                        }
                        is RenderMessage.SetSurfaceSize -> {
                            sW = if (msg.width > 0) msg.width else 1
                            sH = if (msg.height > 0) msg.height else 1
                        }
                        is RenderMessage.SetXOffset -> {
                            currentXOffset = msg.offset
                        }
                        is RenderMessage.SetUserTransform -> {
                            currentUserScale = msg.scale.coerceAtLeast(0.1f)
                            currentUserOffsetX = msg.offsetX
                            currentUserOffsetY = msg.offsetY
                            currentUserRotation = msg.rotation
                        }
                        is RenderMessage.LoadBitmap -> {
                            uploadBitmapInternal(msg.bitmap)
                            currentIsVideo = false
                        }
                        is RenderMessage.SetVideoMode -> {
                            currentIsVideo = msg.isVideo
                        }
                        is RenderMessage.SetBrightness -> {
                            currentBrightness = msg.brightness
                        }
                        is RenderMessage.UpdateVideoFrame -> {
                            frameAvailable = true
                            needsDraw = true
                        }
                        is RenderMessage.Render -> { needsDraw = true
                            // 渲染在下面
                        }
                    }
                }

                if (!isSurfaceValid.get()) break

                if (needsDraw) {
                    draw()
                }
            }

            destroyEGL()
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

            val attr = intArrayOf(
                EGL14.EGL_RED_SIZE, 8,
                EGL14.EGL_GREEN_SIZE, 8,
                EGL14.EGL_BLUE_SIZE, 8,
                EGL14.EGL_ALPHA_SIZE, 8,
                EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
                EGL14.EGL_NONE
            )
            val configs = arrayOfNulls<EGLConfig>(1)
            val numConfigs = IntArray(1)
            if (!EGL14.eglChooseConfig(display, attr, 0, configs, 0, 1, numConfigs, 0) || numConfigs[0] == 0) {
                Log.e(TAG, "eglChooseConfig failed")
                return false
            }

            context = EGL14.eglCreateContext(
                display, configs[0], EGL14.EGL_NO_CONTEXT,
                intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE), 0
            )
            if (context == EGL14.EGL_NO_CONTEXT) {
                Log.e(TAG, "eglCreateContext failed")
                return false
            }

            eglSurface = EGL14.eglCreateWindowSurface(
                display, configs[0], surface,
                intArrayOf(EGL14.EGL_NONE), 0
            )
            if (eglSurface == EGL14.EGL_NO_SURFACE) {
                Log.e(TAG, "eglCreateWindowSurface failed")
                return false
            }

            if (!EGL14.eglMakeCurrent(display, eglSurface, eglSurface, context)) {
                Log.e(TAG, "eglMakeCurrent failed")
                return false
            }

            Log.d(TAG, "initEGL success: sW=$sW, sH=$sH")
            return true
        }

        private fun initGL() {
            // 视频着色器 (OES 外部纹理)
            val vs = "attribute vec4 aPos; attribute vec2 aTex; varying vec2 vTex; uniform mat4 uMVP; uniform mat4 uST; void main(){ gl_Position = uMVP * aPos; vTex = (uST * vec4(aTex,0,1)).xy; }"
            val fsV = "#extension GL_OES_EGL_image_external : require\n precision mediump float; varying vec2 vTex; uniform samplerExternalOES sTex; uniform float uBrightness; void main(){ vec4 c = texture2D(sTex, vTex); float a = min(max(abs(uBrightness), 0.0), 0.6); vec3 target = uBrightness > 0.0 ? vec3(1.0) : vec3(0.0); vec3 rgb = mix(c.rgb, target, a); gl_FragColor = vec4(rgb, c.a); }"
            val fsI = "precision mediump float; varying vec2 vTex; uniform sampler2D sTex; uniform float uBrightness; void main(){ vec4 c = texture2D(sTex, vTex); float a = min(max(abs(uBrightness), 0.0), 0.6); vec3 target = uBrightness > 0.0 ? vec3(1.0) : vec3(0.0); vec3 rgb = mix(c.rgb, target, a); gl_FragColor = vec4(rgb, c.a); }"

            vProg = createProg(vs, fsV)
            iProg = createProg(vs, fsI)

            // 创建纹理
            val tex = IntArray(2)
            GLES20.glGenTextures(2, tex, 0)
            vTexId = tex[0]
            iTexId = tex[1]

            // 视频纹理 (OES)
            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, vTexId)
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)

            videoST = SurfaceTexture(vTexId)

            // 图片纹理
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, iTexId)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        }

        private fun uploadBitmapInternal(bitmap: Bitmap) {
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, iTexId)
            GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bitmap, 0)
            bitmap.recycle()
        }

        private fun draw() {
            if (!isSurfaceValid.get()) return
            if (display == EGL14.EGL_NO_DISPLAY || eglSurface == EGL14.EGL_NO_SURFACE) {
                Log.w(TAG, "draw: invalid EGL state")
                return
            }

            if (!EGL14.eglMakeCurrent(display, eglSurface, eglSurface, context)) {
                Log.w(TAG, "draw: eglMakeCurrent failed")
                return
            }

            GLES20.glViewport(0, 0, sW, sH)
            GLES20.glClearColor(0.01f, 0.01f, 0.01f, 1.0f)
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)

            val stMat = FloatArray(16)

            if (currentIsVideo) {
                if (frameAvailable) {
                    try {
                        videoST?.updateTexImage()
                        videoST?.getTransformMatrix(videoSTMatrix)
                    } catch (e: Exception) {
                        Log.w(TAG, "updateTexImage failed", e)
                    }
                    frameAvailable = false
                }
                System.arraycopy(videoSTMatrix, 0, stMat, 0, 16)
            } else {
                System.arraycopy(imageMatrix, 0, stMat, 0, 16)
            }

            val prog = if (currentIsVideo) vProg else iProg
            GLES20.glUseProgram(prog)


            // 计算 MVP 矩阵（居中裁剪 + 滚动偏移）
            val mvp = FloatArray(16)
            Matrix.setIdentityM(mvp, 0)
            val cAsp = cW.toFloat() / cH
            val sAsp = if (sH > 0) sW.toFloat() / sH else 9f / 16f

            if (cAsp > sAsp) {
                val scale = cAsp / sAsp
                val tx = (scale - 1.0f) * (1.0f - currentXOffset * 2.0f)
                Matrix.scaleM(mvp, 0, scale, 1.0f, 1.0f)
                Matrix.translateM(mvp, 0, tx / scale, 0f, 0f)
            } else {
                Matrix.scaleM(mvp, 0, 1.0f, sAsp / cAsp, 1.0f)
            }

            // 叠加预览页保存的用户变换（以像素位移映射到 NDC）。
            Matrix.scaleM(mvp, 0, currentUserScale, currentUserScale, 1.0f)
            val txUser = if (sW > 0) (currentUserOffsetX / sW.toFloat()) * 2f else 0f
            val tyUser = if (sH > 0) -(currentUserOffsetY / sH.toFloat()) * 2f else 0f
            Matrix.translateM(mvp, 0, txUser / currentUserScale, tyUser / currentUserScale, 0f)
            Matrix.rotateM(mvp, 0, currentUserRotation, 0f, 0f, 1f)

            val aPos = GLES20.glGetAttribLocation(prog, "aPos")
            val aTex = GLES20.glGetAttribLocation(prog, "aTex")
            GLES20.glEnableVertexAttribArray(aPos)
            GLES20.glVertexAttribPointer(aPos, 2, GLES20.GL_FLOAT, false, 8, vBuf)
            GLES20.glEnableVertexAttribArray(aTex)
            GLES20.glVertexAttribPointer(aTex, 2, GLES20.GL_FLOAT, false, 8, tBuf)

            GLES20.glUniformMatrix4fv(GLES20.glGetUniformLocation(prog, "uMVP"), 1, false, mvp, 0)
            GLES20.glUniformMatrix4fv(GLES20.glGetUniformLocation(prog, "uST"), 1, false, stMat, 0)
            GLES20.glUniform1f(GLES20.glGetUniformLocation(prog, "uBrightness"), currentBrightness)

            GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
            GLES20.glBindTexture(
                if (currentIsVideo) GLES11Ext.GL_TEXTURE_EXTERNAL_OES else GLES20.GL_TEXTURE_2D,
                if (currentIsVideo) vTexId else iTexId
            )
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)

            if (isSurfaceValid.get()) {
                EGL14.eglSwapBuffers(display, eglSurface)
            }
        }

        private fun createProg(v: String, f: String): Int {
            val vs = GLES20.glCreateShader(GLES20.GL_VERTEX_SHADER)
            GLES20.glShaderSource(vs, v)
            GLES20.glCompileShader(vs)
            val fs = GLES20.glCreateShader(GLES20.GL_FRAGMENT_SHADER)
            GLES20.glShaderSource(fs, f)
            GLES20.glCompileShader(fs)
            val p = GLES20.glCreateProgram()
            GLES20.glAttachShader(p, vs)
            GLES20.glAttachShader(p, fs)
            GLES20.glLinkProgram(p)
            return p
        }

        private fun destroyEGL() {
            if (display != EGL14.EGL_NO_DISPLAY) {
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
            } catch (e: InterruptedException) {
                // ignore
            }
        }

        fun finishAndWait(timeoutMs: Long) {
            isRunning.set(false)
            isSurfaceValid.set(false)
            messageQueue.clear()
            interrupt()
            try {
                join(timeoutMs)
            } catch (e: InterruptedException) {
                // ignore
            }
        }

        fun waitForInit(timeoutMs: Long): Boolean {
            return try {
                initLatch.await(timeoutMs, TimeUnit.MILLISECONDS)
            } catch (e: InterruptedException) {
                false
            }
        }
    }

    companion object {
        private const val TAG = "SimpleGLRenderer"
    }
}
