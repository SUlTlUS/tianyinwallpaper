package com.zeaze.tianyinwallpaper.service

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.PixelFormat
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.net.Uri
import android.service.wallpaper.WallpaperService
import android.util.Log
import android.view.SurfaceHolder
import com.alibaba.fastjson.JSON
import com.zeaze.tianyinwallpaper.App
import com.zeaze.tianyinwallpaper.model.RasterGroupModel
import com.zeaze.tianyinwallpaper.renderer.RasterGLRenderer
import kotlin.math.abs

/**
 * 图集光栅壁纸服务 - 专门处理静态图片光栅
 * 使用共享的 RasterGLRenderer 渲染器
 */
class StaticRasterWallpaperService : WallpaperService() {
    private var activeEngine: StaticRasterEngine? = null

    override fun onCreateEngine(): Engine = StaticRasterEngine()

    inner class StaticRasterEngine : Engine(), SensorEventListener {

        init {
            activeEngine = this
        }

        // ── 数据 ──
        private var group: RasterGroupModel? = null
        private var isVisible = false

        // ── 共享渲染器 ──
        private var renderer: RasterGLRenderer? = null

        // ── 传感器 ──
        private var sensorManager: SensorManager? = null
        private var gyroSensor: Sensor? = null
        private var lastGyroNs = 0L
        private var accumulatedAngle = 0f
        private var _pref: SharedPreferences? = null
        private val pref: SharedPreferences? get() = _pref

        fun reload() {
            loadActiveGroup()
            loadContent()
        }

        // ────────────────────────────────────────────
        // 生命周期
        // ────────────────────────────────────────────

        override fun onCreate(surfaceHolder: SurfaceHolder) {
            super.onCreate(surfaceHolder)
            surfaceHolder.setFormat(PixelFormat.RGBX_8888)

            _pref = getSharedPreferences(App.TIANYIN, MODE_PRIVATE)
            sensorManager = getSystemService(Context.SENSOR_SERVICE) as SensorManager
            gyroSensor = sensorManager?.getDefaultSensor(Sensor.TYPE_GYROSCOPE)

            renderer = RasterGLRenderer()
            loadActiveGroup()
        }

        override fun onSurfaceCreated(holder: SurfaceHolder) {
            super.onSurfaceCreated(holder)
            // 渲染器会在 onSurfaceChanged 中启动
        }

        override fun onSurfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
            super.onSurfaceChanged(holder, format, width, height)
            renderer?.start(holder.surface)
            renderer?.resize(width, height)
            loadContent()
        }

        override fun onSurfaceDestroyed(holder: SurfaceHolder) {
            super.onSurfaceDestroyed(holder)
            renderer?.stopAndWait(500)
        }

        override fun onVisibilityChanged(visible: Boolean) {
            super.onVisibilityChanged(visible)
            isVisible = visible

            if (visible) {
                registerSensor()

                val newGroupId = _pref?.getString(PREF_RASTER_ACTIVE_GROUP_ID, null)
                if (newGroupId != group?.id) {
                    loadActiveGroup()
                    loadContent()
                }
            } else {
                unregisterSensor()
            }
        }

        override fun onDestroy() {
            super.onDestroy()
            if (activeEngine == this) activeEngine = null
            unregisterSensor()
            renderer?.stop()
            renderer = null
        }

        // ────────────────────────────────────────────
        // 传感器
        // ────────────────────────────────────────────

        private fun registerSensor() {
            gyroSensor?.let {
                sensorManager?.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME)
            }
            lastGyroNs = 0L
            accumulatedAngle = 0f
        }

        private fun unregisterSensor() {
            sensorManager?.unregisterListener(this)
            lastGyroNs = 0L
        }

        override fun onSensorChanged(event: SensorEvent?) {
            val e = event ?: return
            if (e.sensor.type != Sensor.TYPE_GYROSCOPE) return

            if (lastGyroNs == 0L) {
                lastGyroNs = e.timestamp
                return
            }

            val dt = (e.timestamp - lastGyroNs) / 1_000_000_000f
            lastGyroNs = e.timestamp

            val angularVelocity = e.values[1]
            val absOmega = abs(angularVelocity)
            if (absOmega >= 0.01f) {
                accumulatedAngle += angularVelocity * dt
            }

            val sensorWidth = group?.sensorWidth ?: 0.6f
            val tiltNormalized = (abs(accumulatedAngle) / sensorWidth).coerceIn(0f, 1f)
            val direction = when {
                accumulatedAngle < -0.05f -> 1
                accumulatedAngle > 0.05f -> -1
                else -> 0
            }

            renderer?.updateTilt(tiltNormalized, direction)
        }

        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

        // ────────────────────────────────────────────
        // 数据加载
        // ────────────────────────────────────────────

        private fun loadActiveGroup() {
            val activeId = _pref?.getString(PREF_RASTER_ACTIVE_GROUP_ID, null) ?: return
            val groupsJson = _pref?.getString(PREF_RASTER_GROUPS, "[]") ?: "[]"
            val groups = try {
                JSON.parseArray(groupsJson, RasterGroupModel::class.java) ?: emptyList()
            } catch (_: Exception) {
                emptyList()
            }
            group = groups.firstOrNull { it.id == activeId } ?: groups.firstOrNull()
        }

        private fun loadContent() {
            val g = group ?: return
            if (g.type != RasterGroupModel.TYPE_STATIC) return

            // 设置渲染参数
            renderer?.sensorWidth = g.sensorWidth

            // 加载图片
            val bitmaps = g.imageUris.mapNotNull { uriStr ->
                try {
                    applicationContext.contentResolver.openInputStream(Uri.parse(uriStr))?.use {
                        BitmapFactory.decodeStream(it)
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to decode image: $uriStr", e)
                    null
                }
            }

            renderer?.loadBitmaps(bitmaps)
            renderer?.requestRender()
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_RELOAD -> activeEngine?.reload()
        }
        return super.onStartCommand(intent, flags, startId)
    }

    companion object {
        const val ACTION_RELOAD = "com.zeaze.tianyinwallpaper.STATIC_RASTER_RELOAD"
        const val PREF_RASTER_GROUPS = "rasterGroups"
        const val PREF_RASTER_ACTIVE_GROUP_ID = "rasterActiveGroupId"
        private const val TAG = "StaticRasterService"
    }
}
