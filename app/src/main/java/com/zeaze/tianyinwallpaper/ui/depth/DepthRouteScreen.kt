package com.zeaze.tianyinwallpaper.ui.depth

import android.app.Activity
import android.app.WallpaperManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.net.Uri
import android.os.Build
import android.provider.OpenableColumns
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.material.CircularProgressIndicator
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Slider
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
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.zIndex
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import com.kyant.shapes.Capsule
import com.kyant.shapes.RoundedRectangle
import com.zeaze.tianyinwallpaper.App
import com.zeaze.tianyinwallpaper.backdrop.Backdrop
import com.zeaze.tianyinwallpaper.backdrop.backdrops.rememberCanvasBackdrop
import com.zeaze.tianyinwallpaper.backdrop.backdrops.rememberLayerBackdrop
import com.zeaze.tianyinwallpaper.backdrop.backdrops.layerBackdrop
import com.zeaze.tianyinwallpaper.backdrop.drawBackdrop
import com.zeaze.tianyinwallpaper.backdrop.effects.blur
import com.zeaze.tianyinwallpaper.backdrop.effects.colorControls
import com.zeaze.tianyinwallpaper.backdrop.effects.lens
import com.zeaze.tianyinwallpaper.backdrop.highlight.Highlight
import com.zeaze.tianyinwallpaper.base.rxbus.RxBus
import com.zeaze.tianyinwallpaper.base.rxbus.RxConstants
import com.zeaze.tianyinwallpaper.catalog.components.LiquidButton
import com.zeaze.tianyinwallpaper.catalog.components.LiquidSlider
import com.zeaze.tianyinwallpaper.catalog.utils.rememberMultiRegionLuminanceSampler
import com.zeaze.tianyinwallpaper.catalog.utils.rememberRegionLuminanceState
import com.zeaze.tianyinwallpaper.model.DepthWallpaperModel
import com.zeaze.tianyinwallpaper.service.DepthWallpaperService
import com.zeaze.tianyinwallpaper.ui.commom.LiquidWindowAnimatedVisibility
import com.zeaze.tianyinwallpaper.ui.commom.LiquidWindowAnimationMode
import com.zeaze.tianyinwallpaper.ui.main.SelectionBarState
import com.zeaze.tianyinwallpaper.utils.DepthPrefs
import com.zeaze.tianyinwallpaper.utils.GaussianSceneLoader
import com.zeaze.tianyinwallpaper.utils.GradioMcpSogGenerator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyGridState
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import kotlin.math.roundToInt

private enum class DepthAddKind {
    Gaussian,
    OnlineGaussian
}

private const val DEPTH_BLUR_DISABLED = 0f
private const val GAUSSIAN_MIN_SPLAT_BUDGET = 500_000
private const val GAUSSIAN_FAST_SPLAT_BUDGET = 800_000
private const val GAUSSIAN_FULL_SPLAT_BUDGET = 1_500_000
private const val GAUSSIAN_SPLAT_BUDGET_STEP = 100_000
private const val HOLD_SELECT_TIMEOUT_MS = 500L

