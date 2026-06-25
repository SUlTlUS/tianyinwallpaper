package com.zeaze.tianyinwallpaper.ui.setting

import java.util.Locale
import java.io.File
import java.io.InputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.net.Uri
import android.text.TextUtils
import android.text.format.Formatter
import android.widget.Toast
import androidx.activity.compose.PredictiveBackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.ExperimentalAnimationApi
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Icon
import androidx.compose.material.Surface
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import com.kyant.shapes.Capsule
import com.kyant.shapes.RoundedRectangle
import com.zeaze.tianyinwallpaper.backdrop.backdrops.layerBackdrop
import com.zeaze.tianyinwallpaper.backdrop.backdrops.rememberLayerBackdrop
import com.zeaze.tianyinwallpaper.backdrop.backdrops.rememberCanvasBackdrop
import com.zeaze.tianyinwallpaper.backdrop.Backdrop
import com.zeaze.tianyinwallpaper.backdrop.drawBackdrop
import com.zeaze.tianyinwallpaper.backdrop.effects.blur
import com.zeaze.tianyinwallpaper.backdrop.effects.colorControls
import com.zeaze.tianyinwallpaper.backdrop.effects.lens
import com.zeaze.tianyinwallpaper.backdrop.highlight.Highlight
import com.zeaze.tianyinwallpaper.backdrop.shadow.InnerShadow
import com.zeaze.tianyinwallpaper.backdrop.shadow.Shadow
import com.zeaze.tianyinwallpaper.App
import com.zeaze.tianyinwallpaper.MainActivity
import com.zeaze.tianyinwallpaper.R
import com.zeaze.tianyinwallpaper.base.rxbus.RxBus
import com.zeaze.tianyinwallpaper.base.rxbus.RxConstants
import com.zeaze.tianyinwallpaper.catalog.components.LiquidSegmentedOption
import com.zeaze.tianyinwallpaper.catalog.components.LiquidSegmentedSelector
import com.zeaze.tianyinwallpaper.catalog.components.LiquidToggle
import com.zeaze.tianyinwallpaper.catalog.components.LiquidButton
import com.zeaze.tianyinwallpaper.catalog.components.PlainFallbackStyle
import com.zeaze.tianyinwallpaper.catalog.components.PlainIconButton
import com.zeaze.tianyinwallpaper.catalog.components.PlainSwitch
import com.zeaze.tianyinwallpaper.catalog.components.WheelPicker
import com.zeaze.tianyinwallpaper.service.DepthWallpaperService
import com.zeaze.tianyinwallpaper.service.StaticRasterWallpaperService
import com.zeaze.tianyinwallpaper.service.TianYinWallpaperService
import com.zeaze.tianyinwallpaper.service.VideoRasterWallpaperService
import com.zeaze.tianyinwallpaper.update.AppUpdateManager
import com.zeaze.tianyinwallpaper.update.UpdateDialog
import com.zeaze.tianyinwallpaper.update.UpdateDialogState
import com.zeaze.tianyinwallpaper.ui.commom.ProgressiveBlurContent
import com.zeaze.tianyinwallpaper.ui.commom.LiquidWindowAnimatedContent
import com.zeaze.tianyinwallpaper.utils.DepthPrefs
import com.zeaze.tianyinwallpaper.utils.GradioMcpSogGenerator
import com.zeaze.tianyinwallpaper.utils.LiquidGlassPrefs
import com.zeaze.tianyinwallpaper.utils.RasterPrefs
import com.zeaze.tianyinwallpaper.utils.WallpaperStoragePrefs
import com.zeaze.tianyinwallpaper.utils.AppAccentColor
import com.zeaze.tianyinwallpaper.utils.AppAccentColors
import com.zeaze.tianyinwallpaper.utils.WallpaperClockColorMode
import com.alibaba.fastjson.JSON
import com.alibaba.fastjson.JSONArray
import com.alibaba.fastjson.JSONObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private sealed class SettingsDialogState {
    object MinTime : SettingsDialogState()
    object AutoInterval : SettingsDialogState()
    object AutoPoints : SettingsDialogState()
    object PickTime : SettingsDialogState()
}

private enum class DataStorageConfirmAction {
    ResetSettings,
    ClearAllData
}

