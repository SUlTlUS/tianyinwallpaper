package com.zeaze.tianyinwallpaper.utils
import android.content.SharedPreferences
import com.alibaba.fastjson.JSON
import com.zeaze.tianyinwallpaper.model.DepthWallpaperModel
object DepthPrefs{const val PREF_DEPTH_WALLPAPERS="depthWallpapers";const val PREF_DEPTH_ACTIVE_ID="depthActiveWallpaperId";fun loadWallpapers(p:SharedPreferences)=try{JSON.parseArray(p.getString(PREF_DEPTH_WALLPAPERS,"[]"),DepthWallpaperModel::class.java)