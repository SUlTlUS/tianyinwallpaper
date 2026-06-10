package com.zeaze.tianyinwallpaper.ui.main

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.OpenableColumns
import com.zeaze.tianyinwallpaper.utils.DepthPrefs
import java.io.File

fun queryMainRouteDisplayName(context: Context, uri: Uri): String? {
    return runCatching {
        context.contentResolver.query(
            uri,
            arrayOf(OpenableColumns.DISPLAY_NAME),
            null,
            null,
            null
        )?.use { cursor ->
            if (cursor.moveToFirst()) {
                val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (index >= 0) cursor.getString(index) else null
            } else null
        }
    }.getOrNull() ?: uri.lastPathSegment
}

fun copyMainRouteOnlineThumbnailToDepthCache(
    context: Context,
    recordThumbnailUri: String?,
    modelId: String
) {
    if (recordThumbnailUri.isNullOrBlank() || modelId.isBlank()) return
    runCatching {
        val target = DepthPrefs.sogThumbnailFile(context, modelId)
        target.parentFile?.mkdirs()

        val sourceFile = File(recordThumbnailUri)
        if (sourceFile.exists() && sourceFile.length() > 0L) {
            if (target.exists()) target.delete()
            sourceFile.copyTo(target, overwrite = true)
            return@runCatching
        }

        context.contentResolver.openInputStream(Uri.parse(recordThumbnailUri))?.use { input ->
            val bitmap = BitmapFactory.decodeStream(input) ?: return@use
            target.outputStream().use { output ->
                bitmap.compress(Bitmap.CompressFormat.JPEG, 86, output)
            }
            bitmap.recycle()
        }
    }
}
