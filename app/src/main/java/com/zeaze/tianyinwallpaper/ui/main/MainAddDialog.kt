package com.zeaze.tianyinwallpaper.ui.main

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip

@Composable
fun MainAddDialog(
    title: String,
    cancelText: String,
    contentColor: Color,
    accentColor: Color,
    containerColor: Color,
    onPickImageWallpaper: () -> Unit,
    onPickVideoWallpaper: () -> Unit,
    onPickFolderWallpaper: () -> Unit,
    onPickRasterImages: () -> Unit,
    onPickRasterVideo: () -> Unit,
    onPickDepthSog: () -> Unit,
    onOpenOnlineSog: () -> Unit,
    onDismiss: () -> Unit
) {
    Column(
        Modifier
            .padding(16.dp, 20.dp, 16.dp, 20.dp)
            .fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        BasicText(
            text = title,
            style = TextStyle(contentColor, 18.sp, fontWeight = FontWeight.Bold)
        )
        Spacer(Modifier.height(8.dp))

        val items = listOf(
            "图片壁纸" to onPickImageWallpaper,
            "视频壁纸" to onPickVideoWallpaper,
            "文件夹壁纸" to onPickFolderWallpaper,
            "光栅图片组" to onPickRasterImages,
            "光栅视频" to onPickRasterVideo,
            "本地 SOG 景深" to onPickDepthSog,
            "在线生成 SOG" to onOpenOnlineSog,
            cancelText to onDismiss
        )

        items.forEach { (label, onClick) ->
            val isCancel = label == cancelText
            Row(
                Modifier
                    .clip(RoundedCornerShape(999.dp))
                    .background(if (isCancel) containerColor.copy(alpha = 0.2f) else accentColor)
                    .clickable { onClick() }
                    .height(48.dp)
                    .fillMaxWidth(),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically
            ) {
                BasicText(
                    text = label,
                    style = TextStyle(if (isCancel) contentColor else Color.White, 16.sp)
                )
            }
        }
    }
}
