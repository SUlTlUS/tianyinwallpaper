package com.zeaze.tianyinwallpaper.utils

import android.content.SharedPreferences
import com.alibaba.fastjson.JSON
import com.zeaze.tianyinwallpaper.model.RasterGroupModel

/**
 * 光栅壁纸相关的首选项键常量和工具方法
 */
object RasterPrefs {
    const val PREF_RASTER_GROUPS = "rasterGroups"
    const val PREF_RASTER_ACTIVE_GROUP_ID = "rasterActiveGroupId"
    /** 是否保留所有视频光栅转码缓存（true=保留，false=仅保留当前预览的） */
    const val PREF_KEEP_VIDEO_CACHE = "rasterKeepVideoCache"
    /** 标记：下次启动时需要清除视频光栅转码缓存 */
    const val PREF_PENDING_CLEAR_VIDEO_CACHE = "rasterPendingClearVideoCache"

    /**
     * 从 SharedPreferences 加载所有光栅组
     */
    fun loadGroups(pref: SharedPreferences): List<RasterGroupModel> {
        val groupsJson = pref.getString(PREF_RASTER_GROUPS, "[]") ?: "[]"
        return try {
            JSON.parseArray(groupsJson, RasterGroupModel::class.java) ?: emptyList()
        } catch (_: Exception) {
            emptyList()
        }
    }

    /**
     * 从 SharedPreferences 加载当前激活的光栅组
     * @return 激活的光栅组，如果没有激活则返回第一个组，如果没有组则返回 null
     */
    fun loadActiveGroup(pref: SharedPreferences): RasterGroupModel? {
        val activeId = pref.getString(PREF_RASTER_ACTIVE_GROUP_ID, null)
        val groups = loadGroups(pref)
        return groups.firstOrNull { it.id == activeId } ?: groups.firstOrNull()
    }

    /**
     * 保存光栅组列表到 SharedPreferences
     */
    fun saveGroups(pref: SharedPreferences, groups: List<RasterGroupModel>) {
        pref.edit().putString(PREF_RASTER_GROUPS, JSON.toJSONString(groups)).apply()
    }

    /**
     * 设置激活的光栅组 ID
     */
    fun setActiveGroupId(pref: SharedPreferences, groupId: String) {
        pref.edit().putString(PREF_RASTER_ACTIVE_GROUP_ID, groupId).apply()
    }
}
