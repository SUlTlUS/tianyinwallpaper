package com.zeaze.tianyinwallpaper

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Point
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.compose.setContent
import androidx.appcompat.app.AlertDialog
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.alibaba.fastjson.JSON
import com.google.android.material.tabs.TabLayout
import com.pgyer.pgyersdk.PgyerSDKManager
import com.pgyer.pgyersdk.callback.CheckoutVersionCallBack
import com.pgyer.pgyersdk.model.CheckSoftModel
import com.zeaze.tianyinwallpaper.base.BaseActivity
import com.zeaze.tianyinwallpaper.base.BaseFragment
import com.zeaze.tianyinwallpaper.base.BaseFragmentAdapter
import com.zeaze.tianyinwallpaper.model.TianYinWallpaperModel
import com.zeaze.tianyinwallpaper.ui.about.AboutFragment
import com.zeaze.tianyinwallpaper.ui.commom.SaveData
import com.zeaze.tianyinwallpaper.ui.main.MainFragment
import com.zeaze.tianyinwallpaper.ui.setting.SettingFragment
import com.zeaze.tianyinwallpaper.utils.FileUtil
import com.zeaze.tianyinwallpaper.widget.NoScrollViewPager
import java.io.File

class MainActivity : BaseActivity() {
    private var tabLayout: TabLayout? = null
    private var viewPager: NoScrollViewPager? = null
    private var titles: MutableList<String>? = null
    private var fragments: MutableList<BaseFragment>? = null
    private var tabsInitialized = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val parent = window.decorView.findViewById<ViewGroup>(android.R.id.content)
        setContent {
            AndroidView(factory = { context ->
                LayoutInflater.from(context).inflate(R.layout.activity_main, parent, false).also { rootView ->
                    tabLayout = rootView.findViewById(R.id.tab_layout)
                    viewPager = rootView.findViewById(R.id.view_pager)
                    setupTabs()
                }
            })
        }

        val wm = this.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val point = Point()
        wm.defaultDisplay.getRealSize(point)
        FileUtil.width = point.x
        FileUtil.height = point.y
        permission()
        clearNoUseFile()
    }

    private fun setupTabs() {
        if (tabsInitialized) {
            return
        }
        tabsInitialized = true
        titles = ArrayList()
        fragments = ArrayList()
        titles?.add("壁纸")
        titles?.add("壁纸组")
        titles?.add("设置")
        fragments?.add(MainFragment())
        fragments?.add(AboutFragment())
        fragments?.add(SettingFragment())
        val adapter = BaseFragmentAdapter(supportFragmentManager, titles ?: emptyList(), fragments ?: emptyList())
        viewPager?.adapter = adapter
        viewPager?.offscreenPageLimit = 100
        tabLayout?.setupWithViewPager(viewPager)
    }

    fun openSettingPage() {
        viewPager?.setCurrentItem(SETTINGS_TAB_INDEX, true)
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
        private const val SETTINGS_TAB_INDEX = 2
    }
}
