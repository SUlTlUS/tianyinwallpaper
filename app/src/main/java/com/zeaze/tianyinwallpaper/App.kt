package com.zeaze.tianyinwallpaper

import android.app.Application
import android.content.Context
import com.zeaze.tianyinwallpaper.service.raster.KeyframeTranscoder
import com.zeaze.tianyinwallpaper.update.AppUpdateManager
import com.zeaze.tianyinwallpaper.utils.RasterPrefs

class App : Application() {

    companion object {
        const val TIANYIN = "tianyin"
    }

    override fun onCreate() {
        super.onCreate()
        clearPendingVideoCache()
        clearOutdatedDownloadedApks()
    }

    /**
     * 启动时检查是否有待清理的视频光栅转码缓存
     * （用户关闭"保存视频光栅缓存"开关后，延迟到重启再清）
     */
    private fun clearPendingVideoCache() {
        val pref = getSharedPreferences(TIANYIN, Context.MODE_PRIVATE)
        if (pref.getBoolean(RasterPrefs.PREF_PENDING_CLEAR_VIDEO_CACHE, false)) {
            KeyframeTranscoder(this).clearCache()
            pref.edit().putBoolean(RasterPrefs.PREF_PENDING_CLEAR_VIDEO_CACHE, false).apply()
        }
    }

    /**
     * 启动时清理已安装版本不再需要的下载 APK。
     */
    private fun clearOutdatedDownloadedApks() {
        Thread {
            runCatching {
                AppUpdateManager.clearOutdatedDownloadedApks(this)
            }.onFailure {
                android.util.Log.w("App", "清理过期 APK 失败", it)
            }
        }.start()
    }
}
