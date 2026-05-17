package com.zeaze.tianyinwallpaper.ui.depth

import android.app.Activity
import android.app.WallpaperManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.OpenableColumns
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.zIndex
import com.kyant.shapes.Capsule
import com.zeaze.tianyinwallpaper.App
import com.zeaze.tianyinwallpaper.base.rxbus.RxBus
import com.zeaze.tianyinwallpaper.base.rxbus.RxConstants
import com.zeaze.tianyinwallpaper.model.DepthWallpaperModel
import com.zeaze.tianyinwallpaper.service.DepthWallpaperService
import com.zeaze.tianyinwallpaper.utils.DepthImageProcessor
import com.zeaze.tianyinwallpaper.utils.DepthPrefs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.UUID

private enum class DepthAddKind {
    Photo,
    Gaussian,
    Mesh
}

private const val DEPTH_BLUR_DISABLED = 0f

@Composable
fun DepthRouteScreen(
    useDarkTheme: Boolean,
    onBottomBarVisibleChange: (Boolean) -> Unit
) {
    val context = LocalContext.current
    val activity = context as? Activity
    val pref = remember(context) { context.getSharedPreferences(App.TIANYIN, Context.MODE_PRIVATE) }
    val wallpapers = remember { mutableStateListOf<DepthWallpaperModel>() }
    val isLightTheme = !useDarkTheme
    val contentColor = if (isLightTheme) Color.Black else Color.White
    val accentColor = if (isLightTheme) Color(0xFF0088FF) else Color(0xFF0091FF)
    val cardBackground = if (isLightTheme) Color(0xFFE9E9EF) else Color(0xFF202020)

    val density = LocalDensity.current
    val statusBarTopPadding = remember(context) {
        val id = context.resources.getIdentifier("status_bar_height", "dimen", "android")
        if (id > 0) context.resources.getDimensionPixelSize(id) else 0
    }
    val statusBarTopPaddingDp = with(density) { statusBarTopPadding.toDp() }

    var activeId by remember { mutableStateOf(pref.getString(DepthPrefs.PREF_DEPTH_ACTIVE_ID, null)) }
    var showAddDialog by remember { mutableStateOf(false) }
    var pendingAddKind by remember { mutableStateOf(DepthAddKind.Photo) }
    var pendingReplaceId by remember { mutableStateOf<String?>(null) }
    var previewModel by remember { mutableStateOf<DepthWallpaperModel?>(null) }
    var showPermissionDialog by remember { mutableStateOf(false) }

    fun DepthWallpaperModel.normalizedDepthParams(): DepthWallpaperModel {
        return copy(
            blurStrength = DEPTH_BLUR_DISABLED
        )
    }

    fun loadWallpapers() {
        val parsed = DepthPrefs.loadWallpapers(pref).map { it.normalizedDepthParams() }
        wallpapers.clear()
        wallpapers.addAll(parsed)
        if (activeId == null || wallpapers.none { it.id == activeId }) {
            activeId = wallpapers.firstOrNull()?.id
        }
        DepthPrefs.saveWallpapers(pref, wallpapers.toList())
    }

    fun saveWallpapers() {
        DepthPrefs.saveWallpapers(pref, wallpapers.toList())
        activeId?.let { DepthPrefs.setActiveWallpaperId(pref, it) }
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
                val old = wallpapers[index]
                val next = old.copy(
                    imageUri = if (kind == DepthAddKind.Photo) uri.toString() else "",
                    gaussianUri = if (kind == DepthAddKind.Gaussian) uri.toString() else "",
                    meshUri = if (kind == DepthAddKind.Mesh) uri.toString() else "",
                    displayName = displayName.ifBlank { uri.lastPathSegment.orEmpty() },
                    blurStrength = DEPTH_BLUR_DISABLED
                ).normalizedDepthParams()
                DepthImageProcessor.deleteCacheFor(context, old)
                wallpapers[index] = next
                activeId = next.id
                previewModel = next
                pendingReplaceId = null
                saveWallpapers()
                return
            }
            pendingReplaceId = null
        }
        val model = DepthWallpaperModel(
            id = UUID.randomUUID().toString(),
            imageUri = if (kind == DepthAddKind.Photo) uri.toString() else "",
            gaussianUri = if (kind == DepthAddKind.Gaussian) uri.toString() else "",
            meshUri = if (kind == DepthAddKind.Mesh) uri.toString() else "",
            displayName = displayName.ifBlank { uri.lastPathSegment.orEmpty() },
            createdAt = System.currentTimeMillis(),
            sensorSensitivity = 4.5f,
            parallaxStrength = 0.045f,
            blurStrength = DEPTH_BLUR_DISABLED
        ).normalizedDepthParams()
        wallpapers.add(0, model)
        activeId = model.id
        saveWallpapers()
    }

    fun activeWallpaper(): DepthWallpaperModel? {
        return wallpapers.firstOrNull { it.id == activeId } ?: wallpapers.firstOrNull()
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

    fun applyDepthWallpaper() {
        val target = activeWallpaper()
        if (target == null) {
            Toast.makeText(context, "Add a depth wallpaper first", Toast.LENGTH_SHORT).show()
            return
        }
        activeId = target.id
        saveWallpapers()
        val hostActivity = activity ?: return
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
            addWallpaper(uri, pendingAddKind)
        }
    }

    fun launchAdd(kind: DepthAddKind) {
        pendingReplaceId = null
        pendingAddKind = kind
        showAddDialog = false
        filePicker.launch(
            when (kind) {
                DepthAddKind.Photo -> arrayOf("image/*")
                DepthAddKind.Gaussian,
                DepthAddKind.Mesh -> arrayOf("*/*")
            }
        )
    }

    fun replacePreview(kind: DepthAddKind) {
        val target = previewModel ?: return
        pendingReplaceId = target.id
        pendingAddKind = kind
        filePicker.launch(
            when (kind) {
                DepthAddKind.Photo -> arrayOf("image/*")
                DepthAddKind.Gaussian,
                DepthAddKind.Mesh -> arrayOf("*/*")
            }
        )
    }

    fun removeWallpaper(model: DepthWallpaperModel) {
        wallpapers.removeAll { it.id == model.id }
        DepthImageProcessor.deleteCacheFor(context, model)
        if (activeId == model.id) {
            activeId = wallpapers.firstOrNull()?.id
        }
        if (previewModel?.id == model.id) {
            previewModel = null
        }
        saveWallpapers()
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
        if (activeId == normalized.id) {
            activeId = normalized.id
        }
        saveWallpapers()
    }

    LaunchedEffect(Unit) {
        loadWallpapers()
    }

    LaunchedEffect(previewModel) {
        onBottomBarVisibleChange(previewModel == null)
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
            .subscribe { previewModel = activeWallpaper() }
        onDispose {
            triggerAdd.dispose()
            triggerApply.dispose()
            triggerPreview.dispose()
            onBottomBarVisibleChange(true)
        }
    }

    BackHandler(enabled = previewModel != null) {
        previewModel = null
    }

    Box(Modifier.fillMaxSize().background(MaterialTheme.colors.background)) {
        LazyVerticalGrid(
            columns = GridCells.Fixed(3),
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                start = 12.dp,
                end = 12.dp,
                top = statusBarTopPaddingDp + 76.dp,
                bottom = 110.dp
            ),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            itemsIndexed(wallpapers, key = { _, model -> model.id }) { _, model ->
                DepthWallpaperCard(
                    model = model,
                    active = model.id == activeId,
                    cardBackground = cardBackground,
                    onClick = {
                        activeId = model.id
                        saveWallpapers()
                        previewModel = model
                    },
                    onDelete = { removeWallpaper(model) }
                )
            }
        }

        if (wallpapers.isEmpty()) {
            Text(
                text = "Tap + to add a depth wallpaper",
                color = contentColor.copy(alpha = 0.7f),
                modifier = Modifier.align(Alignment.Center)
            )
        }

        if (showAddDialog) {
            DepthAddDialog(
                accentColor = accentColor,
                contentColor = contentColor,
                onDismiss = { showAddDialog = false },
                onPick = { launchAdd(it) }
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
                onDismiss = { previewModel = null },
                onApply = {
                    activeId = model.id
                    saveWallpapers()
                    applyDepthWallpaper()
                },
                onPickKind = { replacePreview(it) },
                onModelChange = { updateWallpaper(it) },
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
    onDismiss: () -> Unit,
    onApply: () -> Unit,
    onPickKind: (DepthAddKind) -> Unit,
    onModelChange: (DepthWallpaperModel) -> Unit,
    modifier: Modifier = Modifier
) {
    val pillBackground = if (MaterialTheme.colors.isLight) Color(0x22FFFFFF) else Color(0x55222222)
    var previewFps by remember(model.id) { mutableStateOf(60) }
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black)
    ) {
        DepthPreviewView(
            model = model,
            previewFps = previewFps,
            modifier = Modifier.fillMaxSize()
        )

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, end = 16.dp, top = statusBarTopPaddingDp + 12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            DepthPreviewPill(
                text = "取消",
                color = contentColor,
                background = pillBackground,
                onClick = onDismiss
            )
            DepthPreviewPill(
                text = "应用",
                color = Color.White,
                background = Color(0x662A83FF),
                onClick = onApply
            )
        }

        DepthParamPanel(
            model = model,
            previewFps = previewFps,
            onPreviewFpsChange = { previewFps = it },
            onModelChange = onModelChange,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(start = 16.dp, end = 16.dp, bottom = 78.dp)
        )

        Row(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 24.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            DepthPreviewKindPill(
                text = "图片景深",
                selected = !model.isGaussian() && !model.isMesh(),
                contentColor = contentColor,
                accentColor = accentColor,
                onClick = { onPickKind(DepthAddKind.Photo) }
            )
            DepthPreviewKindPill(
                text = "Gaussian",
                selected = model.isGaussian(),
                contentColor = contentColor,
                accentColor = accentColor,
                onClick = { onPickKind(DepthAddKind.Gaussian) }
            )
            DepthPreviewKindPill(
                text = "Mesh",
                selected = model.isMesh(),
                contentColor = contentColor,
                accentColor = accentColor,
                onClick = { onPickKind(DepthAddKind.Mesh) }
            )
        }
    }
}

