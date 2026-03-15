package com.zeaze.tianyinwallpaper.service

import android.content.Intent
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.PixelFormat
import android.graphics.SurfaceTexture
import android.media.MediaPlayer
import android.net.Uri
import android.os.Handler
import android.service.wallpaper.WallpaperService
import android.util.Log
import android.view.SurfaceHolder
import com.alibaba.fastjson.JSON
import com.zeaze.tianyinwallpaper.App
import com.zeaze.tianyinwallpaper.model.TianYinWallpaperModel
import com.zeaze.tianyinwallpaper.renderer.SimpleGLRenderer
import com.zeaze.tianyinwallpaper.utils.FileUtil
import java.io.InputStream
import java.util.Calendar
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.roundToInt

class TianYinWallpaperService : WallpaperService() {
    override fun onCreateEngine(): Engine = TianYinSolaEngine()

    inner class TianYinSolaEngine : Engine(), SurfaceTexture.OnFrameAvailableListener {

        private var mediaPlayer: MediaPlayer? = null
        private var renderer: SimpleGLRenderer? = null
        private var list: List<TianYinWallpaperModel>? = null
        private var index = -1
        private var shuffledIndices = mutableListOf<Int>()
        private var shuffledPointer = -1
        private var currentXOffset = 0.5f
        private val initialLoadCompleted = AtomicBoolean(false)
        private var isMediaPlayerPrepared = false

        private var pref: SharedPreferences? = null
        private var wallpaperScrollEnabled = true
        private var pageChangeEnabled = false
        private var lastXOffset = -1f

        init {
            activeEngine = this
        }

        override fun onCreate(surfaceHolder: SurfaceHolder) {
            super.onCreate(surfaceHolder)
            surfaceHolder.setFormat(PixelFormat.RGBX_8888)

            pref = getSharedPreferences(App.TIANYIN, MODE_PRIVATE)
            wallpaperScrollEnabled = pref?.getBoolean("wallpaperScroll", true) == true
            pageChangeEnabled = pref?.getBoolean("pageChange", false) == true

            pref?.registerOnSharedPreferenceChangeListener { sharedPreferences, key ->
                when (key) {
                    "wallpaperScroll" -> {
                        wallpaperScrollEnabled = sharedPreferences.getBoolean(key, true)
                        renderer?.requestRender()
                    }
                    "pageChange" -> pageChangeEnabled = sharedPreferences.getBoolean(key, false)
                }
            }

            try {
                val s = FileUtil.loadData(applicationContext, FileUtil.wallpaperPath)
                list = JSON.parseArray(s, TianYinWallpaperModel::class.java)
            } catch (_: Exception) {}
            initialLoadCompleted.set(false)
        }

        override fun onSurfaceCreated(holder: SurfaceHolder) {
            super.onSurfaceCreated(holder)
            renderer?.stop()
            renderer = SimpleGLRenderer()
        }

        override fun onSurfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
            super.onSurfaceChanged(holder, format, width, height)
            renderer?.start(holder.surface, width, height)
            if (index == -1) nextWallpaper() else loadContent()
        }

        override fun onSurfaceDestroyed(holder: SurfaceHolder) {
            super.onSurfaceDestroyed(holder)
            renderer?.stopAndWait(500)
        }

        override fun onVisibilityChanged(visible: Boolean) {
            super.onVisibilityChanged(visible)
            if (visible) {
                if (mediaPlayer != null && isMediaPlayerPrepared && !mediaPlayer!!.isPlaying) {
                    mediaPlayer!!.start()
                }
                renderer?.requestRender()

                try {
                    val s = FileUtil.loadData(applicationContext, FileUtil.wallpaperPath)
                    val newList = JSON.parseArray(s, TianYinWallpaperModel::class.java)
                    if (newList != list) {
                        list = newList
                        shuffledIndices.clear()
                        shuffledPointer = -1
                    }
                } catch (_: Exception) {}

                if (checkAutoSwitch()) nextWallpaper()
            } else {
                if (mediaPlayer != null && mediaPlayer!!.isPlaying) {
                    mediaPlayer!!.pause()
                }
                if (initialLoadCompleted.get() && checkAutoSwitch()) {
                    Handler(mainLooper).postDelayed({ nextWallpaper() }, 100)
                }
            }
        }

