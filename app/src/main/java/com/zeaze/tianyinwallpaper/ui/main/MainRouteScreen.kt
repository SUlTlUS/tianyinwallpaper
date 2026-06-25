package com.zeaze.tianyinwallpaper.ui.main

import android.app.Activity
import android.app.WallpaperManager
import android.content.Context
import android.content.ComponentName
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.SurfaceTexture
import android.media.MediaMetadataRetriever
import android.media.MediaPlayer
import android.net.Uri
import android.provider.Settings
import android.provider.OpenableColumns
import android.util.Log
import android.util.LruCache
import android.view.Surface
import android.view.TextureView
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.DrawableRes
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.ExperimentalAnimationApi
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
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
import androidx.compose.material.Icon
import androidx.compose.material.Surface
import androidx.compose.material.Text
import androidx.compose.foundation.border

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.zIndex
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
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
import com.zeaze.tianyinwallpaper.backdrop.effects.vibrancy
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
import com.zeaze.tianyinwallpaper.catalog.components.LiquidButton
import com.zeaze.tianyinwallpaper.catalog.components.LiquidToggle
import com.zeaze.tianyinwallpaper.catalog.components.WheelPicker
import com.zeaze.tianyinwallpaper.service.TianYinWallpaperService
import com.zeaze.tianyinwallpaper.service.DepthWallpaperService
import com.zeaze.tianyinwallpaper.service.MixedWallpaperPlaylist
import com.zeaze.tianyinwallpaper.service.StaticRasterWallpaperService
import com.zeaze.tianyinwallpaper.service.VideoRasterWallpaperService
import com.zeaze.tianyinwallpaper.service.raster.KeyframeTranscoder
import com.zeaze.tianyinwallpaper.ui.depth.DepthPreviewOverlay
import com.zeaze.tianyinwallpaper.ui.depth.THUMBNAIL_HEIGHT
import com.zeaze.tianyinwallpaper.ui.depth.THUMBNAIL_WIDTH
import com.zeaze.tianyinwallpaper.ui.depth.loadOrGenerateGaussianThumbnail
import com.zeaze.tianyinwallpaper.ui.raster.RasterDetailScreen
import com.zeaze.tianyinwallpaper.utils.FileUtil
import com.zeaze.tianyinwallpaper.utils.ThumbnailUtils
import com.zeaze.tianyinwallpaper.utils.DepthPrefs
import com.zeaze.tianyinwallpaper.utils.RasterPrefs
import com.zeaze.tianyinwallpaper.utils.WallpaperStoragePrefs
import com.zeaze.tianyinwallpaper.utils.AppAccentColors
import com.zeaze.tianyinwallpaper.utils.LiquidGlassPrefs
import com.zeaze.tianyinwallpaper.utils.showToast
import io.reactivex.functions.Consumer
import java.io.File
import java.io.FileOutputStream
import java.util.Collections
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.withContext
import kotlinx.coroutines.launch
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyGridState
import androidx.compose.ui.geometry.Rect
import com.zeaze.tianyinwallpaper.catalog.utils.rememberMultiRegionLuminanceSampler
import com.zeaze.tianyinwallpaper.catalog.utils.rememberRegionLuminanceState
import com.zeaze.tianyinwallpaper.backdrop.Backdrop
import kotlin.math.roundToInt

internal const val WALLPAPER_TYPE_STATIC = 0
internal const val WALLPAPER_TYPE_DYNAMIC = 1
data class DepthOnlineSogImportRequest(
    val sogUri: Uri,
    val recordThumbnailUri: String?,
    val recordId: String
)
private const val SNAP_NONE = 0
private const val SNAP_LEFT = 1
private const val SNAP_RIGHT = 2
private const val SNAP_TOP = 4
private const val SNAP_BOTTOM = 8
private const val PREF_MAIN_CUSTOM_WALLPAPER_ORDER = "mainCustomWallpaperOrder"

