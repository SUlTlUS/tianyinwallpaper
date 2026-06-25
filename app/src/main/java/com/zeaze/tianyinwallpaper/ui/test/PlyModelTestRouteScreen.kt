package com.zeaze.tianyinwallpaper.ui.test

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.CircularProgressIndicator
import androidx.compose.material.Slider
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kyant.shapes.Capsule
import com.zeaze.tianyinwallpaper.ui.depth.SuperSplatWebView
import com.zeaze.tianyinwallpaper.utils.GaussianPlyLoader
import com.zeaze.tianyinwallpaper.utils.GaussianSceneLoader
import com.zeaze.tianyinwallpaper.utils.GaussianSogLoader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

@Composable
fun PlyModelTestRouteScreen(
    useDarkTheme: Boolean,
    onBack: (() -> Unit)? = null
) {
    val context = LocalContext.current
    val isLightTheme = !useDarkTheme
    val backgroundColor = if (isLightTheme) Color(0xFFF2F2F6) else Color(0xFF121212)
    val panelColor = if (isLightTheme) Color.White else Color(0xFF1E1E1E)
    val contentColor = if (isLightTheme) Color(0xFF1B1B1F) else Color(0xFFF1F1F1)
    val accentColor = Color(0xFF2A83FF)

    var selectedUri by remember { mutableStateOf<Uri?>(null) }
    var displayName by remember { mutableStateOf("") }
    var gaussianScene by remember { mutableStateOf<GaussianPlyLoader.GaussianScene?>(null) }
    var isLoading by remember { mutableStateOf(false) }
    var errorText by remember { mutableStateOf<String?>(null) }
    var maxSplats by remember { mutableStateOf(GaussianSogLoader.DEFAULT_MAX_SPLATS) }
    var maxSplatsSlider by remember { mutableStateOf(GaussianSogLoader.DEFAULT_MAX_SPLATS.toFloat()) }
    var rendererMode by remember { mutableStateOf(SogTestRendererMode.WEB) }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        runCatching {
            context.contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
        }
        selectedUri = uri
        displayName = queryDisplayName(context, uri).orEmpty()
    }

    LaunchedEffect(selectedUri, maxSplats) {
        val uri = selectedUri ?: return@LaunchedEffect
        isLoading = true
        errorText = null
        gaussianScene = null
        val result = withContext(Dispatchers.IO) {
            GaussianSceneLoader.loadSceneDetailed(
                context = context.applicationContext,
                uriString = uri.toString(),
                maxSplats = maxSplats,
                viewportAspect = SOG_PREVIEW_ASPECT
            )
        }
        isLoading = false
        if (result.scene != null) {
            gaussianScene = result.scene
        } else {
            errorText = result.error?.takeIf { it.isNotBlank() }
                ?: "SOG 读取或解析失败，请确认文件包含 Gaussian SOG 数据"
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(backgroundColor)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            if (onBack != null) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp)
                        .clickable { onBack() },
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("‹ 返回设置", color = contentColor, fontSize = 17.sp, fontWeight = FontWeight.Medium)
                }
            }
            Text(
                text = "Gaussian SOG 测试",
                style = TextStyle(
                    color = contentColor,
                    fontSize = 30.sp,
                    fontWeight = FontWeight.Bold
                )
            )

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp)
                    .clip(Capsule())
                    .background(accentColor)
                    .clickable { picker.launch(arrayOf("*/*")) },
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("选择 SOG 文件", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
            }

            SplatLimitCard(
                maxSplats = maxSplats,
                sliderValue = maxSplatsSlider,
                enabled = selectedUri != null && !isLoading,
                panelColor = panelColor,
                contentColor = contentColor,
                onSliderChange = { maxSplatsSlider = it },
                onCommit = {
                    val nextLimit = quantizeSplatLimit(maxSplatsSlider)
                    maxSplatsSlider = nextLimit.toFloat()
                    maxSplats = nextLimit
                }
            )

            RendererModeCard(
                selectedMode = rendererMode,
                panelColor = panelColor,
                contentColor = contentColor,
                onModeChange = { rendererMode = it }
            )

            if (isLoading) {
                LoadingCard(panelColor, contentColor, accentColor)
            }

            errorText?.let { text ->
                Text(
                    text = text,
                    color = Color(0xFFFF4D4F),
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(18.dp))
                        .background(panelColor)
                        .padding(16.dp),
                    textAlign = TextAlign.Center
                )
            }

            gaussianScene?.let { loadedScene ->
                GaussianPreviewCard(
                    scene = loadedScene,
                    uriString = selectedUri?.toString().orEmpty(),
                    rendererMode = rendererMode,
                    panelColor = panelColor,
                    contentColor = contentColor
                )

                SceneInfoCard(
                    fileName = displayName.ifBlank { selectedUri?.lastPathSegment.orEmpty() },
                    scene = loadedScene,
                    panelColor = panelColor,
                    contentColor = contentColor
                )
            }

            Spacer(Modifier.height(28.dp))
        }
    }
}

@Composable
private fun SplatLimitCard(
    maxSplats: Int,
    sliderValue: Float,
    enabled: Boolean,
    panelColor: Color,
    contentColor: Color,
    onSliderChange: (Float) -> Unit,
    onCommit: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(panelColor)
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("Max aux splats", color = contentColor, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
            Text(
                "${formatSplats(quantizeSplatLimit(sliderValue))} / ${formatSplats(maxSplats)}",
                color = contentColor.copy(alpha = 0.62f),
                fontSize = 13.sp
            )
        }
        Slider(
            value = sliderValue,
            onValueChange = onSliderChange,
            onValueChangeFinished = onCommit,
            valueRange = MIN_TEST_SPLATS.toFloat()..MAX_TEST_SPLATS.toFloat(),
            enabled = enabled,
            modifier = Modifier.fillMaxWidth()
        )
    }
}

