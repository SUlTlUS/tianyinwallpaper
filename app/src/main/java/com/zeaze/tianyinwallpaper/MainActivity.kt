package com.zeaze.tianyinwallpaper

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Point
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import android.view.View
import android.view.WindowInsetsController
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.compose.PredictiveBackHandler
import androidx.activity.compose.setContent
import androidx.appcompat.app.AlertDialog
import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Surface
import androidx.compose.material.Text
import androidx.compose.material.darkColors
import androidx.compose.material.lightColors
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.Lifecycle
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.alibaba.fastjson.JSON
import com.kyant.shapes.Capsule
import com.zeaze.tianyinwallpaper.backdrop.backdrops.LayerBackdrop
import com.zeaze.tianyinwallpaper.backdrop.backdrops.layerBackdrop
import com.zeaze.tianyinwallpaper.backdrop.backdrops.rememberLayerBackdrop
import com.zeaze.tianyinwallpaper.base.BaseActivity
import com.zeaze.tianyinwallpaper.base.rxbus.RxBus
import com.zeaze.tianyinwallpaper.base.rxbus.RxConstants
import com.zeaze.tianyinwallpaper.catalog.components.LiquidBottomTab
import com.zeaze.tianyinwallpaper.catalog.components.LiquidBottomTabs
import com.zeaze.tianyinwallpaper.catalog.components.LiquidButton
import com.zeaze.tianyinwallpaper.model.TianYinWallpaperModel
import com.zeaze.tianyinwallpaper.ui.about.AboutRouteScreen
import com.zeaze.tianyinwallpaper.ui.commom.SaveData
import com.zeaze.tianyinwallpaper.ui.main.MainRouteScreen
import com.zeaze.tianyinwallpaper.ui.main.MainTopBar
import com.zeaze.tianyinwallpaper.ui.main.SelectionBarState
import com.zeaze.tianyinwallpaper.ui.main.SelectionTopBar
import com.zeaze.tianyinwallpaper.ui.setting.SettingRouteScreen
import com.zeaze.tianyinwallpaper.ui.test.CorrugatedTestRouteScreen
import com.zeaze.tianyinwallpaper.ui.test.PlyModelTestRouteScreen
import com.zeaze.tianyinwallpaper.update.AppUpdateManager
import com.zeaze.tianyinwallpaper.update.UpdateDialog
import com.zeaze.tianyinwallpaper.update.UpdateDialogState
import com.zeaze.tianyinwallpaper.utils.FileUtil
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

class MainActivity : BaseActivity() {
    private val bottomTabs: List<Pair<String, String>> = listOf(
        ROUTE_MAIN to "壁纸",
        ROUTE_ABOUT to "壁纸组",
        ROUTE_SETTING to "设置"
    )

    private var showBottomBar by mutableStateOf(true)
    private var showSettingPage by mutableStateOf(false)
    private var showAboutPage by mutableStateOf(false)
    private var pendingRoute by mutableStateOf<String?>(null)

    companion object {
        private const val PERMISSION_REQUEST_CODE = 1
        private const val REQUEST_CODE_SET_WALLPAPER = 0x001
        private const val ROUTE_MAIN = "main"
        private const val ROUTE_ABOUT = "about"
        private const val ROUTE_APP_INFO = "app_info"
        private const val ROUTE_SETTING = "setting"
        private const val ROUTE_CORRUGATED_TEST = "corrugated_test"
        private const val ROUTE_PLY_MODEL_TEST = "ply_model_test"
        const val PREF_THEME_MODE = "themeMode"
        const val THEME_MODE_FOLLOW_SYSTEM = 0
        const val THEME_MODE_LIGHT = 1
        const val THEME_MODE_DARK = 2
        private val BOTTOM_BAR_SELECTED_COLOR = Color(0xFF2A83FF)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)

        val displayMetrics = resources.displayMetrics
        val widthDp = displayMetrics.widthPixels / displayMetrics.density
        if (widthDp < 600) {
            requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        }

        setContent {
            MainActivityScreen()
        }

