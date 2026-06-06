package com.zeaze.tianyinwallpaper.service

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.Color
import android.graphics.PixelFormat
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.service.wallpaper.WallpaperService
import android.util.Log
import android.view.Surface
import android.view.SurfaceHolder
import android.view.View
import android.view.WindowManager
import android.webkit.WebView
import com.zeaze.tianyinwallpaper.App
import com.zeaze.tianyinwallpaper.model.DepthWallpaperModel
import com.zeaze.tianyinwallpaper.renderer.DepthGLRenderer
import com.zeaze.tianyinwallpaper.renderer.NativeGaussianRenderer
import com.zeaze.tianyinwallpaper.renderer.NativeGaussianRendererFactory
import com.zeaze.tianyinwallpaper.utils.DepthPrefs
import com.zeaze.tianyinwallpaper.utils.GaussianPlyLoader
import com.zeaze.tianyinwallpaper.utils.GaussianSceneLoader
import com.zeaze.tianyinwallpaper.utils.SuperSplatWebController
import com.zeaze.tianyinwallpaper.utils.SuperSplatWebParams
import kotlin.math.exp
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.sqrt

class DepthWallpaperService : WallpaperService() {
    private var activeEngine: DepthWallpaperEngine? = null

    override fun onCreateEngine(): Engine = DepthWallpaperEngine()

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        GaussianSceneLoader.trimMemory(level)
    }

    inner class DepthWallpaperEngine : Engine() {
        private var renderer: NativeGaussianRenderer? = null
        private var webSplatController: SuperSplatWebController? = null
        private var webSplatView: WebView? = null
        private var sensorManager: SensorManager? = null
        private var motionSensor: Sensor? = null
        private var motionSensorPreference = 0
        private val mainHandler = Handler(Looper.getMainLooper())
        private var windowManager: WindowManager? = null
        private var pref: SharedPreferences? = null
        private var model: DepthWallpaperModel? = null

        private var isVisible = false
        private var surfaceReady = false
        private var surfaceHolderRef: SurfaceHolder? = null
        private var surfaceWidth = 1
        private var surfaceHeight = 1
        private var gaussianWebActive = false
        private var webDrawScheduled = false

        private var loadVersion = 0
        private var loadedImageKey: String? = null
        private var lastSensorEventMs = 0L
        private var lastDispatchNs = 0L
        private var sensorEventCount = 0L
        private var dispatchCount = 0L
        private var lastSensorLogMs = 0L
        private var lastDispatchLogMs = 0L
        private var hasBaselineTilt = false
        private var baselineTiltX = 0f
        private var baselineTiltY = 0f
        private var filteredTiltX = 0f
        private var filteredTiltY = 0f
        private var lastTiltX = 0f
        private var lastTiltY = 0f
        private var hasGravity = false
        private var gyroLastTimestamp = 0L
        private var gyroTiltX = 0f
        private var gyroTiltY = 0f
        private var gyroFilteredTiltX = 0f
        private var gyroFilteredTiltY = 0f
        private var gyroFilterInitialized = false
        private var gyroLastFilterTimestamp = 0L
        private val gravity = FloatArray(3)
        private val rotationMatrix = FloatArray(9)
        private val remappedRotationMatrix = FloatArray(9)
        private val orientationAngles = FloatArray(3)

        private val prefChangeListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == DepthPrefs.PREF_DEPTH_WALLPAPERS || key == DepthPrefs.PREF_DEPTH_ACTIVE_ID) {
                loadActiveModel()
                model?.let {
                    if (it.isGaussian() && gaussianWebActive) {
                        updateWebSplatParams(it)
                    } else {
                        renderer?.updateParams(it.renderParallaxStrength(), 0f)
                        if (it.isGaussian()) renderer?.updateGaussianParams(it.nativeGaussianParams())
                    }
                }
                if (isVisible && surfaceReady && model?.contentKey() != loadedImageKey) {
                    loadContent()
                }
            }
        }

        private val webDrawRunnable = object : Runnable {
            override fun run() {
                webDrawScheduled = false
                if (!gaussianWebActive || !isVisible || !surfaceReady) return
                drawWebSplatFrame()
                mainHandler.postDelayed(this, WEBVIEW_FRAME_INTERVAL_MS)
                webDrawScheduled = true
            }
        }

        init {
            activeEngine = this
        }

        private val sensorWatchdog = object : Runnable {
            override fun run() {
                if (!isVisible) return
                val now = SystemClock.elapsedRealtime()
                if (surfaceReady && (lastSensorEventMs == 0L || now - lastSensorEventMs > SENSOR_STALE_TIMEOUT_MS)) {
                    Log.w(
                        TAG,
                        "sensor stale, restart. visible=$isVisible surfaceReady=$surfaceReady " +
                            "lastSensorAgeMs=${if (lastSensorEventMs == 0L) -1 else now - lastSensorEventMs} " +
                            "sensor=${motionSensor?.name}"
                    )
                    restartMotionSensor()
                    lastSensorEventMs = now
                    renderer?.requestRender()
                }
                mainHandler.postDelayed(this, SENSOR_WATCHDOG_INTERVAL_MS)
            }
        }

        override fun onCreate(surfaceHolder: SurfaceHolder) {
            super.onCreate(surfaceHolder)
            surfaceHolder.setFormat(PixelFormat.RGBX_8888)
            pref = getSharedPreferences(App.TIANYIN, MODE_PRIVATE)
            pref?.registerOnSharedPreferenceChangeListener(prefChangeListener)
            sensorManager = getSystemService(Context.SENSOR_SERVICE) as SensorManager
            motionSensor = findMotionSensor()
            windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
            renderer = NativeGaussianRendererFactory.create(this@DepthWallpaperService).also { it.setRenderingEnabled(false) }
            loadActiveModel()
            Log.d(TAG, "onCreate sensor=${motionSensor?.name ?: "none"} model=${model?.id} gaussian=${model?.isGaussian()}")
        }

        override fun onSurfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
            super.onSurfaceChanged(holder, format, width, height)
            surfaceReady = true
            surfaceHolderRef = holder
            surfaceWidth = width.coerceAtLeast(1)
            surfaceHeight = height.coerceAtLeast(1)
            Log.d(TAG, "onSurfaceChanged ${surfaceWidth}x$surfaceHeight visible=$isVisible")
            stopWebSplatMode(destroy = true)
            ensureRendererStarted()
            if (isVisible) {
                loadedImageKey = null
                loadContent()
            }
        }

        override fun onSurfaceDestroyed(holder: SurfaceHolder) {
            super.onSurfaceDestroyed(holder)
            surfaceReady = false
            surfaceHolderRef = null
            loadVersion++
            loadedImageKey = null
            Log.d(TAG, "onSurfaceDestroyed visible=$isVisible loadVersion=$loadVersion")
            unregisterSensor()
            stopWebSplatMode(destroy = true)
            renderer?.stopAndWait(500)
        }

        override fun onVisibilityChanged(visible: Boolean) {
            super.onVisibilityChanged(visible)
            isVisible = visible
            Log.d(
                TAG,
                "onVisibilityChanged visible=$visible surfaceReady=$surfaceReady " +
                    "loadedKey=$loadedImageKey modelKey=${model?.contentKey()}"
            )
            if (visible) {
                loadActiveModel()
                model?.let {
                    if (it.isGaussian() && it.useWebGaussianRenderer()) {
                        renderer?.setRenderingEnabled(false)
                    } else {
                        stopWebSplatMode(destroy = true)
                        ensureRendererStarted()
                        renderer?.setRenderingEnabled(true)
                        renderer?.updateParams(it.renderParallaxStrength(), 0f)
                        if (it.isGaussian()) renderer?.updateGaussianParams(it.nativeGaussianParams())
                    }
                }
                registerSensor()
                if (surfaceReady && model?.contentKey() != loadedImageKey) {
                    loadContent()
                } else {
                    if (model?.isGaussian() == true && gaussianWebActive) {
                        startWebDrawLoop()
                    } else {
                        renderer?.requestRender()
                    }
                }
            } else {
                unregisterSensor()
                renderer?.setRenderingEnabled(false)
                stopWebDrawLoop()
            }
        }

        override fun onDestroy() {
            super.onDestroy()
            if (activeEngine == this) activeEngine = null
            loadVersion++
            Log.d(TAG, "onDestroy loadVersion=$loadVersion")
            unregisterSensor()
            stopWebSplatMode(destroy = true)
            pref?.unregisterOnSharedPreferenceChangeListener(prefChangeListener)
            renderer?.stop()
            renderer = null
        }

        fun reload() {
            Log.d(TAG, "reload")
            loadActiveModel()
            if (isVisible && surfaceReady) {
                loadContent()
            }
        }

        private fun loadActiveModel() {
            model = pref?.let { DepthPrefs.loadActiveWallpaper(it) }
            Log.d(TAG, "loadActiveModel id=${model?.id} gaussian=${model?.isGaussian()} gaussianUri=${model?.gaussianUri?.isNotBlank()}")
        }

        private fun loadContent() {
            val target = model ?: return
            val targetKey = target.contentKey()
            Log.d(
                TAG,
                "loadContent start key=$targetKey gaussian=${target.isGaussian()} " +
                    "visible=$isVisible surface=$surfaceWidth x $surfaceHeight loaded=$loadedImageKey"
            )
            val currentVersion = ++loadVersion
            if (target.isGaussian()) {
                if (target.useWebGaussianRenderer()) {
                    loadGaussianWebContent(target, targetKey, currentVersion)
                } else {
                    loadGaussianNativeContent(target, targetKey, currentVersion, "native-gaussian")
                }
                return
            }
            stopWebSplatMode(destroy = true)
            renderer?.setRenderingEnabled(false)
            loadedImageKey = null
            Log.w(TAG, "loadContent ignored non-Gaussian depth wallpaper id=${target.id}")
        }

        private fun loadGaussianWebContent(
            target: DepthWallpaperModel,
            targetKey: String,
            currentVersion: Int
        ) {
            mainHandler.post {
                if (currentVersion != loadVersion || !isVisible || !surfaceReady) {
                    Log.w(
                        TAG,
                        "loadContent web gaussian skipped current=$currentVersion loadVersion=$loadVersion " +
                            "visible=$isVisible surfaceReady=$surfaceReady"
                    )
                    return@post
                }
                activateWebSplatMode()
                val controller = webSplatController ?: return@post
                loadedImageKey = targetKey
                controller.modelUri = Uri.parse(target.gaussianUri)
                controller.pendingParams = target.webSplatParams()
                controller.loadModelIfNeeded(target.gaussianUri)
                controller.setParams(target.webSplatParams())
                startWebDrawLoop()
                mainHandler.postDelayed({
                    if (
                        currentVersion == loadVersion &&
                        isVisible &&
                        surfaceReady &&
                        webSplatController?.pageReady != true
                    ) {
                        Log.w(TAG, "web gaussian not ready; keep waiting for WebView renderer")
                        startWebDrawLoop()
                    }
                }, WEBVIEW_READY_TIMEOUT_MS)
                Log.d(
                    TAG,
                    "loadContent web gaussian loaded uri=${target.gaussianUri} " +
                        "surface=${surfaceWidth}x$surfaceHeight"
                )
            }
        }

        private fun loadGaussianNativeContent(
            target: DepthWallpaperModel,
            targetKey: String,
            currentVersion: Int,
            reason: String
        ) {
            stopWebSplatMode(destroy = true)
            ensureRendererStarted()
            renderer?.setRenderingEnabled(isVisible)
            renderer?.updateParams(target.renderParallaxStrength(), 0f)
            renderer?.updateGaussianParams(target.nativeGaussianParams())
            renderer?.resetCamera()
            Thread {
                val viewportAspect = surfaceWidth.toFloat() / surfaceHeight.coerceAtLeast(1).toFloat()
                val fastResult = GaussianSceneLoader.loadSceneDetailed(
                    context = applicationContext,
                    uriString = target.gaussianUri,
                    maxSplats = SERVICE_FAST_GAUSSIAN_SPLATS,
                    viewportAspect = viewportAspect
                )
                val fastScene = fastResult.scene
                if (fastScene != null && currentVersion == loadVersion && isVisible && surfaceReady) {
                    deliverNativeGaussianScene(fastScene, targetKey, reason, "fast", viewportAspect)
                } else {
                    Log.w(
                        TAG,
                        "loadContent gaussian native fast skipped reason=$reason scene=${fastScene != null} " +
                            "error=${fastResult.error} current=$currentVersion loadVersion=$loadVersion " +
                            "visible=$isVisible surfaceReady=$surfaceReady"
                    )
                    if (currentVersion == loadVersion && isVisible && surfaceReady) {
                        fallbackNativeGaussianToWeb(target, targetKey, currentVersion, "fast-load-failed", fastResult.error)
                    }
                    return@Thread
                }

                if (SERVICE_MAX_GAUSSIAN_SPLATS <= SERVICE_FAST_GAUSSIAN_SPLATS) return@Thread
                val fullResult = GaussianSceneLoader.loadSceneDetailed(
                    context = applicationContext,
                    uriString = target.gaussianUri,
                    maxSplats = SERVICE_MAX_GAUSSIAN_SPLATS,
                    viewportAspect = viewportAspect
                )
                val fullScene = fullResult.scene
                if (
                    fullScene != null &&
                    fullScene.count > fastScene.count &&
                    currentVersion == loadVersion &&
                    isVisible &&
                    surfaceReady
                ) {
                    deliverNativeGaussianScene(fullScene, targetKey, reason, "full", viewportAspect)
                } else if (fullResult.error != null) {
                    Log.w(TAG, "loadContent gaussian native full skipped reason=$reason error=${fullResult.error}")
                }
            }.also { it.name = "DepthGaussianFallbackLoader" }.start()
        }

        private fun fallbackNativeGaussianToWeb(
            target: DepthWallpaperModel,
            targetKey: String,
            currentVersion: Int,
            reason: String,
            error: String?
        ) {
            mainHandler.post {
                if (currentVersion != loadVersion || !isVisible || !surfaceReady || model?.contentKey() != targetKey) {
                    Log.w(
                        TAG,
                        "native gaussian web fallback skipped reason=$reason current=$currentVersion " +
                            "loadVersion=$loadVersion visible=$isVisible surfaceReady=$surfaceReady error=$error"
                    )
                    return@post
                }
                Log.w(TAG, "native gaussian fallback to WebView reason=$reason error=$error uri=${target.gaussianUri}")
                loadGaussianWebContent(target, targetKey, currentVersion)
            }
        }

        private fun deliverNativeGaussianScene(
            scene: GaussianPlyLoader.GaussianScene,
            targetKey: String,
            reason: String,
            quality: String,
            viewportAspect: Float
        ) {
            loadedImageKey = targetKey
            Log.d(
                TAG,
                "loadContent gaussian native loaded reason=$reason quality=$quality count=${scene.count} " +
                    "visible=${scene.screenVisibleSplatCount} aux=${scene.auxiliarySplatCount} " +
                    "image=${scene.imageWidth}x${scene.imageHeight} viewportAspect=$viewportAspect"
            )
            renderer?.loadGaussians(scene)
        }

        private fun activateWebSplatMode() {
            gaussianWebActive = true
            renderer?.setRenderingEnabled(false)
            renderer?.stopAndWait(500)
            val controller = webSplatController ?: SuperSplatWebController(applicationContext).also {
                it.onRenderRequested = { startWebDrawLoop() }
                webSplatController = it
            }
            if (webSplatView == null) {
                webSplatView = controller.createWebView(this@DepthWallpaperService).apply {
                    setLayerType(View.LAYER_TYPE_HARDWARE, null)
                }
            }
            layoutWebSplatView()
        }

        private fun stopWebSplatMode(destroy: Boolean) {
            gaussianWebActive = false
            stopWebDrawLoop()
            if (destroy) {
                webSplatController?.destroy()
                webSplatController = null
                webSplatView = null
            }
        }

        private fun layoutWebSplatView() {
            val view = webSplatView ?: return
            val widthSpec = View.MeasureSpec.makeMeasureSpec(surfaceWidth.coerceAtLeast(1), View.MeasureSpec.EXACTLY)
            val heightSpec = View.MeasureSpec.makeMeasureSpec(surfaceHeight.coerceAtLeast(1), View.MeasureSpec.EXACTLY)
            view.measure(widthSpec, heightSpec)
            view.layout(0, 0, surfaceWidth.coerceAtLeast(1), surfaceHeight.coerceAtLeast(1))
        }

        private fun updateWebSplatParams(target: DepthWallpaperModel) {
            webSplatController?.setParams(target.webSplatParams())
        }

        private fun startWebDrawLoop() {
            if (!gaussianWebActive || !isVisible || !surfaceReady || webDrawScheduled) return
            webDrawScheduled = true
            mainHandler.post(webDrawRunnable)
        }

        private fun stopWebDrawLoop() {
            webDrawScheduled = false
            mainHandler.removeCallbacks(webDrawRunnable)
        }

        private fun drawWebSplatFrame() {
            val holder = surfaceHolderRef ?: return
            val view = webSplatView ?: return
            try {
                var hardwareCanvas = false
                val canvas = runCatching {
                    holder.surface.lockHardwareCanvas().also {
                        hardwareCanvas = true
                    }
                }.getOrNull() ?: holder.lockCanvas() ?: return
                try {
                    canvas.drawColor(Color.BLACK)
                    view.draw(canvas)
                } finally {
                    if (hardwareCanvas) {
                        holder.surface.unlockCanvasAndPost(canvas)
                    } else {
                        holder.unlockCanvasAndPost(canvas)
                    }
                }
            } catch (error: Throwable) {
                Log.w(TAG, "draw web gaussian failed", error)
            }
        }

        private fun ensureRendererStarted() {
            val holder = surfaceHolderRef ?: return
            if (!surfaceReady) return
            renderer?.start(holder.surface)
            renderer?.resize(surfaceWidth, surfaceHeight)
        }

        private fun DepthWallpaperModel.webSplatParams(): SuperSplatWebParams {
            return SuperSplatWebParams(
                parallaxStrength = renderParallaxStrength(),
                cameraZoom = cameraZoom,
                centerOffsetX = centerOffsetX,
                centerOffsetY = centerOffsetY,
                focusDepth = focusDepth
            )
        }

        private fun DepthWallpaperModel.nativeGaussianParams(): DepthGLRenderer.GaussianRenderParams {
            return DepthGLRenderer.GaussianRenderParams(
                splatScale = 1.05f,
                globalOpacity = 1.0f,
                alphaFalloff = 1.0f,
                minPointSize = 0.5f,
                maxPointSize = 120f,
                cameraZoom = cameraZoom,
                centerOffsetX = centerOffsetX,
                centerOffsetY = centerOffsetY,
                focusDepthOffset = focusDepth,
                useLayerCache = false
            )
        }

        private fun DepthWallpaperModel.contentKey(): String {
            return "$id|$gaussianUri|$gaussianRenderMode|gaussian-v9"
        }

        private fun DepthWallpaperModel.renderParallaxStrength(): Float {
            return parallaxStrength
        }

        private fun DepthWallpaperModel.renderSensorSensitivity(): Float {
            return sensorSensitivity
        }

        private fun registerSensor() {
            unregisterSensor()
            resetSensorState()
            motionSensorPreference = 0
            val sensor = findMotionSensor(motionSensorPreference).also { motionSensor = it }
            sensor?.let {
                val ok = sensorManager?.registerListener(sensorListener, sensor, SensorManager.SENSOR_DELAY_GAME) == true
                lastSensorEventMs = if (ok) SystemClock.elapsedRealtime() else 0L
                startSensorWatchdog()
                Log.d(TAG, "registerSensor ok=$ok sensor=${sensor.name} type=${sensor.type}")
            } ?: Log.w(TAG, "registerSensor no sensor")
        }

        private fun sensorTypeName(type: Int): String {
            return when (type) {
                Sensor.TYPE_GYROSCOPE -> "gyroscope"
                Sensor.TYPE_GAME_ROTATION_VECTOR -> "game_rotation"
                Sensor.TYPE_ROTATION_VECTOR -> "rotation"
                Sensor.TYPE_ACCELEROMETER -> "accelerometer"
                else -> type.toString()
            }
        }

        private fun unregisterSensor() {
            Log.d(TAG, "unregisterSensor events=$sensorEventCount dispatches=$dispatchCount")
            sensorManager?.unregisterListener(sensorListener)
            stopSensorWatchdog()
        }

        private fun restartMotionSensor() {
            sensorManager?.unregisterListener(sensorListener)
            val candidates = availableMotionSensors()
            if (candidates.isEmpty()) {
                Log.w(TAG, "restartMotionSensor no candidates")
                return
            }
            val currentIndex = candidates.indexOfFirst { it.type == motionSensor?.type }
                .takeIf { it >= 0 }
                ?: motionSensorPreference
            motionSensorPreference = (currentIndex + 1).floorMod(candidates.size)
            val sensor = candidates[motionSensorPreference]
            motionSensor = sensor
            resetSensorState()
            val ok = sensorManager?.registerListener(sensorListener, sensor, SensorManager.SENSOR_DELAY_GAME) == true
            lastSensorEventMs = if (ok) SystemClock.elapsedRealtime() else 0L
            Log.w(
                TAG,
                "restartMotionSensor ok=$ok sensor=${sensor.name} type=${sensor.type} " +
                    "preference=$motionSensorPreference candidates=${candidates.joinToString { sensorTypeName(it.type) }}"
            )
        }

        private fun startSensorWatchdog() {
            mainHandler.removeCallbacks(sensorWatchdog)
            mainHandler.postDelayed(sensorWatchdog, SENSOR_WATCHDOG_INTERVAL_MS)
        }

        private fun stopSensorWatchdog() {
            mainHandler.removeCallbacks(sensorWatchdog)
        }

        private fun resetSensorState() {
            lastDispatchNs = 0L
            hasBaselineTilt = false
            baselineTiltX = 0f
            baselineTiltY = 0f
            filteredTiltX = 0f
            filteredTiltY = 0f
            lastTiltX = 0f
            lastTiltY = 0f
            gyroLastTimestamp = 0L
            gyroTiltX = 0f
            gyroTiltY = 0f
            gyroFilteredTiltX = 0f
            gyroFilteredTiltY = 0f
            gyroFilterInitialized = false
            gyroLastFilterTimestamp = 0L
            lastSensorEventMs = 0L
            sensorEventCount = 0L
            dispatchCount = 0L
            lastSensorLogMs = 0L
            lastDispatchLogMs = 0L
            hasGravity = false
            gravity.fill(0f)
            if (model?.isGaussian() == true && gaussianWebActive) {
                webSplatController?.resetSensorBaseline = true
                webSplatController?.setTilt(0f, 0f)
            } else {
                renderer?.resetCamera()
            }
        }

        private val sensorListener = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent?) {
                val e = event ?: return
                if (!isVisible) return
                sensorEventCount++
                val now = SystemClock.elapsedRealtime()
                lastSensorEventMs = now
                if (now - lastSensorLogMs > SENSOR_LOG_INTERVAL_MS) {
                    lastSensorLogMs = now
                    Log.d(
                        TAG,
                        "sensor event #$sensorEventCount type=${sensorTypeName(e.sensor.type)} " +
                            "values=${e.values.take(3).joinToString(prefix = "[", postfix = "]") { String.format("%.3f", it) }} " +
                            "visible=$isVisible surface=$surfaceReady"
                    )
                }
                when (e.sensor.type) {
                    Sensor.TYPE_GYROSCOPE -> handleGyroscope(e)
                    Sensor.TYPE_GAME_ROTATION_VECTOR,
                    Sensor.TYPE_ROTATION_VECTOR -> handleRotationVector(e)
                    Sensor.TYPE_ACCELEROMETER -> handleAccelerometer(e)
                }
            }

            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
        }

        private fun handleRotationVector(event: SensorEvent) {
            SensorManager.getRotationMatrixFromVector(rotationMatrix, event.values)
            val rotation = currentDisplayRotation()
            val remapped = SensorManager.remapCoordinateSystem(
                rotationMatrix,
                displayXAxis(rotation),
                displayYAxis(rotation),
                remappedRotationMatrix
            )
            val matrix = if (remapped) remappedRotationMatrix else rotationMatrix
            SensorManager.getOrientation(matrix, orientationAngles)
            val rawX = -orientationAngles[2]
            val rawY = orientationAngles[1]
            dispatchAbsoluteTilt(rawX, rawY, event.timestamp)
        }

        private fun handleAccelerometer(event: SensorEvent) {
            if (!hasGravity) {
                gravity[0] = event.values[0]
                gravity[1] = event.values[1]
                gravity[2] = event.values[2]
                hasGravity = true
            } else {
                gravity[0] += (event.values[0] - gravity[0]) * GRAVITY_FILTER_ALPHA
                gravity[1] += (event.values[1] - gravity[1]) * GRAVITY_FILTER_ALPHA
                gravity[2] += (event.values[2] - gravity[2]) * GRAVITY_FILTER_ALPHA
            }

            val rotation = currentDisplayRotation()
            val screenX = when (rotation) {
                Surface.ROTATION_90 -> -gravity[1]
                Surface.ROTATION_180 -> -gravity[0]
                Surface.ROTATION_270 -> gravity[1]
                else -> gravity[0]
            }
            val screenY = when (rotation) {
                Surface.ROTATION_90 -> gravity[0]
                Surface.ROTATION_180 -> -gravity[1]
                Surface.ROTATION_270 -> -gravity[0]
                else -> gravity[1]
            }
            val z = gravity[2]
            val rawX = atan2(screenX, sqrt(screenY * screenY + z * z))
            val rawY = atan2(-screenY, sqrt(screenX * screenX + z * z))
            dispatchAbsoluteTilt(rawX, rawY, event.timestamp)
        }

        private fun handleGyroscope(event: SensorEvent) {
            val now = event.timestamp
            val controller = webSplatController
            if (controller?.resetSensorBaseline == true || !gyroFilterInitialized) {
                gyroTiltX = 0f
                gyroTiltY = 0f
                gyroFilteredTiltX = 0f
                gyroFilteredTiltY = 0f
                gyroLastTimestamp = now
                gyroLastFilterTimestamp = now
                gyroFilterInitialized = true
                controller?.resetSensorBaseline = false
                return
            }

            val dt = if (gyroLastTimestamp == 0L) 0f else (now - gyroLastTimestamp) / 1_000_000_000f
            gyroLastTimestamp = now
            if (dt <= 0f || dt > 0.5f) return

            val rotation = currentDisplayRotation()
            val (gx, gy) = when (rotation) {
                Surface.ROTATION_90 -> -event.values[1] to event.values[0]
                Surface.ROTATION_180 -> -event.values[0] to -event.values[1]
                Surface.ROTATION_270 -> event.values[1] to -event.values[0]
                else -> event.values[0] to event.values[1]
            }

            val sensitivity = model?.renderSensorSensitivity() ?: 4.5f
            val gyroScale = 2.5f * (sensitivity.coerceIn(1f, 9f) / 4.5f)
            gyroTiltX = (gyroTiltX + gy * dt * gyroScale).coerceIn(-1f, 1f)
            gyroTiltY = (gyroTiltY + gx * dt * gyroScale).coerceIn(-1f, 1f)
            val filterDt = if (gyroLastFilterTimestamp == 0L) {
                0f
            } else {
                ((now - gyroLastFilterTimestamp) / 1_000_000_000f).coerceIn(0f, 0.1f)
            }
            gyroLastFilterTimestamp = now
            val alpha = (1f - exp((-filterDt / 0.12f).toDouble()).toFloat()).coerceIn(0.08f, 0.45f)
            gyroFilteredTiltX += (gyroTiltX - gyroFilteredTiltX) * alpha
            gyroFilteredTiltY += (gyroTiltY - gyroFilteredTiltY) * alpha

            if (
                now - lastDispatchNs > MIN_DISPATCH_INTERVAL_NS &&
                (abs(gyroFilteredTiltX - lastTiltX) > MIN_TILT_CHANGE || abs(gyroFilteredTiltY - lastTiltY) > MIN_TILT_CHANGE)
            ) {
                lastDispatchNs = now
                lastTiltX = gyroFilteredTiltX
                lastTiltY = gyroFilteredTiltY
                dispatchCount++
                dispatchDepthTilt(gyroFilteredTiltX, gyroFilteredTiltY)
            }
        }

        private fun dispatchAbsoluteTilt(rawX: Float, rawY: Float, timestampNs: Long) {
            if (!hasBaselineTilt) {
                hasBaselineTilt = true
                baselineTiltX = rawX
                baselineTiltY = rawY
                lastDispatchNs = timestampNs
                Log.d(TAG, "tilt baseline raw=(${String.format("%.3f", rawX)}, ${String.format("%.3f", rawY)})")
                return
            }

            val targetX = angleDelta(rawX, baselineTiltX).coerceIn(-MAX_ANGLE_RAD, MAX_ANGLE_RAD)
            val targetY = angleDelta(rawY, baselineTiltY).coerceIn(-MAX_ANGLE_RAD, MAX_ANGLE_RAD)
            filteredTiltX += (targetX - filteredTiltX) * TILT_FILTER_ALPHA
            filteredTiltY += (targetY - filteredTiltY) * TILT_FILTER_ALPHA

            val sensitivity = model?.renderSensorSensitivity() ?: 4.5f
            val threshold = (0.78f - sensitivity.coerceIn(1f, 9f) * 0.055f).coerceIn(0.26f, 0.72f)
            val tiltX = (filteredTiltX / threshold).coerceIn(-1f, 1f)
            val tiltY = (filteredTiltY / threshold).coerceIn(-1f, 1f)
            val minDispatchIntervalNs = MIN_DISPATCH_INTERVAL_NS
            if (
                timestampNs - lastDispatchNs > minDispatchIntervalNs &&
                (abs(tiltX - lastTiltX) > MIN_TILT_CHANGE || abs(tiltY - lastTiltY) > MIN_TILT_CHANGE)
            ) {
                lastDispatchNs = timestampNs
                lastTiltX = tiltX
                lastTiltY = tiltY
                dispatchCount++
                val now = SystemClock.elapsedRealtime()
                if (now - lastDispatchLogMs > DISPATCH_LOG_INTERVAL_MS) {
                    lastDispatchLogMs = now
                    Log.d(
                        TAG,
                        "dispatch tilt #$dispatchCount raw=(${String.format("%.3f", rawX)}, ${String.format("%.3f", rawY)}) " +
                            "filtered=(${String.format("%.3f", filteredTiltX)}, ${String.format("%.3f", filteredTiltY)}) " +
                            "tilt=(${String.format("%.3f", tiltX)}, ${String.format("%.3f", tiltY)})"
                    )
                }
                dispatchDepthTilt(tiltX, tiltY)
            }
        }

        private fun dispatchDepthTilt(tiltX: Float, tiltY: Float) {
            when {
                model?.isGaussian() == true && gaussianWebActive -> {
                    webSplatController?.setTilt(tiltX, tiltY)
                    startWebDrawLoop()
                }
                else -> renderer?.updateTilt(tiltX, tiltY)
            }
        }

        private fun angleDelta(value: Float, baseline: Float): Float {
            var delta = value - baseline
            while (delta > Math.PI.toFloat()) delta -= TWO_PI
            while (delta < -Math.PI.toFloat()) delta += TWO_PI
            return delta
        }

        private fun findMotionSensor(preference: Int = 0): Sensor? {
            val candidates = availableMotionSensors()
            if (candidates.isEmpty()) return null
            return candidates[preference.floorMod(candidates.size)]
        }

        private fun availableMotionSensors(): List<Sensor> {
            val manager = sensorManager ?: return emptyList()
            return listOfNotNull(manager.getDefaultSensor(Sensor.TYPE_GYROSCOPE))
        }

        private fun Int.floorMod(modulus: Int): Int {
            if (modulus <= 0) return 0
            val value = this % modulus
            return if (value < 0) value + modulus else value
        }

        private fun displayXAxis(rotation: Int): Int {
            return when (rotation) {
                Surface.ROTATION_90 -> SensorManager.AXIS_Y
                Surface.ROTATION_180 -> SensorManager.AXIS_MINUS_X
                Surface.ROTATION_270 -> SensorManager.AXIS_MINUS_Y
                else -> SensorManager.AXIS_X
            }
        }

        private fun displayYAxis(rotation: Int): Int {
            return when (rotation) {
                Surface.ROTATION_90 -> SensorManager.AXIS_MINUS_X
                Surface.ROTATION_180 -> SensorManager.AXIS_MINUS_Y
                Surface.ROTATION_270 -> SensorManager.AXIS_X
                else -> SensorManager.AXIS_Y
            }
        }

        private fun currentDisplayRotation(): Int {
            @Suppress("DEPRECATION")
            return windowManager?.defaultDisplay?.rotation ?: Surface.ROTATION_0
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_RELOAD) {
            activeEngine?.reload()
        }
        return super.onStartCommand(intent, flags, startId)
    }

    companion object {
        private const val TAG = "DepthWallpaperService"
        const val ACTION_RELOAD = "com.zeaze.tianyinwallpaper.DEPTH_RELOAD"
        private const val MAX_ANGLE_RAD = 0.82f
        private const val TWO_PI = 6.2831855f
        private const val TILT_FILTER_ALPHA = 0.52f
        private const val GRAVITY_FILTER_ALPHA = 0.32f
        private const val MIN_TILT_CHANGE = 0.002f
        private const val MIN_DISPATCH_INTERVAL_NS = 16_000_000L
        private const val SENSOR_WATCHDOG_INTERVAL_MS = 3_000L
        private const val SENSOR_STALE_TIMEOUT_MS = 4_500L
        private const val SENSOR_LOG_INTERVAL_MS = 1_000L
        private const val DISPATCH_LOG_INTERVAL_MS = 1_000L
        private const val WEBVIEW_FRAME_INTERVAL_MS = 16L
        private const val WEBVIEW_READY_TIMEOUT_MS = 4_000L
        private const val SERVICE_FAST_GAUSSIAN_SPLATS = 500_000
        private const val SERVICE_MAX_GAUSSIAN_SPLATS = 1_500_000
    }
}