@Composable
private fun DepthParamPanel(
    model: DepthWallpaperModel,
    previewFps: Int,
    onPreviewFpsChange: (Int) -> Unit,
    onModelChange: (DepthWallpaperModel) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(24.dp))
            .background(Color(0x99000000))
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        DepthParamSlider(
            label = "灵敏度",
            value = model.sensorSensitivity,
            valueText = String.format("%.1f", model.sensorSensitivity),
            range = 1f..9f,
            onValueChange = { onModelChange(model.copy(sensorSensitivity = it)) }
        )
        DepthParamSlider(
            label = "视差强度",
            value = model.parallaxStrength,
            valueText = String.format("%.3f", model.parallaxStrength),
            range = 0.001f..0.075f,
            onValueChange = { onModelChange(model.copy(parallaxStrength = it)) }
        )
        DepthFpsToggle(
            value = previewFps,
            onValueChange = onPreviewFpsChange
        )
    }
}

@Composable
private fun DepthFpsToggle(
    value: Int,
    onValueChange: (Int) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 2.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = "Preview FPS",
            color = Color.White,
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium
        )
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            DepthFpsPill(
                text = "30",
                selected = value <= 30,
                onClick = { onValueChange(30) }
            )
            DepthFpsPill(
                text = "60",
                selected = value > 30,
                onClick = { onValueChange(60) }
            )
        }
    }
}