        val wm = getSystemService(WINDOW_SERVICE) as WindowManager
        val point = Point()
        @Suppress("DEPRECATION")
        wm.defaultDisplay.getRealSize(point)
        FileUtil.width = point.x
        FileUtil.height = point.y
        permission()
        clearNoUseFile()
    }

    @Composable
    private fun MainActivityScreen() {
        val pref = remember(this) { getSharedPreferences(App.TIANYIN, MODE_PRIVATE) }
        var themeMode by remember {
            mutableStateOf(pref.getInt(PREF_THEME_MODE, THEME_MODE_FOLLOW_SYSTEM))
        }
        val useDarkTheme = when (themeMode) {
            THEME_MODE_DARK -> true
            THEME_MODE_LIGHT -> false
            else -> isSystemInDarkTheme()
        }

        val view = LocalView.current
        SideEffect {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                val appearance = if (!useDarkTheme) {
                    WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or
                        WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS
                } else {
                    0
                }
                view.windowInsetsController?.setSystemBarsAppearance(
                    appearance,
                    WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or
                        WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS
                )
            } else {
                @Suppress("DEPRECATION")
                view.systemUiVisibility = if (useDarkTheme) {
                    view.systemUiVisibility and View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR.inv()
                } else {
                    view.systemUiVisibility or View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
                }
            }
        }

        MaterialTheme(colors = if (useDarkTheme) darkColors() else lightColors()) {
            val themeBackgroundColor = MaterialTheme.colors.background
            val enableLiquidGlass = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
            val navController = rememberNavController()
            val navBackStackEntry by navController.currentBackStackEntryAsState()
            val currentRoute = navBackStackEntry?.destination?.route ?: ROUTE_MAIN
            val scope = rememberCoroutineScope()
            val haptic = LocalHapticFeedback.current

            var updateDialogState by remember { mutableStateOf(UpdateDialogState()) }
            var hasCheckedUpdate by remember { mutableStateOf(false) }
            var showMoreMenu by remember { mutableStateOf(false) }
            var wallpaperSelectionState by remember { mutableStateOf(SelectionBarState(false, false)) }

            val liquidBackdrop = if (enableLiquidGlass) {
                rememberLayerBackdrop {
                    drawRect(themeBackgroundColor)
                    drawContent()
                }
            } else {
                null
            }

            LaunchedEffect(pendingRoute) {
                val route = pendingRoute ?: return@LaunchedEffect
                if (currentRoute != route) {
                    navigateToRoute(navController, route)
                }
                pendingRoute = null
            }

            DisposableEffect(Unit) {
                val wallpaperSelectionDisposable = RxBus.getDefault()
                    .toObservableWithCode(RxConstants.RX_SELECTION_MODE_CHANGED, SelectionBarState::class.java)
                    .subscribe { state -> wallpaperSelectionState = state }
                onDispose { wallpaperSelectionDisposable.dispose() }
            }

            val settingPageWidthPx = remember(this@MainActivity) {
                resources.displayMetrics.widthPixels.toFloat().coerceAtLeast(1f)
            }
            val settingPageOffset = remember { Animatable(settingPageWidthPx) }
            var renderSettingPage by remember { mutableStateOf(false) }
            var settingBackDragOffsetPx by remember { mutableStateOf(0f) }
            var settingBackGestureActive by remember { mutableStateOf(false) }

            fun closeSettingPage() {
                if (!renderSettingPage && !showSettingPage) return
                scope.launch {
                    val startOffset = (settingPageOffset.value + settingBackDragOffsetPx)
                        .coerceIn(0f, settingPageWidthPx)
                    settingBackDragOffsetPx = 0f
                    settingBackGestureActive = false
                    settingPageOffset.snapTo(startOffset)
                    showSettingPage = false
                }
            }

            LaunchedEffect(showSettingPage, settingPageWidthPx) {
                if (showSettingPage) {
                    renderSettingPage = true
                    settingBackDragOffsetPx = 0f
                    settingBackGestureActive = false
                    settingPageOffset.snapTo(settingPageWidthPx)
                    settingPageOffset.animateTo(
                        targetValue = 0f,
                        animationSpec = spring(
                            dampingRatio = Spring.DampingRatioNoBouncy,
                            stiffness = Spring.StiffnessMediumLow
                        )
                    )
                } else if (renderSettingPage) {
                    settingBackDragOffsetPx = 0f
                    settingBackGestureActive = false
                    settingPageOffset.animateTo(
                        targetValue = settingPageWidthPx,
                        animationSpec = spring(
                            dampingRatio = Spring.DampingRatioNoBouncy,
                            stiffness = Spring.StiffnessMediumLow
                        )
                    )
                    renderSettingPage = false
                    settingPageOffset.snapTo(settingPageWidthPx)
                }
            }

            PredictiveBackHandler(enabled = showSettingPage && currentRoute == ROUTE_MAIN) { progress ->
                try {
                    progress.collect { backEvent ->
                        settingBackGestureActive = true
                        settingBackDragOffsetPx =
                            (settingPageWidthPx * backEvent.progress).coerceIn(0f, settingPageWidthPx)
                    }
                    val startOffset = (settingPageOffset.value + settingBackDragOffsetPx)
                        .coerceIn(0f, settingPageWidthPx)
                    settingBackDragOffsetPx = 0f
                    settingBackGestureActive = false
                    settingPageOffset.snapTo(startOffset)
                    showSettingPage = false
                } catch (_: CancellationException) {
                    settingBackGestureActive = false
                    settingBackDragOffsetPx = 0f
                    settingPageOffset.animateTo(
                        targetValue = 0f,
                        animationSpec = spring(
                            dampingRatio = Spring.DampingRatioNoBouncy,
                            stiffness = Spring.StiffnessMediumLow
                        )
                    )
                }
            }

            val aboutPageWidthPx = remember(this@MainActivity) {
                resources.displayMetrics.widthPixels.toFloat().coerceAtLeast(1f)
            }
            val aboutPageOffset = remember { Animatable(aboutPageWidthPx) }
            var renderAboutPage by remember { mutableStateOf(false) }
            var aboutBackDragOffsetPx by remember { mutableStateOf(0f) }
            var aboutBackGestureActive by remember { mutableStateOf(false) }

            fun closeAboutPage() {
                if (!renderAboutPage && !showAboutPage) return
                scope.launch {
                    val startOffset = (aboutPageOffset.value + aboutBackDragOffsetPx)
                        .coerceIn(0f, aboutPageWidthPx)
                    aboutBackDragOffsetPx = 0f
                    aboutBackGestureActive = false
                    aboutPageOffset.snapTo(startOffset)
                    showAboutPage = false
                }
            }

            LaunchedEffect(showAboutPage, aboutPageWidthPx) {
                if (showAboutPage) {
                    renderAboutPage = true
                    aboutBackDragOffsetPx = 0f
                    aboutBackGestureActive = false
                    aboutPageOffset.snapTo(aboutPageWidthPx)
                    aboutPageOffset.animateTo(
                        targetValue = 0f,
                        animationSpec = spring(
                            dampingRatio = Spring.DampingRatioNoBouncy,
                            stiffness = Spring.StiffnessMediumLow
                        )
                    )
                } else if (renderAboutPage) {
                    aboutBackDragOffsetPx = 0f
                    aboutBackGestureActive = false
                    aboutPageOffset.animateTo(
                        targetValue = aboutPageWidthPx,
                        animationSpec = spring(
                            dampingRatio = Spring.DampingRatioNoBouncy,
                            stiffness = Spring.StiffnessMediumLow
                        )
                    )
                    renderAboutPage = false
                    aboutPageOffset.snapTo(aboutPageWidthPx)
                }
            }

            PredictiveBackHandler(enabled = showAboutPage && currentRoute == ROUTE_MAIN && !showSettingPage) { progress ->
                try {
                    progress.collect { backEvent ->
                        aboutBackGestureActive = true
                        aboutBackDragOffsetPx =
                            (aboutPageWidthPx * backEvent.progress).coerceIn(0f, aboutPageWidthPx)
                    }
                    val startOffset = (aboutPageOffset.value + aboutBackDragOffsetPx)
                        .coerceIn(0f, aboutPageWidthPx)
                    aboutBackDragOffsetPx = 0f
                    aboutBackGestureActive = false
                    aboutPageOffset.snapTo(startOffset)
                    showAboutPage = false
                } catch (_: CancellationException) {
                    aboutBackGestureActive = false
                    aboutBackDragOffsetPx = 0f
                    aboutPageOffset.animateTo(
                        targetValue = 0f,
                        animationSpec = spring(
                            dampingRatio = Spring.DampingRatioNoBouncy,
                            stiffness = Spring.StiffnessMediumLow
                        )
                    )
                }
            }

            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(themeBackgroundColor)
            ) {
                NavHost(
                    navController = navController,
                    startDestination = ROUTE_MAIN,
                    modifier = Modifier
                        .fillMaxSize()
                        .let { modifier ->
                            if (enableLiquidGlass && liquidBackdrop != null) modifier.layerBackdrop(liquidBackdrop) else modifier
                        }
                ) {
                    composable(ROUTE_MAIN) {
                        MainRouteScreen(
                            useDarkTheme = useDarkTheme,
                            onOpenSettingPage = { openSettingPage() },
                            onBottomBarVisibleChange = { setBottomBarVisible(it) }
                        )
                    }
                    composable(
                        route = ROUTE_APP_INFO,
                        enterTransition = {
                            slideIntoContainer(
                                AnimatedContentTransitionScope.SlideDirection.Left,
                                animationSpec = tween(280)
                            )
                        },
                        popExitTransition = {
                            slideOutOfContainer(
                                AnimatedContentTransitionScope.SlideDirection.Right,
                                animationSpec = tween(280)
                            )
                        }
                    ) {
                        com.zeaze.tianyinwallpaper.ui.setting.AppInfoRouteScreen(
                            useDarkTheme = useDarkTheme
                        )
                    }
                    composable(
                        route = ROUTE_CORRUGATED_TEST,
                        enterTransition = {
                            slideIntoContainer(
                                AnimatedContentTransitionScope.SlideDirection.Left,
                                animationSpec = tween(280)
                            )
                        },
                        popExitTransition = {
                            slideOutOfContainer(
                                AnimatedContentTransitionScope.SlideDirection.Right,
                                animationSpec = tween(280)
                            )
                        }
                    ) {
                        CorrugatedTestRouteScreen(useDarkTheme = useDarkTheme)
                    }
                    composable(
                        route = ROUTE_PLY_MODEL_TEST,
                        enterTransition = {
                            slideIntoContainer(
                                AnimatedContentTransitionScope.SlideDirection.Left,
                                animationSpec = tween(280)
                            )
                        },
                        popExitTransition = {
                            slideOutOfContainer(
                                AnimatedContentTransitionScope.SlideDirection.Right,
                                animationSpec = tween(280)
                            )
                        }
                    ) {
                        PlyModelTestRouteScreen(useDarkTheme = useDarkTheme)
                    }
                }

                val selectedBottomIndex = when {
                    showAboutPage || renderAboutPage -> 1
                    showSettingPage || renderSettingPage -> 2
                    else -> 0
                }
                val shouldShowRootBars = currentRoute == ROUTE_MAIN &&
                    !showSettingPage && !renderSettingPage &&
                    !showAboutPage && !renderAboutPage

                if (shouldShowRootBars) {
                    val density = LocalDensity.current
                    val statusBarTopPadding = remember(this@MainActivity) {
                        val id = resources.getIdentifier("status_bar_height", "dimen", "android")
                        if (id > 0) resources.getDimensionPixelSize(id) else 0
                    }
                    val statusBarTopPaddingDp = with(density) { statusBarTopPadding.toDp() }

                    if (wallpaperSelectionState.selectionMode) {
                        SelectionTopBar(
                            statusBarTopPaddingDp = statusBarTopPaddingDp,
                            enableLiquidGlass = enableLiquidGlass,
                            backdrop = liquidBackdrop,
                            isAllSelected = wallpaperSelectionState.isAllSelected,
                            isLightTheme = !useDarkTheme,
                            onCancelSelect = {
                                RxBus.postWithCode(RxConstants.RX_SELECTION_CANCEL, Unit)
                            },
                            onDelete = {
                                RxBus.postWithCode(RxConstants.RX_SELECTION_DELETE, Unit)
                            },
                            onToggleSelectAll = {
                                RxBus.postWithCode(RxConstants.RX_SELECTION_TOGGLE_ALL, Unit)
                            }
                        )
                    } else if (showBottomBar) {
                        MainTopBar(
                            statusBarTopPaddingDp = statusBarTopPaddingDp,
                            enableLiquidGlass = enableLiquidGlass,
                            backdrop = liquidBackdrop,
                            isLightTheme = !useDarkTheme,
                            onAdd = { RxBus.postWithCode(RxConstants.RX_TRIGGER_ADD_WALLPAPER, Unit) },
                            onApply = { RxBus.postWithCode(RxConstants.RX_TRIGGER_APPLY_WALLPAPER, Unit) },
                            onMoreClick = { showMoreMenu = true },
                            onPreview = { RxBus.postWithCode(RxConstants.RX_TRIGGER_PREVIEW_WALLPAPER, Unit) },
                            showAddButton = false,
                            showPreviewButton = true,
                            showApplyButton = true,
                            showMoreButton = true,
                            keepSlotWhenHidden = true
                        )
                    }

                    RootMoreMenuOverlay(
                        visible = showMoreMenu,
                        statusBarTopPaddingDp = statusBarTopPaddingDp,
                        useDarkTheme = useDarkTheme,
                        enableLiquidGlass = enableLiquidGlass,
                        liquidBackdrop = liquidBackdrop,
                        menuItems = listOf(
                            "保存" to {
                                showMoreMenu = false
                                RxBus.postWithCode(RxConstants.RX_TRIGGER_SAVE_GROUP, Unit)
                            },
                            "选择" to {
                                showMoreMenu = false
                                RxBus.postWithCode(RxConstants.RX_TRIGGER_ENTER_SELECT_MODE, Unit)
                            },
                            "壁纸组" to {
                                showMoreMenu = false
                                openAboutPage()
                            },
                            "设置" to {
                                showMoreMenu = false
                                openSettingPage()
                            }
                        ),
                        onDismiss = { showMoreMenu = false }
                    )
                }

                if (showBottomBar && currentRoute == ROUTE_MAIN) {
                    val bottomInsets = WindowInsets.navigationBars.asPaddingValues()
                    val bottomBarBottomPadding = bottomInsets.calculateBottomPadding() + 12.dp
                    val bottomGroupHorizontalPadding = 18.dp
                    val bottomGroupGap = 8.dp
                    val bottomGroupHeight = 64.dp
                    val bottomActionSize = 64.dp
                    val addButtonSurfaceColor = if (useDarkTheme) Color(0xAA2A2A2E) else Color(0xE6FFFFFF)
                    val addButtonTextColor = if (useDarkTheme) Color.White else Color(0xFF111318)
                    val showBottomAddButton = selectedBottomIndex == 0 &&
                        !wallpaperSelectionState.selectionMode &&
                        !showAboutPage && !renderAboutPage &&
                        !showSettingPage && !renderSettingPage

                    Row(
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .fillMaxWidth()
                            .padding(horizontal = bottomGroupHorizontalPadding)
                            .padding(bottom = bottomBarBottomPadding),
                        horizontalArrangement = Arrangement.spacedBy(bottomGroupGap),
                        verticalAlignment = Alignment.Bottom
                    ) {
                        LiquidBottomTabs(
                            selectedTabIndex = { selectedBottomIndex },
                            onTabSelected = { index ->
                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                when (index) {
                                    0 -> {
                                        if (currentRoute != ROUTE_MAIN) navigateToRoute(navController, ROUTE_MAIN)
                                        showAboutPage = false
                                        showSettingPage = false
                                    }
                                    1 -> {
                                        if (currentRoute != ROUTE_MAIN) navigateToRoute(navController, ROUTE_MAIN)
                                        showSettingPage = false
                                        showAboutPage = true
                                    }
                                    2 -> {
                                        if (currentRoute != ROUTE_MAIN) navigateToRoute(navController, ROUTE_MAIN)
                                        showAboutPage = false
                                        showSettingPage = true
                                    }
                                }
                            },
                            backdrop = if (enableLiquidGlass) liquidBackdrop else null,
                            tabsCount = bottomTabs.size,
                            isLightTheme = !useDarkTheme,
                            modifier = Modifier
                                .weight(1f)
                                .height(bottomGroupHeight)
                        ) {
                            bottomTabs.forEachIndexed { index, (_, title) ->
                                LiquidBottomTab({
                                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                    when (index) {
                                        0 -> {
                                            if (currentRoute != ROUTE_MAIN) navigateToRoute(navController, ROUTE_MAIN)
                                            showAboutPage = false
                                            showSettingPage = false
                                        }
                                        1 -> {
                                            if (currentRoute != ROUTE_MAIN) navigateToRoute(navController, ROUTE_MAIN)
                                            showSettingPage = false
                                            showAboutPage = true
                                        }
                                        2 -> {
                                            if (currentRoute != ROUTE_MAIN) navigateToRoute(navController, ROUTE_MAIN)
                                            showAboutPage = false
                                            showSettingPage = true
                                        }
                                    }
                                }) {
                                    val selected = selectedBottomIndex == index
                                    Text(
                                        text = title,
                                        color = if (selected) BOTTOM_BAR_SELECTED_COLOR else MaterialTheme.colors.onSurface,
                                        fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal
                                    )
                                }
                            }
                        }

                        if (showBottomAddButton) {
                            if (enableLiquidGlass && liquidBackdrop != null) {
                                LiquidButton(
                                    onClick = { RxBus.postWithCode(RxConstants.RX_TRIGGER_ADD_WALLPAPER, Unit) },
                                    backdrop = liquidBackdrop,
                                    surfaceColor = addButtonSurfaceColor,
                                    modifier = Modifier.size(bottomActionSize),
                                    buttonHeight = bottomActionSize,
                                    contentPadding = PaddingValues(0.dp)
                                ) {
                                    BasicText(
                                        text = "+",
                                        style = TextStyle(
                                            color = addButtonTextColor,
                                            fontSize = 32.sp,
                                            fontWeight = FontWeight.Medium
                                        )
                                    )
                                }
                            } else {
                                Box(
                                    modifier = Modifier
                                        .size(bottomActionSize)
                                        .clip(CircleShape)
                                        .background(addButtonSurfaceColor)
                                        .clickable { RxBus.postWithCode(RxConstants.RX_TRIGGER_ADD_WALLPAPER, Unit) },
                                    contentAlignment = Alignment.Center
                                ) {
                                    BasicText(
                                        text = "+",
                                        style = TextStyle(
                                            color = addButtonTextColor,
                                            fontSize = 32.sp,
                                            fontWeight = FontWeight.Medium
                                        )
                                    )
                                }
                            }
                        }
                    }
                }

                if (renderAboutPage || showAboutPage) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .zIndex(8f)
                            .graphicsLayer {
                                translationX = aboutPageOffset.value + aboutBackDragOffsetPx
                                alpha = 1f
                            }
                    ) {
                        AboutRouteScreen(
                            useDarkTheme = useDarkTheme,
                            onBack = { closeAboutPage() }
                        )
                    }
                }

                if (renderSettingPage || showSettingPage) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .zIndex(10f)
                            .graphicsLayer {
                                translationX = settingPageOffset.value + settingBackDragOffsetPx
                                alpha = 1f
                            }
                    ) {
                        SettingRouteScreen(
                            useDarkTheme = useDarkTheme,
                            onThemeModeChange = { mode -> themeMode = mode },
                            onOpenAppInfo = {
                                closeSettingPage()
                                openAppInfoPage()
                            },
                            onOpenCorrugatedTest = {
                                closeSettingPage()
                                openCorrugatedTestPage()
                            },
                            onOpenPlyModelTest = {
                                closeSettingPage()
                                openPlyModelTestPage()
                            }
                        )
                    }
                }
            }

            UpdateDialog(
                state = updateDialogState,
                parentBackdrop = liquidBackdrop,
                onDismiss = {
                    updateDialogState = UpdateDialogState(isVisible = false)
                },
                onConfirm = {
                    val info = updateDialogState.updateInfo
                    if (info != null) {
                        updateDialogState = updateDialogState.copy(
                            isDownloading = true,
                            downloadProgress = 0
                        )
                        AppUpdateManager.downloadApk(this@MainActivity, info, object : AppUpdateManager.DownloadCallback {
                            override fun onProgress(progress: Int) {
                                updateDialogState = updateDialogState.copy(downloadProgress = progress)
                            }

                            override fun onSuccess(file: File) {
                                updateDialogState = updateDialogState.copy(isDownloading = false)
                                val md5 = AppUpdateManager.calculateMD5(file)
                                if (md5 != null && md5.equals(info.md5, ignoreCase = true)) {
                                    AppUpdateManager.installApk(this@MainActivity, file)
                                } else {
                                    Toast.makeText(this@MainActivity, "文件校验失败，请重新下载", Toast.LENGTH_SHORT).show()
                                }
                            }

                            override fun onError(message: String) {
                                updateDialogState = updateDialogState.copy(
                                    isDownloading = false,
                                    errorMessage = message
                                )
                                Toast.makeText(this@MainActivity, message, Toast.LENGTH_SHORT).show()
                            }
                        })
                    }
                },
                isLightTheme = !useDarkTheme
            )

            LaunchedEffect(Unit) {
                if (!hasCheckedUpdate) {
                    hasCheckedUpdate = true
                    when (val result = AppUpdateManager.checkUpdate()) {
                        is AppUpdateManager.CheckResult.HasUpdate -> {
                            updateDialogState = UpdateDialogState(
                                isVisible = true,
                                isChecking = false,
                                updateInfo = result.updateInfo
                            )
                        }
                        is AppUpdateManager.CheckResult.NoUpdate -> Unit
                        is AppUpdateManager.CheckResult.Error -> {
                            Log.w("MainActivity", "检查更新失败: ${result.message}")
                        }
                    }
                }
            }
        }
    }

    private fun navigateToRoute(navController: NavHostController, route: String) {
        if (navController.currentBackStackEntry?.destination?.route == route) return
        navController.navigate(route) {
            launchSingleTop = true
            restoreState = true
        }
    }

    private fun openAboutPage() {
        showAboutPage = true
    }

    private fun openSettingPage() {
        showSettingPage = true
    }

    private fun openAppInfoPage() {
        pendingRoute = ROUTE_APP_INFO
    }

    private fun openCorrugatedTestPage() {
        pendingRoute = ROUTE_CORRUGATED_TEST
    }

    private fun openPlyModelTestPage() {
        pendingRoute = ROUTE_PLY_MODEL_TEST
    }

    private fun setBottomBarVisible(visible: Boolean) {
        runOnUiThread {
            showBottomBar = visible
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_CODE_SET_WALLPAPER) {
            if (resultCode == RESULT_OK) {
                Toast.makeText(this, "设置动态壁纸成功", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this, "取消设置动态壁纸", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun clearNoUseFile() {
        Thread {
            try {
                val uuids = mutableListOf<String>()
                val file = File(getExternalFilesDir(null), FileUtil.wallpaperFilePath)
                if (!file.exists()) file.mkdirs()

                var dataStr = FileUtil.loadData(this@MainActivity, FileUtil.dataPath)
                val saveDataList = JSON.parseArray(dataStr, SaveData::class.java) ?: emptyList()
                saveDataList.forEach { saveData ->
                    JSON.parseArray(saveData.s, TianYinWallpaperModel::class.java)
                        ?.forEach { model -> model.uuid?.let { uuids.add(it) } }
                }

                getSharedPreferences("tianyin", MODE_PRIVATE).getString("wallpaperCache", "")
                    ?.let { cache ->
                        if (cache.isNotEmpty()) {
                            JSON.parseArray(cache, TianYinWallpaperModel::class.java)
                                ?.forEach { model -> model.uuid?.let { uuids.add(it) } }
                        }
                    }

                dataStr = FileUtil.loadData(applicationContext, FileUtil.wallpaperPath)
                JSON.parseArray(dataStr, TianYinWallpaperModel::class.java)
                    ?.forEach { model -> model.uuid?.let { uuids.add(it) } }

                file.listFiles()?.forEach { paper ->
                    val keep = uuids.any { uuid -> paper.name.startsWith(uuid) }
                    if (!keep) paper.delete()
                }
            } catch (e: Exception) {
                Log.e("MainActivity", "Failed to clear unused wallpaper files", e)
            }
        }.start()
    }

    private fun permission() {
        val permissionList = mutableListOf<String>()
        val requiredPermissions = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            listOf(
                Manifest.permission.INTERNET,
                Manifest.permission.SET_WALLPAPER,
                Manifest.permission.READ_MEDIA_IMAGES,
                Manifest.permission.READ_MEDIA_VIDEO
            )
        } else {
            listOf(
                Manifest.permission.INTERNET,
                Manifest.permission.SET_WALLPAPER,
                Manifest.permission.READ_EXTERNAL_STORAGE
            )
        }

        requiredPermissions.forEach { perm ->
            if (ContextCompat.checkSelfPermission(this, perm) != PackageManager.PERMISSION_GRANTED) {
                permissionList.add(perm)
            }
        }

        if (permissionList.isNotEmpty()) {
            ActivityCompat.requestPermissions(
                this,
                permissionList.toTypedArray(),
                PERMISSION_REQUEST_CODE
            )
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != PERMISSION_REQUEST_CODE) return
        for (i in grantResults.indices) {
            if (grantResults[i] == PackageManager.PERMISSION_DENIED) {
                AlertDialog.Builder(this)
                    .setMessage("没有获取到${permissions[i]}权限，无法使用，请去系统设置里开启权限")
                    .setPositiveButton("去设置") { _, _ ->
                        val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                            data = Uri.fromParts("package", packageName, null)
                        }
                        startActivity(intent)
                    }
                    .setNegativeButton("取消", null)
                    .setCancelable(false)
                    .create()
                    .show()
                return
            }
        }
    }
}

@Composable
private fun RootMoreMenuOverlay(
    visible: Boolean,
    statusBarTopPaddingDp: Dp,
    useDarkTheme: Boolean,
    enableLiquidGlass: Boolean,
    liquidBackdrop: LayerBackdrop?,
    menuItems: List<Pair<String, () -> Unit>>,
    onDismiss: () -> Unit
) {
    if (!visible) return

    Box(
        modifier = Modifier
            .fillMaxSize()
            .zIndex(20f)
            .clickable(onClick = onDismiss)
    ) {
        val surfaceColor = if (useDarkTheme) Color(0xE61C1C20) else Color(0xF2FFFFFF)
        val textColor = if (useDarkTheme) Color.White else Color(0xFF16181D)
        val itemHeight = 44.dp
        Surface(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(top = statusBarTopPaddingDp + 58.dp, end = 12.dp)
                .clip(RoundedCornerShape(22.dp)),
            shape = RoundedCornerShape(22.dp),
            color = surfaceColor,
            elevation = if (enableLiquidGlass && liquidBackdrop != null) 0.dp else 8.dp
        ) {
            Column(
                modifier = Modifier
                    .background(surfaceColor)
                    .padding(vertical = 6.dp)
            ) {
                menuItems.forEachIndexed { index, (title, action) ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(itemHeight)
                            .clickable { action() }
                            .padding(horizontal = 18.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = title,
                            color = textColor,
                            fontSize = 15.sp,
                            fontWeight = if (index == 0) FontWeight.Medium else FontWeight.Normal
                        )
                    }
                }
            }
        }
    }
}
