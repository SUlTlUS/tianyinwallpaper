package com.zeaze.tianyinwallpaper.catalog.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.sp
import com.zeaze.tianyinwallpaper.backdrop.Backdrop

/**
 * 组合原来的底部液态玻璃组件，并给加号按钮增加拖拽驱动融球。
 *
 * 保留：
 * - [LiquidBottomTabs] 原有 Tab 选中/拖动/隐藏参考层逻辑；
 * - [LiquidButton] 原有玻璃按钮和交互形变；
 * - 低版本无 backdrop 时仍走原来的普通 fallback 按钮，不画假融球。
 *
 * 新增：
 * - Android 13+ 且有 [Backdrop] 时，右侧加号按钮可拖拽；
 * - 融球强度根据加号按钮与左侧 Tab 胶囊的距离实时变化；
 * - 松手后按钮弹回，neck 收缩消失。
 */
@Composable
fun LiquidBottomBarWithMetaball(
    selectedTabIndex: () -> Int,
    onTabSelected: (index: Int) -> Unit,
    tabs: List<String>,
    backdrop: Backdrop?,
    isLightTheme: Boolean,
    onAddClick: () -> Unit,
    modifier: Modifier = Modifier,
    groupGap: Dp = 8.dp,
    groupHeight: Dp = 64.dp,
    actionSize: Dp = 64.dp,
    selectedColor: Color = Color(0xFF2A83FF),
    addTextColor: Color = if (isLightTheme) Color(0xFF111318) else Color.White,
    fallbackAddSurfaceColor: Color = if (isLightTheme) Color(0xE6FFFFFF) else Color(0xAA2A2A2E)
) {
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(groupGap),
        verticalAlignment = Alignment.Bottom
    ) {
        LiquidBottomTabs(
            selectedTabIndex = selectedTabIndex,
            onTabSelected = onTabSelected,
            backdrop = backdrop,
            tabsCount = tabs.size,
            isLightTheme = isLightTheme,
            modifier = Modifier
                .weight(1f)
                .height(groupHeight)
        ) {
            tabs.forEachIndexed { index, title ->
                LiquidBottomTab({ onTabSelected(index) }) {
                    val selected = selectedTabIndex() == index
                    Text(
                        text = title,
                        color = if (selected) selectedColor else MaterialTheme.colors.onSurface,
                        fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal
                    )
                }
            }
        }

        if (backdrop != null) {
            LiquidDraggableMetaballButton(
                onClick = onAddClick,
                backdrop = backdrop,
                isLightTheme = isLightTheme,
                modifier = Modifier.size(actionSize),
                actionSize = actionSize,
                gapToAnchor = groupGap,
                content = {
                    PlusText(addTextColor)
                }
            )
        } else {
            Box(
                modifier = Modifier
                    .size(actionSize)
                    .clip(CircleShape)
                    .background(fallbackAddSurfaceColor)
                    .clickable { onAddClick() },
                contentAlignment = Alignment.Center
            ) {
                PlusText(addTextColor)
            }
        }
    }
}

@Composable
private fun PlusText(color: Color) {
    BasicText(
        text = "+",
        style = TextStyle(
            color = color,
            fontSize = 32.sp,
            fontWeight = FontWeight.Medium
        )
    )
}
