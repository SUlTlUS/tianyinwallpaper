package com.zeaze.tianyinwallpaper.ui.about

import android.content.Context
import android.graphics.Bitmap
import android.widget.Toast
import androidx.activity.compose.PredictiveBackHandler
import com.zeaze.tianyinwallpaper.utils.ThumbnailUtils
import com.zeaze.tianyinwallpaper.App
import com.zeaze.tianyinwallpaper.base.rxbus.RxBus
import com.zeaze.tianyinwallpaper.base.rxbus.RxConstants
import com.zeaze.tianyinwallpaper.backdrop.Backdrop
import com.zeaze.tianyinwallpaper.backdrop.backdrops.layerBackdrop
import com.zeaze.tianyinwallpaper.backdrop.backdrops.rememberCanvasBackdrop
import com.zeaze.tianyinwallpaper.backdrop.backdrops.rememberLayerBackdrop
import com.zeaze.tianyinwallpaper.ui.commom.SaveData
import com.zeaze.tianyinwallpaper.ui.commom.LiquidConfirmOverlay
import com.zeaze.tianyinwallpaper.ui.commom.ProgressiveBlurContent
import com.zeaze.tianyinwallpaper.model.TianYinWallpaperModel
import com.zeaze.tianyinwallpaper.ui.main.MainWallpaperKindFilter
import com.zeaze.tianyinwallpaper.ui.main.MainWallpaperSortDirection
import com.zeaze.tianyinwallpaper.ui.main.MainWallpaperSortMode
import com.zeaze.tianyinwallpaper.utils.FileUtil
import com.zeaze.tianyinwallpaper.utils.LiquidGlassPrefs
import com.zeaze.tianyinwallpaper.catalog.components.LiquidButton
import com.zeaze.tianyinwallpaper.catalog.components.LiquidButtonGroup
import com.zeaze.tianyinwallpaper.catalog.components.PlainFallbackStyle
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
import androidx.compose.foundation.layout.width
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
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.zIndex
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.alibaba.fastjson.JSON
import com.kyant.shapes.Capsule
import com.zeaze.tianyinwallpaper.R
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.CancellationException
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyListState
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback

private data class AboutGroupUiModel(
    val stableId: String,
    val addedIndex: Int,
    val saveData: SaveData,
    val wallpapers: List<TianYinWallpaperModel>,
    val typeRank: Int
)

private val AboutTopButtonSize = 44.dp

