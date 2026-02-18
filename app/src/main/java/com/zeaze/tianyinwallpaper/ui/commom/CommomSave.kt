package com.zeaze.tianyinwallpaper.ui.commom

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.alibaba.fastjson.JSON
import com.zeaze.tianyinwallpaper.R
import com.zeaze.tianyinwallpaper.utils.FileUtil

class CommomSave {
    var view: View? = null
    var alertDialog: AlertDialog? = null
    var adapter: SaveAdapter? = null

    fun saveDialog(context: Context, path: String, btnStrings: Array<String>, listener: OnClickListener) {
        view = LayoutInflater.from(context).inflate(R.layout.save_list, null)
        alertDialog = AlertDialog.Builder(context).setView(view).create()
        alertDialog?.setOnDismissListener {
            alertDialog = null
            view = null
            adapter = null
        }
        val recyclerView: RecyclerView = view!!.findViewById(R.id.rv)
        adapter = SaveAdapter(context, path, object : SaveAdapter.OnClick {
            override fun onClick(s: String?, name: String?) {
                listener.onListClick(s, name, this@CommomSave)
            }

            override fun onChange(i: Int) {
                FileUtil.save(context, JSON.toJSONString(SaveAdapter.getSaveDataList()), path, object : FileUtil.OnSave {
                    override fun onSave() {
                        listener.onListChange()
                    }
                })
            }
        })
        recyclerView.adapter = adapter
        recyclerView.layoutManager = LinearLayoutManager(context)
        val btn0: TextView = view!!.findViewById(R.id.btn0)
        val btn1: TextView = view!!.findViewById(R.id.btn1)
        val btn2: TextView = view!!.findViewById(R.id.btn2)
        if (btnStrings.isNotEmpty()) {
            btn0.visibility = View.VISIBLE
            btn0.text = btnStrings[0]
            btn0.setOnClickListener { listener.onBtnClick(this@CommomSave, 0) }
        } else {
            btn0.visibility = View.GONE
        }
        if (btnStrings.size > 1) {
            btn1.visibility = View.VISIBLE
            btn1.text = btnStrings[1]
            btn1.setOnClickListener { listener.onBtnClick(this@CommomSave, 1) }
        } else {
            btn1.visibility = View.GONE
        }
        if (btnStrings.size > 2) {
            btn2.visibility = View.VISIBLE
            btn2.text = btnStrings[2]
            btn2.setOnClickListener { listener.onBtnClick(this@CommomSave, 2) }
        } else {
            btn2.visibility = View.GONE
        }
        alertDialog?.show()
    }

    interface OnClickListener {
        fun onBtnClick(save: CommomSave, i: Int)
        fun onListClick(s: String?, name: String?, save: CommomSave)
        fun onListChange()
    }
}
