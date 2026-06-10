package com.zeaze.tianyinwallpaper.ui.main

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kyant.shapes.Capsule
import com.zeaze.tianyinwallpaper.model.DepthWallpaperModel
import com.zeaze.tianyinwallpaper.model.RasterGroupModel
import com.zeaze.tianyinwallpaper.model.TianYinWallpaperModel
import com.zeaze.tianyinwallpaper.utils.DepthPrefs
import com.zeaze.tianyinwallpaper.utils.ThumbnailUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
fun MainWallpaperFilterBar(
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
fun MainWallpaperGrid(
    items: List<MainUnifiedWallpaperItem>,
    state: LazyGridState,
    contentPadding: androidx.compose.foundation.layout.PaddingValues,
    onClickWallpaper: (TianYinWallpaperModel) -> Unit,
    onClickRaster: (RasterGroupModel) -> Unit,
    onClickDepth: (DepthWallpaperModel) -> Unit,
    modifier: Modifier = Modifier
) {
    LazyVerticalGrid(
        state = state,
        columns = GridCells.Fixed(3),
        modifier = modifier,
        contentPadding = contentPadding,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        items(
            items,
            key = { item ->
                when (item) {
                    is MainUnifiedWallpaperItem.Wallpaper -> "wallpaper_${item.model.uuid ?: item.index}"
                    is MainUnifiedWallpaperItem.Raster -> "raster_${item.group.id}"
                    is MainUnifiedWallpaperItem.Depth -> "depth_${item.model.id}"
                }
            }
        ) { item ->
            when (item) {
                is MainUnifiedWallpaperItem.Wallpaper -> MainUnifiedWallpaperCard(
                    modifier = Modifier.fillMaxWidth(),
                    model = item.model,
                    isSelected = false,
                    onClick = { onClickWallpaper(item.model) }
                )
                is MainUnifiedWallpaperItem.Raster -> MainUnifiedRasterCard(
                    modifier = Modifier.fillMaxWidth(),
                    group = item.group,
                    onClick = { onClickRaster(item.group) }
                )
                is MainUnifiedWallpaperItem.Depth -> MainUnifiedDepthCard(
                    modifier = Modifier.fillMaxWidth(),
                    model = item.model,
                    onClick = { onClickDepth(item.model) }
                )
            }
        }
    }
}

@Composable
fun MainUnifiedWallpaperCard(
    modifier: Modifier = Modifier,
    model: TianYinWallpaperModel,
    isSelected: Boolean,
    onClick: () -> Unit
) {
    val context = LocalContext.current
    Box(
        modifier = modifier
            .aspectRatio(context.resources.displayMetrics.let { it.widthPixels.toFloat() / it.heightPixels.toFloat() })
            .clip(RoundedCornerShape(16.dp))
            .background(Color.Black)
            .clickable(onClick = onClick)
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
                .background(Color(0x66000000), RoundedCornerShape(16.dp))
                .padding(horizontal = 6.dp, vertical = 2.dp)
        )
        if (isSelected) {
            Box(modifier = Modifier.fillMaxSize().background(Color(0x77000000)))
        }
    }
}

@Composable
fun MainUnifiedRasterCard(
    modifier: Modifier = Modifier,
    group: RasterGroupModel,
    onClick: () -> Unit
) {
    val context = LocalContext.current
    val request = remember(group.id, group.type, group.videoUri, group.imageUris.firstOrNull()) {
        if (group.type == RasterGroupModel.TYPE_STATIC) {
            val firstImageUri = group.imageUris.firstOrNull()
            firstImageUri?.let {
                ThumbnailUtils.Request(
                    uuid = group.id,
                    type = WALLPAPER_TYPE_STATIC,
                    imgUri = it,
                    videoUri = null,
                    imgPath = null
                )
            }
        } else {
            group.videoUri?.let {
                ThumbnailUtils.Request(
                    uuid = group.id,
                    type = WALLPAPER_TYPE_DYNAMIC,
                    imgUri = null,
                    videoUri = it,
                    imgPath = null
                )
            }
        }
    }
    val bitmap by produceState<Bitmap?>(initialValue = request?.let { ThumbnailUtils.getFromCache(it) }, request) {
        value = request?.let { ThumbnailUtils.loadThumbnail(context, it) }
    }
    Box(
        modifier = modifier
            .aspectRatio(context.resources.displayMetrics.let { it.widthPixels.toFloat() / it.heightPixels.toFloat() })
            .clip(RoundedCornerShape(16.dp))
            .background(Color(0xFF18181A))
            .clickable(onClick = onClick)
    ) {
        if (bitmap != null) {
            Image(
                bitmap = bitmap!!.asImageBitmap(),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
        }
        Text(
            text = if (group.type == RasterGroupModel.TYPE_STATIC) "图集光栅" else "视频光栅",
            color = Color.White,
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(3.dp)
                .background(Color(0x66000000), RoundedCornerShape(16.dp))
                .padding(horizontal = 6.dp, vertical = 2.dp)
        )
    }
}

@Composable
fun MainUnifiedDepthCard(
    modifier: Modifier = Modifier,
    model: DepthWallpaperModel,
    onClick: () -> Unit
) {
    val context = LocalContext.current
    val bitmap by produceState<Bitmap?>(initialValue = null, model.id, model.gaussianUri) {
        value = withContext(Dispatchers.IO) {
            val file = DepthPrefs.sogThumbnailFile(context, model.id)
            if (file.exists()) BitmapFactory.decodeFile(file.absolutePath) else null
        }
    }
    Box(
        modifier = modifier
            .aspectRatio(context.resources.displayMetrics.let { it.widthPixels.toFloat() / it.heightPixels.toFloat() })
            .clip(RoundedCornerShape(16.dp))
            .background(Color(0xFF18181A))
            .clickable(onClick = onClick)
    ) {
        if (bitmap != null) {
            Image(
                bitmap = bitmap!!.asImageBitmap(),
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
        Text(
            text = "景深",
            color = Color.White,
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(3.dp)
                .background(Color(0x66000000), RoundedCornerShape(16.dp))
                .padding(horizontal = 6.dp, vertical = 2.dp)
        )
    }
}

@Composable
fun WallpaperCardImage(modifier: Modifier = Modifier, model: TianYinWallpaperModel) {
    val context = LocalContext.current
    val request = ThumbnailUtils.Request(
        uuid = model.uuid.orEmpty(),
        type = model.type,
        imgUri = model.imgUri,
        videoUri = model.videoUri,
        imgPath = model.imgPath
    )
    val bitmapState = produceState<Bitmap?>(
        initialValue = ThumbnailUtils.getFromCache(request),
        request
    ) {
        val loaded = withContext(Dispatchers.IO) {
            ThumbnailUtils.loadThumbnail(context, request)
        }
        value = loaded
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
