package com.zeaze.tianyinwallpaper.service

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.PixelFormat
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLSurface
import android.opengl.GLES20
import android.os.Handler
import android.os.HandlerThread
import android.service.wallpaper.WallpaperService
import android.util.Log
import android.view.Surface
import android.view.SurfaceHolder
import android.view.WindowManager
import com.zeaze.tianyinwallpaper.App
import com.zeaze.tianyinwallpaper.model.RasterGroupModel
import com.zeaze.tianyinwallpaper.service.raster.KeyframeTranscoder
import com.zeaze.tianyinwallpaper.service.raster.RVEffectPreCtrl
import com.zeaze.tianyinwallpaper.service.raster.RasterVideoPreRenderParamBean
import com.zeaze.tianyinwallpaper.utils.RasterPrefs
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.math.abs

/**
 * 视频光栅壁纸服务
 *
 * 核心逻辑：
 *   陀螺仪角度 → 绝对值映射为帧索引 → seekToFrame → OpenGL 渲染到壁纸 Surface
 *   水平(0°) = 第0帧，向任一方向倾斜45° = 最后一帧
 *
 * 线程模型：
 *   主线程      - 生命周期回调（onCreate/onVisibilityChanged 等）
 *   传感器线程  - AngleSensor 回调，结果 post 到 EGL 线程
 *   EGL 线程    - 所有 effectCtrl / 播放状态 / 渲染操作
 */
class VideoRasterWallpaperService : WallpaperService() {

    private var activeEngine: VideoRasterEngine? = null

    override fun onCreateEngine(): Engine = VideoRasterEngine()

    // -------------------------------------------------------------------------
    // AngleSensor
    // -------------------------------------------------------------------------

    inner class AngleSensor : SensorEventListener {

        private var sensorManager: SensorManager? = null
        private var gyroSensor: Sensor? = null

        var mCurAngleSensorValue: Float = 0f
            private set

        // 实时获取屏幕旋转角度的 provider
        var displayRotationProvider: () -> Int = { Surface.ROTATION_0 }

        private var lastTimestamp = 0L
        private var accumulatedAngle = 0f
        private var filteredVelocity = 0f

        private val FILTER_ALPHA  = 0.4f
        private val MAX_ANGLE_RAD = Math.toRadians(45.0).toFloat()

        var onAngleChanged: ((Float) -> Unit)? = null

        fun registerSensor(context: Context) {
            sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
            gyroSensor    = sensorManager?.getDefaultSensor(Sensor.TYPE_GYROSCOPE)
            
            // 设置实时获取屏幕旋转角度的 provider
            val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
            displayRotationProvider = {
                @Suppress("DEPRECATION")
                windowManager.defaultDisplay.rotation
            }
            
            Log.d(TAG, "AngleSensor.register: hasGyro=${gyroSensor != null}")
            gyroSensor?.let { sensorManager?.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME) }
            reset()
        }

        fun unregisterSensor() {
            Log.d(TAG, "AngleSensor.unregister")
            sensorManager?.unregisterListener(this)
            reset()
        }

        fun reset() {
            lastTimestamp    = 0L
            accumulatedAngle = 0f
            filteredVelocity = 0f
            mCurAngleSensorValue = 0f
        }

