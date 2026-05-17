package com.zeaze.tianyinwallpaper.utils

import android.content.Context
import android.net.Uri
import android.util.Log
import java.io.BufferedReader
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStreamReader
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.nio.ShortBuffer
import java.nio.charset.StandardCharsets
import kotlin.math.PI
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

object PhotoMeshPlyLoader {
    private const val TAG = "PhotoMeshPlyLoader"
    const val DEFAULT_MAX_FACES = 8_000_000
    const val MIN_FACE_LIMIT = 40_000
    const val MAX_FACE_LIMIT = 8_000_000
    private const val MAX_CHUNK_VERTICES = 60_000
    private const val MAX_CHUNK_INDICES = 180_000
    private val cacheWriteLock = Any()

    data class MeshScene(
        val imageWidth: Int,
        val imageHeight: Int,
        val hFovRad: Float,
        val vFovRad: Float,
        val vertexCount: Int,
        val sourceFaceCount: Int,
        val faceCount: Int,
        val faceStride: Int,
        val nearDepth: Float,
        val farDepth: Float,
        val focusDepth: Float,
        val parallaxAnchorDepth: Float,
        val chunks: List<MeshChunk>,
        val backgroundR: Float,
        val backgroundG: Float,
        val backgroundB: Float
    )

    data class MeshChunk(
        val vertices: FloatBuffer,
        val indices: ShortBuffer,
        val vertexCount: Int,
        val indexCount: Int
    )

    private data class Header(
        val format: String,
        val vertexCount: Int,
        val faceCount: Int,
        val vertexProperties: List<String>,
        val imageWidth: Int,
        val imageHeight: Int,
        val hFovRad: Float,
        val vFovRad: Float
    )

    fun loadScene(
        context: Context,
        uriString: String,
        maxFaces: Int = DEFAULT_MAX_FACES
    ): MeshScene? {
        if (uriString.isBlank()) return null
        val uri = Uri.parse(uriString)
        val faceLimit = maxFaces.coerceIn(MIN_FACE_LIMIT, MAX_FACE_LIMIT)
        val cacheFile = meshCacheFile(context, uriString, faceLimit)
        loadCachedScene(cacheFile)?.let { return it }
        return runCatching {
            val scene = context.contentResolver.openInputStream(uri)?.use { input ->
                BufferedReader(InputStreamReader(input, StandardCharsets.US_ASCII), 64 * 1024).use { reader ->
                    parse(reader, faceLimit)
                }
            } ?: error("Cannot open PLY uri: $uriString")
            saveCachedScene(cacheFile, scene)
            scene
        }.onFailure {
            Log.w(TAG, "Failed to load photo mesh PLY: $uriString", it)
        }.getOrNull()
    }

    private fun meshCacheFile(context: Context, uriString: String, faceLimit: Int): File? {
        val root = context.getExternalFilesDir(null) ?: return null
        val dir = File(root, "photo_mesh_cache")
        if (!dir.mkdirs() && !dir.exists()) return null
        val hash = Integer.toHexString("$uriString|$faceLimit|$MESH_CACHE_VERSION".hashCode())
        return File(dir, "$hash.tymesh")
    }

