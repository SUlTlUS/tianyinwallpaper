package com.zeaze.tianyinwallpaper.service

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
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
import android.opengl.Matrix
import android.os.Handler
import android.os.HandlerThread
import android.service.wallpaper.WallpaperService
import android.util.Log
import android.view.Surface
import android.view.SurfaceHolder
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.SeekParameters
import com.alibaba.fastjson.JSON
import com.zeaze.tianyinwallpaper.App
import com.zeaze.tianyinwallpaper.model.RasterGroupModel
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.abs
import kotlin.math.sqrt

@UnstableApi
class VideoRasterWallpaperService : WallpaperService() {
    private var activeEngine: VideoRasterEngine? = null

    override fun onCreateEngine(): Engine = VideoRasterEngine()

    data class ReverseBean(
        var reverseSupport: Boolean = true,
        var reverseStiffness: Float = 180f,
        var reverseDampingRatio: Float = 0.85f,
        var mStartVelocity: Float = 0f,
        var mLastReverse: Boolean = false
    ) {
        fun damping(): Float = 2f * sqrt(reverseStiffness) * reverseDampingRatio
    }

    inner class AngleSensor : SensorEventListener {
        private var sensorManager: SensorManager? = null
        private var gyroSensor: Sensor? = null

        var mCurAngleSensorValue: Float = 0f
            private set

        private var lastTimestamp: Long = 0L
        private var accumulatedAngle: Float = 0f
        private var filteredAngle: Float = 0f

        private val FILTER_ALPHA = 0.12f
        private val MAX_ANGLE_RAD = Math.toRadians(45.0).toFloat()
        private val DEAD_ZONE_RAD = Math.toRadians(1.5).toFloat()

        var onAngleChanged: ((Float) -> Unit)? = null

        fun registerSensor(context: Context) {
            sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
            gyroSensor = sensorManager?.getDefaultSensor(Sensor.TYPE_GYROSCOPE)

            val hasSensor = gyroSensor != null
            Log.d(TAG, "AngleSensor: registerSensor, hasGyroSensor=$hasSensor")

            gyroSensor?.let {
                sensorManager?.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME)
            }

            reset()
        }

        fun unregisterSensor() {
            Log.d(TAG, "AngleSensor: unregisterSensor")
            sensorManager?.unregisterListener(this)
            reset()
        }

        fun reset() {
            lastTimestamp = 0L
            accumulatedAngle = 0f
            filteredAngle = 0f
            mCurAngleSensorValue = 0f
        }

        override fun onSensorChanged(event: SensorEvent?) {
            val e = event ?: return
            if (e.sensor.type != Sensor.TYPE_GYROSCOPE) return

            processGyroscopeEvent(e)
        }

