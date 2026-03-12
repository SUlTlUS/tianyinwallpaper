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
import android.media.MediaPlayer
import android.net.Uri
import android.os.Build
import android.util.Log
import android.widget.Toast
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
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.isSystemInDarkTheme
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
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.layout.wrapContentSize
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.zIndex
import com.zeaze.tianyinwallpaper.backdrop.Backdrop
import com.zeaze.tianyinwallpaper.catalog.components.LiquidButton
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
import com.zeaze.tianyinwallpaper.service.TianYinWallpaperService
import com.zeaze.tianyinwallpaper.ui.main.SelectionTopBar
import com.zeaze.tianyinwallpaper.utils.FileUtil
import com.kyant.shapes.Capsule
import com.kyant.shapes.RoundedRectangle
import java.io.IOException
import java.util.UUID
import kotlin.concurrent.thread
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlinx.coroutines.delay
import androidx.compose.animation.core.tween
import androidx.activity.compose.rememberLauncherForActivityResult
import com.zeaze.tianyinwallpaper.service.RasterWallpaperService
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.ui.input.pointer.pointerInput
private const val PREF_RASTER_GROUPS = "rasterGroups"
private const val PREF_RASTER_ACTIVE_GROUP_ID = "rasterActiveGroupId"
private const val WALLPAPER_TYPE_STATIC = 0
private const val WALLPAPER_TYPE_DYNAMIC = 1
private const val MAX_STATIC_GROUP_IMAGES = 5
private const val MIN_STATIC_GROUP_IMAGES = 2

private enum class StaticPickMode {
    CREATE_NEW,
    REPLACE_ALL,
    APPEND
}

// 统一对话框状态管理（对齐 MainRouteScreen 的 sealed class DialogState 模式）
private sealed class RasterDialogState {
    object Type : RasterDialogState()
    object Delete : RasterDialogState()
}

