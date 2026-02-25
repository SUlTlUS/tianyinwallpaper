@file:Suppress(names = ["DEPRECATION", "SpellCheckingInspection"])

package com.zeaze.tianyinwallpaper.bitmapstomp4

import android.graphics.Bitmap
import android.media.*
import android.os.Build
import android.os.Looper
import android.util.Log
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import kotlin.concurrent.thread

/**
 * @author YaphetZhao
 * @email yaphetzhao@gmail.com
 * @data 2020-07-30
 * @wechat yaphetzhao92
 */
class YapVideoEncoder(
    private val provider: IYapVideoProvider<Bitmap>,
    private val outFile: File,
    private val frameRate: Int = 60
) {
    private var mediaCodec: MediaCodec? = null
    private var mediaMuxer: MediaMuxer? = null
    private var mMuxerStarted = false
    private var isRunning = false
    private var mTrackIndex = 0
    private var colorFormat = 0
    private val defaultTimeOutUs = 10000L

    private val colorFormats: IntArray
        get() {
            val numCodecs = MediaCodecList.getCodecCount()
            for (i in 0 until numCodecs) {
                val info = MediaCodecList.getCodecInfoAt(i)
                if (!info.isEncoder) continue
                if (info.supportedTypes.any { it.equals("video/avc", ignoreCase = true) }) {
                    return info.getCapabilitiesForType("video/avc").colorFormats
                }
            }
            throw IllegalStateException("No AVC encoder found")
        }

    private fun init(width: Int, height: Int) {
        val formats = colorFormats
        colorFormat = formats.firstOrNull {
            it == MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420SemiPlanar ||
                    it == MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Planar ||
                    it == MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420PackedSemiPlanar ||
                    it == MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420PackedPlanar
        } ?: MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420SemiPlanar

        val widthFix = if (width % 2 != 0) width - 1 else width
        val heightFix = if (height % 2 != 0) height - 1 else height

        val mediaFormat = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, widthFix, heightFix).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, colorFormat)
            setInteger(MediaFormat.KEY_BIT_RATE, widthFix * heightFix)
            setInteger(MediaFormat.KEY_FRAME_RATE, frameRate)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 10)
        }

        try {
            mediaCodec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
            if (!outFile.exists()) {
                outFile.createNewFile()
            }
            mediaMuxer = MediaMuxer(outFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        } catch (e: IOException) {
            e.printStackTrace()
        }
        mediaCodec?.run {
            configure(mediaFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            start()
        }
        isRunning = true
    }

    fun start() {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            thread { start() }
            return
        }
        try {
            if (provider.size() > 0) {
                val bitmap = provider.next()
                init(bitmap.width, bitmap.height)
                run(bitmap)
            }
        } finally {
            finish()
        }
    }

    private fun finish() {
        isRunning = false
        mediaCodec?.run {
            try {
                stop()
                release()
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
        mediaCodec = null
        mediaMuxer?.run {
            try {
                if (mMuxerStarted) {
                    stop()
                    release()
                }
            } catch (e: Exception) {
                provider.progress(-1f)
                e.printStackTrace()
            }
        }
        mediaMuxer = null
    }

    private fun run(bitmapFirst: Bitmap?) {
        var bitmap = bitmapFirst
        isRunning = true
        var generateIndex: Long = 0
        val info = MediaCodec.BufferInfo()

        while (isRunning) {
            val inputBufferIndex = mediaCodec!!.dequeueInputBuffer(defaultTimeOutUs)
            if (inputBufferIndex >= 0) {
                val ptsUsec = computePresentationTime(generateIndex)
                provider.progress(generateIndex / provider.size().toFloat())

                if (generateIndex >= provider.size()) {
                    mediaCodec!!.queueInputBuffer(inputBufferIndex, 0, 0, ptsUsec, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                    isRunning = false
                    drainEncoder(true, info)
                } else {
                    val currentBitmap = bitmap ?: provider.next()
                    bitmap = null

                    val widthFix = if (currentBitmap.width % 2 != 0) currentBitmap.width - 1 else currentBitmap.width
                    val heightFix = if (currentBitmap.height % 2 != 0) currentBitmap.height - 1 else currentBitmap.height

                    val input = getNV12(widthFix, heightFix, currentBitmap)
                    val inputBuffer = mediaCodec!!.getInputBuffer(inputBufferIndex)

                    inputBuffer?.run {
                        clear()
                        put(input)
                    }
                    mediaCodec!!.queueInputBuffer(inputBufferIndex, 0, input.size, ptsUsec, 0)
                    drainEncoder(false, info)
                }
                generateIndex++
            } else {
                try {
                    Thread.sleep(50)
                } catch (e: InterruptedException) {
                    provider.progress(-1f)
                }
            }
        }
    }

    private fun computePresentationTime(frameIndex: Long): Long {
        return 132 + frameIndex * 1000000 / frameRate
    }

    private fun drainEncoder(endOfStream: Boolean, bufferInfo: MediaCodec.BufferInfo) {
        if (endOfStream) {
            try {
                mediaCodec?.signalEndOfInputStream()
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }

        while (true) {
            val encoderStatus = mediaCodec!!.dequeueOutputBuffer(bufferInfo, defaultTimeOutUs)
            when (encoderStatus) {
                MediaCodec.INFO_TRY_AGAIN_LATER -> if (!endOfStream) break
                MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    if (mMuxerStarted) throw RuntimeException("format changed twice")
                    mTrackIndex = mediaMuxer!!.addTrack(mediaCodec!!.outputFormat)
                    mediaMuxer!!.start()
                    mMuxerStarted = true
                }
                else -> {
                    if (encoderStatus < 0) {
                        Log.d("YapVideoEncoder", "unexpected result from encoder.dequeueOutputBuffer: $encoderStatus")
                    } else {
                        val outputBuffer = mediaCodec!!.getOutputBuffer(encoderStatus)
                            ?: throw RuntimeException("encoderOutputBuffer $encoderStatus was null")

                        if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) {
                            bufferInfo.size = 0
                        }

                        if (bufferInfo.size != 0) {
                            if (!mMuxerStarted) Log.d("YapVideoEncoder", "error:muxer hasn't started")
                            outputBuffer.position(bufferInfo.offset)
                            outputBuffer.limit(bufferInfo.offset + bufferInfo.size)
                            try {
                                mediaMuxer!!.writeSampleData(mTrackIndex, outputBuffer, bufferInfo)
                            } catch (e: Exception) {
                                e.printStackTrace()
                            }
                        }
                        mediaCodec!!.releaseOutputBuffer(encoderStatus, false)
                        if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                            if (!endOfStream) {
                                Log.d("YapVideoEncoder", "reached end of stream unexpectedly")
                                provider.progress(-1f)
                            }
                            break
                        }
                    }
                }
            }
        }
    }

    private fun getNV12(inputWidth: Int, inputHeight: Int, scaled: Bitmap): ByteArray {
        val argb = IntArray(inputWidth * inputHeight)
        scaled.getPixels(argb, 0, inputWidth, 0, 0, inputWidth, inputHeight)
        val yuv = ByteArray(inputWidth * inputHeight * 3 / 2)

        when (colorFormat) {
            MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420SemiPlanar -> encodeYUV420SP(yuv, argb, inputWidth, inputHeight)
            MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Planar -> encodeYUV420P(yuv, argb, inputWidth, inputHeight)
            MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420PackedSemiPlanar -> encodeYUV420PSP(yuv, argb, inputWidth, inputHeight)
            MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420PackedPlanar -> encodeYUV420PP(yuv, argb, inputWidth, inputHeight)
        }
        return yuv
    }

    private fun encodeYUVIndex(r: Int, g: Int, b: Int): Int = (66 * r + 129 * g + 25 * b + 128 shr 8) + 16
    private fun encodeUIndex(r: Int, g: Int, b: Int): Int = (112 * r - 94 * g - 18 * b + 128 shr 8) + 128
    private fun encodeVIndex(r: Int, g: Int, b: Int): Int = (-38 * r - 74 * g + 112 * b + 128 shr 8) + 128
    private fun Int.clamp(): Byte = this.coerceIn(0, 255).toByte()

    private fun encodeYUV420SP(yuv420sp: ByteArray, argb: IntArray, width: Int, height: Int) {
        val frameSize = width * height
        var yIndex = 0
        var uvIndex = frameSize
        for (j in 0 until height) {
            for (i in 0 until width) {
                val index = j * width + i
                val r = (argb[index] shr 16) and 0xff
                val g = (argb[index] shr 8) and 0xff
                val b = argb[index] and 0xff

                yuv420sp[yIndex++] = encodeYUVIndex(r, g, b).clamp()
                if (j % 2 == 0 && i % 2 == 0) {
                    yuv420sp[uvIndex++] = encodeVIndex(r, g, b).clamp()
                    yuv420sp[uvIndex++] = encodeUIndex(r, g, b).clamp()
                }
            }
        }
    }

    private fun encodeYUV420P(yuv420: ByteArray, argb: IntArray, width: Int, height: Int) {
        val frameSize = width * height
        var yIndex = 0
        var uIndex = frameSize
        var vIndex = frameSize + frameSize / 4
        for (j in 0 until height) {
            for (i in 0 until width) {
                val index = j * width + i
                val r = (argb[index] shr 16) and 0xff
                val g = (argb[index] shr 8) and 0xff
                val b = argb[index] and 0xff

                yuv420[yIndex++] = encodeYUVIndex(r, g, b).clamp()
                if (j % 2 == 0 && i % 2 == 0) {
                    yuv420[uIndex++] = encodeUIndex(r, g, b).clamp()
                    yuv420[vIndex++] = encodeVIndex(r, g, b).clamp()
                }
            }
        }
    }

    private fun encodeYUV420PSP(yuv: ByteArray, argb: IntArray, width: Int, height: Int) {
        var yIndex = 0
        for (j in 0 until height) {
            for (i in 0 until width) {
                val index = j * width + i
                val r = (argb[index] shr 16) and 0xff
                val g = (argb[index] shr 8) and 0xff
                val b = argb[index] and 0xff

                yuv[yIndex++] = encodeYUVIndex(r, g, b).clamp()
                if (j % 2 == 0 && i % 2 == 0) {
                    yuv[yIndex + 1] = encodeVIndex(r, g, b).clamp()
                    yuv[yIndex + 3] = encodeUIndex(r, g, b).clamp()
                }
                if (i % 2 == 0) yIndex++
            }
        }
    }

    private fun encodeYUV420PP(yuv: ByteArray, argb: IntArray, width: Int, height: Int) {
        var yIndex = 0
        var vIndex = yuv.size / 2
        for (j in 0 until height) {
            for (i in 0 until width) {
                val index = j * width + i
                val r = (argb[index] shr 16) and 0xff
                val g = (argb[index] shr 8) and 0xff
                val b = argb[index] and 0xff

                val y = encodeYUVIndex(r, g, b).clamp()
                val u = encodeUIndex(r, g, b).clamp()
                val v = encodeVIndex(r, g, b).clamp()

                when {
                    j % 2 == 0 && i % 2 == 0 -> {
                        yuv[yIndex++] = y
                        yuv[yIndex + 1] = v
                        yuv[vIndex + 1] = u
                        yIndex++
                    }
                    j % 2 == 0 && i % 2 == 1 -> yuv[yIndex++] = y
                    j % 2 == 1 && i % 2 == 0 -> {
                        yuv[vIndex++] = y
                        vIndex++
                    }
                    else -> yuv[vIndex++] = y
                }
            }
        }
    }

}