private suspend fun PointerInputScope.detectMainWallpaperCardTap(onTap: () -> Unit) {
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false)
        val pointerId = down.id
        val downPosition = down.position
        val touchSlop = viewConfiguration.touchSlop
        var tapCandidate = !down.isConsumed

        val upBeforeLongPress = withTimeoutOrNull(viewConfiguration.longPressTimeoutMillis) {
            while (true) {
                val event = awaitPointerEvent(PointerEventPass.Final)
                val change = event.changes.firstOrNull { it.id == pointerId }
                    ?: return@withTimeoutOrNull false
                if ((change.position - downPosition).getDistance() > touchSlop || change.isConsumed) {
                    tapCandidate = false
                }
                if (!change.pressed) {
                    return@withTimeoutOrNull tapCandidate
                }
            }
        }

        if (upBeforeLongPress == true) {
            onTap()
        } else {
            do {
                val event = awaitPointerEvent(PointerEventPass.Final)
            } while (event.changes.any { it.pressed })
        }
    }
}

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
    accentColorKey: String = AppAccentColors.DEFAULT_KEY,
    kindFilters: Set<MainWallpaperKindFilter> = emptySet(),
    sortMode: MainWallpaperSortMode = MainWallpaperSortMode.Custom,
    sortDirection: MainWallpaperSortDirection = MainWallpaperSortDirection.Descending,
    liquidGlassEnabled: Boolean = true,
    onOpenSettingPage: () -> Unit,
    onBottomBarVisibleChange: (Boolean) -> Unit,
    onOpenDepthOnlinePage: () -> Unit = {},
    onCustomSortActivated: () -> Unit = {}
) {
    val context = LocalContext.current
    val haptic = LocalHapticFeedback.current
    val isLightTheme = !useDarkTheme
    val contentColor = if (isLightTheme) Color.Black else Color.White
    val accentColor = AppAccentColors.resolve(accentColorKey, useDarkTheme)
    val containerColor = if (isLightTheme) Color(0xFFFAFAFA).copy(0.6f) else Color(0xFF121212).copy(0.4f)
    val dimColor = if (isLightTheme) Color(0xFF29293A).copy(0.23f) else Color(0xFF121212).copy(0.56f)

    val enableLiquidGlass = liquidGlassEnabled && LiquidGlassPrefs.isEnabled(context)
    val activity = context as? Activity
    val pref = remember(context) { context.getSharedPreferences(App.TIANYIN, Context.MODE_PRIVATE) }
    val editor = remember(pref) { pref.edit() }
    val coroutineScope = rememberCoroutineScope()

    val wallpapers = remember { mutableStateListOf<TianYinWallpaperModel>() }
    val selectedPositions = remember { mutableStateListOf<Int>() }
    val selectedRasterGroupIds = remember { mutableStateListOf<String>() }
    val selectedDepthWallpaperIds = remember { mutableStateListOf<String>() }
    val rasterGroups = remember { mutableStateListOf<RasterGroupModel>() }
    val depthWallpapers = remember { mutableStateListOf<DepthWallpaperModel>() }

    var selectionMode by remember { mutableStateOf(false) }
    var groupName by remember { mutableStateOf("") }

    var showWallpaperTypeDialog by remember { mutableStateOf(false) }
    var showPermissionDialog by remember { mutableStateOf(false) }
    var showDeleteSelectedDialog by remember { mutableStateOf(false) }
    var showOverwriteDialog by remember { mutableStateOf(false) }
    var showSaveDialog by remember { mutableStateOf(false) }
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

    fun launchWallpaperService(serviceClass: Class<out android.service.wallpaper.WallpaperService>) {
        val hostActivity = activity
        if (hostActivity == null) {
            Log.w("MainRouteScreen", "launchWallpaperService skipped: activity is null")
            return
        }
        hostActivity.runOnUiThread {
            runCatching { WallpaperManager.getInstance(hostActivity).clear() }
                .onFailure { Log.w("MainRouteScreen", "Clear wallpaper failed before applying", it) }
            val intent = Intent().apply {
                action = WallpaperManager.ACTION_CHANGE_LIVE_WALLPAPER
                putExtra(
                    WallpaperManager.EXTRA_LIVE_WALLPAPER_COMPONENT,
                    ComponentName(hostActivity, serviceClass)
                )
            }
            wallpaperLaunch.launch(intent)
        }
    }

    fun performApplyWallpapers(list: List<TianYinWallpaperModel>) {
        coroutineScope.launch {
            withContext(Dispatchers.IO) {
                MixedWallpaperPlaylist.clear(pref)
                FileUtil.save(context, JSON.toJSONString(list), FileUtil.wallpaperPath) {
                    launchWallpaperService(TianYinWallpaperService::class.java)
                }
            }
        }
    }

    fun applyWallpapers() {
        val regularWallpapers = wallpapers.toList()
        if (regularWallpapers.isEmpty()) {
            context.showToast("至少需要1张壁纸才能开始设置")
            return
        }
        performApplyWallpapers(regularWallpapers)
    }

    fun applySingleWallpaper(model: TianYinWallpaperModel) {
        performApplyWallpapers(listOf(model))
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
        coroutineScope.launch {
            val cacheToAppDir = pref.getBoolean(WallpaperStoragePrefs.PREF_CACHE_REGULAR_TO_APP_DIR, false)
            val list = withContext(Dispatchers.IO) {
                results.map { (uri, dynamic) ->
                    val modelId = UUID.randomUUID().toString()
                    val storedUri = if (cacheToAppDir) {
                        WallpaperStoragePrefs.copyRegularToAppDir(context, uri, modelId, dynamic) ?: uri
                    } else {
                        uri
                    }
                    TianYinWallpaperModel().apply {
                        uuid = modelId
                        createdAt = System.currentTimeMillis()
                        if (dynamic) {
                            type = WALLPAPER_TYPE_DYNAMIC
                            videoUri = storedUri.toString()
                        } else {
                            type = WALLPAPER_TYPE_STATIC
                            imgUri = storedUri.toString()
                        }
                    }
                }
            }
            wallpapers.addAll(0, list)
            saveCache()
        }
    }

    fun appendModels(results: List<Uri>, dynamic: Boolean) {
        appendMixedModels(results.map { it to dynamic })
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
            coroutineScope.launch {
                val targetIndex = replaceIndex ?: return@launch
                val oldModel = wallpapers.getOrNull(targetIndex)
                val modelId = UUID.randomUUID().toString()
                val storedUri = withContext(Dispatchers.IO) {
                    if (pref.getBoolean(WallpaperStoragePrefs.PREF_CACHE_REGULAR_TO_APP_DIR, false)) {
                        WallpaperStoragePrefs.copyRegularToAppDir(context, uri, modelId, dynamic = false) ?: uri
                    } else {
                        uri
                    }
                }
                val newModel = TianYinWallpaperModel().apply {
                    uuid = modelId
                    type = WALLPAPER_TYPE_STATIC
                    imgUri = storedUri.toString()
                    createdAt = System.currentTimeMillis()
                }
                wallpapers[targetIndex] = newModel
                oldModel?.let {
                    ThumbnailUtils.removeWallpaperCache(
                        context,
                        ThumbnailUtils.requestForWallpaper(it)
                    )
                }
                fullScreenPreviewModel = newModel
                saveCache()
                replaceIndex = null
            }
        }
    }
    val replaceVideoLaunch = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null && replaceIndex != null) {
            takePersistableUriPermissions(listOf(uri))
            coroutineScope.launch {
                val targetIndex = replaceIndex ?: return@launch
                val oldModel = wallpapers.getOrNull(targetIndex)
                val modelId = UUID.randomUUID().toString()
                val storedUri = withContext(Dispatchers.IO) {
                    if (pref.getBoolean(WallpaperStoragePrefs.PREF_CACHE_REGULAR_TO_APP_DIR, false)) {
                        WallpaperStoragePrefs.copyRegularToAppDir(context, uri, modelId, dynamic = true) ?: uri
                    } else {
                        uri
                    }
                }
                val newModel = TianYinWallpaperModel().apply {
                    uuid = modelId
                    type = WALLPAPER_TYPE_DYNAMIC
                    videoUri = storedUri.toString()
                    createdAt = System.currentTimeMillis()
                }
                wallpapers[targetIndex] = newModel
                oldModel?.let {
                    ThumbnailUtils.removeWallpaperCache(
                        context,
                        ThumbnailUtils.requestForWallpaper(it)
                    )
                }
                fullScreenPreviewModel = newModel
                saveCache()
                replaceIndex = null
            }
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
        val groupId = UUID.randomUUID().toString()
        val cacheToAppDir = pref.getBoolean(WallpaperStoragePrefs.PREF_CACHE_RASTER_TO_APP_DIR, false)
        val storedUris = uris.mapIndexed { index, uri ->
            if (cacheToAppDir) {
                WallpaperStoragePrefs.copyRasterToAppDir(
                    context = context,
                    sourceUri = uri,
                    groupId = groupId,
                    itemKey = index.toString(),
                    dynamic = false
                )?.toString() ?: uri.toString()
            } else {
                uri.toString()
            }
        }
        rasterGroups.add(
            0,
            RasterGroupModel(
                id = groupId,
                type = RasterGroupModel.TYPE_STATIC,
                imageUris = storedUris,
                createdAt = System.currentTimeMillis()
            )
        )
        persistRasterGroups()
        context.showToast("已添加光栅图片组")
    }

    fun addRasterDynamicGroup(uri: Uri) {
        takePersistableUriPermissions(listOf(uri))
        val groupId = UUID.randomUUID().toString()
        val storedUri = if (pref.getBoolean(WallpaperStoragePrefs.PREF_CACHE_RASTER_TO_APP_DIR, false)) {
            WallpaperStoragePrefs.copyRasterToAppDir(
                context = context,
                sourceUri = uri,
                groupId = groupId,
                itemKey = "video",
                dynamic = true
            ) ?: uri
        } else {
            uri
        }
        rasterGroups.add(
            0,
            RasterGroupModel(
                id = groupId,
                type = RasterGroupModel.TYPE_DYNAMIC,
                videoUri = storedUri.toString(),
                createdAt = System.currentTimeMillis()
            )
        )
        persistRasterGroups()
        context.showToast("已添加光栅视频")
    }

    fun addRasterDynamicGroups(uris: List<Uri>) {
        if (uris.isEmpty()) return
        takePersistableUriPermissions(uris)
        coroutineScope.launch {
            val cacheToAppDir = pref.getBoolean(WallpaperStoragePrefs.PREF_CACHE_RASTER_TO_APP_DIR, false)
            val groups = withContext(Dispatchers.IO) {
                uris.map { uri ->
                    val groupId = UUID.randomUUID().toString()
                    val storedUri = if (cacheToAppDir) {
                        WallpaperStoragePrefs.copyRasterToAppDir(
                            context = context,
                            sourceUri = uri,
                            groupId = groupId,
                            itemKey = "video",
                            dynamic = true
                        ) ?: uri
                    } else {
                        uri
                    }
                    RasterGroupModel(
                        id = groupId,
                        type = RasterGroupModel.TYPE_DYNAMIC,
                        videoUri = storedUri.toString(),
                        createdAt = System.currentTimeMillis()
                    )
                }
            }
            rasterGroups.addAll(0, groups)
            persistRasterGroups()
            context.showToast("已添加${groups.size}个光栅视频")
        }
    }

    fun addDepthSogWallpaper(
        uri: Uri,
        recordThumbnailUri: String? = null,
        sourceGenerationRecordId: String? = null
    ) {
        if (uri.scheme == "content") {
            takePersistableUriPermissions(listOf(uri))
        }
        val modelId = UUID.randomUUID().toString()
        val displayName = queryMainRouteDisplayName(context, uri).orEmpty()
        val localUri = if (pref.getBoolean(WallpaperStoragePrefs.PREF_CACHE_DEPTH_TO_APP_DIR, true)) {
            DepthPrefs.copySogToAppDir(context, uri, modelId)
        } else {
            null
        }
        val model = DepthWallpaperModel(
            id = modelId,
            gaussianUri = localUri?.toString() ?: uri.toString(),
            sourceGenerationRecordId = sourceGenerationRecordId.orEmpty(),
            displayName = displayName.ifBlank { uri.lastPathSegment.orEmpty() },
            createdAt = System.currentTimeMillis(),
            sensorSensitivity = DepthWallpaperModel.DEFAULT_SOG_SENSOR_SENSITIVITY,
            parallaxStrength = DepthWallpaperModel.DEFAULT_SOG_PARALLAX_STRENGTH,
            gaussianRenderMode = "web",
            cameraZoom = DepthWallpaperModel.DEFAULT_SOG_CAMERA_ZOOM,
            centerOffsetX = 0f,
            centerOffsetY = 0f,
            focusDepth = DepthWallpaperModel.DEFAULT_SOG_FOCUS_DEPTH,
            webPerformanceMode = pref.getBoolean(DepthPrefs.PREF_WEB_PERFORMANCE_MODE, true),
            gaussianMaxSplats = 800_000,
            blurStrength = 0f
        )
        copyMainRouteOnlineThumbnailToDepthCache(context, recordThumbnailUri, model.id)
        depthWallpapers.add(0, model)
        persistDepthWallpapers()
        if (recordThumbnailUri.isNullOrBlank()) {
            coroutineScope.launch(Dispatchers.IO) {
                loadOrGenerateGaussianThumbnail(context.applicationContext, model, THUMBNAIL_WIDTH, THUMBNAIL_HEIGHT)
            }
        }
        context.showToast("已添加景深 SOG")
    }

    fun addDepthSogWallpapers(uris: List<Uri>) {
        if (uris.isEmpty()) return
        takePersistableUriPermissions(uris.filter { it.scheme == "content" })
        coroutineScope.launch {
            val cacheToAppDir = pref.getBoolean(WallpaperStoragePrefs.PREF_CACHE_DEPTH_TO_APP_DIR, true)
            val models = withContext(Dispatchers.IO) {
                uris.map { uri ->
                    val modelId = UUID.randomUUID().toString()
                    val displayName = queryMainRouteDisplayName(context, uri).orEmpty()
                    val localUri = if (cacheToAppDir) DepthPrefs.copySogToAppDir(context, uri, modelId) else null
                    DepthWallpaperModel(
                        id = modelId,
                        gaussianUri = localUri?.toString() ?: uri.toString(),
                        displayName = displayName.ifBlank { uri.lastPathSegment.orEmpty() },
                        createdAt = System.currentTimeMillis(),
                        sensorSensitivity = DepthWallpaperModel.DEFAULT_SOG_SENSOR_SENSITIVITY,
                        parallaxStrength = DepthWallpaperModel.DEFAULT_SOG_PARALLAX_STRENGTH,
                        gaussianRenderMode = "web",
                        cameraZoom = DepthWallpaperModel.DEFAULT_SOG_CAMERA_ZOOM,
                        centerOffsetX = 0f,
                        centerOffsetY = 0f,
                        focusDepth = DepthWallpaperModel.DEFAULT_SOG_FOCUS_DEPTH,
                        webPerformanceMode = pref.getBoolean(DepthPrefs.PREF_WEB_PERFORMANCE_MODE, true),
                        gaussianMaxSplats = 800_000,
                        blurStrength = 0f
                    )
                }
            }
            depthWallpapers.addAll(0, models)
            persistDepthWallpapers()
            models.forEach { model ->
                launch(Dispatchers.IO) {
                    loadOrGenerateGaussianThumbnail(
                        context.applicationContext,
                        model,
                        THUMBNAIL_WIDTH,
                        THUMBNAIL_HEIGHT
                    )
                }
            }
            context.showToast("已添加${models.size}个景深 SOG")
        }
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

    val rasterDynamicLaunch = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        addRasterDynamicGroups(uris)
    }

    val depthSogLaunch = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        addDepthSogWallpapers(uris)
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
                        val now = System.currentTimeMillis()
                        var migrated = false
                        validWallpapers.forEachIndexed { index, model ->
                            if (model.createdAt <= 0L) {
                                model.createdAt = now - index
                                migrated = true
                            }
                        }
                        wallpapers.addAll(validWallpapers)
                        groupName = pref.getString("wallpaperTvCache", "") ?: ""
                        if (migrated) saveCache()
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
        selectedRasterGroupIds.clear()
        selectedDepthWallpaperIds.clear()
    }

    fun exitSelectionMode() {
        selectionMode = false
        selectedPositions.clear()
        selectedRasterGroupIds.clear()
        selectedDepthWallpaperIds.clear()
    }

    fun dismissCurrentDialog() {
        showWallpaperTypeDialog = false
        showPermissionDialog = false
        showDeleteSelectedDialog = false
        showOverwriteDialog = false
        showSaveDialog = false
        timeDialogIndex = null
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
    fun selectedItemCount(): Int {
        return selectedPositions.size + selectedRasterGroupIds.size + selectedDepthWallpaperIds.size
    }

    fun totalSelectableItemCount(): Int {
        return wallpapers.size + rasterGroups.size + depthWallpapers.size
    }

    fun publishSelectionState() {
        val totalCount = totalSelectableItemCount()
        val isAllSelected = selectedItemCount() == totalCount && totalCount > 0
        RxBus.postWithCode(RxConstants.RX_SELECTION_MODE_CHANGED, SelectionBarState(selectionMode, isAllSelected))
    }

    fun buildThumbnailRequest(model: TianYinWallpaperModel): ThumbnailUtils.Request {
        return ThumbnailUtils.requestForWallpaper(model)
    }

    val mediaSizeCache = remember { mutableMapOf<String, Long>() }
    var recentOpenedVersion by remember { mutableStateOf(0) }
    val customWallpaperOrder = remember(pref) {
        mutableStateListOf<String>().apply {
            val savedOrder = runCatching {
                JSON.parseArray(
                    pref.getString(PREF_MAIN_CUSTOM_WALLPAPER_ORDER, "[]"),
                    String::class.java
                ) ?: emptyList()
            }.getOrDefault(emptyList())
            addAll(savedOrder.filter { it.isNotBlank() }.distinct())
        }
    }

    fun resolveMediaSize(source: String?): Long {
        if (source.isNullOrBlank()) return 0L
        return mediaSizeCache.getOrPut(source) {
            runCatching {
                when {
                    source.startsWith("content://") -> {
                        context.contentResolver.query(
                            Uri.parse(source),
                            arrayOf(OpenableColumns.SIZE),
                            null,
                            null,
                            null
                        )?.use { cursor ->
                            val index = cursor.getColumnIndex(OpenableColumns.SIZE)
                            if (index >= 0 && cursor.moveToFirst()) cursor.getLong(index) else 0L
                        } ?: 0L
                    }
                    source.startsWith("file://") -> File(Uri.parse(source).path.orEmpty()).length()
                    else -> File(source).length()
                }
            }.getOrDefault(0L)
        }
    }

    fun wallpaperSortId(model: TianYinWallpaperModel, index: Int): String {
        val stableId = model.uuid?.takeIf { it.isNotBlank() }
            ?: model.imgUri?.takeIf { it.isNotBlank() }
            ?: model.videoUri?.takeIf { it.isNotBlank() }
            ?: model.imgPath?.takeIf { it.isNotBlank() }
            ?: model.videoPath?.takeIf { it.isNotBlank() }
            ?: index.toString()
        return "wallpaper:$stableId"
    }

    fun rasterSortId(group: RasterGroupModel): String = "raster:${group.id}"

    fun depthSortId(model: DepthWallpaperModel): String = "depth:${model.id}"

    fun unifiedSortId(item: MainUnifiedWallpaperItem): String = when (item) {
        is MainUnifiedWallpaperItem.Wallpaper -> wallpaperSortId(item.model, item.index)
        is MainUnifiedWallpaperItem.Raster -> rasterSortId(item.group)
        is MainUnifiedWallpaperItem.Depth -> depthSortId(item.model)
    }

    fun persistCustomWallpaperOrder(order: List<String>) {
        pref.edit()
            .putString(PREF_MAIN_CUSTOM_WALLPAPER_ORDER, JSON.toJSONString(order))
            .commit()
    }

    fun markRecentOpened(id: String) {
        pref.edit().putLong("main_recent_opened_$id", System.currentTimeMillis()).apply()
        recentOpenedVersion++
    }

    fun wallpaperSize(model: TianYinWallpaperModel): Long {
        return if (model.type == WALLPAPER_TYPE_DYNAMIC) {
            resolveMediaSize(model.videoUri ?: model.videoPath)
        } else {
            resolveMediaSize(model.imgUri ?: model.imgPath)
        }
    }

    fun itemTypeRank(item: MainUnifiedWallpaperItem): Int = when (item) {
        is MainUnifiedWallpaperItem.Wallpaper -> if (item.model.type == WALLPAPER_TYPE_DYNAMIC) 1 else 0
        is MainUnifiedWallpaperItem.Raster -> if (item.group.type == RasterGroupModel.TYPE_DYNAMIC) 3 else 2
        is MainUnifiedWallpaperItem.Depth -> 4
    }

    fun itemAddedRank(item: MainUnifiedWallpaperItem): Long = when (item) {
        is MainUnifiedWallpaperItem.Wallpaper -> item.model.createdAt.takeIf { it > 0L } ?: -item.index.toLong()
        is MainUnifiedWallpaperItem.Raster -> item.group.createdAt
        is MainUnifiedWallpaperItem.Depth -> item.model.createdAt
    }

    fun itemSizeRank(item: MainUnifiedWallpaperItem): Long = when (item) {
        is MainUnifiedWallpaperItem.Wallpaper -> wallpaperSize(item.model)
        is MainUnifiedWallpaperItem.Raster -> {
            if (item.group.type == RasterGroupModel.TYPE_DYNAMIC) {
                resolveMediaSize(item.group.videoUri)
            } else {
                item.group.imageUris.sumOf { resolveMediaSize(it) }
            }
        }
        is MainUnifiedWallpaperItem.Depth -> resolveMediaSize(item.model.gaussianUri)
    }

    fun itemRecentRank(item: MainUnifiedWallpaperItem): Long {
        recentOpenedVersion
        return when (item) {
            is MainUnifiedWallpaperItem.Wallpaper -> pref.getLong("main_recent_opened_${wallpaperSortId(item.model, item.index)}", 0L)
            is MainUnifiedWallpaperItem.Raster -> pref.getLong("main_recent_opened_${rasterSortId(item.group)}", 0L)
            is MainUnifiedWallpaperItem.Depth -> pref.getLong("main_recent_opened_${depthSortId(item.model)}", 0L)
        }
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
        showLivePreview
    ) {
        onBottomBarVisibleChange(
            !selectionMode &&
                fullScreenPreviewModel == null &&
                rasterDetailGroup == null &&
                depthPreviewModel == null &&
                !showLivePreview
        )
    }

    // 发布选择模式状态
    LaunchedEffect(
        selectionMode,
        selectedPositions.size,
        selectedRasterGroupIds.size,
        selectedDepthWallpaperIds.size,
        wallpapers.size,
        rasterGroups.size,
        depthWallpapers.size
    ) {
        publishSelectionState()
    }
    
    DisposableEffect(Unit) {
        val addDisposable = RxBus.getDefault()
            .toObservableWithCode(RxConstants.RX_ADD_WALLPAPER, TianYinWallpaperModel::class.java)
            .subscribe(Consumer { o ->
                if (o.createdAt <= 0L) o.createdAt = System.currentTimeMillis()
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
                val now = System.currentTimeMillis()
                list.forEachIndexed { index, model ->
                    if (model.createdAt <= 0L) model.createdAt = now - index
                }
                wallpapers.clear()
                wallpapers.addAll(list)
                groupName = data.name ?: ""
                saveCache()
                context.showToast("壁纸列表已覆盖")
            }

        val importOnlineDepthDisposable = RxBus.getDefault()
            .toObservableWithCode(RxConstants.RX_IMPORT_ONLINE_DEPTH_SOG, DepthOnlineSogImportRequest::class.java)
            .subscribe { request ->
                addDepthSogWallpaper(
                    request.sogUri,
                    request.recordThumbnailUri,
                    request.recordId
                )
            }

        // 选择模式操作监听
        val selectionCancelDisposable = RxBus.getDefault()
            .toObservableWithCode(RxConstants.RX_SELECTION_CANCEL, Unit::class.java)
            .subscribe { exitSelectionMode() }

        val selectionDeleteDisposable = RxBus.getDefault()
            .toObservableWithCode(RxConstants.RX_SELECTION_DELETE, Unit::class.java)
            .subscribe {
                if (selectedItemCount() == 0) {
                    context.showToast(context.getString(R.string.no_selected_tip))
                } else {
                    showDeleteSelectedDialog = true
                }
            }

        val selectionToggleAllDisposable = RxBus.getDefault()
            .toObservableWithCode(RxConstants.RX_SELECTION_TOGGLE_ALL, Unit::class.java)
            .subscribe {
                val totalCount = totalSelectableItemCount()
                val isAllSelected = selectedItemCount() == totalCount && totalCount > 0
                selectedPositions.clear()
                selectedRasterGroupIds.clear()
                selectedDepthWallpaperIds.clear()
                if (!isAllSelected) {
                    wallpapers.indices.forEach { selectedPositions.add(it) }
                    rasterGroups.forEach { selectedRasterGroupIds.add(it.id) }
                    depthWallpapers.forEach { selectedDepthWallpaperIds.add(it.id) }
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
            importOnlineDepthDisposable.dispose()
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


    val allCustomUnifiedItems = buildMainUnifiedWallpaperItems(
        wallpapers = wallpapers,
        rasterGroups = rasterGroups,
        depthWallpapers = depthWallpapers,
        kindFilters = emptySet()
    )
    val allCustomIds = allCustomUnifiedItems.map(::unifiedSortId)
    val normalizedCustomOrder = allCustomIds
        .filterNot { it in customWallpaperOrder }
        .plus(customWallpaperOrder.filter { it in allCustomIds })

    val customOrderRank = normalizedCustomOrder.withIndex().associate { it.value to it.index }
    val customUnifiedItems = buildMainUnifiedWallpaperItems(
        wallpapers = wallpapers,
        rasterGroups = rasterGroups,
        depthWallpapers = depthWallpapers,
        kindFilters = kindFilters
    ).sortedBy { customOrderRank[unifiedSortId(it)] ?: Int.MAX_VALUE }
    val unifiedItems = if (sortMode == MainWallpaperSortMode.Custom) {
        customUnifiedItems
    } else {
        val sorted = when (sortMode) {
            MainWallpaperSortMode.Custom -> customUnifiedItems
            MainWallpaperSortMode.AddedDate -> customUnifiedItems.sortedWith(
                compareBy<MainUnifiedWallpaperItem> { itemAddedRank(it) }.thenBy { itemTypeRank(it) }
            )
            MainWallpaperSortMode.Type -> customUnifiedItems.sortedWith(
                compareBy<MainUnifiedWallpaperItem> { itemTypeRank(it) }.thenByDescending { itemAddedRank(it) }
            )
            MainWallpaperSortMode.Size -> customUnifiedItems.sortedWith(
                compareBy<MainUnifiedWallpaperItem> { itemSizeRank(it) }.thenBy { itemTypeRank(it) }
            )
            MainWallpaperSortMode.RecentOpened -> customUnifiedItems.sortedWith(
                compareBy<MainUnifiedWallpaperItem> { itemRecentRank(it) }.thenBy { itemTypeRank(it) }
            )
        }
        if (sortDirection == MainWallpaperSortDirection.Descending) sorted.asReversed() else sorted
    }

    val reorderableState = rememberReorderableLazyGridState(
        lazyGridState = gridState,
        onMove = { from, to ->
            if (from.index == to.index) return@rememberReorderableLazyGridState
            val visibleIds = unifiedItems.map(::unifiedSortId).toMutableList()
            if (from.index !in visibleIds.indices || to.index !in visibleIds.indices) {
                return@rememberReorderableLazyGridState
            }
            val movedId = visibleIds.removeAt(from.index)
            visibleIds.add(to.index, movedId)

            val visibleIdSet = visibleIds.toSet()
            val visibleIterator = visibleIds.iterator()
            val nextOrder = if (sortMode == MainWallpaperSortMode.Custom) {
                normalizedCustomOrder.map { id ->
                    if (id in visibleIdSet) visibleIterator.next() else id
                }
            } else {
                visibleIds + normalizedCustomOrder.filterNot { it in visibleIdSet }
            }
            customWallpaperOrder.clear()
            customWallpaperOrder.addAll(nextOrder)
            persistCustomWallpaperOrder(nextOrder)
        }
    )

    val contentLayerBackground = if (useDarkTheme) Color(0xFF0A0A0C) else MaterialTheme.colors.background
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
                    top = statusBarTopPaddingDp + 76.dp,
                    bottom = if (selectionMode) 90.dp else 110.dp
                ),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                items(
                    unifiedItems,
                    key = { item -> unifiedSortId(item) }
                ) { item ->
                    val itemKey = unifiedSortId(item)
                    ReorderableItem(reorderableState, key = itemKey) { isDragging ->
                        val dragModifier = Modifier
                            .fillMaxWidth()
                            .zIndex(if (isDragging) 1f else 0f)
                            .graphicsLayer {
                                scaleX = if (isDragging) 1.05f else 1f
                                scaleY = if (isDragging) 1.05f else 1f
                                shadowElevation = if (isDragging) 8.dp.toPx() else 0f
                                shape = RoundedCornerShape(16.dp)
                                clip = true
                            }

                        when (item) {
                            is MainUnifiedWallpaperItem.Wallpaper -> {
                                val selected = selectedPositions.contains(item.index)
                                val itemModifier = dragModifier
                                    .let { base ->
                                        val onDragStarted = {
                                            onCustomSortActivated()
                                            if (!selectionMode) enterSelectionMode()
                                            if (!selectedPositions.contains(item.index)) selectedPositions.add(item.index)
                                        }
                                        if (selectionMode) {
                                            base.draggableHandle(onDragStarted = { onDragStarted() })
                                        } else {
                                            base.longPressDraggableHandle(onDragStarted = { onDragStarted() })
                                        }
                                    }
                                    .pointerInput(itemKey, selectionMode, selected) {
                                        detectMainWallpaperCardTap {
                                            if (selectionMode) {
                                                if (selected) selectedPositions.remove(item.index) else selectedPositions.add(item.index)
                                            } else {
                                                markRecentOpened(wallpaperSortId(item.model, item.index))
                                                fullScreenPreviewModel = item.model
                                            }
                                        }
                                    }
                                MainUnifiedWallpaperCard(
                                    modifier = itemModifier,
                                    model = item.model,
                                    selectionMode = selectionMode,
                                    isSelected = selected,
                                    enableLiquidGlass = enableLiquidGlass,
                                    useDarkTheme = useDarkTheme,
                                    onClick = {
                                        if (selectionMode) {
                                            if (selected) selectedPositions.remove(item.index) else selectedPositions.add(item.index)
                                        } else {
                                            markRecentOpened(wallpaperSortId(item.model, item.index))
                                            fullScreenPreviewModel = item.model
                                        }
                                    },
                                    onLongClick = {}
                                )
                            }
                            is MainUnifiedWallpaperItem.Raster -> {
                                val selected = selectedRasterGroupIds.contains(item.group.id)
                                val itemModifier = dragModifier
                                    .let { base ->
                                        val onDragStarted = {
                                            onCustomSortActivated()
                                            if (!selectionMode) enterSelectionMode()
                                            if (!selectedRasterGroupIds.contains(item.group.id)) selectedRasterGroupIds.add(item.group.id)
                                        }
                                        if (selectionMode) {
                                            base.draggableHandle(onDragStarted = { onDragStarted() })
                                        } else {
                                            base.longPressDraggableHandle(onDragStarted = { onDragStarted() })
                                        }
                                    }
                                    .pointerInput(itemKey, selectionMode, selected) {
                                        detectMainWallpaperCardTap {
                                            if (selectionMode) {
                                                if (selected) selectedRasterGroupIds.remove(item.group.id) else selectedRasterGroupIds.add(item.group.id)
                                            } else {
                                                markRecentOpened(rasterSortId(item.group))
                                                rasterDetailGroup = item.group
                                            }
                                        }
                                    }
                                MainUnifiedRasterCard(
                                    modifier = itemModifier,
                                    group = item.group,
                                    selectionMode = selectionMode,
                                    isSelected = selected,
                                    enableLiquidGlass = enableLiquidGlass,
                                    useDarkTheme = useDarkTheme,
                                    onClick = {
                                        if (selectionMode) {
                                            if (selected) selectedRasterGroupIds.remove(item.group.id) else selectedRasterGroupIds.add(item.group.id)
                                        } else {
                                            markRecentOpened(rasterSortId(item.group))
                                            rasterDetailGroup = item.group
                                        }
                                    },
                                    onLongClick = {}
                                )
                            }
                            is MainUnifiedWallpaperItem.Depth -> {
                                val selected = selectedDepthWallpaperIds.contains(item.model.id)
                                val itemModifier = dragModifier
                                    .let { base ->
                                        val onDragStarted = {
                                            onCustomSortActivated()
                                            if (!selectionMode) enterSelectionMode()
                                            if (!selectedDepthWallpaperIds.contains(item.model.id)) selectedDepthWallpaperIds.add(item.model.id)
                                        }
                                        if (selectionMode) {
                                            base.draggableHandle(onDragStarted = { onDragStarted() })
                                        } else {
                                            base.longPressDraggableHandle(onDragStarted = { onDragStarted() })
                                        }
                                    }
                                    .pointerInput(itemKey, selectionMode, selected) {
                                        detectMainWallpaperCardTap {
                                            if (selectionMode) {
                                                if (selected) selectedDepthWallpaperIds.remove(item.model.id) else selectedDepthWallpaperIds.add(item.model.id)
                                            } else {
                                                markRecentOpened(depthSortId(item.model))
                                                depthPreviewModel = item.model
                                            }
                                        }
                                    }
                                MainUnifiedDepthCard(
                                    modifier = itemModifier,
                                    model = item.model,
                                    selectionMode = selectionMode,
                                    isSelected = selected,
                                    enableLiquidGlass = enableLiquidGlass,
                                    useDarkTheme = useDarkTheme,
                                    onClick = {
                                        if (selectionMode) {
                                            if (selected) selectedDepthWallpaperIds.remove(item.model.id) else selectedDepthWallpaperIds.add(item.model.id)
                                        } else {
                                            markRecentOpened(depthSortId(item.model))
                                            depthPreviewModel = item.model
                                        }
                                    },
                                    onLongClick = {}
                                )
                            }
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
            backdrop = liquidBackdrop
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
                val sheetBackdrop = if (enableLiquidGlass) rememberLayerBackdrop() else null
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
                            exportedBackdrop = sheetBackdrop,
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
                                onPickImageWallpaper = {
                                    showWallpaperTypeDialog = false
                                    imageLaunch.launch(arrayOf("image/*"))
                                },
                                onPickVideoWallpaper = {
                                    showWallpaperTypeDialog = false
                                    videoLaunch.launch(arrayOf("video/*"))
                                },
                                onPickRasterImages = {
                                    showWallpaperTypeDialog = false
                                    rasterStaticLaunch.launch(arrayOf("image/*"))
                                },
                                onPickRasterVideo = {
                                    showWallpaperTypeDialog = false
                                    rasterDynamicLaunch.launch(arrayOf("video/*"))
                                },
                                onPickDepthSog = {
                                    showWallpaperTypeDialog = false
                                    depthSogLaunch.launch(arrayOf("*/*"))
                                },
                                onOpenOnlineSog = {
                                    showWallpaperTypeDialog = false
                                    onOpenDepthOnlinePage()
                                },
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
                                                val rasterIds = selectedRasterGroupIds.toSet()
                                                val depthIds = selectedDepthWallpaperIds.toSet()

                                                if (rasterIds.isNotEmpty()) {
                                                    val removedVideoUris = rasterGroups
                                                        .asSequence()
                                                        .filter { it.id in rasterIds && it.type == RasterGroupModel.TYPE_DYNAMIC }
                                                        .mapNotNull { it.videoUri?.takeIf(String::isNotBlank) }
                                                        .toSet()
                                                    rasterGroups.removeAll { it.id in rasterIds }
                                                    RasterPrefs.saveGroups(pref, rasterGroups)
                                                    val retainedVideoUris = rasterGroups
                                                        .asSequence()
                                                        .mapNotNull { it.videoUri?.takeIf(String::isNotBlank) }
                                                        .toSet()
                                                    val unusedVideoUris = removedVideoUris - retainedVideoUris
                                                    if (unusedVideoUris.isNotEmpty()) {
                                                        coroutineScope.launch(Dispatchers.IO) {
                                                            val transcoder = KeyframeTranscoder(context.applicationContext)
                                                            unusedVideoUris.forEach(transcoder::deleteCacheFor)
                                                        }
                                                    }
                                                    if (rasterDetailGroup?.id?.let { it in rasterIds } == true) {
                                                        rasterDetailGroup = null
                                                        rasterStaticEditorGroupId = null
                                                        rasterVideoEditorGroupId = null
                                                    }
                                                }

                                                if (depthIds.isNotEmpty()) {
                                                    depthIds.forEach { DepthPrefs.deleteSogDir(context, it) }
                                                    depthWallpapers.removeAll { it.id in depthIds }
                                                    DepthPrefs.saveWallpapers(pref, depthWallpapers)
                                                    if (depthPreviewModel?.id?.let { it in depthIds } == true) depthPreviewModel = null
                                                }

                                                val indexes = selectedPositions.toMutableList()
                                                Collections.sort(indexes, Collections.reverseOrder())
                                                for (index in indexes) {
                                                    removeWallpaperAt(index)
                                                }
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


        val currentRasterDetail = rasterDetailGroup?.let { selected ->
            rasterGroups.firstOrNull { it.id == selected.id } ?: selected
        }
        currentRasterDetail?.let { group ->
            BackHandler(
                enabled = rasterStaticEditorGroupId == null && rasterVideoEditorGroupId == null
            ) {
                rasterDetailGroup = null
                rasterStaticEditorGroupId = null
                rasterVideoEditorGroupId = null
            }
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .zIndex(10f)
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
                    onStaticEditorReplaceAll = { context.showToast("请在光栅专页编辑图集") },
                    onStaticEditorAppend = { context.showToast("请在光栅专页追加图片") },
                    onStaticEditorReplaceSingle = { _, _ -> context.showToast("请在光栅专页替换单张图片") },
                    onStaticEditorMove = { editorGroup, fromIndex, toIndex ->
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
                    onStaticEditorDeleteSingle = { _, _ -> context.showToast("请在光栅专页删除图片") },
                    videoEditorGroupId = rasterVideoEditorGroupId,
                    onVideoEditorDismiss = { rasterVideoEditorGroupId = null },
                    onVideoEditorReplaceVideo = { context.showToast("请在光栅专页替换视频") },
                    onSensorWidthChanged = { editorGroup, value -> updateRasterGroupById(editorGroup.id) { it.copy(sensorWidth = value) } },
                    onSensorWidthChangeFinished = { _, _ -> persistAndRefreshRasterGroup() },
                    onEffectTypeChanged = { editorGroup, value -> updateRasterGroupById(editorGroup.id) { it.copy(effectType = value) }; persistAndRefreshRasterGroup(editorGroup.id) },
                    onTransitionBandChanged = { editorGroup, value -> updateRasterGroupById(editorGroup.id) { it.copy(transitionBand = value) } },
                    onTransitionBandChangeFinished = { _, _ -> persistAndRefreshRasterGroup() },
                    onEdgeSoftnessChanged = { editorGroup, value -> updateRasterGroupById(editorGroup.id) { it.copy(edgeSoftness = value) } },
                    onEdgeSoftnessChangeFinished = { _, _ -> persistAndRefreshRasterGroup() },
                    onStripedWavelengthChanged = { editorGroup, value -> updateRasterGroupById(editorGroup.id) { it.copy(stripedWavelength = value) } },
                    onStripedWavelengthChangeFinished = { _, _ -> persistAndRefreshRasterGroup() },
                    onStripedAmplitudeChanged = { editorGroup, value -> updateRasterGroupById(editorGroup.id) { it.copy(stripedAmplitude = value) } },
                    onStripedAmplitudeChangeFinished = { _, _ -> persistAndRefreshRasterGroup() },
                    onNarrowWavelengthChanged = { editorGroup, value -> updateRasterGroupById(editorGroup.id) { it.copy(narrowWavelength = value) } },
                    onNarrowWavelengthChangeFinished = { _, _ -> persistAndRefreshRasterGroup() },
                    onNarrowAmplitudeChanged = { editorGroup, value -> updateRasterGroupById(editorGroup.id) { it.copy(narrowAmplitude = value) } },
                    onNarrowAmplitudeChangeFinished = { _, _ -> persistAndRefreshRasterGroup() },
                    onGlassAnimEnabledChanged = { editorGroup, value -> updateRasterGroupById(editorGroup.id) { it.copy(glassAnimEnabled = value) }; persistAndRefreshRasterGroup(editorGroup.id) },
                    onGlassBandWidthChanged = { editorGroup, value -> updateRasterGroupById(editorGroup.id) { it.copy(glassBandWidth = value) } },
                    onGlassBandWidthChangeFinished = { _, _ -> persistAndRefreshRasterGroup() },
                    onDeadZoneEnabledChanged = { editorGroup, value -> updateRasterGroupById(editorGroup.id) { it.copy(deadZoneEnabled = value) }; persistAndRefreshRasterGroup(editorGroup.id) },
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
            BackHandler {
                persistDepthWallpapers()
                depthPreviewModel = null
            }
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .zIndex(10f)
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
                    onModelChange = { updated -> updateDepthPreview(updated) },
                    modifier = Modifier.fillMaxSize()
                )
            }
        }

        // Full screen preview page in the activity window so liquid glass can sample its content.
        val currentPreviewModel: TianYinWallpaperModel? = fullScreenPreviewModel
        currentPreviewModel?.let { model ->
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .zIndex(10f)
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
                    }
                )
            }
        }

        MainPreviewOverlayHost(
            visible = showLivePreview,
            statusBarTopPaddingDp = statusBarTopPaddingDp,
            enableLiquidGlass = enableLiquidGlass,
            onClose = { showLivePreview = false },
            modifier = Modifier
                .fillMaxSize()
                .zIndex(10f)
        )
    }
}


@Composable
private fun MainWallpaperFilterBar(
    selectedFilter: MainWallpaperFilter,
    onFilterSelected: (MainWallpaperFilter) -> Unit,
    contentColor: Color,
    accentColor: Color,
    containerColor: Color,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        MainWallpaperFilter.values().forEach { filter ->
            val selected = selectedFilter == filter
            Box(
                modifier = Modifier
                    .clip(Capsule())
                    .background(if (selected) accentColor.copy(alpha = 0.9f) else containerColor)
                    .clickable { onFilterSelected(filter) }
                    .padding(horizontal = 14.dp, vertical = 8.dp),
                contentAlignment = Alignment.Center
            ) {
                BasicText(
                    text = filter.label,
                    style = TextStyle(
                        color = if (selected) Color.White else contentColor,
                        fontSize = 14.sp,
                        fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal
                    )
                )
            }
        }
    }
}