@OptIn(ExperimentalAnimationApi::class)
@Composable
fun SettingRouteScreen(
    useDarkTheme: Boolean,
    onThemeModeChange: (Int) -> Unit = {},
    accentColorKey: String = AppAccentColors.DEFAULT_KEY,
    onAccentColorChange: (String) -> Unit = {},
    liquidGlassEnabled: Boolean = true,
    onLiquidGlassEnabledChange: (Boolean) -> Unit = {},
    onOpenAppInfo: () -> Unit = {},
    onOpenUsage: () -> Unit = {},
    onOpenDataStorage: () -> Unit = {},
    onOpenCorrugatedTest: () -> Unit = {},
    onOpenPlyModelTest: () -> Unit = {}
) {
    val context = LocalContext.current
    val pref = remember(context) { context.getSharedPreferences(App.TIANYIN, Context.MODE_PRIVATE) }
    val editor = remember(pref) { pref.edit() }

    var testPagesVisible by remember { mutableStateOf(pref.getBoolean(PREF_CORRUGATED_TEST_ENABLED, false)) }
    DisposableEffect(pref) {
        val listener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { preferences, key ->
            if (key == PREF_CORRUGATED_TEST_ENABLED) {
                testPagesVisible = preferences.getBoolean(PREF_CORRUGATED_TEST_ENABLED, false)
            }
        }
        pref.registerOnSharedPreferenceChangeListener(listener)
        onDispose { pref.unregisterOnSharedPreferenceChangeListener(listener) }
    }

    var hidePermissionDialog by remember { mutableStateOf(pref.getBoolean("hide_permission_dialog", false)) }
    var rand by remember { mutableStateOf(pref.getBoolean("rand", false)) }
    var keepVideoCache by remember { mutableStateOf(pref.getBoolean(RasterPrefs.PREF_KEEP_VIDEO_CACHE, false)) }
    var cacheRegularToAppDir by remember {
        mutableStateOf(pref.getBoolean(WallpaperStoragePrefs.PREF_CACHE_REGULAR_TO_APP_DIR, false))
    }
    var cacheRasterToAppDir by remember {
        mutableStateOf(pref.getBoolean(WallpaperStoragePrefs.PREF_CACHE_RASTER_TO_APP_DIR, false))
    }
    var cacheDepthToAppDir by remember {
        mutableStateOf(pref.getBoolean(WallpaperStoragePrefs.PREF_CACHE_DEPTH_TO_APP_DIR, true))
    }
    var webPerformanceMode by remember { mutableStateOf(pref.getBoolean(DepthPrefs.PREF_WEB_PERFORMANCE_MODE, true)) }
    var showModelScopeTokenDialog by remember { mutableStateOf(false) }
    var modelScopeTokenText by remember {
        mutableStateOf(GradioMcpSogGenerator.getModelScopeToken(context.applicationContext).orEmpty())
    }
    var showOnlineGenerationAddressDialog by remember { mutableStateOf(false) }
    var generationServiceType by remember {
        mutableStateOf(GradioMcpSogGenerator.getGenerationServiceType(context.applicationContext))
    }
    var onlineGenerationAddressText by remember {
        mutableStateOf(
            GradioMcpSogGenerator.getGenerationBaseUrl(
                context.applicationContext,
                generationServiceType
            )
        )
    }
    var pageChange by remember { mutableStateOf(pref.getBoolean("pageChange", false)) }
    var wallpaperScroll by remember { mutableStateOf(pref.getBoolean("wallpaperScroll", false)) }
    var minTime by remember { mutableStateOf(pref.getInt("minTime", 1)) }
    var themeMode by remember { mutableStateOf(pref.getInt(MainActivity.PREF_THEME_MODE, MainActivity.THEME_MODE_FOLLOW_SYSTEM)) }
    var globalClockColorMode by remember {
        mutableStateOf(pref.getInt(WallpaperClockColorMode.PREF_GLOBAL_MODE, WallpaperClockColorMode.LIGHT_CLOCK))
    }
    var autoSwitchMode by remember {
        mutableStateOf(pref.getInt(TianYinWallpaperService.PREF_AUTO_SWITCH_MODE, AUTO_SWITCH_MODE_NONE))
    }
    var autoSwitchInterval by remember {
        val storedSeconds = pref.getLong(TianYinWallpaperService.PREF_AUTO_SWITCH_INTERVAL_SECONDS, -1L)
        if (storedSeconds != -1L) {
            mutableStateOf(storedSeconds)
        } else {
            // Migration from minutes
            val minutes = pref.getLong("autoSwitchIntervalMinutes", DEFAULT_AUTO_SWITCH_INTERVAL_SECONDS / 60)
            mutableStateOf(minutes * 60L)
        }
    }
    var autoSwitchPoints by remember {
        mutableStateOf(
            pref.getString(TianYinWallpaperService.PREF_AUTO_SWITCH_TIME_POINTS, DEFAULT_AUTO_SWITCH_TIME_POINTS)
                .takeUnless { TextUtils.isEmpty(it) } ?: DEFAULT_AUTO_SWITCH_TIME_POINTS
        )
    }
    var showMinTimeDialog by remember { mutableStateOf(false) }
    var tempMinTime by remember { mutableStateOf(minTime) }

    var showAutoIntervalDialog by remember { mutableStateOf(false) }
    var showAutoPointsDialog by remember { mutableStateOf(false) }
    var showTimePickerDialog by remember { mutableStateOf(false) }
    var autoPointsInput by remember { mutableStateOf(autoSwitchPoints) }
    
    // 更新对话框状态
    var updateDialogState by remember { mutableStateOf(UpdateDialogState()) }
    var shouldCheckUpdate by remember { mutableStateOf(false) }
    val scope = androidx.compose.runtime.rememberCoroutineScope()

    // “关于”二级页：和景深在线生成页一致，打开从右侧推入，返回从左到右滑出；返回时不改变透明度。
    val density = LocalDensity.current
    var showAppInfoPage by remember { mutableStateOf(false) }
    var renderAppInfoPage by remember { mutableStateOf(false) }
    val appInfoPageWidthPx = remember(context) {
        context.resources.displayMetrics.widthPixels.toFloat().coerceAtLeast(1f)
    }
    val appInfoPageOffset = remember { Animatable(appInfoPageWidthPx) }
    var appInfoBackDragOffsetPx by remember { mutableStateOf(0f) }
    var appInfoBackGestureActive by remember { mutableStateOf(false) }
    val appInfoBackEdgePx = with(density) { 40.dp.toPx() }

    fun closeAppInfoPage() {
        if (!renderAppInfoPage && !showAppInfoPage) return
        scope.launch {
            val startOffset = (appInfoPageOffset.value + appInfoBackDragOffsetPx)
                .coerceIn(0f, appInfoPageWidthPx)
            appInfoBackDragOffsetPx = 0f
            appInfoBackGestureActive = false
            appInfoPageOffset.snapTo(startOffset)
            showAppInfoPage = false
        }
    }

    androidx.compose.runtime.LaunchedEffect(showAppInfoPage, appInfoPageWidthPx) {
        if (showAppInfoPage) {
            renderAppInfoPage = true
            appInfoBackDragOffsetPx = 0f
            appInfoBackGestureActive = false
            appInfoPageOffset.snapTo(appInfoPageWidthPx)
            appInfoPageOffset.animateTo(
                targetValue = 0f,
                animationSpec = spring(
                    dampingRatio = Spring.DampingRatioNoBouncy,
                    stiffness = Spring.StiffnessMediumLow
                )
            )
        } else if (renderAppInfoPage) {
            appInfoBackDragOffsetPx = 0f
            appInfoBackGestureActive = false
            appInfoPageOffset.animateTo(
                targetValue = appInfoPageWidthPx,
                animationSpec = spring(
                    dampingRatio = Spring.DampingRatioNoBouncy,
                    stiffness = Spring.StiffnessMediumLow
                )
            )
            renderAppInfoPage = false
            appInfoPageOffset.snapTo(appInfoPageWidthPx)
        }
    }

    var pickingHour by remember { mutableStateOf(12) }
    var pickingMinute by remember { mutableStateOf(0) }

    val currentDialogState = when {
        showMinTimeDialog -> SettingsDialogState.MinTime
        showAutoIntervalDialog -> SettingsDialogState.AutoInterval
        showAutoPointsDialog -> SettingsDialogState.AutoPoints
        showTimePickerDialog -> SettingsDialogState.PickTime
        else -> null
    }

    PredictiveBackHandler(enabled = showAppInfoPage && currentDialogState == null && !updateDialogState.isVisible) { progress ->
        try {
            progress.collect { backEvent ->
                appInfoBackGestureActive = true
                appInfoBackDragOffsetPx = (appInfoPageWidthPx * backEvent.progress)
                    .coerceIn(0f, appInfoPageWidthPx)
            }
            val startOffset = (appInfoPageOffset.value + appInfoBackDragOffsetPx)
                .coerceIn(0f, appInfoPageWidthPx)
            appInfoBackDragOffsetPx = 0f
            appInfoBackGestureActive = false
            appInfoPageOffset.snapTo(startOffset)
            showAppInfoPage = false
        } catch (_: CancellationException) {
            appInfoBackGestureActive = false
            appInfoBackDragOffsetPx = 0f
            appInfoPageOffset.animateTo(
                targetValue = 0f,
                animationSpec = spring(
                    dampingRatio = Spring.DampingRatioNoBouncy,
                    stiffness = Spring.StiffnessMediumLow
                )
            )
        }
    }


    val isLightTheme = !useDarkTheme
    // 浅色：页面底色白，分组卡片浅灰；深色：提高分组/底色层级对比。
    val backgroundColor = if (isLightTheme) Color.White else Color(0xFF0A0A0C)
    val contentColor = if (isLightTheme) Color.Black else Color.White
    val themeModeOptions = remember {
        listOf(
            LiquidSegmentedOption(MainActivity.THEME_MODE_FOLLOW_SYSTEM, "跟随系统"),
            LiquidSegmentedOption(MainActivity.THEME_MODE_LIGHT, "浅色"),
            LiquidSegmentedOption(MainActivity.THEME_MODE_DARK, "深色")
        )
    }
    val autoSwitchModeOptions = remember {
        AUTO_SWITCH_MODE_ITEMS.mapIndexed { index, label ->
            LiquidSegmentedOption(index, label)
        }
    }
    val clockColorModeOptions = remember {
        listOf(
            LiquidSegmentedOption(WallpaperClockColorMode.LIGHT_CLOCK, "浅色时钟"),
            LiquidSegmentedOption(WallpaperClockColorMode.DARK_CLOCK, "深色时钟")
        )
    }
    val generationServiceOptions = remember {
        listOf(
            LiquidSegmentedOption(GradioMcpSogGenerator.SERVICE_TYPE_ONLINE, "在线服务"),
            LiquidSegmentedOption(GradioMcpSogGenerator.SERVICE_TYPE_LOCAL, "本地服务")
        )
    }
    val accentColor = AppAccentColors.resolve(accentColorKey, useDarkTheme)
    val containerColor = if (isLightTheme) {
        Color(0xFFF2F3F7).copy(alpha = 0.78f)
    } else {
        Color(0xFF2A2A2E).copy(alpha = 0.62f)
    }
    val groupBackgroundColor = if (isLightTheme) {
        Color(0xFFF2F3F7)
    } else {
        Color(0xFF1C1C20).copy(alpha = 0.94f)
    }
    val supportsLiquidGlass = LiquidGlassPrefs.isSupported
    val enableLiquidGlass = supportsLiquidGlass && liquidGlassEnabled
    val liquidBackdrop = if (enableLiquidGlass) rememberLayerBackdrop() else null
    val fallbackDialogBackdrop = rememberCanvasBackdrop { drawRect(backgroundColor) }
    val dialogBackdrop = liquidBackdrop ?: fallbackDialogBackdrop
    val scrollState = rememberScrollState()
    val collapsedTitleProgress = (scrollState.value / 56f).coerceIn(0f, 1f)

    Box(modifier = Modifier.fillMaxSize()) {
        // Capture layer: Move settings content inside to allow dialog backdrop sampling
        Box(
            modifier = Modifier
                .fillMaxSize()
                .let { m ->
                    if (enableLiquidGlass && liquidBackdrop != null) {
                        m.layerBackdrop(liquidBackdrop)
                    } else m
                }
        ) {
            Box(Modifier.fillMaxSize().background(backgroundColor))

            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(scrollState)
                    .statusBarsPadding()
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                // Settings Group 1: General
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(28.dp))
                        .background(groupBackgroundColor)
                ) {
                    SettingCheckItem("关闭权限提示", hidePermissionDialog, contentColor, groupBackgroundColor, isLightTheme, enableLiquidGlass) {
                        hidePermissionDialog = it
                        editor.putBoolean("hide_permission_dialog", it).apply()
                    }

                }

                // Settings Group 4: Advanced
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(28.dp))
                        .background(groupBackgroundColor)
                ) {
                    Text(
                        text = "主题模式",
                        style = TextStyle(
                            color = contentColor,
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Medium
                        ),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 24.dp, vertical = 16.dp)
                    )
                    LiquidSegmentedSelector(
                        options = themeModeOptions,
                        enableLiquidGlass = enableLiquidGlass,
                        selectedValue = { themeMode },
                        onValueSelected = { mode ->
                            if (mode != themeMode) {
                                editor.putInt(MainActivity.PREF_THEME_MODE, mode).apply()
                                themeMode = mode
                                onThemeModeChange(mode)
                            }
                        },
                        isLightTheme = isLightTheme,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp)
                            .padding(bottom = 16.dp)
                    )

                    Text(
                        text = "锁屏时钟颜色",
                        style = TextStyle(
                            color = contentColor,
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Medium
                        ),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 24.dp, vertical = 16.dp)
                    )
                    LiquidSegmentedSelector(
                        options = clockColorModeOptions,
                        enableLiquidGlass = enableLiquidGlass,
                        selectedValue = { globalClockColorMode },
                        onValueSelected = { mode ->
                            if (mode != globalClockColorMode) {
                                editor.putInt(WallpaperClockColorMode.PREF_GLOBAL_MODE, mode).apply()
                                globalClockColorMode = mode
                            }
                            applyGlobalClockColorMode(context)
                        },
                        isLightTheme = isLightTheme,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp)
                            .padding(bottom = 16.dp)
                    )
                    Text(
                        text = "强调色",
                        style = TextStyle(
                            color = contentColor,
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Medium
                        ),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 24.dp, vertical = 16.dp)
                    )
                    AccentColorPicker(
                        selectedKey = accentColorKey,
                        useDarkTheme = useDarkTheme,
                        contentColor = contentColor,
                        onSelected = { option ->
                            editor.putString(AppAccentColors.PREF_KEY, option.key).apply()
                            onAccentColorChange(option.key)
                        },
                        enableLiquidGlass = enableLiquidGlass,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 20.dp)
                            .padding(bottom = 16.dp)
                    )
                    SettingCheckItemWithSubtitle(
                        label = "Liquid Glass 效果",
                        subtitle = if (supportsLiquidGlass) {
                            "开启后使用液态玻璃导航、按钮和面板效果"
                        } else {
                            "当前系统版本暂不支持 Liquid Glass"
                        },
                        checked = liquidGlassEnabled,
                        contentColor = contentColor,
                        backgroundColor = groupBackgroundColor,
                        isLightTheme = isLightTheme,
                        enableLiquidGlass = enableLiquidGlass
                    ) {
                        editor.putBoolean(LiquidGlassPrefs.PREF_ENABLE_LIQUID_GLASS, it).apply()
                        onLiquidGlassEnabledChange(it)
                    }
                }

                // Settings Group 2: Video wallpaper
                Column(
                    modifier = Modifier
                        .fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = "光栅壁纸",
                        style = TextStyle(
                            color = contentColor.copy(alpha = 0.72f),
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Medium
                        ),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 8.dp)
                    )
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(28.dp))
                            .background(groupBackgroundColor)
                    ) {
                        SettingCheckItemWithSubtitle(
                            label = "缓存光栅壁纸",
                            subtitle = "把添加的光栅壁纸复制到 app 目录",
                            checked = cacheRasterToAppDir,
                            contentColor = contentColor,
                            backgroundColor = groupBackgroundColor,
                            isLightTheme = isLightTheme,
                            enableLiquidGlass = enableLiquidGlass
                        ) {
                            cacheRasterToAppDir = it
                            editor.putBoolean(WallpaperStoragePrefs.PREF_CACHE_RASTER_TO_APP_DIR, it).apply()
                        }

                        SettingCheckItemWithSubtitle(
                            label = "加速视频光栅壁纸加载",
                            subtitle = "占用更多存储空间，提高视频光栅加载速度",
                            checked = keepVideoCache,
                            contentColor = contentColor,
                            backgroundColor = groupBackgroundColor,
                            isLightTheme = isLightTheme,
                            enableLiquidGlass = enableLiquidGlass
                        ) {
                            keepVideoCache = it
                            editor.putBoolean(RasterPrefs.PREF_KEEP_VIDEO_CACHE, it)
                            if (!it) {
                                // 关闭时不立刻清缓存，标记待清理，重启应用再清
                                editor.putBoolean(RasterPrefs.PREF_PENDING_CLEAR_VIDEO_CACHE, true)
                            }
                            editor.apply()
                        }

                    }
                }

                // Settings Group 3: SOG wallpaper
                Column(
                    modifier = Modifier
                        .fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = "景深壁纸",
                        style = TextStyle(
                            color = contentColor.copy(alpha = 0.72f),
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Medium
                        ),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 8.dp)
                    )
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(28.dp))
                            .background(groupBackgroundColor)
                    ) {

                        SettingCheckItemWithSubtitle(
                            label = "缓存景深壁纸",
                            subtitle = "把添加的 SOG 复制到 app 目录",
                            checked = cacheDepthToAppDir,
                            contentColor = contentColor,
                            backgroundColor = groupBackgroundColor,
                            isLightTheme = isLightTheme,
                            enableLiquidGlass = enableLiquidGlass
                        ) {
                            cacheDepthToAppDir = it
                            editor.putBoolean(WallpaperStoragePrefs.PREF_CACHE_DEPTH_TO_APP_DIR, it).apply()
                        }

                        SettingCheckItemWithSubtitle(
                            label = "性能模式",
                            subtitle = "降低渲染负载，保证流畅度",
                            checked = webPerformanceMode,
                            contentColor = contentColor,
                            backgroundColor = groupBackgroundColor,
                            isLightTheme = isLightTheme,
                            enableLiquidGlass = enableLiquidGlass
                        ) {
                            webPerformanceMode = it
                            editor.putBoolean(DepthPrefs.PREF_WEB_PERFORMANCE_MODE, it).apply()
                        }

                        LiquidSegmentedSelector(
                            options = generationServiceOptions,
                            enableLiquidGlass = enableLiquidGlass,
                            selectedValue = { generationServiceType },
                            onValueSelected = { serviceType ->
                                if (serviceType != generationServiceType) {
                                    generationServiceType = serviceType
                                    GradioMcpSogGenerator.setGenerationServiceType(
                                        context.applicationContext,
                                        serviceType
                                    )
                                    onlineGenerationAddressText = GradioMcpSogGenerator
                                        .getGenerationBaseUrl(context.applicationContext, serviceType)
                                }
                            },
                            isLightTheme = isLightTheme,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 8.dp)
                        )
                        Column(Modifier.fillMaxWidth()) {
                            AnimatedVisibility(
                                visible = generationServiceType == GradioMcpSogGenerator.SERVICE_TYPE_ONLINE,
                                enter = fadeIn(animationSpec = spring(stiffness = Spring.StiffnessLow)) +
                                    expandVertically(animationSpec = spring(stiffness = Spring.StiffnessLow)),
                                exit = fadeOut(animationSpec = spring(stiffness = Spring.StiffnessLow)) +
                                    shrinkVertically(animationSpec = spring(stiffness = Spring.StiffnessLow))
                            ) {
                                Column {
                                    SettingTextItem(
                                        label = "在线生成地址",
                                        contentColor = contentColor,
                                        onClick = {
                                            onlineGenerationAddressText = GradioMcpSogGenerator
                                                .getGenerationBaseUrl(
                                                    context.applicationContext,
                                                    GradioMcpSogGenerator.SERVICE_TYPE_ONLINE
                                                )
                                            showOnlineGenerationAddressDialog = true
                                        }
                                    )
                                    SettingActionItemWithSubtitle(
                                        label = "SDK Token",
                                        subtitle = if (modelScopeTokenText.isNotBlank()) "已配置" else "未配置",
                                        contentColor = contentColor,
                                        accentColor = accentColor,
                                        onClick = {
                                            modelScopeTokenText = GradioMcpSogGenerator
                                                .getModelScopeToken(context.applicationContext)
                                                .orEmpty()
                                            showModelScopeTokenDialog = true
                                        }
                                    )
                                }
                            }

                            AnimatedVisibility(
                                visible = generationServiceType == GradioMcpSogGenerator.SERVICE_TYPE_LOCAL,
                                enter = fadeIn(animationSpec = spring(stiffness = Spring.StiffnessLow)) +
                                    expandVertically(animationSpec = spring(stiffness = Spring.StiffnessLow)),
                                exit = fadeOut(animationSpec = spring(stiffness = Spring.StiffnessLow)) +
                                    shrinkVertically(animationSpec = spring(stiffness = Spring.StiffnessLow))
                            ) {
                                SettingTextItem(
                                    label = "本地生成地址",
                                    contentColor = contentColor,
                                    onClick = {
                                        onlineGenerationAddressText = GradioMcpSogGenerator
                                            .getGenerationBaseUrl(
                                                context.applicationContext,
                                                GradioMcpSogGenerator.SERVICE_TYPE_LOCAL
                                            )
                                        showOnlineGenerationAddressDialog = true
                                    }
                                )
                            }
                        }
                    }
                }

                // Settings Group 5: Static wallpaper
                Column(
                    modifier = Modifier
                        .fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = "图片/视频壁纸",
                        style = TextStyle(
                            color = contentColor.copy(alpha = 0.72f),
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Medium
                        ),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 8.dp)
                    )
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(28.dp))
                            .background(groupBackgroundColor)
                    ) {
                        SettingCheckItemWithSubtitle(
                            label = "缓存图片/视频壁纸",
                            subtitle = "把添加的普通图片/视频复制到 app 目录",
                            checked = cacheRegularToAppDir,
                            contentColor = contentColor,
                            backgroundColor = groupBackgroundColor,
                            isLightTheme = isLightTheme,
                            enableLiquidGlass = enableLiquidGlass
                        ) {
                            cacheRegularToAppDir = it
                            editor.putBoolean(WallpaperStoragePrefs.PREF_CACHE_REGULAR_TO_APP_DIR, it).apply()
                        }
                        SettingCheckItem("壁纸跟随屏幕滚动", wallpaperScroll, contentColor, groupBackgroundColor, isLightTheme, enableLiquidGlass) {
                            wallpaperScroll = it
                            editor.putBoolean("wallpaperScroll", it).apply()
                            if (it && pageChange) {
                                pageChange = false
                                editor.putBoolean("pageChange", false).apply()
                            }
                        }
                        SettingCheckItem("随机切换壁纸", rand, contentColor, groupBackgroundColor, isLightTheme, enableLiquidGlass) {
                            rand = it
                            editor.putBoolean("rand", it).apply()
                        }
                        LiquidSegmentedSelector(
                            enableLiquidGlass = enableLiquidGlass,
                            options = autoSwitchModeOptions,
                            selectedValue = { autoSwitchMode },
                            onValueSelected = { index ->
                                if (index != autoSwitchMode) {
                                    editor.putInt(TianYinWallpaperService.PREF_AUTO_SWITCH_MODE, index)
                                    editor.putLong(TianYinWallpaperService.PREF_AUTO_SWITCH_ANCHOR_AT, System.currentTimeMillis())
                                    editor.putLong(TianYinWallpaperService.PREF_AUTO_SWITCH_LAST_SWITCH_AT, 0L)
                                    editor.apply()
                                    autoSwitchMode = index
                                    autoSwitchInterval = pref.getLong(
                                        TianYinWallpaperService.PREF_AUTO_SWITCH_INTERVAL_SECONDS,
                                        pref.getLong("autoSwitchIntervalMinutes", 60L) * 60L
                                    )
                                    autoSwitchPoints = pref.getString(
                                        TianYinWallpaperService.PREF_AUTO_SWITCH_TIME_POINTS,
                                        DEFAULT_AUTO_SWITCH_TIME_POINTS
                                    ).takeUnless { TextUtils.isEmpty(it) } ?: DEFAULT_AUTO_SWITCH_TIME_POINTS
                                }
                            },
                            isLightTheme = isLightTheme,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp)
                                .padding(top = 16.dp, bottom = 12.dp)
                        )

                        val autoModeDetailSpacer by animateDpAsState(
                            targetValue = 4.dp,
                            animationSpec = spring(stiffness = Spring.StiffnessMediumLow),
                            label = "AutoModeDetailSpacer"
                        )
                        Spacer(Modifier.height(autoModeDetailSpacer))

                        Column(Modifier.fillMaxWidth()) {
                            AnimatedVisibility(
                                visible = autoSwitchMode == AUTO_SWITCH_MODE_NONE,
                                enter = fadeIn(animationSpec = spring(stiffness = Spring.StiffnessLow)) +
                                    expandVertically(animationSpec = spring(stiffness = Spring.StiffnessLow)),
                                exit = fadeOut(animationSpec = spring(stiffness = Spring.StiffnessLow)) +
                                    shrinkVertically(animationSpec = spring(stiffness = Spring.StiffnessLow))
                            ) {
                                Column {

                                    SettingTextItem("壁纸最小切换时间: ${minTime}秒", contentColor) {
                                        tempMinTime = minTime
                                        showMinTimeDialog = true
                                    }
                                    SettingCheckItem("滑动桌面切换壁纸", pageChange, contentColor, groupBackgroundColor, isLightTheme, enableLiquidGlass) {
                                        pageChange = it
                                        editor.putBoolean("pageChange", it).apply()
                                        if (it && wallpaperScroll) {
                                            wallpaperScroll = false
                                            editor.putBoolean("wallpaperScroll", false).apply()
                                        }
                                    }
                                }
                            }

                            AnimatedVisibility(
                                visible = autoSwitchMode == 1,
                                enter = fadeIn(animationSpec = spring(stiffness = Spring.StiffnessLow)) +
                                    expandVertically(animationSpec = spring(stiffness = Spring.StiffnessLow)),
                                exit = fadeOut(animationSpec = spring(stiffness = Spring.StiffnessLow)) +
                                    shrinkVertically(animationSpec = spring(stiffness = Spring.StiffnessLow))
                            ) {
                                val intervalText = remember(autoSwitchInterval) {
                                    val d = autoSwitchInterval / (24 * 3600)
                                    val h = (autoSwitchInterval % (24 * 3600)) / 3600
                                    val m = (autoSwitchInterval % 3600) / 60
                                    val s = autoSwitchInterval % 60
                                    buildString {
                                        if (d > 0) append("${d}天")
                                        if (h > 0) append("${h}时")
                                        if (m > 0 || (d == 0L && h == 0L)) append("${m}分")
                                        if (s > 0) append("${s}秒")
                                    }
                                }
                                SettingTextItem("自动切换间隔：$intervalText", contentColor) {
                                    showAutoIntervalDialog = true
                                }
                            }

                            AnimatedVisibility(
                                visible = autoSwitchMode == 2,
                                enter = fadeIn(animationSpec = spring(stiffness = Spring.StiffnessLow)) +
                                    expandVertically(animationSpec = spring(stiffness = Spring.StiffnessLow)),
                                exit = fadeOut(animationSpec = spring(stiffness = Spring.StiffnessLow)) +
                                    shrinkVertically(animationSpec = spring(stiffness = Spring.StiffnessLow))
                            ) {
                                SettingTextItem("自动切换时间点：$autoSwitchPoints", contentColor) {
                                    autoPointsInput = autoSwitchPoints
                                    showAutoPointsDialog = true
                                }
                            }
                        }

                    }
                }

                // Settings Group 4: About
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(28.dp))
                        .background(groupBackgroundColor)
                ) {
                    if (testPagesVisible) {
                        SettingTextItem("SOG测试", contentColor) {
                            onOpenPlyModelTest()
                        }
                        SettingTextItem("玻璃测试", contentColor) {
                            onOpenCorrugatedTest()
                        }
                    }
                    SettingTextItem("数据与存储", contentColor) {
                        onOpenDataStorage()
                    }
                    SettingTextItem("使用说明", contentColor) {
                        onOpenUsage()
                    }
                    SettingTextItem("关于", contentColor) {
                        onOpenAppInfo()
                    }
                }
                
                Spacer(modifier = Modifier.height(110.dp))
            }
        }

        if (collapsedTitleProgress > 0f) {
            ProgressiveBlurContent(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .fillMaxWidth()
                    .graphicsLayer { alpha = collapsedTitleProgress }
                    .zIndex(2f),
                backdrop = liquidBackdrop
            )
        }

        if (renderAppInfoPage || showAppInfoPage) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .zIndex(4f)
                    .graphicsLayer {
                        translationX = appInfoPageOffset.value + appInfoBackDragOffsetPx
                        alpha = 1f
                    }
            ) {
                AppInfoRouteScreen(
                    useDarkTheme = useDarkTheme,
                    onBack = { closeAppInfoPage() },
                    enableLiquidGlass = enableLiquidGlass
                )
            }

            // 左边缘拖拽返回：作为非系统预测性返回时的补充，返回时只移动、不变淡。
            Box(
                modifier = Modifier
                    .fillMaxHeight()
                    .width(40.dp)
                    .align(Alignment.CenterStart)
                    .zIndex(6f)
                    .pointerInput(showAppInfoPage, appInfoPageWidthPx) {
                        detectDragGestures(
                            onDragStart = { offset ->
                                appInfoBackGestureActive = showAppInfoPage && offset.x <= appInfoBackEdgePx
                            },
                            onDragCancel = {
                                appInfoBackGestureActive = false
                                appInfoBackDragOffsetPx = 0f
                            },
                            onDragEnd = {
                                if (appInfoBackGestureActive && appInfoBackDragOffsetPx > appInfoPageWidthPx * 0.28f) {
                                    closeAppInfoPage()
                                } else {
                                    appInfoBackDragOffsetPx = 0f
                                }
                                appInfoBackGestureActive = false
                            },
                            onDrag = { change, dragAmount ->
                                if (appInfoBackGestureActive) {
                                    val nextOffset = (appInfoBackDragOffsetPx + dragAmount.x)
                                        .coerceIn(0f, appInfoPageWidthPx)
                                    if (nextOffset != appInfoBackDragOffsetPx) {
                                        appInfoBackDragOffsetPx = nextOffset
                                    }
                                }
                            }
                        )
                    }
            )
        }

        LiquidDialog(
                visible = showOnlineGenerationAddressDialog,
                backdrop = dialogBackdrop,
                isLightTheme = isLightTheme,
                onDismissRequest = { showOnlineGenerationAddressDialog = false },
                onConfirm = {
                    runCatching {
                        GradioMcpSogGenerator.setGenerationBaseUrl(
                            context.applicationContext,
                            generationServiceType,
                            onlineGenerationAddressText
                        )
                    }.onSuccess { normalized ->
                        onlineGenerationAddressText = normalized
                        showOnlineGenerationAddressDialog = false
                        Toast.makeText(context, "生成地址已保存", Toast.LENGTH_SHORT).show()
                    }.onFailure {
                        Toast.makeText(context, it.message ?: "生成地址无效", Toast.LENGTH_SHORT).show()
                    }
                },
                title = if (generationServiceType == GradioMcpSogGenerator.SERVICE_TYPE_LOCAL) {
                    "本地生成地址"
                } else {
                    "在线生成地址"
                },
                confirmText = "保存",
                dismissText = "取消",
                customContent = {
                    BasicTextField(
                        value = onlineGenerationAddressText,
                        onValueChange = { onlineGenerationAddressText = it },
                        singleLine = true,
                        textStyle = TextStyle(color = contentColor, fontSize = 14.sp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(18.dp))
                            .background(groupBackgroundColor)
                            .padding(horizontal = 16.dp, vertical = 14.dp),
                        decorationBox = { innerTextField ->
                            if (onlineGenerationAddressText.isBlank()) {
                                Text(
                                    text = if (
                                        generationServiceType == GradioMcpSogGenerator.SERVICE_TYPE_LOCAL
                                    ) {
                                        "例如 http://192.168.1.177:7860"
                                    } else {
                                        "例如 https://example.com"
                                    },
                                    color = contentColor.copy(alpha = 0.42f),
                                    fontSize = 13.sp
                                )
                            }
                            innerTextField()
                        }
                    )
                }
            )

        LiquidDialog(
                visible = showModelScopeTokenDialog,
                backdrop = dialogBackdrop,
                isLightTheme = isLightTheme,
                onDismissRequest = { showModelScopeTokenDialog = false },
                onConfirm = {
                    val token = modelScopeTokenText.trim()
                    GradioMcpSogGenerator.setModelScopeToken(context.applicationContext, token)
                    GradioMcpSogGenerator.resetMcpState()
                    modelScopeTokenText = token
                    showModelScopeTokenDialog = false
                    Toast.makeText(
                        context,
                        if (token.isBlank()) "Token 已清除" else "Token 已保存",
                        Toast.LENGTH_SHORT
                    ).show()
                },
                title = "ModelScope SDK Token",
                confirmText = "保存",
                dismissText = "取消",
                customContent = {
                    BasicTextField(
                        value = modelScopeTokenText,
                        onValueChange = { modelScopeTokenText = it },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        textStyle = TextStyle(color = contentColor, fontSize = 14.sp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(18.dp))
                            .background(groupBackgroundColor)
                            .padding(horizontal = 16.dp, vertical = 14.dp),
                        decorationBox = { innerTextField ->
                            if (modelScopeTokenText.isBlank()) {
                                Text(
                                    text = "粘贴 ms-… token，留空保存可清除",
                                    color = contentColor.copy(alpha = 0.42f),
                                    fontSize = 13.sp
                                )
                            }
                            innerTextField()
                        }
                    )
                }
            )

        // 更新对话框
        UpdateDialog(
            state = updateDialogState,
            parentBackdrop = liquidBackdrop,
            onDismiss = {
                updateDialogState = UpdateDialogState(isVisible = false)
                shouldCheckUpdate = false
            },
            onConfirm = {
                val info = updateDialogState.updateInfo
                if (info != null) {
                    // 开始下载
                    updateDialogState = updateDialogState.copy(
                        isDownloading = true,
                        downloadProgress = 0
                    )
                    AppUpdateManager.downloadApk(context, info, object : AppUpdateManager.DownloadCallback {
                        override fun onProgress(progress: Int) {
                            updateDialogState = updateDialogState.copy(downloadProgress = progress)
                        }
                        
                        override fun onSuccess(file: java.io.File) {
                            updateDialogState = updateDialogState.copy(isDownloading = false)
                            // 验证 MD5
                            val md5 = AppUpdateManager.calculateMD5(file)
                            val apkVersionCode = AppUpdateManager.getApkVersionCode(context, file)
                            if (apkVersionCode != info.code.toLong()) {
                                Toast.makeText(context, "下载的安装包版本不匹配，请重新检查更新", Toast.LENGTH_SHORT).show()
                            } else if (md5 != null && md5.equals(info.md5, ignoreCase = true)) {
                                AppUpdateManager.installApk(context, file)
                            } else {
                                Toast.makeText(context, "文件校验失败，请重新下载", Toast.LENGTH_SHORT).show()
                            }
                        }
                        
                        override fun onError(message: String) {
                            updateDialogState = updateDialogState.copy(
                                isDownloading = false,
                                errorMessage = message
                            )
                            Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
                        }
                    })
                } else if (updateDialogState.isLatestVersion) {
                    // 已是最新版本，关闭对话框
                    updateDialogState = UpdateDialogState(isVisible = false)
                    shouldCheckUpdate = false
                } else {
                    // 重新检查更新
                    shouldCheckUpdate = true
                    updateDialogState = UpdateDialogState(isVisible = true, isChecking = true)
                }
            },
            isLightTheme = isLightTheme
        )

        // 检查更新副作用
        androidx.compose.runtime.LaunchedEffect(shouldCheckUpdate) {
            if (shouldCheckUpdate) {
                when (val result = AppUpdateManager.checkUpdate()) {
                    is AppUpdateManager.CheckResult.HasUpdate -> {
                        updateDialogState = UpdateDialogState(
                            isVisible = true,
                            isChecking = false,
                            updateInfo = result.updateInfo
                        )
                    }
                    is AppUpdateManager.CheckResult.NoUpdate -> {
                        updateDialogState = UpdateDialogState(
                            isVisible = true,
                            isChecking = false,
                            isLatestVersion = true
                        )
                    }
                    is AppUpdateManager.CheckResult.Error -> {
                        updateDialogState = UpdateDialogState(
                            isVisible = true,
                            isChecking = false,
                            errorMessage = result.message
                        )
                    }
                }
            }
        }

        // 1. Custom Liquid Glass Dialog
        LiquidWindowAnimatedContent(
            targetState = currentDialogState,
            contentAlignment = Alignment.Center,
            label = "SettingsDialogOverlay",
            modifier = Modifier
                .fillMaxSize()
                .let { modifier ->
                    if (currentDialogState != null) {
                        modifier.pointerInput(currentDialogState) {
                            detectTapGestures {
                                showMinTimeDialog = false
                                showAutoIntervalDialog = false
                                showAutoPointsDialog = false
                                showTimePickerDialog = false
                            }
                        }
                    } else {
                        modifier
                    }
                }
        ) { state ->
            if (state != null) {
                val dialogBackdrop = liquidBackdrop ?: rememberCanvasBackdrop { drawRect(containerColor) }
                Column(
                    Modifier
                        .padding(50f.dp)
                        .wrapContentHeight()
                        .drawBackdrop(
                            backdrop = dialogBackdrop,
                            shape = { RoundedRectangle(48f.dp) },
                            effects = {
                                colorControls(
                                    brightness = if (isLightTheme) 0.2f else 0f,
                                    saturation = 1.5f
                                )
                                blur(if (isLightTheme) 16f.dp.toPx() else 8f.dp.toPx())
                                lens(24f.dp.toPx(), 48f.dp.toPx(), depthEffect = true)
                            },
                            highlight = { Highlight.Plain },
                            onDrawSurface = { drawRect(containerColor) }
                        )
                        .pointerInput(Unit) { detectTapGestures { /* consume */ } }
                ) {
                    when (state) {
                        SettingsDialogState.MinTime -> {
                            Column(
                                Modifier.padding(16.dp, 20.dp, 16.dp, 20.dp).fillMaxWidth(),
                                verticalArrangement = Arrangement.spacedBy(12.dp),
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                BasicText("最小切换时间(秒)", style = TextStyle(contentColor, 18.sp, fontWeight = FontWeight.Bold))

                                Row(
                                    modifier = Modifier.fillMaxWidth().height(160.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.Center
                                ) {
                                    WheelPicker(
                                        count = 61,
                                        initialIndex = tempMinTime.coerceIn(0, 60),
                                        onItemSelected = { tempMinTime = it },
                                        contentColor = contentColor,
                                        label = "秒",
                                        modifier = Modifier.weight(1f)
                                    )
                                }

                                Row(
                                    Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                                ) {
                                    Row(
                                        Modifier
                                            .weight(1f)
                                            .clip(Capsule())
                                            .background(accentColor)
                                            .clickable {
                                                editor.putInt("minTime", tempMinTime).apply()
                                                minTime = tempMinTime
                                                showMinTimeDialog = false
                                            }
                                            .height(48.dp),
                                        horizontalArrangement = Arrangement.Center,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        BasicText("确定", style = TextStyle(Color.White, 16.sp))
                                    }
                                    Row(
                                        Modifier
                                            .weight(1f)
                                            .clip(Capsule())
                                            .background(containerColor.copy(0.2f))
                                            .clickable { showMinTimeDialog = false }
                                            .height(48.dp),
                                        horizontalArrangement = Arrangement.Center,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        BasicText("取消", style = TextStyle(contentColor, 16.sp))
                                    }
                                }
                            }
                        }
                        SettingsDialogState.AutoInterval -> {
                            var selectedDays by remember { mutableStateOf((autoSwitchInterval / (24 * 3600)).toInt()) }
                            var selectedHours by remember { mutableStateOf(((autoSwitchInterval % (24 * 3600)) / 3600).toInt()) }
                            var selectedMinutes by remember { mutableStateOf(((autoSwitchInterval % 3600) / 60).toInt()) }
                            var selectedSeconds by remember { mutableStateOf((autoSwitchInterval % 60).toInt()) }

                            Column(
                                Modifier.padding(16.dp, 20.dp, 16.dp, 20.dp).fillMaxWidth(),
                                verticalArrangement = Arrangement.spacedBy(12.dp),
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                BasicText("自动切换间隔", style = TextStyle(contentColor, 18.sp, fontWeight = FontWeight.Bold))

                                Row(
                                    modifier = Modifier.fillMaxWidth().height(160.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.Center
                                ) {
                                    WheelPicker(
                                        count = 31,
                                        initialIndex = selectedDays,
                                        onItemSelected = { selectedDays = it },
                                        contentColor = contentColor,
                                        label = ":",
                                        modifier = Modifier.weight(1f)
                                    )
                                    WheelPicker(
                                        count = 24,
                                        initialIndex = selectedHours,
                                        onItemSelected = { selectedHours = it },
                                        contentColor = contentColor,
                                        label = ":",
                                        modifier = Modifier.weight(1f)
                                    )
                                    WheelPicker(
                                        count = 60,
                                        initialIndex = selectedMinutes,
                                        onItemSelected = { selectedMinutes = it },
                                        contentColor = contentColor,
                                        label = ":",
                                        modifier = Modifier.weight(1f)
                                    )
                                    WheelPicker(
                                        count = 60,
                                        initialIndex = selectedSeconds,
                                        onItemSelected = { selectedSeconds = it },
                                        contentColor = contentColor,
                                        label = "",
                                        modifier = Modifier.weight(1f)
                                    )
                                }

                                Row(
                                    Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                                ) {
                                    Row(
                                        Modifier
                                            .weight(1f)
                                            .clip(Capsule())
                                            .background(accentColor)
                                            .clickable {
                                                val totalSeconds = selectedDays * 24 * 3600L + selectedHours * 3600L + selectedMinutes * 60L + selectedSeconds
                                                if (totalSeconds > 0) {
                                                    editor.putLong(TianYinWallpaperService.PREF_AUTO_SWITCH_INTERVAL_SECONDS, totalSeconds)
                                                    editor.putLong(TianYinWallpaperService.PREF_AUTO_SWITCH_ANCHOR_AT, System.currentTimeMillis())
                                                    editor.putLong(TianYinWallpaperService.PREF_AUTO_SWITCH_LAST_SWITCH_AT, 0L)
                                                    editor.apply()
                                                    autoSwitchInterval = totalSeconds
                                                    showAutoIntervalDialog = false
                                                } else {
                                                    Toast.makeText(context, "间隔必须大于0", Toast.LENGTH_SHORT).show()
                                                }
                                            }
                                            .height(48.dp),
                                        horizontalArrangement = Arrangement.Center,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        BasicText("确定", style = TextStyle(Color.White, 16.sp))
                                    }
                                    Row(
                                        Modifier
                                            .weight(1f)
                                            .clip(Capsule())
                                            .background(containerColor.copy(0.2f))
                                            .clickable { showAutoIntervalDialog = false }
                                            .height(48.dp),
                                        horizontalArrangement = Arrangement.Center,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        BasicText("取消", style = TextStyle(contentColor, 16.sp))
                                    }
                                }
                            }
                        }
                        SettingsDialogState.AutoPoints -> {
                            val points = remember(autoPointsInput) {
                                autoPointsInput.split(",").filter { it.isNotBlank() }
                            }
                            Column(
                                Modifier.padding(16.dp, 20.dp, 16.dp, 20.dp).fillMaxWidth(),
                                verticalArrangement = Arrangement.spacedBy(12.dp),
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                BasicText("设置自动切换时间点", style = TextStyle(contentColor, 18.sp, fontWeight = FontWeight.Bold))

                                Column(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .heightIn(max = 280.dp)
                                        .clip(RoundedCornerShape(24.dp))
                                        .background(containerColor.copy(0.1f))
                                        .padding(16.dp)
                                        .verticalScroll(rememberScrollState()),
                                    verticalArrangement = Arrangement.spacedBy(8.dp),
                                    horizontalAlignment = Alignment.Start
                                ) {
                                    if (points.isEmpty()) {
                                        BasicText("暂无时间点", style = TextStyle(contentColor.copy(0.5f), 14.sp))
                                    } else {
                                        points.forEachIndexed { index, point ->
                                            Row(
                                                Modifier
                                                    .fillMaxWidth()
                                                    .clip(Capsule())
                                                    .background(containerColor.copy(0.1f))
                                                    .padding(horizontal = 16.dp, vertical = 8.dp),
                                                horizontalArrangement = Arrangement.SpaceBetween,
                                                verticalAlignment = Alignment.CenterVertically
                                            ) {
                                                BasicText("${index + 1}. $point", style = TextStyle(contentColor, 16.sp))
                                                BasicText(
                                                    "删除",
                                                    style = TextStyle(Color.Red.copy(0.8f), 14.sp),
                                                    modifier = Modifier.clickable {
                                                        autoPointsInput = points.toMutableList().apply { removeAt(index) }.joinToString(",")
                                                    }
                                                )
                                            }
                                        }
                                    }
                                }

                                Row(
                                    Modifier
                                        .fillMaxWidth()
                                        .clip(Capsule())
                                        .background(accentColor.copy(0.2f))
                                        .clickable {
                                            showAutoPointsDialog = false
                                            showTimePickerDialog = true
                                        }
                                        .height(48.dp),
                                    horizontalArrangement = Arrangement.Center,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    BasicText("+ 添加时间点", style = TextStyle(accentColor, 16.sp, fontWeight = FontWeight.Bold))
                                }

                                Row(
                                    Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                                ) {
                                    Row(
                                        Modifier
                                            .weight(1f)
                                            .clip(Capsule())
                                            .background(accentColor)
                                            .clickable {
                                                if (autoPointsInput.isNotBlank()) {
                                                    editor.putString(TianYinWallpaperService.PREF_AUTO_SWITCH_TIME_POINTS, autoPointsInput)
                                                    editor.putLong(TianYinWallpaperService.PREF_AUTO_SWITCH_ANCHOR_AT, System.currentTimeMillis())
                                                    editor.putLong(TianYinWallpaperService.PREF_AUTO_SWITCH_LAST_SWITCH_AT, 0L)
                                                    editor.apply()
                                                    autoSwitchPoints = autoPointsInput
                                                    showAutoPointsDialog = false
                                                } else {
                                                    Toast.makeText(context, "请至少添加一个时间点", Toast.LENGTH_SHORT).show()
                                                }
                                            }
                                            .height(48.dp),
                                        horizontalArrangement = Arrangement.Center,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        BasicText("确定", style = TextStyle(Color.White, 16.sp))
                                    }
                                    Row(
                                        Modifier
                                            .weight(1f)
                                            .clip(Capsule())
                                            .background(containerColor.copy(0.2f))
                                            .clickable { showAutoPointsDialog = false }
                                            .height(48.dp),
                                        horizontalArrangement = Arrangement.Center,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        BasicText("取消", style = TextStyle(contentColor, 16.sp))
                                    }
                                }
                            }
                        }
                        SettingsDialogState.PickTime -> {
                            Column(
                                Modifier.padding(16.dp, 20.dp, 16.dp, 20.dp).fillMaxWidth(),
                                verticalArrangement = Arrangement.spacedBy(12.dp),
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                BasicText("选择时间", style = TextStyle(contentColor, 18.sp, fontWeight = FontWeight.Bold))

                                Row(
                                    modifier = Modifier.fillMaxWidth().height(160.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.Center
                                ) {
                                    WheelPicker(
                                        count = 24,
                                        initialIndex = pickingHour,
                                        onItemSelected = { pickingHour = it },
                                        contentColor = contentColor,
                                        label = "时",
                                        modifier = Modifier.weight(1f)
                                    )
                                    WheelPicker(
                                        count = 60,
                                        initialIndex = pickingMinute,
                                        onItemSelected = { pickingMinute = it },
                                        contentColor = contentColor,
                                        label = "分",
                                        modifier = Modifier.weight(1f)
                                    )
                                }

                                Row(
                                    Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                                ) {
                                    Row(
                                        Modifier
                                            .weight(1f)
                                            .clip(Capsule())
                                            .background(accentColor)
                                            .clickable {
                                                val newTime = String.format(Locale.getDefault(), "%02d:%02d", pickingHour, pickingMinute)
                                                val currentPoints = autoPointsInput.split(",").filter { it.isNotBlank() }.toMutableList()
                                                if (!currentPoints.contains(newTime)) {
                                                    currentPoints.add(newTime)
                                                    currentPoints.sort()
                                                    autoPointsInput = currentPoints.joinToString(",")
                                                }
                                                showTimePickerDialog = false
                                                showAutoPointsDialog = true
                                            }
                                            .height(48.dp),
                                        horizontalArrangement = Arrangement.Center,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        BasicText("确定", style = TextStyle(Color.White, 16.sp))
                                    }
                                    Row(
                                        Modifier
                                            .weight(1f)
                                            .clip(Capsule())
                                            .background(containerColor.copy(0.2f))
                                            .clickable {
                                                showTimePickerDialog = false
                                                showAutoPointsDialog = true
                                            }
                                            .height(48.dp),
                                        horizontalArrangement = Arrangement.Center,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        BasicText("取消", style = TextStyle(contentColor, 16.sp))
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun DataStorageRouteScreen(
    useDarkTheme: Boolean,
    onBack: (() -> Unit)? = null,
    enableLiquidGlass: Boolean = true
) {
    val context = LocalContext.current
    val density = LocalDensity.current
    val statusBarTopPadding = remember(context) {
        val id = context.resources.getIdentifier("status_bar_height", "dimen", "android")
        if (id > 0) context.resources.getDimensionPixelSize(id) else 0
    }
    val statusBarTopPaddingDp = with(density) { statusBarTopPadding.toDp() }
    val isLightTheme = !useDarkTheme
    val backgroundColor = if (isLightTheme) Color.White else Color(0xFF0A0A0C)
    val cardColor = if (isLightTheme) {
        Color(0xFFF2F3F7)
    } else {
        Color(0xFF1C1C20).copy(alpha = 0.94f)
    }
    val contentColor = if (isLightTheme) Color.Black else Color.White
    val secondaryColor = contentColor.copy(alpha = 0.52f)
    val appColor = Color(0xFF2F80ED)
    val dataColor = Color(0xFFFFB703)
    val cacheColor = Color(0xFF06C999)
    val dangerColor = Color(0xFFE83A4A)
    val topSurfaceColor = if (isLightTheme) Color.White.copy(alpha = 0.3f) else Color.Black.copy(alpha = 0.3f)
    val useLiquidGlass = enableLiquidGlass && LiquidGlassPrefs.isEnabled(context)
    val liquidBackdrop = if (useLiquidGlass) rememberLayerBackdrop() else null
    val topBackdrop = if (useLiquidGlass) rememberLayerBackdrop() else null
    val storageStats = remember(context) { collectStorageStats(context) }
    val totalBytes = storageStats.totalBytes.coerceAtLeast(1L).toFloat()
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    var confirmAction by remember { mutableStateOf<DataStorageConfirmAction?>(null) }
    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/zip")
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val success = withContext(Dispatchers.IO) { exportSettingsBackup(context, uri) }
            Toast.makeText(
                context,
                if (success) "备份已导出" else "备份导出失败",
                Toast.LENGTH_SHORT
            ).show()
        }
    }
    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val success = withContext(Dispatchers.IO) { importSettingsBackup(context, uri) }
            Toast.makeText(
                context,
                if (success) "备份已导入" else "备份导入失败",
                Toast.LENGTH_SHORT
            ).show()
            if (success) recreateHostActivity(context)
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .let { modifier ->
                    if (topBackdrop != null) modifier.layerBackdrop(topBackdrop) else modifier
                }
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .let { modifier ->
                        if (liquidBackdrop != null) modifier.layerBackdrop(liquidBackdrop) else modifier
                    }
            ) {
                Box(Modifier.fillMaxSize().background(backgroundColor))
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .statusBarsPadding()
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    if (onBack != null) {
                        Spacer(modifier = Modifier.height(56.dp))
                    } else {
                        Spacer(modifier = Modifier.height(48.dp))
                    }

                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(
                            painter = painterResource(R.drawable.storage),
                            contentDescription = null,
                            tint = Color.Unspecified,
                            modifier = Modifier
                                .width(112.dp)
                                .height(81.dp)
                        )
                        Text(
                            text = "数据与存储",
                            color = contentColor,
                            fontSize = 28.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = "管理App存储空间占用",
                            color = secondaryColor,
                            fontSize = 14.sp
                        )
                    }

            Text(
                text = "存储空间用量",
                style = TextStyle(
                    color = secondaryColor,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp)
            )

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(28.dp))
                    .background(cardColor)
                    .padding(horizontal = 24.dp, vertical = 16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(18.dp)
                        .clip(Capsule())
                        .background(contentColor.copy(alpha = 0.08f))
                ) {
                    Box(
                        modifier = Modifier
                            .weight((storageStats.appBytes / totalBytes).coerceAtLeast(0.001f))
                            .fillMaxHeight()
                            .background(appColor)
                    )
                    Box(
                        modifier = Modifier
                            .weight((storageStats.userBytes / totalBytes).coerceAtLeast(0.001f))
                            .fillMaxHeight()
                            .background(dataColor)
                    )
                    Box(
                        modifier = Modifier
                            .weight((storageStats.cacheBytes / totalBytes).coerceAtLeast(0.001f))
                            .fillMaxHeight()
                            .background(cacheColor)
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    StorageLegendItem(color = appColor, label = "应用大小", contentColor = secondaryColor)
                    StorageLegendItem(color = dataColor, label = "用户数据", contentColor = secondaryColor)
                    StorageLegendItem(color = cacheColor, label = "缓存", contentColor = secondaryColor)
                }

                StorageUsageRow("应用大小", formatStorageSize(context, storageStats.appBytes), secondaryColor, contentColor)
                StorageUsageRow("用户数据", formatStorageSize(context, storageStats.userBytes), secondaryColor, contentColor)
                Column(
                    modifier = Modifier.padding(start = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(9.dp)
                ) {
                    storageStats.userBreakdown.forEach { item ->
                        StorageUsageDetailRow(
                            label = item.label,
                            value = formatStorageSize(context, item.bytes),
                            color = secondaryColor
                        )
                    }
                }
                StorageUsageRow("缓存", formatStorageSize(context, storageStats.cacheBytes), secondaryColor, contentColor)
                StorageUsageRow("合计", formatStorageSize(context, storageStats.totalBytes), contentColor, contentColor)
            }

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(28.dp))
                    .background(cardColor)
            ) {
                DataStorageActionRow("导出备份文件", contentColor) {
                    exportLauncher.launch(defaultBackupFileName())
                }
                DataStorageActionRow("导入备份文件", contentColor) {
                    importLauncher.launch(arrayOf("application/zip", "application/octet-stream", "*/*"))
                }
            }

            Text(
                text = "重置",
                style = TextStyle(
                    color = secondaryColor,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp)
            )

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(28.dp))
                    .background(cardColor)
            ) {
                DataStorageActionRow("重置所有设置", dangerColor) {
                    confirmAction = DataStorageConfirmAction.ResetSettings
                }
                DataStorageActionRow("清除所有数据", dangerColor) {
                    confirmAction = DataStorageConfirmAction.ClearAllData
                }
            }

                    Spacer(modifier = Modifier.height(48.dp))
                }
            }

            ProgressiveBlurContent(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .fillMaxWidth()
                    .zIndex(2f),
                backdrop = liquidBackdrop,
                isLightTheme = isLightTheme
            )
        }

        if (onBack != null) {
            SettingsBackCircleButton(
                onClick = onBack,
                backdrop = topBackdrop,
                surfaceColor = topSurfaceColor,
                contentColor = contentColor,
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(top = statusBarTopPaddingDp + 12.dp, start = 12.dp)
                    .zIndex(3f)
            )
        }

        DataStorageConfirmDialog(
            action = confirmAction,
            dangerColor = dangerColor,
            contentColor = contentColor,
            onDismiss = { confirmAction = null },
            onConfirm = { action ->
                confirmAction = null
                when (action) {
                        DataStorageConfirmAction.ResetSettings -> {
                            resetAllSettings(context)
                            Toast.makeText(context, "设置已重置", Toast.LENGTH_SHORT).show()
                            recreateHostActivity(context)
                        }
                        DataStorageConfirmAction.ClearAllData -> {
                            scope.launch {
                                withContext(Dispatchers.IO) { clearAllAppData(context) }
                                Toast.makeText(context, "数据已清除", Toast.LENGTH_SHORT).show()
                                recreateHostActivity(context)
                            }
                        }
                    }
                }
        )
    }
}