        override fun onOffsetsChanged(
            xOffset: Float, yOffset: Float,
            xOffsetStep: Float, yOffsetStep: Float,
            xPixelOffset: Int, yPixelOffset: Int
        ) {
            currentXOffset = if (wallpaperScrollEnabled) xOffset else 0.5f
            renderer?.setXOffset(currentXOffset)
            renderer?.requestRender()

            if (pageChangeEnabled && xOffsetStep > 0 && lastXOffset != -1f) {
                val oldPage = (lastXOffset / xOffsetStep).roundToInt()
                val newPage = (xOffset / xOffsetStep).roundToInt()
                if (oldPage != newPage) nextWallpaper()
            }
            lastXOffset = xOffset
        }

        private fun checkAutoSwitch(): Boolean {
            val list = this.list ?: return false
            if (list.isEmpty()) return false
            val pref = this.pref ?: return false
            val mode = pref.getInt(PREF_AUTO_SWITCH_MODE, 0)

            val now = System.currentTimeMillis()
            val lastSwitchAt = pref.getLong(PREF_AUTO_SWITCH_LAST_SWITCH_AT, 0L)

            val calendar = Calendar.getInstance()
            val currentMinutes = calendar.get(Calendar.HOUR_OF_DAY) * 60 + calendar.get(Calendar.MINUTE)

            // 条件切换
            val conditionalIndex = list.indexOfFirst {
                it.startTime != -1 && it.endTime != -1 &&
                currentMinutes >= it.startTime && currentMinutes < it.endTime
            }
            if (conditionalIndex != -1) {
                if (index != conditionalIndex) {
                    index = conditionalIndex
                    loadContent()
                    pref.edit().putLong(PREF_AUTO_SWITCH_LAST_SWITCH_AT, now).apply()
                }
                return false
            }

            if (mode == 0) return !pageChangeEnabled

            var shouldSwitch = false
            when (mode) {
                1 -> {
                    var intervalSeconds = pref.getLong(PREF_AUTO_SWITCH_INTERVAL_SECONDS, -1L)
                    if (intervalSeconds == -1L) {
                        intervalSeconds = pref.getLong("autoSwitchIntervalMinutes", 60L) * 60L
                    }
                    if (lastSwitchAt == 0L || now - lastSwitchAt >= intervalSeconds * 1000L) {
                        shouldSwitch = true
                    }
                }
                2 -> {
                    val timePointsStr = pref.getString(PREF_AUTO_SWITCH_TIME_POINTS, "") ?: ""
                    val timePoints = timePointsStr.split(",").filter { it.isNotBlank() }
                    if (timePoints.isNotEmpty()) {
                        val lastSwitchCalendar = Calendar.getInstance().apply { timeInMillis = lastSwitchAt }
                        val lastSwitchDay = lastSwitchCalendar.get(Calendar.DAY_OF_YEAR)
                        val lastSwitchYear = lastSwitchCalendar.get(Calendar.YEAR)
                        val lastSwitchMinutes = lastSwitchCalendar.get(Calendar.HOUR_OF_DAY) * 60 + lastSwitchCalendar.get(Calendar.MINUTE)

                        val currentDay = calendar.get(Calendar.DAY_OF_YEAR)
                        val currentYear = calendar.get(Calendar.YEAR)

                        for (point in timePoints) {
                            try {
                                val parts = point.split(":")
                                if (parts.size == 2) {
                                    val pointMinutes = parts[0].trim().toInt() * 60 + parts[1].trim().toInt()
                                    if (currentYear > lastSwitchYear || currentDay > lastSwitchDay) {
                                        if (currentMinutes >= pointMinutes) {
                                            shouldSwitch = true
                                            break
                                        }
                                    } else if (currentMinutes >= pointMinutes && lastSwitchMinutes < pointMinutes) {
                                        shouldSwitch = true
                                        break
                                    }
                                }
                            } catch (_: Exception) {}
                        }
                    }
                }
            }

            if (shouldSwitch) {
                pref.edit().putLong(PREF_AUTO_SWITCH_LAST_SWITCH_AT, now).apply()
            }
            return shouldSwitch
        }

        fun next() = nextWallpaper()
        fun prev() = prevWallpaper()

