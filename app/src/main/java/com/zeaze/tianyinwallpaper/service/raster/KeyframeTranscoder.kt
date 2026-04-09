package com.zeaze.tianyinwallpaper.service.raster

import android.content.Context
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.os.Build
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

        // ── 2. 设置 Encoder（全关键帧）──
        val supportsCQ = isCQSupported()
        Log.w(TAG, "Encoder CQ mode supported: $supportsCQ")

        val encFormat = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height).apply {
            setInteger(MediaFormat.KEY_FRAME_RATE, fps)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 0)   // ★ 全关键帧
            setInteger(MediaFormat.KEY_COLOR_FORMAT,
                MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)

            // 尽量透传输入色彩元数据，减少 SDR/HDR 与色域误判导致的偏色。
            copyColorKeyIfPresent(inputFormat, this, MediaFormat.KEY_COLOR_STANDARD)
            copyColorKeyIfPresent(inputFormat, this, MediaFormat.KEY_COLOR_TRANSFER)
            copyColorKeyIfPresent(inputFormat, this, MediaFormat.KEY_COLOR_RANGE)

            if (supportsCQ && Build.VERSION.SDK_INT >= 28) {
                // ★ CQ 模式：直接用 QP 控制质量，不依赖码率
                // QP 范围 0~51，值越小质量越高。18 ≈ 视觉无损
                setInteger(MediaFormat.KEY_BITRATE_MODE,
                    MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CQ)
                setInteger(MediaFormat.KEY_QUALITY, 51)
                // 部分设备 CQ 模式仍要求设置 bitrate（作为上限提示）
                setInteger(MediaFormat.KEY_BIT_RATE, width * height * 20)
            } else {
                // 降级：高码率 VBR
                setInteger(MediaFormat.KEY_BIT_RATE, width * height * 60)
            }
        }

        val encoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
        encoder.configure(encFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        val inputSurface = encoder.createInputSurface()
        encoder.start()

        Log.w(TAG, "Encoder request color: ${describeColorFormat(encFormat)}")

        // ── 3. 设置 Decoder（输出到 Encoder 的 InputSurface）──
        val decoder = MediaCodec.createDecoderByType(mime)
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
            extractor.release()

            tmpFile.renameTo(outFile)
            Log.w(TAG, "Transcode OK: $frameCount frames → ${outFile.absolutePath} (${outFile.length() / 1024}KB)")
            return outFile.absolutePath

        } catch (e: Exception) {
            Log.e(TAG, "Transcode failed", e)
            safeRelease { decoder.stop() }
            safeRelease { decoder.release() }
            safeRelease { encoder.stop() }
            safeRelease { encoder.release() }
            safeRelease { inputSurface.release() }
            safeRelease { if (muxerStarted) muxer.stop() }
            safeRelease { muxer.release() }
            safeRelease { extractor.release() }
            tmpFile.delete()
            return null
        }
    }

    /**
     * 检查设备的 H.264 编码器是否支持 CQ（Constant Quality / QP）模式
     */
    private fun isCQSupported(): Boolean {
        try {
            val codecList = MediaCodecList(MediaCodecList.REGULAR_CODECS)
            for (info in codecList.codecInfos) {
                if (!info.isEncoder) continue
                if (!info.supportedTypes.any { it.equals("video/avc", ignoreCase = true) }) continue
                val caps = info.getCapabilitiesForType("video/avc")
                if (caps?.encoderCapabilities?.isBitrateModeSupported(
                        MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CQ) == true) {
                    return true
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "isCQSupported check failed", e)
        }
        return false
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







