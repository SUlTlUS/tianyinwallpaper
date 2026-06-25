package com.zeaze.tianyinwallpaper.utils

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import java.io.File
import java.util.Locale

object WallpaperStoragePrefs {
    const val PREF_CACHE_REGULAR_TO_APP_DIR = "cacheRegularWallpapersToAppDir"
    const val PREF_CACHE_RASTER_TO_APP_DIR = "cacheRasterWallpapersToAppDir"
    const val PREF_CACHE_DEPTH_TO_APP_DIR = "cacheDepthWallpapersToAppDir"

    fun copyRegularToAppDir(context: Context, sourceUri: Uri, modelId: String, dynamic: Boolean): Uri? {
        val defaultExtension = if (dynamic) "mp4" else "jpg"
        return copyToAppDir(context, sourceUri, "regular", modelId, defaultExtension)
    }

    fun copyRasterToAppDir(
        context: Context,
        sourceUri: Uri,
        groupId: String,
        itemKey: String,
        dynamic: Boolean
    ): Uri? {
        val defaultExtension = if (dynamic) "mp4" else "jpg"
        return copyToAppDir(context, sourceUri, "raster", "${groupId}_$itemKey", defaultExtension)
    }

    private fun copyToAppDir(
        context: Context,
        sourceUri: Uri,
        category: String,
        baseName: String,
        defaultExtension: String
    ): Uri? {
        val dir = File(File(context.filesDir, "imported_wallpapers"), category)
        if (!dir.mkdirs() && !dir.exists()) return null
        val extension = sourceExtension(context, sourceUri, defaultExtension)
        val dest = File(dir, "$baseName.$extension")
        return runCatching {
            context.contentResolver.openInputStream(sourceUri)?.use { input ->
                dest.outputStream().use { output -> input.copyTo(output) }
            } ?: return null
            Uri.fromFile(dest)
        }.getOrNull()
    }

    private fun sourceExtension(context: Context, sourceUri: Uri, fallback: String): String {
        val displayName = runCatching {
            context.contentResolver.query(sourceUri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                ?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                        if (index >= 0) cursor.getString(index) else null
                    } else {
                        null
                    }
                }
        }.getOrNull()
        val candidate = displayName ?: sourceUri.lastPathSegment
        val extension = candidate
            ?.substringAfterLast('.', missingDelimiterValue = "")
            ?.lowercase(Locale.US)
            ?.filter { it.isLetterOrDigit() }
            ?.take(8)
            .orEmpty()
        return extension.ifBlank { fallback }
    }
}
