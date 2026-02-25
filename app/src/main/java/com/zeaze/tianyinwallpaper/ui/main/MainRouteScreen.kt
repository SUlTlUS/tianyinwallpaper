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
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.VectorConverter
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.material.AlertDialog
import androidx.compose.material.Button
import androidx.compose.material.Checkbox
import androidx.compose.material.DropdownMenu
import androidx.compose.material.DropdownMenuItem
import androidx.compose.material.MaterialTheme
import androidx.compose.material.OutlinedTextField
import androidx.compose.material.Surface
import androidx.compose.material.Text
import androidx.compose.material.TextFieldDefaults
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
import androidx.compose.ui.geometry.center
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.toSize
import androidx.compose.ui.util.fastCoerceAtMost
import androidx.compose.ui.util.lerp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.documentfile.provider.DocumentFile
import com.alibaba.fastjson.JSON
import com.kyant.backdrop.backdrops.LayerBackdrop
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.effects.colorControls
import com.kyant.backdrop.effects.lens
import com.kyant.backdrop.effects.vibrancy
import com.kyant.backdrop.highlight.Highlight
import com.zeaze.tianyinwallpaper.App
import com.zeaze.tianyinwallpaper.R
import com.zeaze.tianyinwallpaper.base.rxbus.RxBus
import com.zeaze.tianyinwallpaper.base.rxbus.RxConstants
import com.zeaze.tianyinwallpaper.model.TianYinWallpaperModel
import com.zeaze.tianyinwallpaper.ui.setting.LiquidToggle
import com.zeaze.tianyinwallpaper.service.TianYinWallpaperService
import com.zeaze.tianyinwallpaper.utils.FileUtil
import io.reactivex.functions.Consumer
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.Collections
import java.util.UUID
import kotlin.concurrent.thread
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.tanh
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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
    val pref = remember(context) { context.getSharedPreferences(App.TIANYIN, android.content.Context.MODE_PRIVATE) }
    val editor = remember(pref) { pref.edit() }

    val wallpapers = remember { mutableStateListOf<TianYinWallpaperModel>() }
    val selectedPositions = remember { mutableStateListOf<Int>() }

    var selectionMode by remember { mutableStateOf(false) }
    var groupName by remember { mutableStateOf("") }

    var showWallpaperTypeDialog by remember { mutableStateOf(false) }
    var showPermissionDialog by remember { mutableStateOf(false) }
    var showMoreMenu by remember { mutableStateOf(false) }
    var showDeleteSelectedDialog by remember { mutableStateOf(false) }

    var actionDialogIndex by remember { mutableStateOf<Int?>(null) }
    var timeDialogIndex by remember { mutableStateOf<Int?>(null) }
    val density = LocalDensity.current
    val statusBarTopPadding = remember(context) {
        val id = context.resources.getIdentifier("status_bar_height", "dimen", "android")
        if (id > 0) context.resources.getDimensionPixelSize(id) else 0
    }
    val statusBarTopPaddingDp = with(density) { statusBarTopPadding.toDp() }

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
        thread {
            FileUtil.save(context, JSON.toJSONString(wallpapers), FileUtil.wallpaperPath) {
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

    val rowGroups = remember(wallpapers.size) {
        wallpapers.indices.toList().chunked(3
        )
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
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(
                    start = 12.dp,
                    end = 12.dp,
                    top = statusBarTopPaddingDp + 88.dp,
                    bottom = if (selectionMode) 90.dp else 12.dp
                ),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                items(rowGroups) { row ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        repeat(3) { columnIndex ->
                            val itemIndex = row.getOrNull(columnIndex)
                            if (itemIndex == null) {
                                Spacer(modifier = Modifier.weight(1f))
                            } else {
                                val model = wallpapers[itemIndex]
                                val selected = selectedPositions.contains(itemIndex)
                                Box(
                                    modifier = Modifier
                                        .weight(1f)
                                        .aspectRatio(0.62f)
                                        .clip(RoundedCornerShape(16.dp))
                                        .clickable {
                                            if (selectionMode) {
                                                if (selected) selectedPositions.remove(itemIndex) else selectedPositions.add(itemIndex)
                                            } else {
                                                actionDialogIndex = itemIndex
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
                                    if (selected) {
                                        Box(modifier = Modifier.fillMaxSize().background(Color(0x77000000)))
                                        Surface(
                                            modifier = Modifier
                                                .align(Alignment.TopEnd)
                                                .padding(6.dp),
                                            shape = CircleShape,
                                            color = Color(0xD91A1A1A)
                                        ) {
                                            Text(
                                                text = "✓",
                                                color = Color.White,
                                                fontSize = 12.sp,
                                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
        // 前景层：包含 TopBar 和删除按钮，它们采样底层捕获的内容
        if (selectionMode) {
            SelectionTopBar(
                statusBarTopPaddingDp = statusBarTopPaddingDp,
                enableLiquidGlass = enableLiquidGlass,
                backdrop = liquidBackdrop,
                onCancelSelect = { exitSelectionMode() }
            )
            GlassCircleButton(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 26.dp),
                enableLiquidGlass = enableLiquidGlass,
                backdrop = liquidBackdrop,
                label = context.getString(R.string.delete_symbol),
                onClick = {
                    if (selectedPositions.isEmpty()) {
                        toast(context.getString(R.string.no_selected_tip))
                    } else {
                        showDeleteSelectedDialog = true
                    }
                }
            )
        } else {
            MainTopBar(
                statusBarTopPaddingDp = statusBarTopPaddingDp,
                enableLiquidGlass = enableLiquidGlass,
                backdrop = liquidBackdrop,
                groupName = groupName,
                onGroupNameChange = {
                    groupName = it
                    saveCache()
                },
                onAdd = { showWallpaperTypeDialog = true },
                onApply = { applyWallpapers() },
                moreMenuExpanded = showMoreMenu,
                onMoreMenuExpandedChange = { showMoreMenu = it },
                onSelect = {
                    showMoreMenu = false
                    enterSelectionMode()
                },
                onOpenSetting = {
                    showMoreMenu = false
                    onOpenSettingPage()
                }
            )
        }

        // 1. Background dimming layer
        AnimatedVisibility(
            visible = actionDialogIndex != null,
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
                    ) { actionDialogIndex = null }
            )
        }

        // 2. Custom Liquid Glass Dialog
        AnimatedContent(
            targetState = actionDialogIndex,
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
            label = "ActionDialog",
            modifier = Modifier.fillMaxSize()
        ) { targetIndex ->
            if (targetIndex != null) {
                val model = wallpapers.getOrNull(targetIndex) ?: return@AnimatedContent
                var loopState by remember(targetIndex) { mutableStateOf(model.loop) }

                Column(
                    modifier = Modifier
                        .requiredWidth(260.dp)
                        .wrapContentHeight()
                        .drawBackdrop(
                            backdrop = liquidBackdrop!!,
                            shape = { RoundedCornerShape(32.dp) },
                            effects = {
                                colorControls(
                                    brightness = if (isLightTheme) 0.2f else 0f,
                                    saturation = 1.5f
                                )
                                blur(if (isLightTheme) 16f.dp.toPx() else 8f.dp.toPx())
                                lens(24f.dp.toPx(), 48f.dp.toPx(), depthEffect = true)
                            },
                            highlight = { Highlight.Default },
                            onDrawSurface = { drawRect(containerColor) }
                        )
                        .pointerInput(Unit) { detectTapGestures { /* consume */ } }
                ) {
                    BasicText(
                        "壁纸选项",
                        Modifier.padding(20.dp, 20.dp, 20.dp, 4.dp),
                        style = TextStyle(contentColor, 18.sp, FontWeight.Medium)
                    )

                    Box(
                        Modifier
                            .fillMaxWidth()
                            .padding(vertical = 8.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        WallpaperThumbnail(
                            model = model,
                            modifier = Modifier
                                .fillMaxWidth(0.92f)
                                .aspectRatio(0.62f)
                                .clip(RoundedCornerShape(16.dp))
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
                                .clip(CircleShape)
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
                                .clip(CircleShape)
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
                                backdrop = liquidBackdrop
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
                                    .clip(CircleShape)
                                    .background(Color(0xFFFF4D4F))
                                    .clickable {
                                        delete(targetIndex)
                                        actionDialogIndex = null
                                    }
                                    .height(48.dp),
                                horizontalArrangement = Arrangement.Center,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                BasicText("删除", style = TextStyle(Color.White, 16.sp))
                            }

                            // Cancel Button
                            Row(
                                Modifier
                                    .weight(1f)
                                    .clip(CircleShape)
                                    .background(accentColor)
                                    .clickable { actionDialogIndex = null }
                                    .height(48.dp),
                                horizontalArrangement = Arrangement.Center,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                BasicText("取消", style = TextStyle(Color.White, 16.sp))
                            }
                        }
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
                    Button(onClick = { showWallpaperTypeDialog = false; directoryLaunch.launch(null) }, modifier = Modifier.fillMaxWidth()) {
                        Text(context.getString(R.string.main_wallpaper_type_directory))
                    }
                    Button(onClick = { showWallpaperTypeDialog = false }, modifier = Modifier.fillMaxWidth()) {
                        Text(context.getString(R.string.common_cancel))
                    }
                }
            },
            confirmButton = {}
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
                Button(onClick = { showDeleteSelectedDialog = false }) { Text(context.getString(R.string.common_cancel)) }
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
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Button(onClick = {
                            startTime = -1
                            endTime = -1
                            startText = ""
                            endText = ""
                        }) { Text("重置") }
                        Spacer(modifier = Modifier.width(8.dp))
                        Button(onClick = { timeDialogIndex = null }) { Text(context.getString(R.string.common_cancel)) }
                        Spacer(modifier = Modifier.width(8.dp))
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
                    }
                }
            )
        }
    }


}

@Composable
private fun MainTopBar(
    statusBarTopPaddingDp: androidx.compose.ui.unit.Dp,
    enableLiquidGlass: Boolean,
    backdrop: LayerBackdrop?,
    groupName: String,
    onGroupNameChange: (String) -> Unit,
    onAdd: () -> Unit,
    onApply: () -> Unit,
    moreMenuExpanded: Boolean,
    onMoreMenuExpandedChange: (Boolean) -> Unit,
    onSelect: () -> Unit,
    onOpenSetting: () -> Unit
) {
    val animationScope = androidx.compose.runtime.rememberCoroutineScope()
    val inputInteractiveHighlight = remember(animationScope) { LiquidInteractiveHighlight(animationScope) }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = statusBarTopPaddingDp + 10.dp, start = 8.dp, end = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        GlassCircleButton(
            enableLiquidGlass = enableLiquidGlass,
            backdrop = backdrop,
            label = "+",
            onClick = onAdd
        )

        val inputModifier = if (enableLiquidGlass && backdrop != null) {
            Modifier.drawBackdrop(
                backdrop = backdrop,
                shape = { RoundedCornerShape(26.dp) },
                effects = {
                    vibrancy()
                    blur(2f.dp.toPx())
                    lens(12f.dp.toPx(), 24f.dp.toPx())
                },
                layerBlock = {
                    if (size.width > 0f && size.height > 0f) {
                        val progress = inputInteractiveHighlight.pressProgress
                        val scale = lerp(1f, 1f + 4f.dp.toPx() / size.height, progress)

                        val maxOffset = size.minDimension.coerceAtLeast(1f)
                        val initialDerivative = 0.05f
                        val offset = inputInteractiveHighlight.offset
                        translationX = maxOffset * tanh(initialDerivative * offset.x / maxOffset)
                        translationY = maxOffset * tanh(initialDerivative * offset.y / maxOffset)

                        val maxDragScale = 4f.dp.toPx() / size.height
                        val offsetAngle = atan2(offset.y, offset.x)
                        val width = size.width
                        val height = size.height
                        scaleX = scale + maxDragScale * abs(cos(offsetAngle) * offset.x / size.maxDimension.coerceAtLeast(1f)) * (width / height).fastCoerceAtMost(1f)
                        scaleY = scale + maxDragScale * abs(sin(offsetAngle) * offset.y / size.maxDimension.coerceAtLeast(1f)) * (height / width).fastCoerceAtMost(1f)
                    }
                }
            ).then(inputInteractiveHighlight.gestureModifier)
        } else {
            Modifier
        }

        OutlinedTextField(
            value = groupName,
            onValueChange = onGroupNameChange,
            modifier = Modifier
                .weight(1f)
                .height(52.dp)
                .then(inputModifier),
            singleLine = true,
            shape = RoundedCornerShape(26.dp),
            colors = TextFieldDefaults.outlinedTextFieldColors(
                backgroundColor = Color.Transparent,
                focusedBorderColor = Color.Transparent,
                unfocusedBorderColor = Color.Transparent,
                disabledBorderColor = Color.Transparent,
                errorBorderColor = Color.Transparent
            ),
            placeholder = { Text("输入壁纸组名称") }
        )
        Box {
            GlassCircleButton(
                enableLiquidGlass = enableLiquidGlass,
                backdrop = backdrop,
                label = "…",
                onClick = { onMoreMenuExpandedChange(true) }
            )
            DropdownMenu(
                expanded = moreMenuExpanded,
                onDismissRequest = { onMoreMenuExpandedChange(false) }
            ) {
                DropdownMenuItem(onClick = onSelect) {
                    Text("选择")
                }
                DropdownMenuItem(onClick = onOpenSetting) {
                    Text("设置")
                }
            }
        }
        GlassCircleButton(
            enableLiquidGlass = enableLiquidGlass,
            backdrop = backdrop,
            label = "✓",
            onClick = onApply
        )
    }
}

@Composable
private fun SelectionTopBar(
    statusBarTopPaddingDp: androidx.compose.ui.unit.Dp,
    enableLiquidGlass: Boolean,
    backdrop: LayerBackdrop?,
    onCancelSelect: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = statusBarTopPaddingDp + 10.dp, end = 8.dp),
        horizontalArrangement = Arrangement.End
    ) {
        GlassCircleButton(
            enableLiquidGlass = enableLiquidGlass,
            backdrop = backdrop,
            label = "×",
            onClick = onCancelSelect
        )
    }
}


@Composable
private fun GlassCircleButton(
    modifier: Modifier = Modifier,
    enableLiquidGlass: Boolean,
    backdrop: LayerBackdrop?,
    label: String,
    onClick: () -> Unit
) {
    val animationScope = androidx.compose.runtime.rememberCoroutineScope()
    val interactiveHighlight = remember(animationScope) { LiquidInteractiveHighlight(animationScope) }

    val glassModifier = if (enableLiquidGlass && backdrop != null) {
        Modifier.drawBackdrop(
            backdrop = backdrop,
            shape = { CircleShape },
            effects = {
                vibrancy()
                blur(2f.dp.toPx())
                lens(12f.dp.toPx(), 24f.dp.toPx())
            },
            layerBlock = {
                if (size.width > 0f && size.height > 0f) {
                    val progress = interactiveHighlight.pressProgress
                    val scale = lerp(1f, 1f + 4f.dp.toPx() / size.height, progress)

                    val maxOffset = size.minDimension.coerceAtLeast(1f)
                    val initialDerivative = 0.05f
                    val offset = interactiveHighlight.offset
                    translationX = maxOffset * tanh(initialDerivative * offset.x / maxOffset)
                    translationY = maxOffset * tanh(initialDerivative * offset.y / maxOffset)

                    val maxDragScale = 4f.dp.toPx() / size.height
                    val offsetAngle = atan2(offset.y, offset.x)
                    val width = size.width
                    val height = size.height
                    scaleX = scale + maxDragScale * abs(cos(offsetAngle) * offset.x / size.maxDimension.coerceAtLeast(1f)) * (width / height).fastCoerceAtMost(1f)
                    scaleY = scale + maxDragScale * abs(sin(offsetAngle) * offset.y / size.maxDimension.coerceAtLeast(1f)) * (height / width).fastCoerceAtMost(1f)
                }
            }
        ).then(interactiveHighlight.gestureModifier)
    } else {
        Modifier
    }

    val isDark = isSystemInDarkTheme()
    val surfaceColor = if (enableLiquidGlass) Color.Transparent else (if (isDark) Color(0x33000000) else Color(0xAAFFFFFF))
    val borderColor = if (enableLiquidGlass) null else androidx.compose.foundation.BorderStroke(1.dp, if (isDark) Color(0x33FFFFFF) else Color(0x88FFFFFF))
    val textColor = if (isDark) Color.White else Color(0xFF1A2433)

    Surface(
        modifier = modifier
            .size(48.dp)
            .then(glassModifier),
        shape = CircleShape,
        color = surfaceColor,
        border = borderColor
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .clickable(onClick = onClick),
            contentAlignment = Alignment.Center
        ) {
            Text(text = label, color = textColor, fontSize = 20.sp)
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
private fun WallpaperThumbnail(model: TianYinWallpaperModel, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    if (model.type == 1 && !model.videoUri.isNullOrEmpty()) {
        AndroidView(
            factory = { ctx ->
                TextureView(ctx).apply {
                    val textureView = this
                    layoutParams = ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT
                    )
                    surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                        private var mediaPlayer: MediaPlayer? = null

                        private fun updateMatrix(mp: MediaPlayer, view: TextureView) {
                            val vWidth = mp.videoWidth.toFloat()
                            val vHeight = mp.videoHeight.toFloat()
                            val viewWidth = view.width.toFloat()
                            val viewHeight = view.height.toFloat()

                            if (vWidth > 0 && vHeight > 0 && viewWidth > 0 && viewHeight > 0) {
                                val matrix = Matrix()
                                val videoRatio = vWidth / vHeight
                                val viewRatio = viewWidth / viewHeight

                                // TextureView 默认会拉伸填充。
                                // 我们通过缩放来抵消拉伸并实现 Center Crop。
                                var scaleX = 1f
                                var scaleY = 1f

                                if (videoRatio > viewRatio) {
                                    // 视频比视图宽 -> 保持高度，拉伸宽度以裁剪两侧
                                    scaleX = videoRatio / viewRatio
                                } else {
                                    // 视频比视图高 -> 保持宽度，拉伸高度以裁剪顶底
                                    scaleY = viewRatio / videoRatio
                                }

                                matrix.setScale(scaleX, scaleY, viewWidth / 2f, viewHeight / 2f)
                                view.setTransform(matrix)
                            }
                        }

                        override fun onSurfaceTextureAvailable(
                            surface: SurfaceTexture,
                            width: Int,
                            height: Int
                        ) {
                            try {
                                mediaPlayer = MediaPlayer().apply {
                                    setSurface(Surface(surface))
                                    setDataSource(ctx, Uri.parse(model.videoUri))
                                    isLooping = true
                                    setVolume(0f, 0f)
                                    setOnPreparedListener { mp ->
                                        updateMatrix(mp, textureView)
                                        mp.start()
                                    }
                                    prepareAsync()
                                }
                            } catch (e: Exception) {
                                e.printStackTrace()
                            }
                        }

                        override fun onSurfaceTextureSizeChanged(
                            surface: SurfaceTexture,
                            width: Int,
                            height: Int
                        ) {
                            mediaPlayer?.let { updateMatrix(it, textureView) }
                        }

                        override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean {
                            mediaPlayer?.stop()
                            mediaPlayer?.release()
                            mediaPlayer = null
                            return true
                        }

                        override fun onSurfaceTextureUpdated(surface: SurfaceTexture) {}
                    }
                }
            },
            modifier = modifier
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
                modifier = modifier,
                contentScale = ContentScale.Crop
            )
        }
    }
}

private class LiquidInteractiveHighlight(
    val animationScope: kotlinx.coroutines.CoroutineScope
) {
    var touchOffset by mutableStateOf(Offset.Zero)
    var isPressed by mutableStateOf(false)
    val pressAnim = Animatable(0f)
    val pressProgress: Float get() = pressAnim.value

    val offsetAnim = Animatable(Offset.Zero, Offset.VectorConverter)
    val offset: Offset get() = offsetAnim.value

    val gestureModifier = Modifier.pointerInput(Unit) {
        detectTapGestures(
            onPress = { offset ->
                isPressed = true
                touchOffset = offset
                val center = size.toSize().center
                val targetOffset = offset - center
                animationScope.launch {
                    pressAnim.animateTo(1f, spring(0.8f, 400f))
                }
                animationScope.launch {
                    offsetAnim.animateTo(targetOffset, spring(0.8f, 400f))
                }
                try {
                    tryAwaitRelease()
                } finally {
                    isPressed = false
                    animationScope.launch {
                        pressAnim.animateTo(0f, spring(0.8f, 400f))
                    }
                    animationScope.launch {
                        offsetAnim.animateTo(Offset.Zero, spring(0.8f, 400f))
                    }
                }
            }
        )
    }
}
