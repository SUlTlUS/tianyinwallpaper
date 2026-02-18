package com.zeaze.tianyinwallpaper;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import android.Manifest;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Point;
import android.net.Uri;
import android.os.Bundle;
import android.util.Log;
import android.view.WindowManager;
import android.widget.Toast;

import com.alibaba.fastjson.JSON;
import com.google.android.material.tabs.TabLayout;
import com.pgyer.pgyersdk.PgyerSDKManager;
import com.pgyer.pgyersdk.callback.CheckoutVersionCallBack;
import com.pgyer.pgyersdk.model.CheckSoftModel;
import com.zeaze.tianyinwallpaper.base.BaseActivity;
import com.zeaze.tianyinwallpaper.base.BaseFragment;
import com.zeaze.tianyinwallpaper.base.BaseFragmentAdapter;
import com.zeaze.tianyinwallpaper.model.TianYinWallpaperModel;
import com.zeaze.tianyinwallpaper.ui.about.AboutFragment;
import com.zeaze.tianyinwallpaper.ui.main.MainFragment;
import com.zeaze.tianyinwallpaper.ui.setting.SettingFragment;
import com.zeaze.tianyinwallpaper.ui.commom.SaveData;
import com.zeaze.tianyinwallpaper.utils.FileUtil;
import com.zeaze.tianyinwallpaper.widget.NoScrollViewPager;


import java.io.File;
import java.util.ArrayList;
import java.util.List;

public class MainActivity extends BaseActivity  {
    private final static int REQUEST_CODE_SET_WALLPAPER = 0x001;
    private TabLayout tabLayout;
    private NoScrollViewPager viewPager;
    private List<String> titles;
    private List<BaseFragment>fragments;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        tabLayout=findViewById(R.id.tab_layout);
        viewPager=findViewById(R.id.view_pager);

        titles=new ArrayList<>();
        fragments=new ArrayList<>();
        titles.add("壁纸");
        titles.add("设置");
        titles.add("关于");
        fragments.add(new MainFragment());
        fragments.add(new SettingFragment());
        fragments.add(new AboutFragment());
        BaseFragmentAdapter adapter=new BaseFragmentAdapter(getSupportFragmentManager(),titles,fragments);
        viewPager.setAdapter(adapter);
        viewPager.setOffscreenPageLimit(100);
        tabLayout.setupWithViewPager(viewPager);

