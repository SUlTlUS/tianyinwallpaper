package com.zeaze.tianyinwallpaper.utils

import android.content.Context
import android.net.Uri
import android.util.Log
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.nio.charset.StandardCharsets
import kotlin.math.atan2
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

object GaussianPlyLoader {
    private const val TAG = "GaussianPlyLoader"
    const val DEFAULT_MAX_SPLATS = 500_000
    private const val SH_C0 = 0.28209479177387814f

    data class GaussianScene(
        val count: Int,
        val positions: FloatBuffer,
        val colors: FloatBuffer,
        val scales: FloatBuffer,
        val depthLayers: List<GaussianDepthLayer>,
        val imageWidth: Int,
        val imageHeight: Int,
        val focalLengthPx: Float,
        val nearDepth: Float,
        val farDepth: Float,
        val focusDepth: Float,
        val parallaxAnchorDepth: Float,
        val parallaxAnchorSplatCount: Int,
        val parallaxAnchorMinSplatCount: Int,
        val screenVisibleSplatCount: Int,
        val auxiliarySplatCount: Int,
        val backgroundR: Float,
        val backgroundG: Float,
        val backgroundB: Float
    )

    data class GaussianDepthLayer(
        val start: Int,
        val count: Int,
        val depth: Float,
        val depthBucket: Int
    )

    private data class PlyElement(
        val name: String,
        val count: Int,
        val properties: List<PlyProperty>,
        val dataOffset: Int,
        val stride: Int
    )

    private data class PlyProperty(
        val type: String,
        val name: String,
        val offset: Int
    )

    private data class HeaderParseResult(
        val format: String,
        val dataStart: Int,
        val elements: List<PlyElement>
    )

    private data class Splat(
        val x: Float,
        val y: Float,
        val z: Float,
        val r: Float,
        val g: Float,
        val b: Float,
        val a: Float,
        val scaleX: Float,
        val scaleY: Float,
        val angle: Float,
        val isScreenVisible: Boolean,
        val orderKey: Int
    )

    private data class LayerBuildState(
        val start: Int,
        var count: Int = 0,
        var depthSum: Double = 0.0
    )

    private data class AsciiMetadata(
        val imageWidth: Int,
        val imageHeight: Int,
        val focalLengthPx: Float
    )

    private data class AnchorDepth(
        val depth: Float,
        val splatCount: Int,
        val minSplatCount: Int
    )

    fun loadScene(
        context: Context,
        uriString: String,
        maxSplats: Int = DEFAULT_MAX_SPLATS,
        viewportAspect: Float? = null
    ): GaussianScene? {
        if (uriString.isBlank()) return null
        return runCatching {
            context.contentResolver.openInputStream(Uri.parse(uriString))?.use { it.readBytes() }
                ?: error("Cannot open PLY uri: $uriString")
        }.mapCatching { bytes ->
            parse(bytes, maxSplats, viewportAspect)
        }.onFailure {
            Log.w(TAG, "Failed to load Gaussian PLY: $uriString", it)
        }.getOrNull()
    }

    fun parse(
        bytes: ByteArray,
        maxSplats: Int = DEFAULT_MAX_SPLATS,
        viewportAspect: Float? = null
    ): GaussianScene {
        val header = parseHeader(bytes)
        val splatLimit = maxSplats.coerceIn(MIN_SPLAT_LIMIT, MAX_SPLAT_LIMIT)
        return when (header.format) {
            "binary_little_endian" -> parseBinaryLittleEndian(bytes, header, splatLimit, viewportAspect)
            "ascii" -> parseAscii(bytes, header, splatLimit, viewportAspect)
            else -> throw IllegalArgumentException("Unsupported PLY format: ${header.format}")
        }
    }

