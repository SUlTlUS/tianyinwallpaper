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
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.EaseOut
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.util.fastCoerceIn
import androidx.compose.ui.util.fastRoundToInt
import androidx.compose.ui.util.lerp
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.Stable
import androidx.compose.foundation.layout.RowScope
import kotlin.math.abs
import kotlin.math.sign
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
import com.kyant.backdrop.effects.*
import com.zeaze.tianyinwallpaper.base.BaseActivity
import com.zeaze.tianyinwallpaper.model.TianYinWallpaperModel
import com.zeaze.tianyinwallpaper.ui.about.AboutRouteScreen
import com.zeaze.tianyinwallpaper.ui.commom.SaveData
import com.zeaze.tianyinwallpaper.ui.main.MainRouteScreen
import com.zeaze.tianyinwallpaper.ui.setting.SettingRouteScreen
import com.zeaze.tianyinwallpaper.utils.FileUtil
import java.io.File
import kotlinx.coroutines.launch
import androidx.compose.ui.input.pointer.PointerInputChange
import com.kyant.backdrop.highlight.Highlight
import com.kyant.backdrop.shadow.InnerShadow
import com.kyant.backdrop.shadow.Shadow

val LocalLiquidBottomTabScale = compositionLocalOf { { 1f } }

@Stable
class DampedDragAnimation(
    val animationScope: kotlinx.coroutines.CoroutineScope,
    initialValue: Float,
    val valueRange: ClosedFloatingPointRange<Float>,
) {
    var anim = Animatable(initialValue)
    var pressAnim = Animatable(0f)
    val pressProgress: Float get() = pressAnim.value
    val value: Float get() = anim.value
    val velocity: Float get() = anim.velocity
    var targetValue by mutableStateOf(initialValue)

    // 交互回调
    var onDragStarted: () -> Unit = {}
    var onDrag: (DampedDragAnimation.(dragAmount: Offset) -> Unit) = { _ -> }
    var onDragStopped: (DampedDragAnimation.() -> Unit) = {}

    val modifier = Modifier.pointerInput(Unit) {
        detectDragGestures(
            onDragStart = {
                onDragStarted()
                animationScope.launch {
                    pressAnim.animateTo(1f, spring(0.5f, 400f))
                }
            },
            onDragEnd = {
                animationScope.launch {
                    pressAnim.animateTo(0f, spring(0.5f, 400f))
                }
                onDragStopped()
            },
            onDragCancel = {
                animationScope.launch {
                    pressAnim.animateTo(0f, spring(0.5f, 400f))
                }
                onDragStopped()
            },
            onDrag = { change: PointerInputChange, dragAmount: Offset ->
                change.consume()
                onDrag(dragAmount)
            }
        )
    }

    fun updateValue(newValue: Float) {
        targetValue = newValue
        animationScope.launch { anim.snapTo(newValue) }
    }

    suspend fun animateToValue(target: Float) {
        targetValue = target
        anim.animateTo(target, spring(0.8f, 400f))
    }
}

