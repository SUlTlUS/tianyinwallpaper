package com.zeaze.tianyinwallpaper.ui.raster

import android.app.Activity
import android.app.WallpaperManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.util.Log
import android.widget.Toast
import com.zeaze.tianyinwallpaper.utils.RasterPrefs
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.ExperimentalAnimationApi
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.layout.offset
import androidx.compose.ui.unit.IntOffset
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed as lazyItemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Text

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size as GeometrySize
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.zIndex
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyGridState
import sh.calvin.reorderable.rememberReorderableLazyListState
import com.zeaze.tianyinwallpaper.backdrop.Backdrop
import com.zeaze.tianyinwallpaper.catalog.components.LiquidButton
import com.zeaze.tianyinwallpaper.catalog.components.LiquidSlider
import com.zeaze.tianyinwallpaper.catalog.utils.rememberMultiRegionLuminanceSampler
import com.zeaze.tianyinwallpaper.catalog.utils.rememberRegionLuminanceState
import androidx.compose.ui.geometry.Rect
import com.alibaba.fastjson.JSON
import com.zeaze.tianyinwallpaper.App
import com.zeaze.tianyinwallpaper.R
import com.zeaze.tianyinwallpaper.backdrop.backdrops.layerBackdrop
import com.zeaze.tianyinwallpaper.backdrop.backdrops.rememberCanvasBackdrop
import com.zeaze.tianyinwallpaper.backdrop.backdrops.rememberLayerBackdrop
import com.zeaze.tianyinwallpaper.backdrop.drawBackdrop
import com.zeaze.tianyinwallpaper.backdrop.effects.blur
import com.zeaze.tianyinwallpaper.backdrop.effects.colorControls
import com.zeaze.tianyinwallpaper.backdrop.effects.lens
import com.zeaze.tianyinwallpaper.backdrop.effects.vibrancy
import com.zeaze.tianyinwallpaper.backdrop.highlight.Highlight
import com.zeaze.tianyinwallpaper.base.rxbus.RxBus
import com.zeaze.tianyinwallpaper.base.rxbus.RxConstants
import com.zeaze.tianyinwallpaper.model.RasterGroupModel
import com.zeaze.tianyinwallpaper.model.TianYinWallpaperModel
import com.zeaze.tianyinwallpaper.service.VideoRasterWallpaperService
import com.zeaze.tianyinwallpaper.service.StaticRasterWallpaperService
import com.zeaze.tianyinwallpaper.ui.commom.ProgressiveBlurContent
import com.zeaze.tianyinwallpaper.ui.main.SelectionBarState
import com.zeaze.tianyinwallpaper.ui.main.SelectionTopBar
import com.zeaze.tianyinwallpaper.utils.FileUtil
import com.zeaze.tianyinwallpaper.utils.ThumbnailUtils
import com.zeaze.tianyinwallpaper.utils.showToast
import com.kyant.shapes.Capsule
import com.kyant.shapes.RoundedRectangle
import java.io.IOException
import java.util.UUID

import kotlin.math.abs
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.withContext
import kotlinx.coroutines.launch
import androidx.compose.animation.core.tween
import androidx.compose.ui.input.pointer.pointerInput

private const val WALLPAPER_TYPE_STATIC = 0
private const val WALLPAPER_TYPE_DYNAMIC = 1
private const val MIN_STATIC_GROUP_IMAGES = 2
private const val HOLD_SELECT_TIMEOUT_MS = 500L

private enum class StaticPickMode {
    CREATE_NEW,
    REPLACE_ALL,
    APPEND
}

// 统一对话框状态管理（对齐 MainRouteScreen 的 sealed class DialogState 模式）
private sealed class RasterDialogState {
    object Type : RasterDialogState()
    object Delete : RasterDialogState()
    object Permission : RasterDialogState()
}

