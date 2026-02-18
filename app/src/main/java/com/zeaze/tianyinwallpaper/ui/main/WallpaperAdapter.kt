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
import android.widget.EditText
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
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
        val view = LayoutInflater.from(parent.context).inflate(R.layout.adapter_wallpaper, parent, false)
        parentWidth = parent.width
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        if (parentWidth > 0) {
            val view = holder.root
            val layoutParams = view.layoutParams
            layoutParams.height = (parentWidth * heightPixels / widthPixels.toFloat() / MainFragment.column).toInt()
            view.layoutParams = layoutParams
            Log.d("TAG", "onBindViewHolder: " + layoutParams.height)
        }
        val model = list[position]
        if (model.type == 0 && !model.imgUri.isNullOrEmpty()) {
            Glide.with(context).load(Uri.parse(model.imgUri)).into(holder.iv)
        } else if (model.type == 1 && !model.videoUri.isNullOrEmpty()) {
            Glide.with(context).load(Uri.parse(model.videoUri)).into(holder.iv)
        } else {
            Glide.with(context).load(model.imgPath).into(holder.iv)
        }
        if (model.type == 0) {
            holder.tr.text = "静态"
        } else {
            holder.tr.text = "动态"
        }
        if (model.startTime == -1 || model.endTime == -1) {
            holder.time.visibility = View.INVISIBLE
        } else {
            holder.time.text = getTimeString(model.startTime) + " - " + getTimeString(model.endTime)
            holder.time.visibility = View.VISIBLE
        }
        val i = position
        holder.selectMask.visibility = if (selectionMode && selectedPositions.contains(i)) View.VISIBLE else View.GONE
        holder.selectMark.visibility = if (selectionMode && selectedPositions.contains(i)) View.VISIBLE else View.GONE
        holder.itemView.setOnClickListener {
            if (onWallpaperClickListener?.onWallpaperClick(i) == true) {
                return@setOnClickListener
            }
            val builder = AlertDialog.Builder(context)
            val view = LayoutInflater.from(context).inflate(R.layout.wallpaper_item, null)
            view.findViewById<View>(R.id.tv1).setOnClickListener {
                setIf(i)
                alertDialog?.dismiss()
            }
            view.findViewById<View>(R.id.tv3).setOnClickListener {
                setLoop(i)
                alertDialog?.dismiss()
            }
            view.findViewById<View>(R.id.tv2).setOnClickListener {
                delete(i)
                alertDialog?.dismiss()
            }
            builder.setView(view)
            alertDialog = builder.create()
            alertDialog?.show()
        }
    }

    private fun setIf(i: Int) {
        val builder = AlertDialog.Builder(context)
        val view = LayoutInflater.from(context).inflate(R.layout.wallpaper_se_time, null)
        val start: TextView = view.findViewById(R.id.start)
        val end: TextView = view.findViewById(R.id.end)
        val reset: TextView = view.findViewById(R.id.reset)
        val set: TextView = view.findViewById(R.id.set)
        val model = list[i]
        if (model.startTime != -1) {
            start.text = "开始时间：" + getTimeString(model.startTime)
        }
        if (model.endTime != -1) {
            end.text = "结束时间：" + getTimeString(model.endTime)
        }
        start.tag = model.startTime
        end.tag = model.endTime
        start.setOnClickListener { selectTime(it as TextView, "开始时间：") }
        end.setOnClickListener { selectTime(it as TextView, "结束时间：") }
        reset.setOnClickListener {
            start.tag = -1
            end.tag = -1
            start.text = "开始时间：点击选择"
            end.text = "结束时间：点击选择"
        }
        set.setOnClickListener {
            model.startTime = start.tag as Int
            model.endTime = end.tag as Int
            if (model.startTime != -1 && model.endTime == -1) {
                model.endTime = 24 * 60
            }
            if (model.endTime != -1 && model.startTime == -1) {
                model.startTime = 0
            }
            tryToNotifyDataSetChanged()
            dialog?.dismiss()
        }
        builder.setView(view)
        builder.setCancelable(false)
        dialog = builder.create()
        dialog?.show()
    }

    private fun setLoop(i: Int) {
        val builder = AlertDialog.Builder(context)
        val view = LayoutInflater.from(context).inflate(R.layout.wallpaper_se_loop, null)
        val loopCheckBox: CheckBox = view.findViewById(R.id.loopCheckBox)
        val set: TextView = view.findViewById(R.id.set)
        val model = list[i]
        loopCheckBox.isChecked = model.loop
        builder.setView(view)
        builder.setCancelable(false)
        val loopDialog = builder.create()
        set.setOnClickListener {
            model.loop = loopCheckBox.isChecked
            tryToNotifyDataSetChanged()
            loopDialog.dismiss()
        }
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

    private fun selectTime(tv: TextView, s: String) {
        val picker = TimePicker(context as Activity)
        val wheelLayout: TimeWheelLayout = picker.wheelLayout
        wheelLayout.setTimeMode(TimeMode.HOUR_24_NO_SECOND)
        wheelLayout.setTimeFormatter(UnitTimeFormatter())
        if (tv.tag as Int != -1) {
            wheelLayout.setDefaultValue(TimeEntity.target((tv.tag as Int) / 60, (tv.tag as Int) % 60, 0))
        } else {
            wheelLayout.setDefaultValue(TimeEntity.target(0, 0, 0))
        }
        wheelLayout.setResetWhenLinkage(false)
        picker.setOnTimePickedListener { hour, minute, _ ->
            tv.text = s + getTimeString(hour * 60 + minute)
            tv.tag = hour * 60 + minute
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

    inner class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val tr: TextView = itemView.findViewById(R.id.tr)
        val time: TextView = itemView.findViewById(R.id.time)
        val iv: ImageView = itemView.findViewById(R.id.iv)
        val root: View = itemView.findViewById(R.id.root)
        val selectMask: View = itemView.findViewById(R.id.select_mask)
        val selectMark: TextView = itemView.findViewById(R.id.select_mark)
    }

    interface OnWallpaperClickListener {
        fun onWallpaperClick(position: Int): Boolean
    }
}
