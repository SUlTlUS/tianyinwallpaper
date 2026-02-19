package com.zeaze.tianyinwallpaper.ui.main

import android.app.Activity.RESULT_OK
import android.app.WallpaperManager
import android.content.ComponentName
import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import android.widget.ImageView
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.material.MaterialTheme
import androidx.compose.material.OutlinedTextField
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.alibaba.fastjson.JSON
import com.bumptech.glide.Glide
import com.github.gzuliyujiang.wheelpicker.TimePicker
import com.github.gzuliyujiang.wheelpicker.annotation.TimeMode
import com.github.gzuliyujiang.wheelpicker.entity.TimeEntity
import com.github.gzuliyujiang.wheelpicker.impl.UnitTimeFormatter
import com.github.gzuliyujiang.wheelpicker.widget.TimeWheelLayout
import com.lxj.xpopup.XPopup
import com.lxj.xpopup.impl.LoadingPopupView
import com.zeaze.tianyinwallpaper.App
import com.zeaze.tianyinwallpaper.MainActivity
import com.zeaze.tianyinwallpaper.R
import com.zeaze.tianyinwallpaper.base.BaseFragment
import com.zeaze.tianyinwallpaper.base.rxbus.RxBus
import com.zeaze.tianyinwallpaper.base.rxbus.RxConstants
import com.zeaze.tianyinwallpaper.model.TianYinWallpaperModel
import com.zeaze.tianyinwallpaper.service.TianYinWallpaperService
import com.zeaze.tianyinwallpaper.utils.FileUtil
import io.reactivex.functions.Consumer
import java.io.IOException
import java.util.Collections
import java.util.UUID

class MainFragment : BaseFragment() {

    private val wallpapers = mutableStateListOf<TianYinWallpaperModel>()
    private val selectedPositions = mutableStateListOf<Int>()

    private var selectionMode by mutableStateOf(false)
    private var groupName by mutableStateOf("")

    private var showWallpaperTypeDialog by mutableStateOf(false)
    private var showPermissionDialog by mutableStateOf(false)
    private var showMoreDialog by mutableStateOf(false)
    private var showDeleteSelectedDialog by mutableStateOf(false)
    private var showWallpaperSettingDialog by mutableStateOf(false)
    private var showMinTimeDialog by mutableStateOf(false)
    private var showAutoModeDialog by mutableStateOf(false)

    private var actionDialogIndex by mutableStateOf<Int?>(null)
    private var timeDialogIndex by mutableStateOf<Int?>(null)
    private var loopDialogIndex by mutableStateOf<Int?>(null)

    private var popupView: LoadingPopupView? = null
    private var model: TianYinWallpaperModel? = null

    private var now = 0
    private var uris: List<Uri>? = null
    private var type = 1

    private var pref: SharedPreferences? = null
    private var editor: SharedPreferences.Editor? = null

    private lateinit var imageLaunch: ActivityResultLauncher<Array<String>>
    private lateinit var videoLaunch: ActivityResultLauncher<Array<String>>
    private lateinit var wallpaperLaunch: ActivityResultLauncher<Intent>

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        pref = requireContext().getSharedPreferences(App.TIANYIN, android.content.Context.MODE_PRIVATE)
        editor = pref?.edit()

        imageLaunch = registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { results ->
            if (results.isNullOrEmpty()) {
                model = null
                return@registerForActivityResult
            }
            takePersistableUriPermissions(results)
            now = 0
            uris = results
            type = 1
            popupView = XPopup.Builder(context)
                .dismissOnBackPressed(false)
                .dismissOnTouchOutside(false)
                .asLoading("转换壁纸中")
                .show() as LoadingPopupView
            exchange(now)
        }

        videoLaunch = registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { results ->
            if (results.isNullOrEmpty()) {
                model = null
                return@registerForActivityResult
            }
            takePersistableUriPermissions(results)
            now = 0
            uris = results
            type = 2
            popupView = XPopup.Builder(context)
                .dismissOnBackPressed(false)
                .dismissOnTouchOutside(false)
                .asLoading("转换壁纸中")
                .show() as LoadingPopupView
            exchange(now)
        }

