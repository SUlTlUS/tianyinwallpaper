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
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.StandardCharsets
import java.util.Locale
import java.util.zip.ZipFile
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

object GaussianSogLoader {
    private const val TAG = "GaussianSogLoader"
    private const val SH_C0 = 0.28209479177387814f
    const val DEFAULT_MAX_SPLATS = 1_200_000
    private const val MIN_SPLAT_LIMIT = 10_000
    private const val MAX_SPLAT_LIMIT = 1_500_000
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

    private data class Quat(
        val x: Float,
        val y: Float,
        val z: Float,
        val w: Float
    )

    private data class ProjectedEllipse(
        val major: Float,
        val minor: Float,
        val angle: Float
    )

    private data class AnchorDepth(
        val depth: Float,
        val splatCount: Int,
        val minSplatCount: Int
    )

    private class SogSplatAccumulator(capacity: Int) {
        val x = FloatArray(capacity)
        val y = FloatArray(capacity)
        val z = FloatArray(capacity)
        val r = FloatArray(capacity)
        val g = FloatArray(capacity)
        val b = FloatArray(capacity)
        val a = FloatArray(capacity)
        val scaleX = FloatArray(capacity)
        val scaleY = FloatArray(capacity)
        val scaleZ = FloatArray(capacity)
        val quatX = FloatArray(capacity)
        val quatY = FloatArray(capacity)
        val quatZ = FloatArray(capacity)
        val quatW = FloatArray(capacity)
        val visible = BooleanArray(capacity)
        val orderKey = IntArray(capacity)
        var count = 0
            private set
        var near = Float.POSITIVE_INFINITY
            private set
        var far = Float.NEGATIVE_INFINITY
            private set
        var minX = Float.POSITIVE_INFINITY
            private set
        var minY = Float.POSITIVE_INFINITY
            private set
        var minZ = Float.POSITIVE_INFINITY
            private set
        var maxX = Float.NEGATIVE_INFINITY
            private set
        var maxY = Float.NEGATIVE_INFINITY
            private set
        var maxZ = Float.NEGATIVE_INFINITY
            private set
        var colorWeight = 0f
            private set
        var backgroundR = 0f
            private set
        var backgroundG = 0f
            private set
        var backgroundB = 0f
            private set
        var screenVisibleCount = 0
            private set

        fun add(
            px: Float,
            py: Float,
            pz: Float,
            cr: Float,
            cg: Float,
            cb: Float,
            ca: Float,
            sx: Float,
            sy: Float,
            sz: Float,
            qx: Float,
            qy: Float,
            qz: Float,
            qw: Float,
            isVisible: Boolean,
            key: Int
        ) {
            if (count >= x.size) return
            val index = count++
            x[index] = px
            y[index] = py
            z[index] = pz
            r[index] = cr
            g[index] = cg
            b[index] = cb
            a[index] = ca
            scaleX[index] = sx
            scaleY[index] = sy
            scaleZ[index] = sz
            quatX[index] = qx
            quatY[index] = qy
            quatZ[index] = qz
            quatW[index] = qw
            visible[index] = isVisible
            orderKey[index] = key
            minX = min(minX, px)
            minY = min(minY, py)
            minZ = min(minZ, pz)
            maxX = max(maxX, px)
            maxY = max(maxY, py)
            maxZ = max(maxZ, pz)
            near = min(near, pz)
            far = max(far, pz)
            backgroundR += cr * ca
            backgroundG += cg * ca
            backgroundB += cb * ca
            colorWeight += ca
            if (isVisible) screenVisibleCount += 1
        }
    }