    private fun parseHeader(bytes: ByteArray): HeaderParseResult {
        val dataStart = findDataStart(bytes)
        val headerText = String(bytes, 0, dataStart, StandardCharsets.US_ASCII)
        val lines = headerText.lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .toList()
        require(lines.firstOrNull() == "ply") { "Not a PLY file" }

        var format = ""
        var runningOffset = dataStart
        val elements = mutableListOf<MutableElement>()
        var current: MutableElement? = null

        for (line in lines.drop(1)) {
            val parts = line.split(Regex("\\s+"))
            when (parts.firstOrNull()) {
                "format" -> format = parts.getOrNull(1).orEmpty()
                "element" -> {
                    val element = MutableElement(
                        name = parts.getOrNull(1).orEmpty(),
                        count = parts.getOrNull(2)?.toIntOrNull() ?: 0
                    )
                    elements += element
                    current = element
                }
                "property" -> {
                    if (parts.getOrNull(1) == "list") {
                        throw IllegalArgumentException("PLY list properties are not supported")
                    }
                    val element = current ?: continue
                    val type = parts.getOrNull(1).orEmpty()
                    val name = parts.getOrNull(2).orEmpty()
                    val offset = element.stride
                    element.properties += PlyProperty(type, name, offset)
                    element.stride += typeSize(type)
                }
            }
        }

        val frozen = elements.map { element ->
            val dataOffset = runningOffset
            runningOffset += element.count * element.stride
            PlyElement(
                name = element.name,
                count = element.count,
                properties = element.properties.toList(),
                dataOffset = dataOffset,
                stride = element.stride
            )
        }

        return HeaderParseResult(format, dataStart, frozen)
    }

    private class MutableElement(
        val name: String,
        val count: Int
    ) {
        val properties = mutableListOf<PlyProperty>()
        var stride: Int = 0
    }

    private fun findDataStart(bytes: ByteArray): Int {
        val marker = "end_header".toByteArray(StandardCharsets.US_ASCII)
        val index = bytes.indexOf(marker)
        require(index >= 0) { "PLY header missing end_header" }
        var dataStart = index + marker.size
        if (dataStart < bytes.size && bytes[dataStart] == '\r'.code.toByte()) dataStart += 1
        if (dataStart < bytes.size && bytes[dataStart] == '\n'.code.toByte()) dataStart += 1
        return dataStart
    }

    private fun parseBinaryLittleEndian(
        bytes: ByteArray,
        header: HeaderParseResult,
        maxSplats: Int,
        viewportAspect: Float?
    ): GaussianScene {
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        val vertex = header.elements.firstOrNull { it.name == "vertex" }
            ?: throw IllegalArgumentException("PLY missing vertex element")
        val properties = vertex.properties.associateBy { it.name }
        val required = listOf(
            "x", "y", "z",
            "f_dc_0", "f_dc_1", "f_dc_2",
            "opacity", "scale_0", "scale_1", "scale_2"
        )
        required.forEach { name ->
            require(name in properties) { "PLY missing vertex property: $name" }
        }

        val imageSize = readImageSizeBinary(buffer, header.elements)
        val focal = readFocalLengthBinary(buffer, header.elements)
        val splats = ArrayList<Splat>(min(maxSplats, vertex.count))

        for (i in 0 until vertex.count) {
            val base = vertex.dataOffset + i * vertex.stride
            val x = properties.getFloat(buffer, base, "x")
            val y = properties.getFloat(buffer, base, "y")
            val z = properties.getFloat(buffer, base, "z")
            val opacity = sigmoid(properties.getFloat(buffer, base, "opacity"))
            if (!z.isFinite() || z <= 0.001f || opacity < 0.015f) continue
            val isScreenVisible = isProjectedIntoViewport(
                x = x,
                y = y,
                z = z,
                imageWidth = imageSize.first,
                imageHeight = imageSize.second,
                focalLengthPx = focal,
                viewportAspect = viewportAspect
            )
            if (!isScreenVisible && !shouldKeepAuxiliarySplat(i, vertex.count, maxSplats)) continue
            val scale0 = exp(properties.getFloat(buffer, base, "scale_0"))
            val scale1 = exp(properties.getFloat(buffer, base, "scale_1"))
            val scale2 = exp(properties.getFloat(buffer, base, "scale_2"))
            val angle = readQuaternionAngle(properties.keys) { name ->
                properties.getFloat(buffer, base, name)
            }
            splats += Splat(
                x = x,
                y = y,
                z = z,
                r = shToColor(properties.getFloat(buffer, base, "f_dc_0")),
                g = shToColor(properties.getFloat(buffer, base, "f_dc_1")),
                b = shToColor(properties.getFloat(buffer, base, "f_dc_2")),
                a = opacity,
                scaleX = max(scale0, scale2 * 0.45f).coerceIn(0.0001f, 1000f),
                scaleY = max(scale1, scale2 * 0.45f).coerceIn(0.0001f, 1000f),
                angle = angle,
                isScreenVisible = isScreenVisible,
                orderKey = sampleHash(i)
            )
        }
        return buildScene(splats, imageSize.first, imageSize.second, focal)
    }