@OptIn(ExperimentalAnimationApi::class)
@Composable
fun RasterRouteScreen(onBottomBarVisibleChange: (Boolean) -> Unit = {}) {
    val context = LocalContext.current
    val activity = context as? Activity
    val isLightTheme = !isSystemInDarkTheme()
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

    fun toast(msg: String) {
        Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
    }

    val wallpaperLaunch = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            toast("光栅壁纸设置成功")
        } else {
            toast("设置失败，请授予壁纸权限")
        }
    }

    fun persistGroups() {
        pref.edit().putString(PREF_RASTER_GROUPS, JSON.toJSONString(groups)).apply()
    }

    fun loadGroups() {
        val parsed = JSON.parseArray(
            pref.getString(PREF_RASTER_GROUPS, "[]"),
            RasterGroupModel::class.java
        ) ?: emptyList()
        groups.clear()
        groups.addAll(parsed)
    }

    fun enterSelectionMode(initialId: String? = null) {
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

    fun removeGroupById(groupId: String) {
        val removeIndex = groups.indexOfFirst { it.id == groupId }
        if (removeIndex < 0) return

        groups.removeAt(removeIndex)
        selectedIds.remove(groupId)

        if (detailGroup?.id == groupId) {
            detailGroup = null
        }

        val activeGroupId = pref.getString(PREF_RASTER_ACTIVE_GROUP_ID, null)
        if (activeGroupId == groupId) {
            val fallbackActiveId = groups.firstOrNull()?.id
            pref.edit().putString(PREF_RASTER_ACTIVE_GROUP_ID, fallbackActiveId).apply()
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
            toast("当前光栅组合内容为空")
            return
        }
        thread {
            FileUtil.save(context, JSON.toJSONString(models), FileUtil.wallpaperPath) {
                val hostActivity = context as? Activity
                if (hostActivity == null) {
                    toast("当前页面无法打开系统壁纸设置")
                    return@save
                }
                hostActivity.runOnUiThread {
                    val wallpaperManager = WallpaperManager.getInstance(hostActivity)
                    try {
                        wallpaperManager.clear()
                    } catch (e: IOException) {
                        Log.w("RasterRouteScreen", "Clear wallpaper failed", e)
                    }
                    val intent = Intent().apply {
                        action = WallpaperManager.ACTION_CHANGE_LIVE_WALLPAPER
                        putExtra(
                            WallpaperManager.EXTRA_LIVE_WALLPAPER_COMPONENT,
                            ComponentName(hostActivity, TianYinWallpaperService::class.java)
                        )
                    }
                    wallpaperLaunch.launch(intent)
                    toast("已应用光栅组合")
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
            toast("请先添加光栅组合")
            return
        }

        pref.edit().putString(PREF_RASTER_ACTIVE_GROUP_ID, target.id).apply()

        val hostActivity = activity ?: return
        val wallpaperManager = WallpaperManager.getInstance(hostActivity)
        try {
            wallpaperManager.clear()
        } catch (e: java.io.IOException) {
            e.printStackTrace()
        }
        val intent = Intent().apply {
            action = WallpaperManager.ACTION_CHANGE_LIVE_WALLPAPER
            putExtra(
                WallpaperManager.EXTRA_LIVE_WALLPAPER_COMPONENT,
                ComponentName(hostActivity, RasterWallpaperService::class.java)
            )
        }
        wallpaperLaunch.launch(intent)
    }

    val pickStaticLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments()
    ) { uris ->
        if (uris.isEmpty()) return@rememberLauncherForActivityResult
        val limited = uris.take(MAX_STATIC_GROUP_IMAGES)
        limited.forEach { uri ->
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
                    StaticPickMode.REPLACE_ALL -> limited.map { it.toString() }
                    StaticPickMode.APPEND -> (current.imageUris + limited.map { it.toString() }).take(MAX_STATIC_GROUP_IMAGES)
                    StaticPickMode.CREATE_NEW -> limited.map { it.toString() }
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
                    imageUris = limited.map { it.toString() },
                    createdAt = System.currentTimeMillis()
                )
            )
            persistGroups()
            if (uris.size > MAX_STATIC_GROUP_IMAGES) {
                toast("静态光栅最多选择${MAX_STATIC_GROUP_IMAGES}张，已自动截取前${MAX_STATIC_GROUP_IMAGES}张")
            }
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
                if (detailGroup == null) {
                    toast("请先添加光栅组合")
                }
            }

        val selectDisposable = RxBus.getDefault()
            .toObservableWithCode(RxConstants.RX_TRIGGER_ENTER_RASTER_SELECT_MODE, Unit::class.java)
            .subscribe { enterSelectionMode() }

        onDispose {
            addDisposable.dispose()
            applyDisposable.dispose()
            previewDisposable.dispose()
            selectDisposable.dispose()
            onBottomBarVisibleChange(true)
        }
    }

    LaunchedEffect(selectionMode, detailGroup) {
        onBottomBarVisibleChange(!selectionMode && detailGroup == null)
    }

    val cardAspectRatio = remember {
        val width = FileUtil.width.takeIf { it > 0 } ?: 9
        val height = FileUtil.height.takeIf { it > 0 } ?: 16
        width.toFloat() / height.toFloat()
    }
    val previewAspectRatio = cardAspectRatio

    var showSelectionBar by remember { mutableStateOf(false) }
    LaunchedEffect(selectionMode) {
        if (selectionMode) {
            delay(16)
            showSelectionBar = true
        } else {
            showSelectionBar = false
        }
    }

    // 拖拽排序状态（对齐 MainRouteScreen 的拖拽排序）
    val gridState = rememberLazyGridState()
    var draggingItemIndex by remember { mutableStateOf<Int?>(null) }
    var draggingItemKey by remember { mutableStateOf<Any?>(null) }
    var dragOffset by remember { mutableStateOf(Offset.Zero) }
    var startViewportOffset by remember { mutableStateOf(0) }

    // 辅助函数：更新排序后的选中索引
    fun updateSelectedIdsAfterMove(from: Int, to: Int) {
        val fromId = groups.getOrNull(from)?.id ?: return
        // selectedIds 基于 id，不需要调整索引
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
                    .fillMaxSize()
                    .pointerInput(Unit) {
                        detectDragGesturesAfterLongPress(
                            onDragStart = { offset ->
                                dragOffset = Offset.Zero
                                val touchOffset = offset
                                val layoutInfo = gridState.layoutInfo
                                startViewportOffset = layoutInfo.viewportStartOffset

                                val touchedItem = layoutInfo.visibleItemsInfo
                                    .filter { it.index != -1 }
                                    .firstOrNull { item ->
                                        val left = item.offset.x.toFloat()
                                        val right = (item.offset.x + item.size.width).toFloat()
                                        val top = (item.offset.y - startViewportOffset).toFloat()
                                        val bottom = (item.offset.y - startViewportOffset + item.size.height).toFloat()
                                        touchOffset.x in left..right && touchOffset.y in top..bottom
                                    }

                                if (touchedItem != null) {
                                    draggingItemIndex = touchedItem.index
                                    draggingItemKey = touchedItem.key
                                } else {
                                    layoutInfo.visibleItemsInfo
                                        .filter { it.index != -1 }
                                        .minByOrNull { item ->
                                            val screenCenterX = item.offset.x + item.size.width / 2f
                                            val screenCenterY = item.offset.y - startViewportOffset + item.size.height / 2f
                                            (screenCenterX - touchOffset.x) * (screenCenterX - touchOffset.x) +
                                                    (screenCenterY - touchOffset.y) * (screenCenterY - touchOffset.y)
                                        }?.let { item ->
                                            draggingItemIndex = item.index
                                            draggingItemKey = item.key
                                        }
                                }
                            },
                            onDrag = { change, dragAmount ->
                                change.consume()
                                dragOffset += dragAmount
                                draggingItemKey?.let { currentKey ->
                                    val draggingItem = gridState.layoutInfo.visibleItemsInfo.find { it.key == currentKey }
                                    if (draggingItem != null) {
                                        val currentIndex = draggingItem.index

                                        val draggingScreenCenter = Offset(
                                            draggingItem.offset.x + draggingItem.size.width / 2f,
                                            draggingItem.offset.y - startViewportOffset + draggingItem.size.height / 2f
                                        ) + dragOffset

                                        gridState.layoutInfo.visibleItemsInfo
                                            .filter { it.key != currentKey && it.index != -1 }
                                            .minByOrNull { item ->
                                                val screenCenterX = item.offset.x + item.size.width / 2f
                                                val screenCenterY = item.offset.y - startViewportOffset + item.size.height / 2f
                                                (screenCenterX - draggingScreenCenter.x) * (screenCenterX - draggingScreenCenter.x) +
                                                        (screenCenterY - draggingScreenCenter.y) * (screenCenterY - draggingScreenCenter.y)
                                            }?.let { targetItem ->
                                                val targetScreenCenter = Offset(
                                                    targetItem.offset.x + targetItem.size.width / 2f,
                                                    targetItem.offset.y - startViewportOffset + targetItem.size.height / 2f
                                                )
                                                val distSq = (targetScreenCenter.x - draggingScreenCenter.x) * (targetScreenCenter.x - draggingScreenCenter.x) +
                                                        (targetScreenCenter.y - draggingScreenCenter.y) * (targetScreenCenter.y - draggingScreenCenter.y)

                                                if (distSq < (targetItem.size.width * targetItem.size.width * 0.6f)) {
                                                    val targetIndex = targetItem.index

                                                    val movedItem = groups.removeAt(currentIndex)
                                                    groups.add(targetIndex, movedItem)

                                                    val oldScreenCenter = Offset(
                                                        draggingItem.offset.x + draggingItem.size.width / 2f,
                                                        draggingItem.offset.y - startViewportOffset + draggingItem.size.height / 2f
                                                    )
                                                    val newScreenCenter = Offset(
                                                        targetItem.offset.x + targetItem.size.width / 2f,
                                                        targetItem.offset.y - startViewportOffset + targetItem.size.height / 2f
                                                    )
                                                    dragOffset -= (newScreenCenter - oldScreenCenter)
                                                    draggingItemIndex = targetIndex
                                                }
                                            }
                                    }
                                }
                            },
                            onDragEnd = {
                                draggingItemIndex = null
                                draggingItemKey = null
                                dragOffset = Offset.Zero
                                startViewportOffset = 0
                                persistGroups()
                            },
                            onDragCancel = {
                                draggingItemIndex = null
                                draggingItemKey = null
                                dragOffset = Offset.Zero
                                startViewportOffset = 0
                            }
                        )
                    },
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
                    val isDragging = draggingItemKey != null && draggingItemKey == group.id

                    RasterGroupCard(
                        group = group,
                        selected = selected,
                        aspectRatio = cardAspectRatio,
                        isDragging = isDragging,
                        dragOffset = dragOffset,
                        showRemoveButton = selectionMode,
                        onRemove = { removeGroupById(group.id) },
                        onClick = {
                            if (selectionMode) {
                                if (selected) selectedIds.remove(group.id) else selectedIds.add(group.id)
                            } else {
                                detailGroup = group.copy()
                            }
                        },
                        onLongClick = {
                            if (!selectionMode) {
                                enterSelectionMode(group.id)
                            } else if (!selected) {
                                selectedIds.add(group.id)
                            }
                        }
                    )
                }
            }
        }

        // 前景层：SelectionTopBar
        if (showSelectionBar) {
            val isAllSelected = groups.isNotEmpty() && selectedIds.size == groups.size
            SelectionTopBar(
                statusBarTopPaddingDp = statusBarTopPaddingDp,
                enableLiquidGlass = enableLiquidGlass,
                backdrop = liquidBackdrop,
                isAllSelected = isAllSelected,
                onCancelSelect = { exitSelectionMode() },
                onDelete = {
                    if (selectedIds.isEmpty()) {
                        toast(context.getString(R.string.no_selected_tip))
                    } else {
                        showDeleteDialog = true
                    }
                },
                onToggleSelectAll = {
                    if (isAllSelected) {
                        selectedIds.clear()
                    } else {
                        selectedIds.clear()
                        selectedIds.addAll(groups.map { it.id })
                    }
                }
            )
        }

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
                        showTypeDialog = false
                        showDeleteDialog = false
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
                    .togetherWith(fadeOut(animationSpec = spring(stiffness = Spring.StiffnessLow)))
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
                                    "静态光栅：每个组合最多5张图片\n动态光栅：每个组合只能选择1个视频",
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
                                    BasicText("静态", style = TextStyle(Color.White, 16.sp))
                                }
                                Row(
                                    Modifier
                                        .clip(Capsule())
                                        .background(accentColor)
                                        .clickable {
                                            showTypeDialog = false
                                            pickDynamicLauncher.launch(arrayOf("video/*"))
                                        }
                                        .height(48.dp)
                                        .fillMaxWidth(),
                                    horizontalArrangement = Arrangement.Center,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    BasicText("动态", style = TextStyle(Color.White, 16.sp))
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
                previewAspectRatio = previewAspectRatio,
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
                        toast("图集至少保留${MIN_STATIC_GROUP_IMAGES}张图片")
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
                    val idx = groups.indexOfFirst { g -> g.id == editorGroup.id }
                    if (idx >= 0) {
                        groups[idx] = groups[idx].copy(sensorWidth = newWidth)
                        persistGroups()
                    }
                },
                onVideoAction = {
                    if (group.type == RasterGroupModel.TYPE_STATIC) {
                        dynamicPickTargetId = null
                        pickDynamicLauncher.launch(arrayOf("video/*"))
                    } else {
                        dynamicPickTargetId = group.id
                        pickDynamicLauncher.launch(arrayOf("video/*"))
                    }
                }
            )
        }
    }


}

