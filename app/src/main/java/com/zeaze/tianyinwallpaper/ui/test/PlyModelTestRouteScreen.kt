package com.zeaze.tianyinwallpaper.ui.test

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.kyant.shapes.Capsule
import com.zeaze.tianyinwallpaper.renderer.DepthGLRenderer
import com.zeaze.tianyinwallpaper.utils.GaussianPlyLoader
import com.zeaze.tianyinwallpaper.utils.GaussianSceneLoader
import com.zeaze.tianyinwallpaper.utils.PhotoMeshPlyLoader
import java.io.InputStreamReader
import java.nio.charset.StandardCharsets
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

@Composable
fun PlyModelTestRouteScreen(
    useDarkTheme: Boolean
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
    var meshScene by remember { mutableStateOf<PhotoMeshPlyLoader.MeshScene?>(null) }
    var isLoading by remember { mutableStateOf(false) }
    var errorText by remember { mutableStateOf<String?>(null) }
    var maxSplats by remember { mutableStateOf(GaussianPlyLoader.DEFAULT_MAX_SPLATS) }
    var maxSplatsSlider by remember { mutableStateOf(GaussianPlyLoader.DEFAULT_MAX_SPLATS.toFloat()) }
    var maxFaces by remember { mutableStateOf(PhotoMeshPlyLoader.DEFAULT_MAX_FACES) }
    var maxFacesSlider by remember { mutableStateOf(PhotoMeshPlyLoader.DEFAULT_MAX_FACES.toFloat()) }

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

    LaunchedEffect(selectedUri, maxSplats, maxFaces) {
        val uri = selectedUri ?: return@LaunchedEffect
        isLoading = true
        errorText = null
        gaussianScene = null
        meshScene = null
        val loadAttempt = withContext(Dispatchers.IO) {
            when (detectPlyKind(context.applicationContext, uri)) {
                PlyKind.Mesh -> LoadAttempt(PhotoMeshPlyLoader.loadScene(
                    context = context.applicationContext,
                    uriString = uri.toString(),
                    maxFaces = maxFaces
                )?.let { PlyLoadResult.Mesh(it) })
                PlyKind.Gaussian -> LoadAttempt(null, "Gaussian PLY 已移除，请先转换为 SOG")
                PlyKind.Sog -> GaussianSceneLoader.loadSceneDetailed(
                    context = context.applicationContext,
                    uriString = uri.toString(),
                    maxSplats = maxSplats,
                    viewportAspect = PLY_PREVIEW_ASPECT
                ).let { result ->
                    LoadAttempt(result.scene?.let { PlyLoadResult.Gaussian(it) }, result.error)
                }
                PlyKind.Unknown -> {
                    PhotoMeshPlyLoader.loadScene(
                        context = context.applicationContext,
                        uriString = uri.toString(),
                        maxFaces = maxFaces
                    )?.let { LoadAttempt(PlyLoadResult.Mesh(it)) }
                        ?: LoadAttempt(null, "未识别为 SOG 或 Mesh PLY")
                }
            }
        }
        isLoading = false
        val loaded = loadAttempt.result
        if (loaded == null) {
            errorText = "SOG/Mesh 读取或解析失败，请确认文件包含 SOG 或 SHARP mesh 数据"
            errorText = loadAttempt.error?.takeIf { it.isNotBlank() } ?: errorText
        } else {
            when (loaded) {
                is PlyLoadResult.Gaussian -> gaussianScene = loaded.scene
                is PlyLoadResult.Mesh -> meshScene = loaded.scene
            }
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

            MeshFaceLimitCard(
                maxFaces = maxFaces,
                sliderValue = maxFacesSlider,
                enabled = selectedUri != null && !isLoading,
                panelColor = panelColor,
                contentColor = contentColor,
                onSliderChange = { maxFacesSlider = it },
                onCommit = {
                    val nextLimit = quantizeFaceLimit(maxFacesSlider)
                    maxFacesSlider = nextLimit.toFloat()
                    maxFaces = nextLimit
                }
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

            meshScene?.let { loadedScene ->
                MeshPreviewCard(
                    scene = loadedScene,
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
private fun MeshFaceLimitCard(
    maxFaces: Int,
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
            Text("Max mesh faces", color = contentColor, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
            Text(
                "${formatFaces(quantizeFaceLimit(sliderValue))} / ${formatFaces(maxFaces)}",
                color = contentColor.copy(alpha = 0.62f),
                fontSize = 13.sp
            )
        }
        Slider(
            value = sliderValue,
            onValueChange = onSliderChange,
            onValueChangeFinished = onCommit,
            valueRange = MIN_TEST_FACES.toFloat()..MAX_TEST_FACES.toFloat(),
            enabled = enabled,
            modifier = Modifier.fillMaxWidth()
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
    panelColor: Color,
    contentColor: Color
) {
    val renderer = remember { DepthGLRenderer() }
    val previewTilt = remember { FloatArray(2) }
    var splatScale by remember { mutableStateOf(1f) }
    var globalOpacity by remember { mutableStateOf(1f) }
    var alphaFalloff by remember { mutableStateOf(1f) }
    var minPointSize by remember { mutableStateOf(0.5f) }
    var maxPointSize by remember { mutableStateOf(160f) }

    fun currentGaussianParams(): DepthGLRenderer.GaussianRenderParams {
        return DepthGLRenderer.GaussianRenderParams(
            splatScale = splatScale,
            globalOpacity = globalOpacity,
            alphaFalloff = alphaFalloff,
            minPointSize = minPointSize,
            maxPointSize = maxPointSize
        )
    }

    fun applyGaussianParams() {
        renderer.updateGaussianParams(currentGaussianParams())
    }

    DisposableEffect(Unit) {
        onDispose {
            renderer.stopAndWait(300)
        }
    }

    LaunchedEffect(scene) {
        renderer.loadGaussians(scene)
        applyGaussianParams()
        renderer.updateTilt(previewTilt[0], previewTilt[1])
    }

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
            Text("Gaussian SOG 预览", color = contentColor, fontWeight = FontWeight.SemiBold)
            Text(
                text = "拖动预览区域测试视差",
                color = contentColor.copy(alpha = 0.55f),
                fontSize = 12.sp
            )
        }

        AndroidView(
            factory = { viewContext ->
                SurfaceView(viewContext).apply {
                    holder.addCallback(object : SurfaceHolder.Callback {
                        override fun surfaceCreated(holder: SurfaceHolder) = Unit

                        override fun surfaceChanged(
                            holder: SurfaceHolder,
                            format: Int,
                            width: Int,
                            height: Int
                        ) {
                            renderer.start(holder.surface)
                            renderer.resize(width, height)
                            renderer.loadGaussians(scene)
                            renderer.updateParams(0.075f, 0f)
                            applyGaussianParams()
                            renderer.updateTilt(previewTilt[0], previewTilt[1])
                        }

                        override fun surfaceDestroyed(holder: SurfaceHolder) {
                            renderer.stopAndWait(300)
                        }
                    })
                }
            },
            update = { view ->
                if (view.holder.surface.isValid && view.width > 0 && view.height > 0) {
                    renderer.resize(view.width, view.height)
                    applyGaussianParams()
                    renderer.updateTilt(previewTilt[0], previewTilt[1])
                }
            },
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(9f / 16f)
                .clip(RoundedCornerShape(16.dp))
                .background(Color.Black)
                .pointerInput(scene) {
                    detectDragGestures { change, dragAmount ->
                        change.consume()
                        previewTilt[0] = (previewTilt[0] + dragAmount.x / 360f).coerceIn(-1f, 1f)
                        previewTilt[1] = (previewTilt[1] + dragAmount.y / 520f).coerceIn(-1f, 1f)
                        renderer.updateTilt(previewTilt[0], previewTilt[1])
                    }
                }
        )

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(18.dp))
                .background(contentColor.copy(alpha = 0.07f))
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            GaussianParamSlider(
                label = "Splat scale",
                value = splatScale,
                valueRange = 0.35f..3.0f,
                contentColor = contentColor,
                onValueChange = {
                    splatScale = it
                },
                onValueChangeFinished = { applyGaussianParams() }
            )
            GaussianParamSlider(
                label = "Global opacity",
                value = globalOpacity,
                valueRange = 0.2f..3.0f,
                contentColor = contentColor,
                onValueChange = {
                    globalOpacity = it
                },
                onValueChangeFinished = { applyGaussianParams() }
            )
            GaussianParamSlider(
                label = "Alpha falloff",
                value = alphaFalloff,
                valueRange = 1.4f..9.0f,
                contentColor = contentColor,
                onValueChange = {
                    alphaFalloff = it
                },
                onValueChangeFinished = { applyGaussianParams() }
            )
            GaussianParamSlider(
                label = "Min point size",
                value = minPointSize,
                valueRange = 0.5f..8.0f,
                contentColor = contentColor,
                onValueChange = {
                    minPointSize = it.coerceAtMost(maxPointSize)
                },
                onValueChangeFinished = { applyGaussianParams() }
            )
            GaussianParamSlider(
                label = "Max point size",
                value = maxPointSize,
                valueRange = 24f..160f,
                contentColor = contentColor,
                onValueChange = {
                    maxPointSize = it.coerceAtLeast(minPointSize)
                },
                onValueChangeFinished = { applyGaussianParams() }
            )
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(42.dp)
                .clip(Capsule())
                .background(contentColor.copy(alpha = 0.10f))
                .clickable {
                    previewTilt[0] = 0f
                    previewTilt[1] = 0f
                    splatScale = 3f
                    globalOpacity = 3f
                    alphaFalloff = 9f
                    minPointSize = 8f
                    maxPointSize = 160f
                    applyGaussianParams()
                    renderer.updateTilt(0f, 0f)
                },
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("重置预览参数", color = contentColor, fontSize = 14.sp, fontWeight = FontWeight.Medium)
        }
    }
}

@Composable
private fun MeshPreviewCard(
    scene: PhotoMeshPlyLoader.MeshScene,
    panelColor: Color,
    contentColor: Color
) {
    val renderer = remember { DepthGLRenderer() }
    val previewTilt = remember { FloatArray(2) }
    var parallaxStrength by remember { mutableStateOf(0.045f) }

    fun applyParams() {
        renderer.updateParams(parallaxStrength, 0f)
    }

    DisposableEffect(Unit) {
        onDispose {
            renderer.stopAndWait(300)
        }
    }

    LaunchedEffect(scene) {
        renderer.loadMesh(scene)
        applyParams()
        renderer.updateTilt(previewTilt[0], previewTilt[1])
    }

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
            Text("Mesh preview", color = contentColor, fontWeight = FontWeight.SemiBold)
            Text(
                text = "Drag to test parallax",
                color = contentColor.copy(alpha = 0.55f),
                fontSize = 12.sp
            )
        }

        AndroidView(
            factory = { viewContext ->
                SurfaceView(viewContext).apply {
                    holder.addCallback(object : SurfaceHolder.Callback {
                        override fun surfaceCreated(holder: SurfaceHolder) = Unit

                        override fun surfaceChanged(
                            holder: SurfaceHolder,
                            format: Int,
                            width: Int,
                            height: Int
                        ) {
                            renderer.start(holder.surface)
                            renderer.resize(width, height)
                            renderer.loadMesh(scene)
                            applyParams()
                            renderer.updateTilt(previewTilt[0], previewTilt[1])
                        }

                        override fun surfaceDestroyed(holder: SurfaceHolder) {
                            renderer.stopAndWait(300)
                        }
                    })
                }
            },
            update = { view ->
                if (view.holder.surface.isValid && view.width > 0 && view.height > 0) {
                    renderer.resize(view.width, view.height)
                    applyParams()
                    renderer.updateTilt(previewTilt[0], previewTilt[1])
                }
            },
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(9f / 16f)
                .clip(RoundedCornerShape(16.dp))
                .background(Color.Black)
                .pointerInput(scene) {
                    detectDragGestures { change, dragAmount ->
                        change.consume()
                        previewTilt[0] = (previewTilt[0] + dragAmount.x / 360f).coerceIn(-1f, 1f)
                        previewTilt[1] = (previewTilt[1] + dragAmount.y / 520f).coerceIn(-1f, 1f)
                        renderer.updateTilt(previewTilt[0], previewTilt[1])
                    }
                }
        )

        GaussianParamSlider(
            label = "Parallax strength",
            value = parallaxStrength,
            valueRange = 0.005f..0.075f,
            contentColor = contentColor,
            onValueChange = {
                parallaxStrength = it
                applyParams()
            },
            onValueChangeFinished = { applyParams() }
        )
    }
}

@Composable
private fun GaussianParamSlider(
    label: String,
    value: Float,
    valueRange: ClosedFloatingPointRange<Float>,
    contentColor: Color,
    onValueChange: (Float) -> Unit,
    onValueChangeFinished: () -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(label, color = contentColor, fontSize = 13.sp)
            Text(String.format("%.2f", value), color = contentColor.copy(alpha = 0.58f), fontSize = 12.sp)
        }
        Slider(
            value = value,
            onValueChange = onValueChange,
            onValueChangeFinished = onValueChangeFinished,
            valueRange = valueRange,
            modifier = Modifier.fillMaxWidth()
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
private fun SceneInfoCard(
    fileName: String,
    scene: PhotoMeshPlyLoader.MeshScene,
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
        Text(fileName.ifBlank { "Mesh PLY" }, color = contentColor, fontWeight = FontWeight.SemiBold)
        InfoLine("Type", "3D-photo mesh", contentColor)
        InfoLine("Faces", "${formatFaces(scene.faceCount)} / ${formatFaces(scene.sourceFaceCount)}", contentColor)
        InfoLine("Vertices", formatFaces(scene.vertexCount), contentColor)
        InfoLine("Chunks", scene.chunks.size.toString(), contentColor)
        InfoLine("Image", "${scene.imageWidth} x ${scene.imageHeight}", contentColor)
        InfoLine("FOV", "${fmt(scene.hFovRad)} / ${fmt(scene.vFovRad)} rad", contentColor)
        InfoLine("Depth", "${fmt(scene.nearDepth)} / ${fmt(scene.focusDepth)} / ${fmt(scene.farDepth)}", contentColor)
        InfoLine("Stride", scene.faceStride.toString(), contentColor)
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

private fun quantizeFaceLimit(value: Float): Int {
    return ((value / FACE_LIMIT_STEP).roundToInt() * FACE_LIMIT_STEP)
        .coerceIn(MIN_TEST_FACES, MAX_TEST_FACES)
}

private fun formatFaces(value: Int): String {
    return if (value >= 1_000_000) {
        String.format("%.2fM", value / 1_000_000f)
    } else {
        "${value / 1_000}K"
    }
}

private sealed class PlyLoadResult {
    data class Gaussian(val scene: GaussianPlyLoader.GaussianScene) : PlyLoadResult()
    data class Mesh(val scene: PhotoMeshPlyLoader.MeshScene) : PlyLoadResult()
}

private data class LoadAttempt(
    val result: PlyLoadResult?,
    val error: String? = null
)

private enum class PlyKind {
    Gaussian,
    Sog,
    Mesh,
    Unknown
}

private fun detectPlyKind(context: Context, uri: Uri): PlyKind {
    return runCatching {
        context.contentResolver.openInputStream(uri)?.use { input ->
            val buffered = input.buffered(16 * 1024)
            buffered.mark(4)
            val first = buffered.read()
            val second = buffered.read()
            buffered.reset()
            if (first == 'P'.code && second == 'K'.code) {
                return@use PlyKind.Sog
            }
            InputStreamReader(buffered, StandardCharsets.US_ASCII).buffered(16 * 1024).use { reader ->
                var hasFaceElement = false
                var hasFaceList = false
                var hasGaussianField = false
                var hasColorField = false
                var currentElement = ""
                var line = reader.readLine()
                var lines = 0
                while (line != null && lines < 512) {
                    val trimmed = line.trim()
                    if (trimmed == "end_header") break
                    val parts = trimmed.split(Regex("\\s+"))
                    when (parts.firstOrNull()) {
                        "element" -> {
                            currentElement = parts.getOrNull(1).orEmpty()
                            if (currentElement == "face") hasFaceElement = true
                        }
                        "property" -> {
                            val propertyName = parts.lastOrNull().orEmpty()
                            if (currentElement == "face" && parts.getOrNull(1) == "list") {
                                hasFaceList = true
                            }
                            if (currentElement == "vertex" && propertyName in setOf("red", "green", "blue", "r", "g", "b")) {
                                hasColorField = true
                            }
                            if (currentElement == "vertex" && propertyName in setOf("f_dc_0", "opacity", "scale_0")) {
                                hasGaussianField = true
                            }
                        }
                    }
                    line = reader.readLine()
                    lines++
                }
                when {
                    hasFaceElement && hasFaceList && hasColorField -> PlyKind.Mesh
                    hasGaussianField -> PlyKind.Gaussian
                    else -> PlyKind.Unknown
                }
            }
        } ?: PlyKind.Unknown
    }.getOrDefault(PlyKind.Unknown)
}

private const val MIN_TEST_SPLATS = 60_000
private const val MAX_TEST_SPLATS = 800_000
private const val SPLAT_LIMIT_STEP = 10_000
private const val MIN_TEST_FACES = 40_000
private const val MAX_TEST_FACES = 1_600_000
private const val FACE_LIMIT_STEP = 20_000
private const val PLY_PREVIEW_ASPECT = 9f / 16f

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
