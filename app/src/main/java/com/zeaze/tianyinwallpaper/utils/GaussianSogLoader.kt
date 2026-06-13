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
import java.nio.FloatBuffer
import java.nio.IntBuffer
import java.nio.charset.StandardCharsets
import java.util.Arrays
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipInputStream
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
    private val backgroundColorCache = ConcurrentHashMap<String, SogBackgroundColor>()

    data class SogBackgroundColor(
        val red: Float,
        val green: Float,
        val blue: Float
    )

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

    private data class MortonBounds(
        val minX: Float,
        val minY: Float,
        val minZ: Float,
        val maxX: Float,
        val maxY: Float,
        val maxZ: Float
    )

    private data class SogSelectionStats(
        val visible: Int,
        val auxiliary: Int,
        val foregroundVisible: Int,
        val foregroundDepthLimit: Float
    ) {
        val otherVisible: Int = (visible - foregroundVisible).coerceAtLeast(0)
    }

    private data class SogSelectionBudgets(
        val foregroundVisible: Int,
        val otherVisible: Int,
        val auxiliary: Int
    )

    private data class LodChunkBounds(
        val minX: Float,
        val minY: Float,
        val minZ: Float,
        val maxX: Float,
        val maxY: Float,
        val maxZ: Float
    )

    private data class LodChunkSource(
        val filename: String,
        val offset: Int,
        val count: Int,
        val bounds: LodChunkBounds?
    )

    private data class LodStageSource(
        val lodLevel: Int,
        val chunks: List<LodChunkSource>
    )

    private data class GpuChunkRequest(
        val offset: Int,
        val count: Int,
        val maxSplats: Int,
        val bounds: LodChunkBounds?
    )

    private data class BundledZipIndex(
        val entries: List<ZipEntry>,
        val byName: Map<String, ZipEntry>
    )

    data class SogGpuScene(
        val count: Int,
        val meansL: IntBuffer,
        val meansU: IntBuffer,
        val scales: IntBuffer,
        val sh0: IntBuffer,
        val quats: IntBuffer,
        val scaleCodebook: FloatBuffer,
        val sh0Codebook: FloatBuffer,
        val meansMinMax: FloatBuffer,
        val imageWidth: Int,
        val imageHeight: Int,
        val focusDepth: Float,
        val parallaxAnchorDepth: Float,
        val backgroundR: Float,
        val backgroundG: Float,
        val backgroundB: Float,
        val sceneCenterX: Float,
        val sceneCenterY: Float,
        val sceneCenterZ: Float,
        val sceneRadius: Float,
        val defaultCameraDistance: Float,
        val screenVisibleSplatCount: Int,
        val auxiliarySplatCount: Int,
        val chunkCenterX: Float,
        val chunkCenterY: Float,
        val chunkCenterZ: Float,
        val chunkRadius: Float
    )

    data class SogGpuStage(
        val chunks: List<SogGpuScene>,
        val stageIndex: Int,
        val stageCount: Int,
        val lodLevel: Int,
        val sourceNames: List<String>
    ) {
        val count: Int = chunks.sumOf { it.count }
        val screenVisibleSplatCount: Int = chunks.sumOf { it.screenVisibleSplatCount }
        val auxiliarySplatCount: Int = chunks.sumOf { it.auxiliarySplatCount }
    }

    private class SogGpuAccumulator(capacity: Int) {
        val sourceIndex = IntArray(capacity)
        val x = FloatArray(capacity)
        val y = FloatArray(capacity)
        val z = FloatArray(capacity)
        val orderKey = IntArray(capacity)
        var count = 0
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
        var backgroundR = 0f
            private set
        var backgroundG = 0f
            private set
        var backgroundB = 0f
            private set
        var colorWeight = 0f
            private set
        var screenVisibleCount = 0
            private set

        fun add(
            source: Int,
            px: Float,
            py: Float,
            pz: Float,
            cr: Float,
            cg: Float,
            cb: Float,
            ca: Float,
            isVisible: Boolean,
            key: Int
        ) {
            if (count >= sourceIndex.size) return
            val index = count++
            sourceIndex[index] = source
            x[index] = px
            y[index] = py
            z[index] = pz
            orderKey[index] = key
            minX = min(minX, px)
            minY = min(minY, py)
            minZ = min(minZ, pz)
            maxX = max(maxX, px)
            maxY = max(maxY, py)
            maxZ = max(maxZ, pz)
            backgroundR += cr * ca
            backgroundG += cg * ca
            backgroundB += cb * ca
            colorWeight += ca
            if (isVisible) screenVisibleCount += 1
        }
    }

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
            val index = when {
                count < x.size -> count++
                isVisible && screenVisibleCount < x.size -> x.size - 1
                else -> return
            }
            writeAt(
                index = index,
                px = px,
                py = py,
                pz = pz,
                cr = cr,
                cg = cg,
                cb = cb,
                ca = ca,
                sx = sx,
                sy = sy,
                sz = sz,
                qx = qx,
                qy = qy,
                qz = qz,
                qw = qw,
                isVisible = isVisible,
                key = key
            )
            if (isVisible) {
                val visibleIndex = screenVisibleCount
                if (index != visibleIndex) swap(index, visibleIndex)
                screenVisibleCount += 1
            }
        }

        private fun writeAt(
            index: Int,
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
        }

        private fun swap(a: Int, b: Int) {
            swap(x, a, b)
            swap(y, a, b)
            swap(z, a, b)
            swap(r, a, b)
            swap(g, a, b)
            swap(this.b, a, b)
            swap(this.a, a, b)
            swap(scaleX, a, b)
            swap(scaleY, a, b)
            swap(scaleZ, a, b)
            swap(quatX, a, b)
            swap(quatY, a, b)
            swap(quatZ, a, b)
            swap(quatW, a, b)
            val visibleValue = visible[a]
            visible[a] = visible[b]
            visible[b] = visibleValue
            val keyValue = orderKey[a]
            orderKey[a] = orderKey[b]
            orderKey[b] = keyValue
        }

        private fun swap(array: FloatArray, a: Int, b: Int) {
            val value = array[a]
            array[a] = array[b]
            array[b] = value
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

    fun loadGpuSceneOrThrow(
        context: Context,
        uriString: String,
        maxSplats: Int = DEFAULT_MAX_SPLATS,
        viewportAspect: Float? = null
    ): SogGpuScene {
        if (uriString.isBlank()) error("Empty SOG uri")
        val files = context.contentResolver.openInputStream(Uri.parse(uriString))?.use { input ->
            readBundledFiles(context, input)
        } ?: error("Cannot open SOG uri: $uriString")
        return parseGpuScene(files, maxSplats, viewportAspect)
    }

    fun loadBackgroundColorOrThrow(context: Context, uriString: String): SogBackgroundColor {
        backgroundColorCache[uriString]?.let { return it }
        if (uriString.isBlank()) error("Empty SOG uri")
        val color = context.contentResolver.openInputStream(Uri.parse(uriString))?.use { input ->
            loadBackgroundColorFromInput(context, input, 0)
        } ?: error("Cannot open SOG uri: $uriString")
        backgroundColorCache[uriString] = color
        return color
    }

    private fun loadBackgroundColorFromInput(
        context: Context,
        input: InputStream,
        depth: Int
    ): SogBackgroundColor {
        require(depth <= 2) { "SOG nesting is too deep" }
        return withBundledZip(context, input) { zip, zipIndex ->
            val metaEntry = zipIndex.findEntry("meta.json", allowBaseName = true)
            if (metaEntry != null) {
                return@withBundledZip parseBackgroundColor(readBundledFiles(zip, zipIndex))
            }
            val nested = zipIndex.entries.firstOrNull { entry ->
                entryBaseName(entry.name).lowercase(Locale.US).endsWith(".sog")
            } ?: error("SOG background source has no meta.json or nested SOG")
            val bytes = zip.getInputStream(nested).use { it.readBytes() }
            loadBackgroundColorFromInput(context, ByteArrayInputStream(bytes), depth + 1)
        }
    }

    private fun parseBackgroundColor(files: Map<String, ByteArray>): SogBackgroundColor {
        val metaBytes = files.findEntry("meta.json") ?: error("SOG missing meta.json")
        val meta = JSON.parseObject(String(metaBytes, StandardCharsets.UTF_8))
        require(meta.getIntValue("version") == 2) { "Unsupported SOG version" }
        val count = meta.getIntValue("count")
        require(count > 0) { "SOG has no splats" }

        val meansMeta = meta.getJSONObject("means") ?: error("SOG missing means metadata")
        val meansFiles = meansMeta.requiredFiles("means", 2)
        val meansL = files.decodeImage(meansFiles[0])
        val meansU = files.decodeImage(meansFiles[1])
        requireSameImageShape("means", meansL, meansU)
        val mins = meansMeta.requiredFloatArray("means.mins", "mins", 3)
        val maxs = meansMeta.requiredFloatArray("means.maxs", "maxs", 3)
        val flipZ = shouldFlipDepth(count, meansL, meansU, mins, maxs)

        val sh0Meta = meta.getJSONObject("sh0") ?: error("SOG missing sh0 metadata")
        val sh0Image = files.decodeImage(sh0Meta.requiredFiles("sh0", 1)[0])
        val sh0Codebook = sh0Meta.requiredCodebook("sh0")
        require(count <= min(min(meansL.count, meansU.count), sh0Image.count)) {
            "SOG count exceeds background source image size"
        }

        var red = 0f
        var green = 0f
        var blue = 0f
        var weight = 0f
        repeat(count) { index ->
            val z = decodePosition(index, 2, meansL, meansU, mins, maxs)
                .let { if (flipZ) -it else it }
            val alpha = sh0Image.channel(index, 3) / 255f
            if (!z.isFinite() || z <= 0.001f || alpha < 0.015f) return@repeat
            red += shToColor(sh0Codebook[sh0Image.channel(index, 0)]) * alpha
            green += shToColor(sh0Codebook[sh0Image.channel(index, 1)]) * alpha
            blue += shToColor(sh0Codebook[sh0Image.channel(index, 2)]) * alpha
            weight += alpha
        }
        val safeWeight = weight.coerceAtLeast(0.0001f)
        return SogBackgroundColor(
            red = (red / safeWeight).coerceIn(0f, 1f),
            green = (green / safeWeight).coerceIn(0f, 1f),
            blue = (blue / safeWeight).coerceIn(0f, 1f)
        )
    }

    fun loadGpuSceneStagesOrThrow(
        context: Context,
        uriString: String,
        maxSplats: Int = DEFAULT_MAX_SPLATS,
        viewportAspect: Float? = null,
        onStage: (SogGpuStage) -> Boolean
    ): Int {
        if (uriString.isBlank()) error("Empty SOG uri")
        return context.contentResolver.openInputStream(Uri.parse(uriString))?.use { input ->
            withBundledZip(context, input) { zip, zipIndex ->
                val lodMetaEntry = zipIndex.findEntry("lod-meta.json", allowBaseName = true)
                if (lodMetaEntry == null) {
                    val files = readBundledFiles(zip, zipIndex)
                    val metaBytes = files.findEntry("meta.json")
                        ?: error("SOG missing meta.json; entries=${files.keys.distinct().sorted().take(12).joinToString()}")
                    val sourceCount = JSON.parseObject(String(metaBytes, StandardCharsets.UTF_8)).getIntValue("count")
                    val requests = buildUniformGpuChunkRequests(sourceCount, maxSplats)
                    val chunks = parseGpuScenes(
                        files = files,
                        requests = requests,
                        viewportAspect = viewportAspect,
                        minimumSplatLimit = 1
                    )
                    val stage = SogGpuStage(
                        chunks = chunks,
                        stageIndex = 0,
                        stageCount = 1,
                        lodLevel = 0,
                        sourceNames = List(chunks.size) { "meta.json" }
                    )
                    return@withBundledZip if (onStage(stage)) 1 else 0
                }

                val lodMeta = zip.getInputStream(lodMetaEntry).use { stream ->
                    JSON.parseObject(String(stream.readBytes(), StandardCharsets.UTF_8))
                }
                val lodRoot = normalizeEntryName(lodMetaEntry.name)
                    .substringBeforeLast('/', missingDelimiterValue = "")
                val lodStages = resolveLodStageSources(lodMeta) { filename ->
                    zipIndex.resolveLodEntry(lodRoot, filename)
                }
                require(lodStages.isNotEmpty()) { "LOD SOG has no complete stages" }

                var delivered = 0
                lodStages.forEachIndexed { index, source ->
                    val sourceCounts = source.chunks.map { chunk ->
                        if (chunk.count > 0) chunk.count else readSogSourceCount(zip, zipIndex, chunk.filename)
                    }
                    val chunkBudgets = allocateChunkBudgets(sourceCounts, maxSplats)
                    val parsedChunks = arrayOfNulls<SogGpuScene>(source.chunks.size)
                    source.chunks.withIndex().groupBy { it.value.filename }.forEach { (filename, indexedChunks) ->
                        val chunkFiles = readSogChunkFiles(zip, zipIndex, filename)
                        val requests = indexedChunks.map { indexed ->
                            val sourceCount = if (indexed.value.count > 0) indexed.value.count else sourceCounts[indexed.index]
                            GpuChunkRequest(
                                offset = indexed.value.offset,
                                count = sourceCount,
                                maxSplats = chunkBudgets[indexed.index],
                                bounds = indexed.value.bounds
                            )
                        }
                        val scenes = parseGpuScenes(chunkFiles, requests, viewportAspect, minimumSplatLimit = 1)
                        indexedChunks.forEachIndexed { requestIndex, indexed ->
                            parsedChunks[indexed.index] = scenes[requestIndex]
                        }
                    }
                    val chunks = parsedChunks.map { it ?: error("LOD SOG chunk parse incomplete") }
                    val stage = SogGpuStage(
                        chunks = chunks,
                        stageIndex = index,
                        stageCount = lodStages.size,
                        lodLevel = source.lodLevel,
                        sourceNames = source.chunks.map { it.filename }
                    )
                    if (!onStage(stage)) return@withBundledZip delivered
                    delivered += 1
                }
                delivered
            }
        } ?: error("Cannot open SOG uri: $uriString")
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
        val mortonBounds = createMortonBounds(mins, maxs, flipZ)
        val selectionStats = if (count > splatLimit) {
            countSelectableSplats(
                count = count,
                meansL = meansL,
                meansU = meansU,
                mins = mins,
                maxs = maxs,
                flipZ = flipZ,
                sh0Image = sh0Image,
                viewportAspect = viewportAspect
            )
        } else {
            null
        }

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
                mortonBounds = mortonBounds,
                selectionStats = selectionStats,
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

    private fun parseGpuScene(
        files: Map<String, ByteArray>,
        maxSplats: Int = DEFAULT_MAX_SPLATS,
        viewportAspect: Float? = null,
        minimumSplatLimit: Int = MIN_SPLAT_LIMIT
    ): SogGpuScene {
        return parseGpuScenes(
            files = files,
            requests = listOf(
                GpuChunkRequest(offset = 0, count = Int.MAX_VALUE, maxSplats = maxSplats, bounds = null)
            ),
            viewportAspect = viewportAspect,
            minimumSplatLimit = minimumSplatLimit
        ).single()
    }

    private fun parseGpuScenes(
        files: Map<String, ByteArray>,
        requests: List<GpuChunkRequest>,
        viewportAspect: Float?,
        minimumSplatLimit: Int
    ): List<SogGpuScene> {
        require(requests.isNotEmpty()) { "SOG has no chunk requests" }
        val metaBytes = files.findEntry("meta.json")
            ?: error("SOG missing meta.json; entries=${files.keys.distinct().sorted().take(12).joinToString()}")
        val meta = JSON.parseObject(String(metaBytes, StandardCharsets.UTF_8))
        require(meta.getIntValue("version") == 2) { "Unsupported SOG version: ${meta.getIntValue("version")}" }
        val count = meta.getIntValue("count")
        require(count > 0) { "SOG has no splats" }

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
        val mortonBounds = createMortonBounds(mins, maxs, flipZ)
        return requests.map { request ->
            require(request.offset in 0 until count) {
                "SOG chunk offset out of range: offset=${request.offset} sourceCount=$count"
            }
            val sourceOffset = request.offset
            val requestedCount = if (request.count == Int.MAX_VALUE) count - sourceOffset else request.count
            require(requestedCount > 0 && requestedCount <= count - sourceOffset) {
                "SOG chunk range out of bounds: offset=$sourceOffset count=$requestedCount sourceCount=$count"
            }
            val sourceCount = requestedCount
            val minimumLimit = minimumSplatLimit.coerceIn(1, sourceCount)
            val splatLimit = request.maxSplats
                .coerceIn(minimumLimit, MAX_SPLAT_LIMIT)
                .coerceAtMost(sourceCount)
            val selectionStats = if (sourceCount > splatLimit) {
                countSelectableSplats(
                    offset = sourceOffset,
                    count = sourceCount,
                    meansL = meansL,
                    meansU = meansU,
                    mins = mins,
                    maxs = maxs,
                    flipZ = flipZ,
                    sh0Image = sh0Image,
                    viewportAspect = viewportAspect
                )
            } else {
                null
            }
            val splats = SogGpuAccumulator(splatLimit)
            val selectionSeen = IntArray(3)
            repeat(sourceCount) { localIndex ->
                val sourceIndex = sourceOffset + localIndex
                addGpuSplat(
                    index = sourceIndex,
                    splatLimit = splatLimit,
                    meansL = meansL,
                    meansU = meansU,
                    mins = mins,
                    maxs = maxs,
                    flipZ = flipZ,
                    sh0Image = sh0Image,
                    sh0Codebook = sh0Codebook,
                    viewportAspect = viewportAspect,
                    mortonBounds = mortonBounds,
                    selectionStats = selectionStats,
                    selectionSeen = selectionSeen,
                    splats = splats
                )
            }
            val scene = buildGpuScene(
                splats = splats,
                meansL = meansL,
                meansU = meansU,
                scalesImage = scalesImage,
                sh0Image = sh0Image,
                quatsImage = quatsImage,
                scaleCodebook = scaleCodebook,
                sh0Codebook = sh0Codebook,
                meansMins = mins,
                meansMaxs = maxs,
                flipZ = flipZ,
                imageWidth = DEFAULT_IMAGE_WIDTH,
                imageHeight = DEFAULT_IMAGE_HEIGHT,
                focalLengthPx = DEFAULT_FOCAL_LENGTH,
                sourceLabel = "SOG-GPU"
            )
            request.bounds?.let { bounds -> scene.withChunkBounds(bounds, flipZ) } ?: scene
        }
    }

    private fun SogGpuScene.withChunkBounds(bounds: LodChunkBounds, flipZ: Boolean): SogGpuScene {
        val z0 = if (flipZ) -bounds.minZ else bounds.minZ
        val z1 = if (flipZ) -bounds.maxZ else bounds.maxZ
        val minX = min(bounds.minX, bounds.maxX)
        val minY = min(bounds.minY, bounds.maxY)
        val minZ = min(z0, z1)
        val maxX = max(bounds.minX, bounds.maxX)
        val maxY = max(bounds.minY, bounds.maxY)
        val maxZ = max(z0, z1)
        val centerX = (minX + maxX) * 0.5f
        val centerY = (minY + maxY) * 0.5f
        val centerZ = (minZ + maxZ) * 0.5f
        val halfX = (maxX - minX) * 0.5f
        val halfY = (maxY - minY) * 0.5f
        val halfZ = (maxZ - minZ) * 0.5f
        val radius = sqrt(halfX * halfX + halfY * halfY + halfZ * halfZ).coerceAtLeast(0.001f)
        return copy(
            chunkCenterX = centerX,
            chunkCenterY = centerY,
            chunkCenterZ = centerZ,
            chunkRadius = radius
        )
    }

    private fun addGpuSplat(
        index: Int,
        splatLimit: Int,
        meansL: ImageData,
        meansU: ImageData,
        mins: FloatArray,
        maxs: FloatArray,
        flipZ: Boolean,
        sh0Image: ImageData,
        sh0Codebook: FloatArray,
        viewportAspect: Float?,
        mortonBounds: MortonBounds,
        selectionStats: SogSelectionStats?,
        selectionSeen: IntArray,
        splats: SogGpuAccumulator
    ) {
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
        selectionStats?.let { stats ->
            val budgets = stats.budgets(splatLimit)
            val foregroundVisible = isForegroundVisible(visible, z, stats)
            val keep = if (foregroundVisible) {
                shouldKeepRankForBudget(selectionSeen[0]++, stats.foregroundVisible, budgets.foregroundVisible)
            } else if (visible) {
                shouldKeepRankForBudget(selectionSeen[1]++, stats.otherVisible, budgets.otherVisible)
            } else {
                shouldKeepRankForBudget(selectionSeen[2]++, stats.auxiliary, budgets.auxiliary)
            }
            if (!keep) return
        }
        splats.add(
            source = index,
            px = x,
            py = y,
            pz = z,
            cr = shToColor(sh0Codebook[sh0Image.channel(index, 0)]),
            cg = shToColor(sh0Codebook[sh0Image.channel(index, 1)]),
            cb = shToColor(sh0Codebook[sh0Image.channel(index, 2)]),
            ca = opacity.coerceIn(0f, 1f),
            isVisible = visible,
            key = mortonKey(x, y, z, mortonBounds)
        )
    }

    private fun buildGpuScene(
        splats: SogGpuAccumulator,
        meansL: ImageData,
        meansU: ImageData,
        scalesImage: ImageData,
        sh0Image: ImageData,
        quatsImage: ImageData?,
        scaleCodebook: FloatArray,
        sh0Codebook: FloatArray,
        meansMins: FloatArray,
        meansMaxs: FloatArray,
        flipZ: Boolean,
        imageWidth: Int,
        imageHeight: Int,
        focalLengthPx: Float,
        sourceLabel: String
    ): SogGpuScene {
        val count = splats.count
        require(count > 0) { "$sourceLabel has no visible Gaussians" }
        val near = splats.minZ
        val far = splats.maxZ
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
        val order = buildSortedOrder(count) { index ->
            packSortKey(sortBucket(index), splats.orderKey[index], index)
        }
        val meansLBuffer = allocateIntBuffer(count)
        val meansUBuffer = allocateIntBuffer(count)
        val scalesBuffer = allocateIntBuffer(count)
        val sh0Buffer = allocateIntBuffer(count)
        val quatsBuffer = allocateIntBuffer(count)
        for (orderedIndex in 0 until count) {
            val index = splats.sourceIndex[order[orderedIndex]]
            meansLBuffer.put(meansL.packedRgba(index))
            meansUBuffer.put(meansU.packedRgba(index))
            scalesBuffer.put(scalesImage.packedRgba(index))
            sh0Buffer.put(sh0Image.packedRgba(index))
            quatsBuffer.put(quatsImage?.packedRgba(index) ?: DEFAULT_QUAT_PACKED)
        }
        meansLBuffer.position(0)
        meansUBuffer.position(0)
        scalesBuffer.position(0)
        sh0Buffer.position(0)
        quatsBuffer.position(0)
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
            "built SOG GPU scene count=$count visible=${splats.screenVisibleCount} aux=${count - splats.screenVisibleCount} " +
                "near=$near far=$far radius=${bounds.radius}"
        )
        return SogGpuScene(
            count = count,
            meansL = meansLBuffer,
            meansU = meansUBuffer,
            scales = scalesBuffer,
            sh0 = sh0Buffer,
            quats = quatsBuffer,
            scaleCodebook = allocateFloatBuffer(scaleCodebook),
            sh0Codebook = allocateFloatBuffer(sh0Codebook),
            meansMinMax = allocateFloatBuffer(
                floatArrayOf(
                    meansMins[0],
                    meansMins[1],
                    meansMins[2],
                    if (flipZ) 1f else 0f,
                    meansMaxs[0],
                    meansMaxs[1],
                    meansMaxs[2],
                    0f
                )
            ),
            imageWidth = imageWidth.coerceAtLeast(1),
            imageHeight = imageHeight.coerceAtLeast(1),
            focusDepth = focus,
            parallaxAnchorDepth = anchor.depth,
            backgroundR = (splats.backgroundR / colorWeight).coerceIn(0f, 1f),
            backgroundG = (splats.backgroundG / colorWeight).coerceIn(0f, 1f),
            backgroundB = (splats.backgroundB / colorWeight).coerceIn(0f, 1f),
            sceneCenterX = WEBVIEW_CAMERA_TARGET_X,
            sceneCenterY = WEBVIEW_CAMERA_TARGET_Y,
            sceneCenterZ = WEBVIEW_CAMERA_TARGET_Z,
            sceneRadius = bounds.radius,
            defaultCameraDistance = defaultCameraDistance,
            screenVisibleSplatCount = splats.screenVisibleCount,
            auxiliarySplatCount = count - splats.screenVisibleCount,
            chunkCenterX = bounds.centerX,
            chunkCenterY = bounds.centerY,
            chunkCenterZ = bounds.centerZ,
            chunkRadius = bounds.radius
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
        mortonBounds: MortonBounds,
        selectionStats: SogSelectionStats?,
        splats: SogSplatAccumulator
    ) {
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
        selectionStats?.let { stats ->
            val budgets = stats.budgets(splatLimit)
            val foregroundVisible = isForegroundVisible(visible, z, stats)
            val keep = if (foregroundVisible) {
                shouldKeepForBudget(index, stats.foregroundVisible, budgets.foregroundVisible)
            } else if (visible) {
                shouldKeepForBudget(index, stats.otherVisible, budgets.otherVisible)
            } else {
                shouldKeepForBudget(index, stats.auxiliary, budgets.auxiliary)
            }
            if (!keep) return
        }
        val sx = decodeScale(scaleCodebook[scalesImage.channel(index, 0)])
        val sy = decodeScale(scaleCodebook[scalesImage.channel(index, 1)])
        val sz = decodeScale(scaleCodebook[scalesImage.channel(index, 2)])
        val quat = decodeQuat(index, quatsImage) ?: Quat(0f, 0f, 0f, 1f)
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
            key = mortonKey(x, y, z, mortonBounds)
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

    private fun countSelectableSplats(
        offset: Int = 0,
        count: Int,
        meansL: ImageData,
        meansU: ImageData,
        mins: FloatArray,
        maxs: FloatArray,
        flipZ: Boolean,
        sh0Image: ImageData,
        viewportAspect: Float?
    ): SogSelectionStats {
        var visible = 0
        var auxiliary = 0
        var visibleNear = Float.POSITIVE_INFINITY
        var visibleFar = Float.NEGATIVE_INFINITY
        repeat(count) { localIndex ->
            val index = offset + localIndex
            val x = decodePosition(index, 0, meansL, meansU, mins, maxs)
            val y = decodePosition(index, 1, meansL, meansU, mins, maxs)
            val z = decodePosition(index, 2, meansL, meansU, mins, maxs)
                .let { if (flipZ) -it else it }
            val opacity = sh0Image.channel(index, 3) / 255f
            if (!z.isFinite() || z <= 0.001f || opacity < 0.015f) return@repeat
            if (
                GaussianPlyLoader.isProjectedIntoViewport(
                    x = x,
                    y = y,
                    z = z,
                    imageWidth = DEFAULT_IMAGE_WIDTH,
                    imageHeight = DEFAULT_IMAGE_HEIGHT,
                    focalLengthPx = DEFAULT_FOCAL_LENGTH,
                    viewportAspect = viewportAspect
                )
            ) {
                visible += 1
                visibleNear = min(visibleNear, z)
                visibleFar = max(visibleFar, z)
            } else {
                auxiliary += 1
            }
        }
        if (visible <= 0) {
            return SogSelectionStats(
                visible = 0,
                auxiliary = auxiliary,
                foregroundVisible = 0,
                foregroundDepthLimit = Float.POSITIVE_INFINITY
            )
        }
        val visibleRange = visibleFar - visibleNear
        if (!visibleRange.isFinite() || visibleRange <= 0.000001f) {
            return SogSelectionStats(
                visible = visible,
                auxiliary = auxiliary,
                foregroundVisible = visible,
                foregroundDepthLimit = visibleFar
            )
        }

        val buckets = IntArray(SOG_FOREGROUND_DEPTH_BUCKETS)
        repeat(count) { localIndex ->
            val index = offset + localIndex
            val x = decodePosition(index, 0, meansL, meansU, mins, maxs)
            val y = decodePosition(index, 1, meansL, meansU, mins, maxs)
            val z = decodePosition(index, 2, meansL, meansU, mins, maxs)
                .let { if (flipZ) -it else it }
            val opacity = sh0Image.channel(index, 3) / 255f
            if (!z.isFinite() || z <= 0.001f || opacity < 0.015f) return@repeat
            val isVisible = GaussianPlyLoader.isProjectedIntoViewport(
                x = x,
                y = y,
                z = z,
                imageWidth = DEFAULT_IMAGE_WIDTH,
                imageHeight = DEFAULT_IMAGE_HEIGHT,
                focalLengthPx = DEFAULT_FOCAL_LENGTH,
                viewportAspect = viewportAspect
            )
            if (!isVisible) return@repeat
            val bucket = (((z - visibleNear) / visibleRange).coerceIn(0f, 1f) *
                (SOG_FOREGROUND_DEPTH_BUCKETS - 1)).toInt()
            buckets[bucket] += 1
        }

        val targetForeground = (visible * SOG_FOREGROUND_DEPTH_FRACTION)
            .toInt()
            .coerceIn(1, visible)
        var foregroundVisible = 0
        var foregroundBucket = 0
        for (bucket in buckets.indices) {
            foregroundVisible += buckets[bucket]
            foregroundBucket = bucket
            if (foregroundVisible >= targetForeground) break
        }
        val foregroundDepthLimit = visibleNear +
            visibleRange * ((foregroundBucket + 1).toFloat() / SOG_FOREGROUND_DEPTH_BUCKETS.toFloat())
        return SogSelectionStats(
            visible = visible,
            auxiliary = auxiliary,
            foregroundVisible = foregroundVisible.coerceIn(1, visible),
            foregroundDepthLimit = foregroundDepthLimit
        )
    }

    private fun SogSelectionStats.budgets(splatLimit: Int): SogSelectionBudgets {
        val safeLimit = splatLimit.coerceAtLeast(0)
        val safeForeground = foregroundVisible.coerceIn(0, visible)
        val safeOtherVisible = (visible - safeForeground).coerceAtLeast(0)
        if (visible <= safeLimit) {
            val auxiliaryBudget = (safeLimit - visible).coerceAtLeast(0).coerceAtMost(auxiliary)
            return SogSelectionBudgets(
                foregroundVisible = safeForeground,
                otherVisible = safeOtherVisible,
                auxiliary = auxiliaryBudget
            )
        }

        val foregroundCap = if (safeForeground > 0 && safeLimit > 0) {
            max(1, (safeLimit * SOG_FOREGROUND_BUDGET_FRACTION).toInt())
                .coerceAtMost(safeLimit)
        } else {
            0
        }
        val foregroundBudget = min(safeForeground, foregroundCap)
        val otherVisibleBudget = min(
            safeOtherVisible,
            (safeLimit - foregroundBudget).coerceAtLeast(0)
        )
        val auxiliaryBudget = min(
            auxiliary,
            (safeLimit - foregroundBudget - otherVisibleBudget).coerceAtLeast(0)
        )
        return SogSelectionBudgets(
            foregroundVisible = foregroundBudget,
            otherVisible = otherVisibleBudget,
            auxiliary = auxiliaryBudget
        )
    }

    private fun isForegroundVisible(visible: Boolean, z: Float, stats: SogSelectionStats): Boolean {
        return visible &&
            stats.foregroundVisible > 0 &&
            stats.foregroundDepthLimit.isFinite() &&
            z <= stats.foregroundDepthLimit
    }

    private fun shouldKeepForBudget(index: Int, total: Int, budget: Int): Boolean {
        if (budget <= 0 || total <= 0) return false
        if (total <= budget) return true
        val sample = (GaussianPlyLoader.sampleHash(index).toLong() and 0xffffffffL).toDouble() / 4294967296.0
        return sample < budget.toDouble() / total.toDouble()
    }

    private fun shouldKeepRankForBudget(rank: Int, total: Int, budget: Int): Boolean {
        if (budget <= 0 || total <= 0) return false
        if (total <= budget) return true
        val before = rank.toLong() * budget.toLong() / total.toLong()
        val after = (rank.toLong() + 1L) * budget.toLong() / total.toLong()
        return after > before
    }

    private fun createMortonBounds(
        mins: FloatArray,
        maxs: FloatArray,
        flipZ: Boolean
    ): MortonBounds {
        val x0 = symmetricUnlog(mins[0])
        val x1 = symmetricUnlog(maxs[0])
        val y0 = symmetricUnlog(mins[1])
        val y1 = symmetricUnlog(maxs[1])
        val z0 = symmetricUnlog(mins[2]).let { if (flipZ) -it else it }
        val z1 = symmetricUnlog(maxs[2]).let { if (flipZ) -it else it }
        return MortonBounds(
            minX = min(x0, x1),
            minY = min(y0, y1),
            minZ = min(z0, z1),
            maxX = max(x0, x1),
            maxY = max(y0, y1),
            maxZ = max(z0, z1)
        )
    }

    private fun mortonKey(
        x: Float,
        y: Float,
        z: Float,
        bounds: MortonBounds
    ): Int {
        val mx = quantizeMorton(x, bounds.minX, bounds.maxX)
        val my = quantizeMorton(y, bounds.minY, bounds.maxY)
        val mz = quantizeMorton(z, bounds.minZ, bounds.maxZ)
        return (expandBits8(mx) shl 2) or (expandBits8(my) shl 1) or expandBits8(mz)
    }

    private fun quantizeMorton(value: Float, minValue: Float, maxValue: Float): Int {
        val range = maxValue - minValue
        if (!value.isFinite() || !range.isFinite() || range <= 0.000001f) return 0
        return (((value - minValue) / range).coerceIn(0f, 1f) * MORTON_AXIS_MAX + 0.5f)
            .toInt()
            .coerceIn(0, MORTON_AXIS_MAX)
    }

    private fun expandBits8(value: Int): Int {
        var x = value and MORTON_AXIS_MAX
        x = (x or (x shl 8)) and 0x00F00F
        x = (x or (x shl 4)) and 0x0C30C3
        x = (x or (x shl 2)) and 0x249249
        return x
    }

    private inline fun buildSortedOrder(count: Int, keyFor: (Int) -> Long): IntArray {
        val keys = LongArray(count) { index -> keyFor(index) }
        Arrays.sort(keys)
        return IntArray(count) { index ->
            ((keys[index] xor Long.MIN_VALUE) and SORT_INDEX_MASK).toInt()
        }
    }

    private fun packSortKey(bucket: Int, mortonKey: Int, index: Int): Long {
        val raw = ((bucket.toLong() and SORT_BUCKET_MASK) shl SORT_BUCKET_SHIFT) or
            ((mortonKey.toLong() and SORT_MORTON_MASK) shl SORT_MORTON_SHIFT) or
            (index.toLong() and SORT_INDEX_MASK)
        return raw xor Long.MIN_VALUE
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
        var near = Float.POSITIVE_INFINITY
        var far = Float.NEGATIVE_INFINITY
        var minX = Float.POSITIVE_INFINITY
        var minY = Float.POSITIVE_INFINITY
        var minZ = Float.POSITIVE_INFINITY
        var maxX = Float.NEGATIVE_INFINITY
        var maxY = Float.NEGATIVE_INFINITY
        var maxZ = Float.NEGATIVE_INFINITY
        var colorWeight = 0f
        var backgroundR = 0f
        var backgroundG = 0f
        var backgroundB = 0f
        for (index in 0 until count) {
            val x = splats.x[index]
            val y = splats.y[index]
            val z = splats.z[index]
            minX = min(minX, x)
            minY = min(minY, y)
            minZ = min(minZ, z)
            maxX = max(maxX, x)
            maxY = max(maxY, y)
            maxZ = max(maxZ, z)
            near = min(near, z)
            far = max(far, z)
            val alpha = splats.a[index]
            backgroundR += splats.r[index] * alpha
            backgroundG += splats.g[index] * alpha
            backgroundB += splats.b[index] * alpha
            colorWeight += alpha
        }
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

        val order = buildSortedOrder(count) { index ->
            packSortKey(sortBucket(index), splats.orderKey[index], index)
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
        val safeColorWeight = colorWeight.coerceAtLeast(0.0001f)
        val bounds = GaussianPlyLoader.computeSceneBounds(
            minX = minX,
            minY = minY,
            minZ = minZ,
            maxX = maxX,
            maxY = maxY,
            maxZ = maxZ,
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
            backgroundR = (backgroundR / safeColorWeight).coerceIn(0f, 1f),
            backgroundG = (backgroundG / safeColorWeight).coerceIn(0f, 1f),
            backgroundB = (backgroundB / safeColorWeight).coerceIn(0f, 1f),
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

    private fun computeParallaxAnchor(
        splats: SogGpuAccumulator,
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

    private fun ImageData.packedRgba(index: Int): Int {
        val pixel = pixels[index]
        val r = (pixel ushr 16) and 0xff
        val g = (pixel ushr 8) and 0xff
        val b = pixel and 0xff
        val a = (pixel ushr 24) and 0xff
        return r or (g shl 8) or (b shl 16) or (a shl 24)
    }

    private fun allocateIntBuffer(count: Int): IntBuffer {
        return ByteBuffer
            .allocateDirect(count * Int.SIZE_BYTES)
            .order(ByteOrder.nativeOrder())
            .asIntBuffer()
    }

    private fun allocateFloatBuffer(values: FloatArray): FloatBuffer {
        return ByteBuffer
            .allocateDirect(values.size * FLOAT_SIZE_BYTES)
            .order(ByteOrder.nativeOrder())
            .asFloatBuffer()
            .apply {
                put(values)
                position(0)
            }
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
        return withBundledZip(context, input) { zip, zipIndex ->
            readBundledFiles(zip, zipIndex)
        }
    }

    private inline fun <T> withBundledZip(
        context: Context,
        input: InputStream,
        block: (ZipFile, BundledZipIndex) -> T
    ): T {
        val tempFile = File.createTempFile("gaussian_sog_", ".sog", context.cacheDir)
        return try {
            tempFile.outputStream().use { output -> input.copyTo(output) }
            ZipFile(tempFile).use { zip ->
                val entries = ArrayList<ZipEntry>()
                val byName = HashMap<String, ZipEntry>()
                val zipEntries = zip.entries()
                while (zipEntries.hasMoreElements()) {
                    val entry = zipEntries.nextElement()
                    if (!entry.isDirectory) {
                        val normalized = normalizeEntryName(entry.name)
                        entries += entry
                        byName[normalized] = entry
                        byName[normalized.lowercase(Locale.US)] = entry
                    }
                }
                block(zip, BundledZipIndex(entries, byName))
            }
        } finally {
            tempFile.delete()
        }
    }

    private fun readBundledFiles(zip: ZipFile, zipIndex: BundledZipIndex): Map<String, ByteArray> {
        val files = HashMap<String, ByteArray>()
        zipIndex.entries.forEach { entry ->
            val baseName = entryBaseName(entry.name)
            if (shouldKeepBundledEntry(baseName)) {
                val bytes = zip.getInputStream(entry).use { it.readBytes() }
                files.storeEntry(normalizeEntryName(entry.name), bytes)
                files.storeEntry(baseName, bytes)
            }
        }
        return files
    }

    private fun resolveLodStageSources(
        lodMeta: JSONObject,
        resolveEntry: (String) -> String?
    ): List<LodStageSource> {
        val filenames = lodMeta.getJSONArray("filenames") ?: return emptyList()
        val chunksByLod = linkedMapOf<Int, MutableList<LodChunkSource>>()
        collectLodChunks(lodMeta.getJSONObject("tree"), filenames, chunksByLod)
        if (chunksByLod.isEmpty()) {
            for (i in 0 until filenames.size) {
                val filename = filenames.getString(i) ?: continue
                val lod = filename.substringBefore('_').toIntOrNull() ?: continue
                chunksByLod.getOrPut(lod) { arrayListOf() }
                    .add(LodChunkSource(filename = filename, offset = 0, count = 0, bounds = null))
            }
        }
        val stages = chunksByLod.entries.mapNotNull { (lodLevel, chunks) ->
            val resolvedChunks = chunks.mapNotNull { chunk ->
                resolveEntry(chunk.filename)?.let { resolved -> chunk.copy(filename = resolved) }
            }
            if (resolvedChunks.size != chunks.size) {
                Log.w(
                    TAG,
                    "LOD SOG missing files lod=$lodLevel expected=${chunks.size} found=${resolvedChunks.size}"
                )
                return@mapNotNull null
            }
            LodStageSource(lodLevel = lodLevel, chunks = resolvedChunks)
        }.sortedByDescending { it.lodLevel }
        return stages
    }

    private fun readSogSourceCount(zip: ZipFile, zipIndex: BundledZipIndex, filename: String): Int {
        val entry = zipIndex.findEntry(filename) ?: error("LOD SOG chunk missing meta.json: $filename")
        val metaBytes = if (filename.endsWith(".sog", ignoreCase = true)) {
            readNestedSogMeta(zip, entry)
        } else {
            zip.getInputStream(entry).use { it.readBytes() }
        }
        val count = JSON.parseObject(String(metaBytes, StandardCharsets.UTF_8)).getIntValue("count")
        require(count > 0) { "SOG has no splats" }
        return count
    }

    private fun allocateChunkBudgets(sourceCounts: List<Int>, maxSplats: Int): IntArray {
        require(sourceCounts.isNotEmpty()) { "LOD stage has no chunks" }
        val totalCount = sourceCounts.sumOf { it.toLong() }.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
        val target = maxSplats
            .coerceAtLeast(sourceCounts.size)
            .coerceAtMost(totalCount)
        val budgets = IntArray(sourceCounts.size) { 1 }
        val remainingTarget = target - sourceCounts.size
        val remainingSource = sourceCounts.sumOf { (it - 1).coerceAtLeast(0).toLong() }
        if (remainingTarget <= 0 || remainingSource <= 0L) return budgets
        var sourcePrefix = 0L
        var assignedPrefix = 0
        sourceCounts.forEachIndexed { index, count ->
            sourcePrefix += (count - 1).coerceAtLeast(0).toLong()
            val nextAssigned = (remainingTarget.toLong() * sourcePrefix / remainingSource).toInt()
            budgets[index] += nextAssigned - assignedPrefix
            assignedPrefix = nextAssigned
        }
        return budgets
    }

    private fun buildUniformGpuChunkRequests(sourceCount: Int, maxSplats: Int): List<GpuChunkRequest> {
        require(sourceCount > 0) { "SOG has no splats" }
        val target = maxSplats
            .coerceIn(1, MAX_SPLAT_LIMIT)
            .coerceAtMost(sourceCount)
        return listOf(GpuChunkRequest(offset = 0, count = sourceCount, maxSplats = target, bounds = null))
    }

    private fun collectLodChunks(
        node: JSONObject?,
        filenames: JSONArray,
        result: MutableMap<Int, MutableList<LodChunkSource>>
    ) {
        if (node == null) return
        val nodeBounds = parseLodChunkBounds(node.getJSONObject("bound"))
        val lods = node.getJSONObject("lods")
        if (lods != null) {
            lods.keys.forEach { key ->
                val lodLevel = key.toIntOrNull() ?: return@forEach
                val lodData = lods.getJSONObject(key) ?: return@forEach
                val fileIndex = lodData.getInteger("file") ?: return@forEach
                if (fileIndex < 0 || fileIndex >= filenames.size) return@forEach
                val filename = filenames.getString(fileIndex) ?: return@forEach
                val offset = lodData.getIntValue("offset").coerceAtLeast(0)
                val count = lodData.getIntValue("count")
                if (count <= 0) return@forEach
                result.getOrPut(lodLevel) { arrayListOf() }
                    .add(LodChunkSource(filename = filename, offset = offset, count = count, bounds = nodeBounds))
            }
            return
        }
        node.getJSONArray("children")?.let { children ->
            for (i in 0 until children.size) {
                collectLodChunks(children.getJSONObject(i), filenames, result)
            }
        }
    }

    private fun parseLodChunkBounds(bound: JSONObject?): LodChunkBounds? {
        val minValues = bound?.getJSONArray("min") ?: return null
        val maxValues = bound.getJSONArray("max") ?: return null
        if (minValues.size < 3 || maxValues.size < 3) return null
        val result = LodChunkBounds(
            minX = minValues.getFloatValue(0),
            minY = minValues.getFloatValue(1),
            minZ = minValues.getFloatValue(2),
            maxX = maxValues.getFloatValue(0),
            maxY = maxValues.getFloatValue(1),
            maxZ = maxValues.getFloatValue(2)
        )
        return result.takeIf {
            it.minX.isFinite() && it.minY.isFinite() && it.minZ.isFinite() &&
                it.maxX.isFinite() && it.maxY.isFinite() && it.maxZ.isFinite()
        }
    }

    private fun readSogChunkFiles(
        zip: ZipFile,
        zipIndex: BundledZipIndex,
        filename: String
    ): Map<String, ByteArray> {
        val normalized = normalizeEntryName(filename)
        if (normalized.endsWith(".sog", ignoreCase = true)) {
            val entry = zipIndex.findEntry(normalized)
                ?: error("LOD SOG chunk missing bundle: $filename")
            return readNestedSogFiles(zip, entry, filename)
        }
        val prefix = normalized.substringBeforeLast('/', missingDelimiterValue = "")
        val result = HashMap<String, ByteArray>()
        val prefixWithSlash = if (prefix.isEmpty()) "" else "$prefix/"
        zipIndex.entries.forEach { entry ->
            val normalizedName = normalizeEntryName(entry.name)
            if (normalizedName.startsWith(prefixWithSlash)) {
                val relative = normalizedName.removePrefix(prefixWithSlash)
                val baseName = entryBaseName(relative)
                if (relative.isNotBlank() && shouldKeepBundledEntry(baseName)) {
                    val bytes = zip.getInputStream(entry).use { it.readBytes() }
                    result.storeEntry(relative, bytes)
                    result.storeEntry(baseName, bytes)
                }
            }
        }
        require(result.findEntry("meta.json") != null) { "LOD SOG chunk missing meta.json: $filename" }
        return result
    }

    private fun readNestedSogMeta(zip: ZipFile, entry: ZipEntry): ByteArray {
        ZipInputStream(zip.getInputStream(entry)).use { nestedZip ->
            while (true) {
                val nestedEntry = nestedZip.nextEntry ?: break
                if (!nestedEntry.isDirectory && entryBaseName(nestedEntry.name).equals("meta.json", ignoreCase = true)) {
                    return nestedZip.readBytes()
                }
            }
        }
        error("LOD SOG bundle missing meta.json: ${entry.name}")
    }

    private fun readNestedSogFiles(
        zip: ZipFile,
        entry: ZipEntry,
        sourceName: String
    ): Map<String, ByteArray> {
        val files = HashMap<String, ByteArray>()
        ZipInputStream(zip.getInputStream(entry)).use { nestedZip ->
            while (true) {
                val nestedEntry = nestedZip.nextEntry ?: break
                if (!nestedEntry.isDirectory) {
                    val normalized = normalizeEntryName(nestedEntry.name)
                    val baseName = entryBaseName(normalized)
                    if (shouldKeepBundledEntry(baseName)) {
                        val bytes = nestedZip.readBytes()
                        files.storeEntry(normalized, bytes)
                        files.storeEntry(baseName, bytes)
                    }
                }
            }
        }
        require(files.findEntry("meta.json") != null) { "LOD SOG bundle missing meta.json: $sourceName" }
        return files
    }

    private fun BundledZipIndex.findEntry(name: String, allowBaseName: Boolean = false): ZipEntry? {
        val normalized = normalizeEntryName(name)
        return byName[normalized]
            ?: byName[normalized.lowercase(Locale.US)]
            ?: if (allowBaseName) {
                val baseName = entryBaseName(normalized)
                entries.firstOrNull { entryBaseName(it.name).equals(baseName, ignoreCase = true) }
            } else {
                null
            }
    }

    private fun BundledZipIndex.resolveLodEntry(lodRoot: String, name: String): String? {
        val normalized = normalizeEntryName(name)
        val rooted = if (lodRoot.isEmpty()) normalized else "$lodRoot/$normalized"
        return when {
            findEntry(rooted) != null -> rooted
            findEntry(normalized) != null -> normalized
            else -> null
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

    private fun normalizeEntryName(name: String): String {
        return name.replace('\\', '/').trimStart('/')
    }

    private fun shouldKeepBundledEntry(baseName: String): Boolean {
        val lower = baseName.lowercase()
        if (lower == "meta.json") return true
        if (lower == "lod-meta.json") return true
        if (lower.endsWith(".sog")) return true
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
    private const val MORTON_AXIS_MAX = 255
    private const val SORT_INDEX_MASK = 0xFFFFFFL
    private const val SORT_MORTON_MASK = 0xFFFFFFL
    private const val SORT_BUCKET_MASK = 0xFFFFL
    private const val SORT_MORTON_SHIFT = 24
    private const val SORT_BUCKET_SHIFT = 48
    private const val DEFAULT_QUAT_PACKED = 0xFC808080.toInt()
    private const val SOG_FOREGROUND_DEPTH_BUCKETS = 64
    private const val SOG_FOREGROUND_DEPTH_FRACTION = 0.55f
    private const val SOG_FOREGROUND_BUDGET_FRACTION = 0.85f
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
