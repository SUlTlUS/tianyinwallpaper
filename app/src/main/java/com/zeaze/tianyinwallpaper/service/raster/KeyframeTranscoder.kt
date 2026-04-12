package com.zeaze.tianyinwallpaper.service.raster

import android.content.Context
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.util.Log
import java.io.File

/**
 * 视频全关键帧转码器
 *
 * 将任意视频转码为 GOP=1（每帧都是关键帧）的 H.264 视频，
 * 使得 MediaExtractor.seekTo(SEEK_TO_PREVIOUS_SYNC) 可以精确定位到任意帧，
 * 从而实现零延迟的前后帧切换。
 *
 * 转码后的视频存储在应用内部目录 files/raster_kf/ 下。
 */
class KeyframeTranscoder(private val context: Context) {

    companion object {
        private const val TAG = "KFTranscoder"
        private const val DIR = "raster_kf"
        private const val TIMEOUT_US = 10_000L
        /** 硬件编码器码率乘数：width * height * 60 bps */
        private const val HW_BIT_RATE_MULT = 60
        /** 软件编码器码率乘数：width * height * 30 bps（软件编码效率较低，降低码率减小体积） */
        private const val SW_BIT_RATE_MULT = 10
    }

    interface Listener {
        fun onProgress(progress: Float)
        fun onComplete(outputPath: String)
        fun onError(message: String)
    }

    /**
     * 获取已缓存的转码视频路径，如果尚未转码则返回 null
     */
    fun getCachedPath(sourceUri: String): String? {
        val outFile = getOutputFile(sourceUri)
        return if (outFile.exists() && outFile.length() > 0) outFile.absolutePath else null
    }

    /**
     * 清除所有转码缓存（码率/参数调整后需要重新转码时调用）
     */
    fun clearCache() {
        val dir = File(context.filesDir, DIR)
        if (dir.exists()) {
            dir.listFiles()?.forEach { it.delete() }
            Log.w(TAG, "clearCache: deleted all cached keyframe videos")
        }
    }

    /**
     * 删除指定视频 URI 的转码缓存
     */
    fun deleteCacheFor(videoUri: String) {
        val file = getOutputFile(videoUri)
        if (file.exists()) {
            file.delete()
            Log.w(TAG, "deleteCacheFor: deleted ${file.name}")
        }
    }

    /**
     * 删除指定视频 URI 以外的所有转码缓存
     */
    fun deleteOtherCaches(keepVideoUri: String) {
        val keepFile = getOutputFile(keepVideoUri)
        val dir = File(context.filesDir, DIR)
        if (dir.exists()) {
            dir.listFiles()?.forEach {
                if (it.name != keepFile.name) {
                    it.delete()
                    Log.w(TAG, "deleteOtherCaches: deleted ${it.name}")
                }
            }
        }
    }

    /**
     * 在后台线程异步转码
     */
    fun transcodeAsync(sourceUri: String, listener: Listener) {
        Thread(Runnable {
            try {
                val path = doTranscode(sourceUri) { progress ->
                    listener.onProgress(progress)
                }
                if (path != null) {
                    listener.onComplete(path)
                } else {
                    listener.onError("Transcode returned null")
                }
            } catch (e: Exception) {
                Log.e(TAG, "transcode error", e)
                listener.onError(e.message ?: "Unknown error")
            }
        }, "KFTranscode").start()
    }

    private fun getOutputFile(sourceUri: String): File {
        val dir = File(context.filesDir, DIR)
        dir.mkdirs()
        // 用 URI 的稳定 hash 作为文件名
        val hash = sourceUri.hashCode().and(0x7FFFFFFF).toString(16).padStart(8, '0')
        return File(dir, "kf_$hash.mp4")
    }

