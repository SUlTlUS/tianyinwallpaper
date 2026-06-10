package com.zeaze.tianyinwallpaper.ui.main

import android.app.Activity
import android.app.WallpaperManager
import android.content.Context
import android.content.ComponentName
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.util.Log
import androidx.activity.compose.BackHandler
import androidx.activity.compose.PredictiveBackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.ExperimentalAnimationApi
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Surface
import androidx.compose.material.Text

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.documentfile.provider.DocumentFile
import com.alibaba.fastjson.JSON
import com.kyant.shapes.Capsule
import com.kyant.shapes.RoundedRectangle
import com.zeaze.tianyinwallpaper.backdrop.backdrops.layerBackdrop
import com.zeaze.tianyinwallpaper.backdrop.backdrops.rememberLayerBackdrop
import com.zeaze.tianyinwallpaper.backdrop.backdrops.rememberCanvasBackdrop
import com.zeaze.tianyinwallpaper.backdrop.drawBackdrop
import com.zeaze.tianyinwallpaper.backdrop.effects.blur
import com.zeaze.tianyinwallpaper.backdrop.effects.colorControls
import com.zeaze.tianyinwallpaper.backdrop.effects.lens
import com.zeaze.tianyinwallpaper.backdrop.highlight.Highlight
import com.zeaze.tianyinwallpaper.App
import com.zeaze.tianyinwallpaper.R
import com.zeaze.tianyinwallpaper.base.rxbus.RxBus
import com.zeaze.tianyinwallpaper.base.rxbus.RxConstants
import com.zeaze.tianyinwallpaper.model.TianYinWallpaperModel
import com.zeaze.tianyinwallpaper.model.DepthWallpaperModel
import com.zeaze.tianyinwallpaper.model.RasterGroupModel
import com.zeaze.tianyinwallpaper.ui.commom.ProgressiveBlurContent
import com.zeaze.tianyinwallpaper.ui.commom.SaveData
import com.zeaze.tianyinwallpaper.ui.commom.LiquidWindowAnimatedContent
import com.zeaze.tianyinwallpaper.service.TianYinWallpaperService
import com.zeaze.tianyinwallpaper.service.DepthWallpaperService
import com.zeaze.tianyinwallpaper.service.StaticRasterWallpaperService
import com.zeaze.tianyinwallpaper.service.VideoRasterWallpaperService
import com.zeaze.tianyinwallpaper.ui.depth.DepthPreviewOverlay
import com.zeaze.tianyinwallpaper.ui.raster.RasterDetailScreen
import com.zeaze.tianyinwallpaper.utils.FileUtil
import com.zeaze.tianyinwallpaper.utils.ThumbnailUtils
import com.zeaze.tianyinwallpaper.utils.DepthPrefs
import com.zeaze.tianyinwallpaper.utils.RasterPrefs
import com.zeaze.tianyinwallpaper.utils.showToast
import io.reactivex.functions.Consumer
import java.io.IOException
import java.util.Collections
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.launch
import kotlinx.coroutines.CancellationException
import androidx.compose.runtime.rememberCoroutineScope
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyGridState
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.zeaze.tianyinwallpaper.ui.depth.DepthOnlineGenerateRoute

internal const val WALLPAPER_TYPE_STATIC = 0
internal const val WALLPAPER_TYPE_DYNAMIC = 1
private const val SNAP_NONE = 0
private const val SNAP_LEFT = 1
private const val SNAP_RIGHT = 2
private const val SNAP_TOP = 4
private const val SNAP_BOTTOM = 8
private const val HOLD_SELECT_TIMEOUT_MS = 500L

internal fun wallpaperTypeByMimeOrName(mimeType: String?, fileName: String?): Int? {
    val normalizedMime = mimeType.orEmpty().lowercase()
    val normalizedName = fileName.orEmpty().lowercase()
    if (normalizedMime.startsWith("image/") ||
        normalizedName.endsWith(".jpg") ||
        normalizedName.endsWith(".jpeg") ||
        normalizedName.endsWith(".png") ||
        normalizedName.endsWith(".webp") ||
        normalizedName.endsWith(".gif") ||
        normalizedName.endsWith(".bmp")
    ) {
        return WALLPAPER_TYPE_STATIC
    }
    if (normalizedMime.startsWith("video/") ||
        normalizedName.endsWith(".mp4") ||
        normalizedName.endsWith(".mkv") ||
        normalizedName.endsWith(".webm") ||
        normalizedName.endsWith(".avi") ||
        normalizedName.endsWith(".mov") ||
        normalizedName.endsWith(".3gp")
    ) {
        return WALLPAPER_TYPE_DYNAMIC
    }
    return null
}

private sealed class DialogState {
    object Type : DialogState()
    object Permission : DialogState()
    object Delete : DialogState()
    object Overwrite : DialogState()
    data class Time(val index: Int) : DialogState()
    object Save : DialogState()
}

private fun getTimeString(t: Int): String {
    var time = t
    var s = ""
    s = if (time / 60 == 0) s + "00" else if (time / 60 < 10) s + "0" + time / 60 else s + time / 60
    time %= 60
    s = if (time < 10) "$s:0$time" else "$s:$time"
    return s
}

private fun minuteWindowRanges(start: Int, end: Int): List<IntRange> {
    if (start !in 0 until 24 * 60 || end !in 0 until 24 * 60) return emptyList()
    if (start == end) return emptyList()
    return if (start < end) {
        listOf(start until end)
    } else {
        listOf(start until (24 * 60), 0 until end)
    }
}

private fun isTimeWindowOverlap(startA: Int, endA: Int, startB: Int, endB: Int): Boolean {
    val rangesA = minuteWindowRanges(startA, endA)
    val rangesB = minuteWindowRanges(startB, endB)
    if (rangesA.isEmpty() || rangesB.isEmpty()) return false
    return rangesA.any { a ->
        rangesB.any { b ->
            val overlapStart = maxOf(a.first, b.first)
            val overlapEndExclusive = minOf(a.last + 1, b.last + 1)
            overlapStart < overlapEndExclusive
        }
    }
}