    private fun loadCachedScene(cacheFile: File?): MeshScene? {
        if (cacheFile == null || !cacheFile.exists() || cacheFile.length() <= 0L) return null
        return runCatching {
            DataInputStream(BufferedInputStream(FileInputStream(cacheFile), 256 * 1024)).use { input ->
                require(input.readInt() == MESH_CACHE_MAGIC) { "Invalid mesh cache magic" }
                require(input.readInt() == MESH_CACHE_VERSION) { "Unsupported mesh cache version" }
                require(input.readInt() == MESH_VERTEX_STRIDE_FLOATS) { "Unsupported mesh vertex stride" }

                val imageWidth = input.readInt()
                val imageHeight = input.readInt()
                val hFovRad = input.readFloat()
                val vFovRad = input.readFloat()
                val vertexCount = input.readInt()
                val sourceFaceCount = input.readInt()
                val faceCount = input.readInt()
                val faceStride = input.readInt()
                val nearDepth = input.readFloat()
                val farDepth = input.readFloat()
                val focusDepth = input.readFloat()
                val parallaxAnchorDepth = input.readFloat()
                val backgroundR = input.readFloat()
                val backgroundG = input.readFloat()
                val backgroundB = input.readFloat()
                val chunkCount = input.readInt().coerceAtLeast(0)
                require(chunkCount > 0) { "Empty mesh cache" }

                val chunks = ArrayList<MeshChunk>(chunkCount)
                repeat(chunkCount) {
                    val chunkVertexCount = input.readInt()
                    val chunkIndexCount = input.readInt()
                    require(chunkVertexCount in 1..MAX_CHUNK_VERTICES) { "Invalid cached chunk vertices" }
                    require(chunkIndexCount in 1..MAX_CHUNK_INDICES) { "Invalid cached chunk indices" }
                    val vertices = directFloatBuffer(chunkVertexCount * MESH_VERTEX_STRIDE_FLOATS)
                    repeat(chunkVertexCount * MESH_VERTEX_STRIDE_FLOATS) {
                        vertices.put(input.readFloat())
                    }
                    vertices.position(0)
                    val indices = directShortBuffer(chunkIndexCount)
                    repeat(chunkIndexCount) {
                        indices.put(input.readShort())
                    }
                    indices.position(0)
                    chunks += MeshChunk(
                        vertices = vertices,
                        indices = indices,
                        vertexCount = chunkVertexCount,
                        indexCount = chunkIndexCount
                    )
                }

                MeshScene(
                    imageWidth = imageWidth,
                    imageHeight = imageHeight,
                    hFovRad = hFovRad,
                    vFovRad = vFovRad,
                    vertexCount = vertexCount,
                    sourceFaceCount = sourceFaceCount,
                    faceCount = faceCount,
                    faceStride = faceStride,
                    nearDepth = nearDepth,
                    farDepth = farDepth,
                    focusDepth = focusDepth,
                    parallaxAnchorDepth = parallaxAnchorDepth,
                    chunks = chunks,
                    backgroundR = backgroundR,
                    backgroundG = backgroundG,
                    backgroundB = backgroundB
                )
            }.also {
                Log.d(
                    TAG,
                    "loaded cached mesh faces=${it.faceCount}/${it.sourceFaceCount} " +
                        "chunks=${it.chunks.size} image=${it.imageWidth}x${it.imageHeight}"
                )
            }
        }.onFailure {
            Log.w(TAG, "Failed to load mesh cache: ${cacheFile.absolutePath}", it)
            cacheFile.delete()
        }.getOrNull()
    }

    private fun saveCachedScene(cacheFile: File?, scene: MeshScene) {
        if (cacheFile == null) return
        runCatching {
            val parent = cacheFile.parentFile
            if (parent != null && !parent.exists()) parent.mkdirs()
            val tempFile = File(
                cacheFile.parentFile,
                "${cacheFile.name}.${System.identityHashCode(Thread.currentThread())}.${System.nanoTime()}.tmp"
            )
            DataOutputStream(BufferedOutputStream(FileOutputStream(tempFile), 256 * 1024)).use { output ->
                output.writeInt(MESH_CACHE_MAGIC)
                output.writeInt(MESH_CACHE_VERSION)
                output.writeInt(MESH_VERTEX_STRIDE_FLOATS)
                output.writeInt(scene.imageWidth)
                output.writeInt(scene.imageHeight)
                output.writeFloat(scene.hFovRad)
                output.writeFloat(scene.vFovRad)
                output.writeInt(scene.vertexCount)
                output.writeInt(scene.sourceFaceCount)
                output.writeInt(scene.faceCount)
                output.writeInt(scene.faceStride)
                output.writeFloat(scene.nearDepth)
                output.writeFloat(scene.farDepth)
                output.writeFloat(scene.focusDepth)
                output.writeFloat(scene.parallaxAnchorDepth)
                output.writeFloat(scene.backgroundR)
                output.writeFloat(scene.backgroundG)
                output.writeFloat(scene.backgroundB)
                output.writeInt(scene.chunks.size)
                scene.chunks.forEach { chunk ->
                    output.writeInt(chunk.vertexCount)
                    output.writeInt(chunk.indexCount)
                    val vertices = chunk.vertices.duplicate()
                    vertices.position(0)
                    repeat(chunk.vertexCount * MESH_VERTEX_STRIDE_FLOATS) {
                        output.writeFloat(vertices.get())
                    }
                    val indices = chunk.indices.duplicate()
                    indices.position(0)
                    repeat(chunk.indexCount) {
                        output.writeShort(indices.get().toInt())
                    }
                }
            }
            commitCacheTempFile(tempFile, cacheFile)
            Log.d(TAG, "saved mesh cache: ${cacheFile.absolutePath}")
        }.onFailure {
            Log.w(TAG, "Failed to save mesh cache: ${cacheFile.absolutePath}", it)
        }
    }