        WindowManager wm = (WindowManager) this
                .getSystemService(Context.WINDOW_SERVICE);
        Point point=new Point();
        wm.getDefaultDisplay().getRealSize(point);
        FileUtil.width = point.x;
        FileUtil.height = point.y;
        permission();
        clearNoUseFile();

    }

    public void openSettingPage() {
        if (viewPager != null) {
            viewPager.setCurrentItem(1, true);
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, @Nullable Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQUEST_CODE_SET_WALLPAPER) {
            if (resultCode == RESULT_OK) {
                Toast.makeText(this, "设置动态壁纸成功", Toast.LENGTH_SHORT).show();
            } else {
                Toast.makeText(this, "取消设置动态壁纸", Toast.LENGTH_SHORT).show();
            }
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        PgyerSDKManager.checkSoftwareUpdate(this, new CheckoutVersionCallBack() {
            @Override
            public void onSuccess(CheckSoftModel checkSoftModel) {
                AlertDialog.Builder builder = new AlertDialog.Builder(MainActivity.this);
                builder.setTitle("检测到有新版本")
                        .setMessage(checkSoftModel.buildUpdateDescription)
                        .setPositiveButton("更新", new DialogInterface.OnClickListener() {
                            public void onClick(DialogInterface dialog, int id) {
                                Uri uri = Uri.parse(checkSoftModel.downloadURL);
                                Intent intent = new Intent(Intent.ACTION_VIEW, uri);
                                startActivity(intent);
                            }
                        })
                        .setNegativeButton("下次", new DialogInterface.OnClickListener() {
                            public void onClick(DialogInterface dialog, int id) {

                            }
                        })
                        .setCancelable(false)
                        .create()
                        .show();
            }

            @Override
            public void onFail(String s) {
            }
        });
    }

    private void clearNoUseFile(){
        new Thread(new Runnable() {
            @Override
            public void run() {
                List<String> uuids=new ArrayList<>();
                File file=new File(getExternalFilesDir(null)+FileUtil.wallpaperFilePath);
                if (!file.exists()){
                    file.mkdirs();
                }
                String s= FileUtil.loadData(MainActivity.this,FileUtil.dataPath);
                List<SaveData> saveDataList= JSON.parseArray(s, SaveData.class);
                List<TianYinWallpaperModel> list;
                for (SaveData saveData:saveDataList){
                    list=JSON.parseArray(saveData.getS(),TianYinWallpaperModel.class);
                    for (TianYinWallpaperModel model:list){
                        uuids.add(model.getUuid());
                    }
                }
                String cache=getSharedPreferences("tianyin",MODE_PRIVATE).getString("wallpaperCache","");
                if (!cache.equals("")) {
                    list=JSON.parseArray(cache,TianYinWallpaperModel.class);
                    for (TianYinWallpaperModel model:list){
                        uuids.add(model.getUuid());
                    }
                }
                s= FileUtil.loadData(getApplicationContext(),FileUtil.wallpaperPath);
                list= JSON.parseArray(s, TianYinWallpaperModel.class);
                for (TianYinWallpaperModel model:list){
                    uuids.add(model.getUuid());
                }
                String[] papers=file.list();
                if (papers!=null) {
                    for (String paper : papers) {
                        boolean b = false;
                        for (String uuid : uuids) {
                            if (paper.startsWith(uuid)) {
                                b = true;
                                break;
                            }
                        }
                        if (!b) {
                            new File(file, paper).delete();
                        }
                    }
                }
            }
        }).start();
    }

    private void permission(){
        List<String> permissionList = new ArrayList<>();
        if(ContextCompat.checkSelfPermission(this, Manifest.permission.INTERNET) != PackageManager.PERMISSION_GRANTED){
            permissionList.add(Manifest.permission.INTERNET);
        }
        if(ContextCompat.checkSelfPermission(this,Manifest.permission.SET_WALLPAPER) != PackageManager.PERMISSION_GRANTED){
            permissionList.add(Manifest.permission.SET_WALLPAPER);
        }
        // Check for storage permissions based on Android version
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            // Android 13+
            if(ContextCompat.checkSelfPermission(this, Manifest.permission.READ_MEDIA_IMAGES) != PackageManager.PERMISSION_GRANTED){
                permissionList.add(Manifest.permission.READ_MEDIA_IMAGES);
            }
            if(ContextCompat.checkSelfPermission(this, Manifest.permission.READ_MEDIA_VIDEO) != PackageManager.PERMISSION_GRANTED){
                permissionList.add(Manifest.permission.READ_MEDIA_VIDEO);
            }
        } else {
            // Android 12 and below
            if(ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED){
                permissionList.add(Manifest.permission.READ_EXTERNAL_STORAGE);
            }
        }
        if(!permissionList.isEmpty()){
            String [] permissions = permissionList.toArray(new String[permissionList.size()]);
            ActivityCompat.requestPermissions(this,permissions,1);
        }
        else {

        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        for (int i = 0; i < grantResults.length; i++) {
            if (grantResults[i] == PackageManager.PERMISSION_DENIED) {
                new AlertDialog.Builder(this).setMessage("没有获取到" + permissions[i] + "权限，无法使用，请去系统设置里开启权限")
                        .setPositiveButton("确定", new DialogInterface.OnClickListener() {
                            public void onClick(DialogInterface dialog, int id) {
                                finish();
                            }
                        })
                        .setCancelable(false)
                        .create()
                        .show();
                return;
            }
        }
    }

}
