package com.zeaze.tianyinwallpaper.ui.raster

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.SurfaceTexture
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.net.Uri
import android.util.Log
import android.view.Surface
import android.view.TextureView
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import com.zeaze.tianyinwallpaper.model.RasterGroupModel
import com.zeaze.tianyinwallpaper.renderer.RasterGLRenderer
import kotlin.math.abs

/**
 * 光栅壁纸预览组件 - 使用 TextureView + 共享渲染器
 * 效果与实际壁纸服务完全一致
 */
@Composable
fun RasterPreviewView(
    group: RasterGroupModel,
    sensorWidth: Float = 0.6f,
    transitionBand: Float = 0.55f,
    edgeSoftness: Float = 0.25f,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    
    // 渲染器实例
    val renderer = remember { RasterGLRenderer() }
    
    // 传感器状态
    var tiltNormalized by remember { mutableStateOf(0f) }
    var tiltDirection by remember { mutableStateOf(0) }

    // 累积角度 - 提到外部避免被重置
    var lastNs by remember { mutableStateOf(0L) }
    var accumulatedAngle by remember { mutableStateOf(0f) }

    // TextureView 引用
    var textureView by remember { mutableStateOf<TextureView?>(null) }

    // 初始化渲染器
    LaunchedEffect(group.id, group.imageUris) {
        // 加载图片
        val bitmaps = group.imageUris.mapNotNull { uriStr ->
            try {
                context.contentResolver.openInputStream(Uri.parse(uriStr))?.use {
                    BitmapFactory.decodeStream(it)
                }
            } catch (e: Exception) {
                Log.w("RasterPreviewView", "Failed to load image: $uriStr", e)
                null
            }
        }

        renderer.sensorWidth = sensorWidth
        renderer.transitionBand = transitionBand
        renderer.edgeSoftness = edgeSoftness
        renderer.loadBitmaps(bitmaps)
        renderer.requestRender()
    }

    // 更新渲染参数
    LaunchedEffect(sensorWidth, transitionBand, edgeSoftness) {
        renderer.sensorWidth = sensorWidth
        renderer.transitionBand = transitionBand
        renderer.edgeSoftness = edgeSoftness
    }

    // 传感器监听
    DisposableEffect(context) {
        val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
        val gyroSensor = sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE)

        val listener = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent?) {
                val e = event ?: return
                if (e.sensor.type != Sensor.TYPE_GYROSCOPE) return

                if (lastNs == 0L) {
                    lastNs = e.timestamp
                    return
                }

                val dt = (e.timestamp - lastNs) / 1_000_000_000f
                lastNs = e.timestamp

                val angularVelocity = e.values[1]
                val absOmega = abs(angularVelocity)
                if (absOmega >= 0.01f) {
                    accumulatedAngle += angularVelocity * dt
                }

                val newTilt = (abs(accumulatedAngle) / sensorWidth).coerceIn(0f, 1f)
                // 与 WallpaperService 保持一致的方向判断
                val newDirection = when {
                    accumulatedAngle < -0.05f -> 1
                    accumulatedAngle > 0.05f -> -1
                    else -> tiltDirection
                }

                if (newTilt != tiltNormalized || newDirection != tiltDirection) {
                    tiltNormalized = newTilt
                    tiltDirection = newDirection
                    renderer.updateTilt(newTilt, newDirection)
                }
            }

            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
        }

        if (gyroSensor != null) {
            sensorManager.registerListener(listener, gyroSensor, SensorManager.SENSOR_DELAY_GAME)
        }

        onDispose {
            sensorManager.unregisterListener(listener)
        }
    }

    AndroidView(
        factory = { ctx ->
            TextureView(ctx).apply {
                surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                    override fun onSurfaceTextureAvailable(surface: SurfaceTexture, width: Int, height: Int) {
                        renderer.start(Surface(surface))
                        renderer.resize(width, height)
                        renderer.requestRender()
                    }

                    override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, width: Int, height: Int) {
                        renderer.resize(width, height)
                        renderer.requestRender()
                    }

                    override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean {
                        // 同步等待渲染器停止，防止 Surface 被销毁后仍在渲染
                        renderer.stopAndWait(500)
                        // 给 BufferQueue 一点时间完成清理
                        try { Thread.sleep(16) } catch (_: Exception) {}
                        return true
                    }

                    override fun onSurfaceTextureUpdated(surface: SurfaceTexture) {}
                }
            }.also { textureView = it }
        },
        modifier = modifier.fillMaxSize()
    )

    // 组件销毁时确保渲染器停止（防止意外情况）
    DisposableEffect(renderer) {
        onDispose {
            renderer.stop()
        }
    }
}
