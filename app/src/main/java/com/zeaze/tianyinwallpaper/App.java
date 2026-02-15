package com.zeaze.tianyinwallpaper;

import android.app.Application;
import android.content.Context;

import androidx.multidex.MultiDex;

import com.pgyer.pgyersdk.PgyerSDKManager;
import com.pgyer.pgyersdk.pgyerenum.Features;

public class App extends Application {
    public static final String TIANYIN = "tianyin";

    @Override
    public void onCreate() {
        super.onCreate();
    }

    @Override
    protected void attachBaseContext(Context base) {
        super.attachBaseContext(base);
        // Disabled Pgyer SDK to avoid API key errors
        // initPgyerSDK(this);
        MultiDex.install(this);
    }

    private static void initPgyerSDK( App application){
        new PgyerSDKManager.Init()
                .setContext(application) //设置上下问对象
                .start();
    }
}