    private fun commitCacheTempFile(tempFile: File, cacheFile: File) {
        synchronized(cacheWriteLock) {
            if (cacheFile.exists() && !cacheFile.delete()) {
                tempFile.delete()
                Log.d(TAG, "mesh cache already exists and is in use: ${cacheFile.absolutePath}")
                return
            }
            if (tempFile.renameTo(cacheFile)) return

            tempFile.copyTo(cacheFile, overwrite = true)
            tempFile.delete()
            require(cacheFile.exists() && cacheFile.length() > 0L) {
                "Failed to commit mesh cache temp file"
            }
        }
    }

    fun parse(
        reader: BufferedReader,
        maxFaces: Int = DEFAULT_MAX_FACES
    ): MeshScene {
        val header = parseHeader(reader)
        require(header.format == "ascii") {
            "Only ascii photo mesh PLY is supported"
        }
        require(header.vertexCount > 0) { "PLY missing vertex data" }
        require(header.faceCount > 0) { "PLY missing face data" }

        val propertyIndex = header.vertexProperties.withIndex().associate { it.value to it.index }
        val xIndex = propertyIndex["x"] ?: error("PLY missing vertex property: x")
        val yIndex = propertyIndex["y"] ?: error("PLY missing vertex property: y")
        val zIndex = propertyIndex["z"] ?: error("PLY missing vertex property: z")
        val rIndex = propertyIndex["red"] ?: propertyIndex["r"] ?: error("PLY missing vertex property: red")
        val gIndex = propertyIndex["green"] ?: propertyIndex["g"] ?: error("PLY missing vertex property: green")
        val bIndex = propertyIndex["blue"] ?: propertyIndex["b"] ?: error("PLY missing vertex property: blue")
        val alphaIndex = propertyIndex["alpha"] ?: propertyIndex["a"]

        val sourceVertices = FloatArray(header.vertexCount * MESH_VERTEX_STRIDE_FLOATS)
        var nearDepth = Float.POSITIVE_INFINITY
        var farDepth = 0f
        var depthSum = 0.0
        var validDepthCount = 0
        var farColorR = 0.02f
        var farColorG = 0.02f
        var farColorB = 0.02f

        repeat(header.vertexCount) { index ->
            val line = reader.readLine() ?: error("Unexpected EOF while reading vertices")
            val parts = line.trim().split(WHITESPACE)
            require(parts.size > max(max(max(xIndex, yIndex), max(zIndex, rIndex)), max(gIndex, bIndex))) {
                "Malformed vertex line at $index"
            }
            val x = parts[xIndex].toFloat()
            val y = parts[yIndex].toFloat()
            val z = parts[zIndex].toFloat()
            val r = parts[rIndex].toFloat().toUnitColor()
            val g = parts[gIndex].toFloat().toUnitColor()
            val b = parts[bIndex].toFloat().toUnitColor()
            val semanticAlpha = alphaIndex
                ?.let { parts.getOrNull(it)?.toFloatOrNull() }
                ?.coerceAtLeast(1f)
                ?: 1f

            val base = index * MESH_VERTEX_STRIDE_FLOATS
            sourceVertices[base] = x
            sourceVertices[base + 1] = y
            sourceVertices[base + 2] = z
            sourceVertices[base + 3] = r
            sourceVertices[base + 4] = g
            sourceVertices[base + 5] = b
            sourceVertices[base + 6] = semanticAlpha

            if (z.isFinite() && z > 0.001f) {
                nearDepth = min(nearDepth, z)
                if (z > farDepth) {
                    farDepth = z
                    farColorR = r
                    farColorG = g
                    farColorB = b
                }
                depthSum += z.toDouble()
                validDepthCount++
            }
        }

        if (!nearDepth.isFinite() || farDepth <= 0f) {
            nearDepth = 0.1f
            farDepth = 10f
        }
        val focusDepth = if (validDepthCount > 0) {
            (depthSum / validDepthCount.toDouble()).toFloat()
        } else {
            (nearDepth + farDepth) * 0.5f
        }

        val faceLimit = maxFaces.coerceIn(MIN_FACE_LIMIT, MAX_FACE_LIMIT)
        if (faceLimit < header.faceCount && ENABLE_EXPERIMENTAL_RESAMPLED_MESH) {
            buildResampledSurfaceScene(
                header = header,
                sourceVertices = sourceVertices,
                faceLimit = faceLimit,
                nearDepth = nearDepth,
                farDepth = farDepth,
                focusDepth = focusDepth,
                backgroundR = farColorR,
                backgroundG = farColorG,
                backgroundB = farColorB
            )?.let { return it }
        }

        val stride = max(1, ceil(header.faceCount.toDouble() / faceLimit.toDouble()).toInt())
        val chunks = ArrayList<MeshChunk>()
        val builder = ChunkBuilder(sourceVertices)
        val triangle = IntArray(3)
        var keptFaces = 0

        repeat(header.faceCount) { faceIndex ->
            val line = reader.readLine() ?: error("Unexpected EOF while reading faces")
            if (!parseTriangleFace(line, triangle, header.vertexCount)) return@repeat
            if (!shouldKeepFace(faceIndex, header.faceCount, faceLimit, triangle, sourceVertices)) return@repeat
            if (!builder.canAdd(triangle[0], triangle[1], triangle[2])) {
                builder.build()?.let { chunks += it }
                builder.reset()
            }
            if (builder.addTriangle(triangle[0], triangle[1], triangle[2])) {
                keptFaces++
            }
        }
        builder.build()?.let { chunks += it }

        require(keptFaces > 0 && chunks.isNotEmpty()) { "No triangle faces were loaded from PLY" }

        Log.d(
            TAG,
            "loaded mesh vertices=${header.vertexCount} faces=$keptFaces/${header.faceCount} " +
                "stride=$stride chunks=${chunks.size} image=${header.imageWidth}x${header.imageHeight}"
        )

        return MeshScene(
            imageWidth = header.imageWidth,
            imageHeight = header.imageHeight,
            hFovRad = header.hFovRad,
            vFovRad = header.vFovRad,
            vertexCount = header.vertexCount,
            sourceFaceCount = header.faceCount,
            faceCount = keptFaces,
            faceStride = stride,
            nearDepth = nearDepth,
            farDepth = farDepth,
            focusDepth = focusDepth,
            parallaxAnchorDepth = farDepth,
            chunks = chunks,
            backgroundR = farColorR,
            backgroundG = farColorG,
            backgroundB = farColorB
        )
    }