@Composable
private fun StorageLegendItem(
    color: Color,
    label: String,
    contentColor: Color
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Box(
            modifier = Modifier
                .size(12.dp)
                .clip(CircleShape)
                .background(color)
        )
        Text(
            text = label,
            style = TextStyle(
                color = contentColor,
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium
            )
        )
    }
}

@Composable
private fun StorageUsageRow(
    label: String,
    value: String,
    labelColor: Color,
    valueColor: Color
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            style = TextStyle(
                color = labelColor,
                fontSize = 16.sp,
                fontWeight = FontWeight.Medium
            )
        )
        Text(
            text = value,
            style = TextStyle(
                color = valueColor,
                fontSize = 16.sp,
                fontWeight = FontWeight.Medium
            )
        )
    }
}

@Composable
private fun StorageUsageDetailRow(
    label: String,
    value: String,
    color: Color
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = "• $label",
            color = color,
            fontSize = 13.sp
        )
        Text(
            text = value,
            color = color,
            fontSize = 13.sp
        )
    }
}

@Composable
private fun DataStorageActionRow(
    label: String,
    color: Color,
    onClick: () -> Unit
) {
    Text(
        text = label,
        style = TextStyle(
            color = color,
            fontSize = 16.sp,
            fontWeight = FontWeight.Medium
        ),
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 24.dp, vertical = 22.dp)
    )
}

