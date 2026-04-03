package com.zeaze.tianyinwallpaper.ui.main

import android.app.WallpaperManager
import android.graphics.Bitmap
import android.graphics.Matrix
import android.graphics.SurfaceTexture
import android.media.MediaPlayer
import android.net.Uri
import android.view.Surface
import android.view.TextureView
import android.view.ViewGroup
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredHeight
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Surface
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
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
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Offset
import com.kyant.shapes.Capsule
import com.kyant.shapes.RoundedRectangle
import com.zeaze.tianyinwallpaper.backdrop.Backdrop
import com.zeaze.tianyinwallpaper.backdrop.backdrops.layerBackdrop
import com.zeaze.tianyinwallpaper.backdrop.backdrops.rememberCanvasBackdrop
import com.zeaze.tianyinwallpaper.backdrop.backdrops.rememberLayerBackdrop
import com.zeaze.tianyinwallpaper.backdrop.drawBackdrop
import com.zeaze.tianyinwallpaper.backdrop.effects.blur
import com.zeaze.tianyinwallpaper.backdrop.effects.colorControls
import com.zeaze.tianyinwallpaper.backdrop.effects.lens
import com.zeaze.tianyinwallpaper.backdrop.highlight.Highlight
import com.zeaze.tianyinwallpaper.catalog.components.LiquidButton
import com.zeaze.tianyinwallpaper.catalog.components.LiquidSlider
import com.zeaze.tianyinwallpaper.catalog.components.LiquidToggle
import com.zeaze.tianyinwallpaper.catalog.components.WheelPicker
import com.zeaze.tianyinwallpaper.catalog.utils.rememberMultiRegionLuminanceSampler
import com.zeaze.tianyinwallpaper.catalog.utils.rememberRegionLuminanceState
import com.zeaze.tianyinwallpaper.model.TianYinWallpaperModel
import com.zeaze.tianyinwallpaper.service.TianYinWallpaperService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.sqrt

@Composable
internal fun LiveSyncPreview(
    wallpaperList: List<TianYinWallpaperModel>,
    currentIndex: Int,
    statusBarTopPaddingDp: androidx.compose.ui.unit.Dp,
    onClose: () -> Unit,
    onPrev: () -> Unit,
    onNext: () -> Unit
) {
    val context = LocalContext.current
    val isWallpaperApplied = run {
        val info = WallpaperManager.getInstance(context).wallpaperInfo
        info?.packageName == context.packageName && info.serviceName == TianYinWallpaperService::class.java.name
    }
    val currentModel = if (isWallpaperApplied && currentIndex in wallpaperList.indices) wallpaperList[currentIndex] else null

    val isLightTheme = MaterialTheme.colors.isLight
    val enableLiquidGlass = android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU
    val previewBackdrop = if (enableLiquidGlass) rememberLayerBackdrop() else null
    val pillBackground = if (!isLightTheme) Color(0x22222222) else Color(0x22FFFFFF)
    val onPage = if (isLightTheme) Color.Black else Color.White

    val luminanceRegions = remember {
        mapOf(
            "close" to Rect(0f, 0f, 0.22f, 0.1f),
            "index" to Rect(0.78f, 0f, 1f, 0.1f),
            "prev" to Rect(0f, 0.9f, 0.5f, 1f),
            "next" to Rect(0.5f, 0.9f, 1f, 1f)
        )
    }
    val luminanceSampler = if (enableLiquidGlass && previewBackdrop != null) {
        rememberMultiRegionLuminanceSampler(
            enabled = true,
            sampleLayer = previewBackdrop.graphicsLayer,
            regions = luminanceRegions,
            sampleIntervalMs = 200L
        )
    } else null
    val closeLuminanceState = if (luminanceSampler != null) rememberRegionLuminanceState(luminanceSampler, "close") else null
    val indexLuminanceState = if (luminanceSampler != null) rememberRegionLuminanceState(luminanceSampler, "index") else null
    val prevLuminanceState = if (luminanceSampler != null) rememberRegionLuminanceState(luminanceSampler, "prev") else null
    val nextLuminanceState = if (luminanceSampler != null) rememberRegionLuminanceState(luminanceSampler, "next") else null

    BackHandler(enabled = true) { onClose() }

    Box(modifier = Modifier.fillMaxSize()) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .let { m ->
                    if (enableLiquidGlass && previewBackdrop != null) m.layerBackdrop(previewBackdrop) else m
                }
        ) {
            Box(Modifier.fillMaxSize().background(Color.Black))
            if (currentModel != null) {
                WallpaperThumbnail(
                    model = currentModel,
                    modifier = Modifier.fillMaxSize(),
                    useClip = false
                )
            } else {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("当前未播放", color = Color.White)
                }
            }
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, end = 16.dp, top = statusBarTopPaddingDp + 12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (enableLiquidGlass && previewBackdrop != null) {
                LiquidButton(
                    onClick = onClose,
                    backdrop = previewBackdrop,
                    surfaceColor = pillBackground,
                    luminanceState = closeLuminanceState,
                    modifier = Modifier.height(44.dp)
                ) {
                    androidx.compose.foundation.text.BasicText(
                        "关闭",
                        modifier = Modifier.padding(horizontal = 14.dp),
                        style = TextStyle(closeLuminanceState?.contentColor ?: onPage, 15.sp)
                    )
                }
            } else {
                Text(
                    text = "关闭",
                    color = onPage,
                    modifier = Modifier
                        .clip(androidx.compose.foundation.shape.RoundedCornerShape(18.dp))
                        .background(pillBackground)
                        .clickable { onClose() }
                        .padding(horizontal = 18.dp, vertical = 8.dp)
                )
            }

            val positionText = if (currentModel != null) "${currentIndex + 1}/${wallpaperList.size}" else "0/0"
            if (enableLiquidGlass && previewBackdrop != null) {
                Box(
                    modifier = Modifier
                        .drawBackdrop(
                            backdrop = previewBackdrop,
                            shape = { Capsule() },
                            effects = {
                                blur(if (isLightTheme) 16f.dp.toPx() else 8f.dp.toPx())
                                lens(12f.dp.toPx(), 20f.dp.toPx(), depthEffect = true)
                            },
                            highlight = { Highlight.Plain },
                            onDrawSurface = { drawRect(pillBackground) }
                        )
                        .padding(horizontal = 14.dp, vertical = 8.dp)
                ) {
                    Text(
                        text = positionText,
                        color = indexLuminanceState?.contentColor ?: onPage,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Medium
                    )
                }
            } else {
                Text(
                    text = positionText,
                    color = Color.White,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium
                )
            }
        }

        Row(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 24.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            val controlColor = pillBackground
            val textColor = onPage

            if (enableLiquidGlass && previewBackdrop != null) {
                LiquidButton(
                    onClick = onPrev,
                    backdrop = previewBackdrop,
                    luminanceState = prevLuminanceState,
                    modifier = Modifier
                        .height(44.dp),
                    surfaceColor = controlColor
                ) {
                    androidx.compose.foundation.text.BasicText(
                        "上一张",
                        modifier = Modifier.padding(horizontal = 14.dp),
                        style = TextStyle(
                            color = prevLuminanceState?.contentColor ?: textColor,
                            15.sp,
                            fontWeight = FontWeight.Medium
                        )
                    )
                }
            } else {
                Surface(
                    modifier = Modifier
                        .weight(1f)
                        .height(44.dp)
                        .clickable { onPrev() },
                    shape = Capsule(),
                    color = controlColor,
                    border = androidx.compose.foundation.BorderStroke(1.dp, Color.White.copy(alpha = 0.2f))
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Text(text = "上一张", color = textColor, fontSize = 15.sp, fontWeight = FontWeight.Medium)
                    }
                }
            }

            if (enableLiquidGlass && previewBackdrop != null) {
                LiquidButton(
                    onClick = onNext,
                    backdrop = previewBackdrop,
                    luminanceState = nextLuminanceState,
                    modifier = Modifier
                        .height(44.dp),
                    surfaceColor = controlColor
                ) {
                    androidx.compose.foundation.text.BasicText(
                        "下一张",
                        modifier = Modifier.padding(horizontal = 14.dp),
                        style = TextStyle(
                            color = prevLuminanceState?.contentColor ?: textColor,
                            15.sp,
                            fontWeight = FontWeight.Medium
                        )
                    )
                }
            } else {
                Surface(
                    modifier = Modifier
                        .weight(1f)
                        .height(44.dp)
                        .clickable { onNext() },
                    shape = Capsule(),
                    color = controlColor,
                    border = androidx.compose.foundation.BorderStroke(1.dp, Color.White.copy(alpha = 0.2f))
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Text(text = "下一张", color = textColor, fontSize = 15.sp, fontWeight = FontWeight.Medium)
                    }
                }
            }
        }
    }
}

