package com.zeaze.tianyinwallpaper.ui.depth

import android.annotation.SuppressLint
import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.net.Uri
import android.view.Surface
import android.view.WindowManager
import android.webkit.WebView
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.zeaze.tianyinwallpaper.utils.SuperSplatWebController
import com.zeaze.tianyinwallpaper.utils.SuperSplatWebParams
import kotlin.math.exp

@SuppressLint("SetJavaScriptEnabled")
@Composable
fun SuperSplatWebView(
    uriString: String,
    sensorSensitivity: Float,
    parallaxStrength: Float,
    cameraZoom: Float,
    centerOffsetX: Float,
    centerOffsetY: Float,
    focusDepth: Float,
    previewFps: Int,
    onCenterOffsetChange: (Float, Float) -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val modelUri = remember(uriString) { Uri.parse(uriString) }
    val controller = remember { SuperSplatWebController(context.applicationContext) }
    val params = SuperSplatWebParams(
        parallaxStrength = parallaxStrength,
        cameraZoom = cameraZoom,
        centerOffsetX = centerOffsetX,
        centerOffsetY = centerOffsetY,
        focusDepth = focusDepth
    )

    DisposableEffect(Unit) {
        onDispose {
            controller.destroy()
        }
    }

    AndroidView(
        factory = { context ->
            controller.createWebView(context)
        },
        update = { webView ->
            controller.attachWebView(webView)
            controller.onCenterOffsetChange = onCenterOffsetChange
            controller.modelUri = modelUri
            controller.pendingParams = params
            controller.loadModelIfNeeded(uriString)
        },
        modifier = modifier
    )

    LaunchedEffect(params) {
        controller.setParams(params)
    }

    DisposableEffect(lifecycleOwner, uriString) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                controller.resetSensorBaseline = true
                controller.resetCamera()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        controller.resetSensorBaseline = true
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    DisposableEffect(sensorSensitivity, previewFps) {
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

                if (controller.resetSensorBaseline) {
                    tiltX = 0f
                    tiltY = 0f
                    filteredTiltX = 0f
                    filteredTiltY = 0f
                    filterInitialized = true
                    lastTimestamp = now
                    lastFilterTimestamp = now
                    controller.resetSensorBaseline = false
                    return
                }
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
                val filterDt = if (lastFilterTimestamp == 0L) {
                    0f
                } else {
                    ((now - lastFilterTimestamp) / 1_000_000_000f).coerceIn(0f, 0.1f)
                }
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
                if (lastDispatchTimestamp != 0L && now - lastDispatchTimestamp < minDispatchIntervalNs) {
                    return
                }
                lastDispatchTimestamp = now
                controller.setTilt(filteredTiltX, filteredTiltY)
            }

            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
        }

        if (gyroSensor != null) {
            sensorManager.registerListener(
                listener,
                gyroSensor,
                SensorManager.SENSOR_DELAY_GAME
            )
        }

        onDispose {
            sensorManager.unregisterListener(listener)
        }
    }
}
