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
import android.media.MediaPlayer
import android.net.Uri
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
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
import com.zeaze.tianyinwallpaper.model.RasterGroupModel
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.roundToInt

class RasterWallpaperService : WallpaperService() {
    private var activeEngine: RasterEngine? = null

    override fun onCreateEngine(): Engine = RasterEngine()

    inner class RasterEngine : Engine(), SurfaceTexture.OnFrameAvailableListener, SensorEventListener {

        init {
            activeEngine = this
        }

        // ── 数据 ──
        private var group: RasterGroupModel? = null
        // ✅ 修复：增大传感器角度阈值，让倾斜范围更合理（1.5f 适配大多数手机握持角度）
        private var sensorWidth = 0.6f
        // 每个图片区间中，扫描线过渡带占比（0~1）
        // 0.25 表示只有 25% 的角度范围在扫描，75% 是稳定展示
        private val transitionBand = 0.55f
        private var isVisible = false  // ✅ 壁纸可见性标志

        // ── 传感器 ──
        private var sensorManager: SensorManager? = null
        private var gyroSensor: Sensor? = null
        private var lastGyroNs = 0L
        private var accumulatedAngle = 0f        // 积分得到的角度（弧度）
        private var stationaryDuration = 0f      // 静止累计时长（秒）
        private var tiltNormalized = 0f           // 0~1 绝对值
        private var tiltDirection = 0             // -1=左倾，+1=右倾
                
        // ✅ 修复：低通滤波参数（优化平滑度和响应速度）
        private var filteredAngle = 0f
        private val FILTER_ALPHA = 0.15f // 更小的 alpha 值，更平滑且响应更快

        // ── 静态光栅 ──
        private var staticBitmaps = mutableListOf<Bitmap?>()
        private var imageCount = 0

        // ── 扫描线状态 ──
        // 用浮点索引表示当前位置：2.0=第3张，2.5=第3张和第4张之间扫描到一半
        private var currentFloatIndex = 0f
        private var displayedIntIndex = -1   // 当前主纹理对应的整数索引
        private var scanFromIndex = -1       // 扫描 texA 的索引
        private var scanToIndex = -1         // 扫描 texB 的索引
        private var scanProgress = 0f        // 0~1 扫描进度
        private var scanDirection = 0        // +1=从右到左，-1=从左到右

        // ── 动态光栅 ──
        private var exoPlayer: ExoPlayer? = null
        private var isPlayerPrepared = false
        private var videoDurationMs = 0L
        private var lastSeekPositionMs = 0L  // 上次 seek 的位置，用于阈值过滤
        private var lastSeekTimeNs = 0L      // 上次 seek 的时间，用于节流
        // ✅ 修复：降低 Seek 节流间隔，从 120ms 改为 80ms，更灵敏
        private val MIN_SEEK_INTERVAL_NS = 80_000_000L 
        // ✅ 修复：降低位置变化阈值，从 50ms 改为 20ms，小幅倾斜也能触发
        private val MIN_SEEK_POSITION_DELTA = 20L
        // ✅ 核心修复：Handler 用于切换到主线程执行 ExoPlayer 操作
        private val playerHandler = android.os.Handler(android.os.Looper.getMainLooper())
        private var isWaitingForSurface = false  // ✅ 标记是否正在等待 Surface 初始化

        // ── EGL ──
        private var eglThread: EglThread? = null
        private val updateSurface = AtomicBoolean(false)

        // ── 偏好 ──
        private var pref: SharedPreferences? = null
        private val handler = android.os.Handler(android.os.Looper.getMainLooper())

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

            pref = getSharedPreferences(App.TIANYIN, MODE_PRIVATE)
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
            isVisible = visible  // ✅ 更新可见性标志
            
            if (visible) {
                registerSensor()

                // ✅ 仅在 group 真正变化时才重新加载内容
                val newGroupId = pref?.getString(PREF_RASTER_ACTIVE_GROUP_ID, null)
                if (newGroupId != group?.id) {
                    loadActiveGroup()   // 更新 group
                    eglThread?.post { loadContent() } // 重新加载（仅当变化时）
                }

                // ✅ 删除所有自动播放控制 - 视频完全由陀螺仪控制
            } else {
                unregisterSensor()
                // ✅ 删除所有自动播放控制 - 视频完全由陀螺仪控制
            }
        }