@Composable
private fun MainThumbnailTypeIcon(
    @DrawableRes iconRes: Int,
    contentDescription: String,
    sourceBitmap: Bitmap?,
    viewportAspectRatio: Float,
    viewportSizePx: IntSize,
    useDarkTheme: Boolean,
    bakeKey: Any?,
    modifier: Modifier = Modifier
) {
    val badgeShape = RoundedCornerShape(999.dp)
    val density = LocalDensity.current
    val targetPx = remember(density) { with(density) { 28.dp.roundToPx().coerceAtLeast(1) } }
    val marginPx = remember(density) { with(density) { 4.dp.roundToPx().coerceAtLeast(0) } }
    val bakedBadge = remember(sourceBitmap, iconRes, useDarkTheme, bakeKey, targetPx, marginPx, viewportAspectRatio, viewportSizePx) {
        bakeThumbnailTypeIcon(
            source = sourceBitmap,
            targetPx = targetPx,
            viewportAspectRatio = viewportAspectRatio,
            viewportWidthPx = viewportSizePx.width,
            viewportHeightPx = viewportSizePx.height,
            useDarkTheme = useDarkTheme,
            badgeMarginPx = marginPx
        )
    }
    val iconTint = if (useDarkTheme) Color.White else Color(0xDD111318)
    val glassModifier = modifier
        .padding(4.dp)
        .size(28.dp)
        .clip(badgeShape)
        .border(
            width = 1.dp,
            color = if (useDarkTheme) Color.White.copy(alpha = 0.28f) else Color.White.copy(alpha = 0.58f),
            shape = badgeShape
        )

    Box(
        modifier = glassModifier,
        contentAlignment = Alignment.Center
    ) {
        if (bakedBadge != null) {
            Image(
                bitmap = bakedBadge.bitmap,
                contentDescription = null,
                contentScale = ContentScale.FillBounds,
                modifier = Modifier.matchParentSize()
            )
        } else {
            Box(
                modifier = Modifier
                    .matchParentSize()
                    .background(if (useDarkTheme) Color.Black.copy(alpha = 0.34f) else Color.White.copy(alpha = 0.42f))
            )
        }
        Icon(
            painter = painterResource(iconRes),
            contentDescription = contentDescription,
            modifier = Modifier.size(16.dp),
            tint = bakedBadge?.iconTint ?: iconTint
        )
    }
}

