package com.zeaze.tianyinwallpaper.ui.main

import android.app.Activity
import android.content.Context
import android.content.DialogInterface
import android.content.SharedPreferences
import android.net.Uri
import android.util.DisplayMetrics
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.FrameLayout
import android.widget.EditText
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.Button
import androidx.compose.material.Checkbox
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.recyclerview.widget.RecyclerView
import com.alibaba.fastjson.JSON
import com.bumptech.glide.Glide
import com.github.gzuliyujiang.wheelpicker.TimePicker
import com.github.gzuliyujiang.wheelpicker.annotation.TimeMode
import com.github.gzuliyujiang.wheelpicker.entity.TimeEntity
import com.github.gzuliyujiang.wheelpicker.impl.UnitTimeFormatter
import com.github.gzuliyujiang.wheelpicker.widget.TimeWheelLayout
import com.zeaze.tianyinwallpaper.R
import com.zeaze.tianyinwallpaper.model.TianYinWallpaperModel

class WallpaperAdapter(context: Context, private val list: MutableList<TianYinWallpaperModel>, private val tv: EditText?) :
    RecyclerView.Adapter<WallpaperAdapter.ViewHolder>() {
    private var parentWidth = 0
    private val pref: SharedPreferences = context.getSharedPreferences("tianyin", Context.MODE_PRIVATE)
    private val edit: SharedPreferences.Editor = pref.edit()
    private val widthPixels: Int
    private val heightPixels: Int
    private var alertDialog: AlertDialog? = null
    private var dialog: AlertDialog? = null
    private var selectionMode = false
    private val selectedPositions = HashSet<Int>()
    private var onWallpaperClickListener: OnWallpaperClickListener? = null
    private val context: Context = context

    init {
        val displayMetrics: DisplayMetrics = context.resources.displayMetrics
        widthPixels = displayMetrics.widthPixels
        heightPixels = displayMetrics.heightPixels
        list.clear()
        val cache = pref.getString("wallpaperCache", "")
        if (!cache.isNullOrEmpty()) {
            list.addAll(JSON.parseArray(cache, TianYinWallpaperModel::class.java))
            tv?.setText(pref.getString("wallpaperTvCache", ""))
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        parentWidth = parent.width
        val composeView = ComposeView(parent.context).apply {
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }
        return ViewHolder(composeView)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val itemHeight = if (parentWidth > 0) {
            (parentWidth * heightPixels / widthPixels.toFloat() / MainFragment.column).toInt()
        } else {
            0
        }
        if (parentWidth > 0) {
            val view = holder.composeView
            val layoutParams = view.layoutParams
            layoutParams.height = itemHeight
            view.layoutParams = layoutParams
            Log.d("TAG", "onBindViewHolder: " + layoutParams.height)
        }
        val model = list[position]
        val i = position
        holder.composeView.setContent {
            MaterialTheme {
                WallpaperItem(model, selectionMode && selectedPositions.contains(i), itemHeight) {
                    if (onWallpaperClickListener?.onWallpaperClick(i) == true) {
                        return@WallpaperItem
                    }
                    showActionDialog(i)
                }
            }
        }
    }

    private fun showActionDialog(position: Int) {
        val composeView = ComposeView(context).apply {
            setContent {
                MaterialTheme {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Text("请选择操作", modifier = Modifier.padding(horizontal = 16.dp))
                        Button(onClick = {
                            setTimeCondition(position)
                            alertDialog?.dismiss()
                        }, modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp)) {
                            Text("设置时间条件")
                        }
                        Button(onClick = {
                            setLoop(position)
                            alertDialog?.dismiss()
                        }, modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp)) {
                            Text("设置循环播放")
                        }
                        Button(onClick = {
                            delete(position)
                            alertDialog?.dismiss()
                        }, modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp)) {
                            Text("删除")
                        }
                    }
                }
            }
        }
        alertDialog = AlertDialog.Builder(context).setView(composeView).create()
        alertDialog?.show()
    }

    @Composable
    /**
     * Compose item for a wallpaper entry in the main business list.
     *
     * @param model wallpaper model shown in the item
     * @param selected whether current item is selected in selection mode
     * @param itemHeight item height in pixels calculated from screen ratio
     * @param onClick click callback for opening operation dialog
     */
    private fun WallpaperItem(model: TianYinWallpaperModel, selected: Boolean, itemHeight: Int, onClick: () -> Unit) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { onClick() }
                .padding(3.dp)
        ) {
            androidx.compose.ui.viewinterop.AndroidView(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color.Black),
                factory = { ctx ->
                    ImageView(ctx).apply {
                        scaleType = ImageView.ScaleType.CENTER_CROP
                    }
                },
                update = { iv ->
                    val lp = iv.layoutParams
                    if (lp != null && itemHeight > 0) {
                        lp.height = itemHeight
                        iv.layoutParams = lp
                    } else if (itemHeight > 0) {
                        iv.layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, itemHeight)
                    }
                    if (model.type == 0 && !model.imgUri.isNullOrEmpty()) {
                        Glide.with(context).load(Uri.parse(model.imgUri)).into(iv)
                    } else if (model.type == 1 && !model.videoUri.isNullOrEmpty()) {
                        Glide.with(context).load(Uri.parse(model.videoUri)).into(iv)
                    } else {
                        Glide.with(context).load(model.imgPath).into(iv)
                    }
                }
            )
            Text(
                text = if (model.type == 0) "静态" else "动态",
                color = Color.White,
                fontSize = 12.sp,
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .background(Color(0x66000000))
                    .padding(horizontal = 6.dp, vertical = 2.dp)
            )
            if (model.startTime != -1 && model.endTime != -1) {
                Text(
                    text = getTimeString(model.startTime) + " - " + getTimeString(model.endTime),
                    color = Color.White,
                    fontSize = 11.sp,
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .background(Color(0x66000000))
                        .padding(horizontal = 6.dp, vertical = 2.dp)
                )
            }
            if (selected) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color(0x88000000))
                )
                Text(
                    text = "✓",
                    color = Color.White,
                    fontSize = 18.sp,
                    modifier = Modifier
                        .align(Alignment.Center)
                )
            }
        }
    }

    private fun setTimeCondition(position: Int) {
        val model = list[position]
        val composeView = ComposeView(context).apply {
            setContent {
                MaterialTheme {
                    var startTime by remember { mutableStateOf(model.startTime) }
                    var endTime by remember { mutableStateOf(model.endTime) }
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Text(
                            text = if (startTime == -1) "开始时间：点击选择" else "开始时间：" + getTimeString(startTime),
                            modifier = Modifier.clickable {
                                selectTime(startTime) { picked -> startTime = picked }
                            }
                        )
                        Text(
                            text = if (endTime == -1) "结束时间：点击选择" else "结束时间：" + getTimeString(endTime),
                            modifier = Modifier.clickable {
                                selectTime(endTime) { picked -> endTime = picked }
                            }
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(onClick = {
                                startTime = -1
                                endTime = -1
                            }) {
                                Text("重置")
                            }
                            Button(onClick = {
                                model.startTime = startTime
                                model.endTime = endTime
                                if (model.startTime != -1 && model.endTime == -1) {
                                    model.endTime = 24 * 60
                                }
                                if (model.endTime != -1 && model.startTime == -1) {
                                    model.startTime = 0
                                }
                                tryToNotifyDataSetChanged()
                                dialog?.dismiss()
                            }) {
                                Text("确定")
                            }
                        }
                    }
                }
            }
        }
        dialog = AlertDialog.Builder(context).setView(composeView).setCancelable(false).create()
        dialog?.show()
    }

    private fun setLoop(position: Int) {
        val model = list[position]
        lateinit var loopDialog: AlertDialog
        val composeView = ComposeView(context).apply {
            setContent {
                MaterialTheme {
                    var loop by remember { mutableStateOf(model.loop) }
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(checked = loop, onCheckedChange = { loop = it })
                            Text(text = "循环播放")
                        }
                        Button(onClick = {
                            model.loop = loop
                            tryToNotifyDataSetChanged()
                            loopDialog.dismiss()
                        }) {
                            Text("确定")
                        }
                    }
                }
            }
        }
        loopDialog = AlertDialog.Builder(context).setView(composeView).setCancelable(false).create()
        loopDialog.show()
    }

    private fun getTimeString(t: Int): String {
        var time = t
        var s = ""
        s = if (time / 60 == 0) s + "00" else if (time / 60 < 10) s + "0" + time / 60 else s + time / 60
        time %= 60
        s = if (time < 10) s + ":0" + time else s + ":" + time
        return s
    }

    private fun selectTime(time: Int, onPicked: (Int) -> Unit) {
        val picker = TimePicker(context as Activity)
        val wheelLayout: TimeWheelLayout = picker.wheelLayout
        wheelLayout.setTimeMode(TimeMode.HOUR_24_NO_SECOND)
        wheelLayout.setTimeFormatter(UnitTimeFormatter())
        if (time != -1) {
            wheelLayout.setDefaultValue(TimeEntity.target(time / 60, time % 60, 0))
        } else {
            wheelLayout.setDefaultValue(TimeEntity.target(0, 0, 0))
        }
        wheelLayout.setResetWhenLinkage(false)
        picker.setOnTimePickedListener { hour, minute, _ ->
            onPicked(hour * 60 + minute)
        }
        picker.show()
    }

    private fun delete(i: Int) {
        AlertDialog.Builder(context)
            .setTitle("是否删除选中壁纸")
            .setNeutralButton("取消", null)
            .setPositiveButton("确定") { _: DialogInterface?, _: Int ->
                list.removeAt(i)
                tryToNotifyDataSetChanged()
            }
            .setCancelable(false)
            .show()
    }

    override fun getItemCount(): Int {
        return list.size
    }

    fun tryToNotifyDataSetChanged() {
        edit.putString("wallpaperCache", JSON.toJSONString(list))
        if (tv != null) {
            edit.putString("wallpaperTvCache", tv.text.toString())
        }
        edit.apply()
        notifyDataSetChanged()
    }

    fun setSelectionMode(selectionMode: Boolean) {
        this.selectionMode = selectionMode
        if (!selectionMode) {
            selectedPositions.clear()
        }
        notifyDataSetChanged()
    }

    fun isSelectionMode(): Boolean {
        return selectionMode
    }

    fun toggleSelected(position: Int) {
        if (selectedPositions.contains(position)) {
            selectedPositions.remove(position)
        } else {
            selectedPositions.add(position)
        }
        notifyItemChanged(position)
    }

    fun getSelectedPositions(): Set<Int> {
        return HashSet(selectedPositions)
    }

    fun clearSelected() {
        selectedPositions.clear()
        notifyDataSetChanged()
    }

    fun setOnWallpaperClickListener(onWallpaperClickListener: OnWallpaperClickListener?) {
        this.onWallpaperClickListener = onWallpaperClickListener
    }

    inner class ViewHolder(val composeView: ComposeView) : RecyclerView.ViewHolder(composeView) {
    }

    interface OnWallpaperClickListener {
        fun onWallpaperClick(position: Int): Boolean
    }
}
