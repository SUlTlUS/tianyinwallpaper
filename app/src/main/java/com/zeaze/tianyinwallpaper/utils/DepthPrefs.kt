package com.zeaze.tianyinwallpaper.utils
import android.content.SharedPreferences
import com.zeaze.tianyinwallpaper.model.DepthWallpaperModel
object DepthPrefs{const val PREF_DEPTH_WALLPAPERS="depthWallpapers";const val PREF_DEPTH_ACTIVE_ID="depthActiveWallpaperId";fun loadWallpapers(p:SharedPreferences):List<DepthWallpaperModel>{return emptyList()};fun saveWallpapers(p:SharedPreferences,w