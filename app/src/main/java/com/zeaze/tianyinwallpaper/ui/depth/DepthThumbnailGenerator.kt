package com.zeaze.tianyinwallpaper.ui.depth

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PixelFormat
import android.media.ImageReader
import android.os.Looper
import android.util.Log
import com.zeaze.tianyinwallpaper.App
import com.zeaze.tianyinwallpaper.model.DepthWallpaperModel
import com.zeaze.tianyinwallpaper.renderer.WebGaussianWallpaperRenderer
import com.zeaze.tianyinwallpaper.utils.DepthPrefs
import com.zeaze.tianyinwallpaper.utils.GaussianSceneLoader
import com.zeaze.tianyinwallpaper.utils.SuperSplatWebParams
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

internal const val THUMBNAIL_WIDTH = 540
internal const val THUMBNAIL_HEIGHT = 960
private const val THUMBNAIL_MAX_SPLATS = 500_000
private const val WEB_THUMBNAIL_TIMEOUT_MS = 15_000L
private val webThumbnailLock = Any()

private data class ProjectedSplat(
    val u: Float,
    val v: Float,
    val z: Float,
    val radius: Float,
    val r: Float,
    val g: Float,
    val b: Float,
    val a: Float
)

internal fun generateGaussianThumbnail(
    context: Context,
    uriString: String,
    width: Int,
    height: Int
): Bitmap? {
    return runCatching {
        val scene = GaussianSceneLoader.loadSceneDetailed(
            context = context,
            uriString = uriString,
            maxSplats = THUMBNAIL_MAX_SPLATS,
            viewportAspect = width.toFloat() / height.coerceAtLeast(1).toFloat()
        ).scene ?: return null

        val thumbW = width.toFloat()
        val thumbH = height.toFloat()
        val imageW = scene.imageWidth.coerceAtLeast(1).toFloat()
        val imageH = scene.imageHeight.coerceAtLeast(1).toFloat()
        val focal = scene.focalLengthPx

        val fillScale = maxOf(thumbW / imageW, thumbH / imageH)
        val offsetU = (thumbW - imageW * fillScale) * 0.5f
        val offsetV = (thumbH - imageH * fillScale) * 0.5f
        val splatScale = (fillScale * 1.35f).coerceIn(0.35f, 30f)

        val count = scene.count
        val step = (count / THUMBNAIL_MAX_SPLATS).coerceAtLeast(1)

        val splats = ArrayList<ProjectedSplat>(THUMBNAIL_MAX_SPLATS)
        for (i in 0 until count step step) {
            val px = scene.positions[i * 3]
            val py = scene.positions[i * 3 + 1]
            val pz = scene.positions[i * 3 + 2]
            if (pz <= 0.01f) continue

            val a = scene.colors[i * 4 + 3]
            if (a < 0.015f) continue

            val sx = scene.scales[i * 3]
            val sy = scene.scales[i * 3 + 1]
            val sz = scene.scales[i * 3 + 2]

            val projX = px / pz
            val projY = py / pz
            val u = ((projX * focal) + imageW * 0.5f) * fillScale + offsetU
            val v = ((projY * focal) + imageH * 0.5f) * fillScale + offsetV
            val rad = maxOf(sx, sy, sz, 0.0006f) * focal / pz * splatScale * 2.6f

            if (u < -rad || u > thumbW + rad || v < -rad || v > thumbH + rad) continue

            splats += ProjectedSplat(
                u, v, pz, rad,
                scene.colors[i * 4], scene.colors[i * 4 + 1], scene.colors[i * 4 + 2], a
            )
        }

        splats.sortByDescending { it.z }

        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val bgR = (scene.backgroundR * 255).toInt().coerceIn(0, 255)
        val bgG = (scene.backgroundG * 255).toInt().coerceIn(0, 255)
        val bgB = (scene.backgroundB * 255).toInt().coerceIn(0, 255)
        canvas.drawColor(android.graphics.Color.rgb(bgR, bgG, bgB))

        val paint = Paint().apply {
            isAntiAlias = true
            style = Paint.Style.FILL
        }

        for (s in splats) {
            val alpha = (s.a * 255f).toInt().coerceIn(0, 255)
            if (alpha < 3) continue
            paint.color = android.graphics.Color.argb(
                alpha,
                (s.r * 255f).toInt().coerceIn(0, 255),
                (s.g * 255f).toInt().coerceIn(0, 255),
                (s.b * 255f).toInt().coerceIn(0, 255)
            )
            canvas.drawCircle(s.u, s.v, s.radius.coerceAtLeast(0.8f), paint)
        }

        bitmap
    }.getOrNull()
}

