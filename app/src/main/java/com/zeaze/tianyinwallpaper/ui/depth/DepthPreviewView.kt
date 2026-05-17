package com.zeaze.tianyinwallpaper.ui.depth

import android.content.Context
import android.graphics.SurfaceTexture
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.view.Surface
import android.view.TextureView
import android.view.WindowManager
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.zeaze.tianyinwallpaper.model.DepthWallpaperModel
import com.zeaze.tianyinwallpaper.renderer.DepthGLRenderer
import com.zeaze.tianyinwallpaper.utils.DepthImageProcessor
import com.zeaze.tianyinwallpaper.utils.GaussianPlyLoader
import com.zeaze.tianyinwallpaper.utils.PhotoMeshPlyLoader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Depth wallpaper preview view copied from RasterPreviewView and adapted for DepthGLRenderer.
 *
 * Supported content:
 * - Photo + generated depth map
 * - Gaussian splat PLY
 * - 3D-photo mesh PLY
 */
@Composable
fun DepthPreviewView(
    model: DepthWallpaperModel?,
    previewFps: Int = 60,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()

    val renderer = remember { DepthGLRenderer() }

    var textureView by remember { mutableStateOf<TextureView?>(null) }
    var isRendererStarted by remember { mutableStateOf(false) }

    LaunchedEffect(
        isRendererStarted,
        model?.id,
        model?.parallaxStrength,
        model?.isMesh()
    ) {
        val target = model
        if (isRendererStarted && target != null) {
            renderer.updateParams(target.renderParallaxStrength(), 0f)
        }
    }

    LaunchedEffect(
        isRendererStarted,
        model?.id,
        model?.imageUri,
        model?.gaussianUri,
        model?.meshUri
    ) {
        val target = model
        if (isRendererStarted && target != null) {
            coroutineScope.launch {
                withContext(Dispatchers.IO) {
                    renderer.loadFromDepthModel(context, target, textureView)
                }
            }
        }
    }

    DisposableEffect(context, model?.sensorSensitivity, model?.isMesh(), previewFps) {
        val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
        val gyroSensor = sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE)

        val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val sensorSensitivity = model?.renderSensorSensitivity() ?: 4.5f
        val gyroScale = 2.5f * (sensorSensitivity.coerceIn(1f, 9f) / 4.5f)
        val minDispatchIntervalNs = if (previewFps <= 30) {
            33_000_000L
        } else {
            16_000_000L
        }
        var lastTimestamp = 0L
        var lastDispatchTimestamp = 0L
        var tiltX = 0f
        var tiltY = 0f

        val sensorListener = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent?) {
                if (event == null) return
                val now = event.timestamp
                val dt = if (lastTimestamp == 0L) 0f else (now - lastTimestamp) / 1_000_000_000f
                lastTimestamp = now
                if (dt <= 0f || dt > 0.5f) return

                @Suppress("DEPRECATION")
                val rotation = windowManager.defaultDisplay.rotation
                val (gx, gy) = when (rotation) {
                    Surface.ROTATION_90 -> -event.values[1] to event.values[0]
                    Surface.ROTATION_180 -> -event.values[0] to -event.values[1]
                    Surface.ROTATION_270 -> event.values[1] to -event.values[0]
                    else -> event.values[0] to event.values[1]
                }

                tiltX = (tiltX + gx * dt * gyroScale).coerceIn(-1f, 1f)
                tiltY = (tiltY + gy * dt * gyroScale).coerceIn(-1f, 1f)
                if (lastDispatchTimestamp != 0L && now - lastDispatchTimestamp < minDispatchIntervalNs) {
                    return
                }
                lastDispatchTimestamp = now
                if (model?.isMesh() == true) {
                    renderer.updateTilt(tiltY, tiltX)
                } else {
                    renderer.updateTilt(tiltX, tiltY)
                }
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
                            isRendererStarted = true
                            coroutineScope.launch {
                                withContext(Dispatchers.IO) {
                                    model?.let { renderer.loadFromDepthModel(context, it, textureView) }
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

        if (!isRendererStarted || model == null) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colors.background),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = if (model == null) "No active depth wallpaper" else "Loading depth wallpaper",
                    color = MaterialTheme.colors.onBackground.copy(alpha = 0.7f),
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium
                )
            }
        }
    }
}

private fun DepthGLRenderer.loadFromDepthModel(
    context: Context,
    model: DepthWallpaperModel,
    textureView: TextureView?
) {
    updateParams(model.renderParallaxStrength(), 0f)
    when {
        model.isGaussian() -> {
            val scene = GaussianPlyLoader.loadScene(
                context = context,
                uriString = model.gaussianUri,
                viewportAspect = textureView.viewportAspect()
            )
            if (scene != null) {
                loadGaussians(scene)
            }
        }
        model.isMesh() -> {
            val scene = PhotoMeshPlyLoader.loadScene(
                context = context,
                uriString = model.meshUri,
                maxFaces = PhotoMeshPlyLoader.MAX_FACE_LIMIT
            )
            if (scene != null) {
                loadMesh(scene)
            }
        }
        else -> {
            val layeredTextures = DepthImageProcessor.loadLayeredTextureSet(
                context = context,
                model = model,
                targetWidth = (textureView?.width ?: 720).coerceAtLeast(1) * 2,
                targetHeight = (textureView?.height ?: 1280).coerceAtLeast(1) * 2
            )
            if (layeredTextures != null) {
                loadLayeredTextures(layeredTextures)
            } else {
                val textures = DepthImageProcessor.loadTextureSet(
                    context = context,
                    model = model,
                    targetWidth = (textureView?.width ?: 720).coerceAtLeast(1) * 2,
                    targetHeight = (textureView?.height ?: 1280).coerceAtLeast(1) * 2
                )
                if (textures != null) {
                    loadTextures(textures)
                }
            }
        }
    }
}

private fun TextureView?.viewportAspect(): Float {
    val width = this?.width?.takeIf { it > 0 } ?: 9
    val height = this?.height?.takeIf { it > 0 } ?: 16
    return width.toFloat() / height.toFloat()
}

private fun DepthWallpaperModel.renderParallaxStrength(): Float {
    return parallaxStrength
}

private fun DepthWallpaperModel.renderSensorSensitivity(): Float {
    return sensorSensitivity
}
