package com.zeaze.tianyinwallpaper.service.raster

import android.content.Context
import android.graphics.SurfaceTexture
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.os.SystemClock
import android.util.Log
import android.view.Surface
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 视频资源管理器
 * 使用 MediaExtractor + MediaCodec 实现同步帧精确解码
 *
 * 核心优势（相比 MediaPlayer seekTo）：
 *   - 前向顺序解码：目标在前方 1~5 帧时只需 1~5ms
 *   - 仅在回退或大跳转时才 seek 关键帧
 *   - 时间预算：每次 seekToFrame 最多 28ms，超时渲染最近已解码帧
 *   - 零 start/pause 开销
 */
class RVRes(
    private val context: Context,
    private val callback: Callback
) {
    companion object {
        private const val TAG = "RVRes"
        /** 每次 seekToFrame() 调用的最大解码时间（ms） */
        private const val DECODE_BUDGET_MS = 28L
        /** 超过此帧距，使用 extractor.seekTo 而非顺序解码 */
        private const val FORWARD_THRESHOLD = 90
    }

    interface Callback {
        fun onVideoFrameReady(frameIndex: Int, textureId: Int, transformMatrix: FloatArray)
        fun onVideoPrepared(frameCount: Int, duration: Long, width: Int, height: Int)
        fun onVideoError(message: String)
        fun onSeekComplete()
    }

    // ── 解码器 ───────────────────────────────────────────────────────────────
    private var extractor: MediaExtractor? = null
    private var codec: MediaCodec? = null
    private var codecInputEos = false
    private var videoTrackIndex = -1

    // ── Surface / 纹理 ──────────────────────────────────────────────────────
    private var surfaceTexture: SurfaceTexture? = null
    private var codecSurface: Surface? = null
    private var textureId: Int = 0

    // ── 状态 ────────────────────────────────────────────────────────────────
    private var isPrepared = false

    // ── 视频信息 ────────────────────────────────────────────────────────────
    private var videoPath: String = ""
    private var videoDuration: Long = 0          // ms
    private var videoFrameCount: Int = 0
    private var videoFrameRate: Float = 30f
    private var videoWidth: Int = 0
    private var videoHeight: Int = 0
    private var currentFrameIndex: Int = -1      // 最近渲染到 SurfaceTexture 的帧索引
    private var codecPosition: Int = -1          // codec 实际解码到的帧位置（可能领先于 currentFrameIndex）
    private var backwardRecoveryTarget: Int = -1 // >= 0 表示正在从后退 seek 中恢复，值为目标帧号

    // ── 纹理矩阵 ───────────────────────────────────────────────────────────
    private val transformMatrix = FloatArray(16)
    private val frameAvailable = AtomicBoolean(false)

    // ── 帧可用回调 ─────────────────────────────────────────────────────────
    private var onFrameAvailableCallback: (() -> Unit)? = null

    // ── GL 缓冲区 ──────────────────────────────────────────────────────────
    private val vertexBuffer: FloatBuffer
    private val texCoordBuffer: FloatBuffer
    private var shaderProgram: Int = 0

    init {
        val vertexData = floatArrayOf(
            -1f, -1f,  1f, -1f,
            -1f,  1f,  1f,  1f
        )
        vertexBuffer = ByteBuffer.allocateDirect(vertexData.size * 4)
            .order(ByteOrder.nativeOrder())
            .asFloatBuffer()
            .put(vertexData)
        vertexBuffer.position(0)

        val texCoordData = floatArrayOf(
            0f, 0f,  1f, 0f,
            0f, 1f,  1f, 1f
        )
        texCoordBuffer = ByteBuffer.allocateDirect(texCoordData.size * 4)
            .order(ByteOrder.nativeOrder())
            .asFloatBuffer()
            .put(texCoordData)
        texCoordBuffer.position(0)

        android.opengl.Matrix.setIdentityM(transformMatrix, 0)
    }

    // ── GL 初始化 ──────────────────────────────────────────────────────────

    fun initGL(): Int {
        Log.w(TAG, "initGL: starting...")

        val textures = IntArray(1)
        GLES20.glGenTextures(1, textures, 0)
        textureId = textures[0]

        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureId)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)

        surfaceTexture = SurfaceTexture(textureId)
        surfaceTexture?.setOnFrameAvailableListener {
            frameAvailable.set(true)
            onFrameAvailableCallback?.invoke()
        }

        shaderProgram = createOESProgram()
        Log.w(TAG, "initGL: completed, textureId=$textureId, shader=$shaderProgram")
        return textureId
    }

    // ── 视频加载（EGL 线程调用）──────────────────────────────────────────────

    fun loadVideo(path: String) {
        Log.w(TAG, "loadVideo: $path")
        videoPath = path
        releasePlayer()

        val st = surfaceTexture ?: run {
            Log.e(TAG, "loadVideo: SurfaceTexture not initialized!")
            return
        }

        try {
            // ── 1. 设置 MediaExtractor ──
            val ext = MediaExtractor()
            if (path.startsWith("content://")) {
                ext.setDataSource(context, android.net.Uri.parse(path), null)
            } else {
                ext.setDataSource(path)
            }

            // ── 2. 查找视频轨道 ──
            var trackIdx = -1
            var format: MediaFormat? = null
            for (i in 0 until ext.trackCount) {
                val fmt = ext.getTrackFormat(i)
                val mime = fmt.getString(MediaFormat.KEY_MIME) ?: continue
                if (mime.startsWith("video/")) {
                    trackIdx = i
                    format = fmt
                    break
                }
            }
            if (trackIdx < 0 || format == null) {
                ext.release()
                callback.onVideoError("No video track found")
                return
            }

            ext.selectTrack(trackIdx)
            videoTrackIndex = trackIdx

            // ── 3. 提取视频参数 ──
            val mime = format.getString(MediaFormat.KEY_MIME)!!
            videoWidth = format.getInteger(MediaFormat.KEY_WIDTH)
            videoHeight = format.getInteger(MediaFormat.KEY_HEIGHT)
            // KEY_DURATION 在 MediaFormat 中是微秒
            videoDuration = if (format.containsKey(MediaFormat.KEY_DURATION))
                format.getLong(MediaFormat.KEY_DURATION) / 1000 else 0L   // μs → ms

            // ── 3.5 扫描实际帧数（比 duration*fps 公式可靠）──
            var actualFrameCount = 0
            while (ext.getSampleTime() >= 0) {
                actualFrameCount++
                if (!ext.advance()) break
            }
            ext.seekTo(0, MediaExtractor.SEEK_TO_PREVIOUS_SYNC) // 重置到起始位置
            videoFrameCount = actualFrameCount.coerceAtLeast(1)

            // 从实际帧数反推帧率（比 KEY_FRAME_RATE 更准确）
            videoFrameRate = if (videoDuration > 0 && videoFrameCount > 1)
                (videoFrameCount.toFloat() * 1000f) / videoDuration.toFloat()
            else if (format.containsKey(MediaFormat.KEY_FRAME_RATE))
                format.getInteger(MediaFormat.KEY_FRAME_RATE).toFloat()
            else 30f

            // ── 4. 创建 MediaCodec（输出到 SurfaceTexture）──
            codecSurface = Surface(st)
            val dec = MediaCodec.createDecoderByType(mime)
            dec.configure(format, codecSurface, null, 0)
            dec.start()

            extractor = ext
            codec = dec
            codecInputEos = false
            currentFrameIndex = -1
            codecPosition = -1

            isPrepared = true
            Log.w(TAG, "loadVideo OK: duration=${videoDuration}ms, frames=$videoFrameCount, " +
                    "size=${videoWidth}x$videoHeight, fps=$videoFrameRate, mime=$mime")

            callback.onVideoPrepared(videoFrameCount, videoDuration, videoWidth, videoHeight)

        } catch (e: Exception) {
            Log.e(TAG, "loadVideo failed", e)
            callback.onVideoError(e.message ?: "Failed to load video")
        }
    }

    // ── 帧精确 seek（EGL 线程调用，同步解码）───────────────────────────────

    /**
     * 同步解码到目标帧。
     *
     * 全关键帧视频模式下（配合 KeyframeTranscoder）：
     *   - 任意方向 seek 只需解码 0~2 帧，2~3ms 完成
     *   - 前向小步进直接顺序解码，无需 seek
     *
     * 非全关键帧降级模式下：
     *   - 后退/大跳转仍需从关键帧顺序解码，但有超时保护
     */
    fun seekToFrame(frameIndex: Int, preDecodeCount: Int = 0) {
        if (!isPrepared) return
        val ext = extractor ?: return
        val dec = codec ?: return

        val target = frameIndex.coerceIn(0, videoFrameCount - 1)
        if (target == currentFrameIndex) return

        // ── 判断是否需要 extractor seek ──
        // 全关键帧视频：seek 极快，仅对小幅前进跳过 seek
        // 非全关键帧视频：seek 后需要从关键帧解码，但超时保护
        val needSeek = codecPosition < 0
                || target < codecPosition                  // 后退 → 必须 seek
                || (target - codecPosition) > 3            // 前进超过 3 帧 → seek 更快

        if (needSeek) {
            ext.seekTo(frameToTimeUs(target), MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
            dec.flush()
            codecInputEos = false
            codecPosition = -1
            Log.d(TAG, "seekToFrame: seek for target=$target")
        }

        // ── 同步解码循环 ──
        val startTime = SystemClock.elapsedRealtime()
        val info = MediaCodec.BufferInfo()

        while (true) {
            if (SystemClock.elapsedRealtime() - startTime > DECODE_BUDGET_MS) {
                Log.d(TAG, "seekToFrame: budget expired, codecPos=$codecPosition, target=$target")
                break
            }

            // ── 喂入压缩数据 ──
            if (!codecInputEos) {
                val inIdx = dec.dequeueInputBuffer(0)
                if (inIdx >= 0) {
                    val buf = dec.getInputBuffer(inIdx)!!
                    val size = ext.readSampleData(buf, 0)
                    if (size >= 0) {
                        dec.queueInputBuffer(inIdx, 0, size, ext.sampleTime, 0)
                        ext.advance()
                    } else {
                        dec.queueInputBuffer(inIdx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                        codecInputEos = true
                    }
                }
            }

            // ── 取出解码帧 ──
            val outIdx = dec.dequeueOutputBuffer(info, 5_000)
            when {
                outIdx >= 0 -> {
                    val frame = ptsToFrame(info.presentationTimeUs)

                    if (frame >= target) {
                        // ★ 到达目标帧 → 渲染
                        dec.releaseOutputBuffer(outIdx, true)
                        currentFrameIndex = frame
                        codecPosition = frame
                        frameAvailable.set(true)
                        break
                    } else {
                        // 中间帧 → 跳过（不渲染）
                        dec.releaseOutputBuffer(outIdx, false)
                        codecPosition = frame
                    }
                }
                outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> { /* 正常 */ }
                outIdx == MediaCodec.INFO_TRY_AGAIN_LATER -> {
                    if (codecInputEos) break
                }
            }
        }

        callback.onSeekComplete()
    }

    // ── 纹理更新（EGL 线程，draw 时调用）───────────────────────────────────

    fun updateTexImage(): Boolean {
        if (frameAvailable.get()) {
            frameAvailable.set(false)
            try {
                surfaceTexture?.updateTexImage()
                surfaceTexture?.getTransformMatrix(transformMatrix)
                callback.onVideoFrameReady(currentFrameIndex, textureId, transformMatrix)
                return true
            } catch (e: Exception) {
                Log.e(TAG, "updateTexImage error: ${e.message}")
                return false
            }
        }
        return currentFrameIndex >= 0
    }

    // ── 辅助方法 ───────────────────────────────────────────────────────────

    /** 帧索引 → 微秒 */
    private fun frameToTimeUs(frame: Int): Long =
        (frame * 1_000_000.0 / videoFrameRate).toLong()

    /** 微秒 → 帧索引 */
    private fun ptsToFrame(ptsUs: Long): Int =
        ((ptsUs * videoFrameRate) / 1_000_000.0).toInt().coerceIn(0, videoFrameCount - 1)

    fun getTransformMatrix(): FloatArray = transformMatrix
    fun getCurrentPosition(): Float {
        if (!isPrepared || videoFrameCount <= 0) return 0f
        return currentFrameIndex.toFloat() / videoFrameCount.toFloat()
    }
    fun getCurrentFrame(): Int = currentFrameIndex
    fun getFrameCount(): Int = videoFrameCount
    fun getTextureId(): Int = textureId
    fun isPrepared(): Boolean = isPrepared

    fun seekToPosition(position: Float, shouldPauseAfter: Boolean = true) {
        if (!isPrepared || videoDuration <= 0) return
        val frame = (position * videoFrameCount).toInt()
        seekToFrame(frame)
    }

    fun setTargetFrameRate(fps: Int) { /* no-op */ }

    fun setOnFrameAvailableCallback(callback: () -> Unit) {
        onFrameAvailableCallback = callback
    }

    /** MediaCodec 无 play/pause 概念，此方法为 no-op */
    fun ensurePaused() { /* no-op — codec 只在 seekToFrame 时才解码 */ }

    // ── 释放 ───────────────────────────────────────────────────────────────

    fun unbindVideoPlayer() {
        Log.d(TAG, "unbindVideoPlayer")
        releasePlayer()
        currentFrameIndex = -1
        codecPosition = -1
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

    private fun releasePlayer() {
        Log.d(TAG, "releasePlayer")
        try { codec?.stop() } catch (_: Exception) {}
        try { codec?.release() } catch (_: Exception) {}
        codec = null

        try { extractor?.release() } catch (_: Exception) {}
        extractor = null

        codecSurface?.release()
        codecSurface = null

        isPrepared = false
        currentFrameIndex = -1
        codecPosition = -1
        codecInputEos = false
    }

    // ── 着色器 ─────────────────────────────────────────────────────────────

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
        val vs = loadShader(GLES20.GL_VERTEX_SHADER, vertexSource)
        val fs = loadShader(GLES20.GL_FRAGMENT_SHADER, fragmentSource)
        if (vs == 0 || fs == 0) return 0

        val program = GLES20.glCreateProgram()
        GLES20.glAttachShader(program, vs)
        GLES20.glAttachShader(program, fs)
        GLES20.glLinkProgram(program)

        val linkStatus = IntArray(1)
        GLES20.glGetProgramiv(program, GLES20.GL_LINK_STATUS, linkStatus, 0)
        if (linkStatus[0] == 0) {
            Log.e(TAG, "Link failed: ${GLES20.glGetProgramInfoLog(program)}")
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
            Log.e(TAG, "Compile failed: ${GLES20.glGetShaderInfoLog(shader)}")
            GLES20.glDeleteShader(shader)
            return 0
        }
        return shader
    }
}
