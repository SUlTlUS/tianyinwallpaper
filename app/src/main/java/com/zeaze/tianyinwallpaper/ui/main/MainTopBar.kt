package com.zeaze.tianyinwallpaper.ui.main

import androidx.annotation.DrawableRes
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.material.Icon
import androidx.compose.material.Surface
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
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

private val IosTopButtonHeight = 44.dp
private val IosTopButtonHorizontalPadding = 16.dp

@Composable
private fun TopCircleLiquidButton(
    visible: Boolean,
    onClick: () -> Unit,
    text: String,
    textColor: Color,
    surfaceColor: Color,
    isDark: Boolean,
    enableLiquidGlass: Boolean,
    backdrop: LayerBackdrop?,
    keepSlotWhenHidden: Boolean,
    modifier: Modifier = Modifier,
    size: Dp = IosTopButtonHeight,
    fontSize: Dp = 20.dp,
    @DrawableRes iconRes: Int? = null,
    iconContentDescription: String? = null,
    iconSize: Dp = 20.dp,
    iconOffsetX: Dp = 0.dp,
    iconOffsetY: Dp = 0.dp
) {
    if (visible) {
        if (enableLiquidGlass && backdrop != null) {
            if (iconRes != null) {
                LiquidButton(
                    onClick = onClick,
                    backdrop = backdrop,
                    modifier = modifier.size(size),
                    surfaceColor = surfaceColor,
                    buttonHeight = size,
                    contentPadding = PaddingValues(0.dp),
                    iconRes = iconRes,
                    iconContentDescription = iconContentDescription,
                    iconSize = iconSize,
                    iconTint = textColor,
                    iconOffsetX = iconOffsetX,
                    iconOffsetY = iconOffsetY
                )
            } else {
                LiquidButton(
                    onClick = onClick,
                    backdrop = backdrop,
                    modifier = modifier.size(size),
                    surfaceColor = surfaceColor,
                    buttonHeight = size,
                    contentPadding = PaddingValues(0.dp)
                ) {
                    BasicText(
                        text = text,
                        style = TextStyle(
                            color = textColor,
                            fontSize = fontSize.value.sp,
                            fontWeight = FontWeight.Medium
                        )
                    )
                }
            }
        } else {
            Surface(
                modifier = modifier.size(size),
                shape = CircleShape,
                color = if (isDark) Color(0x33000000) else Color(0xAAFFFFFF),
                border = BorderStroke(1.dp, if (isDark) Color(0x33FFFFFF) else Color(0x88FFFFFF))
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .clickable(onClick = onClick),
                    contentAlignment = Alignment.Center
                ) {
                    if (iconRes != null) {
                        Icon(
                            painter = painterResource(id = iconRes),
                            contentDescription = iconContentDescription,
                            modifier = Modifier.size(iconSize),
                            tint = textColor
                        )
                    } else {
                        Text(
                            text = text,
                            color = textColor,
                            fontSize = fontSize.value.sp,
                            fontWeight = FontWeight.Medium
                        )
                    }
                }
            }
        }
    } else if (keepSlotWhenHidden) {
        Spacer(modifier = modifier.size(size))
    }
}

@Composable
private fun TopCapsuleLiquidButton(
    visible: Boolean,
    onClick: () -> Unit,
    text: String,
    textColor: Color,
    surfaceColor: Color,
    isDark: Boolean,
    enableLiquidGlass: Boolean,
    backdrop: LayerBackdrop?,
    keepSlotWhenHidden: Boolean = false,
    modifier: Modifier = Modifier,
    hiddenWidth: Dp = 0.dp,
    isDestructive: Boolean = false
) {
    if (visible) {
        val fallbackColor = when {
            isDestructive -> Color(0xFFFF4D4F)
            isDark -> Color(0x33000000)
            else -> Color(0xAAFFFFFF)
        }
        val fallbackTextColor = if (isDestructive) Color.White else textColor

        if (enableLiquidGlass && backdrop != null) {
            LiquidButton(
                onClick = onClick,
                backdrop = backdrop,
                surfaceColor = surfaceColor,
                modifier = modifier.height(IosTopButtonHeight),
                buttonHeight = IosTopButtonHeight,
                contentPadding = PaddingValues(horizontal = IosTopButtonHorizontalPadding)
            ) {
                BasicText(
                    text = text,
                    style = TextStyle(
                        color = if (isDestructive) Color.White else textColor,
                        fontSize = 16.sp,
                        fontWeight = if (isDestructive) FontWeight.Medium else FontWeight.Normal
                    )
                )
            }
        } else {
            Surface(
                modifier = modifier
                    .height(IosTopButtonHeight)
                    .clickable(onClick = onClick),
                shape = Capsule(),
                color = fallbackColor,
                border = if (isDestructive) null else BorderStroke(
                    1.dp,
                    if (isDark) Color(0x33FFFFFF) else Color(0x88FFFFFF)
                )
            ) {
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .padding(horizontal = IosTopButtonHorizontalPadding)
                        .fillMaxHeight()
                ) {
                    Text(text = text, color = fallbackTextColor, fontSize = 16.sp)
                }
            }
        }
    } else if (keepSlotWhenHidden) {
        Spacer(modifier = modifier.width(hiddenWidth).height(IosTopButtonHeight))
    }
}

