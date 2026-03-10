package com.zeaze.tianyinwallpaper.ui.about

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.os.Build
import android.util.Log
import android.util.LruCache
import android.widget.Toast
import com.zeaze.tianyinwallpaper.base.rxbus.RxBus
import com.zeaze.tianyinwallpaper.base.rxbus.RxConstants
import com.zeaze.tianyinwallpaper.backdrop.backdrops.layerBackdrop
import com.zeaze.tianyinwallpaper.backdrop.backdrops.rememberCanvasBackdrop
import com.zeaze.tianyinwallpaper.backdrop.backdrops.rememberLayerBackdrop
import com.zeaze.tianyinwallpaper.backdrop.drawBackdrop
import com.zeaze.tianyinwallpaper.backdrop.effects.blur
import com.zeaze.tianyinwallpaper.backdrop.effects.colorControls
import com.zeaze.tianyinwallpaper.backdrop.effects.lens
import com.zeaze.tianyinwallpaper.backdrop.highlight.Highlight
import com.zeaze.tianyinwallpaper.ui.commom.SaveData
import com.zeaze.tianyinwallpaper.ui.commom.LiquidConfirmOverlay
import com.zeaze.tianyinwallpaper.model.TianYinWallpaperModel
import com.zeaze.tianyinwallpaper.utils.FileUtil
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
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
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.alibaba.fastjson.JSON
import com.kyant.shapes.Capsule
import com.kyant.shapes.RoundedRectangle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.zeaze.tianyinwallpaper.catalog.components.LiquidButton

