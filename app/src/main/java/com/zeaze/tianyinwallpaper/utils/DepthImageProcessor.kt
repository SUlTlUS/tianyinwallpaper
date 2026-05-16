package com.zeaze.tianyinwallpaper.utils

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.net.Uri
import android.util.Log
import com.zeaze.tianyinwallpaper.model.DepthWallpaperModel
import com.zeaze.tianyinwallpaper.renderer.RenderBitmapCache
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

object DepthImageProcessor {
    private const val TAG = "DepthImageProcessor"
    private const val MAX_TEXTURE_EDGE = 2048
    private const val MAX_DEPTH_EDGE = 384

    data class TextureBitmap(
        val width: Int,
        val height: Int,
        val rgba: ByteBuffer
    )

    data class TextureSet(
        val color: TextureBitmap,
        val depth: TextureBitmap
    )

    fun loadTextureSet(
        context: Context,
        model: DepthWallpaperModel,
        targetWidth: Int,
        targetHeight: Int
    ): TextureSet? {
        if (model.imageUri.isBlank()) return null
        val colorBitmap = loadOwnedColorBitmap(context, model.imageUri, targetWidth, targetHeight)
            ?: return null
        val depthBitmap = loadOrCreateDepthMap(context, model, colorBitmap)
        return try {
            TextureSet(
                color = colorBitmap.toTextureBitmap(),
                depth = depthBitmap.toTextureBitmap()
            )
        } catch (e: Exception) {
            Log.w(TAG, "Failed to build depth texture set", e)
            null
        } finally {
            if (!colorBitmap.isRecycled) colorBitmap.recycle()
            if (depthBitmap !== colorBitmap && !depthBitmap.isRecycled) depthBitmap.recycle()
        }
    }

    fun ensureDepthCache(context: Context, model: DepthWallpaperModel) {
        if (model.imageUri.isBlank()) return
        val colorBitmap = loadOwnedColorBitmap(context, model.imageUri, 720, 1280) ?: return
        try {
            val depthBitmap = loadOrCreateDepthMap(context, model, colorBitmap)
            if (!depthBitmap.isRecycled) depthBitmap.recycle()
        } finally {
            if (!colorBitmap.isRecycled) colorBitmap.recycle()
        }
    }

    fun deleteCacheFor(context: Context, model: DepthWallpaperModel) {
        val dir = depthCacheDir(context) ?: return
        val prefix = model.id.takeIf { it.isNotBlank() } ?: return
        dir.listFiles()?.forEach { file ->
            if (file.name.startsWith("$prefix-")) {
                file.delete()
            }
        }
    }

    fun depthCacheFile(context: Context, model: DepthWallpaperModel): File? {
        return depthCacheFile(context, model, DepthModelRunner.modelCacheKey(context))
    }

    private fun depthCacheFile(
        context: Context,
        model: DepthWallpaperModel,
        cacheKey: String
    ): File? {
        val dir = depthCacheDir(context) ?: return null
        val hash = Integer.toHexString("${model.imageUri}|$cacheKey".hashCode())
        return File(dir, "${model.id}-$hash.png")
    }

    private fun depthCacheDir(context: Context): File? {
        val root = context.getExternalFilesDir(null) ?: return null
        val dir = File(root, "depth_cache")
        if (!dir.mkdirs() && !dir.exists()) return null
        return dir
    }

    private fun loadOwnedColorBitmap(
        context: Context,
        uriString: String,
        targetWidth: Int,
        targetHeight: Int
    ): Bitmap? {
        return try {
            val uri = Uri.parse(uriString)
            val (reqWidth, reqHeight) = textureRequestSize(targetWidth, targetHeight)
            val cached = RenderBitmapCache.loadSync(context, uri, reqWidth, reqHeight)
                ?: return null
            cached.toSoftwareArgbCopy()
        } catch (e: Exception) {
            Log.w(TAG, "Failed to load image for depth wallpaper: $uriString", e)
            null
        }
    }

    private fun textureRequestSize(width: Int, height: Int): Pair<Int, Int> {
        val safeWidth = width.takeIf { it > 0 } ?: FileUtil.width.takeIf { it > 0 } ?: 1080
        val safeHeight = height.takeIf { it > 0 } ?: FileUtil.height.takeIf { it > 0 } ?: 1920
        val maxEdge = max(safeWidth, safeHeight).coerceAtLeast(1)
        val scale = min(1f, MAX_TEXTURE_EDGE.toFloat() / maxEdge.toFloat())
        return max(1, (safeWidth * scale).roundToInt()) to
            max(1, (safeHeight * scale).roundToInt())
    }

