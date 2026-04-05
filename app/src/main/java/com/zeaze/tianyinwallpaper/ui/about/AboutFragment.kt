package com.zeaze.tianyinwallpaper.ui.about

import android.content.Context
import android.graphics.Bitmap
import android.os.Build
import android.widget.Toast
import com.zeaze.tianyinwallpaper.utils.ThumbnailUtils
import com.zeaze.tianyinwallpaper.App
import com.zeaze.tianyinwallpaper.base.rxbus.RxBus
import com.zeaze.tianyinwallpaper.base.rxbus.RxConstants
import com.zeaze.tianyinwallpaper.backdrop.backdrops.layerBackdrop
import com.zeaze.tianyinwallpaper.backdrop.backdrops.rememberCanvasBackdrop
import com.zeaze.tianyinwallpaper.backdrop.backdrops.rememberLayerBackdrop
import com.zeaze.tianyinwallpaper.ui.commom.SaveData
import com.zeaze.tianyinwallpaper.ui.commom.LiquidConfirmOverlay
import com.zeaze.tianyinwallpaper.ui.commom.ProgressiveBlurContent
import com.zeaze.tianyinwallpaper.model.TianYinWallpaperModel
import com.zeaze.tianyinwallpaper.utils.FileUtil
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Surface
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.zIndex
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.alibaba.fastjson.JSON
import com.kyant.shapes.Capsule
import com.zeaze.tianyinwallpaper.backdrop.backdrops.LayerBackdrop
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.zeaze.tianyinwallpaper.catalog.components.LiquidButton
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyListState
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback

private data class AboutGroupUiModel(
    val saveData: SaveData,
    val wallpapers: List<TianYinWallpaperModel>
)

