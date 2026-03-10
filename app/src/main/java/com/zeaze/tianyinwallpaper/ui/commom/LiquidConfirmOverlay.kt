package com.zeaze.tianyinwallpaper.ui.commom

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.material.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
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

@Composable
fun LiquidConfirmOverlay(
    visible: Boolean,
    backdrop: Backdrop,
    message: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    title: String = "提示",
    confirmText: String = "确定",
    dismissText: String = "取消"
) {
    if (!visible) return

    val isLightTheme = !isSystemInDarkTheme()
    val contentColor = if (isLightTheme) Color.Black else Color.White
    val accentColor = if (isLightTheme) Color(0xFF0088FF) else Color(0xFF0091FF)
    val containerColor =
        if (isLightTheme) Color(0xFFFAFAFA).copy(0.6f) else Color(0xFF121212).copy(0.4f)

    androidx.compose.foundation.layout.Box(
        modifier = Modifier
            .fillMaxSize()
            .clickable { onDismiss() },
        contentAlignment = Alignment.Center
    ) {
        Column(
            Modifier
                .padding(horizontal = 40.dp)
                .clickable(enabled = false) {}
                .drawBackdrop(
                    backdrop = backdrop,
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
                .fillMaxWidth()
        ) {
            BasicText(
                title,
                Modifier.padding(28.dp, 24.dp, 28.dp, 12.dp),
                style = TextStyle(contentColor, 24.sp, FontWeight.Medium)
            )
            BasicText(
                message,
                Modifier.padding(24.dp, 12.dp, 24.dp, 12.dp),
                style = TextStyle(contentColor.copy(0.68f), 15.sp)
            )
            Row(
                Modifier
                    .padding(24.dp, 12.dp, 24.dp, 24.dp)
                    .fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    Modifier
                        .clip(Capsule())
                        .background(containerColor.copy(0.2f))
                        .clickable { onDismiss() }
                        .height(48.dp)
                        .weight(1f),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    BasicText(dismissText, style = TextStyle(contentColor, 16.sp))
                }
                Row(
                    Modifier
                        .clip(Capsule())
                        .background(accentColor)
                        .clickable { onConfirm() }
                        .height(48.dp)
                        .weight(1f),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    BasicText(confirmText, style = TextStyle(Color.White, 16.sp))
                }
            }
        }
    }
}

