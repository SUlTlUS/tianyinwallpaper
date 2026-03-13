package com.zeaze.tianyinwallpaper.service.raster

import android.content.Context
import android.graphics.SurfaceTexture
import android.media.MediaCodec
import android.media.MediaCodecList
import android.media.MediaExtractor
import android.media.MediaFormat
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.Surface
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.util.ArrayDeque
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 视频资源管理器
 * 参考自 vivo KFramePlayer
 * 使用 MediaCodec 直接解码视频帧
 */
class RVRes(
    private val context: Context,
    private val callback: Callback
) {
    companion object {
        private const val TAG = "RVRes"
        private const val MIME_TYPE_AVC = "video/avc"
        private const val MIME_TYPE_HEVC = "video/hevc"
    }
    
    interface Callback {
        fun onVideoFrameReady(frameIndex: Int, textureId: Int, transformMatrix: FloatArray)
        fun onVideoPrepared(frameCount: Int, duration: Long, width: Int, height: Int)
        fun onVideoError(message: String)
    }
    
    // 解码器相关
    private var mediaCodec: MediaCodec? = null
    private var mediaExtractor: MediaExtractor? = null
    private var videoFormat: MediaFormat? = null
    private var videoTrackIndex: Int = -1
    
    // Surface 相关
    private var surfaceTexture: SurfaceTexture? = null
    private var surface: Surface? = null
    private var textureId: Int = 0
    
    // 状态
    private var isPrepared = false
    private var hasNotifiedPrepared = false
    
    // 视频信息
    private var videoDuration: Long = 0
    private var videoFrameCount: Int = 0
    private var videoFrameRate: Float = 30f
    private var currentFrameIndex: Int = -2  // 初始化为 -2，确保第一帧能解码
    
    // 纹理矩阵
    private val transformMatrix = FloatArray(16)
    private val updateSurface = AtomicBoolean(false)
    
    // 解码线程
    private var decodeThread: HandlerThread? = null
    private var decodeHandler: Handler? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    
    // 输入缓冲区队列
    private val inputBufferQueue = ArrayDeque<Int>()
    private val inputBufferLock = Any()
    
    // 待解码帧队列
    private val decodeQueue = ArrayDeque<DecodeTask>()
    private val decodeLock = Any()
    
    // 渲染任务队列（等待渲染的已解码帧）
    private val renderQueue = ArrayDeque<RenderTask>()
    private val renderLock = Any()
    
    // 上一帧索引（用于连续帧优化）
    private var lastDecodedFrameIndex: Int = -1
    
    // 当前期望渲染的目标帧时间范围（用于过滤中间帧）
    private var targetFrameTimeUs: Long = -1
    
    // 预解码窗口大小（最多预解码 4 帧）
    private val prefetchWindow = 4
    
    // 预解码控制计数器（参考 vivo K3/h.java 的 f1946r）
    private var prefetchCounter = 100  // 初始允许预解码
    
    // 帧率控制（毫秒）- 默认 60fps = 16.67ms
    private var frameIntervalMs = 16L  // 可配置：60fps=16ms, 90fps=11ms, 30fps=33ms
    private var lastRenderTimeUs = 0L  // 上次渲染时间（微秒）
    
    // 已解码帧缓存（最近解码的帧，用于快速访问）
    private val decodedFrameCache = mutableMapOf<Int, FrameInfo>()
    private val cacheLock = Any()
    private val maxCacheSize = 10  // 最多缓存 10 帧
    
    // 帧可用回调
    private var onFrameAvailableCallback: (() -> Unit)? = null
    
    private data class DecodeTask(
        val frameIndex: Int,
        val retryCount: Int = 10
    )
    
    private data class RenderTask(
        val frameIndex: Int,
        val bufferIndex: Int
    )
    
    /**
     * 已解码帧信息
     */
    data class FrameInfo(
        val frameIndex: Int,
        val timestampUs: Long,
        val bufferIndex: Int
    )
    
    // 顶点和纹理缓冲区
    private val vertexBuffer: FloatBuffer
    private val texCoordBuffer: FloatBuffer
    private var shaderProgram: Int = 0
    
    init {
        // 初始化顶点数据
        val vertexData = floatArrayOf(
            -1f, -1f,
            1f, -1f,
            -1f, 1f,
            1f, 1f
        )
        vertexBuffer = ByteBuffer.allocateDirect(vertexData.size * 4)
            .order(ByteOrder.nativeOrder())
            .asFloatBuffer()
            .put(vertexData)
        vertexBuffer.position(0)
        
        val texCoordData = floatArrayOf(
            0f, 0f,
            1f, 0f,
            0f, 1f,
            1f, 1f
        )
        texCoordBuffer = ByteBuffer.allocateDirect(texCoordData.size * 4)
            .order(ByteOrder.nativeOrder())
            .asFloatBuffer()
            .put(texCoordData)
        texCoordBuffer.position(0)
        
        android.opengl.Matrix.setIdentityM(transformMatrix, 0)
    }
    
    fun initGL(): Int {
        Log.w(TAG, "initGL: starting...")
        
        // 创建 OES 纹理
        val textures = IntArray(1)
        GLES20.glGenTextures(1, textures, 0)
        textureId = textures[0]
        
        Log.w(TAG, "initGL: textureId=$textureId")
        
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureId)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        
        // 创建 SurfaceTexture
        surfaceTexture = SurfaceTexture(textureId)
        Log.w(TAG, "initGL: SurfaceTexture created: $surfaceTexture")
        
        // 设置帧可用监听器 - 不指定 Handler，回调会在任意线程
        surfaceTexture?.setOnFrameAvailableListener({ 
            Log.w(TAG, "onFrameAvailable: texture frame ready, thread=${Thread.currentThread().name}, callback=${onFrameAvailableCallback != null}, frameIndex=$currentFrameIndex")
            updateSurface.set(true)
            // 通知回调
            onFrameAvailableCallback?.invoke()
            callback.onVideoFrameReady(currentFrameIndex, textureId, transformMatrix)
        })
        
        // 创建着色器程序
        shaderProgram = createOESProgram()
        Log.w(TAG, "initGL: shaderProgram=$shaderProgram")
        
        Log.w(TAG, "initGL: completed, textureId=$textureId")
        return textureId
    }
    
    fun loadVideo(videoPath: String) {
        Log.w(TAG, "loadVideo: $videoPath")
        releaseDecoder()
        hasNotifiedPrepared = false
        
        val st = surfaceTexture ?: run {
            Log.e(TAG, "loadVideo: SurfaceTexture not initialized!")
            return
        }
        
        Log.w(TAG, "loadVideo: SurfaceTexture ready, creating decoder...")
        
        // 创建 Surface
        surface = Surface(st)
        
        // 启动解码线程
        decodeThread = HandlerThread("VideoDecoder").apply { start() }
        decodeHandler = Handler(decodeThread!!.looper)
        
        // 准备视频
        prepareVideo(videoPath)
    }
    
    private fun prepareVideo(videoPath: String) {
        try {
            // 创建 MediaExtractor
            mediaExtractor = MediaExtractor()
            
            // 处理 content:// URI
            if (videoPath.startsWith("content://")) {
                Log.w(TAG, "prepareVideo: resolving content URI: $videoPath")
                val uri = android.net.Uri.parse(videoPath)
                val fileDescriptor = context.contentResolver.openFileDescriptor(uri, "r")?.fileDescriptor
                if (fileDescriptor == null) {
                    callback.onVideoError("Failed to open content URI: $videoPath")
                    return
                }
                mediaExtractor?.setDataSource(fileDescriptor)
            } else {
                mediaExtractor?.setDataSource(videoPath)
            }
            
            // 查找视频轨道
            val extractor = mediaExtractor!!
            for (i in 0 until extractor.trackCount) {
                val format = extractor.getTrackFormat(i)
                val mime = format.getString(MediaFormat.KEY_MIME) ?: continue
                if (mime.startsWith("video/")) {
                    videoTrackIndex = i
                    videoFormat = format
                    extractor.selectTrack(i)
                    break
                }
            }
            
            if (videoFormat == null) {
                callback.onVideoError("No video track found")
                return
            }
            
            val format = videoFormat!!
            videoDuration = format.getLong(MediaFormat.KEY_DURATION) / 1000 // 转换为毫秒
            val frameRate = if (format.containsKey(MediaFormat.KEY_FRAME_RATE)) {
                format.getInteger(MediaFormat.KEY_FRAME_RATE)
            } else 30
            videoFrameRate = frameRate.toFloat()
            videoFrameCount = (videoDuration * videoFrameRate / 1000).toInt()
            
            val width = format.getInteger(MediaFormat.KEY_WIDTH)
            val height = format.getInteger(MediaFormat.KEY_HEIGHT)
            
            Log.w(TAG, "Video info: duration=${videoDuration}ms, frames=$videoFrameCount, size=${width}x${height}, fps=$frameRate")
            
            // 创建解码器
            createDecoder()
            
            isPrepared = true
            
            // 通知准备完成
            hasNotifiedPrepared = true
            callback.onVideoPrepared(videoFrameCount, videoDuration, width, height)
            
            // 立即请求解码第一帧
            Log.w(TAG, "prepareVideo: requesting first frame decode")
            mainHandler.postDelayed({
                seekToFrame(0)
            }, 100)
            
        } catch (e: Exception) {
            Log.e(TAG, "prepareVideo failed", e)
            callback.onVideoError(e.message ?: "Failed to prepare video")
        }
    }
    
    private fun createDecoder() {
        val format = videoFormat ?: return
        val surf = surface ?: return
        
        try {
            // 尝试使用 low-latency 解码器
            val codecList = MediaCodecList(MediaCodecList.REGULAR_CODECS)
            val codecName = codecList.findDecoderForFormat(format)
            
            mediaCodec = if (codecName != null) {
                Log.w(TAG, "Using decoder: $codecName")
                MediaCodec.createByCodecName(codecName)
            } else {
                Log.w(TAG, "Using default decoder for mime: ${format.getString(MediaFormat.KEY_MIME)}")
                MediaCodec.createDecoderByType(format.getString(MediaFormat.KEY_MIME)!!)
            }
            
            // 优化 1: 高解码率加速 (operating-rate = 240fps)
            try {
                format.setInteger("operating-rate", 240)
                Log.w(TAG, "Set operating-rate: 240")
            } catch (e: Exception) {
                Log.w(TAG, "operating-rate not supported: ${e.message}")
            }
            
            // 优化 2: 低延迟解码
            try {
                format.setFeatureEnabled("low-latency", true)
                Log.w(TAG, "Enabled low-latency feature")
            } catch (e: Exception) {
                Log.w(TAG, "low-latency feature not supported: ${e.message}")
            }
            
            // 优化 3: 允许丢帧加速（跳过非关键帧渲染）
            try {
                format.setInteger("allow-frame-drop", 1)
                Log.w(TAG, "Set allow-frame-drop: 1")
            } catch (e: Exception) {
                Log.w(TAG, "allow-frame-drop not supported: ${e.message}")
            }
            
            // 配置解码器
            mediaCodec?.configure(format, surf, null, 0)
            
            // 优化 2: 低延迟解码参数
            try {
                val params = android.os.Bundle()
                params.putInt("low-latency", 1)
                mediaCodec?.setParameters(params)
                Log.w(TAG, "Set low-latency: 1")
            } catch (e: Exception) {
                Log.w(TAG, "low-latency parameter not supported: ${e.message}")
            }
            
            // 设置异步回调
            mediaCodec?.setCallback(object : MediaCodec.Callback() {
                override fun onInputBufferAvailable(codec: MediaCodec, index: Int) {
                    Log.d(TAG, "onInputBufferAvailable: index=$index")
                    synchronized(inputBufferLock) {
                        inputBufferQueue.add(index)
                        Log.d(TAG, "onInputBufferAvailable: inputBufferQueue size=${inputBufferQueue.size}")
                    }
                    tryDecodeNextFrame()
                }
                
                override fun onOutputBufferAvailable(codec: MediaCodec, index: Int, info: MediaCodec.BufferInfo) {
                    // 只渲染目标帧，丢弃中间帧（避免花屏）
                    val presentationTime = info.presentationTimeUs
                    val frameDuration = 1_000_000L / videoFrameRate.toLong()
                    
                    // 判断是否是目标帧（允许一个帧的误差）
                    val isTargetFrame = (targetFrameTimeUs >= 0 && 
                        kotlin.math.abs(presentationTime - targetFrameTimeUs) <= frameDuration)
                    
                    // 帧率控制：检查是否到达渲染时间
                    val shouldRenderNow = shouldRender(presentationTime)
                    
                    val render = info.size > 0 && isTargetFrame && shouldRenderNow
                    Log.w(TAG, "onOutputBufferAvailable: index=$index, size=${info.size}, pts=${presentationTime}us, target=$targetFrameTimeUs, render=$render, frameControl=$shouldRenderNow")
                    
                    try {
                        if (render) {
                            // 记录已解码帧到缓存
                            val frameIndex = (presentationTime * videoFrameRate / 1_000_000).toInt()
                            addToFrameCache(FrameInfo(frameIndex, presentationTime, index))
                            currentFrameIndex = frameIndex
                            
                            // 更新渲染时间戳
                            updateRenderTime(presentationTime)
                            
                            // 添加到渲染队列（用于队列大小判断）
                            synchronized(renderLock) {
                                renderQueue.add(RenderTask(frameIndex, index))
                            }
                            
                            Log.w(TAG, "Rendered frame $currentFrameIndex, time=${presentationTime}us, cached=${decodedFrameCache.size}")
                        }
                        codec.releaseOutputBuffer(index, render)
                        
                        // 成功释放缓冲区后，重置预解码计数器（允许继续预解码）
                        // 参考 vivo K3/h.java: 每次处理完队列后设置 f1946r = 100
                        if (render) {
                            prefetchCounter = 100
                            Log.d(TAG, "onOutputBufferAvailable: reset prefetchCounter to 100")
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "releaseOutputBuffer error: ${e.message}")
                    }
                }
                
                override fun onError(codec: MediaCodec, e: MediaCodec.CodecException) {
                    Log.e(TAG, "MediaCodec error: ${e.message}")
                    callback.onVideoError(e.message ?: "Codec error")
                }
                
                override fun onOutputFormatChanged(codec: MediaCodec, format: MediaFormat) {
                    Log.w(TAG, "Output format changed: $format")
                }
            }, decodeHandler)
            
            // 启动解码器
            mediaCodec?.start()
            Log.w(TAG, "Decoder started")
            
        } catch (e: Exception) {
            Log.e(TAG, "createDecoder failed", e)
            callback.onVideoError(e.message ?: "Failed to create decoder")
        }
    }
    
    private fun tryDecodeNextFrame() {
        Log.d(TAG, "tryDecodeNextFrame: called")
        decodeHandler?.post {
            synchronized(decodeLock) {
                if (decodeQueue.isEmpty()) {
                    Log.d(TAG, "tryDecodeNextFrame: decodeQueue is empty")
                    return@post
                }
            }
            
            synchronized(inputBufferLock) {
                if (inputBufferQueue.isEmpty()) {
                    Log.w(TAG, "tryDecodeNextFrame: inputBufferQueue is empty, waiting for codec...")
                    return@post
                }
                
                val bufferIndex = inputBufferQueue.removeFirst()
                val task = synchronized(decodeLock) {
                    if (decodeQueue.isEmpty()) {
                        inputBufferQueue.addFirst(bufferIndex)
                        Log.d(TAG, "tryDecodeNextFrame: decodeQueue became empty")
                        return@post
                    }
                    decodeQueue.removeFirst()
                }
                
                Log.w(TAG, "tryDecodeNextFrame: decoding frame ${task.frameIndex}, bufferIndex=$bufferIndex")
                decodeFrame(task, bufferIndex)
            }
        }
    }
    
    private fun decodeFrame(task: DecodeTask, bufferIndex: Int) {
        val codec = mediaCodec ?: return
        val extractor = mediaExtractor ?: return
        
        Log.w(TAG, "decodeFrame: task.frameIndex=${task.frameIndex}, bufferIndex=$bufferIndex, lastDecoded=$lastDecodedFrameIndex")
        
        try {
            val inputBuffer = codec.getInputBuffer(bufferIndex) ?: run {
                Log.e(TAG, "Input buffer is null")
                return
            }
            
            // 计算目标时间
            val targetTimeUs = (task.frameIndex.toLong() * 1_000_000 / videoFrameRate.toLong())
            Log.d(TAG, "decodeFrame: targetTimeUs=${targetTimeUs}us for frame ${task.frameIndex}")
            
            // 优化 3: 智能 seek 策略
            val isConsecutive = (task.frameIndex - lastDecodedFrameIndex == 1)
            
            if (isConsecutive && lastDecodedFrameIndex >= 0) {
                // 连续帧：直接 advance，不重新 seek
                Log.d(TAG, "decodeFrame: consecutive frame, advance only")
                extractor.advance()
            } else {
                // 跳帧：seek 到最近关键帧 (SEEK_TO_CLOSEST_SYNC = 2)
                Log.d(TAG, "decodeFrame: non-consecutive, seeking to ${targetTimeUs}us")
                extractor.seekTo(targetTimeUs, MediaExtractor.SEEK_TO_CLOSEST_SYNC)
            }
            
            // 读取数据
            var sampleSize = extractor.readSampleData(inputBuffer, 0)
            var sampleTime = extractor.sampleTime
            var sampleFlags = extractor.sampleFlags
            
            Log.d(TAG, "decodeFrame: after seek - sampleSize=$sampleSize, sampleTime=$sampleTime, sampleFlags=$sampleFlags")
            
            // 如果读取的不是目标帧，尝试前进到正确的帧
            var attempts = 0
            while (sampleSize > 0 && sampleTime < targetTimeUs && attempts < 30) {
                // 跳过这一帧
                extractor.advance()
                sampleSize = extractor.readSampleData(inputBuffer, 0)
                sampleTime = extractor.sampleTime
                sampleFlags = extractor.sampleFlags
                attempts++
                Log.d(TAG, "decodeFrame: advancing, attempt=$attempts, time=$sampleTime")
            }
            
            if (sampleSize > 0 && sampleTime >= 0) {
                codec.queueInputBuffer(bufferIndex, 0, sampleSize, sampleTime, sampleFlags)
                lastDecodedFrameIndex = task.frameIndex
                Log.w(TAG, "Queued input buffer for frame ${task.frameIndex}, time=${sampleTime}us, size=$sampleSize, attempts=$attempts")
            } else {
                Log.w(TAG, "No sample data for frame ${task.frameIndex}, signaling EOS")
                codec.queueInputBuffer(bufferIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
            }
            
        } catch (e: Exception) {
            Log.e(TAG, "decodeFrame error", e)
        }
    }
    
    fun updateTexImage(): Boolean {
        val hasUpdate = updateSurface.getAndSet(false)
        Log.d(TAG, "updateTexImage: hasUpdate=$hasUpdate, thread=${Thread.currentThread().name}")
        if (hasUpdate) {
            try {
                surfaceTexture?.updateTexImage()
                surfaceTexture?.getTransformMatrix(transformMatrix)
                
                // 从渲染队列中移除已完成的帧
                synchronized(renderLock) {
                    if (renderQueue.isNotEmpty()) {
                        renderQueue.removeFirst()
                    }
                }
                
                Log.w(TAG, "updateTexImage: success, frameIndex=$currentFrameIndex, renderQueueSize=${renderQueue.size}")
                return true
            } catch (e: Exception) {
                Log.e(TAG, "updateTexImage failed", e)
            }
        }
        return false
    }
    
    fun getTransformMatrix(): FloatArray = transformMatrix
    
    fun seekToFrame(frameIndex: Int) {
        if (!isPrepared) {
            Log.w(TAG, "seekToFrame: not prepared, skipping")
            return
        }
        
        val targetFrame = frameIndex.coerceIn(0, videoFrameCount - 1)
        Log.d(TAG, "seekToFrame: targetFrame=$targetFrame, currentFrameIndex=$currentFrameIndex")
        
        if (targetFrame == currentFrameIndex) {
            Log.d(TAG, "seekToFrame: same frame, skipping")
            return
        }
        
        // 清空队列并重置连续帧状态（拖动时不预解码，避免乱序）
        synchronized(decodeLock) {
            decodeQueue.clear()
            renderQueue.clear()  // 同时清空渲染队列
            decodeQueue.add(DecodeTask(targetFrame))
            // 重置连续帧判断，强制 seek
            lastDecodedFrameIndex = -1
            // 设置目标帧时间（用于过滤中间帧）
            targetFrameTimeUs = (targetFrame.toLong() * 1_000_000 / videoFrameRate.toLong())
            // 重置渲染时间戳（拖动后需要立即渲染）
            lastRenderTimeUs = 0L
            // 重置预解码计数器（允许重新预解码）
            prefetchCounter = 100
            Log.d(TAG, "seekToFrame: added decode task for frame $targetFrame, targetTimeUs=$targetFrameTimeUs")
        }
        
        // 预解码后续几帧（平滑拖动）
        prefetchFrames(targetFrame + 1, prefetchWindow)
        
        tryDecodeNextFrame()
    }
    
    /**
     * 预解码指定范围的帧（带队列大小判断）
     * @param startIndex 起始帧索引
     * @param count 预解码帧数（最多 prefetchWindow 帧）
     */
    private fun prefetchFrames(startIndex: Int, count: Int) {
        // 队列大小判断：参考 vivo K3/h.java:426-432
        // 计算当前队列中的总任务数（解码队列 + 渲染队列）
        val totalQueueSize = decodeQueue.size + renderQueue.size
        
        // 根据队列大小动态调整预解码数量
        val maxPrefetchCount = if (totalQueueSize <= 3) {
            // 队列空闲时，允许预解码最多 4 帧
            4
        } else {
            // 队列繁忙时，动态计算预解码数量
            // 公式：(totalQueueSize - 3) * 16，最多 100 帧
            val dynamicCount = (totalQueueSize - 3) * 16
            dynamicCount.coerceIn(1, 100)
        }
        
        // 检查是否允许预解码
        if (prefetchCounter <= 0) {
            Log.d(TAG, "prefetchFrames: prefetchCounter=$prefetchCounter, skipping")
            return
        }
        
        // 限制实际预解码数量
        val actualCount = minOf(count, prefetchWindow, maxPrefetchCount)
        
        Log.w(TAG, "prefetchFrames: start=$startIndex, count=$actualCount, queueSize=$totalQueueSize, maxPrefetch=$maxPrefetchCount, counter=$prefetchCounter")
        
        synchronized(decodeLock) {
            for (i in 0 until actualCount) {
                val frameIndex = startIndex + i
                if (frameIndex in 0 until videoFrameCount) {
                    decodeQueue.add(DecodeTask(frameIndex))
                    Log.d(TAG, "prefetchFrames: queued frame $frameIndex")
                }
            }
        }
        
        // 递减计数器
        prefetchCounter--
    }
    
    /**
     * 添加已解码帧到缓存
     */
    private fun addToFrameCache(frameInfo: FrameInfo) {
        synchronized(cacheLock) {
            // 如果缓存已满，移除最旧的帧
            if (decodedFrameCache.size >= maxCacheSize) {
                val oldestKey = decodedFrameCache.keys.minOrNull()
                oldestKey?.let { decodedFrameCache.remove(it) }
            }
            decodedFrameCache[frameInfo.frameIndex] = frameInfo
        }
    }
    
    /**
     * 设置目标帧率
     * @param fps 帧率（30/60/90 等）
     */
    fun setTargetFrameRate(fps: Int) {
        frameIntervalMs = (1000 / fps).toLong()
        Log.w(TAG, "setTargetFrameRate: $fps fps, interval=${frameIntervalMs}ms")
    }
    
    /**
     * 判断是否需要渲染（帧率控制）
     * @param currentTimeUs 当前时间（微秒）
     * @return true 表示需要渲染，false 表示跳过
     */
    private fun shouldRender(currentTimeUs: Long): Boolean {
        if (lastRenderTimeUs == 0L) return true  // 第一次渲染
        
        val elapsedUs = currentTimeUs - lastRenderTimeUs
        val elapsedMs = elapsedUs / 1000
        
        return elapsedMs >= frameIntervalMs
    }
    
    /**
     * 更新渲染时间戳
     */
    private fun updateRenderTime(currentTimeUs: Long) {
        lastRenderTimeUs = currentTimeUs
    }
    
    /**
     * 计算到下一次渲染的延迟（毫秒）
     * @param currentTimeUs 当前时间（微秒）
     * @return 延迟毫秒数
     */
    private fun getNextRenderDelay(currentTimeUs: Long): Long {
        if (lastRenderTimeUs == 0L) return 0L
        
        val elapsedUs = currentTimeUs - lastRenderTimeUs
        val elapsedMs = elapsedUs / 1000
        
        val delay = frameIntervalMs - elapsedMs
        return if (delay > 0) delay else 0L
    }
    
    /**
     * 查询已解码的帧（立即返回）
     * @param frameIndex 帧索引
     * @return 已解码帧信息，如果未解码返回 null
     */
    fun getDecodedFrame(frameIndex: Int): FrameInfo? {
        return synchronized(cacheLock) {
            decodedFrameCache[frameIndex]
        }
    }
    
    /**
     * 清除帧缓存
     */
    private fun clearFrameCache() {
        synchronized(cacheLock) {
            decodedFrameCache.clear()
        }
    }

    
    fun seekToPosition(position: Float, shouldPauseAfter: Boolean = true) {
        if (!isPrepared || videoDuration <= 0) return
        
        val targetTimeMs = (position * videoDuration).toLong().coerceIn(0, videoDuration)
        val targetFrame = (targetTimeMs * videoFrameRate / 1000).toInt()
        
        seekToFrame(targetFrame)
    }
    
    fun getCurrentPosition(): Float {
        if (!isPrepared || videoDuration <= 0) return 0f
        return currentFrameIndex.toFloat() / videoFrameCount.toFloat()
    }
    
    fun getCurrentFrame(): Int = currentFrameIndex
    
    fun getFrameCount(): Int = videoFrameCount
    
    fun getTextureId(): Int = textureId
    
    fun isPrepared(): Boolean = isPrepared
    
    fun setOnFrameAvailableCallback(callback: () -> Unit) {
        onFrameAvailableCallback = callback
    }
    
    fun unbindVideoPlayer() {
        Log.d(TAG, "unbindVideoPlayer")
        mainHandler.removeCallbacksAndMessages(null)
        releaseDecoder()
        currentFrameIndex = -1
        lastDecodedFrameIndex = -1
    }
    
    fun release() {
        Log.d(TAG, "release")
        unbindVideoPlayer()
        
        if (textureId != 0) {
            GLES20.glDeleteTextures(1, intArrayOf(textureId), 0)
            textureId = 0
        }
        
        surfaceTexture?.release()
        surfaceTexture = null
        
        if (shaderProgram != 0) {
            GLES20.glDeleteProgram(shaderProgram)
            shaderProgram = 0
        }
    }
    
    private fun releaseDecoder() {
        Log.d(TAG, "releaseDecoder")
        
        // 清除帧缓存
        clearFrameCache()
        
        // 停止解码器
        try {
            mediaCodec?.stop()
        } catch (e: Exception) {
            Log.e(TAG, "stop decoder error: ${e.message}")
        }
        
        try {
            mediaCodec?.release()
        } catch (e: Exception) {
            Log.e(TAG, "release decoder error: ${e.message}")
        }
        mediaCodec = null
        
        // 释放 MediaExtractor
        try {
            mediaExtractor?.release()
        } catch (e: Exception) {
            Log.e(TAG, "release extractor error: ${e.message}")
        }
        mediaExtractor = null
        
        // 释放 Surface
        surface?.release()
        surface = null
        
        // 清空队列
        synchronized(inputBufferLock) {
            inputBufferQueue.clear()
        }
        synchronized(decodeLock) {
            decodeQueue.clear()
        }
        
        // 停止解码线程
        decodeHandler?.removeCallbacksAndMessages(null)
        decodeThread?.quitSafely()
        decodeThread = null
        decodeHandler = null
        
        isPrepared = false
        hasNotifiedPrepared = false
    }
    
    private fun createOESProgram(): Int {
        val vertexShader = """
            attribute vec4 aPosition;
            attribute vec2 aTexCoord;
            varying vec2 vTexCoord;
            uniform mat4 uTransform;
            void main() {
                gl_Position = aPosition;
                vTexCoord = (uTransform * vec4(aTexCoord, 0.0, 1.0)).xy;
            }
        """
        
        val fragmentShader = """
            #extension GL_OES_EGL_image_external : require
            precision mediump float;
            varying vec2 vTexCoord;
            uniform samplerExternalOES sTexture;
            void main() {
                gl_FragColor = texture2D(sTexture, vTexCoord);
            }
        """
        
        return createProgram(vertexShader, fragmentShader)
    }
    
    private fun createProgram(vertexSource: String, fragmentSource: String): Int {
        val vertexShader = loadShader(GLES20.GL_VERTEX_SHADER, vertexSource)
        val fragmentShader = loadShader(GLES20.GL_FRAGMENT_SHADER, fragmentSource)
        
        if (vertexShader == 0 || fragmentShader == 0) return 0
        
        val program = GLES20.glCreateProgram()
        GLES20.glAttachShader(program, vertexShader)
        GLES20.glAttachShader(program, fragmentShader)
        GLES20.glLinkProgram(program)
        
        val linkStatus = IntArray(1)
        GLES20.glGetProgramiv(program, GLES20.GL_LINK_STATUS, linkStatus, 0)
        
        if (linkStatus[0] == 0) {
            Log.e(TAG, "Failed to link program: ${GLES20.glGetProgramInfoLog(program)}")
            GLES20.glDeleteProgram(program)
            return 0
        }
        
        return program
    }
    
    private fun loadShader(type: Int, source: String): Int {
        val shader = GLES20.glCreateShader(type)
        GLES20.glShaderSource(shader, source)
        GLES20.glCompileShader(shader)
        
        val compiled = IntArray(1)
        GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, compiled, 0)
        
        if (compiled[0] == 0) {
            Log.e(TAG, "Failed to compile shader: ${GLES20.glGetShaderInfoLog(shader)}")
            GLES20.glDeleteShader(shader)
            return 0
        }
        
        return shader
    }
}
