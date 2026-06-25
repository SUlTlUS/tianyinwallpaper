package com.zeaze.tianyinwallpaper.ui.depth

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.material.CircularProgressIndicator
import androidx.compose.material.Icon
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.blur as composeBlur
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kyant.shapes.Capsule
import com.kyant.shapes.RoundedRectangle
import com.zeaze.tianyinwallpaper.R
import com.zeaze.tianyinwallpaper.backdrop.Backdrop
import com.zeaze.tianyinwallpaper.backdrop.backdrops.rememberLayerBackdrop
import com.zeaze.tianyinwallpaper.backdrop.backdrops.layerBackdrop
import com.zeaze.tianyinwallpaper.backdrop.drawBackdrop
import com.zeaze.tianyinwallpaper.backdrop.effects.blur
import com.zeaze.tianyinwallpaper.backdrop.effects.colorControls
import com.zeaze.tianyinwallpaper.backdrop.effects.lens
import com.zeaze.tianyinwallpaper.backdrop.highlight.Highlight
import com.zeaze.tianyinwallpaper.catalog.components.LiquidButton
import com.zeaze.tianyinwallpaper.catalog.components.LiquidSlider
import com.zeaze.tianyinwallpaper.catalog.components.PlainSlider
import com.zeaze.tianyinwallpaper.catalog.utils.rememberMultiRegionLuminanceSampler
import com.zeaze.tianyinwallpaper.catalog.utils.rememberRegionLuminanceState
import com.zeaze.tianyinwallpaper.model.DepthWallpaperModel
import com.zeaze.tianyinwallpaper.ui.commom.LiquidWindowAnimatedVisibility
import com.zeaze.tianyinwallpaper.ui.commom.LiquidWindowAnimationMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

