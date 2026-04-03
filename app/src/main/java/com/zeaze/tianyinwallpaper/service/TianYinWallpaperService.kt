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
import android.os.SystemClock
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
        private var surfaceWidth = 1
        private var surfaceHeight = 1
        private var currentContentWidth = 1
        private var currentContentHeight = 1
        private var currentUserScale = 1f
        private val autoSwitchHandler = Handler(mainLooper)
        private val autoSwitchRunnable = Runnable {
            val mode = pref?.getInt(PREF_AUTO_SWITCH_MODE, 0) ?: 0
            if (initialLoadCompleted.get() && (mode != 0 || hasConditionalRules()) && checkAutoSwitch()) {
                nextWallpaper()
            }
            scheduleNextAutoSwitchCheck()
        }
        private var prefListener: SharedPreferences.OnSharedPreferenceChangeListener? = null

        init {
            activeEngine = this
        }

        override fun onCreate(surfaceHolder: SurfaceHolder) {
            super.onCreate(surfaceHolder)
            surfaceHolder.setFormat(PixelFormat.RGBX_8888)

            pref = getSharedPreferences(App.TIANYIN, MODE_PRIVATE)
            wallpaperScrollEnabled = pref?.getBoolean("wallpaperScroll", true) == true
            pageChangeEnabled = pref?.getBoolean("pageChange", false) == true

            prefListener = SharedPreferences.OnSharedPreferenceChangeListener { sharedPreferences, key ->
                when (key) {
                    "wallpaperScroll" -> {
                        wallpaperScrollEnabled = sharedPreferences.getBoolean(key, true)
                        renderer?.requestRender()
                    }
                    "pageChange" -> pageChangeEnabled = sharedPreferences.getBoolean(key, false)
                    PREF_AUTO_SWITCH_MODE,
                    PREF_AUTO_SWITCH_INTERVAL_SECONDS,
                    PREF_AUTO_SWITCH_TIME_POINTS,
                    PREF_AUTO_SWITCH_LAST_SWITCH_AT,
                    PREF_AUTO_SWITCH_ANCHOR_AT -> scheduleNextAutoSwitchCheck()
                }
            }
            pref?.registerOnSharedPreferenceChangeListener(prefListener)

            try {
                val s = FileUtil.loadData(applicationContext, FileUtil.wallpaperPath)
                list = JSON.parseArray(s, TianYinWallpaperModel::class.java)
            } catch (_: Exception) {}
            initialLoadCompleted.set(false)
            scheduleNextAutoSwitchCheck()
        }

        override fun onSurfaceCreated(holder: SurfaceHolder) {
            super.onSurfaceCreated(holder)
            renderer?.stop()
            renderer = SimpleGLRenderer()
        }

        override fun onSurfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
            super.onSurfaceChanged(holder, format, width, height)
            surfaceWidth = if (width > 0) width else 1
            surfaceHeight = if (height > 0) height else 1
            renderer?.start(holder.surface, width, height)
            // 首次加载必须立即显示，不受最小切换时间限制。
            if (index == -1) nextWallpaper(ignoreMinInterval = true) else loadContent()
            scheduleNextAutoSwitchCheck()
        }

        override fun onSurfaceDestroyed(holder: SurfaceHolder) {
            super.onSurfaceDestroyed(holder)
            renderer?.stopAndWait(500)
        }

        override fun onVisibilityChanged(visible: Boolean) {
            super.onVisibilityChanged(visible)
            val autoSwitchMode = pref?.getInt(PREF_AUTO_SWITCH_MODE, 0) ?: 0
            if (visible) {
                Log.d(TAG, "onVisibilityChanged: visible=true, index=$index")
                if (mediaPlayer != null && isMediaPlayerPrepared && !mediaPlayer!!.isPlaying) {
                    mediaPlayer!!.start()
                }
                renderer?.requestRender()

                try {
                    val oldModel = list?.getOrNull(index)
                    val oldUuid = oldModel?.uuid
                    val oldImgUri = oldModel?.imgUri
                    val oldVideoUri = oldModel?.videoUri
                    val s = FileUtil.loadData(applicationContext, FileUtil.wallpaperPath)
                    val newList = JSON.parseArray(s, TianYinWallpaperModel::class.java)
                    if (newList != list) {
                        Log.d(TAG, "onVisibilityChanged: list changed, oldSize=${list?.size ?: 0}, newSize=${newList?.size ?: 0}, oldIndex=$index, oldUuid=$oldUuid")
                        list = newList
                        shuffledIndices.clear()
                        shuffledPointer = -1

                        if (!newList.isNullOrEmpty()) {
                            val mappedIndex = when {
                                !oldUuid.isNullOrBlank() -> newList.indexOfFirst { it.uuid == oldUuid }
                                !oldVideoUri.isNullOrBlank() -> newList.indexOfFirst { it.videoUri == oldVideoUri }
                                !oldImgUri.isNullOrBlank() -> newList.indexOfFirst { it.imgUri == oldImgUri }
                                else -> -1
                            }
                            index = when {
                                mappedIndex >= 0 -> mappedIndex
                                index in newList.indices -> index
                                else -> 0
                            }
                            Log.d(TAG, "onVisibilityChanged: remap result mappedIndex=$mappedIndex, finalIndex=$index, finalUuid=${newList.getOrNull(index)?.uuid}")
                            pref?.edit()?.putInt(PREF_CURRENT_INDEX, index)?.apply()
                        }
                    }
                } catch (_: Exception) {}

                if (checkAutoSwitch()) {
                    nextWallpaper()
                }
            } else {
                if (mediaPlayer != null && mediaPlayer!!.isPlaying) {
                    mediaPlayer!!.pause()
                }
                if (initialLoadCompleted.get()) {
                    if (autoSwitchMode == 0) {
                        Log.d(TAG, "onVisibilityChanged: visible=false, switch wallpaper now for mode=0")
                        nextWallpaper()
                    } else if (checkAutoSwitch()) {
                        Handler(mainLooper).postDelayed({ nextWallpaper() }, 100)
                    }
                }
            }
            scheduleNextAutoSwitchCheck()
        }

        private fun hasConditionalRules(): Boolean {
            val current = list ?: return false
            return current.any { it.startTime != -1 && it.endTime != -1 }
        }

        private fun scheduleNextAutoSwitchCheck() {
            autoSwitchHandler.removeCallbacks(autoSwitchRunnable)
            val delayMs = computeNextAutoSwitchDelayMs() ?: return
            autoSwitchHandler.postDelayed(autoSwitchRunnable, delayMs)
        }

        private fun computeNextAutoSwitchDelayMs(): Long? {
            val pref = this.pref ?: return null
            val now = System.currentTimeMillis()
            val mode = pref.getInt(PREF_AUTO_SWITCH_MODE, 0)
            val candidates = mutableListOf<Long>()

            computeConditionalBoundaryDelayMs(now)?.let { candidates.add(it) }

            when (mode) {
                1 -> {
                    var intervalSeconds = pref.getLong(PREF_AUTO_SWITCH_INTERVAL_SECONDS, -1L)
                    if (intervalSeconds == -1L) {
                        intervalSeconds = pref.getLong("autoSwitchIntervalMinutes", 60L) * 60L
                    }
                    if (intervalSeconds > 0L) {
                        val lastSwitchAt = pref.getLong(PREF_AUTO_SWITCH_LAST_SWITCH_AT, 0L)
                        val nextAt = if (lastSwitchAt == 0L) now else lastSwitchAt + intervalSeconds * 1000L
                        candidates.add((nextAt - now).coerceAtLeast(0L))
                    }
                }
                2 -> {
                    val pointsStr = pref.getString(PREF_AUTO_SWITCH_TIME_POINTS, "") ?: ""
                    parseTimePointMinutes(pointsStr)
                        .takeIf { it.isNotEmpty() }
                        ?.let { computeNextDelayForMinutePoints(now, it) }
                        ?.let { candidates.add(it) }
                }
            }

            return candidates.minOrNull()?.coerceAtLeast(0L)
        }

        private fun computeConditionalBoundaryDelayMs(now: Long): Long? {
            val current = list ?: return null
            val points = current.asSequence()
                .filter { it.startTime != -1 && it.endTime != -1 }
                .flatMap { sequenceOf(it.startTime, it.endTime) }
                .filter { it in 0 until 24 * 60 }
                .distinct()
                .toList()
            if (points.isEmpty()) return null
            return computeNextDelayForMinutePoints(now, points)
        }

        private fun parseTimePointMinutes(timePointsStr: String): List<Int> {
            return timePointsStr.split(",")
                .asSequence()
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .mapNotNull { point ->
                    val parts = point.split(":")
                    if (parts.size != 2) return@mapNotNull null
                    val hour = parts[0].trim().toIntOrNull() ?: return@mapNotNull null
                    val minute = parts[1].trim().toIntOrNull() ?: return@mapNotNull null
                    if (hour !in 0..23 || minute !in 0..59) return@mapNotNull null
                    hour * 60 + minute
                }
                .distinct()
                .sorted()
                .toList()
        }

        private fun computeNextDelayForMinutePoints(now: Long, minutePoints: List<Int>): Long? {
            if (minutePoints.isEmpty()) return null
            var minDelay: Long? = null
            for (dayOffset in 0..2) {
                val base = Calendar.getInstance().apply {
                    timeInMillis = now
                    add(Calendar.DAY_OF_YEAR, dayOffset)
                    set(Calendar.SECOND, 0)
                    set(Calendar.MILLISECOND, 0)
                }
                for (pointMinutes in minutePoints) {
                    val trigger = base.clone() as Calendar
                    trigger.set(Calendar.HOUR_OF_DAY, pointMinutes / 60)
                    trigger.set(Calendar.MINUTE, pointMinutes % 60)
                    val triggerAt = trigger.timeInMillis
                    if (triggerAt <= now) continue
                    val delay = triggerAt - now
                    if (minDelay == null || delay < minDelay) {
                        minDelay = delay
                    }
                }
                if (minDelay != null) break
            }
            return minDelay
        }

        override fun onOffsetsChanged(
            xOffset: Float, yOffset: Float,
            xOffsetStep: Float, yOffsetStep: Float,
            xPixelOffset: Int, yPixelOffset: Int
        ) {
            val scrollEffective = wallpaperScrollEnabled && canCurrentWallpaperScrollHorizontally()
            currentXOffset = if (scrollEffective) xOffset else 0.5f
            renderer?.setXOffset(currentXOffset)
            renderer?.requestRender()

            if (pageChangeEnabled && (list?.size ?: 0) > 1 && xOffsetStep > 0 && lastXOffset != -1f) {
                val oldPage = (lastXOffset / xOffsetStep).roundToInt()
                val newPage = (xOffset / xOffsetStep).roundToInt()
                if (oldPage != newPage) nextWallpaper()
            }
            lastXOffset = xOffset
        }

        // 仅当“缩放后的内容宽度”大于屏幕宽度时，允许当前壁纸跟随屏幕滚动。
        private fun canCurrentWallpaperScrollHorizontally(): Boolean {
            val cW = currentContentWidth.coerceAtLeast(1)
            val cH = currentContentHeight.coerceAtLeast(1)
            val sW = surfaceWidth.coerceAtLeast(1)
            val sH = surfaceHeight.coerceAtLeast(1)
            val baseScale = maxOf(sW.toFloat() / cW.toFloat(), sH.toFloat() / cH.toFloat())
            val scaledDisplayWidth = cW.toFloat() * baseScale * currentUserScale.coerceAtLeast(0.1f)
            return scaledDisplayWidth > sW.toFloat() + 1f
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
                if (index != conditionalIndex && canSwitchByMinInterval()) {
                    index = conditionalIndex
                    loadContent()
                    markSwitchTimestamp()
                    pref.edit().putLong(PREF_AUTO_SWITCH_LAST_SWITCH_AT, now).apply()
                }
                return false
            }

            // mode=0 表示不做时间驱动自动切换，避免每次可见性变化都触发 next。
            if (mode == 0) return false

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
                                    } else if (currentMinutes >= pointMinutes && pointMinutes > lastSwitchMinutes) {
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

        private fun canSwitchByMinInterval(): Boolean {
            // 仅在“不可见时切换（mode=0）”模式下应用最小切换时间限制。
            val mode = pref?.getInt(PREF_AUTO_SWITCH_MODE, 0) ?: 0
            if (mode != 0) return true

            val minSeconds = (pref?.getInt("minTime", 1) ?: 1).coerceAtLeast(0)
            if (minSeconds == 0) return true
            val last = pref?.getLong(PREF_MIN_SWITCH_LAST_AT, 0L) ?: 0L
            if (last == 0L) return true
            val now = System.currentTimeMillis()
            return now - last >= minSeconds * 1000L
        }

        private fun markSwitchTimestamp() {
            pref?.edit()?.putLong(PREF_MIN_SWITCH_LAST_AT, System.currentTimeMillis())?.apply()
        }

        fun next() = nextWallpaper(ignoreMinInterval = true)
        fun prev() = prevWallpaper(ignoreMinInterval = true)

        private fun nextWallpaper(ignoreMinInterval: Boolean = false) {
            val list = this.list ?: return
            if (list.isEmpty()) return

            // 单壁纸模式：只在首次进入时加载一次，后续不重复重播。
            if (list.size == 1) {
                if (index != 0) {
                    index = 0
                    loadContent()
                    markSwitchTimestamp()
                    pref?.edit()?.putInt(PREF_CURRENT_INDEX, index)?.apply()
                }
                return
            }

            if (!ignoreMinInterval && !canSwitchByMinInterval()) {
                Log.d(TAG, "nextWallpaper blocked by minTime")
                return
            }

            val calendar = Calendar.getInstance()
            val currentMinutes = calendar.get(Calendar.HOUR_OF_DAY) * 60 + calendar.get(Calendar.MINUTE)
            val isRand = pref?.getBoolean("rand", false) == true

            Log.d(TAG, "nextWallpaper: start index=$index, size=${list.size}, isRand=$isRand")

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
                Log.d(TAG, "nextWallpaper: rand selected index=$index, found=$found, pointer=$shuffledPointer")
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
                Log.d(TAG, "nextWallpaper: seq selected index=$index")
            }
            Log.d(TAG, "nextWallpaper: loading index=$index, uuid=${list.getOrNull(index)?.uuid}, type=${list.getOrNull(index)?.type}")
            loadContent()
            markSwitchTimestamp()
            pref?.edit()?.putInt(PREF_CURRENT_INDEX, index)?.apply()
        }

        fun updateIndex(newIndex: Int) {
             if (newIndex in 0 until (list?.size ?: 0)) {
                 index = newIndex
                 loadContent()
                 markSwitchTimestamp()
             }
        }

        fun refreshCurrentFromStorage() {
            try {
                val s = FileUtil.loadData(applicationContext, FileUtil.wallpaperPath)
                val newList = JSON.parseArray(s, TianYinWallpaperModel::class.java)
                if (!newList.isNullOrEmpty()) {
                    list = newList
                    if (index !in newList.indices) index = 0
                    loadContent()
                }
            } catch (e: Exception) {
                Log.e(TAG, "refreshCurrentFromStorage error", e)
            }
        }

        // 仅同步列表顺序并尽量保持当前壁纸不变（按 uuid 重映射 index）。
        fun syncPlaylistFromStorageKeepCurrent() {
            try {
                val oldModel = list?.getOrNull(index)
                val oldUuid = oldModel?.uuid
                val oldVideoUri = oldModel?.videoUri
                val oldImgUri = oldModel?.imgUri
                val s = FileUtil.loadData(applicationContext, FileUtil.wallpaperPath)
                val newList = JSON.parseArray(s, TianYinWallpaperModel::class.java)
                if (!newList.isNullOrEmpty()) {
                    Log.d(TAG, "syncPlaylist: oldSize=${list?.size ?: 0}, newSize=${newList.size}, oldIndex=$index, oldUuid=$oldUuid")
                    list = newList
                    shuffledIndices.clear()
                    shuffledPointer = -1
                    val mappedIndex = when {
                        !oldUuid.isNullOrBlank() -> newList.indexOfFirst { it.uuid == oldUuid }
                        !oldVideoUri.isNullOrBlank() -> newList.indexOfFirst { it.videoUri == oldVideoUri }
                        !oldImgUri.isNullOrBlank() -> newList.indexOfFirst { it.imgUri == oldImgUri }
                        else -> -1
                    }
                    index = when {
                        mappedIndex >= 0 -> mappedIndex
                        index in newList.indices -> index
                        else -> 0
                    }
                    Log.d(TAG, "syncPlaylist: mappedIndex=$mappedIndex, finalIndex=$index, finalUuid=${newList.getOrNull(index)?.uuid}")
                    pref?.edit()?.putInt(PREF_CURRENT_INDEX, index)?.apply()
                }
            } catch (e: Exception) {
                Log.e(TAG, "syncPlaylistFromStorageKeepCurrent error", e)
            }
        }

        fun updateCurrentTransform(scale: Float, offsetX: Float, offsetY: Float) {
            currentUserScale = scale
            renderer?.setUserTransform(scale, offsetX, offsetY)
            val currentList = list
            if (currentList != null && index in currentList.indices) {
                currentList[index].scale = scale
                currentList[index].offsetX = offsetX
                currentList[index].offsetY = offsetY
            }
            renderer?.requestRender()
        }

        private fun prevWallpaper(ignoreMinInterval: Boolean = false) {
            val list = this.list ?: return
            if (list.isEmpty()) return

            // 单壁纸模式无需执行上一张切换，避免重复重播。
            if (list.size == 1) return

            if (!ignoreMinInterval && !canSwitchByMinInterval()) {
                Log.d(TAG, "prevWallpaper blocked by minTime")
                return
            }

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
            markSwitchTimestamp()
            pref?.edit()?.putInt(PREF_CURRENT_INDEX, index)?.apply()
        }

        private fun loadContent() {
            if (index < 0 || index >= (list?.size ?: 0)) return
            val model = list!![index]

            currentUserScale = model.scale
            renderer?.setUserTransform(model.scale, model.offsetX, model.offsetY)

            if (mediaPlayer != null) {
                mediaPlayer!!.reset()
                isMediaPlayerPrepared = false
            }

            if (model.type == 1) {
                // 先切到视频模式，避免图片->视频切换期间着色器状态不一致导致黑屏。
                renderer?.setVideoMode(true)
                prepareVideo(model)
            } else {
                // 切到图片时移除视频帧监听，防止旧视频回调干扰渲染。
                renderer?.setOnFrameAvailableListener(null)
                renderer?.setVideoMode(false)
                prepareImage(model)
            }
        }

        private fun prepareVideo(model: TianYinWallpaperModel) {
            try {
                if (mediaPlayer == null) mediaPlayer = MediaPlayer()
                mediaPlayer!!.reset()
                mediaPlayer!!.setDataSource(applicationContext, Uri.parse(model.videoUri))
                renderer?.setUserTransform(model.scale, model.offsetX, model.offsetY)

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

                val volume = model.volume.coerceIn(0f, 1f)
                mediaPlayer!!.setVolume(volume, volume)

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
                    currentContentWidth = if (w > 0) w else 1
                    currentContentHeight = if (h > 0) h else 1
                    renderer?.setContentSize(w, h)
                    renderer?.videoSurfaceTexture?.setDefaultBufferSize(w, h)
                    renderer?.setVideoMode(true)
                    isMediaPlayerPrepared = true
                    if (isVisible) mp.start()
                    renderer?.requestRender()
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
            renderer?.setOnFrameAvailableListener(null)
            renderer?.setVideoMode(false)
            renderer?.setUserTransform(model.scale, model.offsetX, model.offsetY)
            try {
                val `is`: InputStream? = applicationContext.contentResolver.openInputStream(Uri.parse(model.imgUri))
                val bitmap = BitmapFactory.decodeStream(`is`)
                `is`?.close()
                if (bitmap != null) {
                    Log.d(TAG, "prepareImage: ${bitmap.width}x${bitmap.height}")
                    currentContentWidth = if (bitmap.width > 0) bitmap.width else 1
                    currentContentHeight = if (bitmap.height > 0) bitmap.height else 1
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
            autoSwitchHandler.removeCallbacks(autoSwitchRunnable)
            prefListener?.let { pref?.unregisterOnSharedPreferenceChangeListener(it) }
            prefListener = null
            mediaPlayer?.release()
            mediaPlayer = null
            renderer?.stop()
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_PREV_WALLPAPER -> activeEngine?.prev()
            ACTION_NEXT_WALLPAPER -> activeEngine?.next()
            ACTION_REFRESH_CURRENT -> activeEngine?.refreshCurrentFromStorage()
            ACTION_SYNC_PLAYLIST -> activeEngine?.syncPlaylistFromStorageKeepCurrent()
            ACTION_UPDATE_TRANSFORM -> {
                val scale = intent.getFloatExtra(EXTRA_SCALE, 1f)
                val offsetX = intent.getFloatExtra(EXTRA_OFFSET_X, 0f)
                val offsetY = intent.getFloatExtra(EXTRA_OFFSET_Y, 0f)
                activeEngine?.updateCurrentTransform(scale, offsetX, offsetY)
            }
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
        const val ACTION_REFRESH_CURRENT = "com.zeaze.tianyinwallpaper.REFRESH_CURRENT"
        const val ACTION_SYNC_PLAYLIST = "com.zeaze.tianyinwallpaper.SYNC_PLAYLIST"
        const val ACTION_UPDATE_TRANSFORM = "com.zeaze.tianyinwallpaper.UPDATE_TRANSFORM"
        const val ACTION_UPDATE_INDEX = "com.zeaze.tianyinwallpaper.UPDATE_INDEX"
        const val EXTRA_INDEX = "extra_index"
        const val EXTRA_SCALE = "extra_scale"
        const val EXTRA_OFFSET_X = "extra_offset_x"
        const val EXTRA_OFFSET_Y = "extra_offset_y"
        const val PREF_CURRENT_INDEX = "current_wallpaper_index"

        private var activeEngine: TianYinSolaEngine? = null
        const val PREF_AUTO_SWITCH_MODE = "autoSwitchMode"
        const val PREF_AUTO_SWITCH_INTERVAL_SECONDS = "autoSwitchIntervalSeconds"
        const val PREF_AUTO_SWITCH_TIME_POINTS = "autoSwitchTimePoints"
        const val PREF_AUTO_SWITCH_ANCHOR_AT = "autoSwitchAnchorAt"
        const val PREF_AUTO_SWITCH_LAST_SWITCH_AT = "autoSwitchLastSwitchAt"
        private const val TAG = "TianYinWallpaper"
        private const val PREF_MIN_SWITCH_LAST_AT = "pref_min_switch_last_at"
    }
}
