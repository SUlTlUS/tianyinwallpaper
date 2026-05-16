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
import android.view.WindowManager
import android.view.WindowInsetsController
import android.widget.Toast
import androidx.activity.compose.setContent
import androidx.appcompat.app.AlertDialog
import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material.darkColors
import androidx.compose.material.lightColors
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.core.content.ContextCompat
import androidx.core.app.ActivityCompat
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.Lifecycle
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.alibaba.fastjson.JSON
import com.zeaze.tianyinwallpaper.backdrop.backdrops.layerBackdrop
import com.zeaze.tianyinwallpaper.backdrop.backdrops.rememberLayerBackdrop
import com.zeaze.tianyinwallpaper.base.BaseActivity
import com.zeaze.tianyinwallpaper.model.TianYinWallpaperModel
import com.zeaze.tianyinwallpaper.ui.about.AboutRouteScreen
import com.zeaze.tianyinwallpaper.ui.commom.SaveData
import com.zeaze.tianyinwallpaper.ui.depth.DepthRouteScreen
import com.zeaze.tianyinwallpaper.ui.main.MainRouteScreen
import com.zeaze.tianyinwallpaper.ui.setting.SettingRouteScreen
import com.zeaze.tianyinwallpaper.ui.raster.RasterRouteScreen
import com.zeaze.tianyinwallpaper.ui.test.CorrugatedTestRouteScreen
import com.zeaze.tianyinwallpaper.ui.test.DepthModelTestRouteScreen
import com.zeaze.tianyinwallpaper.ui.test.PlyModelTestRouteScreen
import com.zeaze.tianyinwallpaper.utils.FileUtil
import java.io.File
import kotlinx.coroutines.launch
import com.zeaze.tianyinwallpaper.catalog.components.LiquidBottomTab
import com.zeaze.tianyinwallpaper.catalog.components.LiquidBottomTabs
import com.zeaze.tianyinwallpaper.backdrop.drawBackdrop
import com.zeaze.tianyinwallpaper.backdrop.effects.blur
import com.zeaze.tianyinwallpaper.backdrop.effects.lens
import com.zeaze.tianyinwallpaper.backdrop.effects.vibrancy
import com.kyant.shapes.RoundedRectangle
import com.zeaze.tianyinwallpaper.base.rxbus.RxBus
import com.zeaze.tianyinwallpaper.base.rxbus.RxConstants
import com.zeaze.tianyinwallpaper.ui.main.MainTopBar
import com.zeaze.tianyinwallpaper.ui.main.SelectionBarState
import com.zeaze.tianyinwallpaper.ui.main.SelectionTopBar
import androidx.compose.foundation.text.BasicText
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.sp
import com.kyant.shapes.Capsule
import com.zeaze.tianyinwallpaper.update.AppUpdateManager
import com.zeaze.tianyinwallpaper.update.UpdateDialog
import com.zeaze.tianyinwallpaper.update.UpdateDialogState

class MainActivity : BaseActivity() {
    private val tabItems: List<Pair<String, Int>> = listOf(
        ROUTE_MAIN to R.string.main_tab_wallpaper,
        ROUTE_RASTER to R.string.main_tab_raster,
        ROUTE_DEPTH to R.string.main_tab_depth
    )
    private var showBottomBar by mutableStateOf(true)
    private var pendingRoute by mutableStateOf<String?>(null)

    companion object {
        private const val PERMISSION_REQUEST_CODE = 1
        private const val REQUEST_CODE_SET_WALLPAPER = 0x001
        private const val ROUTE_MAIN = "main"
        private const val ROUTE_ABOUT = "about"
        private const val ROUTE_APP_INFO = "app_info"
        private const val ROUTE_RASTER = "raster"
        private const val ROUTE_DEPTH = "depth"
        private const val ROUTE_SETTING = "setting"
        private const val ROUTE_CORRUGATED_TEST = "corrugated_test"
        private const val ROUTE_DEPTH_MODEL_TEST = "depth_model_test"
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
        
        // 机型判断：如果是手机则锁定为竖屏
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
            mutableStateOf(
                pref.getInt(
                    PREF_THEME_MODE,
                    THEME_MODE_FOLLOW_SYSTEM
                )
            )
        }
        val useDarkTheme = when (themeMode) {
            THEME_MODE_DARK -> true
            THEME_MODE_LIGHT -> false
            else -> isSystemInDarkTheme()
        }

