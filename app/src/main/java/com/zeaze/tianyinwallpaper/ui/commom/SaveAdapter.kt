package com.zeaze.tianyinwallpaper.ui.commom

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.RecyclerView
import com.alibaba.fastjson.JSON
import com.zeaze.tianyinwallpaper.R
import com.zeaze.tianyinwallpaper.utils.FileUtil
import kotlin.concurrent.thread

class SaveAdapter(private val context: Context, dataPath: String, private val onClick: OnClick) :
    RecyclerView.Adapter<SaveAdapter.ViewHolder>() {

    private var saveDataList: MutableList<SaveData> = mutableListOf()

    init {
        thread {
            try {
                val s = FileUtil.loadData(context, dataPath)
                val list = JSON.parseArray(s, SaveData::class.java)
                if (list != null) {
                    saveDataList = list
                    (context as? AppCompatActivity)?.runOnUiThread { notifyDataSetChanged() }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.adapter_save, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val data = saveDataList[position]
        holder.tv.text = data.name
        holder.tv.setOnClickListener { onClick.onClick(data.s, data.name) }
        holder.tvde.setOnClickListener {
            AlertDialog.Builder(context)
                .setMessage("选择操作")
                .setPositiveButton("上移一格") { _, _ -> moveUp(position) }
                .setNegativeButton("下移一格") { _, _ -> moveDown(position) }
                .setNeutralButton("删除") { _, _ -> delete(position) }
                .create()
                .show()
        }
    }

    private fun moveUp(position: Int) {
        if (position <= 0) return
        val item = saveDataList.removeAt(position)
        saveDataList.add(position - 1, item)
        notifyItemMoved(position, position - 1)
        onClick.onChange(position)
    }

    private fun moveDown(position: Int) {
        if (position >= saveDataList.size - 1) return
        val item = saveDataList.removeAt(position)
        saveDataList.add(position + 1, item)
        notifyItemMoved(position, position + 1)
        onClick.onChange(position)
    }

    private fun delete(position: Int) {
        AlertDialog.Builder(context)
            .setMessage("确认删除")
            .setPositiveButton("删除") { _, _ ->
                saveDataList.removeAt(position)
                notifyItemRemoved(position)
                notifyItemRangeChanged(position, saveDataList.size - position)
                onClick.onChange(position)
            }
            .setNegativeButton("取消", null)
            .show()
    }

    override fun getItemCount(): Int = saveDataList.size

    fun getSaveDataList(): MutableList<SaveData> = saveDataList

    class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val tv: TextView = itemView.findViewById(R.id.tv)
        val tvde: TextView = itemView.findViewById(R.id.tv_de)
    }

    interface OnClick {
        fun onClick(s: String?, name: String?)
        fun onChange(i: Int)
    }
}
