package com.zeaze.tianyinwallpaper.utils
import android.content.SharedPreferences
import com.alibaba.fastjson.JSON
import com.zeaze.tianyinwallpaper.model.DepthWallpaperModel
object DepthPrefs{const val PREF_DEPTH_WALLPAPERS="depthWallpapers";const val PREF_DEPTH_ACTIVE_ID="depthActiveWallpaperId";fun loadWallpapers(pref:SharedPreferences):List<DepthWallpaperModel>{val json=pref.getString(PREF_DEPTH_WALLPAPERS,"[]")?:"[]";return try{JSON.parseArray(json,DepthWallpaperModel::class.java)?:emptyList()}catch(_:Exception){emptyList()}};fun saveWallpapers(pref:SharedPreferences,wallpapers:List<DepthWallpaperModel>){pref.edit().putString(PREF_DEPTH_WALLPAPERS,JSON.toJSONString(wallpapers)).commit()};fun loadActiveWallpaper(pref:SharedPreferences):DepthWallpaperModel?{val activeId=p