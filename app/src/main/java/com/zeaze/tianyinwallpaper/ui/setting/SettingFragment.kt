package com.zeaze.tianyinwallpaper.ui.setting

import android.content.Context
import android.content.DialogInterface
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.text.Html
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.TextUtils
import android.text.method.LinkMovementMethod
import android.text.style.URLSpan
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.Checkbox
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.fragment.app.Fragment
import com.zeaze.tianyinwallpaper.App
import com.zeaze.tianyinwallpaper.R
import com.zeaze.tianyinwallpaper.service.TianYinWallpaperService

class SettingFragment : Fragment() {
    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        return ComposeView(requireContext()).apply {
            setContent {
                MaterialTheme {
                    SettingRouteScreen()
                }
            }
        }
    }
}

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
            setMinTime(context, pref, editor, DialogInterface.OnDismissListener {
                minTime = pref.getInt("minTime", 1)
            })
        }
        val modeText = if (autoSwitchMode >= AUTO_SWITCH_MODE_NONE && autoSwitchMode < AUTO_SWITCH_MODE_ITEMS.size) {
            AUTO_SWITCH_MODE_ITEMS[autoSwitchMode]
        } else {
            AUTO_SWITCH_MODE_ITEMS[AUTO_SWITCH_MODE_NONE]
        }
        SettingTextItem("自动切换模式：$modeText（点击修改）") {
            showAutoSwitchModeDialog(context, pref, editor, Runnable {
                autoSwitchMode = pref.getInt(TianYinWallpaperService.PREF_AUTO_SWITCH_MODE, AUTO_SWITCH_MODE_NONE)
                autoSwitchInterval = pref.getLong(
                    TianYinWallpaperService.PREF_AUTO_SWITCH_INTERVAL_MINUTES,
                    DEFAULT_AUTO_SWITCH_INTERVAL_MINUTES
                )
                autoSwitchPoints = pref.getString(
                    TianYinWallpaperService.PREF_AUTO_SWITCH_TIME_POINTS,
                    DEFAULT_AUTO_SWITCH_TIME_POINTS
                ).takeUnless { TextUtils.isEmpty(it) } ?: DEFAULT_AUTO_SWITCH_TIME_POINTS
            })
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
        AndroidView(
            factory = { ctx ->
                TextView(ctx).apply {
                    movementMethod = LinkMovementMethod.getInstance()
                    setTextColor(ContextCompat.getColor(ctx, R.color.textColor))
                    textSize = 12f
                    setPadding(10, 10, 10, 10)
                    background = ContextCompat.getDrawable(ctx, R.drawable.edit_background)
                }
            },
            update = {
                it.text = getAboutText(context)
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

private fun getAboutText(context: Context): CharSequence {
    var verName = "获取失败"
    try {
        verName = context.packageManager.getPackageInfo(context.packageName, 0).versionName
    } catch (e: PackageManager.NameNotFoundException) {
        e.printStackTrace()
    }
    return getClickableHtml(
        "天音壁纸是一个用来设置壁纸的软件>_< <br>\n" +
            "点击“增加壁纸”，可以增加当前壁纸组的壁纸<br>\n" +
            "点击“应用本组”，会把当前壁纸组设置为手机壁纸，每次进入桌面，都会更新显示壁纸组里的下一张壁纸<br>\n" +
            "点击右上角的齿轮，可以保存当前壁纸组<br>\n" +
            "齿轮里的“壁纸通用设置”，可以设置通用的壁纸切换方式<br>\n" +
            "目前支持顺序切换和随机切换，和最小切换时间<br>\n" +
            "最小切换时间的意思是在切换壁纸后，未达这个时间间隔的话是不会二次切换壁纸的<br>\n" +
            "齿轮里的“清空当前壁纸组”，可以方便的一键清空壁纸组来设置新的壁纸<br>\n" +
            "点击壁纸缩略图，可以选择删除壁纸或者设置壁纸显示的条件，长按可以调整顺序<br>\n" +
            "当满足条件时，会优先显示满足条件的壁纸，借此，可以设置早安壁纸，下班壁纸<br>\n" +
            "目前仅支持按时间设置条件，开始时间为闭区间，结束时间为开区间<br>\n" +
            "欢迎加入天音壁纸qq群,BUG和意见都可以提：<a href=\"https://jq.qq.com/?_wv=1027&k=vjcrjY7L\">722673402</a><br>\n" +
            "------<br>\n" +
            "项目开源地址：<a href=\"https://github.com/prpr12/tianyinwallpaper.git\">https://github.com/prpr12/tianyinwallpaper.git</a><br>\n" +
            "软件下载地址：<a href=\"https://www.pgyer.com/eEna\">https://www.pgyer.com/eEna</a><br>\n" +
            "------<br>\n" +
            "当前版本号：$verName\n"
    )
}

private fun getClickableHtml(html: String): CharSequence {
    val spannedHtml: Spanned = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
        Html.fromHtml(html, Html.FROM_HTML_MODE_COMPACT)
    } else {
        Html.fromHtml(html)
    }
    val clickableBuilder = SpannableStringBuilder(spannedHtml)
    val urls = clickableBuilder.getSpans(0, spannedHtml.length, URLSpan::class.java)
    if (urls.isEmpty()) {
        return html.replace("\\n", "\n").replace("\\r", "\r")
    }
    return clickableBuilder
}

private fun setMinTime(
    context: Context,
    pref: SharedPreferences,
    editor: SharedPreferences.Editor,
    onDismissListener: DialogInterface.OnDismissListener
) {
    val editView = LayoutInflater.from(context).inflate(R.layout.main_edit, null)
    val et: EditText = editView.findViewById(R.id.tv)
    et.setText(pref.getInt("minTime", 1).toString())
    et.hint = "请输入整数"
    AlertDialog.Builder(context)
        .setView(editView)
        .setNeutralButton("取消", null)
        .setPositiveButton("确定") { _, _ ->
            try {
                val i = et.text.toString().toInt()
                editor.putInt("minTime", i)
                editor.apply()
            } catch (e: Exception) {
                Toast.makeText(context, "请输入整数", Toast.LENGTH_SHORT).show()
            }
        }
        .setCancelable(false)
        .setOnDismissListener(onDismissListener)
        .show()
}

private fun showAutoSwitchModeDialog(
    context: Context,
    pref: SharedPreferences,
    editor: SharedPreferences.Editor,
    onDismiss: Runnable
) {
    val checked = pref.getInt(TianYinWallpaperService.PREF_AUTO_SWITCH_MODE, AUTO_SWITCH_MODE_NONE)
    AlertDialog.Builder(context)
        .setTitle("选择自动切换模式")
        .setSingleChoiceItems(AUTO_SWITCH_MODE_ITEMS, checked) { dialog, which ->
            editor.putInt(TianYinWallpaperService.PREF_AUTO_SWITCH_MODE, which)
            editor.putLong(TianYinWallpaperService.PREF_AUTO_SWITCH_ANCHOR_AT, System.currentTimeMillis())
            editor.putLong(TianYinWallpaperService.PREF_AUTO_SWITCH_LAST_SWITCH_AT, 0L)
            editor.apply()
            dialog.dismiss()
        }
        .setOnDismissListener { onDismiss.run() }
        .show()
}

private const val DEFAULT_AUTO_SWITCH_INTERVAL_MINUTES = 60L
private const val DEFAULT_AUTO_SWITCH_TIME_POINTS = "08:00,12:00,18:00,22:00"
private const val AUTO_SWITCH_MODE_NONE = 0
private val AUTO_SWITCH_MODE_ITEMS = arrayOf("手动切换", "按固定时间间隔切换", "按每日时间点切换")