    private fun parseAscii(
        bytes: ByteArray,
        header: HeaderParseResult,
        maxSplats: Int,
        viewportAspect: Float?
    ): GaussianScene {
        val text = String(bytes, header.dataStart, bytes.size - header.dataStart, StandardCharsets.US_ASCII)
        val vertex = header.elements.firstOrNull { it.name == "vertex" }
            ?: throw IllegalArgumentException("PLY missing vertex element")
        val propertyNames = vertex.properties.map { it.name }
        val index = propertyNames.withIndex().associate { it.value to it.index }
        val required = listOf(
            "x", "y", "z",
            "f_dc_0", "f_dc_1", "f_dc_2",
            "opacity", "scale_0", "scale_1", "scale_2"
        )
        required.forEach { name ->
            require(name in index) { "PLY missing vertex property: $name" }
        }

        val allLines = text.lineSequence().toList()
        val metadata = readAsciiMetadata(header, allLines.drop(vertex.count).iterator())
        val splats = ArrayList<Splat>(min(maxSplats, vertex.count))
        for (i in 0 until vertex.count) {
            val parts = allLines.getOrNull(i)?.trim()?.split(Regex("\\s+")) ?: break
            if (parts.size < propertyNames.size) continue
            val x = parts.floatAt(index.getValue("x"))
            val y = parts.floatAt(index.getValue("y"))
            val z = parts.floatAt(index.getValue("z"))
            val opacity = sigmoid(parts.floatAt(index.getValue("opacity")))
            if (!z.isFinite() || z <= 0.001f || opacity < 0.015f) continue
            val isScreenVisible = isProjectedIntoViewport(
                x = x,
                y = y,
                z = z,
                imageWidth = metadata.imageWidth,
                imageHeight = metadata.imageHeight,
                focalLengthPx = metadata.focalLengthPx,
                viewportAspect = viewportAspect
            )
            if (!isScreenVisible && !shouldKeepAuxiliarySplat(i, vertex.count, maxSplats)) continue
            val scale = max(
                exp(parts.floatAt(index.getValue("scale_0"))),
                max(
                    exp(parts.floatAt(index.getValue("scale_1"))),
                    exp(parts.floatAt(index.getValue("scale_2")))
                )
            ).coerceIn(0.0001f, 1000f)
            val scale0 = exp(parts.floatAt(index.getValue("scale_0")))
            val scale1 = exp(parts.floatAt(index.getValue("scale_1")))
            val scale2 = exp(parts.floatAt(index.getValue("scale_2")))
            val angle = readQuaternionAngle(index.keys) { name ->
                parts.floatAt(index.getValue(name))
            }
            splats += Splat(
                x = x,
                y = y,
                z = z,
                r = shToColor(parts.floatAt(index.getValue("f_dc_0"))),
                g = shToColor(parts.floatAt(index.getValue("f_dc_1"))),
                b = shToColor(parts.floatAt(index.getValue("f_dc_2"))),
                a = opacity,
                scaleX = max(scale0, scale2 * 0.45f).coerceIn(0.0001f, scale),
                scaleY = max(scale1, scale2 * 0.45f).coerceIn(0.0001f, scale),
                angle = angle,
                isScreenVisible = isScreenVisible,
                orderKey = sampleHash(i)
            )
        }

        return buildScene(splats, metadata.imageWidth, metadata.imageHeight, metadata.focalLengthPx)
    }