    /**
     * 同步转码（在当前线程执行，适合在后台线程调用）
     *
     * 原理：
     *   Decoder 解码原视频 → 输出到 Encoder 的 InputSurface → Encoder 以 I_FRAME_INTERVAL=0 编码 → Muxer 写文件
     *
     * 策略：优先使用硬件编码器，若崩溃则降级到软件编码器并禁用高阶配置。
     */
    private fun doTranscode(sourceUri: String, onProgress: (Float) -> Unit): String? {
        val outFile = getOutputFile(sourceUri)
        if (outFile.exists() && outFile.length() > 0) return outFile.absolutePath

        val tmpFile = File(outFile.parentFile!!, outFile.name + ".tmp")
        if (tmpFile.exists()) tmpFile.delete()

        // ── 1. 设置 MediaExtractor ──
        val extractor = MediaExtractor()
        try {
            if (sourceUri.startsWith("content://")) {
                extractor.setDataSource(context, android.net.Uri.parse(sourceUri), null)
            } else {
                extractor.setDataSource(sourceUri)
            }
        } catch (e: Exception) {
            Log.e(TAG, "setDataSource failed: $sourceUri", e)
            extractor.release()
            return null
        }

        // 查找视频轨道
        var trackIndex = -1
        var inputFormat: MediaFormat? = null
        for (i in 0 until extractor.trackCount) {
            val fmt = extractor.getTrackFormat(i)
            if (fmt.getString(MediaFormat.KEY_MIME)?.startsWith("video/") == true) {
                trackIndex = i; inputFormat = fmt; break
            }
        }
        if (trackIndex < 0 || inputFormat == null) {
            Log.e(TAG, "No video track found")
            extractor.release()
            return null
        }
        extractor.selectTrack(trackIndex)

        val mime = inputFormat.getString(MediaFormat.KEY_MIME)!!
        val width = inputFormat.getInteger(MediaFormat.KEY_WIDTH).let { if (it % 2 != 0) it - 1 else it }
        val height = inputFormat.getInteger(MediaFormat.KEY_HEIGHT).let { if (it % 2 != 0) it - 1 else it }
        val fps = if (inputFormat.containsKey(MediaFormat.KEY_FRAME_RATE))
            inputFormat.getInteger(MediaFormat.KEY_FRAME_RATE) else 30
        val durationUs = if (inputFormat.containsKey(MediaFormat.KEY_DURATION))
            inputFormat.getLong(MediaFormat.KEY_DURATION) else 0L

        Log.w(TAG, "Input: ${width}x${height}, fps=$fps, duration=${durationUs / 1000}ms, mime=$mime")
        Log.w(TAG, "Input color: ${describeColorFormat(inputFormat)}")

        try {
            // ── 2. 先尝试硬件编码器，崩溃后降级到软件编码器 ──
            val result = transcodeWithEncoder(
                extractor, inputFormat, mime, width, height, fps, durationUs, tmpFile, onProgress,
                encoderName = null, useAdvancedConfig = true, forceSwDecoder = false
            )
            if (result != null) return result

            // 硬件编码器失败，尝试软件编码器 + 软件解码器（禁用高阶配置）
            val swEncoderName = findSoftwareEncoder() ?: run {
                Log.e(TAG, "No software H.264 encoder available")
                return null
            }
            Log.w(TAG, "Hardware encoder failed, falling back to software encoder: $swEncoderName")
            // 重置 extractor 到起始位置
            extractor.seekTo(0, MediaExtractor.SEEK_TO_CLOSEST_SYNC)

            return transcodeWithEncoder(
                extractor, inputFormat, mime, width, height, fps, durationUs, tmpFile, onProgress,
                encoderName = swEncoderName, useAdvancedConfig = false, forceSwDecoder = true
            )
        } finally {
            extractor.release() // ★ 无论成功失败都释放，避免泄漏
        }
    }

    /**
     * 使用指定编码器执行转码。
     *
     * @param encoderName 编码器名称，null 表示由系统选择（默认硬件）
     * @param useAdvancedConfig 是否启用高阶配置（色彩元数据透传等），软件编码器降级时应为 false
     * @param forceSwDecoder 是否强制使用软件解码器，硬件服务崩溃时需要同步降级解码器
     */
    private fun transcodeWithEncoder(
        extractor: MediaExtractor,
        inputFormat: MediaFormat,
        mime: String,
        width: Int,
        height: Int,
        fps: Int,
        durationUs: Long,
        tmpFile: File,
        onProgress: (Float) -> Unit,
        encoderName: String?,
        useAdvancedConfig: Boolean,
        forceSwDecoder: Boolean = false
    ): String? {
        if (tmpFile.exists()) tmpFile.delete()

        // ── 2. 设置 Encoder（全关键帧，高码率 VBR）──
        // 软件编码器码率可独立调整；硬件编码器默认 ×60，软件编码器默认 ×30
        val bitRateMultiplier = if (encoderName != null) SW_BIT_RATE_MULT else HW_BIT_RATE_MULT
        val encFormat = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height).apply {
            setInteger(MediaFormat.KEY_FRAME_RATE, fps)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 0)   // ★ 全关键帧
            setInteger(MediaFormat.KEY_COLOR_FORMAT,
                MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(
                MediaFormat.KEY_BIT_RATE,
                if (encoderName != null)
                    (width * height * bitRateMultiplier).coerceAtMost(20_000_000)
                else
                    width * height * bitRateMultiplier
            )

            // 高阶配置：透传输入色彩元数据，减少 SDR/HDR 与色域误判导致的偏色。
            // 软件编码器降级时禁用，避免兼容性问题。
            if (useAdvancedConfig) {
                copyColorKeyIfPresent(inputFormat, this, MediaFormat.KEY_COLOR_STANDARD)
                copyColorKeyIfPresent(inputFormat, this, MediaFormat.KEY_COLOR_TRANSFER)
                copyColorKeyIfPresent(inputFormat, this, MediaFormat.KEY_COLOR_RANGE)
            }
        }

