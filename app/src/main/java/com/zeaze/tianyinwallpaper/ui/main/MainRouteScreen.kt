package com.zeaze.tianyinwallpaper.ui.main

import android.app.Activity
import android.app.WallpaperManager
import android.content.ComponentName
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.Settings
import android.util.Log
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.AlertDialog
import androidx.compose.material.Button
import androidx.compose.material.Checkbox
import androidx.compose.material.OutlinedTextField
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.alibaba.fastjson.JSON
import com.zeaze.tianyinwallpaper.App
import com.zeaze.tianyinwallpaper.R
import com.zeaze.tianyinwallpaper.base.rxbus.RxBus
import com.zeaze.tianyinwallpaper.base.rxbus.RxConstants
import com.zeaze.tianyinwallpaper.model.TianYinWallpaperModel
import com.zeaze.tianyinwallpaper.service.TianYinWallpaperService
import com.zeaze.tianyinwallpaper.utils.FileUtil
import io.reactivex.functions.Consumer
import java.io.IOException
import java.util.Collections
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
fun MainRouteScreen(
    onOpenSettingPage: () -> Unit,
    onBottomBarVisibleChange: (Boolean) -> Unit
) {
    val context = LocalContext.current
    val activity = context as? Activity
    val pref = remember(context) { context.getSharedPreferences(App.TIANYIN, android.content.Context.MODE_PRIVATE) }
    val editor = remember(pref) { pref.edit() }

    val wallpapers = remember { mutableStateListOf<TianYinWallpaperModel>() }
    val selectedPositions = remember { mutableStateListOf<Int>() }

    var selectionMode by remember { mutableStateOf(false) }
    var groupName by remember { mutableStateOf("") }

    var showWallpaperTypeDialog by remember { mutableStateOf(false) }
    var showPermissionDialog by remember { mutableStateOf(false) }
    var showMoreDialog by remember { mutableStateOf(false) }
    var showDeleteSelectedDialog by remember { mutableStateOf(false) }
    var showWallpaperSettingDialog by remember { mutableStateOf(false) }
    var showMinTimeDialog by remember { mutableStateOf(false) }
    var showAutoModeDialog by remember { mutableStateOf(false) }

    var actionDialogIndex by remember { mutableStateOf<Int?>(null) }
    var timeDialogIndex by remember { mutableStateOf<Int?>(null) }
    var loopDialogIndex by remember { mutableStateOf<Int?>(null) }

    fun toast(msg: String) {
        Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
    }

    fun saveCache() {
        editor.putString("wallpaperCache", JSON.toJSONString(wallpapers))
        editor.putString("wallpaperTvCache", groupName)
        editor.apply()
    }

    fun takePersistableUriPermissions(uris: List<Uri>) {
        val hostActivity = activity ?: return
        for (uri in uris) {
            try {
                hostActivity.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            } catch (e: SecurityException) {
                Log.e("MainRouteScreen", "Could not take persistable permission for URI: $uri", e)
            }
        }
    }

    val wallpaperLaunch = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            toast("设置成功")
        } else {
            showPermissionDialog = true
        }
    }

    fun applyWallpapers() {
        if (wallpapers.isEmpty()) {
            toast("至少需要1张壁纸才能开始设置")
            return
        }
        Thread {
            FileUtil.save(context, JSON.toJSONString(wallpapers), FileUtil.wallpaperPath, object : FileUtil.OnSave {
                override fun onSave() {
                    val hostActivity = activity ?: run {
                        Log.w("MainRouteScreen", "onSave skipped: activity is null")
                        return
                    }
                    hostActivity.runOnUiThread {
                        val wallpaperManager = WallpaperManager.getInstance(hostActivity)
                        try {
                            wallpaperManager.clear()
                        } catch (e: IOException) {
                            e.printStackTrace()
                        }
                        val intent = Intent().apply {
                            action = WallpaperManager.ACTION_CHANGE_LIVE_WALLPAPER
                            putExtra(
                                WallpaperManager.EXTRA_LIVE_WALLPAPER_COMPONENT,
                                ComponentName(hostActivity, TianYinWallpaperService::class.java)
                            )
                        }
                        wallpaperLaunch.launch(intent)
                    }
                }
            })
        }.start()
    }

    fun appendModels(results: List<Uri>, dynamic: Boolean) {
        takePersistableUriPermissions(results)
        val list = results.map { uri ->
            TianYinWallpaperModel().apply {
                uuid = UUID.randomUUID().toString()
                if (dynamic) {
                    type = 1
                    videoUri = uri.toString()
                } else {
                    type = 0
                    imgUri = uri.toString()
                }
            }
        }
        wallpapers.addAll(0, list)
        saveCache()
    }

    val imageLaunch = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { results ->
        if (!results.isNullOrEmpty()) appendModels(results, dynamic = false)
    }
    val videoLaunch = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { results ->
        if (!results.isNullOrEmpty()) appendModels(results, dynamic = true)
    }

    LaunchedEffect(Unit) {
        if (wallpapers.isEmpty()) {
            val cache = pref.getString("wallpaperCache", "")
            if (!cache.isNullOrEmpty()) {
                wallpapers.addAll(JSON.parseArray(cache, TianYinWallpaperModel::class.java))
                groupName = pref.getString("wallpaperTvCache", "") ?: ""
            }
        }
    }

    LaunchedEffect(selectionMode) {
        onBottomBarVisibleChange(!selectionMode)
    }

    DisposableEffect(Unit) {
        val disposable = RxBus.getDefault()
            .toObservableWithCode(RxConstants.RX_ADD_WALLPAPER, TianYinWallpaperModel::class.java)
            .subscribe(Consumer { o ->
                wallpapers.add(0, o)
                saveCache()
                toast("已加入，请在“壁纸“里查看")
            })
        onDispose {
            disposable.dispose()
            onBottomBarVisibleChange(true)
        }
    }

    fun enterSelectionMode() {
        selectionMode = true
        selectedPositions.clear()
    }

    fun exitSelectionMode() {
        selectionMode = false
        selectedPositions.clear()
    }

    fun delete(index: Int) {
        if (index in wallpapers.indices) {
            wallpapers.removeAt(index)
            saveCache()
        }
    }

    fun getTimeString(t: Int): String {
        var time = t
        var s = ""
        s = if (time / 60 == 0) s + "00" else if (time / 60 < 10) s + "0" + time / 60 else s + time / 60
        time %= 60
        s = if (time < 10) "$s:0$time" else "$s:$time"
        return s
    }

    fun parseTimeText(text: String): Int? {
        val parts = text.split(":")
        if (parts.size != 2) return null
        val hour = parts[0].toIntOrNull() ?: return null
        val minute = parts[1].toIntOrNull() ?: return null
        if (hour !in 0..23 || minute !in 0..59) return null
        return hour * 60 + minute
    }

    fun parseAndValidateTime(text: String, label: String): Int? {
        return if (text.isBlank()) {
            -1
        } else {
            parseTimeText(text) ?: run {
                toast("$label 格式错误，请使用HH:mm格式")
                null
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFFEDEDED))
            .padding(8.dp)
    ) {
        if (!selectionMode) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Button(onClick = { showWallpaperTypeDialog = true }) { Text("+") }
                    Button(onClick = { applyWallpapers() }) { Text("✓") }
                    Button(onClick = { showMoreDialog = true }) { Text("…") }
                }
                OutlinedTextField(
                    value = groupName,
                    onValueChange = {
                        groupName = it
                        saveCache()
                    },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    label = { Text("输入壁纸组的名称") }
                )
            }
        } else {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(onClick = { exitSelectionMode() }) { Text("取消选择") }
                Button(onClick = { showDeleteSelectedDialog = true }) { Text("删除选中") }
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            itemsIndexed(wallpapers) { index, model ->
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(120.dp)
                        .clickable {
                            if (selectionMode) {
                                if (selectedPositions.contains(index)) selectedPositions.remove(index) else selectedPositions.add(index)
                            } else {
                                actionDialogIndex = index
                            }
                        }
                        .background(Color.Black)
                ) {
                    WallpaperCardImage(
                        modifier = Modifier.fillMaxSize(),
                        model = model
                    )
                    Text(
                        text = if (model.type == 0) "静态" else "动态",
                        color = Color.White,
                        fontSize = 12.sp,
                        modifier = Modifier
                            .align(Alignment.TopStart)
                            .background(Color(0x66000000))
                            .padding(horizontal = 6.dp, vertical = 2.dp)
                    )
                    if (model.startTime != -1 && model.endTime != -1) {
                        Text(
                            text = "${getTimeString(model.startTime)} - ${getTimeString(model.endTime)}",
                            color = Color.White,
                            fontSize = 11.sp,
                            modifier = Modifier
                                .align(Alignment.BottomCenter)
                                .background(Color(0x66000000))
                                .padding(horizontal = 6.dp, vertical = 2.dp)
                        )
                    }
                    if (selectedPositions.contains(index)) {
                        Box(modifier = Modifier.fillMaxSize().background(Color(0x88000000)))
                        Text("✓", color = Color.White, fontSize = 18.sp, modifier = Modifier.align(Alignment.Center))
                    }
                }
            }
        }
    }

    if (showWallpaperTypeDialog) {
        AlertDialog(
            onDismissRequest = { showWallpaperTypeDialog = false },
            title = { Text(context.getString(R.string.main_select_wallpaper_type_tip)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { showWallpaperTypeDialog = false; imageLaunch.launch(arrayOf("image/*")) }, modifier = Modifier.fillMaxWidth()) {
                        Text(context.getString(R.string.main_wallpaper_type_static))
                    }
                    Button(onClick = { showWallpaperTypeDialog = false; videoLaunch.launch(arrayOf("video/*")) }, modifier = Modifier.fillMaxWidth()) {
                        Text(context.getString(R.string.main_wallpaper_type_dynamic))
                    }
                }
            },
            confirmButton = {
                Button(onClick = { showWallpaperTypeDialog = false }) { Text(context.getString(R.string.common_cancel)) }
            }
        )
    }

    if (showPermissionDialog) {
        AlertDialog(
            onDismissRequest = { showPermissionDialog = false },
            title = { Text(context.getString(R.string.main_set_wallpaper_failed_permission_tip)) },
            confirmButton = {
                Button(onClick = {
                    val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                        data = Uri.fromParts("package", context.packageName, null)
                    }
                    context.startActivity(intent)
                    showPermissionDialog = false
                }) { Text(context.getString(R.string.common_confirm)) }
            },
            dismissButton = {
                Button(onClick = { showPermissionDialog = false }) { Text(context.getString(R.string.common_cancel)) }
            }
        )
    }

    if (showMoreDialog) {
        AlertDialog(
            onDismissRequest = { showMoreDialog = false },
            title = { Text("更多") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { showMoreDialog = false; enterSelectionMode() }, modifier = Modifier.fillMaxWidth()) {
                        Text(context.getString(R.string.menu_select_mode))
                    }
                    Button(onClick = { showMoreDialog = false; showWallpaperSettingDialog = true }, modifier = Modifier.fillMaxWidth()) {
                        Text("壁纸设置")
                    }
                    Button(onClick = { showMoreDialog = false; onOpenSettingPage() }, modifier = Modifier.fillMaxWidth()) {
                        Text(context.getString(R.string.menu_setting))
                    }
                }
            },
            confirmButton = {
                Button(onClick = { showMoreDialog = false }) { Text(context.getString(R.string.common_cancel)) }
            }
        )
    }

    if (showDeleteSelectedDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteSelectedDialog = false },
            title = { Text(context.getString(R.string.delete_selected_confirm)) },
            confirmButton = {
                Button(onClick = {
                    val indexes = selectedPositions.toMutableList()
                    Collections.sort(indexes, Collections.reverseOrder())
                    for (index in indexes) {
                        if (index in wallpapers.indices) wallpapers.removeAt(index)
                    }
                    selectedPositions.clear()
                    saveCache()
                    exitSelectionMode()
                    showDeleteSelectedDialog = false
                }) { Text(context.getString(R.string.common_delete)) }
            },
            dismissButton = {
                Button(onClick = { showDeleteSelectedDialog = false }) { Text(context.getString(R.string.common_cancel)) }
            }
        )
    }

    actionDialogIndex?.let { index ->
        AlertDialog(
            onDismissRequest = { actionDialogIndex = null },
            title = { Text("请选择操作") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { actionDialogIndex = null; timeDialogIndex = index }, modifier = Modifier.fillMaxWidth()) { Text("设置时间条件") }
                    Button(onClick = { actionDialogIndex = null; loopDialogIndex = index }, modifier = Modifier.fillMaxWidth()) { Text("设置循环播放") }
                    Button(onClick = { actionDialogIndex = null; delete(index) }, modifier = Modifier.fillMaxWidth()) { Text("删除") }
                }
            },
            confirmButton = {
                Button(onClick = { actionDialogIndex = null }) { Text(context.getString(R.string.common_cancel)) }
            }
        )
    }

    timeDialogIndex?.let { index ->
        val model = wallpapers.getOrNull(index)
        if (model != null) {
            var startTime by remember(index) { mutableStateOf(model.startTime) }
            var endTime by remember(index) { mutableStateOf(model.endTime) }
            var startText by remember(index) { mutableStateOf(if (startTime == -1) "" else getTimeString(startTime)) }
            var endText by remember(index) { mutableStateOf(if (endTime == -1) "" else getTimeString(endTime)) }
            AlertDialog(
                onDismissRequest = { timeDialogIndex = null },
                title = { Text("设置时间条件") },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(
                            value = startText,
                            onValueChange = { startText = it },
                            singleLine = true,
                            label = { Text("开始时间(HH:mm)") }
                        )
                        OutlinedTextField(
                            value = endText,
                            onValueChange = { endText = it },
                            singleLine = true,
                            label = { Text("结束时间(HH:mm)") }
                        )
                    }
                },
                confirmButton = {
                    Button(onClick = {
                        startTime = parseAndValidateTime(startText, "开始时间") ?: return@Button
                        endTime = parseAndValidateTime(endText, "结束时间") ?: run {
                            return@Button
                        }
                        model.startTime = startTime
                        model.endTime = endTime
                        if (model.startTime != -1 && model.endTime == -1) model.endTime = 24 * 60
                        if (model.endTime != -1 && model.startTime == -1) model.startTime = 0
                        saveCache()
                        timeDialogIndex = null
                    }) { Text(context.getString(R.string.common_confirm)) }
                },
                dismissButton = {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = {
                            startTime = -1
                            endTime = -1
                            startText = ""
                            endText = ""
                        }) { Text("重置") }
                        Button(onClick = { timeDialogIndex = null }) { Text(context.getString(R.string.common_cancel)) }
                    }
                }
            )
        }
    }

    loopDialogIndex?.let { index ->
        val model = wallpapers.getOrNull(index)
        if (model != null) {
            var loop by remember(index) { mutableStateOf(model.loop) }
            AlertDialog(
                onDismissRequest = { loopDialogIndex = null },
                title = { Text("设置循环播放") },
                text = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = loop, onCheckedChange = { loop = it })
                        Text("循环播放")
                    }
                },
                confirmButton = {
                    Button(onClick = {
                        model.loop = loop
                        saveCache()
                        loopDialogIndex = null
                    }) { Text(context.getString(R.string.common_confirm)) }
                },
                dismissButton = {
                    Button(onClick = { loopDialogIndex = null }) { Text(context.getString(R.string.common_cancel)) }
                }
            )
        }
    }

    if (showWallpaperSettingDialog) {
        var rand by remember { mutableStateOf(pref.getBoolean("rand", false)) }
        var pageChange by remember { mutableStateOf(pref.getBoolean("pageChange", false)) }
        var needBackgroundPlay by remember { mutableStateOf(pref.getBoolean("needBackgroundPlay", false)) }
        var wallpaperScroll by remember { mutableStateOf(pref.getBoolean("wallpaperScroll", false)) }
        val minTime = pref.getInt("minTime", 1)
        val mode = pref.getInt(TianYinWallpaperService.PREF_AUTO_SWITCH_MODE, AUTO_SWITCH_MODE_NONE)

        AlertDialog(
            onDismissRequest = { showWallpaperSettingDialog = false },
            title = { Text("壁纸设置（Compose）") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(modifier = Modifier.fillMaxWidth().clickable { rand = !rand; editor.putBoolean("rand", rand).apply() }, horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Text("随机切换")
                        Checkbox(checked = rand, onCheckedChange = { checked -> rand = checked; editor.putBoolean("rand", rand).apply() })
                    }
                    Row(modifier = Modifier.fillMaxWidth().clickable { pageChange = !pageChange; editor.putBoolean("pageChange", pageChange).apply() }, horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Text("按页切换并预加载")
                        Checkbox(checked = pageChange, onCheckedChange = { checked -> pageChange = checked; editor.putBoolean("pageChange", pageChange).apply() })
                    }
                    Row(modifier = Modifier.fillMaxWidth().clickable { needBackgroundPlay = !needBackgroundPlay; editor.putBoolean("needBackgroundPlay", needBackgroundPlay).apply() }, horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Text("后台继续播放")
                        Checkbox(checked = needBackgroundPlay, onCheckedChange = { checked -> needBackgroundPlay = checked; editor.putBoolean("needBackgroundPlay", needBackgroundPlay).apply() })
                    }
                    Row(modifier = Modifier.fillMaxWidth().clickable { wallpaperScroll = !wallpaperScroll; editor.putBoolean("wallpaperScroll", wallpaperScroll).apply() }, horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Text("壁纸跟随滑动")
                        Checkbox(checked = wallpaperScroll, onCheckedChange = { checked -> wallpaperScroll = checked; editor.putBoolean("wallpaperScroll", wallpaperScroll).apply() })
                    }
                    Text("壁纸最小切换时间: ${minTime}秒", modifier = Modifier.clickable { showMinTimeDialog = true })
                    Text("自动切换模式: ${AUTO_SWITCH_MODE_ITEMS.getOrElse(mode) { AUTO_SWITCH_MODE_ITEMS[0] }}", modifier = Modifier.clickable { showAutoModeDialog = true })
                }
            },
            confirmButton = {
                Button(onClick = { showWallpaperSettingDialog = false }) { Text(context.getString(R.string.common_confirm)) }
            }
        )
    }

    if (showMinTimeDialog) {
        var text by remember { mutableStateOf((pref.getInt("minTime", 1)).toString()) }
        AlertDialog(
            onDismissRequest = { showMinTimeDialog = false },
            title = { Text("请输入最小切换时间（秒）") },
            text = { OutlinedTextField(value = text, onValueChange = { text = it }, singleLine = true) },
            confirmButton = {
                Button(onClick = {
                    try {
                        val value = text.toInt()
                        editor.putInt("minTime", value).apply()
                        showMinTimeDialog = false
                    } catch (_: Exception) {
                        toast("请输入整数")
                    }
                }) { Text(context.getString(R.string.common_confirm)) }
            },
            dismissButton = {
                Button(onClick = { showMinTimeDialog = false }) { Text(context.getString(R.string.common_cancel)) }
            }
        )
    }

    if (showAutoModeDialog) {
        val checked = pref.getInt(TianYinWallpaperService.PREF_AUTO_SWITCH_MODE, AUTO_SWITCH_MODE_NONE)
        AlertDialog(
            onDismissRequest = { showAutoModeDialog = false },
            title = { Text("选择自动切换模式") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    AUTO_SWITCH_MODE_ITEMS.forEachIndexed { index, mode ->
                        Button(onClick = {
                            editor.putInt(TianYinWallpaperService.PREF_AUTO_SWITCH_MODE, index)
                            editor.putLong(TianYinWallpaperService.PREF_AUTO_SWITCH_ANCHOR_AT, System.currentTimeMillis())
                            editor.putLong(TianYinWallpaperService.PREF_AUTO_SWITCH_LAST_SWITCH_AT, 0L)
                            editor.apply()
                            showAutoModeDialog = false
                        }, modifier = Modifier.fillMaxWidth()) {
                            Text(if (index == checked) "✓ $mode" else mode)
                        }
                    }
                }
            },
            confirmButton = {
                Button(onClick = { showAutoModeDialog = false }) { Text(context.getString(R.string.common_cancel)) }
            }
        )
    }
}