@OptIn(ExperimentalAnimationApi::class)
@Composable
fun RasterRouteScreen(
    useDarkTheme: Boolean,
    onBottomBarVisibleChange: (Boolean) -> Unit = {}
) {
    val context = LocalContext.current
    val haptic = LocalHapticFeedback.current
    val activity = context as? Activity
    val isLightTheme = !useDarkTheme
    val contentColor = if (isLightTheme) Color.Black else Color.White
    val accentColor = if (isLightTheme) Color(0xFF0088FF) else Color(0xFF0091FF)
    val containerColor = if (isLightTheme) Color(0xFFFAFAFA).copy(0.6f) else Color(0xFF121212).copy(0.4f)
    val dimColor = if (isLightTheme) Color(0xFF29293A).copy(0.23f) else Color(0xFF121212).copy(0.56f)

    val pref = remember(context) { context.getSharedPreferences(App.TIANYIN, Context.MODE_PRIVATE) }
    val groups = remember { mutableStateListOf<RasterGroupModel>() }
    val selectedIds = remember { mutableStateListOf<String>() }

    var selectionMode by remember { mutableStateOf(false) }
    var showTypeDialog by remember { mutableStateOf(false) }
    var showDeleteDialog by remember { mutableStateOf(false) }
    var showPermissionDialog by remember { mutableStateOf(false) }
    var detailGroup by remember { mutableStateOf<RasterGroupModel?>(null) }
    var staticPickMode by remember { mutableStateOf(StaticPickMode.CREATE_NEW) }
    var staticPickTargetId by remember { mutableStateOf<String?>(null) }
    var dynamicPickTargetId by remember { mutableStateOf<String?>(null) }
    var staticEditorGroupId by remember { mutableStateOf<String?>(null) }
    
    //  新增：替换单张图片的状态
    var singleReplaceTargetId by remember { mutableStateOf<String?>(null) }
    var singleReplaceIndex by remember { mutableStateOf(-1) }

    // 统一对话框状态（对齐 MainRouteScreen 的 currentDialogState 模式）
    val currentDialogState = when {
        showTypeDialog -> RasterDialogState.Type
        showDeleteDialog -> RasterDialogState.Delete
        showPermissionDialog -> RasterDialogState.Permission
        else -> null
    }

    val enableLiquidGlass = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
    val themeBackgroundColor = MaterialTheme.colors.background
    val liquidBackdrop = if (enableLiquidGlass) rememberLayerBackdrop() else null

    val density = LocalDensity.current
    val statusBarTopPadding = remember(context) {
        val id = context.resources.getIdentifier("status_bar_height", "dimen", "android")
        if (id > 0) context.resources.getDimensionPixelSize(id) else 0
    }
    val statusBarTopPaddingDp = with(density) { statusBarTopPadding.toDp() }

    val wallpaperLaunch = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            context.showToast("光栅壁纸设置成功")
        } else {
            if (pref.getBoolean("hide_permission_dialog", false)) {
                context.showToast("设置失败")
            } else {
                showPermissionDialog = true
            }
        }
    }

    // 协程作用域用于后台任务
    val coroutineScope = rememberCoroutineScope()

    fun persistGroups() {
        coroutineScope.launch {
            withContext(Dispatchers.IO) {
                RasterPrefs.saveGroups(pref, groups)
            }
        }
    }

    fun loadGroups() {
        coroutineScope.launch {
            val parsed = withContext(Dispatchers.IO) {
                RasterPrefs.loadGroups(pref)
            }
            groups.clear()
            groups.addAll(parsed)
        }
    }

    fun enterSelectionMode(initialId: String? = null) {
        if (!selectionMode) {
            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
        }
        selectionMode = true
        selectedIds.clear()
        if (initialId != null) {
            selectedIds.add(initialId)
        }
    }

    fun exitSelectionMode() {
        selectionMode = false
        selectedIds.clear()
    }

    fun dismissCurrentDialog() {
        showTypeDialog = false
        showDeleteDialog = false
        showPermissionDialog = false
    }

    BackHandler(enabled = showDeleteDialog) {
        showDeleteDialog = false
    }

    BackHandler(
        enabled = selectionMode &&
            !showDeleteDialog &&
            currentDialogState == null &&
            detailGroup == null
    ) {
        exitSelectionMode()
    }

    // 发送选择模式状态
    fun publishSelectionState() {
        val isAllSelected = groups.isNotEmpty() && selectedIds.size == groups.size
        RxBus.postWithCode(RxConstants.RX_RASTER_SELECTION_MODE_CHANGED, SelectionBarState(selectionMode, isAllSelected))
    }

    fun removeGroupById(groupId: String) {
        val removeIndex = groups.indexOfFirst { it.id == groupId }
        if (removeIndex < 0) return

        groups.removeAt(removeIndex)
        selectedIds.remove(groupId)

        if (detailGroup?.id == groupId) {
            detailGroup = null
        }

        val activeGroupId = pref.getString(RasterPrefs.PREF_RASTER_ACTIVE_GROUP_ID, null)
        if (activeGroupId == groupId) {
            val fallbackActiveId = groups.firstOrNull()?.id
            pref.edit().putString(RasterPrefs.PREF_RASTER_ACTIVE_GROUP_ID, fallbackActiveId).apply()
        }

        if (groups.isEmpty()) {
            exitSelectionMode()
        }

        persistGroups()
    }

    fun toWallpaperModels(group: RasterGroupModel): List<TianYinWallpaperModel> {
        return if (group.type == RasterGroupModel.TYPE_DYNAMIC) {
            listOf(
                TianYinWallpaperModel(
                    type = WALLPAPER_TYPE_DYNAMIC,
                    uuid = UUID.randomUUID().toString(),
                    videoUri = group.videoUri,
                    loop = true
                )
            )
        } else {
            group.imageUris.map { uri ->
                TianYinWallpaperModel(
                    type = WALLPAPER_TYPE_STATIC,
                    uuid = UUID.randomUUID().toString(),
                    imgUri = uri
                )
            }
        }
    }

    fun applyRasterToSystem(group: RasterGroupModel) {
        val models = toWallpaperModels(group)
        if (models.isEmpty()) {
            context.showToast("当前光栅组合内容为空")
            return
        }
        // 视频光栅壁纸正在开发中
        if (group.type == RasterGroupModel.TYPE_DYNAMIC) {
            context.showToast("视频光栅壁纸正在开发中，敬请期待")
            return
        }
        coroutineScope.launch {
            withContext(Dispatchers.IO) {
                FileUtil.save(context, JSON.toJSONString(models), FileUtil.wallpaperPath) {
                    val hostActivity = context as? Activity
                    if (hostActivity == null) {
                        context.showToast("当前页面无法打开系统壁纸设置")
                        return@save
                    }
                    hostActivity.runOnUiThread {
                        val wallpaperManager = WallpaperManager.getInstance(hostActivity)
                        try {
                            wallpaperManager.clear()
                        } catch (e: IOException) {
                            Log.w("RasterRouteScreen", "Clear wallpaper failed", e)
                        }

                        val serviceClass = when (group.type) {
                            RasterGroupModel.TYPE_DYNAMIC -> VideoRasterWallpaperService::class.java
                            RasterGroupModel.TYPE_STATIC -> StaticRasterWallpaperService::class.java
                            else -> throw IllegalStateException("Unknown group type: ${group.type}")
                        }

                        val intent = Intent().apply {
                            action = WallpaperManager.ACTION_CHANGE_LIVE_WALLPAPER
                            putExtra(
                                WallpaperManager.EXTRA_LIVE_WALLPAPER_COMPONENT,
                                ComponentName(hostActivity, serviceClass)
                            )
                        }
                        wallpaperLaunch.launch(intent)
                        context.showToast("已应用光栅组合")
                    }
                }
            }
        }
    }

    fun applyActiveGroup(targetGroup: RasterGroupModel? = null) {
        val target = targetGroup ?: when {
            selectedIds.size == 1 -> groups.firstOrNull { g -> g.id == selectedIds.first() }
            else -> groups.firstOrNull()
        }
        if (target == null) {
            context.showToast("请先添加光栅组合")
            return
        }

        pref.edit().putString(RasterPrefs.PREF_RASTER_ACTIVE_GROUP_ID, target.id).apply()

        // 视频光栅壁纸正在开发中
        if (target.type == RasterGroupModel.TYPE_DYNAMIC) {
            context.showToast("视频光栅壁纸正在开发中，敬请期待")
            return
        }

        val hostActivity = activity ?: return
        val wallpaperManager = WallpaperManager.getInstance(hostActivity)
        try {
            wallpaperManager.clear()
        } catch (e: java.io.IOException) {
            e.printStackTrace()
        }

        val serviceClass: Class<*> = when (target.type) {
            RasterGroupModel.TYPE_DYNAMIC -> VideoRasterWallpaperService::class.java
            RasterGroupModel.TYPE_STATIC -> StaticRasterWallpaperService::class.java
            else -> throw IllegalStateException("Unknown group type: ${target.type}")
        }

        val intent = Intent().apply {
            action = WallpaperManager.ACTION_CHANGE_LIVE_WALLPAPER
            putExtra(
                WallpaperManager.EXTRA_LIVE_WALLPAPER_COMPONENT,
                ComponentName(hostActivity, serviceClass)
            )
        }
        wallpaperLaunch.launch(intent)
    }

    val pickStaticLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments()
    ) { uris ->
        if (uris.isEmpty()) return@rememberLauncherForActivityResult
        uris.forEach { uri ->
            runCatching {
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            }
        }

        val targetId = staticPickTargetId
        if (targetId != null) {
            val idx = groups.indexOfFirst { it.id == targetId }
            if (idx >= 0) {
                val current = groups[idx]
                val merged = when (staticPickMode) {
                    StaticPickMode.REPLACE_ALL -> uris.map { it.toString() }
                    StaticPickMode.APPEND -> current.imageUris + uris.map { it.toString() }
                    StaticPickMode.CREATE_NEW -> uris.map { it.toString() }
                }
                groups[idx] = current.copy(
                    type = RasterGroupModel.TYPE_STATIC,
                    imageUris = merged,
                    videoUri = null
                )
                if (detailGroup?.id == current.id) {
                    detailGroup = groups[idx].copy()
                }
                persistGroups()
            }
        } else {
            groups.add(
                0,
                RasterGroupModel(
                    id = UUID.randomUUID().toString(),
                    type = RasterGroupModel.TYPE_STATIC,
                    imageUris = uris.map { it.toString() },
                    createdAt = System.currentTimeMillis()
                )
            )
            persistGroups()
        }

        staticPickMode = StaticPickMode.CREATE_NEW
        staticPickTargetId = null
    }

    val pickDynamicLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        runCatching {
            context.contentResolver.takePersistableUriPermission(
                uri,
                android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
        }

        val targetId = dynamicPickTargetId
        if (targetId != null) {
            val idx = groups.indexOfFirst { it.id == targetId }
            if (idx >= 0) {
                val current = groups[idx]
                groups[idx] = current.copy(
                    type = RasterGroupModel.TYPE_DYNAMIC,
                    imageUris = emptyList(),
                    videoUri = uri.toString()
                )
                if (detailGroup?.id == current.id) {
                    detailGroup = groups[idx].copy()
                }
                persistGroups()
            }
        } else {
            groups.add(
                0,
                RasterGroupModel(
                    id = UUID.randomUUID().toString(),
                    type = RasterGroupModel.TYPE_DYNAMIC,
                    videoUri = uri.toString(),
                    createdAt = System.currentTimeMillis()
                )
            )
            persistGroups()
        }

        dynamicPickTargetId = null
    }

    //  新增：单张替换 picker
    val pickSingleReplaceLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        runCatching {
            context.contentResolver.takePersistableUriPermission(
                uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
        }
        val targetId = singleReplaceTargetId
        val replaceIdx = singleReplaceIndex
        if (targetId != null && replaceIdx >= 0) {
            val idx = groups.indexOfFirst { it.id == targetId }
            if (idx >= 0) {
                val current = groups[idx]
                val newUris = current.imageUris.toMutableList()
                if (replaceIdx < newUris.size) {
                    newUris[replaceIdx] = uri.toString()
                    groups[idx] = current.copy(imageUris = newUris)
                    if (detailGroup?.id == current.id) {
                        detailGroup = groups[idx].copy()
                    }
                    persistGroups()
                }
            }
        }
        singleReplaceTargetId = null
        singleReplaceIndex = -1
    }

    DisposableEffect(Unit) {
        loadGroups()

        val addDisposable = RxBus.getDefault()
            .toObservableWithCode(RxConstants.RX_TRIGGER_ADD_RASTER, Unit::class.java)
            .subscribe { showTypeDialog = true }

        val applyDisposable = RxBus.getDefault()
            .toObservableWithCode(RxConstants.RX_TRIGGER_APPLY_RASTER, Unit::class.java)
            .subscribe { applyActiveGroup() }

        val previewDisposable = RxBus.getDefault()
            .toObservableWithCode(RxConstants.RX_TRIGGER_PREVIEW_RASTER, Unit::class.java)
            .subscribe {
                detailGroup = when {
                    selectedIds.size == 1 -> groups.firstOrNull { it.id == selectedIds.first() }?.copy()
                    else -> groups.firstOrNull()?.copy()
                }
                if (detailGroup == null) context.showToast("请先添加光栅组合")
            }

        val selectDisposable = RxBus.getDefault()
            .toObservableWithCode(RxConstants.RX_TRIGGER_ENTER_RASTER_SELECT_MODE, Unit::class.java)
            .subscribe { enterSelectionMode() }

        // 选择模式操作监听
        val selectionCancelDisposable = RxBus.getDefault()
            .toObservableWithCode(RxConstants.RX_RASTER_SELECTION_CANCEL, Unit::class.java)
            .subscribe { exitSelectionMode() }

        val selectionDeleteDisposable = RxBus.getDefault()
            .toObservableWithCode(RxConstants.RX_RASTER_SELECTION_DELETE, Unit::class.java)
            .subscribe {
                if (selectedIds.isEmpty()) {
                    context.showToast(context.getString(R.string.no_selected_tip))
                } else {
                    showDeleteDialog = true
                }
            }

        val selectionToggleAllDisposable = RxBus.getDefault()
            .toObservableWithCode(RxConstants.RX_RASTER_SELECTION_TOGGLE_ALL, Unit::class.java)
            .subscribe {
                val isAllSelected = groups.isNotEmpty() && selectedIds.size == groups.size
                if (isAllSelected) {
                    selectedIds.clear()
                } else {
                    selectedIds.clear()
                    selectedIds.addAll(groups.map { it.id })
                }
            }

        onDispose {
            addDisposable.dispose()
            applyDisposable.dispose()
            previewDisposable.dispose()
            selectDisposable.dispose()
            selectionCancelDisposable.dispose()
            selectionDeleteDisposable.dispose()
            selectionToggleAllDisposable.dispose()
            onBottomBarVisibleChange(true)
            // 退出时清除选择模式状态
            RxBus.postWithCode(RxConstants.RX_RASTER_SELECTION_MODE_CHANGED, SelectionBarState(false, false))
        }
    }

    LaunchedEffect(selectionMode, detailGroup) {
        onBottomBarVisibleChange(!selectionMode && detailGroup == null)
    }

    // 发布选择模式状态
    LaunchedEffect(selectionMode, selectedIds.size, groups.size) {
        publishSelectionState()
    }

    val cardAspectRatio = remember {
        val width = FileUtil.width.takeIf { it > 0 } ?: 9
        val height = FileUtil.height.takeIf { it > 0 } ?: 16
        width.toFloat() / height.toFloat()
    }

    val gridState = rememberLazyGridState()
    var pendingReorderSave by remember { mutableStateOf(false) }
    val reorderableState = rememberReorderableLazyGridState(
        lazyGridState = gridState,
        onMove = { from, to ->
            if (from.index == to.index) return@rememberReorderableLazyGridState
            val movedItem = groups.removeAt(from.index)
            groups.add(to.index, movedItem)
            pendingReorderSave = true
        }
    )

    LaunchedEffect(pendingReorderSave) {
        if (pendingReorderSave) {
            delay(300)
            if (pendingReorderSave) {
                persistGroups()
                pendingReorderSave = false
            }
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        // 捕获层：包含背景 + 滚动列表（对齐 MainRouteScreen，使 Liquid Glass 控件采样真实内容）
        Box(
            modifier = Modifier
                .fillMaxSize()
                .let { m ->
                    if (enableLiquidGlass && liquidBackdrop != null) {
                        m.layerBackdrop(liquidBackdrop)
                    } else m
                }
        ) {
            Box(Modifier.fillMaxSize().background(themeBackgroundColor))

            LazyVerticalGrid(
                state = gridState,
                columns = GridCells.Fixed(3),
                modifier = Modifier
                    .fillMaxSize(),
                contentPadding = PaddingValues(
                    start = 12.dp,
                    end = 12.dp,
                    top = statusBarTopPaddingDp + 76.dp,
                    bottom = if (selectionMode) 90.dp else 110.dp
                ),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                itemsIndexed(groups, key = { _, group -> group.id }) { index, group ->
                    val selected = selectedIds.contains(group.id)
                    ReorderableItem(reorderableState, key = group.id) { isDragging ->
                        RasterGroupCard(
                            modifier = Modifier
                                .longPressDraggableHandle()
                                .pointerInput(group.id, selectionMode) {
                                    awaitEachGesture {
                                        val down = awaitFirstDown(requireUnconsumed = false)
                                        val stayedStillForTimeout = withTimeoutOrNull(HOLD_SELECT_TIMEOUT_MS) {
                                            while (true) {
                                                val event = awaitPointerEvent()
                                                val change = event.changes.firstOrNull { it.id == down.id }
                                                    ?: return@withTimeoutOrNull false
                                                if (!change.pressed) return@withTimeoutOrNull false
                                                if ((change.position - down.position).getDistance() > viewConfiguration.touchSlop) {
                                                    return@withTimeoutOrNull false
                                                }
                                            }
                                        } == null

                                        if (stayedStillForTimeout && !selectionMode) {
                                            enterSelectionMode(group.id)
                                        }
                                    }
                                },
                            group = group,
                            selected = selected,
                            aspectRatio = cardAspectRatio,
                            isDragging = isDragging,
                            showRemoveButton = selectionMode,
                            onRemove = { removeGroupById(group.id) },
                            onClick = {
                                if (selectionMode) {
                                    if (selected) selectedIds.remove(group.id) else selectedIds.add(group.id)
                                } else {
                                    detailGroup = group.copy()
                                }
                            }
                        )
                    }
                }
            }
        }

        ProgressiveBlurContent(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .fillMaxWidth()
                .zIndex(2f),
            backdrop = liquidBackdrop
        )

        if (!selectionMode && groups.isEmpty()) {
            Text(
                text = "点击顶部 + 添加光栅组合",
                color = MaterialTheme.colors.onBackground.copy(alpha = 0.7f),
                modifier = Modifier.align(Alignment.Center)
            )
        }

        // 1. 背景遮罩层（对齐 MainRouteScreen 的 dimColor 动画遮罩）
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
                        dismissCurrentDialog()
                        staticEditorGroupId = null
                    }
            )
        }

        // 2. 自定义 Liquid Glass 对话框（对齐 MainRouteScreen 的 AnimatedContent + drawBackdrop 模式）
        // 2. Liquid Glass 对话框
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
            label = "RasterDialogOverlay",
            modifier = Modifier.fillMaxSize()
        ) { state ->
            if (state != null) {
                val dialogBackdrop = liquidBackdrop ?: rememberCanvasBackdrop { drawRect(containerColor) }
                when (state) {
                    RasterDialogState.Type -> {
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
                                .pointerInput(Unit) { detectTapGestures { } }
                        ) {
                            // ... Type 对话框内容不变 ...
                            Column(
                                Modifier.padding(16.dp, 20.dp, 16.dp, 20.dp).fillMaxWidth(),
                                verticalArrangement = Arrangement.spacedBy(8.dp),
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                BasicText("选择光栅类型", style = TextStyle(contentColor, 18.sp, fontWeight = FontWeight.Bold))
                                Spacer(Modifier.height(4.dp))
                                BasicText(
                                    "图集光栅：每个组合支持多张图片\n动态光栅：每个组合只能选择1个视频",
                                    style = TextStyle(contentColor.copy(alpha = 0.7f), 14.sp)
                                )
                                Spacer(Modifier.height(8.dp))
                                Row(
                                    Modifier
                                        .clip(Capsule())
                                        .background(accentColor)
                                        .clickable {
                                            showTypeDialog = false
                                            pickStaticLauncher.launch(arrayOf("image/*"))
                                        }
                                        .height(48.dp)
                                        .fillMaxWidth(),
                                    horizontalArrangement = Arrangement.Center,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    BasicText("图集", style = TextStyle(Color.White, 16.sp))
                                }
                                Row(
                                    Modifier
                                        .clip(Capsule())
                                        .background(accentColor)
                                        .clickable {
                                            showTypeDialog = false
                                            context.showToast("视频光栅壁纸正在开发中，敬请期待")
                                        }
                                        .height(48.dp)
                                        .fillMaxWidth(),
                                    horizontalArrangement = Arrangement.Center,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    BasicText("视频", style = TextStyle(Color.White, 16.sp))
                                }
                                Row(
                                    Modifier
                                        .clip(Capsule())
                                        .background(containerColor.copy(0.2f))
                                        .clickable { showTypeDialog = false }
                                        .height(48.dp)
                                        .fillMaxWidth(),
                                    horizontalArrangement = Arrangement.Center,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    BasicText(context.getString(R.string.common_cancel), style = TextStyle(contentColor, 16.sp))
                                }
                            }
                        }
                    }
                    RasterDialogState.Delete -> {
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
                                .pointerInput(Unit) { detectTapGestures { } }
                        ) {
                            // ... Delete 对话框内容不变 ...
                            Column(
                                Modifier.padding(16.dp, 20.dp, 16.dp, 20.dp).fillMaxWidth(),
                                verticalArrangement = Arrangement.spacedBy(8.dp),
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                BasicText("确认删除选中的光栅组合？", style = TextStyle(contentColor, 18.sp, fontWeight = FontWeight.Bold))
                                Spacer(Modifier.height(12.dp))
                                Row(
                                    Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                                ) {
                                    Row(
                                        Modifier
                                            .weight(1f)
                                            .clip(Capsule())
                                            .background(Color(0xFFFF4D4F).copy(alpha = 0.75f))
                                            .clickable {
                                                groups.removeAll { selectedIds.contains(it.id) }
                                                selectedIds.clear()
                                                selectionMode = false
                                                persistGroups()
                                                showDeleteDialog = false
                                            }
                                            .height(48.dp),
                                        horizontalArrangement = Arrangement.Center,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        BasicText(context.getString(R.string.common_delete), style = TextStyle(Color.White, 16.sp))
                                    }
                                    Row(
                                        Modifier
                                            .weight(1f)
                                            .clip(Capsule())
                                            .background(containerColor.copy(0.2f))
                                            .clickable { showDeleteDialog = false }
                                            .height(48.dp),
                                        horizontalArrangement = Arrangement.Center,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        BasicText(context.getString(R.string.common_cancel), style = TextStyle(contentColor, 16.sp))
                                    }
                                }
                            }
                        }
                    }
                    RasterDialogState.Permission -> {
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
                                .pointerInput(Unit) { detectTapGestures { } }
                        ) {
                            Column(
                                Modifier.padding(16.dp, 20.dp, 16.dp, 20.dp).fillMaxWidth(),
                                verticalArrangement = Arrangement.spacedBy(8.dp),
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                BasicText(context.getString(R.string.main_set_wallpaper_failed_permission_title), style = TextStyle(color = contentColor, fontSize = 20.sp, fontWeight = FontWeight.Bold))
                                BasicText(context.getString(R.string.main_set_wallpaper_failed_permission_tip), style = TextStyle(color = contentColor.copy(alpha=0.8f), fontSize = 14.sp, textAlign = androidx.compose.ui.text.style.TextAlign.Center), modifier = Modifier.padding(top = 4.dp, bottom = 12.dp))
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
                                                val intent = android.content.Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                                                    data = android.net.Uri.fromParts("package", context.packageName, null)
                                                }
                                                context.startActivity(intent)
                                                showPermissionDialog = false
                                            }
                                            .height(48.dp),
                                        horizontalArrangement = Arrangement.Center,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        BasicText(context.getString(R.string.common_confirm), style = TextStyle(Color.White, 16.sp))
                                    }
                                    Row(
                                        Modifier
                                            .weight(1f)
                                            .clip(Capsule())
                                            .background(containerColor.copy(0.2f))
                                            .clickable {
                                                showPermissionDialog = false
                                            }
                                            .height(48.dp),
                                        horizontalArrangement = Arrangement.Center,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        BasicText(context.getString(R.string.common_cancel), style = TextStyle(contentColor, 16.sp))
                                    }
                                }
                            }
                        }
                    }

                }
            }
        }
    }

    // Detail Screen（保留 Dialog 包装，因为它是全屏页面而非小对话框）
    val currentDetailGroup: RasterGroupModel? = detailGroup
    currentDetailGroup?.let { group ->
        Dialog(
            onDismissRequest = { detailGroup = null },
            properties = DialogProperties(
                usePlatformDefaultWidth = false,
                decorFitsSystemWindows = false
            )
        ) {
            RasterDetailScreen(
                group = group,
                previewAspectRatio = cardAspectRatio,
                statusBarTopPaddingDp = statusBarTopPaddingDp,
                enableLiquidGlass = enableLiquidGlass,
                backdrop = liquidBackdrop,
                // ✅ 新增：传入编辑状态
                staticEditorGroupId = staticEditorGroupId,
                onStaticEditorDismiss = { staticEditorGroupId = null },
                onStaticEditorReplaceAll = { editorGroup ->
                    staticPickMode = StaticPickMode.REPLACE_ALL
                    staticPickTargetId = editorGroup.id
                    pickStaticLauncher.launch(arrayOf("image/*"))
                },
                onStaticEditorAppend = { editorGroup ->
                    staticPickMode = StaticPickMode.APPEND
                    staticPickTargetId = editorGroup.id
                    pickStaticLauncher.launch(arrayOf("image/*"))
                },
                onStaticEditorReplaceSingle = { editorGroup: RasterGroupModel, index: Int ->
                    singleReplaceTargetId = editorGroup.id
                    singleReplaceIndex = index
                    pickSingleReplaceLauncher.launch(arrayOf("image/*"))
                },
                onStaticEditorMove = { editorGroup, fromIndex, toIndex ->
                    if (fromIndex == toIndex) return@RasterDetailScreen
                    val idx = groups.indexOfFirst { it.id == editorGroup.id }
                    if (idx < 0) return@RasterDetailScreen

                    val current = groups[idx]
                    val imageUris = current.imageUris.toMutableList()
                    if (fromIndex !in imageUris.indices || toIndex !in imageUris.indices) return@RasterDetailScreen

                    val moved = imageUris.removeAt(fromIndex)
                    imageUris.add(toIndex, moved)
                    groups[idx] = current.copy(imageUris = imageUris)
                    if (detailGroup?.id == current.id) {
                        detailGroup = groups[idx].copy()
                    }
                },
                onStaticEditorCommitReorder = { persistGroups() },
                onStaticEditorDeleteSingle = { editorGroup, index ->
                    val idx = groups.indexOfFirst { it.id == editorGroup.id }
                    if (idx < 0) return@RasterDetailScreen

                    val current = groups[idx]
                    if (current.imageUris.size <= MIN_STATIC_GROUP_IMAGES) {
                        context.showToast("图集至少保留${MIN_STATIC_GROUP_IMAGES}张图片")
                        return@RasterDetailScreen
                    }

                    val imageUris = current.imageUris.toMutableList()
                    if (index !in imageUris.indices) return@RasterDetailScreen
                    imageUris.removeAt(index)
                    groups[idx] = current.copy(imageUris = imageUris)
                    if (detailGroup?.id == current.id) {
                        detailGroup = groups[idx].copy()
                    }
                    persistGroups()
                },
                groups = groups,
                onDismiss = { detailGroup = null },
                onApply = {
                    applyActiveGroup(group)
                    detailGroup = null
                },
                onImageAction = {
                    if (group.type == RasterGroupModel.TYPE_STATIC) {
                        staticEditorGroupId = group.id
                    } else {
                        staticPickMode = StaticPickMode.CREATE_NEW
                        staticPickTargetId = null
                        pickStaticLauncher.launch(arrayOf("image/*"))
                    }
                },
                onSensorWidthChanged = { editorGroup, newWidth ->
                    // 实时更新预览，不持久化
                    val idx = groups.indexOfFirst { g -> g.id == editorGroup.id }
                    if (idx >= 0) {
                        groups[idx] = groups[idx].copy(sensorWidth = newWidth)
                        if (detailGroup?.id == editorGroup.id) {
                            detailGroup = groups[idx].copy()
                        }
                    }
                },
                onSensorWidthChangeFinished = { editorGroup, _ ->
                    // 拖动结束时持久化
                    persistGroups()
                },
                onEffectTypeChanged = { editorGroup, newType ->
                    val idx = groups.indexOfFirst { g -> g.id == editorGroup.id }
                    if (idx >= 0) {
                        groups[idx] = groups[idx].copy(effectType = newType)
                        if (detailGroup?.id == editorGroup.id) {
                            detailGroup = groups[idx].copy()
                        }
                        persistGroups()
                    }
                },
                onTransitionBandChanged = { editorGroup, newBand ->
                    // 实时更新预览，不持久化
                    val idx = groups.indexOfFirst { g -> g.id == editorGroup.id }
                    if (idx >= 0) {
                        groups[idx] = groups[idx].copy(transitionBand = newBand)
                        if (detailGroup?.id == editorGroup.id) {
                            detailGroup = groups[idx].copy()
                        }
                    }
                },
                onTransitionBandChangeFinished = { editorGroup, _ ->
                    // 拖动结束时持久化
                    persistGroups()
                },
                onEdgeSoftnessChanged = { editorGroup, newSoftness ->
                    // 实时更新预览，不持久化
                    val idx = groups.indexOfFirst { g -> g.id == editorGroup.id }
                    if (idx >= 0) {
                        groups[idx] = groups[idx].copy(edgeSoftness = newSoftness)
                        if (detailGroup?.id == editorGroup.id) {
                            detailGroup = groups[idx].copy()
                        }
                    }
                },
                onEdgeSoftnessChangeFinished = { editorGroup, _ ->
                    // 拖动结束时持久化
                    persistGroups()
                },
                onVideoAction = {
                    context.showToast("视频光栅壁纸正在开发中，敬请期待")
                }
            )
        }
    }


}

