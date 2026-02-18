package com.zeaze.tianyinwallpaper.ui.main

import android.app.Activity.RESULT_OK
import android.app.WallpaperManager
import android.content.ComponentName
import android.content.DialogInterface
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.RenderEffect
import android.graphics.Shader
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.TextUtils
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.widget.CheckBox
import android.widget.CompoundButton
import android.widget.EditText
import android.widget.ImageView
import android.widget.PopupMenu
import android.widget.TextView
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.alibaba.fastjson.JSON
import com.lxj.xpopup.XPopup
import com.lxj.xpopup.impl.LoadingPopupView
import com.zeaze.tianyinwallpaper.App
import com.zeaze.tianyinwallpaper.MainActivity
import com.zeaze.tianyinwallpaper.R
import com.zeaze.tianyinwallpaper.base.BaseFragment
import com.zeaze.tianyinwallpaper.base.rxbus.RxBus
import com.zeaze.tianyinwallpaper.base.rxbus.RxConstants
import com.zeaze.tianyinwallpaper.model.TianYinWallpaperModel
import com.zeaze.tianyinwallpaper.service.TianYinWallpaperService
import com.zeaze.tianyinwallpaper.utils.FileUtil
import io.reactivex.functions.Consumer
import java.io.IOException
import java.util.Collections
import java.util.UUID

class MainFragment : BaseFragment() {
    private var rv: RecyclerView? = null
    private var manager: GridLayoutManager? = null
    private var wallpaperAdapter: WallpaperAdapter? = null
    private var tv: EditText? = null
    private var select: TextView? = null
    private var apply: TextView? = null
    private var more: TextView? = null
    private var cancelSelect: TextView? = null
    private var deleteSelected: TextView? = null
    private var topScrim: ImageView? = null
    private var topScrimBitmap: Bitmap? = null
    private var topScrimUpdatePending = false
    private var selectionMode = false
    private val list: MutableList<TianYinWallpaperModel> = ArrayList()
    private var model: TianYinWallpaperModel? = null
    private var popupView: LoadingPopupView? = null

    private var now = 0
    private var uris: List<Uri>? = null
    private var type = 1

    private var pref: SharedPreferences? = null
    private var editor: SharedPreferences.Editor? = null