internal fun loadOrGenerateGaussianThumbnail(
    context: Context,
    model: DepthWallpaperModel,
    width: Int,
    height: Int
): Bitmap? {
    val file = gaussianThumbnailCacheFile(context, model)
    if (file?.exists() == true && file.length() > 0L) {
        return BitmapFactory.decodeFile(file.absolutePath, BitmapFactory.Options().apply {
            inPreferredConfig = Bitmap.Config.RGB_565
        })
    }

    val bitmap = generateGaussianThumbnailWithWebView(context, model, width, height) ?: return null
    if (model.sourceGenerationRecordId.isBlank() && bitmap.isNearlySolidColor()) {
        bitmap.recycle()
        Log.w(TAG, "rejected invalid solid SOG thumbnail id=${model.id}")
        return null
    }
    if (file != null) {
        runCatching {
            file.outputStream().use { output ->
                bitmap.compress(Bitmap.CompressFormat.JPEG, 88, output)
            }
        }
    }
    return bitmap
}

private fun Bitmap.isNearlySolidColor(): Boolean {
    if (width <= 0 || height <= 0) return true
    var minRed = 255
    var minGreen = 255
    var minBlue = 255
    var maxRed = 0
    var maxGreen = 0
    var maxBlue = 0
    val sampleCount = 7
    repeat(sampleCount) { row ->
        val y = ((height - 1L) * row / (sampleCount - 1)).toInt()
        repeat(sampleCount) { column ->
            val x = ((width - 1L) * column / (sampleCount - 1)).toInt()
            val color = getPixel(x, y)
            val red = android.graphics.Color.red(color)
            val green = android.graphics.Color.green(color)
            val blue = android.graphics.Color.blue(color)
            minRed = minOf(minRed, red)
            minGreen = minOf(minGreen, green)
            minBlue = minOf(minBlue, blue)
            maxRed = maxOf(maxRed, red)
            maxGreen = maxOf(maxGreen, green)
            maxBlue = maxOf(maxBlue, blue)
        }
    }
    return maxRed - minRed <= SOLID_THUMBNAIL_CHANNEL_RANGE &&
        maxGreen - minGreen <= SOLID_THUMBNAIL_CHANNEL_RANGE &&
        maxBlue - minBlue <= SOLID_THUMBNAIL_CHANNEL_RANGE
}

