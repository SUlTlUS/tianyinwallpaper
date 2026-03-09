package com.zeaze.tianyinwallpaper.ui.main

import android.app.Activity
import android.app.WallpaperManager
import android.content.Context
import android.content.ComponentName
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.graphics.SurfaceTexture
import android.media.MediaMetadataRetriever
import android.media.MediaPlayer
import android.net.Uri
import android.provider.Settings
import android.util.Log
import android.util.LruCache
import android.view.Surface
import android.view.TextureView
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.ExperimentalAnimationApi
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
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
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.zIndex
import androidx.documentfile.provider.DocumentFile
import com.alibaba.fastjson.JSON
import com.kyant.shapes.Capsule
import com.kyant.shapes.RoundedRectangle
import com.zeaze.tianyinwallpaper.backdrop.backdrops.LayerBackdrop
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
import com.zeaze.tianyinwallpaper.ui.commom.SaveData
import com.zeaze.tianyinwallpaper.ui.setting.LiquidToggle
import com.zeaze.tianyinwallpaper.catalog.components.LiquidButton
import com.zeaze.tianyinwallpaper.service.TianYinWallpaperService
import com.zeaze.tianyinwallpaper.utils.FileUtil
import io.reactivex.functions.Consumer
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.Collections
import java.util.UUID
import kotlin.concurrent.thread
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.IntOffset
private data class ThumbnailCacheKey(
    val type: Int,
    val uuid: String,
    val imgUri: String,
    val videoUri: String,
    val imgPath: String
)

private const val THUMBNAIL_CACHE_MEMORY_DIVISOR = 8L
private const val THUMBNAIL_VIDEO_WIDTH = 360
private const val THUMBNAIL_VIDEO_HEIGHT = 640
private const val WALLPAPER_TYPE_STATIC = 0
private const val WALLPAPER_TYPE_DYNAMIC = 1

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
    data class Action(val index: Int) : DialogState()
    object Type : DialogState()
    object Permission : DialogState()
    object Delete : DialogState()
    object Overwrite : DialogState()
    data class Time(val index: Int) : DialogState()
    object Save : DialogState()
}

private val THUMBNAIL_CACHE = object : LruCache<ThumbnailCacheKey, Bitmap>(
    (Runtime.getRuntime().maxMemory() / THUMBNAIL_CACHE_MEMORY_DIVISOR / 1024L).toInt()
) {
    override fun sizeOf(key: ThumbnailCacheKey, value: Bitmap): Int {
        return value.byteCount / 1024
    }
}

private fun buildThumbnailCacheKey(model: TianYinWallpaperModel): ThumbnailCacheKey {
    return ThumbnailCacheKey(
        type = model.type,
        uuid = model.uuid.orEmpty(),
        imgUri = model.imgUri.orEmpty(),
        videoUri = model.videoUri.orEmpty(),
        imgPath = model.imgPath.orEmpty()
    )
}

private fun loadThumbnailBitmap(context: Context, model: TianYinWallpaperModel): Bitmap? {
    val options = BitmapFactory.Options().apply {
        inPreferredConfig = Bitmap.Config.RGB_565
    }
    return runCatching {
        when {
            model.type == 0 && !model.imgUri.isNullOrEmpty() -> {
                context.contentResolver.openInputStream(Uri.parse(model.imgUri))?.use {
                    BitmapFactory.decodeStream(it, null, options)
                }
            }
            model.type == 1 && !model.videoUri.isNullOrEmpty() -> {
                val thumbnailFile = getVideoThumbnailFile(context, model)
                if (thumbnailFile != null && thumbnailFile.exists()) {
                    return@runCatching BitmapFactory.decodeFile(thumbnailFile.absolutePath, options)
                }
                val retriever = MediaMetadataRetriever()
                try {
                    retriever.setDataSource(context, Uri.parse(model.videoUri))
                    val frame = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O_MR1) {
                        retriever.getScaledFrameAtTime(
                            0,
                            MediaMetadataRetriever.OPTION_CLOSEST_SYNC,
                            THUMBNAIL_VIDEO_WIDTH,
                            THUMBNAIL_VIDEO_HEIGHT
                        )
                    } else {
                        retriever.getFrameAtTime(0)
                    }
                    if (frame != null && thumbnailFile != null) {
                        runCatching {
                            FileOutputStream(thumbnailFile).use {
                                val saved = frame.compress(Bitmap.CompressFormat.JPEG, 85, it)
                                if (!saved) {
                                    Log.w("MainRouteScreen", "Failed to persist video thumbnail: ${thumbnailFile.absolutePath}")
                                }
                            }
                        }.onFailure {
                            Log.e("MainRouteScreen", "Failed to save video thumbnail: ${thumbnailFile.absolutePath}", it)
                        }
                    }
                    frame
                } finally {
                    retriever.release()
                }
            }
            !model.imgPath.isNullOrEmpty() -> BitmapFactory.decodeFile(model.imgPath, options)
            else -> null
        }
    }.getOrNull()
}

private fun getVideoThumbnailFile(context: Context, model: TianYinWallpaperModel): File? {
    val uuid = model.uuid ?: return null
    val root = context.getExternalFilesDir(null) ?: return null
    val thumbnailDir = File(root, "thumbnail_cache")
    if (!thumbnailDir.mkdirs() && !thumbnailDir.exists()) {
        return null
    }
    return File(thumbnailDir, "$uuid.jpg")
}


