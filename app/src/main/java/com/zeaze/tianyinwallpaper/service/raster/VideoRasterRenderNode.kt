package com.zeaze.tianyinwallpaper.service.raster

import android.opengl.GLES20
import android.opengl.Matrix
import android.util.Log
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

/**
 * 视频光栅渲染节点
 * 参考自 vivo VideoRasterRenderNode (f.java)
 * 负责将视频帧渲染到光栅效果层
 */
class VideoRasterRenderNode(
    private val configure: RVEffectConfigure
) {
    companion object {
        private const val TAG = "VideoRasterRenderNode"
    }
    
    // 矩阵管理器
    private val mvpMatrix = FloatArray(16)
    private val tempMatrix = FloatArray(16)
    
    // 渲染参数
    private var scaleX: Float = 1f
    private var scaleY: Float = 1f
    private var posX: Float = 0f
    private var posY: Float = 0f
    private var alpha: Float = 1f
    private var visible: Boolean = true
    private var process: Float = 0f
    
    // 光栅参数
    private var rasterOffsetX: Float = 0f
    private var rasterOffsetY: Float = 0f
    private var rasterScaleX: Float = 1f
    private var rasterScaleY: Float = 1f
    
    // 着色器
    private var shaderProgram: Int = 0
    private var vertexBuffer: FloatBuffer
    private var texCoordBuffer: FloatBuffer
    
    // 资源引用
    private var rvRes: RVRes? = null
    
    init {
        Matrix.setIdentityM(mvpMatrix, 0)
        
        // 初始化顶点缓冲区
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
    }
    
    fun setResource(rvRes: RVRes?) {
        this.rvRes = rvRes
        Log.d(TAG, "setResource: rvRes=$rvRes")
    }
    
    fun updateDumpInfo(dumpInfo: RVEffectConfigure.RVDumpInfo?) {
        dumpInfo?.let {
            scaleX = it.scaleX
            scaleY = it.scaleY
            posX = it.posX
            posY = it.posY
            Log.d(TAG, "updateDumpInfo: scale=(${scaleX}, ${scaleY}), pos=(${posX}, ${posY})")
        }
    }
    
    fun setRasterParams(
        offsetX: Float,
        offsetY: Float,
        scale: Float
    ) {
        rasterOffsetX = offsetX
        rasterOffsetY = offsetY
        rasterScaleX = scale
        rasterScaleY = scale
    }
    
    fun setProcess(process: Float) {
        this.process = process
    }
    
    fun setAlpha(alpha: Float) {
        this.alpha = alpha
    }
    
    fun setVisible(visible: Boolean) {
        this.visible = visible
    }
    
    private var drawFrameCounter = 0L
    
    fun onDrawFrame(
        surfaceWidth: Int,
        surfaceHeight: Int,
        videoTextureId: Int,
        transformMatrix: FloatArray
    ) {
        if (!visible || alpha <= 0f) {
            drawFrameCounter++
            if (drawFrameCounter % 60 == 0L) {
                Log.w(TAG, "onDrawFrame: skipped, visible=$visible, alpha=$alpha")
            }
            return
        }
        
        if (videoTextureId <= 0) {
            Log.e(TAG, "onDrawFrame: invalid textureId=$videoTextureId")
            return
        }
        
        // 计算MVP矩阵
        Matrix.setIdentityM(mvpMatrix, 0)
        
        // 应用缩放和位置
        Matrix.translateM(mvpMatrix, 0, posX, posY, 0f)
        Matrix.scaleM(mvpMatrix, 0, scaleX, scaleY, 1f)
        
        // 应用光栅偏移
        if (rasterOffsetX != 0f || rasterOffsetY != 0f) {
            Matrix.translateM(mvpMatrix, 0, rasterOffsetX, rasterOffsetY, 0f)
        }
        
        // 设置混合
        GLES20.glEnable(GLES20.GL_BLEND)
        GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA)
        
        // 使用着色器程序
        GLES20.glUseProgram(shaderProgram)
        
        // 设置顶点属性
        val aPosition = GLES20.glGetAttribLocation(shaderProgram, "aPosition")
        GLES20.glEnableVertexAttribArray(aPosition)
        GLES20.glVertexAttribPointer(aPosition, 2, GLES20.GL_FLOAT, false, 8, vertexBuffer)
        
        val aTexCoord = GLES20.glGetAttribLocation(shaderProgram, "aTexCoord")
        GLES20.glEnableVertexAttribArray(aTexCoord)
        GLES20.glVertexAttribPointer(aTexCoord, 2, GLES20.GL_FLOAT, false, 8, texCoordBuffer)
        
        // 设置uniform
        GLES20.glUniformMatrix4fv(
            GLES20.glGetUniformLocation(shaderProgram, "uMVPMatrix"),
            1, false, mvpMatrix, 0
        )
        GLES20.glUniformMatrix4fv(
            GLES20.glGetUniformLocation(shaderProgram, "uTransformMatrix"),
            1, false, transformMatrix, 0
        )
        GLES20.glUniform1f(GLES20.glGetUniformLocation(shaderProgram, "uAlpha"), alpha)
        
        // 绑定纹理
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(android.opengl.GLES11Ext.GL_TEXTURE_EXTERNAL_OES, videoTextureId)
        GLES20.glUniform1i(GLES20.glGetUniformLocation(shaderProgram, "sTexture"), 0)
        
        // 绘制
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        
        // 清理
        GLES20.glDisableVertexAttribArray(aPosition)
        GLES20.glDisableVertexAttribArray(aTexCoord)
        GLES20.glDisable(GLES20.GL_BLEND)
    }
    
    fun initShader() {
        Log.d(TAG, "initShader: starting...")
        val vertexShader = """
            attribute vec4 aPosition;
            attribute vec2 aTexCoord;
            varying vec2 vTexCoord;
            uniform mat4 uMVPMatrix;
            uniform mat4 uTransformMatrix;
            void main() {
                gl_Position = uMVPMatrix * aPosition;
                vTexCoord = (uTransformMatrix * vec4(aTexCoord, 0.0, 1.0)).xy;
            }
        """
        
        val fragmentShader = """
            #extension GL_OES_EGL_image_external : require
            precision mediump float;
            varying vec2 vTexCoord;
            uniform samplerExternalOES sTexture;
            uniform float uAlpha;
            void main() {
                vec4 color = texture2D(sTexture, vTexCoord);
                gl_FragColor = vec4(color.rgb, color.a * uAlpha);
            }
        """
        
        shaderProgram = createProgram(vertexShader, fragmentShader)
        Log.w(TAG, "initShader: completed, program=$shaderProgram")
    }
    
    fun release() {
        if (shaderProgram != 0) {
            GLES20.glDeleteProgram(shaderProgram)
            shaderProgram = 0
        }
    }
    
    private fun createProgram(vertexSource: String, fragmentSource: String): Int {
        val vertexShader = loadShader(GLES20.GL_VERTEX_SHADER, vertexSource)
        val fragmentShader = loadShader(GLES20.GL_FRAGMENT_SHADER, fragmentSource)
        
        if (vertexShader == 0 || fragmentShader == 0) {
            Log.e(TAG, "createProgram: shader creation failed, vs=$vertexShader, fs=$fragmentShader")
            return 0
        }
        
        val program = GLES20.glCreateProgram()
        GLES20.glAttachShader(program, vertexShader)
        GLES20.glAttachShader(program, fragmentShader)
        GLES20.glLinkProgram(program)
        
        val linkStatus = IntArray(1)
        GLES20.glGetProgramiv(program, GLES20.GL_LINK_STATUS, linkStatus, 0)
        if (linkStatus[0] == 0) {
            Log.e(TAG, "createProgram: link failed: ${GLES20.glGetProgramInfoLog(program)}")
            GLES20.glDeleteProgram(program)
            return 0
        }
        
        Log.d(TAG, "createProgram: success, program=$program")
        return program
    }
    
    private fun loadShader(type: Int, source: String): Int {
        val shader = GLES20.glCreateShader(type)
        GLES20.glShaderSource(shader, source)
        GLES20.glCompileShader(shader)
        
        val compiled = IntArray(1)
        GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, compiled, 0)
        if (compiled[0] == 0) {
            Log.e(TAG, "loadShader: compile failed: ${GLES20.glGetShaderInfoLog(shader)}")
            GLES20.glDeleteShader(shader)
            return 0
        }
        
        return shader
    }
}