private data class DataStorageStats(
    val appBytes: Long,
    val userBytes: Long,
    val cacheBytes: Long,
    val userBreakdown: List<DataStorageBreakdown>
) {
    val totalBytes: Long get() = appBytes + userBytes + cacheBytes
}

private data class DataStorageBreakdown(
    val label: String,
    val bytes: Long
)

private fun collectStorageStats(context: Context): DataStorageStats {
    val appBytes = buildList {
        add(context.applicationInfo.sourceDir)
        context.applicationInfo.splitSourceDirs?.let { addAll(it) }
    }.sumOf { path -> runCatching { File(path).length() }.getOrDefault(0L) }
    val cacheBytes = directorySize(context.cacheDir) + directorySize(context.externalCacheDir)
    val userBytes = directorySize(context.filesDir)
    val importedWallpaperDir = File(context.filesDir, "imported_wallpapers")
    val knownUserData = listOf(
        DataStorageBreakdown("SOG 景深壁纸", directorySize(File(context.filesDir, "sog"))),
        DataStorageBreakdown("普通图片/视频壁纸", directorySize(File(importedWallpaperDir, "regular"))),
        DataStorageBreakdown("光栅壁纸", directorySize(File(importedWallpaperDir, "raster"))),
        DataStorageBreakdown(
            "在线生成文件",
            directorySize(File(context.filesDir, "generated_sog")) +
                directorySize(File(context.filesDir, "depth_generated_sog"))
        ),
        DataStorageBreakdown("视频转码文件", directorySize(File(context.filesDir, "raster_kf"))),
        DataStorageBreakdown(
            "预览缩略图",
            directorySize(File(context.filesDir, "depth_preview_thumbnails")) +
                directorySize(File(context.filesDir, "sog_thumbnails"))
        )
    )
    val knownUserBytes = knownUserData.sumOf { it.bytes }
    val otherUserBytes = (userBytes - knownUserBytes).coerceAtLeast(0L)
    val userBreakdown = buildList {
        addAll(knownUserData.filter { it.bytes > 0L })
        if (otherUserBytes > 0L) {
            add(DataStorageBreakdown("其他文件", otherUserBytes))
        }
    }
    return DataStorageStats(
        appBytes = appBytes,
        userBytes = userBytes,
        cacheBytes = cacheBytes,
        userBreakdown = userBreakdown
    )
}

