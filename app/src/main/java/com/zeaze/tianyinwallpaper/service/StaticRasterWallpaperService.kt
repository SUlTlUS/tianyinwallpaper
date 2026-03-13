package com.zeaze.tianyinwallpaper.service

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.PixelFormat
import android.graphics.SurfaceTexture
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.net.Uri
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLSurface
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.opengl.GLUtils
import android.opengl.Matrix
import android.os.Handler
import android.os.HandlerThread
import android.service.wallpaper.WallpaperService
import android.util.Log
import android.view.SurfaceHolder
import com.alibaba.fastjson.JSON
import com.zeaze.tianyinwallpaper.App
import com.zeaze.tianyinwallpaper.model.RasterGroupModel
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 图集光栅壁纸服务 - 专门处理静态图片光栅
 * 基于陀螺仪控制图片切换和扫描线过渡效果
 */
class StaticRasterWallpaperService : WallpaperService() {
    private var activeEngine: StaticRasterEngine? = null

    override fun onCreateEngine(): Engine = StaticRasterEngine()

    inner class StaticRasterEngine : Engine(), SensorEventListener {

        init {
            activeEngine = this
        }

        // ── 数据 ──
        private var group: RasterGroupModel? = null
        private var sensorWidth = 0.6f
        private val transitionBand = 0.55f
        private var isVisible = false

        // ── 传感器 ──
        private var sensorManager: SensorManager? = null
        private var gyroSensor: Sensor? = null
        private var lastGyroNs = 0L
        private var accumulatedAngle = 0f
        private var stationaryDuration = 0f
        private var tiltNormalized = 0f
        private var tiltDirection = 0
        
        private var filteredAngle = 0f
        private val FILTER_ALPHA = 0.15f

        // ── 静态光栅 ──
        private var staticBitmaps = mutableListOf<Bitmap?>()
        private var imageCount = 0

        // ── 扫描线状态 ──
        private var currentFloatIndex = 0f
        private var displayedIntIndex = -1
        private var scanFromIndex = -1
        private var scanToIndex = -1
        private var scanProgress = 0f
        private var scanDirection = 0

        // ── EGL ──
        private var eglThread: EglThread? = null
        private var _pref: SharedPreferences? = null
        private val pref: SharedPreferences? get() = _pref

        fun reload() {
            loadActiveGroup()
            eglThread?.post { loadContent() }
        }

        // ────────────────────────────────────────────
        // 生命周期
        // ────────────────────────────────────────────

        override fun onCreate(surfaceHolder: SurfaceHolder) {
            super.onCreate(surfaceHolder)
            surfaceHolder.setFormat(PixelFormat.RGBX_8888)

            _pref = getSharedPreferences(App.TIANYIN, MODE_PRIVATE)
            sensorManager = getSystemService(Context.SENSOR_SERVICE) as SensorManager
            gyroSensor = sensorManager?.getDefaultSensor(Sensor.TYPE_GYROSCOPE)

            loadActiveGroup()
        }

        override fun onSurfaceCreated(holder: SurfaceHolder) {
            super.onSurfaceCreated(holder)
            eglThread?.finish()
            eglThread = EglThread(holder)
            eglThread?.start()
        }

        override fun onSurfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
            super.onSurfaceChanged(holder, format, width, height)
            eglThread?.onSizeChanged(width, height)
        }

        override fun onVisibilityChanged(visible: Boolean) {
            super.onVisibilityChanged(visible)
            isVisible = visible
            
            if (visible) {
                registerSensor()

                val newGroupId = _pref?.getString(PREF_RASTER_ACTIVE_GROUP_ID, null)
                if (newGroupId != group?.id) {
                    loadActiveGroup()
                    eglThread?.post { loadContent() }
                }
            } else {
                unregisterSensor()
            }
        }

        override fun onDestroy() {
            super.onDestroy()
            if (activeEngine == this) activeEngine = null
            unregisterSensor()
            releaseStaticBitmaps()
            eglThread?.finish()
        }

        // ────────────────────────────────────────────
        // 传感器
        // ────────────────────────────────────────────