class InteractiveHighlight(
    val animationScope: kotlinx.coroutines.CoroutineScope,
    val position: (androidx.compose.ui.unit.IntSize, Offset) -> Offset
) {
    var touchOffset by mutableStateOf(Offset.Zero)
    val gestureModifier = Modifier.pointerInput(Unit) {
        awaitPointerEventScope {
            while (true) {
                val event = awaitPointerEvent()
                touchOffset = event.changes.first().position
            }
        }
    }
    val modifier = Modifier
}

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
        var themeMode by remember { mutableStateOf(pref.getInt(PREF_THEME_MODE, THEME_MODE_FOLLOW_SYSTEM)) }
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
                    val selectedIndex = tabItems.indexOfFirst { it.first == currentRoute }
                    val tabsModifier = Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .height(84.dp)
                        .padding(horizontal = 16.dp, vertical = 6.dp)

                    if (enableLiquidGlass && liquidBackdrop != null) {
                        LiquidBottomTabs(
                            selectedTabIndex = { if (selectedIndex >= 0) selectedIndex else 0 },
                            onTabSelected = { index ->
                                navigateToRoute(navController, tabItems[index].first)
                            },
                            backdrop = liquidBackdrop,
                            tabsCount = tabItems.size,
                            modifier = tabsModifier
                        ) {
                            tabItems.forEachIndexed { index, (route, titleRes) ->
                                val selected = currentRoute == route
                                val selectedColor = BOTTOM_BAR_SELECTED_COLOR
                                val scale = LocalLiquidBottomTabScale.current
                                Box(
                                    modifier = Modifier
                                        .fillMaxHeight()
                                        .weight(1f)
                                        .graphicsLayer {
                                            scaleX = scale()
                                            scaleY = scale()
                                        }
                                        .clickable {
                                            navigateToRoute(navController, route)
                                        },
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
                    } else {
                        // Fallback manual bottom bar for older versions
                        Box(
                            modifier = tabsModifier
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
        selectedTabIndex: () -> Int,
        onTabSelected: (index: Int) -> Unit,
        backdrop: com.kyant.backdrop.Backdrop,
        tabsCount: Int,
        modifier: Modifier = Modifier,
        content: @Composable RowScope.() -> Unit
    ) {
        val isLightTheme = !isSystemInDarkTheme()
        val accentColor = if (isLightTheme) Color(0xFF0088FF) else Color(0xFF0091FF)
        val containerColor = if (isLightTheme) Color(0xFFFAFAFA).copy(0.4f) else Color(0xFF121212).copy(0.4f)
        val tabsBackdrop = rememberLayerBackdrop()

        BoxWithConstraints(
            modifier,
            contentAlignment = Alignment.CenterStart
        ) {
            val constraints = this.constraints
            val density = LocalDensity.current
            val tabWidth = with(density) { (constraints.maxWidth.toFloat() - 16f.dp.toPx()) / tabsCount }
            val offsetAnimation = remember { Animatable(0f) }
            val panelOffset by remember(density) {
                derivedStateOf<Float> {
                    val fraction = (offsetAnimation.value / constraints.maxWidth).fastCoerceIn(-1f, 1f)
                    with(density) { 4f.dp.toPx() * fraction.sign * EaseOut.transform(abs(fraction)) }
                }
            }

            val isLtr = LocalLayoutDirection.current == LayoutDirection.Ltr
            val animationScope = rememberCoroutineScope()
            var currentIndex by remember { mutableIntStateOf(selectedTabIndex()) }
            val dampedDragAnimation = remember(animationScope, tabsCount) {
                val animation = DampedDragAnimation(
                    animationScope = animationScope,
                    initialValue = selectedTabIndex().toFloat(),
                    valueRange = 0f..(tabsCount - 1).toFloat(),
                )
                animation.onDrag = { dragAmount ->
                    val delta = dragAmount.x / tabWidth * if (isLtr) 1f else -1f
                    updateValue((targetValue + delta).fastCoerceIn(0f, (tabsCount - 1).toFloat()))
                    animationScope.launch { offsetAnimation.snapTo(offsetAnimation.value + dragAmount.x) }
                }
                animation.onDragStopped = {
                    val targetIndex = targetValue.fastRoundToInt().fastCoerceIn(0, tabsCount - 1)
                    if (currentIndex != targetIndex) {
                        currentIndex = targetIndex
                        onTabSelected(targetIndex)
                    }
                    animationScope.launch { animateToValue(targetIndex.toFloat()) }
                    animationScope.launch { offsetAnimation.animateTo(0f, spring(1f, 300f, 0.5f)) }
                }
                animation
            }

            val externalIndex = selectedTabIndex()
            LaunchedEffect(externalIndex) {
                if (currentIndex != externalIndex) { currentIndex = externalIndex }
                dampedDragAnimation.animateToValue(externalIndex.toFloat())
            }

            val interactiveHighlight = remember(animationScope) {
                InteractiveHighlight(animationScope = animationScope, position = { size, _ ->
                    val basePadding = with(density) { 8.dp.toPx() }
                    Offset(
                        if (isLtr) (dampedDragAnimation.value + 0.5f) * tabWidth + panelOffset + basePadding
                        else size.width - (dampedDragAnimation.value + 0.5f) * tabWidth + panelOffset - basePadding,
                        size.height / 2f
                    )
                })
            }

            // 面板层 (Base Panel)
            Row(
                Modifier
                    .padding(horizontal = 8.dp) // 移到前面：改变外部框宽度
                    .graphicsLayer {
                        translationX = panelOffset
                    }
                    .drawBackdrop(
                        backdrop = backdrop,
                        shape = { RoundedCornerShape(percent = 50) },
                        effects = {
                            vibrancy()
                            blur(8f.dp.toPx())
                            lens(24f.dp.toPx(), 24f.dp.toPx())
                        },
                        layerBlock = {
                            val progress = dampedDragAnimation.pressProgress
                            val scale = lerp(1f, 1f + 16f.dp.toPx() / size.width, progress)
                            scaleX = scale
                            scaleY = scale
                        },
                        onDrawSurface = { drawRect(containerColor) }
                    )
                    .then(interactiveHighlight.modifier)
                    .height(64.dp)
                    .fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                content = content
            )

            // 指示器跟随内容层 (Revealed Content)
            CompositionLocalProvider(
                LocalLiquidBottomTabScale provides { lerp(1f, 1.3f, dampedDragAnimation.pressProgress) }
            ) {
                Row(
                    Modifier
                        .padding(horizontal = 8.dp) // 移到前面：同步缩减背景框
                        .clearAndSetSemantics {}
                        .alpha(0f)
                        .layerBackdrop(tabsBackdrop)
                        .graphicsLayer {
                            translationX = panelOffset
                        }
                        .drawBackdrop(
                            backdrop = backdrop,
                            shape = { RoundedCornerShape(percent = 50) },
                            effects = {
                                val progress = dampedDragAnimation.pressProgress
                                vibrancy()
                                blur(8f.dp.toPx())
                                lens(
                                    24f.dp.toPx() * progress,
                                    24f.dp.toPx() * progress
                                )
                            },
                            highlight = {
                                val progress = dampedDragAnimation.pressProgress
                                Highlight.Default.copy(alpha = progress)
                            },
                            onDrawSurface = { drawRect(containerColor) }
                        )
                        .then(interactiveHighlight.modifier)
                        .height(64.dp) // 修正高度为 64dp
                        .fillMaxWidth()
                        .graphicsLayer(colorFilter = ColorFilter.tint(accentColor)),
                    verticalAlignment = Alignment.CenterVertically,
                    content = content
                )
            }

            // 上层交互指示器 (Indicator Capsule)
            Box(
                Modifier
                    .graphicsLayer {
                        val basePadding = with(density) { 8.dp.toPx() }
                        val maxWidthPx = constraints.maxWidth.toFloat()
                        translationX = if (isLtr) dampedDragAnimation.value * tabWidth + panelOffset + basePadding
                        else maxWidthPx - (dampedDragAnimation.value + 1f) * tabWidth + panelOffset - basePadding
                    }
                    .padding(horizontal = 2.dp) // 移动到 graphicsLayer 之后：确保左右间距均匀且不增加布局容器宽度
                    .then(interactiveHighlight.gestureModifier)
                    .then(dampedDragAnimation.modifier)
                    .drawBackdrop(
                        backdrop = rememberCombinedBackdrop(backdrop, tabsBackdrop),
                        shape = { RoundedCornerShape(percent = 50) },
                        effects = {
                            val progress = dampedDragAnimation.pressProgress
                            lens(
                                10f.dp.toPx() * progress,
                                14f.dp.toPx() * progress,
                                chromaticAberration = true
                            )
                        },
                        highlight = {
                            val progress = dampedDragAnimation.pressProgress
                            Highlight.Default.copy(alpha = progress)
                        },
                        shadow = {
                            val progress = dampedDragAnimation.pressProgress
                            Shadow(alpha = progress)
                        },
                        innerShadow = {
                            val progress = dampedDragAnimation.pressProgress
                            InnerShadow(
                                radius = 8f.dp * progress,
                                alpha = progress
                            )
                        },
                        layerBlock = {
                            // 物理内核逻辑：基于速度的拉伸 (Squash & Stretch)
                            // v 越大，X 变长，Y 变扁
                            val v = dampedDragAnimation.velocity * 0.05f
                            scaleX = 1f + abs(v)
                            scaleY = 1f / (1f + abs(v))

                            // 应用 pressProgress 带来的额外整体缩放 (1.25x)
                            val p = dampedDragAnimation.pressProgress
                            val extraScale = lerp(1f, 1.4286f, p)
                            scaleX *= extraScale
                            scaleY *= extraScale
                        },
                        onDrawSurface = {
                            val progress = dampedDragAnimation.pressProgress
                            drawRect(
                                if (isLightTheme) Color.Black.copy(0.1f)
                                else Color.White.copy(0.1f),
                                alpha = 1f - progress
                            )
                            drawRect(Color.Black.copy(alpha = 0.03f * progress))
                        }
                    )
                    .height(56.dp)
                    .width(with(density) { tabWidth.toDp() })
            )
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
                val uuids = mutableListOf<String>()
                val file = File(getExternalFilesDir(null), FileUtil.wallpaperFilePath)
                if (!file.exists()) {
                    file.mkdirs()
                }

                var dataStr = FileUtil.loadData(this@MainActivity, FileUtil.dataPath)
                val saveDataList = JSON.parseArray(dataStr, SaveData::class.java) ?: emptyList()

                saveDataList.forEach { saveData ->
                    JSON.parseArray(saveData.s, TianYinWallpaperModel::class.java)?.forEach { model ->
                        model.uuid?.let { uuids.add(it) }
                    }
                }

                getSharedPreferences("tianyin", MODE_PRIVATE).getString("wallpaperCache", "")?.let { cache ->
                    if (cache.isNotEmpty()) {
                        JSON.parseArray(cache, TianYinWallpaperModel::class.java)?.forEach { model ->
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
            listOf(Manifest.permission.INTERNET, Manifest.permission.SET_WALLPAPER, Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VIDEO)
        } else {
            listOf(Manifest.permission.INTERNET, Manifest.permission.SET_WALLPAPER, Manifest.permission.READ_EXTERNAL_STORAGE)
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

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
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


    companion object {
        private const val PERMISSION_REQUEST_CODE = 1
        private const val REQUEST_CODE_SET_WALLPAPER = 0x001
        private const val ROUTE_MAIN = "main"
        private const val ROUTE_ABOUT = "about"
        private const val ROUTE_SETTING = "setting"
        const val PREF_THEME_MODE = "themeMode"
        const val THEME_MODE_FOLLOW_SYSTEM = 0
        const val THEME_MODE_LIGHT = 1
        const val THEME_MODE_DARK = 2
        private val BOTTOM_BAR_SELECTED_COLOR = Color(0xFF2A83FF)
        private const val BOTTOM_BAR_PRESS_DAMPING_RATIO = 0.5f
        private const val BOTTOM_BAR_PRESS_STIFFNESS = 300f
        private const val BOTTOM_BAR_PRESS_SCALE_DELTA = 0.08f
    }
}




























