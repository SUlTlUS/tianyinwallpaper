package com.zeaze.tianyinwallpaper.ui.raster

import android.content.Context
import android.graphics.SurfaceTexture
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.view.Surface
import android.view.TextureView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.zeaze.tianyinwallpaper.model.RasterGroupModel
import com.zeaze.tianyinwallpaper.renderer.RasterGLRenderer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 光栅壁纸预览组件 - 使用 TextureView + 共享渲染器
 * 效果与实际壁纸服务完全一致
 * 
 * 支持：
 * - 多种扫描线效果
 * - 实时参数更新
 * - 集成传感器数据处理
 * - 异步加载图片避免卡顿
 */
@Composable
fun RasterPreviewView(
    group: RasterGroupModel,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    
    // 渲染器实例（集成传感器处理）
    val renderer = remember { RasterGLRenderer() }

    // TextureView 引用
    var textureView by remember { mutableStateOf<TextureView?>(null) }
    
    // 记录渲染器是否已启动
    var isRendererStarted by remember { mutableStateOf(false) }

    // 当参数变化时更新渲染参数（不重新加载图片）
    LaunchedEffect(
        isRendererStarted,
        group.id,
        group.sensorWidth,
        group.transitionBand,
        group.edgeSoftness,
        group.effectType,
        group.lenticularPitch,
        group.lenticularAngle
    ) {
        if (isRendererStarted) {
            renderer.updateParamsFromModel(group)
        }
    }
    
    // 当图片列表变化时异步重新加载
    LaunchedEffect(group.imageUris, isRendererStarted) {
        if (isRendererStarted && group.imageUris.isNotEmpty()) {
            // 异步加载图片
            coroutineScope.launch {
                withContext(Dispatchers.IO) {
                    renderer.loadFromModel(context, group)
                }
            }
        }
    }

    // 传感器监听 - 使用渲染器内置的传感器处理
    DisposableEffect(context) {
        val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
        val gyroSensor = sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE)

        val sensorListener = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent?) {
                renderer.onSensorEvent(event)
            }
            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
        }

        if (gyroSensor != null) {
            sensorManager.registerListener(
                sensorListener,
                gyroSensor,
                SensorManager.SENSOR_DELAY_GAME
            )
        }

        onDispose {
            sensorManager.unregisterListener(sensorListener)
        }
    }

    Box(modifier = modifier.fillMaxSize()) {
        AndroidView(
            factory = { ctx ->
                TextureView(ctx).apply {
                    surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                        override fun onSurfaceTextureAvailable(surface: SurfaceTexture, width: Int, height: Int) {
                            renderer.start(Surface(surface))
                            renderer.resize(width, height)
                            // 异步加载图片，避免阻塞主线程
                            isRendererStarted = true
                            coroutineScope.launch {
                                withContext(Dispatchers.IO) {
                                    renderer.loadFromModel(context, group)
                                }
                            }
                        }

                        override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, width: Int, height: Int) {
                            renderer.resize(width, height)
                            renderer.requestRender()
                        }

                        override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean {
                            isRendererStarted = false
                            renderer.stopAndWait(500)
                            return true
                        }

                        override fun onSurfaceTextureUpdated(surface: SurfaceTexture) {}
                    }
                }.also { textureView = it }
            },
            modifier = Modifier.fillMaxSize()
        )

        // 加载动画覆盖层
        if (!isRendererStarted) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colors.background),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "正在加载",
                    color = MaterialTheme.colors.onBackground.copy(alpha = 0.7f),
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium
                )
            }
        }
    }
}