    private fun buildScene(
        splats: MutableList<Splat>,
        imageWidth: Int,
        imageHeight: Int,
        focalLengthPx: Float
    ): GaussianScene {
        require(splats.isNotEmpty()) { "PLY has no visible Gaussians" }
        var near = Float.POSITIVE_INFINITY
        var far = Float.NEGATIVE_INFINITY
        splats.forEach { splat ->
            near = min(near, splat.z)
            far = max(far, splat.z)
        }
        val anchor = computeParallaxAnchor(splats, near, far)
        val layerBucketScale = if (far > near) (DEPTH_LAYER_COUNT - 1).toFloat() / (far - near) else 1f
        fun layerBucket(depth: Float): Int {
            return (((far - depth) * layerBucketScale).toInt())
                .coerceIn(0, DEPTH_LAYER_COUNT - 1)
        }
        val bucketScale = if (far > near) DEPTH_SORT_BUCKETS / (far - near) else 1f
        splats.sortWith(
            compareBy<Splat> { layerBucket(it.z) }
                .thenByDescending { ((it.z - near) * bucketScale).toInt() }
                .thenBy { it.orderKey }
        )

        val positions = ByteBuffer
            .allocateDirect(splats.size * 3 * 4)
            .order(ByteOrder.nativeOrder())
            .asFloatBuffer()
        val colors = ByteBuffer
            .allocateDirect(splats.size * 4 * 4)
            .order(ByteOrder.nativeOrder())
            .asFloatBuffer()
        val scales = ByteBuffer
            .allocateDirect(splats.size * 3 * 4)
            .order(ByteOrder.nativeOrder())
            .asFloatBuffer()

        var colorWeight = 0f
        var backgroundR = 0f
        var backgroundG = 0f
        var backgroundB = 0f
        var screenVisibleCount = 0
        var activeLayerBucket = -1
        var activeLayer: LayerBuildState? = null
        val depthLayers = ArrayList<GaussianDepthLayer>(DEPTH_LAYER_COUNT)
        splats.forEach { splat ->
            val currentLayerBucket = layerBucket(splat.z)
            if (currentLayerBucket != activeLayerBucket) {
                activeLayer?.let { layer ->
                    depthLayers += GaussianDepthLayer(
                        start = layer.start,
                        count = layer.count,
                        depth = (layer.depthSum / layer.count.coerceAtLeast(1)).toFloat(),
                        depthBucket = activeLayerBucket
                    )
                }
                activeLayerBucket = currentLayerBucket
                activeLayer = LayerBuildState(start = positions.position() / 3)
            }
            activeLayer?.let { layer ->
                layer.count += 1
                layer.depthSum += splat.z.toDouble()
            }
            positions.put(splat.x)
            positions.put(splat.y)
            positions.put(splat.z)
            colors.put(splat.r)
            colors.put(splat.g)
            colors.put(splat.b)
            colors.put(splat.a)
            scales.put(splat.scaleX)
            scales.put(splat.scaleY)
            scales.put(splat.angle)
            backgroundR += splat.r * splat.a
            backgroundG += splat.g * splat.a
            backgroundB += splat.b * splat.a
            colorWeight += splat.a
            if (splat.isScreenVisible) screenVisibleCount++
        }
        activeLayer?.let { layer ->
            depthLayers += GaussianDepthLayer(
                start = layer.start,
                count = layer.count,
                depth = (layer.depthSum / layer.count.coerceAtLeast(1)).toFloat(),
                depthBucket = activeLayerBucket
            )
        }
        positions.position(0)
        colors.position(0)
        scales.position(0)

        val focus = splats[splats.size / 2].z
        return GaussianScene(
            count = splats.size,
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
            screenVisibleSplatCount = screenVisibleCount,
            auxiliarySplatCount = splats.size - screenVisibleCount,
            backgroundR = (backgroundR / colorWeight.coerceAtLeast(0.0001f)).coerceIn(0f, 1f),
            backgroundG = (backgroundG / colorWeight.coerceAtLeast(0.0001f)).coerceIn(0f, 1f),
            backgroundB = (backgroundB / colorWeight.coerceAtLeast(0.0001f)).coerceIn(0f, 1f)
        )
    }