private data class BakedThumbnailTypeIcon(
    val bitmap: ImageBitmap,
    val iconTint: Color
)

private fun bakeThumbnailTypeIcon(
    source: Bitmap?,
    targetPx: Int,
    viewportAspectRatio: Float,
    viewportWidthPx: Int,
    viewportHeightPx: Int,
    useDarkTheme: Boolean,
    badgeMarginPx: Int
): BakedThumbnailTypeIcon? {
    if (
        source == null ||
        source.isRecycled ||
        source.width <= 0 ||
        source.height <= 0 ||
        targetPx <= 0 ||
        viewportWidthPx <= 0 ||
        viewportHeightPx <= 0
    ) {
        return null
    }
    return runCatching {
        val sourceAspectRatio = source.width.toFloat() / source.height.toFloat()
        val safeViewportAspectRatio = viewportAspectRatio
            .takeIf { it.isFinite() && it > 0f }
            ?: sourceAspectRatio
        val visibleRect = if (sourceAspectRatio > safeViewportAspectRatio) {
            val visibleWidth = (source.height * safeViewportAspectRatio)
                .roundToInt()
                .coerceIn(1, source.width)
            RectF(
                (source.width - visibleWidth) / 2f,
                0f,
                (source.width + visibleWidth) / 2f,
                source.height.toFloat()
            )
        } else {
            val visibleHeight = (source.width / safeViewportAspectRatio)
                .roundToInt()
                .coerceIn(1, source.height)
            RectF(
                0f,
                (source.height - visibleHeight) / 2f,
                source.width.toFloat(),
                (source.height + visibleHeight) / 2f
            )
        }
        val scale = maxOf(
            viewportWidthPx / visibleRect.width().coerceAtLeast(1f),
            viewportHeightPx / visibleRect.height().coerceAtLeast(1f)
        )
        val sourceSampleSide = (targetPx / scale)
            .roundToInt()
            .coerceIn(1, minOf(source.width, source.height))
        val sampleLeftInViewport = (viewportWidthPx - targetPx - badgeMarginPx).coerceAtLeast(0)
        val sampleTopInViewport = badgeMarginPx.coerceAtLeast(0)
        val srcLeft = (visibleRect.left + sampleLeftInViewport / scale)
            .roundToInt()
            .coerceIn(0, source.width - sourceSampleSide)
        val srcTop = (visibleRect.top + sampleTopInViewport / scale)
            .roundToInt()
            .coerceIn(0, source.height - sourceSampleSide)
        val crop = Bitmap.createBitmap(source, srcLeft, srcTop, sourceSampleSide, sourceSampleSide)
        val blurSeedSize = 8.coerceAtMost(targetPx).coerceAtLeast(1)
        val blurSeed = Bitmap.createScaledBitmap(crop, blurSeedSize, blurSeedSize, true)
        val frosted = Bitmap.createScaledBitmap(blurSeed, targetPx, targetPx, true)
        if (crop !== source && crop !== blurSeed && crop !== frosted) crop.recycle()
        if (blurSeed !== crop && blurSeed !== frosted) blurSeed.recycle()

        val output = Bitmap.createBitmap(targetPx, targetPx, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(output)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        canvas.drawBitmap(frosted, 0f, 0f, paint)
        if (frosted !== source && frosted !== crop && frosted !== output) frosted.recycle()

        val rect = RectF(0f, 0f, targetPx.toFloat(), targetPx.toFloat())
        paint.style = Paint.Style.FILL
        if (useDarkTheme) {
            paint.color = android.graphics.Color.argb(92, 0, 0, 0)
            canvas.drawRoundRect(rect, targetPx / 2f, targetPx / 2f, paint)
            paint.color = android.graphics.Color.argb(24, 255, 255, 255)
            canvas.drawRoundRect(rect, targetPx / 2f, targetPx / 2f, paint)
        } else {
            paint.color = android.graphics.Color.argb(122, 255, 255, 255)
            canvas.drawRoundRect(rect, targetPx / 2f, targetPx / 2f, paint)
            paint.color = android.graphics.Color.argb(12, 0, 0, 0)
            canvas.drawRoundRect(rect, targetPx / 2f, targetPx / 2f, paint)
        }
        BakedThumbnailTypeIcon(
            bitmap = output.asImageBitmap(),
            iconTint = if (useDarkTheme) Color.White else Color(0xDD111318)
        )
    }.getOrNull()
}

@Composable
private fun rememberThumbnailBitmap(request: ThumbnailUtils.Request?): Bitmap? {
    val context = LocalContext.current
    val bitmapState = produceState<Bitmap?>(
        initialValue = request?.let { ThumbnailUtils.getFromCacheOrDisk(context.applicationContext, it) },
        request?.cacheKey
    ) {
        val safeRequest = request ?: run {
            value = null
            return@produceState
        }
        if (value != null) return@produceState
        value = withContext(Dispatchers.IO) {
            ThumbnailUtils.loadThumbnail(context.applicationContext, safeRequest)
        }
    }
    return bitmapState.value
}

@Composable
private fun rememberDepthThumbnailBitmap(model: DepthWallpaperModel): Bitmap? {
    val context = LocalContext.current
    val bitmapState = produceState<Bitmap?>(initialValue = null, model.id, model.gaussianUri) {
        value = withContext(Dispatchers.IO) {
            loadOrGenerateGaussianThumbnail(
                context.applicationContext,
                model,
                THUMBNAIL_WIDTH,
                THUMBNAIL_HEIGHT
            )
        }
    }
    return bitmapState.value
}

@Composable
private fun MainUnifiedWallpaperCard(
    modifier: Modifier = Modifier,
    model: TianYinWallpaperModel,
    selectionMode: Boolean,
    isSelected: Boolean,
    enableLiquidGlass: Boolean,
    useDarkTheme: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit = {}
) {
    val context = LocalContext.current
    val cardAspectRatio = context.resources.displayMetrics.let { it.widthPixels.toFloat() / it.heightPixels.toFloat() }
    val request = remember(model.uuid, model.type, model.imgUri, model.videoUri, model.imgPath, model.videoPath) {
        ThumbnailUtils.requestForWallpaper(model)
    }
    val bitmap = rememberThumbnailBitmap(request)
    var cardSizePx by remember { mutableStateOf(IntSize.Zero) }
    Box(
        modifier = modifier
            .aspectRatio(cardAspectRatio)
            .onSizeChanged { cardSizePx = it }
            .clip(RoundedCornerShape(16.dp))
            .background(Color(0xFF18181A))
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color(0xFF18181A))
        ) {
            if (bitmap != null) {
                Image(
                    bitmap = bitmap.asImageBitmap(),
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop
                )
            } else {
                BasicText(
                    text = model.uuid ?: model.imgUri ?: model.videoUri ?: model.imgPath ?: "Wallpaper",
                    modifier = Modifier.align(Alignment.Center).padding(8.dp),
                    style = TextStyle(Color.White.copy(alpha = 0.78f), 12.sp, fontWeight = FontWeight.Medium)
                )
            }
        }
        MainThumbnailTypeIcon(
            iconRes = if (model.type == WALLPAPER_TYPE_STATIC) R.drawable.picture else R.drawable.video,
            contentDescription = if (model.type == WALLPAPER_TYPE_STATIC) "图片" else "视频",
            sourceBitmap = if (enableLiquidGlass) bitmap else null,
            viewportAspectRatio = cardAspectRatio,
            viewportSizePx = cardSizePx,
            useDarkTheme = useDarkTheme,
            bakeKey = request.cacheKey to bitmap,
            modifier = Modifier.align(Alignment.TopEnd)
        )
        MainWallpaperSelectionIndicator(
            visible = selectionMode,
            selected = isSelected,
            modifier = Modifier.align(Alignment.BottomEnd)
        )
    }
}

