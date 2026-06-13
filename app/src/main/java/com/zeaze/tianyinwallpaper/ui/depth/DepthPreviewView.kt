package com.zeaze.tianyinwallpaper.ui.depth

import android.content.Context
import android.graphics.SurfaceTexture
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.util.Log
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
import com.zeaze.tianyinwallpaper.renderer.DepthGLRenderer
import com.zeaze.tianyinwallpaper.renderer.NativeGaussianBackendMode
import com.zeaze.tianyinwallpaper.renderer.NativeGaussianRenderer
import com.zeaze.tianyinwallpaper.renderer.NativeGaussianRendererFactory
import com.zeaze.tianyinwallpaper.utils.GaussianPlyLoader
import com.zeaze.tianyinwallpaper.utils.GaussianSceneLoader
import com.zeaze.tianyinwallpaper.utils.GaussianSogLoader
import com.zeaze.tianyinwallpaper.utils.SuperSplatWebParams
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.exp

@Composable
fun DepthPreviewView(
    model: DepthWallpaperModel?,
    previewFps: Int = 60,
    onModelChange: (DepthWallpaperModel) -> Unit = {},
    onLoadingChanged: (Boolean) -> Unit = {},
    webBackdropCaptureEnabled: Boolean = false,
    onWebBackdropFrame: (ImageBitmap?) -> Unit = {},
    sensorInputEnabled: Boolean = true,
    modifier: Modifier = Modifier
) {
    if (model?.isGaussian() == true && model.gaussianUri.isNotBlank()) {
        if (model.useWebGaussianRenderer()) {
            WebGaussianTexturePreviewView(
                model = model,
                previewFps = previewFps,
                onModelChange = onModelChange,
                onLoadingChanged = onLoadingChanged,
                backdropCaptureEnabled = webBackdropCaptureEnabled,
                onBackdropFrame = onWebBackdropFrame,
                sensorInputEnabled = sensorInputEnabled,
                modifier = modifier.fillMaxSize()
            )
        } else {
            GaussianTexturePreviewView(
                model = model,
                previewFps = previewFps,
                onLoadingChanged = onLoadingChanged,
                sensorInputEnabled = sensorInputEnabled,
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
private fun WebGaussianTexturePreviewView(
    model: DepthWallpaperModel,
    previewFps: Int,
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
    var webLoading by remember(model.gaussianUri) { mutableStateOf(true) }
    var previewSurface by remember { mutableStateOf<Surface?>(null) }

    val webParams = remember(
        model.parallaxStrength,
        model.cameraZoom,
        model.centerOffsetX,
        model.centerOffsetY,
        model.focusDepth,
        model.cameraFov,
        model.webPerformanceMode
    ) {
        SuperSplatWebParams(
            parallaxStrength = model.parallaxStrength,
            cameraZoom = model.cameraZoom,
            centerOffsetX = model.centerOffsetX,
            centerOffsetY = model.centerOffsetY,
            focusDepth = model.focusDepth,
            cameraFov = model.cameraFov,
            performanceMode = model.webPerformanceMode
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
        onDispose {
            renderer.setWebLoadingListener(null)
            renderer.setWebCenterOffsetListener(null)
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

    LaunchedEffect(rendererStarted, model.gaussianUri, webParams) {
        if (!rendererStarted) return@LaunchedEffect
        renderer.loadWebGaussians(model.gaussianUri, webParams)
        renderer.requestRender()
    }

    AndroidView(
        factory = { viewContext ->
            TextureView(viewContext).apply {
                isClickable = true
                setOnTouchListener { _, event -> renderer.dispatchTouchEvent(event) }
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
private fun GaussianTexturePreviewView(
    model: DepthWallpaperModel,
    previewFps: Int,
    onLoadingChanged: (Boolean) -> Unit,
    sensorInputEnabled: Boolean,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val appContext = context.applicationContext
    val backendMode = remember(model.gaussianRenderMode) { model.nativeBackendMode() }
    val renderer = remember(appContext, backendMode) { NativeGaussianRendererFactory.create(appContext, backendMode) }
    var contentLoaded by remember(model.gaussianUri, model.gaussianMaxSplats, backendMode) { mutableStateOf(false) }
    var isRendererStarted by remember { mutableStateOf(false) }
    var previewWidth by remember { mutableStateOf(0) }
    var previewHeight by remember { mutableStateOf(0) }
    var activeLoadKey by remember { mutableStateOf<String?>(null) }
    var loadedContentKey by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(contentLoaded, isRendererStarted) {
        onLoadingChanged(!contentLoaded || !isRendererStarted)
    }

    DisposableEffect(renderer) {
        onDispose {
            onLoadingChanged(false)
            renderer.stopAndWait(300)
        }
    }

    LaunchedEffect(model.gaussianUri, model.gaussianMaxSplats, backendMode, previewWidth, previewHeight) {
        if (previewWidth <= 0 || previewHeight <= 0) return@LaunchedEffect
        val previewMaxSplats = model.nativePreviewMaxSplats()
        val viewportAspect = previewWidth.toFloat() / previewHeight.coerceAtLeast(1).toFloat()
        val loadKey = "${model.gaussianUri}|$previewMaxSplats|${(viewportAspect * 1000f).toInt()}|$backendMode"
        if (contentLoaded && loadedContentKey == loadKey) return@LaunchedEffect
        if (activeLoadKey == loadKey) return@LaunchedEffect
        activeLoadKey = loadKey
        onLoadingChanged(true)
        contentLoaded = false
        val loadResult = withContext(Dispatchers.IO) {
            if (model.gaussianMaxSplats > previewMaxSplats) {
                Log.d(
                    TAG,
                    "native preview splat budget capped requested=${model.gaussianMaxSplats} preview=$previewMaxSplats"
                )
            }
            if (backendMode == NativeGaussianBackendMode.VULKAN) {
                var sogStage: GaussianSogLoader.SogGpuStage? = null
                runCatching {
                    GaussianSogLoader.loadGpuSceneStagesOrThrow(
                        context = appContext,
                        uriString = model.gaussianUri,
                        maxSplats = previewMaxSplats,
                        viewportAspect = viewportAspect
                    ) { stage ->
                        sogStage = stage
                        true
                    }
                }.onFailure {
                    Log.w(TAG, "native preview SOG GPU load failed uri=${model.gaussianUri}", it)
                }
                sogStage?.let { return@withContext NativePreviewLoadResult.Sog(it) }
            }
            GaussianSceneLoader.clearSceneCache()
            val result = GaussianSceneLoader.loadSceneDetailed(
                context = appContext,
                uriString = model.gaussianUri,
                maxSplats = previewMaxSplats,
                viewportAspect = viewportAspect
            )
            result.error?.let { Log.w(TAG, "native preview gaussian load failed error=$it uri=${model.gaussianUri}") }
            result.scene?.let(NativePreviewLoadResult::Scene) ?: NativePreviewLoadResult.Failed
        }
        if (activeLoadKey != loadKey) {
            return@LaunchedEffect
        }
        val loaded = when (loadResult) {
            is NativePreviewLoadResult.Sog -> {
                val uploaded = renderer.loadSogGaussians(loadResult.stage.chunks)
                if (uploaded) {
                    val stage = loadResult.stage
                    Log.d(
                        TAG,
                        "native preview SOG GPU loaded stage=${stage.stageIndex + 1}/${stage.stageCount} " +
                            "lod=${stage.lodLevel} chunks=${stage.chunks.size} count=${stage.count} " +
                            "visible=${stage.screenVisibleSplatCount} aux=${stage.auxiliarySplatCount} " +
                            "viewportAspect=$viewportAspect"
                    )
                    true
                } else {
                    val fallback = withContext(Dispatchers.IO) {
                        GaussianSceneLoader.clearSceneCache()
                        val result = GaussianSceneLoader.loadSceneDetailed(
                            context = appContext,
                            uriString = model.gaussianUri,
                            maxSplats = previewMaxSplats,
                            viewportAspect = viewportAspect
                        )
                        result.error?.let {
                            Log.w(TAG, "native preview gaussian fallback load failed error=$it uri=${model.gaussianUri}")
                        }
                        result.scene
                    }
                    if (activeLoadKey == loadKey && fallback != null) {
                        renderer.loadGaussians(fallback)
                        true
                    } else {
                        false
                    }
                }
            }
            is NativePreviewLoadResult.Scene -> {
                renderer.loadGaussians(loadResult.scene)
                true
            }
            NativePreviewLoadResult.Failed -> false
        }
        activeLoadKey = null
        contentLoaded = loaded
        loadedContentKey = if (loaded) loadKey else null
        if (loaded) {
            renderer.resetCamera()
        }
    }

    LaunchedEffect(model.parallaxStrength, model.cameraZoom, model.centerOffsetX, model.centerOffsetY, model.focusDepth) {
        renderer.updateParams(model.parallaxStrength, 0f)
        renderer.updateGaussianParams(model.nativePreviewParams())
        renderer.requestRender()
    }

    AndroidView(
        factory = { viewContext ->
            TextureView(viewContext).apply {
                surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                    override fun onSurfaceTextureAvailable(surface: SurfaceTexture, width: Int, height: Int) {
                        previewWidth = width.coerceAtLeast(1)
                        previewHeight = height.coerceAtLeast(1)
                        renderer.start(Surface(surface))
                        renderer.resize(width, height)
                        renderer.updateParams(model.parallaxStrength, 0f)
                        renderer.updateGaussianParams(model.nativePreviewParams())
                        isRendererStarted = true
                    }

                    override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, width: Int, height: Int) {
                        previewWidth = width.coerceAtLeast(1)
                        previewHeight = height.coerceAtLeast(1)
                        renderer.resize(width, height)
                        renderer.requestRender()
                    }

                    override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean {
                        isRendererStarted = false
                        contentLoaded = false
                        activeLoadKey = null
                        loadedContentKey = null
                        previewWidth = 0
                        previewHeight = 0
                        renderer.stopAndWait(300)
                        return true
                    }

                    override fun onSurfaceTextureUpdated(surface: SurfaceTexture) = Unit
                }
            }
        },
        update = {
            renderer.updateParams(model.parallaxStrength, 0f)
            renderer.updateGaussianParams(model.nativePreviewParams())
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
private const val NATIVE_PREVIEW_MIN_GAUSSIAN_SPLATS = 100_000
private const val NATIVE_PREVIEW_MAX_GAUSSIAN_SPLATS = 800_000

private sealed class NativePreviewLoadResult {
    data class Sog(val stage: GaussianSogLoader.SogGpuStage) : NativePreviewLoadResult()
    data class Scene(val scene: GaussianPlyLoader.GaussianScene) : NativePreviewLoadResult()
    object Failed : NativePreviewLoadResult()
}

private fun DepthWallpaperModel.nativePreviewMaxSplats(): Int {
    return gaussianMaxSplats.coerceIn(
        NATIVE_PREVIEW_MIN_GAUSSIAN_SPLATS,
        NATIVE_PREVIEW_MAX_GAUSSIAN_SPLATS
    )
}

private fun DepthWallpaperModel.nativeBackendMode(): NativeGaussianBackendMode {
    return if (useVulkanGaussianRenderer()) {
        NativeGaussianBackendMode.VULKAN
    } else {
        NativeGaussianBackendMode.GLES
    }
}

private fun DepthWallpaperModel.nativePreviewParams(): DepthGLRenderer.GaussianRenderParams {
    val coverageBoost = gaussianCoverageBoost(
        splats = nativePreviewMaxSplats(),
        minSplats = NATIVE_PREVIEW_MIN_GAUSSIAN_SPLATS,
        maxSplats = NATIVE_PREVIEW_MAX_GAUSSIAN_SPLATS
    )
    return DepthGLRenderer.GaussianRenderParams(
        splatScale = 1.35f * (1f + coverageBoost * 0.28f),
        globalOpacity = 1.0f,
        alphaFalloff = (0.72f - coverageBoost * 0.14f).coerceAtLeast(0.5f),
        minPointSize = 0.5f + coverageBoost * 0.18f,
        maxPointSize = 120f,
        cameraZoom = cameraZoom,
        centerOffsetX = centerOffsetX,
        centerOffsetY = centerOffsetY,
        focusDepthOffset = focusDepth,
        useLayerCache = false
    )
}

private fun gaussianCoverageBoost(
    splats: Int,
    minSplats: Int,
    maxSplats: Int
): Float {
    val span = (maxSplats - minSplats).coerceAtLeast(1).toFloat()
    val normalized = ((splats - minSplats) / span).coerceIn(0f, 1f)
    return 1f - normalized
}