    private fun computeParallaxAnchor(
        splats: List<Splat>,
        near: Float,
        far: Float
    ): AnchorDepth {
        val minSplatCount = max(
            ANCHOR_MIN_SPLATS,
            (splats.size * ANCHOR_MIN_SPLAT_FRACTION).toInt()
        ).coerceAtMost(splats.size.coerceAtLeast(1))
        if (far <= near || splats.size <= minSplatCount) {
            return AnchorDepth(far, splats.size, minSplatCount)
        }

        val counts = IntArray(ANCHOR_DEPTH_BUCKETS)
        val depthSums = DoubleArray(ANCHOR_DEPTH_BUCKETS)
        val bucketScale = (ANCHOR_DEPTH_BUCKETS - 1).toFloat() / (far - near)
        splats.forEach { splat ->
            val bucket = ((splat.z - near) * bucketScale)
                .toInt()
                .coerceIn(0, ANCHOR_DEPTH_BUCKETS - 1)
            counts[bucket] += 1
            depthSums[bucket] += splat.z.toDouble()
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

    private fun Map<String, PlyProperty>.getFloat(
        buffer: ByteBuffer,
        baseOffset: Int,
        name: String
    ): Float {
        val property = getValue(name)
        return readNumber(buffer, baseOffset + property.offset, property.type).toFloat()
    }

    private fun readImageSizeBinary(
        buffer: ByteBuffer,
        elements: List<PlyElement>
    ): Pair<Int, Int> {
        val element = elements.firstOrNull { it.name == "image_size" } ?: return 640 to 480
        val property = element.properties.firstOrNull() ?: return 640 to 480
        val width: Int
        val height: Int
        if (element.properties.size >= 2 && element.count == 1) {
            width = readNumber(buffer, element.dataOffset + element.properties[0].offset, element.properties[0].type).toInt()
            height = readNumber(buffer, element.dataOffset + element.properties[1].offset, element.properties[1].type).toInt()
        } else {
            width = readNumber(buffer, element.dataOffset, property.type).toInt()
            height = readNumber(buffer, element.dataOffset + element.stride, property.type).toInt()
        }
        return width.coerceAtLeast(1) to height.coerceAtLeast(1)
    }

    private fun readFocalLengthBinary(
        buffer: ByteBuffer,
        elements: List<PlyElement>
    ): Float {
        val element = elements.firstOrNull { it.name == "intrinsic" } ?: return 512f
        val property = element.properties.firstOrNull() ?: return 512f
        return readNumber(buffer, element.dataOffset, property.type).toFloat().coerceAtLeast(1f)
    }

    private fun readAsciiMetadata(
        header: HeaderParseResult,
        lines: Iterator<String>
    ): AsciiMetadata {
        var width = 640
        var height = 480
        var focal = 512f
        for (element in header.elements.dropWhile { it.name != "vertex" }.drop(1)) {
            repeat(element.count) {
                if (!lines.hasNext()) {
                    return AsciiMetadata(width.coerceAtLeast(1), height.coerceAtLeast(1), focal.coerceAtLeast(1f))
                }
                val parts = lines.next().trim().split(Regex("\\s+"))
                when (element.name) {
                    "image_size" -> {
                        if (parts.size >= 2) {
                            width = parts[0].toIntOrNull() ?: width
                            height = parts[1].toIntOrNull() ?: height
                        } else if (parts.isNotEmpty()) {
                            if (it == 0) width = parts[0].toIntOrNull() ?: width
                            if (it == 1) height = parts[0].toIntOrNull() ?: height
                        }
                    }
                    "intrinsic" -> {
                        if (it == 0 && parts.isNotEmpty()) {
                            focal = parts[0].toFloatOrNull() ?: focal
                        }
                    }
                }
            }
        }
        return AsciiMetadata(width.coerceAtLeast(1), height.coerceAtLeast(1), focal.coerceAtLeast(1f))
    }

    private fun readNumber(buffer: ByteBuffer, offset: Int, type: String): Double {
        return when (type) {
            "char", "int8" -> buffer.get(offset).toDouble()
            "uchar", "uint8" -> (buffer.get(offset).toInt() and 0xff).toDouble()
            "short", "int16" -> buffer.getShort(offset).toDouble()
            "ushort", "uint16" -> (buffer.getShort(offset).toInt() and 0xffff).toDouble()
            "int", "int32" -> buffer.getInt(offset).toDouble()
            "uint", "uint32" -> (buffer.getInt(offset).toLong() and 0xffffffffL).toDouble()
            "float", "float32" -> buffer.getFloat(offset).toDouble()
            "double", "float64" -> buffer.getDouble(offset)
            else -> throw IllegalArgumentException("Unsupported PLY property type: $type")
        }
    }

    private fun typeSize(type: String): Int {
        return when (type) {
            "char", "uchar", "int8", "uint8" -> 1
            "short", "ushort", "int16", "uint16" -> 2
            "int", "uint", "float", "int32", "uint32", "float32" -> 4
            "double", "float64" -> 8
            else -> throw IllegalArgumentException("Unsupported PLY property type: $type")
        }
    }

    private fun ByteArray.indexOf(marker: ByteArray): Int {
        outer@ for (i in 0..size - marker.size) {
            for (j in marker.indices) {
                if (this[i + j] != marker[j]) continue@outer
            }
            return i
        }
        return -1
    }

    private fun List<String>.floatAt(index: Int): Float {
        return getOrNull(index)?.toFloatOrNull() ?: 0f
    }

    private fun shToColor(value: Float): Float {
        return (value * SH_C0 + 0.5f).coerceIn(0f, 1f)
    }

    private fun sigmoid(value: Float): Float {
        return (1f / (1f + exp(-value))).coerceIn(0f, 1f)
    }

    private fun isProjectedIntoViewport(
        x: Float,
        y: Float,
        z: Float,
        imageWidth: Int,
        imageHeight: Int,
        focalLengthPx: Float,
        viewportAspect: Float?
    ): Boolean {
        if (!x.isFinite() || !y.isFinite() || !z.isFinite() || z <= 0.001f) return false
        val safeImageWidth = imageWidth.coerceAtLeast(1).toFloat()
        val safeImageHeight = imageHeight.coerceAtLeast(1).toFloat()
        val imageAspect = safeImageWidth / safeImageHeight
        val screenAspect = viewportAspect
            ?.takeIf { it.isFinite() && it > 0.01f }
            ?: imageAspect
        val fillX: Float
        val fillY: Float
        if (imageAspect > screenAspect) {
            fillX = imageAspect / screenAspect
            fillY = 1f
        } else {
            fillX = 1f
            fillY = screenAspect / imageAspect
        }
        val projectedX = (x / z) * (2f * focalLengthPx.coerceAtLeast(1f) / safeImageWidth) * fillX
        val projectedY = -(y / z) * (2f * focalLengthPx.coerceAtLeast(1f) / safeImageHeight) * fillY
        return projectedX >= -VIEWPORT_KEEP_MARGIN &&
            projectedX <= VIEWPORT_KEEP_MARGIN &&
            projectedY >= -VIEWPORT_KEEP_MARGIN &&
            projectedY <= VIEWPORT_KEEP_MARGIN
    }

    private fun shouldKeepAuxiliarySplat(index: Int, total: Int, maxSplats: Int): Boolean {
        if (total <= maxSplats) return true
        val sample = (sampleHash(index).toLong() and 0xffffffffL).toDouble() / 4294967296.0
        return sample < maxSplats.toDouble() / total.toDouble()
    }

    private fun sampleHash(index: Int): Int {
        var value = index.toLong() + HASH_SEED
        value = (value xor (value ushr 30)) * HASH_MUL_A
        value = (value xor (value ushr 27)) * HASH_MUL_B
        value = value xor (value ushr 31)
        return value.toInt()
    }

    private inline fun readQuaternionAngle(
        keys: Set<String>,
        read: (String) -> Float
    ): Float {
        if (!keys.containsAll(listOf("rot_0", "rot_1", "rot_2", "rot_3"))) return 0f
        val rawW = read("rot_0")
        val rawX = read("rot_1")
        val rawY = read("rot_2")
        val rawZ = read("rot_3")
        val norm = sqrt(rawW * rawW + rawX * rawX + rawY * rawY + rawZ * rawZ).coerceAtLeast(0.0001f)
        val w = rawW / norm
        val x = rawX / norm
        val y = rawY / norm
        val z = rawZ / norm
        return atan2(2f * (w * z + x * y), 1f - 2f * (y * y + z * z))
    }

    private const val DEPTH_SORT_BUCKETS = 4096f
    private const val DEPTH_LAYER_COUNT = 16
    private const val MIN_SPLAT_LIMIT = 10_000
    private const val MAX_SPLAT_LIMIT = 900_000
    private const val VIEWPORT_KEEP_MARGIN = 1.18f
    private const val ANCHOR_DEPTH_BUCKETS = 128
    private const val ANCHOR_LAYER_WINDOW_BUCKETS = 4
    private const val ANCHOR_MIN_SPLATS = 768
    private const val ANCHOR_MIN_SPLAT_FRACTION = 0.012f
    private const val HASH_SEED = -7046029254386353131L
    private const val HASH_MUL_A = -4658895280553007687L
    private const val HASH_MUL_B = -7723592293110705685L
}