@OptIn(FlowPreview::class)
@Composable
fun AboutRouteScreen(
    useDarkTheme: Boolean,
    onSelectionModeChange: (Boolean) -> Unit = {},
    onBack: () -> Unit = {}
) {
    val context = LocalContext.current
    val density = LocalDensity.current
    val haptic = LocalHapticFeedback.current
    val statusBarTopPadding = remember(context) {
        val id = context.resources.getIdentifier("status_bar_height", "dimen", "android")
        if (id > 0) context.resources.getDimensionPixelSize(id) else 0
    }
    val statusBarTopPaddingDp = with(density) { statusBarTopPadding.toDp() }
    val groupUiList = remember { mutableStateListOf<AboutGroupUiModel>() }
    val selectedPositions = remember { mutableStateListOf<Int>() }
    val listState = rememberLazyListState()
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    var loadGroupsJob by remember { mutableStateOf<Job?>(null) }
    var lastGroupsJson by remember { mutableStateOf<String?>(null) }
    var groupsVersion by remember { mutableStateOf(0) }
    var selectionMode by remember { mutableStateOf(false) }
    var pendingOverwriteGroup by remember { mutableStateOf<SaveData?>(null) }
    val isLightTheme = !useDarkTheme
    val enableLiquidGlass = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
    val liquidBackdrop = if (enableLiquidGlass) rememberLayerBackdrop() else null
    val backgroundColor = if (isLightTheme) Color(0xFFF2F2F6) else Color(0xFF121212)
    val canvasBackdrop = rememberCanvasBackdrop { drawRect(backgroundColor) }
    val dialogBackdrop = liquidBackdrop ?: canvasBackdrop
    val groupBackgroundColor = if (isLightTheme) Color.White else Color(0xFF1E1E1E)
    val groupLabelColor = if (isLightTheme) Color(0xFF1A1A1F) else Color(0xFFF5F5FA)
    val selectedIndicatorColor = if (isLightTheme) Color(0xFF0088FF) else Color(0xFF4DA3FF)

    fun updateSelectedIndices(from: Int, to: Int) {
        val currentSelected = selectedPositions.toList()
        selectedPositions.clear()
        currentSelected.forEach { index ->
            when {
                index == from -> selectedPositions.add(to)
                from < to && index in (from + 1)..to -> selectedPositions.add(index - 1)
                from > to && index in to..<from -> selectedPositions.add(index + 1)
                else -> selectedPositions.add(index)
            }
        }
    }

    var pendingReorderSave by remember { mutableStateOf(false) }

    val reorderableState = rememberReorderableLazyListState(
        lazyListState = listState,
        onMove = { from, to ->
            updateSelectedIndices(from.index, to.index)
            val movedItem = groupUiList.removeAt(from.index)
            groupUiList.add(to.index, movedItem)
            pendingReorderSave = true
        }
    )

    fun enterSelectionMode() {
        selectionMode = true
        selectedPositions.clear()
    }

    fun exitSelectionMode() {
        selectionMode = false
        selectedPositions.clear()
    }

    fun loadGroups() {
        loadGroupsJob?.cancel()
        loadGroupsJob = scope.launch(Dispatchers.IO) {
            val data = FileUtil.loadData(context, FileUtil.dataPath)
            if (data == lastGroupsJson) return@launch
            val list = JSON.parseArray(data, SaveData::class.java) ?: emptyList()
            val uiModels = list.map { saveData ->
                AboutGroupUiModel(
                    saveData = saveData,
                    wallpapers = JSON.parseArray(saveData.s, TianYinWallpaperModel::class.java) ?: emptyList()
                )
            }
            withContext(Dispatchers.Main) {
                if (data == lastGroupsJson) return@withContext
                lastGroupsJson = data
                groupUiList.clear()
                groupUiList.addAll(uiModels)
                selectedPositions.clear()
                groupsVersion++
            }
        }
    }

    fun deleteSelectedGroups() {
        if (selectedPositions.isEmpty()) {
            Toast.makeText(context, "请先选择壁纸组", Toast.LENGTH_SHORT).show()
            return
        }
        val selectedSet = selectedPositions.toSet()
        val remained = groupUiList.filterIndexed { index, _ -> index !in selectedSet }
        val remainedJson = JSON.toJSONString(remained.map { it.saveData })
        FileUtil.save(context, remainedJson, FileUtil.dataPath) {
            scope.launch {
                lastGroupsJson = remainedJson
                groupUiList.clear()
                groupUiList.addAll(remained)
                groupsVersion++
                exitSelectionMode()
                Toast.makeText(context, "已删除选中壁纸组", Toast.LENGTH_SHORT).show()
            }
        }
    }

    LaunchedEffect(Unit) { loadGroups() }
    val preloadRequests = remember(groupsVersion) { buildPreloadRequests(groupUiList) }
    LaunchedEffect(listState, groupsVersion) {
        if (preloadRequests.isEmpty()) return@LaunchedEffect
        androidx.compose.runtime.snapshotFlow {
            val visibleItems = listState.layoutInfo.visibleItemsInfo
            val start = visibleItems.firstOrNull()?.index ?: 0
            val end = visibleItems.lastOrNull()?.index ?: start
            start to end
        }
            .debounce(120)
            .distinctUntilChanged()
            .collectLatest { (start, end) ->
                ThumbnailUtils.preloadVisibleRange(
                    context = context,
                    requests = preloadRequests,
                    visibleStart = start,
                    visibleEnd = end,
                    preloadOffset = PRELOAD_RANGE_OFFSET
                )
            }
    }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) loadGroups()
        }
        lifecycleOwner.lifecycle.addObserver(observer)

        val optionsDisposable = RxBus.getDefault()
            .toObservableWithCode(RxConstants.RX_TRIGGER_GROUP_OPTIONS, Unit::class.java)
            .subscribe { enterSelectionMode() }

        val groupsChangedDisposable = RxBus.getDefault()
            .toObservableWithCode(RxConstants.RX_GROUPS_CHANGED, Unit::class.java)
            .subscribe { loadGroups() }

        onDispose {
            loadGroupsJob?.cancel()
            optionsDisposable.dispose()
            groupsChangedDisposable.dispose()
            lifecycleOwner.lifecycle.removeObserver(observer)
            onSelectionModeChange(false)
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .let { m ->
                    if (enableLiquidGlass && liquidBackdrop != null) m.layerBackdrop(liquidBackdrop) else m
                }
        ) {
            Box(modifier = Modifier.fillMaxSize().background(backgroundColor))
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 12.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
                contentPadding = PaddingValues(
                    top = statusBarTopPaddingDp + 76.dp,
                    bottom = if (selectionMode) 90.dp else 110.dp
                )
            ) {
                itemsIndexed(
                    groupUiList,
                    key = { _, item -> "${item.saveData.name ?: ""}\u0000${item.saveData.s ?: ""}" }
                ) { index, group ->
                    val selected = selectedPositions.contains(index)
                    val key = "${group.saveData.name ?: ""}\u0000${group.saveData.s ?: ""}"

                    ReorderableItem(reorderableState, key = key) { isDragging ->
                        AboutGroupItem(
                            modifier = Modifier.fillMaxWidth()
                                .graphicsLayer {
                                    scaleX = if (isDragging) 1.05f else 1f
                                    scaleY = if (isDragging) 1.05f else 1f
                                    shadowElevation = if (isDragging) 8.dp.toPx() else 0f
                                    shape = RoundedCornerShape(28.dp)
                                    clip = true
                                }
                                .longPressDraggableHandle(
                                    onDragStopped = {
                                        if (pendingReorderSave) {
                                            val newJson = JSON.toJSONString(groupUiList.map { it.saveData })
                                            FileUtil.save(context, newJson, FileUtil.dataPath) {
                                                lastGroupsJson = newJson
                                                groupsVersion++
                                            }
                                            pendingReorderSave = false
                                        }
                                    }
                                )
                                .pointerInput(key, selectionMode) {
                                    awaitEachGesture {
                                        val down = awaitFirstDown(requireUnconsumed = false)
                                        val touchSlop = viewConfiguration.touchSlop
                                        val stayedStillForTimeout = withTimeoutOrNull(400L) {
                                            while (true) {
                                                val event = awaitPointerEvent()
                                                val change = event.changes.firstOrNull { it.id == down.id } ?: return@withTimeoutOrNull false
                                                if (!change.pressed) return@withTimeoutOrNull false
                                                if ((change.position - down.position).getDistance() > touchSlop) {
                                                    return@withTimeoutOrNull false
                                                }
                                            }
                                        } == null
                                        if (stayedStillForTimeout && !selectionMode) {
                                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                            enterSelectionMode()
                                            if (!selectedPositions.contains(index)) {
                                                selectedPositions.add(index)
                                            }
                                        }
                                    }
                                },
                            context = context,
                            data = group.saveData,
                            wallpapers = group.wallpapers,
                            labelColor = groupLabelColor,
                            selectedIndicatorColor = selectedIndicatorColor,
                            containerColor = groupBackgroundColor,
                            selected = selected,
                            onClick = {
                                if (selectionMode) {
                                    if (selected) selectedPositions.remove(index) else selectedPositions.add(index)
                                } else {
                                    pendingOverwriteGroup = group.saveData
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

        LiquidConfirmOverlay(
            visible = pendingOverwriteGroup != null,
            backdrop = dialogBackdrop,
            isLightTheme = !useDarkTheme,
            message = "是否覆盖壁纸列表",
            onDismiss = { pendingOverwriteGroup = null },
            onConfirm = {
                val group = pendingOverwriteGroup ?: return@LiquidConfirmOverlay
                val normalizedList = runCatching {
                    JSON.parseArray(group.s, TianYinWallpaperModel::class.java)
                }.getOrNull()
                if (normalizedList.isNullOrEmpty()) {
                    Toast.makeText(context, "壁纸组数据无效，覆盖失败", Toast.LENGTH_SHORT).show()
                    pendingOverwriteGroup = null
                    return@LiquidConfirmOverlay
                }

                val normalizedJson = JSON.toJSONString(normalizedList)
                // Persist immediately so overwrite still works even if Main screen is not active.
                context.getSharedPreferences(App.TIANYIN, Context.MODE_PRIVATE)
                    .edit()
                    .putString("wallpaperCache", normalizedJson)
                    .putString("wallpaperTvCache", group.name ?: "")
                    .apply()

                RxBus.postWithCode(
                    RxConstants.RX_TRIGGER_OVERWRITE_WALLPAPER_LIST,
                    SaveData(normalizedJson, group.name)
                )
                Toast.makeText(context, "已覆盖当前壁纸列表", Toast.LENGTH_SHORT).show()
                pendingOverwriteGroup = null
            }
        )

        if (selectionMode) {
            val isAllSelected = selectedPositions.size == groupUiList.size && groupUiList.isNotEmpty()
            com.zeaze.tianyinwallpaper.ui.main.SelectionTopBar(
                modifier = Modifier.zIndex(3f),
                statusBarTopPaddingDp = statusBarTopPaddingDp,
                enableLiquidGlass = enableLiquidGlass,
                backdrop = liquidBackdrop,
                isAllSelected = isAllSelected,
                isLightTheme = !useDarkTheme,
                onCancelSelect = { exitSelectionMode() },
                onDelete = { deleteSelectedGroups() },

                onToggleSelectAll = {
                    if (isAllSelected) {
                        selectedPositions.clear()
                    } else {
                        selectedPositions.clear()
                        groupUiList.indices.forEach { selectedPositions.add(it) }
                    }
                }
            )
        } else {
            Row(
                modifier = Modifier
                    .zIndex(3f)
                    .fillMaxWidth()
                    .padding(top = statusBarTopPaddingDp + 10.dp, start = 12.dp, end = 12.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                val isDark = useDarkTheme
                val adaptiveSurfaceColor = if (isDark) Color.Black.copy(0.3f) else Color.White.copy(0.3f)
                val textColor = if (isDark) Color.White else Color.Black

                AboutTopActionButton(
                    text = "返回",
                    onClick = onBack,
                    enableLiquidGlass = enableLiquidGlass,
                    liquidBackdrop = liquidBackdrop,
                    surfaceColor = adaptiveSurfaceColor,
                    isDark = isDark,
                    textColor = textColor
                )

                AboutTopActionButton(
                    text = "多选",
                    onClick = { enterSelectionMode() },
                    enableLiquidGlass = enableLiquidGlass,
                    liquidBackdrop = liquidBackdrop,
                    surfaceColor = adaptiveSurfaceColor,
                    isDark = isDark,
                    textColor = textColor
                )
            }
        }
    }

    LaunchedEffect(selectionMode) {
        onSelectionModeChange(selectionMode)
    }
}

@Composable
private fun AboutTopActionButton(
    text: String,
    onClick: () -> Unit,
    enableLiquidGlass: Boolean,
    liquidBackdrop: LayerBackdrop?,
    surfaceColor: Color,
    isDark: Boolean,
    textColor: Color
) {
    if (enableLiquidGlass && liquidBackdrop != null) {
        LiquidButton(
            onClick = onClick,
            backdrop = liquidBackdrop,
            surfaceColor = surfaceColor,
            modifier = Modifier.height(48.dp)
        ) {
            BasicText(
                text = text,
                modifier = Modifier.padding(horizontal = 16.dp),
                style = TextStyle(textColor, 15.sp)
            )
        }
    } else {
        Surface(
            modifier = Modifier
                .height(48.dp)
                .clickable { onClick() },
            shape = Capsule(),
            color = if (isDark) Color(0x33000000) else Color(0xAAFFFFFF),
            border = BorderStroke(1.dp, if (isDark) Color(0x33FFFFFF) else Color(0x88FFFFFF))
        ) {
            Box(contentAlignment = Alignment.Center, modifier = Modifier.padding(horizontal = 16.dp)) {
                Text(text = text, color = textColor, fontSize = 15.sp)
            }
        }
    }
}

@Composable
private fun AboutGroupItem(
    modifier: Modifier = Modifier,
    context: Context,
    data: SaveData,
    wallpapers: List<TianYinWallpaperModel>,
    labelColor: Color,
    selectedIndicatorColor: Color,
    containerColor: Color,
    selected: Boolean,
    onClick: () -> Unit
) {
    val cardShape = RoundedCornerShape(28.dp)
    Surface(
        modifier = modifier
            .clip(cardShape)
            .clickable { onClick() },
        shape = cardShape,
        color = containerColor,
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(cardShape)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(10.dp)
            ) {
                Text(
                    text = data.name ?: "未命名壁纸组",
                    color = labelColor,
                    modifier = Modifier.padding(start = 8.dp)
                )
                Spacer(modifier = Modifier.height(ITEM_PREVIEW_TOP_MARGIN_DP.dp))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(ITEM_PREVIEW_HEIGHT_DP.dp),
                    horizontalArrangement = Arrangement.spacedBy(ITEM_PREVIEW_SPACING_DP.dp)
                ) {
                    repeat(PREVIEW_COUNT) { index ->
                        val model = wallpapers.getOrNull(index)
                        PreviewImage(
                            context = context,
                            model = model,
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxHeight()
                        )
                    }
                }
            }
            if (selected) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .clip(cardShape)
                        .background(Color(0x33000000))
                )
                Box(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(10.dp)
                        .size(22.dp)
                        .clip(CircleShape)
                        .background(selectedIndicatorColor),
                    contentAlignment = Alignment.Center
                ) {
                    BasicText(
                        text = "✓",
                        style = TextStyle(color = Color.White, fontSize = 12.sp)
                    )
                }
            }
        }
    }
}

