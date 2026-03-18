package com.zeaze.tianyinwallpaper.utils

import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLSurface
import android.util.Log
import android.view.Surface

/**
 * EGL 上下文管理辅助类
 * 封装 EGL 初始化、销毁和上下文管理逻辑
 */
class EglHelper(private val tag: String = "EglHelper") {

    var display: EGLDisplay = EGL14.EGL_NO_DISPLAY
        private set
    var context: EGLContext = EGL14.EGL_NO_CONTEXT
        private set
    var eglSurface: EGLSurface = EGL14.EGL_NO_SURFACE
        private set

    private var config: EGLConfig? = null

    /**
     * 初始化 EGL
     * @param surface 用于创建 EGL Surface 的 Surface 对象
     * @param withErrorLogging 是否启用详细错误日志
     * @return 初始化是否成功
     */
    fun init(surface: Surface, withErrorLogging: Boolean = true): Boolean {
        // 获取 EGL Display
        display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        if (display == EGL14.EGL_NO_DISPLAY) {
            if (withErrorLogging) Log.e(tag, "eglGetDisplay failed")
            return false
        }

        // 初始化 EGL
        val version = IntArray(2)
        if (!EGL14.eglInitialize(display, version, 0, version, 1)) {
            if (withErrorLogging) Log.e(tag, "eglInitialize failed")
            return false
        }

        // 选择配置
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
        if (!EGL14.eglChooseConfig(display, attr, 0, configs, 0, 1, numConfigs, 0) || numConfigs[0] == 0) {
            if (withErrorLogging) Log.e(tag, "eglChooseConfig failed")
            return false
        }
        config = configs[0]

        // 创建上下文
        context = EGL14.eglCreateContext(
            display, config!!, EGL14.EGL_NO_CONTEXT,
            intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE), 0
        )
        if (context == EGL14.EGL_NO_CONTEXT) {
            if (withErrorLogging) Log.e(tag, "eglCreateContext failed")
            return false
        }

        // 创建 Surface
        eglSurface = EGL14.eglCreateWindowSurface(
            display, config!!, surface,
            intArrayOf(EGL14.EGL_NONE), 0
        )
        if (eglSurface == EGL14.EGL_NO_SURFACE) {
            if (withErrorLogging) Log.e(tag, "eglCreateWindowSurface failed")
            return false
        }

        // 设置当前上下文
        if (!EGL14.eglMakeCurrent(display, eglSurface, eglSurface, context)) {
            if (withErrorLogging) Log.e(tag, "eglMakeCurrent failed")
            return false
        }

        if (withErrorLogging) Log.d(tag, "initEGL success")
        return true
    }

    /**
     * 简化的初始化方法（无详细错误日志）
     */
    fun initSimple(surface: Surface): Boolean {
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
        config = configs[0]

        context = EGL14.eglCreateContext(
            display, config!!, EGL14.EGL_NO_CONTEXT,
            intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE), 0
        )

        eglSurface = EGL14.eglCreateWindowSurface(
            display, config!!, surface,
            intArrayOf(EGL14.EGL_NONE), 0
        )

        return EGL14.eglMakeCurrent(display, eglSurface, eglSurface, context)
    }

    /**
     * 销毁 EGL 资源
     */
    fun destroy() {
        if (display != EGL14.EGL_NO_DISPLAY) {
            EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)

            if (eglSurface != EGL14.EGL_NO_SURFACE) {
                EGL14.eglDestroySurface(display, eglSurface)
                eglSurface = EGL14.EGL_NO_SURFACE
            }

            if (context != EGL14.EGL_NO_CONTEXT) {
                EGL14.eglDestroyContext(display, context)
                context = EGL14.EGL_NO_CONTEXT
            }

            EGL14.eglTerminate(display)
            display = EGL14.EGL_NO_DISPLAY
        }
    }

    /**
     * 交换缓冲区
     */
    fun swapBuffers(): Boolean {
        return EGL14.eglSwapBuffers(display, eglSurface)
    }

    /**
     * 检查 EGL 是否已初始化
     */
    val isReady: Boolean
        get() = display != EGL14.EGL_NO_DISPLAY && 
                context != EGL14.EGL_NO_CONTEXT && 
                eglSurface != EGL14.EGL_NO_SURFACE
}
