package com.zeaze.tianyinwallpaper.service

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.app.WallpaperColors
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.service.wallpaper.WallpaperService
import android.view.Surface
import android.view.SurfaceHolder
import android.view.WindowManager
import com.alibaba.fastjson.JSON
import com.zeaze.tianyinwallpaper.App
import com.zeaze.tianyinwallpaper.model.RasterGroupModel
import com.zeaze.tianyinwallpaper.renderer.RasterGLRenderer
import com.zeaze.tianyinwallpaper.utils.RasterPrefs
import com.zeaze.tianyinwallpaper.utils.WallpaperClockColorMode

/**
 * 图集光栅壁纸服务 - 专门处理静态图片光栅
 * 
 * 特性：
 * - 多种扫描线效果支持
 * - 集成传感器数据处理
 * - Bitmap缓存机制
 * - 实时参数更新
 * - 无限制图片数量支持
 */
class StaticRasterWallpaperService : WallpaperService() {
    private var activeEngine: StaticRasterEngine? = null

    override fun onCreateEngine(): Engine = StaticRasterEngine()

    inner class StaticRasterEngine : Engine() {

        init {
            activeEngine = this
        }

        // ── 数据 ──
        private var group: RasterGroupModel? = null
        private var isVisible = false
        private var currentWallpaperColors: WallpaperColors? = null

        // ── 渲染器（集成传感器处理）
        private var renderer: RasterGLRenderer? = null

        // ── 传感器 ──
        private var sensorManager: SensorManager? = null
        private var gyroSensor: Sensor? = null
        
        // ── SharedPreferences ──
        private var _pref: SharedPreferences? = null
        private val pref: SharedPreferences? get() = _pref

        // SharedPreferences 监听器 - 监听参数变化
        private val prefChangeListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (
                key == RasterPrefs.PREF_RASTER_GROUPS ||
                key == RasterPrefs.PREF_RASTER_ACTIVE_GROUP_ID ||
                key == WallpaperClockColorMode.PREF_GLOBAL_MODE
            ) {
                // 参数变化，更新渲染器
                loadActiveGroup()
                val g = group
                if (g != null && g.type == RasterGroupModel.TYPE_STATIC) {
                    updateCurrentWallpaperColors(g)
                }
                if (g != null && g.type == RasterGroupModel.TYPE_STATIC && isVisible) {
                    renderer?.updateParamsFromModel(g)
                }
            }
        }

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
            _pref?.registerOnSharedPreferenceChangeListener(prefChangeListener)

            sensorManager = getSystemService(Context.SENSOR_SERVICE) as SensorManager
            gyroSensor = sensorManager?.getDefaultSensor(Sensor.TYPE_GYROSCOPE)

            // 初始化渲染器（集成传感器处理）
            renderer = RasterGLRenderer()
            renderer?.setRenderingEnabled(false)

            loadActiveGroup()
            group?.takeIf { it.type == RasterGroupModel.TYPE_STATIC }?.let { updateCurrentWallpaperColors(it) }
        }

        override fun onComputeColors(): WallpaperColors? {
            return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) currentWallpaperColors else null
        }

        override fun onSurfaceCreated(holder: SurfaceHolder) {
            super.onSurfaceCreated(holder)
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
                renderer?.setRenderingEnabled(true)
                registerSensor()

                // 重新从 SharedPreferences 读取最新参数
                loadActiveGroup()
                val g = group
                if (g != null && g.type == RasterGroupModel.TYPE_STATIC) {
                    updateCurrentWallpaperColors(g)
                    // 检查图片列表是否相同
                    val currentUris = g.imageUris.toSet()
                    val loadedUris = loadedImageUris.toSet()
                    
                    if (currentUris == loadedUris) {
                        // 图片相同，只更新参数
                        renderer?.updateParamsFromModel(g)
                    } else {
                        // 图片不同，重新加载
                        loadContent()
                    }
                }
            } else {
                unregisterSensor()
                renderer?.setRenderingEnabled(false)
            }
        }
        
        // 记录已加载的图片 URI
        private var loadedImageUris: List<String> = emptyList()

        override fun onDestroy() {
            super.onDestroy()
            if (activeEngine == this) activeEngine = null
            _pref?.unregisterOnSharedPreferenceChangeListener(prefChangeListener)
            unregisterSensor()
            renderer?.stop()
            renderer = null
        }

        // ────────────────────────────────────────────
        // 传感器
        // ────────────────────────────────────────────

        private fun registerSensor() {
            // 设置实时获取屏幕旋转角度的 provider
            val windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
            renderer?.displayRotationProvider = {
                @Suppress("DEPRECATION")
                windowManager.defaultDisplay.rotation
            }
            
            gyroSensor?.let { sensor ->
                renderer?.resetSensor()
                sensorManager?.registerListener(
                    sensorListener, 
                    sensor, 
                    SensorManager.SENSOR_DELAY_GAME
                )
            }
        }

        private fun unregisterSensor() {
            sensorManager?.unregisterListener(sensorListener)
        }
        
        // 传感器监听器 - 使用渲染器内置处理
        private val sensorListener = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent?) {
                renderer?.onSensorEvent(event)
            }
            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
        }

        // ────────────────────────────────────────────
        // 数据加载
        // ────────────────────────────────────────────

        private fun loadActiveGroup() {
            group = _pref?.let { RasterPrefs.loadActiveGroup(it) }
        }

        private fun loadContent() {
            val g = group ?: return
            if (g.type != RasterGroupModel.TYPE_STATIC) return

            // 使用集成的加载方法
            updateCurrentWallpaperColors(g)
            renderer?.loadFromModel(applicationContext, g)
            loadedImageUris = g.imageUris
        }

        private fun updateCurrentWallpaperColors(group: RasterGroupModel) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O_MR1) return
            currentWallpaperColors = WallpaperClockColorMode.wallpaperColorsFor(
                applicationContext,
                group.clockColorMode
            )
            Handler(mainLooper).post { notifyColorsChanged() }
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
        private const val TAG = "StaticRasterService"
    }
}
