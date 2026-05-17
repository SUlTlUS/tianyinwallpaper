package com.zeaze.tianyinwallpaper.service

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.PixelFormat
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.service.wallpaper.WallpaperService
import android.util.Log
import android.view.Surface
import android.view.SurfaceHolder
import android.view.WindowManager
import com.zeaze.tianyinwallpaper.App
import com.zeaze.tianyinwallpaper.model.DepthWallpaperModel
import com.zeaze.tianyinwallpaper.renderer.DepthGLRenderer
import com.zeaze.tianyinwallpaper.utils.DepthImageProcessor
import com.zeaze.tianyinwallpaper.utils.DepthModelRunner
import com.zeaze.tianyinwallpaper.utils.DepthPrefs
import com.zeaze.tianyinwallpaper.utils.GaussianPlyLoader
import com.zeaze.tianyinwallpaper.utils.PhotoMeshPlyLoader
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.sqrt

class DepthWallpaperService : WallpaperService() {
    private var activeEngine: DepthWallpaperEngine? = null

    override fun onCreateEngine(): Engine = DepthWallpaperEngine()

    inner class DepthWallpaperEngine : Engine() {
        private var renderer: DepthGLRenderer? = null
        private var sensorManager: SensorManager? = null
        private var motionSensor: Sensor? = null
        private var motionSensorPreference = 0
        private val mainHandler = Handler(Looper.getMainLooper())
        private var windowManager: WindowManager? = null
        private var pref: SharedPreferences? = null
        private var model: DepthWallpaperModel? = null

        private var isVisible = false
        private var surfaceReady = false
        private var surfaceWidth = 1
        private var surfaceHeight = 1

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
        private val gravity = FloatArray(3)
        private val rotationMatrix = FloatArray(9)
        private val remappedRotationMatrix = FloatArray(9)
        private val orientationAngles = FloatArray(3)

        private val prefChangeListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == DepthPrefs.PREF_DEPTH_WALLPAPERS || key == DepthPrefs.PREF_DEPTH_ACTIVE_ID) {
                loadActiveModel()
                model?.let {
                    renderer?.updateParams(it.renderParallaxStrength(), 0f)
                }
                if (isVisible && surfaceReady && model?.contentKey() != loadedImageKey) {
                    loadContent()
                }
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
            renderer = DepthGLRenderer().also { it.setRenderingEnabled(false) }
            loadActiveModel()
            Log.d(TAG, "onCreate sensor=${motionSensor?.name ?: "none"} model=${model?.id} gaussian=${model?.isGaussian()} mesh=${model?.isMesh()}")
        }

