package com.zeaze.tianyinwallpaper.utils

import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
import com.alibaba.fastjson.JSON
import com.zeaze.tianyinwallpaper.model.DepthWallpaperModel
import java.io.File
import java.security.MessageDigest

object DepthPrefs {
    const val PREF_DEPTH_WALLPAPERS = "depthWallpapers"
    const val PREF_DEPTH_ACTIVE_ID = "depthActiveWallpaperId"
    const val PREF_WEB_PERFORMANCE_MODE = "depthWebPerformanceMode"

    fun loadWallpapers(pref: SharedPreferences): List<DepthWallpaperModel> {
        val json = pref.getString(PREF_DEPTH_WALLPAPERS, "[]") ?: "[]"
        return try {
            JSON.parseArray(json, DepthWallpaperModel::class.java) ?: emptyList()
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun saveWallpapers(pref: SharedPreferences, wallpapers: List<DepthWallpaperModel>) {
        pref.edit().putString(PREF_DEPTH_WALLPAPERS, JSON.toJSONString(wallpapers)).apply()
    }

    fun loadActiveWallpaper(pref: SharedPreferences): DepthWallpaperModel? {
        val activeId = pref.getString(PREF_DEPTH_ACTIVE_ID, null) ?: return null
        return loadWallpapers(pref).firstOrNull { it.id == activeId }
    }

    fun setActiveWallpaperId(pref: SharedPreferences, id: String) {
        pref.edit().putString(PREF_DEPTH_ACTIVE_ID, id).apply()
    }

    fun findImportedOnlineRecordIds(
        pref: SharedPreferences,
        recordSogPaths: Map<String, String?>
    ): Set<String> {
        if (recordSogPaths.isEmpty()) return emptySet()

        val wallpapers = loadWallpapers(pref)
        val importedIds = wallpapers.mapNotNullTo(mutableSetOf()) { model ->
            model.sourceGenerationRecordId.takeIf { it in recordSogPaths }
        }
        val unresolved = recordSogPaths.filterKeys { it !in importedIds }
        if (unresolved.isEmpty()) return importedIds

        val importedFiles = wallpapers.mapNotNull { model ->
            fileFromStoredUri(model.gaussianUri)?.takeIf { it.isFile }
        }
        val filesBySize = importedFiles.groupBy { it.length() }
        val digestCache = mutableMapOf<String, String?>()

        unresolved.forEach { (recordId, path) ->
            val source = path?.let(::File)?.takeIf { it.isFile } ?: return@forEach
            val candidates = filesBySize[source.length()].orEmpty()
            if (candidates.any { candidate ->
                    sameCanonicalFile(source, candidate) ||
                        fileDigest(source, digestCache) == fileDigest(candidate, digestCache)
                }
            ) {
                importedIds += recordId
            }
        }
        return importedIds
    }

    private fun fileFromStoredUri(value: String): File? {
        if (value.isBlank()) return null
        val uri = runCatching { Uri.parse(value) }.getOrNull() ?: return null
        return when (uri.scheme) {
            null -> File(value)
            "file" -> uri.path?.let(::File)
            else -> null
        }
    }

    private fun sameCanonicalFile(first: File, second: File): Boolean {
        return runCatching { first.canonicalFile == second.canonicalFile }.getOrDefault(false)
    }

    private fun fileDigest(file: File, cache: MutableMap<String, String?>): String? {
        val key = runCatching { file.canonicalPath }.getOrElse { file.absolutePath }
        return cache.getOrPut(key) {
            runCatching {
                val digest = MessageDigest.getInstance("SHA-256")
                file.inputStream().buffered().use { input ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        digest.update(buffer, 0, count)
                    }
                }
                digest.digest().joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
            }.getOrNull()
        }
    }

    // ── SOG file storage ────────────────────────────────────────────

    private const val SOG_DIR = "sog"
    private const val SOG_SCENE_FILENAME = "scene.sog"
    private const val SOG_THUMBNAIL_FILENAME = "thumbnail.jpg"

    fun sogDir(context: Context, modelId: String): File {
        return File(context.filesDir, "$SOG_DIR/$modelId")
    }

    fun sogSceneFile(context: Context, modelId: String): File {
        return File(sogDir(context, modelId), SOG_SCENE_FILENAME)
    }

    fun sogThumbnailFile(context: Context, modelId: String): File {
        return File(sogDir(context, modelId), SOG_THUMBNAIL_FILENAME)
    }

    fun copySogToAppDir(context: Context, sourceUri: Uri, modelId: String): Uri? {
        val dir = sogDir(context, modelId)
        if (!dir.mkdirs() && !dir.exists()) return null
        val dest = sogSceneFile(context, modelId)
        return runCatching {
            context.contentResolver.openInputStream(sourceUri)?.use { input ->
                dest.outputStream().use { output -> input.copyTo(output) }
            }
            Uri.fromFile(dest)
        }.getOrNull()
    }

    fun deleteSogDir(context: Context, modelId: String) {
        sogDir(context, modelId).deleteRecursively()
    }
}