@OptIn(ExperimentalAnimationApi::class)
@Composable
fun MainRouteScreen(
    onOpenSettingPage: () -> Unit,
    onBottomBarVisibleChange: (Boolean) -> Unit
) {
    val context = LocalContext.current
    val isLightTheme = !isSystemInDarkTheme()
    val contentColor = if (isLightTheme) Color.Black else Color.White
    val accentColor = if (isLightTheme) Color(0xFF0088FF) else Color(0xFF0091FF)
    val containerColor = if (isLightTheme) Color(0xFFFAFAFA).copy(0.6f) else Color(0xFF121212).copy(0.4f)
    val dimColor = if (isLightTheme) Color(0xFF29293A).copy(0.23f) else Color(0xFF121212).copy(0.56f)

    val enableLiquidGlass = android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU
    val activity = context as? Activity
    val pref = remember(context) { context.getSharedPreferences(App.TIANYIN, Context.MODE_PRIVATE) }
    val editor = remember(pref) { pref.edit() }

    val wallpapers = remember { mutableStateListOf<TianYinWallpaperModel>() }
    val selectedPositions = remember { mutableStateListOf<Int>() }

    var selectionMode by remember { mutableStateOf(false) }
    var groupName by remember { mutableStateOf("") }

    var showWallpaperTypeDialog by remember { mutableStateOf(false) }
    var showPermissionDialog by remember { mutableStateOf(false) }
    var showMoreMenu by remember { mutableStateOf(false) }
    var showDeleteSelectedDialog by remember { mutableStateOf(false) }
    var showOverwriteDialog by remember { mutableStateOf(false) }
    var showSaveDialog by remember { mutableStateOf(false) }

    var actionDialogIndex by remember { mutableStateOf<Int?>(null) }
    var timeDialogIndex by remember { mutableStateOf<Int?>(null) }
    var fullScreenPreviewModel by remember { mutableStateOf<TianYinWallpaperModel?>(null) }
    var showLivePreview by remember { mutableStateOf(false) }

    // Observe current index from SharedPreferences
    var liveSyncIndex by remember { mutableStateOf(pref.getInt(TianYinWallpaperService.PREF_CURRENT_INDEX, 0)) }
    val preferenceListener = remember {
        android.content.SharedPreferences.OnSharedPreferenceChangeListener { p, key ->
            if (key == TianYinWallpaperService.PREF_CURRENT_INDEX) {
                val newIndex = p.getInt(key, 0)
                Log.d("MainRouteScreen", "Live sync index updated: $newIndex")
                liveSyncIndex = newIndex
            }
        }
    }
    DisposableEffect(showLivePreview) {
        if (showLivePreview) {
            pref.registerOnSharedPreferenceChangeListener(preferenceListener)
            // Sync current index immediately when opening
            liveSyncIndex = pref.getInt(TianYinWallpaperService.PREF_CURRENT_INDEX, 0)
            onDispose { pref.unregisterOnSharedPreferenceChangeListener(preferenceListener) }
        } else {
            onDispose { }
        }
    }

    val currentDialogState = when {
        actionDialogIndex != null -> DialogState.Action(actionDialogIndex!!)
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

    fun toast(msg: String) {
        Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
    }

    fun checkAndSaveGroup() {
        if (groupName.isBlank() || wallpapers.isEmpty()) return

        val currentContent = JSON.toJSONString(wallpapers)
        val data = FileUtil.loadData(context, FileUtil.dataPath)
        val list = JSON.parseArray(data, SaveData::class.java)?.toMutableList() ?: mutableListOf()

        val existing = list.find { it.name == groupName }
        if (existing != null) {
            if (existing.s != currentContent) {
                showOverwriteDialog = true
            }
        } else {
            list.add(0, SaveData(currentContent, groupName))
            FileUtil.save(context, JSON.toJSONString(list), FileUtil.dataPath) {
                toast("壁纸组已保存到列表")
            }
        }
    }

    fun performOverwriteSave() {
        if (groupName.isBlank()) return
        val currentContent = JSON.toJSONString(wallpapers)
        val data = FileUtil.loadData(context, FileUtil.dataPath)
        val list = JSON.parseArray(data, SaveData::class.java)?.toMutableList() ?: mutableListOf()

        val index = list.indexOfFirst { it.name == groupName }
        if (index != -1) {
            list[index].s = currentContent
            val item = list.removeAt(index)
            list.add(0, item)
            FileUtil.save(context, JSON.toJSONString(list), FileUtil.dataPath) {
                toast("壁纸组已覆盖保存")
            }
        }
        showOverwriteDialog = false
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

    fun performApply(list: List<TianYinWallpaperModel>) {
        thread {
            FileUtil.save(context, JSON.toJSONString(list), FileUtil.wallpaperPath) {
                val hostActivity = activity ?: run {
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

    fun applyWallpapers() {
        if (wallpapers.isEmpty()) {
            toast("至少需要1张壁纸才能开始设置")
            return
        }
        performApply(wallpapers.toList())
    }

    fun applySingleWallpaper(model: TianYinWallpaperModel) {
        performApply(listOf(model))
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
        if (!results.isNullOrEmpty()) appendModels(results, dynamic = false)
    }
    val videoLaunch = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { results ->
        if (!results.isNullOrEmpty()) appendModels(results, dynamic = true)
    }
    val directoryLaunch = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { treeUri ->
        if (treeUri == null) return@rememberLauncherForActivityResult
        try {
            activity?.contentResolver?.takePersistableUriPermission(treeUri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        } catch (e: SecurityException) {
            Log.e("MainRouteScreen", "Could not take persistable permission for tree URI: $treeUri", e)
            toast(context.getString(R.string.main_wallpaper_directory_permission_failed))
        }
        val media = collectMediaFromDirectory(treeUri)
        if (media.isEmpty()) {
            toast(context.getString(R.string.main_wallpaper_type_directory_empty))
        } else {
            appendMixedModels(media, takeUriPermissions = false)
        }
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
    }

    LaunchedEffect(selectionMode, fullScreenPreviewModel, showLivePreview) {
        onBottomBarVisibleChange(!selectionMode && fullScreenPreviewModel == null && !showLivePreview)
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

    val gridState = rememberLazyGridState()
    var draggingItemIndex by remember { mutableStateOf<Int?>(null) }
    var draggingItemKey by remember { mutableStateOf<Any?>(null) }
    var dragOffset by remember { mutableStateOf(androidx.compose.ui.geometry.Offset.Zero) }
    var startViewportOffset by remember { mutableStateOf(0) }

    // 辅助函数：更新排序后的选中索引
    fun updateSelectedIndices(from: Int, to: Int) {
        val currentSelected = selectedPositions.toList()
        selectedPositions.clear()
        currentSelected.forEach { index ->
            when {
                index == from -> selectedPositions.add(to)
                from < to && index in (from + 1)..to -> selectedPositions.add(index - 1)
                from > to && index in to..(from - 1) -> selectedPositions.add(index + 1)
                else -> selectedPositions.add(index)
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
                    .fillMaxSize()
                    .pointerInput(Unit) {
                        detectDragGesturesAfterLongPress(
                            onDragStart = { offset ->
                                dragOffset = Offset.Zero
                                val touchOffset = offset

                                val layoutInfo = gridState.layoutInfo
                                startViewportOffset = layoutInfo.viewportStartOffset

                                // 1. 查找触摸点所在的item（考虑滚动偏移）
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
                                    // 2. 回退方案：按屏幕中心距离最近选择
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

                                        // 被拖动项的当前屏幕中心
                                        val draggingScreenCenter = Offset(
                                            draggingItem.offset.x + draggingItem.size.width / 2f,
                                            draggingItem.offset.y - startViewportOffset + draggingItem.size.height / 2f
                                        ) + dragOffset

                                        // 查找最近的其他可见项
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
                                                    updateSelectedIndices(currentIndex, targetIndex)

                                                    val movedItem = wallpapers.removeAt(currentIndex)
                                                    wallpapers.add(targetIndex, movedItem)

                                                    // 更新dragOffset以保持视觉连续性
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
                                saveCache()
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
                itemsIndexed(wallpapers, key = { _, model -> model.uuid ?: UUID.randomUUID().toString() }) { index, model ->
                    val selected = selectedPositions.contains(index)
                    val isDragging = draggingItemKey != null && draggingItemKey == (model.uuid ?: index)

                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .aspectRatio(0.62f)
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
                            .clickable {
                                if (selectionMode) {
                                    if (selected) selectedPositions.remove(index) else selectedPositions.add(index)
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
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier
                                .align(Alignment.TopEnd)
                                .padding(3.dp)
                                .background(Color(0x66000000), shape = RoundedCornerShape(16.dp))
                                .padding(horizontal = 6.dp, vertical = 2.dp)
                        )
                        if (model.startTime != -1 && model.endTime != -1) {
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
                    }
                }
            }
        }
        // 前景层：包含 TopBar 和删除按钮，它们采样底层捕获的内容
        if (selectionMode) {
            val isAllSelected = selectedPositions.size == wallpapers.size && wallpapers.isNotEmpty()
            SelectionTopBar(
                statusBarTopPaddingDp = statusBarTopPaddingDp,
                enableLiquidGlass = enableLiquidGlass,
                backdrop = liquidBackdrop,
                isAllSelected = isAllSelected,
                onCancelSelect = { exitSelectionMode() },
                onDelete = {
                    if (selectedPositions.isEmpty()) {
                        toast(context.getString(R.string.no_selected_tip))
                    } else {
                        showDeleteSelectedDialog = true
                    }
                },
                onToggleSelectAll = {
                    if (isAllSelected) {
                        selectedPositions.clear()
                    } else {
                        selectedPositions.clear()
                        wallpapers.indices.forEach { selectedPositions.add(it) }
                    }
                }
            )
        } else {
            MainTopBar(
                statusBarTopPaddingDp = statusBarTopPaddingDp,
                enableLiquidGlass = enableLiquidGlass,
                backdrop = liquidBackdrop,
                onAdd = { showWallpaperTypeDialog = true },
                onApply = { applyWallpapers() },
                onMoreClick = { showMoreMenu = true },
                onPreview = { showLivePreview = true }
            )

            // 下拉菜单作为顶层 Overlay 以确保坐标采样正确 (避免 Popup 窗口偏移问题)
            if (showMoreMenu) {
                // 点击外部消失
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .pointerInput(Unit) {
                            detectTapGestures { showMoreMenu = false }
                        }
                ) {
                    val menuBackdrop = liquidBackdrop ?: rememberCanvasBackdrop { drawRect(containerColor) }
                    Column(
                        Modifier
                            .padding(top = statusBarTopPaddingDp + 66.dp, end = 12.dp)
                            .width(140.dp)
                            .align(Alignment.TopEnd)
                            .drawBackdrop(
                                backdrop = menuBackdrop,
                                shape = { RoundedRectangle(20f.dp) },
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
                            .padding(8.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        val menuItems = listOf(
                            "保存" to {
                                showMoreMenu = false
                                showSaveDialog = true
                            },
                            "选择" to {
                                showMoreMenu = false
                                enterSelectionMode()
                            },
                            "设置" to {
                                showMoreMenu = false
                                onOpenSettingPage()
                            }
                        )
                        menuItems.forEach { (label, onClick) ->
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .height(44.dp)
                                    .clip(Capsule())
                                    .clickable { onClick() }
                                    .padding(horizontal = 16.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.Center
                            ) {
                                BasicText(label, style = TextStyle(contentColor, 15.sp))
                            }
                        }
                    }
                }
            }
        }

        // 1. Background dimming layer
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
                        actionDialogIndex = null
                        showWallpaperTypeDialog = false
                        showPermissionDialog = false
                        showDeleteSelectedDialog = false
                        showOverwriteDialog = false
                        showSaveDialog = false
                        timeDialogIndex = null
                    }
            )
        }

        // 2. Custom Liquid Glass Dialog
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
            label = "DialogOverlay",
            modifier = Modifier.fillMaxSize()
        ) { state ->
            if (state != null) {
                val dialogBackdrop = liquidBackdrop ?: rememberCanvasBackdrop { drawRect(containerColor) }
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
                        .pointerInput(Unit) { detectTapGestures { /* consume */ } }
                ) {
                    when (state) {
                        is DialogState.Action -> {
                            val targetIndex = state.index
                            val model = wallpapers.getOrNull(targetIndex)
                            if (model != null) {
                                var loopState by remember(targetIndex) { mutableStateOf(model.loop) }

                                Box(
                                    Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = 16.dp)
                                        .padding(horizontal = 9f.dp)
                                        .clickable { fullScreenPreviewModel = model },
                                    contentAlignment = Alignment.Center
                                ) {
                                    WallpaperThumbnail(
                                        model = model,
                                        modifier = Modifier
                                            .fillMaxWidth(0.946f)
                                            .aspectRatio(0.62f)
                                            .clip(RoundedRectangle(35f.dp))
                                    )
                                }

                                Column(
                                    Modifier
                                        .padding(16.dp, 4.dp, 16.dp, 20.dp)
                                        .fillMaxWidth(),
                                    verticalArrangement = Arrangement.spacedBy(8.dp),
                                    horizontalAlignment = Alignment.CenterHorizontally
                                ) {
                                    // Time Button
                                    Row(
                                        Modifier
                                            .clip(Capsule())
                                            .background(containerColor.copy(0.2f))
                                            .clickable {
                                                timeDialogIndex = targetIndex
                                                actionDialogIndex = null
                                            }
                                            .height(48.dp)
                                            .fillMaxWidth()
                                            .padding(horizontal = 12.dp),
                                        horizontalArrangement = Arrangement.Center,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        BasicText("时间", style = TextStyle(contentColor, 16.sp))
                                    }

                                    // Loop Toggle
                                    Row(
                                        Modifier
                                            .clip(Capsule())
                                            .background(containerColor.copy(0.2f))
                                            .height(48.dp)
                                            .fillMaxWidth()
                                            .padding(horizontal = 24.dp),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        BasicText("循环播放", style = TextStyle(contentColor, 16.sp))
                                        LiquidToggle(
                                            selected = { loopState },
                                            onSelect = {
                                                loopState = it
                                                model.loop = it
                                                saveCache()
                                            },
                                            backdrop = dialogBackdrop
                                        )
                                    }

                                    // Delete & Cancel Buttons
                                    Row(
                                        Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                                    ) {
                                        // Delete Button
                                        Row(
                                            Modifier
                                                .weight(1f)
                                                .clip(Capsule())
                                                .background(Color(0xFFFF4D4F))
                                                .clickable {
                                                    delete(targetIndex)
                                                    actionDialogIndex = null
                                                }
                                                .height(48.dp)
                                                .padding(horizontal = 16f.dp),

                                            horizontalArrangement = Arrangement.spacedBy(4f.dp, Alignment.CenterHorizontally),
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            BasicText("删除", style = TextStyle(Color.White, 16.sp))
                                        }

                                        // Cancel Button
                                        Row(
                                            Modifier
                                                .weight(1f)
                                                .clip(Capsule())
                                                .background(accentColor.copy(alpha = 0.75f))
                                                .clickable { actionDialogIndex = null }
                                                .height(48.dp)
                                                .padding(horizontal = 16f.dp),
                                            horizontalArrangement = Arrangement.spacedBy(4f.dp, Alignment.CenterHorizontally),
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            BasicText("取消", style = TextStyle(Color.White, 16.sp))
                                        }
                                    }
                                }
                            }
                        }
                        DialogState.Type -> {
                            Column(
                                Modifier.padding(16.dp, 20.dp, 16.dp, 20.dp).fillMaxWidth(),
                                verticalArrangement = Arrangement.spacedBy(8.dp),
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                BasicText(context.getString(R.string.main_select_wallpaper_type_tip), style = TextStyle(contentColor, 18.sp, fontWeight = FontWeight.Bold))
                                Spacer(Modifier.height(8.dp))
                                val items = listOf(
                                    context.getString(R.string.main_wallpaper_type_static) to { showWallpaperTypeDialog = false; imageLaunch.launch(arrayOf("image/*")) },
                                    context.getString(R.string.main_wallpaper_type_dynamic) to { showWallpaperTypeDialog = false; videoLaunch.launch(arrayOf("video/*")) },
                                    context.getString(R.string.main_wallpaper_type_directory) to { showWallpaperTypeDialog = false; directoryLaunch.launch(null) },
                                    context.getString(R.string.common_cancel) to { showWallpaperTypeDialog = false }
                                )
                                items.forEach { (label, onClick) ->
                                    Row(
                                        Modifier
                                            .clip(Capsule())
                                            .background(if(label == context.getString(R.string.common_cancel)) containerColor.copy(0.2f) else accentColor)
                                            .clickable { onClick() }
                                            .height(48.dp)
                                            .fillMaxWidth(),
                                        horizontalArrangement = Arrangement.Center,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        BasicText(label, style = TextStyle(if(label == context.getString(R.string.common_cancel)) contentColor else Color.White, 16.sp))
                                    }
                                }
                            }
                        }
                        DialogState.Permission -> {
                            Column(
                                Modifier.padding(16.dp, 20.dp, 16.dp, 20.dp).fillMaxWidth(),
                                verticalArrangement = Arrangement.spacedBy(8.dp),
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                BasicText(context.getString(R.string.main_set_wallpaper_failed_permission_tip), style = TextStyle(contentColor, 18.sp, fontWeight = FontWeight.Bold))
                                Spacer(Modifier.height(12.dp))
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
                                            .clickable { showPermissionDialog = false }
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
                                                    if (index in wallpapers.indices) wallpapers.removeAt(index)
                                                }
                                                selectedPositions.clear()
                                                saveCache()
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
                                                    toast("请输入名称")
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

        // Full screen preview overlay
        AnimatedContent(
            targetState = fullScreenPreviewModel,
            transitionSpec = {
                scaleIn(
                    initialScale = 0.8f,
                    animationSpec = spring(
                        dampingRatio = Spring.DampingRatioNoBouncy,
                        stiffness = Spring.StiffnessLow
                    )
                ).togetherWith(
                    scaleOut(
                        targetScale = 0.8f,
                        animationSpec = spring(
                            dampingRatio = Spring.DampingRatioNoBouncy,
                            stiffness = Spring.StiffnessLow
                        )
                    )
                )
            },
            contentAlignment = Alignment.Center,
            label = "FullScreenPreview",
            modifier = Modifier.fillMaxSize()
        ) { model ->
            if (model != null) {
                val previewBackdrop = if (enableLiquidGlass) rememberLayerBackdrop() else null
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        //.padding(horizontal = 16.dp, vertical = 32.dp)
                        .clip(RoundedCornerShape(32.dp))
                        .background(Color.Black)
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null
                        ) {
                            fullScreenPreviewModel = null
                        }
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .let { m ->
                                if (enableLiquidGlass && previewBackdrop != null) {
                                    m.layerBackdrop(previewBackdrop)
                                } else m
                            }
                    ) {
                        WallpaperThumbnail(
                            model = model,
                            modifier = Modifier.fillMaxSize(),
                            useClip = false
                        )
                    }

                    Box(
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .padding(bottom = 24.dp)
                    ) {
                        val isDark = isSystemInDarkTheme()
                        val adaptiveSurfaceColor = if (isDark) Color.Black.copy(0.3f) else Color.White.copy(0.3f)
                        val textColor = if (isDark) Color.White else Color.Black

                        if (enableLiquidGlass && previewBackdrop != null) {
                            LiquidButton(
                                onClick = { applySingleWallpaper(model) },
                                backdrop = previewBackdrop,
                                surfaceColor = accentColor.copy(alpha = 0.75f),
                                modifier = Modifier.height(44.dp)
                            ) {
                                BasicText(
                                    "应用此壁纸",
                                    modifier = Modifier.padding(horizontal = 12.dp),
                                    style = TextStyle(Color.White, 16.sp, fontWeight = FontWeight.Medium)
                                )
                            }
                        } else {
                            Surface(
                                modifier = Modifier
                                    .height(44.dp)
                                    .clickable { applySingleWallpaper(model) },
                                shape = Capsule(),
                                color = accentColor.copy(alpha = 0.75f)
                            ) {
                                Box(
                                    contentAlignment = Alignment.Center,
                                    modifier = Modifier.padding(horizontal = 32.dp)
                                ) {
                                    Text(
                                        text = "应用此壁纸",
                                        color = Color.White,
                                        fontSize = 16.sp,
                                        fontWeight = FontWeight.Medium
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        // Live preview overlay
        AnimatedVisibility(
            visible = showLivePreview,
            enter = fadeIn() + scaleIn(initialScale = 0.8f),
            exit = fadeOut() + scaleOut(targetScale = 0.8f)
        ) {
            val wallpaperList = remember(showLivePreview) {
                val listData = FileUtil.loadData(context, FileUtil.wallpaperPath)
                JSON.parseArray(listData, TianYinWallpaperModel::class.java) ?: emptyList()
            }
            LiveSyncPreview(
                wallpaperList = wallpaperList,
                currentIndex = liveSyncIndex,
                onClose = { showLivePreview = false },
                onPrev = { sendServiceIntent(TianYinWallpaperService.ACTION_PREV_WALLPAPER) },
                onNext = { sendServiceIntent(TianYinWallpaperService.ACTION_NEXT_WALLPAPER) }
            )
        }
    }
}

@Composable
private fun MainTopBar(
    statusBarTopPaddingDp: androidx.compose.ui.unit.Dp,
    enableLiquidGlass: Boolean,
    backdrop: LayerBackdrop?,
    onAdd: () -> Unit,
    onApply: () -> Unit,
    onMoreClick: () -> Unit,
    onPreview: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = statusBarTopPaddingDp + 10.dp, start = 8.dp, end = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        val isDark = isSystemInDarkTheme()
        val adaptiveSurfaceColor = if (isDark) Color.Black.copy(0.3f) else Color.White.copy(0.3f)
        val textColor = if (isDark) Color.White else Color.Black

        // Add Button
        if (enableLiquidGlass && backdrop != null) {
            LiquidButton(
                onClick = onAdd,
                backdrop = backdrop,
                modifier = Modifier.size(48.dp),
                surfaceColor = adaptiveSurfaceColor
            ) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    BasicText(text = "+", style = TextStyle(textColor, 20.sp))
                }
            }
        } else {
            Surface(
                modifier = Modifier.size(48.dp),
                shape = CircleShape,
                color = if (isDark) Color(0x33000000) else Color(0xAAFFFFFF),
                border = androidx.compose.foundation.BorderStroke(
                    1.dp,
                    if (isDark) Color(0x33FFFFFF) else Color(0x88FFFFFF)
                )
            ) {
                Box(
                    modifier = Modifier.fillMaxSize().clickable(onClick = onAdd),
                    contentAlignment = Alignment.Center
                ) {
                    Text(text = "+", color = textColor, fontSize = 20.sp)
                }
            }
        }

        Spacer(modifier = Modifier.weight(1f))

        // Preview Button
        if (enableLiquidGlass && backdrop != null) {
            LiquidButton(
                onClick = onPreview,
                backdrop = backdrop,
                modifier = Modifier.height(48.dp),
                surfaceColor = adaptiveSurfaceColor
            ) {
                Box(contentAlignment = Alignment.Center, modifier = Modifier.padding(horizontal = 16.dp).fillMaxHeight()) {
                    BasicText(text = "预览", style = TextStyle(textColor, 15.sp))
                }
            }
        } else {
            Surface(
                modifier = Modifier.height(48.dp).clickable(onClick = onPreview),
                shape = Capsule(),
                color = if (isDark) Color(0x33000000) else Color(0xAAFFFFFF),
                border = androidx.compose.foundation.BorderStroke(
                    1.dp,
                    if (isDark) Color(0x33FFFFFF) else Color(0x88FFFFFF)
                )
            ) {
                Box(contentAlignment = Alignment.Center, modifier = Modifier.padding(horizontal = 16.dp).fillMaxHeight()) {
                    Text(text = "预览", color = textColor, fontSize = 15.sp)
                }
            }
        }

        // Apply Button
        if (enableLiquidGlass && backdrop != null) {
            LiquidButton(
                onClick = onApply,
                backdrop = backdrop,
                modifier = Modifier.size(48.dp),
                surfaceColor = adaptiveSurfaceColor
            ) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    BasicText(text = "✓", style = TextStyle(textColor, 20.sp))
                }
            }
        } else {
            Surface(
                modifier = Modifier.size(48.dp),
                shape = CircleShape,
                color = if (isDark) Color(0x33000000) else Color(0xAAFFFFFF),
                border = androidx.compose.foundation.BorderStroke(1.dp, if (isDark) Color(0x33FFFFFF) else Color(0x88FFFFFF))
            ) {
                Box(
                    modifier = Modifier.fillMaxSize().clickable(onClick = onApply),
                    contentAlignment = Alignment.Center
                ) {
                    Text(text = "✓", color = textColor, fontSize = 20.sp)
                }
            }
        }

        // More Button
        if (enableLiquidGlass && backdrop != null) {
            LiquidButton(
                onClick = onMoreClick,
                backdrop = backdrop,
                modifier = Modifier.size(48.dp),
                surfaceColor = adaptiveSurfaceColor
            ) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    BasicText(text = "…", style = TextStyle(textColor, 20.sp))
                }
            }
        } else {
            Surface(
                modifier = Modifier.size(48.dp),
                shape = CircleShape,
                color = if (isDark) Color(0x33000000) else Color(0xAAFFFFFF),
                border = androidx.compose.foundation.BorderStroke(1.dp, if (isDark) Color(0x33FFFFFF) else Color(0x88FFFFFF))
            ) {
                Box(
                    modifier = Modifier.fillMaxSize().clickable(onClick = onMoreClick),
                    contentAlignment = Alignment.Center
                ) {
                    Text(text = "…", color = textColor, fontSize = 20.sp)
                }
            }
        }
    }
}

@Composable
private fun LiveSyncPreview(
    wallpaperList: List<TianYinWallpaperModel>,
    currentIndex: Int,
    onClose: () -> Unit,
    onPrev: () -> Unit,
    onNext: () -> Unit
) {
    val currentModel = if (currentIndex in wallpaperList.indices) wallpaperList[currentIndex] else null

    val enableLiquidGlass = android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU
    val previewBackdrop = if (enableLiquidGlass) rememberLayerBackdrop() else null

    Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
        if (currentModel != null) {
            Box(modifier = Modifier.fillMaxSize().let { if (enableLiquidGlass && previewBackdrop != null) it.layerBackdrop(previewBackdrop) else it }) {
                WallpaperThumbnail(
                    model = currentModel,
                    modifier = Modifier.fillMaxSize(),
                    useClip = false
                )
            }
        } else {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("暂无播放中的壁纸", color = Color.White)
            }
        }

        // Close detection layer
        Box(modifier = Modifier.fillMaxSize().clickable(
            interactionSource = remember { MutableInteractionSource() },
            indication = null
        ) { onClose() })

        // Bottom Controls
        Row(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 24.dp, start = 12.dp, end = 12.dp)
                .fillMaxWidth()
                .pointerInput(Unit) { detectTapGestures { } }, // consume taps
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            val controlColor = Color.Black.copy(0.4f)
            val textColor = Color.White

            // Previous Button
            if (enableLiquidGlass && previewBackdrop != null) {
                LiquidButton(
                    onClick = onPrev,
                    backdrop = previewBackdrop,
                    modifier = Modifier
                        .weight(1f)
                        .height(48.dp),
                    surfaceColor = controlColor
                ) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        BasicText(text = "上一张", style = TextStyle(textColor, 16.sp, fontWeight = FontWeight.Medium))
                    }
                }
            } else {
                Surface(
                    modifier = Modifier
                        .weight(1f)
                        .height(48.dp)
                        .clickable { onPrev() },
                    shape = Capsule(),
                    color = controlColor,
                    border = androidx.compose.foundation.BorderStroke(1.dp, Color.White.copy(alpha = 0.2f))
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Text(text = "上一张", color = textColor, fontSize = 16.sp, fontWeight = FontWeight.Medium)
                    }
                }
            }

            // Next Button
            if (enableLiquidGlass && previewBackdrop != null) {
                LiquidButton(
                    onClick = onNext,
                    backdrop = previewBackdrop,
                    modifier = Modifier
                        .weight(1f)
                        .height(48.dp),
                    surfaceColor = controlColor
                ) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        BasicText(text = "下一张", style = TextStyle(textColor, 16.sp, fontWeight = FontWeight.Medium))
                    }
                }
            } else {
                Surface(
                    modifier = Modifier
                        .weight(1f)
                        .height(48.dp)
                        .clickable { onNext() },
                    shape = Capsule(),
                    color = controlColor,
                    border = androidx.compose.foundation.BorderStroke(1.dp, Color.White.copy(alpha = 0.2f))
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Text(text = "下一张", color = textColor, fontSize = 16.sp, fontWeight = FontWeight.Medium)
                    }
                }
            }
        }
    }
}

@Composable
private fun SelectionTopBar(
    statusBarTopPaddingDp: androidx.compose.ui.unit.Dp,
    enableLiquidGlass: Boolean,
    backdrop: LayerBackdrop?,
    isAllSelected: Boolean,
    onCancelSelect: () -> Unit,
    onDelete: () -> Unit,
    onToggleSelectAll: () -> Unit
) {
    val context = LocalContext.current
    val isDark = isSystemInDarkTheme()
    val adaptiveSurfaceColor = if (isDark) Color.Black.copy(0.3f) else Color.White.copy(0.3f)
    val textColor = if (isDark) Color.White else Color.Black

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = statusBarTopPaddingDp + 10.dp, start = 12.dp, end = 12.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Delete Button (Red)
        if (enableLiquidGlass && backdrop != null) {
            LiquidButton(
                onClick = onDelete,
                backdrop = backdrop,
                surfaceColor = Color(0xFFFF4D4F).copy(alpha = 0.8f),
                modifier = Modifier.height(44.dp)
            ) {
                BasicText(
                    context.getString(R.string.common_delete),
                    modifier = Modifier.padding(horizontal = 16.dp),
                    style = TextStyle(Color.White, 15.sp, fontWeight = FontWeight.Medium)
                )
            }
        } else {
            Surface(
                modifier = Modifier
                    .height(44.dp)
                    .clickable { onDelete() },
                shape = Capsule(),
                color = Color(0xFFFF4D4F)
            ) {
                Box(contentAlignment = Alignment.Center, modifier = Modifier.padding(horizontal = 16.dp)) {
                    Text(text = context.getString(R.string.common_delete), color = Color.White, fontSize = 15.sp)
                }
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            // Select All Toggle Button
            val selectAllLabel = if (isAllSelected) "取消全选" else "全选"
            if (enableLiquidGlass && backdrop != null) {
                LiquidButton(
                    onClick = onToggleSelectAll,
                    backdrop = backdrop,
                    surfaceColor = adaptiveSurfaceColor,
                    modifier = Modifier.height(44.dp)
                ) {
                    BasicText(
                        selectAllLabel,
                        modifier = Modifier.padding(horizontal = 16.dp),
                        style = TextStyle(textColor, 15.sp)
                    )
                }
            } else {
                Surface(
                    modifier = Modifier
                        .height(44.dp)
                        .clickable { onToggleSelectAll() },
                    shape = Capsule(),
                    color = adaptiveSurfaceColor
                ) {
                    Box(contentAlignment = Alignment.Center, modifier = Modifier.padding(horizontal = 16.dp)) {
                        Text(text = selectAllLabel, color = textColor, fontSize = 15.sp)
                    }
                }
            }

            // Cancel Button
            if (enableLiquidGlass && backdrop != null) {
                LiquidButton(
                    onClick = onCancelSelect,
                    backdrop = backdrop,
                    surfaceColor = adaptiveSurfaceColor,
                    modifier = Modifier.height(44.dp)
                ) {
                    BasicText(
                        context.getString(R.string.common_cancel),
                        modifier = Modifier.padding(horizontal = 16.dp),
                        style = TextStyle(textColor, 15.sp)
                    )
                }
            } else {
                Surface(
                    modifier = Modifier
                        .height(44.dp)
                        .clickable { onCancelSelect() },
                    shape = Capsule(),
                    color = adaptiveSurfaceColor
                ) {
                    Box(contentAlignment = Alignment.Center, modifier = Modifier.padding(horizontal = 16.dp)) {
                        Text(text = context.getString(R.string.common_cancel), color = textColor, fontSize = 15.sp)
                    }
                }
            }
        }
    }
}

@Composable
private fun WallpaperCardImage(modifier: Modifier = Modifier, model: TianYinWallpaperModel) {
    val context = LocalContext.current
    val cacheKey = buildThumbnailCacheKey(model)
    val bitmapState = produceState<Bitmap?>(
        initialValue = THUMBNAIL_CACHE.get(cacheKey),
        cacheKey
    ) {
        val loaded = withContext(Dispatchers.IO) {
            THUMBNAIL_CACHE.get(cacheKey) ?: loadThumbnailBitmap(context, model)
        }
        value = loaded
        loaded?.let { THUMBNAIL_CACHE.put(cacheKey, it) }
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

@Composable
private fun WallpaperThumbnail(
    model: TianYinWallpaperModel,
    modifier: Modifier = Modifier,
    useClip: Boolean = true
) {
    val context = LocalContext.current
    val dialogShape = RoundedRectangle(35f.dp)

    if (model.type == 1 && !model.videoUri.isNullOrEmpty()) {
        AndroidView(
            factory = { ctx ->
                TextureView(ctx).apply {
                    layoutParams = ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT
                    )
                    // 初始化播放器并存入 tag
                    val player = MediaPlayer().apply {
                        setVolume(0f, 0f)
                        isLooping = true
                    }
                    val holder = VideoPlayerHolder(player, model.videoUri)
                    tag = holder

                    // 设置 SurfaceTextureListener
                    surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                        override fun onSurfaceTextureAvailable(surface: SurfaceTexture, width: Int, height: Int) {
                            val holder = tag as? VideoPlayerHolder ?: return
                            val uri = holder.uri ?: return
                            try {
                                holder.player.setSurface(Surface(surface))
                                holder.player.reset()
                                holder.player.setDataSource(ctx, Uri.parse(uri))
                                holder.player.setOnPreparedListener { mp ->
                                    updateMatrix(mp, this@apply)
                                    mp.start()
                                }
                                holder.player.prepareAsync()
                            } catch (e: Exception) {
                                e.printStackTrace()
                            }
                        }

                        override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, width: Int, height: Int) {
                            (tag as? VideoPlayerHolder)?.player?.let { updateMatrix(it, this@apply) }
                        }

                        override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean {
                            (tag as? VideoPlayerHolder)?.player?.let {
                                it.pause()
                                it.stop()
                            }
                            return true
                        }

                        override fun onSurfaceTextureUpdated(surface: SurfaceTexture) {}
                    }
                }
            },
            update = { textureView ->
                // 当 model 变化时，更新 holder 中的 URI 并重新准备（如果 surface 已可用）
                val holder = textureView.tag as? VideoPlayerHolder ?: return@AndroidView
                val newUri = model.videoUri ?: return@AndroidView
                holder.uri = newUri

                if (textureView.isAvailable) {
                    try {
                        holder.player.reset()
                        holder.player.setDataSource(context, Uri.parse(newUri))
                        holder.player.setSurface(Surface(textureView.surfaceTexture))
                        holder.player.setOnPreparedListener { mp ->
                            updateMatrix(mp, textureView)
                            mp.start()
                        }
                        holder.player.prepareAsync()
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }
                }
            },
            onRelease = { textureView ->
                (textureView.tag as? VideoPlayerHolder)?.player?.release()
                textureView.tag = null
            },
            modifier = if (useClip) modifier.clip(dialogShape) else modifier
        )
    } else {
        val cacheKey = buildThumbnailCacheKey(model)
        val bitmapState = produceState<Bitmap?>(
            initialValue = THUMBNAIL_CACHE.get(cacheKey),
            cacheKey
        ) {
            val loaded = withContext(Dispatchers.IO) {
                THUMBNAIL_CACHE.get(cacheKey) ?: loadThumbnailBitmap(context, model)
            }
            value = loaded
            loaded?.let { THUMBNAIL_CACHE.put(cacheKey, it) }
        }
        bitmapState.value?.let { bitmap ->
            Image(
                bitmap = bitmap.asImageBitmap(),
                contentDescription = null,
                modifier = if (useClip) modifier.clip(dialogShape) else modifier,
                contentScale = ContentScale.Crop
            )
        }
    }
}

private fun updateMatrix(mp: MediaPlayer, view: TextureView) {
    val vWidth = mp.videoWidth.toFloat()
    val vHeight = mp.videoHeight.toFloat()
    val viewWidth = view.width.toFloat()
    val viewHeight = view.height.toFloat()

    if (vWidth > 0 && vHeight > 0 && viewWidth > 0 && viewHeight > 0) {
        val matrix = Matrix()
        val videoRatio = vWidth / vHeight
        val viewRatio = viewWidth / viewHeight

        var scaleX = 1f
        var scaleY = 1f

        if (videoRatio > viewRatio) {
            scaleX = videoRatio / viewRatio
        } else {
            scaleY = viewRatio / videoRatio
        }

        matrix.setScale(scaleX, scaleY, viewWidth / 2f, viewHeight / 2f)
        view.setTransform(matrix)
    }
}

private data class VideoPlayerHolder(
    val player: MediaPlayer,
    var uri: String?
)