@Composable
fun DepthRouteScreen(
    useDarkTheme: Boolean,
    onBottomBarVisibleChange: (Boolean) -> Unit
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val haptic = LocalHapticFeedback.current
    val viewConfiguration = LocalViewConfiguration.current
    val activity = context as? Activity
    val pref = remember(context) { context.getSharedPreferences(App.TIANYIN, Context.MODE_PRIVATE) }
    val wallpapers = remember { mutableStateListOf<DepthWallpaperModel>() }
    val selectedIds = remember { mutableStateListOf<String>() }
    val isLightTheme = !useDarkTheme
    val contentColor = if (isLightTheme) Color.Black else Color.White
    val accentColor = if (isLightTheme) Color(0xFF0088FF) else Color(0xFF0091FF)
    val cardBackground = if (isLightTheme) Color(0xFFE9E9EF) else Color(0xFF202020)
    val containerColor = if (isLightTheme) Color(0xFFFAFAFA).copy(0.6f) else Color(0xFF121212).copy(0.4f)
    val enableLiquidGlass = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
    val liquidBackdrop = if (enableLiquidGlass) rememberLayerBackdrop() else null

    val density = LocalDensity.current
    val statusBarTopPadding = remember(context) {
        val id = context.resources.getIdentifier("status_bar_height", "dimen", "android")
        if (id > 0) context.resources.getDimensionPixelSize(id) else 0
    }
    val statusBarTopPaddingDp = with(density) { statusBarTopPadding.toDp() }

    var activeId by remember { mutableStateOf(pref.getString(DepthPrefs.PREF_DEPTH_ACTIVE_ID, null)) }
    var selectedId by remember { mutableStateOf(activeId) }
    var selectionMode by remember { mutableStateOf(false) }
    var showAddDialog by remember { mutableStateOf(false) }
    var pendingAddKind by remember { mutableStateOf(DepthAddKind.Gaussian) }
    var pendingReplaceId by remember { mutableStateOf<String?>(null) }
    var previewModel by remember { mutableStateOf<DepthWallpaperModel?>(null) }
    var showPermissionDialog by remember { mutableStateOf(false) }
    var showOnlineGenerateDialog by remember { mutableStateOf(false) }
    var onlineImageUrl by remember { mutableStateOf("") }
    var onlineGenerationBusy by remember { mutableStateOf(false) }

    fun DepthWallpaperModel.normalizedDepthParams(): DepthWallpaperModel {
        return copy(
            gaussianRenderMode = if (gaussianRenderMode == "web") "web" else "native",
            cameraZoom = if (cameraZoom > 0f) cameraZoom.coerceIn(0.6f, 10f) else 1f,
            centerOffsetX = centerOffsetX.coerceIn(-2.5f, 2.5f),
            centerOffsetY = centerOffsetY.coerceIn(-2.5f, 2.5f),
            focusDepth = if (focusDepth.isFinite()) focusDepth.coerceIn(-1f, 1f) else 0.25f,
            gaussianMaxSplats = gaussianMaxSplats.coerceIn(
                GAUSSIAN_MIN_SPLAT_BUDGET,
                GAUSSIAN_FULL_SPLAT_BUDGET
            ),
            blurStrength = DEPTH_BLUR_DISABLED
        )
    }

    fun publishSelectionState() {
        val isAllSelected = wallpapers.isNotEmpty() && selectedIds.size == wallpapers.size
        RxBus.postWithCode(
            RxConstants.RX_DEPTH_SELECTION_MODE_CHANGED,
            SelectionBarState(selectionMode, isAllSelected)
        )
    }

    fun loadWallpapers() {
        val parsed = DepthPrefs.loadWallpapers(pref).map { it.normalizedDepthParams() }
        wallpapers.clear()
        wallpapers.addAll(parsed)
        if (activeId != null && wallpapers.none { it.id == activeId }) {
            activeId = null
        }
        if (selectedId == null || wallpapers.none { it.id == selectedId }) {
            selectedId = activeId ?: wallpapers.firstOrNull()?.id
        }
        selectedIds.removeAll { id -> wallpapers.none { it.id == id } }
        if (selectionMode && wallpapers.isEmpty()) {
            selectionMode = false
        }
        DepthPrefs.saveWallpapers(pref, wallpapers.toList())
        publishSelectionState()
    }

    fun saveWallpapers() {
        DepthPrefs.saveWallpapers(pref, wallpapers.toList())
    }

    fun enterSelectionMode(initialId: String? = null) {
        if (!selectionMode) {
            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
        }
        selectionMode = true
        selectedIds.clear()
        if (initialId != null) selectedIds.add(initialId)
        publishSelectionState()
    }

    fun exitSelectionMode() {
        selectionMode = false
        selectedIds.clear()
        publishSelectionState()
    }

    fun persistActiveWallpaperId(id: String?) {
        activeId = id
        if (id == null) {
            pref.edit().remove(DepthPrefs.PREF_DEPTH_ACTIVE_ID).apply()
        } else {
            DepthPrefs.setActiveWallpaperId(pref, id)
        }
    }

    fun takeReadPermission(uri: Uri) {
        runCatching {
            context.contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
        }
    }

    fun addWallpaper(uri: Uri, kind: DepthAddKind) {
        takeReadPermission(uri)
        val displayName = queryDisplayName(context, uri).orEmpty()
        val replaceId = pendingReplaceId
        if (replaceId != null) {
            val index = wallpapers.indexOfFirst { it.id == replaceId }
            if (index >= 0) {
                val old = previewModel?.takeIf { it.id == replaceId } ?: wallpapers[index]
                val next = old.copy(
                    gaussianUri = uri.toString(),
                    displayName = displayName.ifBlank { uri.lastPathSegment.orEmpty() },
                    blurStrength = DEPTH_BLUR_DISABLED
                ).normalizedDepthParams()
                selectedId = next.id
                previewModel = next
                pendingReplaceId = null
                return
            }
            pendingReplaceId = null
        }
        val model = DepthWallpaperModel(
            id = UUID.randomUUID().toString(),
            gaussianUri = uri.toString(),
            displayName = displayName.ifBlank { uri.lastPathSegment.orEmpty() },
            createdAt = System.currentTimeMillis(),
            sensorSensitivity = 9f,
            parallaxStrength = 0.075f,
            gaussianRenderMode = "native",
            cameraZoom = 1f,
            centerOffsetX = 0f,
            centerOffsetY = 0f,
            focusDepth = 0.25f,
            gaussianMaxSplats = GAUSSIAN_FAST_SPLAT_BUDGET,
            blurStrength = DEPTH_BLUR_DISABLED
        ).normalizedDepthParams()
        wallpapers.add(0, model)
        selectedId = model.id
        previewModel = model
        saveWallpapers()
    }

    fun activeWallpaper(): DepthWallpaperModel? {
        return wallpapers.firstOrNull { it.id == activeId }
    }

    fun selectedWallpaper(): DepthWallpaperModel? {
        return wallpapers.firstOrNull { it.id == selectedId } ?: activeWallpaper() ?: wallpapers.firstOrNull()
    }

    val wallpaperLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            Toast.makeText(context, "Depth wallpaper applied", Toast.LENGTH_SHORT).show()
        } else if (!pref.getBoolean("hide_permission_dialog", false)) {
            showPermissionDialog = true
        }
    }

    fun applyDepthWallpaper(targetModel: DepthWallpaperModel? = null) {
        val target = targetModel ?: selectedWallpaper()
        if (target == null) {
            Toast.makeText(context, "Add a depth wallpaper first", Toast.LENGTH_SHORT).show()
            return
        }
        val hostActivity = activity ?: return
        runCatching {
            WallpaperManager.getInstance(hostActivity).clear()
        }.onFailure {
            android.util.Log.w("DepthRouteScreen", "Clear wallpaper failed before applying depth wallpaper", it)
        }
        saveWallpapers()
        persistActiveWallpaperId(target.id)
        val intent = Intent(WallpaperManager.ACTION_CHANGE_LIVE_WALLPAPER).apply {
            putExtra(
                WallpaperManager.EXTRA_LIVE_WALLPAPER_COMPONENT,
                ComponentName(hostActivity, DepthWallpaperService::class.java)
            )
        }
        wallpaperLauncher.launch(intent)
    }

    val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            val kind = pendingAddKind
            coroutineScope.launch {
                Toast.makeText(context, "Loading Gaussian SOG", Toast.LENGTH_SHORT).show()
                val result = withContext(Dispatchers.IO) {
                    GaussianSceneLoader.loadSceneDetailed(
                        context = context.applicationContext,
                        uriString = uri.toString()
                    )
                }
                if (result.scene != null) {
                    addWallpaper(uri, kind)
                } else {
                    Toast.makeText(
                        context,
                        result.error?.takeIf { it.isNotBlank() } ?: "Gaussian SOG load failed",
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        }
    }

    fun launchAdd(kind: DepthAddKind) {
        pendingReplaceId = null
        pendingAddKind = kind
        showAddDialog = false
        if (kind == DepthAddKind.OnlineGaussian) {
            showOnlineGenerateDialog = true
            return
        }
        filePicker.launch(arrayOf("*/*"))
    }

    fun generateOnlineGaussian() {
        val imageUrl = onlineImageUrl.trim()
        if (imageUrl.isBlank() || onlineGenerationBusy) return
        onlineGenerationBusy = true
        coroutineScope.launch {
            Toast.makeText(context, "Generating SOG", Toast.LENGTH_SHORT).show()
            val uri = withContext(Dispatchers.IO) {
                runCatching {
                    GradioMcpSogGenerator.generateFromImageUrl(
                        context = context.applicationContext,
                        imageUrl = imageUrl,
                        focalLength35mm = 0f
                    )
                }
            }
            uri.onSuccess { generatedUri ->
                val result = withContext(Dispatchers.IO) {
                    GaussianSceneLoader.loadSceneDetailed(
                        context = context.applicationContext,
                        uriString = generatedUri.toString()
                    )
                }
                if (result.scene != null) {
                    showOnlineGenerateDialog = false
                    onlineImageUrl = ""
                    addWallpaper(generatedUri, DepthAddKind.Gaussian)
                } else {
                    Toast.makeText(
                        context,
                        result.error?.takeIf { it.isNotBlank() } ?: "Generated SOG load failed",
                        Toast.LENGTH_LONG
                    ).show()
                }
            }.onFailure {
                Toast.makeText(context, it.message ?: "Online SOG generation failed", Toast.LENGTH_LONG).show()
            }
            onlineGenerationBusy = false
        }
    }

    fun replacePreview(kind: DepthAddKind) {
        val target = previewModel ?: return
        pendingReplaceId = target.id
        pendingAddKind = kind
        filePicker.launch(arrayOf("*/*"))
    }

    fun removeWallpaper(model: DepthWallpaperModel) {
        wallpapers.removeAll { it.id == model.id }
        removeGaussianThumbnailCache(context, model)
        selectedIds.remove(model.id)
        if (activeId == model.id) {
            persistActiveWallpaperId(null)
        }
        if (selectedId == model.id) selectedId = activeId ?: wallpapers.firstOrNull()?.id
        if (previewModel?.id == model.id) {
            previewModel = null
        }
        if (wallpapers.isEmpty()) exitSelectionMode()
        saveWallpapers()
        publishSelectionState()
    }

    fun removeSelectedWallpapers() {
        if (selectedIds.isEmpty()) {
            Toast.makeText(context, "请选择景深壁纸", Toast.LENGTH_SHORT).show()
            return
        }
        val ids = selectedIds.toSet()
        wallpapers.filter { it.id in ids }.forEach { removeGaussianThumbnailCache(context, it) }
        wallpapers.removeAll { it.id in ids }
        if (activeId != null && ids.contains(activeId)) {
            persistActiveWallpaperId(null)
        }
        if (selectedId != null && ids.contains(selectedId)) {
            selectedId = activeId ?: wallpapers.firstOrNull()?.id
        }
        if (previewModel?.id?.let { ids.contains(it) } == true) {
            previewModel = null
        }
        selectionMode = false
        selectedIds.clear()
        saveWallpapers()
        publishSelectionState()
    }

    fun updateWallpaper(model: DepthWallpaperModel) {
        val normalized = model.normalizedDepthParams()
        val index = wallpapers.indexOfFirst { it.id == model.id }
        if (index >= 0) {
            wallpapers[index] = normalized
        }
        if (previewModel?.id == model.id) {
            previewModel = normalized
        }
        if (selectedId == normalized.id) {
            selectedId = normalized.id
        }
        saveWallpapers()
    }

    fun commitWallpaperDraft(model: DepthWallpaperModel): DepthWallpaperModel {
        val normalized = model.normalizedDepthParams()
        val index = wallpapers.indexOfFirst { it.id == normalized.id }
        if (index >= 0) {
            wallpapers[index] = normalized
        }
        selectedId = normalized.id
        previewModel = normalized
        saveWallpapers()
        return normalized
    }

    LaunchedEffect(Unit) {
        loadWallpapers()
    }

    LaunchedEffect(previewModel, selectionMode) {
        onBottomBarVisibleChange(previewModel == null && !selectionMode)
        publishSelectionState()
    }

    DisposableEffect(Unit) {
        val triggerAdd = RxBus.getDefault()
            .toObservableWithCode(RxConstants.RX_TRIGGER_ADD_DEPTH, Unit::class.java)
            .subscribe {
                pendingReplaceId = null
                showAddDialog = true
            }
        val triggerApply = RxBus.getDefault()
            .toObservableWithCode(RxConstants.RX_TRIGGER_APPLY_DEPTH, Unit::class.java)
            .subscribe { applyDepthWallpaper() }
        val triggerPreview = RxBus.getDefault()
            .toObservableWithCode(RxConstants.RX_TRIGGER_PREVIEW_DEPTH, Unit::class.java)
            .subscribe { previewModel = selectedWallpaper()?.normalizedDepthParams() }
        val triggerSelect = RxBus.getDefault()
            .toObservableWithCode(RxConstants.RX_TRIGGER_ENTER_DEPTH_SELECT_MODE, Unit::class.java)
            .subscribe { enterSelectionMode() }
        val selectionCancel = RxBus.getDefault()
            .toObservableWithCode(RxConstants.RX_DEPTH_SELECTION_CANCEL, Unit::class.java)
            .subscribe { exitSelectionMode() }
        val selectionDelete = RxBus.getDefault()
            .toObservableWithCode(RxConstants.RX_DEPTH_SELECTION_DELETE, Unit::class.java)
            .subscribe { removeSelectedWallpapers() }
        val selectionToggleAll = RxBus.getDefault()
            .toObservableWithCode(RxConstants.RX_DEPTH_SELECTION_TOGGLE_ALL, Unit::class.java)
            .subscribe {
                val isAllSelected = wallpapers.isNotEmpty() && selectedIds.size == wallpapers.size
                selectedIds.clear()
                if (!isAllSelected) {
                    selectedIds.addAll(wallpapers.map { it.id })
                }
                selectionMode = true
                publishSelectionState()
            }
        onDispose {
            triggerAdd.dispose()
            triggerApply.dispose()
            triggerPreview.dispose()
            triggerSelect.dispose()
            selectionCancel.dispose()
            selectionDelete.dispose()
            selectionToggleAll.dispose()
            RxBus.postWithCode(RxConstants.RX_DEPTH_SELECTION_MODE_CHANGED, SelectionBarState(false, false))
            onBottomBarVisibleChange(true)
        }
    }

    BackHandler(enabled = selectionMode && previewModel == null) {
        exitSelectionMode()
    }

    BackHandler(enabled = previewModel != null) {
        previewModel = null
    }

    val gridState = rememberLazyGridState()
    val reorderableState = rememberReorderableLazyGridState(
        lazyGridState = gridState,
        onMove = { from, to ->
            if (from.index == to.index) return@rememberReorderableLazyGridState
            val item = wallpapers.removeAt(from.index)
            wallpapers.add(to.index, item)
            saveWallpapers()
        }
    )

    Box(Modifier.fillMaxSize().background(MaterialTheme.colors.background).let { m ->
        if (enableLiquidGlass && liquidBackdrop != null) m.layerBackdrop(liquidBackdrop) else m
    }) {
        LazyVerticalGrid(
            columns = GridCells.Fixed(3),
            state = gridState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                start = 12.dp,
                end = 12.dp,
                top = statusBarTopPaddingDp + 76.dp,
                bottom = if (selectionMode) 90.dp else 110.dp
            ),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            itemsIndexed(wallpapers, key = { _, model -> model.id }) { _, model ->
                val selected = selectedIds.contains(model.id)
                ReorderableItem(reorderableState, key = model.id) { isDragging ->
                    DepthWallpaperCard(
                        modifier = Modifier
                            .longPressDraggableHandle()
                            .pointerInput(model.id, selectionMode) {
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
                                        enterSelectionMode(model.id)
                                    }
                                }
                            },
                        model = model,
                        selected = selected,
                        isDragging = isDragging,
                        showRemoveButton = selectionMode,
                        cardBackground = cardBackground,
                        onClick = {
                            if (selectionMode) {
                                if (selected) selectedIds.remove(model.id) else selectedIds.add(model.id)
                                publishSelectionState()
                            } else {
                                selectedId = model.id
                                previewModel = model.normalizedDepthParams()
                            }
                        },
                        onDelete = { removeWallpaper(model) }
                    )
                }
            }
        }

        if (wallpapers.isEmpty()) {
            Text(
                text = "Tap + to add a Gaussian wallpaper",
                color = contentColor.copy(alpha = 0.7f),
                modifier = Modifier.align(Alignment.Center)
            )
        }

        if (showAddDialog) {
            DepthAddDialog(
                accentColor = accentColor,
                contentColor = contentColor,
                containerColor = containerColor,
                liquidBackdrop = liquidBackdrop,
                onDismiss = { showAddDialog = false },
                onPick = { launchAdd(it) }
            )
        }

        if (showOnlineGenerateDialog) {
            DepthOnlineGenerateDialog(
                imageUrl = onlineImageUrl,
                busy = onlineGenerationBusy,
                accentColor = accentColor,
                contentColor = contentColor,
                containerColor = containerColor,
                onImageUrlChange = { onlineImageUrl = it },
                onGenerate = { generateOnlineGaussian() },
                onDismiss = {
                    if (!onlineGenerationBusy) showOnlineGenerateDialog = false
                }
            )
        }

        if (showPermissionDialog) {
            Dialog(onDismissRequest = { showPermissionDialog = false }) {
                Column(
                    modifier = Modifier
                        .clip(RoundedCornerShape(24.dp))
                        .background(MaterialTheme.colors.surface)
                        .padding(18.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text("Wallpaper permission was not granted", color = contentColor, fontWeight = FontWeight.Bold)
                    Text(
                        "Use the system wallpaper screen to apply the depth wallpaper.",
                        color = contentColor.copy(alpha = 0.7f),
                        fontSize = 14.sp
                    )
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(44.dp)
                            .clip(Capsule())
                            .background(accentColor)
                            .clickable { showPermissionDialog = false },
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("OK", color = Color.White)
                    }
                }
            }
        }

        previewModel?.let { model ->
            DepthPreviewOverlay(
                model = model,
                statusBarTopPaddingDp = statusBarTopPaddingDp,
                contentColor = contentColor,
                accentColor = accentColor,
                containerColor = containerColor,
                enableLiquidGlass = enableLiquidGlass,
                onDismiss = { previewModel = null },
                onApply = {
                    val committed = commitWallpaperDraft(model)
                    applyDepthWallpaper(committed)
                },
                onModelChange = { previewModel = it.normalizedDepthParams() },
                modifier = Modifier
                    .fillMaxSize()
                    .zIndex(4f)
            )
        }
    }
}

