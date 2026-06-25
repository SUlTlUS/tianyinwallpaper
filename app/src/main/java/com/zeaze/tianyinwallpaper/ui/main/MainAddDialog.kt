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
import androidx.annotation.DrawableRes
import androidx.compose.material.Icon
import androidx.compose.ui.res.painterResource
import com.zeaze.tianyinwallpaper.R

@Composable
fun MainAddDialog(
    title: String,
    cancelText: String,
    contentColor: Color,
    accentColor: Color,
    containerColor: Color,
    onPickImageWallpaper: () -> Unit,
    onPickVideoWallpaper: () -> Unit,
    onPickRasterImages: () -> Unit,
    onPickRasterVideo: () -> Unit,
    onPickDepthSog: () -> Unit,
    onOpenOnlineSog: () -> Unit,
    onDismiss: () -> Unit
) {
    val itemBackgroundColor = accentColor.copy(alpha = 0.12f)

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp, vertical = 24.dp),
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
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            WallpaperTypeItem(
                label = "图片",
                contentColor = contentColor,
                backgroundColor = itemBackgroundColor,
                onClick = onPickImageWallpaper,
                modifier = Modifier.weight(1f),
                iconRes = R.drawable.picture
            )
            WallpaperTypeItem(
                label = "视频",
                contentColor = contentColor,
                backgroundColor = itemBackgroundColor,
                onClick = onPickVideoWallpaper,
                modifier = Modifier.weight(1f),
                iconRes = R.drawable.video
            )
        }

        Spacer(Modifier.height(18.dp))

        WallpaperTypeSectionTitle(
            text = "光栅",
            contentColor = contentColor
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            WallpaperTypeItem(
                label = "图集光栅",
                contentColor = contentColor,
                backgroundColor = itemBackgroundColor,
                onClick = onPickRasterImages,
                modifier = Modifier.weight(1f),
                iconRes = R.drawable.pictureraster
            )
            WallpaperTypeItem(
                label = "视频光栅",
                contentColor = contentColor,
                backgroundColor = itemBackgroundColor,
                onClick = onPickRasterVideo,
                modifier = Modifier.weight(1f),
                iconRes = R.drawable.videoraster
            )
        }

        Spacer(Modifier.height(18.dp))

        WallpaperTypeSectionTitle(
            text = "景深",
            contentColor = contentColor
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            WallpaperTypeItem(
                label = "本地导入",
                contentColor = contentColor,
                backgroundColor = itemBackgroundColor,
                onClick = onPickDepthSog,
                modifier = Modifier.weight(1f),
                iconRes = R.drawable.depth
            )
            WallpaperTypeItem(
                label = "在线生成",
                contentColor = contentColor,
                backgroundColor = itemBackgroundColor,
                onClick = onOpenOnlineSog,
                modifier = Modifier.weight(1f),
                iconRes = R.drawable.depth
            )
        }

    }
}

@Composable
private fun WallpaperTypeSectionTitle(
    text: String,
    contentColor: Color
) {

}

@Composable
private fun WallpaperTypeItem(
    label: String,
    @DrawableRes iconRes: Int,
    contentColor: Color,
    backgroundColor: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .height(75.dp)
            .clip(RoundedCornerShape(18.dp))
            .background(backgroundColor)
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(
            painter = painterResource(id = iconRes),
            contentDescription = label,
            modifier = Modifier.size(28.dp),
            tint = contentColor
        )

        Spacer(Modifier.height(8.dp))

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