@Composable
fun AboutRouteScreen(
    onSelectionModeChange: (Boolean) -> Unit = {},
    onBack: () -> Unit = {}
) {
    val context = LocalContext.current
    val density = LocalDensity.current
    val statusBarTopPadding = remember(context) {
        val id = context.resources.getIdentifier("status_bar_height", "dimen", "android")
        if (id > 0) context.resources.getDimensionPixelSize(id) else 0
    }
    val statusBarTopPaddingDp = with(density) { statusBarTopPadding.toDp() }
    val saveDataList = remember { mutableStateListOf<SaveData>() }
    val selectedPositions = remember { mutableStateListOf<Int>() }
    var selectionMode by remember { mutableStateOf(false) }
    var pendingOverwriteGroup by remember { mutableStateOf<SaveData?>(null) }
    val enableLiquidGlass = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
    val liquidBackdrop = if (enableLiquidGlass) rememberLayerBackdrop() else null
    val backgroundColor = MaterialTheme.colors.background
    val canvasBackdrop = rememberCanvasBackdrop { drawRect(backgroundColor) }
    val dialogBackdrop = liquidBackdrop ?: canvasBackdrop

    fun enterSelectionMode() {
        selectionMode = true
        selectedPositions.clear()
    }

    fun exitSelectionMode() {
        selectionMode = false
        selectedPositions.clear()
    }

    fun loadGroups() {
        Thread {
            val data = FileUtil.loadData(context, FileUtil.dataPath)
            val list = JSON.parseArray(data, SaveData::class.java) ?: emptyList()
            (context as? android.app.Activity)?.runOnUiThread {
                saveDataList.clear()
                saveDataList.addAll(list)
                selectedPositions.clear()
            }
        }.start()
    }

    fun deleteSelectedGroups() {
        if (selectedPositions.isEmpty()) {
            Toast.makeText(context, "请先选择壁纸组", Toast.LENGTH_SHORT).show()
            return
        }
        val selectedSet = selectedPositions.toSet()
        val remained = saveDataList.filterIndexed { index, _ -> index !in selectedSet }
        FileUtil.save(context, JSON.toJSONString(remained), FileUtil.dataPath) {
            (context as? android.app.Activity)?.runOnUiThread {
                saveDataList.clear()
                saveDataList.addAll(remained)
                exitSelectionMode()
                Toast.makeText(context, "已删除选中壁纸组", Toast.LENGTH_SHORT).show()
            }
        }
    }

    LaunchedEffect(Unit) { loadGroups() }
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
            Box(modifier = Modifier.fillMaxSize().background(MaterialTheme.colors.background))
            LazyColumn(
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
                    saveDataList,
                    key = { _, item -> "${item.name ?: ""}\u0000${item.s ?: ""}" }
                ) { index, data ->
                    val selected = selectedPositions.contains(index)
                    AboutGroupItem(
                        context = context,
                        data = data,
                        selected = selected,
                        onClick = {
                            if (selectionMode) {
                                if (selected) selectedPositions.remove(index) else selectedPositions.add(index)
                            } else {
                                pendingOverwriteGroup = data
                            }
                        }
                    )
                }
            }
        }

        LiquidConfirmOverlay(
            visible = pendingOverwriteGroup != null,
            backdrop = dialogBackdrop,
            message = "是否覆盖壁纸列表",
            onDismiss = { pendingOverwriteGroup = null },
            onConfirm = {
                val group = pendingOverwriteGroup ?: return@LiquidConfirmOverlay
                RxBus.postWithCode(RxConstants.RX_TRIGGER_OVERWRITE_WALLPAPER_LIST, group)
                Toast.makeText(context, "已覆盖当前壁纸列表", Toast.LENGTH_SHORT).show()
                pendingOverwriteGroup = null
            }
        )

        if (selectionMode) {
            val isAllSelected = selectedPositions.size == saveDataList.size && saveDataList.isNotEmpty()
            com.zeaze.tianyinwallpaper.ui.main.SelectionTopBar(
                statusBarTopPaddingDp = statusBarTopPaddingDp,
                enableLiquidGlass = enableLiquidGlass,
                backdrop = liquidBackdrop,
                isAllSelected = isAllSelected,
                onCancelSelect = { exitSelectionMode() },
                onDelete = { deleteSelectedGroups() },
                onToggleSelectAll = {
                    if (isAllSelected) {
                        selectedPositions.clear()
                    } else {
                        selectedPositions.clear()
                        saveDataList.indices.forEach { selectedPositions.add(it) }
                    }
                }
            )
        } else {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = statusBarTopPaddingDp + 10.dp, start = 12.dp, end = 12.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                val isDark = isSystemInDarkTheme()
                val adaptiveSurfaceColor = if (isDark) Color.Black.copy(0.3f) else Color.White.copy(0.3f)
                val textColor = if (isDark) Color.White else Color.Black

                if (enableLiquidGlass && liquidBackdrop != null) {
                    LiquidButton(
                        onClick = onBack,
                        backdrop = liquidBackdrop,
                        surfaceColor = adaptiveSurfaceColor,
                        modifier = Modifier.height(48.dp)
                    ) {
                        BasicText(
                            text = "返回",
                            modifier = Modifier.padding(horizontal = 16.dp),
                            style = TextStyle(textColor, 15.sp)
                        )
                    }
                } else {
                    Surface(
                        modifier = Modifier
                            .height(48.dp)
                             .clickable { onBack() },
                        shape = Capsule(),
                        color = if (isDark) Color(0x33000000) else Color(0xAAFFFFFF),
                        border = BorderStroke(1.dp, if (isDark) Color(0x33FFFFFF) else Color(0x88FFFFFF))
                    ) {
                        Box(contentAlignment = Alignment.Center, modifier = Modifier.padding(horizontal = 16.dp)) {
                            Text(text = "返回", color = textColor, fontSize = 15.sp)
                        }
                    }
                }

                if (enableLiquidGlass && liquidBackdrop != null) {
                    LiquidButton(
                        onClick = { enterSelectionMode() },
                        backdrop = liquidBackdrop,
                        surfaceColor = adaptiveSurfaceColor,
                        modifier = Modifier.height(48.dp)
                    ) {
                        BasicText(
                            text = "多选",
                            modifier = Modifier.padding(horizontal = 16.dp),
                            style = TextStyle(textColor, 15.sp)
                        )
                    }
                } else {
                    Surface(
                        modifier = Modifier
                            .height(48.dp)
                             .clickable { enterSelectionMode() },
                        shape = Capsule(),
                        color = if (isDark) Color(0x33000000) else Color(0xAAFFFFFF),
                        border = BorderStroke(1.dp, if (isDark) Color(0x33FFFFFF) else Color(0x88FFFFFF))
                    ) {
                        Box(contentAlignment = Alignment.Center, modifier = Modifier.padding(horizontal = 16.dp)) {
                            Text(text = "多选", color = textColor, fontSize = 15.sp)
                        }
                    }
                }
            }
        }
    }

    LaunchedEffect(selectionMode) {
        onSelectionModeChange(selectionMode)
    }
}

@Composable
private fun AboutGroupItem(
    context: Context,
    data: SaveData,
    selected: Boolean,
    onClick: () -> Unit
) {
    val wallpapers = remember(data.s) {
        JSON.parseArray(data.s, TianYinWallpaperModel::class.java) ?: emptyList()
    }
    val cardShape = RoundedCornerShape(16.dp)
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .clickable { onClick() },
        shape = cardShape,
        elevation = 4.dp,
        color = MaterialTheme.colors.surface,
        border = BorderStroke(
            if (selected) 1.2.dp else 0.5.dp,
            if (selected) Color(0xFF2A83FF) else MaterialTheme.colors.onSurface.copy(alpha = 0.1f)
        )
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
                Text(text = data.name ?: "未命名壁纸组")
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
                         .background(Color(0x33000000))
                )
            }
        }
    }
}

