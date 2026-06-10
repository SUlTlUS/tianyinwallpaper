package com.zeaze.tianyinwallpaper.ui.depth

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import com.zeaze.tianyinwallpaper.model.DepthWallpaperModel
import com.zeaze.tianyinwallpaper.utils.DepthPrefs
import com.zeaze.tianyinwallpaper.utils.GaussianSceneLoader
import java.io.File

internal const val THUMBNAIL_WIDTH = 540
internal const val THUMBNAIL_HEIGHT = 960
private const val THUMBNAIL_MAX_SPLATS = 500_000

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
    if (file?.exists() == true) {
        BitmapFactory.decodeFile(file.absolutePath, BitmapFactory.Options().apply {
            inPreferredConfig = Bitmap.Config.RGB_565
        })?.let { return it }
    }

    val bitmap = generateGaussianThumbnail(context, model.gaussianUri, width, height) ?: return null
    if (file != null) {
        runCatching {
            file.outputStream().use { output ->
                bitmap.compress(Bitmap.CompressFormat.JPEG, 88, output)
            }
        }
    }
    return bitmap
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
