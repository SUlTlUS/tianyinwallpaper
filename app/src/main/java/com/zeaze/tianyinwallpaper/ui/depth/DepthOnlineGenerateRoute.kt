package com.zeaze.tianyinwallpaper.ui.depth

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.OpenableColumns
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.material.CircularProgressIndicator
import androidx.compose.material.Icon
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kyant.shapes.Capsule
import com.zeaze.tianyinwallpaper.App
import com.zeaze.tianyinwallpaper.R
import com.zeaze.tianyinwallpaper.backdrop.backdrops.layerBackdrop
import com.zeaze.tianyinwallpaper.backdrop.backdrops.rememberLayerBackdrop
import com.zeaze.tianyinwallpaper.catalog.components.LiquidButton
import com.zeaze.tianyinwallpaper.catalog.components.PlainFallbackStyle
import com.zeaze.tianyinwallpaper.catalog.components.PlainIconButton
import com.zeaze.tianyinwallpaper.utils.DepthPrefs
import com.zeaze.tianyinwallpaper.utils.GradioMcpSogGenerator
import com.zeaze.tianyinwallpaper.utils.LiquidGlassPrefs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun DepthOnlineGenerateRoute(
    modifier: Modifier = Modifier,
    useDarkTheme: Boolean,
    enableLiquidGlass: Boolean = true,
    onBack: () -> Unit,
    onImportSog: (Uri, String?, String) -> Unit
) {
    val context = LocalContext.current
    val density = LocalDensity.current
    val coroutineScope = rememberCoroutineScope()
    val isLight = !useDarkTheme
    val contentColor = if (isLight) Color.Black else Color.White
    val accentColor = if (isLight) Color(0xFF0088FF) else Color(0xFF0091FF)
    val pageBackgroundColor = if (isLight) Color.White else Color(0xFF0A0A0C)
    val groupBackgroundColor = if (isLight) Color(0xFFF2F3F7) else Color(0xFF1C1C20).copy(alpha = 0.94f)
    val subtleGroupBackground = if (isLight) Color(0xFFE8EBF2) else Color(0xFF25252A).copy(alpha = 0.88f)
    val useLiquidGlass = enableLiquidGlass && LiquidGlassPrefs.isEnabled(context)
    val topBackdrop = if (useLiquidGlass) rememberLayerBackdrop() else null
    val topSurfaceColor = if (isLight) Color.White.copy(alpha = 0.3f) else Color.Black.copy(alpha = 0.3f)
    val statusBarTopPaddingDp = remember(context) {
        val id = context.resources.getIdentifier("status_bar_height", "dimen", "android")
        val px = if (id > 0) context.resources.getDimensionPixelSize(id) else 0
        with(density) { px.toDp() }
    }

    var history by remember { mutableStateOf<List<GradioMcpSogGenerator.SogGenerationRecord>>(emptyList()) }
    var importedRecordIds by remember { mutableStateOf<Set<String>>(emptySet()) }
    val pollingRecordIds = remember { mutableStateListOf<String>() }
    val wallpaperPrefs = remember(context) {
        context.getSharedPreferences(App.TIANYIN, Context.MODE_PRIVATE)
    }

    fun refreshHistory() {
        coroutineScope.launch {
            val snapshot = withContext(Dispatchers.IO) {
                val records = GradioMcpSogGenerator.getHistory(context.applicationContext)
                val importedIds = DepthPrefs.findImportedOnlineRecordIds(
                    wallpaperPrefs,
                    records.associate { it.id to it.sogLocalPath }
                )
                records to importedIds
            }
            history = snapshot.first
            importedRecordIds = snapshot.second
        }
    }

    fun startRecordResume(recordId: String) {
        if (recordId in pollingRecordIds) return
        pollingRecordIds.add(recordId)
        coroutineScope.launch(Dispatchers.IO) {
            try {
                GradioMcpSogGenerator.pollAndUpdateRecord(context.applicationContext, recordId)
            } finally {
                withContext(Dispatchers.Main) {
                    pollingRecordIds.remove(recordId)
                    history = GradioMcpSogGenerator.getHistory(context.applicationContext)
                }
            }
        }
    }

    LaunchedEffect(Unit) {
        val snapshot = withContext(Dispatchers.IO) {
            val records = GradioMcpSogGenerator.getHistory(context.applicationContext)
            val importedIds = DepthPrefs.findImportedOnlineRecordIds(
                wallpaperPrefs,
                records.associate { it.id to it.sogLocalPath }
            )
            records to importedIds
        }
        history = snapshot.first
        importedRecordIds = snapshot.second
        history.filter { record ->
            !record.sogDownloaded &&
                (record.status == "generating" ||
                    record.status == "pending" ||
                    record.status == "downloading" ||
                    (record.status == "completed" && !record.sogDownloaded)) &&
                (!record.taskId.isNullOrBlank() || !record.eventId.isNullOrBlank() || !record.sogServerUrl.isNullOrBlank())
        }.forEach { startRecordResume(it.id) }
    }

    val onlineImagePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) {
            coroutineScope.launch {
                uris.forEach { uri ->
                    val fileName = queryOnlineDisplayName(context, uri)
                    var recordId: String? = null
                    runCatching {
                        val stableUri = withContext(Dispatchers.IO) {
                            GradioMcpSogGenerator.copyInputImageToCache(context.applicationContext, uri, fileName)
                        }
                        val createdRecordId = GradioMcpSogGenerator.createPendingRecord(
                            context.applicationContext,
                            stableUri,
                            fileName
                        )
                        recordId = createdRecordId
                        refreshHistory()
                        withContext(Dispatchers.IO) {
                            GradioMcpSogGenerator.submitTaskForRecord(
                                context.applicationContext,
                                createdRecordId,
                                stableUri
                            )
                        }
                        history = withContext(Dispatchers.IO) {
                            GradioMcpSogGenerator.getHistory(context.applicationContext)
                        }
                        startRecordResume(createdRecordId)
                    }.onFailure {
                        val failedRecordId = recordId
                        if (failedRecordId != null) {
                            withContext(Dispatchers.IO) {
                                GradioMcpSogGenerator.updateRecord(context.applicationContext, failedRecordId) { rec ->
                                    rec.copy(status = "failed", errorMessage = it.message?.take(100))
                                }
                                history = GradioMcpSogGenerator.getHistory(context.applicationContext)
                            }
                        }
                        Toast.makeText(
                            context,
                            "${fileName ?: "图片"}: ${it.message ?: "Submit task failed"}",
                            Toast.LENGTH_LONG
                        ).show()
                    }
                }
            }
        }
    }

    fun downloadRecord(record: GradioMcpSogGenerator.SogGenerationRecord) {
        if (record.id !in pollingRecordIds) pollingRecordIds.add(record.id)
        coroutineScope.launch {
            runCatching {
                val updated = withContext(Dispatchers.IO) {
                    GradioMcpSogGenerator.downloadRecordSog(context.applicationContext, record.id)
                }
                history = withContext(Dispatchers.IO) {
                    GradioMcpSogGenerator.getHistory(context.applicationContext)
                }
                if (updated.sogDownloaded && updated.sogLocalPath != null) {
                    onImportSog(
                        Uri.fromFile(File(updated.sogLocalPath)),
                        updated.inputImageLocalUri,
                        updated.id
                    )
                    importedRecordIds = importedRecordIds + updated.id
                }
            }.onFailure {
                Toast.makeText(context, it.message ?: "下载失败", Toast.LENGTH_LONG).show()
            }
            pollingRecordIds.remove(record.id)
            refreshHistory()
        }
    }

    fun importRecord(record: GradioMcpSogGenerator.SogGenerationRecord) {
        if (record.sogLocalPath != null) {
            onImportSog(
                Uri.fromFile(File(record.sogLocalPath)),
                record.inputImageLocalUri,
                record.id
            )
            importedRecordIds = importedRecordIds + record.id
        }
    }

    fun retryRecord(record: GradioMcpSogGenerator.SogGenerationRecord) {
        if (record.id !in pollingRecordIds) pollingRecordIds.add(record.id)
        coroutineScope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    GradioMcpSogGenerator.retryTask(context.applicationContext, record.id)
                    GradioMcpSogGenerator.pollAndUpdateRecord(context.applicationContext, record.id)
                }
                history = withContext(Dispatchers.IO) {
                    GradioMcpSogGenerator.getHistory(context.applicationContext)
                }
            }.onFailure {
                Toast.makeText(context, it.message ?: "重试失败", Toast.LENGTH_LONG).show()
            }
            pollingRecordIds.remove(record.id)
            refreshHistory()
        }
    }

    fun deleteRecord(record: GradioMcpSogGenerator.SogGenerationRecord) {
        coroutineScope.launch {
            withContext(Dispatchers.IO) {
                GradioMcpSogGenerator.deleteRecord(context.applicationContext, record.id)
            }
            history = withContext(Dispatchers.IO) {
                GradioMcpSogGenerator.getHistory(context.applicationContext)
            }
        }
    }

    fun clearAllRecords() {
        coroutineScope.launch {
            withContext(Dispatchers.IO) {
                GradioMcpSogGenerator.clearHistory(context.applicationContext)
            }
            history = emptyList()
            importedRecordIds = emptySet()
            pollingRecordIds.clear()
        }
    }

    Box(modifier = modifier.fillMaxSize()) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .let { base ->
                    if (useLiquidGlass && topBackdrop != null) base.layerBackdrop(topBackdrop) else base
                }
        ) {
            Box(Modifier.fillMaxSize().background(pageBackgroundColor))
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 16.dp),
                contentPadding = PaddingValues(
                    top = statusBarTopPaddingDp + 76.dp,
                    bottom = 28.dp
                ),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                item {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = "在线生成景深壁纸",
                    style = TextStyle(contentColor, 32.sp, fontWeight = FontWeight.Bold)
                )
                Text(
                    text = "无需后台运行",
                    color = contentColor.copy(alpha = 0.55f),
                    fontSize = 13.sp,
                    lineHeight = 18.sp
                )
                    }
                }

                item {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {

                OnlineDialogButton(
                    "选择图片",
                    accentColor,
                    Color.White,
                    { onlineImagePicker.launch(arrayOf("image/*")) },
                    Modifier.fillMaxWidth()
                )
            }
        }

                item {
            Row(Modifier.fillMaxWidth().padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("生成记录", color = contentColor, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.width(8.dp))
                Box(
                    modifier = Modifier
                        .size(32.dp)
                        .clip(CircleShape)
                        .background(Color(0xFFFF3B30).copy(alpha = if (history.isEmpty()) 0.06f else 0.12f))
                        .clickable(
                            enabled = history.isNotEmpty(),
                            indication = null,
                            interactionSource = remember { MutableInteractionSource() }
                        ) { clearAllRecords() }
                        .padding(8.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        painter = painterResource(R.drawable.delete),
                        contentDescription = "清空全部生成记录",
                        modifier = Modifier.fillMaxSize(),
                        tint = Color(0xFFFF3B30).copy(alpha = if (history.isEmpty()) 0.35f else 1f)
                    )
                }
                Spacer(Modifier.weight(1f))
                Text("${history.size} 条", color = contentColor.copy(alpha = 0.45f), fontSize = 13.sp)
            }
        }

        if (history.isEmpty()) {
            item {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(220.dp)
                        .clip(RoundedCornerShape(28.dp))
                        .background(subtleGroupBackground),
                    contentAlignment = Alignment.Center
                ) {
                    Text("暂无生成记录", color = contentColor.copy(alpha = 0.35f), fontSize = 14.sp)
                }
            }
        } else {
                items(history, key = { it.id }) { record ->
                val isPolling = record.id in pollingRecordIds
                val isImported = record.id in importedRecordIds
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(24.dp))
                        .background(groupBackgroundColor)
                        .clickable(enabled = !isPolling && !isImported) {
                            when {
                                record.status == "completed" && record.sogDownloaded -> importRecord(record)
                                record.status == "completed" && !record.sogDownloaded -> downloadRecord(record)
                                record.status == "failed" -> retryRecord(record)
                            }
                        }
                        .padding(horizontal = 14.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OnlineRecordThumbnail(
                        localUri = record.inputImageLocalUri,
                        modifier = Modifier
                            .size(56.dp)
                            .clip(RoundedCornerShape(16.dp))
                    )
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            record.inputImageName ?: record.inputImageUrl?.substringAfterLast('/') ?: "图片",
                            color = contentColor,
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Medium,
                            maxLines = 1
                        )
                        Text(
                            when (record.status) {
                                "pending" -> "${formatOnlineAbsoluteTime(record.createdAt)}  等待中…"
                                "generating" -> "${formatOnlineAbsoluteTime(record.createdAt)}  生成中…"
                                "completed" -> when {
                                    isImported -> "${formatOnlineAbsoluteTime(record.createdAt)}  已导入"
                                    record.sogDownloaded -> "${formatOnlineAbsoluteTime(record.createdAt)}  已下载，可导入"
                                    else -> "${formatOnlineAbsoluteTime(record.createdAt)}  已完成，待下载"
                                }
                                "downloading" -> "${formatOnlineAbsoluteTime(record.createdAt)}  下载中…"
                                "failed" -> "${formatOnlineAbsoluteTime(record.createdAt)}  失败: ${record.errorMessage?.take(30) ?: "未知"}"
                                else -> "${formatOnlineAbsoluteTime(record.createdAt)}  ${record.status}"
                            },
                            color = when (record.status) {
                                "pending", "generating", "downloading" -> accentColor
                                "failed" -> Color(0xFFFF4444)
                                "completed" -> if (isImported || record.sogDownloaded) Color(0xFF4CAF50) else accentColor
                                else -> contentColor.copy(alpha = 0.5f)
                            },
                            fontSize = 12.sp,
                            maxLines = 1,
                            modifier = Modifier.padding(top = 4.dp)
                        )
                    }
                    Spacer(Modifier.width(8.dp))
                    when {
                        isPolling -> CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 1.8.dp, color = accentColor)
                        isImported -> Text("已导入", color = Color(0xFF4CAF50), fontSize = 13.sp)
                        record.status == "completed" && record.sogDownloaded -> OnlineTextAction("导入", Color(0xFF4CAF50)) { importRecord(record) }
                        record.status == "completed" && !record.sogDownloaded -> OnlineTextAction("下载", accentColor) { downloadRecord(record) }
                        record.status == "downloading" -> Text("下载中", color = accentColor, fontSize = 13.sp)
                        record.status == "failed" -> OnlineTextAction("重试", Color(0xFFFF8800)) { retryRecord(record) }
                    }
                    Spacer(Modifier.width(6.dp))
                    Box(
                        modifier = Modifier
                            .size(32.dp)
                            .clip(CircleShape)
                            .background(contentColor.copy(alpha = 0.08f))
                            .clickable(
                                indication = null,
                                interactionSource = remember { MutableInteractionSource() }
                            ) { deleteRecord(record) },
                        contentAlignment = Alignment.Center
                    ) {
                        Text("×", color = contentColor.copy(alpha = 0.55f), fontSize = 18.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }
                }
            }
        }

        if (useLiquidGlass && topBackdrop != null) {
            LiquidButton(
                onClick = onBack,
                backdrop = topBackdrop,
                modifier = Modifier
                    .padding(top = statusBarTopPaddingDp + 12.dp, start = 12.dp)
                    .size(44.dp),
                surfaceColor = topSurfaceColor,
                buttonHeight = 44.dp,
                contentPadding = PaddingValues(0.dp),
                iconRes = R.drawable.back,
                iconContentDescription = "返回",
                iconSize = 20.dp,
                iconTint = contentColor
            )
        } else {
            PlainIconButton(
                onClick = onBack,
                iconRes = R.drawable.back,
                contentDescription = "返回",
                modifier = Modifier.padding(top = statusBarTopPaddingDp + 12.dp, start = 12.dp),
                size = 44.dp,
                iconSize = 20.dp,
                surfaceColor = PlainFallbackStyle.surface(isLightTheme = isLight),
                contentColor = contentColor,
                border = PlainFallbackStyle.border(isLightTheme = isLight)
            )
        }
    }
}