private fun directorySize(file: File?): Long {
    if (file == null || !file.exists()) return 0L
    if (file.isFile) return file.length()
    return runCatching {
        file.listFiles()?.sumOf { child -> directorySize(child) } ?: 0L
    }.getOrDefault(0L)
}

private fun formatStorageSize(context: Context, bytes: Long): String {
    return Formatter.formatShortFileSize(context, bytes.coerceAtLeast(0L))
}

@Composable
private fun DataStorageConfirmDialog(
    action: DataStorageConfirmAction?,
    dangerColor: Color,
    contentColor: Color,
    onDismiss: () -> Unit,
    onConfirm: (DataStorageConfirmAction) -> Unit
) {
    LiquidWindowAnimatedContent(
        targetState = action,
        contentAlignment = Alignment.Center,
        label = "DataStorageConfirmDialog",
        modifier = Modifier.fillMaxSize()
    ) { visibleAction ->
        if (visibleAction == null) return@LiquidWindowAnimatedContent
        DataStorageConfirmDialogContent(
            action = visibleAction,
            dangerColor = dangerColor,
            contentColor = contentColor,
            onDismiss = onDismiss,
            onConfirm = { onConfirm(visibleAction) }
        )
    }
}

@Composable
private fun DataStorageConfirmDialogContent(
    action: DataStorageConfirmAction,
    dangerColor: Color,
    contentColor: Color,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit
) {
    val title = when (action) {
        DataStorageConfirmAction.ResetSettings -> "重置所有设置"
        DataStorageConfirmAction.ClearAllData -> "清除所有数据"
    }
    val message = when (action) {
        DataStorageConfirmAction.ResetSettings -> "将恢复设置项默认值，壁纸与分组数据会保留。"
        DataStorageConfirmAction.ClearAllData -> "将清除设置、壁纸列表、分组、导入缓存和缩略图缓存。此操作不可撤销。"
    }
    Box(
        modifier = Modifier
            .fillMaxSize()
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null
            ) {
                onDismiss()
            },
        contentAlignment = Alignment.Center
    ) {
        Column(
            modifier = Modifier
                .padding(horizontal = 40.dp)
                .clip(RoundedCornerShape(28.dp))
                .background(
                    if (contentColor == Color.Black) Color(0xFFFAFAFA).copy(alpha = 0.94f)
                    else Color(0xFF1C1C20).copy(alpha = 0.94f)
                )
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null
                ) {}
                .padding(24.dp)
        ) {
            Text(
                text = title,
                color = contentColor,
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = message,
                color = contentColor.copy(alpha = 0.72f),
                fontSize = 14.sp,
                lineHeight = 20.sp,
                modifier = Modifier.padding(top = 12.dp)
            )
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 20.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Row(
                    modifier = Modifier
                        .weight(1f)
                        .clip(Capsule())
                        .background(contentColor.copy(alpha = 0.1f))
                        .clickable(onClick = onDismiss)
                        .height(44.dp),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(text = "取消", color = contentColor, fontSize = 15.sp)
                }
                Row(
                    modifier = Modifier
                        .weight(1f)
                        .clip(Capsule())
                        .background(dangerColor)
                        .clickable(onClick = onConfirm)
                        .height(44.dp),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(text = "确认", color = Color.White, fontSize = 15.sp)
                }
            }
        }
    }
}

private fun recreateHostActivity(context: Context) {
    (context as? Activity)?.recreate()
}

private fun defaultBackupFileName(): String {
    return "tianyin-backup-${System.currentTimeMillis()}.zip"
}

private fun exportSettingsBackup(context: Context, targetUri: Uri): Boolean {
    return runCatching {
        context.contentResolver.openOutputStream(targetUri, "wt")?.use { output ->
            ZipOutputStream(output.buffered()).use { zip ->
                val metadata = JSONObject().apply {
                    put("version", 4)
                    put("createdAt", System.currentTimeMillis())
                    put("format", "zip")
                }
                addZipStringEntry(zip, BACKUP_METADATA_ENTRY, JSON.toJSONString(metadata))
                addZipStringEntry(
                    zip,
                    BACKUP_PREF_ENTRY,
                    JSON.toJSONString(
                        collectBackupPreferences(
                            context.getSharedPreferences(App.TIANYIN, Context.MODE_PRIVATE)
                        )
                    )
                )
                addZipStringEntry(
                    zip,
                    BACKUP_SOG_HISTORY_ENTRY,
                    GradioMcpSogGenerator.exportHistoryJson(context)
                )
                addDirectoryToZip(zip, context.filesDir, BACKUP_INTERNAL_FILES_PREFIX)
                addDirectoryToZip(zip, context.getExternalFilesDir(null), BACKUP_EXTERNAL_FILES_PREFIX)
                addDirectoryToZip(zip, context.cacheDir, BACKUP_INTERNAL_CACHE_PREFIX)
                addDirectoryToZip(zip, context.externalCacheDir, BACKUP_EXTERNAL_CACHE_PREFIX)
            }
        } ?: return false
        true
    }.getOrDefault(false)
}

private fun importSettingsBackup(context: Context, sourceUri: Uri): Boolean {
    return runCatching {
        val restoreRoot = File(context.codeCacheDir, "backup_restore").apply {
            deleteRecursively()
            mkdirs()
        }
        context.contentResolver.openInputStream(sourceUri)?.use { input ->
            unzipBackupToDirectory(input.buffered(), restoreRoot)
        } ?: return false
        val preferencesFile = File(restoreRoot, BACKUP_PREF_ENTRY)
        if (!preferencesFile.exists()) return false
        val pref = context.getSharedPreferences(App.TIANYIN, Context.MODE_PRIVATE)
        restoreBackupPreferences(pref, JSON.parseObject(preferencesFile.readText()) ?: JSONObject())
        restoreDirectoryBackup(File(restoreRoot, BACKUP_INTERNAL_FILES_PREFIX), context.filesDir)
        restoreDirectoryBackup(File(restoreRoot, BACKUP_EXTERNAL_FILES_PREFIX), context.getExternalFilesDir(null))
        restoreDirectoryBackup(File(restoreRoot, BACKUP_INTERNAL_CACHE_PREFIX), context.cacheDir)
        restoreDirectoryBackup(File(restoreRoot, BACKUP_EXTERNAL_CACHE_PREFIX), context.externalCacheDir)
        repairRestoredDirectory(File(context.filesDir, "generated_sog"))
        val sogHistoryFile = File(restoreRoot, BACKUP_SOG_HISTORY_ENTRY)
        if (sogHistoryFile.isFile) {
            GradioMcpSogGenerator.restoreHistoryJson(context, sogHistoryFile.readText())
        }
        restoreRoot.deleteRecursively()
        notifyStorageDataChanged()
        true
    }.getOrDefault(false)
}

private fun addZipStringEntry(zip: ZipOutputStream, name: String, value: String) {
    zip.putNextEntry(ZipEntry(name))
    zip.write(value.toByteArray(Charsets.UTF_8))
    zip.closeEntry()
}

