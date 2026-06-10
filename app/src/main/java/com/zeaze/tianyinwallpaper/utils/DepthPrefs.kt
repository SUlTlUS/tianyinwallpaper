package com.zeaze.tianyinwallpaper.utils

import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
import com.alibaba.fastjson.JSON
import com.zeaze.tianyinwallpaper.model.DepthWallpaperModel
import java.io.File

object DepthPrefs {
    const val PREF_DEPTH_WALLPAPERS = "depthWallpapers"
    const val PREF_DEPTH_ACTIVE_ID = "depthActiveWallpaperId"

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
