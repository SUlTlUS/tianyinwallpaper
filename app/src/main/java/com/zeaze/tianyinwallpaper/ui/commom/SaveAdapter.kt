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

class SaveAdapter(private val context: Context, dataPath: String, private val onClick: OnClick) :
    RecyclerView.Adapter<SaveAdapter.ViewHolder>() {

    init {
        Thread {
            val s = FileUtil.loadData(context, dataPath)
            saveDataList = JSON.parseArray(s, SaveData::class.java)
            (context as AppCompatActivity).runOnUiThread { notifyDataSetChanged() }
        }.start()
    }

    override fun onCreateViewHolder(viewGroup: ViewGroup, i: Int): ViewHolder {
        val view = LayoutInflater.from(viewGroup.context).inflate(R.layout.adapter_save, viewGroup, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(viewHolder: ViewHolder, ii: Int) {
        val i = ii
        viewHolder.tv.text = saveDataList[i].name
        viewHolder.tv.setOnClickListener { onClick.onClick(saveDataList[i].s, saveDataList[i].name) }
        viewHolder.tvde.setOnClickListener {
            AlertDialog.Builder(context)
                .setMessage("选择操作")
                .setPositiveButton("上移一格") { _, _ -> up(i) }
                .setNegativeButton("下移一格") { _, _ -> down(i) }
                .setNeutralButton("删除") { _, _ -> delete(i) }
                .create()
                .show()
        }
    }

    private fun up(i: Int) {
        if (i == 0) return
        val saveData = saveDataList[i - 1]
        saveDataList[i - 1] = saveDataList[i]
        saveDataList[i] = saveData
        notifyDataSetChanged()
        onClick.onChange(i)
    }

    private fun down(i: Int) {
        if (i == saveDataList.size - 1) return
        val saveData = saveDataList[i + 1]
        saveDataList[i + 1] = saveDataList[i]
        saveDataList[i] = saveData
        notifyDataSetChanged()
        onClick.onChange(i)
    }

    private fun delete(i: Int) {
        AlertDialog.Builder(context)
            .setMessage("确认删除")
            .setPositiveButton("删除") { _, _ ->
                saveDataList.removeAt(i)
                notifyDataSetChanged()
                onClick.onChange(i)
            }
            .setNegativeButton("取消", null)
            .create()
            .show()
    }

    override fun getItemCount(): Int {
        return saveDataList.size
    }

    class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val tv: TextView = itemView.findViewById(R.id.tv)
        val tvde: TextView = itemView.findViewById(R.id.tv_de)
    }

    interface OnClick {
        fun onClick(s: String?, name: String?)
        fun onChange(i: Int)
    }

    companion object {
        private var saveDataList: MutableList<SaveData> = ArrayList()

        fun getSaveDataList(): MutableList<SaveData> {
            if (saveDataList.isEmpty()) {
                saveDataList = ArrayList()
            }
            return saveDataList
        }
    }
}