private fun addDirectoryToZip(zip: ZipOutputStream, root: File?, prefix: String) {
    if (root == null || !root.exists()) return
    val base = root.canonicalFile
    zip.putNextEntry(ZipEntry(prefix))
    zip.closeEntry()
    base.walkTopDown().forEach { file ->
        if (file == base) return@forEach
        val relativePath = file.canonicalFile.relativeTo(base).invariantSeparatorsPath
        val entryName = prefix + relativePath + if (file.isDirectory) "/" else ""
        zip.putNextEntry(ZipEntry(entryName))
        if (file.isFile) {
            file.inputStream().use { input -> input.copyTo(zip) }
        }
        zip.closeEntry()
    }
}

private fun unzipBackupToDirectory(input: InputStream, targetRoot: File) {
    val root = targetRoot.canonicalFile
    ZipInputStream(input).use { zip ->
        while (true) {
            val entry = zip.nextEntry ?: break
            val target = safeZipTarget(root, entry.name)
            if (target != null) {
                if (entry.isDirectory || entry.name.endsWith('/')) {
                    ensureRestoreDirectory(target)
                } else {
                    target.parentFile?.let(::ensureRestoreDirectory)
                    if (target.isDirectory) target.deleteRecursively()
                    target.outputStream().use { output -> zip.copyTo(output) }
                }
            }
            zip.closeEntry()
        }
    }
}

private fun safeZipTarget(root: File, entryName: String): File? {
    if (entryName.isBlank() || entryName.startsWith("/") || entryName.startsWith("\\")) return null
    val target = File(root, entryName).canonicalFile
    val rootPath = root.canonicalPath + File.separator
    return if (target.canonicalPath.startsWith(rootPath)) target else null
}

private fun restoreDirectoryBackup(source: File, target: File?) {
    if (!source.exists() || target == null) return
    deleteDirectoryContents(target)
    ensureRestoreDirectory(target)
    copyDirectoryContents(source, target)
}

private fun copyDirectoryContents(source: File, target: File) {
    source.listFiles()?.forEach { file ->
        val dest = File(target, file.name)
        if (file.isDirectory) {
            ensureRestoreDirectory(dest)
            copyDirectoryContents(file, dest)
        } else {
            dest.parentFile?.let(::ensureRestoreDirectory)
            if (dest.isDirectory) dest.deleteRecursively()
            file.inputStream().use { input ->
                dest.outputStream().use { output -> input.copyTo(output) }
            }
        }
    }
}

private fun ensureRestoreDirectory(directory: File) {
    if (directory.isDirectory) return
    if (directory.exists() && !directory.delete()) {
        error("Cannot replace restored file with directory: ${directory.absolutePath}")
    }
    if (!directory.mkdirs() && !directory.isDirectory) {
        error("Cannot create restored directory: ${directory.absolutePath}")
    }
}

private fun repairRestoredDirectory(directory: File) {
    if (directory.isDirectory) return
    var legacyFile: File? = null
    if (directory.exists()) {
        val backup = File(
            directory.parentFile,
            "${directory.name}_legacy_${System.currentTimeMillis()}.sog"
        )
        if (!directory.renameTo(backup)) {
            error("Cannot preserve restored file: ${directory.absolutePath}")
        }
        legacyFile = backup
    }
    ensureRestoreDirectory(directory)
    legacyFile?.let { source ->
        val target = File(directory, source.name)
        if (!source.renameTo(target)) {
            source.copyTo(target, overwrite = false)
            source.delete()
        }
    }
}

private fun collectBackupPreferences(pref: SharedPreferences): JSONObject {
    val preferences = JSONObject()
    pref.all.forEach { (key, value) ->
        preferences[key] = sharedPreferenceValueToBackup(value)
    }
    return preferences
}

private fun sharedPreferenceValueToBackup(value: Any?): JSONObject {
    val item = JSONObject()
    when (value) {
        is Boolean -> {
            item["type"] = "boolean"
            item["value"] = value
        }
        is Int -> {
            item["type"] = "int"
            item["value"] = value
        }
        is Long -> {
            item["type"] = "long"
            item["value"] = value
        }
        is Float -> {
            item["type"] = "float"
            item["value"] = value
        }
        is String -> {
            item["type"] = "string"
            item["value"] = value
        }
        is Set<*> -> {
            item["type"] = "string_set"
            item["value"] = JSONArray(value.filterIsInstance<String>())
        }
        else -> {
            item["type"] = "string"
            item["value"] = value?.toString().orEmpty()
        }
    }
    return item
}

private fun restoreBackupPreferences(pref: SharedPreferences, preferences: JSONObject) {
    val editor = pref.edit().clear()
    preferences.forEach { (key, rawValue) ->
        val item = rawValue as? JSONObject ?: return@forEach
        putSharedPreferenceBackupValue(editor, key, item)
    }
    editor.apply()
}

private fun putSharedPreferenceBackupValue(
    editor: SharedPreferences.Editor,
    key: String,
    item: JSONObject
) {
    when (item.getString("type")) {
        "boolean" -> editor.putBoolean(key, item.getBooleanValue("value"))
        "int" -> editor.putInt(key, item.getIntValue("value"))
        "long" -> editor.putLong(key, item.getLongValue("value"))
        "float" -> editor.putFloat(key, item.getFloatValue("value"))
        "string_set" -> {
            val array = item.getJSONArray("value") ?: JSONArray()
            editor.putStringSet(key, array.mapNotNull { it?.toString() }.toSet())
        }
        else -> editor.putString(key, item.getString("value"))
    }
}

private fun resetAllSettings(context: Context) {
    val pref = context.getSharedPreferences(App.TIANYIN, Context.MODE_PRIVATE)
    val preserved = pref.all.filterKeys { it in DATA_PREFERENCE_KEYS }
    val editor = pref.edit().clear()
    preserved.forEach { (key, value) ->
        putSharedPreferenceValue(editor, key, value)
    }
    editor.apply()
    notifyStorageDataChanged()
}

private fun clearAllAppData(context: Context) {
    context.getSharedPreferences(App.TIANYIN, Context.MODE_PRIVATE).edit().clear().commit()
    deleteDirectoryContents(File(context.applicationInfo.dataDir, "shared_prefs"))
    deleteDirectoryContents(File(context.applicationInfo.dataDir, "databases"))
    deleteDirectoryContents(context.filesDir)
    deleteDirectoryContents(context.cacheDir)
    deleteDirectoryContents(context.getExternalFilesDir(null))
    deleteDirectoryContents(context.externalCacheDir)
    notifyStorageDataChanged()
}

private fun putSharedPreferenceValue(
    editor: SharedPreferences.Editor,
    key: String,
    value: Any?
) {
    when (value) {
        is Boolean -> editor.putBoolean(key, value)
        is Int -> editor.putInt(key, value)
        is Long -> editor.putLong(key, value)
        is Float -> editor.putFloat(key, value)
        is String -> editor.putString(key, value)
        is Set<*> -> editor.putStringSet(key, value.filterIsInstance<String>().toSet())
    }
}

private fun deleteDirectoryContents(dir: File?) {
    dir?.listFiles()?.forEach { file ->
        runCatching {
            if (file.isDirectory) {
                file.deleteRecursively()
            } else {
                file.delete()
            }
        }
    }
}

private fun notifyStorageDataChanged() {
    RxBus.postWithCode(RxConstants.RX_GROUPS_CHANGED, Unit)
}

private fun applyGlobalClockColorMode(context: Context) {
    notifyRunningWallpaperClockColorMode(context)
    notifyStorageDataChanged()
}

private fun notifyRunningWallpaperClockColorMode(context: Context) {
    listOf(
        TianYinWallpaperService::class.java,
        StaticRasterWallpaperService::class.java,
        VideoRasterWallpaperService::class.java,
        DepthWallpaperService::class.java
    ).forEach { serviceClass ->
        context.startService(
            Intent(context, serviceClass).apply {
                action = TianYinWallpaperService.ACTION_UPDATE_CLOCK_COLOR_MODE
            }
        )
    }
}

@Composable
private fun SettingsBackCircleButton(
    onClick: () -> Unit,
    backdrop: Backdrop? = null,
    surfaceColor: Color,
    contentColor: Color,
    modifier: Modifier = Modifier
) {
    if (backdrop != null) {
        LiquidButton(
            onClick = onClick,
            backdrop = backdrop,
            modifier = modifier.size(44.dp),
            surfaceColor = surfaceColor,
            buttonHeight = 44.dp,
            contentPadding = PaddingValues(0.dp),
            iconRes = R.drawable.back,
            iconContentDescription = "返回",
            iconSize = 18.dp,
            iconTint = contentColor
        )
    } else {
        PlainIconButton(
            onClick = onClick,
            iconRes = R.drawable.back,
            contentDescription = "返回",
            modifier = modifier,
            size = 44.dp,
            iconSize = 18.dp,
            surfaceColor = surfaceColor,
            contentColor = contentColor,
            border = PlainFallbackStyle.border(isLightTheme = MaterialTheme.colors.isLight)
        )
    }
}

@Composable
fun UsageGuideRouteScreen(
    useDarkTheme: Boolean,
    onBack: (() -> Unit)? = null,
    enableLiquidGlass: Boolean = true
) {
    val context = LocalContext.current
    val density = LocalDensity.current
    val statusBarTopPadding = remember(context) {
        val id = context.resources.getIdentifier("status_bar_height", "dimen", "android")
        if (id > 0) context.resources.getDimensionPixelSize(id) else 0
    }
    val statusBarTopPaddingDp = with(density) { statusBarTopPadding.toDp() }
    val isLightTheme = !useDarkTheme
    val backgroundColor = if (isLightTheme) Color.White else Color(0xFF0A0A0C)
    val contentColor = if (isLightTheme) Color.Black else Color.White
    val groupBackgroundColor = if (isLightTheme) {
        Color(0xFFF2F3F7)
    } else {
        Color(0xFF1C1C20).copy(alpha = 0.94f)
    }
    val topSurfaceColor = if (isLightTheme) Color.White.copy(alpha = 0.3f) else Color.Black.copy(alpha = 0.3f)
    val useLiquidGlass = enableLiquidGlass && LiquidGlassPrefs.isEnabled(context)
    val liquidBackdrop = if (useLiquidGlass) rememberLayerBackdrop() else null
    val topBackdrop = if (useLiquidGlass) rememberLayerBackdrop() else null

    Box(modifier = Modifier.fillMaxSize()) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .let { modifier -> if (topBackdrop != null) modifier.layerBackdrop(topBackdrop) else modifier }
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .let { modifier -> if (liquidBackdrop != null) modifier.layerBackdrop(liquidBackdrop) else modifier }
            ) {
                Box(Modifier.fillMaxSize().background(backgroundColor))
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 24.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    Spacer(modifier = Modifier.height(statusBarTopPaddingDp + if (onBack != null) 76.dp else 48.dp))
                    Text(
                        text = "使用说明",
                        style = TextStyle(contentColor, 28.sp, FontWeight.Bold)
                    )
                    Text(
                        text = "按功能类型查看常用操作和注意事项。",
                        style = TextStyle(contentColor.copy(alpha = 0.62f), 14.sp)
                    )

                    UsageGuideSection(
                        title = "常规壁纸",
                        body = "添加图片或视频后，可点击缩略图进入预览并应用。长按或进入选择模式可调整顺序、删除或管理分组。随机切换、桌面滑动切换和最小切换时间在设置页中配置。"
                    )
                    UsageGuideSection(
                        title = "光栅壁纸",
                        body = "光栅壁纸支持静态图集和动态视频光栅。首次使用动态视频会建立转码缓存，缓存策略可在数据与存储页管理。"
                    )
                    UsageGuideSection(
                        title = "景深 / SOG 壁纸",
                        body = "导入 SOG 后使用 WebView 渲染。预览页可调整灵敏度、视差强度、视角和注视深度；注视深度按模型内部百分比计算。缩略图会按默认相机参数生成，已有缩略图不会重算。"
                    )
                    UsageGuideSection(
                        title = "在线服务",
                        body = "在线服务用于提交图片生成 SOG。配置 SDK Token 后可提交任务，生成记录会保留在在线页面；已导入的记录会标记为已导入，避免重复操作。"
                    )
                    UsageGuideSection(
                        title = "本地服务",
                        body = "本地服务需要先在电脑或局域网内启动生成服务，再在设置页填写服务地址。手机和服务端需要处在可互相访问的网络环境。"
                    )
                    UsageGuideSection(
                        title = "设置与数据",
                        body = "数据与存储页可备份、恢复、清理缓存和管理导入文件。关于页可手动检查更新，也可以控制启动时是否自动检查更新。"
                    )
                    Spacer(modifier = Modifier.height(48.dp))
                }
            }

            ProgressiveBlurContent(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .fillMaxWidth()
                    .zIndex(2f),
                backdrop = liquidBackdrop,
                isLightTheme = isLightTheme
            )
        }

        if (onBack != null) {
            SettingsBackCircleButton(
                onClick = onBack,
                backdrop = topBackdrop,
                surfaceColor = topSurfaceColor,
                contentColor = contentColor,
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(top = statusBarTopPaddingDp + 12.dp, start = 12.dp)
                    .zIndex(3f)
            )
        }
    }
}

