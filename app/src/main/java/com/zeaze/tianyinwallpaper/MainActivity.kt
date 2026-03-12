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
import android.view.WindowManager
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlin.math.abs
import kotlin.math.sign
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.darkColors
import androidx.compose.material.lightColors
import androidx.navigation.compose.NavHost
import androidx.navigation.NavHostController
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
import com.zeaze.tianyinwallpaper.ui.main.MainRouteScreen
import com.zeaze.tianyinwallpaper.ui.setting.SettingRouteScreen
import com.zeaze.tianyinwallpaper.ui.raster.RasterRouteScreen
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
import androidx.compose.foundation.text.BasicText
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.sp
import com.kyant.shapes.Capsule

class MainActivity : BaseActivity() {
    private val tabItems: List<Pair<String, Int>> = listOf(
        ROUTE_MAIN to R.string.main_tab_wallpaper,
        ROUTE_RASTER to R.string.main_tab_raster
    )
    private var showBottomBar by mutableStateOf(true)
    private var pendingRoute by mutableStateOf<String?>(null)

    companion object {
        private const val PERMISSION_REQUEST_CODE = 1
        private const val REQUEST_CODE_SET_WALLPAPER = 0x001
        private const val ROUTE_MAIN = "main"
        private const val ROUTE_ABOUT = "about"
        private const val ROUTE_RASTER = "raster"
        private const val ROUTE_SETTING = "setting"
        const val PREF_THEME_MODE = "themeMode"
        const val THEME_MODE_FOLLOW_SYSTEM = 0
        const val THEME_MODE_LIGHT = 1
        const val THEME_MODE_DARK = 2
        private val BOTTOM_BAR_SELECTED_COLOR = Color(0xFF2A83FF)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
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
                        AboutRouteScreen(onBack = { navController.popBackStack() })
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
                            onThemeModeChange = { mode ->
                                themeMode = mode
                            }
                        )
                    }
                }


                val currentPageRoute =
                    if (currentRoute == ROUTE_MAIN) tabItems.getOrNull(pagerState.currentPage)?.first ?: ROUTE_MAIN
                    else currentRoute
                val isWallpaperPage = currentRoute == ROUTE_MAIN && currentPageRoute == ROUTE_MAIN
                val isRasterPage = currentRoute == ROUTE_MAIN && currentPageRoute == ROUTE_RASTER
                val shouldShowTopBar = showBottomBar && currentRoute == ROUTE_MAIN
                var showMoreMenu by remember { mutableStateOf(false) }

                if (shouldShowTopBar) {
                    val density = LocalDensity.current
                    val statusBarTopPadding = remember(this) {
                        val id = resources.getIdentifier("status_bar_height", "dimen", "android")
                        if (id > 0) resources.getDimensionPixelSize(id) else 0
                    }
                    val statusBarTopPaddingDp = with(density) { statusBarTopPadding.toDp() }

                    MainTopBar(
                        statusBarTopPaddingDp = statusBarTopPaddingDp,
                        enableLiquidGlass = enableLiquidGlass,
                        backdrop = liquidBackdrop,
                        onAdd = {
                            if (isRasterPage) {
                                RxBus.postWithCode(RxConstants.RX_TRIGGER_ADD_RASTER, Unit)
                            } else {
                                RxBus.postWithCode(RxConstants.RX_TRIGGER_ADD_WALLPAPER, Unit)
                            }
                        },
                        onApply = {
                            if (isRasterPage) {
                                RxBus.postWithCode(RxConstants.RX_TRIGGER_APPLY_RASTER, Unit)
                            } else {
                                RxBus.postWithCode(RxConstants.RX_TRIGGER_APPLY_WALLPAPER, Unit)
                            }
                        },
                        onMoreClick = { showMoreMenu = true },
                        onPreview = {
                            if (isRasterPage) {
                                RxBus.postWithCode(RxConstants.RX_TRIGGER_PREVIEW_RASTER, Unit)
                            } else {
                                RxBus.postWithCode(RxConstants.RX_TRIGGER_PREVIEW_WALLPAPER, Unit)
                            }
                        },
                        showAddButton = isWallpaperPage || isRasterPage,
                        showPreviewButton = isWallpaperPage,
                        showApplyButton = isWallpaperPage,
                        showMoreButton = true,
                        keepSlotWhenHidden = true
                    )

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

                    if (enableLiquidGlass && liquidBackdrop != null) {
                        LiquidBottomTabs(
                            selectedTabIndex = { selectedIndex },
                            onTabSelected = { index ->
                                scope.launch { pagerState.animateScrollToPage(index) }
                            },
                            backdrop = liquidBackdrop,
                            tabsCount = tabItems.size,
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
                    } else {
                        // Fallback manual bottom bar for older versions
                        Box(
                            modifier = Modifier
                                .align(Alignment.BottomCenter)
                                .fillMaxWidth()
                                .height(84.dp)
                                .padding(horizontal = 16.dp, vertical = 6.dp)
                                .clip(RoundedCornerShape(26.dp))
                                .background(
                                    Brush.verticalGradient(
                                        colors = listOf(Color(0xCCFFFFFF), Color(0x66FFFFFF))
                                    )
                                )
                                .border(1.dp, Color(0x80FFFFFF), RoundedCornerShape(26.dp))
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 10.dp, vertical = 8.dp),
                                horizontalArrangement = Arrangement.SpaceAround,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                tabItems.forEachIndexed { index, (route, titleRes) ->
                                    Text(
                                        text = getString(titleRes),
                                        color = if (selectedIndex == index) MaterialTheme.colors.primary else MaterialTheme.colors.onSurface,
                                        fontWeight = if (selectedIndex == index) FontWeight.Bold else FontWeight.Normal,
                                        modifier = Modifier
                                            .clickable {
                                                scope.launch { pagerState.animateScrollToPage(index) }
                                            }
                                            .padding(horizontal = 8.dp, vertical = 8.dp)
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }


    @Composable
    private fun MainPagerScreen(
        pagerState: androidx.compose.foundation.pager.PagerState,
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
                    onOpenSettingPage = onOpenSettingPage,
                    onBottomBarVisibleChange = onBottomBarVisibleChange
                )

                ROUTE_RASTER -> RasterRouteScreen(onBottomBarVisibleChange = onBottomBarVisibleChange)
            }
        }
    }

    private fun navigateToRoute(navController: NavHostController, route: String) {
        if (navController.currentBackStackEntry?.destination?.route == route) {
            return // 如果已经在当前页，不执行跳转，避免重复触发
        }
        navController.navigate(route) {
            // 弹出到图表的起始目标，避免堆栈无限增长
            popUpTo(navController.graph.startDestinationId) {
                saveState = true
            }
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

    override fun onResume() {
        super.onResume()
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