private const val DEPTH_LOADING_FADE_DURATION_MS = 300
private const val DEPTH_LOADING_COMPLETED_HOLD_MS = 400

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
    val context = LocalContext.current
    val density = LocalDensity.current
    val isLightTheme = MaterialTheme.colors.isLight
    val pillBackground = if (MaterialTheme.colors.isLight) Color(0x22FFFFFF) else Color(0x55222222)
    val applyIconColor = if (accentColor.luminance() > 0.5f) Color.Black else Color.White
    val shouldCaptureWebBackdrop = enableLiquidGlass && model.isGaussian() && model.useWebGaussianRenderer()
    var webBackdropFrame by remember(model.id, model.gaussianUri, model.gaussianRenderMode) {
        mutableStateOf<ImageBitmap?>(null)
    }
    val currentWebBackdropFrame = rememberUpdatedState(webBackdropFrame)
    val currentShouldCaptureWebBackdrop = rememberUpdatedState(shouldCaptureWebBackdrop)
    val detailBackdrop = if (enableLiquidGlass) {
        rememberLayerBackdrop(
            onDraw = remember {
                {
                    drawContent()
                    if (currentShouldCaptureWebBackdrop.value) {
                        val frame = currentWebBackdropFrame.value
                        if (frame != null) {
                            drawImage(
                                image = frame,
                                dstSize = IntSize(
                                    size.width.roundToInt().coerceAtLeast(1),
                                    size.height.roundToInt().coerceAtLeast(1)
                                )
                            )
                        } else {
                            drawRect(Color.Black)
                        }
                    }
                }
            }
        )
    } else {
        null
    }
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
    val cachedLoadingThumbnail = remember(model.id, model.gaussianUri) {
        gaussianThumbnailCacheFile(context.applicationContext, model)
            ?.takeIf { it.exists() }
            ?.let { file ->
                BitmapFactory.decodeFile(file.absolutePath, BitmapFactory.Options().apply {
                    inPreferredConfig = Bitmap.Config.RGB_565
                })
            }
    }
    val loadingThumbnail by produceState<Bitmap?>(
        initialValue = cachedLoadingThumbnail,
        model.id,
        model.gaussianUri
    ) {
        if (cachedLoadingThumbnail != null) return@produceState
        value = withContext(Dispatchers.IO) {
            loadOrGenerateGaussianThumbnail(
                context = context.applicationContext,
                model = model,
                width = THUMBNAIL_WIDTH,
                height = THUMBNAIL_HEIGHT
            )
        }
    }
    var loadingOverlayVisible by remember(model.id, model.gaussianUri) { mutableStateOf(true) }
    var sensorInputEnabled by remember(model.id, model.gaussianUri) { mutableStateOf(false) }
    LaunchedEffect(previewLoading) {
        if (previewLoading) {
            loadingOverlayVisible = true
            sensorInputEnabled = false
        } else {
            delay(DEPTH_LOADING_COMPLETED_HOLD_MS.toLong())
            loadingOverlayVisible = false
            delay(DEPTH_LOADING_FADE_DURATION_MS.toLong())
            sensorInputEnabled = true
        }
    }
    var showParamPanel by remember(model.id) { mutableStateOf(false) }
    var cameraResetKey by remember(model.id) { mutableStateOf(0) }

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
                cameraResetKey = cameraResetKey,
                onModelChange = onModelChange,
                onLoadingChanged = { previewLoading = it },
                webBackdropCaptureEnabled = shouldCaptureWebBackdrop,
                onWebBackdropFrame = { webBackdropFrame = it },
                sensorInputEnabled = sensorInputEnabled,
                modifier = Modifier.fillMaxSize()
            )
            AnimatedVisibility(
                visible = loadingOverlayVisible,
                enter = EnterTransition.None,
                exit = fadeOut(animationSpec = tween(DEPTH_LOADING_FADE_DURATION_MS)),
                modifier = Modifier.fillMaxSize()
            ) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    loadingThumbnail?.let { thumbnail ->
                        Image(
                            bitmap = thumbnail.asImageBitmap(),
                            contentDescription = null,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier
                                .fillMaxSize()
                                .composeBlur(24.dp)
                                .graphicsLayer {
                                    scaleX = 1.08f
                                    scaleY = 1.08f
                                }
                        )
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .background(Color.Black.copy(alpha = 0.18f))
                        )
                    }
                    Column(
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
                    modifier = Modifier.size(44.dp),
                    buttonHeight = 44.dp,
                    contentPadding = PaddingValues(0.dp),
                    iconRes = R.drawable.back,
                    iconContentDescription = "取消",
                    iconSize = 18.dp,
                    iconTint = cancelLuminanceState?.contentColor ?: contentColor
                )
                LiquidButton(
                    onClick = { if (!previewLoading) onApply() },
                    backdrop = detailBackdrop,
                    surfaceColor = accentColor,
                    luminanceState = applyLuminanceState,
                    modifier = Modifier
                        .size(44.dp)
                        .graphicsLayer { alpha = if (previewLoading) 0.5f else 1f },
                    buttonHeight = 44.dp,
                    contentPadding = PaddingValues(0.dp),
                    iconRes = R.drawable.complete,
                    iconContentDescription = "应用",
                    iconSize = 18.dp,
                    iconTint = applyIconColor
                )
            } else {
                Row(
                    modifier = Modifier
                        .size(44.dp)
                        .clip(CircleShape)
                        .background(pillBackground)
                        .clickable { closeWithAnimation() },
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        painter = painterResource(R.drawable.back),
                        contentDescription = "取消",
                        modifier = Modifier.width(18.dp).height(18.dp),
                        tint = contentColor
                    )
                }
                Row(
                    modifier = Modifier
                        .size(44.dp)
                        .clip(CircleShape)
                        .background(accentColor)
                        .clickable { if (!previewLoading) onApply() }
                        .graphicsLayer { alpha = if (previewLoading) 0.5f else 1f },
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        painter = painterResource(R.drawable.complete),
                        contentDescription = "应用",
                        modifier = Modifier.width(18.dp).height(18.dp),
                        tint = applyIconColor
                    )
                }
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
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null
                    ) { closeParamPanel() }
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
                onResetCamera = { cameraResetKey += 1 },
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
    }
}

