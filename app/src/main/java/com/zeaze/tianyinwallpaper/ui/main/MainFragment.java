package com.zeaze.tianyinwallpaper.ui.main;

import static android.app.Activity.RESULT_OK;
import static android.content.Context.MODE_PRIVATE;

import android.app.WallpaperManager;
import android.content.ComponentName;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Bundle;
import android.provider.Settings;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.CheckBox;
import android.widget.CompoundButton;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.ItemTouchHelper;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.alibaba.fastjson.JSON;
import com.bumptech.glide.Glide;
import com.lxj.xpopup.XPopup;
import com.lxj.xpopup.impl.LoadingPopupView;
import com.zeaze.tianyinwallpaper.App;
import com.zeaze.tianyinwallpaper.base.rxbus.RxBus;
import com.zeaze.tianyinwallpaper.base.rxbus.RxConstants;
import com.zeaze.tianyinwallpaper.service.TianYinWallpaperService;
import com.zeaze.tianyinwallpaper.ui.commom.CommomSave;
import com.zeaze.tianyinwallpaper.ui.commom.SaveAdapter;
import com.zeaze.tianyinwallpaper.ui.commom.SaveData;
import com.zeaze.tianyinwallpaper.utils.FileUtil;
import com.zeaze.tianyinwallpaper.R;
import com.zeaze.tianyinwallpaper.base.BaseFragment;
import com.zeaze.tianyinwallpaper.model.TianYinWallpaperModel;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

import io.reactivex.functions.Consumer;

public class MainFragment extends BaseFragment {
    private RecyclerView rv;
    private GridLayoutManager manager;
    private WallpaperAdapter wallpaperAdapter;
    private ImageView upload;
    private EditText tv;
    private TextView select,apply;
    private List<TianYinWallpaperModel> list=new ArrayList();;
    public static int column=3;
    private TianYinWallpaperModel model;
    private LoadingPopupView popupView;

    //批量转换记录
    private int now=0;
    private List<Uri> uris;
    private int type=1;//1静态2动态

    private SharedPreferences pref;
    private SharedPreferences.Editor editor;