        private fun nextWallpaper() {
            val list = this.list ?: return
            if (list.isEmpty()) return

            val calendar = Calendar.getInstance()
            val currentMinutes = calendar.get(Calendar.HOUR_OF_DAY) * 60 + calendar.get(Calendar.MINUTE)
            val isRand = pref?.getBoolean("rand", false) == true

            if (isRand) {
                if (shuffledIndices.size != list.size) {
                    shuffledIndices = list.indices.toMutableList()
                    shuffledIndices.shuffle()
                    shuffledPointer = -1
                }
                var found = -1
                var attempts = 0
                while (attempts < list.size) {
                    shuffledPointer++
                    if (shuffledPointer >= shuffledIndices.size) {
                        shuffledIndices.shuffle()
                        shuffledPointer = 0
                    }
                    val nextIdx = shuffledIndices[shuffledPointer]
                    val m = list[nextIdx]
                    if (m.startTime == -1 || (currentMinutes >= m.startTime && currentMinutes < m.endTime)) {
                        found = nextIdx
                        break
                    }
                    attempts++
                }
                index = if (found != -1) found else (index + 1) % list.size
            } else {
                var nextIndex = (index + 1) % list.size
                var count = 0
                while (count < list.size) {
                    val m = list[nextIndex]
                    if (m.startTime == -1 || (currentMinutes >= m.startTime && currentMinutes < m.endTime)) break
                    nextIndex = (nextIndex + 1) % list.size
                    count++
                }
                index = nextIndex
            }
            loadContent()
            pref?.edit()?.putInt(PREF_CURRENT_INDEX, index)?.apply()
        }

        fun updateIndex(newIndex: Int) {
            if (newIndex in 0 until (list?.size ?: 0)) {
                index = newIndex
                loadContent()
            }
        }

        private fun prevWallpaper() {
            val list = this.list ?: return
            if (list.isEmpty()) return

            val calendar = Calendar.getInstance()
            val currentMinutes = calendar.get(Calendar.HOUR_OF_DAY) * 60 + calendar.get(Calendar.MINUTE)

            var nextIndex = if (index <= 0) list.size - 1 else index - 1
            var count = 0
            while (count < list.size) {
                val m = list[nextIndex]
                if (m.startTime == -1 || (currentMinutes >= m.startTime && currentMinutes < m.endTime)) break
                nextIndex = if (nextIndex <= 0) list.size - 1 else nextIndex - 1
                count++
            }
            index = nextIndex
            loadContent()
            pref?.edit()?.putInt(PREF_CURRENT_INDEX, index)?.apply()
        }

        private fun loadContent() {
            if (index < 0 || index >= (list?.size ?: 0)) return
            val model = list!![index]

            if (mediaPlayer != null) {
                mediaPlayer!!.reset()
                isMediaPlayerPrepared = false
            }

            if (model.type == 1) prepareVideo(model) else prepareImage(model)
        }

        private fun prepareVideo(model: TianYinWallpaperModel) {
            try {
                if (mediaPlayer == null) mediaPlayer = MediaPlayer()
                mediaPlayer!!.reset()
                mediaPlayer!!.setDataSource(applicationContext, Uri.parse(model.videoUri))

                val videoST = renderer?.videoSurfaceTexture
                if (videoST == null) {
                    Log.e(TAG, "videoSurfaceTexture is null!")
                    initialLoadCompleted.set(true)
                    Handler(mainLooper).post { nextWallpaper() }
                    return
                }

                // 设置帧可用监听器
                renderer?.setOnFrameAvailableListener(this)

                val surface = android.view.Surface(videoST)
                mediaPlayer!!.setSurface(surface)
                surface.release()

                mediaPlayer!!.setVolume(0f, 0f)

                if (model.loop) {
                    mediaPlayer!!.setOnSeekCompleteListener { mp ->
                        if (isVisible) mp.start()
                    }
                    mediaPlayer!!.setOnCompletionListener { mp ->
                        try { mp.seekTo(0) } catch (e: IllegalStateException) {
                            Log.w(TAG, "seekTo(0) failed", e)
                        }
                    }
                } else {
                    mediaPlayer!!.setOnSeekCompleteListener(null)
                    mediaPlayer!!.setOnCompletionListener(null)
                }

                mediaPlayer!!.setOnErrorListener { _, what, extra ->
                    Log.e(TAG, "MediaPlayer error: what=$what, extra=$extra")
                    isMediaPlayerPrepared = false
                    Handler(mainLooper).post { nextWallpaper() }
                    true
                }

                mediaPlayer!!.setOnInfoListener { _, what, _ ->
                    if (what == MediaPlayer.MEDIA_INFO_VIDEO_RENDERING_START) {
                        isMediaPlayerPrepared = true
                    }
                    false
                }

                mediaPlayer!!.setOnPreparedListener { mp ->
                    val w = mp.videoWidth
                    val h = mp.videoHeight
                    renderer?.setContentSize(w, h)
                    renderer?.videoSurfaceTexture?.setDefaultBufferSize(w, h)
                    renderer?.setVideoMode(true)
                    isMediaPlayerPrepared = true
                    if (isVisible) mp.start()
                    initialLoadCompleted.set(true)
                }
                mediaPlayer!!.prepareAsync()
            } catch (e: Exception) {
                Log.e(TAG, "Video error", e)
                initialLoadCompleted.set(true)
                Handler(mainLooper).post { nextWallpaper() }
            }
        }

