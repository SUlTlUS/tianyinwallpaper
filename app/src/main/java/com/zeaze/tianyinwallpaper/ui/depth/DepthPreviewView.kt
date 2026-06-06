package com.zeaze.tianyinwallpaper.ui.depth

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.util.Log
import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.zeaze.tianyinwallpaper.model.DepthWallpaperModel
import com.zeaze.tianyinwallpaper.renderer.DepthGLRenderer
import com.zeaze.tianyinwallpaper.renderer.NativeGaussianRendererFactory
import com.zeaze.tianyinwallpaper.utils.GaussianPlyLoader
import com.zeaze.tianyinwallpaper.utils.GaussianSceneLoader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.exp

@Composable
fun DepthPreviewView(
    model: DepthWallpaperModel?,
    previewFps: Int = 60,
    onModelChange: (DepthWallpaperModel) -> Unit = {},
    modifier: Modifier = Modifier
) {
    if (model?.isGaussian() == true && model.gaussianUri.isNotBlank()) {
        if (model.useWebGaussianRenderer()) {
            SuperSplatWebView(
                uriString = model.gaussianUri,
                sensorSensitivity = model.sensorSensitivity,
                parallaxStrength = model.parallaxStrength,
                cameraZoom = model.cameraZoom,
                centerOffsetX = model.centerOffsetX,
                centerOffsetY = model.centerOffsetY,
                focusDepth = model.focusDepth,
                previewFps = previewFps,
                onCenterOffsetChange = { x, y ->
                    onModelChange(model.copy(centerOffsetX = x, centerOffsetY = y))
                },
                modifier = modifier.fillMaxSize()
            )
        } else {
            NativeGaussianPreviewView(
                model = model,
                previewFps = previewFps,
                modifier = modifier.fillMaxSize()
            )
        }
        return
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colors.background),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = "No Gaussian wallpaper selected",
            color = MaterialTheme.colors.onBackground.copy(alpha = 0.7f),
            fontSize = 14.sp,
            fontWeight = FontWeight.Medium
        )
    }
}

