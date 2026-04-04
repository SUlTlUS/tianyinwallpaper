package com.zeaze.tianyinwallpaper.ui.setting

import java.util.Locale
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.text.TextUtils
import android.widget.Toast
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.ExperimentalAnimationApi
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.expandVertically
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Surface
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kyant.shapes.Capsule
import com.kyant.shapes.RoundedRectangle
import com.zeaze.tianyinwallpaper.backdrop.backdrops.layerBackdrop
import com.zeaze.tianyinwallpaper.backdrop.backdrops.rememberLayerBackdrop
import com.zeaze.tianyinwallpaper.backdrop.backdrops.rememberCanvasBackdrop
import com.zeaze.tianyinwallpaper.backdrop.drawBackdrop
import com.zeaze.tianyinwallpaper.backdrop.effects.blur
import com.zeaze.tianyinwallpaper.backdrop.effects.colorControls
import com.zeaze.tianyinwallpaper.backdrop.effects.lens
import com.zeaze.tianyinwallpaper.backdrop.highlight.Highlight
import com.zeaze.tianyinwallpaper.App
import com.zeaze.tianyinwallpaper.MainActivity
import com.zeaze.tianyinwallpaper.catalog.components.LiquidSegmentedOption
import com.zeaze.tianyinwallpaper.catalog.components.LiquidSegmentedSelector
import com.zeaze.tianyinwallpaper.catalog.components.LiquidToggle
import com.zeaze.tianyinwallpaper.catalog.components.LiquidButton
import com.zeaze.tianyinwallpaper.catalog.components.WheelPicker
import com.zeaze.tianyinwallpaper.service.TianYinWallpaperService
import com.zeaze.tianyinwallpaper.update.AppUpdateManager
import com.zeaze.tianyinwallpaper.update.UpdateDialog
import com.zeaze.tianyinwallpaper.update.UpdateDialogState
import kotlinx.coroutines.launch

private sealed class SettingsDialogState {
    object MinTime : SettingsDialogState()
    object AutoInterval : SettingsDialogState()
    object AutoPoints : SettingsDialogState()
    object PickTime : SettingsDialogState()
}