@Composable
fun LicenseRouteScreen(
    useDarkTheme: Boolean,
    onBack: (() -> Unit)? = null,
    enableLiquidGlass: Boolean = true
) {
    val context = LocalContext.current
    val density = LocalDensity.current
    val statusBarTopPadding = remember(context) {
        val id = context.resources.getIdentifier("status_bar_height", "dimen", "android")
        if (id > 0) context.resources.getDimensionPixelSize(id) else 0
    }
    val statusBarTopPaddingDp = with(density) { statusBarTopPadding.toDp() }
    val isLightTheme = !useDarkTheme
    val backgroundColor = if (isLightTheme) Color.White else Color(0xFF0A0A0C)
    val contentColor = if (isLightTheme) Color.Black else Color.White
    val topSurfaceColor = if (isLightTheme) Color.White.copy(alpha = 0.3f) else Color.Black.copy(alpha = 0.3f)
    val useLiquidGlass = enableLiquidGlass && LiquidGlassPrefs.isEnabled(context)
    val liquidBackdrop = if (useLiquidGlass) rememberLayerBackdrop() else null
    val topBackdrop = if (useLiquidGlass) rememberLayerBackdrop() else null

    Box(modifier = Modifier.fillMaxSize()) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .let { modifier -> if (topBackdrop != null) modifier.layerBackdrop(topBackdrop) else modifier }
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .let { modifier -> if (liquidBackdrop != null) modifier.layerBackdrop(liquidBackdrop) else modifier }
            ) {
                Box(Modifier.fillMaxSize().background(backgroundColor))
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 24.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    Spacer(modifier = Modifier.height(statusBarTopPaddingDp + if (onBack != null) 76.dp else 48.dp))
                    Text(
                        text = "许可证",
                        style = TextStyle(contentColor, 28.sp, FontWeight.Bold)
                    )
                    Text(
                        text = "应用与引用项目的开源许可证说明",
                        style = TextStyle(contentColor.copy(alpha = 0.62f), 14.sp)
                    )

                    UsageGuideSection(
                        title = "天音壁纸",
                        body = "本应用源码使用 Apache License 2.0 开源，完整条款以仓库 LICENSE 文件为准。"
                    )
                    UsageGuideSection(
                        title = "开源依赖",
                        body = "Android Jetpack / Jetpack Compose、OkHttp、Glide、Fastjson 等依赖遵循各自项目许可证。分发和再利用时请同时遵守对应许可证与 NOTICE 要求。"
                    )
                    UsageGuideSection(
                        title = "参考项目与资源",
                        body = "PlayCanvas SuperSplat、Apple ml-sharp、Kyant / AndroidLiquidGlass、ModelScope 等项目或服务仅作为引用、参考或兼容目标列出；具体授权、商标和使用限制以各自官方仓库、文档和许可证为准。"
                    )
                    UsageGuideSection(
                        title = "商标说明",
                        body = "页面中出现的第三方名称和商标归其各自权利人所有。列出项目名称不代表这些项目、贡献者或公司对天音壁纸的认可、赞助或合作关系。"
                    )
                    Spacer(modifier = Modifier.height(48.dp))
                }
            }

            ProgressiveBlurContent(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .fillMaxWidth()
                    .zIndex(2f),
                backdrop = liquidBackdrop,
                isLightTheme = isLightTheme
            )
        }

        if (onBack != null) {
            SettingsBackCircleButton(
                onClick = onBack,
                backdrop = topBackdrop,
                surfaceColor = topSurfaceColor,
                contentColor = contentColor,
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(top = statusBarTopPaddingDp + 12.dp, start = 12.dp)
                    .zIndex(3f)
            )
        }
    }
}

@Composable
fun AcknowledgementsRouteScreen(
    useDarkTheme: Boolean,
    onBack: (() -> Unit)? = null,
    enableLiquidGlass: Boolean = true
) {
    val context = LocalContext.current
    val density = LocalDensity.current
    val statusBarTopPadding = remember(context) {
        val id = context.resources.getIdentifier("status_bar_height", "dimen", "android")
        if (id > 0) context.resources.getDimensionPixelSize(id) else 0
    }
    val statusBarTopPaddingDp = with(density) { statusBarTopPadding.toDp() }
    val isLightTheme = !useDarkTheme
    val backgroundColor = if (isLightTheme) Color.White else Color(0xFF0A0A0C)
    val contentColor = if (isLightTheme) Color.Black else Color.White
    val groupBackgroundColor = if (isLightTheme) {
        Color(0xFFF2F3F7)
    } else {
        Color(0xFF1C1C20).copy(alpha = 0.94f)
    }
    val topSurfaceColor = if (isLightTheme) Color.White.copy(alpha = 0.3f) else Color.Black.copy(alpha = 0.3f)
    val useLiquidGlass = enableLiquidGlass && LiquidGlassPrefs.isEnabled(context)
    val liquidBackdrop = if (useLiquidGlass) rememberLayerBackdrop() else null
    val topBackdrop = if (useLiquidGlass) rememberLayerBackdrop() else null

    Box(modifier = Modifier.fillMaxSize()) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .let { modifier -> if (topBackdrop != null) modifier.layerBackdrop(topBackdrop) else modifier }
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .let { modifier -> if (liquidBackdrop != null) modifier.layerBackdrop(liquidBackdrop) else modifier }
            ) {
                Box(Modifier.fillMaxSize().background(backgroundColor))
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 24.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    Spacer(modifier = Modifier.height(statusBarTopPaddingDp + if (onBack != null) 76.dp else 48.dp))
                    Text(
                        text = "致谢",
                        style = TextStyle(contentColor, 28.sp, FontWeight.Bold)
                    )
                    Text(
                        text = "天音壁纸使用或参考了以下开源项目与服务，感谢这些项目提供的基础能力与实现思路。",
                        style = TextStyle(contentColor.copy(alpha = 0.62f), 14.sp)
                    )

                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(28.dp))
                            .background(groupBackgroundColor)
                            .padding(24.dp),
                        verticalArrangement = Arrangement.spacedBy(14.dp)
                    ) {
                        AcknowledgementLink("Android Jetpack / Jetpack Compose", "https://developer.android.com/jetpack/compose")
                        AcknowledgementLink("Kyant / AndroidLiquidGlass", "https://github.com/Kyant0")
                        AcknowledgementLink("Apple / ml-sharp", "https://github.com/apple/ml-sharp")
                        AcknowledgementLink("PlayCanvas SuperSplat", "https://github.com/playcanvas/supersplat")
                        AcknowledgementLink("ModelScope", "https://modelscope.cn")
                        AcknowledgementLink("OkHttp", "https://square.github.io/okhttp/")
                    }
                    UsageGuideSection(
                        title = "说明",
                        body = "以上项目及其贡献者与天音壁纸无直接关联，仅供学习研究使用；列出名称不代表认可、赞助或合作关系。"
                    )
                    Spacer(modifier = Modifier.height(48.dp))
                }
            }

            ProgressiveBlurContent(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .fillMaxWidth()
                    .zIndex(2f),
                backdrop = liquidBackdrop,
                isLightTheme = isLightTheme
            )
        }

        if (onBack != null) {
            SettingsBackCircleButton(
                onClick = onBack,
                backdrop = topBackdrop,
                surfaceColor = topSurfaceColor,
                contentColor = contentColor,
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(top = statusBarTopPaddingDp + 12.dp, start = 12.dp)
                    .zIndex(3f)
            )
        }
    }
}

