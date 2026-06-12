package com.zeaze.tianyinwallpaper.ui.depth

import android.app.Activity
import android.app.WallpaperManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.net.Uri
import android.os.Build
import android.provider.OpenableColumns
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.material.CircularProgressIndicator
import androidx.compose.material.Icon
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.BasicTextField
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.zIndex
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import com.kyant.shapes.Capsule
import com.kyant.shapes.RoundedRectangle
import com.zeaze.tianyinwallpaper.App
import com.zeaze.tianyinwallpaper.R
import com.zeaze.tianyinwallpaper.backdrop.Backdrop
import com.zeaze.tianyinwallpaper.backdrop.backdrops.rememberCanvasBackdrop
import com.zeaze.tianyinwallpaper.backdrop.backdrops.rememberLayerBackdrop
import com.zeaze.tianyinwallpaper.backdrop.backdrops.layerBackdrop
import com.zeaze.tianyinwallpaper.backdrop.drawBackdrop
import com.zeaze.tianyinwallpaper.backdrop.effects.blur
import com.zeaze.tianyinwallpaper.backdrop.effects.colorControls
import com.zeaze.tianyinwallpaper.backdrop.effects.lens
import com.zeaze.tianyinwallpaper.backdrop.highlight.Highlight
import com.zeaze.tianyinwallpaper.base.rxbus.RxBus
import com.zeaze.tianyinwallpaper.base.rxbus.RxConstants
import com.zeaze.tianyinwallpaper.catalog.components.LiquidButton
import com.zeaze.tianyinwallpaper.catalog.components.LiquidSlider
import com.zeaze.tianyinwallpaper.catalog.utils.rememberMultiRegionLuminanceSampler
import com.zeaze.tianyinwallpaper.catalog.utils.rememberRegionLuminanceState
import com.zeaze.tianyinwallpaper.model.DepthWallpaperModel
import com.zeaze.tianyinwallpaper.service.DepthWallpaperService
import com.zeaze.tianyinwallpaper.ui.commom.LiquidWindowAnimatedVisibility
import com.zeaze.tianyinwallpaper.ui.commom.LiquidWindowAnimationMode
import com.zeaze.tianyinwallpaper.ui.main.SelectionBarState
import com.zeaze.tianyinwallpaper.utils.DepthPrefs
import com.zeaze.tianyinwallpaper.utils.GaussianPlyLoader
import com.zeaze.tianyinwallpaper.utils.GaussianSceneLoader
import com.zeaze.tianyinwallpaper.utils.GradioMcpSogGenerator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyGridState
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import kotlin.math.roundToInt

private const val GAUSSIAN_MIN_SPLAT_BUDGET = 100_000
private const val GAUSSIAN_FULL_SPLAT_BUDGET = 1_500_000
private const val GAUSSIAN_SPLAT_BUDGET_STEP = 100_000

private fun formatSplatBudget(value: Int): String {
    return String.format("%.1fM", value / 1_000_000f)
}

private fun Int.roundToNearest(step: Int): Int {
    if (step <= 0) return this
    return ((this + step / 2) / step) * step
}