@Composable
private fun MainUnifiedRasterCard(
    modifier: Modifier = Modifier,
    group: RasterGroupModel,
    selectionMode: Boolean,
    isSelected: Boolean,
    enableLiquidGlass: Boolean,
    useDarkTheme: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit
) {
    val context = LocalContext.current
    val cardAspectRatio = context.resources.displayMetrics.let { it.widthPixels.toFloat() / it.heightPixels.toFloat() }
    var cardSizePx by remember { mutableStateOf(IntSize.Zero) }
    val request = remember(group.id, group.type, group.videoUri, group.imageUris.firstOrNull()) {
        ThumbnailUtils.requestForRasterGroup(group)
    }
    val bitmap = rememberThumbnailBitmap(request)
    Box(
        modifier = modifier
            .aspectRatio(cardAspectRatio)
            .onSizeChanged { cardSizePx = it }
            .clip(RoundedCornerShape(16.dp))
            .background(Color(0xFF18181A))
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color(0xFF18181A))
        ) {
            if (bitmap != null) {
                Image(
                    bitmap = bitmap.asImageBitmap(),
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )
            } else {
                BasicText(
                    text = group.id.ifBlank { "Raster" },
                    modifier = Modifier.align(Alignment.Center).padding(8.dp),
                    style = TextStyle(Color.White.copy(alpha = 0.78f), 12.sp, fontWeight = FontWeight.Medium)
                )
            }
        }
        MainThumbnailTypeIcon(
            iconRes = if (group.type == RasterGroupModel.TYPE_STATIC) R.drawable.pictureraster else R.drawable.videoraster,
            sourceBitmap = if (enableLiquidGlass) bitmap else null,
            viewportAspectRatio = cardAspectRatio,
            viewportSizePx = cardSizePx,
            useDarkTheme = useDarkTheme,
            bakeKey = (request?.cacheKey ?: group.id) to bitmap,
            contentDescription = if (group.type == RasterGroupModel.TYPE_STATIC) "图集光栅" else "视频光栅",
            modifier = Modifier.align(Alignment.TopEnd)
        )
        MainWallpaperSelectionIndicator(
            visible = selectionMode,
            selected = isSelected,
            modifier = Modifier.align(Alignment.BottomEnd)
        )
    }
}

