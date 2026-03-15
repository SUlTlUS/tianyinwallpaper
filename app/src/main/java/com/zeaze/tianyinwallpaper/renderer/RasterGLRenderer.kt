package com.zeaze.tianyinwallpaper.renderer

import android.graphics.Bitmap
import android.graphics.SurfaceTexture
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLSurface
import android.opengl.GLES20
import android.opengl.GLUtils
import android.opengl.Matrix
import android.util.Log
import android.view.Surface
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 光栅壁纸共享渲染器
 * 用于 WallpaperService 和预览界面的统一渲染
 */
class RasterGLRenderer {

    // ── 配置参数 ──
    var sensorWidth: Float = 0.6f
    var transitionBand: Float = 0.55f
    var edgeSoftness: Float = 0.25f

    // ── 状态 ──
    private var imageCount: Int = 0
    private var currentFloatIndex: Float = 0f
    private var displayedIntIndex: Int = -1
    private var scanFromIndex: Int = -1
    private var scanToIndex: Int = -1
    private var scanProgress: Float = 0f
    private var scanDirection: Int = 0

    // ── Bitmap 数据 ──
    private val bitmaps = mutableListOf<Bitmap>()
    private var surfaceWidth: Int = 1
    private var surfaceHeight: Int = 1

    // ── EGL ──
    private var eglThread: EglRenderThread? = null
    private val isRunning = AtomicBoolean(false)
    private val isSurfaceValid = AtomicBoolean(false)

    // ── 消息队列 ──
    private val messageQueue = ConcurrentLinkedQueue<RenderMessage>()

    private sealed class RenderMessage {
        data class LoadBitmaps(val bitmaps: List<Bitmap>) : RenderMessage()
        data class UpdateTilt(val tilt: Float, val direction: Int) : RenderMessage()
        data class Resize(val width: Int, val height: Int) : RenderMessage()
        data class ExecuteTask(val task: () -> Unit) : RenderMessage()
        object Render : RenderMessage()
    }

    // ── 公共接口 ──

    /**
     * 启动渲染器
     * @param surface 渲染目标 Surface
     */
    fun start(surface: Surface) {
        // 如果已经在运行，先停止旧渲染器
        if (isRunning.get()) {
            stopAndWait(300)
        }
        isSurfaceValid.set(true)
        isRunning.set(true)
        eglThread = EglRenderThread(surface)
        eglThread?.start()
    }

    /**
     * 停止渲染器（同步等待）
     */
    fun stop() {
        isSurfaceValid.set(false)
        if (!isRunning.getAndSet(false)) return
        messageQueue.clear()
        eglThread?.finish()
        eglThread = null
        releaseBitmaps()
    }

    /**
     * 停止渲染器并等待完成
     * @param timeoutMs 等待超时时间（毫秒）
     */
    fun stopAndWait(timeoutMs: Long = 500) {
        isSurfaceValid.set(false)
        if (!isRunning.getAndSet(false)) return
        messageQueue.clear()
        eglThread?.finishAndWait(timeoutMs)
        eglThread = null
        releaseBitmaps()
    }

    /**
     * 更新 Surface 尺寸
     */
    fun resize(width: Int, height: Int) {
        surfaceWidth = width
        surfaceHeight = height
        messageQueue.offer(RenderMessage.Resize(width, height))
    }

    /**
     * 加载图片
     */
    fun loadBitmaps(newBitmaps: List<Bitmap>) {
        releaseBitmaps()
        bitmaps.clear()
        bitmaps.addAll(newBitmaps)
        imageCount = bitmaps.size
        displayedIntIndex = -1
        scanFromIndex = -1
        scanToIndex = -1
        scanProgress = 0f
        currentFloatIndex = 0f
        messageQueue.offer(RenderMessage.LoadBitmaps(newBitmaps.toList()))
    }