@Composable
private fun RasterGroupCard(
    modifier: Modifier = Modifier,
    group: RasterGroupModel,
    selected: Boolean,
    aspectRatio: Float,
    isDragging: Boolean = false,
    dragOffset: Offset = Offset.Zero,
    showRemoveButton: Boolean = false,
    onRemove: () -> Unit = {},
    onClick: () -> Unit
) {
    BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
        val safeRatio = if (aspectRatio > 0f) aspectRatio else 9f / 16f
        val cardHeight = maxWidth / safeRatio
        Box(
            modifier = modifier
                .fillMaxWidth()
                .height(cardHeight)
                .zIndex(if (isDragging) 1f else 0f)
                .graphicsLayer {
                    if (isDragging) {
                        translationX = dragOffset.x
                        translationY = dragOffset.y
                        scaleX = 1.05f
                        scaleY = 1.05f
                        alpha = 0.9f
                    }
                }
                .clip(RoundedCornerShape(16.dp))
                .background(Color.Black)
                .clickable(onClick = onClick)
        ) {
            RasterGroupThumbnail(group = group)
            Text(
                text = if (group.type == RasterGroupModel.TYPE_STATIC) "静态" else "动态",
                color = Color.White,
                fontWeight = FontWeight.Bold,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(8.dp)
                    .background(Color(0x66000000), RoundedCornerShape(10.dp))
                    .padding(horizontal = 8.dp, vertical = 2.dp)
            )
            if (selected) {
                Box(modifier = Modifier.fillMaxSize().background(Color(0x77000000)))
                Text(
                    text = "✓",
                    color = Color.White,
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(8.dp)
                        .background(Color(0xCC1A1A1A), RoundedCornerShape(12.dp))
                        .padding(horizontal = 8.dp, vertical = 2.dp)
                )
            }

            if (showRemoveButton) {
                Text(
                    text = "×",
                    color = Color.White,
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(6.dp)
                        .clip(CircleShape)
                        .background(Color(0xFFE53935))
                        .clickable { onRemove() }
                        .padding(horizontal = 5.dp)
                )
            }
        }
    }
}