@Composable
private fun MainUnifiedDepthCard(
    modifier: Modifier = Modifier,
    model: DepthWallpaperModel,
    selectionMode: Boolean,
    isSelected: Boolean,
    enableLiquidGlass: Boolean,
    useDarkTheme: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit
) {
    val context = LocalContext.current
    val cardAspectRatio = context.resources.displayMetrics.let { it.widthPixels.toFloat() / it.heightPixels.toFloat() }
    var cardSizePx by remember { mutableStateOf(IntSize.Zero) }
    val bitmap = rememberDepthThumbnailBitmap(model)
    Box(
        modifier = modifier
            .aspectRatio(cardAspectRatio)
            .onSizeChanged { cardSizePx = it }
            .clip(RoundedCornerShape(16.dp))
            .background(Color(0xFF18181A))
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color(0xFF18181A))
        ) {
            if (bitmap != null) {
                Image(
                    bitmap = bitmap.asImageBitmap(),
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )
            } else {
                BasicText(
                    text = model.displayName.ifBlank { "Gaussian SOG" },
                    modifier = Modifier.align(Alignment.Center).padding(8.dp),
                    style = TextStyle(Color.White.copy(alpha = 0.78f), 12.sp, fontWeight = FontWeight.Medium)
                )
            }
        }
        MainThumbnailTypeIcon(
            iconRes = R.drawable.depth,
            sourceBitmap = if (enableLiquidGlass) bitmap else null,
            viewportAspectRatio = cardAspectRatio,
            viewportSizePx = cardSizePx,
            useDarkTheme = useDarkTheme,
            bakeKey = model.id to bitmap,
            contentDescription = "景深",
            modifier = Modifier.align(Alignment.TopEnd)
        )
        MainWallpaperSelectionIndicator(
            visible = selectionMode,
            selected = isSelected,
            modifier = Modifier.align(Alignment.BottomEnd)
        )
    }
}