@Composable
private fun RasterGroupCard(
    group: RasterGroupModel,
    selected: Boolean,
    aspectRatio: Float,
    isDragging: Boolean = false,
    dragOffset: Offset = Offset.Zero,
    showRemoveButton: Boolean = false,
    onRemove: () -> Unit = {},
    onClick: () -> Unit,
    onLongClick: () -> Unit
) {
    BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
        val safeRatio = if (aspectRatio > 0f) aspectRatio else 9f / 16f
        val cardHeight = maxWidth / safeRatio
        Box(
            modifier = Modifier
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
                .combinedClickable(onClick = onClick, onLongClick = onLongClick)
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
        value = runCatching {
            if (group.type == RasterGroupModel.TYPE_STATIC) {
                val firstUri = group.imageUris.firstOrNull() ?: return@runCatching null
                context.contentResolver.openInputStream(Uri.parse(firstUri))?.use {
                    android.graphics.BitmapFactory.decodeStream(it)
                }
            } else {
                val videoUri = group.videoUri ?: return@runCatching null
                FileUtil.getVideoThumbnailFromUri(context, Uri.parse(videoUri))
            }
        }.getOrNull()
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
    // ✅ 新增参数
    staticEditorGroupId: String?,
    onStaticEditorDismiss: () -> Unit,
    onStaticEditorReplaceAll: (RasterGroupModel) -> Unit,
    onStaticEditorAppend: (RasterGroupModel) -> Unit,
    onStaticEditorReplaceSingle: (RasterGroupModel, Int) -> Unit,
    onStaticEditorMove: (RasterGroupModel, Int, Int) -> Unit,
    onStaticEditorCommitReorder: () -> Unit,
    onStaticEditorDeleteSingle: (RasterGroupModel, Int) -> Unit,
    onSensorWidthChanged: (RasterGroupModel, Float) -> Unit,
    groups: List<RasterGroupModel>,
    onDismiss: () -> Unit,
    onApply: () -> Unit,
    onImageAction: () -> Unit,
    onVideoAction: () -> Unit
) {
    val isDark = !MaterialTheme.colors.isLight
    val isLightTheme = MaterialTheme.colors.isLight
    val pageBackground = MaterialTheme.colors.background
    val onPage = MaterialTheme.colors.onBackground
    val frameBackground = if (isDark) Color(0xFF151515) else Color(0xFFF2F2F2)
    val frameBorder = if (isDark) Color(0x55FFFFFF) else Color(0x33000000)
    val pillBackground = if (isDark) Color(0x22222222) else Color(0x22FFFFFF)
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

            BoxWithConstraints(
                modifier = Modifier
                    .align(Alignment.Center)
                    .fillMaxWidth(0.72f)
            ) {
                val safeRatio = if (screenAspectRatio > 0f) screenAspectRatio else 9f / 16f
                val frameHeight = maxWidth / safeRatio
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(frameHeight)
                        .clip(RoundedCornerShape(30.dp))
                        .border(1.dp, frameBorder, RoundedCornerShape(30.dp))
                        .background(frameBackground)
                ) {
                    if (group.type == RasterGroupModel.TYPE_STATIC) {
                        GyroStaticRasterPreview(
                            group = group,
                            sensorWidth = group.sensorWidth,
                            modifier = Modifier
                                .fillMaxSize()
                                .clip(RoundedCornerShape(30.dp))
                        )
                    } else {
                        GyroDynamicRasterPreview(
                            group = group,
                            sensorWidth = group.sensorWidth,
                            modifier = Modifier
                                .fillMaxSize()
                                .clip(RoundedCornerShape(30.dp))
                        )
                    }
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
                LiquidButton(onClick = onDismiss, backdrop = detailBackdrop) {
                    Text(text = "取消", color = onPage)
                }
                LiquidButton(onClick = onApply, backdrop = detailBackdrop, tint = Color(0xFF2A83FF)) {
                    Text(text = "应用", color = Color.White)
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
                    else Color.Black.copy(0.3f),
                    modifier = Modifier.height(44.dp)
                ) {
                    BasicText(
                        "图集光栅",
                        modifier = Modifier.padding(horizontal = 14.dp),
                        style = TextStyle(
                            if (isStatic) Color.White else onPage,
                            15.sp
                        )
                    )
                }
                LiquidButton(
                    onClick = onVideoAction,
                    backdrop = detailBackdrop,
                    surfaceColor = if (!isStatic) Color(0xFF2A83FF).copy(alpha = 0.75f)
                    else Color.Black.copy(0.3f),
                    modifier = Modifier.height(44.dp)
                ) {
                    BasicText(
                        "视频光栅",
                        modifier = Modifier.padding(horizontal = 14.dp),
                        style = TextStyle(
                            if (!isStatic) Color.White else onPage,
                            15.sp
                        )
                    )
                }
            } else {
                Text(
                    text = "图集光栅",
                    color = if (isStatic) onPage else onPage.copy(alpha = 0.7f),
                    modifier = Modifier
                        .clip(RoundedCornerShape(20.dp))
                        .background(if (isStatic) Color(0x332A83FF) else Color.Transparent)
                        .combinedClickable(onClick = onImageAction)
                        .padding(horizontal = 14.dp, vertical = 8.dp)
                )
                Text(
                    text = "视频光栅",
                    color = if (!isStatic) onPage else onPage.copy(alpha = 0.7f),
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
            contentAlignment = Alignment.BottomCenter,
            label = "StaticEditOverlay",
            modifier = Modifier.fillMaxSize()
        ) { currentEditorGroup ->
            if (currentEditorGroup != null) {
                val editBackdrop = detailBackdrop ?: rememberCanvasBackdrop { drawRect(containerColor) }
                val thumbnailListState = rememberLazyListState()
                var draggingIndex by remember(currentEditorGroup.id, currentEditorGroup.imageUris) { mutableStateOf<Int?>(null) }
                var dragOffsetX by remember(currentEditorGroup.id, currentEditorGroup.imageUris) { mutableStateOf(0f) }
                var didReorderInCurrentDrag by remember(currentEditorGroup.id, currentEditorGroup.imageUris) { mutableStateOf(false) }

                Column(
                    Modifier
                        .padding(horizontal = 24.dp, vertical = 32.dp)  // 左右24dp，上下32dp
                        .fillMaxWidth()
                        .wrapContentHeight()
                        .drawBackdrop(
                            backdrop = editBackdrop,
                            shape = { RoundedRectangle(28f.dp) },
                            effects = {
                                colorControls(
                                    brightness = if (isLightTheme) 0.2f else 0f,
                                    saturation = 1.5f
                                )
                                blur(if (isLightTheme) 16f.dp.toPx() else 8f.dp.toPx())
                                lens(16f.dp.toPx(), 32f.dp.toPx(), depthEffect = true)
                            },
                            highlight = { Highlight.Plain },
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
                        "支持添加2-5张图片，长按图片可拖拽排序",
                        style = TextStyle(contentColor.copy(alpha = 0.7f), 14.sp)
                    )
                    Spacer(Modifier.height(12.dp))
                    LazyRow(
                        state = thumbnailListState,
                        modifier = Modifier
                            .fillMaxWidth()
                            .pointerInput(currentEditorGroup.id, currentEditorGroup.imageUris) {
                                detectDragGesturesAfterLongPress(
                                    onDragStart = { offset ->
                                        val touched = thumbnailListState.layoutInfo.visibleItemsInfo
                                            .firstOrNull { item ->
                                                item.index < currentEditorGroup.imageUris.size &&
                                                        offset.x in item.offset.toFloat()..(item.offset + item.size).toFloat()
                                            }
                                        if (touched != null) {
                                            draggingIndex = touched.index
                                            dragOffsetX = 0f
                                            didReorderInCurrentDrag = false
                                        }
                                    },
                                    onDrag = { change, dragAmount ->
                                        val currentIndex = draggingIndex ?: return@detectDragGesturesAfterLongPress
                                        change.consume()
                                        dragOffsetX += dragAmount.x

                                        val visibleItems = thumbnailListState.layoutInfo.visibleItemsInfo
                                        val draggingItem = visibleItems.firstOrNull { it.index == currentIndex } ?: return@detectDragGesturesAfterLongPress
                                        val draggingCenterX = draggingItem.offset + draggingItem.size / 2f + dragOffsetX

                                        visibleItems
                                            .filter { it.index != currentIndex && it.index < currentEditorGroup.imageUris.size }
                                            .minByOrNull { item ->
                                                abs(item.offset + item.size / 2f - draggingCenterX)
                                            }
                                            ?.let { targetItem ->
                                                val targetCenterX = targetItem.offset + targetItem.size / 2f
                                                val distance = abs(targetCenterX - draggingCenterX)
                                                if (distance < targetItem.size * 0.6f) {
                                                    onStaticEditorMove(currentEditorGroup, currentIndex, targetItem.index)
                                                    val oldCenterX = draggingItem.offset + draggingItem.size / 2f
                                                    dragOffsetX -= (targetCenterX - oldCenterX)
                                                    draggingIndex = targetItem.index
                                                    didReorderInCurrentDrag = true
                                                }
                                            }
                                    },
                                    onDragEnd = {
                                        if (didReorderInCurrentDrag) {
                                            onStaticEditorCommitReorder()
                                        }
                                        draggingIndex = null
                                        dragOffsetX = 0f
                                        didReorderInCurrentDrag = false
                                    },
                                    onDragCancel = {
                                        draggingIndex = null
                                        dragOffsetX = 0f
                                        didReorderInCurrentDrag = false
                                    }
                                )
                            },
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
                            val isDragging = draggingIndex == index
                            Box(
                                modifier = Modifier
                                    .height(150.dp)
                                    .aspectRatio(screenAspectRatio)
                                    .zIndex(if (isDragging) 1f else 0f)
                                    .graphicsLayer {
                                        if (isDragging) {
                                            translationX = dragOffsetX
                                            scaleX = 1.04f
                                            scaleY = 1.04f
                                            alpha = 0.92f
                                        }
                                    }
                                    .clip(RoundedCornerShape(12.dp))
                                    .border(
                                        1.dp,
                                        if (index == 0) Color(0xFF2A83FF) else Color.Transparent,
                                        RoundedCornerShape(12.dp)
                                    )
                                    .clickable { onStaticEditorReplaceSingle(currentEditorGroup, index) }
                            ) {
                                val bmp by produceState<android.graphics.Bitmap?>(initialValue = null, uri) {
                                    value = runCatching {
                                        context.contentResolver.openInputStream(Uri.parse(uri))?.use {
                                            android.graphics.BitmapFactory.decodeStream(it)
                                        }
                                    }.getOrNull()
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
                                        .background(Color(0xFFE53935),)
                                        .clickable { onStaticEditorDeleteSingle(currentEditorGroup, index) }
                                        .padding(horizontal = 7.dp, vertical = 0.dp)
                                )
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
                    // ✅ 传感器宽度滑块
                    Spacer(Modifier.height(12.dp))

                    var sensorWidth by remember(currentEditorGroup.id) {
                        mutableStateOf(currentEditorGroup.sensorWidth)
                    }

                    BasicText(
                        "灵敏度",
                        style = TextStyle(contentColor, 14.sp, fontWeight = FontWeight.Bold)
                    )

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

                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .height(40.dp)
                        ) {
                            // 轨道背景
                            Box(
                                modifier = Modifier
                                    .align(Alignment.Center)
                                    .fillMaxWidth()
                                    .height(4.dp)
                                    .clip(RoundedCornerShape(2.dp))
                                    .background(contentColor.copy(0.15f))
                            )
                            // 已填充部分
                            Box(
                                modifier = Modifier
                                    .align(Alignment.CenterStart)
                                    .fillMaxWidth((sensorWidth - 1f) / 8f) // 1~9 映射到 0~1
                                    .height(4.dp)
                                    .clip(RoundedCornerShape(2.dp))
                                    .background(Color(0xFF2A83FF))
                            )
                            // 拖动区域
                            Box(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .pointerInput(Unit) {
                                        detectHorizontalDragGestures(
                                            onHorizontalDrag = { change, _ ->
                                                change.consume()
                                                val fraction = (change.position.x / size.width).coerceIn(0f, 1f)
                                                sensorWidth = (1f + fraction * 8f).coerceIn(1f, 9f)
                                                // ✅ 通过回调通知外层
                                                onSensorWidthChanged(currentEditorGroup, sensorWidth)
                                            }
                                        )
                                    }
                            )
                        }

                        BasicText(
                            "低",
                            style = TextStyle(contentColor.copy(0.6f), 12.sp)
                        )
                    }

                    // 数值显示
                    BasicText(
                        "倾斜 ${String.format("%.0f", Math.toDegrees((sensorWidth / 9.8).toDouble()))}° 到达边缘",
                        style = TextStyle(contentColor.copy(0.5f), 12.sp),
                        modifier = Modifier.fillMaxWidth()
                    )
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
private fun GyroStaticRasterPreview(
    group: RasterGroupModel,
    sensorWidth: Float = 4.5f,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val (tilt, direction) = rememberTiltState(sensorWidth)

    val targetIndex = remember(tilt, group.imageUris.size) {
        if (group.imageUris.isEmpty()) 0
        else {
            val shaped = tilt * tilt  // 平方映射，平放区域更宽
            (shaped * (group.imageUris.size - 1))
                .roundToInt()
                .coerceIn(0, group.imageUris.size - 1)
        }
    }

    var lastIndex by remember { mutableStateOf(targetIndex) }
    // ✅ 扫描方向由传感器左右倾决定
    val slideDirection = if (direction >= 0) 1 else -1
    LaunchedEffect(targetIndex) { lastIndex = targetIndex }

    AnimatedContent(
        targetState = targetIndex,
        transitionSpec = {
            if (slideDirection > 0) {
                // 右倾 → 从右到左扫入
                (slideInHorizontally { it } + fadeIn(tween(200)))
                    .togetherWith(slideOutHorizontally { -it } + fadeOut(tween(150)))
            } else {
                // 左倾 → 从左到右扫入
                (slideInHorizontally { -it } + fadeIn(tween(200)))
                    .togetherWith(slideOutHorizontally { it } + fadeOut(tween(150)))
            }
        },
        label = "static-raster-transition",
        modifier = modifier
    ) { index ->
        val bitmap by produceState<android.graphics.Bitmap?>(initialValue = null, group.id, index) {
            val imageUri = group.imageUris.getOrNull(index)
            value = if (imageUri == null) null
            else runCatching {
                context.contentResolver.openInputStream(Uri.parse(imageUri))?.use {
                    android.graphics.BitmapFactory.decodeStream(it)
                }
            }.getOrNull()
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
                modifier = Modifier.fillMaxSize().background(Color.DarkGray),
                contentAlignment = Alignment.Center
            ) { Text("图片加载失败", color = Color.White) }
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
    
    // 视频播放器状态
    var mediaPlayer by remember { mutableStateOf<MediaPlayer?>(null) }
    var videoDuration by remember { mutableStateOf(0L) }
    var isPlaying by remember { mutableStateOf(false) }
    var currentPosition by remember { mutableStateOf(0L) }
    var targetPosition by remember { mutableStateOf(0L) }
    
    // 初始化 MediaPlayer 并管理生命周期
    DisposableEffect(videoUri) {
        if (videoUri.isNullOrEmpty()) {
            return@DisposableEffect onDispose {
                mediaPlayer?.release()
                mediaPlayer = null
            }
        }
        
        try {
            val mp = MediaPlayer().apply {
                setDataSource(context, Uri.parse(videoUri))
                setOnPreparedListener { mp ->
                    videoDuration = mp.duration.toLong()
                    mp.isLooping = false
                    mp.pause()
                    // 初始位置设为第一帧
                    mp.seekTo(0)
                }
                setOnCompletionListener { mp ->
                    mp.seekTo(0)
                    currentPosition = 0
                    targetPosition = 0
                }
                prepareAsync()
            }
            mediaPlayer = mp
        } catch (e: Exception) {
            Log.w("RasterRouteScreen", "Failed to initialize MediaPlayer", e)
        }
        
        onDispose {
            mediaPlayer?.release()
            mediaPlayer = null
        }
    }
    
    // 根据倾斜控制视频播放 - 连续变化
    val targetTimeMs = remember(tilt, videoDuration) {
        if (videoDuration > 0) {
            (videoDuration.toFloat() * tilt).toLong().coerceAtLeast(0L)
        } else 0L
    }
    
    // 根据目标时间实时更新播放位置（无阈值限制）
    LaunchedEffect(targetTimeMs, mediaPlayer) {
        if (videoDuration <= 0 || mediaPlayer == null) return@LaunchedEffect
        
        // 判断是正向还是反向播放
        if (targetTimeMs > currentPosition) {
            // 正向播放：从左倾或右倾开始播放到目标位置
            mediaPlayer?.apply {
                if (!isPlaying) {
                    seekTo(currentPosition.toInt())
                    start()
                    isPlaying = true
                }
            }
        } else if (targetTimeMs < currentPosition) {
            // 反向播放：倒回到目标位置
            mediaPlayer?.apply {
                if (isPlaying) {
                    pause()
                    isPlaying = false
                }
                seekTo(targetTimeMs.toInt())
                currentPosition = targetTimeMs
            }
        }
    }
    
    // 定期更新当前位置
    LaunchedEffect(mediaPlayer) {
        while (true) {
            delay(100) // 每 100ms 更新一次
            mediaPlayer?.let { mp ->
                if (mp.isPlaying) {
                    val pos = mp.currentPosition.toLong()
                    if (pos >= targetPosition) {
                        mp.pause()
                        isPlaying = false
                        currentPosition = targetPosition
                    } else {
                        currentPosition = pos
                    }
                }
            }
        }
    }
    
    // 显示当前帧的 Bitmap - 使用 snapshotFlow 实现连续更新
    val currentFrame by produceState<android.graphics.Bitmap?>(initialValue = null, targetTimeMs) {
        if (videoUri.isNullOrEmpty()) {
            value = null
            return@produceState
        }
        
        // 每次 targetTimeMs 变化时都重新提取帧
        value = runCatching {
            val retriever = MediaMetadataRetriever()
            try {
                retriever.setDataSource(context, Uri.parse(videoUri))
                val timeUs = (targetTimeMs * 1000).coerceAtLeast(0L)
                retriever.getFrameAtTime(timeUs, MediaMetadataRetriever.OPTION_CLOSEST)
            } finally {
                retriever.release()
            }
        }.onFailure {
            Log.w("RasterRouteScreen", "Failed to extract frame", it)
        }.getOrNull()
    }

    Box(modifier = modifier) {
        if (currentFrame != null) {
            Image(
                bitmap = currentFrame!!.asImageBitmap(),
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop
            )
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
                    tilt = (Math.abs(clampedAccumulated) / maxAngleRadians).coerceIn(0f, 1f)
                    
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