    private fun loadOrCreateDepthMap(
        context: Context,
        model: DepthWallpaperModel,
        colorBitmap: Bitmap
    ): Bitmap {
        val modelCacheFile = depthCacheFile(context, model)
        loadCachedDepthMap(modelCacheFile)?.let { return it }

        val inference = DepthModelRunner.inferDepthResult(context, colorBitmap)
        if (inference != null) {
            if (inference.rawDepthMap !== inference.upsampledDepthMap && !inference.rawDepthMap.isRecycled) {
                inference.rawDepthMap.recycle()
            }
            saveDepthMap(modelCacheFile, inference.upsampledDepthMap)
            Log.i(
                TAG,
                "Generated TFLite depth map with ${inference.modelName} " +
                    "${inference.outputWidth}x${inference.outputHeight}->${inference.sourceWidth}x${inference.sourceHeight}"
            )
            return inference.upsampledDepthMap
        }

        DepthModelRunner.lastError()?.let {
            Log.w(TAG, "Depth model unavailable for wallpaper, using heuristic depth: $it")
        }
        val heuristicCacheFile = depthCacheFile(context, model, "heuristic-v2")
        loadCachedDepthMap(heuristicCacheFile)?.let { return it }
        val depthMap = estimateDepthMap(colorBitmap)
        saveDepthMap(heuristicCacheFile, depthMap)
        return depthMap
    }

    private fun loadCachedDepthMap(cacheFile: File?): Bitmap? {
        if (cacheFile == null || !cacheFile.exists()) return null
        return runCatching {
            BitmapFactory.decodeFile(
                cacheFile.absolutePath,
                BitmapFactory.Options().apply {
                    inPreferredConfig = Bitmap.Config.ARGB_8888
                }
            )?.toSoftwareArgbCopy()
        }.getOrNull()
    }

    private fun saveDepthMap(cacheFile: File?, depthMap: Bitmap) {
        if (cacheFile == null) return
        runCatching {
            FileOutputStream(cacheFile).use { out ->
                depthMap.compress(Bitmap.CompressFormat.PNG, 100, out)
            }
        }.onFailure {
            Log.w(TAG, "Failed to save depth cache: ${cacheFile.absolutePath}", it)
        }
    }

    private fun estimateDepthMap(source: Bitmap): Bitmap {
        val maxEdge = max(source.width, source.height).coerceAtLeast(1)
        val scale = min(1f, MAX_DEPTH_EDGE.toFloat() / maxEdge.toFloat())
        val w = max(2, (source.width * scale).roundToInt())
        val h = max(2, (source.height * scale).roundToInt())
        val small = if (source.width == w && source.height == h) {
            source
        } else {
            Bitmap.createScaledBitmap(source, w, h, true)
        }

        val pixels = IntArray(w * h)
        small.getPixels(pixels, 0, w, 0, 0, w, h)
        if (small !== source && !small.isRecycled) small.recycle()

        val luminance = FloatArray(w * h)
        val saturation = FloatArray(w * h)
        for (i in pixels.indices) {
            val p = pixels[i]
            val r = ((p ushr 16) and 0xff) / 255f
            val g = ((p ushr 8) and 0xff) / 255f
            val b = (p and 0xff) / 255f
            val hi = max(r, max(g, b))
            val lo = min(r, min(g, b))
            luminance[i] = 0.299f * r + 0.587f * g + 0.114f * b
            saturation[i] = if (hi <= 0.0001f) 0f else (hi - lo) / hi
        }

        val edge = FloatArray(w * h)
        var maxEdgeValue = 0.0001f
        for (y in 1 until h - 1) {
            for (x in 1 until w - 1) {
                val i = y * w + x
                val gx =
                    -luminance[i - w - 1] - 2f * luminance[i - 1] - luminance[i + w - 1] +
                        luminance[i - w + 1] + 2f * luminance[i + 1] + luminance[i + w + 1]
                val gy =
                    -luminance[i - w - 1] - 2f * luminance[i - w] - luminance[i - w + 1] +
                        luminance[i + w - 1] + 2f * luminance[i + w] + luminance[i + w + 1]
                val value = sqrt(gx * gx + gy * gy)
                edge[i] = value
                maxEdgeValue = max(maxEdgeValue, value)
            }
        }

        val rawDepth = FloatArray(w * h)
        var minDepth = Float.MAX_VALUE
        var maxDepth = -Float.MAX_VALUE
        for (y in 0 until h) {
            val yNorm = y.toFloat() / (h - 1).coerceAtLeast(1).toFloat()
            val bottomPrior = yNorm
            for (x in 0 until w) {
                val i = y * w + x
                val xNorm = x.toFloat() / (w - 1).coerceAtLeast(1).toFloat()
                val dx = (xNorm - 0.5f) * 2f
                val dy = (yNorm - 0.5f) * 2f
                val centerPrior = (1f - sqrt(dx * dx * 0.85f + dy * dy * 0.65f)).coerceIn(0f, 1f)
                val edgeScore = (edge[i] / maxEdgeValue).coerceIn(0f, 1f)
                val darkSeparation = (1f - luminance[i]) * 0.12f
                val depth =
                    centerPrior * 0.44f +
                        bottomPrior * 0.22f +
                        saturation[i] * 0.16f +
                        edgeScore * 0.30f +
                        darkSeparation
                rawDepth[i] = depth
                minDepth = min(minDepth, depth)
                maxDepth = max(maxDepth, depth)
            }
        }

        val range = (maxDepth - minDepth).coerceAtLeast(0.0001f)
        for (i in rawDepth.indices) {
            rawDepth[i] = ((rawDepth[i] - minDepth) / range).coerceIn(0f, 1f)
        }
        val blurred = boxBlur(rawDepth, w, h, radius = 5, passes = 3)

        val outPixels = IntArray(w * h)
        for (i in blurred.indices) {
            val value = (blurred[i].coerceIn(0f, 1f) * 255f).roundToInt().coerceIn(0, 255)
            outPixels[i] = Color.argb(255, value, value, value)
        }
        return Bitmap.createBitmap(outPixels, w, h, Bitmap.Config.ARGB_8888)
    }

