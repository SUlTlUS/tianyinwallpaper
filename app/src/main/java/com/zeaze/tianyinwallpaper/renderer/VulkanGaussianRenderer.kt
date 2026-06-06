package com.zeaze.tianyinwallpaper.renderer
import android.content.Context
import android.view.Surface
import com.zeaze.tianyinwallpaper.utils.GaussianPlyLoader
class VulkanGaussianRenderer(private val c:Context):NativeGaussianRenderer{private val f=DepthGLRenderer();override fun start(s:Surface)=f.start(s);override fun stop()=f.stop();override fun stopAndWait(t:Long)=f.stopAndWait(t);override fun resize(w:Int,h:Int)=f