@Composable
private fun RasterGroupThumbnail(group: RasterGroupModel) {
    val context = LocalContext.current
    val bitmap by produceState<android.graphics.Bitmap?>(initialValue = null, group.id) {
        value = withContext(Dispatchers.IO) {
            runCatching {
                if (group.type == RasterGroupModel.TYPE_STATIC) {
                    val firstUri = group.imageUris.firstOrNull() ?: return@withContext null
                    // 使用采样减少内存占用
                    val options = android.graphics.BitmapFactory.Options().apply {
                        inPreferredConfig = android.graphics.Bitmap.Config.RGB_565
                        inSampleSize = 4
                    }
                    context.contentResolver.openInputStream(Uri.parse(firstUri))?.use {
                        android.graphics.BitmapFactory.decodeStream(it, null, options)
                    }
                } else {
                    val videoUri = group.videoUri ?: return@withContext null
                    ThumbnailUtils.getVideoFrame(context, Uri.parse(videoUri))
                }
            }.getOrNull()
        }
    }

    if (bitmap != null) {
        Image(
            bitmap = bitmap!!.asImageBitmap(),
            contentDescription = null,
            modifier = Modifier.fillMaxSize(),
            contentScale = ContentScale.Crop
        )
    } else {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.DarkGray),
            contentAlignment = Alignment.Center
        ) {
            Text("无缩略图", color = Color.White)
        }
    }
}

