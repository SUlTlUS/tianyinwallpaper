package com.zeaze.tianyinwallpaper.ui.main;

import android.app.Activity;
import android.content.Context;
import android.content.DialogInterface;
import android.content.SharedPreferences;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.recyclerview.widget.RecyclerView;

import com.alibaba.fastjson.JSON;
import com.bumptech.glide.Glide;
import com.github.gzuliyujiang.wheelpicker.TimePicker;
import com.github.gzuliyujiang.wheelpicker.annotation.TimeMode;
import com.github.gzuliyujiang.wheelpicker.contract.OnTimePickedListener;
import com.github.gzuliyujiang.wheelpicker.entity.TimeEntity;
import com.github.gzuliyujiang.wheelpicker.impl.UnitTimeFormatter;
import com.github.gzuliyujiang.wheelpicker.widget.TimeWheelLayout;
import com.zeaze.tianyinwallpaper.R;
import com.zeaze.tianyinwallpaper.model.TianYinWallpaperModel;

import java.util.List;

public class WallpaperAdapter extends RecyclerView.Adapter<WallpaperAdapter.ViewHolder> {
    private Context context;
    private final List<TianYinWallpaperModel> list;
    private int parentWidth;
    private SharedPreferences pref;
    private SharedPreferences.Editor edit;
    private EditText tv;
    private int widthPixels,heightPixels;
    private AlertDialog alertDialog;
    private AlertDialog dialog;

