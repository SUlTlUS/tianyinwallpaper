package com.zeaze.tianyinwallpaper.ui.main

import android.app.WallpaperManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Matrix
import android.graphics.SurfaceTexture
import android.media.MediaPlayer
import android.view.Surface
import android.view.TextureView
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.requiredHeight
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Surface
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.IntOffset
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.core.net.toUri
import com.alibaba.fastjson.JSON
import com.kyant.shapes.Capsule
import com.kyant.shapes.RoundedRectangle
import com.zeaze.tianyinwallpaper.App
import com.zeaze.tianyinwallpaper.backdrop.Backdrop
import com.zeaze.tianyinwallpaper.backdrop.backdrops.layerBackdrop
import com.zeaze.tianyinwallpaper.backdrop.backdrops.LayerBackdrop
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
import com.zeaze.tianyinwallpaper.ui.commom.LiquidWindowAnimatedVisibility
import com.zeaze.tianyinwallpaper.ui.commom.LiquidWindowAnimationMode
import com.zeaze.tianyinwallpaper.utils.FileUtil
import com.zeaze.tianyinwallpaper.utils.WallpaperClockColorMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt
import kotlin.math.sqrt
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

@Composable
internal fun MainPreviewOverlayHost(
    visible: Boolean,
    statusBarTopPaddingDp: androidx.compose.ui.unit.Dp,
    onClose: () -> Unit
) {
    val context = LocalContext.current
    val pref = remember(context) { context.getSharedPreferences(App.TIANYIN, Context.MODE_PRIVATE) }

    var liveSyncIndex by remember { mutableStateOf(pref.getInt(TianYinWallpaperService.PREF_CURRENT_INDEX, 0)) }
    val preferenceListener = remember {
        android.content.SharedPreferences.OnSharedPreferenceChangeListener { p, key ->
            if (key == TianYinWallpaperService.PREF_CURRENT_INDEX) {
                liveSyncIndex = p.getInt(key, 0)
            }
        }
    }

    DisposableEffect(visible) {
        if (visible) {
            pref.registerOnSharedPreferenceChangeListener(preferenceListener)
            liveSyncIndex = pref.getInt(TianYinWallpaperService.PREF_CURRENT_INDEX, 0)
            onDispose { pref.unregisterOnSharedPreferenceChangeListener(preferenceListener) }
        } else {
            onDispose { }
        }
    }

    var isListLoaded by remember(visible) { mutableStateOf(false) }

    if (visible) {
        val wallpaperList by produceState<List<TianYinWallpaperModel>>(
            initialValue = emptyList(),
            visible
        ) {
            if (visible) {
                isListLoaded = false
                value = withContext(Dispatchers.IO) {
                    val listData = FileUtil.loadData(context, FileUtil.wallpaperPath)
                    JSON.parseArray(listData, TianYinWallpaperModel::class.java) ?: emptyList()
                }
                isListLoaded = true
            }
        }

        Dialog(
            onDismissRequest = onClose,
            properties = DialogProperties(
                usePlatformDefaultWidth = false,
                decorFitsSystemWindows = false
            )
        ) {
            LiveSyncPreview(
                wallpaperList = wallpaperList,
                isListLoaded = isListLoaded,
                currentIndex = liveSyncIndex,
                statusBarTopPaddingDp = statusBarTopPaddingDp,
                onClose = onClose,
                onPrev = {
                    context.startService(Intent(context, TianYinWallpaperService::class.java).apply {
                        action = TianYinWallpaperService.ACTION_PREV_WALLPAPER
                    })
                },
                onNext = {
                    context.startService(Intent(context, TianYinWallpaperService::class.java).apply {
                        action = TianYinWallpaperService.ACTION_NEXT_WALLPAPER
                    })
                }
            )
        }
    }
}

@Composable
private fun WallpaperPreviewStage(
    enableLiquidGlass: Boolean,
    backdrop: LayerBackdrop?,
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit
) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .let { m ->
                if (enableLiquidGlass && backdrop != null) m.layerBackdrop(backdrop) else m
            },
        content = content
    )
}

