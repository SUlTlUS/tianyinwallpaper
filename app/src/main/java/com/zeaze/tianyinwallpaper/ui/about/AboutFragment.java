package com.zeaze.tianyinwallpaper.ui.about;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.alibaba.fastjson.JSON;
import com.bumptech.glide.Glide;
import com.zeaze.tianyinwallpaper.R;
import com.zeaze.tianyinwallpaper.base.BaseFragment;
import com.zeaze.tianyinwallpaper.model.TianYinWallpaperModel;
import com.zeaze.tianyinwallpaper.ui.commom.SaveData;
import com.zeaze.tianyinwallpaper.utils.FileUtil;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class AboutFragment extends BaseFragment {
    private final List<SaveData> saveDataList = new ArrayList<>();
    private GroupAdapter adapter;

    @Override
    protected void init() {
        RecyclerView rv = view.findViewById(R.id.rv);
        rv.setLayoutManager(new LinearLayoutManager(getContext()));
        adapter = new GroupAdapter();
        rv.setAdapter(adapter);
    }

    @Override
    public void onResume() {
        super.onResume();
        loadGroups();
    }

    private void loadGroups() {
        new Thread(() -> {
            String data = FileUtil.loadData(getContext(), FileUtil.dataPath);
            List<SaveData> list = JSON.parseArray(data, SaveData.class);
            if (list == null) {
                list = Collections.emptyList();
            }
            List<SaveData> finalList = list;
            if (getActivity() != null) {
                getActivity().runOnUiThread(() -> {
                    saveDataList.clear();
                    saveDataList.addAll(finalList);
                    adapter.notifyDataSetChanged();
                });
            }
        }).start();
    }

    @Override
    protected int getLayout() {
        return R.layout.about_fragment;
    }

    private class GroupAdapter extends RecyclerView.Adapter<GroupAdapter.ViewHolder> {
        @NonNull
        @Override
        public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View itemView = LayoutInflater.from(parent.getContext()).inflate(R.layout.about_group_item, parent, false);
            return new ViewHolder(itemView);
        }

        @Override
        public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
            SaveData data = saveDataList.get(position);
            holder.name.setText(data.getName() == null || data.getName().isEmpty() ? "未命名壁纸组" : data.getName());
            List<TianYinWallpaperModel> wallPapers = JSON.parseArray(data.getS(), TianYinWallpaperModel.class);
            bindPreview(holder.previews, wallPapers);
        }

        @Override
        public int getItemCount() {
            return saveDataList.size();
        }

        class ViewHolder extends RecyclerView.ViewHolder {
            private final TextView name;
            private final ImageView[] previews;

            public ViewHolder(@NonNull View itemView) {
                super(itemView);
                name = itemView.findViewById(R.id.name);
                previews = new ImageView[]{
                        itemView.findViewById(R.id.preview1),
                        itemView.findViewById(R.id.preview2),
                        itemView.findViewById(R.id.preview3),
                        itemView.findViewById(R.id.preview4)
                };
            }
        }
    }

    private void bindPreview(ImageView[] previews, List<TianYinWallpaperModel> wallpapers) {
        for (int i = 0; i < previews.length; i++) {
            ImageView imageView = previews[i];
            if (wallpapers != null && i < wallpapers.size()) {
                TianYinWallpaperModel model = wallpapers.get(i);
                String path = model.getType() == 1 ? model.getVideoUri() : model.getImgUri();
                if (path == null || path.isEmpty()) {
                    path = model.getImgPath();
                }
                imageView.setVisibility(View.VISIBLE);
                Glide.with(imageView.getContext()).load(path).into(imageView);
            } else {
                imageView.setImageDrawable(null);
                imageView.setVisibility(View.INVISIBLE);
            }
        }
    }
}