internal fun generateGaussianThumbnailWithWebView(
    context: Context,
    model: DepthWallpaperModel,
    width: Int,
    height: Int
): Bitmap? {
    if (model.gaussianUri.isBlank()) return null
    if (Looper.myLooper() == Looper.getMainLooper()) {
        Log.w(TAG, "skip WebView thumbnail on main thread id=${model.id}")
        return null
    }
    return synchronized(webThumbnailLock) {
        runCatching {
            val safeWidth = width.coerceAtLeast(1)
            val safeHeight = height.coerceAtLeast(1)
            val appContext = context.applicationContext
            val prefs = appContext.getSharedPreferences(App.TIANYIN, Context.MODE_PRIVATE)
            val imageReader = ImageReader.newInstance(safeWidth, safeHeight, PixelFormat.RGBA_8888, 2)
            val renderer = WebGaussianWallpaperRenderer(appContext)
            val latch = CountDownLatch(1)
            val captureRequested = AtomicBoolean(false)
            var result: Bitmap? = null
            val thumbnailListener: (Bitmap) -> Unit = { bitmap ->
                if (result == null) {
                    result = bitmap.copy(Bitmap.Config.ARGB_8888, false)
                    latch.countDown()
                }
            }
            val hasSavedOriginCameraDefaults =
                model.cameraCalibrationVersion >= DepthWallpaperModel.ORIGIN_CAMERA_CALIBRATION_VERSION &&
                    model.cameraDefaultDistance > 0f &&
                    model.cameraDefaultFov > 0f
            val initialCameraDefaultDistance = if (hasSavedOriginCameraDefaults) model.cameraDefaultDistance else 0f
            val initialCameraDefaultFov = if (hasSavedOriginCameraDefaults) model.cameraDefaultFov else 0f
            val initialCameraCalibrationVersion = if (hasSavedOriginCameraDefaults) {
                DepthWallpaperModel.ORIGIN_CAMERA_CALIBRATION_VERSION
            } else {
                0
            }
            val initialCameraFov = initialCameraDefaultFov.takeIf { it > 0f } ?: DEFAULT_THUMBNAIL_CAMERA_FOV
            val thumbnailParams = SuperSplatWebParams(
                parallaxStrength = DepthWallpaperModel.DEFAULT_SOG_PARALLAX_STRENGTH,
                cameraZoom = initialCameraDefaultDistance.takeIf { it > 0f }
                    ?: DepthWallpaperModel.DEFAULT_SOG_CAMERA_ZOOM,
                cameraDefaultDistance = initialCameraDefaultDistance,
                cameraDefaultFov = initialCameraDefaultFov,
                cameraCalibrationVersion = initialCameraCalibrationVersion,
                centerOffsetX = 0f,
                centerOffsetY = 0f,
                focusDepth = DepthWallpaperModel.DEFAULT_SOG_FOCUS_DEPTH,
                cameraFov = initialCameraFov,
                performanceMode = prefs.getBoolean(DepthPrefs.PREF_WEB_PERFORMANCE_MODE, true)
            )
            try {
                renderer.resize(safeWidth, safeHeight)
                renderer.start(imageReader.surface)
                renderer.setRenderingEnabled(true)
                if (hasSavedOriginCameraDefaults) {
                    renderer.setThumbnailFrameListener(thumbnailListener)
                } else {
                    renderer.setWebCameraDefaultsListener { distance, fov ->
                        if (captureRequested.compareAndSet(false, true)) {
                            renderer.loadWebGaussians(
                                model.gaussianUri,
                                thumbnailParams.copy(
                                    cameraZoom = distance,
                                    cameraDefaultDistance = distance,
                                    cameraDefaultFov = fov,
                                    cameraCalibrationVersion = DepthWallpaperModel.ORIGIN_CAMERA_CALIBRATION_VERSION,
                                    cameraFov = fov
                                )
                            )
                            renderer.setThumbnailFrameListener(thumbnailListener, armImmediately = true)
                            renderer.requestRender()
                        }
                    }
                }
                renderer.loadWebGaussians(
                    model.gaussianUri,
                    thumbnailParams
                )
                renderer.requestRender()
                if (!latch.await(WEB_THUMBNAIL_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
                    Log.w(TAG, "WebView thumbnail timed out id=${model.id} uri=${model.gaussianUri}")
                    null
                } else {
                    result
                }
            } finally {
                renderer.setThumbnailFrameListener(null)
                renderer.setWebCameraDefaultsListener(null)
                renderer.setRenderingEnabled(false)
                renderer.stopAndWait(500)
                imageReader.close()
            }
        }.onFailure {
            Log.w(TAG, "WebView thumbnail failed id=${model.id} uri=${model.gaussianUri}", it)
        }.getOrNull()
    }
}

internal fun removeGaussianThumbnailCache(context: Context, model: DepthWallpaperModel) {
    gaussianThumbnailCacheFile(context, model)?.takeIf { it.exists() }?.delete()
}

internal fun gaussianThumbnailCacheFile(context: Context, model: DepthWallpaperModel): File? {
    if (model.id.isBlank() || model.gaussianUri.isBlank()) return null
    return DepthPrefs.sogThumbnailFile(context, model.id)
}

internal fun gaussianThumbnailCacheKey(model: DepthWallpaperModel): String {
    return "gaussian_${model.id}"
}

private const val TAG = "DepthThumbnailGenerator"
private const val SOLID_THUMBNAIL_CHANNEL_RANGE = 6
private const val DEFAULT_THUMBNAIL_CAMERA_FOV = 60f
