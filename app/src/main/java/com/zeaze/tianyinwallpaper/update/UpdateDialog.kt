package com.zeaze.tianyinwallpaper.update

import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.scaleIn
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.LinearProgressIndicator
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kyant.shapes.Capsule
import com.kyant.shapes.RoundedRectangle
import com.zeaze.tianyinwallpaper.backdrop.Backdrop
import com.zeaze.tianyinwallpaper.backdrop.drawBackdrop
import com.zeaze.tianyinwallpaper.backdrop.effects.blur
import com.zeaze.tianyinwallpaper.backdrop.effects.colorControls
import com.zeaze.tianyinwallpaper.backdrop.effects.lens
import com.zeaze.tianyinwallpaper.backdrop.highlight.Highlight
import com.zeaze.tianyinwallpaper.backdrop.backdrops.rememberCanvasBackdrop
import com.zeaze.tianyinwallpaper.backdrop.backdrops.rememberLayerBackdrop
import com.zeaze.tianyinwallpaper.backdrop.backdrops.layerBackdrop
import com.zeaze.tianyinwallpaper.backdrop.shadow.InnerShadow
import com.zeaze.tianyinwallpaper.backdrop.shadow.Shadow

/**
 * 更新对话框状态
 */
data class UpdateDialogState(
    val isVisible: Boolean = false,
    val isChecking: Boolean = false,
    val updateInfo: UpdateInfo? = null,
    val errorMessage: String? = null,
    val isDownloading: Boolean = false,
    val downloadProgress: Int = 0,
    val isLatestVersion: Boolean = false  // 是否已是最新版本
)

/**
 * 更新对话框组件
 * @param parentBackdrop 父组件的 backdrop，用于 Liquid Glass 效果。如果为 null，则使用普通的 canvas backdrop
 */