@Composable
private fun DepthFpsPill(
    text: String,
    selected: Boolean,
    onClick: () -> Unit
) {
    Text(
        text = text,
        color = Color.White,
        fontSize = 13.sp,
        fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
        modifier = Modifier
            .clip(RoundedCornerShape(16.dp))
            .background(if (selected) Color(0x662A83FF) else Color(0x22000000))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 6.dp)
    )
}

@Composable
private fun DepthParamSlider(
    label: String,
    value: Float,
    valueText: String,
    range: ClosedFloatingPointRange<Float>,
    onValueChange: (Float) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(0.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(label, color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Medium)
            Text(valueText, color = Color.White.copy(alpha = 0.68f), fontSize = 12.sp)
        }
        Slider(
            value = value.coerceIn(range.start, range.endInclusive),
            onValueChange = onValueChange,
            valueRange = range,
            modifier = Modifier.fillMaxWidth()
        )
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
    model: DepthWallpaperModel,
    active: Boolean,
    cardBackground: Color,
    onClick: () -> Unit,
    onDelete: () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(9f / 16f)
            .clip(RoundedCornerShape(16.dp))
            .background(Color.Black)
            .clickable(onClick = onClick)
    ) {
        DepthWallpaperThumbnail(model = model, cardBackground = cardBackground)
        Text(
            text = model.typeLabel(),
            color = Color.White,
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(3.dp)
                .background(Color(0x66000000), RoundedCornerShape(16.dp))
                .padding(horizontal = 6.dp, vertical = 2.dp)
        )
        if (active) {
            Text(
                text = "Active",
                color = Color.White,
                fontSize = 11.sp,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 3.dp)
                    .background(Color(0x66000000), RoundedCornerShape(16.dp))
                    .padding(horizontal = 6.dp, vertical = 2.dp)
            )
        }
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