@Composable
private fun PreviewImage(context: Context, model: TianYinWallpaperModel?, modifier: Modifier) {
    val bitmap by produceState<Bitmap?>(initialValue = null, model) {
        value = loadPreviewBitmap(context, model)
    }
    bitmap?.let {
        Image(
            bitmap = it.asImageBitmap(),
            contentDescription = "Wallpaper preview",
            modifier = modifier.clip(RoundedCornerShape(20.dp)),
            contentScale = ContentScale.Crop
        )
    } ?: Spacer(modifier = modifier
        .clip(RoundedCornerShape(8.dp))
        .background(MaterialTheme.colors.background))
}

private suspend fun loadPreviewBitmap(context: Context, model: TianYinWallpaperModel?): Bitmap? {
    if (model == null) return null
    return withContext(Dispatchers.IO) {
        ThumbnailUtils.loadThumbnail(context, model.toThumbnailRequest())
    }
}

private fun buildPreloadRequests(groups: List<AboutGroupUiModel>): List<ThumbnailUtils.Request> {
    val requests = ArrayList<ThumbnailUtils.Request>(groups.size * PREVIEW_COUNT)
    groups.forEach { group ->
        group.wallpapers.take(PREVIEW_COUNT).forEach { model ->
            requests.add(model.toThumbnailRequest(priority = ThumbnailUtils.Request.PRIORITY_HIGH))
        }
    }
    return requests.distinctBy { it.cacheKey }
}

private fun TianYinWallpaperModel.toThumbnailRequest(
    priority: Int = ThumbnailUtils.Request.PRIORITY_NORMAL
): ThumbnailUtils.Request {
    val fallbackId = imgUri ?: videoUri ?: imgPath ?: "unknown"
    return ThumbnailUtils.Request(
        uuid = uuid ?: fallbackId,
        type = type,
        imgUri = imgUri,
        videoUri = videoUri,
        imgPath = imgPath,
        priority = priority
    )
}

private const val ITEM_PREVIEW_HEIGHT_DP = 100
private const val ITEM_PREVIEW_TOP_MARGIN_DP = 8
private const val ITEM_PREVIEW_SPACING_DP = 6
private const val PREVIEW_COUNT = 5
private const val PRELOAD_RANGE_OFFSET = 6