@OptIn(ExperimentalAnimationApi::class)
@Composable
fun SettingRouteScreen(
    useDarkTheme: Boolean,
    onThemeModeChange: (Int) -> Unit = {},
    onOpenAppInfo: () -> Unit = {}
) {
    val context = LocalContext.current
    val pref = remember(context) { context.getSharedPreferences(App.TIANYIN, Context.MODE_PRIVATE) }
    val editor = remember(pref) { pref.edit() }

    var hidePermissionDialog by remember { mutableStateOf(pref.getBoolean("hide_permission_dialog", false)) }
    var rand by remember { mutableStateOf(pref.getBoolean("rand", false)) }
    var pageChange by remember { mutableStateOf(pref.getBoolean("pageChange", false)) }
    var wallpaperScroll by remember { mutableStateOf(pref.getBoolean("wallpaperScroll", false)) }
    var minTime by remember { mutableStateOf(pref.getInt("minTime", 1)) }
    var themeMode by remember { mutableStateOf(pref.getInt(MainActivity.PREF_THEME_MODE, MainActivity.THEME_MODE_FOLLOW_SYSTEM)) }
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

    var pickingHour by remember { mutableStateOf(12) }
    var pickingMinute by remember { mutableStateOf(0) }

    val currentDialogState = when {
        showMinTimeDialog -> SettingsDialogState.MinTime
        showAutoIntervalDialog -> SettingsDialogState.AutoInterval
        showAutoPointsDialog -> SettingsDialogState.AutoPoints
        showTimePickerDialog -> SettingsDialogState.PickTime
        else -> null
    }

    val isLightTheme = !useDarkTheme
    val backgroundColor =
        if (isLightTheme) Color(0xFFF2F2F6)
        else Color(0xFF121212)
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
    val accentColor = if (isLightTheme) Color(0xFF0088FF) else Color(0xFF0091FF)
    val containerColor = if (isLightTheme) Color(0xFFFAFAFA).copy(0.6f) else Color(0xFF121212).copy(0.4f)
    val dimColor = if (isLightTheme) Color(0xFF29293A).copy(0.23f) else Color(0xFF121212).copy(0.56f)
    val groupBackgroundColor = if (isLightTheme) Color.White else Color(0xFF1E1E1E)

    val enableLiquidGlass = android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU
    val liquidBackdrop = if (enableLiquidGlass) rememberLayerBackdrop() else null

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
                    .verticalScroll(rememberScrollState())
                    .statusBarsPadding()
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Text(
                    text = "设置",
                    style = TextStyle(
                        color = contentColor,
                        fontSize = 32.sp,
                        fontWeight = FontWeight.Bold
                    ),
                    modifier = Modifier.padding(bottom = 8.dp)
                )

                // Settings Group 1: General
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(28.dp))
                        .background(groupBackgroundColor)
                ) {
                    SettingCheckItem("不显示权限提示", hidePermissionDialog, contentColor, groupBackgroundColor, isLightTheme) {
                        hidePermissionDialog = it
                        editor.putBoolean("hide_permission_dialog", it).apply()
                    }
                    SettingCheckItem("随机切换壁纸", rand, contentColor, groupBackgroundColor, isLightTheme) {
                        rand = it
                        editor.putBoolean("rand", it).apply()
                    }
                    SettingCheckItem("壁纸跟随屏幕滚动", wallpaperScroll, contentColor, groupBackgroundColor, isLightTheme) {
                        wallpaperScroll = it
                        editor.putBoolean("wallpaperScroll", it).apply()
                        if (it && pageChange) {
                            pageChange = false
                            editor.putBoolean("pageChange", false).apply()
                        }
                    }
                }

                // Settings Group 2: Advanced
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
                }

                // Settings Group 3: Switch Mode
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(28.dp))
                        .background(groupBackgroundColor)
                ) {
                    Text(
                        text = "切换模式",
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
                            .padding(bottom = 12.dp)
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
                            SettingCheckItem("滑动桌面切换壁纸", pageChange, contentColor, groupBackgroundColor, isLightTheme) {
                                pageChange = it
                                editor.putBoolean("pageChange", it).apply()
                                if (it && wallpaperScroll) {
                                    wallpaperScroll = false
                                    editor.putBoolean("wallpaperScroll", false).apply()
                                }
                            }
                            SettingTextItem("壁纸最小切换时间: ${minTime}秒", contentColor.copy(0.8f)) {
                                tempMinTime = minTime
                                showMinTimeDialog = true
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
                        SettingTextItem("自动切换间隔：$intervalText", contentColor.copy(0.8f)) {
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
                        SettingTextItem("自动切换时间点：$autoSwitchPoints", contentColor.copy(0.8f)) {
                            autoPointsInput = autoSwitchPoints
                            showAutoPointsDialog = true
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
                    SettingTextItem("关于", contentColor) {
                        onOpenAppInfo()
                    }
                }
                
                // Keep the Check Update button separate and styled as before
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clip(Capsule())
                        .background(accentColor)
                        .clickable { 
                            shouldCheckUpdate = true
                            updateDialogState = UpdateDialogState(isVisible = true, isChecking = true)
                        }
                        .height(48.dp),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    BasicText("检查更新", style = TextStyle(Color.White, 16.sp, fontWeight = FontWeight.Bold))
                }
            }
        }

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
                            if (md5 != null && md5.equals(info.md5, ignoreCase = true)) {
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

        // 1. Background dimming layer
        AnimatedVisibility(
            visible = currentDialogState != null,
            enter = fadeIn(),
            exit = fadeOut()
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(dimColor)
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null
                    ) {
                        showMinTimeDialog = false
                        showAutoIntervalDialog = false
                        showAutoPointsDialog = false
                        showTimePickerDialog = false
                    }
            )
        }

        // 2. Custom Liquid Glass Dialog
        AnimatedContent(
            targetState = currentDialogState,
            transitionSpec = {
                (fadeIn(animationSpec = spring(stiffness = Spring.StiffnessLow)) +
                        scaleIn(
                            initialScale = 0.8f,
                            animationSpec = spring(
                                dampingRatio = Spring.DampingRatioMediumBouncy,
                                stiffness = Spring.StiffnessLow
                            )
                        ))
                    .togetherWith(
                        fadeOut(animationSpec = spring(stiffness = Spring.StiffnessLow)) +
                                scaleOut(
                                    targetScale = 0.8f,
                                    animationSpec = spring(stiffness = Spring.StiffnessLow)
                                )
                    )
            },
            contentAlignment = Alignment.Center,
            label = "SettingsDialogOverlay",
            modifier = Modifier.fillMaxSize()
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
fun AppInfoRouteScreen(
    useDarkTheme: Boolean
) {
    val context = LocalContext.current
    val density = androidx.compose.ui.platform.LocalDensity.current
    val statusBarTopPadding = remember(context) {
        val id = context.resources.getIdentifier("status_bar_height", "dimen", "android")
        if (id > 0) context.resources.getDimensionPixelSize(id) else 0
    }
    val statusBarTopPaddingDp = with(density) { statusBarTopPadding.toDp() }

    val isLightTheme = !useDarkTheme
    val backgroundColor =
        if (isLightTheme) Color(0xFFF2F2F6)
        else Color(0xFF121212)
    val contentColor = if (isLightTheme) Color.Black else Color.White
    val containerColor = if (isLightTheme) Color.White else Color(0xFF1E1E1E)

    val enableLiquidGlass = android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU
    val liquidBackdrop = if (enableLiquidGlass) rememberLayerBackdrop() else null

    val verName = getVersionName(context)
    val aboutText = remember { getAboutText() }

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
                .padding(top = statusBarTopPaddingDp)
                .verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // App Icon
            Spacer(modifier = Modifier.height(48.dp))
            Box(
                modifier = Modifier
                    .height(100.dp)
                    .fillMaxWidth(),
                contentAlignment = Alignment.Center
            ) {
                // You can add an actual Image here later, for now just text
                Text(
                    text = "天音壁纸",
                    style = TextStyle(
                        color = contentColor,
                        fontSize = 28.sp,
                        fontWeight = FontWeight.Bold
                    )
                )
            }
            Text(
                text = "版本 $verName",
                style = TextStyle(
                    color = contentColor.copy(0.6f),
                    fontSize = 14.sp
                )
            )
            Spacer(modifier = Modifier.height(32.dp))

            // Main Info Block
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp)
                    .clip(RoundedCornerShape(28.dp))
                    .background(containerColor)
                    .padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Text(
                    text = "使用说明",
                    style = TextStyle(contentColor, 18.sp, FontWeight.Bold)
                )
                Text(
                    text = aboutText,
                    style = TextStyle(color = contentColor.copy(0.8f), fontSize = 15.sp, lineHeight = 22.sp)
                )
            }
            
            Spacer(modifier = Modifier.height(24.dp))

            // Links Block
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp)
                    .clip(RoundedCornerShape(28.dp))
                    .background(containerColor)
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
                
                Box(modifier = Modifier.fillMaxWidth().height(0.5.dp).padding(horizontal = 24.dp).background(contentColor.copy(0.1f)))
                
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
            }
            
            Spacer(modifier = Modifier.height(48.dp))
        }
    }
}