    private val topScrimScrollListener: RecyclerView.OnScrollListener = object : RecyclerView.OnScrollListener() {
        override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
            if (kotlin.math.abs(dy) < TOP_SCRIM_SCROLL_THRESHOLD) return
            requestTopScrimBlurUpdate()
        }
    }

    override fun init() {
        addDisposable(
            RxBus.getDefault().toObservableWithCode(RxConstants.RX_ADD_WALLPAPER, TianYinWallpaperModel::class.java)
                .subscribe(Consumer { o: TianYinWallpaperModel ->
                    list.add(0, o)
                    activity?.runOnUiThread {
                        wallpaperAdapter?.tryToNotifyDataSetChanged()
                        toast("已加入，请在“壁纸“里查看")
                    }
                })
        )

        rv = rootView.findViewById(R.id.rv)
        select = rootView.findViewById(R.id.select)
        apply = rootView.findViewById(R.id.apply)
        more = rootView.findViewById(R.id.more)
        cancelSelect = rootView.findViewById(R.id.cancel_select)
        deleteSelected = rootView.findViewById(R.id.delete_selected)
        topScrim = rootView.findViewById(R.id.top_scrim)
        tv = rootView.findViewById(R.id.tv)
        if (topScrim != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            topScrim?.setRenderEffect(RenderEffect.createBlurEffect(TOP_SCRIM_BLUR_RADIUS, TOP_SCRIM_BLUR_RADIUS, Shader.TileMode.CLAMP))
        }
        val topBar: View? = rootView.findViewById(R.id.fl)
        if (topBar != null) {
            ViewCompat.setOnApplyWindowInsetsListener(topBar) { v, insets ->
                v.translationY = insets.getInsets(WindowInsetsCompat.Type.statusBars()).top.toFloat()
                insets
            }
        }

        manager = GridLayoutManager(context, column)
        rv?.layoutManager = manager
        wallpaperAdapter = WallpaperAdapter(requireContext(), list, tv)
        rv?.adapter = wallpaperAdapter
        wallpaperAdapter?.setOnWallpaperClickListener(object : WallpaperAdapter.OnWallpaperClickListener {
            override fun onWallpaperClick(position: Int): Boolean {
                if (!selectionMode) {
                    return false
                }
                wallpaperAdapter?.toggleSelected(position)
                return true
            }
        })
        wallpaperAdapter?.tryToNotifyDataSetChanged()
        helper.attachToRecyclerView(rv)
        rv?.addOnScrollListener(topScrimScrollListener)
        requestTopScrimBlurUpdate()

        pref = requireContext().getSharedPreferences(App.TIANYIN, android.content.Context.MODE_PRIVATE)
        editor = requireContext().getSharedPreferences(App.TIANYIN, android.content.Context.MODE_PRIVATE).edit()

        select?.setOnClickListener {
            Log.d("TAG", "onClick: ")
            if (model != null) {
                return@setOnClickListener
            }
            AlertDialog.Builder(requireContext())
                .setTitle("请选择壁纸类型，可长按选中的壁纸来多选")
                .setNeutralButton("取消") { _, _ -> model = null }
                .setNegativeButton("静态") { _, _ -> selectWallpaper() }
                .setPositiveButton("动态") { _, _ -> selectLiveWallpaper() }
                .setCancelable(false)
                .show()
        }
        apply?.setOnClickListener {
            if (list.isEmpty()) {
                toast("至少需要1张壁纸才能开始设置")
                return@setOnClickListener
            }
            Thread {
                FileUtil.save(requireContext(), JSON.toJSONString(list), FileUtil.wallpaperPath, object : FileUtil.OnSave {
                    override fun onSave() {
                        val hostActivity = activity ?: run {
                            Log.w("MainFragment", "onSave skipped: activity is null")
                            return
                        }
                        hostActivity.runOnUiThread {
                            val wallpaperManager = WallpaperManager.getInstance(hostActivity)
                            try {
                                wallpaperManager.clear()
                            } catch (e: IOException) {
                                e.printStackTrace()
                            }
                            val intent = Intent()
                            intent.action = WallpaperManager.ACTION_CHANGE_LIVE_WALLPAPER
                            intent.putExtra(
                                WallpaperManager.EXTRA_LIVE_WALLPAPER_COMPONENT,
                                ComponentName(hostActivity, TianYinWallpaperService::class.java)
                            )
                            wallpaperLaunch.launch(intent)
                        }
                    }
                })
            }.start()
        }
        more?.setOnClickListener { v -> showMoreMenu(v) }
        cancelSelect?.setOnClickListener { exitSelectionMode() }
        deleteSelected?.setOnClickListener { deleteSelectedWallpapers() }
    }

    private fun updateTopScrimBlur() {
        if (topScrim == null || rv == null) return
        if (rv!!.width <= 0 || topScrim!!.height <= 0) return
        if (topScrimBitmap == null || topScrimBitmap!!.width != rv!!.width || topScrimBitmap!!.height != topScrim!!.height) {
            if (topScrimBitmap != null && !topScrimBitmap!!.isRecycled) {
                topScrimBitmap!!.recycle()
            }
            topScrimBitmap = Bitmap.createBitmap(rv!!.width, topScrim!!.height, Bitmap.Config.RGB_565)
        }
        topScrimBitmap!!.eraseColor(Color.TRANSPARENT)
        val canvas = Canvas(topScrimBitmap!!)
        rv!!.draw(canvas)
        topScrim!!.setImageBitmap(topScrimBitmap)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            topScrim!!.setRenderEffect(RenderEffect.createBlurEffect(TOP_SCRIM_BLUR_RADIUS, TOP_SCRIM_BLUR_RADIUS, Shader.TileMode.CLAMP))
        }
    }

    private fun requestTopScrimBlurUpdate() {
        if (rv == null || topScrimUpdatePending) return
        topScrimUpdatePending = true
        rv!!.post {
            topScrimUpdatePending = false
            updateTopScrimBlur()
        }
    }

    override fun getLayout(): Int {
        return R.layout.main_fragment
    }

    private lateinit var imageLaunch: ActivityResultLauncher<Array<String>>
    private lateinit var videoLaunch: ActivityResultLauncher<Array<String>>
    private lateinit var wallpaperLaunch: ActivityResultLauncher<Intent>

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        imageLaunch = registerForActivityResult(
            ActivityResultContracts.OpenMultipleDocuments()
        ) { results ->
            if (results.isNullOrEmpty()) {
                model = null
                return@registerForActivityResult
            }
            takePersistableUriPermissions(results)
            now = 0
            uris = results
            type = 1
            popupView = XPopup.Builder(context)
                .dismissOnBackPressed(false)
                .dismissOnTouchOutside(false)
                .asLoading("转换壁纸中")
                .show() as LoadingPopupView
            exchange(now)
        }
        videoLaunch = registerForActivityResult(
            ActivityResultContracts.OpenMultipleDocuments()
        ) { results ->
            if (results.isNullOrEmpty()) {
                model = null
                return@registerForActivityResult
            }
            takePersistableUriPermissions(results)
            now = 0
            uris = results
            type = 2
            popupView = XPopup.Builder(context)
                .dismissOnBackPressed(false)
                .dismissOnTouchOutside(false)
                .asLoading("转换壁纸中")
                .show() as LoadingPopupView
            exchange(now)
        }
        wallpaperLaunch = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == RESULT_OK) {
                toast("设置成功")
            } else {
                AlertDialog.Builder(requireContext()).setMessage("设置失败，可能是没有设置动态壁纸权限，是否去设置")
                    .setPositiveButton("确定") { _, _ ->
                        val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                        val uri = Uri.fromParts("package", requireActivity().packageName, null)
                        intent.data = uri
                        startActivity(intent)
                    }
                    .setNeutralButton("取消", null)
                    .create()
                    .show()
            }
        }
    }

    private fun takePersistableUriPermissions(uris: List<Uri>) {
        for (uri in uris) {
            try {
                requireActivity().contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            } catch (e: SecurityException) {
                Log.e("MainFragment", "Could not take persistable permission for URI: $uri", e)
            }
        }
    }

    private fun exchange(index: Int) {
        if (uris == null || uris!!.size <= index) {
            return
        }
        Thread {
            model = TianYinWallpaperModel()
            val currentUri = uris!![index]
            if (type == 1) {
                model!!.type = 0
                model!!.uuid = UUID.randomUUID().toString()
                model!!.imgUri = currentUri.toString()
                addModel()
            } else {
                model!!.type = 1
                model!!.uuid = UUID.randomUUID().toString()
                model!!.videoUri = currentUri.toString()
                addModel()
            }
        }.start()
    }

    fun selectWallpaper() {
        imageLaunch.launch(arrayOf("image/*"))
    }

    fun selectLiveWallpaper() {
        videoLaunch.launch(arrayOf("video/*"))
    }

    private fun showMoreMenu(anchor: View) {
        val popupMenu = PopupMenu(context, anchor)
        popupMenu.menu.add(getString(R.string.menu_select_mode))
        popupMenu.menu.add(getString(R.string.menu_setting))
        popupMenu.setOnMenuItemClickListener { item ->
            if (getString(R.string.menu_select_mode).contentEquals(item.title)) {
                enterSelectionMode()
                return@setOnMenuItemClickListener true
            }
            if (getString(R.string.menu_setting).contentEquals(item.title)) {
                if (activity is MainActivity) {
                    (activity as MainActivity).openSettingPage()
                }
                return@setOnMenuItemClickListener true
            }
            false
        }
        popupMenu.show()
    }

    private fun enterSelectionMode() {
        selectionMode = true
        wallpaperAdapter?.setSelectionMode(true)
        select?.visibility = View.GONE
        apply?.visibility = View.GONE
        tv?.visibility = View.GONE
        more?.visibility = View.GONE
        cancelSelect?.visibility = View.VISIBLE
        deleteSelected?.visibility = View.VISIBLE
        val bottomBar = activity?.findViewById<View>(R.id.linearLayout)
        bottomBar?.visibility = View.GONE
        topScrim?.visibility = View.GONE
        toast(getString(R.string.select_mode_tip))
    }

    private fun exitSelectionMode() {
        selectionMode = false
        wallpaperAdapter?.setSelectionMode(false)
        select?.visibility = View.VISIBLE
        apply?.visibility = View.VISIBLE
        tv?.visibility = View.VISIBLE
        more?.visibility = View.VISIBLE
        cancelSelect?.visibility = View.GONE
        deleteSelected?.visibility = View.GONE
        val bottomBar = activity?.findViewById<View>(R.id.linearLayout)
        bottomBar?.visibility = View.VISIBLE
        topScrim?.visibility = View.VISIBLE
    }

    private fun deleteSelectedWallpapers() {
        val selected = wallpaperAdapter?.getSelectedPositions() ?: emptySet()
        if (selected.isEmpty()) {
            toast(getString(R.string.no_selected_tip))
            return
        }
        AlertDialog.Builder(requireContext())
            .setMessage(getString(R.string.delete_selected_confirm))
            .setNegativeButton(getString(R.string.common_cancel), null)
            .setPositiveButton(getString(R.string.common_delete)) { _, _ ->
                val indexes: MutableList<Int> = ArrayList(selected)
                Collections.sort(indexes, Collections.reverseOrder())
                for (index in indexes) {
                    if (index >= 0 && index < list.size) {
                        list.removeAt(index)
                    }
                }
                wallpaperAdapter?.tryToNotifyDataSetChanged()
                exitSelectionMode()
            }
            .show()
    }

    private val helper: ItemTouchHelper = ItemTouchHelper(object : ItemTouchHelper.Callback() {
        override fun getMovementFlags(recyclerView: RecyclerView, viewHolder: RecyclerView.ViewHolder): Int {
            var dragFlag = 0
            if (recyclerView.layoutManager is GridLayoutManager) {
                dragFlag = ItemTouchHelper.UP or ItemTouchHelper.DOWN or ItemTouchHelper.LEFT or ItemTouchHelper.RIGHT
            } else if (recyclerView.layoutManager is LinearLayoutManager) {
                dragFlag = ItemTouchHelper.UP or ItemTouchHelper.DOWN
            }
            return makeMovementFlags(dragFlag, 0)
        }

        override fun onMove(recyclerView: RecyclerView, viewHolder: RecyclerView.ViewHolder, target: RecyclerView.ViewHolder): Boolean {
            val fromPosition = viewHolder.adapterPosition
            val toPosition = target.adapterPosition
            if (fromPosition < toPosition) {
                for (i in fromPosition until toPosition) {
                    Collections.swap(list, i, i + 1)
                }
            } else {
                for (i in fromPosition downTo toPosition + 1) {
                    Collections.swap(list, i, i - 1)
                }
            }
            wallpaperAdapter?.notifyItemMoved(fromPosition, toPosition)
            return true
        }

        override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) {
        }

        override fun isLongPressDragEnabled(): Boolean {
            return !selectionMode
        }

        override fun onSelectedChanged(viewHolder: RecyclerView.ViewHolder?, actionState: Int) {
            if (actionState != ItemTouchHelper.ACTION_STATE_IDLE) {
                toast("可以开始拖动了")
            }
            super.onSelectedChanged(viewHolder, actionState)
        }

        override fun clearView(recyclerView: RecyclerView, viewHolder: RecyclerView.ViewHolder) {
            super.clearView(recyclerView, viewHolder)
            wallpaperAdapter?.tryToNotifyDataSetChanged()
        }
    })

    private fun addModel() {
        activity?.runOnUiThread {
            list.add(0, model!!)
            wallpaperAdapter?.tryToNotifyDataSetChanged()
            model = null
            now += 1
            if (now >= (uris?.size ?: 0)) {
                uris = null
                popupView?.dismiss()
            } else {
                exchange(now)
                popupView?.setTitle("转化壁纸中,进度$now/${uris?.size}")
            }
        }
    }

    private var alertDialog: AlertDialog? = null

    private fun wallpaperSetting() {
        val builder = AlertDialog.Builder(requireContext())
        val settingView = LayoutInflater.from(context).inflate(R.layout.main_wallpaper_setting, null)
        val checkBox: CheckBox = settingView.findViewById(R.id.checkBox)
        checkBox.isChecked = pref?.getBoolean("rand", false) == true
        checkBox.setOnCheckedChangeListener { _: CompoundButton?, isChecked: Boolean ->
            editor?.putBoolean("rand", isChecked)
            editor?.apply()
        }

        val checkBox2: CheckBox = settingView.findViewById(R.id.checkBox2)
        checkBox2.isChecked = pref?.getBoolean("pageChange", false) == true
        checkBox2.setOnCheckedChangeListener { _: CompoundButton?, isChecked: Boolean ->
            editor?.putBoolean("pageChange", isChecked)
            editor?.apply()
        }

        val checkBox3: CheckBox = settingView.findViewById(R.id.checkBox3)
        checkBox3.isChecked = pref?.getBoolean("needBackgroundPlay", false) == true
        checkBox3.setOnCheckedChangeListener { _: CompoundButton?, isChecked: Boolean ->
            editor?.putBoolean("needBackgroundPlay", isChecked)
            editor?.apply()
        }

        val checkBox4: CheckBox = settingView.findViewById(R.id.checkBox4)
        checkBox4.isChecked = pref?.getBoolean("wallpaperScroll", false) == true
        checkBox4.setOnCheckedChangeListener { _: CompoundButton?, isChecked: Boolean ->
            editor?.putBoolean("wallpaperScroll", isChecked)
            editor?.apply()
        }

        val minTv: TextView = settingView.findViewById(R.id.tv)
        minTv.text = "壁纸最小切换时间:" + pref?.getInt("minTime", 1) + "秒（点击修改）"
        minTv.setOnClickListener {
            setMinTime(DialogInterface.OnDismissListener {
                minTv.text = "壁纸最小切换时间:" + pref?.getInt("minTime", 1) + "秒"
            })
        }

        val autoSwitchModeView: TextView = settingView.findViewById(R.id.autoSwitchModeView)
        val autoSwitchIntervalView: TextView = settingView.findViewById(R.id.autoSwitchIntervalView)
        val autoSwitchPointsView: TextView = settingView.findViewById(R.id.autoSwitchPointsView)
        refreshAutoSwitchSettingView(autoSwitchModeView, autoSwitchIntervalView, autoSwitchPointsView)
        autoSwitchModeView.setOnClickListener {
            showAutoSwitchModeDialog(Runnable {
                refreshAutoSwitchSettingView(autoSwitchModeView, autoSwitchIntervalView, autoSwitchPointsView)
            })
        }
        builder.setView(settingView)
        alertDialog = builder.create()
        alertDialog?.show()
    }

    private fun setMinTime(onDismissListener: DialogInterface.OnDismissListener) {
        val editView = LayoutInflater.from(context).inflate(R.layout.main_edit, null)
        val builder = AlertDialog.Builder(requireContext())
        val et: EditText = editView.findViewById(R.id.tv)
        et.setText((pref?.getInt("minTime", 1) ?: 1).toString())
        et.hint = "请输入整数"
        builder.setView(editView)
            .setNeutralButton("取消", null)
            .setPositiveButton("确定") { _, _ ->
                try {
                    val i = et.text.toString().toInt()
                    editor?.putInt("minTime", i)
                    editor?.apply()
                } catch (e: Exception) {
                    toast("请输入整数")
                }
            }
            .setCancelable(false)
            .setOnDismissListener(onDismissListener)
            .show()
    }

    private fun refreshAutoSwitchSettingView(modeView: TextView, intervalView: TextView, pointsView: TextView) {
        val mode = pref?.getInt(TianYinWallpaperService.PREF_AUTO_SWITCH_MODE, AUTO_SWITCH_MODE_NONE) ?: AUTO_SWITCH_MODE_NONE
        val modeText = if (mode >= AUTO_SWITCH_MODE_NONE && mode < AUTO_SWITCH_MODE_ITEMS.size) {
            AUTO_SWITCH_MODE_ITEMS[mode]
        } else {
            AUTO_SWITCH_MODE_ITEMS[AUTO_SWITCH_MODE_NONE]
        }
        modeView.text = "自动切换模式：$modeText（点击修改）"
        intervalView.text = "自动切换间隔：" + (pref?.getLong(
            TianYinWallpaperService.PREF_AUTO_SWITCH_INTERVAL_MINUTES,
            DEFAULT_AUTO_SWITCH_INTERVAL_MINUTES
        ) ?: DEFAULT_AUTO_SWITCH_INTERVAL_MINUTES) + "分钟（点击修改）"
        var points = pref?.getString(TianYinWallpaperService.PREF_AUTO_SWITCH_TIME_POINTS, DEFAULT_AUTO_SWITCH_TIME_POINTS)
        points = if (TextUtils.isEmpty(points)) DEFAULT_AUTO_SWITCH_TIME_POINTS else points
        pointsView.text = "自动切换时间点：$points（点击修改）"
    }

    private fun showAutoSwitchModeDialog(onDismiss: Runnable) {
        val checked = pref?.getInt(TianYinWallpaperService.PREF_AUTO_SWITCH_MODE, AUTO_SWITCH_MODE_NONE) ?: AUTO_SWITCH_MODE_NONE
        AlertDialog.Builder(requireContext())
            .setTitle("选择自动切换模式")
            .setSingleChoiceItems(AUTO_SWITCH_MODE_ITEMS, checked) { dialog, which ->
                editor?.putInt(TianYinWallpaperService.PREF_AUTO_SWITCH_MODE, which)
                editor?.putLong(TianYinWallpaperService.PREF_AUTO_SWITCH_ANCHOR_AT, System.currentTimeMillis())
                editor?.putLong(TianYinWallpaperService.PREF_AUTO_SWITCH_LAST_SWITCH_AT, 0L)
                editor?.apply()
                dialog.dismiss()
            }
            .setOnDismissListener { onDismiss.run() }
            .show()
    }

    override fun onDestroyView() {
        rv?.removeOnScrollListener(topScrimScrollListener)
        if (topScrimBitmap != null && !topScrimBitmap!!.isRecycled) {
            topScrimBitmap!!.recycle()
            topScrimBitmap = null
        }
        super.onDestroyView()
    }

    companion object {
        private const val DEFAULT_AUTO_SWITCH_INTERVAL_MINUTES = 60L
        private const val DEFAULT_AUTO_SWITCH_TIME_POINTS = "08:00,12:00,18:00,22:00"
        private const val AUTO_SWITCH_MODE_NONE = 0
        private val AUTO_SWITCH_MODE_ITEMS = arrayOf("手动切换", "按固定时间间隔切换", "按每日时间点切换")
        private const val TOP_SCRIM_BLUR_RADIUS = 24f
        private const val TOP_SCRIM_SCROLL_THRESHOLD = 2
        var column = 3
    }
}
