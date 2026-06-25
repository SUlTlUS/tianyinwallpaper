package com.zeaze.tianyinwallpaper.ui.setting

    import androidx.compose.foundation.background
    import androidx.compose.foundation.clickable
    import androidx.compose.foundation.interaction.MutableInteractionSource
    import androidx.compose.foundation.layout.*
    import androidx.compose.foundation.text.BasicText
    import androidx.compose.material.MaterialTheme
    import androidx.compose.runtime.Composable
    import androidx.compose.runtime.remember
    import androidx.compose.ui.Alignment
    import androidx.compose.ui.Modifier
    import androidx.compose.ui.draw.clip
    import androidx.compose.ui.graphics.BlendMode
    import androidx.compose.ui.graphics.Color
    import androidx.compose.ui.graphics.graphicsLayer
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
    import com.zeaze.tianyinwallpaper.ui.commom.LiquidWindowAnimatedContent

    @Composable
    fun LiquidDialog(
        visible: Boolean = true,
        backdrop: Backdrop,
        isLightTheme: Boolean,
        onDismissRequest: () -> Unit,
        onConfirm: () -> Unit,
        title: String = "提示",
        content: String = "",
        confirmText: String = "确定",
        dismissText: String = "取消",
        customContent: (@Composable ColumnScope.() -> Unit)? = null
    ) {
        val contentColor = if (isLightTheme) Color.Black else Color.White
        val accentColor = MaterialTheme.colors.primary
        val containerColor = if (isLightTheme) Color(0xFFFAFAFA).copy(0.6f) else Color(0xFF121212).copy(0.4f)

        LiquidWindowAnimatedContent(
            targetState = if (visible) Unit else null,
            contentAlignment = Alignment.Center,
            label = "LiquidDialog",
            modifier = Modifier.fillMaxSize()
        ) { isVisible ->
            if (isVisible == null) return@LiquidWindowAnimatedContent
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null
                    ) {
                        onDismissRequest()
                    },
                contentAlignment = Alignment.Center
            ) {
                Column(
                    Modifier
                        .padding(horizontal = 40.dp)
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null
                        ) {}
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

                    if (customContent != null) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(24.dp, 12.dp, 24.dp, 12.dp),
                            content = customContent
                        )
                    } else {
                        BasicText(
                            content,
                            Modifier
                                .graphicsLayer(blendMode = if (isLightTheme) BlendMode.SrcOver else BlendMode.Plus)
                                .padding(24.dp, 12.dp, 24.dp, 12.dp),
                            style = TextStyle(contentColor.copy(0.68f), 15.sp),
                            maxLines = 10
                        )
                    }

                    Row(
                        Modifier
                            .padding(24.dp, 12.dp, 24.dp, 24.dp)
                            .fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        DialogButton(dismissText, containerColor.copy(0.2f), contentColor, onDismissRequest)
                        DialogButton(confirmText, accentColor, Color.White, onConfirm)
                    }
                }
            }
        }
    }

    @Composable
    private fun RowScope.DialogButton(
        text: String,
        bgColor: Color,
        textColor: Color,
        onClick: () -> Unit
    ) {
        Row(
            Modifier
                .clip(Capsule())
                .background(bgColor)
                .clickable(onClick = onClick)
                .height(48.dp)
                .weight(1f),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically
        ) {
            BasicText(text, style = TextStyle(textColor, 16.sp))
        }
    }