@Composable
private fun DepthPreviewOverlay(
    model: DepthWallpaperModel,
    statusBarTopPaddingDp: androidx.compose.ui.unit.Dp,
    contentColor: Color,
    accentColor: Color,
    containerColor: Color,
    enableLiquidGlass: Boolean,
    onDismiss: () -> Unit,
    onApply: () -> Unit,
    onModelChange: (DepthWallpaperModel) -> Unit,
    modifier: Modifier = Modifier
) {
    val density = LocalDensity.current
    val isLightTheme = MaterialTheme.colors.isLight
    val pillBackground = if (MaterialTheme.colors.isLight) Color(0x22FFFFFF) else Color(0x55222222)
    val detailBackdrop = if (enableLiquidGlass) rememberLayerBackdrop() else null
    val coroutineScope = rememberCoroutineScope()
    val animatedOffset = remember { Animatable(0f) }
    val dismissThreshold = with(density) { 200.dp.toPx() }
    val bottomActionPadding =
        with(density) { WindowInsets.navigationBars.getBottom(this).toDp() } + 24.dp
    val sheetOuterBottomPadding =
        with(density) { WindowInsets.navigationBars.getBottom(this).toDp() } + 16.dp
    var previewLoading by remember(model.id, model.gaussianUri, model.gaussianRenderMode, model.gaussianMaxSplats) {
        mutableStateOf(true)
    }
    var showParamPanel by remember(model.id) { mutableStateOf(false) }

    val luminanceRegions = remember {
        mapOf(
            "cancel" to Rect(0f, 0f, 0.18f, 0.1f),
            "apply" to Rect(0.82f, 0f, 1f, 0.1f),
            "gaussian" to Rect(0.36f, 0.9f, 0.64f, 1f)
        )
    }
    val luminanceSampler = if (detailBackdrop != null) {
        rememberMultiRegionLuminanceSampler(
            enabled = true,
            sampleLayer = detailBackdrop.graphicsLayer,
            regions = luminanceRegions,
            sampleIntervalMs = 200L
        )
    } else {
        null
    }
    val cancelLuminanceState = luminanceSampler?.let { rememberRegionLuminanceState(it, "cancel") }
    val applyLuminanceState = luminanceSampler?.let { rememberRegionLuminanceState(it, "apply") }
    val gaussianLuminanceState = luminanceSampler?.let { rememberRegionLuminanceState(it, "gaussian") }

    fun closeWithAnimation() {
        coroutineScope.launch {
            animatedOffset.animateTo(2000f, animationSpec = tween(250))
            onDismiss()
        }
    }

    fun closeParamPanel() {
        coroutineScope.launch {
            animatedOffset.animateTo(2000f, animationSpec = tween(200))
            showParamPanel = false
        }
    }

    LaunchedEffect(showParamPanel) {
        if (showParamPanel) {
            animatedOffset.snapTo(0f)
        }
    }

    BackHandler(enabled = showParamPanel) {
        closeParamPanel()
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black)
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .let { m ->
                    if (detailBackdrop != null) m.layerBackdrop(detailBackdrop) else m
                }
        ) {
            DepthPreviewView(
                model = model,
                onModelChange = onModelChange,
                onLoadingChanged = { previewLoading = it },
                modifier = Modifier.fillMaxSize()
            )
        }

        if (previewLoading) {
            Column(
                modifier = Modifier.align(Alignment.Center),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                CircularProgressIndicator(color = Color.White, strokeWidth = 2.dp)
                Text(
                    text = "正在加载",
                    color = Color.White.copy(alpha = 0.82f),
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium
                )
            }
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, end = 16.dp, top = statusBarTopPaddingDp + 12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (detailBackdrop != null) {
                LiquidButton(
                    onClick = { closeWithAnimation() },
                    backdrop = detailBackdrop,
                    surfaceColor = pillBackground,
                    luminanceState = cancelLuminanceState,
                    modifier = Modifier.height(44.dp)
                ) {
                    BasicText(
                        "取消",
                        modifier = Modifier.padding(horizontal = 14.dp),
                        style = TextStyle(
                            color = cancelLuminanceState?.contentColor ?: contentColor,
                            fontSize = 15.sp
                        )
                    )
                }
                LiquidButton(
                    onClick = { if (!previewLoading) onApply() },
                    backdrop = detailBackdrop,
                    surfaceColor = if (previewLoading) Color.Gray.copy(alpha = 0.5f)
                    else accentColor.copy(alpha = 0.75f),
                    tint = if (previewLoading) Color.Unspecified else accentColor,
                    luminanceState = applyLuminanceState,
                    modifier = Modifier
                        .height(44.dp)
                        .graphicsLayer { alpha = if (previewLoading) 0.5f else 1f }
                ) {
                    BasicText(
                        "应用",
                        modifier = Modifier.padding(horizontal = 14.dp),
                        style = TextStyle(
                            color = if (previewLoading) Color.White.copy(alpha = 0.5f) else Color.White,
                            fontSize = 15.sp
                        )
                    )
                }
            } else {
                DepthPreviewPill(
                    text = "取消",
                    color = contentColor,
                    background = pillBackground,
                    onClick = { closeWithAnimation() }
                )
                DepthPreviewPill(
                    text = "应用",
                    color = if (previewLoading) Color.White.copy(alpha = 0.5f) else Color.White,
                    background = if (previewLoading) Color.Gray.copy(alpha = 0.3f) else Color(0x662A83FF),
                    onClick = { if (!previewLoading) onApply() }
                )
            }
        }

        Box(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = bottomActionPadding)
        ) {
            if (detailBackdrop != null) {
                LiquidButton(
                    onClick = { showParamPanel = true },
                    backdrop = detailBackdrop,
                    surfaceColor = accentColor.copy(alpha = 0.75f),
                    tint = accentColor,
                    luminanceState = gaussianLuminanceState,
                    modifier = Modifier.height(44.dp)
                ) {
                    BasicText(
                        "参数调节",
                        modifier = Modifier.padding(horizontal = 14.dp),
                        style = TextStyle(Color.White, 15.sp)
                    )
                }
            } else {
                DepthPreviewKindPill(
                    text = "参数调节",
                    selected = true,
                    contentColor = contentColor,
                    accentColor = accentColor,
                    onClick = { showParamPanel = true }
                )
            }
        }

        if (showParamPanel) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .clickable { closeParamPanel() }
            )
        }

        LiquidWindowAnimatedVisibility(
            visible = showParamPanel,
            mode = LiquidWindowAnimationMode.BottomSheet,
            label = "DepthParamSheet",
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(start = 16.dp, end = 16.dp, bottom = sheetOuterBottomPadding)
        ) {
            DepthParamPanel(
                model = model,
                onModelChange = onModelChange,
                backdrop = detailBackdrop,
                containerColor = containerColor,
                contentColor = contentColor,
                accentColor = accentColor,
                isLightTheme = isLightTheme,
                sheetOffset = animatedOffset.value,
                onDrag = { dragY ->
                    coroutineScope.launch {
                        animatedOffset.snapTo((animatedOffset.value + dragY).coerceAtLeast(0f))
                    }
                },
                onDragEnd = {
                    coroutineScope.launch {
                        if (animatedOffset.value > dismissThreshold) {
                            animatedOffset.animateTo(2000f, animationSpec = tween(200))
                            showParamPanel = false
                        } else {
                            animatedOffset.animateTo(
                                0f,
                                animationSpec = spring(
                                    dampingRatio = Spring.DampingRatioNoBouncy,
                                    stiffness = Spring.StiffnessMedium
                                )
                            )
                        }
                    }
                },
                modifier = Modifier
            )
        }
    }
}