        override fun onSensorChanged(event: SensorEvent?) {
            val e = event ?: return
            if (e.sensor.type != Sensor.TYPE_GYROSCOPE) return

            if (lastTimestamp == 0L) { lastTimestamp = e.timestamp; return }

            val dt = (e.timestamp - lastTimestamp) / 1_000_000_000f
            lastTimestamp = e.timestamp
            if (dt <= 0f || dt > 0.5f) return

            // 获取角速度，根据屏幕旋转选择正确的轴
            // ROTATION_0 (竖屏): Y轴
            // ROTATION_90 (左横屏): X轴
            // ROTATION_180 (倒竖屏): -Y轴
            // ROTATION_270 (右横屏): -X轴
            val rotation = displayRotationProvider()
            val angularVelocity = when (rotation) {
                Surface.ROTATION_90 -> e.values[0]    // 左横屏
                Surface.ROTATION_180 -> -e.values[1]  // 倒竖屏
                Surface.ROTATION_270 -> -e.values[0]  // 右横屏
                else -> e.values[1]                   // 竖屏
            }
            
            filteredVelocity  = FILTER_ALPHA * angularVelocity + (1f - FILTER_ALPHA) * filteredVelocity
            accumulatedAngle += filteredVelocity * dt
            accumulatedAngle  = accumulatedAngle.coerceIn(-MAX_ANGLE_RAD, MAX_ANGLE_RAD)

            mCurAngleSensorValue = accumulatedAngle
            onAngleChanged?.invoke(mCurAngleSensorValue)
        }

        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
    }

    // -------------------------------------------------------------------------
    // VideoRasterEngine
    // -------------------------------------------------------------------------

    inner class VideoRasterEngine : Engine() {

        init { activeEngine = this }

        private var pendingFrame = 0
        private var group: RasterGroupModel? = null
        private var isVisible = false
        private val angleSensor = AngleSensor()

        // ── 播放状态（全部在 EGL 线程读写）──
        // ★ 绝对值映射：currentPlaybackPosition [0, 1] 对应帧 [0, totalFrames-1]
        //   0.0 = 第0帧（水平），1.0 = 最后一帧（任一方向倾斜45°）
        private var currentPlaybackPosition = 0f
        private var lastFrameIndex = -1
        private var frameStableCount = 0   // 帧稳定计数，用于在用户停止倾斜后暂停播放器

        private var eglThread: EglThread? = null
        private var eglHandler: Handler? = null
        private var isWaitingForSurface = false
        private var pref: SharedPreferences? = null
        private var effectCtrl: RVEffectPreCtrl? = null
        private var playbackRunnable: Runnable? = null
        private val PLAYBACK_INTERVAL_MS = 32L   // ~30fps，匹配视频帧率，减少 seek 压力

        // ── 对外接口 ──────────────────────────────────────────────────────────

        fun reload() {
            loadActiveGroup()
            eglThread?.post { loadContent() }
        }

        // ── 生命周期 ──────────────────────────────────────────────────────────

        override fun onCreate(surfaceHolder: SurfaceHolder) {
            super.onCreate(surfaceHolder)
            Log.w(TAG, "onCreate")
            surfaceHolder.setFormat(PixelFormat.RGBX_8888)
            pref = getSharedPreferences(App.TIANYIN, MODE_PRIVATE)

            angleSensor.onAngleChanged = { angle ->
                eglHandler?.post { doSensorChangeEvent(angle) }
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
            Log.d(TAG, "onVisibilityChanged: $visible")
            if (visible) {
                angleSensor.registerSensor(applicationContext)
                checkGroupChange()
                eglHandler?.post {
                    if (effectCtrl?.isPrepared() == true) {
                        startPlaybackLoop()
                        eglThread?.requestRender()
                    }
                }
            } else {
                angleSensor.unregisterSensor()
                eglHandler?.post {
                    stopPlaybackLoop()
                    effectCtrl?.ensurePaused()
                }
            }
        }

        override fun onDestroy() {
            super.onDestroy()
            if (activeEngine == this) activeEngine = null
            angleSensor.unregisterSensor()

            val latch = CountDownLatch(1)
            eglThread?.post {
                try {
                    stopPlaybackLoop()
                    effectCtrl?.release()
                }
                catch (e: Exception) { Log.e(TAG, "release error: ${e.message}") }
                finally { latch.countDown() }
            }
            latch.await(2, TimeUnit.SECONDS)
            eglThread?.finish()
        }

        // ── 内容管理 ─────────────────────────────────────────────────────────

        private fun loadActiveGroup() {
            group = pref?.let { RasterPrefs.loadActiveGroup(it) }
            Log.w(TAG, "loadActiveGroup: $group")
        }

        private fun checkGroupChange() {
            val newId = pref?.getString(RasterPrefs.PREF_RASTER_ACTIVE_GROUP_ID, null)
            if (newId != group?.id) {
                loadActiveGroup()
                eglThread?.post { loadContent() }
            }
        }

        private var isTranscoding = false

        /**
         * 加载视频内容（EGL 线程）
         *
         * 流程：
         *   1. 检查是否已有全关键帧转码缓存
         *   2. 有 → 直接加载转码后的视频（seek 零延迟）
         *   3. 无 → 后台线程转码，完成后回 EGL 线程加载
         */
        private fun loadContent() {
            Log.w(TAG, "loadContent: group=$group")
            val g = group ?: return
            if (g.type != RasterGroupModel.TYPE_DYNAMIC) {
                Log.w(TAG, "loadContent: not dynamic, skip")
                return
            }
            val videoUri = g.videoUri
            if (videoUri.isNullOrEmpty()) {
                Log.e(TAG, "loadContent: videoUri empty")
                return
            }

            initEffectCtrl()

            val texId = effectCtrl?.getTextureId() ?: 0
            if (texId == 0) {
                Log.w(TAG, "loadContent: texture not ready, waiting...")
                isWaitingForSurface = true
                return
            }

            // ★ 全关键帧转码：检查缓存 / 启动转码
            val transcoder = KeyframeTranscoder(applicationContext)
            val cachedPath = transcoder.getCachedPath(videoUri)

            if (cachedPath != null) {
                Log.w(TAG, "loadContent: using cached keyframe video: $cachedPath")
                doLoadVideo(cachedPath)
            } else if (!isTranscoding) {
                isTranscoding = true
                Log.w(TAG, "loadContent: starting keyframe transcode...")
                transcoder.transcodeAsync(videoUri, object : KeyframeTranscoder.Listener {
                    override fun onProgress(progress: Float) {
                        if ((progress * 100).toInt() % 10 == 0) {
                            Log.d(TAG, "transcode progress: ${(progress * 100).toInt()}%")
                        }
                    }

                    override fun onComplete(outputPath: String) {
                        Log.w(TAG, "transcode complete: $outputPath")
                        isTranscoding = false
                        eglHandler?.post { doLoadVideo(outputPath) }
                    }

                    override fun onError(message: String) {
                        Log.e(TAG, "transcode failed: $message, falling back to original")
                        isTranscoding = false
                        eglHandler?.post { doLoadVideo(videoUri) }
                    }
                })
            }
        }

        /** 实际加载视频到播放器（EGL 线程） */
        private fun doLoadVideo(videoPath: String) {
            Log.w(TAG, "doLoadVideo: $videoPath")
            effectCtrl?.loadSourceFromParams(
                RasterVideoPreRenderParamBean(videoPath = videoPath, videoFrameRate = 30)
            )
        }

        fun onSurfaceTextureAvailable() {
            if (!isWaitingForSurface) return
            isWaitingForSurface = false

            val videoUri = group?.videoUri
            if (videoUri.isNullOrEmpty()) return

            val texId = effectCtrl?.getTextureId() ?: 0
            if (texId == 0) {
                Log.e(TAG, "onSurfaceTextureAvailable: still no texture, abort")
                return
            }

            // 复用 loadContent 的转码逻辑
            Log.w(TAG, "onSurfaceTextureAvailable: retrying load")
            val transcoder = KeyframeTranscoder(applicationContext)
            val cachedPath = transcoder.getCachedPath(videoUri)
            doLoadVideo(cachedPath ?: videoUri)
        }

        private fun initEffectCtrl() {
            stopPlaybackLoop()
            effectCtrl?.release()

            effectCtrl = RVEffectPreCtrl(applicationContext, object : RVEffectPreCtrl.Callback {
                override fun onPrepared(frameCount: Int, duration: Long) {
                    Log.w(TAG, "onPrepared: frames=$frameCount, duration=$duration")
                    eglHandler?.post {
                        lastFrameIndex = -1
                        currentPlaybackPosition = 0f   // 初始位于第0帧
                        effectCtrl?.seekToFrame(0)
                        if (isVisible) {
                            startPlaybackLoop()
                        } else {
                            effectCtrl?.ensurePaused()
                        }
                    }
                }

                override fun onFrameReady() {
                    eglThread?.requestRender()
                }

                override fun onSeekComplete() {
                    // ★ 不再用 isSeeking 门控，仅触发渲染
                    eglHandler?.post { eglThread?.requestRender() }
                }

                override fun onError(message: String) {
                    Log.e(TAG, "onError: $message")
                }
            })

            effectCtrl?.init()
            Log.w(TAG, "initEffectCtrl: done, textureId=${effectCtrl?.getTextureId()}")
        }

        // ── 传感器 → 帧控制（EGL 线程）────────────────────────────────────────

        /**
         * ★ 绝对值映射：陀螺仪倾斜角度的绝对值映射为帧位置
         *
         * angle =   0° → position = 0.0 → 第0帧（水平）
         * angle = ±45° → position = 1.0 → 最后一帧
         *
         * 左倾和右倾效果相同，连续无跳变
         */
        private fun doSensorChangeEvent(sensorValue: Float) {
            if (effectCtrl?.isPrepared() != true) return
            val sw = group?.sensorWidth ?: 1.5f
            val maxAngle = (0.3285 + 0.041 * sw).toFloat()

            val normalized = (sensorValue / maxAngle).coerceIn(-1f, 1f)
            currentPlaybackPosition = abs(normalized)   // [0, 1]
        }

        // ── 播放循环（EGL 线程）───────────────────────────────────────────────


        /**
         * ★ 根据当前位置计算目标帧并同步解码
         *
         * seekToFrame 现在是同步的（MediaCodec 直接解码），
         * 前向 1~5 帧耗时 ~1~5ms，大跳转在 28ms 预算内解码尽可能多的帧。
         */
        private fun startPlaybackLoop() {
            stopPlaybackLoop()
            if (!isVisible) return
            Log.w(TAG, "startPlaybackLoop")

            playbackRunnable = object : Runnable {
                override fun run() {
                    if (effectCtrl?.isPrepared() == true) {
                        updateFrame()
                        eglThread?.requestRender()
                    }
                    eglHandler?.postDelayed(this, PLAYBACK_INTERVAL_MS)
                }
            }
            eglHandler?.post(playbackRunnable!!)
        }

        private fun stopPlaybackLoop() {
            playbackRunnable?.let { eglHandler?.removeCallbacks(it) }
            playbackRunnable = null
        }

        private fun updateFrame() {
            val totalFrames = effectCtrl?.getFrameCount() ?: return

            pendingFrame = (currentPlaybackPosition * (totalFrames - 1)).toInt()
                .coerceIn(0, totalFrames - 1)

            if (pendingFrame == lastFrameIndex) {
                if (frameStableCount++ > 3) {
                    effectCtrl?.ensurePaused()
                }
                return
            }

            frameStableCount = 0
            effectCtrl?.seekToFrame(pendingFrame)
            lastFrameIndex = pendingFrame
        }

        // ── EglThread ─────────────────────────────────────────────────────────

        private inner class EglThread(private val holder: SurfaceHolder) : HandlerThread("VideoRasterEGL") {

            private var display: EGLDisplay = EGL14.EGL_NO_DISPLAY
            private var context: EGLContext = EGL14.EGL_NO_CONTEXT
            private var eglSurface: EGLSurface = EGL14.EGL_NO_SURFACE
            private var handler: Handler? = null

            private var sW = 0
            private var sH = 0
            private var isEglReady = false

            fun onSizeChanged(w: Int, h: Int) {
                sW = w; sH = h
                post {
                    effectCtrl?.setDesignSize(w, h)
                    requestRender()
                }
            }

            fun post(r: () -> Unit) { handler?.post(r) }

            override fun onLooperPrepared() {
                Log.w(TAG, "EglThread.onLooperPrepared")
                if (!initEGL()) {
                    Log.e(TAG, "initEGL failed!")
                    return
                }
                handler    = Handler(looper)
                eglHandler = handler
                isEglReady = true

                post {
                    loadContent()
                    onSurfaceTextureAvailable()
                }
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
                val configs    = arrayOfNulls<EGLConfig>(1)
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

            fun requestRender() {
                handler?.removeCallbacks(drawRunnable)
                handler?.post(drawRunnable)
            }

            private val drawRunnable = Runnable { draw() }

            private fun draw() {
                if (!isEglReady || eglSurface == EGL14.EGL_NO_SURFACE || sW <= 0 || sH <= 0) return

                EGL14.eglMakeCurrent(display, eglSurface, eglSurface, context)

                if (effectCtrl?.isPrepared() != true) {
                    GLES20.glViewport(0, 0, sW, sH)
                    GLES20.glClearColor(0f, 0f, 0f, 1f)
                    GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
                    EGL14.eglSwapBuffers(display, eglSurface)
                    return
                }

                effectCtrl?.onDrawFrame(sW, sH)
                if (!EGL14.eglSwapBuffers(display, eglSurface)) {
                    Log.e(TAG, "eglSwapBuffers failed: ${EGL14.eglGetError()}")
                }
            }

            fun finish() {
                val latch = CountDownLatch(1)
                handler?.post {
                    try {
                        if (display != EGL14.EGL_NO_DISPLAY) {
                            EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
                            EGL14.eglDestroySurface(display, eglSurface)
                            EGL14.eglDestroyContext(display, context)
                            EGL14.eglTerminate(display)
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "EGL destroy error: ${e.message}")
                    } finally {
                        isEglReady = false
                        latch.countDown()
                    }
                }
                latch.await(1, TimeUnit.SECONDS)
                quitSafely()
            }
        }
    }

    // -------------------------------------------------------------------------
    // Service
    // -------------------------------------------------------------------------

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_RELOAD -> activeEngine?.reload()
        }
        return super.onStartCommand(intent, flags, startId)
    }

    companion object {
        const val ACTION_RELOAD = "com.zeaze.tianyinwallpaper.VIDEO_RASTER_RELOAD"
        private const val TAG   = "VideoRasterGL"
    }
}