    @Override
    protected void init() {
        addDisposable(RxBus.getDefault().toObservableWithCode(RxConstants.RX_ADD_WALLPAPER, TianYinWallpaperModel.class)
                .subscribe(new Consumer<TianYinWallpaperModel>() {
                    @Override
                    public void accept(TianYinWallpaperModel o) throws Exception {
                        list.add(0,o);
                        getActivity().runOnUiThread(new Runnable() {
                            @Override
                            public void run() {
                                wallpaperAdapter.tryToNotifyDataSetChanged();
                                toast("已加入，请在“壁纸“里查看");
                            }
                        });
                    }
                })
        );

        rv=view.findViewById(R.id.rv);
        upload =view.findViewById(R.id.upload);
        select =view.findViewById(R.id.select);
        apply =view.findViewById(R.id.apply);
        tv =view.findViewById(R.id.tv);

        manager=new GridLayoutManager(getContext(),column);
        rv.setLayoutManager(manager);
        wallpaperAdapter=new WallpaperAdapter(getContext(),list,tv);
        rv.setAdapter(wallpaperAdapter);
        wallpaperAdapter.tryToNotifyDataSetChanged();
        helper.attachToRecyclerView(rv);

        pref = getContext().getSharedPreferences(App.TIANYIN,MODE_PRIVATE);
        editor = getContext().getSharedPreferences(App.TIANYIN,MODE_PRIVATE).edit();

        Glide.with(getContext()).load(R.drawable.setting).into(upload);
        initUpload();
        select.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Log.d("TAG", "onClick: ");
                if (model!=null){
                    return;
                }
                AlertDialog.Builder builder = new AlertDialog.Builder(getContext());
                builder.setTitle("请选择壁纸类型，可长按选中的壁纸来多选")
                        .setNeutralButton("取消", new DialogInterface.OnClickListener() {
                            @Override
                            public void onClick(DialogInterface dialog, int which) {
                                model=null;
                            }
                        })
                        .setNegativeButton("静态", new DialogInterface.OnClickListener() {
                            @Override
                            public void onClick(DialogInterface dialog, int which) {
                                selectWallpaper();
                            }
                        })
                        .setPositiveButton("动态", new DialogInterface.OnClickListener() {
                            @Override
                            public void onClick(DialogInterface dialog, int which) {
                                selectLiveWallpaper();
                            }
                        })
                        .setCancelable(false)
                        .show();

            }
        });
        apply.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (list.size()==0){
                    toast("至少需要1张壁纸才能开始设置");
                    return;
                }
                new Thread(new Runnable() {
                    @Override
                    public void run() {
                        FileUtil.save(getContext(), JSON.toJSONString(list),FileUtil.wallpaperPath, new FileUtil.onSave() {
                            @Override
                            public void onSave() {
                                getActivity().runOnUiThread(new Runnable() {
                                    @Override
                                    public void run() {
                                        WallpaperManager wallpaperManager = WallpaperManager.getInstance(getContext());
                                        try {
                                            wallpaperManager.clear();
                                        } catch (IOException e) {
                                            e.printStackTrace();
                                        }
                                        Intent intent = new Intent();
                                        intent.setAction(WallpaperManager.ACTION_CHANGE_LIVE_WALLPAPER);
                                        intent.putExtra(WallpaperManager.EXTRA_LIVE_WALLPAPER_COMPONENT, new ComponentName(getActivity(), TianYinWallpaperService.class));
                                        wallpaperLaunch.launch(intent);
                                    }
                                });
                            }
                        });
                    }
                }).start();
            }
        });
    }

    @Override
    protected int getLayout() {
        return R.layout.main_fragment;
    }

    private ActivityResultLauncher imageLaunch,videoLaunch,wallpaperLaunch;

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        imageLaunch=registerForActivityResult(
                new ActivityResultContracts.OpenMultipleDocuments(), results -> {
                    if (results==null||results.size()==0){
                        model=null;
                        return;
                    }
                    takePersistableUriPermissions(results);
                    now=0;
                    uris=results;
                    type=1;
                    popupView=(LoadingPopupView)new XPopup.Builder(getContext())
                            .dismissOnBackPressed(false)
                            .dismissOnTouchOutside(false)
                            .asLoading("转换壁纸中")
                            .show();
                    exchange(now);
                }
        );
        videoLaunch=registerForActivityResult(
                new ActivityResultContracts.OpenMultipleDocuments(), results -> {
                    if (results==null||results.size()==0){
                        model = null;
                        return;
                    }
                    takePersistableUriPermissions(results);
                    now=0;
                    uris=results;
                    type=2;
                    popupView=(LoadingPopupView)new XPopup.Builder(getContext())
                            .dismissOnBackPressed(false)
                            .dismissOnTouchOutside(false)
                            .asLoading("转换壁纸中")
                            .show();
                    exchange(now);
                }
        );
         wallpaperLaunch=registerForActivityResult(new ActivityResultContracts.StartActivityForResult(),result -> {
            if (result.getResultCode()==RESULT_OK){
                toast("设置成功");
            }
            else{
                new AlertDialog.Builder(getContext()).setMessage("设置失败，可能是没有设置动态壁纸权限，是否去设置")
                        .setPositiveButton("确定", new DialogInterface.OnClickListener() {
                            public void onClick(DialogInterface dialog, int id) {
                                Intent intent = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
                                Uri uri = Uri.fromParts("package", getActivity().getPackageName(), null);
                                intent.setData(uri);
                                startActivity(intent);
                            }
                        })
                        .setNeutralButton("取消", new DialogInterface.OnClickListener() {
                            @Override
                            public void onClick(DialogInterface dialog, int which) {

                            }
                        })
                        .create()
                        .show();
            }
        });
    }

    private void takePersistableUriPermissions(List<Uri> uris) {
        // Take persistable URI permissions for each selected URI
        for (Uri uri : uris) {
            try {
                getActivity().getContentResolver().takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION
                );
            } catch (SecurityException e) {
                // Permission may not be available for all URIs
                Log.e("MainFragment", "Could not take persistable permission for URI: " + uri, e);
            }
        }
    }

    private void exchange(int index){
        if (uris==null||uris.size()<=index){
            return;
        }
        new Thread(new Runnable() {
            @Override
            public void run() {
                model=new TianYinWallpaperModel();
                Uri currentUri = uris.get(index);
                if (type == 1) {
                    model.setType(0);
                    model.setUuid(UUID.randomUUID().toString());
                    model.setImgUri(currentUri.toString());
                    addModel();
                }
                else{
                    model.setType(1);
                    model.setUuid(UUID.randomUUID().toString());
                    // Store the URI string for direct access to original video
                    model.setVideoUri(currentUri.toString());
                    // No need to save thumbnail - adapter will load directly from videoUri
                    addModel();

                }
            }
        }).start();
    }
    public void selectWallpaper() {
        imageLaunch.launch(new String[]{"image/*"});
    }

    public void selectLiveWallpaper() {
        videoLaunch.launch(new String[]{"video/*"});
    }

    ItemTouchHelper helper = new ItemTouchHelper(new ItemTouchHelper.Callback() {
        @Override
        public int getMovementFlags(RecyclerView recyclerView, RecyclerView.ViewHolder viewHolder) {
            int dragFrlg = 0;
            if (recyclerView.getLayoutManager() instanceof GridLayoutManager){
                dragFrlg = ItemTouchHelper.UP|ItemTouchHelper.DOWN|ItemTouchHelper.LEFT|ItemTouchHelper.RIGHT;
            }else if(recyclerView.getLayoutManager() instanceof LinearLayoutManager){
                dragFrlg = ItemTouchHelper.UP|ItemTouchHelper.DOWN;
            }
            return makeMovementFlags(dragFrlg,0);
        }
        @Override
        public boolean onMove(RecyclerView recyclerView, RecyclerView.ViewHolder viewHolder, RecyclerView.ViewHolder target) {
            int fromPosition = viewHolder.getAdapterPosition();
            int toPosition = target.getAdapterPosition();
            if (fromPosition < toPosition) {
                for (int i = fromPosition; i < toPosition; i++) {
                    Collections.swap(list, i, i + 1);
                }
            } else {
                for (int i = fromPosition; i > toPosition; i--) {
                    Collections.swap(list, i, i - 1);
                }
            }
            wallpaperAdapter.notifyItemMoved(fromPosition, toPosition);
            return true;
        }
        @Override
        public void onSwiped(RecyclerView.ViewHolder viewHolder, int direction) {
        }
        @Override
        public boolean isLongPressDragEnabled() {
            return true;
        }
        @Override
        public void onSelectedChanged(RecyclerView.ViewHolder viewHolder, int actionState) {
            if (actionState != ItemTouchHelper.ACTION_STATE_IDLE) {
                toast("可以开始拖动了");
            }
            super.onSelectedChanged(viewHolder, actionState);
        }
        @Override
        public void clearView(RecyclerView recyclerView, RecyclerView.ViewHolder viewHolder) {
            super.clearView(recyclerView, viewHolder);
            wallpaperAdapter.tryToNotifyDataSetChanged();
        }
    });

    private void addModel(){
        getActivity().runOnUiThread(new Runnable() {
            @Override
            public void run() {
                list.add(0,model);
                wallpaperAdapter.tryToNotifyDataSetChanged();
                model=null;
                now = now+1;
                if (now>=uris.size()){
                    uris.clear();
                    popupView.dismiss();
                }
                else{
                    exchange(now);
                    popupView.setTitle("转化壁纸中,进度"+now+"/"+uris.size());
                }
            }
        });
    }

    private void initUpload(){
        upload.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                new CommomSave().saveDialog(getContext(), FileUtil.dataPath,new String[]{"保存","壁纸通用设置","清空当前壁纸组"}, new CommomSave.onClickListener() {
                    @Override
                    public void onBtnClick(CommomSave save,int i) {
                        if (i==0){
                            if (tv.getText().toString().equals("")){
                                toast("请先输入表名称");
                                return;
                            }
                            SaveData saveData=new SaveData();
                            saveData.setName(tv.getText().toString());
                            saveData.setS(JSON.toJSONString(list));
                            SaveAdapter.getSaveDataList().add(0,saveData);
                            new Thread(new Runnable() {
                                @Override
                                public void run() {
                                    FileUtil.save(getContext(), JSON.toJSONString(SaveAdapter.getSaveDataList()),FileUtil.dataPath, new FileUtil.onSave() {
                                        @Override
                                        public void onSave() {
                                            getActivity().runOnUiThread(new Runnable() {
                                                @Override
                                                public void run() {
                                                    toast("保存成功");
                                                    save.adapter.notifyDataSetChanged();
                                                }
                                            });
                                        }
                                    });
                                }
                            }).start();
                        }
                        if (i==1){
                            wallpaperSetting();
                        }
                        if (i==2){
                            TextView textView =(TextView) LayoutInflater.from(getContext()).inflate(R.layout.textview, null);
                            textView.setText("是否清空表格");
                            AlertDialog.Builder builder = new AlertDialog.Builder(getContext());
                            builder.setView(textView)
                                    .setPositiveButton("确定", new DialogInterface.OnClickListener() {
                                        public void onClick(DialogInterface dialog, int id) {
                                            tv.setText("");
                                            list.clear();
                                            wallpaperAdapter.tryToNotifyDataSetChanged();
                                            toast("清空成功");
                                        }
                                    })
                                    .setNeutralButton("取消", new DialogInterface.OnClickListener() {
                                        @Override
                                        public void onClick(DialogInterface dialog, int which) {

                                        }
                                    })
                                    .create()
                                    .show();
                        }
                    }

                    @Override
                    public void onListClick(String s, String name, CommomSave save) {
                        list.clear();
                        list.addAll(JSON.parseArray(s,TianYinWallpaperModel.class));
                        wallpaperAdapter.tryToNotifyDataSetChanged();
                        tv.setText(name);
                        save.alertDialog.dismiss();
                    }

                    @Override
                    public void onListChange() {
                        toast("操作成功");
                    }
                });

            }
        });
    }

    private AlertDialog alertDialog;
    private void wallpaperSetting(){
        AlertDialog.Builder builder = new AlertDialog.Builder(getContext());
        View view=LayoutInflater.from(getContext()).inflate(R.layout.main_wallpaper_setting, null);
        CompoundButton checkBox=view.findViewById(R.id.checkBox);
        checkBox.setChecked(pref.getBoolean("rand",false));
        checkBox.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                editor.putBoolean("rand",isChecked);
                editor.apply();
            }
        });

        CompoundButton checkBox2=view.findViewById(R.id.checkBox2);
        checkBox2.setChecked(pref.getBoolean("pageChange",false));
        checkBox2.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                editor.putBoolean("pageChange",isChecked);
                editor.apply();
            }
        });

        CompoundButton checkBox3=view.findViewById(R.id.checkBox3);
        checkBox3.setChecked(pref.getBoolean("needBackgroundPlay",false));
        checkBox3.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                editor.putBoolean("needBackgroundPlay",isChecked);
                editor.apply();
            }
        });

        CheckBox checkBox4=view.findViewById(R.id.checkBox4);
        checkBox4.setChecked(pref.getBoolean("wallpaperScroll",false));
        checkBox4.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                editor.putBoolean("wallpaperScroll",isChecked);
                editor.apply();
            }
        });

        TextView tv=view.findViewById(R.id.tv);
        tv.setText("壁纸最小切换时间:"+pref.getInt("minTime",1)+"秒（点击修改）");
        tv.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                setMinTime(new DialogInterface.OnDismissListener() {
                    @Override
                    public void onDismiss(DialogInterface dialogInterface) {
                        tv.setText("壁纸最小切换时间:"+pref.getInt("minTime",1)+"秒");
                    }
                });
            }
        });
        builder.setView(view);
        alertDialog=builder.create();
        alertDialog.show();
    }

    private void setMinTime(DialogInterface.OnDismissListener onDismissListener){
        View view = LayoutInflater.from(getContext()).inflate(R.layout.main_edit, null);
        AlertDialog.Builder builder = new AlertDialog.Builder(getContext());
        final EditText et = view.findViewById(R.id.tv);
        et.setText(pref.getInt("minTime",1)+"");
        et.setHint("请输入整数");
        builder.setView(view)
                .setNeutralButton("取消", null)
                .setPositiveButton("确定", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        try {
                            int i=Integer.parseInt(et.getText().toString());
                            editor.putInt("minTime",i);
                            editor.apply();
                        }
                        catch (Exception e){
                            toast("请输入整数");
                        }
                    }
                })
                .setCancelable(false)
                .setOnDismissListener(onDismissListener)
                .show();
    }
}
