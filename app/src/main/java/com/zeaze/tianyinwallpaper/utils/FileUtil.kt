package com.zeaze.tianyinwallpaper.utils

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import android.media.MediaMetadataRetriever
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.InputStreamReader
import java.io.OutputStream
import java.io.OutputStreamWriter

object FileUtil {
    var dataPath = "data.json"
    var wallpaperPath = "wallpaper.json"
    var wallpaperFilePath = "/wallpaper/"
    var width = 9
    var height = 16

    fun ImageSizeCompress(context: Context, uri: Uri): Bitmap? {
        var stream: InputStream? = null
        var inputStream: InputStream? = null
        return try {
            stream = context.contentResolver.openInputStream(uri)
            BitmapFactory.decodeStream(stream, null, BitmapFactory.Options())
        } catch (e: Exception) {
            e.printStackTrace()
            null
        } finally {
            try {
                inputStream?.close()
                stream?.close()
            } catch (e: IOException) {
                e.printStackTrace()
            }
        }
    }

    fun getVideoThumbnailFromUri(context: Context, uri: Uri): Bitmap? {
        var retriever: MediaMetadataRetriever? = null
        return try {
            retriever = MediaMetadataRetriever()
            retriever.setDataSource(context, uri)
            retriever.getFrameAtTime(0, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
        } catch (e: Exception) {
            e.printStackTrace()
            null
        } finally {
            if (retriever != null) {
                try {
                    retriever.release()
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
        }
    }

    fun bitmap2Path(bitmap: Bitmap, path: String): Bitmap {
        var target = bitmap
        if (target.width * height == width * target.height) {
            // no-op
        } else if (target.width * height > width * target.height) {
            val matrix = Matrix()
            if (target.width > width) {
                matrix.postScale(height.toFloat() / target.height, height.toFloat() / target.height)
            }
            target = Bitmap.createBitmap(
                target,
                (target.width - target.height * width / height) / 2,
                0,
                target.height * width / height,
                target.height,
                matrix,
                true
            )
        } else {
            val matrix = Matrix()
            if (target.width > width) {
                matrix.postScale(width.toFloat() / target.width, width.toFloat() / target.width)
            }
            target = Bitmap.createBitmap(
                target,
                0,
                (target.height - target.width * height / width) / 2,
                target.width,
                target.width * height / width,
                matrix,
                true
            )
        }
        try {
            val os: OutputStream = FileOutputStream(path)
            target.compress(Bitmap.CompressFormat.PNG, 100, os)
            os.flush()
            os.close()
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return target
    }

    fun uri2Path(context: Context, uri: Uri, path: String): String {
        var inputStream: InputStream? = null
        var outputStream: OutputStream? = null
        try {
            inputStream = context.contentResolver.openInputStream(uri)
            outputStream = FileOutputStream(path)
            val buffer = ByteArray(1024)
            var len = inputStream?.read(buffer) ?: -1
            while (len != -1) {
                outputStream.write(buffer, 0, len)
                len = inputStream?.read(buffer) ?: -1
            }
            inputStream?.close()
            outputStream.close()
        } catch (e: Exception) {
            e.printStackTrace()
        } finally {
            try {
                inputStream?.close()
                outputStream?.close()
            } catch (e: IOException) {
                e.printStackTrace()
            }
        }
        return path
    }

    fun save(context: Context, data: String, dataPath: String, onSave: OnSave) {
        var writer: BufferedWriter? = null
        try {
            val out = FileOutputStream(context.getExternalFilesDir(null).toString() + "/" + dataPath)
            writer = BufferedWriter(OutputStreamWriter(out))
            writer.write(data)
        } catch (e: IOException) {
            e.printStackTrace()
        } finally {
            try {
                writer?.close()
            } catch (e: IOException) {
                e.printStackTrace()
            }
        }
        onSave.onSave()
    }

    fun loadData(context: Context, dataPath: String): String {
        var reader: BufferedReader? = null
        val content = StringBuilder()
        try {
            val `in` = FileInputStream(context.getExternalFilesDir(null).toString() + "/" + dataPath)
            reader = BufferedReader(InputStreamReader(`in`))
            var line: String?
            while (reader.readLine().also { line = it } != null) {
                content.append(line)
            }
        } catch (e: IOException) {
            e.printStackTrace()
        } finally {
            if (reader != null) {
                try {
                    reader.close()
                } catch (e: IOException) {
                    e.printStackTrace()
                }
            }
        }
        var s = content.toString()
        if (s == "") {
            s = "[]"
        }
        return s
    }

    interface OnSave {
        fun onSave()
    }
}