@Composable
fun MainTopBar(
    statusBarTopPaddingDp: androidx.compose.ui.unit.Dp,
    enableLiquidGlass: Boolean,
    backdrop: LayerBackdrop?,
    isLightTheme: Boolean,
    onAdd: () -> Unit,
    onApply: () -> Unit,
    onMoreClick: () -> Unit,
    onFilterClick: () -> Unit = {},
    onPreview: () -> Unit,
    showAddButton: Boolean = true,
    showPreviewButton: Boolean = true,
    showApplyButton: Boolean = true,
    showMoreButton: Boolean = true,
    showFilterButton: Boolean = true,
    keepSlotWhenHidden: Boolean = true
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = statusBarTopPaddingDp + 8.dp, start = 12.dp, end = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        val isDark = !isLightTheme
        val adaptiveSurfaceColor = if (isDark) Color.Black.copy(0.3f) else Color.White.copy(0.3f)
        val textColor = if (isDark) Color.White else Color.Black

        TopCircleLiquidButton(
            visible = showAddButton,
            onClick = onAdd,
            text = "+",
            textColor = textColor,
            surfaceColor = adaptiveSurfaceColor,
            isDark = isDark,
            enableLiquidGlass = enableLiquidGlass,
            backdrop = backdrop,
            keepSlotWhenHidden = keepSlotWhenHidden
        )

        Spacer(modifier = Modifier.weight(1f))

        TopCapsuleLiquidButton(
            visible = showPreviewButton,
            onClick = onPreview,
            text = "当前播放",
            textColor = textColor,
            surfaceColor = adaptiveSurfaceColor,
            isDark = isDark,
            enableLiquidGlass = enableLiquidGlass,
            backdrop = backdrop,
            keepSlotWhenHidden = keepSlotWhenHidden,
            hiddenWidth = 88.dp
        )

        TopCircleLiquidButton(
            visible = showApplyButton,
            onClick = onApply,
            text = "",
            textColor = textColor,
            surfaceColor = adaptiveSurfaceColor,
            isDark = isDark,
            enableLiquidGlass = enableLiquidGlass,
            backdrop = backdrop,
            keepSlotWhenHidden = keepSlotWhenHidden,
            iconRes = R.drawable.complete,
            iconContentDescription = "完成",
            iconSize = 20.dp
        )

        TopCircleLiquidButton(
            visible = showFilterButton,
            onClick = onFilterClick,
            text = "",
            textColor = textColor,
            surfaceColor = adaptiveSurfaceColor,
            isDark = isDark,
            enableLiquidGlass = enableLiquidGlass,
            backdrop = backdrop,
            keepSlotWhenHidden = keepSlotWhenHidden,
            iconRes = R.drawable.fliter,
            iconContentDescription = "筛选",
            iconSize = 20.dp
        )

        TopCircleLiquidButton(
            visible = showMoreButton,
            onClick = onMoreClick,
            text = "⋯",
            textColor = textColor,
            surfaceColor = adaptiveSurfaceColor,
            isDark = isDark,
            enableLiquidGlass = enableLiquidGlass,
            backdrop = backdrop,
            keepSlotWhenHidden = keepSlotWhenHidden
        )
    }
}

@Composable
fun SelectionTopBar(
    modifier: Modifier = Modifier,
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
        modifier = modifier
            .fillMaxWidth()
            .padding(top = statusBarTopPaddingDp + 8.dp, start = 12.dp, end = 12.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        TopCircleLiquidButton(
            visible = true,
            onClick = onDelete,
            text = "",
            textColor = Color(0xFFFF3B30),
            surfaceColor = adaptiveSurfaceColor,
            isDark = isDark,
            enableLiquidGlass = enableLiquidGlass,
            backdrop = backdrop,
            keepSlotWhenHidden = false,
            iconRes = R.drawable.delete,
            iconContentDescription = context.getString(R.string.common_delete),
            iconSize = 21.dp
        )

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TopCapsuleLiquidButton(
                visible = true,
                onClick = onToggleSelectAll,
                text = if (isAllSelected) "取消全选" else "全选",
                textColor = textColor,
                surfaceColor = adaptiveSurfaceColor,
                isDark = isDark,
                enableLiquidGlass = enableLiquidGlass,
                backdrop = backdrop
            )

            TopCapsuleLiquidButton(
                visible = true,
                onClick = onCancelSelect,
                text = context.getString(R.string.common_cancel),
                textColor = textColor,
                surfaceColor = adaptiveSurfaceColor,
                isDark = isDark,
                enableLiquidGlass = enableLiquidGlass,
                backdrop = backdrop
            )
        }
    }
}
