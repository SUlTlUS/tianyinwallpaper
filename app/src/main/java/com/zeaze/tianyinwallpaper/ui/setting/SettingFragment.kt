package com.zeaze.tianyinwallpaper.ui.setting

import android.content.Context
import android.content.pm.PackageManager
import android.text.TextUtils
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.AlertDialog
import androidx.compose.material.Button
import androidx.compose.material.Checkbox
import androidx.compose.material.MaterialTheme
import androidx.compose.material.OutlinedTextField
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import com.zeaze.tianyinwallpaper.App
import com.zeaze.tianyinwallpaper.service.TianYinWallpaperService

@Composable
fun SettingRouteScreen() {
    val context = LocalContext.current
    val pref = remember(context) { context.getSharedPreferences(App.TIANYIN, Context.MODE_PRIVATE) }
    val editor = remember(pref) { pref.edit() }

    var rand by remember { mutableStateOf(pref.getBoolean("rand", false)) }
    var pageChange by remember { mutableStateOf(pref.getBoolean("pageChange", false)) }
    var needBackgroundPlay by remember { mutableStateOf(pref.getBoolean("needBackgroundPlay", false)) }
    var wallpaperScroll by remember { mutableStateOf(pref.getBoolean("wallpaperScroll", false)) }
    var minTime by remember { mutableStateOf(pref.getInt("minTime", 1)) }
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

    Column(
        modifier = Modifier
            .background(MaterialTheme.colors.background)
            .verticalScroll(rememberScrollState())
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        SettingCheckItem("随机切换壁纸", rand) {
            rand = it
            editor.putBoolean("rand", it).apply()
        }
        SettingCheckItem("进入桌面切换壁纸", pageChange) {
            pageChange = it
            editor.putBoolean("pageChange", it).apply()
        }
        SettingCheckItem("后台播放动态壁纸", needBackgroundPlay) {
            needBackgroundPlay = it
            editor.putBoolean("needBackgroundPlay", it).apply()
        }
        SettingCheckItem("壁纸跟随屏幕滚动", wallpaperScroll) {
            wallpaperScroll = it
            editor.putBoolean("wallpaperScroll", it).apply()
        }
        SettingTextItem("壁纸最小切换时间:${minTime}秒（点击修改）") {
            minTimeInput = minTime.toString()
            showMinTimeDialog = true
        }
        val modeText = if (autoSwitchMode >= AUTO_SWITCH_MODE_NONE && autoSwitchMode < AUTO_SWITCH_MODE_ITEMS.size) {
            AUTO_SWITCH_MODE_ITEMS[autoSwitchMode]
        } else {
            AUTO_SWITCH_MODE_ITEMS[AUTO_SWITCH_MODE_NONE]
        }
        SettingTextItem("自动切换模式：$modeText（点击修改）") {
            showAutoModeDialog = true
        }
        Text(
            text = "自动切换间隔：${autoSwitchInterval}分钟",
            style = MaterialTheme.typography.body2,
            color = MaterialTheme.colors.onBackground
        )
        Text(
            text = "自动切换时间点：$autoSwitchPoints",
            style = MaterialTheme.typography.body2,
            color = MaterialTheme.colors.onBackground
        )
        AboutSection(context = context)
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
                }
            },
            confirmButton = {
                Button(onClick = { showAutoModeDialog = false }) { Text("取消") }
            }
        )
    }
}

@Composable
private fun SettingCheckItem(label: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.body1,
            modifier = Modifier
                .weight(1f)
                .clickable { onCheckedChange(!checked) }
        )
        Checkbox(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@Composable
private fun SettingTextItem(label: String, onClick: () -> Unit) {
    Text(
        text = label,
        style = MaterialTheme.typography.body1,
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() }
            .padding(vertical = 6.dp)
    )
}

@Composable
private fun AboutSection(context: Context) {
    val verName = getVersionName(context)
    val aboutText = remember { getAboutText() }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colors.surface)
            .padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Text(text = aboutText)
        LinkText("欢迎加入天音壁纸QQ群,BUG和意见都可以提：722673402", "https://jq.qq.com/?_wv=1027&k=vjcrjY7L")
        LinkText("项目开源地址：https://github.com/prpr12/tianyinwallpaper.git", "https://github.com/prpr12/tianyinwallpaper.git")
        LinkText("软件下载地址：https://www.pgyer.com/eEna", "https://www.pgyer.com/eEna")
        Text(text = "当前版本号：$verName")
    }
}

@Composable
private fun LinkText(label: String, url: String) {
    val uriHandler = androidx.compose.ui.platform.LocalUriHandler.current
    Text(
        text = label,
        style = MaterialTheme.typography.body2,
        color = Color(0xFF1565C0),
        textDecoration = TextDecoration.Underline,
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