private const val AUTO_SWITCH_MODE_NONE = 0
private val AUTO_SWITCH_MODE_ITEMS = arrayOf("手动切换", "按固定时间间隔切换", "按每日时间点切换")

@Composable
private fun WallpaperCardImage(modifier: Modifier = Modifier, model: TianYinWallpaperModel) {
    val context = LocalContext.current
    val bitmapState = produceState<Bitmap?>(initialValue = null, model.type, model.imgUri, model.videoUri, model.imgPath) {
        value = withContext(Dispatchers.IO) {
            runCatching {
                when {
                    model.type == 0 && !model.imgUri.isNullOrEmpty() -> {
                        context.contentResolver.openInputStream(Uri.parse(model.imgUri))?.use {
                            BitmapFactory.decodeStream(it)
                        }
                    }
                    model.type == 1 && !model.videoUri.isNullOrEmpty() -> {
                        val retriever = MediaMetadataRetriever()
                        try {
                            retriever.setDataSource(context, Uri.parse(model.videoUri))
                            retriever.getFrameAtTime(0)
                        } finally {
                            retriever.release()
                        }
                    }
                    !model.imgPath.isNullOrEmpty() -> BitmapFactory.decodeFile(model.imgPath)
                    else -> null
                }
            }.getOrNull()
        }
    }
    bitmapState.value?.let { bitmap ->
        Image(
            bitmap = bitmap.asImageBitmap(),
            contentDescription = null,
            modifier = modifier,
            contentScale = ContentScale.Crop
        )
    }
}
