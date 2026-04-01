package com.zeaze.tianyinwallpaper.ui.main

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.material.Surface
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kyant.shapes.Capsule
import com.zeaze.tianyinwallpaper.R
import com.zeaze.tianyinwallpaper.backdrop.backdrops.LayerBackdrop
import com.zeaze.tianyinwallpaper.catalog.components.LiquidButton

/**
 * 选择模式状态数据类，用于 RxBus 通信
 */
data class SelectionBarState(val selectionMode: Boolean, val isAllSelected: Boolean)

@Composable
fun MainTopBar(
    statusBarTopPaddingDp: androidx.compose.ui.unit.Dp,
    enableLiquidGlass: Boolean,
    backdrop: LayerBackdrop?,
    isLightTheme: Boolean,
    onAdd: () -> Unit,
    onApply: () -> Unit,
    onMoreClick: () -> Unit,
    onPreview: () -> Unit,
    showAddButton: Boolean = true,
    showPreviewButton: Boolean = true,
    showApplyButton: Boolean = true,
    showMoreButton: Boolean = true,
    keepSlotWhenHidden: Boolean = true
) {
    @Composable
    fun roundButtonSlot(
        visible: Boolean,
        onClick: () -> Unit,
        text: String,
        textColor: Color,
        adaptiveSurfaceColor: Color,
        isDark: Boolean
    ) {
        if (visible) {
            if (enableLiquidGlass && backdrop != null) {
                LiquidButton(
                    onClick = onClick,
                    backdrop = backdrop,
                    modifier = Modifier.size(48.dp),
                    surfaceColor = adaptiveSurfaceColor
                ) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        BasicText(text = text, style = TextStyle(textColor, 20.sp))
                    }
                }
            } else {
                Surface(
                    modifier = Modifier.size(48.dp),
                    shape = CircleShape,
                    color = if (isDark) Color(0x33000000) else Color(0xAAFFFFFF),
                    border = BorderStroke(1.dp, if (isDark) Color(0x33FFFFFF) else Color(0x88FFFFFF))
                ) {
                    Box(
                        modifier = Modifier.fillMaxSize().clickable(onClick = onClick),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(text = text, color = textColor, fontSize = 20.sp)
                    }
                }
            }
        } else if (keepSlotWhenHidden) {
            Spacer(modifier = Modifier.size(48.dp))
        }
    }

    @Composable
    fun previewButtonSlot(
        visible: Boolean,
        onClick: () -> Unit,
        textColor: Color,
        adaptiveSurfaceColor: Color,
        isDark: Boolean
    ) {
        if (visible) {
            if (enableLiquidGlass && backdrop != null) {
                LiquidButton(
                    onClick = onClick,
                    backdrop = backdrop,
                    modifier = Modifier.height(48.dp),
                    surfaceColor = adaptiveSurfaceColor
                ) {
                    Box(contentAlignment = Alignment.Center, modifier = Modifier.padding(horizontal = 16.dp).fillMaxHeight()) {
                        BasicText(text = "当前播放", style = TextStyle(textColor, 15.sp))
                    }
                }
            } else {
                Surface(
                    modifier = Modifier.height(48.dp).clickable(onClick = onClick),
                    shape = Capsule(),
                    color = if (isDark) Color(0x33000000) else Color(0xAAFFFFFF),
                    border = BorderStroke(1.dp, if (isDark) Color(0x33FFFFFF) else Color(0x88FFFFFF))
                ) {
                    Box(contentAlignment = Alignment.Center, modifier = Modifier.padding(horizontal = 16.dp).fillMaxHeight()) {
                        Text(text = "当前播放", color = textColor, fontSize = 15.sp)
                    }
                }
            }
        } else if (keepSlotWhenHidden) {
            Spacer(modifier = Modifier.width(64.dp).height(48.dp))
        }
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = statusBarTopPaddingDp + 10.dp, start = 8.dp, end = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        val isDark = !isLightTheme
        val adaptiveSurfaceColor = if (isDark) Color.Black.copy(0.3f) else Color.White.copy(0.3f)
        val textColor = if (isDark) Color.White else Color.Black

        roundButtonSlot(showAddButton, onAdd, "+", textColor, adaptiveSurfaceColor, isDark)

        Spacer(modifier = Modifier.weight(1f))

        previewButtonSlot(showPreviewButton, onPreview, textColor, adaptiveSurfaceColor, isDark)
        roundButtonSlot(showApplyButton, onApply, "✓", textColor, adaptiveSurfaceColor, isDark)
        roundButtonSlot(showMoreButton, onMoreClick, "⋯", textColor, adaptiveSurfaceColor, isDark)
    }
}