        override fun onSurfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
            super.onSurfaceChanged(holder, format, width, height)
            surfaceReady = true
            surfaceWidth = width.coerceAtLeast(1)
            surfaceHeight = height.coerceAtLeast(1)
            Log.d(TAG, "onSurfaceChanged ${surfaceWidth}x$surfaceHeight visible=$isVisible")
            renderer?.start(holder.surface)
            renderer?.resize(surfaceWidth, surfaceHeight)
            if (isVisible) {
                loadedImageKey = null
                loadContent()
            }
        }

        override fun onSurfaceDestroyed(holder: SurfaceHolder) {
            super.onSurfaceDestroyed(holder)
            surfaceReady = false
            loadVersion++
            loadedImageKey = null
            Log.d(TAG, "onSurfaceDestroyed visible=$isVisible loadVersion=$loadVersion")
            unregisterSensor()
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
                renderer?.setRenderingEnabled(true)
                loadActiveModel()
                model?.let {
                    renderer?.updateParams(it.renderParallaxStrength(), 0f)
                }
                registerSensor()
                if (surfaceReady && model?.contentKey() != loadedImageKey) {
                    loadContent()
                } else {
                    renderer?.requestRender()
                }
            } else {
                unregisterSensor()
                renderer?.setRenderingEnabled(false)
            }
        }

        override fun onDestroy() {
            super.onDestroy()
            if (activeEngine == this) activeEngine = null
            loadVersion++
            Log.d(TAG, "onDestroy loadVersion=$loadVersion")
            unregisterSensor()
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
            Log.d(TAG, "loadActiveModel id=${model?.id} gaussian=${model?.isGaussian()} mesh=${model?.isMesh()} image=${model?.imageUri?.isNotBlank()} gaussianPly=${model?.gaussianUri?.isNotBlank()} meshPly=${model?.meshUri?.isNotBlank()}")
        }

        private fun loadContent() {
            val target = model ?: return
            val targetKey = target.contentKey()
            Log.d(
                TAG,
                "loadContent start key=$targetKey gaussian=${target.isGaussian()} mesh=${target.isMesh()} " +
                    "visible=$isVisible surface=$surfaceWidth x $surfaceHeight loaded=$loadedImageKey"
            )
            renderer?.updateParams(target.renderParallaxStrength(), 0f)
            val currentVersion = ++loadVersion
            Thread {
                if (target.isGaussian()) {
                    val viewportAspect = surfaceWidth.toFloat() / surfaceHeight.coerceAtLeast(1).toFloat()
                    val scene = GaussianPlyLoader.loadScene(
                        context = applicationContext,
                        uriString = target.gaussianUri,
                        viewportAspect = viewportAspect
                    )
                    if (scene != null && currentVersion == loadVersion && isVisible && surfaceReady) {
                        loadedImageKey = targetKey
                        Log.d(
                            TAG,
                            "loadContent gaussian loaded count=${scene.count} " +
                                "visible=${scene.screenVisibleSplatCount} aux=${scene.auxiliarySplatCount} " +
                                "image=${scene.imageWidth}x${scene.imageHeight} viewportAspect=$viewportAspect"
                        )
                        renderer?.loadGaussians(scene)
                    } else {
                        Log.w(
                            TAG,
                            "loadContent gaussian skipped scene=${scene != null} current=$currentVersion loadVersion=$loadVersion " +
                                "visible=$isVisible surfaceReady=$surfaceReady"
                        )
                    }
                } else if (target.isMesh()) {
                    val scene = PhotoMeshPlyLoader.loadScene(
                        context = applicationContext,
                        uriString = target.meshUri,
                        maxFaces = WALLPAPER_MESH_FACE_LIMIT
                    )
                    if (scene != null && currentVersion == loadVersion && isVisible && surfaceReady) {
                        loadedImageKey = targetKey
                        Log.d(
                            TAG,
                            "loadContent mesh loaded faces=${scene.faceCount}/${scene.sourceFaceCount} " +
                                "chunks=${scene.chunks.size} image=${scene.imageWidth}x${scene.imageHeight} " +
                                "depth=${scene.nearDepth}/${scene.focusDepth}/${scene.farDepth}"
                        )
                        renderer?.loadMesh(scene)
                    } else {
                        Log.w(
                            TAG,
                            "loadContent mesh skipped scene=${scene != null} current=$currentVersion loadVersion=$loadVersion " +
                                "visible=$isVisible surfaceReady=$surfaceReady"
                        )
                    }
                } else {
                    val layeredTextures = DepthImageProcessor.loadLayeredTextureSet(
                        context = applicationContext,
                        model = target,
                        targetWidth = surfaceWidth * 2,
                        targetHeight = surfaceHeight * 2
                    )
                    if (layeredTextures != null) {
                        if (currentVersion == loadVersion && isVisible && surfaceReady) {
                            loadedImageKey = targetKey
                            Log.d(
                                TAG,
                                "loadContent layered texture loaded count=${layeredTextures.layers.size} " +
                                    "image=${layeredTextures.imageWidth}x${layeredTextures.imageHeight}"
                            )
                            renderer?.loadLayeredTextures(layeredTextures)
                        } else {
                            Log.w(
                                TAG,
                                "loadContent layered texture skipped current=$currentVersion loadVersion=$loadVersion " +
                                    "visible=$isVisible surfaceReady=$surfaceReady"
                            )
                        }
                    } else {
                        val textures = DepthImageProcessor.loadTextureSet(
                            context = applicationContext,
                            model = target,
                            targetWidth = surfaceWidth * 2,
                            targetHeight = surfaceHeight * 2
                        )
                        if (textures != null && currentVersion == loadVersion && isVisible && surfaceReady) {
                            loadedImageKey = targetKey
                            Log.d(
                                TAG,
                                "loadContent texture fallback loaded color=${textures.color.width}x${textures.color.height} " +
                                    "depth=${textures.depth.width}x${textures.depth.height}"
                            )
                            renderer?.loadTextures(textures)
                        } else {
                            Log.w(
                                TAG,
                                "loadContent texture fallback skipped textures=${textures != null} current=$currentVersion loadVersion=$loadVersion " +
                                    "visible=$isVisible surfaceReady=$surfaceReady"
                            )
                        }
                    }
                }
            }.also { it.name = "DepthContentLoader" }.start()
        }

        private fun DepthWallpaperModel.contentKey(): String {
            if (isGaussian()) {
                return "$id|$gaussianUri"
            }
            if (isMesh()) {
                return "$id|$meshUri|faces=$WALLPAPER_MESH_FACE_LIMIT"
            }
            return "$id|$imageUri|layered-v2|${DepthModelRunner.modelCacheKey(applicationContext)}"
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
                val ok = sensorManager?.registerListener(sensorListener, sensor, SensorManager.SENSOR_DELAY_UI) == true
                lastSensorEventMs = if (ok) SystemClock.elapsedRealtime() else 0L
                startSensorWatchdog()
                Log.d(TAG, "registerSensor ok=$ok sensor=${sensor.name} type=${sensor.type}")
            } ?: Log.w(TAG, "registerSensor no sensor")
        }

        private fun sensorTypeName(type: Int): String {
            return when (type) {
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
            val ok = sensorManager?.registerListener(sensorListener, sensor, SensorManager.SENSOR_DELAY_UI) == true
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
            lastSensorEventMs = 0L
            sensorEventCount = 0L
            dispatchCount = 0L
            lastSensorLogMs = 0L
            lastDispatchLogMs = 0L
            hasGravity = false
            gravity.fill(0f)
            renderer?.updateTilt(0f, 0f)
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
            val minDispatchIntervalNs = if (model?.isMesh() == true) {
                MESH_MIN_DISPATCH_INTERVAL_NS
            } else {
                MIN_DISPATCH_INTERVAL_NS
            }
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
                renderer?.updateTilt(tiltX, tiltY)
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
            return listOf(
                manager.getDefaultSensor(Sensor.TYPE_GAME_ROTATION_VECTOR),
                manager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR),
                manager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
            ).filterNotNull().distinctBy { it.type }
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
        private const val MESH_MIN_DISPATCH_INTERVAL_NS = 33_000_000L
        private const val WALLPAPER_MESH_FACE_LIMIT = PhotoMeshPlyLoader.MAX_FACE_LIMIT
        private const val SENSOR_WATCHDOG_INTERVAL_MS = 3_000L
        private const val SENSOR_STALE_TIMEOUT_MS = 4_500L
        private const val SENSOR_LOG_INTERVAL_MS = 1_000L
        private const val DISPATCH_LOG_INTERVAL_MS = 1_000L
    }
}
