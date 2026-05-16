package com.zeaze.tianyinwallpaper.utils

import android.content.Context
import android.content.res.AssetFileDescriptor
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.util.Log
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt
import org.tensorflow.lite.DataType
import org.tensorflow.lite.Interpreter
import org.tensorflow.lite.Tensor

object DepthModelRunner {
    private const val TAG = "DepthModelRunner"
    private const val CACHE_VERSION = "v2"
    private val modelCandidates = listOf(
        "midas_v21_small_256_float32.tflite",
        "depth_model.tflite",
        "midas_depth.tflite",
        "fast_depth.tflite"
    )

    private val lock = Any()
    @Volatile private var runner: Runner? = null
    @Volatile private var unavailable = false
    @Volatile private var lastErrorMessage: String? = null

    data class DepthInferenceResult(
        val modelName: String,
        val inputWidth: Int,
        val inputHeight: Int,
        val outputWidth: Int,
        val outputHeight: Int,
        val sourceWidth: Int,
        val sourceHeight: Int,
        val inferenceMs: Long,
        val rawDepthMap: Bitmap,
        val upsampledDepthMap: Bitmap
    )

    fun modelCacheKey(context: Context): String {
        val assetName = findModelAsset(context) ?: return "heuristic-$CACHE_VERSION"
        return "tflite-$CACHE_VERSION-${assetName}"
    }

    fun lastError(): String? = lastErrorMessage

    fun inferDepthMap(context: Context, source: Bitmap): Bitmap? {
        val result = inferDepthResult(context, source) ?: return null
        if (result.rawDepthMap !== result.upsampledDepthMap && !result.rawDepthMap.isRecycled) {
            result.rawDepthMap.recycle()
        }
        return result.upsampledDepthMap
    }

    fun inferDepthResult(context: Context, source: Bitmap): DepthInferenceResult? {
        val activeRunner = synchronized(lock) {
            if (unavailable) return@synchronized null
            runner ?: createRunner(context.applicationContext).also {
                if (it == null) unavailable = true else runner = it
            }
        } ?: return null

        return synchronized(activeRunner) {
            runCatching {
                activeRunner.infer(source)
            }.onSuccess {
                lastErrorMessage = null
            }.onFailure {
                setLastError("Depth model inference failed", it)
                Log.w(TAG, "Depth model inference failed; falling back to heuristic depth", it)
            }.getOrNull()
        }
    }

    private fun createRunner(context: Context): Runner? {
        val assetName = findModelAsset(context)
            ?: return null.also {
                lastErrorMessage = "Depth model asset not found. Expected one of: ${modelCandidates.joinToString()}"
            }
        return try {
            val buffer = loadModelBuffer(context, assetName)
            val interpreter = Interpreter(
                buffer,
                Interpreter.Options().apply {
                    setNumThreads(2)
                }
            )
            Runner(assetName, interpreter).also {
                lastErrorMessage = null
            }
        } catch (e: Exception) {
            setLastError("Failed to load depth model from assets/$assetName", e)
            Log.w(TAG, "Failed to load depth model from assets", e)
            null
        }
    }

    private fun setLastError(prefix: String, throwable: Throwable) {
        val message = throwable.message
            ?.replace('\n', ' ')
            ?.replace(Regex("\\s+"), " ")
            ?.trim()
            ?.takeIf { it.isNotBlank() }
        lastErrorMessage = buildString {
            append(prefix)
            append(" (")
            append(throwable::class.java.simpleName)
            if (message != null) {
                append(": ")
                append(message)
            }
            append(")")
        }
    }

    private fun findModelAsset(context: Context): String? {
        val assets = runCatching { context.assets.list("")?.toSet().orEmpty() }
            .getOrDefault(emptySet())
        return modelCandidates.firstOrNull { it in assets }
    }

    private fun loadModelBuffer(context: Context, assetName: String): ByteBuffer {
        return try {
            context.assets.openFd(assetName).use { descriptor ->
                mapAsset(descriptor)
            }
        } catch (_: Exception) {
            context.assets.open(assetName).use { input ->
                val bytes = input.readBytes()
                ByteBuffer.allocateDirect(bytes.size)
                    .order(ByteOrder.nativeOrder())
                    .put(bytes)
                    .also { it.position(0) }
            }
        }
    }

    private fun mapAsset(descriptor: AssetFileDescriptor): ByteBuffer {
        FileInputStream(descriptor.fileDescriptor).use { input ->
            return input.channel.map(
                FileChannel.MapMode.READ_ONLY,
                descriptor.startOffset,
                descriptor.declaredLength
            )
        }
    }