@Composable
private fun MainWallpaperSelectionIndicator(
    visible: Boolean,
    selected: Boolean,
    modifier: Modifier = Modifier
) {
    if (!visible) return
    val accentColor = MaterialTheme.colors.primary
    Box(
        modifier = modifier
            .padding(6.dp)
            .size(20.dp)
            .clip(CircleShape)
            .background(if (selected) accentColor else Color.Black.copy(alpha = 0.22f))
            .border(
                1.5.dp,
                if (selected) accentColor else Color.White.copy(alpha = 0.55f),
                CircleShape
            )
            .padding(3.5.dp),
        contentAlignment = Alignment.Center
    ) {
        if (selected) {
            Icon(
                painter = painterResource(R.drawable.complete),
                contentDescription = "已选中",
                modifier = Modifier.fillMaxSize(),
                tint = Color.White
            )
        }
    }
}

private fun queryMainRouteDisplayName(context: Context, uri: Uri): String? {
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
            } else null
        }
    }.getOrNull() ?: uri.lastPathSegment
}

private fun copyMainRouteOnlineThumbnailToDepthCache(
    context: Context,
    recordThumbnailUri: String?,
    modelId: String
) {
    if (recordThumbnailUri.isNullOrBlank() || modelId.isBlank()) return
    runCatching {
        val target = DepthPrefs.sogThumbnailFile(context, modelId)
        target.parentFile?.mkdirs()

        val sourceFile = File(recordThumbnailUri)
        if (sourceFile.exists() && sourceFile.length() > 0L) {
            if (target.exists()) target.delete()
            sourceFile.copyTo(target, overwrite = true)
            return@runCatching
        }

        context.contentResolver.openInputStream(Uri.parse(recordThumbnailUri))?.use { input ->
            val bitmap = BitmapFactory.decodeStream(input) ?: return@use
            target.outputStream().use { output ->
                bitmap.compress(Bitmap.CompressFormat.JPEG, 86, output)
            }
            bitmap.recycle()
        }
    }
}