@Composable
private fun DepthParamPanel(
    model: DepthWallpaperModel,
    onModelChange: (DepthWallpaperModel) -> Unit,
    backdrop: Backdrop?,
    containerColor: Color,
    contentColor: Color,
    accentColor: Color,
    isLightTheme: Boolean,
    sheetOffset: Float,
    onDrag: (Float) -> Unit,
    onDragEnd: () -> Unit,
    modifier: Modifier = Modifier
) {
    var isSliderInteracting by remember { mutableStateOf(false) }
    val scrollState = rememberScrollState()
    val disableScrollConnection = remember(isSliderInteracting) {
        object : NestedScrollConnection {
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                return if (isSliderInteracting) available else Offset.Zero
            }
        }
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(max = 520.dp)
            .offset { IntOffset(0, sheetOffset.roundToInt()) }
            .then(
                if (backdrop != null) {
                    Modifier.drawBackdrop(
                        backdrop = backdrop,
                        shape = { RoundedRectangle(41f.dp) },
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
                } else {
                    Modifier
                        .clip(RoundedCornerShape(24.dp))
                        .background(Color(0x99000000))
                }
            )
            .padding(start = 16.dp, end = 16.dp, bottom = 16.dp)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 4.dp)
                .pointerInput(Unit) {
                    detectDragGestures(
                        onDragEnd = onDragEnd,
                        onDragCancel = onDragEnd
                    ) { change, dragAmount ->
                        change.consume()
                        onDrag(dragAmount.y)
                    }
                },
            contentAlignment = Alignment.Center
        ) {
            Box(
                modifier = Modifier
                    .padding(vertical = 4.dp)
                    .width(40.dp)
                    .height(4.dp)
                    .background(contentColor.copy(alpha = 0.3f), RoundedCornerShape(2.dp))
            )
        }
        Spacer(Modifier.height(4.dp))
        BasicText(
            "参数调节",
            style = TextStyle(contentColor, 18.sp, fontWeight = FontWeight.Bold),
            modifier = Modifier.fillMaxWidth().wrapContentSize(Alignment.Center)
        )
        Spacer(Modifier.height(12.dp))
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f, fill = false)
                .nestedScroll(disableScrollConnection)
                .padding(horizontal = 8.dp)
                .verticalScroll(scrollState),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
        DepthParamSlider(
            label = "灵敏度",
            value = model.sensorSensitivity,
            valueText = String.format("%.1f", model.sensorSensitivity),
            range = 1f..9f,
            onValueChange = { onModelChange(model.copy(sensorSensitivity = it)) },
            backdrop = backdrop,
            isLightTheme = isLightTheme,
            contentColor = contentColor,
            visibilityThreshold = 0.1f,
            onInteractionChange = { isSliderInteracting = it }
        )
        DepthParamSlider(
            label = "视差强度",
            value = model.parallaxStrength,
            valueText = String.format("%.3f", model.parallaxStrength),
            range = 0.001f..0.075f,
            onValueChange = { onModelChange(model.copy(parallaxStrength = it)) },
            backdrop = backdrop,
            isLightTheme = isLightTheme,
            contentColor = contentColor,
            visibilityThreshold = 0.001f,
            onInteractionChange = { isSliderInteracting = it }
        )
        if (model.isGaussian()) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                DepthPreviewModePill(
                    text = "Native",
                    selected = !model.useWebGaussianRenderer(),
                    onClick = { onModelChange(model.copy(gaussianRenderMode = "native")) },
                    modifier = Modifier.weight(1f)
                )
                DepthPreviewModePill(
                    text = "WebView",
                    selected = model.useWebGaussianRenderer(),
                    onClick = { onModelChange(model.copy(gaussianRenderMode = "web")) },
                    modifier = Modifier.weight(1f)
                )
            }
            DepthParamSlider(
                label = "壁纸密度",
                value = model.gaussianMaxSplats.toFloat(),
                valueText = formatSplatBudget(model.gaussianMaxSplats),
                range = GAUSSIAN_MIN_SPLAT_BUDGET.toFloat()..GAUSSIAN_FULL_SPLAT_BUDGET.toFloat(),
                onValueChange = {
                    val budget = it.toInt()
                        .roundToNearest(GAUSSIAN_SPLAT_BUDGET_STEP)
                        .coerceIn(GAUSSIAN_MIN_SPLAT_BUDGET, GAUSSIAN_FULL_SPLAT_BUDGET)
                    onModelChange(model.copy(gaussianMaxSplats = budget))
                },
                backdrop = backdrop,
                isLightTheme = isLightTheme,
                contentColor = contentColor,
                visibilityThreshold = GAUSSIAN_SPLAT_BUDGET_STEP.toFloat(),
                onInteractionChange = { isSliderInteracting = it }
            )
            DepthParamSlider(
                label = "距离",
                value = model.cameraZoom,
                valueText = String.format("%.2f", model.cameraZoom),
                range = 0.6f..10f,
                onValueChange = { onModelChange(model.copy(cameraZoom = it)) },
                backdrop = backdrop,
                isLightTheme = isLightTheme,
                contentColor = contentColor,
                visibilityThreshold = 0.01f,
                onInteractionChange = { isSliderInteracting = it }
            )
            DepthParamSlider(
                label = "注视深度",
                value = model.focusDepth,
                valueText = String.format("%.2f", model.focusDepth),
                range = -1f..1f,
                onValueChange = { onModelChange(model.copy(focusDepth = it)) },
                backdrop = backdrop,
                isLightTheme = isLightTheme,
                contentColor = contentColor,
                visibilityThreshold = 0.01f,
                onInteractionChange = { isSliderInteracting = it }
            )
            Text(
                text = "重置注视点",
                color = Color.White,
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium,
                modifier = Modifier
                    .clip(RoundedCornerShape(16.dp))
                    .background(Color(0x332A83FF))
                    .clickable {
                        onModelChange(
                            model.copy(
                                centerOffsetX = 0f,
                                centerOffsetY = 0f,
                                focusDepth = 0.25f
                            )
                        )
                    }
                    .padding(horizontal = 12.dp, vertical = 7.dp)
            )
        }
    }
}
}