@Composable
fun SelectionTopBar(
    statusBarTopPaddingDp: androidx.compose.ui.unit.Dp,
    enableLiquidGlass: Boolean,
    backdrop: LayerBackdrop?,
    isAllSelected: Boolean,
    isLightTheme: Boolean,
    onCancelSelect: () -> Unit,
    onDelete: () -> Unit,
    onToggleSelectAll: () -> Unit
) {
    val context = LocalContext.current
    val isDark = !isLightTheme
    val adaptiveSurfaceColor = if (isDark) Color.Black.copy(0.3f) else Color.White.copy(0.3f)
    val textColor = if (isDark) Color.White else Color.Black

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = statusBarTopPaddingDp + 10.dp, start = 12.dp, end = 12.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Delete Button (Red)
        if (enableLiquidGlass && backdrop != null) {
            LiquidButton(
                onClick = onDelete,
                backdrop = backdrop,
                surfaceColor = Color(0xFFFF4D4F).copy(alpha = 0.8f),
                modifier = Modifier.height(48.dp)
            ) {
                BasicText(
                    context.getString(R.string.common_delete),
                    modifier = Modifier.padding(horizontal = 16.dp),
                    style = TextStyle(Color.White, 15.sp, fontWeight = FontWeight.Medium)
                )
            }
        } else {
            Surface(
                modifier = Modifier
                    .height(48.dp)
                    .clickable { onDelete() },
                shape = Capsule(),
                color = Color(0xFFFF4D4F)
            ) {
                Box(contentAlignment = Alignment.Center, modifier = Modifier.padding(horizontal = 16.dp)) {
                    Text(text = context.getString(R.string.common_delete), color = Color.White, fontSize = 15.sp)
                }
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            // Select All Toggle Button
            val selectAllLabel = if (isAllSelected) "取消全选" else "全选"
            if (enableLiquidGlass && backdrop != null) {
                LiquidButton(
                    onClick = onToggleSelectAll,
                    backdrop = backdrop,
                    surfaceColor = adaptiveSurfaceColor,
                    modifier = Modifier.height(48.dp)
                ) {
                    BasicText(
                        selectAllLabel,
                        modifier = Modifier.padding(horizontal = 16.dp),
                        style = TextStyle(textColor, 15.sp)
                    )
                }
            } else {
                Surface(
                    modifier = Modifier
                        .height(48.dp)
                        .clickable { onToggleSelectAll() },
                    shape = Capsule(),
                    color = adaptiveSurfaceColor
                ) {
                    Box(contentAlignment = Alignment.Center, modifier = Modifier.padding(horizontal = 16.dp)) {
                        Text(text = selectAllLabel, color = textColor, fontSize = 15.sp)
                    }
                }
            }

            // Cancel Button
            if (enableLiquidGlass && backdrop != null) {
                LiquidButton(
                    onClick = onCancelSelect,
                    backdrop = backdrop,
                    surfaceColor = adaptiveSurfaceColor,
                    modifier = Modifier.height(48.dp)
                ) {
                    BasicText(
                        context.getString(R.string.common_cancel),
                        modifier = Modifier.padding(horizontal = 16.dp),
                        style = TextStyle(textColor, 15.sp)
                    )
                }
            } else {
                Surface(
                    modifier = Modifier
                        .height(48.dp)
                        .clickable { onCancelSelect() },
                    shape = Capsule(),
                    color = adaptiveSurfaceColor
                ) {
                    Box(contentAlignment = Alignment.Center, modifier = Modifier.padding(horizontal = 16.dp)) {
                        Text(text = context.getString(R.string.common_cancel), color = textColor, fontSize = 15.sp)
                    }
                }
            }
        }
    }
}