@Composable
private fun OnlineTextAction(text: String, color: Color, onClick: () -> Unit) {
    Text(
        text,
        color = color,
        fontSize = 13.sp,
        fontWeight = FontWeight.Medium,
        modifier = Modifier
            .clip(Capsule())
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 5.dp)
    )
}

@Composable
private fun OnlineDialogButton(
    text: String,
    bgColor: Color,
    textColor: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .clip(Capsule())
            .background(bgColor)
            .clickable(onClick = onClick)
            .height(48.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically
    ) {
        BasicText(text, style = TextStyle(textColor, 16.sp))
    }
}

@Composable
private fun OnlineRecordThumbnail(localUri: String?, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val bitmap by produceState<Bitmap?>(initialValue = null, localUri) {
        if (localUri.isNullOrBlank()) {
            value = null
            return@produceState
        }
        value = withContext(Dispatchers.IO) {
            val file = File(localUri)
            if (file.exists()) {
                runCatching {
                    val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    BitmapFactory.decodeFile(file.absolutePath, options)
                    val sampleSize = maxOf(1, minOf(options.outWidth, options.outHeight) / 100)
                    BitmapFactory.decodeFile(file.absolutePath, BitmapFactory.Options().apply { inSampleSize = sampleSize })
                }.getOrNull()
            } else {
                runCatching {
                    context.contentResolver.openInputStream(Uri.parse(localUri))?.use { BitmapFactory.decodeStream(it) }
                }.getOrNull()
            }
        }
    }

    if (bitmap != null) {
        Image(
            bitmap = bitmap!!.asImageBitmap(),
            contentDescription = null,
            modifier = modifier.background(Color.Black.copy(alpha = 0.18f)),
            contentScale = ContentScale.Crop
        )
    } else {
        Box(modifier.background(Color(0xFF333333)), contentAlignment = Alignment.Center) {
            Text("🖼", fontSize = 16.sp, color = Color.White.copy(alpha = 0.4f))
        }
    }
}

private val onlineRecordTimeFormat = SimpleDateFormat("MM/dd HH:mm", Locale.getDefault())

private fun formatOnlineAbsoluteTime(timestamp: Long): String {
    return onlineRecordTimeFormat.format(Date(timestamp))
}

private fun queryOnlineDisplayName(context: Context, uri: Uri): String? {
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
