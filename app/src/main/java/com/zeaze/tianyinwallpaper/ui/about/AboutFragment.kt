package com.zeaze.tianyinwallpaper.ui.about

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.View
import android.view.ViewGroup
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.alibaba.fastjson.JSON
import com.zeaze.tianyinwallpaper.model.TianYinWallpaperModel
import com.zeaze.tianyinwallpaper.ui.commom.SaveData
import com.zeaze.tianyinwallpaper.utils.FileUtil
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class AboutFragment : Fragment() {
    override fun onCreateView(
        inflater: android.view.LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        return ComposeView(requireContext()).apply {
            setContent {
                MaterialTheme {
                    AboutRouteScreen()
                }
            }
        }
    }
}

@Composable
fun AboutRouteScreen() {
    val context = LocalContext.current
    val saveDataList = remember { mutableStateListOf<SaveData>() }

    fun loadGroups() {
        Thread {
            val data = FileUtil.loadData(context, FileUtil.dataPath)
            val list = JSON.parseArray(data, SaveData::class.java) ?: emptyList()
            (context as? android.app.Activity)?.runOnUiThread {
                saveDataList.clear()
                saveDataList.addAll(list)
            }
        }.start()
    }

    LaunchedEffect(Unit) { loadGroups() }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) loadGroups()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxWidth()
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        items(saveDataList, key = { "${it.name ?: ""}\u0000${it.s ?: ""}" }) { data ->
            AboutGroupItem(context = context, data = data)
        }
    }
}

@Composable
private fun AboutGroupItem(context: Context, data: SaveData) {
    val wallpapers = remember(data.s) {
        JSON.parseArray(data.s, TianYinWallpaperModel::class.java) ?: emptyList()
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colors.surface)
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
}

@Composable
private fun PreviewImage(context: Context, model: TianYinWallpaperModel?, modifier: Modifier) {
    var bitmap by remember(model) { mutableStateOf<Bitmap?>(null) }
    LaunchedEffect(model) {
        bitmap = loadPreviewBitmap(context, model)
    }
    bitmap?.let {
        Image(bitmap = it.asImageBitmap(), contentDescription = "Wallpaper preview", modifier = modifier)
    } ?: Spacer(modifier = modifier.background(MaterialTheme.colors.background))
}

private suspend fun loadPreviewBitmap(context: Context, model: TianYinWallpaperModel?): Bitmap? {
    if (model == null) return null
    return withContext(Dispatchers.IO) {
        try {
            val path = when {
                model.type == 1 && !model.videoUri.isNullOrEmpty() -> model.videoUri
                !model.imgUri.isNullOrEmpty() -> model.imgUri
                else -> model.imgPath
            }
            if (path.isNullOrEmpty()) {
                null
            } else if (model.type == 1) {
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
            } else if (path.startsWith("content://")) {
                context.contentResolver.openInputStream(android.net.Uri.parse(path))?.use {
                    BitmapFactory.decodeStream(it)
                }
            } else {
                BitmapFactory.decodeFile(path)
            }
        } catch (e: Exception) {
            Log.w("AboutFragment", "Failed to load preview bitmap", e)
            null
        }
    }
}

private const val ITEM_PREVIEW_HEIGHT_DP = 68
private const val ITEM_PREVIEW_TOP_MARGIN_DP = 8
private const val ITEM_PREVIEW_SPACING_DP = 6
private const val PREVIEW_COUNT = 4
private const val VIDEO_PREVIEW_WIDTH = 240
private const val VIDEO_PREVIEW_HEIGHT = 160