    private fun boxBlur(
        input: FloatArray,
        width: Int,
        height: Int,
        radius: Int,
        passes: Int
    ): FloatArray {
        var src = input.copyOf()
        var tmp = FloatArray(src.size)
        var dst = FloatArray(src.size)
        repeat(passes) {
            for (y in 0 until height) {
                var sum = 0f
                for (x in -radius..radius) {
                    sum += src[y * width + x.coerceIn(0, width - 1)]
                }
                for (x in 0 until width) {
                    tmp[y * width + x] = sum / (radius * 2 + 1)
                    val removeX = (x - radius).coerceIn(0, width - 1)
                    val addX = (x + radius + 1).coerceIn(0, width - 1)
                    sum += src[y * width + addX] - src[y * width + removeX]
                }
            }
            for (x in 0 until width) {
                var sum = 0f
                for (y in -radius..radius) {
                    sum += tmp[y.coerceIn(0, height - 1) * width + x]
                }
                for (y in 0 until height) {
                    dst[y * width + x] = sum / (radius * 2 + 1)
                    val removeY = (y - radius).coerceIn(0, height - 1)
                    val addY = (y + radius + 1).coerceIn(0, height - 1)
                    sum += tmp[addY * width + x] - tmp[removeY * width + x]
                }
            }
            val swap = src
            src = dst
            dst = swap
        }
        return src
    }

    private fun Bitmap.toSoftwareArgbCopy(): Bitmap {
        if (config == Bitmap.Config.ARGB_8888) {
            return copy(Bitmap.Config.ARGB_8888, false)
        }
        val output = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        Canvas(output).drawBitmap(this, 0f, 0f, null)
        return output
    }

    private fun Bitmap.toTextureBitmap(): TextureBitmap {
        val safe = if (config == Bitmap.Config.ARGB_8888) this else toSoftwareArgbCopy()
        val width = safe.width
        val height = safe.height
        val pixels = IntArray(width * height)
        safe.getPixels(pixels, 0, width, 0, 0, width, height)
        val buffer = ByteBuffer
            .allocateDirect(pixels.size * 4)
            .order(ByteOrder.nativeOrder())
        for (pixel in pixels) {
            buffer.put(((pixel ushr 16) and 0xff).toByte())
            buffer.put(((pixel ushr 8) and 0xff).toByte())
            buffer.put((pixel and 0xff).toByte())
            buffer.put(((pixel ushr 24) and 0xff).toByte())
        }
        buffer.position(0)
        if (safe !== this && !safe.isRecycled) safe.recycle()
        return TextureBitmap(width, height, buffer)
    }
}