@Composable
internal fun LiveSyncPreview(
    wallpaperList: List<TianYinWallpaperModel>,
    isListLoaded: Boolean,
    currentIndex: Int,
    statusBarTopPaddingDp: androidx.compose.ui.unit.Dp,
    onClose: () -> Unit,
    onPrev: () -> Unit,
    onNext: () -> Unit
) {
    val context = LocalContext.current

    val isWallpaperApplied = remember {
        val info = WallpaperManager.getInstance(context).wallpaperInfo
        info?.packageName == context.packageName &&
                info.serviceName == TianYinWallpaperService::class.java.name
    }
    val currentModel = when {
        !isListLoaded -> null
        !isWallpaperApplied -> null
        currentIndex !in wallpaperList.indices -> null
        else -> wallpaperList[currentIndex]
    }

    val isLightTheme = MaterialTheme.colors.isLight
    val contentColor = if (isLightTheme) Color.Black else Color.White
    val pageBackground = MaterialTheme.colors.background
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
        // Stage：套 layerBackdrop，内部先铺黑色实底，
        // 使 previewBackdrop 采样到的永远是壁纸像素而非主页内容
        WallpaperPreviewStage(
            enableLiquidGlass = enableLiquidGlass,
            backdrop = previewBackdrop
        ) {
            // ── 实色底层，阻断主页内容穿透 ──────────────────────────
            Box(Modifier.fillMaxSize().background(pageBackground))

            // ── 壁纸内容 ────────────────────────────────────────────
            if (currentModel != null) {
                WallpaperPreviewRenderer(
                    model = currentModel,
                    modifier = Modifier.fillMaxSize(),
                    useClip = false,
                    backgroundColor = Color.Black
                )
            } else if (isListLoaded && !isWallpaperApplied) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("当前未播放", color = contentColor)
                }
            }
        }

        // ── 顶部：关闭 + 序号 ────────────────────────────────────────
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, end = 16.dp, top = statusBarTopPaddingDp + 12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            LiveSyncActionButton(
                label = "关闭",
                onClick = onClose,
                enableLiquidGlass = enableLiquidGlass,
                backdrop = previewBackdrop,
                luminanceState = closeLuminanceState,
                surfaceColor = pillBackground,
                textColor = closeLuminanceState?.contentColor ?: onPage,
                modifier = Modifier.height(44.dp),
                fallbackAsTextChip = true,
                fallbackTextChipPadding = androidx.compose.foundation.layout.PaddingValues(
                    horizontal = 18.dp,
                    vertical = 8.dp
                )
            )

            val positionText = if (currentModel != null) "${currentIndex + 1}/${wallpaperList.size}" else "0/0"
            if (enableLiquidGlass && previewBackdrop != null) {
                Box(
                    modifier = Modifier
                        .drawBackdrop(
                            backdrop = previewBackdrop,
                            shape = { Capsule() },
                            effects = {
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

        // ── 底部：上一张 / 下一张 ────────────────────────────────────
        Row(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 24.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            LiveSyncActionButton(
                label = "上一张",
                onClick = onPrev,
                enableLiquidGlass = enableLiquidGlass,
                backdrop = previewBackdrop,
                luminanceState = prevLuminanceState,
                surfaceColor = pillBackground,
                textColor = prevLuminanceState?.contentColor ?: onPage,
                modifier = Modifier.height(44.dp),
                fallbackBorder = androidx.compose.foundation.BorderStroke(
                    1.dp, Color.White.copy(alpha = 0.2f)
                )
            )
            LiveSyncActionButton(
                label = "下一张",
                onClick = onNext,
                enableLiquidGlass = enableLiquidGlass,
                backdrop = previewBackdrop,
                luminanceState = nextLuminanceState,
                surfaceColor = pillBackground,
                textColor = nextLuminanceState?.contentColor ?: onPage,
                modifier = Modifier.height(44.dp),
                fallbackBorder = androidx.compose.foundation.BorderStroke(
                    1.dp, Color.White.copy(alpha = 0.2f)
                )
            )
        }
    }
}
@Composable
private fun LiveSyncActionButton(
    label: String,
    onClick: () -> Unit,
    enableLiquidGlass: Boolean,
    backdrop: Backdrop?,
    luminanceState: com.zeaze.tianyinwallpaper.catalog.utils.AdaptiveLuminanceGlassState?,
    surfaceColor: Color,
    textColor: Color,
    modifier: Modifier = Modifier,
    fallbackAsTextChip: Boolean = false,
    fallbackTextChipPadding: androidx.compose.foundation.layout.PaddingValues = androidx.compose.foundation.layout.PaddingValues(horizontal = 14.dp, vertical = 8.dp),
    fallbackBorder: androidx.compose.foundation.BorderStroke? = null
) {
    if (enableLiquidGlass && backdrop != null) {
        LiquidButton(
            onClick = onClick,
            backdrop = backdrop,
            luminanceState = luminanceState,
            modifier = modifier,
            surfaceColor = surfaceColor
        ) {
            androidx.compose.foundation.text.BasicText(
                label,
                modifier = Modifier.padding(horizontal = 14.dp),
                style = TextStyle(
                    color = textColor,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Medium
                )
            )
        }
        return
    }

    if (fallbackAsTextChip) {
        Text(
            text = label,
            color = textColor,
            modifier = Modifier
                .clip(androidx.compose.foundation.shape.RoundedCornerShape(18.dp))
                .background(surfaceColor)
                .clickable(onClick = onClick)
                .padding(fallbackTextChipPadding)
        )
        return
    }

    Surface(
        modifier = modifier.clickable(onClick = onClick),
        shape = Capsule(),
        color = surfaceColor,
        border = fallbackBorder
    ) {
        Box(contentAlignment = Alignment.Center) {
            Text(text = label, color = textColor, fontSize = 15.sp, fontWeight = FontWeight.Medium)
        }
    }
}

@Composable
private fun WallpaperPreviewRenderer(
    model: TianYinWallpaperModel,
    modifier: Modifier,
    useClip: Boolean,
    transformScale: Float = 1f,
    transformOffsetX: Float = 0f,
    transformOffsetY: Float = 0f,
    containerWidth: Float = 0f,
    containerHeight: Float = 0f,
    sourceWidth: Float = 0f,
    sourceHeight: Float = 0f,
    rotation: Float = 0f,
    brightness: Float = 0f,
    volume: Float? = null,
    backgroundColor: Color,
    onSourceSizeChanged: ((width: Float, height: Float) -> Unit)? = null
) {
    Box(modifier = modifier.background(backgroundColor)) {
        val isDynamicWallpaper = model.type == WALLPAPER_TYPE_DYNAMIC

    // BottomSheet 拖拽关闭状态
    val sheetCoroutineScope = rememberCoroutineScope()
    val animatedOffset = remember { Animatable(0f) }
    val dismissThreshold = with(LocalDensity.current) { 200.dp.toPx() }
        if (isDynamicWallpaper) {
            WallpaperThumbnail(
                model = model,
                modifier = Modifier.fillMaxSize(),
                useClip = useClip,
                transformScale = transformScale,
                transformOffsetX = transformOffsetX,
                transformOffsetY = transformOffsetY,
                rotation = rotation,
                volume = volume,
                onSourceSizeChanged = onSourceSizeChanged
            )
        } else {
            if (containerWidth <= 0f || containerHeight <= 0f) {
                WallpaperThumbnail(
                    model = model,
                    modifier = Modifier.fillMaxSize(),
                    useClip = useClip,
                    rotation = rotation,
                    volume = volume,
                    onSourceSizeChanged = onSourceSizeChanged
                )
            } else {
                val density = LocalDensity.current
                val baseScale = if (sourceWidth > 0f && sourceHeight > 0f) {
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
                            scaleX = transformScale * epsilonX
                            scaleY = transformScale * epsilonY
                            translationX = transformOffsetX
                            translationY = transformOffsetY
                            rotationZ = rotation
                        },
                    useClip = useClip,
                    rotation = rotation,
                    volume = volume,
                    onSourceSizeChanged = onSourceSizeChanged
                )
            }
        }

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

@Composable
private fun WallpaperThumbnail(
    model: TianYinWallpaperModel,
    modifier: Modifier = Modifier,
    useClip: Boolean = true,
    transformScale: Float = 1f,
    transformOffsetX: Float = 0f,
    transformOffsetY: Float = 0f,
    rotation: Float = 0f,
    volume: Float? = null,
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
                        val currentVol = volume ?: model.volume.coerceIn(0f, 1f)
                        setVolume(currentVol, currentVol)
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
                                val currentVol = volume ?: model.volume.coerceIn(0f, 1f)
                                videoHolder.player.setVolume(currentVol, currentVol)
                                videoHolder.player.setSurface(Surface(surface))
                                videoHolder.player.setDataSource(ctx, uri.toUri())
                                videoHolder.player.setOnPreparedListener { mp ->
                                    onSourceSizeChanged?.invoke(mp.videoWidth.toFloat(), mp.videoHeight.toFloat())
                                    updateMatrix(mp, this@apply, transformScale, transformOffsetX, transformOffsetY, rotation)
                                    mp.start()
                                }
                                videoHolder.player.prepareAsync()
                            } catch (e: Exception) {
                                e.printStackTrace()
                            }
                        }

                        override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, width: Int, height: Int) {
                            (tag as? VideoPlayerHolder)?.player?.let {
                                updateMatrix(it, this@apply, transformScale, transformOffsetX, transformOffsetY, rotation)
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
                    val currentVol = volume ?: model.volume.coerceIn(0f, 1f)
                    val currentRot = rotation
                    if (uriChanged) {
                        try {
                            videoHolder.player.reset()
                            videoHolder.player.isLooping = true
                            videoHolder.player.setVolume(currentVol, currentVol)
                            videoHolder.player.setDataSource(context, newUri.toUri())
                            videoHolder.player.setSurface(Surface(textureView.surfaceTexture))
                            videoHolder.player.setOnPreparedListener { mp ->
                                onSourceSizeChanged?.invoke(mp.videoWidth.toFloat(), mp.videoHeight.toFloat())
                                updateMatrix(mp, textureView, transformScale, transformOffsetX, transformOffsetY, currentRot)
                                mp.start()
                            }
                            videoHolder.player.prepareAsync()
                        } catch (e: Exception) {
                            e.printStackTrace()
                        }
                    } else {
                        videoHolder.player.let { mp ->
                            mp.setVolume(currentVol, currentVol)
                            updateMatrix(mp, textureView, transformScale, transformOffsetX, transformOffsetY, currentRot)
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
    offsetY: Float = 0f,
    rotation: Float = 0f
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
        matrix.postRotate(rotation, viewWidth / 2f + offsetX, viewHeight / 2f + offsetY)

        view.setTransform(matrix)
    }
}

private data class VideoPlayerHolder(
    val player: MediaPlayer,
    var uri: String?
)

@Composable
private fun DetailModeButton(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    enableLiquidGlass: Boolean,
    backdrop: Backdrop?,
    luminanceState: com.zeaze.tianyinwallpaper.catalog.utils.AdaptiveLuminanceGlassState?,
    baseSurfaceColor: Color,
    selectedTint: Color,
    unselectedTextColor: Color,
    modifier: Modifier = Modifier
) {
    val buttonColor = if (selected) selectedTint.copy(alpha = 0.75f) else baseSurfaceColor
    val textColor = if (selected) Color.White else unselectedTextColor

    if (enableLiquidGlass && backdrop != null) {
        if (selected) {
            LiquidButton(
                onClick = onClick,
                backdrop = backdrop,
                surfaceColor = buttonColor,
                tint = selectedTint,
                luminanceState = luminanceState,
                modifier = modifier.height(44.dp)
            ) {
                androidx.compose.foundation.text.BasicText(
                    label,
                    modifier = Modifier.padding(horizontal = 14.dp),
                    style = TextStyle(
                        color = textColor,
                        15.sp,
                        fontWeight = FontWeight.Medium
                    )
                )
            }
        } else {
            LiquidButton(
                onClick = onClick,
                backdrop = backdrop,
                surfaceColor = buttonColor,
                luminanceState = luminanceState,
                modifier = modifier.height(44.dp)
            ) {
                androidx.compose.foundation.text.BasicText(
                    label,
                    modifier = Modifier.padding(horizontal = 14.dp),
                    style = TextStyle(
                        color = textColor,
                        15.sp,
                        fontWeight = FontWeight.Medium
                    )
                )
            }
        }
    } else {
        Surface(
            modifier = modifier
                //.height(44.dp)
                .clickable(onClick = onClick),
            shape = Capsule(),
            color = buttonColor
        ) {
            Box(contentAlignment = Alignment.Center) {
                Text(text = label, color = textColor, fontSize = 15.sp, fontWeight = FontWeight.Medium, modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp))
            }
        }
    }
}

@Composable
private fun SettingToggleRow(
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    contentColor: Color,
    isLightTheme: Boolean
) {
    val currentOnCheckedChange by rememberUpdatedState(onCheckedChange)
    val currentChecked by rememberUpdatedState(checked)
    val toggleBackdrop = rememberLayerBackdrop()

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        androidx.compose.foundation.text.BasicText(label, style = TextStyle(contentColor, 15.sp))
        LiquidToggle(
            selected = { currentChecked },
            onSelect = { currentOnCheckedChange(it) },
            backdrop = toggleBackdrop,
            isLightTheme = isLightTheme
        )
    }
}

@Composable
private fun DetailHeaderChip(
    label: String,
    onClick: () -> Unit,
    enableLiquidGlass: Boolean,
    backdrop: Backdrop?,
    luminanceState: com.zeaze.tianyinwallpaper.catalog.utils.AdaptiveLuminanceGlassState?,
    surfaceColor: Color,
    textColor: Color,
    tint: Color? = null
) {
    if (enableLiquidGlass && backdrop != null) {
        if (tint != null) {
            LiquidButton(
                onClick = onClick,
                backdrop = backdrop,
                surfaceColor = surfaceColor,
                tint = tint,
                luminanceState = luminanceState,
                modifier = Modifier.height(44.dp)
            ) {
                androidx.compose.foundation.text.BasicText(
                    label,
                    modifier = Modifier.padding(horizontal = 14.dp),
                    style = TextStyle(textColor, 15.sp)
                )
            }
        } else {
            LiquidButton(
                onClick = onClick,
                backdrop = backdrop,
                surfaceColor = surfaceColor,
                luminanceState = luminanceState,
                modifier = Modifier.height(44.dp)
            ) {
                androidx.compose.foundation.text.BasicText(
                    label,
                    modifier = Modifier.padding(horizontal = 14.dp),
                    style = TextStyle(textColor, 15.sp)
                )
            }
        }
    } else {
        Text(
            text = label,
            color = textColor,
            modifier = Modifier
                .clip(androidx.compose.foundation.shape.RoundedCornerShape(18.dp))
                .background(surfaceColor)
                .clickable(onClick = onClick)
                .padding(horizontal = 18.dp, vertical = 8.dp)
        )
    }
}

@Composable
private fun TimePickerField(
    label: String,
    hour: Int,
    minute: Int,
    isExpanded: Boolean,
    onToggle: () -> Unit,
    onHourSelected: (Int) -> Unit,
    onMinuteSelected: (Int) -> Unit,
    contentColor: Color,
    containerColor: Color,
    formatHm: (Int, Int) -> String
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(Capsule())
                .background(containerColor.copy(0.2f))
                .clickable(onClick = onToggle)
                .padding(horizontal = 20.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            androidx.compose.foundation.text.BasicText(label, style = TextStyle(contentColor, 16.sp))
            androidx.compose.foundation.text.BasicText(
                formatHm(hour, minute),
                style = TextStyle(contentColor.copy(alpha = 0.8f), 16.sp)
            )
        }

        AnimatedVisibility(
            visible = isExpanded,
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
                        initialIndex = hour,
                        onItemSelected = onHourSelected,
                        contentColor = contentColor,
                        label = "时",
                        modifier = Modifier.weight(1f)
                    )
                    WheelPicker(
                        count = 60,
                        initialIndex = minute,
                        onItemSelected = onMinuteSelected,
                        contentColor = contentColor,
                        label = "分",
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        }
    }
}