@Composable
fun DepthPreviewOverlay(
    model: DepthWallpaperModel,
    statusBarTopPaddingDp: androidx.compose.ui.unit.Dp,
    contentColor: Color,
    accentColor: Color,
    containerColor: Color,
    enableLiquidGlass: Boolean,
    onDismiss: () -> Unit,
    onApply: () -> Unit,
    onModelChange: (DepthWallpaperModel) -> Unit,
    modifier: Modifier = Modifier
) {
    val density = LocalDensity.current
    val isLightTheme = MaterialTheme.colors.isLight
    val pillBackground = if (MaterialTheme.colors.isLight) Color(0x22FFFFFF) else Color(0x55222222)
    val detailBackdrop = if (enableLiquidGlass) rememberLayerBackdrop() else null
    val coroutineScope = rememberCoroutineScope()
    val animatedOffset = remember { Animatable(0f) }
    val dismissThreshold = with(density) { 200.dp.toPx() }
    val bottomActionPadding =
        with(density) { WindowInsets.navigationBars.getBottom(this).toDp() } + 24.dp
    val sheetOuterBottomPadding =
        with(density) { WindowInsets.navigationBars.getBottom(this).toDp() } + 16.dp
    var previewLoading by remember(model.id, model.gaussianUri, model.gaussianRenderMode, model.gaussianMaxSplats) {
        mutableStateOf(true)
    }
    var showParamPanel by remember(model.id) { mutableStateOf(false) }

    val luminanceRegions = remember {
        mapOf(
            "cancel" to Rect(0f, 0f, 0.18f, 0.1f),
            "apply" to Rect(0.82f, 0f, 1f, 0.1f),
            "gaussian" to Rect(0.36f, 0.9f, 0.64f, 1f)
        )
    }
    val luminanceSampler = if (detailBackdrop != null) {
        rememberMultiRegionLuminanceSampler(
            enabled = true,
            sampleLayer = detailBackdrop.graphicsLayer,
            regions = luminanceRegions,
            sampleIntervalMs = 200L
        )
    } else {
        null
    }
    val cancelLuminanceState = luminanceSampler?.let { rememberRegionLuminanceState(it, "cancel") }
    val applyLuminanceState = luminanceSampler?.let { rememberRegionLuminanceState(it, "apply") }
    val gaussianLuminanceState = luminanceSampler?.let { rememberRegionLuminanceState(it, "gaussian") }

    fun closeWithAnimation() {
        coroutineScope.launch {
            animatedOffset.animateTo(2000f, animationSpec = tween(250))
            onDismiss()
        }
    }

    fun closeParamPanel() {
        coroutineScope.launch {
            animatedOffset.animateTo(2000f, animationSpec = tween(200))
            showParamPanel = false
        }
    }

    LaunchedEffect(showParamPanel) {
        if (showParamPanel) {
            animatedOffset.snapTo(0f)
        }
    }

    BackHandler(enabled = showParamPanel) {
        closeParamPanel()
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black)
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .let { m ->
                    if (detailBackdrop != null) m.layerBackdrop(detailBackdrop) else m
                }
        ) {
            DepthPreviewView(
                model = model,
                onModelChange = onModelChange,
                onLoadingChanged = { previewLoading = it },
                modifier = Modifier.fillMaxSize()
            )
        }

        if (previewLoading) {
            Column(
                modifier = Modifier.align(Alignment.Center),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                CircularProgressIndicator(color = Color.White, strokeWidth = 2.dp)
                Text(
                    text = "正在加载",
                    color = Color.White.copy(alpha = 0.82f),
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium
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
            if (detailBackdrop != null) {
                LiquidButton(
                    onClick = { closeWithAnimation() },
                    backdrop = detailBackdrop,
                    surfaceColor = pillBackground,
                    luminanceState = cancelLuminanceState,
                    modifier = Modifier.height(44.dp)
                ) {
                    BasicText(
                        "取消",
                        modifier = Modifier.padding(horizontal = 14.dp),
                        style = TextStyle(
                            color = cancelLuminanceState?.contentColor ?: contentColor,
                            fontSize = 15.sp
                        )
                    )
                }
                LiquidButton(
                    onClick = { if (!previewLoading) onApply() },
                    backdrop = detailBackdrop,
                    surfaceColor = if (previewLoading) Color.Gray.copy(alpha = 0.5f)
                    else accentColor.copy(alpha = 0.75f),
                    tint = if (previewLoading) Color.Unspecified else accentColor,
                    luminanceState = applyLuminanceState,
                    modifier = Modifier
                        .height(44.dp)
                        .graphicsLayer { alpha = if (previewLoading) 0.5f else 1f }
                ) {
                    BasicText(
                        "应用",
                        modifier = Modifier.padding(horizontal = 14.dp),
                        style = TextStyle(
                            color = if (previewLoading) Color.White.copy(alpha = 0.5f) else Color.White,
                            fontSize = 15.sp
                        )
                    )
                }
            } else {
                DepthPreviewPill(
                    text = "取消",
                    color = contentColor,
                    background = pillBackground,
                    onClick = { closeWithAnimation() }
                )
                DepthPreviewPill(
                    text = "应用",
                    color = if (previewLoading) Color.White.copy(alpha = 0.5f) else Color.White,
                    background = if (previewLoading) Color.Gray.copy(alpha = 0.3f) else Color(0x662A83FF),
                    onClick = { if (!previewLoading) onApply() }
                )
            }
        }

        Box(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = bottomActionPadding)
        ) {
            if (detailBackdrop != null) {
                LiquidButton(
                    onClick = { showParamPanel = true },
                    backdrop = detailBackdrop,
                    surfaceColor = pillBackground,
                    luminanceState = gaussianLuminanceState,
                    modifier = Modifier.height(44.dp)
                ) {
                    DepthAdjustButtonContent(
                        textColor = gaussianLuminanceState?.contentColor ?: contentColor
                    )
                }
            } else {
                Row(
                    modifier = Modifier
                        .height(44.dp)
                        .clip(Capsule())
                        .background(pillBackground)
                        .clickable { showParamPanel = true }
                        .padding(horizontal = 14.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    DepthAdjustButtonContent(textColor = contentColor)
                }
            }
        }

        if (showParamPanel) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .clickable { closeParamPanel() }
            )
        }

        LiquidWindowAnimatedVisibility(
            visible = showParamPanel,
            mode = LiquidWindowAnimationMode.BottomSheet,
            label = "DepthParamSheet",
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(start = 16.dp, end = 16.dp, bottom = sheetOuterBottomPadding)
        ) {
            DepthParamPanel(
                model = model,
                onModelChange = onModelChange,
                backdrop = detailBackdrop,
                containerColor = containerColor,
                contentColor = contentColor,
                accentColor = accentColor,
                isLightTheme = isLightTheme,
                sheetOffset = animatedOffset.value,
                onDrag = { dragY ->
                    coroutineScope.launch {
                        animatedOffset.snapTo((animatedOffset.value + dragY).coerceAtLeast(0f))
                    }
                },
                onDragEnd = {
                    coroutineScope.launch {
                        if (animatedOffset.value > dismissThreshold) {
                            animatedOffset.animateTo(2000f, animationSpec = tween(200))
                            showParamPanel = false
                        } else {
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
                modifier = Modifier
            )
        }
    }
}

@Composable
private fun DepthAdjustButtonContent(textColor: Color) {
    Row(
        modifier = Modifier.padding(horizontal = 14.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            painter = painterResource(R.drawable.adjustments),
            contentDescription = null,
            modifier = Modifier.width(18.dp).height(18.dp),
            tint = textColor
        )
        BasicText(
            "调节参数",
            style = TextStyle(
                color = textColor,
                fontSize = 15.sp,
                fontWeight = FontWeight.Medium
            )
        )
    }
}

@Composable
private fun DepthParamPanel(
    model: DepthWallpaperModel,
    onModelChange: (DepthWallpaperModel) -> Unit,
    backdrop: Backdrop?,
    containerColor: Color,
    contentColor: Color,
    accentColor: Color,
    isLightTheme: Boolean,
    sheetOffset: Float,
    onDrag: (Float) -> Unit,
    onDragEnd: () -> Unit,
    modifier: Modifier = Modifier
) {
    var isSliderInteracting by remember { mutableStateOf(false) }
    val scrollState = rememberScrollState()
    val disableScrollConnection = remember(isSliderInteracting) {
        object : NestedScrollConnection {
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                return if (isSliderInteracting) available else Offset.Zero
            }
        }
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(max = 520.dp)
            .offset { IntOffset(0, sheetOffset.roundToInt()) }
            .then(
                if (backdrop != null) {
                    Modifier.drawBackdrop(
                        backdrop = backdrop,
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
                        onDrawSurface = { drawRect(containerColor) }
                    )
                } else {
                    Modifier
                        .clip(RoundedCornerShape(24.dp))
                        .background(Color(0x99000000))
                }
            )
            .padding(start = 16.dp, end = 16.dp, bottom = 16.dp)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 4.dp)
                .pointerInput(Unit) {
                    detectDragGestures(
                        onDragEnd = onDragEnd,
                        onDragCancel = onDragEnd
                    ) { change, dragAmount ->
                        change.consume()
                        onDrag(dragAmount.y)
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
            "参数调节",
            style = TextStyle(contentColor, 18.sp, fontWeight = FontWeight.Bold),
            modifier = Modifier.fillMaxWidth().wrapContentSize(Alignment.Center)
        )
        Spacer(Modifier.height(12.dp))
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f, fill = false)
                .nestedScroll(disableScrollConnection)
                .padding(horizontal = 8.dp)
                .verticalScroll(scrollState),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            DepthParamSlider(
                label = "灵敏度",
                value = model.sensorSensitivity,
                valueText = String.format("%.1f", model.sensorSensitivity),
                range = 1f..9f,
                onValueChange = { onModelChange(model.copy(sensorSensitivity = it)) },
                backdrop = backdrop,
                isLightTheme = isLightTheme,
                contentColor = contentColor,
                visibilityThreshold = 0.1f,
                onInteractionChange = { isSliderInteracting = it }
            )
            DepthParamSlider(
                label = "视差强度",
                value = model.parallaxStrength,
                valueText = String.format("%.3f", model.parallaxStrength),
                range = 0.001f..0.075f,
                onValueChange = { onModelChange(model.copy(parallaxStrength = it)) },
                backdrop = backdrop,
                isLightTheme = isLightTheme,
                contentColor = contentColor,
                visibilityThreshold = 0.001f,
                onInteractionChange = { isSliderInteracting = it }
            )
            if (model.isGaussian()) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    DepthPreviewModePill(
                        text = "Native",
                        selected = !model.useWebGaussianRenderer(),
                        onClick = { onModelChange(model.copy(gaussianRenderMode = "native")) },
                        modifier = Modifier.weight(1f)
                    )
                    DepthPreviewModePill(
                        text = "WebView",
                        selected = model.useWebGaussianRenderer(),
                        onClick = { onModelChange(model.copy(gaussianRenderMode = "web")) },
                        modifier = Modifier.weight(1f)
                    )
                }
                DepthParamSlider(
                    label = "清晰度",
                    value = model.gaussianMaxSplats.toFloat(),
                    valueText = formatSplatBudget(model.gaussianMaxSplats),
                    range = GAUSSIAN_MIN_SPLAT_BUDGET.toFloat()..GAUSSIAN_FULL_SPLAT_BUDGET.toFloat(),
                    onValueChange = {
                        val budget = it.toInt()
                            .roundToNearest(GAUSSIAN_SPLAT_BUDGET_STEP)
                            .coerceIn(GAUSSIAN_MIN_SPLAT_BUDGET, GAUSSIAN_FULL_SPLAT_BUDGET)
                        onModelChange(model.copy(gaussianMaxSplats = budget))
                    },
                    backdrop = backdrop,
                    isLightTheme = isLightTheme,
                    contentColor = contentColor,
                    visibilityThreshold = GAUSSIAN_SPLAT_BUDGET_STEP.toFloat(),
                    onInteractionChange = { isSliderInteracting = it }
                )
                DepthParamSlider(
                    label = "距离",
                    value = model.cameraZoom,
                    valueText = String.format("%.2f", model.cameraZoom),
                    range = 0.6f..10f,
                    onValueChange = { onModelChange(model.copy(cameraZoom = it)) },
                    backdrop = backdrop,
                    isLightTheme = isLightTheme,
                    contentColor = contentColor,
                    visibilityThreshold = 0.01f,
                    onInteractionChange = { isSliderInteracting = it }
                )
                DepthParamSlider(
                    label = "注视深度",
                    value = model.focusDepth,
                    valueText = String.format("%.2f", model.focusDepth),
                    range = -1f..1f,
                    onValueChange = { onModelChange(model.copy(focusDepth = it)) },
                    backdrop = backdrop,
                    isLightTheme = isLightTheme,
                    contentColor = contentColor,
                    visibilityThreshold = 0.01f,
                    onInteractionChange = { isSliderInteracting = it }
                )
                Text(
                    text = "重置注视点",
                    color = Color.White,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier
                        .clip(RoundedCornerShape(16.dp))
                        .background(Color(0x332A83FF))
                        .clickable {
                            onModelChange(
                                model.copy(
                                    centerOffsetX = 0f,
                                    centerOffsetY = 0f,
                                    focusDepth = 0.25f
                                )
                            )
                        }
                        .padding(horizontal = 12.dp, vertical = 7.dp)
                )
            }
        }
    }
}