@Composable
private fun DepthWallpaperThumbnail(
    model: DepthWallpaperModel,
    cardBackground: Color
) {
    val context = LocalContext.current
    val bitmap by produceState<Bitmap?>(initialValue = null, model.imageUri) {
        value = if (model.imageUri.isBlank()) {
            null
        } else {
            withContext(Dispatchers.IO) {
                runCatching {
                    val options = BitmapFactory.Options().apply {
                        inPreferredConfig = Bitmap.Config.RGB_565
                        inSampleSize = 2
                    }
                    context.contentResolver.openInputStream(Uri.parse(model.imageUri))?.use {
                        BitmapFactory.decodeStream(it, null, options)
                    }
                }.getOrNull()
            }
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
            modifier = Modifier.fillMaxSize().background(cardBackground),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = model.typeLabel(),
                color = Color.White.copy(alpha = 0.85f),
                fontWeight = FontWeight.Bold
            )
        }
    }
}

@Composable
private fun DepthAddDialog(
    accentColor: Color,
    contentColor: Color,
    onDismiss: () -> Unit,
    onPick: (DepthAddKind) -> Unit
) {
    Dialog(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .clip(RoundedCornerShape(24.dp))
                .background(MaterialTheme.colors.surface)
                .padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text("Add depth wallpaper", color = contentColor, fontWeight = FontWeight.Bold, fontSize = 18.sp)
            AddDialogButton("Photo depth", accentColor) { onPick(DepthAddKind.Photo) }
            AddDialogButton("Gaussian PLY", accentColor) { onPick(DepthAddKind.Gaussian) }
            AddDialogButton("Mesh PLY", accentColor) { onPick(DepthAddKind.Mesh) }
            Spacer(Modifier.height(2.dp))
            Text(
                text = "Cancel",
                color = contentColor.copy(alpha = 0.7f),
                modifier = Modifier
                    .clip(Capsule())
                    .clickable(onClick = onDismiss)
                    .padding(horizontal = 18.dp, vertical = 8.dp)
            )
        }
    }
}

@Composable
private fun AddDialogButton(
    text: String,
    accentColor: Color,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(46.dp)
            .clip(Capsule())
            .background(accentColor)
            .clickable(onClick = onClick),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(text, color = Color.White, fontWeight = FontWeight.Bold)
    }
}

private fun DepthWallpaperModel.typeLabel(): String {
    return when {
        isMesh() -> "Mesh"
        isGaussian() -> "Gaussian"
        else -> "Photo"
    }
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
