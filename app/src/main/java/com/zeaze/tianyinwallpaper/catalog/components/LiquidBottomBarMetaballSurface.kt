package com.zeaze.tianyinwallpaper.catalog.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.kyant.shapes.Capsule
import com.zeaze.tianyinwallpaper.backdrop.Backdrop
import com.zeaze.tianyinwallpaper.backdrop.drawBackdrop
import com.zeaze.tianyinwallpaper.backdrop.effects.blur
import com.zeaze.tianyinwallpaper.backdrop.effects.bottomBarMetaballMask
import com.zeaze.tianyinwallpaper.backdrop.effects.lens
import com.zeaze.tianyinwallpaper.backdrop.effects.vibrancy

/**
 * 底部栏统一融球玻璃层。
 *
 * 正确结构：
 * - 这个组件负责画整个底部栏的玻璃背景；
 * - shader 同时描述左侧长胶囊和右侧加号圆，并通过 smooth union 融合；
 * - Tab 文本、选中态、加号内容应该作为普通内容叠在上面；
 * - 不要再在中间插入 LiquidMetaballBridge，也不要让左右两个子控件各自重复画玻璃背景。
 */
@Composable
fun LiquidBottomBarMetaballSurface(
    backdrop: Backdrop,
    isLightTheme: Boolean,
    actionSize: Dp,
    gap: Dp,
    modifier: Modifier = Modifier,
    height: Dp = actionSize,
    smoothness: Dp = 34.dp,
    blurRadius: Dp = 8.dp,
    lensRadius: Dp = 24.dp,
    surfaceColor: Color = if (isLightTheme) Color(0xFFFAFAFA).copy(0.34f) else Color(0xFF121212).copy(0.34f),
    content: @Composable BoxScope.() -> Unit
) {
    Box(
        modifier = modifier.height(height),
        contentAlignment = Alignment.Center
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .drawBackdrop(
                    backdrop = backdrop,
                    shape = { Capsule() },
                    effects = {
                        vibrancy()
                        blur(blurRadius.toPx())
                        lens(lensRadius.toPx(), lensRadius.toPx(), chromaticAberration = true)
                        bottomBarMetaballMask(
                            gap = gap.toPx(),
                            actionSize = actionSize.toPx(),
                            smoothness = smoothness.toPx(),
                            opacity = 1f
                        )
                    },
                    onDrawSurface = {
                        drawRect(surfaceColor)
                    }
                )
        )
        content()
    }
}