@Composable
private fun RasterDetailScreen(
    group: RasterGroupModel,
    previewAspectRatio: Float,
    statusBarTopPaddingDp: androidx.compose.ui.unit.Dp,
    enableLiquidGlass: Boolean,
    backdrop: Backdrop?,
    staticEditorGroupId: String?,
    onStaticEditorDismiss: () -> Unit,
    onStaticEditorReplaceAll: (RasterGroupModel) -> Unit,
    onStaticEditorAppend: (RasterGroupModel) -> Unit,
    onStaticEditorReplaceSingle: (RasterGroupModel, Int) -> Unit,
    onStaticEditorMove: (RasterGroupModel, Int, Int) -> Unit,
    onStaticEditorCommitReorder: () -> Unit,
    onStaticEditorDeleteSingle: (RasterGroupModel, Int) -> Unit,
    onSensorWidthChanged: (RasterGroupModel, Float) -> Unit,
    onSensorWidthChangeFinished: (RasterGroupModel, Float) -> Unit,
    // 新增参数回调
    onEffectTypeChanged: (RasterGroupModel, Int) -> Unit,
    onTransitionBandChanged: (RasterGroupModel, Float) -> Unit,
    onTransitionBandChangeFinished: (RasterGroupModel, Float) -> Unit,
    onEdgeSoftnessChanged: (RasterGroupModel, Float) -> Unit,
    onEdgeSoftnessChangeFinished: (RasterGroupModel, Float) -> Unit,
    groups: List<RasterGroupModel>,
    onDismiss: () -> Unit,
    onApply: () -> Unit,
    onImageAction: () -> Unit,
    onVideoAction: () -> Unit
) {
    val isLightTheme = MaterialTheme.colors.isLight
    val pageBackground = MaterialTheme.colors.background
    val onPage = MaterialTheme.colors.onBackground
    val pillBackground = if (!isLightTheme) Color(0x22222222) else Color(0x22FFFFFF)
    val contentColor = if (isLightTheme) Color.Black else Color.White
    val accentColor = if (isLightTheme) Color(0xFF0088FF) else Color(0xFF0091FF)
    val containerColor = if (isLightTheme) Color(0xFFFAFAFA).copy(0.6f) else Color(0xFF121212).copy(0.4f)
    val dimColor = if (isLightTheme) Color(0xFF29293A).copy(0.23f) else Color(0xFF121212).copy(0.56f)

    val detailBackdrop = if (enableLiquidGlass) rememberLayerBackdrop() else null

    val context = LocalContext.current
    val screenAspectRatio = remember(context) {
        val w = FileUtil.width.takeIf { it > 0 } ?: context.resources.displayMetrics.widthPixels
        val h = FileUtil.height.takeIf { it > 0 } ?: context.resources.displayMetrics.heightPixels
        w.toFloat() / h.toFloat()
    }

    val showStaticEditor = staticEditorGroupId != null
    val editorGroup = staticEditorGroupId?.let { id -> groups.firstOrNull { it.id == id } }

    // 定义需要采样的区域
    val luminanceRegions = remember {
        mapOf(
            "cancel" to Rect(0f, 0f, 0.15f, 0.08f),      // 左上角
            "apply" to Rect(0.85f, 0f, 1f, 0.08f),       // 右上角
            "imageAction" to Rect(0.2f, 0.92f, 0.4f, 1f), // 底部左侧
            "videoAction" to Rect(0.6f, 0.92f, 0.8f, 1f)  // 底部右侧
        )
    }

    // 使用单个采样器，一次性计算所有区域 luminance
    val luminanceSampler = if (enableLiquidGlass && detailBackdrop != null) {
        rememberMultiRegionLuminanceSampler(
            enabled = true,
            sampleLayer = detailBackdrop.graphicsLayer,
            regions = luminanceRegions,
            sampleIntervalMs = 200L
        )
    } else null

    // 每个按钮直接读取预计算结果（无额外协程）
    val cancelLuminanceState = if (luminanceSampler != null) {
        rememberRegionLuminanceState(luminanceSampler, "cancel")
    } else null

    val applyLuminanceState = if (luminanceSampler != null) {
        rememberRegionLuminanceState(luminanceSampler, "apply")
    } else null

    val imageActionLuminanceState = if (luminanceSampler != null) {
        rememberRegionLuminanceState(luminanceSampler, "imageAction")
    } else null

    val videoActionLuminanceState = if (luminanceSampler != null) {
        rememberRegionLuminanceState(luminanceSampler, "videoAction")
    } else null

    Box(modifier = Modifier.fillMaxSize()) {
        // 捕获层
        Box(
            modifier = Modifier
                .fillMaxSize()
                .let { m ->
                    if (enableLiquidGlass && detailBackdrop != null) {
                        m.layerBackdrop(detailBackdrop)
                    } else m
                }
        ) {
            Box(Modifier.fillMaxSize().background(pageBackground))

            // 全屏预览区域
            Box(
                modifier = Modifier.fillMaxSize()
            ) {
                if (group.type == RasterGroupModel.TYPE_STATIC) {
                    RasterPreviewView(
                        group = group,
                        modifier = Modifier.fillMaxSize()
                    )
                } else {
                    GyroDynamicRasterPreview(
                        group = group,
                        sensorWidth = group.sensorWidth,
                        modifier = Modifier.fillMaxSize()
                    )
                }
            }
        }

        // 顶部按钮
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, end = 16.dp, top = statusBarTopPaddingDp + 12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (enableLiquidGlass && detailBackdrop != null) {
                // 取消按钮 - 使用独立的 luminance 采样
                LiquidButton(
                    onClick = onDismiss,
                    backdrop = detailBackdrop,
                    surfaceColor = pillBackground,
                    luminanceState = cancelLuminanceState,
                    modifier = Modifier.height(44.dp)
                ) {
                    BasicText(
                        "取消",
                        modifier = Modifier.padding(horizontal = 14.dp),
                        style = TextStyle(
                            color = cancelLuminanceState?.contentColor ?: onPage,
                            fontSize = 15.sp
                        )
                    )
                }
                // 应用按钮 - 蓝色按钮也联动 luminance
                LiquidButton(
                    onClick = onApply,
                    backdrop = detailBackdrop,
                    surfaceColor = Color(0xFF2A83FF).copy(alpha = 0.75f),
                    tint = Color(0xFF2A83FF),
                    luminanceState = applyLuminanceState,
                    modifier = Modifier.height(44.dp)
                ) {
                    BasicText(
                        "应用",
                        modifier = Modifier.padding(horizontal = 14.dp),
                        style = TextStyle(Color.White, 15.sp)
                    )
                }
            } else {
                Text(
                    text = "取消", color = onPage,
                    modifier = Modifier
                        .clip(RoundedCornerShape(18.dp))
                        .background(pillBackground)
                        .combinedClickable(onClick = onDismiss)
                        .padding(horizontal = 18.dp, vertical = 8.dp)
                )
                Text(
                    text = "应用", color = Color.White,
                    modifier = Modifier
                        .clip(RoundedCornerShape(18.dp))
                        .background(Color(0x662A83FF))
                        .combinedClickable(onClick = onApply)
                        .padding(horizontal = 18.dp, vertical = 8.dp)
                )
            }
        }

        // 底部切换栏
        Row(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 24.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            val isStatic = group.type == RasterGroupModel.TYPE_STATIC
            if (enableLiquidGlass && detailBackdrop != null) {
                LiquidButton(
                    onClick = onImageAction,
                    backdrop = detailBackdrop,
                    surfaceColor = if (isStatic) Color(0xFF2A83FF).copy(alpha = 0.75f)
                    else pillBackground,
                    tint = if (isStatic) Color(0xFF2A83FF) else Color.Unspecified,
                    luminanceState = imageActionLuminanceState,
                    modifier = Modifier.height(44.dp)
                ) {
                    BasicText(
                        "图集",
                        modifier = Modifier.padding(horizontal = 14.dp),
                        style = TextStyle(
                            if (isStatic) Color.White else imageActionLuminanceState?.contentColor ?: onPage,
                            15.sp
                        )
                    )
                }
                LiquidButton(
                    onClick = onVideoAction,
                    backdrop = detailBackdrop,
                    surfaceColor = if (!isStatic) Color(0xFF2A83FF).copy(alpha = 0.75f)
                    else pillBackground,
                    tint = if (!isStatic) Color(0xFF2A83FF) else Color.Unspecified,
                    luminanceState = videoActionLuminanceState,
                    modifier = Modifier.height(44.dp)
                ) {
                    BasicText(
                        "视频",
                        modifier = Modifier.padding(horizontal = 14.dp),
                        style = TextStyle(
                            if (!isStatic) Color.White else videoActionLuminanceState?.contentColor ?: onPage,
                            15.sp
                        )
                    )
                }
            } else {
                Text(
                    text = "图集光栅",
                    color = onPage,
                    modifier = Modifier
                        .clip(RoundedCornerShape(20.dp))
                        .background(if (isStatic) Color(0x332A83FF) else Color.Transparent)
                        .combinedClickable(onClick = onImageAction)
                        .padding(horizontal = 14.dp, vertical = 8.dp)
                )
                Text(
                    text = "视频光栅",
                    color = onPage,
                    modifier = Modifier
                        .clip(RoundedCornerShape(20.dp))
                        .background(if (!isStatic) Color(0x332A83FF) else Color.Transparent)
                        .combinedClickable(onClick = onVideoAction)
                        .padding(horizontal = 14.dp, vertical = 8.dp)
                )
            }
        }

        // ✅ StaticEdit 覆盖层：在二级页面内部，与捕获层是兄弟关系
        // 遮罩
        AnimatedVisibility(
            visible = showStaticEditor,
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
                    ) { onStaticEditorDismiss() }
            )
        }

        // 编辑面板
        AnimatedContent(
            targetState = editorGroup,
            contentKey = { it?.id },  // 只根据 ID 判断，避免滑块变化触发动画
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
            contentAlignment = Alignment.BottomCenter,
            label = "StaticEditOverlay",
            modifier = Modifier.fillMaxSize()
        ) { currentEditorGroup ->
            if (currentEditorGroup != null) {
                val editBackdrop = detailBackdrop ?: rememberCanvasBackdrop { drawRect(containerColor) }
                val sheetBackdrop = rememberLayerBackdrop()  // 为 LiquidSlider 导出
                val thumbnailListState = rememberLazyListState()

                Column(
                    Modifier
                        .padding(horizontal = 24.dp, vertical = 32.dp)  // 左右24dp，上下32dp
                        .fillMaxWidth()
                        .wrapContentHeight()
                        .drawBackdrop(
                            backdrop = editBackdrop,
                            shape = { RoundedRectangle(48f.dp) },
                            effects = {
                                colorControls(
                                    brightness = if (isLightTheme) 0.2f else 0f,
                                    saturation = 1.5f
                                )
                                blur(if (isLightTheme) 16f.dp.toPx() else 8f.dp.toPx())
                                lens(16f.dp.toPx(), 32f.dp.toPx(), depthEffect = true)
                            },
                            highlight = { Highlight.Plain },
                            exportedBackdrop = sheetBackdrop,  // 导出给 LiquidSlider 使用
                            onDrawSurface = { drawRect(containerColor) }
                        )
                        .pointerInput(Unit) { detectTapGestures { } }
                        .padding(16.dp)
                ) {
                    Spacer(Modifier.height(4.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        BasicText("图集光栅", style = TextStyle(contentColor, 18.sp, fontWeight = FontWeight.Bold))
                    }
                    Spacer(Modifier.height(4.dp))
                    BasicText(
                        "支持添加多张图片，长按图片可拖拽排序",
                        style = TextStyle(contentColor.copy(alpha = 0.7f), 14.sp)
                    )
                    Spacer(Modifier.height(12.dp))
                    val reorderableState = rememberReorderableLazyListState(
                        lazyListState = thumbnailListState,
                        onMove = { from, to ->
                            onStaticEditorMove(currentEditorGroup, from.index, to.index)
                            onStaticEditorCommitReorder()
                        }
                    )
                    
                    LazyRow(
                        state = thumbnailListState,
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        lazyItemsIndexed(
                            items = currentEditorGroup.imageUris,
                            key = { index, uri ->
                                val occurrence = currentEditorGroup.imageUris
                                    .take(index + 1)
                                    .count { it == uri }
                                "${uri}#$occurrence"
                            }
                        ) { index, uri ->
                            val occurrence = currentEditorGroup.imageUris.take(index + 1).count { it == uri }
                            val itemKey = "${uri}#$occurrence"
                            ReorderableItem(reorderableState, key = itemKey) { isDragging ->
                                Box(
                                    modifier = Modifier
                                        .height(150.dp)
                                        .aspectRatio(screenAspectRatio)
                                        .zIndex(if (isDragging) 1f else 0f)
                                        .graphicsLayer {
                                            scaleX = if (isDragging) 1.05f else 1f
                                            scaleY = if (isDragging) 1.05f else 1f
                                            shadowElevation = if (isDragging) 8.dp.toPx() else 0f
                                            shape = RoundedCornerShape(12.dp)
                                            clip = true
                                        }
                                        .longPressDraggableHandle()
                                        .clip(RoundedCornerShape(12.dp))
                                        .border(
                                            1.dp,
                                            if (index == 0) Color(0xFF2A83FF) else Color.Transparent,
                                            RoundedCornerShape(12.dp)
                                        )
                                        .clickable { onStaticEditorReplaceSingle(currentEditorGroup, index) }
                                ) {
                                val bmp by produceState<android.graphics.Bitmap?>(initialValue = null, uri) {
                                    value = withContext(Dispatchers.IO) {
                                        runCatching {
                                            val options = android.graphics.BitmapFactory.Options().apply {
                                                inPreferredConfig = android.graphics.Bitmap.Config.RGB_565
                                                inSampleSize = 2
                                            }
                                            context.contentResolver.openInputStream(Uri.parse(uri))?.use {
                                                android.graphics.BitmapFactory.decodeStream(it, null, options)
                                            }
                                        }.getOrNull()
                                    }
                                }
                                if (bmp != null) {
                                    Image(
                                        bitmap = bmp!!.asImageBitmap(),
                                        contentDescription = null,
                                        modifier = Modifier.fillMaxSize(),
                                        contentScale = ContentScale.Crop
                                    )
                                } else {
                                    Box(Modifier.fillMaxSize().background(Color.Gray))
                                }
                                BasicText(
                                    "${index + 1}",
                                    style = TextStyle(
                                        Color.White,
                                        12.sp,
                                        fontWeight = FontWeight.Bold
                                    ),
                                    modifier = Modifier
                                        .align(Alignment.TopEnd)
                                        .padding(4.dp)
                                        .background(Color(0x99000000), RoundedCornerShape(8.dp))
                                        .padding(horizontal = 6.dp, vertical = 2.dp)
                                )
                                if (index == 0) {
                                    BasicText(
                                        "封面",
                                        style = TextStyle(Color.White, 12.sp),
                                        modifier = Modifier
                                            .align(Alignment.BottomCenter)
                                            .background(Color(0x66000000))
                                            .padding(horizontal = 6.dp, vertical = 2.dp)
                                    )
                                }

                                Text(
                                    text = "×",
                                    color = Color.White,
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier
                                        .align(Alignment.TopStart)
                                        .padding(4.dp)
                                        .clip(RoundedCornerShape(8.dp))
                                        .background(Color(0xFFE53935))
                                        .clickable { onStaticEditorDeleteSingle(currentEditorGroup, index) }
                                        .padding(horizontal = 7.dp, vertical = 0.dp)
                                )
                            }
                        }
                    }

                    item {
                            Box(
                                modifier = Modifier
                                    .height(150.dp)
                                    .aspectRatio(screenAspectRatio)
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(containerColor.copy(0.2f))
                                    .clickable { onStaticEditorAppend(currentEditorGroup) },
                                contentAlignment = Alignment.Center
                            ) {
                                BasicText("+", style = TextStyle(contentColor, 24.sp))
                            }
                        }
                    }
                    
                    // ── 效果类型选择 ──
                    Spacer(Modifier.height(16.dp))
                    
                    var selectedEffectType by remember(currentEditorGroup.id) {
                        mutableStateOf(currentEditorGroup.effectType)
                    }
                    
                    BasicText(
                        "扫描线效果",
                        style = TextStyle(contentColor, 14.sp, fontWeight = FontWeight.Bold)
                    )
                    
                    Spacer(Modifier.height(8.dp))
                    
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        listOf(
                            RasterGroupModel.EFFECT_STANDARD to "标准",
                            RasterGroupModel.EFFECT_LENTICULAR to "光栅透镜"
                        ).forEach { (type, name) ->
                            val isSelected = selectedEffectType == type
                            Row(
                                modifier = Modifier
                                    .weight(1f)
                                    .height(36.dp)
                                    .clip(RoundedCornerShape(18.dp))
                                    .background(
                                        if (isSelected) accentColor else contentColor.copy(0.1f)
                                    )
                                    .clickable {
                                        selectedEffectType = type
                                        onEffectTypeChanged(currentEditorGroup, type)
                                    },
                                horizontalArrangement = Arrangement.Center,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                BasicText(
                                    name,
                                    style = TextStyle(
                                        if (isSelected) Color.White else contentColor,
                                        13.sp
                                    )
                                )
                            }
                        }
                    }
                    
                    // ── 传感器灵敏度滑块 ──
                    Spacer(Modifier.height(16.dp))

                    var sensorWidth by remember(currentEditorGroup.id) {
                        mutableStateOf(currentEditorGroup.sensorWidth)
                    }

                    // 灵敏度范围 1~9 对应角度 20°~40°
                    val angleThresholdRad = 0.3285 + 0.041 * sensorWidth
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        BasicText(
                            "灵敏度",
                            style = TextStyle(contentColor, 14.sp, fontWeight = FontWeight.Bold)
                        )
                        BasicText(
                            "倾斜 ${String.format("%.0f", Math.toDegrees(angleThresholdRad))}° 到达边缘",
                            style = TextStyle(contentColor.copy(0.5f), 12.sp)
                        )
                    }
                    Spacer(Modifier.height(4.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        BasicText(
                            "高",
                            style = TextStyle(contentColor.copy(0.6f), 12.sp)
                        )

                        LiquidSlider(
                            value = { sensorWidth },
                            onValueChange = {
                                sensorWidth = it
                                onSensorWidthChanged(currentEditorGroup, it)
                            },
                            onValueChangeFinished = {
                                onSensorWidthChangeFinished(currentEditorGroup, sensorWidth)
                            },
                            valueRange = 1f..9f,
                            visibilityThreshold = 0.1f,
                            backdrop = sheetBackdrop,
                            isLightTheme = isLightTheme,
                            modifier = Modifier.weight(1f)
                        )

                        BasicText(
                            "低",
                            style = TextStyle(contentColor.copy(0.6f), 12.sp)
                        )
                    }


                    
                    // ── 过渡带宽调节 ──
                    Spacer(Modifier.height(16.dp))
                    
                    var transitionBand by remember(currentEditorGroup.id) {
                        mutableStateOf(currentEditorGroup.transitionBand)
                    }
                    
                    BasicText(
                        "过渡带宽",
                        style = TextStyle(contentColor, 14.sp, fontWeight = FontWeight.Bold)
                    )
                    
                    Spacer(Modifier.height(4.dp))
                    
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        BasicText(
                            "窄",
                            style = TextStyle(contentColor.copy(0.6f), 12.sp)
                        )
                        
                        LiquidSlider(
                            value = { transitionBand },
                            onValueChange = {
                                transitionBand = it
                                onTransitionBandChanged(currentEditorGroup, it)
                            },
                            onValueChangeFinished = {
                                onTransitionBandChangeFinished(currentEditorGroup, transitionBand)
                            },
                            valueRange = 0.1f..1f,
                            visibilityThreshold = 0.001f,
                            backdrop = sheetBackdrop,
                            isLightTheme = isLightTheme,
                            modifier = Modifier.weight(1f)
                        )
                        
                        BasicText(
                            "宽",
                            style = TextStyle(contentColor.copy(0.6f), 12.sp)
                        )
                    }
                    
                    // ── 边缘柔化调节 ──
                    Spacer(Modifier.height(12.dp))
                    
                    var edgeSoftness by remember(currentEditorGroup.id) {
                        mutableStateOf(currentEditorGroup.edgeSoftness)
                    }
                    
                    BasicText(
                        "边缘柔化",
                        style = TextStyle(contentColor, 14.sp, fontWeight = FontWeight.Bold)
                    )
                    
                    Spacer(Modifier.height(4.dp))
                    
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        BasicText(
                            "锐",
                            style = TextStyle(contentColor.copy(0.6f), 12.sp)
                        )
                        
                        LiquidSlider(
                            value = { edgeSoftness },
                            onValueChange = {
                                edgeSoftness = it
                                onEdgeSoftnessChanged(currentEditorGroup, it)
                            },
                            onValueChangeFinished = {
                                onEdgeSoftnessChangeFinished(currentEditorGroup, edgeSoftness)
                            },
                            valueRange = 0.01f..0.5f,
                            visibilityThreshold = 0.001f,
                            backdrop = sheetBackdrop,
                            isLightTheme = isLightTheme,
                            modifier = Modifier.weight(1f)
                        )
                        
                        BasicText(
                            "柔",
                            style = TextStyle(contentColor.copy(0.6f), 12.sp)
                        )
                    }
                    
                    Spacer(Modifier.height(12.dp))
                    Row(
                        Modifier
                            .clip(Capsule())
                            .background(accentColor)
                            .clickable { onStaticEditorReplaceAll(currentEditorGroup) }
                            .height(48.dp)
                            .fillMaxWidth(),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        BasicText("替换全部图片", style = TextStyle(Color.White, 16.sp))
                    }
                    Spacer(Modifier.height(8.dp))
                    Row(
                        Modifier
                            .clip(Capsule())
                            .background(containerColor.copy(0.2f))
                            .clickable { onStaticEditorDismiss() }
                            .height(48.dp)
                            .fillMaxWidth(),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        BasicText(context.getString(R.string.common_cancel), style = TextStyle(contentColor, 16.sp))
                    }
                }
            }
        }
    }

}