    /**
     * 更新倾斜状态
     * @param tiltNormalized 归一化倾斜值 (0-1)
     * @param direction 倾斜方向 (>0 右倾, <0 左倾)
     */
    fun updateTilt(tiltNormalized: Float, direction: Int) {
        if (imageCount == 0) return

        currentFloatIndex = (tiltNormalized * (imageCount - 1)).coerceIn(0f, (imageCount - 1).toFloat())

        if (imageCount == 1) {
            if (displayedIntIndex != 0) {
                displayedIntIndex = 0
                uploadStaticFrame(0)
            }
            scanProgress = 0f
            scanFromIndex = -1
            scanToIndex = -1
            requestRender()
            return
        }

        val intIndex = currentFloatIndex.toInt().coerceIn(0, imageCount - 2)
        val fraction = currentFloatIndex - intIndex

        val fromIdx = intIndex
        val toIdx = intIndex + 1

        val bandStart = (1f - transitionBand) / 2f
        val bandEnd = (1f + transitionBand) / 2f

        if (fraction < bandStart) {
            scanFromIndex = -1
            scanToIndex = -1
            scanProgress = 0f
            if (displayedIntIndex != fromIdx) {
                displayedIntIndex = fromIdx
                uploadStaticFrame(fromIdx)
            }
        } else if (fraction > bandEnd) {
            scanFromIndex = -1
            scanToIndex = -1
            scanProgress = 0f
            if (displayedIntIndex != toIdx) {
                displayedIntIndex = toIdx
                uploadStaticFrame(toIdx)
            }
        } else {
            val mappedProgress = ((fraction - bandStart) / transitionBand).coerceIn(0f, 1f)
            if (scanFromIndex != fromIdx || scanToIndex != toIdx) {
                scanFromIndex = fromIdx
                scanToIndex = toIdx
                uploadTransitionTextures(fromIdx, toIdx)
            }
            scanDirection = if (direction >= 0) 1 else -1
            scanProgress = mappedProgress
        }
        requestRender()
    }

    /**
     * 请求渲染
     */
    fun requestRender() {
        messageQueue.offer(RenderMessage.Render)
    }

    // ── 内部方法 ──

    private fun uploadStaticFrame(index: Int) {
        eglThread?.post {
            val bitmap = bitmaps.getOrNull(index) ?: return@post
            eglThread?.uploadBitmapSync(bitmap)
        }
    }

    private fun uploadTransitionTextures(fromIdx: Int, toIdx: Int) {
        eglThread?.post {
            val bmpA = bitmaps.getOrNull(fromIdx)
            val bmpB = bitmaps.getOrNull(toIdx)
            if (bmpA != null) eglThread?.uploadToTexA(bmpA)
            if (bmpB != null) eglThread?.uploadToTexB(bmpB)
        }
    }

    private fun releaseBitmaps() {
        bitmaps.forEach { it.recycle() }
        bitmaps.clear()
    }

    // ── EGL 渲染线程 ──

