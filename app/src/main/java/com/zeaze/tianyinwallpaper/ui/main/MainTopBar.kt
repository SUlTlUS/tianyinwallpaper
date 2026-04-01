package com.zeaze.tianyinwallpaper.ui.main

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kyant.shapes.Capsule
import com.zeaze.tianyinwallpaper.backdrop.Backdrop
import com.zeaze.tianyinwallpaper.catalog.components.LiquidButton

/**
 * 主页顶部栏
 * 包含添加、预览、应用、更多按钮
 */
@Composable
fun MainTopBar(
    statusBarTopPaddingDp: androidx.compose.ui.unit.Dp,
    enableLiquidGlass: Boolean,
    backdrop: Backdrop?,
    isLightTheme: Boolean,
    onAdd: () -> Unit,
    onApply: () -> Unit,
    onMoreClick: () -> Unit,
    onPreview: () -> Unit,
    showAddButton: Boolean,
    showPreviewButton: Boolean,
    showApplyButton: Boolean,
    showMoreButton: Boolean,
    keepSlotWhenHidden: Boolean = false
) {
    val onPage = if (isLightTheme) Color.Black else Color.White
    val pillBackground = if (!isLightTheme) Color(0x22222222) else Color(0x22FFFFFF)

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 16.dp, top = statusBarTopPaddingDp + 12.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        // 左侧：添加按钮
        if (showAddButton && enableLiquidGlass && backdrop != null) {
            LiquidButton(
                onClick = onAdd,
                backdrop = backdrop,
                surfaceColor = pillBackground,
                modifier = Modifier.width(80.dp).height(44.dp)
            ) {
                BasicText(
                    "+",
                    style = TextStyle(
                        color = onPage,
                        fontSize = 20.sp
                    )
                )
            }
        } else if (showAddButton) {
            Box(
                modifier = Modifier
                    .background(pillBackground, Capsule())
                    .padding(horizontal = 18.dp, vertical = 8.dp)
            ) {
                BasicText("+", style = TextStyle(onPage, 20.sp, fontWeight = FontWeight.Bold))
            }
        } else if (keepSlotWhenHidden) {
            Spacer(Modifier.width(80.dp))
        }

        // 右侧按钮组
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // 预览按钮
            if (showPreviewButton && enableLiquidGlass && backdrop != null) {
                LiquidButton(
                    onClick = onPreview,
                    backdrop = backdrop,
                    surfaceColor = pillBackground,
                    modifier = Modifier.width(80.dp).height(44.dp)
                ) {
                    BasicText(
                        "预览",
                        style = TextStyle(
                            color = onPage,
                            fontSize = 15.sp
                        )
                    )
                }
            } else if (showPreviewButton) {
                Box(
                    modifier = Modifier
                        .background(pillBackground, Capsule())
                        .padding(horizontal = 18.dp, vertical = 8.dp)
                ) {
                    BasicText("预览", style = TextStyle(onPage, 14.sp))
                }
            }

            // 应用按钮
            if (showApplyButton && enableLiquidGlass && backdrop != null) {
                LiquidButton(
                    onClick = onApply,
                    backdrop = backdrop,
                    surfaceColor = Color(0xFF2A83FF).copy(alpha = 0.75f),
                    tint = Color(0xFF2A83FF),
                    modifier = Modifier.width(80.dp).height(44.dp)
                ) {
                    BasicText(
                        "应用",
                        style = TextStyle(Color.White, 15.sp)
                    )
                }
            } else if (showApplyButton) {
                Box(
                    modifier = Modifier
                        .background(Color(0x662A83FF), Capsule())
                        .padding(horizontal = 18.dp, vertical = 8.dp)
                ) {
                    BasicText("应用", style = TextStyle(Color.White, 14.sp))
                }
            }

            // 更多按钮
            if (showMoreButton && enableLiquidGlass && backdrop != null) {
                LiquidButton(
                    onClick = onMoreClick,
                    backdrop = backdrop,
                    surfaceColor = pillBackground,
                    modifier = Modifier.width(60.dp).height(44.dp)
                ) {
                    BasicText(
                        "⋯",
                        style = TextStyle(
                            color = onPage,
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Bold
                        )
                    )
                }
            } else if (showMoreButton) {
                Box(
                    modifier = Modifier
                        .background(pillBackground, Capsule())
                        .padding(horizontal = 14.dp, vertical = 8.dp)
                ) {
                    BasicText("⋯", style = TextStyle(onPage, 18.sp, fontWeight = FontWeight.Bold))
                }
            }
        }
    }
}

/**
 * 通用文字按钮组件
 */
@Composable
private fun TextLiquidButton(
    text: String,
    onClick: () -> Unit,
    backdrop: Backdrop?,
    enableLiquidGlass: Boolean,
    isLightTheme: Boolean,
    surfaceColor: Color? = null,
    textColor: Color? = null,
    modifier: Modifier = Modifier
) {
    val onPage = if (isLightTheme) Color.Black else Color.White
    val pillBackground = if (!isLightTheme) Color(0x22222222) else Color(0x22FFFFFF)
    val finalSurfaceColor = surfaceColor ?: pillBackground
    val finalTextColor = textColor ?: onPage

    if (enableLiquidGlass && backdrop != null) {
        LiquidButton(
            onClick = onClick,
            backdrop = backdrop,
            surfaceColor = finalSurfaceColor,
            modifier = modifier.height(44.dp)
        ) {
            BasicText(
                text,
                modifier = Modifier.padding(horizontal = 14.dp),
                style = TextStyle(
                    color = finalTextColor,
                    fontSize = 15.sp
                )
            )
        }
    } else {
        Box(
            modifier = Modifier
                .background(finalSurfaceColor, Capsule())
                .padding(horizontal = 18.dp, vertical = 8.dp)
        ) {
            BasicText(text, style = TextStyle(finalTextColor, 14.sp))
        }
    }
}

/**
 * 选择模式顶部栏
 */
@Composable
fun SelectionTopBar(
    statusBarTopPaddingDp: androidx.compose.ui.unit.Dp,
    enableLiquidGlass: Boolean,
    backdrop: Backdrop?,
    isAllSelected: Boolean,
    isLightTheme: Boolean,
    onCancelSelect: () -> Unit,
    onDelete: () -> Unit,
    onToggleSelectAll: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 16.dp, top = statusBarTopPaddingDp + 12.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        // 取消按钮
        TextLiquidButton(
            text = "取消",
            onClick = onCancelSelect,
            backdrop = backdrop,
            enableLiquidGlass = enableLiquidGlass,
            isLightTheme = isLightTheme
        )

        // 全选/取消全选按钮
        TextLiquidButton(
            text = if (isAllSelected) "取消全选" else "全选",
            onClick = onToggleSelectAll,
            backdrop = backdrop,
            enableLiquidGlass = enableLiquidGlass,
            isLightTheme = isLightTheme
        )

        // 删除按钮
        TextLiquidButton(
            text = "删除",
            onClick = onDelete,
            backdrop = backdrop,
            enableLiquidGlass = enableLiquidGlass,
            isLightTheme = isLightTheme,
            surfaceColor = Color(0xFFFF4D4F).copy(alpha = 0.75f),
            textColor = Color.White
        )
    }
}
