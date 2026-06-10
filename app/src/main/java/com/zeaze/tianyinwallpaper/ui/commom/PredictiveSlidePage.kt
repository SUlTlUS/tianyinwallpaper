package com.zeaze.tianyinwallpaper.ui.common

import androidx.activity.compose.PredictiveBackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.zIndex
import kotlinx.coroutines.launch

@Composable
fun PredictiveSlidePage(
    visible: Boolean,
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    zIndex: Float = 20f,
    content: @Composable () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val pageWidthPx = remember(context) { context.resources.displayMetrics.widthPixels.toFloat().coerceAtLeast(1f) }
    val offset = remember { Animatable(pageWidthPx) }
    var renderPage by remember { mutableStateOf(false) }
    var backDragOffsetPx by remember { mutableStateOf(0f) }
    var backGestureActive by remember { mutableStateOf(false) }

    LaunchedEffect(visible, pageWidthPx) {
        if (visible) {
            renderPage = true
            backDragOffsetPx = 0f
            backGestureActive = false
            offset.snapTo(pageWidthPx)
            offset.animateTo(0f, animationSpec = tween(300, easing = FastOutSlowInEasing))
        } else if (renderPage && !backGestureActive) {
            offset.animateTo(pageWidthPx, animationSpec = tween(260, easing = FastOutSlowInEasing))
            renderPage = false
            backDragOffsetPx = 0f
        }
    }

    PredictiveBackHandler(enabled = visible || renderPage) { progress ->
        backGestureActive = true
        try {
            progress.collect { event ->
                backDragOffsetPx = pageWidthPx * event.progress
            }
            scope.launch {
                offset.snapTo(backDragOffsetPx.coerceIn(0f, pageWidthPx))
                backDragOffsetPx = 0f
                offset.animateTo(pageWidthPx, animationSpec = tween(220, easing = FastOutSlowInEasing))
                renderPage = false
                backGestureActive = false
                onDismissRequest()
            }
        } catch (_: Throwable) {
            scope.launch {
                offset.animateTo(0f, animationSpec = tween(160, easing = FastOutSlowInEasing))
                backDragOffsetPx = 0f
                backGestureActive = false
            }
        }
    }

    if (renderPage || visible) {
        Box(
            modifier = modifier
                .fillMaxSize()
                .zIndex(zIndex)
                .graphicsLayer {
                    translationX = offset.value + backDragOffsetPx
                    alpha = 1f
                }
        ) {
            content()
        }
    }
}