        val encoder = if (encoderName != null)
            MediaCodec.createByCodecName(encoderName)
        else
            MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)

        try {
            encoder.configure(encFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        } catch (e: Exception) {
            Log.e(TAG, "Encoder configure failed: ${encoderName ?: "default"}", e)
            safeRelease { encoder.release() }
            return null
        }

        val inputSurface = encoder.createInputSurface()
        encoder.start()

        Log.w(TAG, "Encoder: ${encoderName ?: "hardware-default"}, request color: ${describeColorFormat(encFormat)}")

        // ── 3. 设置 Decoder（输出到 Encoder 的 InputSurface）──
        val decoder = if (forceSwDecoder) {
            findSoftwareDecoder(mime)?.let { name ->
                Log.w(TAG, "Using software decoder: $name")
                MediaCodec.createByCodecName(name)
            } ?: run {
                Log.w(TAG, "No software decoder found, falling back to default")
                MediaCodec.createDecoderByType(mime)
            }
        } else {
            MediaCodec.createDecoderByType(mime)
        }
        decoder.configure(inputFormat, inputSurface, null, 0)
        decoder.start()

        // ── 4. 设置 Muxer ──
        val muxer = MediaMuxer(tmpFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        var muxerTrack = -1
        var muxerStarted = false

        val decInfo = MediaCodec.BufferInfo()
        val encInfo = MediaCodec.BufferInfo()
        var inputEos = false
        var decoderEos = false
        var encoderDone = false
        var frameCount = 0

        try {
            while (!encoderDone) {

                // ── A. 先排空 Encoder 输出（防止 BufferQueue 满导致死锁）──
                while (true) {
                    val idx = encoder.dequeueOutputBuffer(encInfo, 0)  // 非阻塞
                    when {
                        idx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                            muxerTrack = muxer.addTrack(encoder.outputFormat)
                            muxer.start()
                            muxerStarted = true
                            Log.d(TAG, "Muxer started, format: ${encoder.outputFormat}")
                            Log.w(TAG, "Encoder output color: ${describeColorFormat(encoder.outputFormat)}")
                        }
                        idx >= 0 -> {
                            val buf = encoder.getOutputBuffer(idx)!!
                            if (encInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) {
                                encInfo.size = 0
                            }
                            if (encInfo.size > 0 && muxerStarted) {
                                buf.position(encInfo.offset)
                                buf.limit(encInfo.offset + encInfo.size)
                                muxer.writeSampleData(muxerTrack, buf, encInfo)
                                frameCount++
                            }
                            encoder.releaseOutputBuffer(idx, false)
                            if (encInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                                encoderDone = true
                            }
                            // 报告进度
                            if (durationUs > 0 && encInfo.presentationTimeUs > 0) {
                                onProgress((encInfo.presentationTimeUs.toFloat() / durationUs).coerceIn(0f, 1f))
                            }
                        }
                        else -> break
                    }
                }
                if (encoderDone) break

                // ── B. 喂入 Decoder 压缩数据 ──
                if (!inputEos) {
                    val inIdx = decoder.dequeueInputBuffer(TIMEOUT_US)
                    if (inIdx >= 0) {
                        val buf = decoder.getInputBuffer(inIdx)!!
                        val size = extractor.readSampleData(buf, 0)
                        if (size >= 0) {
                            decoder.queueInputBuffer(inIdx, 0, size, extractor.sampleTime, 0)
                            extractor.advance()
                        } else {
                            decoder.queueInputBuffer(inIdx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputEos = true
                        }
                    }
                }

                // ── C. 取出 Decoder 解码帧 → 渲染到 Encoder InputSurface ──
                if (!decoderEos) {
                    val outIdx = decoder.dequeueOutputBuffer(decInfo, TIMEOUT_US)
                    if (outIdx >= 0) {
                        val eos = (decInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0
                        // render=true 把帧渲染到 Encoder 的 InputSurface
                        decoder.releaseOutputBuffer(outIdx, !eos)
                        if (eos) {
                            decoderEos = true
                            encoder.signalEndOfInputStream()
                        }
                    }
                }
            }

            // 成功 → 清理并重命名
            decoder.stop(); decoder.release()
            encoder.stop(); encoder.release()
            inputSurface.release()
            if (muxerStarted) muxer.stop()
            muxer.release()

            val outFile = File(tmpFile.parentFile!!, tmpFile.name.removeSuffix(".tmp"))
            tmpFile.renameTo(outFile)
            Log.w(TAG, "Transcode OK: $frameCount frames → ${outFile.absolutePath} (${outFile.length() / 1024}KB)")
            return outFile.absolutePath

        } catch (e: Exception) {
            Log.e(TAG, "Transcode failed with encoder: ${encoderName ?: "hardware-default"}", e)
            safeRelease { decoder.stop() }
            safeRelease { decoder.release() }
            safeRelease { encoder.stop() }
            safeRelease { encoder.release() }
            safeRelease { inputSurface.release() }
            safeRelease { if (muxerStarted) muxer.stop() }
            safeRelease { muxer.release() }
            tmpFile.delete()
            return null
        }
    }

    /**
     * 查找设备上的软件 H.264 编码器（不依赖硬件编解码器，避免硬件崩溃）
     */
    private fun findSoftwareEncoder(): String? {
        return findSoftwareCodec("video/avc", encoder = true)
    }

    /**
     * 查找设备上的软件解码器（不依赖硬件编解码器，避免硬件服务崩溃污染）
     */
    private fun findSoftwareDecoder(mime: String): String? {
        return findSoftwareCodec(mime, encoder = false)
    }

    /**
     * 通用：查找软件编解码器
     */
    private fun findSoftwareCodec(mime: String, encoder: Boolean): String? {
        try {
            val codecList = MediaCodecList(MediaCodecList.REGULAR_CODECS)
            // 优先匹配典型软件编解码器名称
            for (info in codecList.codecInfos) {
                if (info.isEncoder != encoder) continue
                if (!info.supportedTypes.any { it.equals(mime, ignoreCase = true) }) continue
                val name = info.name.lowercase()
                if (name.contains("omx.google") || name.contains("c2.android")) {
                    return info.name
                }
            }
            // 回退：排除已知硬件前缀，选剩余的
            for (info in codecList.codecInfos) {
                if (info.isEncoder != encoder) continue
                if (!info.supportedTypes.any { it.equals(mime, ignoreCase = true) }) continue
                val name = info.name.lowercase()
                if (!name.startsWith("omx.qcom") && !name.startsWith("omx.mtk") &&
                    !name.startsWith("omx.samsung") && !name.startsWith("omx.hisi") &&
                    !name.startsWith("omx.exynos") && !name.startsWith("c2.qcom") &&
                    !name.startsWith("c2.mtk") && !name.startsWith("c2.samsung") &&
                    !name.startsWith("c2.hisi")) {
                    return info.name
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "findSoftwareCodec(mime=$mime, encoder=$encoder) failed", e)
        }
        return null
    }

    private fun describeColorFormat(format: MediaFormat): String {
        val standard = getFormatIntOrNull(format, MediaFormat.KEY_COLOR_STANDARD)
        val transfer = getFormatIntOrNull(format, MediaFormat.KEY_COLOR_TRANSFER)
        val range = getFormatIntOrNull(format, MediaFormat.KEY_COLOR_RANGE)
        return "standard=$standard, transfer=$transfer, range=$range"
    }

    private fun copyColorKeyIfPresent(source: MediaFormat, target: MediaFormat, key: String) {
        val value = getFormatIntOrNull(source, key) ?: return
        try {
            target.setInteger(key, value)
        } catch (e: Exception) {
            Log.w(TAG, "Skip unsupported color key $key=$value", e)
        }
    }

    private fun getFormatIntOrNull(format: MediaFormat, key: String): Int? {
        if (!format.containsKey(key)) return null
        return try {
            format.getInteger(key)
        } catch (_: Exception) {
            null
        }
    }

    private inline fun safeRelease(block: () -> Unit) {
        try { block() } catch (_: Exception) {}
    }
}