        private fun registerSensor() {
            gyroSensor?.let {
                sensorManager?.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME)
            }
            lastGyroNs = 0L
            accumulatedAngle = 0f
            filteredAngle = 0f
            stationaryDuration = 0f
            tiltNormalized = 0f
            tiltDirection = 0
        }

        private fun unregisterSensor() {
            sensorManager?.unregisterListener(this)
            lastGyroNs = 0L
        }

        override fun onSensorChanged(event: SensorEvent?) {
            val e = event ?: return
            if (e.sensor.type != Sensor.TYPE_GYROSCOPE) return

            if (lastGyroNs == 0L) {
                lastGyroNs = e.timestamp
                return
            }

            val dt = (e.timestamp - lastGyroNs) / 1_000_000_000f
            lastGyroNs = e.timestamp

            val angularVelocity = e.values[1]
            val absOmega = Math.abs(angularVelocity)
            if (absOmega >= 0.01f) {
                val rawDelta = angularVelocity * dt
                accumulatedAngle += rawDelta
                filteredAngle = FILTER_ALPHA * rawDelta + (1 - FILTER_ALPHA) * filteredAngle
            }

            val currentAngle = accumulatedAngle
            val newTilt = (Math.abs(currentAngle) / sensorWidth).coerceIn(0f, 1f)
            
            val newDirection = when {
                accumulatedAngle < -0.05f -> 1
                accumulatedAngle > 0.05f -> -1
                else -> tiltDirection
            }

            if (newTilt != tiltNormalized || newDirection != tiltDirection) {
                tiltNormalized = newTilt
                tiltDirection = newDirection
                onTiltChanged()
            }
        }

        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

        private fun onTiltChanged() {
            val g = group ?: return
            if (g.type != RasterGroupModel.TYPE_STATIC) return
            
            if (imageCount == 0) return

            val newFloatIndex = tiltNormalized * (imageCount - 1)
            currentFloatIndex = newFloatIndex.coerceIn(0f, (imageCount - 1).toFloat())

            if (imageCount == 1) {
                if (displayedIntIndex != 0) {
                    displayedIntIndex = 0
                    eglThread?.uploadStaticFrame(0)
                }
                scanProgress = 0f
                scanFromIndex = -1
                scanToIndex = -1
                eglThread?.requestRender()
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
                val bitmap = staticBitmaps.getOrNull(fromIdx)
                if (bitmap != null && displayedIntIndex != fromIdx) {
                    displayedIntIndex = fromIdx
                    eglThread?.setContentSize(bitmap.width, bitmap.height)
                    eglThread?.uploadBitmapSync(bitmap)
                }
            } else if (fraction > bandEnd) {
                scanFromIndex = -1
                scanToIndex = -1
                scanProgress = 0f
                val bitmap = staticBitmaps.getOrNull(toIdx)
                if (bitmap != null && displayedIntIndex != toIdx) {
                    displayedIntIndex = toIdx
                    eglThread?.setContentSize(bitmap.width, bitmap.height)
                    eglThread?.uploadBitmapSync(bitmap)
                }
            } else {
                val mappedProgress = ((fraction - bandStart) / transitionBand).coerceIn(0f, 1f)
                if (scanFromIndex != fromIdx || scanToIndex != toIdx) {
                    scanFromIndex = fromIdx
                    scanToIndex = toIdx
                    scanDirection = if (tiltDirection >= 0) 1 else -1
                    eglThread?.post {
                        val bmpA = staticBitmaps.getOrNull(fromIdx)
                        val bmpB = staticBitmaps.getOrNull(toIdx)
                        if (bmpA != null) eglThread?.uploadToTexA(bmpA)
                        if (bmpB != null) eglThread?.uploadToTexB(bmpB)
                    }
                }
                scanDirection = if (tiltDirection >= 0) 1 else -1
                scanProgress = mappedProgress
            }
            eglThread?.requestRender()
        }

        // ────────────────────────────────────────────
        // 数据加载
        // ────────────────────────────────────────────

        private fun loadActiveGroup() {
            val activeId = _pref?.getString(PREF_RASTER_ACTIVE_GROUP_ID, null) ?: return
            val groupsJson = _pref?.getString(PREF_RASTER_GROUPS, "[]") ?: "[]"
            val groups = try {
                JSON.parseArray(groupsJson, RasterGroupModel::class.java) ?: emptyList()
            } catch (_: Exception) {
                emptyList()
            }
            group = groups.firstOrNull { it.id == activeId } ?: groups.firstOrNull()
        }

        private fun loadContent() {
            val g = group ?: return
            if (g.type != RasterGroupModel.TYPE_STATIC) return

            prepareStaticImages(g)
        }

        private fun prepareStaticImages(g: RasterGroupModel) {
            releaseStaticBitmaps()
            staticBitmaps.clear()

            g.imageUris.forEach { uriStr ->
                val bitmap = try {
                    applicationContext.contentResolver.openInputStream(Uri.parse(uriStr))?.use {
                        BitmapFactory.decodeStream(it)
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to decode static image: $uriStr", e)
                    null
                }
                staticBitmaps.add(bitmap)
            }

            imageCount = staticBitmaps.size
            displayedIntIndex = -1
            scanFromIndex = -1
            scanToIndex = -1
            scanProgress = 0f

            currentFloatIndex = 0f
            if (staticBitmaps.isNotEmpty()) {
                displayedIntIndex = 0
                uploadStaticFrame(0)
            }
        }

        private fun uploadStaticFrame(index: Int) {
            val bitmap = staticBitmaps.getOrNull(index) ?: return
            eglThread?.setContentSize(bitmap.width, bitmap.height)
            eglThread?.uploadBitmap(bitmap, recycle = false)
        }

        private fun releaseStaticBitmaps() {
            staticBitmaps.forEach { it?.recycle() }
            staticBitmaps.clear()
        }

        // ────────────────────────────────────────────
        // EGL 渲染线程
        // ────────────────────────────────────────────

        private inner class EglThread(private val holder: SurfaceHolder) : HandlerThread("StaticRasterEGL") {
            private var display: EGLDisplay = EGL14.EGL_NO_DISPLAY
            private var context: EGLContext = EGL14.EGL_NO_CONTEXT
            private var eglSurface: EGLSurface = EGL14.EGL_NO_SURFACE
            private var handler: Handler? = null

            private var iTexId = 0
            private var texAId = 0
            private var texBId = 0
            private var iProg = 0
            private var transitionProg = 0
            private var vBuf: FloatBuffer
            private var tBuf: FloatBuffer

            private var sW = 0
            private var sH = 0
            private var cW = 1
            private var cH = 1

            private val imageMatrix = FloatArray(16)

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
            }

            fun uploadBitmapSync(b: Bitmap) {
                GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, iTexId)
                GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, b, 0)
            }

            fun uploadStaticFrame(index: Int) {
                val bitmap = this@StaticRasterEngine.staticBitmaps.getOrNull(index) ?: return
                setContentSize(bitmap.width, bitmap.height)
                uploadBitmapSync(bitmap)
            }

            fun onSizeChanged(w: Int, h: Int) {
                sW = w; sH = h
                requestRender()
            }

            fun setContentSize(w: Int, h: Int) {
                cW = if (w > 0) w else 1
                cH = if (h > 0) h else 1
            }

            fun post(r: () -> Unit) { handler?.post(r) }

            fun uploadBitmap(b: Bitmap, recycle: Boolean = true) {
                post {
                    GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, iTexId)
                    GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, b, 0)
                    if (recycle) b.recycle()
                    requestRender()
                }
            }

            fun uploadToTexA(b: Bitmap) {
                GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texAId)
                GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, b, 0)
            }

            fun uploadToTexB(b: Bitmap) {
                GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texBId)
                GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, b, 0)
            }

            override fun onLooperPrepared() {
                if (!initEGL()) return
                initGL()
                handler = Handler(looper)
                post { loadContent() }
            }

            private fun initEGL(): Boolean {
                display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
                val version = IntArray(2)
                EGL14.eglInitialize(display, version, 0, version, 1)
                val attr = intArrayOf(
                    EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8, EGL14.EGL_BLUE_SIZE, 8,
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
                    display, configs[0], holder.surface,
                    intArrayOf(EGL14.EGL_NONE), 0
                )
                return EGL14.eglMakeCurrent(display, eglSurface, eglSurface, context)
            }

            private fun initGL() {
                val vs = "attribute vec4 aPos; attribute vec2 aTex; varying vec2 vTex; uniform mat4 uMVP; uniform mat4 uST; void main(){ gl_Position = uMVP * aPos; vTex = (uST * vec4(aTex,0,1)).xy; }"
                val fsI = "precision mediump float; varying vec2 vTex; uniform sampler2D sTex; void main(){ gl_FragColor = texture2D(sTex, vTex); }"
                iProg = createProg(vs, fsI)

                val transVs = """
                    attribute vec4 aPos;
                    attribute vec2 aTex;
                    varying vec2 vTex;
                    uniform mat4 uMVP;
                    uniform mat4 uST;
                    void main() {
                        gl_Position = uMVP * aPos;
                        vTex = (uST * vec4(aTex, 0.0, 1.0)).xy;
                    }
                """.trimIndent()

                val transFs = """
                    precision mediump float;
                    varying vec2 vTex;
                    uniform sampler2D sTexA;
                    uniform sampler2D sTexB;
                    uniform float uProgress;
                    uniform float uDirection;
                    uniform float uEdgeSoftness;
                    uniform float uScreenWidth;
                    
                    void main() {
                        vec4 colorA = texture2D(sTexA, vTex);
                        vec4 colorB = texture2D(sTexB, vTex);
                        
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

            fun requestRender() {
                handler?.removeCallbacks(drawRunnable)
                handler?.post(drawRunnable)
            }

            private val drawRunnable = Runnable { draw() }

            private fun draw() {
                if (eglSurface == EGL14.EGL_NO_SURFACE) return
                EGL14.eglMakeCurrent(display, eglSurface, eglSurface, context)

                GLES20.glViewport(0, 0, sW, sH)
                GLES20.glClearColor(0f, 0f, 0f, 1f)
                GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)

                val g = group

                if (g?.type == RasterGroupModel.TYPE_STATIC && scanFromIndex >= 0 && scanToIndex >= 0 && scanProgress > 0f) {
                    drawTransition()
                } else if (g?.type == RasterGroupModel.TYPE_STATIC) {
                    val targetIdx = displayedIntIndex
                    val bitmap = staticBitmaps.getOrNull(targetIdx)
                    if (bitmap != null) {
                        uploadBitmapSync(bitmap)
                    }

                    GLES20.glUseProgram(iProg)

                    val stMat = FloatArray(16)
                    System.arraycopy(imageMatrix, 0, stMat, 0, 16)

                    val mvp = FloatArray(16)
                    Matrix.setIdentityM(mvp, 0)
                    val cAsp = cW.toFloat() / cH
                    val sAsp = sW.toFloat() / sH
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

                if (!EGL14.eglSwapBuffers(display, eglSurface)) {
                    Log.e(TAG, "SwapBuffers failed")
                }
            }

            private fun drawTransition() {
                GLES20.glUseProgram(transitionProg)

                val stMat = FloatArray(16)
                System.arraycopy(imageMatrix, 0, stMat, 0, 16)

                val mvp = FloatArray(16)
                Matrix.setIdentityM(mvp, 0)
                val cAsp = cW.toFloat() / cH
                val sAsp = sW.toFloat() / sH
                if (cAsp > sAsp) {
                    Matrix.scaleM(mvp, 0, cAsp / sAsp, 1f, 1f)
                } else {
                    Matrix.scaleM(mvp, 0, 1f, sAsp / cAsp, 1f)
                }

                GLES20.glUniformMatrix4fv(GLES20.glGetUniformLocation(transitionProg, "uMVP"), 1, false, mvp, 0)
                GLES20.glUniformMatrix4fv(GLES20.glGetUniformLocation(transitionProg, "uST"), 1, false, stMat, 0)

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
                GLES20.glUniform1f(GLES20.glGetUniformLocation(transitionProg, "uEdgeSoftness"), 0.15f)

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

            fun finish() {
                quitSafely()
                if (display != EGL14.EGL_NO_DISPLAY) {
                    EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
                    EGL14.eglDestroySurface(display, eglSurface)
                    EGL14.eglDestroyContext(display, context)
                    EGL14.eglTerminate(display)
                }
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_RELOAD -> activeEngine?.reload()
        }
        return super.onStartCommand(intent, flags, startId)
    }

    companion object {
        const val ACTION_RELOAD = "com.zeaze.tianyinwallpaper.STATIC_RASTER_RELOAD"
        const val PREF_RASTER_GROUPS = "rasterGroups"
        const val PREF_RASTER_ACTIVE_GROUP_ID = "rasterActiveGroupId"
        private const val TAG = "StaticRasterGL"
    }
}