        // 更新状态栏外观
        val view = LocalView.current
        SideEffect {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                val controller = view.windowInsetsController
                val appearance = if (!useDarkTheme) {
                    WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or
                        WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS
                } else {
                    0
                }
                controller?.setSystemBarsAppearance(
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
            val enableLiquidGlass =
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
            val navController = rememberNavController()
            val navBackStackEntry by navController.currentBackStackEntryAsState()
            val currentRoute = navBackStackEntry?.destination?.route ?: ROUTE_MAIN

            val pagerState = rememberPagerState(pageCount = { tabItems.size })
            val scope = androidx.compose.runtime.rememberCoroutineScope()
            val haptic = LocalHapticFeedback.current

            LaunchedEffect(pagerState.currentPage) {
                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
            }

            LaunchedEffect(pendingRoute) {
                val route = pendingRoute ?: return@LaunchedEffect
                if (currentRoute != route) {
                    navigateToRoute(navController, route)
                }
                pendingRoute = null
            }
            
            // 更新对话框状态
            var updateDialogState by remember { mutableStateOf(UpdateDialogState()) }
            var hasCheckedUpdate by remember { mutableStateOf(false) }
            
            val liquidBackdrop = if (enableLiquidGlass) {
                rememberLayerBackdrop {
                    drawRect(themeBackgroundColor)
                    drawContent()
                }
            } else null

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
                        .let {
                            if (enableLiquidGlass && liquidBackdrop != null) {
                                it.layerBackdrop(liquidBackdrop)
                            } else {
                                it
                            }
                        }
                ) {
                    composable(ROUTE_MAIN) {
                        MainPagerScreen(
                            pagerState = pagerState,
                            useDarkTheme = useDarkTheme,
                            onOpenSettingPage = { openSettingPage() },
                            onBottomBarVisibleChange = { setBottomBarVisible(it) }
                        )
                    }
                    composable(
                        route = ROUTE_ABOUT,
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
                        AboutRouteScreen(
                            useDarkTheme = useDarkTheme,
                            onBack = { navController.popBackStack() }
                        )
                    }
                    composable(
                        route = ROUTE_SETTING,
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
                        SettingRouteScreen(
                            useDarkTheme = useDarkTheme,
                            onThemeModeChange = { mode ->
                                themeMode = mode
                            },
                            onOpenAppInfo = { openAppInfoPage() },
                            onOpenCorrugatedTest = { openCorrugatedTestPage() },
                            onOpenDepthModelTest = { openDepthModelTestPage() },
                            onOpenPlyModelTest = { openPlyModelTestPage() }
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
                        route = ROUTE_DEPTH_MODEL_TEST,
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
                        DepthModelTestRouteScreen(useDarkTheme = useDarkTheme)
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


                val currentPageRoute =
                    if (currentRoute == ROUTE_MAIN) tabItems.getOrNull(pagerState.currentPage)?.first ?: ROUTE_MAIN
                    else currentRoute
                val isWallpaperPage = currentRoute == ROUTE_MAIN && currentPageRoute == ROUTE_MAIN
                val isRasterPage = currentRoute == ROUTE_MAIN && currentPageRoute == ROUTE_RASTER
                val isDepthPage = currentRoute == ROUTE_MAIN && currentPageRoute == ROUTE_DEPTH
                val shouldShowTopBar = currentRoute == ROUTE_MAIN
                var showMoreMenu by remember { mutableStateOf(false) }

                // 选择模式状态
                var wallpaperSelectionState by remember { mutableStateOf(SelectionBarState(false, false)) }
                var rasterSelectionState by remember { mutableStateOf(SelectionBarState(false, false)) }

                // 监听选择模式状态变化
                DisposableEffect(Unit) {
                    val wallpaperSelectionDisposable = RxBus.getDefault()
                        .toObservableWithCode(RxConstants.RX_SELECTION_MODE_CHANGED, SelectionBarState::class.java)
                        .subscribe { state -> wallpaperSelectionState = state }

                    val rasterSelectionDisposable = RxBus.getDefault()
                        .toObservableWithCode(RxConstants.RX_RASTER_SELECTION_MODE_CHANGED, SelectionBarState::class.java)
                        .subscribe { state -> rasterSelectionState = state }

                    onDispose {
                        wallpaperSelectionDisposable.dispose()
                        rasterSelectionDisposable.dispose()
                    }
                }

                val isInSelectionMode = (isWallpaperPage && wallpaperSelectionState.selectionMode) ||
                                        (isRasterPage && rasterSelectionState.selectionMode)

                if (shouldShowTopBar) {
                    val density = LocalDensity.current
                    val statusBarTopPadding = remember(this) {
                        val id = resources.getIdentifier("status_bar_height", "dimen", "android")
                        if (id > 0) resources.getDimensionPixelSize(id) else 0
                    }
                    val statusBarTopPaddingDp = with(density) { statusBarTopPadding.toDp() }

                    if (isInSelectionMode) {
                        // 选择模式顶部栏
                        val selectionState = if (isWallpaperPage) wallpaperSelectionState else rasterSelectionState
                        SelectionTopBar(
                            statusBarTopPaddingDp = statusBarTopPaddingDp,
                            enableLiquidGlass = enableLiquidGlass,
                            backdrop = liquidBackdrop,
                            isAllSelected = selectionState.isAllSelected,
                            isLightTheme = !useDarkTheme,
                            onCancelSelect = {
                                if (isWallpaperPage) {
                                    RxBus.postWithCode(RxConstants.RX_SELECTION_CANCEL, Unit)
                                } else {
                                    RxBus.postWithCode(RxConstants.RX_RASTER_SELECTION_CANCEL, Unit)
                                }
                            },
                            onDelete = {
                                if (isWallpaperPage) {
                                    RxBus.postWithCode(RxConstants.RX_SELECTION_DELETE, Unit)
                                } else {
                                    RxBus.postWithCode(RxConstants.RX_RASTER_SELECTION_DELETE, Unit)
                                }
                            },
                            onToggleSelectAll = {
                                if (isWallpaperPage) {
                                    RxBus.postWithCode(RxConstants.RX_SELECTION_TOGGLE_ALL, Unit)
                                } else {
                                    RxBus.postWithCode(RxConstants.RX_RASTER_SELECTION_TOGGLE_ALL, Unit)
                                }
                            }
                        )
                    } else if (showBottomBar) {
                        // 正常模式顶部栏
                        MainTopBar(
                            statusBarTopPaddingDp = statusBarTopPaddingDp,
                            enableLiquidGlass = enableLiquidGlass,
                            backdrop = liquidBackdrop,
                            isLightTheme = !useDarkTheme,
                            onAdd = {
                                when {
                                    isDepthPage -> RxBus.postWithCode(RxConstants.RX_TRIGGER_ADD_DEPTH, Unit)
                                    isRasterPage -> RxBus.postWithCode(RxConstants.RX_TRIGGER_ADD_RASTER, Unit)
                                    else -> RxBus.postWithCode(RxConstants.RX_TRIGGER_ADD_WALLPAPER, Unit)
                                }
                            },
                            onApply = {
                                when {
                                    isDepthPage -> RxBus.postWithCode(RxConstants.RX_TRIGGER_APPLY_DEPTH, Unit)
                                    isRasterPage -> RxBus.postWithCode(RxConstants.RX_TRIGGER_APPLY_RASTER, Unit)
                                    else -> RxBus.postWithCode(RxConstants.RX_TRIGGER_APPLY_WALLPAPER, Unit)
                                }
                            },
                            onMoreClick = { showMoreMenu = true },
                            onPreview = {
                                when {
                                    isDepthPage -> RxBus.postWithCode(RxConstants.RX_TRIGGER_PREVIEW_DEPTH, Unit)
                                    isRasterPage -> RxBus.postWithCode(RxConstants.RX_TRIGGER_PREVIEW_RASTER, Unit)
                                    else -> RxBus.postWithCode(RxConstants.RX_TRIGGER_PREVIEW_WALLPAPER, Unit)
                                }
                            },
                            showAddButton = isWallpaperPage || isRasterPage || isDepthPage,
                            showPreviewButton = isWallpaperPage || isDepthPage,
                            showApplyButton = isWallpaperPage || isDepthPage,
                            showMoreButton = true,
                            keepSlotWhenHidden = true
                        )
                    }

                    if (showMoreMenu) {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .pointerInput(currentPageRoute) {
                                    detectTapGestures { showMoreMenu = false }
                                }
                        ) {
                            val menuItems = if (isWallpaperPage) {
                                listOf(
                                    "保存" to {
                                        showMoreMenu = false
                                        RxBus.postWithCode(RxConstants.RX_TRIGGER_SAVE_GROUP, Unit)
                                    },
                                    "选择" to {
                                        showMoreMenu = false
                                        RxBus.postWithCode(RxConstants.RX_TRIGGER_ENTER_SELECT_MODE, Unit)
                                    },
                                    "设置" to {
                                        showMoreMenu = false
                                        openSettingPage()
                                    },
                                    "壁纸组" to {
                                        showMoreMenu = false
                                        openAboutPage()
                                    }
                                )
                            } else if (isRasterPage) {
                                listOf(
                                    "选择" to {
                                        showMoreMenu = false
                                        RxBus.postWithCode(RxConstants.RX_TRIGGER_ENTER_RASTER_SELECT_MODE, Unit)
                                    },
                                    "设置" to {
                                        showMoreMenu = false
                                        openSettingPage()
                                    }
                                )
                            } else {
                                listOf(
                                    "设置" to {
                                        showMoreMenu = false
                                        openSettingPage()
                                    }
                                )
                            }

                            val menuSurfaceColor = if (useDarkTheme) Color(0xCC1E1E1E) else Color(0xEFFFFFFF)
                            Column(
                                Modifier
                                    .padding(top = statusBarTopPaddingDp + 66.dp, end = 12.dp)
                                    .width(140.dp)
                                    .align(Alignment.TopEnd)
                                    .let {
                                        if (enableLiquidGlass && liquidBackdrop != null) {
                                            it.drawBackdrop(
                                                backdrop = liquidBackdrop,
                                                shape = { RoundedRectangle(20f.dp) },
                                                effects = {
                                                    vibrancy()
                                                    blur(if (useDarkTheme) 8f.dp.toPx() else 16f.dp.toPx())
                                                    lens(14f.dp.toPx(), 28f.dp.toPx(), depthEffect = true)
                                                },
                                                onDrawSurface = { drawRect(menuSurfaceColor) }
                                            )
                                        } else {
                                            it
                                                .clip(RoundedCornerShape(20.dp))
                                                .background(menuSurfaceColor)
                                                .border(
                                                    1.dp,
                                                    if (useDarkTheme) Color.White else Color.Black,
                                                    RoundedCornerShape(20.dp)
                                                )
                                        }
                                    }
                                    .padding(8.dp),
                                verticalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                menuItems.forEach { (label, onClick) ->
                                    Row(
                                        Modifier
                                            .fillMaxWidth()
                                            .height(44.dp)
                                            .clip(Capsule())
                                            .clickable { onClick() }
                                            .padding(horizontal = 16.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.Center
                                    ) {
                                        BasicText(
                                            text = label,
                                            style = TextStyle(
                                                color = if (useDarkTheme) Color.White else Color.Black,
                                                fontSize = 15.sp
                                            )
                                        )
                                    }
                                }
                            }
                        }
                    }
                } else {
                    if (showMoreMenu) {
                        showMoreMenu = false
                    }
                }

                 if (showBottomBar && currentRoute == ROUTE_MAIN) {
                    val selectedIndex = pagerState.currentPage

                    LiquidBottomTabs(
                        selectedTabIndex = { selectedIndex },
                        onTabSelected = { index ->
                            scope.launch { pagerState.animateScrollToPage(index) }
                        },
                        backdrop = if (enableLiquidGlass) liquidBackdrop else null,
                        tabsCount = tabItems.size,
                        isLightTheme = !useDarkTheme,
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .padding(horizontal = 36.dp)
                            .padding(bottom = 10.dp)
                    ) {
                        tabItems.forEachIndexed { index, (route, titleRes) ->
                            LiquidBottomTab({
                                scope.launch {
                                    pagerState.animateScrollToPage(
                                        index
                                    )
                                }
                            }) {
                                val selected = selectedIndex == index
                                val selectedColor = BOTTOM_BAR_SELECTED_COLOR
                                Text(
                                    text = getString(titleRes),
                                    color = if (selected) selectedColor else MaterialTheme.colors.onSurface,
                                    fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal
                                )
                            }
                        }
                    }
                }
            }
            
            // 更新对话框
            UpdateDialog(
                state = updateDialogState,
                parentBackdrop = liquidBackdrop,
                onDismiss = {
                    updateDialogState = UpdateDialogState(isVisible = false)
                },
                onConfirm = {
                    val info = updateDialogState.updateInfo
                    if (info != null) {
                        // 开始下载
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
                                // 验证 MD5
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
            
            // 启动时自动检查更新（只检查一次）
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
                        is AppUpdateManager.CheckResult.NoUpdate -> {
                            // 已是最新版本，不显示对话框
                        }
                        is AppUpdateManager.CheckResult.Error -> {
                            // 检查失败，静默处理
                            Log.w("MainActivity", "检查更新失败: ${result.message}")
                        }
                    }
                }
            }
        }
    }


    @Composable
    private fun MainPagerScreen(
        pagerState: androidx.compose.foundation.pager.PagerState,
        useDarkTheme: Boolean,
        onOpenSettingPage: () -> Unit,
        onBottomBarVisibleChange: (Boolean) -> Unit
    ) {
        HorizontalPager(
            state = pagerState,
            modifier = Modifier.fillMaxSize(),
            beyondViewportPageCount = 1,
            userScrollEnabled = showBottomBar
        ) { page ->
            when (tabItems[page].first) {
                ROUTE_MAIN -> MainRouteScreen(
                    useDarkTheme = useDarkTheme,
                    onOpenSettingPage = onOpenSettingPage,
                    onBottomBarVisibleChange = onBottomBarVisibleChange
                )

                ROUTE_RASTER -> RasterRouteScreen(
                    useDarkTheme = useDarkTheme,
                    onBottomBarVisibleChange = onBottomBarVisibleChange
                )

                ROUTE_DEPTH -> DepthRouteScreen(
                    useDarkTheme = useDarkTheme,
                    onBottomBarVisibleChange = onBottomBarVisibleChange
                )
            }
        }
    }

    private fun navigateToRoute(navController: NavHostController, route: String) {
        if (navController.currentBackStackEntry?.destination?.route == route) {
            return // 如果已经在当前页，不执行跳转，避免重复触发
        }
        navController.navigate(route) {
            launchSingleTop = true
            restoreState = true
        }
    }

    fun openAboutPage() {
        pendingRoute = ROUTE_ABOUT
    }

    fun openSettingPage() {
        pendingRoute = ROUTE_SETTING
    }

    fun openAppInfoPage() {
        pendingRoute = ROUTE_APP_INFO
    }

    fun openCorrugatedTestPage() {
        pendingRoute = ROUTE_CORRUGATED_TEST
    }

    fun openDepthModelTestPage() {
        pendingRoute = ROUTE_DEPTH_MODEL_TEST
    }

    fun openPlyModelTestPage() {
        pendingRoute = ROUTE_PLY_MODEL_TEST
    }

    fun setBottomBarVisible(visible: Boolean) {
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
                if (!file.exists()) {
                    file.mkdirs()
                }

                var dataStr = FileUtil.loadData(this@MainActivity, FileUtil.dataPath)
                val saveDataList = JSON.parseArray(dataStr, SaveData::class.java) ?: emptyList()

                saveDataList.forEach { saveData ->
                    JSON.parseArray(saveData.s, TianYinWallpaperModel::class.java)
                        ?.forEach { model ->
                            model.uuid?.let { uuids.add(it) }
                        }
                }

                getSharedPreferences("tianyin", MODE_PRIVATE).getString("wallpaperCache", "")
                    ?.let { cache ->
                        if (cache.isNotEmpty()) {
                            JSON.parseArray(cache, TianYinWallpaperModel::class.java)
                                ?.forEach { model ->
                                    model.uuid?.let { uuids.add(it) }
                                }
                        }
                    }

                dataStr = FileUtil.loadData(applicationContext, FileUtil.wallpaperPath)
                JSON.parseArray(dataStr, TianYinWallpaperModel::class.java)?.forEach { model ->
                    model.uuid?.let { uuids.add(it) }
                }

                file.listFiles()?.forEach { paper ->
                    val keep = uuids.any { uuid -> paper.name.startsWith(uuid) }
                    if (!keep) {
                        paper.delete()
                    }
                }
            } catch (e: Exception) {
                Log.e("MainActivity", "Failed to clear unused wallpaper files", e)
            }
        }
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
            if (ContextCompat.checkSelfPermission(
                    this,
                    perm
                ) != PackageManager.PERMISSION_GRANTED
            ) {
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