    fun loadScene(
        context: Context,
        uriString: String,
        maxSplats: Int = DEFAULT_MAX_SPLATS,
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
        maxSplats: Int = DEFAULT_MAX_SPLATS,
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
        maxSplats: Int = DEFAULT_MAX_SPLATS,
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

        val quatsImage = meta.getJSONObject("quats")
            ?.requiredFiles("quats", 1)
            ?.firstOrNull()
            ?.let { files.decodeImage(it) }
        if (quatsImage != null) {
            require(count <= quatsImage.count) { "SOG count exceeds quats image size" }
        }

        val mins = meansMeta.requiredFloatArray("means.mins", "mins", 3)
        val maxs = meansMeta.requiredFloatArray("means.maxs", "maxs", 3)
        val flipZ = shouldFlipDepth(count, meansL, meansU, mins, maxs)

        val splats = SogSplatAccumulator(splatLimit)
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
                quatsImage = quatsImage,
                viewportAspect = viewportAspect,
                splats = splats
            )
        }

        return buildScene(
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
        quatsImage: ImageData?,
        viewportAspect: Float?,
        splats: SogSplatAccumulator
    ) {
        if (count > splatLimit && !GaussianPlyLoader.shouldKeepAuxiliarySplat(index, count, splatLimit)) {
            return
        }
        val decodedX = decodePosition(index, 0, meansL, meansU, mins, maxs)
        val decodedY = decodePosition(index, 1, meansL, meansU, mins, maxs)
        val z = decodePosition(index, 2, meansL, meansU, mins, maxs)
            .let { if (flipZ) -it else it }
        val opacity = sh0Image.channel(index, 3) / 255f
        if (!z.isFinite() || z <= 0.001f || opacity < 0.015f) return
        val x = -decodedX
        val y = -decodedY

        val visible = GaussianPlyLoader.isProjectedIntoViewport(
            x = x,
            y = y,
            z = z,
            imageWidth = DEFAULT_IMAGE_WIDTH,
            imageHeight = DEFAULT_IMAGE_HEIGHT,
            focalLengthPx = DEFAULT_FOCAL_LENGTH,
            viewportAspect = viewportAspect
        )
        val sx = decodeScale(scaleCodebook[scalesImage.channel(index, 0)])
        val sy = decodeScale(scaleCodebook[scalesImage.channel(index, 1)])
        val sz = decodeScale(scaleCodebook[scalesImage.channel(index, 2)])
        val quat = rotateQuatForWebView(decodeQuat(index, quatsImage) ?: Quat(0f, 0f, 0f, 1f))
        splats.add(
            px = x,
            py = y,
            pz = z,
            cr = shToColor(sh0Codebook[sh0Image.channel(index, 0)]),
            cg = shToColor(sh0Codebook[sh0Image.channel(index, 1)]),
            cb = shToColor(sh0Codebook[sh0Image.channel(index, 2)]),
            ca = opacity.coerceIn(0f, 1f),
            sx = sx.coerceIn(0.0001f, 1000f),
            sy = sy.coerceIn(0.0001f, 1000f),
            sz = sz.coerceIn(0.0001f, 1000f),
            qx = quat.x,
            qy = quat.y,
            qz = quat.z,
            qw = quat.w,
            isVisible = visible,
            key = GaussianPlyLoader.sampleHash(index)
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

    private fun buildScene(
        splats: SogSplatAccumulator,
        imageWidth: Int,
        imageHeight: Int,
        focalLengthPx: Float,
        sourceLabel: String
    ): GaussianPlyLoader.GaussianScene {
        val count = splats.count
        require(count > 0) { "$sourceLabel has no visible Gaussians" }
        val near = splats.near
        val far = splats.far
        val anchor = computeParallaxAnchor(splats, near, far)
        val layerBucketScale = if (far > near) (DEPTH_LAYER_COUNT - 1).toFloat() / (far - near) else 1f
        fun layerBucket(depth: Float): Int {
            return (((far - depth) * layerBucketScale).toInt())
                .coerceIn(0, DEPTH_LAYER_COUNT - 1)
        }
        val depthBucketScale = if (far > near) (DEPTH_SORT_BUCKETS - 1).toFloat() / (far - near) else 1f
        fun sortBucket(index: Int): Int {
            val layer = layerBucket(splats.z[index])
            val depth = ((splats.z[index] - near) * depthBucketScale)
                .toInt()
                .coerceIn(0, DEPTH_SORT_BUCKETS - 1)
            return layer * DEPTH_SORT_BUCKETS + (DEPTH_SORT_BUCKETS - 1 - depth)
        }

        val bucketCount = DEPTH_LAYER_COUNT * DEPTH_SORT_BUCKETS
        val counts = IntArray(bucketCount)
        for (index in 0 until count) {
            counts[sortBucket(index)] += 1
        }
        val starts = IntArray(bucketCount)
        var running = 0
        for (bucket in 0 until bucketCount) {
            starts[bucket] = running
            running += counts[bucket]
        }
        val cursors = starts.copyOf()
        val order = IntArray(count)
        for (index in 0 until count) {
            val bucket = sortBucket(index)
            order[cursors[bucket]++] = index
        }

        val positions = ByteBuffer
            .allocateDirect(count * 3 * FLOAT_SIZE_BYTES)
            .order(ByteOrder.nativeOrder())
            .asFloatBuffer()
        val colors = ByteBuffer
            .allocateDirect(count * 4 * FLOAT_SIZE_BYTES)
            .order(ByteOrder.nativeOrder())
            .asFloatBuffer()
        val scales = ByteBuffer
            .allocateDirect(count * 3 * FLOAT_SIZE_BYTES)
            .order(ByteOrder.nativeOrder())
            .asFloatBuffer()
        val rotations = ByteBuffer
            .allocateDirect(count * 4 * FLOAT_SIZE_BYTES)
            .order(ByteOrder.nativeOrder())
            .asFloatBuffer()

        var activeLayerBucket = -1
        var activeLayerStart = 0
        var activeLayerCount = 0
        var activeLayerDepthSum = 0.0
        val depthLayers = ArrayList<GaussianPlyLoader.GaussianDepthLayer>(DEPTH_LAYER_COUNT)
        fun flushLayer() {
            if (activeLayerCount <= 0) return
            depthLayers += GaussianPlyLoader.GaussianDepthLayer(
                start = activeLayerStart,
                count = activeLayerCount,
                depth = (activeLayerDepthSum / activeLayerCount).toFloat(),
                depthBucket = activeLayerBucket
            )
        }

        for (orderedIndex in 0 until count) {
            val index = order[orderedIndex]
            val currentLayer = layerBucket(splats.z[index])
            if (currentLayer != activeLayerBucket) {
                flushLayer()
                activeLayerBucket = currentLayer
                activeLayerStart = positions.position() / 3
                activeLayerCount = 0
                activeLayerDepthSum = 0.0
            }
            activeLayerCount += 1
            activeLayerDepthSum += splats.z[index].toDouble()
            positions.put(splats.x[index])
            positions.put(splats.y[index])
            positions.put(splats.z[index])
            colors.put(splats.r[index])
            colors.put(splats.g[index])
            colors.put(splats.b[index])
            colors.put(splats.a[index])
            scales.put(splats.scaleX[index])
            scales.put(splats.scaleY[index])
            scales.put(splats.scaleZ[index])
            rotations.put(splats.quatX[index])
            rotations.put(splats.quatY[index])
            rotations.put(splats.quatZ[index])
            rotations.put(splats.quatW[index])
        }
        flushLayer()

        positions.position(0)
        colors.position(0)
        scales.position(0)
        rotations.position(0)
        val focus = splats.z[order[count / 2]]
        val colorWeight = splats.colorWeight.coerceAtLeast(0.0001f)
        val bounds = GaussianPlyLoader.computeSceneBounds(
            minX = splats.minX,
            minY = splats.minY,
            minZ = splats.minZ,
            maxX = splats.maxX,
            maxY = splats.maxY,
            maxZ = splats.maxZ,
            imageHeight = imageHeight,
            focalLengthPx = focalLengthPx
        )
        val defaultCameraDistance = WEBVIEW_INITIAL_CAMERA_DISTANCE +
            bounds.radius * WEBVIEW_DEFAULT_FOCUS_DEPTH_OFFSET
        Log.d(
            TAG,
            "built SOG scene count=$count visible=${splats.screenVisibleCount} aux=${count - splats.screenVisibleCount} " +
                "near=$near far=$far radius=${bounds.radius} distance=$defaultCameraDistance " +
                "camera=webview target=[0.000, 0.000, 0.000] " +
                "heap=${Runtime.getRuntime().totalMemory() / 1024 / 1024}MB"
        )
        return GaussianPlyLoader.GaussianScene(
            count = count,
            positions = positions,
            colors = colors,
            scales = scales,
            depthLayers = depthLayers,
            imageWidth = imageWidth.coerceAtLeast(1),
            imageHeight = imageHeight.coerceAtLeast(1),
            focalLengthPx = focalLengthPx.coerceAtLeast(1f),
            nearDepth = near,
            farDepth = far,
            focusDepth = focus,
            parallaxAnchorDepth = anchor.depth,
            parallaxAnchorSplatCount = anchor.splatCount,
            parallaxAnchorMinSplatCount = anchor.minSplatCount,
            screenVisibleSplatCount = splats.screenVisibleCount,
            auxiliarySplatCount = count - splats.screenVisibleCount,
            backgroundR = (splats.backgroundR / colorWeight).coerceIn(0f, 1f),
            backgroundG = (splats.backgroundG / colorWeight).coerceIn(0f, 1f),
            backgroundB = (splats.backgroundB / colorWeight).coerceIn(0f, 1f),
            sceneCenterX = WEBVIEW_CAMERA_TARGET_X,
            sceneCenterY = WEBVIEW_CAMERA_TARGET_Y,
            sceneCenterZ = WEBVIEW_CAMERA_TARGET_Z,
            sceneRadius = bounds.radius,
            defaultCameraDistance = defaultCameraDistance,
            rotations = rotations
        )
    }

    private fun computeParallaxAnchor(
        splats: SogSplatAccumulator,
        near: Float,
        far: Float
    ): AnchorDepth {
        val count = splats.count
        val minSplatCount = max(
            ANCHOR_MIN_SPLATS,
            (count * ANCHOR_MIN_SPLAT_FRACTION).toInt()
        ).coerceAtMost(count.coerceAtLeast(1))
        if (far <= near || count <= minSplatCount) {
            return AnchorDepth(far, count, minSplatCount)
        }

        val counts = IntArray(ANCHOR_DEPTH_BUCKETS)
        val depthSums = DoubleArray(ANCHOR_DEPTH_BUCKETS)
        val bucketScale = (ANCHOR_DEPTH_BUCKETS - 1).toFloat() / (far - near)
        for (index in 0 until count) {
            val bucket = ((splats.z[index] - near) * bucketScale)
                .toInt()
                .coerceIn(0, ANCHOR_DEPTH_BUCKETS - 1)
            counts[bucket] += 1
            depthSums[bucket] += splats.z[index].toDouble()
        }

        var bestCount = 0
        var bestDepthSum = 0.0
        for (end in ANCHOR_DEPTH_BUCKETS - 1 downTo 0) {
            val start = max(0, end - ANCHOR_LAYER_WINDOW_BUCKETS + 1)
            var windowCount = 0
            var windowDepthSum = 0.0
            for (bucket in start..end) {
                windowCount += counts[bucket]
                windowDepthSum += depthSums[bucket]
            }
            if (windowCount > bestCount) {
                bestCount = windowCount
                bestDepthSum = windowDepthSum
            }
            if (windowCount >= minSplatCount) {
                return AnchorDepth((windowDepthSum / windowCount).toFloat(), windowCount, minSplatCount)
            }
        }

        return if (bestCount > 0) {
            AnchorDepth((bestDepthSum / bestCount).toFloat(), bestCount, minSplatCount)
        } else {
            AnchorDepth(far, 0, minSplatCount)
        }
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
        return exp(value).coerceAtLeast(0.0001f)
    }

    private fun decodeQuat(index: Int, quatsImage: ImageData?): Quat? {
        if (quatsImage == null || index >= quatsImage.count) return null
        val norm = 1.41421356237f
        val a = (quatsImage.channel(index, 0) / 255f - 0.5f) * norm
        val b = (quatsImage.channel(index, 1) / 255f - 0.5f) * norm
        val c = (quatsImage.channel(index, 2) / 255f - 0.5f) * norm
        val d = sqrt(max(0f, 1f - (a * a + b * b + c * c)))
        return when (quatsImage.channel(index, 3) - 252) {
            0 -> Quat(a, b, c, d)
            1 -> Quat(d, b, c, a)
            2 -> Quat(b, d, c, a)
            3 -> Quat(b, c, d, a)
            else -> null
        }
    }

    private fun projectEllipse(
        x: Float,
        y: Float,
        z: Float,
        sx: Float,
        sy: Float,
        sz: Float,
        quat: Quat?
    ): ProjectedEllipse {
        if (quat == null) {
            val major = max(max(sx, sy), sz * 0.45f)
            val minor = max(min(sx, sy), major * 0.35f)
            return ProjectedEllipse(major, minor, 0f)
        }

        val x2 = quat.x + quat.x
        val y2 = quat.y + quat.y
        val z2 = quat.z + quat.z
        val xx = quat.x * x2
        val xy = quat.x * y2
        val xz = quat.x * z2
        val yy = quat.y * y2
        val yz = quat.y * z2
        val zz = quat.z * z2
        val wx = quat.w * x2
        val wy = quat.w * y2
        val wz = quat.w * z2

        val r00 = 1f - yy - zz
        val r01 = xy - wz
        val r02 = xz + wy
        val r10 = xy + wz
        val r11 = 1f - xx - zz
        val r12 = yz - wx
        val r20 = xz - wy
        val r21 = yz + wx
        val r22 = 1f - xx - yy

        val sx2 = sx * sx
        val sy2 = sy * sy
        val sz2 = sz * sz
        val covXX = sx2 * r00 * r00 + sy2 * r01 * r01 + sz2 * r02 * r02
        val covXY = sx2 * r00 * r10 + sy2 * r01 * r11 + sz2 * r02 * r12
        val covYY = sx2 * r10 * r10 + sy2 * r11 * r11 + sz2 * r12 * r12
        val covXZ = sx2 * r00 * r20 + sy2 * r01 * r21 + sz2 * r02 * r22
        val covYZ = sx2 * r10 * r20 + sy2 * r11 * r21 + sz2 * r12 * r22
        val covZZ = sx2 * r20 * r20 + sy2 * r21 * r21 + sz2 * r22 * r22

        val safeZ = max(z, 0.02f)
        val focal = DEFAULT_FOCAL_LENGTH
        val j00 = focal / safeZ
        val j02 = -focal * x / (safeZ * safeZ)
        val j11 = focal / safeZ
        val j12 = -focal * y / (safeZ * safeZ)
        val screenCovXX = j00 * j00 * covXX + 2f * j00 * j02 * covXZ + j02 * j02 * covZZ
        val screenCovXY = j00 * j11 * covXY + j00 * j12 * covXZ + j02 * j11 * covYZ + j02 * j12 * covZZ
        val screenCovYY = j11 * j11 * covYY + 2f * j11 * j12 * covYZ + j12 * j12 * covZZ

        val trace = (screenCovXX + screenCovYY) * 0.5f
        val diff = (screenCovXX - screenCovYY) * 0.5f
        val radius = sqrt(max(0f, diff * diff + screenCovXY * screenCovXY))
        val screenMajor = sqrt(max(trace + radius, 0.00000001f))
        val screenMinor = sqrt(max(trace - radius, screenMajor * screenMajor * 0.0144f))
        val worldPerPixel = safeZ / focal
        val major = screenMajor * worldPerPixel
        val minor = screenMinor * worldPerPixel
        val angle = 0.5f * atan2(2f * screenCovXY, screenCovXX - screenCovYY)
        return ProjectedEllipse(major, minor, angle)
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

    private fun rotateQuatForWebView(quat: Quat): Quat {
        return Quat(
            x = -quat.y,
            y = quat.x,
            z = quat.w,
            w = -quat.z
        )
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
        if (lower.startsWith("sh") && !lower.startsWith("sh0")) return false
        return true
    }

    private const val DEFAULT_IMAGE_WIDTH = 640
    private const val DEFAULT_IMAGE_HEIGHT = 480
    private const val DEFAULT_FOCAL_LENGTH = 512f
    private const val FLOAT_SIZE_BYTES = 4
    private const val DEPTH_SORT_BUCKETS = 4096
    private const val DEPTH_LAYER_COUNT = 16
    private const val ANCHOR_DEPTH_BUCKETS = 128
    private const val ANCHOR_LAYER_WINDOW_BUCKETS = 4
    private const val ANCHOR_MIN_SPLATS = 768
    private const val ANCHOR_MIN_SPLAT_FRACTION = 0.012f
    private const val WEBVIEW_CAMERA_TARGET_X = 0f
    private const val WEBVIEW_CAMERA_TARGET_Y = 0f
    private const val WEBVIEW_CAMERA_TARGET_Z = 0f
    private const val WEBVIEW_INITIAL_CAMERA_DISTANCE = 1.8f
    private const val WEBVIEW_DEFAULT_FOCUS_DEPTH_OFFSET = 0.25f
}