@Composable
private fun NativeGaussianPreviewView(
    model: DepthWallpaperModel,
    previewFps: Int,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val appContext = context.applicationContext
    val renderer = remember(appContext) { NativeGaussianRendererFactory.create(appContext) }
    var scene by remember(model.gaussianUri) { mutableStateOf<GaussianPlyLoader.GaussianScene?>(null) }

    DisposableEffect(Unit) {
        onDispose {
            renderer.stopAndWait(300)
        }
    }

    LaunchedEffect(model.gaussianUri, model.gaussianMaxSplats) {
        scene = withContext(Dispatchers.IO) {
            val result = GaussianSceneLoader.loadSceneDetailed(
                context = context.applicationContext,
                uriString = model.gaussianUri,
                maxSplats = model.nativePreviewMaxSplats(),
                viewportAspect = context.resources.displayMetrics.widthPixels.toFloat() /
                    context.resources.displayMetrics.heightPixels.coerceAtLeast(1).toFloat()
            )
            result.error?.let { Log.w(TAG, "native preview gaussian load failed error=$it uri=${model.gaussianUri}") }
            result.scene
        }
        scene?.let { renderer.loadGaussians(it) }
        renderer.resetCamera()
    }

    LaunchedEffect(model.parallaxStrength, model.cameraZoom, model.centerOffsetX, model.centerOffsetY, model.focusDepth) {
        renderer.updateParams(model.parallaxStrength, 0f)
        renderer.updateGaussianParams(model.nativePreviewParams())
        renderer.requestRender()
    }

    AndroidView(
        factory = { viewContext ->
            SurfaceView(viewContext).apply {
                holder.addCallback(object : SurfaceHolder.Callback {
                    override fun surfaceCreated(holder: SurfaceHolder) = Unit

                    override fun surfaceChanged(
                        holder: SurfaceHolder,
                        format: Int,
                        width: Int,
                        height: Int
                    ) {
                        renderer.start(holder.surface)
                        renderer.resize(width, height)
                        renderer.updateParams(model.parallaxStrength, 0f)
                        renderer.updateGaussianParams(model.nativePreviewParams())
                        scene?.let { renderer.loadGaussians(it) }
                    }

                    override fun surfaceDestroyed(holder: SurfaceHolder) {
                        renderer.stopAndWait(300)
                    }
                })
            }
        },
        update = {
            renderer.updateParams(model.parallaxStrength, 0f)
            renderer.updateGaussianParams(model.nativePreviewParams())
        },
        modifier = modifier
    )

    DisposableEffect(context, model.sensorSensitivity, previewFps) {
        val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
        val gyroSensor = sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE)
        val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val gyroScale = 2.5f * (model.sensorSensitivity.coerceIn(1f, 9f) / 4.5f)
        val minDispatchIntervalNs = if (previewFps <= 30) 33_000_000L else 16_000_000L
        var lastTimestamp = 0L
        var lastDispatchTimestamp = 0L
        var tiltX = 0f
        var tiltY = 0f
        var filteredTiltX = 0f
        var filteredTiltY = 0f
        var filterInitialized = false
        var lastFilterTimestamp = 0L

        val listener = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent?) {
                if (event == null) return
                val now = event.timestamp
                @Suppress("DEPRECATION")
                val displayRotation = windowManager.defaultDisplay.rotation
                val dt = if (lastTimestamp == 0L) 0f else (now - lastTimestamp) / 1_000_000_000f
                lastTimestamp = now
                if (dt <= 0f || dt > 0.5f) return

                val (gx, gy) = when (displayRotation) {
                    Surface.ROTATION_90 -> -event.values[1] to event.values[0]
                    Surface.ROTATION_180 -> -event.values[0] to -event.values[1]
                    Surface.ROTATION_270 -> event.values[1] to -event.values[0]
                    else -> event.values[0] to event.values[1]
                }

                tiltX = (tiltX + gy * dt * gyroScale).coerceIn(-1f, 1f)
                tiltY = (tiltY + gx * dt * gyroScale).coerceIn(-1f, 1f)
                val filterDt = if (lastFilterTimestamp == 0L) 0f else ((now - lastFilterTimestamp) / 1_000_000_000f).coerceIn(0f, 0.1f)
                lastFilterTimestamp = now
                if (!filterInitialized) {
                    filteredTiltX = tiltX
                    filteredTiltY = tiltY
                    filterInitialized = true
                } else {
                    val alpha = (1f - exp((-filterDt / 0.12f).toDouble()).toFloat()).coerceIn(0.08f, 0.45f)
                    filteredTiltX += (tiltX - filteredTiltX) * alpha
                    filteredTiltY += (tiltY - filteredTiltY) * alpha
                }
                if (lastDispatchTimestamp != 0L && now - lastDispatchTimestamp < minDispatchIntervalNs) return
                lastDispatchTimestamp = now
                renderer.updateTilt(filteredTiltX, filteredTiltY)
            }

            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
        }

        renderer.updateTilt(0f, 0f)
        if (gyroSensor != null) {
            sensorManager.registerListener(listener, gyroSensor, SensorManager.SENSOR_DELAY_GAME)
        }
        onDispose {
            sensorManager.unregisterListener(listener)
        }
    }
}

private const val TAG = "DepthPreviewView"
private const val NATIVE_PREVIEW_MIN_GAUSSIAN_SPLATS = 500_000
private const val NATIVE_PREVIEW_MAX_GAUSSIAN_SPLATS = 1_500_000

private fun DepthWallpaperModel.nativePreviewMaxSplats(): Int {
    return gaussianMaxSplats.coerceIn(
        NATIVE_PREVIEW_MIN_GAUSSIAN_SPLATS,
        NATIVE_PREVIEW_MAX_GAUSSIAN_SPLATS
    )
}

private fun DepthWallpaperModel.nativePreviewParams(): DepthGLRenderer.GaussianRenderParams {
    return DepthGLRenderer.GaussianRenderParams(
        splatScale = 1.35f,
        globalOpacity = 1.0f,
        alphaFalloff = 0.72f,
        minPointSize = 0.5f,
        maxPointSize = 120f,
        cameraZoom = cameraZoom,
        centerOffsetX = centerOffsetX,
        centerOffsetY = centerOffsetY,
        focusDepthOffset = focusDepth,
        useLayerCache = false
    )
}
