package com.zeaze.tianyinwallpaper.ui.setting

import android.content.Context
import android.content.pm.PackageManager
import android.text.TextUtils
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.AlertDialog
import androidx.compose.material.Button
import androidx.compose.material.MaterialTheme
import androidx.compose.material.OutlinedTextField
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kyant.backdrop.backdrops.LayerBackdrop
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.effects.colorControls
import com.kyant.backdrop.effects.lens
import com.kyant.backdrop.effects.vibrancy
import com.kyant.backdrop.highlight.Highlight
import com.zeaze.tianyinwallpaper.App
import com.zeaze.tianyinwallpaper.MainActivity
import com.zeaze.tianyinwallpaper.service.TianYinWallpaperService

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
        mutableStateOf(pref.getLong(TianYinWallpaperService.PREF_AUTO_SWITCH_INTERVAL_MINUTES, DEFAULT_AUTO_SWITCH_INTERVAL_MINUTES))
    }
    var autoSwitchPoints by remember {
        mutableStateOf(
            pref.getString(TianYinWallpaperService.PREF_AUTO_SWITCH_TIME_POINTS, DEFAULT_AUTO_SWITCH_TIME_POINTS)
                .takeUnless { TextUtils.isEmpty(it) } ?: DEFAULT_AUTO_SWITCH_TIME_POINTS
        )
    }
    var showMinTimeDialog by remember { mutableStateOf(false) }
    var minTimeInput by remember { mutableStateOf(minTime.toString()) }
    var showAutoModeDialog by remember { mutableStateOf(false) }
    var showThemeDialog by remember { mutableStateOf(false) }

    val isLightTheme = !isSystemInDarkTheme()
    val contentColor = if (isLightTheme) Color.Black else Color.White
    val accentColor = if (isLightTheme) Color(0xFF0088FF) else Color(0xFF0091FF)
    val containerColor = if (isLightTheme) Color(0xFFFAFAFA).copy(0.6f) else Color(0xFF121212).copy(0.4f)
    val dimColor = if (isLightTheme) Color(0xFF29293A).copy(0.23f) else Color(0xFF121212).copy(0.56f)

    val enableLiquidGlass = android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU
    val liquidBackdrop = if (enableLiquidGlass) rememberLayerBackdrop() else null

    Box(modifier = Modifier.fillMaxSize()) {
        // Capture layer
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
        }

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
                SettingCheckItem("随机切换壁纸", rand, contentColor, liquidBackdrop) {
                    rand = it
                    editor.putBoolean("rand", it).apply()
                }
                SettingCheckItem("进入桌面切换壁纸", pageChange, contentColor, liquidBackdrop) {
                    pageChange = it
                    editor.putBoolean("pageChange", it).apply()
                }
                SettingCheckItem("后台播放动态壁纸", needBackgroundPlay, contentColor, liquidBackdrop) {
                    needBackgroundPlay = it
                    editor.putBoolean("needBackgroundPlay", it).apply()
                }
                SettingCheckItem("壁纸跟随屏幕滚动", wallpaperScroll, contentColor, liquidBackdrop) {
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
                    minTimeInput = minTime.toString()
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

                Column(Modifier.padding(vertical = 4.dp)) {
                    Text(
                        text = "自动切换间隔：${autoSwitchInterval}分钟",
                        style = TextStyle(contentColor.copy(0.6f), 14.sp)
                    )
                    Text(
                        text = "自动切换时间点：$autoSwitchPoints",
                        style = TextStyle(contentColor.copy(0.6f), 14.sp)
                    )
                }
            }

            AboutSection(
                context = context,
                contentColor = contentColor
            )
        }
    }

    if (showMinTimeDialog) {
        AlertDialog(
            onDismissRequest = { showMinTimeDialog = false },
            title = { Text("请输入最小切换时间（秒）") },
            text = {
                OutlinedTextField(
                    value = minTimeInput,
                    onValueChange = { minTimeInput = it },
                    singleLine = true,
                    label = { Text("秒") }
                )
            },
            confirmButton = {
                Button(onClick = {
                    try {
                        val value = minTimeInput.toInt()
                        editor.putInt("minTime", value).apply()
                        minTime = value
                        showMinTimeDialog = false
                    } catch (_: Exception) {
                        Toast.makeText(context, "请输入整数", Toast.LENGTH_SHORT).show()
                    }
                }) { Text("确定") }
            },
            dismissButton = {
                Button(onClick = { showMinTimeDialog = false }) { Text("取消") }
            }
        )
    }

    if (showAutoModeDialog) {
        val checked = autoSwitchMode
        AlertDialog(
            onDismissRequest = { showAutoModeDialog = false },
            title = { Text("选择自动切换模式") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    AUTO_SWITCH_MODE_ITEMS.forEachIndexed { index, mode ->
                        Button(
                            onClick = {
                                editor.putInt(TianYinWallpaperService.PREF_AUTO_SWITCH_MODE, index)
                                editor.putLong(TianYinWallpaperService.PREF_AUTO_SWITCH_ANCHOR_AT, System.currentTimeMillis())
                                editor.putLong(TianYinWallpaperService.PREF_AUTO_SWITCH_LAST_SWITCH_AT, 0L)
                                editor.apply()
                                autoSwitchMode = index
                                autoSwitchInterval = pref.getLong(
                                    TianYinWallpaperService.PREF_AUTO_SWITCH_INTERVAL_MINUTES,
                                    DEFAULT_AUTO_SWITCH_INTERVAL_MINUTES
                                )
                                autoSwitchPoints = pref.getString(
                                    TianYinWallpaperService.PREF_AUTO_SWITCH_TIME_POINTS,
                                    DEFAULT_AUTO_SWITCH_TIME_POINTS
                                ).takeUnless { TextUtils.isEmpty(it) } ?: DEFAULT_AUTO_SWITCH_TIME_POINTS
                                showAutoModeDialog = false
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) { Text(if (index == checked) "✓ $mode" else mode) }
                    }
                    Button(onClick = { showAutoModeDialog = false }, modifier = Modifier.fillMaxWidth()) { Text("取消") }
                }
            },
            confirmButton = {}
        )
    }

    if (showThemeDialog) {
        val themeOptions = listOf(
            MainActivity.THEME_MODE_FOLLOW_SYSTEM to "跟随系统",
            MainActivity.THEME_MODE_LIGHT to "浅色",
            MainActivity.THEME_MODE_DARK to "深色"
        )
        AlertDialog(
            onDismissRequest = { showThemeDialog = false },
            title = { Text("选择主题模式") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    themeOptions.forEach { (mode, label) ->
                        Button(
                            onClick = {
                                editor.putInt(MainActivity.PREF_THEME_MODE, mode).apply()
                                themeMode = mode
                                onThemeModeChange(mode)
                                showThemeDialog = false
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) { Text(if (themeMode == mode) "✓ $label" else label) }
                    }
                    Button(onClick = { showThemeDialog = false }, modifier = Modifier.fillMaxWidth()) { Text("取消") }
                }
            },
            confirmButton = {}
        )
    }
}

@Composable
private fun SettingCheckItem(
    label: String,
    checked: Boolean,
    contentColor: Color,
    liquidBackdrop: LayerBackdrop?,
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
            backdrop = liquidBackdrop
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
        LinkText("QQ群: 722673402", "https://jq.qq.com/?_wv=1027&k=vjcrjY7L", contentColor)
        LinkText("项目开源地址", "https://github.com/prpr12/tianyinwallpaper.git", contentColor)
        LinkText("软件下载地址", "https://www.pgyer.com/eEna", contentColor)
        Text(
            text = "当前版本号：$verName",
            style = TextStyle(contentColor.copy(0.5f), 12.sp)
        )
    }
}

@Composable
private fun LinkText(label: String, url: String, contentColor: Color) {
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

private const val DEFAULT_AUTO_SWITCH_INTERVAL_MINUTES = 60L
private const val DEFAULT_AUTO_SWITCH_TIME_POINTS = "08:00,12:00,18:00,22:00"
private const val AUTO_SWITCH_MODE_NONE = 0
private val AUTO_SWITCH_MODE_ITEMS = arrayOf("手动切换", "按固定时间间隔切换", "按每日时间点切换")
