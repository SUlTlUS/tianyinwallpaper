package com.zeaze.tianyinwallpaper.ui.raster

import android.content.Context
import android.graphics.Bitmap
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
import android.opengl.GLES20
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.view.Surface
import android.view.TextureView
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.CircularProgressIndicator
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.zeaze.tianyinwallpaper.model.RasterGroupModel
import com.zeaze.tianyinwallpaper.service.raster.KeyframeTranscoder
import com.zeaze.tianyinwallpaper.service.raster.RVEffectPreCtrl
import com.zeaze.tianyinwallpaper.service.raster.RasterVideoPreRenderParamBean
import com.zeaze.tianyinwallpaper.utils.ThumbnailUtils
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.math.abs

/**
 * 视频光栅预览组件 - 复用壁纸服务的 EGL + RVEffectPreCtrl 渲染管线
 *
 * 核心功能：
 *   - 使用 TextureView + 独立 EGL 上下文进行渲染
 *   - 复用 RVEffectPreCtrl（同壁纸服务）实现陀螺仪帧精准 seek
 *   - 转码期间显示全屏模糊缩略图 + "正在加载" 提示
 *   - 转码完成后无缝切换到实时渲染
 */
@Composable
fun VideoRasterPreviewView(
    group: RasterGroupModel,
    modifier: Modifier = Modifier,
    onLoadingChanged: ((Boolean) -> Unit)? = null
) {
    val context = LocalContext.current
    val videoUri = group.videoUri

    // ── 状态 ──
    var isLoading by remember { mutableStateOf(true) }
    var transcodingProgress by remember { mutableFloatStateOf(-1f) }  // -1 = 未开始, 0~1 = 转码中
    var thumbnail by remember { mutableStateOf<Bitmap?>(null) }

    // ── EGL 渲染器 ──
    val renderer = remember(videoUri) { VideoRasterPreviewRenderer(context, videoUri.orEmpty()) }

    // 提取模糊缩略图 + 启动监听
    DisposableEffect(videoUri) {
        // 异步提取缩略图
        Thread({
            if (!videoUri.isNullOrEmpty()) {
                val bmp = ThumbnailUtils.getVideoFrame(context, Uri.parse(videoUri))
                thumbnail = bmp
            }
        }, "ThumbExtract").start()

        renderer.onStateChanged = { loading, progress ->
            isLoading = loading
            transcodingProgress = progress
            onLoadingChanged?.invoke(loading)
        }

        onDispose {
            renderer.onStateChanged = null
            renderer.release()
        }
    }

    // ── 陀螺仪传感器 ──
    DisposableEffect(context) {
        val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
        val gyroSensor = sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE)

        val listener = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent?) {
                renderer.onSensorChanged(event)
            }
            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
        }

        gyroSensor?.let {
            sensorManager.registerListener(listener, it, SensorManager.SENSOR_DELAY_GAME)
        }

        onDispose {
            sensorManager.unregisterListener(listener)
        }
    }

    Box(modifier = modifier.fillMaxSize()) {
        // TextureView 用于 EGL 渲染
        AndroidView(
            factory = { ctx ->
                TextureView(ctx).apply {
                    isOpaque = true
                    surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                        override fun onSurfaceTextureAvailable(st: SurfaceTexture, w: Int, h: Int) {
                            renderer.onSurfaceAvailable(Surface(st), w, h)
                        }
                        override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, w: Int, h: Int) {
                            renderer.onSurfaceSizeChanged(w, h)
                        }
                        override fun onSurfaceTextureDestroyed(st: SurfaceTexture): Boolean {
                            renderer.release()
                            return true
                        }
                        override fun onSurfaceTextureUpdated(st: SurfaceTexture) {}
                    }
                }
            },
            modifier = Modifier.fillMaxSize()
        )

        // ── 加载遮罩：全屏模糊缩略图 + "正在加载" ──
        AnimatedVisibility(
            visible = isLoading,
            enter = fadeIn(),
            exit = fadeOut()
        ) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                // 模糊缩略图背景
                if (thumbnail != null) {
                    Image(
                        bitmap = thumbnail!!.asImageBitmap(),
                        contentDescription = null,
                        modifier = Modifier
                            .fillMaxSize()
                            .blur(32.dp),
                        contentScale = ContentScale.Crop
                    )
                } else {
                    Box(Modifier.fillMaxSize().background(Color.Black))
                }

                // 半透明遮罩
                Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.3f)))

                // 中间提示
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier
                        .clip(RoundedCornerShape(16.dp))
                        .background(Color.Black.copy(alpha = 0.5f))
                        .padding(horizontal = 32.dp, vertical = 24.dp)
                ) {
                    CircularProgressIndicator(
                        color = Color.White,
                        strokeWidth = 2.dp,
                        modifier = Modifier.size(32.dp)
                    )
                    val progressText = if (transcodingProgress in 0f..1f) {
                        "正在加载 ${(transcodingProgress * 100).toInt()}%"
                    } else {
                        "正在加载"
                    }
                    Text(
                        text = progressText,
                        style = TextStyle(
                            color = Color.White,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Medium
                        ),
                        modifier = Modifier.padding(top = 12.dp)
                    )
                }
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// 渲染器：复用壁纸服务的 EGL + RVEffectPreCtrl 管线
// ─────────────────────────────────────────────────────────────────────────────