        override fun onDestroy() {
            super.onDestroy()
            if (activeEngine == this) activeEngine = null
            unregisterSensor()
            exoPlayer?.release()
            exoPlayer = null
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
            // 重置所有传感器状态
            lastGyroNs = 0L
            accumulatedAngle = 0f
            filteredAngle = 0f
            stationaryDuration = 0f
            tiltNormalized = 0f // 归零确保初始位置正确
            tiltDirection = 0
            
            // ✅ 关键：壁纸可见时，立即将视频重置到起始帧
            if (group?.type == RasterGroupModel.TYPE_DYNAMIC && isPlayerPrepared) {
                this@RasterEngine.handler.post { // 切到主线程安全调用
                    exoPlayer?.seekTo(0)
                    lastSeekPositionMs = 0
                    lastSeekTimeNs = System.nanoTime()
                    Log.d(TAG, "Reset video to start position")
                }
            }
        }

        private fun unregisterSensor() {
            sensorManager?.unregisterListener(this)
            lastGyroNs = 0L
        }

        // ✅ 新增：记录倾斜方向


        override fun onSensorChanged(event: SensorEvent?) {
            val e = event ?: return
            if (e.sensor.type != Sensor.TYPE_GYROSCOPE) return

            if (lastGyroNs == 0L) {
                lastGyroNs = e.timestamp
                return
            }

            val dt = (e.timestamp - lastGyroNs) / 1_000_000_000f
            lastGyroNs = e.timestamp

            // Y 轴角速度（手机绕竖轴旋转）
            val angularVelocity = e.values[1]

            // 不在桌面上衰减，仅在离开桌面（锁屏等）时由 registerSensor() 归零
            // 轻微漂移抑制：只过滤极小噪声，不积分
            val absOmega = Math.abs(angularVelocity)
            if (absOmega >= 0.01f) {
                val rawDelta = angularVelocity * dt
                accumulatedAngle += rawDelta
                
                // ✅ 修复：正确的一阶低通滤波公式
                // 原公式错误：filteredAngle = FILTER_ALPHA * (filteredAngle + rawDelta) + (1 - FILTER_ALPHA) * filteredAngle
                // 正确公式：新值 = 系数*当前值 + (1-系数)*历史值
                filteredAngle = FILTER_ALPHA * rawDelta + (1 - FILTER_ALPHA) * filteredAngle
            }

            // ✅ 修复：使用 accumulatedAngle 计算倾斜幅度（更稳定），filteredAngle 仅用于平滑
            val currentAngle = accumulatedAngle
            // 幅度：使用累计角度的绝对值，除以合理的 sensorWidth（1.5f）
            val newTilt = (Math.abs(currentAngle) / sensorWidth).coerceIn(0f, 1f)
            
            // 调试日志：输出关键值，方便排查
            Log.d(TAG, "角度：${"%.3f".format(currentAngle)} | 归一化：${"%.3f".format(newTilt)} | 视频时长：$videoDurationMs")
            // TODO: 调试用，确认后删除
            //Log.d(TAG, "sW=$sensorWidth angle=${"%.3f".format(accumulatedAngle)} tilt=${"%.3f".format(newTilt)} imgs=$imageCount")

            // 方向判断仍用原始角度（保证响应速度）
            val newDirection = when {
                accumulatedAngle < -0.05f -> 1    // 右转
                accumulatedAngle > 0.05f -> -1    // 左转
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
            when (g.type) {
                RasterGroupModel.TYPE_STATIC -> {
                    if (imageCount == 0) return

                    // tiltNormalized: 0(平放) → 1(倾斜极限)
                    // 线性映射：每张图片分到等宽的角度范围
                    // （死区比例由 transitionBand 单独控制，不需要平方压缩）
                    val newFloatIndex = tiltNormalized * (imageCount - 1)
                    currentFloatIndex = newFloatIndex.coerceIn(0f, (imageCount - 1).toFloat())

                    if (imageCount == 1) {
                        if (displayedIntIndex != 0) {
                            displayedIntIndex = 0
                            eglThread?.post { uploadStaticFrame(0) }
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

                    // 过渡带居中：bandStart ~ bandEnd 之间才出扫描线
                    // 例如 transitionBand=0.25 → bandStart=0.375, bandEnd=0.625
                    // fraction < 0.375 → 稳定显示 fromIdx
                    // fraction > 0.625 → 稳定显示 toIdx
                    // 0.375~0.625 → 扫描线，progress 从 0 到 1
                    val bandStart = (1f - transitionBand) / 2f
                    val bandEnd = (1f + transitionBand) / 2f

                    if (fraction < bandStart) {
                        // ── 稳定区：显示 fromIdx ──
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
                        // ── 稳定区：显示 toIdx ──
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
                        // ── 过渡带：扫描线区域 ──
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
                RasterGroupModel.TYPE_DYNAMIC -> {
                    // ✅ 核心校验：时长为 0 时直接返回，避免无效计算
                    if (!isPlayerPrepared || videoDurationMs <= 0) {
                        Log.w(TAG, "⚠️ 视频时长无效，跳过 seek: $videoDurationMs ms")
                        return
                    }
                    
                    // 连续映射：直接使用 tiltNormalized 计算目标时间（无分桶）
                    val targetSeekMs = (videoDurationMs.toFloat() * tiltNormalized).toLong().coerceIn(0, videoDurationMs)
                    
                    // ✅ 修复：优化阈值 - 20ms 位置变化 + 80ms 时间间隔（更灵敏）
                    val now = System.nanoTime()
                    val needSeek = (kotlin.math.abs(targetSeekMs - lastSeekPositionMs) > MIN_SEEK_POSITION_DELTA) && 
                                   (now - lastSeekTimeNs > MIN_SEEK_INTERVAL_NS)
                    
                    if (needSeek) {
                        // ✅ 核心修复：使用 Handler 切换到主线程执行 seek，避免跨线程访问
                        // 所有 ExoPlayer 操作必须在绑定线程（主线程）执行
                        try {
                            playerHandler.post {
                                exoPlayer?.let { player ->
                                    // 在线程内安全读取和设置
                                    val currentPosition = player.currentPosition
                                    val shouldSeek = kotlin.math.abs(targetSeekMs - currentPosition) > MIN_SEEK_POSITION_DELTA
                                    
                                    if (shouldSeek) {
                                        player.seekTo(targetSeekMs)
                                        lastSeekPositionMs = targetSeekMs
                                        lastSeekTimeNs = now
                                        Log.d(TAG, "🎯 Seek to $targetSeekMs ms | tilt=${"%.2f".format(tiltNormalized)} | current=$currentPosition | Δ=${targetSeekMs - currentPosition}ms")
                                    }
                                }
                            }
                        } catch (e: Exception) {
                            Log.e(TAG, "seekTo 执行失败：${e.message}", e)
                        }
                    }
                }
            }
        }

        // ────────────────────────────────────────────
        // 数据加载
        // ────────────────────────────────────────────

        private fun loadActiveGroup() {
            val activeId = pref?.getString(PREF_RASTER_ACTIVE_GROUP_ID, null) ?: return
            val groupsJson = pref?.getString(PREF_RASTER_GROUPS, "[]") ?: "[]"
            val groups = try {
                JSON.parseArray(groupsJson, RasterGroupModel::class.java) ?: emptyList()
            } catch (_: Exception) {
                emptyList()
            }
            group = groups.firstOrNull { it.id == activeId } ?: groups.firstOrNull()
            // sensorWidth 不从 model 读取（FastJSON 反序列化值不可靠：旧JSON=4.5/缺失字段=0.0）
            // 直接使用字段声明的默认值（1.0f），如需可配再加 UI
        }

        private fun loadContent() {
            val g = group ?: return

            if (exoPlayer != null) {
                exoPlayer!!.release()
                isPlayerPrepared = false
            }

            when (g.type) {
                RasterGroupModel.TYPE_STATIC -> prepareStaticImages(g)
                RasterGroupModel.TYPE_DYNAMIC -> prepareDynamicVideo(g)
            }
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

            // ✅ 初始显示第1张
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

        private fun prepareDynamicVideo(g: RasterGroupModel) {
            val videoUri = g.videoUri
            if (videoUri.isNullOrEmpty()) {
                Log.e(TAG, "Video URI is empty")
                return
            }

            Log.d(TAG, "prepareDynamicVideo called. Type: ${g.type}")

            // ✅ 1. 释放旧播放器
            exoPlayer?.release()
            exoPlayer = null
            isPlayerPrepared = false

            // ✅ 2. 检查 EGL 线程和 SurfaceTexture 是否就绪
            val st = eglThread?.videoST
            Log.d(TAG, "SurfaceTexture status: ${if (st == null) "NULL" else "READY"}")
            
            if (st == null) {
                Log.w(TAG, "SurfaceTexture not ready yet. Deferring player initialization.")
                isWaitingForSurface = true // 标记需要重试
                return
            }
            
            isWaitingForSurface = false

            try {
                // ✅ 3. 创建 ExoPlayer
                exoPlayer = ExoPlayer.Builder(applicationContext)
                    .build()
                
                // ✅ 4. 关键：使用已就绪的 SurfaceTexture 创建 Surface（不要立即 release）
                val surface = Surface(st)
                Log.d(TAG, "Creating Surface from ST...")
                exoPlayer!!.setVideoSurface(surface)
                Log.d(TAG, "Setting Video Surface...")
                // ❌ 不要调用 surface.release() - ExoPlayer 需要持有这个 Surface
                
                // ✅ 5. 设置播放参数 - 关键：强制禁止自动播放
                exoPlayer!!.repeatMode = Player.REPEAT_MODE_OFF // ✅ 移除循环（不需要）
                exoPlayer!!.playWhenReady = false // ✅ 强制禁止自动播放，视频始终暂停
                exoPlayer!!.volume = 0f
                
                // ✅ 6. 监听状态
                exoPlayer!!.addListener(object : Player.Listener {
                    override fun onPlaybackStateChanged(state: Int) {
                        when (state) {
                            Player.STATE_READY -> {
                                // ✅ 核心修复：正确获取视频时长并校验
                                val duration = exoPlayer!!.duration
                                if (duration > 0 && duration != androidx.media3.common.C.TIME_UNSET) {
                                    videoDurationMs = duration
                                    Log.d(TAG, "✅ 视频时长初始化成功：$videoDurationMs ms")
                                } else {
                                    Log.e(TAG, "⚠️ 视频时长无效：$duration，使用默认值 10000ms")
                                    videoDurationMs = 10000L // 默认 10 秒
                                }
                                
                                isPlayerPrepared = true
                                lastSeekPositionMs = 0L // 重置为 0，确保首次 seek 能触发
                                lastSeekTimeNs = System.nanoTime() // ✅ 初始化时间戳
                                
                                // ✅ 获取视频宽高并设置给 EGL 线程
                                val videoWidth = exoPlayer!!.videoSize.width
                                val videoHeight = exoPlayer!!.videoSize.height
                                if (videoWidth > 0 && videoHeight > 0) {
                                    eglThread?.setContentSize(videoWidth, videoHeight)
                                    Log.d(TAG, "Video size: ${videoWidth}x${videoHeight}")
                                }
                                
                                Log.d(TAG, "ExoPlayer READY. Duration: $videoDurationMs ms, tilt=$tiltNormalized")
                                
                                // ✅【核心修复】视频就绪后立即根据当前陀螺仪角度跳转
                                onTiltChanged() // 此时 isPlayerPrepared=true，会执行 seek
                                
                                eglThread?.requestRender()
                            }
                            Player.STATE_BUFFERING -> {
                                Log.d(TAG, "ExoPlayer BUFFERING")
                            }
                            Player.STATE_ENDED -> {
                                Log.d(TAG, "ExoPlayer ENDED")
                            }
                            Player.STATE_IDLE -> {
                                isPlayerPrepared = false
                            }
                        }
                    }
                    
                    override fun onPlayerError(error: PlaybackException) {
                        Log.e(TAG, "ExoPlayer ERROR: ${error.errorCodeName} - ${error.message}", error)
                        isPlayerPrepared = false
                    }
                    
                    override fun onVideoSizeChanged(videoSize: androidx.media3.common.VideoSize) {
                        if (videoSize.width > 0 && videoSize.height > 0) {
                            eglThread?.setContentSize(videoSize.width, videoSize.height)
                            Log.d(TAG, "Video size changed: ${videoSize.width}x${videoSize.height}")
                            eglThread?.requestRender()
                        }
                    }
                })
                
                // ✅ 7. 设置媒体项并准备
                val mediaItem = MediaItem.fromUri(Uri.parse(videoUri))
                exoPlayer!!.setMediaItem(mediaItem)
                exoPlayer!!.prepare()
                
                Log.d(TAG, "ExoPlayer created and preparing...")
                eglThread?.resetVideoMatrix()
                
            } catch (e: Exception) {
                Log.e(TAG, "Failed to prepare dynamic video", e)
                exoPlayer?.release()
                exoPlayer = null
            }
        }

        // ✅ 新增：由 EGL 线程调用，通知 Surface 已就绪
        fun onSurfaceTextureAvailable() {
            if (isWaitingForSurface && group?.type == RasterGroupModel.TYPE_DYNAMIC) {
                Log.d(TAG, "SurfaceTexture available, retrying player initialization...")
                val g = group ?: return
                if (g.type == RasterGroupModel.TYPE_DYNAMIC) {
                    prepareDynamicVideo(g)
                }
            }
        }

        override fun onFrameAvailable(surfaceTexture: SurfaceTexture) {
            updateSurface.set(true)
            eglThread?.requestRender()
        }

        // ────────────────────────────────────────────
        // EGL 渲染线程
        // ────────────────────────────────────────────

        private inner class EglThread(private val holder: SurfaceHolder) : HandlerThread("RasterEGL") {
            private var display: EGLDisplay = EGL14.EGL_NO_DISPLAY
            private var context: EGLContext = EGL14.EGL_NO_CONTEXT
            private var eglSurface: EGLSurface = EGL14.EGL_NO_SURFACE
            private var handler: Handler? = null

            var videoST: SurfaceTexture? = null
                private set

            private var vTexId = 0
            private var iTexId = 0
            private var texAId = 0
            private var texBId = 0
            private var vProg = 0
            private var iProg = 0
            private var transitionProg = 0
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
            // ✅ 同步版本，在当前线程直接上传（调用方已在 EGL 线程上通过 requestRender 触发）
            fun uploadBitmapSync(b: Bitmap) {
                GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, iTexId)
                GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, b, 0)
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

            fun resetVideoMatrix() {
                post { Matrix.setIdentityM(videoSTMatrix, 0) }
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
                val fsV = "#extension GL_OES_EGL_image_external : require\n precision mediump float; varying vec2 vTex; uniform samplerExternalOES sTex; void main(){ gl_FragColor = texture2D(sTex, vTex); }"
                val fsI = "precision mediump float; varying vec2 vTex; uniform sampler2D sTex; void main(){ gl_FragColor = texture2D(sTex, vTex); }"
                vProg = createProg(vs, fsV)
                iProg = createProg(vs, fsI)

                // 和稳定显示共用同一个顶点着色器（uMVP + uST），消除几何差异
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

                // 用 gl_FragCoord.x / uScreenWidth 取屏幕坐标，不依赖纹理坐标
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

                val tex = IntArray(4)
                GLES20.glGenTextures(4, tex, 0)
                vTexId = tex[0]
                iTexId = tex[1]
                texAId = tex[2]
                texBId = tex[3]

                GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, vTexId)
                GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
                
                // ✅ 创建 SurfaceTexture
                videoST = SurfaceTexture(vTexId)
                videoST?.setOnFrameAvailableListener(this@RasterEngine)

                for (texId in intArrayOf(iTexId, texAId, texBId)) {
                    GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texId)
                    GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
                    GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
                    GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
                    GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
                }

                Matrix.setIdentityM(videoSTMatrix, 0)
                
                // ✅ 【关键修复】通知外部引擎 Surface 已就绪
                handler?.post {
                    this@RasterEngine.onSurfaceTextureAvailable()
                }
            }

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
                    // ✅ 正常渲染前同步确保主纹理是对的
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
                } else {
                    // ── 视频渲染 ──
                    val isVid = g?.type == RasterGroupModel.TYPE_DYNAMIC
                    val stMat = FloatArray(16)

                    if (isVid) {
                        if (updateSurface.getAndSet(false)) {
                            try {
                                videoST?.updateTexImage()
                                videoST?.getTransformMatrix(videoSTMatrix)
                            } catch (e: Exception) {
                                Log.w(TAG, "updateTexImage failed", e)
                            }
                        }
                        System.arraycopy(videoSTMatrix, 0, stMat, 0, 16)
                    } else {
                        System.arraycopy(imageMatrix, 0, stMat, 0, 16)
                    }

                    val prog = if (isVid) vProg else iProg
                    GLES20.glUseProgram(prog)

                    val mvp = FloatArray(16)
                    Matrix.setIdentityM(mvp, 0)
                    val cAsp = cW.toFloat() / cH
                    val sAsp = sW.toFloat() / sH
                    if (cAsp > sAsp) {
                        Matrix.scaleM(mvp, 0, cAsp / sAsp, 1f, 1f)
                    } else {
                        Matrix.scaleM(mvp, 0, 1f, sAsp / cAsp, 1f)
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
                    GLES20.glBindTexture(
                        if (isVid) GLES11Ext.GL_TEXTURE_EXTERNAL_OES else GLES20.GL_TEXTURE_2D,
                        if (isVid) vTexId else iTexId
                    )
                    GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
                }

                if (!EGL14.eglSwapBuffers(display, eglSurface)) {
                    Log.e(TAG, "SwapBuffers failed")
                }
            }

            private fun drawTransition() {
                GLES20.glUseProgram(transitionProg)

                // ── 和稳定显示完全相同的几何管线 ──
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

                // ── 纹理绑定 ──
                GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
                GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texAId)
                GLES20.glUniform1i(GLES20.glGetUniformLocation(transitionProg, "sTexA"), 0)

                GLES20.glActiveTexture(GLES20.GL_TEXTURE1)
                GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texBId)
                GLES20.glUniform1i(GLES20.glGetUniformLocation(transitionProg, "sTexB"), 1)

                // ── 扫描线参数 ──
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
        const val ACTION_RELOAD = "com.zeaze.tianyinwallpaper.RASTER_RELOAD"
        const val PREF_RASTER_GROUPS = "rasterGroups"
        const val PREF_RASTER_ACTIVE_GROUP_ID = "rasterActiveGroupId"
        private const val TAG = "RasterGL"
    }
}