@Composable
private fun DepthPreviewModePill(
    text: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .height(34.dp)
            .clip(RoundedCornerShape(18.dp))
            .background(if (selected) Color(0xAA2A83FF) else Color(0x33000000))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = text,
            color = Color.White,
            fontSize = 13.sp,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium
        )
    }
}

@Composable
private fun DepthParamSlider(
    label: String,
    value: Float,
    valueText: String,
    range: ClosedFloatingPointRange<Float>,
    onValueChange: (Float) -> Unit,
    backdrop: Backdrop?,
    isLightTheme: Boolean,
    contentColor: Color,
    visibilityThreshold: Float,
    onInteractionChange: (Boolean) -> Unit = {}
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            BasicText(label, style = TextStyle(contentColor, 14.sp, fontWeight = FontWeight.Medium))
            BasicText(valueText, style = TextStyle(contentColor.copy(alpha = 0.62f), 12.sp))
        }
        if (backdrop != null) {
            LiquidSlider(
                value = { value.coerceIn(range.start, range.endInclusive) },
                onValueChange = {
                    onInteractionChange(true)
                    onValueChange(it)
                },
                onValueChangeFinished = {
                    onInteractionChange(false)
                },
                valueRange = range,
                visibilityThreshold = visibilityThreshold,
                backdrop = backdrop,
                isLightTheme = isLightTheme,
                dragWholeTrack = true,
                modifier = Modifier.fillMaxWidth()
            )
        } else {
            Slider(
                value = value.coerceIn(range.start, range.endInclusive),
                onValueChange = {
                    onInteractionChange(true)
                    onValueChange(it)
                },
                onValueChangeFinished = {
                    onInteractionChange(false)
                },
                valueRange = range,
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}

@Composable
private fun DepthPreviewPill(
    text: String,
    color: Color,
    background: Color,
    onClick: () -> Unit
) {
    Text(
        text = text,
        color = color,
        fontSize = 15.sp,
        modifier = Modifier
            .clip(RoundedCornerShape(18.dp))
            .background(background)
            .clickable(onClick = onClick)
            .padding(horizontal = 18.dp, vertical = 8.dp)
    )
}

@Composable
private fun DepthPreviewKindPill(
    text: String,
    selected: Boolean,
    contentColor: Color,
    accentColor: Color,
    onClick: () -> Unit
) {
    Text(
        text = text,
        color = if (selected) Color.White else contentColor,
        fontSize = 14.sp,
        modifier = Modifier
            .clip(RoundedCornerShape(20.dp))
            .background(if (selected) accentColor.copy(alpha = 0.75f) else Color(0x33000000))
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 8.dp)
    )
}

