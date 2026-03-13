package com.zeaze.tianyinwallpaper.service.raster

import android.content.Context
import android.graphics.SurfaceTexture
import android.net.Uri
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.SeekParameters
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 视频资源管理器
 * 参考自 vivo RVRes (e.java)
 * 负责视频解码和帧管理
 */
@OptIn(UnstableApi::class)
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
    
    private var exoPlayer: ExoPlayer? = null
    private var surfaceTexture: SurfaceTexture? = null
    private var textureId: Int = 0
    private var isPrepared = false
    private var hasNotifiedPrepared = false  // 标记是否已通知过准备完成
    
    private var videoDuration: Long = 0
    private var videoFrameCount: Int = 0
    private var videoFrameRate: Float = 30f
    private var currentFrameIndex: Int = 0
    
    private val transformMatrix = FloatArray(16)
    private val updateSurface = AtomicBoolean(false)
    
    private val mainHandler = Handler(Looper.getMainLooper())
    // 记录 ExoPlayer 创建时的线程 Handler
    private var playerThreadHandler: Handler? = null
    
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
        // 创建OES纹理
        val textures = IntArray(1)
        GLES20.glGenTextures(1, textures, 0)
        textureId = textures[0]
        
        Log.w(TAG, "initGL: textureId=$textureId")
        
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureId)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        
        // 创建SurfaceTexture
        surfaceTexture = SurfaceTexture(textureId)
        Log.w(TAG, "initGL: SurfaceTexture created: $surfaceTexture")
        surfaceTexture?.setOnFrameAvailableListener {
            updateSurface.set(true)
        }
        
        // 创建着色器程序
        shaderProgram = createOESProgram()
        Log.w(TAG, "initGL: shaderProgram=$shaderProgram")
        
        Log.w(TAG, "initGL: completed, textureId=$textureId")
        return textureId
    }
    
    fun loadVideo(videoPath: String) {
        Log.w(TAG, "loadVideo: $videoPath")
        releasePlayer()
        hasNotifiedPrepared = false  // 重置通知标志
            
        val st = surfaceTexture ?: run {
            Log.e(TAG, "loadVideo: SurfaceTexture not initialized!")
            return
        }
            
        Log.w(TAG, "loadVideo: SurfaceTexture ready, creating ExoPlayer...")
            
        // 记录当前线程（EGL 线程）的 Handler
        playerThreadHandler = if (Looper.myLooper() != null) Handler(Looper.myLooper()!!) else null
            
        try {
            val surface = android.view.Surface(st)
                
            exoPlayer = ExoPlayer.Builder(context).build().apply {
                setVideoSurface(surface)
                volume = 0f
                setSeekParameters(SeekParameters.EXACT)
                repeatMode = Player.REPEAT_MODE_OFF
                playWhenReady = false
                
                val mediaItem = MediaItem.fromUri(Uri.parse(videoPath))
                setMediaItem(mediaItem)
                
                addListener(object : Player.Listener {
                    override fun onPlaybackStateChanged(state: Int) {
                        Log.w(TAG, "onPlaybackStateChanged: state=$state, hasNotifiedPrepared=$hasNotifiedPrepared")
                        if (state == Player.STATE_READY && !hasNotifiedPrepared) {
                            val dur = duration
                            if (dur != C.TIME_UNSET && dur > 0) {
                                videoDuration = dur
                                videoFrameCount = (dur * videoFrameRate / 1000).toInt()
                                
                                val vw = videoSize.width
                                val vh = videoSize.height
                                
                                Log.w(TAG, "Video prepared: duration=${dur}ms, frames=$videoFrameCount, size=${vw}x$vh")
                                
                                hasNotifiedPrepared = true
                                callback.onVideoPrepared(videoFrameCount, videoDuration, vw, vh)
                                
                                isPrepared = true
                                currentFrameIndex = 0
                            } else {
                                Log.e(TAG, "Video prepared but invalid duration: $dur")
                            }
                        }
                    }
                    
                    override fun onPlayerError(error: PlaybackException) {
                        Log.e(TAG, "Player error: ${error.message}")
                        callback.onVideoError(error.message ?: "Unknown error")
                    }
                })
                
                prepare()
                Log.w(TAG, "loadVideo: ExoPlayer preparing...")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load video", e)
            callback.onVideoError(e.message ?: "Failed to load video")
        }
    }
    
    fun updateTexImage(): Boolean {
        if (updateSurface.getAndSet(false)) {
            try {
                surfaceTexture?.updateTexImage()
                surfaceTexture?.getTransformMatrix(transformMatrix)
                return true
            } catch (e: Exception) {
                Log.w(TAG, "updateTexImage failed", e)
            }
        }
        return false
    }
    
    fun getTransformMatrix(): FloatArray = transformMatrix
    
    fun seekToFrame(frameIndex: Int) {
        if (!isPrepared) return
        
        val targetFrame = frameIndex.coerceIn(0, videoFrameCount - 1)
        if (targetFrame == currentFrameIndex) return
        
        val targetTimeMs = (targetFrame * 1000 / videoFrameRate).toLong()
        exoPlayer?.seekTo(targetTimeMs)
        currentFrameIndex = targetFrame
    }
    
    fun seekToPosition(position: Float) {
        if (!isPrepared || videoDuration <= 0) return
        
        val targetTimeMs = (position * videoDuration).toLong().coerceIn(0, videoDuration)
        exoPlayer?.seekTo(targetTimeMs)
        currentFrameIndex = (targetTimeMs * videoFrameRate / 1000).toInt()
    }
    
    fun getCurrentFrame(): Int = currentFrameIndex
    
    fun getFrameCount(): Int = videoFrameCount
    
    fun getTextureId(): Int = textureId
    
    fun isPrepared(): Boolean = isPrepared
    
    fun unbindVideoPlayer() {
        Log.d(TAG, "unbindVideoPlayer")
        mainHandler.removeCallbacksAndMessages(null)
        releasePlayer()
        currentFrameIndex = 0
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
        val player = exoPlayer ?: return
        val handler = playerThreadHandler
        
        // 检查是否在正确的线程
        if (handler != null && Looper.myLooper() != handler.looper) {
            // 不在正确的线程，post 到创建线程执行
            handler.post {
                try {
                    player.release()
                } catch (e: Exception) {
                    Log.e(TAG, "releasePlayer failed: ${e.message}")
                }
            }
        } else {
            // 已经在正确的线程或没有记录的 handler，直接释放
            player.release()
        }
        
        exoPlayer = null
        playerThreadHandler = null
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
