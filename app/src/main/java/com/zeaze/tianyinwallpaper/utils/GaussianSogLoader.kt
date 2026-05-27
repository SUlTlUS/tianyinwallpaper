package com.zeaze.tianyinwallpaper.utils

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Log
import com.alibaba.fastjson.JSON
import com.alibaba.fastjson.JSONArray
import com.alibaba.fastjson.JSONObject
import java.io.ByteArrayInputStream
import java.io.File
import java.io.InputStream
import java.nio.charset.StandardCharsets
import java.util.Locale
import java.util.zip.ZipFile
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min

object GaussianSogLoader {
    private const val TAG = "GaussianSogLoader"
    private const val SH_C0 = 0.28209479177387814f
    private const val MIN_SPLAT_LIMIT = 10_000
    private const val MAX_SPLAT_LIMIT = 900_000
    private const val DEPTH_SIGN_SAMPLE_COUNT = 4096

    private data class ImageData(
        val width: Int,
        val height: Int,
        val pixels: IntArray
    ) {
        val count: Int = width * height

        fun channel(index: Int, channel: Int): Int {
            val pixel = pixels[index]
            return when (channel) {
                0 -> (pixel ushr 16) and 0xff
                1 -> (pixel ushr 8) and 0xff
                2 -> pixel and 0xff
                else -> (pixel ushr 24) and 0xff
            }
        }
    }

    fun loadScene(
        context: Context,
        uriString: String,
        maxSplats: Int = GaussianPlyLoader.DEFAULT_MAX_SPLATS,
        viewportAspect: Float? = null
    ): GaussianPlyLoader.GaussianScene? {
        if (uriString.isBlank()) return null
        return runCatching {
            val files = context.contentResolver.openInputStream(Uri.parse(uriString))?.use { input ->
                readBundledFiles(context, input)
            } ?: error("Cannot open SOG uri: $uriString")
            parse(files, maxSplats, viewportAspect)
        }.onFailure {
            Log.w(TAG, "Failed to load Gaussian SOG: $uriString", it)
        }.getOrNull()
    }

    fun loadSceneOrThrow(
        context: Context,
        uriString: String,
        maxSplats: Int = GaussianPlyLoader.DEFAULT_MAX_SPLATS,
        viewportAspect: Float? = null
    ): GaussianPlyLoader.GaussianScene {
        if (uriString.isBlank()) error("Empty SOG uri")
        val files = context.contentResolver.openInputStream(Uri.parse(uriString))?.use { input ->
            readBundledFiles(context, input)
        } ?: error("Cannot open SOG uri: $uriString")
        return parse(files, maxSplats, viewportAspect)
    }

    fun parse(
        files: Map<String, ByteArray>,
        maxSplats: Int = GaussianPlyLoader.DEFAULT_MAX_SPLATS,
        viewportAspect: Float? = null
    ): GaussianPlyLoader.GaussianScene {
        val metaBytes = files.findEntry("meta.json")
            ?: error("SOG missing meta.json; entries=${files.keys.distinct().sorted().take(12).joinToString()}")
        val meta = JSON.parseObject(String(metaBytes, StandardCharsets.UTF_8))
        require(meta.getIntValue("version") == 2) { "Unsupported SOG version: ${meta.getIntValue("version")}" }
        val count = meta.getIntValue("count")
        require(count > 0) { "SOG has no splats" }
        val splatLimit = maxSplats
            .coerceIn(MIN_SPLAT_LIMIT, MAX_SPLAT_LIMIT)
            .coerceAtMost(count)

        val meansMeta = meta.getJSONObject("means") ?: error("SOG missing means metadata")
        val meansFiles = meansMeta.requiredFiles("means", 2)
        val meansL = files.decodeImage(meansFiles[0])
        val meansU = files.decodeImage(meansFiles[1])
        requireSameImageShape("means", meansL, meansU)
        require(count <= min(meansL.count, meansU.count)) { "SOG count exceeds means image size" }

        val scalesMeta = meta.getJSONObject("scales") ?: error("SOG missing scales metadata")
        val scalesImage = files.decodeImage(scalesMeta.requiredFiles("scales", 1)[0])
        val scaleCodebook = scalesMeta.requiredCodebook("scales")
        require(count <= scalesImage.count) { "SOG count exceeds scales image size" }

        val sh0Meta = meta.getJSONObject("sh0") ?: error("SOG missing sh0 metadata")
        val sh0Image = files.decodeImage(sh0Meta.requiredFiles("sh0", 1)[0])
        val sh0Codebook = sh0Meta.requiredCodebook("sh0")
        require(count <= sh0Image.count) { "SOG count exceeds sh0 image size" }

        val mins = meansMeta.requiredFloatArray("means.mins", "mins", 3)
        val maxs = meansMeta.requiredFloatArray("means.maxs", "maxs", 3)
        val flipZ = shouldFlipDepth(count, meansL, meansU, mins, maxs)

        val splats = ArrayList<GaussianPlyLoader.GaussianSplat>(splatLimit)
        var fallbackNeeded = true
        repeat(count) { index ->
            addDecodedSplat(
                index = index,
                count = count,
                splatLimit = splatLimit,
                meansL = meansL,
                meansU = meansU,
                mins = mins,
                maxs = maxs,
                flipZ = flipZ,
                scalesImage = scalesImage,
                scaleCodebook = scaleCodebook,
                sh0Image = sh0Image,
                sh0Codebook = sh0Codebook,
                viewportAspect = viewportAspect,
                requireViewportVisible = true,
                splats = splats
            )
        }
        if (splats.isNotEmpty()) {
            fallbackNeeded = false
        }
        if (fallbackNeeded) {
            repeat(count) { index ->
                addDecodedSplat(
                    index = index,
                    count = count,
                    splatLimit = splatLimit,
                    meansL = meansL,
                    meansU = meansU,
                    mins = mins,
                    maxs = maxs,
                    flipZ = flipZ,
                    scalesImage = scalesImage,
                    scaleCodebook = scaleCodebook,
                    sh0Image = sh0Image,
                    sh0Codebook = sh0Codebook,
                    viewportAspect = null,
                    requireViewportVisible = false,
                    splats = splats
                )
            }
        }

        return GaussianPlyLoader.buildScene(
            splats = splats,
            imageWidth = DEFAULT_IMAGE_WIDTH,
            imageHeight = DEFAULT_IMAGE_HEIGHT,
            focalLengthPx = DEFAULT_FOCAL_LENGTH,
            sourceLabel = "SOG"
        )
    }

