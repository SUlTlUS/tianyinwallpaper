package com.zeaze.tianyinwallpaper.ui.raster

import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.ExperimentalAnimationApi
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.layout.offset
import androidx.compose.ui.unit.IntOffset
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed as lazyItemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.material.Icon
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Text

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyListState
import com.zeaze.tianyinwallpaper.backdrop.Backdrop
import com.zeaze.tianyinwallpaper.catalog.components.LiquidButton
import com.zeaze.tianyinwallpaper.catalog.components.LiquidSlider
import com.zeaze.tianyinwallpaper.catalog.components.LiquidToggle
import com.zeaze.tianyinwallpaper.catalog.utils.rememberMultiRegionLuminanceSampler
import com.zeaze.tianyinwallpaper.catalog.utils.rememberRegionLuminanceState
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import com.zeaze.tianyinwallpaper.R
import com.zeaze.tianyinwallpaper.backdrop.backdrops.layerBackdrop
import com.zeaze.tianyinwallpaper.backdrop.backdrops.rememberCanvasBackdrop
import com.zeaze.tianyinwallpaper.backdrop.backdrops.rememberLayerBackdrop
import com.zeaze.tianyinwallpaper.backdrop.drawBackdrop
import com.zeaze.tianyinwallpaper.backdrop.effects.blur
import com.zeaze.tianyinwallpaper.backdrop.effects.colorControls
import com.zeaze.tianyinwallpaper.backdrop.effects.lens
import com.zeaze.tianyinwallpaper.backdrop.highlight.Highlight
import com.zeaze.tianyinwallpaper.model.RasterGroupModel
import com.zeaze.tianyinwallpaper.ui.commom.LiquidWindowAnimatedVisibility
import com.zeaze.tianyinwallpaper.ui.commom.LiquidWindowAnimationMode
import com.zeaze.tianyinwallpaper.utils.FileUtil
import com.zeaze.tianyinwallpaper.utils.WallpaperClockColorMode
import com.kyant.shapes.Capsule
import com.kyant.shapes.RoundedRectangle

import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.launch
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween

private const val WALLPAPER_TYPE_STATIC = 0
private const val WALLPAPER_TYPE_DYNAMIC = 1
private const val MIN_STATIC_GROUP_IMAGES = 2

@Composable
private fun RasterAdjustButtonContent(textColor: Color) {
    Row(
        modifier = Modifier.padding(horizontal = 14.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            painter = painterResource(R.drawable.adjustments),
            contentDescription = null,
            modifier = Modifier.size(18.dp),
            tint = textColor
        )
    }
}