    private fun buildResampledSurfaceScene(
        header: Header,
        sourceVertices: FloatArray,
        faceLimit: Int,
        nearDepth: Float,
        farDepth: Float,
        focusDepth: Float,
        backgroundR: Float,
        backgroundG: Float,
        backgroundB: Float
    ): MeshScene? {
        val imageWidth = header.imageWidth.coerceAtLeast(1)
        val imageHeight = header.imageHeight.coerceAtLeast(1)
        val targetFaces = faceLimit.coerceAtLeast(MIN_FACE_LIMIT)
        val fullGridFaces = (imageWidth - 1).coerceAtLeast(1) * (imageHeight - 1).coerceAtLeast(1) * 2
        val sampleStep = ceil(sqrt(fullGridFaces.toDouble() / targetFaces.toDouble()))
            .toInt()
            .coerceAtLeast(1)
        val gridWidth = ceil(imageWidth.toDouble() / sampleStep.toDouble()).toInt().coerceAtLeast(2)
        val gridHeight = ceil(imageHeight.toDouble() / sampleStep.toDouble()).toInt().coerceAtLeast(2)
        val cellCount = gridWidth * gridHeight
        val selectedVertex = IntArray(cellCount) { -1 }
        val selectedScore = DoubleArray(cellCount) { -Double.MAX_VALUE }

        val tanHalfH = Math.tan((header.hFovRad * 0.5f).toDouble()).toFloat().coerceAtLeast(0.0001f)
        val tanHalfV = Math.tan((header.vFovRad * 0.5f).toDouble()).toFloat().coerceAtLeast(0.0001f)
        for (vertexIndex in 0 until header.vertexCount) {
            val base = vertexIndex * MESH_VERTEX_STRIDE_FLOATS
            val z = sourceVertices[base + 2]
            if (!z.isFinite() || z <= 0.0001f) continue
            val imageX = (((sourceVertices[base] / z) / tanHalfH + 1f) * 0.5f * (imageWidth - 1))
                .roundToInt()
                .coerceIn(0, imageWidth - 1)
            val imageY = ((1f - (sourceVertices[base + 1] / z) / tanHalfV) * 0.5f * (imageHeight - 1))
                .roundToInt()
                .coerceIn(0, imageHeight - 1)
            val gridX = (imageX / sampleStep).coerceIn(0, gridWidth - 1)
            val gridY = (imageY / sampleStep).coerceIn(0, gridHeight - 1)
            val cellIndex = gridY * gridWidth + gridX
            val score = vertexSelectionScore(sourceVertices, base)
            if (score > selectedScore[cellIndex]) {
                selectedScore[cellIndex] = score
                selectedVertex[cellIndex] = vertexIndex
            }
        }

        val builder = ChunkBuilder(sourceVertices)
        val chunks = ArrayList<MeshChunk>()
        var keptFaces = 0
        for (gridY in 0 until gridHeight - 1) {
            for (gridX in 0 until gridWidth - 1) {
                val topLeft = selectedVertex[gridY * gridWidth + gridX]
                val topRight = selectedVertex[gridY * gridWidth + gridX + 1]
                val bottomLeft = selectedVertex[(gridY + 1) * gridWidth + gridX]
                val bottomRight = selectedVertex[(gridY + 1) * gridWidth + gridX + 1]
                if (topLeft < 0 || topRight < 0 || bottomLeft < 0 || bottomRight < 0) continue

                if (!builder.canAdd(topLeft, bottomLeft, topRight)) {
                    builder.build()?.let { chunks += it }
                    builder.reset()
                }
                if (builder.addTriangle(topLeft, bottomLeft, topRight)) keptFaces++

                if (!builder.canAdd(topRight, bottomLeft, bottomRight)) {
                    builder.build()?.let { chunks += it }
                    builder.reset()
                }
                if (builder.addTriangle(topRight, bottomLeft, bottomRight)) keptFaces++
            }
        }
        builder.build()?.let { chunks += it }
        if (keptFaces <= 0 || chunks.isEmpty()) return null

        Log.d(
            TAG,
            "resampled mesh vertices=${header.vertexCount} faces=$keptFaces/${header.faceCount} " +
                "step=$sampleStep grid=${gridWidth}x${gridHeight} chunks=${chunks.size} image=${imageWidth}x$imageHeight"
        )
        return MeshScene(
            imageWidth = imageWidth,
            imageHeight = imageHeight,
            hFovRad = header.hFovRad,
            vFovRad = header.vFovRad,
            vertexCount = header.vertexCount,
            sourceFaceCount = header.faceCount,
            faceCount = keptFaces,
            faceStride = sampleStep,
            nearDepth = nearDepth,
            farDepth = farDepth,
            focusDepth = focusDepth,
            parallaxAnchorDepth = farDepth,
            chunks = chunks,
            backgroundR = backgroundR,
            backgroundG = backgroundG,
            backgroundB = backgroundB
        )
    }