@Composable
private fun DepthWallpaperCard(
    modifier: Modifier = Modifier,
    model: DepthWallpaperModel,
    selected: Boolean,
    isDragging: Boolean = false,
    showRemoveButton: Boolean = false,
    cardBackground: Color,
    onClick: () -> Unit,
    onDelete: () -> Unit
) {
    val context = LocalContext.current
    val screenAspect = remember(context) {
        val metrics = context.resources.displayMetrics
        metrics.widthPixels.toFloat() / metrics.heightPixels.coerceAtLeast(1).toFloat()
    }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .aspectRatio(screenAspect)
            .zIndex(if (isDragging) 1f else 0f)
            .graphicsLayer {
                if (isDragging) {
                    scaleX = 1.05f
                    scaleY = 1.05f
                    alpha = 0.9f
                }
            }
            .clip(RoundedCornerShape(16.dp))
            .background(Color.Black)
            .clickable(onClick = onClick)
    ) {
        DepthWallpaperThumbnail(model = model, cardBackground = cardBackground)
        if (selected) {
            Box(modifier = Modifier.fillMaxSize().background(Color(0x77000000)))
            Text(
                text = "√",
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
                    .clickable { onDelete() }
                    .padding(horizontal = 5.dp)
            )
        }
    }
}

@Composable
private fun DepthWallpaperThumbnail(
    model: DepthWallpaperModel,
    cardBackground: Color
) {
    val context = LocalContext.current
    val contentKey = remember(model) { gaussianThumbnailCacheKey(model) }

    val bitmap by produceState<Bitmap?>(initialValue = null, model.id, contentKey) {
        value = if (model.gaussianUri.isNotBlank()) {
            withContext(Dispatchers.IO) {
                loadOrGenerateGaussianThumbnail(context, model, THUMBNAIL_WIDTH, THUMBNAIL_HEIGHT)
            }
        } else {
            null
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
        val isLight = MaterialTheme.colors.isLight
        val placeholderBg = if (isLight) Color(0xFFE8E8ED) else Color(0xFF1C1C1E)
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(placeholderBg),
            contentAlignment = Alignment.Center
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                BasicText(
                    text = "G",
                    style = TextStyle(
                        color = Color.White.copy(alpha = 0.4f),
                        fontSize = 32.sp,
                        fontWeight = FontWeight.Bold
                    )
                )
                BasicText(
                    text = model.typeLabel(),
                    style = TextStyle(
                        color = Color.White.copy(alpha = 0.4f),
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium
                    )
                )
            }
        }
    }
}

