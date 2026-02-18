package com.zeaze.tianyinwallpaper.ui.about

import android.content.Context
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import com.alibaba.fastjson.JSON
import com.bumptech.glide.Glide
import com.zeaze.tianyinwallpaper.R
import com.zeaze.tianyinwallpaper.model.TianYinWallpaperModel
import com.zeaze.tianyinwallpaper.ui.commom.SaveData
import com.zeaze.tianyinwallpaper.utils.FileUtil

class AboutFragment : Fragment() {
    private val saveDataList = mutableStateListOf<SaveData>()

    override fun onCreateView(
        inflater: android.view.LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        return ComposeView(requireContext()).apply {
            setContent {
                MaterialTheme {
                    AboutScreen()
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        loadGroups()
    }

    private fun loadGroups() {
        Thread {
            val data = FileUtil.loadData(requireContext(), FileUtil.dataPath)
            val list = JSON.parseArray(data, SaveData::class.java) ?: emptyList()
            activity?.runOnUiThread {
                saveDataList.clear()
                saveDataList.addAll(list)
            }
        }.start()
    }

    @Composable
    private fun AboutScreen() {
        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            items(saveDataList, key = { "${it.name ?: ""}\u0000${it.s ?: ""}" }) { data ->
                AboutGroupItem(data)
            }
        }
    }

    @Composable
    private fun AboutGroupItem(data: SaveData) {
        val wallpapers = remember(data.s) {
            JSON.parseArray(data.s, TianYinWallpaperModel::class.java) ?: emptyList()
        }
        AndroidView(
            modifier = Modifier.fillMaxWidth(),
            factory = { context ->
                val root = LinearLayout(context).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(10, 10, 10, 10)
                    background = ContextCompat.getDrawable(context, R.drawable.edit_background)
                }
                val nameView = TextView(context).apply {
                    id = View.generateViewId()
                    setTextColor(ContextCompat.getColor(context, R.color.textColor))
                    textSize = 14f
                    maxLines = 1
                }
                root.addView(
                    nameView,
                    LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT
                    )
                )
                val previewsRow = LinearLayout(context).apply {
                    orientation = LinearLayout.HORIZONTAL
                }
                root.setTag(R.id.name, nameView)
                root.setTag(R.id.about_previews_row_tag, previewsRow)
                val rowParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ITEM_PREVIEW_HEIGHT_DP.dpToPx(context)
                ).apply {
                    topMargin = ITEM_PREVIEW_TOP_MARGIN_DP.dpToPx(context)
                }
                root.addView(previewsRow, rowParams)
                repeat(PREVIEW_COUNT) { index ->
                    val imageView = ImageView(context).apply {
                        scaleType = ImageView.ScaleType.CENTER_CROP
                        background = ContextCompat.getDrawable(context, R.drawable.edit_background)
                    }
                    val params = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f)
                    if (index > 0) {
                        params.marginStart = ITEM_PREVIEW_SPACING_DP.dpToPx(context)
                    }
                    previewsRow.addView(imageView, params)
                }
                root
            },
            update = { view ->
                val nameView = view.getTag(R.id.name) as TextView
                val name = data.name
                nameView.text = if (name.isNullOrEmpty()) "未命名壁纸组" else name
                val previewsRow = view.getTag(R.id.about_previews_row_tag) as LinearLayout
                for (i in 0 until previewsRow.childCount) {
                    val preview = previewsRow.getChildAt(i) as ImageView
                    val model = wallpapers.getOrNull(i)
                    if (model != null) {
                        var path = if (model.type == 1) model.videoUri else model.imgUri
                        if (path.isNullOrEmpty()) {
                            path = model.imgPath
                        }
                        preview.visibility = View.VISIBLE
                        Glide.with(preview.context).load(path).into(preview)
                    } else {
                        preview.visibility = View.INVISIBLE
                    }
                }
            }
        )
    }

    companion object {
        private const val ITEM_PREVIEW_HEIGHT_DP = 68
        private const val ITEM_PREVIEW_TOP_MARGIN_DP = 8
        private const val ITEM_PREVIEW_SPACING_DP = 6
        private const val PREVIEW_COUNT = 4
    }

    private fun Int.dpToPx(context: Context): Int {
        return (this * context.resources.displayMetrics.density).toInt()
    }
}