    private fun vertexSelectionScore(vertices: FloatArray, base: Int): Double {
        val z = vertices[base + 2].coerceAtLeast(0.0001f)
        val semanticAlpha = vertices[base + 6]
        val semanticScore = when {
            semanticAlpha >= 4f -> 10_000.0
            semanticAlpha > 1f -> 5_000.0
            else -> 0.0
        }
        return semanticScore + 1.0 / z.toDouble()
    }

    private fun parseHeader(reader: BufferedReader): Header {
        var firstLine = reader.readLine()?.trim().orEmpty()
        require(firstLine == "ply") { "Not a PLY file" }

        var format = ""
        var vertexCount = 0
        var faceCount = 0
        var currentElement = ""
        val vertexProperties = ArrayList<String>()
        var imageWidth = 1
        var imageHeight = 1
        var hFovRad = DEFAULT_FOV_RAD
        var vFovRad = DEFAULT_FOV_RAD

        while (true) {
            val line = reader.readLine()?.trim() ?: error("PLY header missing end_header")
            if (line == "end_header") break
            if (line.isBlank()) continue
            val parts = line.split(WHITESPACE)
            when (parts.firstOrNull()) {
                "format" -> format = parts.getOrNull(1).orEmpty()
                "comment" -> {
                    when (parts.getOrNull(1)) {
                        "W" -> imageWidth = parts.getOrNull(2)?.toIntOrNull()?.coerceAtLeast(1) ?: imageWidth
                        "H" -> imageHeight = parts.getOrNull(2)?.toIntOrNull()?.coerceAtLeast(1) ?: imageHeight
                        "hFov" -> hFovRad = parts.getOrNull(2)?.toFloatOrNull()?.coerceFov() ?: hFovRad
                        "vFov" -> vFovRad = parts.getOrNull(2)?.toFloatOrNull()?.coerceFov() ?: vFovRad
                    }
                }
                "element" -> {
                    currentElement = parts.getOrNull(1).orEmpty()
                    val count = parts.getOrNull(2)?.toIntOrNull() ?: 0
                    when (currentElement) {
                        "vertex" -> vertexCount = count
                        "face" -> faceCount = count
                    }
                }
                "property" -> {
                    if (currentElement == "vertex" && parts.getOrNull(1) != "list") {
                        parts.getOrNull(2)?.let { vertexProperties += it }
                    }
                }
            }
        }

        if (imageWidth == 1 && imageHeight == 1 && vertexCount > 0) {
            val side = sqrt(vertexCount.toFloat()).toInt().coerceAtLeast(1)
            imageWidth = side
            imageHeight = side
        }
        return Header(
            format = format,
            vertexCount = vertexCount,
            faceCount = faceCount,
            vertexProperties = vertexProperties,
            imageWidth = imageWidth,
            imageHeight = imageHeight,
            hFovRad = hFovRad,
            vFovRad = vFovRad
        )
    }

