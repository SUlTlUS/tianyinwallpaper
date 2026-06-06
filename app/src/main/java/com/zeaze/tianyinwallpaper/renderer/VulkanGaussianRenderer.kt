package com.zeaze.tianyinwallpaper.renderer

import android.content.Context
import android.view.Surface
import com.zeaze.tianyinwallpaper.utils.GaussianPlyLoader

class VulkanGaussianRenderer(
    private val appContext: Context
) : NativeGaussianRenderer {
    private val fallback = DepthGLRenderer()

    override fun start(surface: Surface) = fallback.start(surface)
    override fun stop() = fallback.stop()
    override fun stopAndWait(timeoutMs: Long