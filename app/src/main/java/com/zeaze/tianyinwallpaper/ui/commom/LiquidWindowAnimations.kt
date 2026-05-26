package com.zeaze.tianyinwallpaper.ui.commom

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.TransformOrigin

enum class LiquidWindowAnimationMode {
    Dialog,
    BottomSheet
}

@Composable
fun LiquidWindowAnimatedVisibility(
    visible: Boolean,
    modifier: Modifier = Modifier,
    mode: LiquidWindowAnimationMode = LiquidWindowAnimationMode.Dialog,
    label: String = "LiquidWindowAnimatedVisibility",
    content: @Composable () -> Unit
) {
    AnimatedVisibility(
        visible = visible,
        enter = liquidWindowEnter(mode),
        exit = liquidWindowExit(mode),
        modifier = modifier,
        label = label
    ) {
        content()
    }
}

@Composable
fun <T> LiquidWindowAnimatedContent(
    targetState: T?,
    modifier: Modifier = Modifier,
    contentAlignment: Alignment = Alignment.Center,
    label: String = "LiquidWindowAnimatedContent",
    content: @Composable (T?) -> Unit
) {
    var lastVisibleState by remember { mutableStateOf<T?>(targetState) }

    LaunchedEffect(targetState) {
        if (targetState != null) {
            lastVisibleState = targetState
        }
    }

    Box(
        modifier = modifier,
        contentAlignment = contentAlignment
    ) {
        AnimatedVisibility(
            visible = targetState != null,
            enter = liquidWindowEnter(LiquidWindowAnimationMode.Dialog),
            exit = liquidWindowExit(LiquidWindowAnimationMode.Dialog),
            label = label
        ) {
            content(lastVisibleState)
        }
    }
}

private fun liquidWindowEnter(mode: LiquidWindowAnimationMode): EnterTransition {
    return when (mode) {
        LiquidWindowAnimationMode.Dialog -> {
            fadeIn(animationSpec = spring(stiffness = Spring.StiffnessMediumLow)) +
                scaleIn(
                    initialScale = 0.86f,
                    transformOrigin = TransformOrigin.Center,
                    animationSpec = spring(
                        dampingRatio = 0.78f,
                        stiffness = Spring.StiffnessMediumLow
                    )
                )
        }

        LiquidWindowAnimationMode.BottomSheet -> {
            slideInVertically(
                initialOffsetY = { it },
                animationSpec = spring(
                    dampingRatio = 0.78f,
                    stiffness = Spring.StiffnessMediumLow
                )
            ) +
                fadeIn(animationSpec = spring(stiffness = Spring.StiffnessMediumLow)) +
                scaleIn(
                    initialScale = 0.96f,
                    transformOrigin = TransformOrigin(0.5f, 1f),
                    animationSpec = spring(
                        dampingRatio = 0.78f,
                        stiffness = Spring.StiffnessMediumLow
                    )
                )
        }
    }
}

private fun liquidWindowExit(mode: LiquidWindowAnimationMode): ExitTransition {
    return when (mode) {
        LiquidWindowAnimationMode.Dialog -> {
            fadeOut(animationSpec = spring(stiffness = Spring.StiffnessMediumLow)) +
                scaleOut(
                    targetScale = 0.86f,
                    transformOrigin = TransformOrigin.Center,
                    animationSpec = spring(
                        dampingRatio = 0.78f,
                        stiffness = Spring.StiffnessMediumLow
                    )
                )
        }

        LiquidWindowAnimationMode.BottomSheet -> {
            slideOutVertically(
                targetOffsetY = { it },
                animationSpec = spring(
                    dampingRatio = 0.78f,
                    stiffness = Spring.StiffnessMediumLow
                )
            ) +
                fadeOut(animationSpec = spring(stiffness = Spring.StiffnessMediumLow)) +
                scaleOut(
                    targetScale = 0.96f,
                    transformOrigin = TransformOrigin(0.5f, 1f),
                    animationSpec = spring(
                        dampingRatio = 0.78f,
                        stiffness = Spring.StiffnessMediumLow
                    )
                )
        }
    }
}