    private inner class EglRenderThread(private val surface: Surface) : Thread("RasterGLRenderer") {
        private var display: EGLDisplay = EGL14.EGL_NO_DISPLAY
        private var context: EGLContext = EGL14.EGL_NO_CONTEXT
        private var eglSurface: EGLSurface = EGL14.EGL_NO_SURFACE

        private var iTexId: Int = 0
        private var texAId: Int = 0
        private var texBId: Int = 0
        private var iProg: Int = 0
        private var transitionProg: Int = 0
        private var vBuf: FloatBuffer
        private var tBuf: FloatBuffer

        private var sW: Int = 1
        private var sH: Int = 1

        init {
            val vData = floatArrayOf(-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f)
            vBuf = ByteBuffer.allocateDirect(vData.size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer().put(vData)
            vBuf.position(0)
            val tData = floatArrayOf(0f, 0f, 1f, 0f, 0f, 1f, 1f, 1f)
            tBuf = ByteBuffer.allocateDirect(tData.size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer().put(tData)
            tBuf.position(0)
        }

        fun post(r: () -> Unit) {
            messageQueue.offer(RenderMessage.ExecuteTask(r))
        }

        fun uploadBitmapSync(b: Bitmap) {
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, iTexId)
            GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, b, 0)
        }

        fun uploadToTexA(b: Bitmap) {
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texAId)
            GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, b, 0)
        }

        fun uploadToTexB(b: Bitmap) {
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texBId)
            GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, b, 0)
        }

        override fun run() {
            if (!initEGL()) {
                Log.e(TAG, "Failed to init EGL")
                return
            }
            initGL()

            // 处理消息循环
            while (isRunning.get() && isSurfaceValid.get()) {
                // 处理所有消息
                while (isSurfaceValid.get()) {
                    val msg = messageQueue.poll() ?: break
                    if (!isSurfaceValid.get()) break
                    when (msg) {
                        is RenderMessage.Resize -> {
                            sW = msg.width
                            sH = msg.height
                        }
                        is RenderMessage.LoadBitmaps -> {
                            // 已在外部处理
                        }
                        is RenderMessage.UpdateTilt -> {
                            // 倾斜更新已在外部处理
                        }
                        is RenderMessage.ExecuteTask -> {
                            // 确保 EGL 上下文当前有效
                            if (display != EGL14.EGL_NO_DISPLAY && eglSurface != EGL14.EGL_NO_SURFACE && isSurfaceValid.get()) {
                                EGL14.eglMakeCurrent(display, eglSurface, eglSurface, context)
                            }
                            if (isSurfaceValid.get()) {
                                msg.task()
                            }
                        }
                        is RenderMessage.Render -> {
                            // 渲染在下面
                        }
                    }
                }

                // 检查 Surface 是否仍然有效
                if (!isSurfaceValid.get()) break

                // 渲染一帧
                draw()

                // 短暂休眠避免占用 CPU
                try {
                    Thread.sleep(16)
                } catch (e: InterruptedException) {
                    break
                }
            }

            destroyEGL()
        }

        private fun initEGL(): Boolean {
            display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
            val version = IntArray(2)
            EGL14.eglInitialize(display, version, 0, version, 1)

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
            EGL14.eglChooseConfig(display, attr, 0, configs, 0, 1, numConfigs, 0)

            context = EGL14.eglCreateContext(
                display, configs[0], EGL14.EGL_NO_CONTEXT,
                intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE), 0
            )

            eglSurface = EGL14.eglCreateWindowSurface(
                display, configs[0], surface,
                intArrayOf(EGL14.EGL_NONE), 0
            )

            return EGL14.eglMakeCurrent(display, eglSurface, eglSurface, context)
        }

        private fun initGL() {
            // 单图着色器
            val vs = "attribute vec4 aPos; attribute vec2 aTex; varying vec2 vTex; uniform mat4 uMVP; uniform mat4 uST; void main(){ gl_Position = uMVP * aPos; vTex = (uST * vec4(aTex,0,1)).xy; }"
            val fsI = "precision mediump float; varying vec2 vTex; uniform sampler2D sTex; void main(){ gl_FragColor = texture2D(sTex, vTex); }"
            iProg = createProg(vs, fsI)

            // 过渡着色器
            val transVs = """
                attribute vec4 aPos;
                attribute vec2 aTex;
                varying vec2 vTexA;
                varying vec2 vTexB;
                uniform mat4 uMVP;
                uniform mat4 uSTA;
                uniform mat4 uSTB;
                void main() {
                    gl_Position = uMVP * aPos;
                    vTexA = (uSTA * vec4(aTex, 0.0, 1.0)).xy;
                    vTexB = (uSTB * vec4(aTex, 0.0, 1.0)).xy;
                }
            """.trimIndent()

            val transFs = """
                precision mediump float;
                varying vec2 vTexA;
                varying vec2 vTexB;
                uniform sampler2D sTexA;
                uniform sampler2D sTexB;
                uniform float uProgress;
                uniform float uDirection;
                uniform float uEdgeSoftness;
                uniform float uScreenWidth;

                void main() {
                    vec4 colorA = texture2D(sTexA, vTexA);
                    vec4 colorB = texture2D(sTexB, vTexB);

                    float coord = gl_FragCoord.x / uScreenWidth;
                    float blend;

                    if (uDirection > 0.0) {
                        float edge = 1.0 - uProgress;
                        blend = smoothstep(edge - uEdgeSoftness, edge + uEdgeSoftness, coord);
                        gl_FragColor = mix(colorA, colorB, blend);
                    } else {
                        float edge = uProgress;
                        blend = smoothstep(edge - uEdgeSoftness, edge + uEdgeSoftness, coord);
                        gl_FragColor = mix(colorB, colorA, blend);
                    }
                }
            """.trimIndent()

            transitionProg = createProg(transVs, transFs)

            // 创建纹理
            val tex = IntArray(3)
            GLES20.glGenTextures(3, tex, 0)
            iTexId = tex[0]
            texAId = tex[1]
            texBId = tex[2]

            for (texId in intArrayOf(iTexId, texAId, texBId)) {
                GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texId)
                GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
                GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
                GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
                GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
            }
        }

        private fun draw() {
            // Surface 无效时立即返回
            if (!isSurfaceValid.get()) return
            if (display == EGL14.EGL_NO_DISPLAY || eglSurface == EGL14.EGL_NO_SURFACE) return
            if (imageCount == 0) return

            if (!EGL14.eglMakeCurrent(display, eglSurface, eglSurface, context)) return

            GLES20.glViewport(0, 0, sW, sH)
            GLES20.glClearColor(0f, 0f, 0f, 1f)
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)

            if (scanFromIndex >= 0 && scanToIndex >= 0 && scanProgress > 0f) {
                drawTransition()
            } else {
                drawSingle()
            }

            // 再次检查 Surface 有效性后再 Swap
            if (isSurfaceValid.get()) {
                EGL14.eglSwapBuffers(display, eglSurface)
            }
        }

        private fun drawSingle() {
            val targetIdx = displayedIntIndex.coerceIn(0, imageCount - 1)
            val bitmap = bitmaps.getOrNull(targetIdx)
            if (bitmap != null && !bitmap.isRecycled) {
                uploadBitmapSync(bitmap)
            }

            GLES20.glUseProgram(iProg)

            val cW = bitmap?.width ?: 1
            val cH = bitmap?.height ?: 1

            // 纹理坐标变换（Y翻转）
            val stMat = FloatArray(16)
            Matrix.setIdentityM(stMat, 0)
            Matrix.translateM(stMat, 0, 0f, 1f, 0f)
            Matrix.scaleM(stMat, 0, 1f, -1f, 1f)

            // MVP 矩阵（居中裁剪）
            val mvp = FloatArray(16)
            Matrix.setIdentityM(mvp, 0)
            val cAsp = cW.toFloat() / cH
            val sAsp = if (sH > 0) sW.toFloat() / sH else 9f / 16f
            if (cAsp > sAsp) {
                Matrix.scaleM(mvp, 0, cAsp / sAsp, 1f, 1f)
            } else {
                Matrix.scaleM(mvp, 0, 1f, sAsp / cAsp, 1f)
            }

            val aPos = GLES20.glGetAttribLocation(iProg, "aPos")
            val aTex = GLES20.glGetAttribLocation(iProg, "aTex")
            GLES20.glEnableVertexAttribArray(aPos)
            GLES20.glVertexAttribPointer(aPos, 2, GLES20.GL_FLOAT, false, 8, vBuf)
            GLES20.glEnableVertexAttribArray(aTex)
            GLES20.glVertexAttribPointer(aTex, 2, GLES20.GL_FLOAT, false, 8, tBuf)

            GLES20.glUniformMatrix4fv(GLES20.glGetUniformLocation(iProg, "uMVP"), 1, false, mvp, 0)
            GLES20.glUniformMatrix4fv(GLES20.glGetUniformLocation(iProg, "uST"), 1, false, stMat, 0)

            GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, iTexId)
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        }

        private fun drawTransition() {
            GLES20.glUseProgram(transitionProg)

            val sAsp = if (sH > 0) sW.toFloat() / sH else 9f / 16f

            val bmpA = bitmaps.getOrNull(scanFromIndex)
            val bmpB = bitmaps.getOrNull(scanToIndex)

            // texA 纹理坐标变换
            val stMatA = FloatArray(16)
            val aW = bmpA?.width ?: 1
            val aH = bmpA?.height ?: 1
            val aAsp = aW.toFloat() / aH
            Matrix.setIdentityM(stMatA, 0)
            Matrix.translateM(stMatA, 0, 0f, 1f, 0f)
            Matrix.scaleM(stMatA, 0, 1f, -1f, 1f)
            if (aAsp > sAsp) {
                val scale = aAsp / sAsp
                Matrix.translateM(stMatA, 0, 0.5f, 0.5f, 0f)
                Matrix.scaleM(stMatA, 0, 1f / scale, 1f, 1f)
                Matrix.translateM(stMatA, 0, -0.5f, -0.5f, 0f)
            } else {
                val scale = sAsp / aAsp
                Matrix.translateM(stMatA, 0, 0.5f, 0.5f, 0f)
                Matrix.scaleM(stMatA, 0, 1f, 1f / scale, 1f)
                Matrix.translateM(stMatA, 0, -0.5f, -0.5f, 0f)
            }

            // texB 纹理坐标变换
            val stMatB = FloatArray(16)
            val bW = bmpB?.width ?: 1
            val bH = bmpB?.height ?: 1
            val bAsp = bW.toFloat() / bH
            Matrix.setIdentityM(stMatB, 0)
            Matrix.translateM(stMatB, 0, 0f, 1f, 0f)
            Matrix.scaleM(stMatB, 0, 1f, -1f, 1f)
            if (bAsp > sAsp) {
                val scale = bAsp / sAsp
                Matrix.translateM(stMatB, 0, 0.5f, 0.5f, 0f)
                Matrix.scaleM(stMatB, 0, 1f / scale, 1f, 1f)
                Matrix.translateM(stMatB, 0, -0.5f, -0.5f, 0f)
            } else {
                val scale = sAsp / bAsp
                Matrix.translateM(stMatB, 0, 0.5f, 0.5f, 0f)
                Matrix.scaleM(stMatB, 0, 1f, 1f / scale, 1f)
                Matrix.translateM(stMatB, 0, -0.5f, -0.5f, 0f)
            }

            val mvp = FloatArray(16)
            Matrix.setIdentityM(mvp, 0)

            GLES20.glUniformMatrix4fv(GLES20.glGetUniformLocation(transitionProg, "uMVP"), 1, false, mvp, 0)
            GLES20.glUniformMatrix4fv(GLES20.glGetUniformLocation(transitionProg, "uSTA"), 1, false, stMatA, 0)
            GLES20.glUniformMatrix4fv(GLES20.glGetUniformLocation(transitionProg, "uSTB"), 1, false, stMatB, 0)

            val aPos = GLES20.glGetAttribLocation(transitionProg, "aPos")
            val aTex = GLES20.glGetAttribLocation(transitionProg, "aTex")
            GLES20.glEnableVertexAttribArray(aPos)
            GLES20.glVertexAttribPointer(aPos, 2, GLES20.GL_FLOAT, false, 8, vBuf)
            GLES20.glEnableVertexAttribArray(aTex)
            GLES20.glVertexAttribPointer(aTex, 2, GLES20.GL_FLOAT, false, 8, tBuf)

            GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texAId)
            GLES20.glUniform1i(GLES20.glGetUniformLocation(transitionProg, "sTexA"), 0)

            GLES20.glActiveTexture(GLES20.GL_TEXTURE1)
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texBId)
            GLES20.glUniform1i(GLES20.glGetUniformLocation(transitionProg, "sTexB"), 1)

            GLES20.glUniform1f(GLES20.glGetUniformLocation(transitionProg, "uScreenWidth"), sW.toFloat())
            GLES20.glUniform1f(GLES20.glGetUniformLocation(transitionProg, "uProgress"), scanProgress)
            GLES20.glUniform1f(GLES20.glGetUniformLocation(transitionProg, "uDirection"), scanDirection.toFloat())
            GLES20.glUniform1f(GLES20.glGetUniformLocation(transitionProg, "uEdgeSoftness"), edgeSoftness)

            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
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
                // 先解除上下文绑定
                EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)

                // 销毁 EGL Surface（这会释放与 Surface 的关联）
                if (eglSurface != EGL14.EGL_NO_SURFACE) {
                    EGL14.eglDestroySurface(display, eglSurface)
                    eglSurface = EGL14.EGL_NO_SURFACE
                }

                // 销毁上下文
                if (context != EGL14.EGL_NO_CONTEXT) {
                    EGL14.eglDestroyContext(display, context)
                    context = EGL14.EGL_NO_CONTEXT
                }

                // 终止 EGL 显示连接
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
            // 清空消息队列，防止继续处理
            messageQueue.clear()
            interrupt()
            try {
                join(timeoutMs)
            } catch (e: InterruptedException) {
                // ignore
            }
        }
    }

    companion object {
        private const val TAG = "RasterGLRenderer"
    }
}