@Composable
private fun DepthPreviewModePill(
    text: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .height(34.dp)
            .clip(RoundedCornerShape(18.dp))
            .background(if (selected) Color(0xAA2A83FF) else Color(0x33000000))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = text,
            color = Color.White,
            fontSize = 13.sp,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium
        )
    }
}

@Composable
private fun DepthParamSlider(
    label: String,
    value: Float,
    valueText: String,
    range: ClosedFloatingPointRange<Float>,
    onValueChange: (Float) -> Unit,
    backdrop: Backdrop?,
    isLightTheme: Boolean,
    contentColor: Color,
    visibilityThreshold: Float,
    onInteractionChange: (Boolean) -> Unit = {}
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            BasicText(label, style = TextStyle(contentColor, 14.sp, fontWeight = FontWeight.Medium))
            BasicText(valueText, style = TextStyle(contentColor.copy(alpha = 0.62f), 12.sp))
        }
        if (backdrop != null) {
            LiquidSlider(
                value = { value.coerceIn(range.start, range.endInclusive) },
                onValueChange = {
                    onInteractionChange(true)
                    onValueChange(it)
                },
                onValueChangeFinished = {
                    onInteractionChange(false)
                },
                valueRange = range,
                visibilityThreshold = visibilityThreshold,
                backdrop = backdrop,
                isLightTheme = isLightTheme,
                dragWholeTrack = true,
                modifier = Modifier.fillMaxWidth()
            )
        } else {
            Slider(
                value = value.coerceIn(range.start, range.endInclusive),
                onValueChange = {
                    onInteractionChange(true)
                    onValueChange(it)
                },
                onValueChangeFinished = {
                    onInteractionChange(false)
                },
                valueRange = range,
                modifier = Modifier.fillMaxWidth()
            )
        }
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
