package com.zeaze.tianyinwallpaper

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Point
import android.os.Bundle
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.compose.setContent
import androidx.appcompat.app.AlertDialog
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeContentPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Text
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalDensity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material.darkColors
import androidx.compose.material.lightColors
import androidx.navigation.compose.NavHost
import androidx.navigation.NavHostController
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.alibaba.fastjson.JSON
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberCombinedBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.effects.lens
import com.kyant.backdrop.effects.vibrancy
import com.zeaze.tianyinwallpaper.base.BaseActivity
import com.zeaze.tianyinwallpaper.model.TianYinWallpaperModel
import com.zeaze.tianyinwallpaper.ui.about.AboutRouteScreen
import com.zeaze.tianyinwallpaper.ui.commom.SaveData
import com.zeaze.tianyinwallpaper.ui.main.MainRouteScreen
import com.zeaze.tianyinwallpaper.ui.setting.SettingRouteScreen
import com.zeaze.tianyinwallpaper.utils.FileUtil
import java.io.File
import kotlinx.coroutines.launch

class MainActivity : BaseActivity() {
    private val tabItems: List<Pair<String, Int>> = listOf(
        ROUTE_MAIN to R.string.main_tab_wallpaper,
        ROUTE_ABOUT to R.string.main_tab_groups
    )
    private var showBottomBar by mutableStateOf(true)
    private var pendingRoute by mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MainActivityScreen()
        }

        val wm = this.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val point = Point()
        wm.defaultDisplay.getRealSize(point)
        FileUtil.width = point.x
        FileUtil.height = point.y
        permission()
        clearNoUseFile()
    }

    @Composable
    private fun MainActivityScreen() {
        val pref = remember(this) { getSharedPreferences(App.TIANYIN, Context.MODE_PRIVATE) }
        var themeMode by remember { mutableStateOf(pref.getInt(PREF_THEME_MODE, THEME_MODE_FOLLOW_SYSTEM)) }
        val useDarkTheme = when (themeMode) {
            THEME_MODE_DARK -> true
            THEME_MODE_LIGHT -> false
            else -> isSystemInDarkTheme()
        }
        MaterialTheme(colors = if (useDarkTheme) darkColors() else lightColors()) {
        val themeBackgroundColor = MaterialTheme.colors.background
        val enableLiquidGlass = android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU
        val navController = rememberNavController()
        val navBackStackEntry by navController.currentBackStackEntryAsState()
        val currentRoute = navBackStackEntry?.destination?.route ?: ROUTE_MAIN
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
                    .composed {
                        if (enableLiquidGlass && liquidBackdrop != null) {
                            layerBackdrop(liquidBackdrop)
                        } else {
                            this
                        }
                    }
            ) {
                composable(ROUTE_MAIN) {
                    MainRouteScreen(
                        onOpenSettingPage = { openSettingPage() },
                        onBottomBarVisibleChange = { setBottomBarVisible(it) }
                    )
                }
                composable(ROUTE_ABOUT) {
                    AboutRouteScreen()
                }
                composable(ROUTE_SETTING) {
                    SettingRouteScreen(
                        onThemeModeChange = { mode ->
                            themeMode = mode
                        }
                    )
                }
            }
            if (showBottomBar && currentRoute != ROUTE_SETTING) {
                if (enableLiquidGlass && liquidBackdrop != null) {
                    val selectedTabIndex = tabItems.indexOfFirst { it.first == currentRoute }
                    if (selectedTabIndex >= 0) {
                        LiquidBottomTabs(
                            modifier = Modifier
                                .align(Alignment.BottomCenter)
                                .safeContentPadding()
                                .height(64.dp)
                                .fillMaxWidth()
                                .padding(horizontal = 24.dp, vertical = 14.dp),
                            tabItems = tabItems,
                            selectedTabIndex = selectedTabIndex,
                            backdrop = liquidBackdrop,
                            onTabSelected = { index ->
                                tabItems.getOrNull(index)?.first?.let { route ->
                                    navigateToRoute(navController, route)
                                }
                            }
                        )
                    }
                } else {
                    Box(
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .fillMaxWidth()
                            .padding(horizontal = 24.dp, vertical = 14.dp)
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
                            tabItems.forEach { (route, titleRes) ->
                                Text(
                                    text = getString(titleRes),
                                    color = if (currentRoute == route) MaterialTheme.colors.primary else MaterialTheme.colors.onSurface,
                                    fontWeight = if (currentRoute == route) FontWeight.Bold else FontWeight.Normal,
                                    modifier = Modifier
                                        .clickable {
                                            navigateToRoute(navController, route)
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
    private fun LiquidBottomTabs(
        modifier: Modifier,
        tabItems: List<Pair<String, Int>>,
        selectedTabIndex: Int,
        backdrop: com.kyant.backdrop.Backdrop,
        onTabSelected: (index: Int) -> Unit
    ) {
        if (tabItems.isEmpty()) return
        val animationScope = rememberCoroutineScope()
        val pressAnimationSpec = remember {
            spring<Float>(
                dampingRatio = BOTTOM_BAR_PRESS_DAMPING_RATIO,
                stiffness = BOTTOM_BAR_PRESS_STIFFNESS
            )
        }
        val tabCount = tabItems.size
        val selectedIndex = selectedTabIndex
        val isDarkTheme = isSystemInDarkTheme()
        val indicatorProgress = remember { Animatable(selectedIndex.toFloat()) }
        val density = LocalDensity.current
        LaunchedEffect(selectedIndex) {
            indicatorProgress.animateTo(selectedIndex.toFloat(), pressAnimationSpec)
        }
        val pressAnimations = remember(tabCount) { List(tabCount) { Animatable(0f) } }
        val combinedBackdrop = rememberCombinedBackdrop(backdrop, rememberLayerBackdrop())
        BoxWithConstraints(modifier = modifier) {
            val itemWidth = remember(maxWidth, tabCount) { maxWidth / tabCount }
            val itemWidthPx = with(density) { itemWidth.toPx() }
            val indicatorWidth = itemWidth * 0.68f
            val indicatorWidthPx = with(density) { indicatorWidth.toPx() }
            val selectedTint = MaterialTheme.colors.primary
            val selectedColor = BOTTOM_BAR_SELECTED_COLOR
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .drawBackdrop(
                        backdrop = backdrop,
                        shape = { RoundedCornerShape(28.dp) },
                        effects = {
                            vibrancy()
                            blur(8.dp.toPx())
                            lens(22.dp.toPx(), 18.dp.toPx())
                        },
                        onDrawSurface = {
                            drawRect(
                                if (isDarkTheme) Color(0xFF101010).copy(alpha = 0.45f)
                                else Color.White.copy(alpha = 0.55f)
                            )
                        }
                    )
            )
            Box(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .fillMaxWidth()
                    .height(24.dp)
                    .drawBackdrop(
                        backdrop = backdrop,
                        shape = { RectangleShape },
                        effects = {
                            blur(4.dp.toPx())
                            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                                lens(16.dp.toPx(), 10.dp.toPx())
                            }
                        },
                        onDrawSurface = {
                            val tintColor =
                                if (isDarkTheme) Color.Black.copy(alpha = 0.22f)
                                else Color.White.copy(alpha = 0.45f)
                            drawRect(
                                brush = Brush.verticalGradient(
                                    colors = listOf(tintColor, Color.Transparent),
                                    startY = 0f,
                                    endY = size.height
                                )
                            )
                        }
                    )
            )
            Box(
                modifier = Modifier
                    .align(Alignment.CenterStart)
                    .offset {
                        IntOffset(
                            (itemWidthPx * indicatorProgress.value + (itemWidthPx - indicatorWidthPx) / 2f).toInt(),
                            6.dp.roundToPx()
                        )
                    }
                    .width(indicatorWidth)
                    .height(52.dp)
                    .padding(
                        horizontal = 4.dp,
                        vertical = 2.dp
                    )
                    .drawBackdrop(
                        backdrop = combinedBackdrop,
                        shape = { RoundedCornerShape(22.dp) },
                        effects = {
                            vibrancy()
                            blur(8.dp.toPx())
                            lens(20.dp.toPx(), 20.dp.toPx())
                        },
                        onDrawSurface = {
                            drawRect(selectedTint.copy(alpha = 0.20f), blendMode = BlendMode.Hue)
                            drawRect(Color.White.copy(alpha = 0.18f))
                        }
                    )
            )
            Row(
                modifier = Modifier.fillMaxSize(),
                horizontalArrangement = Arrangement.Start,
                verticalAlignment = Alignment.CenterVertically
            ) {
                tabItems.forEachIndexed { index, (route, titleRes) ->
                    val selected = selectedIndex == index
                    val pressAnimation = pressAnimations[index]
                    Box(
                        modifier = Modifier
                            .graphicsLayer {
                                val scale = 1f + BOTTOM_BAR_PRESS_SCALE_DELTA * pressAnimation.value
                                scaleX = scale
                                scaleY = scale
                            }
                            .clickable { onTabSelected(index) }
                            .pointerInput(index) {
                                awaitEachGesture {
                                    awaitFirstDown()
                                    animationScope.launch {
                                        pressAnimation.stop()
                                        pressAnimation.animateTo(1f, pressAnimationSpec)
                                    }
                                    waitForUpOrCancellation()
                                    animationScope.launch {
                                        pressAnimation.stop()
                                        pressAnimation.animateTo(0f, pressAnimationSpec)
                                    }
                                }
                            }
                            .fillMaxHeight()
                            .weight(1f),
                        contentAlignment = Alignment.Center
                    ) {
                        val tabIcon = tabSymbol(route)
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center
                        ) {
                            Text(
                                text = tabIcon,
                                color = if (selected) selectedColor else MaterialTheme.colors.onSurface
                            )
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
    }

    private fun navigateToRoute(navController: NavHostController, route: String) {
        navController.navigate(route) {
            popUpTo(ROUTE_MAIN) {
                saveState = true
            }
            restoreState = true
            launchSingleTop = true
        }
    }

    private fun tabSymbol(route: String): String = when (route) {
        ROUTE_MAIN -> "✈"
        ROUTE_ABOUT -> "◎"
        else -> "•"
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
                val uuids: MutableList<String?> = ArrayList()
                val file = File(getExternalFilesDir(null).toString() + FileUtil.wallpaperFilePath)
                if (!file.exists()) {
                    file.mkdirs()
                }
                var s = FileUtil.loadData(this@MainActivity, FileUtil.dataPath)
                val saveDataList = JSON.parseArray(s, SaveData::class.java) ?: emptyList()
                var list: List<TianYinWallpaperModel>
                for (saveData in saveDataList) {
                    list = JSON.parseArray(saveData.s, TianYinWallpaperModel::class.java) ?: emptyList()
                    for (model in list) {
                        uuids.add(model.uuid)
                    }
                }
                val cache = getSharedPreferences("tianyin", MODE_PRIVATE).getString("wallpaperCache", "")
                if (!cache.isNullOrEmpty()) {
                    list = JSON.parseArray(cache, TianYinWallpaperModel::class.java) ?: emptyList()
                    for (model in list) {
                        uuids.add(model.uuid)
                    }
                }
                s = FileUtil.loadData(applicationContext, FileUtil.wallpaperPath)
                list = JSON.parseArray(s, TianYinWallpaperModel::class.java) ?: emptyList()
                for (model in list) {
                    uuids.add(model.uuid)
                }
                val papers = file.list()
                if (papers != null) {
                    for (paper in papers) {
                        var keep = false
                        for (uuid in uuids) {
                            if (uuid != null && paper.startsWith(uuid)) {
                                keep = true
                                break
                            }
                        }
                        if (!keep) {
                            File(file, paper).delete()
                        }
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }.start()
    }

    private fun permission() {
        val permissionList: MutableList<String> = ArrayList()
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.INTERNET) != PackageManager.PERMISSION_GRANTED) {
            permissionList.add(Manifest.permission.INTERNET)
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.SET_WALLPAPER) != PackageManager.PERMISSION_GRANTED) {
            permissionList.add(Manifest.permission.SET_WALLPAPER)
        }
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_MEDIA_IMAGES) != PackageManager.PERMISSION_GRANTED) {
                permissionList.add(Manifest.permission.READ_MEDIA_IMAGES)
            }
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_MEDIA_VIDEO) != PackageManager.PERMISSION_GRANTED) {
                permissionList.add(Manifest.permission.READ_MEDIA_VIDEO)
            }
        } else {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
                permissionList.add(Manifest.permission.READ_EXTERNAL_STORAGE)
            }
        }
        if (permissionList.isNotEmpty()) {
            val permissions = permissionList.toTypedArray()
            ActivityCompat.requestPermissions(this, permissions, 1)
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        for (i in grantResults.indices) {
            if (grantResults[i] == PackageManager.PERMISSION_DENIED) {
                AlertDialog.Builder(this)
                    .setMessage("没有获取到" + permissions[i] + "权限，无法使用，请去系统设置里开启权限")
                    .setPositiveButton("确定") { _, _ -> finish() }
                    .setCancelable(false)
                    .create()
                    .show()
                return
            }
        }
    }

    companion object {
        private const val REQUEST_CODE_SET_WALLPAPER = 0x001
        private const val ROUTE_MAIN = "main"
        private const val ROUTE_ABOUT = "about"
        private const val ROUTE_SETTING = "setting"
        private val APP_BACKGROUND_COLOR = Color(0xFFEDEDED)
        const val PREF_THEME_MODE = "themeMode"
        const val THEME_MODE_FOLLOW_SYSTEM = 0
        const val THEME_MODE_LIGHT = 1
        const val THEME_MODE_DARK = 2
        private const val BOTTOM_BAR_BLUR_RADIUS = 4f
        private const val BOTTOM_BAR_LENS_WIDTH = 16f
        private const val BOTTOM_BAR_LENS_HEIGHT = 32f
        private const val BOTTOM_BAR_SELECTED_TINT_ALPHA = 0.45f
        private val BOTTOM_BAR_SELECTED_COLOR = Color(0xFF2A83FF)
        private const val BOTTOM_BAR_PRESS_DAMPING_RATIO = 0.5f
        private const val BOTTOM_BAR_PRESS_STIFFNESS = 300f
        private const val BOTTOM_BAR_PRESS_SCALE_DELTA = 0.08f
        private const val INDICATOR_VERTICAL_OFFSET_DP = -4
        private const val INDICATOR_HORIZONTAL_PADDING_DP = 6
        private const val INDICATOR_VERTICAL_PADDING_DP = 6
    }
}