    private class Runner(
        private val assetName: String,
        private val interpreter: Interpreter
    ) {
        private val inputTensor: Tensor = interpreter.getInputTensor(0)
        private val outputTensor: Tensor = interpreter.getOutputTensor(0)
        private val inputShape: IntArray = inputTensor.shape()
        private val outputShape: IntArray = outputTensor.shape()
        private val inputType: DataType = inputTensor.dataType()
        private val outputType: DataType = outputTensor.dataType()
        private val inputLayout: InputLayout = resolveInputLayout(inputShape)
        private val normalization = Normalization.forAsset(assetName)
        private val outputSize: Pair<Int, Int> = resolveOutputSize(outputShape)
        private val outputElementCount: Int = outputShape.fold(1) { acc, dim -> acc * dim.coerceAtLeast(1) }

        init {
            Log.i(
                TAG,
                "Loaded depth model $assetName input=${inputShape.joinToString()} output=${outputShape.joinToString()}"
            )
        }

        fun infer(source: Bitmap): DepthInferenceResult {
            val startMs = System.currentTimeMillis()
            val input = buildInputBuffer(source)
            val output = ByteBuffer.allocateDirect(outputElementCount * outputType.byteSize())
                .order(ByteOrder.nativeOrder())
            interpreter.run(input, output)
            output.position(0)
            val rawDepth = output.toDepthBitmap()
            val upsampledDepth = if (rawDepth.width == source.width && rawDepth.height == source.height) {
                rawDepth
            } else {
                Bitmap.createScaledBitmap(rawDepth, source.width, source.height, true)
            }
            return DepthInferenceResult(
                modelName = assetName,
                inputWidth = inputLayout.width,
                inputHeight = inputLayout.height,
                outputWidth = rawDepth.width,
                outputHeight = rawDepth.height,
                sourceWidth = source.width,
                sourceHeight = source.height,
                inferenceMs = System.currentTimeMillis() - startMs,
                rawDepthMap = rawDepth,
                upsampledDepthMap = upsampledDepth
            )
        }

        private fun buildInputBuffer(source: Bitmap): ByteBuffer {
            val resized = Bitmap.createBitmap(inputLayout.width, inputLayout.height, Bitmap.Config.ARGB_8888)
            Canvas(resized).drawBitmap(
                source,
                null,
                android.graphics.Rect(0, 0, inputLayout.width, inputLayout.height),
                null
            )
            val pixels = IntArray(inputLayout.width * inputLayout.height)
            resized.getPixels(pixels, 0, inputLayout.width, 0, 0, inputLayout.width, inputLayout.height)
            resized.recycle()

            val buffer = ByteBuffer.allocateDirect(inputTensor.numBytes()).order(ByteOrder.nativeOrder())
            if (inputLayout.channelsFirst) {
                for (channel in 0 until 3) {
                    for (pixel in pixels) {
                        putInputValue(buffer, pixel.normalizedChannelValue(channel, normalization))
                    }
                }
            } else {
                for (pixel in pixels) {
                    putInputValue(buffer, pixel.normalizedChannelValue(0, normalization))
                    putInputValue(buffer, pixel.normalizedChannelValue(1, normalization))
                    putInputValue(buffer, pixel.normalizedChannelValue(2, normalization))
                }
            }
            buffer.position(0)
            return buffer
        }

        private fun putInputValue(buffer: ByteBuffer, realValue: Float) {
            when (inputType) {
                DataType.FLOAT32 -> buffer.putFloat(realValue)
                DataType.UINT8 -> {
                    val params = inputTensor.quantizationParams()
                    val q = if (params.scale > 0f) {
                        (realValue / params.scale + params.zeroPoint).roundToInt()
                    } else {
                        (realValue * 255f).roundToInt()
                    }
                    buffer.put(q.coerceIn(0, 255).toByte())
                }
                DataType.INT8 -> {
                    val params = inputTensor.quantizationParams()
                    val q = if (params.scale > 0f) {
                        (realValue / params.scale + params.zeroPoint).roundToInt()
                    } else {
                        ((realValue - 0.5f) * 255f).roundToInt()
                    }
                    buffer.put(q.coerceIn(-128, 127).toByte())
                }
                else -> throw IllegalArgumentException("Unsupported depth model input type: $inputType")
            }
        }

        private fun ByteBuffer.toDepthBitmap(): Bitmap {
            val values = FloatArray(outputElementCount)
            val params = outputTensor.quantizationParams()
            for (i in values.indices) {
                values[i] = when (outputType) {
                    DataType.FLOAT32 -> getFloat()
                    DataType.UINT8 -> {
                        val q = get().toInt() and 0xff
                        if (params.scale > 0f) (q - params.zeroPoint) * params.scale else q / 255f
                    }
                    DataType.INT8 -> {
                        val q = get().toInt()
                        if (params.scale > 0f) (q - params.zeroPoint) * params.scale else (q + 128) / 255f
                    }
                    else -> throw IllegalArgumentException("Unsupported depth model output type: $outputType")
                }
            }

            var minValue = Float.MAX_VALUE
            var maxValue = -Float.MAX_VALUE
            values.forEach { value ->
                if (value.isFinite()) {
                    minValue = min(minValue, value)
                    maxValue = max(maxValue, value)
                }
            }
            val range = (maxValue - minValue).takeIf { it > 0.0001f } ?: 1f
            val (height, width) = outputSize
            val pixels = IntArray(width * height)
            val usableCount = min(values.size, pixels.size)
            for (i in 0 until usableCount) {
                val normalized = ((values[i] - minValue) / range).coerceIn(0f, 1f)
                val gray = (normalized * 255f).roundToInt().coerceIn(0, 255)
                pixels[i] = Color.argb(255, gray, gray, gray)
            }
            return Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888)
        }

