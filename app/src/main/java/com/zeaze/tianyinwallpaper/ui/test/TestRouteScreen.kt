package com.zeaze.tianyinwallpaper.ui.test

import android.net.Uri
import android.os.Build
import android.graphics.BitmapFactory
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Slider
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.geometry.Offset
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.ui.input.pointer.pointerInput
import kotlin.math.roundToInt
import com.zeaze.tianyinwallpaper.backdrop.backdrops.layerBackdrop
import com.zeaze.tianyinwallpaper.backdrop.backdrops.rememberCanvasBackdrop
import com.zeaze.tianyinwallpaper.backdrop.backdrops.rememberLayerBackdrop
import com.zeaze.tianyinwallpaper.catalog.components.GlassProfile
import com.zeaze.tianyinwallpaper.catalog.components.StripedDirection
import com.zeaze.tianyinwallpaper.catalog.components.StripedGlass
import com.zeaze.tianyinwallpaper.catalog.components.rememberStripedPhase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
fun CorrugatedTestRouteScreen(
    useDarkTheme: Boolean
) {
    val context = LocalContext.current
    val isLightTheme = !useDarkTheme
    val backgroundColor = if (isLightTheme) Color(0xFFF2F2F6) else Color(0xFF121212)
    val panelColor = if (isLightTheme) Color.White.copy(alpha = 0.9f) else Color(0xFF1E1E1E).copy(alpha = 0.9f)
    val textColor = if (isLightTheme) Color(0xFF1B1B1F) else Color(0xFFF0F0F0)

    var amplitude by remember { mutableFloatStateOf(7f) }
    var wavelength by remember { mutableFloatStateOf(42f) }
    var blurRadius by remember { mutableFloatStateOf(8f) }
    var animateWave by remember { mutableStateOf(true) }
    var reverseAnimation by remember { mutableStateOf(false) }
    var direction by remember { mutableStateOf(StripedDirection.Horizontal) }
    var profile by remember { mutableStateOf(GlassProfile.Prism) }
    var phasePeriodMs by remember { mutableFloatStateOf(2400f) }
    var highlightStrength by remember { mutableFloatStateOf(0.2f) }
    var shadowStrength by remember { mutableFloatStateOf(0.1f) }
    var narrowAmplitude by remember { mutableFloatStateOf(6f) }
    var narrowWavelength by remember { mutableFloatStateOf(12f) }
    var colorSaturation by remember { mutableFloatStateOf(1.2f) }
    var colorContrast by remember { mutableFloatStateOf(1.1f) }
    var glassOffset by remember { mutableStateOf(Offset.Zero) }

    var glassWidthDp by remember { mutableFloatStateOf(300f) }
    var glassHeightDp by remember { mutableFloatStateOf(240f) }
    val density = LocalDensity.current

    var isPanelExpanded by remember { mutableStateOf(true) }
    var selectedImageUri by remember { mutableStateOf<Uri?>(null) }
    var imageBitmap by remember { mutableStateOf<ImageBitmap?>(null) }

    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
            try {
                val inputStream = context.assets.open("test.png")
                val bitmap = BitmapFactory.decodeStream(inputStream)
                inputStream.close()
                if (bitmap != null) {
                    imageBitmap = bitmap.asImageBitmap()
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        if (uri != null) {
            selectedImageUri = uri
        }
    }

    LaunchedEffect(selectedImageUri) {
        if (selectedImageUri != null) {
            withContext(Dispatchers.IO) {
                try {
                    val inputStream = context.contentResolver.openInputStream(selectedImageUri!!)
                    val bitmap = BitmapFactory.decodeStream(inputStream)
                    inputStream?.close()
                    if (bitmap != null) {
                        imageBitmap = bitmap.asImageBitmap()
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
        }
    }

    val phase = rememberStripedPhase(
        enabled = animateWave,
        periodMillis = phasePeriodMs.toInt().coerceAtLeast(600),
        isReverse = reverseAnimation
    )

    val stageBackdrop = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        rememberLayerBackdrop()
    } else {
        null
    }

    val backdrop = stageBackdrop ?: rememberCanvasBackdrop {
        if (imageBitmap != null) {
            val bmp = imageBitmap!!
            val srcRatio = bmp.width.toFloat() / bmp.height
            val dstRatio = size.width / size.height
            var srcWidth = bmp.width.toFloat()
            var srcHeight = bmp.height.toFloat()
            var srcX = 0f
            var srcY = 0f
            if (srcRatio > dstRatio) {
                srcWidth = srcHeight * dstRatio
                srcX = (bmp.width - srcWidth) / 2f
            } else {
                srcHeight = srcWidth / dstRatio
                srcY = (bmp.height - srcHeight) / 2f
            }
            drawImage(
                image = bmp,
                srcOffset = IntOffset(srcX.toInt(), srcY.toInt()),
                srcSize = IntSize(srcWidth.toInt(), srcHeight.toInt()),
                dstSize = IntSize(size.width.toInt(), size.height.toInt())
            )
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(backgroundColor)
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .let { base -> if (stageBackdrop != null) base.layerBackdrop(stageBackdrop) else base }
        ) {
            if (imageBitmap != null) {
                Image(
                    bitmap = imageBitmap!!,
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop
                )
            }
        }

        StripedGlass(
            backdrop = backdrop,
            shape = RoundedCornerShape(30.dp),
            profile = profile,
            amplitude = amplitude.dp,
            wavelength = wavelength.dp,
            narrowAmplitude = narrowAmplitude.dp,
            narrowWavelength = narrowWavelength.dp,
            highlightStrength = highlightStrength,
            shadowStrength = shadowStrength,
            colorSaturation = colorSaturation,
            colorContrast = colorContrast,
            phase = phase,
            direction = direction,
            blurRadius = blurRadius.dp,
            tint = Color.White.copy(alpha = if (isLightTheme) 0.14f else 0.1f),
            modifier = Modifier
                .align(Alignment.Center)
                .offset {
                    IntOffset(glassOffset.x.roundToInt(), glassOffset.y.roundToInt())
                }
                .pointerInput(Unit) {
                    detectDragGestures { change, dragAmount ->
                        change.consume()
                        glassOffset += dragAmount
                    }
                }
                .width(glassWidthDp.dp)
                .height(glassHeightDp.dp)
        ) {
            Box(Modifier.fillMaxSize()) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(20.dp),
                    contentAlignment = Alignment.BottomStart
                ) {

                }

                Box(
                    modifier = Modifier
                        .align(Alignment.CenterEnd)
                        .width(32.dp)
                        .fillMaxHeight()
                        .pointerInput(Unit) {
                            detectDragGestures { change, dragAmount ->
                                change.consume()
                                val dragDp = with(density) { dragAmount.x.toDp().value }
                                val newWidth = (glassWidthDp + dragDp).coerceAtLeast(100f)
                                val actualDeltaDp = newWidth - glassWidthDp
                                val actualDeltaPx = with(density) { actualDeltaDp.dp.toPx() }
                                glassWidthDp = newWidth
                                glassOffset = Offset(glassOffset.x + actualDeltaPx / 2f, glassOffset.y)
                            }
                        },
                    contentAlignment = Alignment.Center
                ) {
                    Box(modifier = Modifier.width(4.dp).height(24.dp).clip(RoundedCornerShape(2.dp)).background(Color.White.copy(alpha = 0.5f)))
                }

                Box(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .height(32.dp)
                        .fillMaxWidth()
                        .pointerInput(Unit) {
                            detectDragGestures { change, dragAmount ->
                                change.consume()
                                val dragDp = with(density) { dragAmount.y.toDp().value }
                                val newHeight = (glassHeightDp + dragDp).coerceAtLeast(100f)
                                val actualDeltaDp = newHeight - glassHeightDp
                                val actualDeltaPx = with(density) { actualDeltaDp.dp.toPx() }
                                glassHeightDp = newHeight
                                glassOffset = Offset(glassOffset.x, glassOffset.y + actualDeltaPx / 2f)
                            }
                        },
                    contentAlignment = Alignment.Center
                ) {
                    Box(modifier = Modifier.width(24.dp).height(4.dp).clip(RoundedCornerShape(2.dp)).background(Color.White.copy(alpha = 0.5f)))
                }
            }
        }

        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(16.dp)
                .clip(RoundedCornerShape(24.dp))
                .background(panelColor)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("玻璃测试", color = textColor, style = MaterialTheme.typography.subtitle1, fontWeight = FontWeight.SemiBold)
                Text(
                    text = if (isPanelExpanded) "收起" else "展开",
                    color = Color(0xFF3B82F6),
                    modifier = Modifier.clickable { isPanelExpanded = !isPanelExpanded }.padding(4.dp)
                )
            }

            AnimatedVisibility(visible = isPanelExpanded) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 350.dp)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .width(72.dp)
                                .height(40.dp)
                                .clip(RoundedCornerShape(12.dp))
                                .background(Color.Gray.copy(alpha = 0.3f))
                                .clickable {
                                    launcher.launch("image/*")
                                },
                            contentAlignment = Alignment.Center
                        ) {
                            Text("选图", color = textColor, fontSize = 12.sp)
                        }
                    }

                    Text("振幅: ${amplitude.toInt()}dp", color = textColor)
                    Slider(value = amplitude, onValueChange = { amplitude = it }, valueRange = 0f..18f)

                    Text("波长: ${wavelength.toInt()}dp", color = textColor)
                    Slider(value = wavelength, onValueChange = { wavelength = it }, valueRange = 12f..120f)

                    if (profile == GlassProfile.Prism) {
                        Text("窄波振幅: ${narrowAmplitude.toInt()}dp", color = textColor)
                        Slider(value = narrowAmplitude, onValueChange = { narrowAmplitude = it }, valueRange = 0f..18f)

                        Text("窄波长: ${narrowWavelength.toInt()}dp", color = textColor)
                        Slider(value = narrowWavelength, onValueChange = { narrowWavelength = it }, valueRange = 2f..120f)
                    }

                    Text("高光: ${String.format(java.util.Locale.US, "%.1f", highlightStrength).toDouble()}", color = textColor)
                    Slider(value = highlightStrength, onValueChange = { highlightStrength = it }, valueRange = 0f..1f)

                    Text("阴影: ${String.format(java.util.Locale.US, "%.1f", shadowStrength).toDouble()}", color = textColor)
                    Slider(value = shadowStrength, onValueChange = { shadowStrength = it }, valueRange = 0f..1f)

                    Text("饱和度: ${String.format(java.util.Locale.US, "%.1f", colorSaturation).toDouble()}", color = textColor)
                    Slider(value = colorSaturation, onValueChange = { colorSaturation = it }, valueRange = 0f..3f)

                    Text("对比度: ${String.format(java.util.Locale.US, "%.1f", colorContrast).toDouble()}", color = textColor)
                    Slider(value = colorContrast, onValueChange = { colorContrast = it }, valueRange = 0f..3f)

                    Text("模糊: ${blurRadius.toInt()}dp", color = textColor)
                    Slider(value = blurRadius, onValueChange = { blurRadius = it }, valueRange = 0f..18f)

                    if (animateWave) {
                        Text("动画周期: ${phasePeriodMs.toInt()}ms", color = textColor)
                        Slider(value = phasePeriodMs, onValueChange = { phasePeriodMs = it }, valueRange = 600f..6000f)
                    }

                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        ToggleChip(
                            text = "条纹",
                            selected = profile == GlassProfile.Reeded,
                            onClick = { profile = GlassProfile.Reeded }
                        )
                        ToggleChip(
                            text = "波浪",
                            selected = profile == GlassProfile.Corrugated,
                            onClick = { profile = GlassProfile.Corrugated }
                        )
                        ToggleChip(
                            text = "棱镜",
                            selected = profile == GlassProfile.Prism,
                            onClick = { profile = GlassProfile.Prism }
                        )
                    }

                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        ToggleChip(
                            text = "横向",
                            selected = direction == StripedDirection.Horizontal,
                            onClick = { direction = StripedDirection.Horizontal }
                        )
                        ToggleChip(
                            text = "纵向",
                            selected = direction == StripedDirection.Vertical,
                            onClick = { direction = StripedDirection.Vertical }
                        )
                        ToggleChip(
                            text = "反向",
                            selected = reverseAnimation,
                            onClick = { reverseAnimation = !reverseAnimation }
                        )
                        ToggleChip(
                            text = "动画",
                            selected = animateWave,
                            onClick = { animateWave = !animateWave }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ToggleChip(
    text: String,
    selected: Boolean,
    onClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(999.dp))
            .background(if (selected) Color(0xFF3B82F6) else Color.Gray.copy(alpha = 0.2f))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 8.dp)
    ) {
        Text(text = text, color = Color.White)
    }
}