    private fun addDecodedSplat(
        index: Int,
        count: Int,
        splatLimit: Int,
        meansL: ImageData,
        meansU: ImageData,
        mins: FloatArray,
        maxs: FloatArray,
        flipZ: Boolean,
        scalesImage: ImageData,
        scaleCodebook: FloatArray,
        sh0Image: ImageData,
        sh0Codebook: FloatArray,
        viewportAspect: Float?,
        requireViewportVisible: Boolean,
        splats: MutableList<GaussianPlyLoader.GaussianSplat>
    ) {
        if (!GaussianPlyLoader.shouldKeepAuxiliarySplat(index, count, splatLimit)) {
            return
        }
        val x = decodePosition(index, 0, meansL, meansU, mins, maxs)
        val y = decodePosition(index, 1, meansL, meansU, mins, maxs)
        val z = decodePosition(index, 2, meansL, meansU, mins, maxs)
            .let { if (flipZ) -it else it }
        val opacity = sh0Image.channel(index, 3) / 255f
        if (!z.isFinite() || z <= 0.001f || opacity < 0.015f) return

        val visible = GaussianPlyLoader.isProjectedIntoViewport(
            x = x,
            y = y,
            z = z,
            imageWidth = DEFAULT_IMAGE_WIDTH,
            imageHeight = DEFAULT_IMAGE_HEIGHT,
            focalLengthPx = DEFAULT_FOCAL_LENGTH,
            viewportAspect = viewportAspect
        )
        if (requireViewportVisible && !visible) {
            return
        }

        val sx = decodeScale(scaleCodebook[scalesImage.channel(index, 0)])
        val sy = decodeScale(scaleCodebook[scalesImage.channel(index, 1)])
        val sz = decodeScale(scaleCodebook[scalesImage.channel(index, 2)])
        splats += GaussianPlyLoader.GaussianSplat(
            x = x,
            y = y,
            z = z,
            r = shToColor(sh0Codebook[sh0Image.channel(index, 0)]),
            g = shToColor(sh0Codebook[sh0Image.channel(index, 1)]),
            b = shToColor(sh0Codebook[sh0Image.channel(index, 2)]),
            a = opacity.coerceIn(0f, 1f),
            scaleX = max(sx, sz * 0.45f).coerceIn(0.0001f, 1000f),
            scaleY = max(sy, sz * 0.45f).coerceIn(0.0001f, 1000f),
            angle = 0f,
            isScreenVisible = visible,
            orderKey = GaussianPlyLoader.sampleHash(index)
        )
    }

    private fun decodePosition(
        index: Int,
        axis: Int,
        meansL: ImageData,
        meansU: ImageData,
        mins: FloatArray,
        maxs: FloatArray
    ): Float {
        val q = (meansU.channel(index, axis) shl 8) or meansL.channel(index, axis)
        val n = lerp(mins[axis], maxs[axis], q / 65535f)
        return symmetricUnlog(n)
    }

    private fun shouldFlipDepth(
        count: Int,
        meansL: ImageData,
        meansU: ImageData,
        mins: FloatArray,
        maxs: FloatArray
    ): Boolean {
        val samples = min(DEPTH_SIGN_SAMPLE_COUNT, count)
        var positive = 0
        var negative = 0
        repeat(samples) { sample ->
            val index = ((sample.toLong() * count) / samples).toInt().coerceIn(0, count - 1)
            val z = decodePosition(index, 2, meansL, meansU, mins, maxs)
            when {
                z > 0f -> positive += 1
                z < 0f -> negative += 1
            }
        }
        return negative > positive
    }