@Composable
fun UpdateDialog(
    state: UpdateDialogState,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
    isLightTheme: Boolean,
    modifier: Modifier = Modifier,
    parentBackdrop: Backdrop? = null
) {
    val contentColor = if (isLightTheme) Color.Black else Color.White
    val accentColor = if (isLightTheme) Color(0xFF0088FF) else Color(0xFF0091FF)
    val containerColor = if (isLightTheme) Color(0xFFFAFAFA).copy(0.6f) else Color(0xFF121212).copy(0.4f)
    val dimColor = if (isLightTheme) Color(0xFF29293A).copy(0.23f) else Color(0xFF121212).copy(0.56f)

    //背景遮罩
    AnimatedVisibility(
        visible = state.isVisible,
        enter = fadeIn(),
        exit = fadeOut()
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(dimColor)
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null
                ) {
                    if (!state.isDownloading) {
                        onDismiss()
                    }
                }
        )
    }

    // 对话框内容
    AnimatedVisibility(
        visible = state.isVisible,
        enter = fadeIn(animationSpec = spring(stiffness = Spring.StiffnessLow)) +
                scaleIn(
                    initialScale = 0.8f,
                    animationSpec = spring(
                        dampingRatio = Spring.DampingRatioMediumBouncy,
                        stiffness = Spring.StiffnessLow
                    )
                ),
        exit = fadeOut(),
        modifier = modifier.fillMaxSize()
    ) {
        // 使用传入的 backdrop 或创建 canvas backdrop
        val dialogBackdrop = parentBackdrop ?: rememberCanvasBackdrop { drawRect(containerColor) }

        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            Column(
                Modifier
                    .padding(horizontal = 40.dp)
                    .wrapContentHeight()
                    .drawBackdrop(
                        backdrop = dialogBackdrop,
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
                        onDrawSurface = { drawRect(containerColor) }
                    )
                    .pointerInput(Unit) { detectTapGestures { /* 消费点击事件 */ } }
                    .padding(vertical = 24.dp, horizontal = 28.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // 标题
                BasicText(
                    text = "检查更新",
                    style = TextStyle(
                        color = contentColor,
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Bold
                    )
                )

                // 版本信息
                state.updateInfo?.let { info ->
                    BasicText(
                        text = "版本: ${info.name}",
                        style = TextStyle(
                            color = contentColor.copy(0.8f),
                            fontSize = 14.sp
                        )
                    )

                    // 文件大小
                    BasicText(
                        text = "大小: ${AppUpdateManager.formatFileSize(info.size)}",
                        style = TextStyle(
                            color = contentColor.copy(0.6f),
                            fontSize = 12.sp
                        )
                    )

                    // 更新说明
                    if (info.des.isNotBlank()) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(max = 160.dp)
                                .clip(RoundedCornerShape(12.dp))
                                .background(contentColor.copy(0.05f))
                                .padding(12.dp)
                        ) {
                            Column(
                                modifier = Modifier.verticalScroll(rememberScrollState())
                            ) {
                                BasicText(
                                    text = info.des.replace("\\n", "\n"),
                                    style = TextStyle(
                                        color = contentColor.copy(0.8f),
                                        fontSize = 14.sp,
                                        lineHeight = 20.sp
                                    )
                                )
                            }
                        }
                    }
                }

                // 下载进度
                if (state.isDownloading) {
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        LinearProgressIndicator(
                            progress = state.downloadProgress / 100f,
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(4.dp)
                                .clip(RoundedCornerShape(2.dp)),
                            color = accentColor,
                            backgroundColor = contentColor.copy(0.1f)
                        )
                        BasicText(
                            text = "正在下载... ${state.downloadProgress}%",
                            style = TextStyle(
                                color = contentColor.copy(0.6f),
                                fontSize = 12.sp
                            )
                        )
                    }
                }

                // 错误信息
                state.errorMessage?.let { error ->
                    val context = LocalContext.current
                    BasicText(
                        text = error,
                        style = TextStyle(
                            color = Color.Red,
                            fontSize = 12.sp
                        ),
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .background(contentColor.copy(0.05f))
                            .clickable {
                                // 点击复制到剪贴板
                                val clipboard = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                                val clip = android.content.ClipData.newPlainText("错误信息", error)
                                clipboard.setPrimaryClip(clip)
                                Toast.makeText(context, "已复制错误信息", Toast.LENGTH_SHORT).show()
                            }
                            .padding(8.dp)
                    )
                }

                // 检查中状态
                if (state.isChecking) {
                    BasicText(
                        text = "正在检查更新...",
                        style = TextStyle(
                            color = contentColor.copy(0.6f),
                            fontSize = 14.sp
                        )
                    )
                }

                // 已是最新版本
                if (state.isLatestVersion) {
                    BasicText(
                        text = "✓ 已是最新版本",
                        style = TextStyle(
                            color = Color(0xFF4CAF50),
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Medium
                        )
                    )
                }

                // 按钮
                if (!state.isDownloading) {
                    // 已是最新版本：只显示确定按钮
                    if (state.isLatestVersion) {
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clip(Capsule())
                                .background(accentColor)
                                .clickable { onConfirm() }
                                .height(48.dp),
                            horizontalArrangement = Arrangement.Center,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            BasicText(
                                text = "确定",
                                style = TextStyle(Color.White, 16.sp)
                            )
                        }
                    } else if (state.updateInfo != null) {
                        // 有更新：显示两个按钮
                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            // 取消按钮
                            Row(
                                Modifier
                                    .weight(1f)
                                    .clip(Capsule())
                                    .background(contentColor.copy(0.1f))
                                    .clickable { onDismiss() }
                                    .height(48.dp),
                                horizontalArrangement = Arrangement.Center,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                BasicText(
                                    text = "稍后再说",
                                    style = TextStyle(contentColor, 16.sp)
                                )
                            }

                            // 确认按钮
                            Row(
                                Modifier
                                    .weight(1f)
                                    .clip(Capsule())
                                    .background(accentColor)
                                    .clickable { onConfirm() }
                                    .height(48.dp),
                                horizontalArrangement = Arrangement.Center,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                BasicText(
                                    text = "立即更新",
                                    style = TextStyle(Color.White, 16.sp)
                                )
                            }
                        }
                    } else {
                        // 检查中或错误：只显示稍后再说
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clip(Capsule())
                                .background(contentColor.copy(0.1f))
                                .clickable { onDismiss() }
                                .height(48.dp),
                            horizontalArrangement = Arrangement.Center,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            BasicText(
                                text = "稍后再说",
                                style = TextStyle(contentColor, 16.sp)
                            )
                        }
                    }
                }
            }
        }
    }
}
