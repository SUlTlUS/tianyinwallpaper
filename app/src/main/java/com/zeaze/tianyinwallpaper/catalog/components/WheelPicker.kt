package com.zeaze.tianyinwallpaper.catalog.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.LocalOverscrollFactory
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.pager.PagerDefaults
import androidx.compose.foundation.pager.PagerSnapDistance
import androidx.compose.foundation.pager.VerticalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun WheelPicker(
    count: Int,
    initialIndex: Int,
    onItemSelected: (Int) -> Unit,
    contentColor: Color,
    label: String,
    modifier: Modifier = Modifier
) {
    val haptic = LocalHapticFeedback.current
    val pagerState = rememberPagerState(initialPage = initialIndex, pageCount = { count })
    val coroutineScope = rememberCoroutineScope()
    val overscrollOffset = remember { Animatable(0f) }

    LaunchedEffect(pagerState.currentPage) {
        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
        onItemSelected(pagerState.currentPage)
    }

    val nestedScrollConnection = remember {
        object : NestedScrollConnection {
            override fun onPostScroll(
                consumed: Offset,
                available: Offset,
                source: NestedScrollSource
            ): Offset {
                if (available.y != 0f) {
                    val isAtTop = pagerState.currentPage == 0 && available.y > 0
                    val isAtBottom = pagerState.currentPage == count - 1 && available.y < 0
                    if (isAtTop || isAtBottom) {
                        coroutineScope.launch {
                            overscrollOffset.snapTo(overscrollOffset.value + available.y * 0.4f)
                        }
                        return Offset(0f, available.y)
                    }
                }
                return Offset.Zero
            }

            override suspend fun onPreFling(available: Velocity): Velocity {
                if (Math.abs(overscrollOffset.value) > 0.1f) {
                    return available
                }
                return Velocity.Zero
            }

            override suspend fun onPostFling(consumed: Velocity, available: Velocity): Velocity {
                overscrollOffset.animateTo(
                    0f,
                    spring(stiffness = Spring.StiffnessLow, dampingRatio = Spring.DampingRatioMediumBouncy)
                )
                return super.onPostFling(consumed, available)
            }
        }
    }

    Row(
        modifier = modifier
            .nestedScroll(nestedScrollConnection)
            .graphicsLayer {
                translationY = overscrollOffset.value
                val scale = 1f - (kotlin.math.abs(overscrollOffset.value) / 1000f).coerceAtMost(0.05f)
                scaleX = scale
                scaleY = scale
            },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center
    ) {
        Box(modifier = Modifier.weight(1f)) {
            CompositionLocalProvider(
                LocalOverscrollFactory provides null
            ) {
                VerticalPager(
                    state = pagerState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(vertical = 60.dp),
                    flingBehavior = PagerDefaults.flingBehavior(
                        state = pagerState,
                        pagerSnapDistance = PagerSnapDistance.atMost(count)
                    )
                ) { page ->
                    val isSelected = pagerState.currentPage == page
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = if (page < 10) "0$page" else "$page",
                            style = TextStyle(
                                color = if (isSelected) contentColor else contentColor.copy(alpha = 0.3f),
                                fontSize = if (isSelected) 22.sp else 18.sp,
                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
                            )
                        )
                    }
                }
            }
        }
        Text(
            text = label,
            style = TextStyle(color = contentColor, fontSize = 14.sp),
            modifier = Modifier.padding(start = 4.dp, end = 8.dp)
        )
    }
}
