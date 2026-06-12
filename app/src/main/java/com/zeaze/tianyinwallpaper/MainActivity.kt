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
import androidx.activity.compose.setContent
import androidx.annotation.DrawableRes
import androidx.appcompat.app.AlertDialog
import androidx.compose.animation.ExperimentalAnimationApi
import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.text.BasicText
import androidx.compose.material.Icon
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
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.alibaba.fastjson.JSON
import com.kyant.shapes.Capsule
import com.kyant.shapes.RoundedRectangle
import com.zeaze.tianyinwallpaper.backdrop.backdrops.LayerBackdrop
import com.zeaze.tianyinwallpaper.backdrop.backdrops.layerBackdrop
import com.zeaze.tianyinwallpaper.backdrop.backdrops.rememberLayerBackdrop
import com.zeaze.tianyinwallpaper.backdrop.drawBackdrop
import com.zeaze.tianyinwallpaper.backdrop.effects.blur
import com.zeaze.tianyinwallpaper.backdrop.effects.lens
import com.zeaze.tianyinwallpaper.backdrop.effects.vibrancy
import com.zeaze.tianyinwallpaper.base.BaseActivity
import com.zeaze.tianyinwallpaper.base.rxbus.RxBus
import com.zeaze.tianyinwallpaper.base.rxbus.RxConstants
import com.zeaze.tianyinwallpaper.catalog.components.LiquidBottomTab
import com.zeaze.tianyinwallpaper.catalog.components.LiquidBottomTabs
import com.zeaze.tianyinwallpaper.catalog.components.LiquidBottomTabsStyle
import com.zeaze.tianyinwallpaper.catalog.components.LiquidButton
import com.zeaze.tianyinwallpaper.catalog.components.LiquidButtonStyle
import com.zeaze.tianyinwallpaper.catalog.utils.LiquidMorphPhysics
import com.zeaze.tianyinwallpaper.catalog.utils.rememberLiquidMorphController
import com.zeaze.tianyinwallpaper.model.TianYinWallpaperModel
import com.zeaze.tianyinwallpaper.ui.about.AboutRouteScreen
import com.zeaze.tianyinwallpaper.ui.commom.SaveData
import com.zeaze.tianyinwallpaper.ui.main.MainRouteScreen
import com.zeaze.tianyinwallpaper.ui.main.MainTopBar
import com.zeaze.tianyinwallpaper.ui.main.MainWallpaperKindFilter
import com.zeaze.tianyinwallpaper.ui.main.MainWallpaperSortDirection
import com.zeaze.tianyinwallpaper.ui.main.MainWallpaperSortMode
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
import kotlinx.coroutines.launch

private data class LiquidMoreMenuItem(
    val label: String,
    @param:DrawableRes val iconRes: Int? = null,
    val checked: Boolean = false,
    val onClick: () -> Unit
)

private val MainWallpaperKindFilter.iconRes: Int
    @DrawableRes get() = when (this) {
        MainWallpaperKindFilter.ImageWallpaper -> R.drawable.picture
        MainWallpaperKindFilter.VideoWallpaper -> R.drawable.video
        MainWallpaperKindFilter.StaticRaster -> R.drawable.pictureraster
        MainWallpaperKindFilter.VideoRaster -> R.drawable.videoraster
        MainWallpaperKindFilter.Depth -> R.drawable.depth
    }

class MainActivity : BaseActivity() {
    private data class BottomTabItem(
        val route: String,
        val title: String,
        @param:DrawableRes val iconRes: Int,
        @param:DrawableRes val selectedIconRes: Int
    )

    private val bottomTabs: List<BottomTabItem> = listOf(
        BottomTabItem(ROUTE_MAIN, "壁纸", R.drawable.wallpaper, R.drawable.wallpaper_filled),
        BottomTabItem(ROUTE_ABOUT, "分组", R.drawable.list, R.drawable.list_filled),
        BottomTabItem(ROUTE_SETTING, "设置", R.drawable.setting, R.drawable.setting_filled)
    )

    private var showBottomBar by mutableStateOf(true)
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
        private const val PREF_MAIN_WALLPAPER_SORT_MODE = "main_wallpaper_sort_mode"
        private const val PREF_MAIN_WALLPAPER_SORT_DIRECTION = "main_wallpaper_sort_direction"
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

        setContent { MainActivityScreen() }