@Composable
private fun PreviewImage(context: Context, model: TianYinWallpaperModel?, modifier: Modifier) {
    var bitmap by remember(model) { mutableStateOf<Bitmap?>(null) }
    LaunchedEffect(model) {
        bitmap = loadPreviewBitmap(context, model)
    }
    bitmap?.let {
        Image(
            bitmap = it.asImageBitmap(),
            contentDescription = "Wallpaper preview",
            modifier = modifier.clip(RoundedCornerShape(8.dp)),
            contentScale = ContentScale.Crop
        )
    } ?: Spacer(modifier = modifier
        .clip(RoundedCornerShape(8.dp))
        .background(MaterialTheme.colors.background))
}

private val BITMAP_CACHE = object : LruCache<String, Bitmap>(
    (Runtime.getRuntime().maxMemory() / 1024 / 8).toInt()
) {
    override fun sizeOf(key: String, value: Bitmap): Int {
        return value.byteCount / 1024
    }
}

private suspend fun loadPreviewBitmap(context: Context, model: TianYinWallpaperModel?): Bitmap? {
    if (model == null) return null
    val cacheKey = model.uuid ?: (model.imgPath ?: model.imgUri ?: model.videoUri ?: "")
    if (cacheKey.isNotEmpty()) {
        BITMAP_CACHE.get(cacheKey)?.let { return it }
    }
    return withContext(Dispatchers.IO) {
        try {
            val path = when {
                model.type == 1 && !model.videoUri.isNullOrEmpty() -> model.videoUri
                !model.imgUri.isNullOrEmpty() -> model.imgUri
                else -> model.imgPath
            }
            if (path.isNullOrEmpty()) {
                null
            } else {
                val bitmap = if (model.type == 1) {
                    val retriever = MediaMetadataRetriever()
                    try {
                        retriever.setDataSource(context, android.net.Uri.parse(path))
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
                            retriever.getScaledFrameAtTime(0, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, VIDEO_PREVIEW_WIDTH, VIDEO_PREVIEW_HEIGHT)
                        } else {
                            retriever.frameAtTime
                        }
                    } finally {
                        retriever.release()
                    }
                } else {
                    decodeSampledBitmap(context, path, VIDEO_PREVIEW_WIDTH, VIDEO_PREVIEW_HEIGHT)
                }
                bitmap?.let {
                    if (cacheKey.isNotEmpty()) {
                        BITMAP_CACHE.put(cacheKey, it)
                    }
                }
                bitmap
            }
        } catch (e: Exception) {
            Log.w("AboutFragment", "Failed to load preview bitmap", e)
            null
        }
    }
}

private fun decodeSampledBitmap(context: Context, path: String, reqWidth: Int, reqHeight: Int): Bitmap? {
    val options = BitmapFactory.Options().apply {
        inJustDecodeBounds = true
    }

    val uri = android.net.Uri.parse(path)
    if (path.startsWith("content://")) {
        context.contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, options)
        }
    } else {
        BitmapFactory.decodeFile(path, options)
    }

    options.inSampleSize = calculateInSampleSize(options, reqWidth, reqHeight)
    options.inJustDecodeBounds = false

    return if (path.startsWith("content://")) {
        context.contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, options)
        }
    } else {
        BitmapFactory.decodeFile(path, options)
    }
}

private fun calculateInSampleSize(options: BitmapFactory.Options, reqWidth: Int, reqHeight: Int): Int {
    val (height: Int, width: Int) = options.run { outHeight to outWidth }
    var inSampleSize = 1

    if (height > reqHeight || width > reqWidth) {
        val halfHeight: Int = height / 2
        val halfWidth: Int = width / 2
        while (halfHeight / inSampleSize >= reqHeight && halfWidth / inSampleSize >= reqWidth) {
            inSampleSize *= 2
        }
    }
    return inSampleSize
}

private const val ITEM_PREVIEW_HEIGHT_DP = 100
private const val ITEM_PREVIEW_TOP_MARGIN_DP = 8
private const val ITEM_PREVIEW_SPACING_DP = 6
private const val PREVIEW_COUNT = 5
private const val VIDEO_PREVIEW_WIDTH = 360
private const val VIDEO_PREVIEW_HEIGHT = 240
