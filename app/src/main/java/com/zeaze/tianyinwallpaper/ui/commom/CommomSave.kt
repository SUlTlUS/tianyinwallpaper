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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.Button
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.alibaba.fastjson.JSON
import com.zeaze.tianyinwallpaper.R
import com.zeaze.tianyinwallpaper.utils.FileUtil
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class CommomSave {
    var alertDialog: AlertDialog? = null
    var adapter: SaveAdapter? = null

    fun saveDialog(context: Context, path: String, btnStrings: Array<String>, listener: OnClickListener) {
        adapter = null
        val composeView = ComposeView(context).apply {
            setContent {
                MaterialTheme {
                    SaveDialogContent(context, path, btnStrings, listener)
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
    private fun SaveDialogContent(context: Context, path: String, btnStrings: Array<String>, listener: OnClickListener) {
        val list = remember { mutableStateListOf<SaveData>() }
        var opIndex by remember { mutableStateOf<Int?>(null) }
        LaunchedEffect(path) {
            val parsed = withContext(Dispatchers.IO) {
                val data = FileUtil.loadData(context, path)
                JSON.parseArray(data, SaveData::class.java) ?: emptyList()
            }
            list.clear()
            list.addAll(parsed)
        }
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
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(SAVE_LIST_HEIGHT_DP.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                itemsIndexed(list) { index, item ->
                    Text(
                        text = item.name ?: "",
                        color = MaterialTheme.colors.onBackground,
                        modifier = Modifier
                            .fillMaxWidth()
                            .defaultMinSize(minHeight = 42.dp)
                            .semantics { role = Role.Button }
                            .clickable { listener.onListClick(item.s, item.name, this@CommomSave) }
                            .padding(horizontal = 6.dp, vertical = 8.dp)
                    )
                    Button(
                        onClick = { opIndex = index },
                        modifier = Modifier
                            .fillMaxWidth()
                            .semantics { contentDescription = "Operations for ${item.name ?: ""}" }
                    ) {
                        Text("操作")
                    }
                }
            }
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
        opIndex?.let { i ->
            if (i in list.indices) {
                val item = list[i]
                androidx.compose.material.AlertDialog(
                    onDismissRequest = { opIndex = null },
                    title = { Text("选择操作") },
                    text = {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(onClick = {
                                if (i > 0) {
                                    val tmp = list[i - 1]
                                    list[i - 1] = item
                                    list[i] = tmp
                                    saveList(context, path, list, listener)
                                }
                                opIndex = null
                            }, modifier = Modifier.fillMaxWidth()) { Text("上移一格") }
                            Button(onClick = {
                                if (i < list.size - 1) {
                                    val tmp = list[i + 1]
                                    list[i + 1] = item
                                    list[i] = tmp
                                    saveList(context, path, list, listener)
                                }
                                opIndex = null
                            }, modifier = Modifier.fillMaxWidth()) { Text("下移一格") }
                            Button(onClick = {
                                list.removeAt(i)
                                saveList(context, path, list, listener)
                                opIndex = null
                            }, modifier = Modifier.fillMaxWidth()) { Text("删除") }
                        }
                    },
                    confirmButton = {
                        Button(onClick = { opIndex = null }) { Text("关闭") }
                    }
                )
            }
        }
    }

    private fun saveList(context: Context, path: String, list: List<SaveData>, listener: OnClickListener) {
        FileUtil.save(context, JSON.toJSONString(list), path, object : FileUtil.OnSave {
            override fun onSave() {
                listener.onListChange()
            }
        })
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