        wallpaperLaunch = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == RESULT_OK) {
                toast("设置成功")
            } else {
                showPermissionDialog = true
            }
        }
    }

    override fun onCreateView(
        inflater: android.view.LayoutInflater,
        container: android.view.ViewGroup?,
        savedInstanceState: Bundle?
    ): android.view.View {
        rootView = ComposeView(requireContext()).apply {
            setContent {
                MaterialTheme {
                    MainScreen()
                }
            }
        }
        return rootView
    }

    override fun init() {
        if (wallpapers.isEmpty()) {
            val cache = pref?.getString("wallpaperCache", "")
            if (!cache.isNullOrEmpty()) {
                wallpapers.addAll(JSON.parseArray(cache, TianYinWallpaperModel::class.java))
                groupName = pref?.getString("wallpaperTvCache", "") ?: ""
            }
        }

        addDisposable(
            RxBus.getDefault().toObservableWithCode(RxConstants.RX_ADD_WALLPAPER, TianYinWallpaperModel::class.java)
                .subscribe(Consumer { o: TianYinWallpaperModel ->
                    wallpapers.add(0, o)
                    saveCache()
                    toast("已加入，请在“壁纸“里查看")
                })
        )
    }

    override fun getLayout(): Int = R.layout.main_fragment

    @Composable
    private fun MainScreen() {
        LaunchedEffect(selectionMode) {
            (activity as? MainActivity)?.setBottomBarVisible(!selectionMode)
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
                        Button(onClick = { if (model == null) showWallpaperTypeDialog = true }) { Text("+") }
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
                    Button(onClick = { exitSelectionMode() }) {
                        Text("取消选择")
                    }
                    Button(onClick = { showDeleteSelectedDialog = true }) {
                        Text("删除选中")
                    }
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                itemsIndexed(wallpapers) { index, model ->
                    WallpaperCard(
                        model = model,
                        selected = selectedPositions.contains(index),
                        modifier = Modifier.fillMaxWidth(),
                        onClick = {
                            if (selectionMode) {
                                toggleSelected(index)
                            } else {
                                actionDialogIndex = index
                            }
                        }
                    )
                }
            }
        }

        if (showWallpaperTypeDialog) WallpaperTypeDialog()
        if (showPermissionDialog) PermissionDialog()
        if (showMoreDialog) MoreDialog()
        if (showDeleteSelectedDialog) DeleteSelectedDialog()
        if (showWallpaperSettingDialog) WallpaperSettingDialog()
        if (showMinTimeDialog) MinTimeDialog()
        if (showAutoModeDialog) AutoModeDialog()

        actionDialogIndex?.let { ActionDialog(it) }
        timeDialogIndex?.let { TimeConditionDialog(it) }
        loopDialogIndex?.let { LoopDialog(it) }
    }

    @Composable
    private fun WallpaperCard(
        model: TianYinWallpaperModel,
        selected: Boolean,
        modifier: Modifier,
        onClick: () -> Unit
    ) {
        Box(
            modifier = modifier
                .height(120.dp)
                .clickable { onClick() }
                .background(Color.Black)
        ) {
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { ctx -> ImageView(ctx).apply { scaleType = ImageView.ScaleType.CENTER_CROP } },
                update = { iv ->
                    if (model.type == 0 && !model.imgUri.isNullOrEmpty()) {
                        Glide.with(requireContext()).load(Uri.parse(model.imgUri)).into(iv)
                    } else if (model.type == 1 && !model.videoUri.isNullOrEmpty()) {
                        Glide.with(requireContext()).load(Uri.parse(model.videoUri)).into(iv)
                    } else {
                        Glide.with(requireContext()).load(model.imgPath).into(iv)
                    }
                }
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

            if (selected) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color(0x88000000))
                )
                Text("✓", color = Color.White, fontSize = 18.sp, modifier = Modifier.align(Alignment.Center))
            }
        }
    }

    @Composable
    private fun WallpaperTypeDialog() {
        AlertDialog(onDismissRequest = { showWallpaperTypeDialog = false },
            title = { Text(getString(R.string.main_select_wallpaper_type_tip)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { showWallpaperTypeDialog = false; selectWallpaper() }, modifier = Modifier.fillMaxWidth()) {
                        Text(getString(R.string.main_wallpaper_type_static))
                    }
                    Button(onClick = { showWallpaperTypeDialog = false; selectLiveWallpaper() }, modifier = Modifier.fillMaxWidth()) {
                        Text(getString(R.string.main_wallpaper_type_dynamic))
                    }
                }
            },
            confirmButton = {
                Button(onClick = { model = null; showWallpaperTypeDialog = false }) {
                    Text(getString(R.string.common_cancel))
                }
            })
    }

    @Composable
    private fun PermissionDialog() {
        AlertDialog(onDismissRequest = { showPermissionDialog = false },
            title = { Text(getString(R.string.main_set_wallpaper_failed_permission_tip)) },
            confirmButton = {
                Button(onClick = {
                    val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                    val uri = Uri.fromParts("package", requireActivity().packageName, null)
                    intent.data = uri
                    startActivity(intent)
                    showPermissionDialog = false
                }) {
                    Text(getString(R.string.common_confirm))
                }
            },
            dismissButton = {
                Button(onClick = { showPermissionDialog = false }) {
                    Text(getString(R.string.common_cancel))
                }
            })
    }

    @Composable
    private fun MoreDialog() {
        AlertDialog(
            onDismissRequest = { showMoreDialog = false },
            title = { Text("更多") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { showMoreDialog = false; enterSelectionMode() }, modifier = Modifier.fillMaxWidth()) {
                        Text(getString(R.string.menu_select_mode))
                    }
                    Button(onClick = { showMoreDialog = false; showWallpaperSettingDialog = true }, modifier = Modifier.fillMaxWidth()) {
                        Text("壁纸设置")
                    }
                    Button(onClick = {
                        showMoreDialog = false
                        (activity as? MainActivity)?.openSettingPage()
                    }, modifier = Modifier.fillMaxWidth()) {
                        Text(getString(R.string.menu_setting))
                    }
                }
            },
            confirmButton = {
                Button(onClick = { showMoreDialog = false }) { Text(getString(R.string.common_cancel)) }
            }
        )
    }

    @Composable
    private fun DeleteSelectedDialog() {
        AlertDialog(
            onDismissRequest = { showDeleteSelectedDialog = false },
            title = { Text(getString(R.string.delete_selected_confirm)) },
            confirmButton = {
                Button(onClick = {
                    val indexes = selectedPositions.toMutableList()
                    Collections.sort(indexes, Collections.reverseOrder())
                    for (index in indexes) {
                        if (index in wallpapers.indices) {
                            wallpapers.removeAt(index)
                        }
                    }
                    selectedPositions.clear()
                    saveCache()
                    exitSelectionMode()
                    showDeleteSelectedDialog = false
                }) {
                    Text(getString(R.string.common_delete))
                }
            },
            dismissButton = {
                Button(onClick = { showDeleteSelectedDialog = false }) {
                    Text(getString(R.string.common_cancel))
                }
            }
        )
    }

    @Composable
    private fun ActionDialog(index: Int) {
        AlertDialog(
            onDismissRequest = { actionDialogIndex = null },
            title = { Text("请选择操作") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { actionDialogIndex = null; timeDialogIndex = index }, modifier = Modifier.fillMaxWidth()) {
                        Text("设置时间条件")
                    }
                    Button(onClick = { actionDialogIndex = null; loopDialogIndex = index }, modifier = Modifier.fillMaxWidth()) {
                        Text("设置循环播放")
                    }
                    Button(onClick = {
                        actionDialogIndex = null
                        delete(index)
                    }, modifier = Modifier.fillMaxWidth()) {
                        Text("删除")
                    }
                }
            },
            confirmButton = {
                Button(onClick = { actionDialogIndex = null }) { Text(getString(R.string.common_cancel)) }
            }
        )
    }

    @Composable
    private fun TimeConditionDialog(index: Int) {
        val model = wallpapers.getOrNull(index) ?: return
        var startTime by remember(index) { mutableStateOf(model.startTime) }
        var endTime by remember(index) { mutableStateOf(model.endTime) }

        AlertDialog(
            onDismissRequest = { timeDialogIndex = null },
            title = { Text("设置时间条件") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        if (startTime == -1) "开始时间：点击选择" else "开始时间：${getTimeString(startTime)}",
                        modifier = Modifier.clickable { selectTime(startTime) { startTime = it } }
                    )
                    Text(
                        if (endTime == -1) "结束时间：点击选择" else "结束时间：${getTimeString(endTime)}",
                        modifier = Modifier.clickable { selectTime(endTime) { endTime = it } }
                    )
                }
            },
            confirmButton = {
                Button(onClick = {
                    model.startTime = startTime
                    model.endTime = endTime
                    if (model.startTime != -1 && model.endTime == -1) {
                        model.endTime = 24 * 60
                    }
                    if (model.endTime != -1 && model.startTime == -1) {
                        model.startTime = 0
                    }
                    saveCache()
                    timeDialogIndex = null
                }) {
                    Text(getString(R.string.common_confirm))
                }
            },
            dismissButton = {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = {
                        startTime = -1
                        endTime = -1
                    }) { Text("重置") }
                    Button(onClick = { timeDialogIndex = null }) { Text(getString(R.string.common_cancel)) }
                }
            }
        )
    }

    @Composable
    private fun LoopDialog(index: Int) {
        val model = wallpapers.getOrNull(index) ?: return
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
                }) {
                    Text(getString(R.string.common_confirm))
                }
            },
            dismissButton = {
                Button(onClick = { loopDialogIndex = null }) {
                    Text(getString(R.string.common_cancel))
                }
            }
        )
    }

    @Composable
    private fun WallpaperSettingDialog() {
        var rand by remember { mutableStateOf(pref?.getBoolean("rand", false) == true) }
        var pageChange by remember { mutableStateOf(pref?.getBoolean("pageChange", false) == true) }
        var needBackgroundPlay by remember { mutableStateOf(pref?.getBoolean("needBackgroundPlay", false) == true) }
        var wallpaperScroll by remember { mutableStateOf(pref?.getBoolean("wallpaperScroll", false) == true) }
        val minTime = pref?.getInt("minTime", 1) ?: 1
        val mode = pref?.getInt(TianYinWallpaperService.PREF_AUTO_SWITCH_MODE, AUTO_SWITCH_MODE_NONE) ?: AUTO_SWITCH_MODE_NONE

        AlertDialog(
            onDismissRequest = { showWallpaperSettingDialog = false },
            title = { Text("壁纸设置（Compose）") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    SettingCheckRow("随机切换", onToggle = {
                        rand = !rand
                        editor?.putBoolean("rand", rand)?.apply()
                    }, checked = rand)
                    SettingCheckRow("按页切换并预加载", onToggle = {
                        pageChange = !pageChange
                        editor?.putBoolean("pageChange", pageChange)?.apply()
                    }, checked = pageChange)
                    SettingCheckRow("后台继续播放", onToggle = {
                        needBackgroundPlay = !needBackgroundPlay
                        editor?.putBoolean("needBackgroundPlay", needBackgroundPlay)?.apply()
                    }, checked = needBackgroundPlay)
                    SettingCheckRow("壁纸跟随滑动", onToggle = {
                        wallpaperScroll = !wallpaperScroll
                        editor?.putBoolean("wallpaperScroll", wallpaperScroll)?.apply()
                    }, checked = wallpaperScroll)
                    Text("壁纸最小切换时间: ${minTime}秒", modifier = Modifier.clickable { showMinTimeDialog = true })
                    Text("自动切换模式: ${AUTO_SWITCH_MODE_ITEMS.getOrElse(mode) { AUTO_SWITCH_MODE_ITEMS[0] }}", modifier = Modifier.clickable {
                        showAutoModeDialog = true
                    })
                }
            },
            confirmButton = {
                Button(onClick = { showWallpaperSettingDialog = false }) {
                    Text(getString(R.string.common_confirm))
                }
            }
        )
    }

    @Composable
    private fun SettingCheckRow(label: String, onToggle: () -> Unit, checked: Boolean) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { onToggle() },
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(label)
            Checkbox(checked = checked, onCheckedChange = { onToggle() })
        }
    }

    @Composable
    private fun MinTimeDialog() {
        var text by remember { mutableStateOf((pref?.getInt("minTime", 1) ?: 1).toString()) }
        AlertDialog(
            onDismissRequest = { showMinTimeDialog = false },
            title = { Text("请输入最小切换时间（秒）") },
            text = {
                OutlinedTextField(value = text, onValueChange = { text = it }, singleLine = true)
            },
            confirmButton = {
                Button(onClick = {
                    try {
                        val value = text.toInt()
                        editor?.putInt("minTime", value)?.apply()
                        showMinTimeDialog = false
                    } catch (e: Exception) {
                        toast("请输入整数")
                    }
                }) { Text(getString(R.string.common_confirm)) }
            },
            dismissButton = {
                Button(onClick = { showMinTimeDialog = false }) { Text(getString(R.string.common_cancel)) }
            }
        )
    }

    @Composable
    private fun AutoModeDialog() {
        val checked = pref?.getInt(TianYinWallpaperService.PREF_AUTO_SWITCH_MODE, AUTO_SWITCH_MODE_NONE) ?: AUTO_SWITCH_MODE_NONE
        AlertDialog(
            onDismissRequest = { showAutoModeDialog = false },
            title = { Text("选择自动切换模式") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    AUTO_SWITCH_MODE_ITEMS.forEachIndexed { index, mode ->
                        Button(onClick = {
                            editor?.putInt(TianYinWallpaperService.PREF_AUTO_SWITCH_MODE, index)
                            editor?.putLong(TianYinWallpaperService.PREF_AUTO_SWITCH_ANCHOR_AT, System.currentTimeMillis())
                            editor?.putLong(TianYinWallpaperService.PREF_AUTO_SWITCH_LAST_SWITCH_AT, 0L)
                            editor?.apply()
                            showAutoModeDialog = false
                        }, modifier = Modifier.fillMaxWidth()) {
                            Text(if (index == checked) "✓ $mode" else mode)
                        }
                    }
                }
            },
            confirmButton = {
                Button(onClick = { showAutoModeDialog = false }) { Text(getString(R.string.common_cancel)) }
            }
        )
    }

    private fun toggleSelected(position: Int) {
        if (selectedPositions.contains(position)) {
            selectedPositions.remove(position)
        } else {
            selectedPositions.add(position)
        }
    }

    private fun enterSelectionMode() {
        selectionMode = true
        selectedPositions.clear()
    }

    private fun exitSelectionMode() {
        selectionMode = false
        selectedPositions.clear()
    }

    private fun delete(index: Int) {
        if (index in wallpapers.indices) {
            wallpapers.removeAt(index)
            saveCache()
        }
    }

    private fun saveCache() {
        editor?.putString("wallpaperCache", JSON.toJSONString(wallpapers))
        editor?.putString("wallpaperTvCache", groupName)
        editor?.apply()
    }

    private fun applyWallpapers() {
        if (wallpapers.isEmpty()) {
            toast("至少需要1张壁纸才能开始设置")
            return
        }
        Thread {
            FileUtil.save(requireContext(), JSON.toJSONString(wallpapers), FileUtil.wallpaperPath, object : FileUtil.OnSave {
                override fun onSave() {
                    val hostActivity = activity ?: run {
                        Log.w("MainFragment", "onSave skipped: activity is null")
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

    private fun takePersistableUriPermissions(uris: List<Uri>) {
        for (uri in uris) {
            try {
                requireActivity().contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            } catch (e: SecurityException) {
                Log.e("MainFragment", "Could not take persistable permission for URI: $uri", e)
            }
        }
    }

    private fun exchange(index: Int) {
        if (uris == null || uris!!.size <= index) {
            return
        }
        Thread {
            model = TianYinWallpaperModel()
            val currentUri = uris!![index]
            if (type == 1) {
                model!!.type = 0
                model!!.uuid = UUID.randomUUID().toString()
                model!!.imgUri = currentUri.toString()
                addModel()
            } else {
                model!!.type = 1
                model!!.uuid = UUID.randomUUID().toString()
                model!!.videoUri = currentUri.toString()
                addModel()
            }
        }.start()
    }

    private fun addModel() {
        activity?.runOnUiThread {
            wallpapers.add(0, model!!)
            saveCache()
            model = null
            now += 1
            if (now >= (uris?.size ?: 0)) {
                uris = null
                popupView?.dismiss()
            } else {
                exchange(now)
                popupView?.setTitle("转化壁纸中,进度$now/${uris?.size}")
            }
        }
    }

    private fun selectWallpaper() {
        imageLaunch.launch(arrayOf("image/*"))
    }

    private fun selectLiveWallpaper() {
        videoLaunch.launch(arrayOf("video/*"))
    }

    private fun selectTime(time: Int, onPicked: (Int) -> Unit) {
        val picker = TimePicker(requireActivity())
        val wheelLayout: TimeWheelLayout = picker.wheelLayout
        wheelLayout.setTimeMode(TimeMode.HOUR_24_NO_SECOND)
        wheelLayout.setTimeFormatter(UnitTimeFormatter())
        if (time != -1) {
            wheelLayout.setDefaultValue(TimeEntity.target(time / 60, time % 60, 0))
        } else {
            wheelLayout.setDefaultValue(TimeEntity.target(0, 0, 0))
        }
        wheelLayout.setResetWhenLinkage(false)
        picker.setOnTimePickedListener { hour, minute, _ ->
            onPicked(hour * 60 + minute)
        }
        picker.show()
    }

    private fun getTimeString(t: Int): String {
        var time = t
        var s = ""
        s = if (time / 60 == 0) s + "00" else if (time / 60 < 10) s + "0" + time / 60 else s + time / 60
        time %= 60
        s = if (time < 10) s + ":0" + time else s + ":" + time
        return s
    }

    companion object {
        private const val AUTO_SWITCH_MODE_NONE = 0
        private val AUTO_SWITCH_MODE_ITEMS = arrayOf("手动切换", "按固定时间间隔切换", "按每日时间点切换")
        var column = 3
    }
}
