package com.zeaze.tianyinwallpaper.service

import android.content.Intent
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.PixelFormat
import android.graphics.SurfaceTexture
import android.media.MediaPlayer
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
import android.view.Surface
import android.view.SurfaceHolder
import com.alibaba.fastjson.JSON
import com.zeaze.tianyinwallpaper.App
import com.zeaze.tianyinwallpaper.model.TianYinWallpaperModel
import com.zeaze.tianyinwallpaper.utils.FileUtil
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.util.Calendar
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.roundToInt

class TianYinWallpaperService : WallpaperService() {
    override fun onCreateEngine(): Engine {
        return TianYinSolaEngine()
    }

    inner class TianYinSolaEngine : Engine(), SurfaceTexture.OnFrameAvailableListener {
        private var mediaPlayer: MediaPlayer? = null
        private var eglThread: EglThread? = null
        private var list: List<TianYinWallpaperModel>? = null
        private var index = -1
        private var shuffledIndices = mutableListOf<Int>()
        private var shuffledPointer = -1
        private var currentXOffset = 0.5f
        private val initialLoadCompleted = AtomicBoolean(false)
        private val updateSurface = AtomicBoolean(false)
        private var isMediaPlayerPrepared = false

        private var pref: SharedPreferences? = null
        private var wallpaperScrollEnabled = true
        private var pageChangeEnabled = false
        private var lastXOffset = -1f

        init {
            activeEngine = this
        }

        override fun onCreate(surfaceHolder: SurfaceHolder) {
            super.onCreate(surfaceHolder)
            surfaceHolder.setFormat(PixelFormat.RGBX_8888)

            pref = getSharedPreferences(App.TIANYIN, MODE_PRIVATE)
            wallpaperScrollEnabled = pref?.getBoolean("wallpaperScroll", true) == true
            pageChangeEnabled = pref?.getBoolean("pageChange", false) == true

            pref?.registerOnSharedPreferenceChangeListener { sharedPreferences, key ->
                when (key) {
                    "wallpaperScroll" -> {
                        wallpaperScrollEnabled = sharedPreferences.getBoolean(key, true)
                        eglThread?.requestRender()
                    }
                    "pageChange" -> {
                        pageChangeEnabled = sharedPreferences.getBoolean(key, false)
                    }
                }
            }

            try {
                val s = FileUtil.loadData(applicationContext, FileUtil.wallpaperPath)
                list = JSON.parseArray(s, TianYinWallpaperModel::class.java)
            } catch (_: Exception) {
            }
            initialLoadCompleted.set(false)
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
            if (visible) {
                if (mediaPlayer != null && isMediaPlayerPrepared && !mediaPlayer!!.isPlaying) {
                    mediaPlayer!!.start()
                }
                eglThread?.requestRender()

                try {
                    val s = FileUtil.loadData(applicationContext, FileUtil.wallpaperPath)
                    val newList = JSON.parseArray(s, TianYinWallpaperModel::class.java)
                    if (newList != list) {
                        list = newList
                        shuffledIndices.clear()
                        shuffledPointer = -1
                    }
                } catch (_: Exception) {}

                if (checkAutoSwitch()) {
                    nextWallpaper()
                }
            } else {
                if (mediaPlayer != null && mediaPlayer!!.isPlaying) {
                    mediaPlayer!!.pause()
                }
                if (initialLoadCompleted.get()) {
                    if (checkAutoSwitch()) {
                        Handler(mainLooper).postDelayed({ nextWallpaper() }, 100)
                    }
                }
            }
        }

        override fun onOffsetsChanged(
            xOffset: Float,
            yOffset: Float,
            xOffsetStep: Float,
            yOffsetStep: Float,
            xPixelOffset: Int,
            yPixelOffset: Int
        ) {
            currentXOffset = if (wallpaperScrollEnabled) xOffset else 0.5f
            eglThread?.requestRender()

            if (pageChangeEnabled && xOffsetStep > 0 && lastXOffset != -1f) {
                val oldPage = (lastXOffset / xOffsetStep).roundToInt()
                val newPage = (xOffset / xOffsetStep).roundToInt()
                if (oldPage != newPage) {
                    nextWallpaper()
                }
            }
            lastXOffset = xOffset
        }

        private fun checkAutoSwitch(): Boolean {
            val list = this.list ?: return false
            if (list.isEmpty()) return false

            val pref = this.pref ?: return false
            val mode = pref.getInt(PREF_AUTO_SWITCH_MODE, 0)

            val now = System.currentTimeMillis()
            val lastSwitchAt = pref.getLong(PREF_AUTO_SWITCH_LAST_SWITCH_AT, 0L)

            val calendar = Calendar.getInstance()
            val currentMinutes = calendar.get(Calendar.HOUR_OF_DAY) * 60 + calendar.get(Calendar.MINUTE)

            val conditionalIndex = list.indexOfFirst {
                it.startTime != -1 && it.endTime != -1 &&
                currentMinutes >= it.startTime && currentMinutes < it.endTime
            }

            if (conditionalIndex != -1) {
                if (index != conditionalIndex) {
                    index = conditionalIndex
                    eglThread?.post { loadContent() }
                    pref.edit().putLong(PREF_AUTO_SWITCH_LAST_SWITCH_AT, now).apply()
                }
                return false
            }

            if (mode == 0) {
                // If swipe-to-switch is enabled, we don't switch on visibility change to avoid double switching or confusion
                return !pageChangeEnabled
            }

            var shouldSwitch = false
            when (mode) {
                1 -> {
                    var intervalSeconds = pref.getLong(PREF_AUTO_SWITCH_INTERVAL_SECONDS, -1L)
                    if (intervalSeconds == -1L) {
                        intervalSeconds = pref.getLong("autoSwitchIntervalMinutes", 60L) * 60L
                    }
                    if (lastSwitchAt == 0L || now - lastSwitchAt >= intervalSeconds * 1000L) {
                        shouldSwitch = true
                    }
                }
                2 -> {
                    val timePointsStr = pref.getString(PREF_AUTO_SWITCH_TIME_POINTS, "") ?: ""
                    val timePoints = timePointsStr.split(",").filter { it.isNotBlank() }
                    if (timePoints.isNotEmpty()) {
                        val lastSwitchCalendar = Calendar.getInstance().apply { timeInMillis = lastSwitchAt }
                        val lastSwitchDay = lastSwitchCalendar.get(Calendar.DAY_OF_YEAR)
                        val lastSwitchYear = lastSwitchCalendar.get(Calendar.YEAR)
                        val lastSwitchMinutes = lastSwitchCalendar.get(Calendar.HOUR_OF_DAY) * 60 + lastSwitchCalendar.get(Calendar.MINUTE)

                        val currentDay = calendar.get(Calendar.DAY_OF_YEAR)
                        val currentYear = calendar.get(Calendar.YEAR)

                        for (point in timePoints) {
                            try {
                                val parts = point.split(":")
                                if (parts.size == 2) {
                                    val pointMinutes = parts[0].trim().toInt() * 60 + parts[1].trim().toInt()
                                    if (currentYear > lastSwitchYear || currentDay > lastSwitchDay) {
                                        if (currentMinutes >= pointMinutes) {
                                            shouldSwitch = true
                                            break
                                        }
                                    } else {
                                        if (currentMinutes >= pointMinutes && lastSwitchMinutes < pointMinutes) {
                                            shouldSwitch = true
                                            break
                                        }
                                    }
                                }
                            } catch (_: Exception) {
                            }
                        }
                    }
                }
            }

            if (shouldSwitch) {
                pref.edit().putLong(PREF_AUTO_SWITCH_LAST_SWITCH_AT, now).apply()
            }
            return shouldSwitch
        }

        fun next() {
            nextWallpaper()
        }

        fun prev() {
            prevWallpaper()
        }

        private fun nextWallpaper() {
            val list = this.list ?: return
            if (list.isEmpty()) return

            val calendar = Calendar.getInstance()
            val currentMinutes = calendar.get(Calendar.HOUR_OF_DAY) * 60 + calendar.get(Calendar.MINUTE)
            val isRand = pref?.getBoolean("rand", false) == true

            if (isRand) {
                if (shuffledIndices.size != list.size) {
                    shuffledIndices = list.indices.toMutableList()
                    shuffledIndices.shuffle()
                    shuffledPointer = -1
                }

                var found = -1
                var attempts = 0
                while (attempts < list.size) {
                    shuffledPointer++
                    if (shuffledPointer >= shuffledIndices.size) {
                        shuffledIndices.shuffle()
                        shuffledPointer = 0
                    }

                    val nextIdx = shuffledIndices[shuffledPointer]
                    val m = list[nextIdx]
                    if (m.startTime == -1 || (currentMinutes >= m.startTime && currentMinutes < m.endTime)) {
                        found = nextIdx
                        break
                    }
                    attempts++
                }
                index = if (found != -1) found else (index + 1) % list.size
            } else {
                var nextIndex = (index + 1) % list.size
                var count = 0
                while (count < list.size) {
                    val m = list[nextIndex]
                    if (m.startTime == -1 || (currentMinutes >= m.startTime && currentMinutes < m.endTime)) {
                        break
                    }
                    nextIndex = (nextIndex + 1) % list.size
                    count++
                }
                index = nextIndex
            }
            eglThread?.post { loadContent() }

            // Save current index to prefs so activity can sync
            pref?.edit()?.putInt(PREF_CURRENT_INDEX, index)?.apply()
        }

        fun updateIndex(newIndex: Int) {
            if (newIndex in 0 until (list?.size ?: 0)) {
                index = newIndex
                eglThread?.post { loadContent() }
            }
        }

        private fun prevWallpaper() {
            val list = this.list ?: return
            if (list.isEmpty()) return

            val calendar = Calendar.getInstance()
            val currentMinutes = calendar.get(Calendar.HOUR_OF_DAY) * 60 + calendar.get(Calendar.MINUTE)

            var nextIndex = if (index <= 0) list.size - 1 else index - 1
            var count = 0
            while (count < list.size) {
                val m = list[nextIndex]
                if (m.startTime == -1 || (currentMinutes >= m.startTime && currentMinutes < m.endTime)) {
                    break
                }
                nextIndex = if (nextIndex <= 0) list.size - 1 else nextIndex - 1
                count++
            }
            index = nextIndex
            eglThread?.post { loadContent() }

            // Save current index to prefs so activity can sync
            pref?.edit()?.putInt(PREF_CURRENT_INDEX, index)?.apply()
        }

        private fun loadContent() {
            if (index < 0 || index >= (list?.size ?: 0)) return
            val model = list!![index]

            if (mediaPlayer != null) {
                mediaPlayer!!.reset()
                isMediaPlayerPrepared = false
            }

            if (model.type == 1) {
                prepareVideo(model)
            } else {
                prepareImage(model)
            }
        }

        private fun prepareVideo(model: TianYinWallpaperModel) {
            try {
                if (mediaPlayer == null) mediaPlayer = MediaPlayer()
                mediaPlayer!!.reset()
                mediaPlayer!!.setDataSource(applicationContext, Uri.parse(model.videoUri))

                val st = eglThread?.videoST
                if (st == null) return

                val surface = Surface(st)
                mediaPlayer!!.setSurface(surface)
                surface.release()

                mediaPlayer!!.setVolume(0f, 0f)

                if (model.loop) {
                    mediaPlayer!!.setOnSeekCompleteListener { mp ->
                        if (isVisible) {
                            mp.start()
                        }
                    }
                    mediaPlayer!!.setOnCompletionListener { mp ->
                        try {
                            mp.seekTo(0)
                        } catch (e: IllegalStateException) {
                            Log.w(TAG, "seekTo(0) failed on loop completion", e)
                        }
                    }
                } else {
                    mediaPlayer!!.setOnSeekCompleteListener(null)
                    mediaPlayer!!.setOnCompletionListener(null)
                }

                mediaPlayer!!.setOnErrorListener { _, what, extra ->
                    Log.e(TAG, "MediaPlayer error: what=$what, extra=$extra")
                    isMediaPlayerPrepared = false
                    Handler(mainLooper).post { nextWallpaper() }
                    true
                }

                mediaPlayer!!.setOnInfoListener { _, what, _ ->
                    if (what == MediaPlayer.MEDIA_INFO_VIDEO_RENDERING_START) {
                        isMediaPlayerPrepared = true
                    }
                    false
                }

                mediaPlayer!!.setOnPreparedListener { mp ->
                    val w = mp.videoWidth
                    val h = mp.videoHeight
                    eglThread?.setContentSize(w, h)
                    val videoST = eglThread?.videoST
                    if (videoST != null) {
                        videoST.setDefaultBufferSize(w, h)
                    }
                    isMediaPlayerPrepared = true
                    if (isVisible) {
                        mp.start()
                    }
                    markInitialLoadComplete()
                }
                mediaPlayer!!.prepareAsync()
                eglThread?.resetVideoMatrix()
            } catch (e: Exception) {
                Log.e(TAG, "Video error", e)
                markInitialLoadComplete()
                Handler(mainLooper).post { nextWallpaper() }
            }
        }

        private fun prepareImage(model: TianYinWallpaperModel) {
            if (mediaPlayer != null) {
                mediaPlayer!!.reset()
                isMediaPlayerPrepared = false
            }
            try {
                val `is`: InputStream? = applicationContext.contentResolver.openInputStream(Uri.parse(model.imgUri))
                val bitmap = BitmapFactory.decodeStream(`is`)
                `is`?.close()
                if (bitmap != null) {
                    eglThread?.setContentSize(bitmap.width, bitmap.height)
                    eglThread?.uploadBitmap(bitmap)
                    markInitialLoadComplete()
                } else {
                    markInitialLoadComplete()
                    Handler(mainLooper).post { nextWallpaper() }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Image error", e)
                markInitialLoadComplete()
                Handler(mainLooper).post { nextWallpaper() }
            }
        }

        private fun markInitialLoadComplete() {
            initialLoadCompleted.set(true)
        }

        override fun onFrameAvailable(surfaceTexture: SurfaceTexture) {
            updateSurface.set(true)
            eglThread?.requestRender()
        }

        override fun onDestroy() {
            super.onDestroy()
            if (activeEngine == this) activeEngine = null
            mediaPlayer?.release()
            mediaPlayer = null
            eglThread?.finish()
        }

        private inner class EglThread(private val holder: SurfaceHolder) : HandlerThread("TianYinEGL") {
            private var display: EGLDisplay = EGL14.EGL_NO_DISPLAY
            private var context: EGLContext = EGL14.EGL_NO_CONTEXT
            private var eglSurface: EGLSurface = EGL14.EGL_NO_SURFACE
            private var handler: Handler? = null
            var videoST: SurfaceTexture? = null
                private set
            private var vTexId = 0
            private var iTexId = 0
            private var vProg = 0
            private var iProg = 0
            private var vBuf: FloatBuffer
            private var tBuf: FloatBuffer
            private var sW = 0
            private var sH = 0
            private var cW = 1
            private var cH = 1
            private val videoSTMatrix = FloatArray(16)
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

            fun onSizeChanged(w: Int, h: Int) {
                sW = w
                sH = h
                requestRender()
            }

            fun setContentSize(w: Int, h: Int) {
                cW = if (w > 0) w else 1
                cH = if (h > 0) h else 1
            }

            fun post(r: () -> Unit) {
                handler?.post(r)
            }

            fun resetVideoMatrix() {
                post { Matrix.setIdentityM(videoSTMatrix, 0) }
            }

            override fun onLooperPrepared() {
                if (!initEGL()) return
                initGL()
                handler = Handler(looper)
                post {
                    if (index == -1) nextWallpaper() else loadContent()
                }
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
                    display,
                    configs[0],
                    EGL14.EGL_NO_CONTEXT,
                    intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE),
                    0
                )
                eglSurface = EGL14.eglCreateWindowSurface(
                    display,
                    configs[0],
                    holder.surface,
                    intArrayOf(EGL14.EGL_NONE),
                    0
                )
                return EGL14.eglMakeCurrent(display, eglSurface, eglSurface, context)
            }

            private fun initGL() {
                val vs = "attribute vec4 aPos; attribute vec2 aTex; varying vec2 vTex; uniform mat4 uMVP; uniform mat4 uST; void main(){ gl_Position = uMVP * aPos; vTex = (uST * vec4(aTex,0,1)).xy; }"
                val fsV = "#extension GL_OES_EGL_image_external : require\n precision mediump float; varying vec2 vTex; uniform samplerExternalOES sTex; void main(){ gl_FragColor = texture2D(sTex, vTex); }"
                val fsI = "precision mediump float; varying vec2 vTex; uniform sampler2D sTex; void main(){ gl_FragColor = texture2D(sTex, vTex); }"
                vProg = createProg(vs, fsV)
                iProg = createProg(vs, fsI)

                val tex = IntArray(2)
                GLES20.glGenTextures(2, tex, 0)
                vTexId = tex[0]
                iTexId = tex[1]

                GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, vTexId)
                GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
                videoST = SurfaceTexture(vTexId)
                videoST?.setOnFrameAvailableListener(this@TianYinSolaEngine)

                GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, iTexId)
                GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)

                Matrix.setIdentityM(videoSTMatrix, 0)
            }

            fun uploadBitmap(b: Bitmap) {
                post {
                    GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, iTexId)
                    GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, b, 0)
                    b.recycle()
                    requestRender()
                }
            }

            fun requestRender() {
                if (handler != null) {
                    handler!!.removeCallbacks(drawRunnable)
                    handler!!.post(drawRunnable)
                }
            }

            private val drawRunnable = Runnable { draw() }

            private fun draw() {
                if (eglSurface == EGL14.EGL_NO_SURFACE) return
                EGL14.eglMakeCurrent(display, eglSurface, eglSurface, context)

                val isVid = list != null && index >= 0 && index < list!!.size && list!![index].type == 1
                val stMat = FloatArray(16)

                if (isVid) {
                    if (updateSurface.getAndSet(false)) {
                        try {
                            videoST?.updateTexImage()
                            videoST?.getTransformMatrix(videoSTMatrix)
                        } catch (e: Exception) {
                            Log.w(TAG, "updateTexImage failed, using old frame", e)
                        }
                    }
                    System.arraycopy(videoSTMatrix, 0, stMat, 0, 16)
                } else {
                    System.arraycopy(imageMatrix, 0, stMat, 0, 16)
                }

                GLES20.glViewport(0, 0, sW, sH)
                GLES20.glClearColor(0.01f, 0.01f, 0.01f, 1.0f)
                GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)

                val prog = if (isVid) vProg else iProg
                GLES20.glUseProgram(prog)

                val mvp = FloatArray(16)
                Matrix.setIdentityM(mvp, 0)
                val cAsp = cW.toFloat() / cH
                val sAsp = sW.toFloat() / sH

                if (cAsp > sAsp) {
                    val scale = cAsp / sAsp
                    val tx = (scale - 1.0f) * (1.0f - currentXOffset * 2.0f)
                    Matrix.scaleM(mvp, 0, scale, 1.0f, 1.0f)
                    Matrix.translateM(mvp, 0, tx / scale, 0f, 0f)
                } else {
                    Matrix.scaleM(mvp, 0, 1.0f, sAsp / cAsp, 1.0f)
                }

                val aPos = GLES20.glGetAttribLocation(prog, "aPos")
                val aTex = GLES20.glGetAttribLocation(prog, "aTex")
                GLES20.glEnableVertexAttribArray(aPos)
                GLES20.glVertexAttribPointer(aPos, 2, GLES20.GL_FLOAT, false, 8, vBuf)
                GLES20.glEnableVertexAttribArray(aTex)
                GLES20.glVertexAttribPointer(aTex, 2, GLES20.GL_FLOAT, false, 8, tBuf)

                GLES20.glUniformMatrix4fv(GLES20.glGetUniformLocation(prog, "uMVP"), 1, false, mvp, 0)
                GLES20.glUniformMatrix4fv(GLES20.glGetUniformLocation(prog, "uST"), 1, false, stMat, 0)

                GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
                GLES20.glBindTexture(if (isVid) GLES11Ext.GL_TEXTURE_EXTERNAL_OES else GLES20.GL_TEXTURE_2D, if (isVid) vTexId else iTexId)
                GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)

                if (!EGL14.eglSwapBuffers(display, eglSurface)) {
                    Log.e(TAG, "SwapBuffers failed")
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
            ACTION_PREV_WALLPAPER -> activeEngine?.prev()
            ACTION_NEXT_WALLPAPER -> activeEngine?.next()
            ACTION_UPDATE_INDEX -> {
                val idx = intent.getIntExtra(EXTRA_INDEX, -1)
                if (idx != -1) activeEngine?.updateIndex(idx)
            }
        }
        return super.onStartCommand(intent, flags, startId)
    }

    companion object {
        const val ACTION_PREV_WALLPAPER = "com.zeaze.tianyinwallpaper.PREV"
        const val ACTION_NEXT_WALLPAPER = "com.zeaze.tianyinwallpaper.NEXT"
        const val ACTION_UPDATE_INDEX = "com.zeaze.tianyinwallpaper.UPDATE_INDEX"
        const val EXTRA_INDEX = "extra_index"
        const val PREF_CURRENT_INDEX = "current_wallpaper_index"

        private var activeEngine: TianYinSolaEngine? = null
        const val PREF_AUTO_SWITCH_MODE = "autoSwitchMode"
        const val PREF_AUTO_SWITCH_INTERVAL_SECONDS = "autoSwitchIntervalSeconds"
        const val PREF_AUTO_SWITCH_TIME_POINTS = "autoSwitchTimePoints"
        const val PREF_AUTO_SWITCH_ANCHOR_AT = "autoSwitchAnchorAt"
        const val PREF_AUTO_SWITCH_LAST_SWITCH_AT = "autoSwitchLastSwitchAt"
        private const val TAG = "TianYinGL"
    }
}