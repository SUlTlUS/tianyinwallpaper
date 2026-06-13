package com.zeaze.tianyinwallpaper.service

import android.app.WallpaperManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.service.wallpaper.WallpaperService
import android.util.Log
import com.alibaba.fastjson.JSON
import com.zeaze.tianyinwallpaper.model.MixedWallpaperPlaylistItem
import com.zeaze.tianyinwallpaper.model.MixedWallpaperPlaylistItem.Companion.KIND_DEPTH
import com.zeaze.tianyinwallpaper.model.MixedWallpaperPlaylistItem.Companion.KIND_RASTER_DYNAMIC
import com.zeaze.tianyinwallpaper.model.MixedWallpaperPlaylistItem.Companion.KIND_RASTER_STATIC
import com.zeaze.tianyinwallpaper.model.MixedWallpaperPlaylistItem.Companion.KIND_WALLPAPER
import com.zeaze.tianyinwallpaper.model.TianYinWallpaperModel
import com.zeaze.tianyinwallpaper.utils.DepthPrefs
import com.zeaze.tianyinwallpaper.utils.FileUtil
import com.zeaze.tianyinwallpaper.utils.RasterPrefs

object MixedWallpaperPlaylist {
    const val PREF_PLAYLIST = "mixedWallpaperPlaylist"
    const val PREF_ENABLED = "mixedWallpaperPlaylistEnabled"
    const val PREF_CURRENT_INDEX = "mixedWallpaperCurrentIndex"

    private const val TAG = "MixedWallpaperPlaylist"

    fun save(pref: SharedPreferences, items: List<MixedWallpaperPlaylistItem>, currentIndex: Int = 0) {
        val safeIndex = if (items.isEmpty()) 0 else currentIndex.coerceIn(items.indices)
        pref.edit()
            .putBoolean(PREF_ENABLED, items.isNotEmpty())
            .putString(PREF_PLAYLIST, JSON.toJSONString(items))
            .putInt(PREF_CURRENT_INDEX, safeIndex)
            .putInt(TianYinWallpaperService.PREF_CURRENT_INDEX, safeIndex)
            .apply()
    }

    fun clear(pref: SharedPreferences) {
        pref.edit()
            .putBoolean(PREF_ENABLED, false)
            .remove(PREF_PLAYLIST)
            .putInt(PREF_CURRENT_INDEX, 0)
            .apply()
    }

    fun load(pref: SharedPreferences?): List<MixedWallpaperPlaylistItem> {
        if (pref == null || !pref.getBoolean(PREF_ENABLED, false)) return emptyList()
        val json = pref.getString(PREF_PLAYLIST, null).orEmpty()
        if (json.isBlank()) return emptyList()
        return runCatching {
            JSON.parseArray(json, MixedWallpaperPlaylistItem::class.java) ?: emptyList()
        }.getOrDefault(emptyList())
    }

    fun currentIndex(pref: SharedPreferences, size: Int): Int {
        if (size <= 0) return -1
        return pref.getInt(PREF_CURRENT_INDEX, 0).coerceIn(0, size - 1)
    }

    fun prepareItem(
        context: Context,
        pref: SharedPreferences,
        item: MixedWallpaperPlaylistItem
    ): Class<out WallpaperService>? {
        return when (item.kind) {
            KIND_WALLPAPER -> {
                val model = item.wallpaper ?: return null
                FileUtil.save(context, JSON.toJSONString(listOf(model)), FileUtil.wallpaperPath) { }
                TianYinWallpaperService::class.java
            }
            KIND_RASTER_STATIC, KIND_RASTER_DYNAMIC -> {
                val groupId = item.rasterGroupId?.takeIf { it.isNotBlank() } ?: return null
                val group = RasterPrefs.loadGroups(pref).firstOrNull { it.id == groupId } ?: return null
                RasterPrefs.setActiveGroupId(pref, group.id)
                if (item.kind == KIND_RASTER_DYNAMIC) {
                    VideoRasterWallpaperService::class.java
                } else {
                    StaticRasterWallpaperService::class.java
                }
            }
            KIND_DEPTH -> {
                val wallpaperId = item.depthWallpaperId?.takeIf { it.isNotBlank() } ?: return null
                val model = DepthPrefs.loadWallpapers(pref).firstOrNull { it.id == wallpaperId } ?: return null
                DepthPrefs.setActiveWallpaperId(pref, model.id)
                DepthWallpaperService::class.java
            }
            else -> null
        }
    }

    fun applyIndex(
        context: Context,
        pref: SharedPreferences,
        index: Int,
        useChangeIntent: ((Class<out WallpaperService>) -> Unit)? = null
    ): Boolean {
        val items = load(pref)
        if (items.isEmpty() || index !in items.indices) return false
        val serviceClass = prepareItem(context, pref, items[index]) ?: return false
        if (useChangeIntent != null) {
            pref.edit()
                .putInt(PREF_CURRENT_INDEX, index)
                .putInt(TianYinWallpaperService.PREF_CURRENT_INDEX, index)
                .apply()
            useChangeIntent.invoke(serviceClass)
        } else {
            val applied = applyComponent(context, serviceClass)
            if (!applied) return false
            pref.edit()
                .putInt(PREF_CURRENT_INDEX, index)
                .putInt(TianYinWallpaperService.PREF_CURRENT_INDEX, index)
                .apply()
            if (serviceClass == TianYinWallpaperService::class.java) {
                runCatching {
                    context.startService(Intent(context, TianYinWallpaperService::class.java).apply {
                        action = TianYinWallpaperService.ACTION_REFRESH_CURRENT
                    })
                }.onFailure { error ->
                    Log.w(TAG, "refresh current wallpaper failed", error)
                }
            }
        }
        return true
    }

    fun switchRelative(context: Context, pref: SharedPreferences, delta: Int): Boolean {
        val items = load(pref)
        if (items.size <= 1) return false
        val current = currentIndex(pref, items.size)
        val next = (current + delta).mod(items.size)
        return applyIndex(context, pref, next)
    }

    fun regularWallpapers(items: List<MixedWallpaperPlaylistItem>): List<TianYinWallpaperModel> {
        return items.mapNotNull { item -> item.wallpaper?.takeIf { item.kind == KIND_WALLPAPER } }
    }

    private fun applyComponent(context: Context, serviceClass: Class<out WallpaperService>): Boolean {
        val component = ComponentName(context, serviceClass)
        val manager = WallpaperManager.getInstance(context)
        val currentInfo = manager.wallpaperInfo
        if (currentInfo?.packageName == context.packageName && currentInfo.serviceName == serviceClass.name) {
            return true
        }
        val applied = runCatching {
            val method = manager.javaClass.getMethod("setWallpaperComponent", ComponentName::class.java)
            method.invoke(manager, component)
        }.onFailure { error ->
            Log.w(TAG, "setWallpaperComponent failed for ${component.className}", error)
        }.isSuccess
        if (!applied) {
            Log.w(TAG, "component switch unavailable; skip system wallpaper picker for ${component.className}")
        }
        return applied
    }
}