@Composable
private fun DepthParamPanel(
    model: DepthWallpaperModel,
    onModelChange: (DepthWallpaperModel) -> Unit,
    onResetCamera: () -> Unit,
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
    val defaultParams = remember { DepthWallpaperModel() }
    val scrollState = rememberScrollState()
    val controlBackdrop = if (backdrop != null) rememberLayerBackdrop() else null
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
                        exportedBackdrop = controlBackdrop,
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
            "景深",
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
                backdrop = controlBackdrop,
                isLightTheme = isLightTheme,
                contentColor = contentColor,
                visibilityThreshold = 0.1f,
                onInteractionChange = { isSliderInteracting = it }
            )
            DepthParamSlider(
                label = "视差强度",
                value = model.parallaxStrength,
                valueText = String.format("%.3f", model.parallaxStrength),
                range = 0.03f..0.16f,
                onValueChange = { onModelChange(model.copy(parallaxStrength = it)) },
                backdrop = controlBackdrop,
                isLightTheme = isLightTheme,
                contentColor = contentColor,
                visibilityThreshold = 0.001f,
                onInteractionChange = { isSliderInteracting = it }
            )
            if (model.isGaussian()) {
                val displayedFocusDepth = model.focusDepth.takeIf { it > 1f }
                    ?: DepthWallpaperModel.DEFAULT_SOG_FOCUS_DEPTH
                DepthParamSlider(
                    label = "视角",
                    value = model.cameraFov,
                    valueText = "${model.cameraFov.roundToInt()}°",
                    range = 20f..120f,
                    onValueChange = { onModelChange(model.copy(cameraFov = it)) },
                    backdrop = controlBackdrop,
                    isLightTheme = isLightTheme,
                    contentColor = contentColor,
                    visibilityThreshold = 1f,
                    onInteractionChange = { isSliderInteracting = it }
                )
                DepthParamSlider(
                    label = "注视深度",
                    value = displayedFocusDepth,
                    valueText = "${displayedFocusDepth.roundToInt()}%",
                    range = 0f..100f,
                    onValueChange = { onModelChange(model.copy(focusDepth = it)) },
                    backdrop = controlBackdrop,
                    isLightTheme = isLightTheme,
                    contentColor = contentColor,
                    visibilityThreshold = 0.01f,
                    onInteractionChange = { isSliderInteracting = it }
                )
                Spacer(Modifier.height(12.dp))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp)
                        .clip(Capsule())
                        .background(containerColor.copy(alpha = 0.2f))
                        .clickable {
                            onModelChange(
                                model.copy(
                                    sensorSensitivity = defaultParams.sensorSensitivity,
                                    parallaxStrength = defaultParams.parallaxStrength,
                                    cameraZoom = model.cameraDefaultDistance.takeIf { it > 0f }
                                        ?: defaultParams.cameraZoom,
                                    centerOffsetX = defaultParams.centerOffsetX,
                                    centerOffsetY = defaultParams.centerOffsetY,
                                    focusDepth = defaultParams.focusDepth,
                                    cameraFov = model.cameraDefaultFov.takeIf { it > 0f }
                                        ?: defaultParams.cameraFov
                                )
                            )
                            onResetCamera()
                        },
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    BasicText("重置", style = TextStyle(contentColor, 16.sp))
                }
            }
        }
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
            PlainSlider(
                value = value.coerceIn(range.start, range.endInclusive),
                onValueChange = {
                    onInteractionChange(true)
                    onValueChange(it)
                },
                onValueChangeFinished = {
                    onInteractionChange(false)
                },
                valueRange = range,
                contentColor = contentColor,
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