        private fun Int.normalizedChannelValue(channel: Int, normalization: Normalization): Float {
            val value = when (channel) {
                0 -> (this ushr 16) and 0xff
                1 -> (this ushr 8) and 0xff
                else -> this and 0xff
            }
            val zeroToOne = value / 255f
            return (zeroToOne - normalization.mean[channel]) / normalization.std[channel]
        }

        private data class InputLayout(
            val width: Int,
            val height: Int,
            val channelsFirst: Boolean
        )

        private data class Normalization(
            val mean: FloatArray,
            val std: FloatArray
        ) {
            companion object {
                fun forAsset(assetName: String): Normalization {
                    return if (assetName.startsWith("midas_v21_small_256", ignoreCase = true)) {
                        Normalization(
                            mean = floatArrayOf(0f, 0f, 0f),
                            std = floatArrayOf(1f, 1f, 1f)
                        )
                    } else if (assetName.contains("midas", ignoreCase = true)) {
                        Normalization(
                            mean = floatArrayOf(0.485f, 0.456f, 0.406f),
                            std = floatArrayOf(0.229f, 0.224f, 0.225f)
                        )
                    } else {
                        Normalization(
                            mean = floatArrayOf(0f, 0f, 0f),
                            std = floatArrayOf(1f, 1f, 1f)
                        )
                    }
                }
            }
        }

        private companion object {
            private fun resolveInputLayout(shape: IntArray): InputLayout {
                require(shape.size == 4) {
                    "Depth model must have a 4D image input; got ${shape.joinToString()}"
                }
                return when {
                    shape[3] == 3 -> InputLayout(
                        width = shape[2].coerceAtLeast(1),
                        height = shape[1].coerceAtLeast(1),
                        channelsFirst = false
                    )
                    shape[1] == 3 -> InputLayout(
                        width = shape[3].coerceAtLeast(1),
                        height = shape[2].coerceAtLeast(1),
                        channelsFirst = true
                    )
                    else -> throw IllegalArgumentException(
                        "Depth model input must be NHWC or NCHW RGB; got ${shape.joinToString()}"
                    )
                }
            }

            private fun resolveOutputSize(shape: IntArray): Pair<Int, Int> {
                return when (shape.size) {
                    4 -> when {
                        shape[3] == 1 -> shape[1].coerceAtLeast(1) to shape[2].coerceAtLeast(1)
                        shape[1] == 1 -> shape[2].coerceAtLeast(1) to shape[3].coerceAtLeast(1)
                        else -> shape[1].coerceAtLeast(1) to shape[2].coerceAtLeast(1)
                    }
                    3 -> {
                        if (shape[0] == 1) shape[1].coerceAtLeast(1) to shape[2].coerceAtLeast(1)
                        else shape[0].coerceAtLeast(1) to shape[1].coerceAtLeast(1)
                    }
                    2 -> shape[0].coerceAtLeast(1) to shape[1].coerceAtLeast(1)
                    else -> {
                        val edge = sqrt(shape.fold(1) { acc, dim -> acc * dim.coerceAtLeast(1) }.toFloat())
                            .roundToInt()
                            .coerceAtLeast(1)
                        edge to edge
                    }
                }
            }
        }
    }
}