@Composable
private fun RendererModeCard(
    selectedMode: SogTestRendererMode,
    panelColor: Color,
    contentColor: Color,
    onModeChange: (SogTestRendererMode) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(panelColor)
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Text("Renderer", color = contentColor, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            SogRendererModePill(
                text = SogTestRendererMode.WEB.label,
                selected = selectedMode == SogTestRendererMode.WEB,
                contentColor = contentColor,
                onClick = { onModeChange(SogTestRendererMode.WEB) },
                modifier = Modifier.weight(1f)
            )
        }
    }
}

@Composable
private fun SogRendererModePill(
    text: String,
    selected: Boolean,
    contentColor: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .height(34.dp)
            .clip(RoundedCornerShape(18.dp))
            .background(if (selected) Color(0xAA2A83FF) else Color(0x22000000))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = text,
            color = if (selected) Color.White else contentColor.copy(alpha = 0.76f),
            fontSize = 13.sp,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium
        )
    }
}

@Composable
private fun LoadingCard(
    panelColor: Color,
    contentColor: Color,
    accentColor: Color
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(24.dp))
            .background(panelColor)
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        CircularProgressIndicator(color = accentColor)
        Text("正在解析 SOG", color = contentColor.copy(alpha = 0.72f))
    }
}

@Composable
private fun GaussianPreviewCard(
    scene: GaussianPlyLoader.GaussianScene,
    uriString: String,
    rendererMode: SogTestRendererMode,
    panelColor: Color,
    contentColor: Color
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(24.dp))
            .background(panelColor)
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("Gaussian SOG Preview", color = contentColor, fontWeight = FontWeight.SemiBold)
            Text(rendererMode.label, color = contentColor.copy(alpha = 0.55f), fontSize = 12.sp)
        }

        WebGaussianPreview(
            uriString = uriString,
            contentColor = contentColor
        )
    }
}

@Composable
private fun WebGaussianPreview(
    uriString: String,
    contentColor: Color
) {
    if (uriString.isBlank()) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(SOG_PREVIEW_ASPECT)
                .clip(RoundedCornerShape(16.dp))
                .background(Color.Black),
            contentAlignment = Alignment.Center
        ) {
            Text("No SOG URI", color = contentColor.copy(alpha = 0.62f), fontSize = 13.sp)
        }
        return
    }
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(SOG_PREVIEW_ASPECT)
            .clip(RoundedCornerShape(16.dp))
            .background(Color.Black)
    ) {
        SuperSplatWebView(
            uriString = uriString,
            sensorSensitivity = 4.5f,
            parallaxStrength = 0.075f,
            cameraZoom = 1f,
            centerOffsetX = 0f,
            centerOffsetY = 0f,
            focusDepth = 0.25f,
            previewFps = 60,
            onCenterOffsetChange = { _, _ -> },
            modifier = Modifier.fillMaxSize()
        )
    }
}

@Composable
private fun SceneInfoCard(
    fileName: String,
    scene: GaussianPlyLoader.GaussianScene,
    panelColor: Color,
    contentColor: Color
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(24.dp))
            .background(panelColor)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(fileName.ifBlank { "未命名 SOG" }, color = contentColor, fontWeight = FontWeight.SemiBold)
        InfoLine("Splats", scene.count.toString(), contentColor)
        InfoLine("Visible / Aux", "${scene.screenVisibleSplatCount} / ${scene.auxiliarySplatCount}", contentColor)
        InfoLine("Image", "${scene.imageWidth} x ${scene.imageHeight}", contentColor)
        InfoLine("Focal", String.format("%.2f px", scene.focalLengthPx), contentColor)
        InfoLine("Depth", "${fmt(scene.nearDepth)} / ${fmt(scene.focusDepth)} / ${fmt(scene.farDepth)}", contentColor)
        InfoLine(
            "Anchor",
            "${fmt(scene.parallaxAnchorDepth)} (${scene.parallaxAnchorSplatCount}/${scene.parallaxAnchorMinSplatCount})",
            contentColor
        )
        InfoLine(
            "Background",
            "${fmt(scene.backgroundR)}, ${fmt(scene.backgroundG)}, ${fmt(scene.backgroundB)}",
            contentColor
        )
    }
}

@Composable
private fun InfoLine(
    label: String,
    value: String,
    contentColor: Color
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, color = contentColor.copy(alpha = 0.58f), fontSize = 13.sp)
        Text(value, color = contentColor, fontSize = 13.sp, fontWeight = FontWeight.Medium)
    }
}

private fun fmt(value: Float): String = String.format("%.3f", value)

private fun quantizeSplatLimit(value: Float): Int {
    return ((value / SPLAT_LIMIT_STEP).roundToInt() * SPLAT_LIMIT_STEP)
        .coerceIn(MIN_TEST_SPLATS, MAX_TEST_SPLATS)
}

private fun formatSplats(value: Int): String {
    return if (value >= 1_000_000) {
        String.format("%.2fM", value / 1_000_000f)
    } else {
        "${value / 1_000}K"
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

private enum class SogTestRendererMode(val label: String) {
    WEB("WebView")
}

private const val MIN_TEST_SPLATS = 60_000
private const val MAX_TEST_SPLATS = 1_200_000
private const val SPLAT_LIMIT_STEP = 10_000
private const val SOG_PREVIEW_ASPECT = 9f / 16f