@Composable
private fun DepthAddDialog(
    accentColor: Color,
    contentColor: Color,
    containerColor: Color,
    liquidBackdrop: Backdrop?,
    onDismiss: () -> Unit,
    onPick: (DepthAddKind) -> Unit
) {
    val dialogBackdrop = liquidBackdrop ?: rememberCanvasBackdrop { drawRect(containerColor) }
    val isLight = MaterialTheme.colors.isLight

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(Unit) { detectTapGestures { onDismiss() } },
            contentAlignment = Alignment.Center
        ) {
            Column(
                Modifier
                    .padding(50.dp)
                    .wrapContentHeight()
                    .drawBackdrop(
                        backdrop = dialogBackdrop,
                        shape = { RoundedRectangle(48f.dp) },
                        effects = {
                            colorControls(
                                brightness = if (isLight) 0.2f else 0f,
                                saturation = 1.5f
                            )
                            blur(if (isLight) 16f.dp.toPx() else 8f.dp.toPx())
                            lens(24f.dp.toPx(), 48f.dp.toPx(), depthEffect = true)
                        },
                        highlight = { Highlight.Plain },
                        onDrawSurface = { drawRect(containerColor) }
                    )
                    .pointerInput(Unit) { detectTapGestures { } }
            ) {
                Column(
                    Modifier
                        .padding(16.dp, 20.dp, 16.dp, 20.dp)
                        .fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    BasicText(
                        "添加景深壁纸",
                        style = TextStyle(contentColor, 18.sp, fontWeight = FontWeight.Bold)
                    )
                    Spacer(Modifier.height(4.dp))
                    DialogButton("Gaussian SOG", accentColor, Color.White) { onPick(DepthAddKind.Gaussian) }
                    DialogButton("在线生成 SOG", accentColor.copy(alpha = 0.82f), Color.White) {
                        onPick(DepthAddKind.OnlineGaussian)
                    }
                    Spacer(Modifier.height(4.dp))
                    DialogButton("取消", containerColor.copy(0.2f), contentColor) { onDismiss() }
                }
            }
        }
    }
}

@Composable
private fun DepthOnlineGenerateDialog(
    imageUrl: String,
    busy: Boolean,
    accentColor: Color,
    contentColor: Color,
    containerColor: Color,
    onImageUrlChange: (String) -> Unit,
    onGenerate: () -> Unit,
    onDismiss: () -> Unit
) {
    Dialog(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .clip(RoundedCornerShape(24.dp))
                .background(MaterialTheme.colors.surface)
                .padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text("在线生成 SOG", color = contentColor, fontWeight = FontWeight.Bold)
            BasicTextField(
                value = imageUrl,
                onValueChange = onImageUrlChange,
                enabled = !busy,
                textStyle = TextStyle(color = contentColor, fontSize = 14.sp),
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(containerColor.copy(alpha = 0.22f))
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                decorationBox = { innerTextField ->
                    if (imageUrl.isBlank()) {
                        Text(
                            "https://.../image.png",
                            color = contentColor.copy(alpha = 0.42f),
                            fontSize = 14.sp
                        )
                    }
                    innerTextField()
                }
            )
            DialogButton(if (busy) "生成中" else "生成并添加", accentColor, Color.White, onGenerate)
            DialogButton("取消", containerColor.copy(0.2f), contentColor, onDismiss)
        }
    }
}