@OptIn(ExperimentalAnimationApi::class)
@Composable
fun RasterDetailScreen(
    group: RasterGroupModel,
    previewAspectRatio: Float,
    statusBarTopPaddingDp: androidx.compose.ui.unit.Dp,
    enableLiquidGlass: Boolean,
    backdrop: Backdrop?,
    staticEditorGroupId: String?,
    onStaticEditorDismiss: () -> Unit,
    onStaticEditorReplaceAll: (RasterGroupModel) -> Unit,
    onStaticEditorAppend: (RasterGroupModel) -> Unit,
    onStaticEditorReplaceSingle: (RasterGroupModel, Int) -> Unit,
    onStaticEditorMove: (RasterGroupModel, Int, Int) -> Unit,
    onStaticEditorCommitReorder: () -> Unit,
    onStaticEditorDeleteSingle: (RasterGroupModel, Int) -> Unit,
    // ── 视频光栅编辑
    videoEditorGroupId: String?,
    onVideoEditorDismiss: () -> Unit,
    onVideoEditorReplaceVideo: (RasterGroupModel) -> Unit,
    onSensorWidthChanged: (RasterGroupModel, Float) -> Unit,
    onSensorWidthChangeFinished: (RasterGroupModel, Float) -> Unit,
    // 新增参数回调
    onEffectTypeChanged: (RasterGroupModel, Int) -> Unit,
    onTransitionBandChanged: (RasterGroupModel, Float) -> Unit,
    onTransitionBandChangeFinished: (RasterGroupModel, Float) -> Unit,
    onEdgeSoftnessChanged: (RasterGroupModel, Float) -> Unit,
    onEdgeSoftnessChangeFinished: (RasterGroupModel, Float) -> Unit,
    // ── 玻璃效果参数回调
    onStripedWavelengthChanged: (RasterGroupModel, Float) -> Unit,
    onStripedWavelengthChangeFinished: (RasterGroupModel, Float) -> Unit,
    onStripedAmplitudeChanged: (RasterGroupModel, Float) -> Unit,
    onStripedAmplitudeChangeFinished: (RasterGroupModel, Float) -> Unit,
    onNarrowWavelengthChanged: (RasterGroupModel, Float) -> Unit,
    onNarrowWavelengthChangeFinished: (RasterGroupModel, Float) -> Unit,
    onNarrowAmplitudeChanged: (RasterGroupModel, Float) -> Unit,
    onNarrowAmplitudeChangeFinished: (RasterGroupModel, Float) -> Unit,
    // ── 动画开关回调
    onGlassAnimEnabledChanged: (RasterGroupModel, Boolean) -> Unit,
    // ── 玻璃宽度回调
    onGlassBandWidthChanged: (RasterGroupModel, Float) -> Unit,
    onGlassBandWidthChangeFinished: (RasterGroupModel, Float) -> Unit,
    // ── 死区开关回调
    onDeadZoneEnabledChanged: (RasterGroupModel, Boolean) -> Unit,
    onClockColorModeChanged: (RasterGroupModel, Int) -> Unit,
    groups: List<RasterGroupModel>,
    onDismiss: () -> Unit,
    onApply: () -> Unit,
    onImageAction: () -> Unit,
    onVideoAction: () -> Unit
) {
    val isLightTheme = MaterialTheme.colors.isLight
    val pageBackground = MaterialTheme.colors.background
    val onPage = MaterialTheme.colors.onBackground
    val pillBackground = if (!isLightTheme) Color(0x22222222) else Color(0x22FFFFFF)
    val contentColor = if (isLightTheme) Color.Black else Color.White
    val accentColor = if (isLightTheme) Color(0xFF0088FF) else Color(0xFF0091FF)
    val containerColor = if (isLightTheme) Color(0xFFFAFAFA).copy(0.6f) else Color(0xFF121212).copy(0.4f)
    val dimColor = if (isLightTheme) Color(0xFF29293A).copy(0.23f) else Color(0xFF121212).copy(0.56f)
    val density = LocalDensity.current
    val bottomActionPadding =
        with(density) { WindowInsets.navigationBars.getBottom(this).toDp() } + 24.dp

    val detailBackdrop = if (enableLiquidGlass) rememberLayerBackdrop() else null

    val context = LocalContext.current

    // 视频光栅加载状态（转码中 / 准备中）
    var videoLoading by remember { mutableStateOf(group.type == RasterGroupModel.TYPE_DYNAMIC) }

    val screenAspectRatio = remember(context) {
        val w = FileUtil.width.takeIf { it > 0 } ?: context.resources.displayMetrics.widthPixels
        val h = FileUtil.height.takeIf { it > 0 } ?: context.resources.displayMetrics.heightPixels
        w.toFloat() / h.toFloat()
    }

    val showStaticEditor = staticEditorGroupId != null
    val editorGroup = (staticEditorGroupId ?: videoEditorGroupId)?.let { id -> groups.firstOrNull { it.id == id } }

    // 定义需要采样的区域
    val luminanceRegions = remember {
        mapOf(
            "cancel" to Rect(0f, 0f, 0.15f, 0.08f),      // 左上角
            "apply" to Rect(0.85f, 0f, 1f, 0.08f),       // 右上角
            "imageAction" to Rect(0.2f, 0.92f, 0.4f, 1f), // 底部左侧
            "videoAction" to Rect(0.6f, 0.92f, 0.8f, 1f)  // 底部右侧
        )
    }

    // 使用单个采样器，一次性计算所有区域 luminance
    val luminanceSampler = if (enableLiquidGlass && detailBackdrop != null) {
        rememberMultiRegionLuminanceSampler(
            enabled = true,
            sampleLayer = detailBackdrop.graphicsLayer,
            regions = luminanceRegions,
            sampleIntervalMs = 200L
        )
    } else null

    // 每个按钮直接读取预计算结果（无额外协程）
    val cancelLuminanceState = if (luminanceSampler != null) {
        rememberRegionLuminanceState(luminanceSampler, "cancel")
    } else null

    val applyLuminanceState = if (luminanceSampler != null) {
        rememberRegionLuminanceState(luminanceSampler, "apply")
    } else null

    val imageActionLuminanceState = if (luminanceSampler != null) {
        rememberRegionLuminanceState(luminanceSampler, "imageAction")
    } else null

    val videoActionLuminanceState = if (luminanceSampler != null) {
        rememberRegionLuminanceState(luminanceSampler, "videoAction")
    } else null

    // BottomSheet 拖拽关闭状态（提前声明，供 BackHandler 使用）
    val coroutineScope = rememberCoroutineScope()
    val animatedOffset = remember { Animatable(0f) }

    // 系统返回关闭编辑面板（带下滑动画）
    BackHandler(enabled = staticEditorGroupId != null) {
        coroutineScope.launch {
            animatedOffset.animateTo(2000f, animationSpec = tween(250))
            onStaticEditorDismiss()
        }
    }
    BackHandler(enabled = videoEditorGroupId != null) {
        coroutineScope.launch {
            animatedOffset.animateTo(2000f, animationSpec = tween(250))
            onVideoEditorDismiss()
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        // 捕获层
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

            // 全屏预览区域
            Box(
                modifier = Modifier.fillMaxSize()
            ) {
                if (group.type == RasterGroupModel.TYPE_STATIC) {
                    RasterPreviewView(
                        group = group,
                        modifier = Modifier.fillMaxSize()
                    )
                } else {
                    VideoRasterPreviewView(
                        group = group,
                        modifier = Modifier.fillMaxSize(),
                        onLoadingChanged = { videoLoading = it }
                    )
                }

            }
        }

        // 顶部按钮
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, end = 16.dp, top = statusBarTopPaddingDp + 12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (enableLiquidGlass && detailBackdrop != null) {
                // 取消按钮 - 使用独立的 luminance 采样
                LiquidButton(
                    onClick = onDismiss,
                    backdrop = detailBackdrop,
                    surfaceColor = pillBackground,
                    luminanceState = cancelLuminanceState,
                    modifier = Modifier.height(44.dp)
                ) {
                    BasicText(
                        "取消",
                        modifier = Modifier.padding(horizontal = 14.dp),
                        style = TextStyle(
                            color = cancelLuminanceState?.contentColor ?: onPage,
                            fontSize = 15.sp
                        )
                    )
                }
                // 应用按钮 - 蓝色按钮也联动 luminance
                LiquidButton(
                    onClick = { if (!videoLoading) onApply() },
                    backdrop = detailBackdrop,
                    surfaceColor = pillBackground,
                    luminanceState = applyLuminanceState,
                    modifier = Modifier.height(44.dp).graphicsLayer {
                        alpha = if (videoLoading) 0.5f else 1f
                    },
                    iconRes = R.drawable.complete,
                    iconContentDescription = "应用",
                    iconSize = 18.dp,
                    iconTint = accentColor
                )
            } else {
                Text(
                    text = "取消", color = onPage,
                    modifier = Modifier
                        .clip(RoundedCornerShape(18.dp))
                        .background(pillBackground)
                        .combinedClickable(onClick = onDismiss)
                        .padding(horizontal = 18.dp, vertical = 8.dp)
                )
                Row(
                    modifier = Modifier
                        .height(44.dp)
                        .clip(Capsule())
                        .background(pillBackground)
                        .combinedClickable(onClick = { if (!videoLoading) onApply() })
                        .padding(horizontal = 14.dp)
                        .graphicsLayer { alpha = if (videoLoading) 0.5f else 1f },
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        painter = painterResource(R.drawable.complete),
                        contentDescription = "应用",
                        modifier = Modifier.size(18.dp),
                        tint = accentColor
                    )
                }
            }
        }

        // 底部切换栏
        Row(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = bottomActionPadding),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            val isStatic = group.type == RasterGroupModel.TYPE_STATIC
            val actionLuminanceState = if (isStatic) imageActionLuminanceState else videoActionLuminanceState
            val actionTextColor = actionLuminanceState?.contentColor ?: onPage
            val actionClick = { if (isStatic) onImageAction() else onVideoAction() }
            if (enableLiquidGlass && detailBackdrop != null) {
                LiquidButton(
                    onClick = actionClick,
                    backdrop = detailBackdrop,
                    surfaceColor = pillBackground,
                    luminanceState = actionLuminanceState,
                    modifier = Modifier.height(44.dp)
                ) {
                    RasterAdjustButtonContent(textColor = actionTextColor)
                }
            } else {
                Row(
                    modifier = Modifier
                        .height(44.dp)
                        .clip(Capsule())
                        .background(pillBackground)
                        .combinedClickable(onClick = actionClick)
                        .padding(horizontal = 14.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    RasterAdjustButtonContent(textColor = actionTextColor)
                }
            }
        }



        // 编辑面板（BottomSheet）
        val currentEditorGroup = editorGroup
        val isSheetVisible = currentEditorGroup != null


        // BottomSheet 拖拽关闭状态
        val sheetOffsetY = remember { mutableStateOf(0f) }
        val dismissThreshold = with(density) { 200.dp.toPx() }
        val sheetOuterBottomPadding =
            with(density) { WindowInsets.navigationBars.getBottom(this).toDp() } + 16.dp

        // 重置偏移当面板重新显示
        LaunchedEffect(isSheetVisible) {
            if (isSheetVisible) {
                animatedOffset.snapTo(0f)
                sheetOffsetY.value = 0f
            }
        }

        // 点击面板外区域关闭（带下滑动画）
        if (isSheetVisible) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .clickable {
                        coroutineScope.launch {
                            animatedOffset.animateTo(
                                2000f,
                                animationSpec = tween(250)
                            )
                            if (staticEditorGroupId != null) onStaticEditorDismiss()
                            else onVideoEditorDismiss()
                        }
                    }
            )
        }

        // BottomSheet 内容
        LiquidWindowAnimatedVisibility(
            visible = isSheetVisible,
            mode = LiquidWindowAnimationMode.BottomSheet,
            label = "RasterEditorSheet",
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(start = 16.dp, end = 16.dp, bottom = sheetOuterBottomPadding)
        ) {
            if (currentEditorGroup != null) {
                val editBackdrop = detailBackdrop ?: rememberCanvasBackdrop { drawRect(containerColor) }
                val sheetBackdrop = rememberLayerBackdrop()
                val thumbnailListState = rememberLazyListState()

                // ★ 新增：当前选中的标签页 (0: 调整, 1: 效果)
                var staticEditorTab by remember(currentEditorGroup.id) { mutableStateOf(0) }

                // ★ 关键：把所有的状态变量提前声明，防止切换标签页时被销毁重建
                var selectedEffectType by remember(currentEditorGroup.id) { mutableStateOf(currentEditorGroup.effectType) }
                var sensorWidth by remember(currentEditorGroup.id) { mutableStateOf(currentEditorGroup.sensorWidth) }
                var transitionBand by remember(currentEditorGroup.id) { mutableStateOf(currentEditorGroup.transitionBand) }
                var edgeSoftness by remember(currentEditorGroup.id) { mutableStateOf(currentEditorGroup.edgeSoftness) }
                var stripedWavelength by remember(currentEditorGroup.id) { mutableStateOf(currentEditorGroup.stripedWavelength) }
                var stripedAmplitude by remember(currentEditorGroup.id) { mutableStateOf(currentEditorGroup.stripedAmplitude) }
                var narrowWavelength by remember(currentEditorGroup.id) { mutableStateOf(currentEditorGroup.narrowWavelength) }
                var narrowAmplitude by remember(currentEditorGroup.id) { mutableStateOf(currentEditorGroup.narrowAmplitude) }
                var glassAnimEnabled by remember(currentEditorGroup.id) { mutableStateOf(currentEditorGroup.glassAnimEnabled) }
                var glassBandWidth by remember(currentEditorGroup.id) { mutableStateOf(currentEditorGroup.glassBandWidth) }
                var deadZoneEnabled by remember(currentEditorGroup.id) { mutableStateOf(currentEditorGroup.deadZoneEnabled) }
                var clockColorMode by remember(currentEditorGroup.id) { mutableStateOf(currentEditorGroup.clockColorMode) }

                // ★ 控制 slider/toggle 交互时禁止滚动
                var isSliderOrToggleInteracting by remember { mutableStateOf(false) }
                val scrollState = rememberScrollState()
                val disableScrollConnection = remember(isSliderOrToggleInteracting) {
                    object : NestedScrollConnection {
                        override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                            return if (isSliderOrToggleInteracting) available else Offset.Zero
                        }
                    }
                }

                Column(
                    Modifier
                        .fillMaxWidth()
                        .heightIn(max = 520.dp)
                        .offset { IntOffset(0, animatedOffset.value.roundToInt()) }
                        .drawBackdrop(
                            backdrop = editBackdrop,
                            shape = { RoundedRectangle(41f.dp) },
                            effects = {
                                colorControls(brightness = if (isLightTheme) 0.2f else 0f, saturation = 1.5f)
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
                    // 拖拽手柄（固定在顶部，不随内容滚动）
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp)
                            .pointerInput(Unit) {
                                detectDragGestures(
                                    onDragEnd = {
                                        if (animatedOffset.value > dismissThreshold) {
                                            coroutineScope.launch {
                                                animatedOffset.animateTo(
                                                    2000f,
                                                    animationSpec = tween(200)
                                                )
                                                if (staticEditorGroupId != null) onStaticEditorDismiss()
                                                else onVideoEditorDismiss()
                                            }
                                        } else {
                                            coroutineScope.launch {
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
                                        coroutineScope.launch {
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
                                    coroutineScope.launch {
                                        animatedOffset.snapTo(newOffset)
                                    }
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

                    BasicText(
                        if (currentEditorGroup.type == RasterGroupModel.TYPE_STATIC) "图集光栅" else "视频光栅",
                        style = TextStyle(contentColor, 18.sp, fontWeight = FontWeight.Bold),
                        modifier = Modifier.fillMaxWidth().wrapContentSize(Alignment.Center)
                    )
                    Spacer(Modifier.height(12.dp))

                    // 可滚动内容区域
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .weight(1f, fill = false)
                            .nestedScroll(disableScrollConnection)
                            .padding(horizontal = 8.dp)
                            .verticalScroll(scrollState),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {

                    // === 图集缩略图区域（保持不变） ===
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
                                            onClockColorModeChanged(currentEditorGroup, mode)
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
                        Spacer(Modifier.height(16.dp))

                    if (currentEditorGroup.type == RasterGroupModel.TYPE_STATIC) {
                        val reorderableState = rememberReorderableLazyListState(
                            lazyListState = thumbnailListState,
                            onMove = { from, to ->
                                onStaticEditorMove(currentEditorGroup, from.index, to.index)
                                onStaticEditorCommitReorder()
                            }
                        )
                        LazyRow(
                            state = thumbnailListState,
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            lazyItemsIndexed(
                                items = currentEditorGroup.imageUris,
                                key = { index, uri ->
                                    val occurrence = currentEditorGroup.imageUris.take(index + 1).count { it == uri }
                                    "${uri}#$occurrence"
                                }
                            ) { index, uri ->
                                val occurrence = currentEditorGroup.imageUris.take(index + 1).count { it == uri }
                                val itemKey = "${uri}#$occurrence"
                                ReorderableItem(reorderableState, key = itemKey) { isDragging ->
                                    Box(
                                        modifier = Modifier
                                            .height(150.dp)
                                            .aspectRatio(screenAspectRatio)
                                            .zIndex(if (isDragging) 1f else 0f)
                                            .graphicsLayer {
                                                scaleX = if (isDragging) 1.05f else 1f
                                                scaleY = if (isDragging) 1.05f else 1f
                                                shadowElevation = if (isDragging) 8.dp.toPx() else 0f
                                                shape = RoundedCornerShape(12.dp)
                                                clip = true
                                            }
                                            .longPressDraggableHandle()
                                            .clip(RoundedCornerShape(12.dp))
                                            .clickable { onStaticEditorReplaceSingle(currentEditorGroup, index) }
                                    ) {
                                        val bmp by produceState<android.graphics.Bitmap?>(initialValue = null, uri) {
                                            value = withContext(Dispatchers.IO) {
                                                runCatching {
                                                    val options = android.graphics.BitmapFactory.Options().apply {
                                                        inPreferredConfig = android.graphics.Bitmap.Config.RGB_565
                                                        inSampleSize = 2
                                                    }
                                                    context.contentResolver.openInputStream(Uri.parse(uri))?.use {
                                                        android.graphics.BitmapFactory.decodeStream(it, null, options)
                                                    }
                                                }.getOrNull()
                                            }
                                        }
                                        if (bmp != null) {
                                            Image(bitmap = bmp!!.asImageBitmap(), contentDescription = null, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                                        } else {
                                            Box(Modifier.fillMaxSize().background(Color.Gray))
                                        }
                                        BasicText("${index + 1}", style = TextStyle(Color.White, 12.sp, fontWeight = FontWeight.Bold), modifier = Modifier.align(Alignment.TopEnd).padding(4.dp).background(Color(0x99000000), RoundedCornerShape(8.dp)).padding(horizontal = 6.dp, vertical = 2.dp))
                                        if (index == 0) {
                                            BasicText("封面", style = TextStyle(Color.White, 12.sp), modifier = Modifier.align(Alignment.BottomCenter).background(Color(0x66000000)).padding(horizontal = 6.dp, vertical = 2.dp))
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
                                                .clickable { onStaticEditorDeleteSingle(currentEditorGroup, index) }
                                                .padding(horizontal = 5.dp)
                                        )
                                    }
                                }
                            }
                            item {
                                Box(
                                    modifier = Modifier.height(150.dp).aspectRatio(screenAspectRatio).clip(RoundedCornerShape(12.dp)).background(containerColor.copy(0.2f)).clickable { onStaticEditorAppend(currentEditorGroup) },
                                    contentAlignment = Alignment.Center
                                ) {
                                    BasicText("+", style = TextStyle(contentColor, 24.sp))
                                }
                            }
                            item {
                                Box(
                                    modifier = Modifier.height(150.dp).aspectRatio(screenAspectRatio).clip(RoundedCornerShape(12.dp)).background(accentColor.copy(alpha = 0.15f)).clickable { onStaticEditorReplaceAll(currentEditorGroup) },
                                    contentAlignment = Alignment.Center
                                ) {
                                    BasicText("替换全部", style = TextStyle(accentColor, 14.sp, fontWeight = FontWeight.Bold))
                                }
                            }
                        }

                        Spacer(Modifier.height(16.dp))

                        // === ★ 新增：标签页切换 UI ===
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(40.dp)
                                .clip(RoundedCornerShape(20.dp))
                                .background(contentColor.copy(alpha = 0.05f))
                                .padding(4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            listOf(0 to "调整", 1 to "效果").forEach { (index, title) ->
                                val isSelected = staticEditorTab == index
                                Box(
                                    modifier = Modifier
                                        .weight(1f)
                                        .fillMaxHeight()
                                        .clip(RoundedCornerShape(16.dp))
                                        .background(if (isSelected) contentColor.copy(alpha = 0.1f) else Color.Transparent)
                                        .clickable { staticEditorTab = index },
                                    contentAlignment = Alignment.Center
                                ) {
                                    BasicText(
                                        text = title,
                                        style = TextStyle(
                                            color = if (isSelected) contentColor else contentColor.copy(alpha = 0.6f),
                                            fontSize = 14.sp,
                                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
                                        )
                                    )
                                }
                            }
                        }

                        Spacer(Modifier.height(16.dp))

                        // === ★ 新增：带水平滑动动画的标签内容页 ===
                        AnimatedContent(
                            targetState = staticEditorTab,
                            transitionSpec = {
                                if (targetState > initialState) {
                                    slideInHorizontally(
                                        animationSpec = tween(250),
                                        initialOffsetX = { width -> width }
                                    ) + fadeIn(animationSpec = tween(250)) togetherWith
                                            slideOutHorizontally(
                                                animationSpec = tween(250),
                                                targetOffsetX = { width -> -width }
                                            ) + fadeOut(animationSpec = tween(250))
                                } else {
                                    slideInHorizontally(
                                        animationSpec = tween(250),
                                        initialOffsetX = { width -> -width }
                                    ) + fadeIn(animationSpec = tween(250)) togetherWith
                                            slideOutHorizontally(
                                                animationSpec = tween(250),
                                                targetOffsetX = { width -> width }
                                            ) + fadeOut(animationSpec = tween(250))
                                }
                            },
                            label = "StaticTabContent"
                        ) { tab ->
                            Column(Modifier.fillMaxWidth()) {
                                if (tab == 0) {
                                    // ────────── 调整标签页 (Tab 0) ──────────
                                    val angleThresholdRad = 0.3285 + 0.041 * sensorWidth
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        BasicText("边缘死区", style = TextStyle(contentColor, 15.sp))
                                        if (enableLiquidGlass && sheetBackdrop != null) {
                                            LiquidToggle(
                                                selected = { !deadZoneEnabled },
                                                onSelect = {
                                                    deadZoneEnabled = !it
                                                    onDeadZoneEnabledChanged(currentEditorGroup, !it)
                                                },
                                                onDragStarted = { isSliderOrToggleInteracting = true },
                                                onDragFinished = { isSliderOrToggleInteracting = false },
                                                backdrop = sheetBackdrop,
                                                isLightTheme = isLightTheme,
                                            )
                                        } else {
                                            androidx.compose.material.Switch(
                                                checked = !deadZoneEnabled,
                                                onCheckedChange = {
                                                    deadZoneEnabled = !it
                                                    onDeadZoneEnabledChanged(currentEditorGroup, !it)
                                                }
                                            )
                                        }
                                    }
                                    Spacer(Modifier.height(16.dp))
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        BasicText("灵敏度", style = TextStyle(contentColor, 14.sp))
                                        BasicText("倾斜 ${String.format("%.0f", Math.toDegrees(angleThresholdRad))}° 到达边缘", style = TextStyle(contentColor.copy(0.5f), 12.sp))
                                    }
                                    Spacer(Modifier.height(4.dp))
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                                    ) {
                                        BasicText("高", style = TextStyle(contentColor.copy(0.6f), 12.sp))
                                        LiquidSlider(
                                            value = { sensorWidth },
                                            onValueChange = { sensorWidth = it; isSliderOrToggleInteracting = true },
                                            onValueChangeFinished = { isSliderOrToggleInteracting = false; onSensorWidthChanged(currentEditorGroup, sensorWidth); onSensorWidthChangeFinished(currentEditorGroup, sensorWidth) },
                                            valueRange = 1f..9f,
                                            visibilityThreshold = 0.1f,
                                            backdrop = sheetBackdrop,
                                            isLightTheme = isLightTheme,
                                            modifier = Modifier.weight(1f)
                                        )
                                        BasicText("低", style = TextStyle(contentColor.copy(0.6f), 12.sp))
                                    }

                                    Spacer(Modifier.height(16.dp))
                                    BasicText("过渡带宽", style = TextStyle(contentColor, 14.sp))
                                    Spacer(Modifier.height(4.dp))
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                                    ) {
                                        BasicText("窄", style = TextStyle(contentColor.copy(0.6f), 12.sp))
                                        LiquidSlider(
                                            value = { transitionBand },
                                            onValueChange = { transitionBand = it; isSliderOrToggleInteracting = true },
                                            onValueChangeFinished = { isSliderOrToggleInteracting = false; onTransitionBandChanged(currentEditorGroup, transitionBand); onTransitionBandChangeFinished(currentEditorGroup, transitionBand) },
                                            valueRange = 0.1f..1f,
                                            visibilityThreshold = 0.001f,
                                            backdrop = sheetBackdrop,
                                            isLightTheme = isLightTheme,
                                            modifier = Modifier.weight(1f)
                                        )
                                        BasicText("宽", style = TextStyle(contentColor.copy(0.6f), 12.sp))
                                    }

                                    Spacer(Modifier.height(16.dp))
                                    BasicText("边缘柔化", style = TextStyle(contentColor, 14.sp))
                                    Spacer(Modifier.height(4.dp))
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                                    ) {
                                        BasicText("锐", style = TextStyle(contentColor.copy(0.6f), 12.sp))
                                        LiquidSlider(
                                            value = { edgeSoftness },
                                            onValueChange = { edgeSoftness = it; isSliderOrToggleInteracting = true },
                                            onValueChangeFinished = { isSliderOrToggleInteracting = false; onEdgeSoftnessChanged(currentEditorGroup, edgeSoftness); onEdgeSoftnessChangeFinished(currentEditorGroup, edgeSoftness) },
                                            valueRange = 0.01f..0.5f,
                                            visibilityThreshold = 0.001f,
                                            backdrop = sheetBackdrop,
                                            isLightTheme = isLightTheme,
                                            modifier = Modifier.weight(1f)
                                        )
                                        BasicText("柔", style = TextStyle(contentColor.copy(0.6f), 12.sp))
                                    }
                                } else {
                                    // ────────── 效果标签页 (Tab 1) ──────────
                                    //Spacer(Modifier.height(12.dp))
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                                    ) {
                                        listOf(
                                            RasterGroupModel.EFFECT_STANDARD to "标准",
                                            RasterGroupModel.EFFECT_CORRUGATED_GLASS to "波纹",
                                            RasterGroupModel.EFFECT_REEDED_GLASS to "长虹",
                                            RasterGroupModel.EFFECT_PRISM_GLASS to "棱镜"
                                        ).forEach { (type, name) ->
                                            val isSelected = selectedEffectType == type
                                            Row(
                                                modifier = Modifier
                                                    .weight(1f)
                                                    .height(36.dp)
                                                    .clip(RoundedCornerShape(18.dp))
                                                    .background(if (isSelected) accentColor else contentColor.copy(0.1f))
                                                    .clickable {
                                                        selectedEffectType = type
                                                        onEffectTypeChanged(currentEditorGroup, type)
                                                    },
                                                horizontalArrangement = Arrangement.Center,
                                                verticalAlignment = Alignment.CenterVertically
                                            ) {
                                                BasicText(name, style = TextStyle(if (isSelected) Color.White else contentColor, 13.sp))
                                            }
                                        }
                                    }

                                    // ── 玻璃效果专属参数 ──
                                    val isGlassEffect = selectedEffectType == RasterGroupModel.EFFECT_CORRUGATED_GLASS ||
                                            selectedEffectType == RasterGroupModel.EFFECT_REEDED_GLASS ||
                                            selectedEffectType == RasterGroupModel.EFFECT_PRISM_GLASS
                                    val isPrismEffect = selectedEffectType == RasterGroupModel.EFFECT_PRISM_GLASS
                                    AnimatedContent(
                                        targetState = Triple(selectedEffectType, isGlassEffect, isPrismEffect),
                                        transitionSpec = {
                                            if (targetState.first > initialState.first) {
                                                slideInHorizontally(
                                                    animationSpec = tween(200),
                                                    initialOffsetX = { width -> width }
                                                ) + fadeIn(animationSpec = tween(200)) togetherWith
                                                        slideOutHorizontally(
                                                            animationSpec = tween(200),
                                                            targetOffsetX = { width -> -width }
                                                        ) + fadeOut(animationSpec = tween(200))
                                            } else {
                                                slideInHorizontally(
                                                    animationSpec = tween(200),
                                                    initialOffsetX = { width -> -width }
                                                ) + fadeIn(animationSpec = tween(200)) togetherWith
                                                        slideOutHorizontally(
                                                            animationSpec = tween(200),
                                                            targetOffsetX = { width -> width }
                                                        ) + fadeOut(animationSpec = tween(200))
                                            } using SizeTransform(
                                                clip = false,
                                                sizeAnimationSpec = { _, _ ->
                                                    tween(200, easing = FastOutSlowInEasing)
                                                }
                                            )
                                        },
                                        label = "EffectParams"
                                    ) { (_, glassEffect, prismEffect) ->
                                        Column(Modifier.fillMaxWidth()) {
                                            if (glassEffect) {
                                                Spacer(Modifier.height(16.dp))
                                                Row(
                                                    modifier = Modifier
                                                        .fillMaxWidth(),
                                                    horizontalArrangement = Arrangement.SpaceBetween,
                                                    verticalAlignment = Alignment.CenterVertically
                                                ) {
                                                    BasicText("动画", style = TextStyle(contentColor, 15.sp))
                                                    if (enableLiquidGlass && sheetBackdrop != null) {
                                                        LiquidToggle(
                                                            selected = { glassAnimEnabled },
                                                            onSelect = {
                                                                glassAnimEnabled = it
                                                                onGlassAnimEnabledChanged(currentEditorGroup, it)
                                                            },
                                                            onDragStarted = { isSliderOrToggleInteracting = true },
                                                            onDragFinished = { isSliderOrToggleInteracting = false },
                                                            backdrop = sheetBackdrop,
                                                            isLightTheme = isLightTheme,
                                                        )
                                                    } else {
                                                        androidx.compose.material.Switch(
                                                            checked = glassAnimEnabled,
                                                            onCheckedChange = {
                                                                glassAnimEnabled = it
                                                                onGlassAnimEnabledChanged(currentEditorGroup, it)
                                                            }
                                                        )
                                                    }
                                                }

                                                Spacer(Modifier.height(16.dp))
                                                BasicText("宽度", style = TextStyle(contentColor, 14.sp))
                                                Spacer(Modifier.height(4.dp))
                                                Row(
                                                    modifier = Modifier.fillMaxWidth(),
                                                    verticalAlignment = Alignment.CenterVertically,
                                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                                ) {
                                                    BasicText("窄", style = TextStyle(contentColor.copy(0.6f), 12.sp))
                                                    LiquidSlider(
                                                        value = { glassBandWidth },
                                                        onValueChange = { glassBandWidth = it; isSliderOrToggleInteracting = true },
                                                        onValueChangeFinished = { isSliderOrToggleInteracting = false; onGlassBandWidthChanged(currentEditorGroup, glassBandWidth); onGlassBandWidthChangeFinished(currentEditorGroup, glassBandWidth) },
                                                        valueRange = 0.05f..1f,
                                                        visibilityThreshold = 0.01f,
                                                        backdrop = sheetBackdrop,
                                                        isLightTheme = isLightTheme,
                                                        modifier = Modifier.weight(1f)
                                                    )
                                                    BasicText("宽", style = TextStyle(contentColor.copy(0.6f), 12.sp))
                                                }

                                                Spacer(Modifier.height(16.dp))

                                                BasicText("波长", style = TextStyle(contentColor, 14.sp))
                                                Spacer(Modifier.height(4.dp))
                                                Row(
                                                    modifier = Modifier.fillMaxWidth(),
                                                    verticalAlignment = Alignment.CenterVertically,
                                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                                ) {
                                                    BasicText("窄", style = TextStyle(contentColor.copy(0.6f), 12.sp))
                                                    LiquidSlider(
                                                        value = { stripedWavelength },
                                                        onValueChange = { stripedWavelength = it; isSliderOrToggleInteracting = true },
                                                        onValueChangeFinished = { isSliderOrToggleInteracting = false; onStripedWavelengthChanged(currentEditorGroup, stripedWavelength); onStripedWavelengthChangeFinished(currentEditorGroup, stripedWavelength) },
                                                        valueRange = 8f..80f,
                                                        visibilityThreshold = 1f,
                                                        backdrop = sheetBackdrop,
                                                        isLightTheme = isLightTheme,
                                                        modifier = Modifier.weight(1f)
                                                    )
                                                    BasicText("宽", style = TextStyle(contentColor.copy(0.6f), 12.sp))
                                                }

                                                Spacer(Modifier.height(16.dp))
                                                BasicText("振幅", style = TextStyle(contentColor, 14.sp))
                                                Spacer(Modifier.height(4.dp))
                                                Row(
                                                    modifier = Modifier.fillMaxWidth(),
                                                    verticalAlignment = Alignment.CenterVertically,
                                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                                ) {
                                                    BasicText("弱", style = TextStyle(contentColor.copy(0.6f), 12.sp))
                                                    LiquidSlider(
                                                        value = { stripedAmplitude },
                                                        onValueChange = { stripedAmplitude = it; isSliderOrToggleInteracting = true },
                                                        onValueChangeFinished = { isSliderOrToggleInteracting = false; onStripedAmplitudeChanged(currentEditorGroup, stripedAmplitude); onStripedAmplitudeChangeFinished(currentEditorGroup, stripedAmplitude) },
                                                        valueRange = 2f..40f,
                                                        visibilityThreshold = 1f,
                                                        backdrop = sheetBackdrop,
                                                        isLightTheme = isLightTheme,
                                                        modifier = Modifier.weight(1f)
                                                    )
                                                    BasicText("强", style = TextStyle(contentColor.copy(0.6f), 12.sp))
                                                }

                                                // ── 棱镜模式额外参数 ──
                                                if (prismEffect) {
                                                    Spacer(Modifier.height(16.dp))
                                                    BasicText("窄波波长", style = TextStyle(contentColor, 14.sp))
                                                    Spacer(Modifier.height(4.dp))
                                                    Row(
                                                        modifier = Modifier.fillMaxWidth(),
                                                        verticalAlignment = Alignment.CenterVertically,
                                                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                                                    ) {
                                                        BasicText("窄", style = TextStyle(contentColor.copy(0.6f), 12.sp))
                                                        LiquidSlider(
                                                            value = { narrowWavelength },
                                                            onValueChange = { narrowWavelength = it; isSliderOrToggleInteracting = true },
                                                            onValueChangeFinished = { isSliderOrToggleInteracting = false; onNarrowWavelengthChanged(currentEditorGroup, narrowWavelength); onNarrowWavelengthChangeFinished(currentEditorGroup, narrowWavelength) },
                                                            valueRange = 4f..40f,
                                                            visibilityThreshold = 1f,
                                                            backdrop = sheetBackdrop,
                                                            isLightTheme = isLightTheme,
                                                            modifier = Modifier.weight(1f)
                                                        )
                                                        BasicText("宽", style = TextStyle(contentColor.copy(0.6f), 12.sp))
                                                    }

                                                    Spacer(Modifier.height(16.dp))
                                                    BasicText("窄波振幅", style = TextStyle(contentColor, 14.sp))
                                                    Spacer(Modifier.height(4.dp))
                                                    Row(
                                                        modifier = Modifier.fillMaxWidth(),
                                                        verticalAlignment = Alignment.CenterVertically,
                                                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                                                    ) {
                                                        BasicText("弱", style = TextStyle(contentColor.copy(0.6f), 12.sp))
                                                        LiquidSlider(
                                                            value = { narrowAmplitude },
                                                            onValueChange = { narrowAmplitude = it; isSliderOrToggleInteracting = true },
                                                            onValueChangeFinished = { isSliderOrToggleInteracting = false; onNarrowAmplitudeChanged(currentEditorGroup, narrowAmplitude); onNarrowAmplitudeChangeFinished(currentEditorGroup, narrowAmplitude) },
                                                            valueRange = 1f..20f,
                                                            visibilityThreshold = 1f,
                                                            backdrop = sheetBackdrop,
                                                            isLightTheme = isLightTheme,
                                                            modifier = Modifier.weight(1f)
                                                        )
                                                        BasicText("强", style = TextStyle(contentColor.copy(0.6f), 12.sp))
                                                    }
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    } else {
                        // === 视频光栅逻辑（保持原样，只显示灵敏度） ===
                        val angleThresholdRad = 0.3285 + 0.041 * sensorWidth
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            BasicText("灵敏度", style = TextStyle(contentColor, 14.sp))
                            BasicText("倾斜 ${String.format("%.0f", Math.toDegrees(angleThresholdRad))}° 到达边缘", style = TextStyle(contentColor.copy(0.5f), 12.sp))
                        }
                        Spacer(Modifier.height(4.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            BasicText("高", style = TextStyle(contentColor.copy(0.6f), 12.sp))
                            LiquidSlider(
                                value = { sensorWidth },
                                onValueChange = { sensorWidth = it; isSliderOrToggleInteracting = true },
                                onValueChangeFinished = { isSliderOrToggleInteracting = false; onSensorWidthChanged(currentEditorGroup, sensorWidth); onSensorWidthChangeFinished(currentEditorGroup, sensorWidth) },
                                valueRange = 1f..9f,
                                visibilityThreshold = 0.1f,
                                backdrop = sheetBackdrop,
                                isLightTheme = isLightTheme,
                                modifier = Modifier.weight(1f)
                            )
                            BasicText("低", style = TextStyle(contentColor.copy(0.6f), 12.sp))
                        }

                        Spacer(Modifier.height(16.dp))

                    }

                    // ── 底部按钮（仅视频类型显示替换按钮）──
                    if (currentEditorGroup.type != RasterGroupModel.TYPE_STATIC) {
                        Spacer(Modifier.height(24.dp))
                        Row(
                            Modifier
                                .clip(Capsule())
                                .background(accentColor)
                                .clickable { onVideoEditorReplaceVideo(currentEditorGroup) }
                                .height(48.dp)
                                .fillMaxWidth(),
                            horizontalArrangement = Arrangement.Center,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            BasicText("替换视频", style = TextStyle(Color.White, 16.sp))
                        }
                    }
                    } // 可滚动内容区域结束
                }
            }
        }
    }

}