@Composable
private fun GyroDynamicRasterPreview(
    group: RasterGroupModel,
    sensorWidth: Float = 4.5f,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val videoUri = group.videoUri
    val (tilt, direction) = rememberTiltState(sensorWidth)
    
    // 缓存的关键帧（预先提取 10 帧）
    val cachedFrames = remember { mutableStateListOf<android.graphics.Bitmap?>() }
    var framesLoaded by remember { mutableStateOf(false) }
    
    // 预加载关键帧到缓存
    LaunchedEffect(videoUri) {
        if (videoUri.isNullOrEmpty()) return@LaunchedEffect
        
        withContext(Dispatchers.IO) {
            try {
                val retriever = MediaMetadataRetriever()
                retriever.setDataSource(context, Uri.parse(videoUri))
                
                val durationStr = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                val duration = durationStr?.toLongOrNull() ?: 0L
                
                if (duration > 0) {
                    // 预提取 10 个关键帧
                    val frameCount = 10
                    val interval = duration / frameCount
                    
                    repeat(frameCount) { index ->
                        val timeUs = (index * interval * 1000).coerceAtLeast(0L)
                        val frame = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O_MR1) {
                            retriever.getScaledFrameAtTime(
                                timeUs,
                                MediaMetadataRetriever.OPTION_CLOSEST_SYNC,
                                360,
                                640
                            )
                        } else {
                            retriever.getFrameAtTime(timeUs, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                        }
                        cachedFrames.add(frame)
                    }
                }
                retriever.release()
                framesLoaded = true
            } catch (e: Exception) {
                Log.w("RasterRouteScreen", "Failed to preload video frames", e)
            }
        }
    }
    
    // 根据 tilt 选择对应的帧
    val frameIndex = (tilt * (cachedFrames.size - 1)).roundToInt().coerceIn(0, cachedFrames.lastIndex)
    val currentFrame = cachedFrames.getOrNull(frameIndex)

    Box(modifier = modifier) {
        if (currentFrame != null) {
            Image(
                bitmap = currentFrame.asImageBitmap(),
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop
            )
        } else if (!framesLoaded) {
            Box(
                modifier = Modifier.fillMaxSize().background(Color.DarkGray),
                contentAlignment = Alignment.Center
            ) { Text("加载中...", color = Color.White) }
        } else {
            Box(
                modifier = Modifier.fillMaxSize().background(Color.DarkGray),
                contentAlignment = Alignment.Center
            ) { Text("视频帧加载失败", color = Color.White) }
        }
    }
}