@OptIn(FlowPreview::class)
@Composable
fun AboutRouteScreen(
    useDarkTheme: Boolean,
    kindFilters: Set<MainWallpaperKindFilter> = emptySet(),
    sortMode: MainWallpaperSortMode = MainWallpaperSortMode.Custom,
    sortDirection: MainWallpaperSortDirection = MainWallpaperSortDirection.Descending,
    onPageBackdropReady: (Backdrop?) -> Unit = {},
    onSelectionModeChange: (Boolean) -> Unit = {},
    showBackButton: Boolean = false,
    onBack: () -> Unit = {},
    onSortClick: () -> Unit = {},
    onFilterClick: () -> Unit = {},
    enableLiquidGlass: Boolean = true
) {
    val context = LocalContext.current
    val density = LocalDensity.current
    val haptic = LocalHapticFeedback.current
    val statusBarTopPadding = remember(context) {
        val id = context.resources.getIdentifier("status_bar_height", "dimen", "android")
        if (id > 0) context.resources.getDimensionPixelSize(id) else 0
    }
    val statusBarTopPaddingDp = with(density) { statusBarTopPadding.toDp() }
    var groupUiList by remember { mutableStateOf<List<AboutGroupUiModel>>(emptyList()) }
    val selectedPositions = remember { mutableStateListOf<Int>() }
    val listState = rememberLazyListState()
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    var loadGroupsJob by remember { mutableStateOf<Job?>(null) }
    var lastGroupsJson by remember { mutableStateOf<String?>(null) }
    var groupsVersion by remember { mutableStateOf(0) }
    var selectionMode by remember { mutableStateOf(false) }
    var pendingOverwriteGroup by remember { mutableStateOf<SaveData?>(null) }
    val isLightTheme = !useDarkTheme
    val useLiquidGlass = enableLiquidGlass && LiquidGlassPrefs.isEnabled(context)
    val backgroundColor = if (isLightTheme) Color.White else Color(0xFF0A0A0C)
    val canvasBackdrop = rememberCanvasBackdrop { drawRect(backgroundColor) }
    val topBackdrop = if (useLiquidGlass) rememberLayerBackdrop() else null
    val dialogBackdrop = topBackdrop ?: canvasBackdrop
    val groupBackgroundColor = if (isLightTheme) Color(0xFFF2F3F7) else Color(0xFF1C1C20).copy(alpha = 0.94f)
    val topSurfaceColor = if (isLightTheme) Color.White.copy(alpha = 0.3f) else Color.Black.copy(alpha = 0.3f)
    val groupLabelColor = if (isLightTheme) Color(0xFF1A1A1F) else Color(0xFFF5F5FA)
    val selectedIndicatorColor = MaterialTheme.colors.primary
    val pref = remember(context) { context.getSharedPreferences(App.TIANYIN, Context.MODE_PRIVATE) }
    var recentOpenedVersion by remember { mutableStateOf(0) }

    fun groupKey(group: AboutGroupUiModel): String {
        return group.stableId
    }

    fun groupMatchesFilters(group: AboutGroupUiModel): Boolean {
        if (kindFilters.isEmpty()) return true
        return group.wallpapers.any { model ->
            when (model.type) {
                0 -> MainWallpaperKindFilter.ImageWallpaper in kindFilters
                else -> MainWallpaperKindFilter.VideoWallpaper in kindFilters
            }
        }
    }

    fun groupTypeRank(group: AboutGroupUiModel): Int {
        return group.typeRank
    }

    fun groupRecentRank(group: AboutGroupUiModel): Long {
        recentOpenedVersion
        return pref.getLong("group_recent_opened_${groupKey(group)}", 0L)
    }

    fun markGroupOpened(group: AboutGroupUiModel) {
        pref.edit().putLong("group_recent_opened_${groupKey(group)}", System.currentTimeMillis()).apply()
        recentOpenedVersion++
    }

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
            if (sortMode != MainWallpaperSortMode.Custom || kindFilters.isNotEmpty()) return@rememberReorderableLazyListState
            updateSelectedIndices(from.index, to.index)
            val movedGroups = groupUiList.toMutableList()
            val movedItem = movedGroups.removeAt(from.index)
            movedGroups.add(to.index, movedItem)
            groupUiList = movedGroups
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

    DisposableEffect(topBackdrop) {
        onPageBackdropReady(topBackdrop)
        onDispose { onPageBackdropReady(null) }
    }

    PredictiveBackHandler(enabled = selectionMode) { progress ->
        try {
            progress.collect { }
            exitSelectionMode()
        } catch (_: CancellationException) {
        }
    }

    fun loadGroups() {
        loadGroupsJob?.cancel()
        loadGroupsJob = scope.launch(Dispatchers.IO) {
            val data = FileUtil.loadData(context, FileUtil.dataPath)
            if (data == lastGroupsJson) return@launch
            val list = JSON.parseArray(data, SaveData::class.java) ?: emptyList()
            val uiModels = list.mapIndexed { index, saveData ->
                val wallpapers = JSON.parseArray(saveData.s, TianYinWallpaperModel::class.java) ?: emptyList()
                val hasImage = wallpapers.any { it.type == 0 }
                val hasVideo = wallpapers.any { it.type != 0 }
                AboutGroupUiModel(
                    stableId = "${saveData.name.orEmpty().hashCode().toUInt().toString(16)}-${saveData.s.orEmpty().hashCode().toUInt().toString(16)}",
                    addedIndex = index,
                    saveData = saveData,
                    wallpapers = wallpapers,
                    typeRank = when {
                        hasImage && hasVideo -> 2
                        hasVideo -> 1
                        else -> 0
                    }
                )
            }
            withContext(Dispatchers.Main) {
                if (data == lastGroupsJson) return@withContext
                lastGroupsJson = data
                groupUiList = uiModels
                selectedPositions.clear()
                groupsVersion++
            }
        }
    }

    val visibleGroups = remember(groupUiList, kindFilters, sortMode, sortDirection, recentOpenedVersion) {
        val filteredGroups = groupUiList.filter { groupMatchesFilters(it) }
        if (sortMode == MainWallpaperSortMode.Custom) {
            filteredGroups
        } else {
            val sorted = when (sortMode) {
                MainWallpaperSortMode.Custom -> filteredGroups
                MainWallpaperSortMode.AddedDate -> filteredGroups.sortedBy { it.addedIndex }
                MainWallpaperSortMode.Type -> filteredGroups.sortedWith(
                    compareBy<AboutGroupUiModel> { groupTypeRank(it) }.thenBy { it.saveData.name.orEmpty() }
                )
                MainWallpaperSortMode.Size -> filteredGroups.sortedBy { it.wallpapers.size }
                MainWallpaperSortMode.RecentOpened -> filteredGroups.sortedBy { groupRecentRank(it) }
            }
            if (sortDirection == MainWallpaperSortDirection.Descending) sorted.asReversed() else sorted
        }
    }

    fun deleteSelectedGroups() {
        if (selectedPositions.isEmpty()) {
            Toast.makeText(context, "请先选择分组", Toast.LENGTH_SHORT).show()
            return
        }
        val selectedKeys = selectedPositions.mapNotNull { visibleGroups.getOrNull(it) }.map { groupKey(it) }.toSet()
        val remained = groupUiList.filter { groupKey(it) !in selectedKeys }
        val remainedJson = JSON.toJSONString(remained.map { it.saveData })
        FileUtil.save(context, remainedJson, FileUtil.dataPath) {
            scope.launch {
                lastGroupsJson = remainedJson
                groupUiList = remained
                groupsVersion++
                exitSelectionMode()
                Toast.makeText(context, "已删除选中分组", Toast.LENGTH_SHORT).show()
            }
        }
    }

    LaunchedEffect(Unit) { loadGroups() }
    val preloadRequestGroups = remember(groupUiList) { buildPreloadRequestGroups(groupUiList) }
    LaunchedEffect(listState, preloadRequestGroups) {
        if (preloadRequestGroups.isEmpty()) return@LaunchedEffect
        androidx.compose.runtime.snapshotFlow {
            val visibleItems = listState.layoutInfo.visibleItemsInfo
            val start = visibleItems.firstOrNull()?.index ?: 0
            val end = visibleItems.lastOrNull()?.index ?: start
            start to end
        }
            .debounce(120)
            .distinctUntilChanged()
            .collectLatest { (start, end) ->
                val nearbyRequests = buildList {
                    val beforeStart = (start - PRELOAD_GROUP_OFFSET).coerceAtLeast(0)
                    for (index in beforeStart until start.coerceAtMost(preloadRequestGroups.size)) {
                        addAll(preloadRequestGroups[index])
                    }
                    val afterStart = (end + 1).coerceAtLeast(0)
                    val afterEnd = (end + 1 + PRELOAD_GROUP_OFFSET).coerceAtMost(preloadRequestGroups.size)
                    for (index in afterStart until afterEnd) {
                        addAll(preloadRequestGroups[index])
                    }
                }.distinctBy { it.cacheKey }
                if (nearbyRequests.isNotEmpty()) {
                    ThumbnailUtils.preloadVisibleRange(
                        context = context,
                        requests = nearbyRequests,
                        visibleStart = 0,
                        visibleEnd = nearbyRequests.lastIndex,
                        preloadOffset = 0
                    )
                }
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
                .let { base ->
                    if (useLiquidGlass && topBackdrop != null) {
                        base.layerBackdrop(topBackdrop)
                    } else {
                        base
                    }
                }
        ) {
            Box(modifier = Modifier.fillMaxSize()) {
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
                        visibleGroups,
                        key = { _, item -> item.stableId }
                    ) { index, group ->
                        val selected = selectedPositions.contains(index)
                        val key = group.stableId

                        ReorderableItem(reorderableState, key = key) { isDragging ->
                            val itemShape = RoundedCornerShape(28.dp)
                            AboutGroupItem(
                            modifier = Modifier.fillMaxWidth()
                                .let { base ->
                                    if (isDragging) {
                                        base.graphicsLayer {
                                            scaleX = 1.05f
                                            scaleY = 1.05f
                                            shadowElevation = 8.dp.toPx()
                                            shape = itemShape
                                            clip = true
                                        }
                                    } else {
                                        base
                                    }
                                }
                                .let { base ->
                                    if (sortMode == MainWallpaperSortMode.Custom && kindFilters.isEmpty()) {
                                        base.longPressDraggableHandle(
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
                                    } else {
                                        base
                                    }
                                }
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
                                    markGroupOpened(group)
                                    pendingOverwriteGroup = group.saveData
                                }
                            }
                            )
                        }
                    }
                }
            }
        }

        ProgressiveBlurContent(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .fillMaxWidth()
                .zIndex(2f),
            backdrop = topBackdrop,
            isLightTheme = isLightTheme
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
                    Toast.makeText(context, "分组数据无效，覆盖失败", Toast.LENGTH_SHORT).show()
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
            val isAllSelected = selectedPositions.size == visibleGroups.size && visibleGroups.isNotEmpty()
            com.zeaze.tianyinwallpaper.ui.main.SelectionTopBar(
                modifier = Modifier.zIndex(3f),
                statusBarTopPaddingDp = statusBarTopPaddingDp,
                enableLiquidGlass = useLiquidGlass,
                backdrop = topBackdrop,
                isAllSelected = isAllSelected,
                isLightTheme = !useDarkTheme,
                onCancelSelect = { exitSelectionMode() },
                onDelete = { deleteSelectedGroups() },

                onToggleSelectAll = {
                    if (isAllSelected) {
                        selectedPositions.clear()
                    } else {
                        selectedPositions.clear()
                        visibleGroups.indices.forEach { selectedPositions.add(it) }
                    }
                }
            )
        } else if (showBackButton) {
            Row(
                modifier = Modifier
                    .zIndex(3f)
                    .fillMaxWidth()
                    .padding(top = statusBarTopPaddingDp + 12.dp, start = 12.dp, end = 12.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                val isDark = useDarkTheme
                val textColor = if (isDark) Color.White else Color.Black

                if (showBackButton) {
                    AboutTopIconButton(
                        iconRes = R.drawable.back,
                        onClick = onBack,
                        enableLiquidGlass = useLiquidGlass,
                        backdrop = topBackdrop,
                        surfaceColor = topSurfaceColor,
                        isDark = isDark,
                        textColor = textColor
                    )
                }

                AboutSortFilterButtonGroup(
                    onSortClick = onSortClick,
                    onFilterClick = onFilterClick,
                    enableLiquidGlass = useLiquidGlass,
                    backdrop = topBackdrop,
                    surfaceColor = topSurfaceColor,
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
private fun AboutTopIconButton(
    iconRes: Int,
    onClick: () -> Unit,
    enableLiquidGlass: Boolean,
    backdrop: Backdrop?,
    surfaceColor: Color,
    isDark: Boolean,
    textColor: Color
) {
    if (enableLiquidGlass && backdrop != null) {
        LiquidButton(
            onClick = onClick,
            backdrop = backdrop,
            modifier = Modifier.size(AboutTopButtonSize),
            surfaceColor = surfaceColor,
            buttonHeight = AboutTopButtonSize,
            contentPadding = PaddingValues(0.dp),
            iconRes = iconRes,
            iconContentDescription = null,
            iconSize = 20.dp,
            iconTint = textColor
        )
    } else {
        Surface(
            modifier = Modifier
                .size(AboutTopButtonSize)
                .clickable { onClick() },
            shape = CircleShape,
            color = PlainFallbackStyle.surface(isLightTheme = !isDark),
            border = PlainFallbackStyle.border(isLightTheme = !isDark)
        ) {
            Box(contentAlignment = Alignment.Center) {
                Image(
                    painter = painterResource(iconRes),
                    contentDescription = null,
                    colorFilter = ColorFilter.tint(textColor),
                    modifier = Modifier.size(20.dp)
                )
            }
        }
    }
}

@Composable
private fun AboutSortFilterButtonGroup(
    onSortClick: () -> Unit,
    onFilterClick: () -> Unit,
    enableLiquidGlass: Boolean,
    backdrop: Backdrop?,
    surfaceColor: Color,
    isDark: Boolean,
    textColor: Color
) {
    if (enableLiquidGlass && backdrop != null) {
        LiquidButtonGroup(
            buttonCount = 2,
            onButtonClick = { index -> if (index == 0) onSortClick() else onFilterClick() },
            backdrop = backdrop,
            surfaceColor = surfaceColor,
            buttonHeight = AboutTopButtonSize,
            buttonWidth = AboutTopButtonSize,
            contentPadding = PaddingValues(0.dp),
            iconRes = { index -> if (index == 0) R.drawable.sort else R.drawable.fliter },
            iconContentDescription = { index -> if (index == 0) "排序" else "筛选" },
            iconSize = { 20.dp },
            iconTint = { textColor }
        )
    } else {
        Surface(
            modifier = Modifier
                .height(AboutTopButtonSize)
                .width(AboutTopButtonSize * 2f),
            shape = Capsule(),
            color = PlainFallbackStyle.surface(isLightTheme = !isDark),
            border = PlainFallbackStyle.border(isLightTheme = !isDark)
        ) {
            Row(
                modifier = Modifier.fillMaxSize(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                repeat(2) { index ->
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight()
                            .clickable(onClick = if (index == 0) onSortClick else onFilterClick),
                        contentAlignment = Alignment.Center
                    ) {
                        Image(
                            painter = painterResource(if (index == 0) R.drawable.sort else R.drawable.fliter),
                            contentDescription = null,
                            colorFilter = ColorFilter.tint(textColor),
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
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
                    text = data.name ?: "未命名分组",
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
    val request = remember(model) { model?.toThumbnailRequest() }
    val bitmap by produceState<Bitmap?>(
        initialValue = request?.let { ThumbnailUtils.getFromCache(it) },
        request?.cacheKey
    ) {
        if (request != null && value == null) {
            value = loadPreviewBitmap(context, request)
        }
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

private suspend fun loadPreviewBitmap(context: Context, request: ThumbnailUtils.Request?): Bitmap? {
    if (request == null) return null
    return withContext(Dispatchers.IO) {
        ThumbnailUtils.loadThumbnail(context, request)
    }
}

private fun buildPreloadRequestGroups(
    groups: List<AboutGroupUiModel>
): List<List<ThumbnailUtils.Request>> {
    return groups.map { group ->
        group.wallpapers.take(PREVIEW_COUNT)
            .map { model -> model.toThumbnailRequest(priority = ThumbnailUtils.Request.PRIORITY_HIGH) }
            .distinctBy { it.cacheKey }
    }
}

private fun TianYinWallpaperModel.toThumbnailRequest(
    priority: Int = ThumbnailUtils.Request.PRIORITY_NORMAL
): ThumbnailUtils.Request {
    return ThumbnailUtils.requestForWallpaper(this, priority)
}

private const val ITEM_PREVIEW_HEIGHT_DP = 100
private const val ITEM_PREVIEW_TOP_MARGIN_DP = 8
private const val ITEM_PREVIEW_SPACING_DP = 6
private const val PREVIEW_COUNT = 5
private const val PRELOAD_GROUP_OFFSET = 2