private class VideoRasterPreviewRenderer(
    private val context: Context,
    private val videoUri: String
) {
    companion object {
        private const val TAG = "VRPreviewRenderer"
        private const val PLAYBACK_INTERVAL_MS = 32L
    }

    // 外部回调：(isLoading, transcodingProgress)
    var onStateChanged: ((Boolean, Float) -> Unit)? = null

    // ── EGL ──
    private var eglThread: HandlerThread? = null
    private var eglHandler: Handler? = null
    private var display: EGLDisplay = EGL14.EGL_NO_DISPLAY
    private var eglContext: EGLContext = EGL14.EGL_NO_CONTEXT
    private var eglSurface: EGLSurface = EGL14.EGL_NO_SURFACE
    private var isEglReady = false

    // ── 渲染 ──
    private var effectCtrl: RVEffectPreCtrl? = null
    private var sW = 0
    private var sH = 0
    private var isTranscoding = false
    private var released = false

    // ── 陀螺仪 ──
    private var lastTimestamp = 0L
    private var accumulatedAngle = 0f
    private var filteredVelocity = 0f
    private val FILTER_ALPHA = 0.4f
    private val MAX_ANGLE_RAD = Math.toRadians(45.0).toFloat()
    private val DEAD_ZONE_EXIT_RAD = Math.toRadians(1.5).toFloat()
    private val DEAD_ZONE_ENTER_RAD = Math.toRadians(0.5).toFloat()
    private var inDeadZone = true

    // ── 帧控制 ──
    private var currentPlaybackPosition = 0f
    private var lastFrameIndex = -1
    private var frameStableCount = 0
    private var playbackRunnable: Runnable? = null

    // ─────────────────────── Surface 回调 ───────────────────────

    fun onSurfaceAvailable(surface: Surface, w: Int, h: Int) {
        if (released) return
        sW = w; sH = h

        eglThread?.quitSafely()
        eglThread = HandlerThread("VRPreviewEGL").apply { start() }
        eglHandler = Handler(eglThread!!.looper)

        eglHandler?.post {
            if (released) return@post
            initEGL(surface)
            if (isEglReady) {
                loadContent()
            }
        }
    }

    fun onSurfaceSizeChanged(w: Int, h: Int) {
        sW = w; sH = h
        eglHandler?.post {
            effectCtrl?.setDesignSize(w, h)
            requestRender()
        }
    }

    // ─────────────────────── 陀螺仪 ───────────────────────────

    fun onSensorChanged(event: SensorEvent?) {
        val e = event ?: return
        if (e.sensor.type != Sensor.TYPE_GYROSCOPE) return

        if (lastTimestamp == 0L) { lastTimestamp = e.timestamp; return }

        val dt = (e.timestamp - lastTimestamp) / 1_000_000_000f
        lastTimestamp = e.timestamp
        if (dt <= 0f || dt > 0.5f) return

        filteredVelocity = FILTER_ALPHA * e.values[1] + (1f - FILTER_ALPHA) * filteredVelocity
        accumulatedAngle += filteredVelocity * dt
        accumulatedAngle = accumulatedAngle.coerceIn(-MAX_ANGLE_RAD, MAX_ANGLE_RAD)

        inDeadZone = if (inDeadZone) {
            abs(accumulatedAngle) < DEAD_ZONE_EXIT_RAD
        } else {
            abs(accumulatedAngle) < DEAD_ZONE_ENTER_RAD
        }
        val sensorValue = if (inDeadZone) 0f else accumulatedAngle

        eglHandler?.post {
            if (effectCtrl?.isPrepared() != true) return@post
            val normalized = (sensorValue / MAX_ANGLE_RAD).coerceIn(-1f, 1f)
            currentPlaybackPosition = abs(normalized)
        }
    }

    // ─────────────────────── EGL ───────────────────────────────

    private fun initEGL(surface: Surface) {
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

        eglContext = EGL14.eglCreateContext(
            display, configs[0], EGL14.EGL_NO_CONTEXT,
            intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE), 0
        )
        eglSurface = EGL14.eglCreateWindowSurface(
            display, configs[0], surface,
            intArrayOf(EGL14.EGL_NONE), 0
        )
        isEglReady = EGL14.eglMakeCurrent(display, eglSurface, eglSurface, eglContext)
        Log.w(TAG, "initEGL: ready=$isEglReady")
    }

    // ─────────────────────── 内容加载 ─────────────────────────

    private fun loadContent() {
        if (videoUri.isEmpty()) return

        initEffectCtrl()

        val texId = effectCtrl?.getTextureId() ?: 0
        if (texId == 0) {
            Log.w(TAG, "loadContent: texture not ready")
            return
        }

        val transcoder = KeyframeTranscoder(context)
        val cachedPath = transcoder.getCachedPath(videoUri)

        if (cachedPath != null) {
            Log.w(TAG, "loadContent: cache hit → $cachedPath")
            doLoadVideo(cachedPath)
        } else if (!isTranscoding) {
            isTranscoding = true
            notifyState(true, 0f)
            Log.w(TAG, "loadContent: starting transcode...")
            transcoder.transcodeAsync(videoUri, object : KeyframeTranscoder.Listener {
                override fun onProgress(progress: Float) {
                    notifyState(true, progress)
                }

                override fun onComplete(outputPath: String) {
                    Log.w(TAG, "transcode complete: $outputPath")
                    isTranscoding = false
                    eglHandler?.post { doLoadVideo(outputPath) }
                }

                override fun onError(message: String) {
                    Log.e(TAG, "transcode failed: $message, fallback original")
                    isTranscoding = false
                    eglHandler?.post { doLoadVideo(videoUri) }
                }
            })
        }
    }

    private fun doLoadVideo(videoPath: String) {
        effectCtrl?.loadSourceFromParams(
            RasterVideoPreRenderParamBean(videoPath = videoPath, videoFrameRate = 30)
        )
    }

    private fun initEffectCtrl() {
        stopPlaybackLoop()
        effectCtrl?.release()

        effectCtrl = RVEffectPreCtrl(context, object : RVEffectPreCtrl.Callback {
            override fun onPrepared(frameCount: Int, duration: Long) {
                Log.w(TAG, "onPrepared: frames=$frameCount, duration=$duration")
                eglHandler?.post {
                    lastFrameIndex = -1
                    currentPlaybackPosition = 0f
                    effectCtrl?.seekToFrame(0)
                    effectCtrl?.setDesignSize(sW, sH)
                    startPlaybackLoop()
                    notifyState(false, -1f)  // 加载完成
                }
            }

            override fun onFrameReady() { requestRender() }

            override fun onSeekComplete() {
                eglHandler?.post { requestRender() }
            }

            override fun onError(message: String) {
                Log.e(TAG, "effectCtrl error: $message")
            }
        })

        effectCtrl?.init()
    }

    // ─────────────────────── 播放循环 ─────────────────────────

    private fun startPlaybackLoop() {
        stopPlaybackLoop()
        playbackRunnable = object : Runnable {
            override fun run() {
                if (released) return
                if (effectCtrl?.isPrepared() == true) {
                    updateFrame()
                    requestRender()
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

        val target = (currentPlaybackPosition * (totalFrames - 1)).toInt()
            .coerceIn(0, totalFrames - 1)

        if (target == lastFrameIndex) {
            if (frameStableCount++ > 3) {
                effectCtrl?.ensurePaused()
            }
            return
        }

        frameStableCount = 0
        effectCtrl?.seekToFrame(target)
        lastFrameIndex = target
    }

    // ─────────────────────── 渲染 ─────────────────────────────

    private fun requestRender() {
        eglHandler?.removeCallbacks(drawRunnable)
        eglHandler?.post(drawRunnable)
    }

    private val drawRunnable = Runnable { draw() }

    private fun draw() {
        if (!isEglReady || released || sW <= 0 || sH <= 0) return

        EGL14.eglMakeCurrent(display, eglSurface, eglSurface, eglContext)

        if (effectCtrl?.isPrepared() != true) {
            GLES20.glViewport(0, 0, sW, sH)
            GLES20.glClearColor(0f, 0f, 0f, 1f)
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
            EGL14.eglSwapBuffers(display, eglSurface)
            return
        }

        effectCtrl?.onDrawFrame(sW, sH)
        EGL14.eglSwapBuffers(display, eglSurface)
    }

    // ─────────────────────── 状态通知 ─────────────────────────

    private fun notifyState(loading: Boolean, progress: Float) {
        val cb = onStateChanged ?: return
        android.os.Handler(android.os.Looper.getMainLooper()).post {
            cb(loading, progress)
        }
    }

    // ─────────────────────── 释放 ─────────────────────────────

    fun release() {
        if (released) return
        released = true

        eglHandler?.post {
            stopPlaybackLoop()
            try { effectCtrl?.release() } catch (_: Exception) {}
            effectCtrl = null

            try {
                if (display != EGL14.EGL_NO_DISPLAY) {
                    EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
                    if (eglSurface != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(display, eglSurface)
                    if (eglContext != EGL14.EGL_NO_CONTEXT) EGL14.eglDestroyContext(display, eglContext)
                    EGL14.eglTerminate(display)
                }
            } catch (e: Exception) {
                Log.e(TAG, "EGL destroy error: ${e.message}")
            }
            isEglReady = false
        }

        eglThread?.quitSafely()
    }
}

