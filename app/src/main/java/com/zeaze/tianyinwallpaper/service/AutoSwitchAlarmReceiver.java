package com.zeaze.tianyinwallpaper.service;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

public class AutoSwitchAlarmReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null || !TianYinWallpaperService.ACTION_AUTO_SWITCH_ALARM.equals(intent.getAction())) {
            return;
        }
        Intent notifyIntent = new Intent(TianYinWallpaperService.ACTION_AUTO_SWITCH_ALARM_FIRED);
        notifyIntent.setPackage(context.getPackageName());
        context.sendBroadcast(notifyIntent);
    }
}
