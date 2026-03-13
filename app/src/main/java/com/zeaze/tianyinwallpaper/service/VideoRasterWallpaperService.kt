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
import android.view.SurfaceHolder
import com.alibaba.fastjson.JSON
import com.zeaze.tianyinwallpaper.App
import com.zeaze.tianyinwallpaper.model.RasterGroupModel
import com.zeaze.tianyinwallpaper.service.raster.RVEffectPreCtrl
import com.zeaze.tianyinwallpaper.service.raster.RasterVideoPreRenderParamBean
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * 视频光栅壁纸服务
 * 参考 vivo GLRasterWallpaperPrePlugin 架构重构
 */
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

    inner class VideoRasterEngine : Engine() {

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

        private var eglHandler: Handler? = null
        private var isWaitingForSurface = false

        private var eglThread: EglThread? = null
        private val updateSurface = AtomicBoolean(false)

        private var pref: SharedPreferences? = null

        private var frameCount = 0L

        // 新架构：使用 RVEffectPreCtrl
        private var effectCtrl: RVEffectPreCtrl? = null

        fun reload() {
            loadActiveGroup()
            eglThread?.post { loadContent() }
        }

        private fun isReverseOrBreakOperation(): Boolean {
            return playbackSpeed < 0
        }

        override fun onCreate(surfaceHolder: SurfaceHolder) {
            super.onCreate(surfaceHolder)
            Log.w(TAG, "onCreate: VideoRasterEngine created")
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
            Log.d(TAG, "onVisibilityChanged: visible=$visible")

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
            
            // 在 EGL 线程上同步释放资源
            val latch = java.util.concurrent.CountDownLatch(1)
            eglThread?.post {
                try {
                    effectCtrl?.release()
                } catch (e: Exception) {
                    Log.e(TAG, "release error: ${e.message}")
                } finally {
                    latch.countDown()
                }
            }
            latch.await(2, java.util.concurrent.TimeUnit.SECONDS)
            
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
            if (effectCtrl?.isPrepared() != true) return

            val maxAngle = Math.toRadians(45.0).toFloat()
            val normalizedAngle = sensorValue / maxAngle

            playbackSpeed = normalizedAngle.coerceIn(-1f, 1f)

            val currentReverse = playbackSpeed < 0
            reverseBean.mLastReverse = currentReverse

            Log.d(TAG, "Sensor: angle=${String.format("%.3f", sensorValue)}, speed=${String.format("%.3f", playbackSpeed)}")
        }

        private fun loadActiveGroup() {
            val activeId = pref?.getString(PREF_RASTER_ACTIVE_GROUP_ID, null)
            Log.w(TAG, "loadActiveGroup: activeId=$activeId")
            if (activeId == null) {
                Log.w(TAG, "loadActiveGroup: no active group id")
                return
            }
            val groupsJson = pref?.getString(PREF_RASTER_GROUPS, "[]") ?: "[]"
            val groups = try {
                JSON.parseArray(groupsJson, RasterGroupModel::class.java) ?: emptyList()
            } catch (e: Exception) {
                Log.e(TAG, "loadActiveGroup: parse error: ${e.message}")
                emptyList()
            }
            group = groups.firstOrNull { it.id == activeId } ?: groups.firstOrNull()
            Log.w(TAG, "loadActiveGroup: loaded group=$group")
        }

        private fun loadContent() {
            Log.w(TAG, "loadContent: group=$group, type=${group?.type}")
            val g = group ?: return
            if (g.type != RasterGroupModel.TYPE_DYNAMIC) {
                Log.w(TAG, "loadContent: not dynamic type, skipping")
                return
            }

            // 初始化效果控制器
            initEffectCtrl()

            // 加载视频
            val videoUri = g.videoUri
            if (videoUri.isNullOrEmpty()) {
                Log.e(TAG, "Video URI is empty")
                return
            }

            // 创建渲染参数
            val params = RasterVideoPreRenderParamBean(
                videoPath = videoUri,
                videoFrameRate = 30
            )

            Log.w(TAG, "loadContent: calling loadSourceFromParams, videoUri=$videoUri")
            effectCtrl?.loadSourceFromParams(params)

            Log.w(TAG, "loadContent: completed")
        }

        private fun initEffectCtrl() {
            Log.w(TAG, "initEffectCtrl: starting...")
            effectCtrl?.release()
            
            effectCtrl = RVEffectPreCtrl(applicationContext, object : RVEffectPreCtrl.Callback {
                override fun onPrepared(frameCount: Int, duration: Long) {
                    Log.w(TAG, "EffectCtrl onPrepared: frames=$frameCount, duration=$duration")
                    lastUpdateTime = System.currentTimeMillis()
                    currentPlaybackPosition = 0f
                    startPlaybackLoop()
                }

                override fun onFrameReady() {
                    updateSurface.set(true)
                    eglThread?.requestRender()
                }

                override fun onError(message: String) {
                    Log.e(TAG, "EffectCtrl onError: $message")
                }
            })
            
            Log.w(TAG, "initEffectCtrl: calling init()")
            effectCtrl?.init()
            Log.w(TAG, "initEffectCtrl: init() completed, textureId=${effectCtrl?.getTextureId()}")
        }

        fun onSurfaceTextureAvailable() {
            if (isWaitingForSurface && group?.type == RasterGroupModel.TYPE_DYNAMIC) {
                Log.d(TAG, "SurfaceTexture available, retrying...")
                loadContent()
            }
        }

        private var playbackRunnable: Runnable? = null
        private val PLAYBACK_INTERVAL_MS = 16L
        private var lastSeekPosition = -1f  // 记录上次seek的位置

        private fun startPlaybackLoop() {
            Log.w(TAG, "startPlaybackLoop")
            stopPlaybackLoop()
            lastUpdateTime = System.currentTimeMillis()
            lastSeekPosition = -1f  // 重置

            playbackRunnable = object : Runnable {
                override fun run() {
                    val prepared = effectCtrl?.isPrepared() ?: false
                    if (!prepared) {
                        Log.w(TAG, "playbackLoop: not prepared yet, retrying...")
                        eglHandler?.postDelayed(this, PLAYBACK_INTERVAL_MS)
                        return
                    }

                    val now = System.currentTimeMillis()
                    val dt = (now - lastUpdateTime) / 1000f
                    lastUpdateTime = now

                    updatePlayback(dt)

                    // 只有当位置有明显变化时才seek（避免频繁seek导致状态循环）
                    if (kotlin.math.abs(currentPlaybackPosition - lastSeekPosition) > 0.001f) {
                        effectCtrl?.seekToPosition(currentPlaybackPosition)
                        lastSeekPosition = currentPlaybackPosition
                    }
                    
                    // 请求渲染
                    eglThread?.requestRender()

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
            val videoDuration = effectCtrl?.getVideoDuration() ?: 0L
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

        private inner class EglThread(private val holder: SurfaceHolder) : HandlerThread("VideoRasterEGL") {
            private var display: EGLDisplay = EGL14.EGL_NO_DISPLAY
            private var context: EGLContext = EGL14.EGL_NO_CONTEXT
            private var eglSurface: EGLSurface = EGL14.EGL_NO_SURFACE
            private var handler: Handler? = null

            private var sW = 0
            private var sH = 0
            private var isEglReady = false

            fun onSizeChanged(w: Int, h: Int) {
                sW = w
                sH = h
                effectCtrl?.setDesignSize(w, h)
                requestRender()
            }

            fun post(r: () -> Unit) { handler?.post(r) }

            override fun onLooperPrepared() {
                Log.w(TAG, "EglThread.onLooperPrepared: starting...")
                if (!initEGL()) {
                    Log.e(TAG, "EglThread.onLooperPrepared: initEGL failed!")
                    return
                }
                handler = Handler(looper)
                eglHandler = handler
                isEglReady = true
                Log.w(TAG, "EglThread.onLooperPrepared: EGL initialized, posting loadContent...")
                
                handler?.post {
                    this@VideoRasterEngine.onSurfaceTextureAvailable()
                }
                
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

            fun requestRender() {
                handler?.removeCallbacks(drawRunnable)
                handler?.post(drawRunnable)
            }

            private val drawRunnable = Runnable { draw() }

            private fun draw() {
                if (!isEglReady || eglSurface == EGL14.EGL_NO_SURFACE) {
                    Log.w(TAG, "draw: skipped, isEglReady=$isEglReady, surface valid=${eglSurface != EGL14.EGL_NO_SURFACE}")
                    return
                }
                
                if (sW <= 0 || sH <= 0) {
                    Log.w(TAG, "draw: skipped, invalid size: ${sW}x$sH")
                    return
                }
                
                val prepared = effectCtrl?.isPrepared() ?: false
                if (!prepared) {
                    // 还没准备好，绘制黑色背景
                    EGL14.eglMakeCurrent(display, eglSurface, eglSurface, context)
                    GLES20.glViewport(0, 0, sW, sH)
                    GLES20.glClearColor(0f, 0f, 0f, 1f)
                    GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
                    EGL14.eglSwapBuffers(display, eglSurface)
                    return
                }
                
                EGL14.eglMakeCurrent(display, eglSurface, eglSurface, context)

                // 使用 RVEffectPreCtrl 进行渲染
                effectCtrl?.onDrawFrame(sW, sH)

                if (!EGL14.eglSwapBuffers(display, eglSurface)) {
                    Log.e(TAG, "SwapBuffers failed")
                }
            }

            fun finish() {
                // 在 EGL 线程上销毁 EGL 资源
                val destroyLatch = java.util.concurrent.CountDownLatch(1)
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
                        destroyLatch.countDown()
                    }
                }
                destroyLatch.await(1, java.util.concurrent.TimeUnit.SECONDS)
                quitSafely()
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
