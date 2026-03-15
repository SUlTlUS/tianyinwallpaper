package com.zeaze.tianyinwallpaper.service.raster

import android.content.Context
import android.graphics.SurfaceTexture
import android.media.MediaFormat
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Surface
import net.protyposis.android.mediaplayer.FileSource
import net.protyposis.android.mediaplayer.MediaPlayer
import net.protyposis.android.mediaplayer.MediaSource
import net.protyposis.android.mediaplayer.UriSource
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

/**
 * 视频资源管理器
 * 使用 MediaPlayer-Extended 实现帧精确 seek
 * 支持本地文件和 content:// URI
 */
class RVRes(
    private val context: Context,
    private val callback: Callback
) {
    companion object {
        private const val TAG = "RVRes"
    }

    interface Callback {
        fun onVideoFrameReady(frameIndex: Int, textureId: Int, transformMatrix: FloatArray)
        fun onVideoPrepared(frameCount: Int, duration: Long, width: Int, height: Int)
        fun onVideoError(message: String)
    }

    // MediaPlayer-Extended 实例
    private var mediaPlayer: MediaPlayer? = null
    
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
    private var videoWidth: Int = 0
    private var videoHeight: Int = 0
    private var currentFrameIndex: Int = -1
    private var targetFrameIndex: Int = -1

    // 纹理矩阵
    private val transformMatrix = FloatArray(16)
    private var frameAvailable = false

    // 主线程 Handler
    private val mainHandler = Handler(Looper.getMainLooper())

    // 帧可用回调
    private var onFrameAvailableCallback: (() -> Unit)? = null

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

        // 设置帧可用监听器 - 不指定 Handler，只设置标志
        // updateTexImage() 必须在 EGL 线程调用，不能在这里调用
        surfaceTexture?.setOnFrameAvailableListener({ 
            frameAvailable = true
            Log.d(TAG, "onFrameAvailable: textureId=$textureId, targetFrame=$targetFrameIndex")
            onFrameAvailableCallback?.invoke()
        })

        // 创建着色器程序
        shaderProgram = createOESProgram()
        Log.w(TAG, "initGL: shaderProgram=$shaderProgram")

        Log.w(TAG, "initGL: completed, textureId=$textureId")
        return textureId
    }

    fun loadVideo(videoPath: String) {
        Log.w(TAG, "loadVideo: $videoPath")
        releasePlayer()
        hasNotifiedPrepared = false

        val st = surfaceTexture ?: run {
            Log.e(TAG, "loadVideo: SurfaceTexture not initialized!")
            return
        }

        Log.w(TAG, "loadVideo: SurfaceTexture ready, creating MediaPlayer...")

        // 创建 Surface
        surface = Surface(st)

        // 创建 MediaPlayer-Extended
        mediaPlayer = MediaPlayer()
        
        // 设置帧精确 seek 模式
        mediaPlayer?.setSeekMode(MediaPlayer.SeekMode.EXACT)
        Log.w(TAG, "loadVideo: setSeekMode to EXACT")

        // 设置 Surface
        mediaPlayer?.setSurface(surface)

        // 设置监听器
        mediaPlayer?.setOnPreparedListener {
            Log.w(TAG, "MediaPlayer onPrepared")
            onMediaPlayerPrepared()
        }

        mediaPlayer?.setOnVideoSizeChangedListener { mp, width, height ->
            Log.w(TAG, "MediaPlayer onVideoSizeChanged: ${width}x$height")
            videoWidth = width
            videoHeight = height
        }

        mediaPlayer?.setOnErrorListener { mp, what, extra ->
            Log.e(TAG, "MediaPlayer onError: what=$what, extra=$extra")
            callback.onVideoError("MediaPlayer error: what=$what, extra=$extra")
            true
        }

        mediaPlayer?.setOnSeekCompleteListener {
            // seek 完成后更新当前帧索引
            currentFrameIndex = targetFrameIndex
            frameAvailable = true
            Log.d(TAG, "MediaPlayer onSeekComplete, frame=$currentFrameIndex")
        }

        try {
            // 设置数据源
            val mediaSource: MediaSource = if (videoPath.startsWith("content://")) {
                Log.w(TAG, "loadVideo: using UriSource for content URI")
                val uri = android.net.Uri.parse(videoPath)
                UriSource(context, uri)
            } else {
                Log.w(TAG, "loadVideo: using FileSource")
                FileSource(File(videoPath))
            }
            
            mediaPlayer?.setDataSource(mediaSource)
            
            // 异步准备
            Log.w(TAG, "loadVideo: calling prepareAsync()")
            mediaPlayer?.prepareAsync()
            
        } catch (e: Exception) {
            Log.e(TAG, "loadVideo failed", e)
            callback.onVideoError(e.message ?: "Failed to load video")
        }
    }

    private fun onMediaPlayerPrepared() {
        val mp = mediaPlayer ?: return
        
        try {
            // 获取视频信息
            videoDuration = mp.duration.toLong()
            videoWidth = mp.videoWidth
            videoHeight = mp.videoHeight
            
            // 尝试从视频获取帧率（MediaPlayer-Extended 可能不直接提供）
            // 使用 MediaExtractor 获取真实帧率
            try {
                val extractor = android.media.MediaExtractor()
                // 暂时使用默认值，后续可通过其他方式获取
                videoFrameRate = 30f
            } catch (e: Exception) {
                videoFrameRate = 30f
            }
            
            // 计算帧数 - 使用实际视频时长和帧率
            videoFrameCount = if (videoDuration > 0 && videoFrameRate > 0) {
                ((videoDuration * videoFrameRate) / 1000).toInt().coerceAtLeast(1)
            } else {
                1
            }

            Log.w(TAG, "Video info: duration=${videoDuration}ms, frames=$videoFrameCount, size=${videoWidth}x$videoHeight, fps=$videoFrameRate")

            isPrepared = true

            // 通知准备完成
            hasNotifiedPrepared = true
            callback.onVideoPrepared(videoFrameCount, videoDuration, videoWidth, videoHeight)

            // 初始化到第一帧
            targetFrameIndex = 0
            currentFrameIndex = 0
            
            // MediaPlayer-Extended 在暂停状态下 seek 后不会渲染帧
            // 方案：start() → seekTo() → pause()
            mp.start()
            mp.seekTo(0)
            mp.pause()  // 初始化后立即暂停，防止自动播放

        } catch (e: Exception) {
            Log.e(TAG, "onMediaPlayerPrepared error", e)
            callback.onVideoError(e.message ?: "Failed to get video info")
        }
    }

    fun updateTexImage(): Boolean {
        if (frameAvailable) {
            frameAvailable = false
            
            // 在 EGL 线程中更新纹理
            try {
                surfaceTexture?.updateTexImage()
                surfaceTexture?.getTransformMatrix(transformMatrix)
                
                // 通知帧已准备好
                callback.onVideoFrameReady(currentFrameIndex, textureId, transformMatrix)
                
                Log.d(TAG, "updateTexImage: frame updated, frameIndex=$currentFrameIndex")
                return true
            } catch (e: Exception) {
                Log.e(TAG, "updateTexImage error: ${e.message}")
                return false
            }
        }
        
        // 如果已经有帧数据，返回 true
        if (currentFrameIndex >= 0) {
            return true
        }
        
        return false
    }

    fun getTransformMatrix(): FloatArray = transformMatrix

    fun seekToFrame(frameIndex: Int, preDecodeCount: Int = 0) {
        if (!isPrepared) {
            Log.w(TAG, "seekToFrame: not prepared, skipping")
            return
        }

        val targetFrame = frameIndex.coerceIn(0, videoFrameCount - 1)
        Log.w(TAG, "seekToFrame: targetFrame=$targetFrame, currentFrameIndex=$currentFrameIndex")

        if (targetFrame == currentFrameIndex && preDecodeCount == 0) {
            Log.d(TAG, "seekToFrame: same frame, skipping")
            return
        }

        targetFrameIndex = targetFrame
        // 直接更新当前帧索引（seek 是帧精确的）
        currentFrameIndex = targetFrame
        
        // 计算目标时间（毫秒）
        val targetTimeMs = if (videoFrameRate > 0) {
            (targetFrame.toLong() * 1000 / videoFrameRate.toLong())
        } else {
            (targetFrame.toLong() * videoDuration / videoFrameCount.toLong())
        }

        Log.w(TAG, "seekToFrame: seeking to ${targetTimeMs}ms for frame $targetFrame")
        
        // MediaPlayer-Extended 在暂停状态下 seek 后不会渲染帧
        // 解决方案：start() → seekTo() → pause()
        // 1. start() 让播放器进入播放状态（必须，否则 seek 不渲染）
        // 2. seekTo() 跳转到目标帧
        // 3. pause() 立即暂停，防止自动播放
        try {
            mediaPlayer?.start()
            mediaPlayer?.seekTo(targetTimeMs)
            // seek 后立即暂停，防止自动播放
            mediaPlayer?.pause()
        } catch (e: Exception) {
            Log.e(TAG, "seekToFrame error: ${e.message}")
        }
    }

    fun seekToPosition(position: Float, shouldPauseAfter: Boolean = true) {
        if (!isPrepared || videoDuration <= 0) return

        val targetTimeMs = (position * videoDuration).toLong().coerceIn(0, videoDuration)
        val targetFrame = if (videoFrameRate > 0) {
            (targetTimeMs * videoFrameRate / 1000).toInt()
        } else {
            (position * videoFrameCount).toInt()
        }

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

    fun setTargetFrameRate(fps: Int) {
        Log.d(TAG, "setTargetFrameRate: $fps fps (no-op for MediaPlayer-Extended)")
    }

    fun setOnFrameAvailableCallback(callback: () -> Unit) {
        onFrameAvailableCallback = callback
    }

    fun unbindVideoPlayer() {
        Log.d(TAG, "unbindVideoPlayer")
        mainHandler.removeCallbacksAndMessages(null)
        releasePlayer()
        currentFrameIndex = -1
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

        try {
            mediaPlayer?.stop()
        } catch (e: Exception) {
            Log.e(TAG, "stop player error: ${e.message}")
        }

        // MediaPlayer-Extended 没有直接的 release() 方法
        // 调用 stop() 后不再使用该实例即可
        mediaPlayer = null

        // 释放 Surface
        surface?.release()
        surface = null

        isPrepared = false
        hasNotifiedPrepared = false
        currentFrameIndex = -1
        targetFrameIndex = -1
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