    private fun Map<String, ByteArray>.decodeImage(name: String): ImageData {
        val bytes = findEntry(name) ?: error("SOG missing image: $name")
        val bitmap = BitmapFactory.decodeStream(ByteArrayInputStream(bytes), null, BitmapFactory.Options().apply {
            inPreferredConfig = Bitmap.Config.ARGB_8888
            inPremultiplied = false
        }) ?: error("Failed to decode SOG image: $name")
        return bitmap.toImageData().also { bitmap.recycle() }
    }

    private fun Bitmap.toImageData(): ImageData {
        val source = if (config == Bitmap.Config.ARGB_8888) this else copy(Bitmap.Config.ARGB_8888, false)
        val width = source.width
        val height = source.height
        val pixels = IntArray(width * height)
        source.getPixels(pixels, 0, width, 0, 0, width, height)
        if (source !== this) source.recycle()
        return ImageData(width, height, pixels)
    }

    private fun JSONObject.requiredFiles(label: String, minCount: Int): List<String> {
        val files = getJSONArray("files") ?: error("SOG $label missing files")
        require(files.size >= minCount) { "SOG $label requires $minCount file(s)" }
        return List(files.size) { index -> files.getString(index) }
    }

    private fun JSONObject.requiredCodebook(label: String): FloatArray {
        return requiredFloatArray("$label.codebook", "codebook", 256)
    }

    private fun JSONObject.requiredFloatArray(label: String, key: String, minCount: Int): FloatArray {
        val array = getJSONArray(key) ?: error("SOG missing $label")
        require(array.size >= minCount) { "SOG $label requires at least $minCount values" }
        return array.toFloatArray()
    }

    private fun JSONArray.toFloatArray(): FloatArray {
        return FloatArray(size) { index -> getDoubleValue(index).toFloat() }
    }

    private fun requireSameImageShape(label: String, a: ImageData, b: ImageData) {
        require(a.width == b.width && a.height == b.height) {
            "SOG $label image size mismatch: ${a.width}x${a.height} vs ${b.width}x${b.height}"
        }
    }

    private fun symmetricUnlog(value: Float): Float {
        val sign = if (value < 0f) -1f else 1f
        return sign * (exp(abs(value)) - 1f)
    }

    private fun lerp(a: Float, b: Float, t: Float): Float {
        return a + (b - a) * t.coerceIn(0f, 1f)
    }

    private fun shToColor(value: Float): Float {
        return (0.5f + value * SH_C0).coerceIn(0f, 1f)
    }

    private fun decodeScale(value: Float): Float {
        val linear = if (value < 0f) exp(value) else value
        return linear.coerceAtLeast(0.0001f)
    }

    private fun readBundledFiles(context: Context, input: InputStream): Map<String, ByteArray> {
        val tempFile = File.createTempFile("gaussian_sog_", ".sog", context.cacheDir)
        return try {
            tempFile.outputStream().use { output -> input.copyTo(output) }
            ZipFile(tempFile).use { zip ->
            val entries = HashMap<String, ByteArray>()
                val zipEntries = zip.entries()
                while (zipEntries.hasMoreElements()) {
                    val entry = zipEntries.nextElement()
                if (!entry.isDirectory) {
                    val name = entry.name
                    val baseName = entryBaseName(name)
                    if (shouldKeepBundledEntry(baseName)) {
                            val bytes = zip.getInputStream(entry).use { it.readBytes() }
                        entries.storeEntry(name, bytes)
                        entries.storeEntry(baseName, bytes)
                    }
                }
            }
            entries
        }
        } finally {
            tempFile.delete()
        }
    }

    private fun MutableMap<String, ByteArray>.storeEntry(name: String, bytes: ByteArray) {
        this[name] = bytes
        this[name.lowercase(Locale.US)] = bytes
    }

    private fun Map<String, ByteArray>.findEntry(name: String): ByteArray? {
        return this[name]
            ?: this[name.lowercase(Locale.US)]
            ?: this[entryBaseName(name)]
            ?: this[entryBaseName(name).lowercase(Locale.US)]
    }

    private fun entryBaseName(name: String): String {
        return name.substringAfterLast('/').substringAfterLast('\\')
    }

    private fun shouldKeepBundledEntry(baseName: String): Boolean {
        val lower = baseName.lowercase()
        if (lower == "meta.json") return true
        if (!lower.endsWith(".webp")) return false
        if (lower.startsWith("quat")) return false
        if (lower.startsWith("sh") && !lower.startsWith("sh0")) return false
        return true
    }

    private const val DEFAULT_IMAGE_WIDTH = 640
    private const val DEFAULT_IMAGE_HEIGHT = 480
    private const val DEFAULT_FOCAL_LENGTH = 512f
}