        private fun processGyroscopeEvent(event: SensorEvent) {
            if (lastTimestamp == 0L) {
                lastTimestamp = event.timestamp
                return
            }

            val dt = (event.timestamp - lastTimestamp) / 1_000_000_000f
            lastTimestamp = event.timestamp

            if (dt <= 0 || dt > 0.5f) return

            val angularVelocity = event.values[1]
            accumulatedAngle += angularVelocity * dt
            accumulatedAngle = accumulatedAngle.coerceIn(-MAX_ANGLE_RAD, MAX_ANGLE_RAD)
            filteredAngle = FILTER_ALPHA * accumulatedAngle + (1 - FILTER_ALPHA) * filteredAngle

            val angleWithDeadZone = if (abs(filteredAngle) < DEAD_ZONE_RAD) {
                0f
            } else {
                filteredAngle
            }

            mCurAngleSensorValue = angleWithDeadZone
            onAngleChanged?.invoke(mCurAngleSensorValue)
        }

        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
    }

    inner class VideoRasterEngine : Engine(), SurfaceTexture.OnFrameAvailableListener {

        init {
            activeEngine = this
        }

        private var group: RasterGroupModel? = null
        private var isVisible = false

        private val angleSensor = AngleSensor()

        private val reverseBean = ReverseBean()
        private var playbackSpeed = 0f
        private var currentPlaybackPosition = 0f
        private var lastUpdateTime = System.currentTimeMillis()

        private var exoPlayer: ExoPlayer? = null
        private var isPlayerPrepared = false
        private var videoDurationUs = 0L
        private var videoTime = 0L
        private var videoDuration = 0L
        private var videoFrameIndex = 0
        private var videoFrameCount = 0
        private var videoFrameRate = 30f
        private var lastFrameIndex = -1

        private var eglHandler: Handler? = null
        private var isWaitingForSurface = false

        private var eglThread: EglThread? = null
        private val updateSurface = AtomicBoolean(false)

        private var pref: SharedPreferences? = null

        private var frameCount = 0L

        fun reload() {
            loadActiveGroup()
            eglThread?.post { loadContent() }
        }

        private fun isReverseOrBreakOperation(): Boolean {
            return playbackSpeed < 0
        }

        override fun onCreate(surfaceHolder: SurfaceHolder) {
            super.onCreate(surfaceHolder)
            surfaceHolder.setFormat(PixelFormat.RGBX_8888)

            pref = getSharedPreferences(App.TIANYIN, MODE_PRIVATE)

            angleSensor.onAngleChanged = { angleValue ->
                doSensorChangeEvent(angleValue)
            }

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
            Log.d(TAG, "onVisibilityChanged: visible=$visible, isPlayerPrepared=$isPlayerPrepared")

            if (visible) {
                angleSensor.registerSensor(applicationContext)
                checkGroupChange()
            } else {
                angleSensor.unregisterSensor()
            }
        }

        override fun onDestroy() {
            super.onDestroy()
            if (activeEngine == this) activeEngine = null
            angleSensor.unregisterSensor()
            releasePlayer()
            eglThread?.finish()
        }

        private fun checkGroupChange() {
            val newGroupId = pref?.getString(PREF_RASTER_ACTIVE_GROUP_ID, null)
            if (newGroupId != group?.id) {
                loadActiveGroup()
                eglThread?.post { loadContent() }
            }
        }

        private fun doSensorChangeEvent(sensorValue: Float) {
            if (!isPlayerPrepared) return

            val maxAngle = Math.toRadians(45.0).toFloat()
            val normalizedAngle = sensorValue / maxAngle

            playbackSpeed = normalizedAngle.coerceIn(-1f, 1f)

            val currentReverse = playbackSpeed < 0
            reverseBean.mLastReverse = currentReverse

            Log.d(TAG, "Sensor: angle=${String.format("%.3f", sensorValue)}, speed=${String.format("%.3f", playbackSpeed)}")
        }

        private fun loadActiveGroup() {
            val activeId = pref?.getString(PREF_RASTER_ACTIVE_GROUP_ID, null) ?: return
            val groupsJson = pref?.getString(PREF_RASTER_GROUPS, "[]") ?: "[]"
            val groups = try {
                JSON.parseArray(groupsJson, RasterGroupModel::class.java) ?: emptyList()
            } catch (_: Exception) {
                emptyList()
            }
            group = groups.firstOrNull { it.id == activeId } ?: groups.firstOrNull()
        }

        private fun loadContent() {
            val g = group ?: return
            if (g.type != RasterGroupModel.TYPE_DYNAMIC) return

            releasePlayer()
            prepareVideo(g)
        }

        private fun prepareVideo(g: RasterGroupModel) {
            val videoUri = g.videoUri
            if (videoUri.isNullOrEmpty()) {
                Log.e(TAG, "Video URI is empty")
                return
            }

            val st = eglThread?.videoST
            Log.d(TAG, "SurfaceTexture status: ${if (st == null) "NULL" else "READY"}")

            if (st == null) {
                Log.w(TAG, "SurfaceTexture not ready, deferring player initialization")
                isWaitingForSurface = true
                return
            }

            isWaitingForSurface = false

            try {
                val surface = Surface(st)

                exoPlayer = ExoPlayer.Builder(applicationContext)
                    .build().apply {
                        setVideoSurface(surface)
                        volume = 0f
                        setSeekParameters(SeekParameters.EXACT)

                        repeatMode = Player.REPEAT_MODE_ONE
                        playWhenReady = false

                        val mediaItem = MediaItem.fromUri(Uri.parse(videoUri))
                        setMediaItem(mediaItem)

                        addListener(object : Player.Listener {
                            override fun onPlaybackStateChanged(playbackState: Int) {
                                Log.d(TAG, "onPlaybackStateChanged: state=$playbackState")

                                if (playbackState == Player.STATE_READY) {
                                    val dur = duration
                                    if (dur != C.TIME_UNSET && dur > 0) {
                                        videoDuration = dur
                                        videoDurationUs = dur * 1000
                                        videoFrameCount = (dur * videoFrameRate / 1000).toInt()
                                        Log.d(TAG, "ExoPlayer READY: duration=${dur}ms, frames=$videoFrameCount")
                                    } else {
                                        videoDuration = 10_000L
                                        videoDurationUs = 10_000_000L
                                        videoFrameCount = 300
                                        Log.w(TAG, "Invalid duration, using defaults")
                                    }

                                    isPlayerPrepared = true
                                    videoFrameIndex = 0
                                    lastFrameIndex = -1
                                    videoTime = 0L
                                    currentPlaybackPosition = 0f
                                    lastUpdateTime = System.currentTimeMillis()

                                    val vw = videoSize.width
                                    val vh = videoSize.height
                                    if (vw > 0 && vh > 0) {
                                        eglThread?.setContentSize(vw, vh)
                                        Log.d(TAG, "Video size: ${vw}x${vh}")
                                    }

                                    seekTo(0)
                                    eglThread?.requestRender()

                                    startPlaybackLoop()

                                    Log.d(TAG, "Video initialization complete!")
                                }
                            }

                            override fun onPlayerError(error: PlaybackException) {
                                Log.e(TAG, "ExoPlayer error: ${error.message}")
                                isPlayerPrepared = false
                            }
                        })

                        prepare()
                    }

                Log.d(TAG, "ExoPlayer preparing...")
                eglThread?.resetVideoMatrix()

            } catch (e: Exception) {
                Log.e(TAG, "Failed to prepare video", e)
                releasePlayer()
            }
        }

        fun onSurfaceTextureAvailable() {
            if (isWaitingForSurface && group?.type == RasterGroupModel.TYPE_DYNAMIC) {
                Log.d(TAG, "SurfaceTexture available, retrying...")
                val g = group ?: return
                if (g.type == RasterGroupModel.TYPE_DYNAMIC) {
                    prepareVideo(g)
                }
            }
        }

        private fun releasePlayer() {
            stopPlaybackLoop()
            exoPlayer?.release()
            exoPlayer = null
            isPlayerPrepared = false
        }

        private var playbackRunnable: Runnable? = null
        private val PLAYBACK_INTERVAL_MS = 16L

        private fun startPlaybackLoop() {
            Log.d(TAG, "startPlaybackLoop")
            stopPlaybackLoop()
            lastUpdateTime = System.currentTimeMillis()

            playbackRunnable = object : Runnable {
                override fun run() {
                    if (!isPlayerPrepared) return

                    val now = System.currentTimeMillis()
                    val dt = (now - lastUpdateTime) / 1000f
                    lastUpdateTime = now

                    updatePlayback(dt)

                    val targetTimeMs = (currentPlaybackPosition * videoDuration).toLong()
                    val newFrameIndex = (targetTimeMs * videoFrameRate / 1000).toInt()
                        .coerceIn(0, videoFrameCount - 1)

                    if (newFrameIndex != lastFrameIndex) {
                        videoFrameIndex = newFrameIndex
                        videoTime = targetTimeMs
                        seekTo(targetTimeMs)
                        lastFrameIndex = newFrameIndex
                    }

                    eglHandler?.postDelayed(this, PLAYBACK_INTERVAL_MS)
                }
            }

            eglHandler?.post(playbackRunnable!!)
        }

        private fun stopPlaybackLoop() {
            Log.d(TAG, "stopPlaybackLoop")
            playbackRunnable?.let {
                eglHandler?.removeCallbacks(it)
            }
            playbackRunnable = null
        }

        private fun updatePlayback(dt: Float) {
            if (videoDuration <= 0) return

            val maxSpeedMultiplier = 2.0f
            val speed = playbackSpeed * maxSpeedMultiplier

            val positionChange = speed * dt
            currentPlaybackPosition += positionChange

            if (currentPlaybackPosition < 0f) {
                currentPlaybackPosition = -currentPlaybackPosition
                playbackSpeed = -playbackSpeed
            } else if (currentPlaybackPosition > 1f) {
                currentPlaybackPosition = 2f - currentPlaybackPosition
                playbackSpeed = -playbackSpeed
            }

            currentPlaybackPosition = currentPlaybackPosition.coerceIn(0f, 1f)

            frameCount++
            if (frameCount % 60 == 0L) {
                val isReverse = isReverseOrBreakOperation()
                Log.d(TAG, "Playback: pos=${String.format("%.3f", currentPlaybackPosition)}, " +
                        "speed=${String.format("%.3f", playbackSpeed)}, reverse=$isReverse")
            }
        }

        private fun seekTo(timeMs: Long) {
            try {
                exoPlayer?.let { player ->
                    player.seekTo(timeMs)

                    if (frameCount % 30 == 0L) {
                        val isReverse = isReverseOrBreakOperation()
                        Log.d(TAG, "Seek: time=${timeMs}ms, reverse=$isReverse")
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "seekTo failed: ${e.message}")
            }
        }

        override fun onFrameAvailable(surfaceTexture: SurfaceTexture) {
            updateSurface.set(true)
            eglThread?.requestRender()
        }

        private inner class EglThread(private val holder: SurfaceHolder) : HandlerThread("VideoRasterEGL") {
            private var display: EGLDisplay = EGL14.EGL_NO_DISPLAY
            private var context: EGLContext = EGL14.EGL_NO_CONTEXT
            private var eglSurface: EGLSurface = EGL14.EGL_NO_SURFACE
            private var handler: Handler? = null

            var videoST: SurfaceTexture? = null
                private set

            private var vTexId = 0
            private var vProg = 0
            private var vBuf: FloatBuffer
            private var tBuf: FloatBuffer

            private var sW = 0
            private var sH = 0
            private var cW = 1
            private var cH = 1

            private val videoSTMatrix = FloatArray(16)

            init {
                val vData = floatArrayOf(-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f)
                vBuf = ByteBuffer.allocateDirect(vData.size * 4)
                    .order(ByteOrder.nativeOrder())
                    .asFloatBuffer().put(vData)
                vBuf.position(0)

                val tData = floatArrayOf(0f, 0f, 1f, 0f, 0f, 1f, 1f, 1f)
                tBuf = ByteBuffer.allocateDirect(tData.size * 4)
                    .order(ByteOrder.nativeOrder())
                    .asFloatBuffer().put(tData)
                tBuf.position(0)
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

            fun post(r: () -> Unit) { handler?.post(r) }

            fun resetVideoMatrix() {
                post { Matrix.setIdentityM(videoSTMatrix, 0) }
            }

            override fun onLooperPrepared() {
                if (!initEGL()) return
                initGL()
                handler = Handler(looper)
                eglHandler = handler
                post { loadContent() }
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
                    display, configs[0], holder.surface,
                    intArrayOf(EGL14.EGL_NONE), 0
                )

                return EGL14.eglMakeCurrent(display, eglSurface, eglSurface, context)
            }

            private fun initGL() {
                val vs = """
                    attribute vec4 aPos;
                    attribute vec2 aTex;
                    varying vec2 vTex;
                    uniform mat4 uMVP;
                    uniform mat4 uST;
                    void main() {
                        gl_Position = uMVP * aPos;
                        vTex = (uST * vec4(aTex, 0, 1)).xy;
                    }
                """.trimIndent()

                val fsV = """
                    #extension GL_OES_EGL_image_external : require
                    precision mediump float;
                    varying vec2 vTex;
                    uniform samplerExternalOES sTex;
                    void main() {
                        gl_FragColor = texture2D(sTex, vTex);
                    }
                """.trimIndent()

                vProg = createProgram(vs, fsV)

                val tex = IntArray(1)
                GLES20.glGenTextures(1, tex, 0)
                vTexId = tex[0]

                GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, vTexId)
                GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
                GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
                GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
                GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)

                videoST = SurfaceTexture(vTexId)
                videoST?.setOnFrameAvailableListener(this@VideoRasterEngine)

                Matrix.setIdentityM(videoSTMatrix, 0)

                handler?.post {
                    this@VideoRasterEngine.onSurfaceTextureAvailable()
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

                val stMat = FloatArray(16)

                if (updateSurface.getAndSet(false)) {
                    try {
                        videoST?.updateTexImage()
                        videoST?.getTransformMatrix(videoSTMatrix)
                    } catch (e: Exception) {
                        Log.w(TAG, "updateTexImage failed", e)
                    }
                }
                System.arraycopy(videoSTMatrix, 0, stMat, 0, 16)

                GLES20.glUseProgram(vProg)

                val mvp = FloatArray(16)
                Matrix.setIdentityM(mvp, 0)

                val cAsp = cW.toFloat() / cH
                val sAsp = if (sH > 0) sW.toFloat() / sH else 1f

                if (cAsp > sAsp) {
                    Matrix.scaleM(mvp, 0, cAsp / sAsp, 1f, 1f)
                } else {
                    Matrix.scaleM(mvp, 0, 1f, sAsp / cAsp, 1f)
                }

                val aPos = GLES20.glGetAttribLocation(vProg, "aPos")
                val aTex = GLES20.glGetAttribLocation(vProg, "aTex")

                GLES20.glEnableVertexAttribArray(aPos)
                GLES20.glVertexAttribPointer(aPos, 2, GLES20.GL_FLOAT, false, 8, vBuf)
                GLES20.glEnableVertexAttribArray(aTex)
                GLES20.glVertexAttribPointer(aTex, 2, GLES20.GL_FLOAT, false, 8, tBuf)

                GLES20.glUniformMatrix4fv(GLES20.glGetUniformLocation(vProg, "uMVP"), 1, false, mvp, 0)
                GLES20.glUniformMatrix4fv(GLES20.glGetUniformLocation(vProg, "uST"), 1, false, stMat, 0)

                GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
                GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, vTexId)
                GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)

                if (!EGL14.eglSwapBuffers(display, eglSurface)) {
                    Log.e(TAG, "SwapBuffers failed")
                }
            }

            private fun createProgram(vShader: String, fShader: String): Int {
                val vs = GLES20.glCreateShader(GLES20.GL_VERTEX_SHADER)
                GLES20.glShaderSource(vs, vShader)
                GLES20.glCompileShader(vs)

                val fs = GLES20.glCreateShader(GLES20.GL_FRAGMENT_SHADER)
                GLES20.glShaderSource(fs, fShader)
                GLES20.glCompileShader(fs)

                val program = GLES20.glCreateProgram()
                GLES20.glAttachShader(program, vs)
                GLES20.glAttachShader(program, fs)
                GLES20.glLinkProgram(program)

                return program
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
        const val ACTION_RELOAD = "com.zeaze.tianyinwallpaper.VIDEO_RASTER_RELOAD"
        const val PREF_RASTER_GROUPS = "rasterGroups"
        const val PREF_RASTER_ACTIVE_GROUP_ID = "rasterActiveGroupId"
        private const val TAG = "VideoRasterGL"
    }
}