@Composable
private fun DialogButton(
    text: String,
    bgColor: Color,
    textColor: Color,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .clip(Capsule())
            .background(bgColor)
            .clickable(onClick = onClick)
            .height(48.dp)
            .fillMaxWidth(),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically
    ) {
        BasicText(text, style = TextStyle(textColor, 16.sp))
    }
}

private fun DepthWallpaperModel.typeLabel(): String {
    return "Gaussian"
}

private fun formatSplatBudget(value: Int): String {
    return String.format("%.1fM", value / 1_000_000f)
}

private fun Int.roundToNearest(step: Int): Int {
    if (step <= 0) return this
    return ((this + step / 2) / step) * step
}

private fun queryDisplayName(context: Context, uri: Uri): String? {
    return runCatching {
        context.contentResolver.query(
            uri,
            arrayOf(OpenableColumns.DISPLAY_NAME),
            null,
            null,
            null
        )?.use { cursor ->
            if (cursor.moveToFirst()) {
                val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (index >= 0) cursor.getString(index) else null
            } else {
                null
            }
        }
    }.getOrNull() ?: uri.lastPathSegment
}

private const val THUMBNAIL_WIDTH = 216
private const val THUMBNAIL_HEIGHT = 384
private const val THUMBNAIL_MAX_SPLATS = 40_000
private const val GAUSSIAN_THUMBNAIL_CACHE_VERSION = 2

private data class ProjectedSplat(
    val u: Float, val v: Float,
    val z: Float,
    val radius: Float,
    val r: Float, val g: Float, val b: Float, val a: Float
)

private fun generateGaussianThumbnail(
    context: Context,
    uriString: String,
    width: Int,
    height: Int
): Bitmap? {
    return runCatching {
        val scene = GaussianSceneLoader.loadSceneDetailed(
            context = context,
            uriString = uriString,
            maxSplats = THUMBNAIL_MAX_SPLATS,
            viewportAspect = width.toFloat() / height.coerceAtLeast(1).toFloat()
        ).scene ?: return null

        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)

        val bgR = (scene.backgroundR * 255).toInt().coerceIn(0, 255)
        val bgG = (scene.backgroundG * 255).toInt().coerceIn(0, 255)
        val bgB = (scene.backgroundB * 255).toInt().coerceIn(0, 255)
        canvas.drawColor(android.graphics.Color.rgb(bgR, bgG, bgB))

        val count = scene.count
        val step = (count / THUMBNAIL_MAX_SPLATS).coerceAtLeast(1)
        val imageW = scene.imageWidth.coerceAtLeast(1).toFloat()
        val imageH = scene.imageHeight.coerceAtLeast(1).toFloat()
        val focal = scene.focalLengthPx
        val thumbW = width.toFloat()
        val thumbH = height.toFloat()

        // Uniform scale with letterbox/pillarbox, matching drawGaussianPoints approach
        val uniformScale = minOf(thumbW / imageW, thumbH / imageH)
        val offsetU = (thumbW - imageW * uniformScale) * 0.5f
        val offsetV = (thumbH - imageH * uniformScale) * 0.5f

        val splats = ArrayList<ProjectedSplat>(count / step)
        for (i in 0 until count step step) {
            val px = scene.positions[i * 3]
            val py = scene.positions[i * 3 + 1]
            val pz = scene.positions[i * 3 + 2]
            if (pz <= 0.01f) continue

            val a = scene.colors[i * 4 + 3]
            if (a < 0.015f) continue

            val sx = scene.scales[i * 3]
            val sy = scene.scales[i * 3 + 1]
            val sz = scene.scales[i * 3 + 2]

            val projX = px / pz
            val projY = py / pz
            val u = ((projX * focal) + imageW * 0.5f) * uniformScale + offsetU
            val v = ((projY * focal) + imageH * 0.5f) * uniformScale + offsetV
            val rad = maxOf(sx, sy, sz, 0.0006f) * focal / pz * uniformScale * 2.6f

            splats += ProjectedSplat(
                u, v, pz, rad,
                scene.colors[i * 4], scene.colors[i * 4 + 1], scene.colors[i * 4 + 2], a
            )
        }

        splats.sortByDescending { it.z }

        val paint = Paint().apply { isAntiAlias = true; style = Paint.Style.FILL }
        for (splat in splats) {
            val alpha = (splat.a * 255).toInt().coerceIn(0, 255)
            if (alpha < 2) continue
            paint.color = android.graphics.Color.argb(
                alpha,
                (splat.r * 255).toInt().coerceIn(0, 255),
                (splat.g * 255).toInt().coerceIn(0, 255),
                (splat.b * 255).toInt().coerceIn(0, 255)
            )
            canvas.drawCircle(splat.u, splat.v, splat.radius.coerceAtLeast(0.8f), paint)
        }

        bitmap
    }.getOrNull()
}

private fun loadOrGenerateGaussianThumbnail(
    context: Context,
    model: DepthWallpaperModel,
    width: Int,
    height: Int
): Bitmap? {
    val file = gaussianThumbnailCacheFile(context, model)
    if (file?.exists() == true) {
        BitmapFactory.decodeFile(file.absolutePath, BitmapFactory.Options().apply {
            inPreferredConfig = Bitmap.Config.RGB_565
        })?.let { return it }
    }

    val bitmap = generateGaussianThumbnail(context, model.gaussianUri, width, height) ?: return null
    if (file != null) {
        runCatching {
            file.outputStream().use { output ->
                bitmap.compress(Bitmap.CompressFormat.JPEG, 88, output)
            }
        }
    }
    return bitmap
}

private fun removeGaussianThumbnailCache(context: Context, model: DepthWallpaperModel) {
    gaussianThumbnailCacheFile(context, model)?.takeIf { it.exists() }?.delete()
}

private fun gaussianThumbnailCacheFile(context: Context, model: DepthWallpaperModel): File? {
    if (model.id.isBlank() || model.gaussianUri.isBlank()) return null
    val root = context.getExternalFilesDir(null) ?: return null
    val dir = File(root, "thumbnail_cache")
    if (!dir.mkdirs() && !dir.exists()) return null
    return File(dir, "${gaussianThumbnailCacheKey(model)}.jpg")
}

private fun gaussianThumbnailCacheKey(model: DepthWallpaperModel): String {
    val digest = MessageDigest.getInstance("SHA-1")
        .digest(model.gaussianUri.toByteArray())
        .joinToString("") { "%02x".format(it) }
        .take(16)
    return "gaussian_${model.id}_${digest}_v$GAUSSIAN_THUMBNAIL_CACHE_VERSION"
}