@Composable
private fun SettingCheckItem(
    label: String,
    checked: Boolean,
    contentColor: Color,
    backgroundColor: Color,
    isLightTheme: Boolean,
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
        LiquidToggle(
            selected = { checked },
            onSelect = onCheckedChange,
            backdrop = rememberCanvasBackdrop { drawRect(backgroundColor) },
            isLightTheme = isLightTheme
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
private fun LinkText(label: String, url: String) {
    val uriHandler = androidx.compose.ui.platform.LocalUriHandler.current
    Text(
        text = label,
        style = TextStyle(
            color = Color(0xFF0088FF),
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
            "点击“增加壁纸”，可以增加当前壁纸组的壁纸\n" +
            "点击“应用本组”，会把当前壁纸组设置为手机壁纸，每次离开桌面，都会更新显示壁纸组里的下一张壁纸\n" +
            "点击右上角的齿轮，可以保存当前壁纸组\n" +
            "齿轮里的“壁纸通用设置”，可以设置通用的壁纸切换方式\n" +
            "目前支持顺序切换和随机切换，和最小切换时间\n" +
            "最小切换时间的意思是在切换壁纸后，未达这个时间间隔的话是不会二次切换壁纸的\n" +
            "点击壁纸缩略图，可以选择删除壁纸或者设置壁纸显示的条件，长按可以调整顺序\n" +
            "当满足条件时，会优先显示满足条件的壁纸，借此，可以设置早安壁纸，下班壁纸\n" +
            "目前仅支持按时间设置条件，开始时间为闭区间，结束时间为开区间\n" + "\n" +
            "HyperOS3不支持壁纸随屏幕滚动"
    )
}

private const val DEFAULT_AUTO_SWITCH_INTERVAL_SECONDS = 3600L
private const val DEFAULT_AUTO_SWITCH_TIME_POINTS = "12:00"
private const val AUTO_SWITCH_MODE_NONE = 0
private val AUTO_SWITCH_MODE_ITEMS = arrayOf("离开桌面", "时间间隔", "时间点")
