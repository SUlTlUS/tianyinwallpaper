package com.zeaze.tianyinwallpaper.ui.commom

import android.content.Context
import androidx.appcompat.app.AlertDialog
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.alibaba.fastjson.JSON
import com.zeaze.tianyinwallpaper.R
import com.zeaze.tianyinwallpaper.utils.FileUtil

class CommomSave {
    var alertDialog: AlertDialog? = null
    var adapter: SaveAdapter? = null

    fun saveDialog(context: Context, path: String, btnStrings: Array<String>, listener: OnClickListener) {
        val saveAdapter = SaveAdapter(context, path, object : SaveAdapter.OnClick {
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
        adapter = saveAdapter
        val composeView = ComposeView(context).apply {
            setContent {
                MaterialTheme {
                    SaveDialogContent(context, btnStrings, listener, saveAdapter)
                }
            }
        }
        alertDialog = AlertDialog.Builder(context).setView(composeView).create()
        alertDialog?.setOnDismissListener {
            alertDialog = null
            adapter = null
        }
        alertDialog?.show()
    }

    @Composable
    private fun SaveDialogContent(context: Context, btnStrings: Array<String>, listener: OnClickListener, saveAdapter: SaveAdapter) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 10.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text(
                text = context.getString(R.string.save_list_title),
                style = MaterialTheme.typography.h6,
                modifier = Modifier
                    .fillMaxWidth()
                    .semantics { heading() }
            )
            AndroidView(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(SAVE_LIST_HEIGHT_DP.dp),
                factory = { recyclerContext ->
                    RecyclerView(recyclerContext).apply {
                        layoutManager = LinearLayoutManager(recyclerContext)
                        adapter = saveAdapter
                    }
                }
            )
            for (i in btnStrings.indices) {
                Text(
                    text = btnStrings[i],
                    color = MaterialTheme.colors.onBackground,
                    modifier = Modifier
                        .fillMaxWidth()
                        .defaultMinSize(minHeight = 48.dp)
                        .semantics { role = Role.Button }
                        .padding(vertical = 8.dp)
                        .clickable { listener.onBtnClick(this@CommomSave, i) }
                )
            }
        }
    }

    interface OnClickListener {
        fun onBtnClick(save: CommomSave, i: Int)
        fun onListClick(s: String?, name: String?, save: CommomSave)
        fun onListChange()
    }

    companion object {
        private const val SAVE_LIST_HEIGHT_DP = 250
    }
}
