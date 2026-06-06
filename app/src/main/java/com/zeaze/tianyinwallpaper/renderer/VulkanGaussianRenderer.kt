package com.zeaze.tianyinwallpaper.renderer

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import android.view.Surface
import com.zeaze.tianyinwallpaper.utils.GaussianPlyLoader

class VulkanGaussianRenderer(
    private val appContext: Context
) : NativeGaussianRenderer {
    private val fallback = DepthGLRenderer()

    override fun start(surface: Surface) {
        fallback.start(surface)
    }

    override fun stop() {
        fallback.stop()
    }

    override fun stopAndWait(timeoutMs: Long) {
        fallback.stopAndWait(timeoutMs)
    }

    override fun resize(width: Int, height: Int) {
        fallback.resize(width, height)
    }

    override