    private class ChunkBuilder(private val sourceVertices: FloatArray) {
        private val globalToLocal = HashMap<Int, Int>(MAX_CHUNK_VERTICES)
        private val vertices = FloatArray(MAX_CHUNK_VERTICES * MESH_VERTEX_STRIDE_FLOATS)
        private val indices = ShortArray(MAX_CHUNK_INDICES)
        private var vertexCount = 0
        private var indexCount = 0

        fun reset() {
            globalToLocal.clear()
            vertexCount = 0
            indexCount = 0
        }

        fun canAdd(a: Int, b: Int, c: Int): Boolean {
            val newVertices = newVertexCost(a, b, c)
            return vertexCount + newVertices <= MAX_CHUNK_VERTICES &&
                indexCount + 3 <= MAX_CHUNK_INDICES
        }

        fun addTriangle(a: Int, b: Int, c: Int): Boolean {
            if (!canAdd(a, b, c)) return false
            indices[indexCount++] = localIndex(a).toShort()
            indices[indexCount++] = localIndex(b).toShort()
            indices[indexCount++] = localIndex(c).toShort()
            return true
        }

        fun build(): MeshChunk? {
            if (vertexCount == 0 || indexCount == 0) return null
            val vertexBuffer = directFloatBuffer(vertexCount * MESH_VERTEX_STRIDE_FLOATS)
            vertexBuffer.put(vertices, 0, vertexCount * MESH_VERTEX_STRIDE_FLOATS)
            vertexBuffer.position(0)
            val indexBuffer = directShortBuffer(indexCount)
            indexBuffer.put(indices, 0, indexCount)
            indexBuffer.position(0)
            return MeshChunk(
                vertices = vertexBuffer,
                indices = indexBuffer,
                vertexCount = vertexCount,
                indexCount = indexCount
            )
        }

        private fun newVertexCost(a: Int, b: Int, c: Int): Int {
            var cost = 0
            if (a !in globalToLocal) cost++
            if (b !in globalToLocal && b != a) cost++
            if (c !in globalToLocal && c != a && c != b) cost++
            return cost
        }

        private fun localIndex(global: Int): Int {
            globalToLocal[global]?.let { return it }
            val local = vertexCount++
            val sourceBase = global * MESH_VERTEX_STRIDE_FLOATS
            val targetBase = local * MESH_VERTEX_STRIDE_FLOATS
            for (offset in 0 until MESH_VERTEX_STRIDE_FLOATS) {
                vertices[targetBase + offset] = sourceVertices[sourceBase + offset]
            }

            globalToLocal[global] = local
            return local
        }
    }

