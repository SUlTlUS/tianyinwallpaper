package com.zeaze.tianyinwallpaper.ui.main

import androidx.annotation.DrawableRes
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
import com.zeaze.tianyinwallpaper.catalog.components.LiquidButtonGroup
import com.zeaze.tianyinwallpaper.catalog.components.PlainFallbackStyle

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
                color = PlainFallbackStyle.surface(isLightTheme = !isDark),
                border = PlainFallbackStyle.border(isLightTheme = !isDark)
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
            else -> PlainFallbackStyle.surface(isLightTheme = !isDark)
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
                border = if (isDestructive) null else PlainFallbackStyle.border(isLightTheme = !isDark)
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
private fun TopSortFilterLiquidGroup(
    showSortButton: Boolean,
    showFilterButton: Boolean,
    onSortClick: () -> Unit,
    onFilterClick: () -> Unit,
    textColor: Color,
    surfaceColor: Color,
    isDark: Boolean,
    enableLiquidGlass: Boolean,
    backdrop: LayerBackdrop?
) {
    val buttonCount = (if (showSortButton) 1 else 0) + (if (showFilterButton) 1 else 0)
    if (buttonCount <= 0) return

    fun isSortItem(index: Int): Boolean = showSortButton && index == 0

    if (enableLiquidGlass && backdrop != null) {
        LiquidButtonGroup(
            buttonCount = buttonCount,
            onButtonClick = { index ->
                if (isSortItem(index)) onSortClick() else onFilterClick()
            },
            backdrop = backdrop,
            surfaceColor = surfaceColor,
            buttonHeight = IosTopButtonHeight,
            contentPadding = PaddingValues(0.dp),
            iconRes = { index -> if (isSortItem(index)) R.drawable.sort else R.drawable.fliter },
            iconContentDescription = { index -> if (isSortItem(index)) "\u6392\u5e8f" else "\u7b5b\u9009" },
            iconSize = { 20.dp },
            iconTint = { textColor }
        )
    } else {
        Surface(
            modifier = Modifier
                .height(IosTopButtonHeight)
                .width(IosTopButtonHeight * buttonCount),
            shape = Capsule(),
            color = PlainFallbackStyle.surface(isLightTheme = !isDark),
            border = PlainFallbackStyle.border(isLightTheme = !isDark)
        ) {
            Row(
                modifier = Modifier.fillMaxSize(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                repeat(buttonCount) { index ->
                    val sortItem = isSortItem(index)
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight()
                            .clickable(onClick = if (sortItem) onSortClick else onFilterClick),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            painter = painterResource(id = if (sortItem) R.drawable.sort else R.drawable.fliter),
                            contentDescription = if (sortItem) "\u6392\u5e8f" else "\u7b5b\u9009",
                            modifier = Modifier.size(20.dp),
                            tint = textColor
                        )
                    }
                }
            }
        }
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
    onSortClick: () -> Unit = {},
    onFilterClick: () -> Unit = {},
    onPreview: () -> Unit,
    showAddButton: Boolean = true,
    showPreviewButton: Boolean = true,
    showApplyButton: Boolean = true,
    showMoreButton: Boolean = true,
    showSortButton: Boolean = false,
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
            iconSize = 16.dp
        )

        TopSortFilterLiquidGroup(
            showSortButton = showSortButton,
            showFilterButton = showFilterButton,
            onSortClick = onSortClick,
            onFilterClick = onFilterClick,
            textColor = textColor,
            surfaceColor = adaptiveSurfaceColor,
            isDark = isDark,
            enableLiquidGlass = enableLiquidGlass,
            backdrop = backdrop
        )

        TopCircleLiquidButton(
            visible = showMoreButton,
            onClick = onMoreClick,
            text = "",
            textColor = textColor,
            surfaceColor = adaptiveSurfaceColor,
            isDark = isDark,
            enableLiquidGlass = enableLiquidGlass,
            backdrop = backdrop,
            keepSlotWhenHidden = keepSlotWhenHidden,
            iconRes = R.drawable.more,
            iconContentDescription = "更多",
            iconSize = 20.dp
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
