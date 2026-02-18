package com.zeaze.tianyinwallpaper.ui.about

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.alibaba.fastjson.JSON
import com.bumptech.glide.Glide
import com.zeaze.tianyinwallpaper.R
import com.zeaze.tianyinwallpaper.base.BaseFragment
import com.zeaze.tianyinwallpaper.model.TianYinWallpaperModel
import com.zeaze.tianyinwallpaper.ui.commom.SaveData
import com.zeaze.tianyinwallpaper.utils.FileUtil

class AboutFragment : BaseFragment() {
    private val saveDataList: MutableList<SaveData> = ArrayList()
    private lateinit var adapter: GroupAdapter

    override fun init() {
        val rv: RecyclerView = rootView.findViewById(R.id.rv)
        rv.layoutManager = LinearLayoutManager(context)
        adapter = GroupAdapter()
        rv.adapter = adapter
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
                adapter.notifyDataSetChanged()
            }
        }.start()
    }

    override fun getLayout(): Int {
        return R.layout.about_fragment
    }

    private inner class GroupAdapter : RecyclerView.Adapter<GroupAdapter.ViewHolder>() {
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val itemView = LayoutInflater.from(parent.context).inflate(R.layout.about_group_item, parent, false)
            return ViewHolder(itemView)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val data = saveDataList[position]
            val name = data.name
            holder.name.text = if (name.isNullOrEmpty()) "未命名壁纸组" else name
            val wallPapers = JSON.parseArray(data.s, TianYinWallpaperModel::class.java)
            bindPreview(holder.previews, wallPapers)
        }

        override fun getItemCount(): Int {
            return saveDataList.size
        }

        inner class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
            val name: TextView = itemView.findViewById(R.id.name)
            val previews: Array<ImageView> = arrayOf(
                itemView.findViewById(R.id.preview1),
                itemView.findViewById(R.id.preview2),
                itemView.findViewById(R.id.preview3),
                itemView.findViewById(R.id.preview4)
            )
        }
    }

    private fun bindPreview(previews: Array<ImageView>, wallpapers: List<TianYinWallpaperModel>?) {
        for (i in previews.indices) {
            val imageView = previews[i]
            if (wallpapers != null && i < wallpapers.size) {
                val model = wallpapers[i]
                var path = if (model.type == 1) model.videoUri else model.imgUri
                if (path.isNullOrEmpty()) {
                    path = model.imgPath
                }
                imageView.visibility = View.VISIBLE
                Glide.with(imageView.context).load(path).into(imageView)
            } else {
                imageView.setImageDrawable(null)
                imageView.visibility = View.INVISIBLE
            }
        }
    }
}