// ✅ 返回 Pair(倾斜幅度，倾斜方向)，最大 30 度
@Composable
private fun rememberTiltState(sensorWidth: Float = 4.5f, maxAngle: Float = 30f): Pair<Float, Int> {
    val context = LocalContext.current
    var tilt by remember { mutableStateOf(0f) }
    var direction by remember { mutableStateOf(0) }
    
    // 将角度转换为弧度用于计算
    val maxAngleRadians = Math.toRadians(maxAngle.toDouble()).toFloat()

    DisposableEffect(context, sensorWidth, maxAngleRadians) {
        val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
        val gyroSensor = sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE)

        if (gyroSensor != null) {
            var lastNs = 0L
            var accumulated = 0f

            val listener = object : SensorEventListener {
                override fun onSensorChanged(event: SensorEvent?) {
                    val e = event ?: return
                    if (lastNs == 0L) {
                        lastNs = e.timestamp
                        return
                    }

                    val dt = (e.timestamp - lastNs) / 1_000_000_000f
                    lastNs = e.timestamp

                    // 累积角速度得到角度变化
                    accumulated += e.values[1] * dt
                    accumulated *= 0.998f // 阻尼衰减

                    // 限制最大角度在 30 度内
                    val clampedAccumulated = accumulated.coerceIn(-maxAngleRadians, maxAngleRadians)
                    
                    // 计算倾斜幅度 (0-1)，基于 30 度范围
                    tilt = (abs(clampedAccumulated) / maxAngleRadians).coerceIn(0f, 1f)
                    
                    direction = when {
                        accumulated < -0.05f -> 1  // 右倾
                        accumulated > 0.05f -> -1  // 左倾
                        else -> direction
                    }
                }
                override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
            }
            sensorManager.registerListener(listener, gyroSensor, SensorManager.SENSOR_DELAY_GAME)
            onDispose {
                sensorManager.unregisterListener(listener)
            }
        } else {
            onDispose { }
        }
    }

    return tilt to direction
}
