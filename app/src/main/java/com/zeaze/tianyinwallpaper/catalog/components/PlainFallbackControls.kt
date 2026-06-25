package com.zeaze.tianyinwallpaper.catalog.components

import androidx.annotation.DrawableRes
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.Icon
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Slider
import androidx.compose.material.SliderDefaults
import androidx.compose.material.Surface
import androidx.compose.material.Switch
import androidx.compose.material.SwitchDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.kyant.shapes.Capsule

object PlainFallbackStyle {
    fun surface(isLightTheme: Boolean): Color =
        if (isLightTheme) Color(0xFFF2F3F7) else Color(0xFF1C1C20).copy(alpha = 0.94f)

    fun elevatedSurface(isLightTheme: Boolean): Color =
        if (isLightTheme) Color.White else Color(0xFF242429)

    fun borderColor(isLightTheme: Boolean): Color =
        if (isLightTheme) Color.Black.copy(alpha = 0.08f) else Color.White.copy(alpha = 0.10f)

    fun border(isLightTheme: Boolean): BorderStroke =
        BorderStroke(1.dp, borderColor(isLightTheme))
}

@Composable
fun PlainCircleButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    size: Dp = 44.dp,
    surfaceColor: Color,
    contentColor: Color,
    border: BorderStroke? = null,
    content: @Composable () -> Unit
) {
    Surface(
        modifier = modifier
            .size(size)
            .clickable(onClick = onClick),
        shape = CircleShape,
        color = surfaceColor,
        border = border
    ) {
        Box(contentAlignment = Alignment.Center) {
            content()
        }
    }
}

@Composable
fun PlainIconButton(
    onClick: () -> Unit,
    @DrawableRes iconRes: Int,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    size: Dp = 44.dp,
    iconSize: Dp = 20.dp,
    surfaceColor: Color,
    contentColor: Color,
    border: BorderStroke? = null
) {
    PlainCircleButton(
        onClick = onClick,
        modifier = modifier,
        size = size,
        surfaceColor = surfaceColor,
        contentColor = contentColor,
        border = border
    ) {
        Icon(
            painter = painterResource(iconRes),
            contentDescription = contentDescription,
            modifier = Modifier.size(iconSize),
            tint = contentColor
        )
    }
}

@Composable
fun PlainCapsuleButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    height: Dp = 44.dp,
    surfaceColor: Color,
    border: BorderStroke? = null,
    contentPadding: PaddingValues = PaddingValues(horizontal = 16.dp),
    content: @Composable () -> Unit
) {
    Surface(
        modifier = modifier
            .height(height)
            .clickable(onClick = onClick),
        shape = Capsule(),
        color = surfaceColor,
        border = border
    ) {
        Box(
            modifier = Modifier.padding(contentPadding),
            contentAlignment = Alignment.Center
        ) {
            content()
        }
    }
}

@Composable
fun PlainSwitch(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    isLightTheme: Boolean
) {
    Switch(
        checked = checked,
        onCheckedChange = onCheckedChange,
        colors = SwitchDefaults.colors(
            checkedThumbColor = Color.White,
            checkedTrackColor = if (isLightTheme) Color(0xFF34C759) else Color(0xFF30D158),
            uncheckedThumbColor = if (isLightTheme) Color.White else Color(0xFFE5E5EA),
            uncheckedTrackColor = if (isLightTheme) Color(0xFF787880).copy(alpha = 0.28f)
            else Color(0xFF787880).copy(alpha = 0.42f)
        )
    )
}

@Composable
fun PlainSlider(
    value: Float,
    onValueChange: (Float) -> Unit,
    valueRange: ClosedFloatingPointRange<Float>,
    onValueChangeFinished: () -> Unit,
    modifier: Modifier = Modifier,
    contentColor: Color = if (MaterialTheme.colors.isLight) Color.Black else Color.White
) {
    Slider(
        value = value,
        onValueChange = onValueChange,
        valueRange = valueRange,
        onValueChangeFinished = onValueChangeFinished,
        modifier = modifier,
        colors = SliderDefaults.colors(
            thumbColor = MaterialTheme.colors.primary,
            activeTrackColor = MaterialTheme.colors.primary,
            inactiveTrackColor = contentColor.copy(alpha = 0.18f)
        )
    )
}