        val wm = getSystemService(WINDOW_SERVICE) as WindowManager
        val point = Point()
        @Suppress("DEPRECATION")
        wm.defaultDisplay.getRealSize(point)
        FileUtil.width = point.x
        FileUtil.height = point.y
        permission()
        clearNoUseFile()
    }

    @OptIn(ExperimentalAnimationApi::class, ExperimentalFoundationApi::class)
    @Composable
    private fun MainActivityScreen() {
        val pref = remember(this) { getSharedPreferences(App.TIANYIN, MODE_PRIVATE) }
        var themeMode by remember { mutableStateOf(pref.getInt(PREF_THEME_MODE, THEME_MODE_FOLLOW_SYSTEM)) }
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
            val themeBackgroundColor = if (useDarkTheme) Color(0xFF0A0A0C) else MaterialTheme.colors.background
            val enableLiquidGlass = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
            val navController = rememberNavController()
            val navBackStackEntry by navController.currentBackStackEntryAsState()
            val currentRoute = navBackStackEntry?.destination?.route ?: ROUTE_MAIN
            val haptic = LocalHapticFeedback.current
            val density = LocalDensity.current
            val statusBarTopPadding = remember(this) {
                val id = resources.getIdentifier("status_bar_height", "dimen", "android")
                if (id > 0) resources.getDimensionPixelSize(id) else 0
            }
            val statusBarTopPaddingDp = with(density) { statusBarTopPadding.toDp() }

            var selectedRootIndex by remember { mutableStateOf(0) }
            var requestedRootIndex by remember { mutableStateOf<Int?>(null) }
            val rootPagerState = rememberPagerState(
                initialPage = 0,
                pageCount = { bottomTabs.size }
            )
            var pendingOpenAddDialog by remember { mutableStateOf(false) }
            var updateDialogState by remember { mutableStateOf(UpdateDialogState()) }
            var hasCheckedUpdate by remember { mutableStateOf(false) }
            var showMoreMenu by remember { mutableStateOf(false) }
            var showSortMenu by remember { mutableStateOf(false) }
            var showFilterMenu by remember { mutableStateOf(false) }
            var selectedSortMode by remember {
                mutableStateOf(
                    runCatching {
                        MainWallpaperSortMode.valueOf(
                            pref.getString(PREF_MAIN_WALLPAPER_SORT_MODE, MainWallpaperSortMode.Custom.name)
                                ?: MainWallpaperSortMode.Custom.name
                        )
                    }.getOrDefault(MainWallpaperSortMode.Custom)
                )
            }
            var selectedSortDirection by remember {
                mutableStateOf(
                    runCatching {
                        MainWallpaperSortDirection.valueOf(
                            pref.getString(PREF_MAIN_WALLPAPER_SORT_DIRECTION, MainWallpaperSortDirection.Descending.name)
                                ?: MainWallpaperSortDirection.Descending.name
                        )
                    }.getOrDefault(MainWallpaperSortDirection.Descending)
                )
            }
            var selectedKindFilters by remember {
                mutableStateOf(
                    pref.getString("main_wallpaper_kind_filters", "").orEmpty()
                        .split(',')
                        .mapNotNull { name ->
                            name.takeIf { it.isNotBlank() }?.let {
                                runCatching { MainWallpaperKindFilter.valueOf(it) }.getOrNull()
                            }
                        }
                        .toSet()
                )
            }
            fun persistKindFilters(filters: Set<MainWallpaperKindFilter>) {
                selectedKindFilters = filters
                pref.edit()
                    .putString("main_wallpaper_kind_filters", filters.joinToString(",") { it.name })
                    .apply()
            }
            fun persistSortMode(mode: MainWallpaperSortMode) {
                selectedSortMode = mode
                pref.edit().putString(PREF_MAIN_WALLPAPER_SORT_MODE, mode.name).apply()
            }
            fun persistSortDirection(direction: MainWallpaperSortDirection) {
                selectedSortDirection = direction
                pref.edit().putString(PREF_MAIN_WALLPAPER_SORT_DIRECTION, direction.name).apply()
            }
            var wallpaperSelectionState by remember { mutableStateOf(SelectionBarState(false, false)) }

            val rootBackdrop = if (enableLiquidGlass) {
                rememberLayerBackdrop {
                    drawRect(themeBackgroundColor)
                    drawContent()
                }
            } else null

            LaunchedEffect(pendingRoute) {
                val route = pendingRoute ?: return@LaunchedEffect
                if (currentRoute != route) navigateToRoute(navController, route)
                pendingRoute = null
            }

            LaunchedEffect(requestedRootIndex, currentRoute) {
                val target = requestedRootIndex ?: return@LaunchedEffect
                if (target != 0) showBottomBar = true
                if (currentRoute == ROUTE_MAIN) {
                    if (rootPagerState.currentPage != target) {
                        rootPagerState.animateScrollToPage(target)
                    }
                    selectedRootIndex = target
                    requestedRootIndex = null
                }
            }

            LaunchedEffect(rootPagerState.currentPage, rootPagerState.isScrollInProgress) {
                val requested = requestedRootIndex
                if (requested == null) {
                    val page = rootPagerState.currentPage
                    if (selectedRootIndex != page) {
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        selectedRootIndex = page
                        showMoreMenu = false
                        showSortMenu = false
                        showFilterMenu = false
                        if (page != 0) showBottomBar = true
                    }
                } else if (!rootPagerState.isScrollInProgress && rootPagerState.currentPage == requested) {
                    selectedRootIndex = requested
                    requestedRootIndex = null
                }
            }

            DisposableEffect(Unit) {
                val wallpaperSelectionDisposable = RxBus.getDefault()
                    .toObservableWithCode(RxConstants.RX_SELECTION_MODE_CHANGED, SelectionBarState::class.java)
                    .subscribe { state -> wallpaperSelectionState = state }
                onDispose { wallpaperSelectionDisposable.dispose() }
            }

            fun selectRoot(index: Int) {
                if (index == selectedRootIndex && currentRoute == ROUTE_MAIN && requestedRootIndex == null) {
                    showMoreMenu = false
                    showSortMenu = false
                    showFilterMenu = false
                    return
                }
                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                selectedRootIndex = index
                requestedRootIndex = index
                showMoreMenu = false
                showSortMenu = false
                showFilterMenu = false
                if (currentRoute != ROUTE_MAIN) navigateToRoute(navController, ROUTE_MAIN)
            }

            fun triggerBottomAdd() {
                showMoreMenu = false
                showSortMenu = false
                showFilterMenu = false
                if (currentRoute == ROUTE_MAIN && selectedRootIndex == 0) {
                    RxBus.postWithCode(RxConstants.RX_TRIGGER_ADD_WALLPAPER, Unit)
                } else {
                    pendingOpenAddDialog = true
                    selectRoot(0)
                }
            }

            LaunchedEffect(pendingOpenAddDialog, selectedRootIndex, currentRoute) {
                if (pendingOpenAddDialog && selectedRootIndex == 0 && currentRoute == ROUTE_MAIN) {
                    pendingOpenAddDialog = false
                    RxBus.postWithCode(RxConstants.RX_TRIGGER_ADD_WALLPAPER, Unit)
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
                            if (enableLiquidGlass && rootBackdrop != null) modifier.layerBackdrop(rootBackdrop) else modifier
                        }
                ) {
                    composable(ROUTE_MAIN) {
                        HorizontalPager(
                            state = rootPagerState,
                            modifier = Modifier.fillMaxSize(),
                            beyondViewportPageCount = 1,
                            userScrollEnabled = showBottomBar && !wallpaperSelectionState.selectionMode
                        ) { rootIndex ->
                            when (rootIndex) {
                                0 -> WallpaperRootPage(
                                    useDarkTheme = useDarkTheme,
                                    enableLiquidGlass = enableLiquidGlass,
                                    showBottomBar = showBottomBar,
                                    wallpaperSelectionState = wallpaperSelectionState,
                                    showMoreMenu = showMoreMenu && selectedRootIndex == 0,
                                    showSortMenu = showSortMenu && selectedRootIndex == 0,
                                    showFilterMenu = showFilterMenu && selectedRootIndex == 0,
                                    selectedKindFilters = selectedKindFilters,
                                    selectedSortMode = selectedSortMode,
                                    selectedSortDirection = selectedSortDirection,
                                    onShowMoreMenuChange = { showMoreMenu = it },
                                    onShowSortMenuChange = { showSortMenu = it },
                                    onShowFilterMenuChange = { showFilterMenu = it },
                                    onSortModeSelected = { persistSortMode(it) },
                                    onSortDirectionSelected = { persistSortDirection(it) },
                                    onToggleKindFilter = { filter ->
                                        val next = if (filter in selectedKindFilters) {
                                            selectedKindFilters - filter
                                        } else {
                                            selectedKindFilters + filter
                                        }
                                        persistKindFilters(next)
                                    },
                                    onOpenSettingPage = { selectRoot(2) },
                                    onOpenGroupPage = { selectRoot(1) },
                                    onBottomBarVisibleChange = { setBottomBarVisible(it) }
                                )
                                1 -> Box(Modifier.fillMaxSize()) {
                                    val groupPageBackdrop = if (enableLiquidGlass) {
                                        rememberLayerBackdrop {
                                            drawRect(themeBackgroundColor)
                                            drawContent()
                                        }
                                    } else null

                                    Box(
                                        modifier = Modifier
                                            .fillMaxSize()
                                            .let { base ->
                                                if (groupPageBackdrop != null) base.layerBackdrop(groupPageBackdrop) else base
                                            }
                                    ) {
                                        AboutRouteScreen(
                                            useDarkTheme = useDarkTheme,
                                            kindFilters = selectedKindFilters,
                                            sortMode = selectedSortMode,
                                            sortDirection = selectedSortDirection,
                                            onSelectionModeChange = { inSelection -> showBottomBar = !inSelection },
                                            showBackButton = false
                                        )
                                    }

                                    if (showBottomBar) {
                                        MainTopBar(
                                            statusBarTopPaddingDp = statusBarTopPaddingDp,
                                            enableLiquidGlass = enableLiquidGlass,
                                            backdrop = groupPageBackdrop,
                                            isLightTheme = !useDarkTheme,
                                            onAdd = {},
                                            onApply = {},
                                            onMoreClick = { showMoreMenu = true },
                                            onSortClick = { showSortMenu = true },
                                            onFilterClick = { showFilterMenu = true },
                                            onPreview = {},
                                            showAddButton = false,
                                            showPreviewButton = false,
                                            showApplyButton = false,
                                            showMoreButton = true,
                                            showSortButton = true,
                                            showFilterButton = true,
                                            keepSlotWhenHidden = false
                                        )

                                        LiquidMoreMenuOverlay(
                                            visible = showMoreMenu && selectedRootIndex == 1,
                                            statusBarTopPaddingDp = statusBarTopPaddingDp,
                                            currentPageRoute = "group",
                                            useDarkTheme = useDarkTheme,
                                            enableLiquidGlass = enableLiquidGlass,
                                            liquidBackdrop = groupPageBackdrop,
                                            menuItems = listOf(
                                                LiquidMoreMenuItem("选择") {
                                                    showMoreMenu = false
                                                    RxBus.postWithCode(RxConstants.RX_TRIGGER_GROUP_OPTIONS, Unit)
                                                }
                                            ),
                                            onDismiss = { showMoreMenu = false }
                                        )

                                        LiquidMoreMenuOverlay(
                                            visible = showSortMenu && selectedRootIndex == 1,
                                            statusBarTopPaddingDp = statusBarTopPaddingDp,
                                            currentPageRoute = "group_sort",
                                            useDarkTheme = useDarkTheme,
                                            enableLiquidGlass = enableLiquidGlass,
                                            liquidBackdrop = groupPageBackdrop,
                                            menuWidth = 190.dp,
                                            triggerEndPadding = 112.dp,
                                            menuEndPadding = 112.dp,
                                            closeOnItemClick = false,
                                            triggerIconRes = R.drawable.sort,
                                            menuItems = buildSortMenuItems(
                                                selectedSortMode = selectedSortMode,
                                                selectedSortDirection = selectedSortDirection,
                                                onSortModeSelected = { persistSortMode(it) },
                                                onSortDirectionSelected = { persistSortDirection(it) }
                                            ),
                                            onDismiss = { showSortMenu = false }
                                        )

                                        LiquidMoreMenuOverlay(
                                            visible = showFilterMenu && selectedRootIndex == 1,
                                            statusBarTopPaddingDp = statusBarTopPaddingDp,
                                            currentPageRoute = "group_filter",
                                            useDarkTheme = useDarkTheme,
                                            enableLiquidGlass = enableLiquidGlass,
                                            liquidBackdrop = groupPageBackdrop,
                                            menuWidth = 176.dp,
                                            triggerEndPadding = 64.dp,
                                            menuEndPadding = 64.dp,
                                            closeOnItemClick = false,
                                            triggerIconRes = R.drawable.fliter,
                                            menuItems = MainWallpaperKindFilter.values().map { filter ->
                                                LiquidMoreMenuItem(
                                                    label = filter.label,
                                                    iconRes = filter.iconRes,
                                                    checked = filter in selectedKindFilters,
                                                    onClick = {
                                                        val next = if (filter in selectedKindFilters) {
                                                            selectedKindFilters - filter
                                                        } else {
                                                            selectedKindFilters + filter
                                                        }
                                                        persistKindFilters(next)
                                                    }
                                                )
                                            },
                                            onDismiss = { showFilterMenu = false }
                                        )
                                    }
                                }
                                2 -> SettingRouteScreen(
                                    useDarkTheme = useDarkTheme,
                                    onThemeModeChange = { mode -> themeMode = mode },
                                    onOpenAppInfo = { openAppInfoPage() },
                                    onOpenCorrugatedTest = { openCorrugatedTestPage() },
                                    onOpenPlyModelTest = { openPlyModelTestPage() }
                                )
                            }
                        }
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
                            useDarkTheme = useDarkTheme,
                            onBack = { navController.popBackStack() }
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

                if (showBottomBar && currentRoute == ROUTE_MAIN) {
                    val bottomInsets = WindowInsets.navigationBars.asPaddingValues()
                    val bottomBarBottomPadding = bottomInsets.calculateBottomPadding() + 12.dp
                    val bottomGroupHorizontalPadding = 18.dp
                    val bottomGroupGap = 8.dp
                    val bottomGroupHeight = 64.dp
                    val bottomActionSize = 64.dp
                    val bottomTabsStyle = LiquidBottomTabsStyle.default(isLightTheme = !useDarkTheme)
                    val bottomAddButtonStyle = LiquidButtonStyle(
                        height = bottomTabsStyle.trackHeight,
                        horizontalPadding = 0.dp,
                        blurRadius = bottomTabsStyle.trackBlurRadius,
                        lensRadiusX = bottomTabsStyle.trackLensRadius,
                        lensRadiusY = bottomTabsStyle.trackLensRadius,
                        pressedExpansion = bottomTabsStyle.trackPressedExpansion
                    )
                    val addButtonFallbackSurfaceColor = bottomTabsStyle.fallbackContainerColor
                    val addButtonTextColor = if (useDarkTheme) Color.White else Color(0xFF111318)
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
                            selectedTabIndex = { selectedRootIndex },
                            onTabSelected = { index -> selectRoot(index) },
                            backdrop = if (enableLiquidGlass) rootBackdrop else null,
                            tabsCount = bottomTabs.size,
                            isLightTheme = !useDarkTheme,
                            modifier = Modifier
                                .weight(1f)
                                .height(bottomGroupHeight),
                            style = bottomTabsStyle
                        ) {
                            bottomTabs.forEachIndexed { index, tab ->
                                LiquidBottomTab({ selectRoot(index) }) {
                                    val selected = selectedRootIndex == index
                                    val tabColor = if (selected) BOTTOM_BAR_SELECTED_COLOR else MaterialTheme.colors.onSurface
                                    Column(
                                        modifier = Modifier.fillMaxSize(),
                                        horizontalAlignment = Alignment.CenterHorizontally,
                                        verticalArrangement = Arrangement.Center
                                    ) {
                                        Icon(
                                            painter = painterResource(id = if (selected) tab.selectedIconRes else tab.iconRes),
                                            contentDescription = tab.title,
                                            modifier = Modifier.size(21.dp),
                                            tint = tabColor
                                        )
                                        Text(
                                            text = tab.title,
                                            color = tabColor,
                                            fontSize = 11.sp,
                                            lineHeight = 11.sp,
                                            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal
                                        )
                                    }
                                }
                            }
                        }

                        if (enableLiquidGlass && rootBackdrop != null) {
                            LiquidButton(
                                onClick = { triggerBottomAdd() },
                                backdrop = rootBackdrop,
                                modifier = Modifier.size(bottomActionSize),
                                buttonHeight = bottomActionSize,
                                contentPadding = PaddingValues(0.dp),
                                surfaceColor = bottomTabsStyle.containerColor,
                                style = bottomAddButtonStyle,
                                iconRes = R.drawable.plus,
                                iconContentDescription = "添加",
                                iconSize = 30.dp,
                                iconTint = addButtonTextColor
                            )
                        } else {
                            Box(
                                modifier = Modifier
                                    .size(bottomActionSize)
                                    .clip(CircleShape)
                                    .background(addButtonFallbackSurfaceColor)
                                    .clickable { triggerBottomAdd() },
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

            UpdateDialog(
                state = updateDialogState,
                parentBackdrop = rootBackdrop,
                onDismiss = { updateDialogState = UpdateDialogState(isVisible = false) },
                onConfirm = {
                    val info = updateDialogState.updateInfo
                    if (info != null) {
                        updateDialogState = updateDialogState.copy(isDownloading = true, downloadProgress = 0)
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
                                updateDialogState = updateDialogState.copy(isDownloading = false, errorMessage = message)
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
                        is AppUpdateManager.CheckResult.Error -> Log.w("MainActivity", "检查更新失败: ${result.message}")
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
        runOnUiThread { showBottomBar = visible }
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
            ActivityCompat.requestPermissions(this, permissionList.toTypedArray(), PERMISSION_REQUEST_CODE)
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
private fun WallpaperRootPage(
    useDarkTheme: Boolean,
    enableLiquidGlass: Boolean,
    showBottomBar: Boolean,
    wallpaperSelectionState: SelectionBarState,
    showMoreMenu: Boolean,
    showSortMenu: Boolean,
    showFilterMenu: Boolean,
    selectedKindFilters: Set<MainWallpaperKindFilter>,
    selectedSortMode: MainWallpaperSortMode,
    selectedSortDirection: MainWallpaperSortDirection,
    onShowMoreMenuChange: (Boolean) -> Unit,
    onShowSortMenuChange: (Boolean) -> Unit,
    onShowFilterMenuChange: (Boolean) -> Unit,
    onSortModeSelected: (MainWallpaperSortMode) -> Unit,
    onSortDirectionSelected: (MainWallpaperSortDirection) -> Unit,
    onToggleKindFilter: (MainWallpaperKindFilter) -> Unit,
    onOpenSettingPage: () -> Unit,
    onOpenGroupPage: () -> Unit,
    onBottomBarVisibleChange: (Boolean) -> Unit
) {
    val pageBackdrop = if (enableLiquidGlass) rememberLayerBackdrop() else null

    Box(modifier = Modifier.fillMaxSize()) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .let { base ->
                    if (enableLiquidGlass && pageBackdrop != null) {
                        base.layerBackdrop(pageBackdrop)
                    } else {
                        base
                    }
                }
        ) {
            MainRouteScreen(
                useDarkTheme = useDarkTheme,
                kindFilters = selectedKindFilters,
                sortMode = selectedSortMode,
                sortDirection = selectedSortDirection,
                onOpenSettingPage = onOpenSettingPage,
                onBottomBarVisibleChange = onBottomBarVisibleChange
            )
        }

        val context = LocalContext.current
        val density = LocalDensity.current
        val statusBarTopPadding = remember(context) {
            val id = context.resources.getIdentifier("status_bar_height", "dimen", "android")
            if (id > 0) context.resources.getDimensionPixelSize(id) else 0
        }
        val statusBarTopPaddingDp = with(density) { statusBarTopPadding.toDp() }

        if (wallpaperSelectionState.selectionMode) {
            SelectionTopBar(
                statusBarTopPaddingDp = statusBarTopPaddingDp,
                enableLiquidGlass = enableLiquidGlass,
                backdrop = pageBackdrop,
                isAllSelected = wallpaperSelectionState.isAllSelected,
                isLightTheme = !useDarkTheme,
                onCancelSelect = { RxBus.postWithCode(RxConstants.RX_SELECTION_CANCEL, Unit) },
                onDelete = { RxBus.postWithCode(RxConstants.RX_SELECTION_DELETE, Unit) },
                onToggleSelectAll = { RxBus.postWithCode(RxConstants.RX_SELECTION_TOGGLE_ALL, Unit) }
            )
        } else if (showBottomBar) {
            MainTopBar(
                statusBarTopPaddingDp = statusBarTopPaddingDp,
                enableLiquidGlass = enableLiquidGlass,
                backdrop = pageBackdrop,
                isLightTheme = !useDarkTheme,
                onAdd = { RxBus.postWithCode(RxConstants.RX_TRIGGER_ADD_WALLPAPER, Unit) },
                onApply = { RxBus.postWithCode(RxConstants.RX_TRIGGER_APPLY_WALLPAPER, Unit) },
                onMoreClick = { onShowMoreMenuChange(true) },
                onSortClick = { onShowSortMenuChange(true) },
                onFilterClick = { onShowFilterMenuChange(true) },
                onPreview = { RxBus.postWithCode(RxConstants.RX_TRIGGER_PREVIEW_WALLPAPER, Unit) },
                showAddButton = false,
                showPreviewButton = true,
                showApplyButton = true,
                showMoreButton = true,
                showSortButton = true,
                showFilterButton = true,
                keepSlotWhenHidden = false
            )
        }

        LiquidMoreMenuOverlay(
            visible = showMoreMenu,
            statusBarTopPaddingDp = statusBarTopPaddingDp,
            currentPageRoute = "wallpaper",
            useDarkTheme = useDarkTheme,
            enableLiquidGlass = enableLiquidGlass,
            liquidBackdrop = pageBackdrop,
            menuItems = listOf(
                LiquidMoreMenuItem("保存到分组") {
                    onShowMoreMenuChange(false)
                    RxBus.postWithCode(RxConstants.RX_TRIGGER_SAVE_GROUP, Unit)
                },
                LiquidMoreMenuItem("选择") {
                    onShowMoreMenuChange(false)
                    RxBus.postWithCode(RxConstants.RX_TRIGGER_ENTER_SELECT_MODE, Unit)
                }
            ),
            onDismiss = { onShowMoreMenuChange(false) }
        )

        LiquidMoreMenuOverlay(
            visible = showSortMenu,
            statusBarTopPaddingDp = statusBarTopPaddingDp,
            currentPageRoute = "wallpaper_sort",
            useDarkTheme = useDarkTheme,
            enableLiquidGlass = enableLiquidGlass,
            liquidBackdrop = pageBackdrop,
            menuWidth = 190.dp,
            triggerEndPadding = 112.dp,
            menuEndPadding = 112.dp,
            closeOnItemClick = false,
            triggerIconRes = R.drawable.sort,
            menuItems = buildSortMenuItems(
                selectedSortMode = selectedSortMode,
                selectedSortDirection = selectedSortDirection,
                onSortModeSelected = onSortModeSelected,
                onSortDirectionSelected = onSortDirectionSelected
            ),
            onDismiss = { onShowSortMenuChange(false) }
        )

        LiquidMoreMenuOverlay(
            visible = showFilterMenu,
            statusBarTopPaddingDp = statusBarTopPaddingDp,
            currentPageRoute = "wallpaper_filter",
            useDarkTheme = useDarkTheme,
            enableLiquidGlass = enableLiquidGlass,
            liquidBackdrop = pageBackdrop,
            menuWidth = 176.dp,
            triggerEndPadding = 64.dp,
            menuEndPadding = 64.dp,
            closeOnItemClick = false,
            triggerIconRes = R.drawable.fliter,
            menuItems = MainWallpaperKindFilter.values().map { filter ->
                LiquidMoreMenuItem(
                    label = filter.label,
                    iconRes = filter.iconRes,
                    checked = filter in selectedKindFilters,
                    onClick = { onToggleKindFilter(filter) }
                )
            },
            onDismiss = { onShowFilterMenuChange(false) }
        )
    }
}


private fun buildSortMenuItems(
    selectedSortMode: MainWallpaperSortMode,
    selectedSortDirection: MainWallpaperSortDirection,
    onSortModeSelected: (MainWallpaperSortMode) -> Unit,
    onSortDirectionSelected: (MainWallpaperSortDirection) -> Unit
): List<LiquidMoreMenuItem> {
    return MainWallpaperSortMode.values().map { mode ->
        LiquidMoreMenuItem(
            label = mode.label,
            checked = selectedSortMode == mode,
            onClick = { onSortModeSelected(mode) }
        )
    } + MainWallpaperSortDirection.values().map { direction ->
        LiquidMoreMenuItem(
            label = direction.label,
            checked = selectedSortDirection == direction,
            onClick = { onSortDirectionSelected(direction) }
        )
    }
}

private fun lerpFloat(start: Float, stop: Float, fraction: Float): Float {
    return start + (stop - start) * fraction
}

private fun lerpDp(start: Dp, stop: Dp, fraction: Float): Dp {
    return (start.value + (stop.value - start.value) * fraction).dp
}

@Composable
private fun LiquidMoreMenuOverlay(
    visible: Boolean,
    statusBarTopPaddingDp: Dp,
    currentPageRoute: String,
    useDarkTheme: Boolean,
    enableLiquidGlass: Boolean,
    liquidBackdrop: LayerBackdrop?,
    menuItems: List<LiquidMoreMenuItem>,
    onDismiss: () -> Unit,
    menuWidth: Dp = 140.dp,
    triggerEndPadding: Dp = 8.dp,
    menuEndPadding: Dp = 12.dp,
    closeOnItemClick: Boolean = true,
    @DrawableRes triggerIconRes: Int? = null
) {
    var mounted by remember { mutableStateOf(visible) }
    val morph = rememberLiquidMorphController(if (visible) 1f else 0f)
    val density = LocalDensity.current
    val scope = rememberCoroutineScope()

    LaunchedEffect(visible) {
        if (visible) {
            mounted = true
            morph.open()
        } else if (mounted) {
            morph.close()
            mounted = false
        }
    }

    if (!mounted) return

    morph.updateHandoff()
    val p = morph.value.coerceIn(0f, 1f)
    val menuSurfaceColor = if (enableLiquidGlass) {
        if (useDarkTheme) Color.Black.copy(alpha = 0.30f) else Color.White.copy(alpha = 0.30f)
    } else {
        if (useDarkTheme) Color(0x33000000) else Color(0xAAFFFFFF)
    }
    val menuBorderColor = if (useDarkTheme) Color(0x33FFFFFF) else Color(0x88FFFFFF)
    val textColor = if (useDarkTheme) Color.White else Color.Black
    val menuHeight = (menuItems.size * 44 + (menuItems.size - 1) * 4 + 16).dp
    val triggerSize = 48.dp
    val triggerTopPadding = statusBarTopPaddingDp + 10.dp
    val menuTopPadding = statusBarTopPaddingDp + 66.dp
    val triggerCenterTop = triggerTopPadding + triggerSize / 2f
    val triggerCenterEnd = triggerEndPadding + triggerSize / 2f
    val menuCenterTop = menuTopPadding + menuHeight / 2f
    val menuCenterEnd = menuEndPadding + menuWidth / 2f
    val morphState = LiquidMorphPhysics.compute(
        rawValue = morph.value,
        finalDx = with(density) { (triggerCenterEnd - menuCenterEnd).toPx() },
        finalDy = with(density) { (menuCenterTop - triggerCenterTop).toPx() }
    )
    val sizeT = morphState.sizeT.coerceIn(0f, 1f)
    val pathT = morphState.pathT.coerceIn(-0.16f, 1.12f)
    val bodyWidth = lerpDp(triggerSize, menuWidth, sizeT)
    val bodyHeight = lerpDp(triggerSize, menuHeight, sizeT)
    val bodyCenterTop = lerpDp(triggerCenterTop, menuCenterTop, pathT)
    val bodyCenterEnd = lerpDp(triggerCenterEnd, menuCenterEnd, pathT)
    val bodyTopPadding = bodyCenterTop - bodyHeight / 2f
    val bodyEndPadding = bodyCenterEnd - bodyWidth / 2f
    val radiusT = ((sizeT - 0.72f) / 0.28f)
        .coerceIn(0f, 1f)
        .let { it * it * (3f - 2f * it) }
    val menuBodyCorner = lerpFloat(minOf(bodyWidth.value, bodyHeight.value) / 2f, 20f, radiusT).dp
    val menuBodyAlpha = when {
        morph.hasHandedOff -> 0f
        morph.isClosing -> (p / 0.22f).coerceIn(0f, 1f)
        else -> 1f
    }
    val itemAlpha = ((p - 0.50f) / 0.50f).coerceIn(0f, 1f)
    val triggerAlpha = when {
        morph.hasHandedOff -> 0f
        morph.isClosing -> 0f
        else -> morphState.anchorScale
    }
    val triggerScale = morphState.anchorScale
    val menuItemsEnabled = p >= 0.995f && !morph.isClosing && !morph.hasHandedOff
    val overlayBlocksInput = p > 0.30f && !morph.isClosing && !morph.hasHandedOff

    fun closeThen(action: () -> Unit) {
        scope.launch {
            morph.close()
            mounted = false
            action()
        }
    }

    fun triggerMenuItem(action: () -> Unit) {
        if (!menuItemsEnabled) return
        if (closeOnItemClick) {
            closeThen(action)
        } else {
            action()
        }
    }

    Box(
        modifier = Modifier.fillMaxSize().let { base ->
            if (overlayBlocksInput) {
                base.pointerInput(currentPageRoute) {
                    detectTapGestures { closeThen(onDismiss) }
                }
            } else {
                base
            }
        }
    ) {
        Box(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .zIndex(3f)
                .padding(top = triggerTopPadding, end = triggerEndPadding)
                .width(triggerSize)
                .height(triggerSize)
                .graphicsLayer {
                    alpha = if (morph.hasHandedOff) 0f else triggerAlpha
                    scaleX = triggerScale
                    scaleY = triggerScale
                    translationY = morphState.pushDy
                    transformOrigin = TransformOrigin(0.5f, 0.5f)
                }
                .let { base ->
                    if (enableLiquidGlass && liquidBackdrop != null) {
                        base
                            .clip(CircleShape)
                            .drawBackdrop(
                                backdrop = liquidBackdrop,
                                shape = { Capsule() },
                                effects = {
                                    vibrancy()
                                    blur(2f.dp.toPx())
                                    lens(12f.dp.toPx(), 24f.dp.toPx())
                                },
                                onDrawSurface = { drawRect(menuSurfaceColor) }
                            )
                    } else {
                        base
                            .clip(CircleShape)
                            .background(menuSurfaceColor)
                    }
                },
            contentAlignment = Alignment.Center
        ) {
            if (triggerIconRes != null) {
                Icon(
                    painter = painterResource(triggerIconRes),
                    contentDescription = null,
                    modifier = Modifier.size(20.dp),
                    tint = textColor
                )
            } else {
                BasicText("⋯", style = TextStyle(textColor, 18.sp))
            }
        }

        Box(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .zIndex(2f)
                .padding(top = bodyTopPadding, end = bodyEndPadding)
                .width(bodyWidth)
                .height(bodyHeight)
                .graphicsLayer {
                    scaleX = morphState.containerScale
                    scaleY = morphState.containerScale
                    transformOrigin = TransformOrigin(1f, 0f)
                    alpha = menuBodyAlpha
                }
                .let { base ->
                    if (overlayBlocksInput) {
                        base.pointerInput(Unit) {
                            detectTapGestures {
                                // Consume taps inside the menu so the outer layer does not dismiss first.
                            }
                        }
                    } else {
                        base
                    }
                }
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .let { base ->
                        if (enableLiquidGlass && liquidBackdrop != null) {
                            base.drawBackdrop(
                                backdrop = liquidBackdrop,
                                shape = { RoundedRectangle(menuBodyCorner) },
                                effects = {
                                    vibrancy()
                                    blur(6f.dp.toPx())
                                    lens(18f.dp.toPx(), 30f.dp.toPx())
                                },
                                onDrawSurface = { drawRect(menuSurfaceColor) }
                            )
                        } else {
                            base
                                .clip(RoundedCornerShape(menuBodyCorner))
                                .background(menuSurfaceColor)
                                .border(1.dp, menuBorderColor, RoundedCornerShape(menuBodyCorner))
                        }
                    }
            )

            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(8.dp)
                    .graphicsLayer { alpha = itemAlpha },
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                menuItems.forEach { item ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .height(44.dp)
                            .clip(Capsule())
                            .clickable(enabled = menuItemsEnabled) { triggerMenuItem(item.onClick) }
                            .padding(horizontal = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Start
                    ) {
                        BasicText(
                            text = if (item.checked) "✓" else "",
                            modifier = Modifier.width(18.dp),
                            style = TextStyle(
                                color = textColor,
                                fontSize = 15.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                        )
                        item.iconRes?.let { iconRes ->
                            Icon(
                                painter = painterResource(iconRes),
                                contentDescription = null,
                                modifier = Modifier.size(18.dp),
                                tint = textColor
                            )
                            Spacer(Modifier.width(8.dp))
                        }
                        BasicText(
                            text = item.label,
                            style = TextStyle(
                                color = textColor,
                                fontSize = 15.sp
                            )
                        )
                    }
                }
            }
        }
    }
}
