package com.zeaze.tianyinwallpaper.service.raster

import android.content.Context
import android.graphics.Rect
import android.opengl.GLES20
import android.opengl.Matrix
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.util.Size

/**
 * 视频光栅效果控制器
 * 参考自 vivo RVEffectPreCtrl (b.java)
 * 管理视频光栅效果的渲染流程
 */
class RVEffectPreCtrl(
    private val context: Context,
    private val callback: Callback? = null
) {
    companion object {
        private const val TAG = "RVEffectPreCtrl"
    }
    
    interface Callback {
        fun onPrepared(frameCount: Int, duration: Long)
        fun onFrameReady()
        fun onError(message: String)
    }
    
    // 核心组件
    private var rvRes: RVRes? = null
    private var renderNode: VideoRasterRenderNode? = null
    private var configure: RVEffectConfigure? = null
    
    // 渲染参数
    private val renderParams = RasterVideoPreRenderParamBean()
    private val mainHandler = Handler(Looper.getMainLooper())
    
    // 状态
    private var isPrepared = false
    private var isVisible = true
    private var alpha: Float = 1f
    private var process: Float = 0f
    private var videoFrameIndex: Int = 0
    
    // 折叠屏相关
    private var isFoldDevice = false
    private var isSmallScreen = false
    
    // 矩阵
    private val matrixManager = MatrixManager()
    
    inner class MatrixManager {
        val modelMatrix = FloatArray(16)
        val viewMatrix = FloatArray(16)
        val projMatrix = FloatArray(16)
        val mvpMatrix = FloatArray(16)
        val stackMatrix = FloatArray(16)
        
        init {
            Matrix.setIdentityM(modelMatrix, 0)
            Matrix.setIdentityM(viewMatrix, 0)
            Matrix.setIdentityM(projMatrix, 0)
            Matrix.setIdentityM(mvpMatrix, 0)
            Matrix.setIdentityM(stackMatrix, 0)
        }
        
        fun push() {
            System.arraycopy(mvpMatrix, 0, stackMatrix, 0, 16)
        }
        
        fun pop() {
            System.arraycopy(stackMatrix, 0, mvpMatrix, 0, 16)
        }
    }
    
    fun init() {
        Log.w(TAG, "init: starting...")
        
        configure = RVEffectConfigure()
        
        rvRes = RVRes(context, object : RVRes.Callback {
            override fun onVideoFrameReady(frameIndex: Int, textureId: Int, transformMatrix: FloatArray) {
                videoFrameIndex = frameIndex
                callback?.onFrameReady()
            }
            
            override fun onVideoPrepared(frameCount: Int, duration: Long, width: Int, height: Int) {
                Log.w(TAG, "onVideoPrepared: frames=$frameCount, duration=$duration, size=${width}x$height")
                configure?.apply {
                    videoFramesCount = frameCount
                    videoDuration = duration
                    imageSize = Size(width, height)
                }
                
                isPrepared = true
                callback?.onPrepared(frameCount, duration)
            }
            
            override fun onVideoError(message: String) {
                Log.e(TAG, "onVideoError: $message")
                callback?.onError(message)
            }
        })
        
        renderNode = VideoRasterRenderNode(configure!!)
        
        // 初始化GL资源
        Log.w(TAG, "init: calling rvRes.initGL()...")
        val texId = rvRes?.initGL() ?: 0
        renderParams.texIds = texId
        configure?.textureId = texId
        
        Log.w(TAG, "init: textureId=$texId, initializing shader...")
        renderNode?.initShader()
        
        Log.w(TAG, "init: completed")
    }
    
    fun loadSource(videoPath: String) {
        Log.d(TAG, "loadSource: $videoPath")
        renderParams.videoPath = videoPath
        rvRes?.loadVideo(videoPath)
    }
    
    fun loadSourceFromParams(params: RasterVideoPreRenderParamBean) {
        Log.w(TAG, "loadSourceFromParams: videoPath=${params.videoPath}")
        
        // 更新渲染参数
        updateRenderParams(params)
        
        // 更新配置
        configure?.apply {
            textureId = params.texIds
            imageSize = params.texImageSize
            videoPath = params.videoPath
            imagePath = params.videoFirstFrame
            videoFramesCount = params.videoFrameNum
            videoDuration = params.videoTime
            resourceFrom = params.videoResFrom
            updateRasterParams(params.videoTime)
        }
        
        // 更新屏幕尺寸
        updateScreenSize(params)
        
        // 加载视频
        params.videoPath?.let { 
            Log.w(TAG, "loadSourceFromParams: calling rvRes.loadVideo()")
            rvRes?.loadVideo(it) 
        }
    }
    
    fun updateRenderParams(params: RasterVideoPreRenderParamBean) {
        params.inImageRect?.let { renderParams.inImageRect = it }
        params.outImageRect?.let { renderParams.outImageRect = it }
        params.texImageSize?.let { renderParams.texImageSize = it }
        params.inScreenSize?.let { renderParams.inScreenSize = it }
        params.outScreenSize?.let { renderParams.outScreenSize = it }
        params.videoPath?.let { renderParams.videoPath = it }
        params.videoFirstFrame?.let { renderParams.videoFirstFrame = it }
        params.videoFrameNum.let { renderParams.videoFrameNum = it }
        params.videoFrameRate.let { renderParams.videoFrameRate = it }
        params.videoTime.let { renderParams.videoTime = it }
    }
    
    fun updateScreenSize(params: RasterVideoPreRenderParamBean) {
        val cfg = configure ?: return
        
        // 设置内屏尺寸
        params.inScreenSize?.let {
            cfg.setImageDesignScreenWidth(it.width, RVEffectConfigure.SCREEN_TYPE_FOLD_LARGER)
            cfg.setImageDesignScreenHeight(it.height, RVEffectConfigure.SCREEN_TYPE_FOLD_LARGER)
            cfg.setImageDesignScreenWidth(it.width)
            cfg.setImageDesignScreenHeight(it.height)
        }
        
        // 设置外屏尺寸
        params.outScreenSize?.let {
            cfg.setImageDesignScreenWidth(it.width, RVEffectConfigure.SCREEN_TYPE_FOLD_SMALLER)
            cfg.setImageDesignScreenHeight(it.height, RVEffectConfigure.SCREEN_TYPE_FOLD_SMALLER)
        }
        
        // 计算DumpInfo
        val dumpInfo = convertRVDumpInfo(
            params.inImageRect,
            params.texImageSize,
            params.texImageSize,
            params.inScreenSize
        )
        cfg.dumpInfo = dumpInfo
        
        val dumpInfoSecond = convertRVDumpInfo(
            params.outImageRect,
            params.texImageSize,
            params.texImageSize,
            params.outScreenSize
        )
        cfg.dumpInfoSecond = dumpInfoSecond
        
        // 更新渲染节点
        renderNode?.updateDumpInfo(dumpInfo)
    }
    
    private fun convertRVDumpInfo(
        imageRect: Rect?,
        texSize: Size?,
        imageSize: Size?,
        screenSize: Size?
    ): RVEffectConfigure.RVDumpInfo {
        val dumpInfo = RVEffectConfigure.RVDumpInfo()
        
        if (imageRect == null || texSize == null || imageSize == null || screenSize == null) {
            return dumpInfo
        }
        
        // 计算缩放比例
        val texWidth = texSize.width.toFloat()
        val texHeight = texSize.height.toFloat()
        val screenW = screenSize.width.toFloat()
        val screenH = screenSize.height.toFloat()
        
        val imageW = imageRect.width().toFloat()
        val imageH = imageRect.height().toFloat()
        
        dumpInfo.scaleX = screenW / imageW
        dumpInfo.scaleY = screenH / imageH
        dumpInfo.posX = imageRect.left.toFloat() / texWidth
        dumpInfo.posY = imageRect.top.toFloat() / texHeight
        
        return dumpInfo
    }
    
    fun onUpdateAnimationData() {
        if (!isPrepared) return
        
        val frameIndex = rvRes?.getCurrentFrame() ?: 0
        
        Log.d(TAG, "onUpdateAnimationData: process=$process, alpha=$alpha, visible=$isVisible, frameIndex=$frameIndex")
        
        // 更新渲染参数
        renderNode?.setProcess(process)
        renderNode?.setAlpha(alpha)
        renderNode?.setVisible(isVisible)
    }
    
    private var frameCounter = 0L
    
    fun onDrawFrame(surfaceWidth: Int, surfaceHeight: Int) {
        if (!isPrepared) {
            frameCounter++
            if (frameCounter % 60 == 0L) {
                Log.w(TAG, "onDrawFrame: not prepared yet (frame $frameCounter)")
            }
            return
        }
        
        // 设置视口
        GLES20.glViewport(0, 0, surfaceWidth, surfaceHeight)
        
        // 更新视频纹理帧
        val textureUpdated = rvRes?.updateTexImage() ?: false
        
        // 清除缓冲区
        GLES20.glClearColor(0f, 0f, 0f, 1f)
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
        
        val texId = rvRes?.getTextureId() ?: 0
        val transformMatrix = rvRes?.getTransformMatrix() ?: FloatArray(16)
        
        frameCounter++
        if (frameCounter % 60 == 0L) {
            Log.w(TAG, "onDrawFrame: texId=$texId, size=${surfaceWidth}x$surfaceHeight, textureUpdated=$textureUpdated")
        }
        
        renderNode?.onDrawFrame(surfaceWidth, surfaceHeight, texId, transformMatrix)
    }
    
    fun seekToPosition(position: Float) {
        if (!isPrepared) return
        rvRes?.seekToPosition(position)
        process = position
    }
    
    fun seekToFrame(frameIndex: Int) {
        if (!isPrepared) return
        rvRes?.seekToFrame(frameIndex)
    }
    
    fun setAlpha(alpha: Float) {
        this.alpha = alpha
    }
    
    fun setVisible(visible: Boolean) {
        this.isVisible = visible
    }
    
    fun setProcess(process: Float) {
        this.process = process
    }
    
    fun setSmallScreen(isSmall: Boolean) {
        isSmallScreen = isSmall
    }
    
    fun setFoldDevice(isFold: Boolean) {
        isFoldDevice = isFold
    }
    
    fun setDesignSize(width: Int, height: Int) {
        configure?.apply {
            setImageDesignScreenWidth(width)
            setImageDesignScreenHeight(height)
        }
    }
    
    fun pause() {
        Log.d(TAG, "pause")
        // 暂停渲染
    }
    
    fun resume() {
        Log.d(TAG, "resume")
        // 恢复渲染
    }
    
    fun release() {
        Log.d(TAG, "release")
        
        rvRes?.release()
        rvRes = null
        
        renderNode?.release()
        renderNode = null
        
        configure = null
        isPrepared = false
        
        mainHandler.removeCallbacksAndMessages(null)
    }
    
    fun getFrameCount(): Int = configure?.videoFramesCount ?: 0
    
    fun getVideoDuration(): Long = configure?.videoDuration ?: 0L
    
    fun getCurrentFrame(): Int = rvRes?.getCurrentFrame() ?: 0
    
    fun isPrepared(): Boolean = isPrepared
    
    fun getTextureId(): Int = rvRes?.getTextureId() ?: 0
}