    private fun parseTriangleFace(line: String, out: IntArray, vertexCount: Int): Boolean {
        var index = skipSpaces(line, 0)
        val count = parsePositiveInt(line, index) ?: return false
        if (count.value != 3) return false
        index = skipSpaces(line, count.nextIndex)
        val a = parsePositiveInt(line, index) ?: return false
        index = skipSpaces(line, a.nextIndex)
        val b = parsePositiveInt(line, index) ?: return false
        index = skipSpaces(line, b.nextIndex)
        val c = parsePositiveInt(line, index) ?: return false
        if (a.value !in 0 until vertexCount || b.value !in 0 until vertexCount || c.value !in 0 until vertexCount) {
            return false
        }
        out[0] = a.value
        out[1] = b.value
        out[2] = c.value
        return true
    }

    private fun shouldKeepFace(
        faceIndex: Int,
        faceCount: Int,
        faceLimit: Int,
        triangle: IntArray,
        vertices: FloatArray
    ): Boolean {
        if (faceLimit >= faceCount) return true
        val baseThreshold = (faceLimit.toDouble() / faceCount.toDouble()).coerceIn(0.0, 1.0)
        val importance = faceImportance(triangle, vertices)
        val threshold = (baseThreshold * importance).coerceIn(0.0, 1.0)
        return unitHash(faceIndex) < threshold
    }

    private fun faceImportance(triangle: IntArray, vertices: FloatArray): Double {
        var minZ = Float.POSITIVE_INFINITY
        var maxZ = 0f
        var maxSemanticAlpha = 1f
        for (globalIndex in triangle) {
            val base = globalIndex * MESH_VERTEX_STRIDE_FLOATS
            val z = vertices[base + 2]
            if (z.isFinite() && z > 0f) {
                minZ = min(minZ, z)
                maxZ = max(maxZ, z)
            }
            maxSemanticAlpha = max(maxSemanticAlpha, vertices[base + 6])
        }
        if (maxSemanticAlpha >= 4f) return 5.0
        if (maxSemanticAlpha > 1f) return 3.0
        if (minZ.isFinite() && maxZ > minZ * 1.18f) return 2.5
        return 1.0
    }

    private fun unitHash(value: Int): Double {
        var x = value * 0x45d9f3b
        x = x xor (x ushr 16)
        x *= 0x45d9f3b
        x = x xor (x ushr 16)
        return (x.toLong() and 0xffffffffL).toDouble() / 4294967296.0
    }

    private data class IntToken(val value: Int, val nextIndex: Int)

    private fun parsePositiveInt(line: String, start: Int): IntToken? {
        var index = start
        if (index >= line.length || !line[index].isDigit()) return null
        var value = 0
        while (index < line.length && line[index].isDigit()) {
            value = value * 10 + (line[index] - '0')
            index++
        }
        return IntToken(value, index)
    }

    private fun skipSpaces(line: String, start: Int): Int {
        var index = start
        while (index < line.length && line[index].isWhitespace()) index++
        return index
    }

    private fun Float.toUnitColor(): Float {
        return if (this > 1f) (this / 255f).coerceIn(0f, 1f) else this.coerceIn(0f, 1f)
    }

    private fun Float.coerceFov(): Float {
        return coerceIn(0.2f, (PI.toFloat() * 0.92f))
    }

    private fun directFloatBuffer(size: Int): FloatBuffer {
        return ByteBuffer.allocateDirect(size * FLOAT_SIZE_BYTES)
            .order(ByteOrder.nativeOrder())
            .asFloatBuffer()
    }

    private fun directShortBuffer(size: Int): ShortBuffer {
        return ByteBuffer.allocateDirect(size * SHORT_SIZE_BYTES)
            .order(ByteOrder.nativeOrder())
            .asShortBuffer()
    }

    private val WHITESPACE = Regex("\\s+")
    private const val MESH_CACHE_MAGIC = 0x54594d53
    private const val MESH_CACHE_VERSION = 4
    private const val ENABLE_EXPERIMENTAL_RESAMPLED_MESH = false
    private const val MESH_VERTEX_STRIDE_FLOATS = 7
    private const val FLOAT_SIZE_BYTES = 4
    private const val SHORT_SIZE_BYTES = 2
    private const val DEFAULT_FOV_RAD = 0.9272952f
}