    public WallpaperAdapter(Context context, List<TianYinWallpaperModel> list, EditText tv) {
        DisplayMetrics displayMetrics=context.getResources().getDisplayMetrics();
        widthPixels=displayMetrics.widthPixels;
        heightPixels=displayMetrics.heightPixels;
        pref = context.getSharedPreferences("tianyin",context.MODE_PRIVATE);
        edit = pref.edit();
        list.clear();
        String cache=pref.getString("wallpaperCache","");
        if (cache.equals("")) {

        }
        else{
            list.addAll(JSON.parseArray(cache,TianYinWallpaperModel.class));
            tv.setText(pref.getString("wallpaperTvCache",""));
        }
        this.context = context;
        this.list = list;
        this.tv=tv;
    }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view= LayoutInflater.from(parent.getContext()).inflate(R.layout.adapter_wallpaper, parent, false);
        parentWidth = parent.getWidth();
        return new ViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
        if (parentWidth>0) {
            View view = holder.root;
            ViewGroup.LayoutParams layoutParams = view.getLayoutParams();
            layoutParams.height=(int)(parentWidth*heightPixels/widthPixels/MainFragment.column);
            view.setLayoutParams(layoutParams);
            Log.d("TAG", "onBindViewHolder: "+layoutParams.height);
        }
        TianYinWallpaperModel model=list.get(position);
        // Load wallpapers directly from their original URIs when available
        if (model.getType() == 0 && model.getImgUri() != null && !model.getImgUri().isEmpty()) {
            // For static images, load from original URI
            Glide.with(context).load(android.net.Uri.parse(model.getImgUri())).into(holder.iv);
        } else if (model.getType() == 1 && model.getVideoUri() != null && !model.getVideoUri().isEmpty()) {
            // For videos, load from original video URI
            Glide.with(context).load(android.net.Uri.parse(model.getVideoUri())).into(holder.iv);
        } else {
            // Fallback to imgPath for compatibility with old data
            Glide.with(context).load(model.getImgPath()).into(holder.iv);
        }
        if (model.getType()==0){
            holder.tr.setText("静态");
        }
        else{
            holder.tr.setText("动态");
        }
        if (model.getStartTime()==-1|model.getEndTime()==-1){
            holder.time.setVisibility(View.INVISIBLE);
        }
        else{
            holder.time.setText(getTimeString(model.getStartTime())
                    +" - "+getTimeString(model.getEndTime()));
            holder.time.setVisibility(View.VISIBLE);
        }
        final int i=position;
        holder.itemView.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                AlertDialog.Builder builder = new AlertDialog.Builder(context);
                View view=LayoutInflater.from(context).inflate(R.layout.wallpaper_item, null);
                view.findViewById(R.id.tv1).setOnClickListener(new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        setIf(i);
                        alertDialog.dismiss();
                    }
                });
                view.findViewById(R.id.tv3).setOnClickListener(new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        setLoop(i);
                        alertDialog.dismiss();
                    }
                });
                view.findViewById(R.id.tv2).setOnClickListener(new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        delete(i);
                        alertDialog.dismiss();
                    }
                });
                builder.setView(view);
                alertDialog=builder.create();
                alertDialog.show();
            }
        });
    }

    private void setIf(int i){
        AlertDialog.Builder builder = new AlertDialog.Builder(context);
        View view=LayoutInflater.from(context).inflate(R.layout.wallpaper_se_time, null);
        TextView start=view.findViewById(R.id.start);
        TextView end=view.findViewById(R.id.end);
        TextView reset=view.findViewById(R.id.reset);
        TextView set=view.findViewById(R.id.set);
        TianYinWallpaperModel model=list.get(i);
        if (model.getStartTime()!=-1){
            start.setText("开始时间："+getTimeString((model.getStartTime())));
        }
        if (model.getEndTime()!=-1){
            end.setText("结束时间："+getTimeString((model.getEndTime())));
        }
        start.setTag(model.getStartTime());
        end.setTag(model.getEndTime());
        start.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                selectTime((TextView)v,"开始时间：");
            }
        });
        end.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                selectTime((TextView)v,"结束时间：");
            }
        });
        reset.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                start.setTag(-1);
                end.setTag(-1);
                start.setText("开始时间：点击选择");
                end.setText("结束时间：点击选择");
            }
        });
        set.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                model.setStartTime((Integer) start.getTag());
                model.setEndTime((Integer) end.getTag());
                if (model.getStartTime()!=-1&&model.getEndTime()==-1){
                    model.setEndTime(24*60);
                }
                if (model.getEndTime()!=-1&&model.getStartTime()==-1){
                    model.setStartTime(0);
                }
                tryToNotifyDataSetChanged();
                dialog.dismiss();
            }
        });
        builder.setView(view);
        builder.setCancelable(false);
        dialog=builder.create();
        dialog.show();
    }

    private void setLoop(int i){
        AlertDialog.Builder builder = new AlertDialog.Builder(context);
        View view=LayoutInflater.from(context).inflate(R.layout.wallpaper_se_loop, null);
        CheckBox loopCheckBox=view.findViewById(R.id.loopCheckBox);
        TextView set=view.findViewById(R.id.set);
        TianYinWallpaperModel model=list.get(i);
        loopCheckBox.setChecked(model.isLoop());
        set.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                model.setLoop(loopCheckBox.isChecked());
                tryToNotifyDataSetChanged();
                dialog.dismiss();
            }
        });
        builder.setView(view);
        builder.setCancelable(false);
        dialog=builder.create();
        dialog.show();
    }

    private String getTimeString(int t){
        String s="";
        if (t/60==0)s=s+"00";
        else if (t/60<10)s=s+"0"+(t/60);
        else s=s+(t/60);
        t=t%60;
        if (t<10)s=s+":0"+t;
        else s=s+":"+t;
        return s;
    }

    private void selectTime(TextView tv,String s){
        TimePicker picker = new TimePicker((Activity) context);
        TimeWheelLayout wheelLayout = picker.getWheelLayout();
        wheelLayout.setTimeMode(TimeMode.HOUR_24_NO_SECOND);
        wheelLayout.setTimeFormatter(new UnitTimeFormatter());
        if ((Integer)tv.getTag()!=-1) {
            wheelLayout.setDefaultValue(TimeEntity.target(((Integer) tv.getTag()) / 60, ((Integer) tv.getTag()) % 60, 0));
        }
        else{
            wheelLayout.setDefaultValue(TimeEntity.target(0,0,0));
        }
        wheelLayout.setResetWhenLinkage(false);
        picker.setOnTimePickedListener(new OnTimePickedListener() {
            @Override
            public void onTimePicked(int hour, int minute, int second) {
                tv.setText(s+getTimeString(hour * 60 + minute));
                tv.setTag(hour * 60 + minute);
            }
        });
        picker.show();
    }

    private void delete(int i){
        AlertDialog.Builder builder = new AlertDialog.Builder(context);
        builder.setTitle("是否删除选中壁纸").setNeutralButton("取消", null)
                .setPositiveButton("确定", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        list.remove(i);
                        tryToNotifyDataSetChanged();
                    }
                })
                .setCancelable(false)
                .show();
    }

    @Override
    public int getItemCount() {
        return list.size();
    }


    public void tryToNotifyDataSetChanged(){
        edit.putString("wallpaperCache", JSON.toJSONString(list));
        if (tv!=null) {
            edit.putString("wallpaperTvCache", tv.getText().toString());
        }
        edit.apply();
        notifyDataSetChanged();
    }

    class ViewHolder extends RecyclerView.ViewHolder{
        public TextView tr,time;
        public ImageView iv;
        public View root;
        public ViewHolder(@NonNull View itemView) {
            super(itemView);
            tr=itemView.findViewById(R.id.tr);
            iv=itemView.findViewById(R.id.iv);
            time=itemView.findViewById(R.id.time);
            root=itemView.findViewById(R.id.root);
        }
    }

}