@Composable
private fun AdaptiveValueSlider(
    value: Float,
    onValueChange: (Float) -> Unit,
    valueRange: ClosedFloatingPointRange<Float>,
    onValueChangeFinished: () -> Unit,
    backdrop: Backdrop,
    isLightTheme: Boolean,
    modifier: Modifier = Modifier
) {
    val currentOnValueChange by rememberUpdatedState(onValueChange)
    val currentOnValueChangeFinished by rememberUpdatedState(onValueChangeFinished)
    val currentValue by rememberUpdatedState(value)

    LiquidSlider(
        value = { currentValue },
        onValueChange = { currentOnValueChange(it) },
        valueRange = valueRange,
        visibilityThreshold = 0.001f,
        backdrop = backdrop,
        isLightTheme = isLightTheme,
        onValueChangeFinished = { currentOnValueChangeFinished() },
        modifier = modifier
    )
}

private fun resolvedContentColor(
    state: com.zeaze.tianyinwallpaper.catalog.utils.AdaptiveLuminanceGlassState?,
    fallback: Color
): Color = state?.contentColor ?: fallback

@Composable
internal fun WallpaperDetailScreen(
    model: TianYinWallpaperModel,
    statusBarTopPaddingDp: androidx.compose.ui.unit.Dp,
    enableLiquidGlass: Boolean,
    onDismiss: () -> Unit,
    onApply: () -> Unit,
    onReplaceAction: (isDynamic: Boolean) -> Unit,
    onTimeAction: (startTime: Int, endTime: Int, loop: Boolean, independentTime: Boolean) -> Unit,
    onTransformAction: (scale: Float, offsetX: Float, offsetY: Float, rotation: Float) -> Unit,
    onBrightnessAction: (brightness: Float) -> Unit,
    onVolumeAction: (volume: Float) -> Unit,
    onClockColorModeAction: (mode: Int) -> Unit
) {
    val context = LocalContext.current
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
    var rotation by remember { mutableStateOf(model.rotation) }
    val brightnessMin = -0.5f
    val brightnessMax = 0f
    var brightness by remember { mutableStateOf(model.brightness.coerceIn(brightnessMin, brightnessMax)) }
    var volume by remember { mutableStateOf(model.volume.coerceIn(0f, 1f)) }
    var clockColorMode by remember { mutableStateOf(model.clockColorMode) }
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

    // BottomSheet 拖拽关闭状态
    val sheetCoroutineScope = rememberCoroutineScope()
    val animatedOffset = remember { Animatable(0f) }
    val dismissThreshold = with(LocalDensity.current) { 200.dp.toPx() }

    val oOo0 = 0.04f
    val oOOo = 4.0E-4f
    val o0oO = 0.015f
    val o0o0 = 2.0E-4f
    var magneticAssistEnabled by remember { mutableStateOf(true) }

    fun calculateDisplaySize(currentScale: Float): Pair<Float, Float>? {
        if (containerWidth <= 0f || containerHeight <= 0f || sourceWidth <= 0f || sourceHeight <= 0f) {
            return null
        }
        val axisCompensateX = (containerWidth + 1f) / containerWidth
        val axisCompensateY = (containerHeight + 1f) / containerHeight
        val baseScale = kotlin.math.max(containerWidth / sourceWidth, containerHeight / sourceHeight)
        val displayWidth = sourceWidth * baseScale * currentScale * axisCompensateX
        val displayHeight = sourceHeight * baseScale * currentScale * axisCompensateY
        return Pair(displayWidth, displayHeight)
    }

    // 吸附范围与边界范围不同：缩小到小于屏幕时也应有可吸附目标。
    fun getSnapRanges(currentScale: Float): Pair<Float, Float> {
        val displaySize = calculateDisplaySize(currentScale) ?: return Pair(0f, 0f)
        val displayWidth = displaySize.first
        val displayHeight = displaySize.second
        val snapX = kotlin.math.abs(displayWidth - containerWidth) / 2f
        val snapY = kotlin.math.abs(displayHeight - containerHeight) / 2f
        return Pair(snapX, snapY)
    }

    fun stepPhysics(frameScale: Float, dragging: Boolean) {
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

    fun formatHm(hour: Int, minute: Int): String {
        val h = if (hour < 10) "0$hour" else "$hour"
        val m = if (minute < 10) "0$minute" else "$minute"
        return "$h:$m"
    }

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
        onTransformAction(scale, offsetX, offsetY, rotation)
        onBrightnessAction(brightness)
        onVolumeAction(volume)
        onClockColorModeAction(clockColorMode)
    }

    // 预览页返回时先保存当前缩放/位置/亮度。
    BackHandler(enabled = showVideoDialog) {
        sheetCoroutineScope.launch {
            animatedOffset.animateTo(2000f, animationSpec = tween(250))
            showVideoDialog = false
        }
    }
    BackHandler(enabled = showImageDialog) {
        sheetCoroutineScope.launch {
            animatedOffset.animateTo(2000f, animationSpec = tween(250))
            showImageDialog = false
        }
    }
    BackHandler(enabled = !showVideoDialog && !showImageDialog) {
        persistPreviewState()
        onDismiss()
    }

    Box(modifier = Modifier.fillMaxSize()) {
        WallpaperPreviewStage(
            enableLiquidGlass = enableLiquidGlass,
            backdrop = detailBackdrop
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
                                onTransformAction(scale, offsetX, offsetY, rotation)
                            }
                        }

                        fun beginGesture() {
                            isDragging = true
                            velocityX = 0f
                            velocityY = 0f
                        }

                        fun finishGesture() {
                            isDragging = false
                            velocityX = 0f
                            velocityY = 0f
                            // 每次手势结束都立即保存一次，避免快速退出丢失。
                            persistPreviewState()
                        }

                        fun applySinglePointer(change: androidx.compose.ui.input.pointer.PointerInputChange) {
                            val delta = change.position - change.previousPosition
                            offsetX += delta.x
                            offsetY += delta.y
                            velocityX = delta.x
                            velocityY = delta.y
                            lastGestureTimeMs = System.currentTimeMillis()
                            change.consume()
                        }

                        fun applyMultiPointer(
                            pressedChanges: List<androidx.compose.ui.input.pointer.PointerInputChange>,
                            lastCentroid: Offset?,
                            lastDistance: Float
                        ): Pair<Offset, Float> {
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

                            pressedChanges.forEach { it.consume() }
                            return Pair(centroid, distance)
                        }

                        val physicsJob = transformScope.launch {
                            var lastFrameNs = withFrameNanos { it }
                            while (true) {
                                if (!isDragging) {
                                    delay(100)
                                    continue
                                }
                                val nowNs = withFrameNanos { it }
                                val dt = ((nowNs - lastFrameNs) / 1_000_000_000f).coerceIn(0.008f, 0.05f)
                                lastFrameNs = nowNs

                                val frameScale = dt / (1f / 60f)
                                stepPhysics(frameScale, isDragging)

                                val idle = System.currentTimeMillis() - lastGestureTimeMs > 100
                                if (idle && kotlin.math.abs(velocityX) < 0.02f && kotlin.math.abs(velocityY) < 0.02f) {
                                    schedulePersist()
                                }
                            }
                        }

                        try {
                            awaitEachGesture {
                                beginGesture()
                                var lastCentroid: Offset? = null
                                var lastDistance = 0f

                                while (true) {
                                    val event = awaitPointerEvent()
                                    val pressedChanges = event.changes.filter { it.pressed }
                                    if (pressedChanges.isEmpty()) break

                                    if (pressedChanges.size == 1) {
                                        val change = pressedChanges[0]
                                        applySinglePointer(change)
                                        lastCentroid = null
                                        lastDistance = 0f
                                    } else {
                                        val result = applyMultiPointer(pressedChanges, lastCentroid, lastDistance)
                                        lastCentroid = result.first
                                        lastDistance = result.second
                                    }
                                }
                                finishGesture()
                            }
                        } finally {
                            isDragging = false
                            velocityX = 0f
                            velocityY = 0f
                            physicsJob.cancel()
                        }
                    }
            
            ) {
                WallpaperPreviewRenderer(
                    model = model,
                    modifier = Modifier.fillMaxSize(),
                    useClip = false,
                    transformScale = scale,
                    transformOffsetX = offsetX,
                    transformOffsetY = offsetY,
                    containerWidth = containerWidth,
                    containerHeight = containerHeight,
                    sourceWidth = sourceWidth,
                    sourceHeight = sourceHeight,
                    rotation = rotation,
                    brightness = brightness,
                    volume = volume,
                    backgroundColor = pageBackground,
                    onSourceSizeChanged = { w, h ->
                        sourceWidth = w
                        sourceHeight = h
                    }
                )
            }
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, end = 16.dp, top = statusBarTopPaddingDp + 12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            DetailHeaderChip(
                label = "取消",
                onClick = {
                    persistPreviewState()
                    onDismiss()
                },
                enableLiquidGlass = enableLiquidGlass,
                backdrop = detailBackdrop,
                luminanceState = cancelLuminanceState,
                surfaceColor = pillBackground,
                textColor = resolvedContentColor(cancelLuminanceState, onPage)
            )
            DetailHeaderChip(
                label = "应用",
                onClick = {
                    persistPreviewState()
                    onApply()
                },
                enableLiquidGlass = enableLiquidGlass,
                backdrop = detailBackdrop,
                luminanceState = applyLuminanceState,
                surfaceColor = Color(0xFF2A83FF).copy(alpha = 0.75f),
                textColor = Color.White,
                tint = Color(0xFF2A83FF)
            )
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

            DetailModeButton(
                label = "视频",
                selected = isDynamicWallpaper,
                onClick = onVideoButtonClick,
                enableLiquidGlass = enableLiquidGlass,
                backdrop = detailBackdrop,
                luminanceState = replaceLuminanceState,
                baseSurfaceColor = pillBackground,
                selectedTint = Color(0xFF2A83FF),
                unselectedTextColor = resolvedContentColor(replaceLuminanceState, onPage)
            )

            DetailModeButton(
                label = "图片",
                selected = !isDynamicWallpaper,
                onClick = onImageButtonClick,
                enableLiquidGlass = enableLiquidGlass,
                backdrop = detailBackdrop,
                luminanceState = timeLuminanceState,
                baseSurfaceColor = pillBackground,
                selectedTint = Color(0xFF2A83FF),
                unselectedTextColor = resolvedContentColor(timeLuminanceState, onPage)
            )
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
            fun saveTimeSettings(): Boolean {
                startTime = startHour * 60 + startMinute
                endTime = endHour * 60 + endMinute
                if (independentTimeEnabled && startTime == endTime) {
                    Toast.makeText(context, "开始时间不能与结束时间相同", Toast.LENGTH_SHORT).show()
                    return false
                }
                onTimeAction(startTime, endTime, loopEnabled, independentTimeEnabled)
                return true
            }

            fun saveAndClose() {
                if (!saveTimeSettings()) return
                onBrightnessAction(brightness)
                onVolumeAction(volume)
                onClockColorModeAction(clockColorMode)
                sheetCoroutineScope.launch {
                    animatedOffset.animateTo(2000f, animationSpec = tween(250))
                    onDismiss()
                }
            }

            // 重置偏移当面板重新显示
            LaunchedEffect(visible) {
                if (visible) {
                    animatedOffset.snapTo(0f)
                }
            }

            // 点击面板外区域关闭（带下滑动画）
            if (visible) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null
                        ) {
                            sheetCoroutineScope.launch {
                                animatedOffset.animateTo(2000f, animationSpec = tween(250))
                                onDismiss()
                            }
                        }
                )
            }

            // BottomSheet 内容
            val density = LocalDensity.current
            val sheetOuterBottomPadding =
                with(density) { WindowInsets.navigationBars.getBottom(this).toDp() } + 16.dp

            LiquidWindowAnimatedVisibility(
                visible = visible,
                mode = LiquidWindowAnimationMode.BottomSheet,
                label = "MediaSettingDialog",
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(start = 16.dp, end = 16.dp, bottom = sheetOuterBottomPadding)
            ) {
                val sheetBackdrop = rememberLayerBackdrop()
                val editBackdrop = detailBackdrop ?: rememberCanvasBackdrop { drawRect(containerColor) }

                Column(
                    Modifier
                        .fillMaxWidth()
                        .heightIn(max = 520.dp)
                        .offset { IntOffset(0, animatedOffset.value.roundToInt()) }
                        .drawBackdrop(
                            backdrop = editBackdrop,
                            shape = { RoundedRectangle(41f.dp) },
                            effects = {
                                colorControls(
                                    brightness = if (isLightTheme) 0.2f else 0f,
                                    saturation = 1.5f
                                )
                                blur(if (isLightTheme) 16f.dp.toPx() else 8f.dp.toPx())
                                lens(16f.dp.toPx(), 32f.dp.toPx(), depthEffect = true)
                            },
                            highlight = { Highlight.Plain },
                            exportedBackdrop = sheetBackdrop,
                            onDrawSurface = { drawRect(containerColor) }
                        )
                        .pointerInput(Unit) { detectTapGestures { } }
                        .padding(start = 16.dp, end = 16.dp, bottom = 16.dp)
                ) {
                    // 拖拽手柄
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp)
                            .pointerInput(Unit) {
                                detectDragGestures(
                                    onDragEnd = {
                                        if (animatedOffset.value > dismissThreshold) {
                                            sheetCoroutineScope.launch {
                                                animatedOffset.animateTo(2000f, animationSpec = tween(200))
                                                onDismiss()
                                            }
                                        } else {
                                            sheetCoroutineScope.launch {
                                                animatedOffset.animateTo(
                                                    0f,
                                                    animationSpec = spring(
                                                        dampingRatio = Spring.DampingRatioNoBouncy,
                                                        stiffness = Spring.StiffnessMedium
                                                    )
                                                )
                                            }
                                        }
                                    },
                                    onDragCancel = {
                                        sheetCoroutineScope.launch {
                                            animatedOffset.animateTo(
                                                0f,
                                                animationSpec = spring(
                                                    dampingRatio = Spring.DampingRatioNoBouncy,
                                                    stiffness = Spring.StiffnessMedium
                                                )
                                            )
                                        }
                                    }
                                ) { change, dragAmount ->
                                    change.consume()
                                    val newOffset = (animatedOffset.value + dragAmount.y).coerceAtLeast(0f)
                                    sheetCoroutineScope.launch { animatedOffset.snapTo(newOffset) }
                                }
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        Box(
                            modifier = Modifier
                                .padding(vertical = 4.dp)
                                .width(40.dp)
                                .height(4.dp)
                                .background(contentColor.copy(alpha = 0.3f), RoundedCornerShape(2.dp))
                        )
                    }
                    Spacer(Modifier.height(4.dp))

                    // 可滚动内容区域
                    val scrollState = rememberScrollState()
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .verticalScroll(scrollState),
                            //.padding(horizontal = 16.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        androidx.compose.foundation.text.BasicText(title, style = TextStyle(contentColor, 18.sp, fontWeight = FontWeight.Bold))

                        Spacer(Modifier.height(12.dp))

                        SettingToggleRow(
                            label = "边缘吸附",
                            checked = magneticAssistEnabled,
                            onCheckedChange = { magneticAssistEnabled = it },
                            contentColor = contentColor,
                            isLightTheme = isLightTheme
                        )

                        if (showLoopToggle) {
                            Spacer(Modifier.height(12.dp))
                            SettingToggleRow(
                                label = "循环播放",
                                checked = loopEnabled,
                                onCheckedChange = { loopEnabled = it },
                                contentColor = contentColor,
                                isLightTheme = isLightTheme
                            )
                        }
                        Spacer(Modifier.height(12.dp))

                        SettingToggleRow(
                            label = "独立时间",
                            checked = independentTimeEnabled,
                            onCheckedChange = { independentTimeEnabled = it },
                            contentColor = contentColor,
                            isLightTheme = isLightTheme
                        )
                        Spacer(Modifier.height(12.dp))

                        AnimatedVisibility(
                            visible = independentTimeEnabled,
                            enter = fadeIn(animationSpec = spring(stiffness = Spring.StiffnessMediumLow)) +
                                    expandVertically(animationSpec = spring(stiffness = Spring.StiffnessMediumLow)),
                            exit = shrinkVertically(animationSpec = spring(stiffness = Spring.StiffnessMediumLow)) +
                                    fadeOut(animationSpec = spring(stiffness = Spring.StiffnessMediumLow))
                        ) {
                            Column(modifier = Modifier.fillMaxWidth()) {
                                TimePickerField(
                                    label = "开始时间",
                                    hour = startHour,
                                    minute = startMinute,
                                    isExpanded = showTimePicker == "start",
                                    onToggle = { showTimePicker = if (showTimePicker == "start") null else "start" },
                                    onHourSelected = { startHour = it },
                                    onMinuteSelected = { startMinute = it },
                                    contentColor = contentColor,
                                    containerColor = containerColor,
                                    formatHm = ::formatHm
                                )

                                Spacer(Modifier.height(12.dp))
                                TimePickerField(
                                    label = "结束时间",
                                    hour = endHour,
                                    minute = endMinute,
                                    isExpanded = showTimePicker == "end",
                                    onToggle = { showTimePicker = if (showTimePicker == "end") null else "end" },
                                    onHourSelected = { endHour = it },
                                    onMinuteSelected = { endMinute = it },
                                    contentColor = contentColor,
                                    containerColor = containerColor,
                                    formatHm = ::formatHm
                                )
                            }
                        }
                        Spacer(Modifier.height(12.dp))

                        BasicText("锁屏时钟颜色", style = TextStyle(contentColor, 14.sp))
                        Spacer(Modifier.height(8.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            listOf(
                                WallpaperClockColorMode.FOLLOW_GLOBAL,
                                WallpaperClockColorMode.LIGHT_CLOCK,
                                WallpaperClockColorMode.DARK_CLOCK
                            ).forEach { mode ->
                                val selected = clockColorMode == mode
                                Row(
                                    modifier = Modifier
                                        .weight(1f)
                                        .height(36.dp)
                                        .clip(RoundedCornerShape(18.dp))
                                        .background(if (selected) accentColor else contentColor.copy(alpha = 0.1f))
                                        .clickable {
                                            clockColorMode = mode
                                            onClockColorModeAction(mode)
                                        },
                                    horizontalArrangement = Arrangement.Center,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    BasicText(
                                        WallpaperClockColorMode.label(mode),
                                        style = TextStyle(if (selected) Color.White else contentColor, 13.sp)
                                    )
                                }
                            }
                        }

                        Spacer(Modifier.height(12.dp))

                        if (showVolumeSlider) {
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                androidx.compose.foundation.text.BasicText("音量", style = TextStyle(contentColor, 14.sp))
                                androidx.compose.foundation.text.BasicText("${(volume * 100f).toInt()}%", style = TextStyle(contentColor, 14.sp))
                            }

                            Spacer(Modifier.height(8.dp))

                            AdaptiveValueSlider(
                                value = volume,
                                onValueChange = { volume = it.coerceIn(0f, 1f) },
                                valueRange = 0f..1f,
                                onValueChangeFinished = { onVolumeAction(volume) },
                                backdrop = sheetBackdrop,
                                isLightTheme = isLightTheme,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 8.dp)
                            )

                            Spacer(Modifier.height(12.dp))
                        }

                        Row(
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            androidx.compose.foundation.text.BasicText("亮度", style = TextStyle(contentColor, 14.sp))
                            androidx.compose.foundation.text.BasicText("${(((brightness - brightnessMin) / (brightnessMax - brightnessMin)) * 100f).toInt()}%", style = TextStyle(contentColor, 14.sp))
                        }

                        Spacer(Modifier.height(8.dp))

                        AdaptiveValueSlider(
                            value = brightness,
                            onValueChange = { brightness = it.coerceIn(brightnessMin, brightnessMax) },
                            valueRange = brightnessMin..brightnessMax,
                            onValueChangeFinished = { onBrightnessAction(brightness) },
                            backdrop = sheetBackdrop,
                            isLightTheme = isLightTheme,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 8.dp)
                        )

                        Spacer(Modifier.height(12.dp))

                        Row(
                            modifier = Modifier
                                .fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Row(
                                modifier = Modifier
                                    .weight(1f)
                                    .clip(Capsule())
                                    .background(accentColor.copy(alpha = 0.75f))
                                    .height(48.dp)
                                    .clickable {
                                        onDismiss()
                                        onReplace()
                                    }
                                    .padding(horizontal = 12.dp, vertical = 12.dp),
                                horizontalArrangement = Arrangement.Center,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                androidx.compose.foundation.text.BasicText(replaceText, style = TextStyle(Color.White, 16.sp))
                            }

                            Row(
                                modifier = Modifier
                                    .weight(1f)
                                    .clip(Capsule())
                                    .background(containerColor.copy(0.2f))
                                    .height(48.dp)
                                    .clickable {
                                        scale = 1f
                                        offsetX = 0f
                                        offsetY = 0f
                                        velocityX = 0f
                                        velocityY = 0f
                                        onTransformAction(scale, offsetX, offsetY, rotation)
                                    }
                                    .padding(horizontal = 12.dp, vertical = 12.dp),
                                horizontalArrangement = Arrangement.Center,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                androidx.compose.foundation.text.BasicText("重置位置", style = TextStyle(contentColor, 16.sp))
                            }
                        }

                    }
                }
            }
        }

        MediaSettingDialog(
            visible = showVideoDialog,
            title = "视频",
            replaceText = "替换视频",
            showLoopToggle = true,
            showVolumeSlider = true,
            onReplace = { onReplaceAction(true) },
            onDismiss = { showVideoDialog = false }
        )

        MediaSettingDialog(
            visible = showImageDialog,
            title = "图片",
            replaceText = "替换图片",
            showLoopToggle = false,
            showVolumeSlider = false,
            onReplace = { onReplaceAction(false) },
            onDismiss = { showImageDialog = false }
        )
    }
}
