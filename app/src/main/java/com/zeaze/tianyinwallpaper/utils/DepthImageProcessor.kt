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
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

object DepthImageProcessor {
    private const val TAG = "DepthImageProcessor"
    private const val MAX_TEXTURE_EDGE = 2048
    private const val MAX_LAYER_TEXTURE_EDGE = 960
    private const val MAX_DEPTH_EDGE = 384
    private const val DEPTH_LAYER_COUNT = 18
    private const val LAYER_CACHE_VERSION = "layered-v2"

    data class TextureBitmap(
        val width: Int,
        val height: Int,
        val rgba: ByteBuffer
    )

    data class TextureSet(
        val color: TextureBitmap,
        val depth: TextureBitmap
    )

    data class LayerTexture(
        val texture: TextureBitmap,
        val depth: Float
    )

    data class LayeredTextureSet(
        val imageWidth: Int,
        val imageHeight: Int,
        val layers: List<LayerTexture>
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

    fun loadLayeredTextureSet(
        context: Context,
        model: DepthWallpaperModel,
        targetWidth: Int,
        targetHeight: Int
    ): LayeredTextureSet? {
        if (model.imageUri.isBlank()) return null
        val colorBitmap = loadOwnedColorBitmap(
            context = context,
            uriString = model.imageUri,
            targetWidth = targetWidth,
            targetHeight = targetHeight,
            maxTextureEdge = MAX_LAYER_TEXTURE_EDGE
        ) ?: return null
        var depthBitmap: Bitmap? = null
        var scaledDepthBitmap: Bitmap? = null
        var hadOutOfMemory = false
        return try {
            depthBitmap = loadOrCreateDepthMap(context, model, colorBitmap)
            val depthForLayers = if (depthBitmap.width == colorBitmap.width && depthBitmap.height == colorBitmap.height) {
                depthBitmap
            } else {
                Bitmap.createScaledBitmap(depthBitmap, colorBitmap.width, colorBitmap.height, true)
                    .also { scaledDepthBitmap = it }
            }

            val cacheDir = layeredCacheDir(context, model, targetWidth, targetHeight)
            loadCachedLayeredTextureSet(cacheDir, colorBitmap.width, colorBitmap.height)?.let { cached ->
                return cached
            }

            val layers = createLayerTextures(colorBitmap, depthForLayers, cacheDir)
            LayeredTextureSet(
                imageWidth = colorBitmap.width,
                imageHeight = colorBitmap.height,
                layers = layers
            )
        } catch (e: OutOfMemoryError) {
            Log.e(TAG, "Out of memory while building layered depth texture set", e)
            hadOutOfMemory = true
            null
        } catch (e: Exception) {
            Log.w(TAG, "Failed to build layered depth texture set", e)
            null
        } finally {
            scaledDepthBitmap?.let { if (!it.isRecycled) it.recycle() }
            depthBitmap?.let { if (it !== colorBitmap && !it.isRecycled) it.recycle() }
            if (!colorBitmap.isRecycled) colorBitmap.recycle()
            if (hadOutOfMemory) {
                System.gc()
            }
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
                file.deleteRecursively()
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
        targetHeight: Int,
        maxTextureEdge: Int = MAX_TEXTURE_EDGE
    ): Bitmap? {
        return try {
            val uri = Uri.parse(uriString)
            val (reqWidth, reqHeight) = textureRequestSize(targetWidth, targetHeight, maxTextureEdge)
            val cached = RenderBitmapCache.loadSync(context, uri, reqWidth, reqHeight)
                ?: return null
            cached.toSoftwareArgbCopy()
        } catch (e: Exception) {
            Log.w(TAG, "Failed to load image for depth wallpaper: $uriString", e)
            null
        }
    }

    private fun textureRequestSize(width: Int, height: Int, maxTextureEdge: Int): Pair<Int, Int> {
        val safeWidth = width.takeIf { it > 0 } ?: FileUtil.width.takeIf { it > 0 } ?: 1080
        val safeHeight = height.takeIf { it > 0 } ?: FileUtil.height.takeIf { it > 0 } ?: 1920
        val maxEdge = max(safeWidth, safeHeight).coerceAtLeast(1)
        val scale = min(1f, maxTextureEdge.toFloat() / maxEdge.toFloat())
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

    private fun layeredCacheDir(
        context: Context,
        model: DepthWallpaperModel,
        targetWidth: Int,
        targetHeight: Int
    ): File? {
        val dir = depthCacheDir(context) ?: return null
        val hash = Integer.toHexString(
            "${model.imageUri}|${DepthModelRunner.modelCacheKey(context)}|$targetWidth|$targetHeight|$LAYER_CACHE_VERSION"
                .hashCode()
        )
        return File(dir, "${model.id}-$hash-layers")
    }

    private fun loadCachedLayeredTextureSet(
        cacheDir: File?,
        expectedWidth: Int,
        expectedHeight: Int
    ): LayeredTextureSet? {
        if (cacheDir == null || !cacheDir.isDirectory) return null
        val layers = ArrayList<LayerTexture>(DEPTH_LAYER_COUNT)
        for (index in 0 until DEPTH_LAYER_COUNT) {
            val file = File(cacheDir, layerFileName(index))
            if (!file.exists() || file.length() <= 0L) return null
            val bitmap = BitmapFactory.decodeFile(
                file.absolutePath,
                BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.ARGB_8888 }
            )?.toSoftwareArgbCopy() ?: return null
            if (bitmap.width != expectedWidth || bitmap.height != expectedHeight) {
                if (!bitmap.isRecycled) bitmap.recycle()
                cacheDir.deleteRecursively()
                return null
            }
            try {
                layers += LayerTexture(bitmap.toTextureBitmap(), layerDepth(index))
            } finally {
                if (!bitmap.isRecycled) bitmap.recycle()
            }
        }
        Log.d(TAG, "Loaded cached layered depth textures count=${layers.size} ${expectedWidth}x$expectedHeight")
        return LayeredTextureSet(expectedWidth, expectedHeight, layers)
    }

    private fun createLayerTextures(
        colorBitmap: Bitmap,
        depthBitmap: Bitmap,
        cacheDir: File?
    ): List<LayerTexture> {
        val width = colorBitmap.width
        val height = colorBitmap.height
        val pixelCount = width * height
        val colorPixels = IntArray(pixelCount)
        val depthPixels = IntArray(pixelCount)
        colorBitmap.getPixels(colorPixels, 0, width, 0, 0, width, height)
        depthBitmap.getPixels(depthPixels, 0, width, 0, 0, width, height)

        val thresholds = computeLayerThresholds(depthPixels)
        if (cacheDir != null) {
            cacheDir.deleteRecursively()
            cacheDir.mkdirs()
        }

        val layers = ArrayList<LayerTexture>(DEPTH_LAYER_COUNT)
        for (index in 0 until DEPTH_LAYER_COUNT) {
            val layerBitmap = createLayerBitmap(index, colorPixels, depthPixels, thresholds, width, height)
            try {
                saveLayerBitmap(cacheDir, index, layerBitmap)
                layers += LayerTexture(layerBitmap.toTextureBitmap(), layerDepth(index))
            } finally {
                if (!layerBitmap.isRecycled) layerBitmap.recycle()
            }
        }
        Log.d(TAG, "Generated layered depth textures count=${layers.size} ${width}x$height")
        return layers
    }

    private fun computeLayerThresholds(depthPixels: IntArray): FloatArray {
        var sum = 0f
        var count = 0
        for (pixel in depthPixels) {
            val value = (pixel and 0xff) / 255f
            if (value > 0.01f) {
                sum += value
                count++
            }
        }
        val averageDepth = if (count > 0) sum / count else 0.5f
        val exponent = when {
            averageDepth < 0.3f -> 3.9
            averageDepth > 0.7f -> 1.8
            else -> 3.8
        }
        val thresholds = FloatArray(DEPTH_LAYER_COUNT + 1)
        thresholds[0] = 0f
        for (index in 1 until DEPTH_LAYER_COUNT) {
            val normalized = index.toDouble() / (DEPTH_LAYER_COUNT - 1).toDouble()
            thresholds[index] = Math.pow(normalized, exponent).toFloat()
                .coerceAtLeast(thresholds[index - 1] + 0.001f)
        }
        thresholds[DEPTH_LAYER_COUNT] = 1.01f
        return thresholds
    }

    private fun createLayerBitmap(
        index: Int,
        colorPixels: IntArray,
        depthPixels: IntArray,
        thresholds: FloatArray,
        width: Int,
        height: Int
    ): Bitmap {
        if (index == 0) {
            return Bitmap.createBitmap(colorPixels, width, height, Bitmap.Config.ARGB_8888)
        }

        val lower = thresholds[index]
        val upper = thresholds[index + 1]
        val softness = 0.018f
        val output = IntArray(colorPixels.size)
        for (i in colorPixels.indices) {
            val depth = (depthPixels[i] and 0xff) / 255f
            val alpha =
                smoothStep(lower - softness, lower + softness, depth) *
                    (1f - smoothStep(upper - softness, upper + softness, depth))
            if (alpha > 0.003f) {
                val a = (alpha * 255f).roundToInt().coerceIn(0, 255)
                output[i] = (a shl 24) or (colorPixels[i] and 0x00ffffff)
            }
        }
        return Bitmap.createBitmap(output, width, height, Bitmap.Config.ARGB_8888)
    }

    private fun saveLayerBitmap(cacheDir: File?, index: Int, bitmap: Bitmap) {
        if (cacheDir == null || (!cacheDir.exists() && !cacheDir.mkdirs())) return
        val file = File(cacheDir, layerFileName(index))
        runCatching {
            FileOutputStream(file).use { out ->
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
            }
        }.onFailure {
            Log.w(TAG, "Failed to save layer cache: ${file.absolutePath}", it)
        }
    }

    private fun layerFileName(index: Int): String = "layer_${index.toString().padStart(2, '0')}.png"

    private fun layerDepth(index: Int): Float {
        return (index.toFloat() / (DEPTH_LAYER_COUNT - 1).coerceAtLeast(1).toFloat())
            .coerceIn(0f, 1f)
    }

    private fun smoothStep(edge0: Float, edge1: Float, value: Float): Float {
        val range = edge1 - edge0
        if (range > -0.00001f && range < 0.00001f) {
            return if (value >= edge1) 1f else 0f
        }
        val raw = (value - edge0) / range
        val t = when {
            raw <= 0f -> 0f
            raw >= 1f -> 1f
            else -> raw
        }
        return t * t * (3f - 2f * t)
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