@Composable
private fun WallpaperThumbnail(
    model: TianYinWallpaperModel,
    modifier: Modifier = Modifier,
    useClip: Boolean = true,
    transformScale: Float = 1f,
    transformOffsetX: Float = 0f,
    transformOffsetY: Float = 0f,
    onSourceSizeChanged: ((width: Float, height: Float) -> Unit)? = null
) {
    val context = LocalContext.current
    val dialogShape = RoundedRectangle(35f.dp)

    if (model.type == WALLPAPER_TYPE_DYNAMIC && !model.videoUri.isNullOrEmpty()) {
        AndroidView(
            factory = { ctx ->
                TextureView(ctx).apply {
                    layoutParams = ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT
                    )
                    val player = MediaPlayer().apply {
                        val volume = model.volume.coerceIn(0f, 1f)
                        setVolume(volume, volume)
                        isLooping = true
                    }
                    val holder = VideoPlayerHolder(player, model.videoUri)
                    tag = holder

                    surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                        override fun onSurfaceTextureAvailable(surface: SurfaceTexture, width: Int, height: Int) {
                            val videoHolder = tag as? VideoPlayerHolder ?: return
                            val uri = videoHolder.uri ?: return
                            try {
                                videoHolder.player.reset()
                                videoHolder.player.isLooping = true
                                val volume = model.volume.coerceIn(0f, 1f)
                                videoHolder.player.setVolume(volume, volume)
                                videoHolder.player.setSurface(Surface(surface))
                                videoHolder.player.setDataSource(ctx, Uri.parse(uri))
                                videoHolder.player.setOnPreparedListener { mp ->
                                    onSourceSizeChanged?.invoke(mp.videoWidth.toFloat(), mp.videoHeight.toFloat())
                                    updateMatrix(mp, this@apply, transformScale, transformOffsetX, transformOffsetY)
                                    mp.start()
                                }
                                videoHolder.player.prepareAsync()
                            } catch (e: Exception) {
                                e.printStackTrace()
                            }
                        }

                        override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, width: Int, height: Int) {
                            (tag as? VideoPlayerHolder)?.player?.let {
                                updateMatrix(it, this@apply, transformScale, transformOffsetX, transformOffsetY)
                            }
                        }

                        override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean {
                            val videoHolder = tag as? VideoPlayerHolder
                            if (videoHolder != null) {
                                try {
                                    videoHolder.player.stop()
                                } catch (_: Exception) {
                                }
                                try {
                                    videoHolder.player.setSurface(null)
                                } catch (_: Exception) {
                                }
                            }
                            return true
                        }

                        override fun onSurfaceTextureUpdated(surface: SurfaceTexture) {}
                    }
                }
            },
            update = { textureView ->
                val videoHolder = textureView.tag as? VideoPlayerHolder ?: return@AndroidView
                val newUri = model.videoUri ?: return@AndroidView

                val uriChanged = videoHolder.uri != newUri
                videoHolder.uri = newUri

                if (textureView.isAvailable) {
                    val volume = model.volume.coerceIn(0f, 1f)
                    if (uriChanged) {
                        try {
                            videoHolder.player.reset()
                            videoHolder.player.isLooping = true
                            videoHolder.player.setVolume(volume, volume)
                            videoHolder.player.setDataSource(context, Uri.parse(newUri))
                            videoHolder.player.setSurface(Surface(textureView.surfaceTexture))
                            videoHolder.player.setOnPreparedListener { mp ->
                                onSourceSizeChanged?.invoke(mp.videoWidth.toFloat(), mp.videoHeight.toFloat())
                                updateMatrix(mp, textureView, transformScale, transformOffsetX, transformOffsetY)
                                mp.start()
                            }
                            videoHolder.player.prepareAsync()
                        } catch (e: Exception) {
                            e.printStackTrace()
                        }
                    } else {
                        videoHolder.player.let { mp ->
                            mp.setVolume(volume, volume)
                            if (mp.isPlaying) {
                                updateMatrix(mp, textureView, transformScale, transformOffsetX, transformOffsetY)
                            }
                        }
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
        val request = com.zeaze.tianyinwallpaper.utils.ThumbnailUtils.Request(
            uuid = model.uuid.orEmpty(),
            type = model.type,
            imgUri = model.imgUri,
            videoUri = model.videoUri,
            imgPath = model.imgPath
        )
        val bitmapState = produceState<Bitmap?>(
            initialValue = com.zeaze.tianyinwallpaper.utils.ThumbnailUtils.getFromCache(request),
            request
        ) {
            val loaded = withContext(Dispatchers.IO) {
                com.zeaze.tianyinwallpaper.utils.ThumbnailUtils.loadThumbnail(context, request)
            }
            value = loaded
        }
        bitmapState.value?.let { bitmap ->
            onSourceSizeChanged?.invoke(bitmap.width.toFloat(), bitmap.height.toFloat())
            Image(
                bitmap = bitmap.asImageBitmap(),
                contentDescription = null,
                modifier = if (useClip) modifier.clip(dialogShape) else modifier,
                contentScale = ContentScale.Crop
            )
        }
    }
}

private fun updateMatrix(
    mp: MediaPlayer,
    view: TextureView,
    scale: Float = 1f,
    offsetX: Float = 0f,
    offsetY: Float = 0f
) {
    val vWidth = mp.videoWidth.toFloat()
    val vHeight = mp.videoHeight.toFloat()
    val viewWidth = view.width.toFloat()
    val viewHeight = view.height.toFloat()

    if (vWidth > 0 && vHeight > 0 && viewWidth > 0 && viewHeight > 0) {
        val matrix = Matrix()
        val videoRatio = vWidth / vHeight
        val viewRatio = viewWidth / viewHeight

        var baseScaleX = 1f
        var baseScaleY = 1f

        if (videoRatio > viewRatio) {
            baseScaleX = videoRatio / viewRatio
        } else {
            baseScaleY = viewRatio / videoRatio
        }

        // 补偿 1px，避免部分机型上刚好撑满时出现边缘细缝。
        val epsilonX = (viewWidth + 1f) / viewWidth
        val epsilonY = (viewHeight + 1f) / viewHeight
        val finalScaleX = baseScaleX * scale * epsilonX
        val finalScaleY = baseScaleY * scale * epsilonY

        matrix.postTranslate(-viewWidth / 2f, -viewHeight / 2f)
        matrix.postScale(finalScaleX, finalScaleY)
        matrix.postTranslate(viewWidth / 2f + offsetX, viewHeight / 2f + offsetY)

        view.setTransform(matrix)
    }
}

private data class VideoPlayerHolder(
    val player: MediaPlayer,
    var uri: String?
)

@Composable
internal fun WallpaperDetailScreen(
    model: TianYinWallpaperModel,
    statusBarTopPaddingDp: androidx.compose.ui.unit.Dp,
    enableLiquidGlass: Boolean,
    backdrop: Backdrop?,
    onDismiss: () -> Unit,
    onApply: () -> Unit,
    onReplaceAction: (isDynamic: Boolean) -> Unit,
    onTimeAction: (startTime: Int, endTime: Int, loop: Boolean, independentTime: Boolean) -> Unit,
    onTransformAction: (scale: Float, offsetX: Float, offsetY: Float) -> Unit,
    onBrightnessAction: (brightness: Float) -> Unit,
    onVolumeAction: (volume: Float) -> Unit
) {
    val isLightTheme = MaterialTheme.colors.isLight
    val pageBackground = MaterialTheme.colors.background
    val onPage = MaterialTheme.colors.onBackground
    val pillBackground = if (!isLightTheme) Color(0x22222222) else Color(0x22FFFFFF)
    val containerColor = if (isLightTheme) Color(0xFFFAFAFA).copy(0.6f) else Color(0xFF121212).copy(0.4f)
    val accentColor = if (isLightTheme) Color(0xFF0088FF) else Color(0xFF0091FF)
    val contentColor = if (isLightTheme) Color.Black else Color.White

    val detailBackdrop = if (enableLiquidGlass) rememberLayerBackdrop() else null

    var showVideoDialog by remember { mutableStateOf(false) }
    var showImageDialog by remember { mutableStateOf(false) }
    var showTimePicker by remember { mutableStateOf<String?>(null) }
    var startTime by remember { mutableStateOf(model.startTime) }
    var endTime by remember { mutableStateOf(model.endTime) }
    var loopEnabled by remember { mutableStateOf(model.loop) }
    var independentTimeEnabled by remember { mutableStateOf(model.independentTime) }

    var scale by remember { mutableStateOf(model.scale) }
    var offsetX by remember { mutableStateOf(model.offsetX) }
    var offsetY by remember { mutableStateOf(model.offsetY) }
    val brightnessMin = -0.5f
    val brightnessMax = 0f
    var brightness by remember { mutableStateOf(model.brightness.coerceIn(brightnessMin, brightnessMax)) }
    var volume by remember { mutableStateOf(model.volume.coerceIn(0f, 1f)) }
    var sourceWidth by remember { mutableStateOf(0f) }
    var sourceHeight by remember { mutableStateOf(0f) }
    val transformScope = rememberCoroutineScope()

    var containerWidth by remember { mutableStateOf(0f) }
    var containerHeight by remember { mutableStateOf(0f) }

    var velocityX by remember { mutableStateOf(0f) }
    var velocityY by remember { mutableStateOf(0f) }
    var lastGestureTimeMs by remember { mutableStateOf(0L) }
    var isDragging by remember { mutableStateOf(false) }

    val isDynamicWallpaper = model.type == WALLPAPER_TYPE_DYNAMIC

    val oOo0 = 0.04f
    val oOOo = 4.0E-4f
    val o0oO = 0.015f
    val o0o0 = 2.0E-4f
    var magneticAssistEnabled by remember { mutableStateOf(true) }

    fun getMaxOffsets(currentScale: Float): Pair<Float, Float> {
        if (containerWidth <= 0f || containerHeight <= 0f || sourceWidth <= 0f || sourceHeight <= 0f) {
            return Pair(0f, 0f)
        }
        val axisCompensateX = (containerWidth + 1f) / containerWidth
        val axisCompensateY = (containerHeight + 1f) / containerHeight
        // 统一规则：宽高比小于屏幕时宽度撑满，大于屏幕时高度撑满。
        val baseScale = kotlin.math.max(containerWidth / sourceWidth, containerHeight / sourceHeight)
        val displayWidth = sourceWidth * baseScale * currentScale * axisCompensateX
        val displayHeight = sourceHeight * baseScale * currentScale * axisCompensateY
        val maxX = ((displayWidth - containerWidth) / 2f).coerceAtLeast(0f)
        val maxY = ((displayHeight - containerHeight) / 2f).coerceAtLeast(0f)
        return Pair(maxX, maxY)
    }

    // 吸附范围与边界范围不同：缩小到小于屏幕时也应有可吸附目标。
    fun getSnapRanges(currentScale: Float): Pair<Float, Float> {
        if (containerWidth <= 0f || containerHeight <= 0f || sourceWidth <= 0f || sourceHeight <= 0f) {
            return Pair(0f, 0f)
        }
        val axisCompensateX = (containerWidth + 1f) / containerWidth
        val axisCompensateY = (containerHeight + 1f) / containerHeight
        // 与渲染一致，吸附范围也使用同一套铺满轴规则。
        val baseScale = kotlin.math.max(containerWidth / sourceWidth, containerHeight / sourceHeight)
        val displayWidth = sourceWidth * baseScale * currentScale * axisCompensateX
        val displayHeight = sourceHeight * baseScale * currentScale * axisCompensateY
        val snapX = kotlin.math.abs(displayWidth - containerWidth) / 2f
        val snapY = kotlin.math.abs(displayHeight - containerHeight) / 2f
        return Pair(snapX, snapY)
    }

    fun stepPhysics(maxX: Float, maxY: Float, frameScale: Float, dragging: Boolean) {
        var currentX = offsetX
        var currentY = offsetY

        val (snapRangeX, snapRangeY) = getSnapRanges(scale)
        val effectiveMaxX = snapRangeX.coerceAtLeast(1f)
        val effectiveMaxY = snapRangeY.coerceAtLeast(1f)

        // 阈值跟随吸附范围并保留最小值，避免小尺寸时难触发。
        val snapThreshold = (0.12f * kotlin.math.max(effectiveMaxX, effectiveMaxY)).coerceAtLeast(24f)

        if (magneticAssistEnabled && dragging) {
            if (effectiveMaxX > 0f) {
                val leftError = currentX + effectiveMaxX      // target: -effectiveMaxX
                val rightError = currentX - effectiveMaxX     // target: +effectiveMaxX
                val useLeft = kotlin.math.abs(leftError) <= kotlin.math.abs(rightError)
                val edgeErrorX = if (useLeft) leftError else rightError
                val absEdgeErrorX = kotlin.math.abs(edgeErrorX)
                if (absEdgeErrorX < snapThreshold) {
                    val influence = (snapThreshold - absEdgeErrorX) / snapThreshold
                    velocityX += (-edgeErrorX) * oOOo * 10f * influence * frameScale
                    if (absEdgeErrorX < snapThreshold * 0.3f) {
                        currentX = if (useLeft) -effectiveMaxX else effectiveMaxX
                        velocityX = 0f
                    }
                }
            }

            if (effectiveMaxY > 0f) {
                val topError = currentY + effectiveMaxY       // target: -effectiveMaxY
                val bottomError = currentY - effectiveMaxY    // target: +effectiveMaxY
                val useTop = kotlin.math.abs(topError) <= kotlin.math.abs(bottomError)
                val edgeErrorY = if (useTop) topError else bottomError
                val absEdgeErrorY = kotlin.math.abs(edgeErrorY)
                if (absEdgeErrorY < snapThreshold) {
                    val influence = (snapThreshold - absEdgeErrorY) / snapThreshold
                    velocityY += (-edgeErrorY) * oOOo * 10f * influence * frameScale
                    if (absEdgeErrorY < snapThreshold * 0.3f) {
                        currentY = if (useTop) -effectiveMaxY else effectiveMaxY
                        velocityY = 0f
                    }
                }
            }
        }

        // 移除硬边界回弹，允许超出边界自由拖动。

        if (!dragging) {
            velocityX = 0f
            velocityY = 0f
        } else {
            velocityX *= (1f - oOo0)
            velocityY *= (1f - oOo0)
            velocityX *= (1f - o0oO)
            velocityY *= (1f - o0oO)
        }

        if (kotlin.math.abs(velocityX) < o0o0) velocityX = 0f
        if (kotlin.math.abs(velocityY) < o0o0) velocityY = 0f

        if (dragging) {
            currentX += velocityX * frameScale
            currentY += velocityY * frameScale
        }

        offsetX = currentX
        offsetY = currentY
    }

    var startHour by remember { mutableStateOf(if (startTime == -1) 0 else startTime / 60) }
    var startMinute by remember { mutableStateOf(if (startTime == -1) 0 else startTime % 60) }
    var endHour by remember { mutableStateOf(if (endTime == -1) 23 else endTime / 60) }
    var endMinute by remember { mutableStateOf(if (endTime == -1) 59 else endTime % 60) }

    val luminanceRegions = remember {
        mapOf(
            "cancel" to Rect(0f, 0f, 0.15f, 0.08f),
            "apply" to Rect(0.85f, 0f, 1f, 0.08f),
            "replace" to Rect(0.2f, 0.92f, 0.4f, 1f),
            "time" to Rect(0.6f, 0.92f, 1f, 1f)
        )
    }

    val luminanceSampler = if (enableLiquidGlass && detailBackdrop != null) {
        rememberMultiRegionLuminanceSampler(
            enabled = true,
            sampleLayer = detailBackdrop.graphicsLayer,
            regions = luminanceRegions,
            sampleIntervalMs = 200L
        )
    } else null

    val cancelLuminanceState = if (luminanceSampler != null) {
        rememberRegionLuminanceState(luminanceSampler, "cancel")
    } else null

    val applyLuminanceState = if (luminanceSampler != null) {
        rememberRegionLuminanceState(luminanceSampler, "apply")
    } else null

    val replaceLuminanceState = if (luminanceSampler != null) {
        rememberRegionLuminanceState(luminanceSampler, "replace")
    } else null

    val timeLuminanceState = if (luminanceSampler != null) {
        rememberRegionLuminanceState(luminanceSampler, "time")
    } else null

    fun persistPreviewState() {
        onTransformAction(scale, offsetX, offsetY)
        onBrightnessAction(brightness)
        onVolumeAction(volume)
    }

    // 预览页返回时先保存当前缩放/位置/亮度。
    BackHandler(enabled = !showVideoDialog && !showImageDialog) {
        persistPreviewState()
        onDismiss()
    }

    Box(modifier = Modifier.fillMaxSize()) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .let { m ->
                    if (enableLiquidGlass && detailBackdrop != null) {
                        m.layerBackdrop(detailBackdrop)
                    } else m
                }
        ) {
            Box(Modifier.fillMaxSize().background(pageBackground))

            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .onSizeChanged { size ->
                        containerWidth = size.width.toFloat()
                        containerHeight = size.height.toFloat()
                    }
                    .pointerInput(containerWidth, containerHeight, sourceWidth, sourceHeight) {
                        var snapJob: kotlinx.coroutines.Job? = null

                        fun schedulePersist() {
                            snapJob?.cancel()
                            snapJob = transformScope.launch {
                                delay(120)
                                onTransformAction(scale, offsetX, offsetY)
                            }
                        }

                        val physicsJob = transformScope.launch {
                            var lastFrameNs = System.nanoTime()
                            while (true) {
                                delay(16)
                                val nowNs = System.nanoTime()
                                val dt = ((nowNs - lastFrameNs) / 1_000_000_000f).coerceIn(0.008f, 0.05f)
                                lastFrameNs = nowNs

                                val (maxX, maxY) = getMaxOffsets(scale)
                                val frameScale = dt / (1f / 60f)
                                stepPhysics(maxX, maxY, frameScale, isDragging)

                                val idle = System.currentTimeMillis() - lastGestureTimeMs > 100
                                if (idle && kotlin.math.abs(velocityX) < 0.02f && kotlin.math.abs(velocityY) < 0.02f) {
                                    schedulePersist()
                                }
                            }
                        }

                        try {
                            awaitEachGesture {
                                isDragging = true
                                var lastCentroid: Offset? = null
                                var lastDistance = 0f

                                while (true) {
                                    val event = awaitPointerEvent()
                                    val pressedChanges = event.changes.filter { it.pressed }
                                    if (pressedChanges.isEmpty()) break

                                    if (pressedChanges.size == 1) {
                                        val change = pressedChanges[0]
                                        val delta = change.position - change.previousPosition
                                        offsetX += delta.x
                                        offsetY += delta.y
                                        velocityX = delta.x
                                        velocityY = delta.y
                                        lastGestureTimeMs = System.currentTimeMillis()
                                        lastCentroid = null
                                        lastDistance = 0f
                                        change.consume()
                                    } else {
                                        val p0 = pressedChanges[0].position
                                        val p1 = pressedChanges[1].position
                                        val centroid = Offset((p0.x + p1.x) / 2f, (p0.y + p1.y) / 2f)
                                        val dx = p0.x - p1.x
                                        val dy = p0.y - p1.y
                                        val distance = sqrt(dx * dx + dy * dy).coerceAtLeast(1f)

                                        if (lastDistance > 0f) {
                                            val oldScale = scale
                                            val newScale = (oldScale * (distance / lastDistance)).coerceAtLeast(0.1f)
                                            val ratio = if (oldScale == 0f) 1f else newScale / oldScale
                                            val centerX = containerWidth / 2f
                                            val centerY = containerHeight / 2f
                                            val cx = centroid.x - centerX
                                            val cy = centroid.y - centerY
                                            offsetX = (offsetX - cx) * ratio + cx
                                            offsetY = (offsetY - cy) * ratio + cy
                                            scale = newScale
                                        }

                                        if (lastCentroid != null) {
                                            val pan = centroid - lastCentroid
                                            offsetX += pan.x
                                            offsetY += pan.y
                                            velocityX = pan.x
                                            velocityY = pan.y
                                            lastGestureTimeMs = System.currentTimeMillis()
                                        }

                                        lastCentroid = centroid
                                        lastDistance = distance
                                        pressedChanges.forEach { it.consume() }
                                    }
                                }
                                isDragging = false
                                velocityX = 0f
                                velocityY = 0f
                                // 每次手势结束都立即保存一次，避免快速退出丢失。
                                persistPreviewState()
                            }
                        } finally {
                            isDragging = false
                            velocityX = 0f
                            velocityY = 0f
                            physicsJob.cancel()
                        }
                    }
            ) {
                if (isDynamicWallpaper) {
                    WallpaperThumbnail(
                        model = model,
                        modifier = Modifier.fillMaxSize(),
                        useClip = false,
                        transformScale = scale,
                        transformOffsetX = offsetX,
                        transformOffsetY = offsetY,
                        onSourceSizeChanged = { w, h ->
                            sourceWidth = w
                            sourceHeight = h
                        }
                    )
                } else {
                    val density = LocalDensity.current
                    val baseScale = if (containerWidth > 0f && containerHeight > 0f && sourceWidth > 0f && sourceHeight > 0f) {
                        kotlin.math.max(containerWidth / sourceWidth, containerHeight / sourceHeight)
                    } else 1f
                    val baseDisplayWidth = if (sourceWidth > 0f) sourceWidth * baseScale else containerWidth
                    val baseDisplayHeight = if (sourceHeight > 0f) sourceHeight * baseScale else containerHeight

                    WallpaperThumbnail(
                        model = model,
                        modifier = Modifier
                            .align(Alignment.Center)
                            .requiredWidth(with(density) { baseDisplayWidth.toDp() })
                            .requiredHeight(with(density) { baseDisplayHeight.toDp() })
                            .graphicsLayer {
                                val epsilonX = if (containerWidth > 0f) (containerWidth + 1f) / containerWidth else 1f
                                val epsilonY = if (containerHeight > 0f) (containerHeight + 1f) / containerHeight else 1f
                                scaleX = scale * epsilonX
                                scaleY = scale * epsilonY
                                translationX = offsetX
                                translationY = offsetY
                            },
                        useClip = false,
                        onSourceSizeChanged = { w, h ->
                            sourceWidth = w
                            sourceHeight = h
                        }
                    )
                }

                // 统一亮度层，作用于图片和视频预览。
                if (kotlin.math.abs(brightness) > 0.001f) {
                    val overlayColor = if (brightness > 0f) Color.White else Color.Black
                    val overlayAlpha = kotlin.math.abs(brightness).coerceIn(0f, 0.6f)
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(overlayColor.copy(alpha = overlayAlpha))
                    )
                }
            }
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, end = 16.dp, top = statusBarTopPaddingDp + 12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (enableLiquidGlass && detailBackdrop != null) {
                LiquidButton(
                    onClick = {
                        persistPreviewState()
                        onDismiss()
                    },
                    backdrop = detailBackdrop,
                    surfaceColor = pillBackground,
                    luminanceState = cancelLuminanceState,
                    modifier = Modifier.height(44.dp)
                ) {
                    androidx.compose.foundation.text.BasicText(
                        "取消",
                        modifier = Modifier.padding(horizontal = 14.dp),
                        style = TextStyle(
                            color = cancelLuminanceState?.contentColor ?: onPage,
                            fontSize = 15.sp
                        )
                    )
                }
                LiquidButton(
                    onClick = {
                        persistPreviewState()
                        onApply()
                    },
                    backdrop = detailBackdrop,
                    surfaceColor = Color(0xFF2A83FF).copy(alpha = 0.75f),
                    tint = Color(0xFF2A83FF),
                    luminanceState = applyLuminanceState,
                    modifier = Modifier.height(44.dp)
                ) {
                    androidx.compose.foundation.text.BasicText(
                        "应用",
                        modifier = Modifier.padding(horizontal = 14.dp),
                        style = TextStyle(Color.White, 15.sp)
                    )
                }
            } else {
                Text(
                    text = "取消", color = onPage,
                    modifier = Modifier
                        .clip(androidx.compose.foundation.shape.RoundedCornerShape(18.dp))
                        .background(pillBackground)
                        .clickable {
                            persistPreviewState()
                            onDismiss()
                        }
                        .padding(horizontal = 18.dp, vertical = 8.dp)
                )
                Text(
                    text = "应用", color = Color.White,
                    modifier = Modifier
                        .clip(androidx.compose.foundation.shape.RoundedCornerShape(18.dp))
                        .background(Color(0x662A83FF))
                        .clickable {
                            persistPreviewState()
                            onApply()
                        }
                        .padding(horizontal = 18.dp, vertical = 8.dp)
                )
            }
        }

        Row(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 24.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            val onVideoButtonClick = {
                if (isDynamicWallpaper) showVideoDialog = true else onReplaceAction(true)
            }
            val onImageButtonClick = {
                if (!isDynamicWallpaper) showImageDialog = true else onReplaceAction(false)
            }

            val videoButtonColor = if (isDynamicWallpaper) Color(0xFF2A83FF).copy(alpha = 0.75f) else pillBackground
            val videoTextColor = if (isDynamicWallpaper) Color.White else (replaceLuminanceState?.contentColor ?: onPage)

            if (enableLiquidGlass && detailBackdrop != null) {
                if (isDynamicWallpaper) {
                    LiquidButton(
                        onClick = onVideoButtonClick,
                        backdrop = detailBackdrop,
                        surfaceColor = videoButtonColor,
                        tint = Color(0xFF2A83FF),
                        luminanceState = replaceLuminanceState,
                        modifier = Modifier.height(44.dp)
                    ) {
                        androidx.compose.foundation.text.BasicText(
                            "视频",
                            modifier = Modifier.padding(horizontal = 14.dp),
                            style = TextStyle(
                                color = videoTextColor,
                                15.sp,
                                fontWeight = FontWeight.Medium
                            )
                        )
                    }
                } else {
                    LiquidButton(
                        onClick = onVideoButtonClick,
                        backdrop = detailBackdrop,
                        surfaceColor = videoButtonColor,
                        luminanceState = replaceLuminanceState,
                        modifier = Modifier.height(44.dp)
                    ) {
                        androidx.compose.foundation.text.BasicText(
                            "视频",
                            modifier = Modifier.padding(horizontal = 14.dp),
                            style = TextStyle(
                                color = videoTextColor,
                                15.sp,
                                fontWeight = FontWeight.Medium
                            )
                        )
                    }
                }
            } else {
                Surface(
                    modifier = Modifier
                        .height(44.dp)
                        .clickable(onClick = onVideoButtonClick),
                    shape = Capsule(),
                    color = videoButtonColor
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Text(text = "视频", color = videoTextColor, fontSize = 15.sp, fontWeight = FontWeight.Medium)
                    }
                }
            }

            val imageButtonColor = if (!isDynamicWallpaper) Color(0xFF2A83FF).copy(alpha = 0.75f) else pillBackground
            val imageTextColor = if (!isDynamicWallpaper) Color.White else (timeLuminanceState?.contentColor ?: onPage)

            if (enableLiquidGlass && detailBackdrop != null) {
                if (!isDynamicWallpaper) {
                    LiquidButton(
                        onClick = onImageButtonClick,
                        backdrop = detailBackdrop,
                        surfaceColor = imageButtonColor,
                        tint = Color(0xFF2A83FF),
                        luminanceState = timeLuminanceState,
                        modifier = Modifier.height(44.dp)
                    ) {
                        androidx.compose.foundation.text.BasicText(
                            "图片",
                            modifier = Modifier.padding(horizontal = 14.dp),
                            style = TextStyle(
                                color = imageTextColor,
                                15.sp,
                                fontWeight = FontWeight.Medium
                            )
                        )
                    }
                } else {
                    LiquidButton(
                        onClick = onImageButtonClick,
                        backdrop = detailBackdrop,
                        surfaceColor = imageButtonColor,
                        luminanceState = timeLuminanceState,
                        modifier = Modifier.height(44.dp)
                    ) {
                        androidx.compose.foundation.text.BasicText(
                            "图片",
                            modifier = Modifier.padding(horizontal = 14.dp),
                            style = TextStyle(
                                color = imageTextColor,
                                15.sp,
                                fontWeight = FontWeight.Medium
                            )
                        )
                    }
                }
            } else {
                Surface(
                    modifier = Modifier
                        .height(44.dp)
                        .clickable(onClick = onImageButtonClick),
                    shape = Capsule(),
                    color = imageButtonColor
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Text(text = "图片", color = imageTextColor, fontSize = 15.sp, fontWeight = FontWeight.Medium)
                    }
                }
            }
        }

        @Composable
        fun MediaSettingDialog(
            visible: Boolean,
            title: String,
            replaceText: String,
            showLoopToggle: Boolean,
            showVolumeSlider: Boolean,
            onReplace: () -> Unit,
            onDismiss: () -> Unit
        ) {
            AnimatedVisibility(
                visible = visible,
                enter = fadeIn(),
                exit = fadeOut(),
                modifier = Modifier.fillMaxSize()
            ) {
                BackHandler(enabled = true) {
                    if (independentTimeEnabled) {
                        startTime = startHour * 60 + startMinute
                        endTime = endHour * 60 + endMinute
                    } else {
                        startTime = -1
                        endTime = -1
                    }
                    onTimeAction(startTime, endTime, loopEnabled, independentTimeEnabled)
                    onDismiss()
                }

                fun saveAndClose() {
                    if (independentTimeEnabled) {
                        startTime = startHour * 60 + startMinute
                        endTime = endHour * 60 + endMinute
                    } else {
                        startTime = -1
                        endTime = -1
                    }
                    onTimeAction(startTime, endTime, loopEnabled, independentTimeEnabled)
                    onBrightnessAction(brightness)
                    onVolumeAction(volume)
                    onDismiss()
                }

                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null
                        ) { saveAndClose() }
                ) {
                    val sheetBackdrop = rememberLayerBackdrop()
                    Column(
                        Modifier
                            .align(Alignment.Center)
                            .padding(40.dp)
                            .wrapContentHeight()
                            .drawBackdrop(
                                backdrop = detailBackdrop ?: rememberCanvasBackdrop { drawRect(containerColor) },
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
                            .pointerInput(Unit) { detectTapGestures { } }
                    ) {
                        Column(
                            Modifier.padding(16.dp, 20.dp, 16.dp, 20.dp).fillMaxWidth(),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            androidx.compose.foundation.text.BasicText(title, style = TextStyle(contentColor, 18.sp, fontWeight = FontWeight.Bold))

                            Spacer(Modifier.height(12.dp))

                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 8.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                androidx.compose.foundation.text.BasicText("边缘吸附", style = TextStyle(contentColor, 16.sp))
                                if (enableLiquidGlass) {
                                    LiquidToggle(
                                        selected = { magneticAssistEnabled },
                                        onSelect = { magneticAssistEnabled = it },
                                        backdrop = sheetBackdrop,
                                        isLightTheme = isLightTheme
                                    )
                                } else {
                                    androidx.compose.material.Switch(
                                        checked = magneticAssistEnabled,
                                        onCheckedChange = { magneticAssistEnabled = it }
                                    )
                                }
                            }

                            if (showLoopToggle) {
                                Spacer(Modifier.height(12.dp))
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 8.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    androidx.compose.foundation.text.BasicText("循环播放", style = TextStyle(contentColor, 16.sp))
                                    if (enableLiquidGlass) {
                                        LiquidToggle(
                                            selected = { loopEnabled },
                                            onSelect = { loopEnabled = it },
                                            backdrop = sheetBackdrop,
                                            isLightTheme = isLightTheme
                                        )
                                    } else {
                                        androidx.compose.material.Switch(
                                            checked = loopEnabled,
                                            onCheckedChange = { loopEnabled = it }
                                        )
                                    }
                                }
                            }
                            Spacer(Modifier.height(12.dp))

                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 8.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                androidx.compose.foundation.text.BasicText("独立时间", style = TextStyle(contentColor, 16.sp))
                                if (enableLiquidGlass) {
                                    LiquidToggle(
                                        selected = { independentTimeEnabled },
                                        onSelect = { independentTimeEnabled = it },
                                        backdrop = sheetBackdrop,
                                        isLightTheme = isLightTheme
                                    )
                                } else {
                                    androidx.compose.material.Switch(
                                        checked = independentTimeEnabled,
                                        onCheckedChange = { independentTimeEnabled = it }
                                    )
                                }
                            }
                            Spacer(Modifier.height(12.dp))

                            AnimatedVisibility(
                                visible = independentTimeEnabled,
                                enter = fadeIn(animationSpec = spring(stiffness = Spring.StiffnessMediumLow)) +
                                        expandVertically(animationSpec = spring(stiffness = Spring.StiffnessMediumLow)),
                                exit = shrinkVertically(animationSpec = spring(stiffness = Spring.StiffnessMediumLow)) +
                                        fadeOut(animationSpec = spring(stiffness = Spring.StiffnessMediumLow))
                            ) {
                                Column(modifier = Modifier.fillMaxWidth()) {
                                    Column(modifier = Modifier.fillMaxWidth()) {
                                        Row(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .clip(Capsule())
                                                .background(containerColor.copy(0.2f))
                                                .clickable {
                                                    showTimePicker = if (showTimePicker == "start") null else "start"
                                                }
                                                .padding(horizontal = 20.dp, vertical = 12.dp),
                                            horizontalArrangement = Arrangement.SpaceBetween,
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            androidx.compose.foundation.text.BasicText("开始时间", style = TextStyle(contentColor, 16.sp))
                                            androidx.compose.foundation.text.BasicText(
                                                "${if (startHour < 10) "0$startHour" else "$startHour"}:${if (startMinute < 10) "0$startMinute" else "$startMinute"}",
                                                style = TextStyle(contentColor.copy(alpha = 0.8f), 16.sp)
                                            )
                                        }

                                        AnimatedVisibility(
                                            visible = showTimePicker == "start",
                                            enter = fadeIn(animationSpec = spring(stiffness = Spring.StiffnessMediumLow)) +
                                                    expandVertically(animationSpec = spring(stiffness = Spring.StiffnessMediumLow)),
                                            exit = shrinkVertically(animationSpec = spring(stiffness = Spring.StiffnessMediumLow)) +
                                                    fadeOut(animationSpec = spring(stiffness = Spring.StiffnessMediumLow))
                                        ) {
                                            Column(modifier = Modifier.fillMaxWidth()) {
                                                Spacer(Modifier.height(8.dp))
                                                Row(
                                                    modifier = Modifier
                                                        .fillMaxWidth()
                                                        .height(180.dp),
                                                    horizontalArrangement = Arrangement.Center
                                                ) {
                                                    WheelPicker(
                                                        count = 24,
                                                        initialIndex = startHour,
                                                        onItemSelected = { startHour = it },
                                                        contentColor = contentColor,
                                                        label = "时",
                                                        modifier = Modifier.weight(1f)
                                                    )
                                                    WheelPicker(
                                                        count = 60,
                                                        initialIndex = startMinute,
                                                        onItemSelected = { startMinute = it },
                                                        contentColor = contentColor,
                                                        label = "分",
                                                        modifier = Modifier.weight(1f)
                                                    )
                                                }
                                            }
                                        }
                                    }

                                    Spacer(Modifier.height(12.dp))

                                    Column(modifier = Modifier.fillMaxWidth()) {
                                        Row(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .clip(Capsule())
                                                .background(containerColor.copy(0.2f))
                                                .clickable {
                                                    showTimePicker = if (showTimePicker == "end") null else "end"
                                                }
                                                .padding(horizontal = 20.dp, vertical = 12.dp),
                                            horizontalArrangement = Arrangement.SpaceBetween,
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            androidx.compose.foundation.text.BasicText("结束时间", style = TextStyle(contentColor, 16.sp))
                                            androidx.compose.foundation.text.BasicText(
                                                "${if (endHour < 10) "0$endHour" else "$endHour"}:${if (endMinute < 10) "0$endMinute" else "$endMinute"}",
                                                style = TextStyle(contentColor.copy(alpha = 0.8f), 16.sp)
                                            )
                                        }

                                        AnimatedVisibility(
                                            visible = showTimePicker == "end",
                                            enter = fadeIn(animationSpec = spring(stiffness = Spring.StiffnessMediumLow)) +
                                                    expandVertically(animationSpec = spring(stiffness = Spring.StiffnessMediumLow)),
                                            exit = shrinkVertically(animationSpec = spring(stiffness = Spring.StiffnessMediumLow)) +
                                                    fadeOut(animationSpec = spring(stiffness = Spring.StiffnessMediumLow))
                                        ) {
                                            Column(modifier = Modifier.fillMaxWidth()) {
                                                Spacer(Modifier.height(8.dp))
                                                Row(
                                                    modifier = Modifier
                                                        .fillMaxWidth()
                                                        .height(180.dp),
                                                    horizontalArrangement = Arrangement.Center
                                                ) {
                                                    WheelPicker(
                                                        count = 24,
                                                        initialIndex = endHour,
                                                        onItemSelected = { endHour = it },
                                                        contentColor = contentColor,
                                                        label = "时",
                                                        modifier = Modifier.weight(1f)
                                                    )
                                                    WheelPicker(
                                                        count = 60,
                                                        initialIndex = endMinute,
                                                        onItemSelected = { endMinute = it },
                                                        contentColor = contentColor,
                                                        label = "分",
                                                        modifier = Modifier.weight(1f)
                                                    )
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                            Spacer(Modifier.height(12.dp))

                            if (showVolumeSlider) {
                                androidx.compose.foundation.text.BasicText(
                                    text = "音量 ${(volume * 100f).toInt()}%",
                                    style = TextStyle(contentColor, 14.sp)
                                )

                                Spacer(Modifier.height(8.dp))

                                if (enableLiquidGlass) {
                                    LiquidSlider(
                                        value = { volume },
                                        onValueChange = { volume = it.coerceIn(0f, 1f) },
                                        valueRange = 0f..1f,
                                        visibilityThreshold = 0.001f,
                                        backdrop = sheetBackdrop,
                                        isLightTheme = isLightTheme,
                                        onValueChangeFinished = { onVolumeAction(volume) },
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(horizontal = 8.dp)
                                    )
                                } else {
                                    androidx.compose.material.Slider(
                                        value = volume,
                                        onValueChange = { volume = it.coerceIn(0f, 1f) },
                                        valueRange = 0f..1f,
                                        onValueChangeFinished = { onVolumeAction(volume) },
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(horizontal = 8.dp)
                                    )
                                }

                                Spacer(Modifier.height(12.dp))
                            }

                            androidx.compose.foundation.text.BasicText(
                                text = "亮度 ${(((brightness - brightnessMin) / (brightnessMax - brightnessMin)) * 100f).toInt()}%",
                                style = TextStyle(contentColor, 14.sp)
                            )

                            Spacer(Modifier.height(8.dp))

                            if (enableLiquidGlass) {
                                LiquidSlider(
                                    value = { brightness },
                                    onValueChange = { brightness = it.coerceIn(brightnessMin, brightnessMax) },
                                    valueRange = brightnessMin..brightnessMax,
                                    visibilityThreshold = 0.001f,
                                    backdrop = sheetBackdrop,
                                    isLightTheme = isLightTheme,
                                    onValueChangeFinished = { onBrightnessAction(brightness) },
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 8.dp)
                                )
                            } else {
                                androidx.compose.material.Slider(
                                    value = brightness,
                                    onValueChange = { brightness = it.coerceIn(brightnessMin, brightnessMax) },
                                    valueRange = brightnessMin..brightnessMax,
                                    onValueChangeFinished = { onBrightnessAction(brightness) },
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 8.dp)
                                )
                            }

                            Spacer(Modifier.height(12.dp))

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                Row(
                                    modifier = Modifier
                                        .weight(1f)
                                        .clip(Capsule())
                                        .background(accentColor.copy(alpha = 0.75f))
                                        .clickable {
                                            onDismiss()
                                            onReplace()
                                        }
                                        .padding(horizontal = 12.dp, vertical = 12.dp),
                                    horizontalArrangement = Arrangement.Center,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    androidx.compose.foundation.text.BasicText(replaceText, style = TextStyle(Color.White, 15.sp))
                                }

                                Row(
                                    modifier = Modifier
                                        .weight(1f)
                                        .clip(Capsule())
                                        .background(containerColor.copy(0.2f))
                                        .clickable {
                                            scale = 1f
                                            offsetX = 0f
                                            offsetY = 0f
                                            velocityX = 0f
                                            velocityY = 0f
                                            onTransformAction(scale, offsetX, offsetY)
                                        }
                                        .padding(horizontal = 12.dp, vertical = 12.dp),
                                    horizontalArrangement = Arrangement.Center,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    androidx.compose.foundation.text.BasicText("重置位置", style = TextStyle(contentColor, 15.sp))
                                }
                            }



                            Spacer(Modifier.height(12.dp))

                            Row(
                                Modifier
                                    .clip(Capsule())
                                    .background(containerColor.copy(0.2f))
                                    .clickable { saveAndClose() }
                                    .height(48.dp)
                                    .fillMaxWidth(),
                                horizontalArrangement = Arrangement.Center,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                androidx.compose.foundation.text.BasicText("关闭", style = TextStyle(contentColor, 16.sp))
                            }
                        }
                    }
                }
            }
        }

        MediaSettingDialog(
            visible = showVideoDialog,
            title = "视频",
            replaceText = "更换视频",
            showLoopToggle = true,
            showVolumeSlider = true,
            onReplace = { onReplaceAction(true) },
            onDismiss = { showVideoDialog = false }
        )

        MediaSettingDialog(
            visible = showImageDialog,
            title = "图片",
            replaceText = "更换图片",
            showLoopToggle = false,
            showVolumeSlider = false,
            onReplace = { onReplaceAction(false) },
            onDismiss = { showImageDialog = false }
        )
    }
}

