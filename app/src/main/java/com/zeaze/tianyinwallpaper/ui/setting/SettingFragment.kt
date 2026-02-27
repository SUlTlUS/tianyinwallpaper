package com.zeaze.tianyinwallpaper.ui.setting

import android.content.Context
import android.content.pm.PackageManager
import android.text.TextUtils
import android.widget.Toast
import java.util.Locale
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.ExperimentalAnimationApi
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.Velocity
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
import com.zeaze.tianyinwallpaper.catalog.components.LiquidToggle
import com.zeaze.tianyinwallpaper.service.TianYinWallpaperService
import kotlinx.coroutines.launch
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.LocalOverscrollConfiguration
import androidx.compose.foundation.pager.PagerDefaults
import androidx.compose.foundation.pager.PagerSnapDistance
import androidx.compose.foundation.pager.VerticalPager
import androidx.compose.foundation.pager.rememberPagerState

private sealed class SettingsDialogState {
    object MinTime : SettingsDialogState()
    object AutoMode : SettingsDialogState()
    object AutoInterval : SettingsDialogState()
    object AutoPoints : SettingsDialogState()
    object PickTime : SettingsDialogState()
    object Theme : SettingsDialogState()
}

@OptIn(ExperimentalAnimationApi::class)
@Composable
fun SettingRouteScreen(
    onThemeModeChange: (Int) -> Unit = {}
) {
    val context = LocalContext.current
    val pref = remember(context) { context.getSharedPreferences(App.TIANYIN, Context.MODE_PRIVATE) }
    val editor = remember(pref) { pref.edit() }

    var rand by remember { mutableStateOf(pref.getBoolean("rand", false)) }
    var pageChange by remember { mutableStateOf(pref.getBoolean("pageChange", false)) }
    var needBackgroundPlay by remember { mutableStateOf(pref.getBoolean("needBackgroundPlay", false)) }
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
    var minTimeInput by remember { mutableStateOf(minTime.toString()) }
    var tempMinTime by remember { mutableStateOf(minTime) }
    var showAutoModeDialog by remember { mutableStateOf(false) }
    var showThemeDialog by remember { mutableStateOf(false) }

    var showAutoIntervalDialog by remember { mutableStateOf(false) }
    var showAutoPointsDialog by remember { mutableStateOf(false) }
    var showTimePickerDialog by remember { mutableStateOf(false) }
    var autoPointsInput by remember { mutableStateOf(autoSwitchPoints) }

    var pickingHour by remember { mutableStateOf(12) }
    var pickingMinute by remember { mutableStateOf(0) }

    val currentDialogState = when {
        showMinTimeDialog -> SettingsDialogState.MinTime
        showAutoModeDialog -> SettingsDialogState.AutoMode
        showAutoIntervalDialog -> SettingsDialogState.AutoInterval
        showAutoPointsDialog -> SettingsDialogState.AutoPoints
        showTimePickerDialog -> SettingsDialogState.PickTime
        showThemeDialog -> SettingsDialogState.Theme
        else -> null
    }

    val isLightTheme = !isSystemInDarkTheme()
    val backgroundColor =
        if (isLightTheme) Color(0xFFFFFFFF)
        else Color(0xFF121212)
    val contentColor = if (isLightTheme) Color.Black else Color.White
    val accentColor = if (isLightTheme) Color(0xFF0088FF) else Color(0xFF0091FF)
    val containerColor = if (isLightTheme) Color(0xFFFAFAFA).copy(0.6f) else Color(0xFF121212).copy(0.4f)
    val dimColor = if (isLightTheme) Color(0xFF29293A).copy(0.23f) else Color(0xFF121212).copy(0.56f)

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
            Box(Modifier.fillMaxSize().background(MaterialTheme.colors.background))

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
                        .background(MaterialTheme.colors.surface, RoundedCornerShape(48.dp))
                        .padding(24.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    SettingCheckItem("随机切换壁纸", rand, contentColor, backgroundColor) {
                        rand = it
                        editor.putBoolean("rand", it).apply()
                    }
                    SettingCheckItem("进入桌面切换壁纸", pageChange, contentColor, backgroundColor) {
                        pageChange = it
                        editor.putBoolean("pageChange", it).apply()
                    }
                    SettingCheckItem("后台播放动态壁纸", needBackgroundPlay, contentColor, backgroundColor) {
                        needBackgroundPlay = it
                        editor.putBoolean("needBackgroundPlay", it).apply()
                    }
                    SettingCheckItem("壁纸跟随屏幕滚动", wallpaperScroll, contentColor, backgroundColor) {
                        wallpaperScroll = it
                        editor.putBoolean("wallpaperScroll", it).apply()
                    }
                }

                // Settings Group 2: Advanced
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(MaterialTheme.colors.surface, RoundedCornerShape(48.dp))
                        .padding(24.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    SettingTextItem("壁纸最小切换时间: ${minTime}秒", contentColor) {
                        tempMinTime = minTime
                        showMinTimeDialog = true
                    }
                    val themeModeText = when (themeMode) {
                        MainActivity.THEME_MODE_LIGHT -> "浅色"
                        MainActivity.THEME_MODE_DARK -> "深色"
                        else -> "跟随系统"
                    }
                    SettingTextItem("主题模式：$themeModeText", contentColor) {
                        showThemeDialog = true
                    }
                    val modeText = if (autoSwitchMode >= AUTO_SWITCH_MODE_NONE && autoSwitchMode < AUTO_SWITCH_MODE_ITEMS.size) {
                        AUTO_SWITCH_MODE_ITEMS[autoSwitchMode]
                    } else {
                        AUTO_SWITCH_MODE_ITEMS[AUTO_SWITCH_MODE_NONE]
                    }
                SettingTextItem("自动切换模式：$modeText", contentColor) {
                    showAutoModeDialog = true
                }

                if (autoSwitchMode != AUTO_SWITCH_MODE_NONE) {
                    Column(Modifier.padding(vertical = 4.dp)) {
                        if (autoSwitchMode == 1) {
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
                        if (autoSwitchMode == 2) {
                            SettingTextItem("自动切换时间点：$autoSwitchPoints", contentColor.copy(0.8f)) {
                                autoPointsInput = autoSwitchPoints
                                showAutoPointsDialog = true
                            }
                        }
                    }
                }
                }

                AboutSection(
                    context = context,
                    contentColor = contentColor
                )
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
                        showAutoModeDialog = false
                        showAutoIntervalDialog = false
                        showAutoPointsDialog = false
                        showTimePickerDialog = false
                        showThemeDialog = false
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
                    .togetherWith(fadeOut(animationSpec = spring(stiffness = Spring.StiffnessLow)))
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
                        SettingsDialogState.AutoMode -> {
                            Column(
                                Modifier.padding(16.dp, 20.dp, 16.dp, 20.dp).fillMaxWidth(),
                                verticalArrangement = Arrangement.spacedBy(8.dp),
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                BasicText("选择自动切换模式", style = TextStyle(contentColor, 18.sp, fontWeight = FontWeight.Bold))
                                Spacer(Modifier.height(8.dp))
                                AUTO_SWITCH_MODE_ITEMS.forEachIndexed { index, mode ->
                                    val isSelected = autoSwitchMode == index
                                    Row(
                                        Modifier
                                            .clip(Capsule())
                                            .background(if (isSelected) accentColor else containerColor.copy(0.2f))
                                            .clickable {
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
                                                showAutoModeDialog = false
                                            }
                                            .height(48.dp)
                                            .fillMaxWidth(),
                                        horizontalArrangement = Arrangement.Center,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        BasicText(
                                            if (isSelected) "✓ $mode" else mode,
                                            style = TextStyle(if (isSelected) Color.White else contentColor, 16.sp)
                                        )
                                    }
                                }
                                Row(
                                    Modifier
                                        .clip(Capsule())
                                        .background(containerColor.copy(0.2f))
                                        .clickable { showAutoModeDialog = false }
                                        .height(48.dp)
                                        .fillMaxWidth(),
                                    horizontalArrangement = Arrangement.Center,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    BasicText("取消", style = TextStyle(contentColor, 16.sp))
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
                                                    editor.putLong(TianYinWallpaperService.PREF_AUTO_SWITCH_INTERVAL_SECONDS, totalSeconds).apply()
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
                                        .height(200.dp)
                                        .clip(RoundedCornerShape(24.dp))
                                        .background(containerColor.copy(0.1f))
                                        .padding(8.dp)
                                        .verticalScroll(rememberScrollState()),
                                    verticalArrangement = Arrangement.spacedBy(8.dp)
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
                                                    editor.putString(TianYinWallpaperService.PREF_AUTO_SWITCH_TIME_POINTS, autoPointsInput).apply()
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
                        SettingsDialogState.Theme -> {
                            val themeOptions = listOf(
                                MainActivity.THEME_MODE_FOLLOW_SYSTEM to "跟随系统",
                                MainActivity.THEME_MODE_LIGHT to "浅色",
                                MainActivity.THEME_MODE_DARK to "深色"
                            )
                            Column(
                                Modifier.padding(16.dp, 20.dp, 16.dp, 20.dp).fillMaxWidth(),
                                verticalArrangement = Arrangement.spacedBy(8.dp),
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                BasicText("选择主题模式", style = TextStyle(contentColor, 18.sp, fontWeight = FontWeight.Bold))
                                Spacer(Modifier.height(8.dp))
                                themeOptions.forEach { (mode, label) ->
                                    val isSelected = themeMode == mode
                                    Row(
                                        Modifier
                                            .clip(Capsule())
                                            .background(if (isSelected) accentColor else containerColor.copy(0.2f))
                                            .clickable {
                                                editor.putInt(MainActivity.PREF_THEME_MODE, mode).apply()
                                                themeMode = mode
                                                onThemeModeChange(mode)
                                                showThemeDialog = false
                                            }
                                            .height(48.dp)
                                            .fillMaxWidth(),
                                        horizontalArrangement = Arrangement.Center,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        BasicText(
                                            if (isSelected) "✓ $label" else label,
                                            style = TextStyle(if (isSelected) Color.White else contentColor, 16.sp)
                                        )
                                    }
                                }
                                Row(
                                    Modifier
                                        .clip(Capsule())
                                        .background(containerColor.copy(0.2f))
                                        .clickable { showThemeDialog = false }
                                        .height(48.dp)
                                        .fillMaxWidth(),
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

@Composable
private fun SettingCheckItem(
    label: String,
    checked: Boolean,
    contentColor: Color,
    backgroundColor: Color,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onCheckedChange(!checked) }
            .padding(vertical = 8.dp),
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
            .padding(vertical = 10.dp)
    )
}

@Composable
private fun AboutSection(
    context: Context,
    contentColor: Color
) {
    val verName = getVersionName(context)
    val aboutText = remember { getAboutText() }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colors.surface, RoundedCornerShape(48.dp))
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(
            text = "关于",
            style = TextStyle(contentColor, 18.sp, FontWeight.Bold)
        )
        Text(
            text = aboutText,
            style = TextStyle(contentColor.copy(0.8f), 14.sp)
        )
        Spacer(Modifier.height(8.dp))
        LinkText("QQ群: 722673402", "https://jq.qq.com/?_wv=1027&k=vjcrjY7L")
        LinkText("项目开源地址", "https://github.com/prpr12/tianyinwallpaper.git")
        LinkText("软件下载地址", "https://www.pgyer.com/eEna")
        Text(
            text = "当前版本号：$verName",
            style = TextStyle(contentColor.copy(0.5f), 12.sp)
        )
    }
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
            "点击“应用本组”，会把当前壁纸组设置为手机壁纸，每次进入桌面，都会更新显示壁纸组里的下一张壁纸\n" +
            "点击右上角的齿轮，可以保存当前壁纸组\n" +
            "齿轮里的“壁纸通用设置”，可以设置通用的壁纸切换方式\n" +
            "目前支持顺序切换和随机切换，和最小切换时间\n" +
            "最小切换时间的意思是在切换壁纸后，未达这个时间间隔的话是不会二次切换壁纸的\n" +
            "齿轮里的“清空当前壁纸组”，可以方便的一键清空壁纸组来设置新的壁纸\n" +
            "点击壁纸缩略图，可以选择删除壁纸或者设置壁纸显示的条件，长按可以调整顺序\n" +
            "当满足条件时，会优先显示满足条件的壁纸，借此，可以设置早安壁纸，下班壁纸\n" +
            "目前仅支持按时间设置条件，开始时间为闭区间，结束时间为开区间"
    )
}

private const val DEFAULT_AUTO_SWITCH_INTERVAL_SECONDS = 3600L
private const val DEFAULT_AUTO_SWITCH_TIME_POINTS = "12:00"
private const val AUTO_SWITCH_MODE_NONE = 0
private val AUTO_SWITCH_MODE_ITEMS = arrayOf("手动切换", "按固定时间间隔切换", "按每日时间点切换")

@OptIn(ExperimentalAnimationApi::class, ExperimentalFoundationApi::class)
@Composable
private fun WheelPicker(
    count: Int,
    initialIndex: Int,
    onItemSelected: (Int) -> Unit,
    contentColor: Color,
    label: String,
    modifier: Modifier = Modifier
) {
    val haptic = LocalHapticFeedback.current
    val pagerState = rememberPagerState(initialPage = initialIndex, pageCount = { count })
    val coroutineScope = rememberCoroutineScope()
    val overscrollOffset = remember { Animatable(0f) }

    LaunchedEffect(pagerState.currentPage) {
        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
        onItemSelected(pagerState.currentPage)
    }

    val nestedScrollConnection = remember {
        object : NestedScrollConnection {
            override fun onPostScroll(
                consumed: Offset,
                available: Offset,
                source: NestedScrollSource
            ): Offset {
                // 当 Pager 无法消耗位移时（即到达边界），我们手动处理位移产生回弹感
                if (available.y != 0f) {
                    val isAtTop = pagerState.currentPage == 0 && available.y > 0
                    val isAtBottom = pagerState.currentPage == count - 1 && available.y < 0
                    if (isAtTop || isAtBottom) {
                        coroutineScope.launch {
                            // 减小系数以产生拉力感
                            overscrollOffset.snapTo(overscrollOffset.value + available.y * 0.4f)
                        }
                        return Offset(0f, available.y)
                    }
                }
                return Offset.Zero
            }

            override suspend fun onPreFling(available: Velocity): Velocity {
                // 如果目前有回弹偏移，停止任何惯性滚动，直接准备回弹
                if (Math.abs(overscrollOffset.value) > 0.1f) {
                    return available
                }
                return Velocity.Zero
            }

            override suspend fun onPostFling(consumed: Velocity, available: Velocity): Velocity {
                overscrollOffset.animateTo(
                    0f,
                    spring(stiffness = Spring.StiffnessLow, dampingRatio = Spring.DampingRatioMediumBouncy)
                )
                return super.onPostFling(consumed, available)
            }
        }
    }

    Row(
        modifier = modifier
            .nestedScroll(nestedScrollConnection)
            .graphicsLayer {
                translationY = overscrollOffset.value
                val scale = 1f - (Math.abs(overscrollOffset.value) / 1000f).coerceAtMost(0.05f)
                scaleX = scale
                scaleY = scale
            },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center
    ) {
        Box(modifier = Modifier.weight(1f)) {
            // 彻底禁用 Pager 的原生越界效果，防止其吞掉滚动增量
            CompositionLocalProvider(
                LocalOverscrollConfiguration provides null
            ) {
                VerticalPager(
                    state = pagerState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(vertical = 60.dp),
                    flingBehavior = PagerDefaults.flingBehavior(
                        state = pagerState,
                        pagerSnapDistance = PagerSnapDistance.atMost(count)
                    )
                ) { page ->
                    val isSelected = pagerState.currentPage == page
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = if (page < 10) "0$page" else "$page",
                            style = TextStyle(
                                color = if (isSelected) contentColor else contentColor.copy(alpha = 0.3f),
                                fontSize = if (isSelected) 22.sp else 18.sp,
                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
                            )
                        )
                    }
                }
            }
        }
        Text(
            text = label,
            style = TextStyle(color = contentColor, fontSize = 14.sp),
            modifier = Modifier.padding(start = 4.dp, end = 8.dp)
        )
    }
}
