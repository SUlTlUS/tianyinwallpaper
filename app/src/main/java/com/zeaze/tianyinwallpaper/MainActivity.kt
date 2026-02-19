package com.zeaze.tianyinwallpaper

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Point
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.FrameLayout
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.compose.setContent
import androidx.appcompat.app.AlertDialog
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.navigation.compose.NavHost
import androidx.navigation.NavHostController
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.alibaba.fastjson.JSON
import com.pgyer.pgyersdk.PgyerSDKManager
import com.pgyer.pgyersdk.callback.CheckoutVersionCallBack
import com.pgyer.pgyersdk.model.CheckSoftModel
import com.zeaze.tianyinwallpaper.base.BaseActivity
import com.zeaze.tianyinwallpaper.model.TianYinWallpaperModel
import com.zeaze.tianyinwallpaper.ui.about.AboutFragment
import com.zeaze.tianyinwallpaper.ui.commom.SaveData
import com.zeaze.tianyinwallpaper.ui.main.MainFragment
import com.zeaze.tianyinwallpaper.ui.setting.SettingFragment
import com.zeaze.tianyinwallpaper.utils.FileUtil
import java.io.File

class MainActivity : BaseActivity() {
    private val tabItems: List<Pair<String, Int>> = listOf(
        ROUTE_MAIN to R.string.main_tab_wallpaper,
        ROUTE_ABOUT to R.string.main_tab_groups,
        ROUTE_SETTING to R.string.main_tab_settings
    )
    private val mainContainerId: Int = View.generateViewId()
    private val aboutContainerId: Int = View.generateViewId()
    private val settingContainerId: Int = View.generateViewId()
    private var showBottomBar by mutableStateOf(true)
    private var pendingRoute by mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                MainActivityScreen()
            }
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
        Box(modifier = Modifier
            .fillMaxSize()
            .background(APP_BACKGROUND_COLOR)) {
            NavHost(
                navController = navController,
                startDestination = ROUTE_MAIN,
                modifier = Modifier.fillMaxSize()
            ) {
                composable(ROUTE_MAIN) {
                    FragmentHost(routeTag = ROUTE_MAIN, containerId = mainContainerId) { MainFragment() }
                }
                composable(ROUTE_ABOUT) {
                    FragmentHost(routeTag = ROUTE_ABOUT, containerId = aboutContainerId) { AboutFragment() }
                }
                composable(ROUTE_SETTING) {
                    FragmentHost(routeTag = ROUTE_SETTING, containerId = settingContainerId) { SettingFragment() }
                }
            }
            if (showBottomBar) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(MaterialTheme.colors.surface)
                        .padding(horizontal = 10.dp, vertical = 8.dp)
                        .align(Alignment.BottomCenter),
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

    @Composable
    private fun FragmentHost(routeTag: String, containerId: Int, factory: () -> Fragment) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { context ->
                FrameLayout(context).apply {
                    id = containerId
                }
            },
            update = {
                if (supportFragmentManager.isStateSaved) {
                    return@AndroidView
                }
                val current = supportFragmentManager.findFragmentById(containerId)
                if (current?.tag == routeTag) {
                    return@AndroidView
                }
                val fragment = supportFragmentManager.findFragmentByTag(routeTag) ?: factory()
                supportFragmentManager.beginTransaction()
                    .replace(containerId, fragment, routeTag)
                    .commit()
            }
        )
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
        PgyerSDKManager.checkSoftwareUpdate(this, object : CheckoutVersionCallBack {
            override fun onSuccess(checkSoftModel: CheckSoftModel) {
                AlertDialog.Builder(this@MainActivity)
                    .setTitle("检测到有新版本")
                    .setMessage(checkSoftModel.buildUpdateDescription)
                    .setPositiveButton("更新") { _, _ ->
                        val uri = Uri.parse(checkSoftModel.downloadURL)
                        val intent = Intent(Intent.ACTION_VIEW, uri)
                        startActivity(intent)
                    }
                    .setNegativeButton("下次", null)
                    .setCancelable(false)
                    .create()
                    .show()
            }

            override fun onFail(s: String) {
            }
        })
    }

    private fun clearNoUseFile() {
        Thread {
            val uuids: MutableList<String?> = ArrayList()
            val file = File(getExternalFilesDir(null).toString() + FileUtil.wallpaperFilePath)
            if (!file.exists()) {
                file.mkdirs()
            }
            var s = FileUtil.loadData(this@MainActivity, FileUtil.dataPath)
            val saveDataList = JSON.parseArray(s, SaveData::class.java)
            var list: List<TianYinWallpaperModel>
            for (saveData in saveDataList) {
                list = JSON.parseArray(saveData.s, TianYinWallpaperModel::class.java)
                for (model in list) {
                    uuids.add(model.uuid)
                }
            }
            val cache = getSharedPreferences("tianyin", MODE_PRIVATE).getString("wallpaperCache", "")
            if (!cache.isNullOrEmpty()) {
                list = JSON.parseArray(cache, TianYinWallpaperModel::class.java)
                for (model in list) {
                    uuids.add(model.uuid)
                }
            }
            s = FileUtil.loadData(applicationContext, FileUtil.wallpaperPath)
            list = JSON.parseArray(s, TianYinWallpaperModel::class.java)
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
    }
}
