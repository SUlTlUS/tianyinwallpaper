package com.zeaze.tianyinwallpaper.utils

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import android.media.MediaMetadataRetriever
import java.io.*

object FileUtil {
    var dataPath = "data.json"
    var wallpaperPath = "wallpaper.json"
    var wallpaperFilePath = "/wallpaper/"
    var width = 9
    var height = 16

    fun ImageSizeCompress(context: Context, uri: Uri): Bitmap? {
        return try {
            context.contentResolver.openInputStream(uri)?.use { stream ->
                BitmapFactory.decodeStream(stream, null, BitmapFactory.Options())
            }
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    fun getVideoThumbnailFromUri(context: Context, uri: Uri): Bitmap? {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(context, uri)
            retriever.getFrameAtTime(0, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
        } catch (e: Exception) {
            e.printStackTrace()
            null
        } finally {
            try {
                retriever.release()
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    fun bitmap2Path(bitmap: Bitmap, path: String): Bitmap {
        var target = bitmap
        if (target.width * height != width * target.height) {
            val matrix = Matrix()
            if (target.width * height > width * target.height) {
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
        }
        try {
            FileOutputStream(path).use { os ->
                target.compress(Bitmap.CompressFormat.PNG, 100, os)
                os.flush()
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return target
    }

    fun uri2Path(context: Context, uri: Uri, path: String): String {
        try {
            context.contentResolver.openInputStream(uri)?.use { input ->
                FileOutputStream(path).use { output ->
                    input.copyTo(output)
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return path
    }

    fun save(context: Context, data: String, dataPath: String, onSave: () -> Unit) {
        val file = File(context.getExternalFilesDir(null), dataPath)
        try {
            file.writeText(data)
        } catch (e: IOException) {
            e.printStackTrace()
        }
        onSave()
    }

    fun loadData(context: Context, dataPath: String): String {
        val file = File(context.getExternalFilesDir(null), dataPath)
        return try {
            if (file.exists()) file.readText() else "[]"
        } catch (e: IOException) {
            e.printStackTrace()
            "[]"
        }.ifEmpty { "[]" }
    }
}