@Composable
fun AppInfoRouteScreen(
    useDarkTheme: Boolean,
    onBack: (() -> Unit)? = null,
    onOpenAcknowledgements: () -> Unit = {},
    onOpenLicense: () -> Unit = {},
    enableLiquidGlass: Boolean = true
) {
    val context = LocalContext.current
    val density = androidx.compose.ui.platform.LocalDensity.current
    val statusBarTopPadding = remember(context) {
        val id = context.resources.getIdentifier("status_bar_height", "dimen", "android")
        if (id > 0) context.resources.getDimensionPixelSize(id) else 0
    }
    val statusBarTopPaddingDp = with(density) { statusBarTopPadding.toDp() }

    val isLightTheme = !useDarkTheme
    val backgroundColor = if (isLightTheme) Color.White else Color(0xFF0A0A0C)
    val contentColor = if (isLightTheme) Color.Black else Color.White
    val containerColor = if (isLightTheme) {
        Color(0xFFF2F3F7).copy(alpha = 0.78f)
    } else {
        Color(0xFF2A2A2E).copy(alpha = 0.62f)
    }
    val groupBackgroundColor = if (isLightTheme) {
        Color(0xFFF2F3F7)
    } else {
        Color(0xFF1C1C20).copy(alpha = 0.94f)
    }
    val accentColor = MaterialTheme.colors.primary
    val topSurfaceColor = if (isLightTheme) Color.White.copy(alpha = 0.3f) else Color.Black.copy(alpha = 0.3f)

    val useLiquidGlass = enableLiquidGlass && LiquidGlassPrefs.isEnabled(context)
    val liquidBackdrop = if (useLiquidGlass) rememberLayerBackdrop() else null
    val topBackdrop = if (useLiquidGlass) rememberLayerBackdrop() else null

    val verName = getVersionName(context)

    // 版本号点击5次开关测试页入口
    val pref = remember(context) { context.getSharedPreferences(App.TIANYIN, Context.MODE_PRIVATE) }
    var corrugatedTestEnabled by remember { mutableStateOf(pref.getBoolean(PREF_CORRUGATED_TEST_ENABLED, false)) }
    var versionTapCount by remember { mutableStateOf(0) }
    var lastTapTime by remember { mutableStateOf(0L) }
    val haptic = androidx.compose.ui.platform.LocalHapticFeedback.current
    var updateDialogState by remember { mutableStateOf(UpdateDialogState()) }
    var shouldCheckUpdate by remember { mutableStateOf(false) }
    var checkUpdateOnStart by remember {
        mutableStateOf(pref.getBoolean(AppUpdateManager.PREF_CHECK_UPDATE_ON_START, true))
    }

    Box(modifier = Modifier.fillMaxSize()) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .let { m ->
                    if (useLiquidGlass && topBackdrop != null) {
                        m.layerBackdrop(topBackdrop)
                    } else m
                }
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .let { m ->
                        if (useLiquidGlass && liquidBackdrop != null) {
                            m.layerBackdrop(liquidBackdrop)
                        } else m
                    }
            ) {
                Box(Modifier.fillMaxSize().background(backgroundColor))

                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState()),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
            if (onBack != null) {
                Spacer(modifier = Modifier.height(statusBarTopPaddingDp + 76.dp))
            } else {
                Spacer(modifier = Modifier.height(statusBarTopPaddingDp + 48.dp))
            }

            // App Icon
            val appIconBitmap = remember(context) {
                val drawable = context.packageManager.getApplicationIcon(context.applicationInfo)
                val bitmap = android.graphics.Bitmap.createBitmap(
                    192, 192, android.graphics.Bitmap.Config.ARGB_8888
                )
                val canvas = android.graphics.Canvas(bitmap)
                drawable.setBounds(0, 0, 192, 192)
                drawable.draw(canvas)
                bitmap.asImageBitmap()
            }
            androidx.compose.foundation.Image(
                bitmap = appIconBitmap,
                contentDescription = "App Icon",
                modifier = Modifier
                    .width(100.dp)
                    .height(100.dp)
                    .clip(RoundedCornerShape(20.dp)),
                contentScale = androidx.compose.ui.layout.ContentScale.Fit
            )
            Spacer(modifier = Modifier.height(12.dp))
            Text(
                text = "天音壁纸",
                style = TextStyle(
                    color = contentColor,
                    fontSize = 28.sp,
                    fontWeight = FontWeight.Bold
                )
            )
            Text(
                text = "版本 $verName",
                style = TextStyle(
                    color = contentColor.copy(0.6f),
                    fontSize = 14.sp
                ),
                modifier = Modifier
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null
                    ) {
                        val now = System.currentTimeMillis()
                        if (now - lastTapTime > 2000L) {
                            versionTapCount = 1
                        } else {
                            versionTapCount++
                        }
                        lastTapTime = now
                        if (versionTapCount >= 5) {
                            versionTapCount = 0
                            corrugatedTestEnabled = !corrugatedTestEnabled
                            pref.edit().putBoolean(PREF_CORRUGATED_TEST_ENABLED, corrugatedTestEnabled).apply()
                            haptic.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress)
                            Toast.makeText(
                                context,
                                if (corrugatedTestEnabled) "已开启测试页" else "已关闭测试页",
                                Toast.LENGTH_SHORT
                            ).show()
                        }
                    }
                    .padding(vertical = 4.dp)
            )
            Row(
                Modifier
                    .padding(top = 8.dp)
                    .width(160.dp)
                    .clip(Capsule())
                    .background(accentColor)
                    .clickable {
                        shouldCheckUpdate = true
                        updateDialogState = UpdateDialogState(isVisible = true, isChecking = true)
                    }
                    .height(44.dp),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically
            ) {
                BasicText(
                    "\u68c0\u67e5\u66f4\u65b0",
                    style = TextStyle(Color.White, 15.sp, fontWeight = FontWeight.Bold)
                )
            }
            Spacer(modifier = Modifier.height(32.dp))

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp)
                    .clip(RoundedCornerShape(28.dp))
                    .background(groupBackgroundColor)
                    .padding(vertical = 4.dp)
            ) {
                SettingCheckItemWithSubtitle(
                    label = "启动时检查更新",
                    subtitle = "打开应用时自动检测新版本",
                    checked = checkUpdateOnStart,
                    contentColor = contentColor,
                    backgroundColor = groupBackgroundColor,
                    isLightTheme = isLightTheme,
                    enableLiquidGlass = enableLiquidGlass
                ) {
                    checkUpdateOnStart = it
                    pref.edit().putBoolean(AppUpdateManager.PREF_CHECK_UPDATE_ON_START, it).apply()
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            // Links Block
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp)
                    .clip(RoundedCornerShape(28.dp))
                    .background(groupBackgroundColor)
                    .padding(vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 24.dp, vertical = 16.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(text = "开源地址", style = TextStyle(color = contentColor, fontSize = 16.sp))
                    LinkText("GitHub", "https://github.com/SUlTlUS/tianyinwallpaper.git")
                }
                
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 24.dp, vertical = 16.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(text = "下载地址", style = TextStyle(color = contentColor, fontSize = 16.sp))
                    LinkText("Releases", "https://github.com/SUlTlUS/tianyinwallpaper/releases")
                }

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(onClick = onOpenAcknowledgements)
                        .padding(horizontal = 24.dp, vertical = 16.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(text = "致谢", style = TextStyle(color = contentColor, fontSize = 16.sp))
                    BasicText(
                        "查看",
                        style = TextStyle(
                            color = MaterialTheme.colors.primary,
                            fontSize = 14.sp,
                            textDecoration = TextDecoration.Underline
                        )
                    )
                }

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(onClick = onOpenLicense)
                        .padding(horizontal = 24.dp, vertical = 16.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(text = "许可证", style = TextStyle(color = contentColor, fontSize = 16.sp))
                    BasicText(
                        "查看",
                        style = TextStyle(
                            color = MaterialTheme.colors.primary,
                            fontSize = 14.sp,
                            textDecoration = TextDecoration.Underline
                        )
                    )
                }
            }
            
                    Spacer(modifier = Modifier.height(48.dp))
                }
            }

            ProgressiveBlurContent(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .fillMaxWidth()
                    .zIndex(2f),
                backdrop = liquidBackdrop,
                isLightTheme = isLightTheme
            )
        }

        if (onBack != null) {
            SettingsBackCircleButton(
                onClick = onBack,
                backdrop = topBackdrop,
                surfaceColor = topSurfaceColor,
                contentColor = contentColor,
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(top = statusBarTopPaddingDp + 12.dp, start = 12.dp)
                    .zIndex(3f)
            )
        }

        UpdateDialog(
            state = updateDialogState,
            parentBackdrop = liquidBackdrop,
            onDismiss = {
                updateDialogState = UpdateDialogState(isVisible = false)
                shouldCheckUpdate = false
            },
            onConfirm = {
                val info = updateDialogState.updateInfo
                if (info != null) {
                    updateDialogState = updateDialogState.copy(
                        isDownloading = true,
                        downloadProgress = 0
                    )
                    AppUpdateManager.downloadApk(context, info, object : AppUpdateManager.DownloadCallback {
                        override fun onProgress(progress: Int) {
                            updateDialogState = updateDialogState.copy(downloadProgress = progress)
                        }

                        override fun onSuccess(file: java.io.File) {
                            updateDialogState = updateDialogState.copy(isDownloading = false)
                            val md5 = AppUpdateManager.calculateMD5(file)
                            val apkVersionCode = AppUpdateManager.getApkVersionCode(context, file)
                            if (apkVersionCode != info.code.toLong()) {
                                Toast.makeText(
                                    context,
                                    "\u4e0b\u8f7d\u7684\u5b89\u88c5\u5305\u7248\u672c\u4e0d\u5339\u914d\uff0c\u8bf7\u91cd\u65b0\u68c0\u67e5\u66f4\u65b0",
                                    Toast.LENGTH_SHORT
                                ).show()
                            } else if (md5 != null && md5.equals(info.md5, ignoreCase = true)) {
                                AppUpdateManager.installApk(context, file)
                            } else {
                                Toast.makeText(
                                    context,
                                    "\u6587\u4ef6\u6821\u9a8c\u5931\u8d25\uff0c\u8bf7\u91cd\u65b0\u4e0b\u8f7d",
                                    Toast.LENGTH_SHORT
                                ).show()
                            }
                        }

                        override fun onError(message: String) {
                            updateDialogState = updateDialogState.copy(
                                isDownloading = false,
                                errorMessage = message
                            )
                            Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
                        }
                    })
                } else if (updateDialogState.isLatestVersion) {
                    updateDialogState = UpdateDialogState(isVisible = false)
                    shouldCheckUpdate = false
                } else {
                    shouldCheckUpdate = true
                    updateDialogState = UpdateDialogState(isVisible = true, isChecking = true)
                }
            },
            isLightTheme = isLightTheme
        )

        androidx.compose.runtime.LaunchedEffect(shouldCheckUpdate) {
            if (shouldCheckUpdate) {
                when (val result = AppUpdateManager.checkUpdate()) {
                    is AppUpdateManager.CheckResult.HasUpdate -> {
                        updateDialogState = UpdateDialogState(
                            isVisible = true,
                            isChecking = false,
                            updateInfo = result.updateInfo
                        )
                    }
                    is AppUpdateManager.CheckResult.NoUpdate -> {
                        updateDialogState = UpdateDialogState(
                            isVisible = true,
                            isChecking = false,
                            isLatestVersion = true
                        )
                    }
                    is AppUpdateManager.CheckResult.Error -> {
                        updateDialogState = UpdateDialogState(
                            isVisible = true,
                            isChecking = false,
                            errorMessage = result.message
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun AccentColorPicker(
    selectedKey: String,
    useDarkTheme: Boolean,
    contentColor: Color,
    onSelected: (AppAccentColor) -> Unit,
    enableLiquidGlass: Boolean,
    modifier: Modifier = Modifier
) {
    val selected = AppAccentColors.find(selectedKey)
    val isLightTheme = !useDarkTheme
    val presets = AppAccentColors.presets
    val selectedIndex = presets.indexOfFirst { it.key == selected.key }.coerceAtLeast(0)
    val swatchesBackdrop = if (enableLiquidGlass) rememberLayerBackdrop() else null
    val haptic = androidx.compose.ui.platform.LocalHapticFeedback.current
    var activeTargetIndex by remember { mutableStateOf<Int?>(null) }
    val displayIndex = (activeTargetIndex ?: selectedIndex).coerceIn(0, presets.lastIndex)
    val pressProgress by animateFloatAsState(
        targetValue = if (activeTargetIndex != null) 1f else 0f,
        label = "accentColorPressProgress"
    )
    val indicatorColor by animateColorAsState(
        targetValue = presets[displayIndex].resolve(useDarkTheme),
        label = "accentColorIndicatorColor"
    )
    Column(modifier = modifier) {
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxWidth()
                .height(38.dp),
            contentAlignment = Alignment.CenterStart
        ) {
            val swatchSize = 38.dp
            val maxIndicatorOffset = (maxWidth - swatchSize).coerceAtLeast(0.dp)
            val targetOffset = if (presets.size > 1) {
                maxIndicatorOffset * (displayIndex.toFloat() / presets.lastIndex.toFloat())
            } else {
                0.dp
            }
            val indicatorOffset by animateDpAsState(
                targetValue = targetOffset,
                animationSpec = spring(stiffness = Spring.StiffnessMediumLow),
                label = "accentColorIndicatorOffset"
            )

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .let { base ->
                        if (swatchesBackdrop != null) base.layerBackdrop(swatchesBackdrop) else base
                    },
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                presets.forEachIndexed { index, option ->
                    val optionColor = option.resolve(useDarkTheme)
                    Box(
                        modifier = Modifier
                            .size(swatchSize)
                            .pointerInput(option.key, index) {
                                detectTapGestures(
                                    onPress = {
                                        haptic.performHapticFeedback(
                                            androidx.compose.ui.hapticfeedback.HapticFeedbackType.TextHandleMove
                                        )
                                        activeTargetIndex = index
                                        val released = tryAwaitRelease()
                                        if (released) {
                                            onSelected(option)
                                            delay(220L)
                                        }
                                        activeTargetIndex = null
                                    }
                                )
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        Box(
                            modifier = Modifier
                                .size(30.dp)
                                .background(optionColor, CircleShape)
                        )
                    }
                }
            }

            Box(
                modifier = Modifier
                    .offset(x = indicatorOffset)
                    .size(swatchSize)
                    .let { base ->
                        if (swatchesBackdrop != null) {
                            base.drawBackdrop(
                                backdrop = swatchesBackdrop,
                                shape = { CircleShape },
                                effects = {
                                    lens(
                                        10.dp.toPx() * pressProgress,
                                        14.dp.toPx() * pressProgress,
                                        chromaticAberration = true
                                    )
                                },
                                highlight = {
                                    Highlight.Default.copy(alpha = pressProgress)
                                },
                                shadow = {
                                    Shadow(alpha = pressProgress)
                                },
                                innerShadow = {
                                    InnerShadow(radius = 8.dp * pressProgress, alpha = pressProgress)
                                },
                                layerBlock = {
                                    val scale = 1f + 0.08f * pressProgress
                                    scaleX = scale
                                    scaleY = scale
                                },
                                onDrawSurface = {
                                    drawRect(indicatorColor.copy(alpha = 0.1f))
                                    drawRect(
                                        if (isLightTheme) Color.Black.copy(alpha = 0.1f)
                                        else Color.White.copy(alpha = 0.1f),
                                        alpha = 1f - pressProgress
                                    )
                                    drawRect(Color.Black.copy(alpha = 0.03f * pressProgress))
                                }
                            )
                        } else {
                            base.border(2.dp, indicatorColor, CircleShape)
                        }
                    }
            )
        }
        BasicText(
            selected.label,
            style = TextStyle(contentColor.copy(alpha = 0.62f), 13.sp),
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 10.dp)
                .wrapContentHeight(Alignment.CenterVertically)
        )
    }
}

@Composable
private fun SettingCheckItem(
    label: String,
    checked: Boolean,
    contentColor: Color,
    backgroundColor: Color,
    isLightTheme: Boolean,
    enableLiquidGlass: Boolean = true,
    onCheckedChange: (Boolean) -> Unit
) {
    val haptic = androidx.compose.ui.platform.LocalHapticFeedback.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { 
                haptic.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress)
                onCheckedChange(!checked) 
            }
            .padding(horizontal = 24.dp, vertical = 20.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            style = TextStyle(
                color = contentColor,
                fontSize = 16.sp,
                fontWeight = FontWeight.Medium
            ),
            modifier = Modifier.weight(1f)
        )
        if (enableLiquidGlass) {
            LiquidToggle(
                selected = { checked },
                onSelect = onCheckedChange,
                backdrop = rememberCanvasBackdrop { drawRect(backgroundColor) },
                isLightTheme = isLightTheme
            )
        } else {
            PlainSwitch(
                checked = checked,
                onCheckedChange = onCheckedChange,
                isLightTheme = isLightTheme
            )
        }
    }
}

@Composable
private fun SettingCheckItemWithSubtitle(
    label: String,
    subtitle: String,
    checked: Boolean,
    contentColor: Color,
    backgroundColor: Color,
    isLightTheme: Boolean,
    enableLiquidGlass: Boolean = true,
    onCheckedChange: (Boolean) -> Unit
) {
    val haptic = androidx.compose.ui.platform.LocalHapticFeedback.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable {
                haptic.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress)
                onCheckedChange(!checked)
            }
            .padding(horizontal = 24.dp, vertical = 16.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = label,
                style = TextStyle(
                    color = contentColor,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Medium
                )
            )
            Text(
                text = subtitle,
                style = TextStyle(
                    color = contentColor.copy(alpha = 0.5f),
                    fontSize = 12.sp
                )
            )
        }
        if (enableLiquidGlass) {
            LiquidToggle(
                selected = { checked },
                onSelect = onCheckedChange,
                backdrop = rememberCanvasBackdrop { drawRect(backgroundColor) },
                isLightTheme = isLightTheme
            )
        } else {
            PlainSwitch(
                checked = checked,
                onCheckedChange = onCheckedChange,
                isLightTheme = isLightTheme
            )
        }
    }
}

@Composable
private fun SettingActionItemWithSubtitle(
    label: String,
    subtitle: String,
    contentColor: Color,
    accentColor: Color,
    onClick: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 24.dp, vertical = 16.dp)
    ) {
        Text(
            text = label,
            color = contentColor,
            fontSize = 16.sp,
            fontWeight = FontWeight.Medium
        )
        Text(
            text = subtitle,
            color = if (subtitle == "已配置") accentColor else contentColor.copy(alpha = 0.5f),
            fontSize = 12.sp,
            modifier = Modifier.padding(top = 2.dp)
        )
    }
}

@Composable
private fun SettingTextItem(label: String, contentColor: Color, onClick: () -> Unit) {
    Text(
        text = label,
        style = TextStyle(
            color = contentColor,
            fontSize = 16.sp,
            fontWeight = FontWeight.Medium
        ),
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() }
            .padding(horizontal = 24.dp, vertical = 22.dp)
    )
}

@Composable
private fun UsageGuideSection(title: String, body: String) {
    val isLightTheme = MaterialTheme.colors.isLight
    val contentColor = if (isLightTheme) Color.Black else Color.White
    val groupBackgroundColor = if (isLightTheme) {
        Color(0xFFF2F3F7)
    } else {
        Color(0xFF1C1C20).copy(alpha = 0.94f)
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(28.dp))
            .background(groupBackgroundColor)
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Text(
            text = title,
            style = TextStyle(contentColor, 18.sp, FontWeight.Bold)
        )
        Text(
            text = body,
            style = TextStyle(contentColor.copy(alpha = 0.78f), 15.sp, lineHeight = 22.sp)
        )
    }
}

@Composable
private fun AcknowledgementLink(label: String, url: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            style = TextStyle(
                color = if (MaterialTheme.colors.isLight) Color.Black else Color.White,
                fontSize = 14.sp
            ),
            modifier = Modifier.weight(1f)
        )
        LinkText("项目", url)
    }
}


@Composable
private fun LinkText(label: String, url: String) {
    val uriHandler = androidx.compose.ui.platform.LocalUriHandler.current
    Text(
        text = label,
        style = TextStyle(
            color = MaterialTheme.colors.primary,
            fontSize = 14.sp,
            textDecoration = TextDecoration.Underline
        ),
        modifier = Modifier.clickable { uriHandler.openUri(url) }
    )
}

private fun getVersionName(context: Context): String {
    var verName = "获取失败"
    try {
        verName = context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "获取失败"
    } catch (e: PackageManager.NameNotFoundException) {
        e.printStackTrace()
    }
    return verName
}

private fun getAboutText(): String {
    return (
        "天音壁纸是一个用来设置壁纸的软件>_<\n" +
                "分为常规壁纸页和光栅壁纸页\n" +
                "常规壁纸页可以设置视频图片壁纸\n" +
            "点击“+”，可以增加当前壁纸组的壁纸\n" +
            "点击“√”，会把当前壁纸组设置为手机壁纸\n" +
            "点击“…”，显示更多选项，可以保存当前壁纸组\n" +
            "设置页可以设置壁纸切换方式等\n" +
            "点击壁纸缩略图，可以选择删除壁纸或者设置壁纸显示的条件，长按可以调整顺序\n" +
            "当满足条件时，会优先显示满足条件的壁纸，借此，可以设置早安壁纸，下班壁纸\n" +
            "HyperOS3不支持壁纸随屏幕滚动\n" + "\n" +
            "原作者:十二今天也很可爱 @prpr12"
    )
}

private const val DEFAULT_AUTO_SWITCH_INTERVAL_SECONDS = 3600L
private const val DEFAULT_AUTO_SWITCH_TIME_POINTS = "12:00"
private const val AUTO_SWITCH_MODE_NONE = 0
private val AUTO_SWITCH_MODE_ITEMS = arrayOf("离开桌面", "时间间隔", "时间点")
private const val PREF_CORRUGATED_TEST_ENABLED = "corrugated_test_enabled"
private const val BACKUP_METADATA_ENTRY = "metadata.json"
private const val BACKUP_PREF_ENTRY = "preferences/tianyin.json"
private const val BACKUP_SOG_HISTORY_ENTRY = "online/sog-generation-history.json"
private const val BACKUP_INTERNAL_FILES_PREFIX = "files/internal/"
private const val BACKUP_EXTERNAL_FILES_PREFIX = "files/external/"
private const val BACKUP_INTERNAL_CACHE_PREFIX = "cache/internal/"
private const val BACKUP_EXTERNAL_CACHE_PREFIX = "cache/external/"
private val DATA_PREFERENCE_KEYS = setOf(
    "wallpaperCache",
    "wallpaperTvCache",
    "mainCustomWallpaperOrder",
    RasterPrefs.PREF_RASTER_GROUPS,
    RasterPrefs.PREF_RASTER_ACTIVE_GROUP_ID,
    DepthPrefs.PREF_DEPTH_WALLPAPERS,
    DepthPrefs.PREF_DEPTH_ACTIVE_ID
)
