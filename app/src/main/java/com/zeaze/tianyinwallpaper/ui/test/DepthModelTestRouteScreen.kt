package com.zeaze.tianyinwallpaper.ui.test

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
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
import androidx.compose.material.MaterialTheme
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
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kyant.shapes.Capsule
import com.zeaze.tianyinwallpaper.utils.DepthModelRunner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
fun DepthModelTestRouteScreen(
    useDarkTheme: Boolean
) {
    val context = LocalContext.current
    val isLightTheme = !useDarkTheme
    val backgroundColor = if (isLightTheme) Color(0xFFF2F2F6) else Color(0xFF121212)
    val panelColor = if (isLightTheme) Color.White else Color(0xFF1E1E1E)
    val contentColor = if (isLightTheme) Color(0xFF1B1B1F) else Color(0xFFF1F1F1)
    val accentColor = Color(0xFF2A83FF)

    var selectedUri by remember { mutableStateOf<Uri?>(null) }
    var sourceBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var result by remember { mutableStateOf<DepthModelRunner.DepthInferenceResult?>(null) }
    var isRunning by remember { mutableStateOf(false) }
    var errorText by remember { mutableStateOf<String?>(null) }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        selectedUri = uri
    }

    LaunchedEffect(selectedUri) {
        val uri = selectedUri ?: return@LaunchedEffect
        isRunning = true
        errorText = null
        result = null
        val loaded = withContext(Dispatchers.IO) {
            decodePreviewBitmap(context, uri, maxEdge = 1024)
        }
        if (loaded == null) {
            sourceBitmap = null
            isRunning = false
            errorText = "图片读取失败"
            return@LaunchedEffect
        }
        sourceBitmap = loaded
        val inferenceResult = withContext(Dispatchers.IO) {
            DepthModelRunner.inferDepthResult(context, loaded)
        }
        isRunning = false
        if (inferenceResult == null) {
            errorText = DepthModelRunner.lastError()
                ?: "模型未加载或推理失败，请确认 assets/midas_v21_small_256_float32.tflite 存在"
        } else {
            result = inferenceResult
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
                text = "景深模型测试",
                style = TextStyle(
                    color = contentColor,
                    fontSize = 30.sp,
                    fontWeight = FontWeight.Bold
                )
            )

            Text(
                text = "输入图片会先缩放到模型要求的 256x256 做推理，再把深度贴图上采样回测试输入尺寸。",
                style = TextStyle(
                    color = contentColor.copy(alpha = 0.64f),
                    fontSize = 14.sp,
                    lineHeight = 20.sp
                )
            )

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp)
                    .clip(Capsule())
                    .background(accentColor)
                    .clickable { picker.launch(arrayOf("image/*")) },
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("选择图片", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
            }

            if (isRunning) {
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
                    Text("正在生成深度贴图", color = contentColor.copy(alpha = 0.72f))
                }
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

            sourceBitmap?.let { bitmap ->
                BitmapPreviewCard(
                    title = "输入图片",
                    subtitle = "${bitmap.width} x ${bitmap.height}",
                    bitmap = bitmap,
                    panelColor = panelColor,
                    contentColor = contentColor
                )
            }

            result?.let { inference ->
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(24.dp))
                        .background(panelColor)
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text("模型: ${inference.modelName}", color = contentColor, fontWeight = FontWeight.SemiBold)
                    Text(
                        text = "输入 ${inference.inputWidth}x${inference.inputHeight} · 原始输出 ${inference.outputWidth}x${inference.outputHeight} · 上采样 ${inference.sourceWidth}x${inference.sourceHeight} · ${inference.inferenceMs}ms",
                        color = contentColor.copy(alpha = 0.62f),
                        fontSize = 12.sp,
                        lineHeight = 17.sp
                    )
                }

                BitmapPreviewCard(
                    title = "模型原始输出",
                    subtitle = "${inference.outputWidth} x ${inference.outputHeight}",
                    bitmap = inference.rawDepthMap,
                    panelColor = panelColor,
                    contentColor = contentColor
                )

                BitmapPreviewCard(
                    title = "上采样深度贴图",
                    subtitle = "${inference.sourceWidth} x ${inference.sourceHeight}",
                    bitmap = inference.upsampledDepthMap,
                    panelColor = panelColor,
                    contentColor = contentColor
                )
            }

            Spacer(Modifier.height(28.dp))
        }
    }
}

@Composable
private fun BitmapPreviewCard(
    title: String,
    subtitle: String,
    bitmap: Bitmap,
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
            Text(title, color = contentColor, fontWeight = FontWeight.SemiBold)
            Text(subtitle, color = contentColor.copy(alpha = 0.55f), fontSize = 12.sp)
        }
        Image(
            bitmap = bitmap.asImageBitmap(),
            contentDescription = null,
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(bitmap.width.toFloat() / bitmap.height.toFloat())
                .clip(RoundedCornerShape(16.dp))
                .background(MaterialTheme.colors.onSurface.copy(alpha = 0.08f)),
            contentScale = ContentScale.Fit
        )
    }
}

private fun decodePreviewBitmap(
    context: android.content.Context,
    uri: Uri,
    maxEdge: Int
): Bitmap? {
    val bounds = BitmapFactory.Options().apply {
        inJustDecodeBounds = true
    }
    context.contentResolver.openInputStream(uri)?.use { stream ->
        BitmapFactory.decodeStream(stream, null, bounds)
    }
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

    var sampleSize = 1
    while (bounds.outWidth / sampleSize > maxEdge || bounds.outHeight / sampleSize > maxEdge) {
        sampleSize *= 2
    }

    val options = BitmapFactory.Options().apply {
        inPreferredConfig = Bitmap.Config.ARGB_8888
        inSampleSize = sampleSize
    }
    return context.contentResolver.openInputStream(uri)?.use { stream ->
        BitmapFactory.decodeStream(stream, null, options)?.let { bitmap ->
            if (bitmap.config == Bitmap.Config.ARGB_8888) {
                bitmap.copy(Bitmap.Config.ARGB_8888, false).also {
                    if (!bitmap.isRecycled) bitmap.recycle()
                }
            } else {
                bitmap
            }
        }
    }
}
