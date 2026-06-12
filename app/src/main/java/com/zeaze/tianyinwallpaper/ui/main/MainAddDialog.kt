package com.zeaze.tianyinwallpaper.ui.main

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

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
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 20.dp),
        horizontalAlignment = Alignment.Start
    ) {
        BasicText(
            text = title,
            modifier = Modifier.fillMaxWidth(),
            style = TextStyle(
                color = contentColor,
                fontSize = 22.sp,
                fontWeight = FontWeight.Bold
            )
        )

        Spacer(Modifier.height(20.dp))

        WallpaperTypeSectionTitle(
            text = "常规",
            contentColor = contentColor
        )
        Spacer(Modifier.height(8.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            WallpaperTypeItem(
                label = "图片",
                contentColor = contentColor,
                backgroundColor = accentColor.copy(alpha = 0.12f),
                onClick = onPickImageWallpaper,
                modifier = Modifier.weight(1f)
            )
            WallpaperTypeItem(
                label = "视频",
                contentColor = contentColor,
                backgroundColor = accentColor.copy(alpha = 0.12f),
                onClick = onPickVideoWallpaper,
                modifier = Modifier.weight(1f)
            )
            WallpaperTypeItem(
                label = "文件夹",
                contentColor = contentColor,
                backgroundColor = accentColor.copy(alpha = 0.12f),
                onClick = onPickFolderWallpaper,
                modifier = Modifier.weight(1f)
            )
        }

        Spacer(Modifier.height(18.dp))

        WallpaperTypeSectionTitle(
            text = "光栅",
            contentColor = contentColor
        )
        Spacer(Modifier.height(8.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            WallpaperTypeItem(
                label = "图集光栅",
                contentColor = contentColor,
                backgroundColor = accentColor.copy(alpha = 0.12f),
                onClick = onPickRasterImages,
                modifier = Modifier.weight(1f)
            )
            WallpaperTypeItem(
                label = "视频光栅",
                contentColor = contentColor,
                backgroundColor = accentColor.copy(alpha = 0.12f),
                onClick = onPickRasterVideo,
                modifier = Modifier.weight(1f)
            )
        }

        Spacer(Modifier.height(18.dp))

        WallpaperTypeSectionTitle(
            text = "景深",
            contentColor = contentColor
        )
        Spacer(Modifier.height(8.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            WallpaperTypeItem(
                label = "本地导入",
                contentColor = contentColor,
                backgroundColor = accentColor.copy(alpha = 0.12f),
                onClick = onPickDepthSog,
                modifier = Modifier.weight(1f)
            )
            WallpaperTypeItem(
                label = "在线生成",
                contentColor = contentColor,
                backgroundColor = accentColor.copy(alpha = 0.12f),
                onClick = onOpenOnlineSog,
                modifier = Modifier.weight(1f)
            )
        }

        Spacer(Modifier.height(18.dp))

        WallpaperTypeItem(
            label = cancelText,
            contentColor = contentColor,
            backgroundColor = containerColor.copy(alpha = 0.22f),
            onClick = onDismiss,
            compact = true,
            modifier = Modifier.fillMaxWidth()
        )
    }
}

@Composable
private fun WallpaperTypeSectionTitle(
    text: String,
    contentColor: Color
) {
    BasicText(
        text = text,
        modifier = Modifier.fillMaxWidth(),
        style = TextStyle(
            color = contentColor.copy(alpha = 0.72f),
            fontSize = 14.sp,
            fontWeight = FontWeight.Medium
        )
    )
}

@Composable
private fun WallpaperTypeItem(
    label: String,
    contentColor: Color,
    backgroundColor: Color,
    onClick: () -> Unit,
    compact: Boolean = false,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .height(if (compact) 48.dp else 92.dp)
            .clip(RoundedCornerShape(18.dp))
            .background(backgroundColor)
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = if (compact) 0.dp else 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        if (!compact) {
            // 图标占位：之后可直接把此 Box 替换成 Icon(...)。
            Box(Modifier.size(28.dp))
            Spacer(Modifier.height(8.dp))
        }
        BasicText(
            text = label,
            style = TextStyle(
                color = contentColor,
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium
            )
        )
    }
}
