package com.zeaze.tianyinwallpaper.renderer
import android.content.Context
class VulkanGaussianRenderer(private val context: Context): NativeGaussianRenderer by DepthGLRenderer(){companion object{fun isSupported(context: Context)=false}}