        private fun prepareImage(model: TianYinWallpaperModel) {
            if (mediaPlayer != null) {
                mediaPlayer!!.reset()
                isMediaPlayerPrepared = false
            }
            try {
                val `is`: InputStream? = applicationContext.contentResolver.openInputStream(Uri.parse(model.imgUri))
                val bitmap = BitmapFactory.decodeStream(`is`)
                `is`?.close()
                if (bitmap != null) {
                    Log.d(TAG, "prepareImage: ${bitmap.width}x${bitmap.height}")
                    renderer?.setContentSize(bitmap.width, bitmap.height)
                    renderer?.setVideoMode(false)
                    renderer?.loadBitmap(bitmap)
                    renderer?.requestRender()
                    initialLoadCompleted.set(true)
                } else {
                    Log.e(TAG, "prepareImage: bitmap is null")
                    initialLoadCompleted.set(true)
                    Handler(mainLooper).post { nextWallpaper() }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Image error", e)
                initialLoadCompleted.set(true)
                Handler(mainLooper).post { nextWallpaper() }
            }
        }

        override fun onFrameAvailable(surfaceTexture: SurfaceTexture) {
            renderer?.updateVideoFrame()
            renderer?.requestRender()
        }

        override fun onDestroy() {
            super.onDestroy()
            if (activeEngine == this) activeEngine = null
            mediaPlayer?.release()
            mediaPlayer = null
            renderer?.stop()
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_PREV_WALLPAPER -> activeEngine?.prev()
            ACTION_NEXT_WALLPAPER -> activeEngine?.next()
            ACTION_UPDATE_INDEX -> {
                val idx = intent.getIntExtra(EXTRA_INDEX, -1)
                if (idx != -1) activeEngine?.updateIndex(idx)
            }
        }
        return super.onStartCommand(intent, flags, startId)
    }

    companion object {
        const val ACTION_PREV_WALLPAPER = "com.zeaze.tianyinwallpaper.PREV"
        const val ACTION_NEXT_WALLPAPER = "com.zeaze.tianyinwallpaper.NEXT"
        const val ACTION_UPDATE_INDEX = "com.zeaze.tianyinwallpaper.UPDATE_INDEX"
        const val EXTRA_INDEX = "extra_index"
        const val PREF_CURRENT_INDEX = "current_wallpaper_index"

        private var activeEngine: TianYinSolaEngine? = null
        const val PREF_AUTO_SWITCH_MODE = "autoSwitchMode"
        const val PREF_AUTO_SWITCH_INTERVAL_SECONDS = "autoSwitchIntervalSeconds"
        const val PREF_AUTO_SWITCH_TIME_POINTS = "autoSwitchTimePoints"
        const val PREF_AUTO_SWITCH_ANCHOR_AT = "autoSwitchAnchorAt"
        const val PREF_AUTO_SWITCH_LAST_SWITCH_AT = "autoSwitchLastSwitchAt"
        private const val TAG = "TianYinWallpaper"
    }
}