@OptIn(ExperimentalAnimationApi::class)
@Composable
@Suppress("UNUSED_PARAMETER")
fun MainRouteScreen(
    useDarkTheme: Boolean,
    onOpenSettingPage: () -> Unit,
    onBottomBarVisibleChange: (Boolean) -> Unit
) {
    val context = LocalContext.current
    val haptic = LocalHapticFeedback.current
    val isLightTheme = !useDarkTheme
    val contentColor = if (isLightTheme) Color.Black else Color.White
    val accentColor = if (isLightTheme) Color(0xFF0088FF) else Color(0xFF0091FF)
    val containerColor = if (isLightTheme) Color(0xFFFAFAFA).copy(0.6f) else Color(0xFF121212).copy(0.4f)
    val dimColor = if (isLightTheme) Color(0xFF29293A).copy(0.23f) else Color(0xFF121212).copy(0.56f)

    val enableLiquidGlass = android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU
    val activity = context as? Activity
    val pref = remember(context) { context.getSharedPreferences(App.TIANYIN, Context.MODE_PRIVATE) }
    val editor = remember(pref) { pref.edit() }
    val coroutineScope = rememberCoroutineScope()

    val wallpapers = remember { mutableStateListOf<TianYinWallpaperModel>() }
    val selectedPositions = remember { mutableStateListOf<Int>() }
    val rasterGroups = remember { mutableStateListOf<RasterGroupModel>() }
    val depthWallpapers = remember { mutableStateListOf<DepthWallpaperModel>() }

    var selectionMode by remember { mutableStateOf(false) }
    var mainFilter by remember { mutableStateOf(MainWallpaperFilter.All) }
    var groupName by remember { mutableStateOf("") }

    var showWallpaperTypeDialog by remember { mutableStateOf(false) }
    var showPermissionDialog by remember { mutableStateOf(false) }
    var showDeleteSelectedDialog by remember { mutableStateOf(false) }
    var showOverwriteDialog by remember { mutableStateOf(false) }
    var showSaveDialog by remember { mutableStateOf(false) }
    var showDepthOnlinePage by remember { mutableStateOf(false) }
    var renderDepthOnlinePage by remember { mutableStateOf(false) }

    var timeDialogIndex by remember { mutableStateOf<Int?>(null) }
    var replaceIndex by remember { mutableStateOf<Int?>(null) }
    var fullScreenPreviewModel by remember { mutableStateOf<TianYinWallpaperModel?>(null) }
    var rasterDetailGroup by remember { mutableStateOf<RasterGroupModel?>(null) }
    var rasterStaticEditorGroupId by remember { mutableStateOf<String?>(null) }
    var rasterVideoEditorGroupId by remember { mutableStateOf<String?>(null) }
    var depthPreviewModel by remember { mutableStateOf<DepthWallpaperModel?>(null) }
    var showLivePreview by remember { mutableStateOf(false) }

    val currentDialogState = when {
        showWallpaperTypeDialog -> DialogState.Type
        showPermissionDialog -> DialogState.Permission
        showDeleteSelectedDialog -> DialogState.Delete
        showOverwriteDialog -> DialogState.Overwrite
        showSaveDialog -> DialogState.Save
        timeDialogIndex != null -> DialogState.Time(timeDialogIndex!!)
        else -> null
    }

    fun sendServiceIntent(action: String) {
        val intent = Intent(context, TianYinWallpaperService::class.java).apply {
            this.action = action
        }
        context.startService(intent)
    }

    val density = LocalDensity.current
    val statusBarTopPadding = remember(context) {
        val id = context.resources.getIdentifier("status_bar_height", "dimen", "android")
        if (id > 0) context.resources.getDimensionPixelSize(id) else 0
    }
    val statusBarTopPaddingDp = with(density) { statusBarTopPadding.toDp() }
    val depthOnlinePageWidthPx = remember(context) {
        context.resources.displayMetrics.widthPixels.toFloat().coerceAtLeast(1f)
    }
    val depthOnlinePageOffset = remember { Animatable(depthOnlinePageWidthPx) }
    var depthOnlineBackDragOffsetPx by remember { mutableStateOf(0f) }
    var depthOnlineBackGestureActive by remember { mutableStateOf(false) }

    fun closeDepthOnlinePage() {
        if (!renderDepthOnlinePage && !showDepthOnlinePage) return
        coroutineScope.launch {
            val startOffset = (depthOnlinePageOffset.value + depthOnlineBackDragOffsetPx)
                .coerceIn(0f, depthOnlinePageWidthPx)
            depthOnlineBackDragOffsetPx = 0f
            depthOnlineBackGestureActive = false
            depthOnlinePageOffset.snapTo(startOffset)
            showDepthOnlinePage = false
        }
    }

    LaunchedEffect(showDepthOnlinePage, depthOnlinePageWidthPx) {
        if (showDepthOnlinePage) {
            renderDepthOnlinePage = true
            depthOnlineBackDragOffsetPx = 0f
            depthOnlineBackGestureActive = false
            depthOnlinePageOffset.snapTo(depthOnlinePageWidthPx)
            depthOnlinePageOffset.animateTo(
                targetValue = 0f,
                animationSpec = spring(
                    dampingRatio = Spring.DampingRatioNoBouncy,
                    stiffness = Spring.StiffnessMediumLow
                )
            )
        } else if (renderDepthOnlinePage) {
            depthOnlineBackDragOffsetPx = 0f
            depthOnlineBackGestureActive = false
            depthOnlinePageOffset.animateTo(
                targetValue = depthOnlinePageWidthPx,
                animationSpec = spring(
                    dampingRatio = Spring.DampingRatioNoBouncy,
                    stiffness = Spring.StiffnessMediumLow
                )
            )
            renderDepthOnlinePage = false
            depthOnlinePageOffset.snapTo(depthOnlinePageWidthPx)
        }
    }

    fun checkAndSaveGroup() {
        if (groupName.isBlank() || wallpapers.isEmpty()) return

        coroutineScope.launch {
            val currentContent = withContext(Dispatchers.IO) { JSON.toJSONString(wallpapers) }
            val data = withContext(Dispatchers.IO) { FileUtil.loadData(context, FileUtil.dataPath) }
            val list = withContext(Dispatchers.IO) { 
                JSON.parseArray(data, SaveData::class.java)?.toMutableList() ?: mutableListOf() 
            }

            val existing = list.find { it.name == groupName }
            if (existing != null) {
                if (existing.s != currentContent) {
                    showOverwriteDialog = true
                }
            } else {
                list.add(0, SaveData(currentContent, groupName))
                withContext(Dispatchers.IO) {
                    FileUtil.save(context, JSON.toJSONString(list), FileUtil.dataPath) { }
                }
                withContext(Dispatchers.Main) {
                    context.showToast("壁纸组已保存到列表")
                    RxBus.postWithCode(RxConstants.RX_GROUPS_CHANGED, Unit)
                }
            }
        }
    }

    fun performOverwriteSave() {
        if (groupName.isBlank()) return
        coroutineScope.launch {
            val currentContent = withContext(Dispatchers.IO) { JSON.toJSONString(wallpapers) }
            val data = withContext(Dispatchers.IO) { FileUtil.loadData(context, FileUtil.dataPath) }
            val list = withContext(Dispatchers.IO) {
                JSON.parseArray(data, SaveData::class.java)?.toMutableList() ?: mutableListOf()
            }

            val index = list.indexOfFirst { it.name == groupName }
            if (index != -1) {
                list[index].s = currentContent
                val item = list.removeAt(index)
                list.add(0, item)
                withContext(Dispatchers.IO) {
                    FileUtil.save(context, JSON.toJSONString(list), FileUtil.dataPath) { }
                }
                withContext(Dispatchers.Main) {
                    context.showToast("壁纸组已覆盖保存")
                    RxBus.postWithCode(RxConstants.RX_GROUPS_CHANGED, Unit)
                }
            }
            showOverwriteDialog = false
        }
    }

    fun saveCache() {
        editor.putString("wallpaperCache", JSON.toJSONString(wallpapers))
        editor.putString("wallpaperTvCache", groupName)
        editor.apply()

        // 当 service 当前运行在播放列表模式时，同步顺序到 wallpaper.json。
        coroutineScope.launch {
            val activeWallpaperCount = withContext(Dispatchers.IO) {
                runCatching {
                    JSON.parseArray(
                        FileUtil.loadData(context, FileUtil.wallpaperPath),
                        TianYinWallpaperModel::class.java
                    )?.size ?: 0
                }.getOrDefault(0)
            }
            // 预览详情页内的编辑仅更新缓存，不回写运行中的 wallpaper.json，
            // 避免与“应用单张”并发写入产生覆盖竞争。
            if (activeWallpaperCount > 1 && fullScreenPreviewModel == null) {
                withContext(Dispatchers.IO) {
                    FileUtil.save(context, JSON.toJSONString(wallpapers), FileUtil.wallpaperPath) {
                        sendServiceIntent(TianYinWallpaperService.ACTION_SYNC_PLAYLIST)
                    }
                }
            }
        }
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
            context.showToast("设置成功")
        } else {
            if (pref.getBoolean("hide_permission_dialog", false)) {
                context.showToast("设置失败")
            } else {
                showPermissionDialog = true
            }
        }
    }

    fun performApply(list: List<TianYinWallpaperModel>) {
        coroutineScope.launch {
            withContext(Dispatchers.IO) {
                FileUtil.save(context, JSON.toJSONString(list), FileUtil.wallpaperPath) {
                    val hostActivity = activity
                    if (hostActivity == null) {
                        Log.w("MainRouteScreen", "onSave skipped: activity is null")
                        return@save
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
            }
        }
    }

    fun applyWallpapers() {
        if (wallpapers.isEmpty()) {
            context.showToast("至少需要1张壁纸才能开始设置")
            return
        }
        performApply(wallpapers.toList())
    }

    fun applySingleWallpaper(model: TianYinWallpaperModel) {
        performApply(listOf(model))
    }

    fun resolveLatestPreviewModel(previewModel: TianYinWallpaperModel): TianYinWallpaperModel {
        // Prefer exact object reference; then stable identifiers as fallbacks.
        wallpapers.firstOrNull { it === previewModel }?.let { return it }
        previewModel.uuid?.takeIf { it.isNotBlank() }?.let { targetUuid ->
            wallpapers.firstOrNull { it.uuid == targetUuid }?.let { return it }
        }
        previewModel.videoUri?.takeIf { it.isNotBlank() }?.let { targetVideoUri ->
            wallpapers.firstOrNull { it.videoUri == targetVideoUri }?.let { return it }
        }
        previewModel.imgUri?.takeIf { it.isNotBlank() }?.let { targetImgUri ->
            wallpapers.firstOrNull { it.imgUri == targetImgUri }?.let { return it }
        }
        return previewModel
    }

    fun appendMixedModels(results: List<Pair<Uri, Boolean>>, takeUriPermissions: Boolean = true) {
        if (takeUriPermissions) takePersistableUriPermissions(results.map { it.first })
        val list = results.map { (uri, dynamic) ->
            TianYinWallpaperModel().apply {
                uuid = UUID.randomUUID().toString()
                if (dynamic) {
                    type = WALLPAPER_TYPE_DYNAMIC
                    videoUri = uri.toString()
                } else {
                    type = WALLPAPER_TYPE_STATIC
                    imgUri = uri.toString()
                }
            }
        }
        wallpapers.addAll(0, list)
        saveCache()
    }

    fun appendModels(results: List<Uri>, dynamic: Boolean) {
        appendMixedModels(results.map { it to dynamic })
    }

    fun collectMediaFromDirectory(treeUri: Uri): List<Pair<Uri, Boolean>> {
        val treeDocument = DocumentFile.fromTreeUri(context, treeUri) ?: return emptyList()
        val mediaUris = mutableListOf<Pair<Uri, Boolean>>()

        val queue = ArrayDeque<DocumentFile>()
        queue.add(treeDocument)
        while (queue.isNotEmpty()) {
            val document = queue.removeFirst()
            document.listFiles().forEach { file ->
                when {
                    file.isDirectory -> queue.add(file)
                    file.isFile -> when (wallpaperTypeByMimeOrName(file.type, file.name)) {
                        WALLPAPER_TYPE_STATIC -> mediaUris.add(file.uri to false)
                        WALLPAPER_TYPE_DYNAMIC -> mediaUris.add(file.uri to true)
                    }
                }
            }
        }
        return mediaUris
    }

    val imageLaunch = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { results ->
        if (results.isNotEmpty()) appendModels(results, dynamic = false)
    }
    val videoLaunch = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { results ->
        if (results.isNotEmpty()) appendModels(results, dynamic = true)
    }
    val replaceImageLaunch = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null && replaceIndex != null) {
            takePersistableUriPermissions(listOf(uri))
            val targetIndex = replaceIndex!!
            val oldModel = wallpapers.getOrNull(targetIndex)
            val newModel = TianYinWallpaperModel().apply {
                uuid = UUID.randomUUID().toString()
                type = WALLPAPER_TYPE_STATIC
                imgUri = uri.toString()
            }
            wallpapers[targetIndex] = newModel
            oldModel?.let {
                ThumbnailUtils.removeWallpaperCache(
                    context,
                    ThumbnailUtils.Request(
                        uuid = it.uuid.orEmpty(),
                        type = it.type,
                        imgUri = it.imgUri,
                        videoUri = it.videoUri,
                        imgPath = it.imgPath
                    )
                )
            }
            fullScreenPreviewModel = newModel
            saveCache()
            replaceIndex = null
        }
    }
    val replaceVideoLaunch = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null && replaceIndex != null) {
            takePersistableUriPermissions(listOf(uri))
            val targetIndex = replaceIndex!!
            val oldModel = wallpapers.getOrNull(targetIndex)
            val newModel = TianYinWallpaperModel().apply {
                uuid = UUID.randomUUID().toString()
                type = WALLPAPER_TYPE_DYNAMIC
                videoUri = uri.toString()
            }
            wallpapers[targetIndex] = newModel
            oldModel?.let {
                ThumbnailUtils.removeWallpaperCache(
                    context,
                    ThumbnailUtils.Request(
                        uuid = it.uuid.orEmpty(),
                        type = it.type,
                        imgUri = it.imgUri,
                        videoUri = it.videoUri,
                        imgPath = it.imgPath
                    )
                )
            }
            fullScreenPreviewModel = newModel
            saveCache()
            replaceIndex = null
        }
    }
    val directoryLaunch = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { treeUri ->
        if (treeUri == null) return@rememberLauncherForActivityResult
        try {
            activity?.contentResolver?.takePersistableUriPermission(treeUri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        } catch (e: SecurityException) {
            Log.e("MainRouteScreen", "Could not take persistable permission for tree URI: $treeUri", e)
            context.showToast(context.getString(R.string.main_wallpaper_directory_permission_failed))
        }
        val media = collectMediaFromDirectory(treeUri)
        if (media.isEmpty()) {
            context.showToast(context.getString(R.string.main_wallpaper_type_directory_empty))
        } else {
            appendMixedModels(media, takeUriPermissions = false)
        }
    }

    fun persistRasterGroups() {
        val snapshot = rasterGroups.toList()
        coroutineScope.launch(Dispatchers.IO) {
            RasterPrefs.saveGroups(pref, snapshot)
        }
    }

    fun persistDepthWallpapers() {
        DepthPrefs.saveWallpapers(pref, depthWallpapers.toList())
    }

    fun addRasterStaticGroup(uris: List<Uri>) {
        if (uris.isEmpty()) return
        takePersistableUriPermissions(uris)
        rasterGroups.add(
            0,
            RasterGroupModel(
                id = UUID.randomUUID().toString(),
                type = RasterGroupModel.TYPE_STATIC,
                imageUris = uris.map { it.toString() },
                createdAt = System.currentTimeMillis()
            )
        )
        persistRasterGroups()
        mainFilter = MainWallpaperFilter.Raster
        context.showToast("已添加光栅图片组")
    }

    fun addRasterDynamicGroup(uri: Uri) {
        takePersistableUriPermissions(listOf(uri))
        rasterGroups.add(
            0,
            RasterGroupModel(
                id = UUID.randomUUID().toString(),
                type = RasterGroupModel.TYPE_DYNAMIC,
                videoUri = uri.toString(),
                createdAt = System.currentTimeMillis()
            )
        )
        persistRasterGroups()
        mainFilter = MainWallpaperFilter.Raster
        context.showToast("已添加光栅视频")
    }

    fun addDepthSogWallpaper(uri: Uri, recordThumbnailUri: String? = null) {
        if (uri.scheme == "content") {
            takePersistableUriPermissions(listOf(uri))
        }
        val modelId = UUID.randomUUID().toString()
        val displayName = queryMainRouteDisplayName(context, uri).orEmpty()
        val localUri = DepthPrefs.copySogToAppDir(context, uri, modelId)
        val model = DepthWallpaperModel(
            id = modelId,
            gaussianUri = localUri?.toString() ?: uri.toString(),
            displayName = displayName.ifBlank { uri.lastPathSegment.orEmpty() },
            createdAt = System.currentTimeMillis(),
            sensorSensitivity = 9f,
            parallaxStrength = 0.075f,
            gaussianRenderMode = "native",
            cameraZoom = 1f,
            centerOffsetX = 0f,
            centerOffsetY = 0f,
            focusDepth = 0.25f,
            gaussianMaxSplats = 800_000,
            blurStrength = 0f
        )
        copyMainRouteOnlineThumbnailToDepthCache(context, recordThumbnailUri, model.id)
        depthWallpapers.add(0, model)
        persistDepthWallpapers()
        mainFilter = MainWallpaperFilter.Depth
        context.showToast("已添加景深 SOG")
    }

    fun updateRasterGroupById(groupId: String, transform: (RasterGroupModel) -> RasterGroupModel) {
        val idx = rasterGroups.indexOfFirst { it.id == groupId }
        if (idx < 0) return
        val updated = transform(rasterGroups[idx])
        rasterGroups[idx] = updated
        if (rasterDetailGroup?.id == groupId) rasterDetailGroup = updated
    }

    fun persistAndRefreshRasterGroup(groupId: String? = rasterDetailGroup?.id) {
        persistRasterGroups()
        groupId ?: return
        rasterGroups.firstOrNull { it.id == groupId }?.let { latest ->
            rasterDetailGroup = latest
        }
    }

    fun applyRasterGroup(group: RasterGroupModel) {
        val hostActivity = activity ?: return
        val isEmptyGroup = when (group.type) {
            RasterGroupModel.TYPE_STATIC -> group.imageUris.isEmpty()
            RasterGroupModel.TYPE_DYNAMIC -> group.videoUri.isNullOrBlank()
            else -> true
        }
        if (isEmptyGroup) {
            context.showToast("当前光栅组合内容为空")
            return
        }
        pref.edit().putString(RasterPrefs.PREF_RASTER_ACTIVE_GROUP_ID, group.id).apply()
        runCatching { WallpaperManager.getInstance(hostActivity).clear() }
            .onFailure { Log.w("MainRouteScreen", "Clear wallpaper failed before applying raster wallpaper", it) }
        val serviceClass = when (group.type) {
            RasterGroupModel.TYPE_DYNAMIC -> VideoRasterWallpaperService::class.java
            RasterGroupModel.TYPE_STATIC -> StaticRasterWallpaperService::class.java
            else -> {
                context.showToast("未知光栅类型")
                return
            }
        }
        val intent = Intent(WallpaperManager.ACTION_CHANGE_LIVE_WALLPAPER).apply {
            putExtra(
                WallpaperManager.EXTRA_LIVE_WALLPAPER_COMPONENT,
                ComponentName(hostActivity, serviceClass)
            )
        }
        wallpaperLaunch.launch(intent)
    }

    fun updateDepthPreview(updated: DepthWallpaperModel) {
        val idx = depthWallpapers.indexOfFirst { it.id == updated.id }
        if (idx >= 0) {
            depthWallpapers[idx] = updated
        }
        depthPreviewModel = updated
    }

    fun applyDepthWallpaper(model: DepthWallpaperModel) {
        val hostActivity = activity ?: return
        updateDepthPreview(model)
        persistDepthWallpapers()
        DepthPrefs.setActiveWallpaperId(pref, model.id)
        runCatching { WallpaperManager.getInstance(hostActivity).clear() }
            .onFailure { Log.w("MainRouteScreen", "Clear wallpaper failed before applying depth wallpaper", it) }
        val intent = Intent(WallpaperManager.ACTION_CHANGE_LIVE_WALLPAPER).apply {
            putExtra(
                WallpaperManager.EXTRA_LIVE_WALLPAPER_COMPONENT,
                ComponentName(hostActivity, DepthWallpaperService::class.java)
            )
        }
        wallpaperLaunch.launch(intent)
    }

    val rasterStaticLaunch = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        addRasterStaticGroup(uris)
    }

    val rasterDynamicLaunch = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) addRasterDynamicGroup(uri)
    }

    val depthSogLaunch = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) addDepthSogWallpaper(uri)
    }

    LaunchedEffect(Unit) {
        if (wallpapers.isEmpty()) {
            val cache = pref.getString("wallpaperCache", "")
            if (!cache.isNullOrEmpty()) {
                val cachedWallpapers = runCatching {
                    JSON.parseArray(cache, TianYinWallpaperModel::class.java)
                }.onFailure {
                    Log.w("MainRouteScreen", "Failed to parse wallpaperCache json, size=${cache.length}", it)
                }.getOrNull()
                if (cachedWallpapers == null) {
                    editor.remove("wallpaperCache").remove("wallpaperTvCache").apply()
                } else {
                    val validWallpapers = cachedWallpapers.filterNotNull()
                    if (validWallpapers.size != cachedWallpapers.size) {
                        Log.w("MainRouteScreen", "wallpaperCache is corrupted (contains null entries), clearing cache")
                        editor.remove("wallpaperCache").remove("wallpaperTvCache").apply()
                    } else {
                        wallpapers.addAll(validWallpapers)
                        groupName = pref.getString("wallpaperTvCache", "") ?: ""
                    }
                }
            }
        }
        rasterGroups.clear()
        rasterGroups.addAll(RasterPrefs.loadGroups(pref))
        depthWallpapers.clear()
        depthWallpapers.addAll(DepthPrefs.loadWallpapers(pref))
    }

    fun enterSelectionMode() {
        if (!selectionMode) {
            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
        }
        selectionMode = true
        selectedPositions.clear()
    }

    fun exitSelectionMode() {
        selectionMode = false
        selectedPositions.clear()
    }

    fun dismissCurrentDialog() {
        showWallpaperTypeDialog = false
        showPermissionDialog = false
        showDeleteSelectedDialog = false
        showOverwriteDialog = false
        showSaveDialog = false
        timeDialogIndex = null
    }

    PredictiveBackHandler(
        enabled = showDepthOnlinePage && currentDialogState == null && fullScreenPreviewModel == null && rasterDetailGroup == null && depthPreviewModel == null && !showLivePreview
    ) { progress ->
        try {
            progress.collect { backEvent ->
                depthOnlineBackGestureActive = true
                depthOnlineBackDragOffsetPx = (depthOnlinePageWidthPx * backEvent.progress)
                    .coerceIn(0f, depthOnlinePageWidthPx)
            }
            val startOffset = (depthOnlinePageOffset.value + depthOnlineBackDragOffsetPx)
                .coerceIn(0f, depthOnlinePageWidthPx)
            depthOnlineBackDragOffsetPx = 0f
            depthOnlineBackGestureActive = false
            depthOnlinePageOffset.snapTo(startOffset)
            showDepthOnlinePage = false
        } catch (_: CancellationException) {
            depthOnlineBackGestureActive = false
            depthOnlineBackDragOffsetPx = 0f
            depthOnlinePageOffset.animateTo(
                targetValue = 0f,
                animationSpec = spring(
                    dampingRatio = Spring.DampingRatioNoBouncy,
                    stiffness = Spring.StiffnessMediumLow
                )
            )
        }
    }

    BackHandler(enabled = currentDialogState != null) {
        dismissCurrentDialog()
    }

    BackHandler(
        enabled = selectionMode &&
            currentDialogState == null &&
            fullScreenPreviewModel == null &&
            !showLivePreview
    ) {
        exitSelectionMode()
    }

    // 发送选择模式状态
    fun publishSelectionState() {
        val isAllSelected = selectedPositions.size == wallpapers.size && wallpapers.isNotEmpty()
        RxBus.postWithCode(RxConstants.RX_SELECTION_MODE_CHANGED, SelectionBarState(selectionMode, isAllSelected))
    }

    fun buildThumbnailRequest(model: TianYinWallpaperModel): ThumbnailUtils.Request {
        return ThumbnailUtils.Request(
            uuid = model.uuid.orEmpty(),
            type = model.type,
            imgUri = model.imgUri,
            videoUri = model.videoUri,
            imgPath = model.imgPath
        )
    }

    fun removeWallpaperAt(index: Int) {
        if (index !in wallpapers.indices) return
        val removed = wallpapers.removeAt(index)
        ThumbnailUtils.removeWallpaperCache(context, buildThumbnailRequest(removed))

        val updatedSelection = selectedPositions
            .asSequence()
            .filter { it != index }
            .map { if (it > index) it - 1 else it }
            .toList()
        selectedPositions.clear()
        selectedPositions.addAll(updatedSelection)

        if (wallpapers.isEmpty()) {
            exitSelectionMode()
        }
        saveCache()
    }

    LaunchedEffect(
        selectionMode,
        fullScreenPreviewModel,
        rasterDetailGroup,
        depthPreviewModel,
        showLivePreview,
        showDepthOnlinePage,
        renderDepthOnlinePage
    ) {
        onBottomBarVisibleChange(
            !selectionMode &&
                fullScreenPreviewModel == null &&
                rasterDetailGroup == null &&
                depthPreviewModel == null &&
                !showLivePreview &&
                !showDepthOnlinePage &&
                !renderDepthOnlinePage
        )
    }

    // 发布选择模式状态
    LaunchedEffect(selectionMode, selectedPositions.size, wallpapers.size) {
        publishSelectionState()
    }
    
    DisposableEffect(Unit) {
        val addDisposable = RxBus.getDefault()
            .toObservableWithCode(RxConstants.RX_ADD_WALLPAPER, TianYinWallpaperModel::class.java)
            .subscribe(Consumer { o ->
                wallpapers.add(0, o)
                saveCache()
                context.showToast("已加入，请在\"壁纸\"里查看")
            })
    
        val triggerAddDisposable = RxBus.getDefault()
            .toObservableWithCode(RxConstants.RX_TRIGGER_ADD_WALLPAPER, Unit::class.java)
            .subscribe { showWallpaperTypeDialog = true }
    
        val triggerApplyDisposable = RxBus.getDefault()
            .toObservableWithCode(RxConstants.RX_TRIGGER_APPLY_WALLPAPER, Unit::class.java)
            .subscribe { applyWallpapers() }
    
        val triggerPreviewDisposable = RxBus.getDefault()
            .toObservableWithCode(RxConstants.RX_TRIGGER_PREVIEW_WALLPAPER, Unit::class.java)
            .subscribe { showLivePreview = true }
    
        val triggerSaveDisposable = RxBus.getDefault()
            .toObservableWithCode(RxConstants.RX_TRIGGER_SAVE_GROUP, Unit::class.java)
            .subscribe { showSaveDialog = true }
    
        val triggerSelectDisposable = RxBus.getDefault()
            .toObservableWithCode(RxConstants.RX_TRIGGER_ENTER_SELECT_MODE, Unit::class.java)
            .subscribe { enterSelectionMode() }

        val overwriteListDisposable = RxBus.getDefault()
            .toObservableWithCode(RxConstants.RX_TRIGGER_OVERWRITE_WALLPAPER_LIST, SaveData::class.java)
            .subscribe { data ->
                val list = JSON.parseArray(data.s, TianYinWallpaperModel::class.java) ?: emptyList()
                wallpapers.clear()
                wallpapers.addAll(list)
                groupName = data.name ?: ""
                saveCache()
                context.showToast("壁纸列表已覆盖")
            }

        // 选择模式操作监听
        val selectionCancelDisposable = RxBus.getDefault()
            .toObservableWithCode(RxConstants.RX_SELECTION_CANCEL, Unit::class.java)
            .subscribe { exitSelectionMode() }

        val selectionDeleteDisposable = RxBus.getDefault()
            .toObservableWithCode(RxConstants.RX_SELECTION_DELETE, Unit::class.java)
            .subscribe {
                if (selectedPositions.isEmpty()) {
                    context.showToast(context.getString(R.string.no_selected_tip))
                } else {
                    showDeleteSelectedDialog = true
                }
            }

        val selectionToggleAllDisposable = RxBus.getDefault()
            .toObservableWithCode(RxConstants.RX_SELECTION_TOGGLE_ALL, Unit::class.java)
            .subscribe {
                val isAllSelected = selectedPositions.size == wallpapers.size && wallpapers.isNotEmpty()
                if (isAllSelected) {
                    selectedPositions.clear()
                } else {
                    selectedPositions.clear()
                    wallpapers.indices.forEach { selectedPositions.add(it) }
                }
            }

        onDispose {
            addDisposable.dispose()
            triggerAddDisposable.dispose()
            triggerApplyDisposable.dispose()
            triggerPreviewDisposable.dispose()
            triggerSaveDisposable.dispose()
            triggerSelectDisposable.dispose()
            overwriteListDisposable.dispose()
            selectionCancelDisposable.dispose()
            selectionDeleteDisposable.dispose()
            selectionToggleAllDisposable.dispose()
            onBottomBarVisibleChange(true)
            // 退出时清除选择模式状态
            RxBus.postWithCode(RxConstants.RX_SELECTION_MODE_CHANGED, SelectionBarState(false, false))
        }
    }

    fun delete(index: Int) {
        removeWallpaperAt(index)
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
                context.showToast("$label 格式错误，请使用HH:mm格式")
                null
            }
        }
    }

    val gridState = rememberLazyGridState()

    LaunchedEffect(mainFilter) {
        if (mainFilter != MainWallpaperFilter.Wallpaper && selectionMode) {
            exitSelectionMode()
        }
    }

    val unifiedItems = remember(
        wallpapers.size,
        rasterGroups.size,
        depthWallpapers.size,
        mainFilter
    ) {
        buildMainUnifiedWallpaperItems(
            wallpapers = wallpapers,
            rasterGroups = rasterGroups,
            depthWallpapers = depthWallpapers,
            filter = mainFilter
        )
    }

    // 辅助函数：更新排序后的选中索引
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

    // 拖动保存状态
    var pendingReorderSave by remember { mutableStateOf(false) }

    val reorderableState = rememberReorderableLazyGridState(
        lazyGridState = gridState,
        onMove = { from, to ->
            updateSelectedIndices(from.index, to.index)
            val item = wallpapers.removeAt(from.index)
            wallpapers.add(to.index, item)
            pendingReorderSave = true
        }
    )

    // 拖动结束后保存
    LaunchedEffect(pendingReorderSave) {
        if (pendingReorderSave) {
            kotlinx.coroutines.delay(300) // 等待拖动稳定
            if (pendingReorderSave) {
                saveCache()

                pendingReorderSave = false
            }
        }
    }

    val contentLayerBackground = MaterialTheme.colors.background
    val liquidBackdrop = if (enableLiquidGlass) rememberLayerBackdrop() else null

    Box(modifier = Modifier.fillMaxSize()) {
        // 捕获层：仅捕获背景和滚动列表
        Box(
            modifier = Modifier
                .fillMaxSize()
                .let { m ->
                    if (enableLiquidGlass && liquidBackdrop != null) {
                        m.layerBackdrop(liquidBackdrop)
                    } else m
                }
        ) {
            Box(Modifier.fillMaxSize().background(contentLayerBackground))
            LazyVerticalGrid(
                state = gridState,
                columns = GridCells.Fixed(3),
                modifier = Modifier
                    .fillMaxSize(),
                contentPadding = PaddingValues(
                    start = 12.dp,
                    end = 12.dp,
                    top = statusBarTopPaddingDp + 120.dp,
                    bottom = if (selectionMode) 90.dp else 110.dp
                ),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                if (mainFilter == MainWallpaperFilter.Wallpaper) {
                itemsIndexed(wallpapers, key = { _, model -> 
                        model.uuid ?: java.util.UUID.randomUUID().toString().also { model.uuid = it }
                    }) { index, model ->
                        val selected = selectedPositions.contains(index)
                        val key = model.uuid ?: index
    
                        ReorderableItem(reorderableState, key = key) { isDragging ->
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .aspectRatio(context.resources.displayMetrics.let { it.widthPixels.toFloat() / it.heightPixels.toFloat() })
                                    .zIndex(if (isDragging) 1f else 0f)
                                    .graphicsLayer {
                                        scaleX = if (isDragging) 1.05f else 1f
                                        scaleY = if (isDragging) 1.05f else 1f
                                        shadowElevation = if (isDragging) 8.dp.toPx() else 0f
                                        shape = RoundedCornerShape(16.dp)
                                        clip = true
                                    }
                                    .longPressDraggableHandle()
                                    .clip(RoundedCornerShape(16.dp))
                                    .pointerInput(index, selectionMode) {
                                        awaitEachGesture {
                                            val down = awaitFirstDown(requireUnconsumed = false)
    
                                            val touchSlop = viewConfiguration.touchSlop
                                            val stayedStillForTimeout = withTimeoutOrNull(HOLD_SELECT_TIMEOUT_MS) {
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
                                                enterSelectionMode()
                                                if (!selectedPositions.contains(index)) {
                                                    selectedPositions.add(index)
                                                }
                                            }
                                        }
                                    }
                                    .clickable {
                                        if (selectionMode) {
                                            if (selected) selectedPositions.remove(index) else selectedPositions.add(index)
                                        } else {
                                            fullScreenPreviewModel = model
                                        }
                                    }
                                    .background(Color.Black)
                            ) {
                                WallpaperCardImage(
                                    modifier = Modifier.fillMaxSize(),
                                    model = model
                                )
                                Text(
                                    text = if (model.type == 0) "图片" else "视频",
                                    color = Color.White,
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier
                                        .align(Alignment.TopEnd)
                                        .padding(3.dp)
                                        .background(Color(0x66000000), shape = RoundedCornerShape(16.dp))
                                        .padding(horizontal = 6.dp, vertical = 2.dp)
                                )
                                if (model.independentTime && model.startTime != -1 && model.endTime != -1) {
                                    Text(
                                        text = "${getTimeString(model.startTime)} - ${getTimeString(model.endTime)}",
                                        color = Color.White,
                                        fontSize = 11.sp,
                                        modifier = Modifier
                                            .align(Alignment.BottomCenter)
                                            .padding(bottom = 3.dp)
                                            .background(Color(0x66000000), shape = RoundedCornerShape(16.dp))
                                            .padding(horizontal = 6.dp, vertical = 2.dp)
                                    )
                                }
                                if (selected) {
                                    Box(modifier = Modifier.fillMaxSize().background(Color(0x77000000)))
                                    Surface(
                                        modifier = Modifier
                                            .align(Alignment.BottomEnd)
                                            .padding(4.dp),
                                        shape = RoundedCornerShape(18.dp),
                                        color = Color(0xD91A1A1A)
                                    ) {
                                        Text(
                                            text = "✓",
                                            color = Color.White,
                                            fontSize = 12.sp,
                                            modifier = Modifier.padding(horizontal = 7.dp, vertical = 0.dp)
                                        )
                                    }
                                }
    
                                if (selectionMode) {
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
                                            .clickable { removeWallpaperAt(index) }
                                            .padding(horizontal = 5.dp)
                                    )
                                }
                            }
                        }
                    }
    
                } else {
                    items(
                        unifiedItems,
                        key = { item ->
                            when (item) {
                                is MainUnifiedWallpaperItem.Wallpaper -> "wallpaper_${item.model.uuid ?: item.index}"
                                is MainUnifiedWallpaperItem.Raster -> "raster_${item.group.id}"
                                is MainUnifiedWallpaperItem.Depth -> "depth_${item.model.id}"
                            }
                        }
                    ) { item ->
                        when (item) {
                            is MainUnifiedWallpaperItem.Wallpaper -> {
                                MainUnifiedWallpaperCard(
                                    modifier = Modifier.fillMaxWidth(),
                                    model = item.model,
                                    isSelected = false,
                                    onClick = { fullScreenPreviewModel = item.model }
                                )
                            }
                            is MainUnifiedWallpaperItem.Raster -> {
                                MainUnifiedRasterCard(
                                    modifier = Modifier.fillMaxWidth(),
                                    group = item.group,
                                    onClick = { rasterDetailGroup = item.group }
                                )
                            }
                            is MainUnifiedWallpaperItem.Depth -> {
                                MainUnifiedDepthCard(
                                    modifier = Modifier.fillMaxWidth(),
                                    model = item.model,
                                    onClick = { depthPreviewModel = item.model }
                                )
                            }
                        }
                    }
                }            }
        }

        ProgressiveBlurContent(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .fillMaxWidth()
                .zIndex(2f),
            backdrop = liquidBackdrop
        )

        MainWallpaperFilterBar(
            selectedFilter = mainFilter,
            onFilterSelected = { mainFilter = it },
            contentColor = contentColor,
            accentColor = accentColor,
            containerColor = containerColor,
            modifier = Modifier
                .align(Alignment.TopCenter)
                .fillMaxWidth()
                .padding(horizontal = 12.dp)
                .padding(top = statusBarTopPaddingDp + 76.dp)
                .zIndex(3f)
        )

        if (!selectionMode && unifiedItems.isEmpty()) {
            androidx.compose.material.Text(
                text = "没有符合筛选的壁纸",
                color = androidx.compose.material.MaterialTheme.colors.onBackground.copy(alpha = 0.7f),
                modifier = Modifier.align(Alignment.Center)
            )
        }

        // 1. Custom Liquid Glass Dialog
        LiquidWindowAnimatedContent(
            targetState = currentDialogState,
            contentAlignment = Alignment.Center,
            label = "DialogOverlay",
            modifier = Modifier
                .fillMaxSize()
                .let {
                    if (currentDialogState != null) {
                        it.pointerInput(currentDialogState) {
                            detectTapGestures { dismissCurrentDialog() }
                        }
                    } else {
                        it
                    }
                }
        ) { state ->
            if (state != null) {
                val dialogBackdrop = liquidBackdrop ?: rememberCanvasBackdrop { drawRect(containerColor) }
                val sheetBackdrop = rememberLayerBackdrop()  // 为 LiquidToggle 导出
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
                            exportedBackdrop = sheetBackdrop,  // 导出给 LiquidToggle 使用
                            onDrawSurface = { drawRect(containerColor) }
                        )
                        .pointerInput(Unit) { detectTapGestures { /* consume */ } }
                ) {
                    when (state) {
                        DialogState.Type -> {
                            MainAddDialog(
                                title = context.getString(R.string.main_select_wallpaper_type_tip),
                                cancelText = context.getString(R.string.common_cancel),
                                contentColor = contentColor,
                                accentColor = accentColor,
                                containerColor = containerColor,
                                onPickImageWallpaper = { showWallpaperTypeDialog = false; imageLaunch.launch(arrayOf("image/*")) },
                                onPickVideoWallpaper = { showWallpaperTypeDialog = false; videoLaunch.launch(arrayOf("video/*")) },
                                onPickFolderWallpaper = { showWallpaperTypeDialog = false; directoryLaunch.launch(null) },
                                onPickRasterImages = { showWallpaperTypeDialog = false; rasterStaticLaunch.launch(arrayOf("image/*")) },
                                onPickRasterVideo = { showWallpaperTypeDialog = false; rasterDynamicLaunch.launch(arrayOf("video/*")) },
                                onPickDepthSog = { showWallpaperTypeDialog = false; depthSogLaunch.launch(arrayOf("*/*")) },
                                onOpenOnlineSog = { showWallpaperTypeDialog = false; showDepthOnlinePage = true },
                                onDismiss = { showWallpaperTypeDialog = false }
                            )
                        }
                        DialogState.Permission -> {
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
                                                val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                                                    data = Uri.fromParts("package", context.packageName, null)
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
                        DialogState.Delete -> {
                            Column(
                                Modifier.padding(16.dp, 20.dp, 16.dp, 20.dp).fillMaxWidth(),
                                verticalArrangement = Arrangement.spacedBy(8.dp),
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                BasicText(context.getString(R.string.delete_selected_confirm), style = TextStyle(contentColor, 18.sp, fontWeight = FontWeight.Bold))
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
                                                val indexes = selectedPositions.toMutableList()
                                                Collections.sort(indexes, Collections.reverseOrder())
                                                for (index in indexes) {
                                                    removeWallpaperAt(index)
                                                }
                                                selectedPositions.clear()
                                                exitSelectionMode()
                                                showDeleteSelectedDialog = false
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
                                            .clickable { showDeleteSelectedDialog = false }
                                            .height(48.dp),
                                        horizontalArrangement = Arrangement.Center,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        BasicText(context.getString(R.string.common_cancel), style = TextStyle(contentColor, 16.sp))
                                    }
                                }
                            }
                        }
                        DialogState.Overwrite -> {
                            Column(
                                Modifier.padding(16.dp, 20.dp, 16.dp, 20.dp).fillMaxWidth(),
                                verticalArrangement = Arrangement.spacedBy(8.dp),
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                BasicText(context.getString(R.string.overwrite_wallpaper_group_confirm), style = TextStyle(contentColor, 18.sp, fontWeight = FontWeight.Bold))
                                Spacer(Modifier.height(12.dp))
                                Row(
                                    Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                                ) {
                                    Row(
                                        Modifier
                                            .weight(1f)
                                            .clip(Capsule())
                                            .background(Color(0xFF4CAF50))
                                            .clickable {
                                                performOverwriteSave()
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
                                            .clickable { showOverwriteDialog = false }
                                            .height(48.dp),
                                        horizontalArrangement = Arrangement.Center,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        BasicText(context.getString(R.string.common_cancel), style = TextStyle(contentColor, 16.sp))
                                    }
                                }
                            }
                        }
                        DialogState.Save -> {
                            Column(
                                Modifier.padding(16.dp, 20.dp, 16.dp, 20.dp).fillMaxWidth(),
                                verticalArrangement = Arrangement.spacedBy(12.dp),
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                BasicText("保存当前壁纸组", style = TextStyle(contentColor, 18.sp, fontWeight = FontWeight.Bold))

                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(48.dp)
                                        .clip(Capsule())
                                        .background(containerColor.copy(0.2f)),
                                    contentAlignment = Alignment.CenterStart
                                ) {
                                    BasicTextField(
                                        value = groupName,
                                        onValueChange = { groupName = it },
                                        singleLine = true,
                                        textStyle = TextStyle(color = contentColor, fontSize = 16.sp),
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(horizontal = 20.dp),
                                        cursorBrush = SolidColor(accentColor.copy(alpha = 0.75f)),
                                        decorationBox = { innerTextField ->
                                            if (groupName.isEmpty()) {
                                                Text(
                                                    text = "项目名称",
                                                    color = contentColor.copy(alpha = 0.5f),
                                                    fontSize = 16.sp
                                                )
                                            }
                                            innerTextField()
                                        }
                                    )
                                }

                                Spacer(Modifier.height(8.dp))

                                Row(
                                    Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                                ) {
                                    Row(
                                        Modifier
                                            .weight(1f)
                                            .clip(Capsule())
                                            .background(accentColor.copy(alpha = 0.75f))
                                            .clickable {
                                                if (groupName.isNotBlank()) {
                                                    checkAndSaveGroup()
                                                    showSaveDialog = false
                                                } else {
                                                    context.showToast("请输入名称")
                                                }
                                            }
                                            .height(48.dp),
                                        horizontalArrangement = Arrangement.Center,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        BasicText("确认", style = TextStyle(Color.White, 16.sp))
                                    }
                                    Row(
                                        Modifier
                                            .weight(1f)
                                            .clip(Capsule())
                                            .background(containerColor.copy(0.2f))
                                            .clickable { showSaveDialog = false }
                                            .height(48.dp),
                                        horizontalArrangement = Arrangement.Center,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        BasicText("取消", style = TextStyle(contentColor, 16.sp))
                                    }
                                }
                            }
                        }
                        is DialogState.Time -> {
                            val targetIndex = state.index
                            val model = wallpapers.getOrNull(targetIndex)
                            if (model != null) {
                                var startTime by remember(targetIndex) { mutableStateOf(model.startTime) }
                                var endTime by remember(targetIndex) { mutableStateOf(model.endTime) }
                                var startText by remember(targetIndex) { mutableStateOf(if (startTime == -1) "" else getTimeString(startTime)) }
                                var endText by remember(targetIndex) { mutableStateOf(if (endTime == -1) "" else getTimeString(endTime)) }

                                Column(
                                    Modifier.padding(16.dp, 20.dp, 16.dp, 20.dp).fillMaxWidth(),
                                    verticalArrangement = Arrangement.spacedBy(12.dp),
                                    horizontalAlignment = Alignment.CenterHorizontally
                                ) {
                                    BasicText("设置时间条件", style = TextStyle(contentColor, 18.sp, fontWeight = FontWeight.Bold))

                                    Box(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .height(48.dp)
                                            .clip(Capsule())
                                            .background(containerColor.copy(0.2f)),
                                        contentAlignment = Alignment.CenterStart
                                    ) {
                                        BasicTextField(
                                            value = startText,
                                            onValueChange = { startText = it },
                                            singleLine = true,
                                            textStyle = TextStyle(color = contentColor, fontSize = 16.sp),
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .padding(horizontal = 20.dp),
                                            cursorBrush = SolidColor(accentColor.copy(alpha = 0.75f)),
                                            decorationBox = { innerTextField ->
                                                if (startText.isEmpty()) {
                                                    Text(
                                                        text = "开始时间(HH:mm)",
                                                        color = contentColor.copy(alpha = 0.5f),
                                                        fontSize = 16.sp
                                                    )
                                                }
                                                innerTextField()
                                            }
                                        )
                                    }

                                    Box(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .height(48.dp)
                                            .clip(Capsule())
                                            .background(containerColor.copy(0.2f)),
                                        contentAlignment = Alignment.CenterStart
                                    ) {
                                        BasicTextField(
                                            value = endText,
                                            onValueChange = { endText = it },
                                            singleLine = true,
                                            textStyle = TextStyle(color = contentColor, fontSize = 16.sp),
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .padding(horizontal = 20.dp),
                                            cursorBrush = SolidColor(accentColor.copy(alpha = 0.75f)),
                                            decorationBox = { innerTextField ->
                                                if (endText.isEmpty()) {
                                                    Text(
                                                        text = "结束时间(HH:mm)",
                                                        color = contentColor.copy(alpha = 0.5f),
                                                        fontSize = 16.sp
                                                    )
                                                }
                                                innerTextField()
                                            }
                                        )
                                    }

                                    Spacer(Modifier.height(8.dp))

                                    Row(
                                        Modifier
                                            .clip(Capsule())
                                            .background(accentColor.copy(alpha = 0.75f))
                                            .clickable {
                                                startTime = parseAndValidateTime(startText, "开始时间") ?: return@clickable
                                                endTime = parseAndValidateTime(endText, "结束时间") ?: run {
                                                    if (endText.isEmpty()) -1 else return@clickable
                                                }
                                                model.startTime = startTime
                                                model.endTime = endTime
                                                if (model.startTime != -1 && model.endTime == -1) model.endTime = 24 * 60
                                                if (model.endTime != -1 && model.startTime == -1) model.startTime = 0
                                                saveCache()
                                                timeDialogIndex = null
                                            }
                                            .height(48.dp)
                                            .fillMaxWidth(),
                                        horizontalArrangement = Arrangement.Center,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        BasicText(context.getString(R.string.common_confirm), style = TextStyle(Color.White, 16.sp))
                                    }

                                    Row(
                                        Modifier
                                            .clip(Capsule())
                                            .background(containerColor.copy(0.2f))
                                            .clickable {
                                                startTime = -1
                                                endTime = -1
                                                startText = ""
                                                endText = ""
                                                model.startTime = -1
                                                model.endTime = -1
                                                saveCache()
                                            }
                                            .height(48.dp)
                                            .fillMaxWidth(),
                                        horizontalArrangement = Arrangement.Center,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        BasicText("重置", style = TextStyle(contentColor, 16.sp))
                                    }

                                    Row(
                                        Modifier
                                            .clip(Capsule())
                                            .background(containerColor.copy(0.2f))
                                            .clickable { timeDialogIndex = null }
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
            }
        }


        if (renderDepthOnlinePage || showDepthOnlinePage) {
            DepthOnlineGenerateRoute(
                modifier = Modifier
                    .fillMaxSize()
                    .zIndex(9f)
                    .graphicsLayer {
                        translationX = depthOnlinePageOffset.value + depthOnlineBackDragOffsetPx
                        alpha = 1f
                    },
                useDarkTheme = useDarkTheme,
                onBack = { closeDepthOnlinePage() },
                onImportSog = { sogUri, recordThumbnailUri ->
                    addDepthSogWallpaper(sogUri, recordThumbnailUri)
                    closeDepthOnlinePage()
                }
            )
        }

        val currentRasterDetail = rasterDetailGroup?.let { selected ->
            rasterGroups.firstOrNull { it.id == selected.id } ?: selected
        }
        currentRasterDetail?.let { group ->
            Dialog(
                onDismissRequest = {
                    rasterDetailGroup = null
                    rasterStaticEditorGroupId = null
                    rasterVideoEditorGroupId = null
                },
                properties = DialogProperties(
                    usePlatformDefaultWidth = false,
                    decorFitsSystemWindows = false
                )
            ) {
                RasterDetailScreen(
                    group = group,
                    previewAspectRatio = context.resources.displayMetrics.let { dm ->
                        dm.widthPixels.toFloat() / dm.heightPixels.toFloat().coerceAtLeast(1f)
                    },
                    statusBarTopPaddingDp = statusBarTopPaddingDp,
                    enableLiquidGlass = enableLiquidGlass,
                    backdrop = liquidBackdrop,
                    staticEditorGroupId = rasterStaticEditorGroupId,
                    onStaticEditorDismiss = { rasterStaticEditorGroupId = null },
                    onStaticEditorReplaceAll = { _: RasterGroupModel -> context.showToast("请在光栅专页编辑图集") },
                    onStaticEditorAppend = { _: RasterGroupModel -> context.showToast("请在光栅专页追加图片") },
                    onStaticEditorReplaceSingle = { _: RasterGroupModel, _: Int -> context.showToast("请在光栅专页替换单张图片") },
                    onStaticEditorMove = { editorGroup: RasterGroupModel, fromIndex: Int, toIndex: Int ->
                        if (fromIndex == toIndex) return@RasterDetailScreen
                        updateRasterGroupById(editorGroup.id) { current ->
                            val imageUris = current.imageUris.toMutableList()
                            if (fromIndex !in imageUris.indices || toIndex !in imageUris.indices) return@updateRasterGroupById current
                            val moved = imageUris.removeAt(fromIndex)
                            imageUris.add(toIndex, moved)
                            current.copy(imageUris = imageUris)
                        }
                    },
                    onStaticEditorCommitReorder = { persistAndRefreshRasterGroup() },
                    onStaticEditorDeleteSingle = { _: RasterGroupModel, _: Int -> context.showToast("请在光栅专页删除图片") },
                    videoEditorGroupId = rasterVideoEditorGroupId,
                    onVideoEditorDismiss = { rasterVideoEditorGroupId = null },
                    onVideoEditorReplaceVideo = { _: RasterGroupModel -> context.showToast("请在光栅专页替换视频") },
                    onSensorWidthChanged = { editorGroup: RasterGroupModel, value: Float -> updateRasterGroupById(editorGroup.id) { it.copy(sensorWidth = value) } },
                    onSensorWidthChangeFinished = { _: RasterGroupModel, _: Float -> persistAndRefreshRasterGroup() },
                    onEffectTypeChanged = { editorGroup: RasterGroupModel, value: Int -> updateRasterGroupById(editorGroup.id) { it.copy(effectType = value) }; persistAndRefreshRasterGroup(editorGroup.id) },
                    onTransitionBandChanged = { editorGroup: RasterGroupModel, value: Float -> updateRasterGroupById(editorGroup.id) { it.copy(transitionBand = value) } },
                    onTransitionBandChangeFinished = { _: RasterGroupModel, _: Float -> persistAndRefreshRasterGroup() },
                    onEdgeSoftnessChanged = { editorGroup: RasterGroupModel, value: Float -> updateRasterGroupById(editorGroup.id) { it.copy(edgeSoftness = value) } },
                    onEdgeSoftnessChangeFinished = { _: RasterGroupModel, _: Float -> persistAndRefreshRasterGroup() },
                    onStripedWavelengthChanged = { editorGroup: RasterGroupModel, value: Float -> updateRasterGroupById(editorGroup.id) { it.copy(stripedWavelength = value) } },
                    onStripedWavelengthChangeFinished = { _: RasterGroupModel, _: Float -> persistAndRefreshRasterGroup() },
                    onStripedAmplitudeChanged = { editorGroup: RasterGroupModel, value: Float -> updateRasterGroupById(editorGroup.id) { it.copy(stripedAmplitude = value) } },
                    onStripedAmplitudeChangeFinished = { _: RasterGroupModel, _: Float -> persistAndRefreshRasterGroup() },
                    onNarrowWavelengthChanged = { editorGroup: RasterGroupModel, value: Float -> updateRasterGroupById(editorGroup.id) { it.copy(narrowWavelength = value) } },
                    onNarrowWavelengthChangeFinished = { _: RasterGroupModel, _: Float -> persistAndRefreshRasterGroup() },
                    onNarrowAmplitudeChanged = { editorGroup: RasterGroupModel, value: Float -> updateRasterGroupById(editorGroup.id) { it.copy(narrowAmplitude = value) } },
                    onNarrowAmplitudeChangeFinished = { _: RasterGroupModel, _: Float -> persistAndRefreshRasterGroup() },
                    onGlassAnimEnabledChanged = { editorGroup: RasterGroupModel, value: Boolean -> updateRasterGroupById(editorGroup.id) { it.copy(glassAnimEnabled = value) }; persistAndRefreshRasterGroup(editorGroup.id) },
                    onGlassBandWidthChanged = { editorGroup: RasterGroupModel, value: Float -> updateRasterGroupById(editorGroup.id) { it.copy(glassBandWidth = value) } },
                    onGlassBandWidthChangeFinished = { _: RasterGroupModel, _: Float -> persistAndRefreshRasterGroup() },
                    onDeadZoneEnabledChanged = { editorGroup: RasterGroupModel, value: Boolean -> updateRasterGroupById(editorGroup.id) { it.copy(deadZoneEnabled = value) }; persistAndRefreshRasterGroup(editorGroup.id) },
                    onClockColorModeChanged = { editorGroup: RasterGroupModel, value: Int -> updateRasterGroupById(editorGroup.id) { it.copy(clockColorMode = value) }; persistAndRefreshRasterGroup(editorGroup.id) },
                    groups = rasterGroups,
                    onDismiss = {
                        rasterDetailGroup = null
                        rasterStaticEditorGroupId = null
                        rasterVideoEditorGroupId = null
                    },
                    onApply = {
                        applyRasterGroup(group)
                        rasterDetailGroup = null
                    },
                    onImageAction = {
                        if (group.type == RasterGroupModel.TYPE_STATIC) {
                            rasterStaticEditorGroupId = group.id
                        } else {
                            context.showToast("请在光栅专页新建图集")
                        }
                    },
                    onVideoAction = {
                        if (group.type == RasterGroupModel.TYPE_DYNAMIC) {
                            rasterVideoEditorGroupId = group.id
                        } else {
                            context.showToast("请在光栅专页选择视频")
                        }
                    }
                )
            }
        }

        val currentDepthPreview = depthPreviewModel?.let { selected ->
            depthWallpapers.firstOrNull { it.id == selected.id } ?: selected
        }
        currentDepthPreview?.let { model ->
            Dialog(
                onDismissRequest = {
                    persistDepthWallpapers()
                    depthPreviewModel = null
                },
                properties = DialogProperties(
                    usePlatformDefaultWidth = false,
                    decorFitsSystemWindows = false
                )
            ) {
                DepthPreviewOverlay(
                    model = model,
                    statusBarTopPaddingDp = statusBarTopPaddingDp,
                    contentColor = contentColor,
                    accentColor = accentColor,
                    containerColor = containerColor,
                    enableLiquidGlass = enableLiquidGlass,
                    onDismiss = {
                        persistDepthWallpapers()
                        depthPreviewModel = null
                    },
                    onApply = {
                        val committed = depthPreviewModel?.let { pending ->
                            depthWallpapers.firstOrNull { it.id == pending.id } ?: pending
                        } ?: model
                        applyDepthWallpaper(committed)
                        depthPreviewModel = null
                    },
                    onModelChange = { updated: DepthWallpaperModel -> updateDepthPreview(updated) },
                    modifier = Modifier.fillMaxSize()
                )
            }
        }

        // Full screen preview overlay (使用 Dialog 方式，对齐光栅页)
        val currentPreviewModel: TianYinWallpaperModel? = fullScreenPreviewModel
        currentPreviewModel?.let { model ->
            Dialog(
                onDismissRequest = { fullScreenPreviewModel = null },
                properties = DialogProperties(
                    usePlatformDefaultWidth = false,
                    decorFitsSystemWindows = false
                )
            ) {
                WallpaperDetailScreen(
                    model = model,
                    statusBarTopPaddingDp = statusBarTopPaddingDp,
                    enableLiquidGlass = enableLiquidGlass,
                    onDismiss = { fullScreenPreviewModel = null },
                    onApply = {
                        // 预览页“应用”始终只应用当前这张壁纸，不应用整个播放列表。
                        val latestSingle = resolveLatestPreviewModel(model)
                        applySingleWallpaper(latestSingle)
                    },
                    onReplaceAction = { isDynamic ->
                        val index = wallpapers.indexOfFirst { it.uuid == model.uuid }
                        if (index >= 0) {
                            replaceIndex = index
                            if (isDynamic) {
                                replaceVideoLaunch.launch(arrayOf("video/*"))
                            } else {
                                replaceImageLaunch.launch(arrayOf("image/*"))
                            }
                        }
                    },
                    onTimeAction = { newStartTime, newEndTime, newLoop, newIndependentTime ->
                        val index = wallpapers.indexOfFirst { it.uuid == model.uuid }
                        if (index >= 0) {
                            // 创建新对象替换，触发 Compose 重新渲染
                            wallpapers[index] = wallpapers[index].copy(
                                startTime = newStartTime,
                                endTime = newEndTime,
                                loop = newLoop,
                                independentTime = newIndependentTime
                            )

                            if (newIndependentTime && newStartTime != -1 && newEndTime != -1) {
                                var closedConflict = false
                                wallpapers.forEachIndexed { i, item ->
                                    if (
                                        i != index &&
                                        item.independentTime &&
                                        item.startTime != -1 &&
                                        item.endTime != -1 &&
                                        isTimeWindowOverlap(newStartTime, newEndTime, item.startTime, item.endTime)
                                    ) {
                                        wallpapers[i] = item.copy(
                                            independentTime = false
                                        )
                                        closedConflict = true
                                    }
                                }
                                if (closedConflict) {
                                    context.showToast("独立时间冲突，已关闭上一个冲突的独立时间")
                                }
                            }
                        }
                        saveCache()
                    },
                    onTransformAction = { newScale, newOffsetX, newOffsetY, newRotation ->
                        val index = wallpapers.indexOfFirst { it.uuid == model.uuid }
                        if (index >= 0) {
                            wallpapers[index].scale = newScale
                            wallpapers[index].offsetX = newOffsetX
                            wallpapers[index].offsetY = newOffsetY
                            wallpapers[index].rotation = newRotation
                        }
                        saveCache()
                        // 仅同步当前渲染中的变换，避免把完整播放列表写回壁纸文件覆盖“单独应用”。
                        context.startService(
                            Intent(context, TianYinWallpaperService::class.java).apply {
                                action = TianYinWallpaperService.ACTION_UPDATE_TRANSFORM
                                putExtra(TianYinWallpaperService.EXTRA_SCALE, newScale)
                                putExtra(TianYinWallpaperService.EXTRA_OFFSET_X, newOffsetX)
                                putExtra(TianYinWallpaperService.EXTRA_OFFSET_Y, newOffsetY)
                                putExtra(TianYinWallpaperService.EXTRA_ROTATION, newRotation)
                            }
                        )
                    },
                    onBrightnessAction = { newBrightness ->
                        val index = wallpapers.indexOfFirst { it.uuid == model.uuid }
                        if (index >= 0) {
                            wallpapers[index].brightness = newBrightness
                        }
                        saveCache()
                        context.startService(
                            Intent(context, TianYinWallpaperService::class.java).apply {
                                action = TianYinWallpaperService.ACTION_UPDATE_BRIGHTNESS
                                putExtra(TianYinWallpaperService.EXTRA_BRIGHTNESS, newBrightness)
                            }
                        )
                    },
                    onVolumeAction = { newVolume ->
                        val index = wallpapers.indexOfFirst { it.uuid == model.uuid }
                        if (index >= 0) {
                            wallpapers[index].volume = newVolume
                        }
                        saveCache()
                        context.startService(
                            Intent(context, TianYinWallpaperService::class.java).apply {
                                action = TianYinWallpaperService.ACTION_UPDATE_VOLUME
                                putExtra(TianYinWallpaperService.EXTRA_VOLUME, newVolume)
                            }
                        )
                    },
                    onClockColorModeAction = { newMode ->
                        val index = wallpapers.indexOfFirst { it.uuid == model.uuid }
                        if (index >= 0) {
                            wallpapers[index].clockColorMode = newMode
                        }
                        saveCache()
                        context.startService(
                            Intent(context, TianYinWallpaperService::class.java).apply {
                                action = TianYinWallpaperService.ACTION_UPDATE_CLOCK_COLOR_MODE
                                putExtra(TianYinWallpaperService.EXTRA_CLOCK_COLOR_MODE, newMode)
                            }
                        )
                    }
                )
            }
        }

        MainPreviewOverlayHost(
            visible = showLivePreview,
            statusBarTopPaddingDp = statusBarTopPaddingDp,
            onClose = { showLivePreview = false }
        )
    }
}
