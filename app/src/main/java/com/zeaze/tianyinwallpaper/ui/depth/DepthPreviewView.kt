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
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.zeaze.tianyinwallpaper.model.DepthWallpaperModel
import com.zeaze.tianyinwallpaper.renderer.NativeGaussianBackendMode
import com.zeaze.tianyinwallpaper.renderer.NativeGaussianRenderer
import com.zeaze.tianyinwallpaper.renderer.NativeGaussianRendererFactory
import com.zeaze.tianyinwallpaper.utils.SuperSplatWebParams
import kotlin.math.exp
import com.zeaze.tianyinwallpaper.App
import com.zeaze.tianyinwallpaper.utils.DepthPrefs

@Composable
fun DepthPreviewView(
    model: DepthWallpaperModel?,
    previewFps: Int = 60,
    cameraResetKey: Int = 0,
    onModelChange: (DepthWallpaperModel) -> Unit = {},
    onLoadingChanged: (Boolean) -> Unit = {},
    webBackdropCaptureEnabled: Boolean = false,
    onWebBackdropFrame: (ImageBitmap?) -> Unit = {},
    sensorInputEnabled: Boolean = true,
    modifier: Modifier = Modifier
) {
    if (model?.isGaussian() == true && model.gaussianUri.isNotBlank()) {
        WebGaussianTexturePreviewView(
            model = model,
            previewFps = previewFps,
            cameraResetKey = cameraResetKey,
            onModelChange = onModelChange,
            onLoadingChanged = onLoadingChanged,
            backdropCaptureEnabled = webBackdropCaptureEnabled,
            onBackdropFrame = onWebBackdropFrame,
            sensorInputEnabled = sensorInputEnabled,
            modifier = modifier.fillMaxSize()
        )
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
private fun WebGaussianTexturePreviewView(
    model: DepthWallpaperModel,
    previewFps: Int,
    cameraResetKey: Int,
    onModelChange: (DepthWallpaperModel) -> Unit,
    onLoadingChanged: (Boolean) -> Unit,
    backdropCaptureEnabled: Boolean,
    onBackdropFrame: (ImageBitmap?) -> Unit,
    sensorInputEnabled: Boolean,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val renderer = remember(context) {
        NativeGaussianRendererFactory.create(context, NativeGaussianBackendMode.WEB)
    }
    val currentModel by rememberUpdatedState(model)
    val currentOnModelChange by rememberUpdatedState(onModelChange)
    val currentOnLoadingChanged by rememberUpdatedState(onLoadingChanged)
    val currentOnBackdropFrame by rememberUpdatedState(onBackdropFrame)
    var rendererStarted by remember { mutableStateOf(false) }
    var appliedCameraResetKey by remember { mutableStateOf(cameraResetKey) }
    var webLoading by remember(model.gaussianUri) { mutableStateOf(true) }
    var previewSurface by remember { mutableStateOf<Surface?>(null) }
    val normalizedFocusDepth = model.focusDepth.takeIf { it > 1f }
        ?: DepthWallpaperModel.DEFAULT_SOG_FOCUS_DEPTH
    val webPerformanceMode = context
        .getSharedPreferences(App.TIANYIN, Context.MODE_PRIVATE)
        .getBoolean(DepthPrefs.PREF_WEB_PERFORMANCE_MODE, true)

    val webParams = remember(
        model.parallaxStrength,
        model.cameraZoom,
        model.cameraDefaultDistance,
        model.cameraDefaultFov,
        model.cameraCalibrationVersion,
        model.centerOffsetX,
        model.centerOffsetY,
        normalizedFocusDepth,
        model.cameraFov,
        webPerformanceMode
    ) {
        SuperSplatWebParams(
            parallaxStrength = model.parallaxStrength,
            cameraZoom = model.cameraZoom,
            cameraDefaultDistance = model.cameraDefaultDistance,
            cameraDefaultFov = model.cameraDefaultFov,
            cameraCalibrationVersion = model.cameraCalibrationVersion,
            centerOffsetX = model.centerOffsetX,
            centerOffsetY = model.centerOffsetY,
            focusDepth = normalizedFocusDepth,
            cameraFov = model.cameraFov,
            performanceMode = webPerformanceMode
        )
    }

    LaunchedEffect(rendererStarted, webLoading) {
        currentOnLoadingChanged(!rendererStarted || webLoading)
    }

    DisposableEffect(renderer) {
        renderer.setWebLoadingListener { loading -> webLoading = loading }
        renderer.setWebCenterOffsetListener { x, y ->
            currentOnModelChange(currentModel.copy(centerOffsetX = x, centerOffsetY = y))
        }
        renderer.setWebCameraDefaultsListener { distance, fov ->
            if (
                kotlin.math.abs(currentModel.cameraDefaultDistance - distance) > 0.0001f ||
                kotlin.math.abs(currentModel.cameraZoom - distance) > 0.0001f ||
                kotlin.math.abs(currentModel.cameraDefaultFov - fov) > 0.01f ||
                kotlin.math.abs(currentModel.cameraFov - fov) > 0.01f
            ) {
                currentOnModelChange(
                    currentModel.copy(
                        cameraZoom = distance,
                        cameraDefaultDistance = distance,
                        cameraDefaultFov = fov,
                        cameraCalibrationVersion = DepthWallpaperModel.ORIGIN_CAMERA_CALIBRATION_VERSION,
                        cameraFov = fov
                    )
                )
            }
        }
        onDispose {
            renderer.setWebLoadingListener(null)
            renderer.setWebCenterOffsetListener(null)
            renderer.setWebCameraDefaultsListener(null)
            renderer.setBackdropFrameListener(null)
            renderer.setRenderingEnabled(false)
            renderer.stopAndWait(300)
            previewSurface?.release()
            previewSurface = null
            currentOnLoadingChanged(false)
        }
    }

    DisposableEffect(renderer, backdropCaptureEnabled) {
        if (backdropCaptureEnabled) {
            renderer.setBackdropFrameListener { bitmap ->
                currentOnBackdropFrame(bitmap.asImageBitmap())
            }
        } else {
            renderer.setBackdropFrameListener(null)
            currentOnBackdropFrame(null)
        }
        onDispose {
            renderer.setBackdropFrameListener(null)
        }
    }

    LaunchedEffect(rendererStarted, model.gaussianUri, webParams, cameraResetKey) {
        if (!rendererStarted) return@LaunchedEffect
        renderer.loadWebGaussians(model.gaussianUri, webParams)
        if (cameraResetKey != appliedCameraResetKey) {
            renderer.resetCamera()
            appliedCameraResetKey = cameraResetKey
        }
        renderer.requestRender()
    }

    LaunchedEffect(model.cameraDefaultDistance, model.cameraZoom) {
        if (
            model.cameraDefaultDistance > 0f &&
            model.cameraZoom == DepthWallpaperModel.DEFAULT_SOG_CAMERA_ZOOM
        ) {
            currentOnModelChange(model.copy(cameraZoom = model.cameraDefaultDistance))
        }
    }

    AndroidView(
        factory = { viewContext ->
            TextureView(viewContext).apply {
                isClickable = false
                surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                    override fun onSurfaceTextureAvailable(surface: SurfaceTexture, width: Int, height: Int) {
                        val outputSurface = Surface(surface)
                        previewSurface?.release()
                        previewSurface = outputSurface
                        renderer.resize(width, height)
                        renderer.start(outputSurface)
                        renderer.setRenderingEnabled(true)
                        rendererStarted = true
                    }

                    override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, width: Int, height: Int) {
                        renderer.resize(width, height)
                        renderer.requestRender()
                    }

                    override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean {
                        rendererStarted = false
                        renderer.setRenderingEnabled(false)
                        renderer.stopAndWait(300)
                        previewSurface?.release()
                        previewSurface = null
                        return true
                    }

                    override fun onSurfaceTextureUpdated(surface: SurfaceTexture) = Unit
                }
            }
        },
        modifier = modifier
    )

    GaussianPreviewSensorEffect(
        context = context,
        renderer = renderer,
        sensorSensitivity = model.sensorSensitivity,
        previewFps = previewFps,
        enabled = sensorInputEnabled
    )
}

@Composable
private fun GaussianPreviewSensorEffect(
    context: Context,
    renderer: NativeGaussianRenderer,
    sensorSensitivity: Float,
    previewFps: Int,
    enabled: Boolean
) {
    DisposableEffect(context, renderer, sensorSensitivity, previewFps, enabled) {
        if (!enabled) {
            renderer.updateTilt(0f, 0f)
            return@DisposableEffect onDispose { }
        }
        val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
        val gyroSensor = sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE)
        val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val gyroScale = 2.5f * (sensorSensitivity.coerceIn(1f, 9f) / 4.5f)
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
