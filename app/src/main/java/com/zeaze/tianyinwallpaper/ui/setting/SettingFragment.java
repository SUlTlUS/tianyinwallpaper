package com.zeaze.tianyinwallpaper.ui.setting;

import static android.content.Context.MODE_PRIVATE;

import android.content.DialogInterface;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Build;
import android.text.Html;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.TextUtils;
import android.text.method.LinkMovementMethod;
import android.text.style.URLSpan;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.TextView;

import androidx.appcompat.app.AlertDialog;

import com.zeaze.tianyinwallpaper.App;
import com.zeaze.tianyinwallpaper.R;
import com.zeaze.tianyinwallpaper.base.BaseFragment;
import com.zeaze.tianyinwallpaper.service.TianYinWallpaperService;

public class SettingFragment extends BaseFragment {
    private static final long DEFAULT_AUTO_SWITCH_INTERVAL_MINUTES = 60L;
    private static final String DEFAULT_AUTO_SWITCH_TIME_POINTS = "08:00,12:00,18:00,22:00";
    private static final int AUTO_SWITCH_MODE_NONE = 0;
    private static final String[] AUTO_SWITCH_MODE_ITEMS = new String[]{"手动切换", "按固定时间间隔切换", "按每日时间点切换"};
    private SharedPreferences pref;
    private SharedPreferences.Editor editor;

    @Override
    protected void init() {
        pref = getContext().getSharedPreferences(App.TIANYIN, MODE_PRIVATE);
        editor = pref.edit();
        bindWallpaperSetting();
        bindAboutInfo();
    }

    @Override
    protected int getLayout() {
        return R.layout.setting_fragment;
    }

    private void bindWallpaperSetting() {
        CheckBox checkBox = view.findViewById(R.id.checkBox);
        checkBox.setChecked(pref.getBoolean("rand", false));
        checkBox.setOnCheckedChangeListener((buttonView, isChecked) -> {
            editor.putBoolean("rand", isChecked);
            editor.apply();
        });

        CheckBox checkBox2 = view.findViewById(R.id.checkBox2);
        checkBox2.setChecked(pref.getBoolean("pageChange", false));
        checkBox2.setOnCheckedChangeListener((buttonView, isChecked) -> {
            editor.putBoolean("pageChange", isChecked);
            editor.apply();
        });

        CheckBox checkBox3 = view.findViewById(R.id.checkBox3);
        checkBox3.setChecked(pref.getBoolean("needBackgroundPlay", false));
        checkBox3.setOnCheckedChangeListener((buttonView, isChecked) -> {
            editor.putBoolean("needBackgroundPlay", isChecked);
            editor.apply();
        });

        CheckBox checkBox4 = view.findViewById(R.id.checkBox4);
        checkBox4.setChecked(pref.getBoolean("wallpaperScroll", false));
        checkBox4.setOnCheckedChangeListener((buttonView, isChecked) -> {
            editor.putBoolean("wallpaperScroll", isChecked);
            editor.apply();
        });

        TextView minTimeView = view.findViewById(R.id.tv);
        minTimeView.setText("壁纸最小切换时间:" + pref.getInt("minTime", 1) + "秒（点击修改）");
        minTimeView.setOnClickListener(v -> setMinTime(dialogInterface ->
                minTimeView.setText("壁纸最小切换时间:" + pref.getInt("minTime", 1) + "秒")));

        TextView autoSwitchModeView = view.findViewById(R.id.autoSwitchModeView);
        TextView autoSwitchIntervalView = view.findViewById(R.id.autoSwitchIntervalView);
        TextView autoSwitchPointsView = view.findViewById(R.id.autoSwitchPointsView);
        refreshAutoSwitchSettingView(autoSwitchModeView, autoSwitchIntervalView, autoSwitchPointsView);
        autoSwitchModeView.setOnClickListener(v -> showAutoSwitchModeDialog(() ->
                refreshAutoSwitchSettingView(autoSwitchModeView, autoSwitchIntervalView, autoSwitchPointsView)));
    }

    private void bindAboutInfo() {
        TextView about = view.findViewById(R.id.about);
        about.setMovementMethod(LinkMovementMethod.getInstance());
        String verName = "获取失败";
        try {
            verName = getActivity().getPackageManager().getPackageInfo(getActivity().getPackageName(), 0).versionName;
        } catch (PackageManager.NameNotFoundException e) {
            e.printStackTrace();
        }
        about.setText(getClickableHtml("天音壁纸是一个用来设置壁纸的软件>_< <br>\n" +
                "点击“增加壁纸”，可以增加当前壁纸组的壁纸<br>\n" +
                "点击“应用本组”，会把当前壁纸组设置为手机壁纸，每次进入桌面，都会更新显示壁纸组里的下一张壁纸<br>\n" +
                "点击右上角的齿轮，可以保存当前壁纸组<br>\n" +
                "齿轮里的“壁纸通用设置”，可以设置通用的壁纸切换方式<br>\n" +
                "目前支持顺序切换和随机切换，和最小切换时间<br>\n" +
                "最小切换时间的意思是在切换壁纸后，未达这个时间间隔的话是不会二次切换壁纸的<br>\n" +
                "齿轮里的“清空当前壁纸组”，可以方便的一键清空壁纸组来设置新的壁纸<br>\n" +
                "点击壁纸缩略图，可以选择删除壁纸或者设置壁纸显示的条件，长按可以调整顺序<br>\n" +
                "当满足条件时，会优先显示满足条件的壁纸，借此，可以设置早安壁纸，下班壁纸<br>\n" +
                "目前仅支持按时间设置条件，开始时间为闭区间，结束时间为开区间<br>\n" +
                "欢迎加入天音壁纸qq群,BUG和意见都可以提：<a href=\"https://jq.qq.com/?_wv=1027&k=vjcrjY7L\">722673402</a><br>\n" +
                "------<br>\n" +
                "项目开源地址：<a href=\"https://github.com/prpr12/tianyinwallpaper.git\">https://github.com/prpr12/tianyinwallpaper.git</a><br>\n" +
                "软件下载地址：<a href=\"https://www.pgyer.com/eEna\">https://www.pgyer.com/eEna</a><br>\n" +
                "------<br>\n" +
                "当前版本号：" + verName + "\n"));
    }

    private CharSequence getClickableHtml(String html) {
        Spanned spannedHtml;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            spannedHtml = Html.fromHtml(html, Html.FROM_HTML_MODE_COMPACT);
        } else {
            spannedHtml = Html.fromHtml(html);
        }
        SpannableStringBuilder clickableBuilder = new SpannableStringBuilder(spannedHtml);
        URLSpan[] urls = clickableBuilder.getSpans(0, spannedHtml.length(), URLSpan.class);
        if (urls.length == 0) {
            return html.replace("\\n", "\n").replace("\\r", "\r");
        }
        return clickableBuilder;
    }

    private void setMinTime(DialogInterface.OnDismissListener onDismissListener) {
        View editView = LayoutInflater.from(getContext()).inflate(R.layout.main_edit, null);
        final EditText et = editView.findViewById(R.id.tv);
        et.setText(pref.getInt("minTime", 1) + "");
        et.setHint("请输入整数");
        new AlertDialog.Builder(getContext())
                .setView(editView)
                .setNeutralButton("取消", null)
                .setPositiveButton("确定", (dialog, which) -> {
                    try {
                        int i = Integer.parseInt(et.getText().toString());
                        editor.putInt("minTime", i);
                        editor.apply();
                    } catch (Exception e) {
                        toast("请输入整数");
                    }
                })
                .setCancelable(false)
                .setOnDismissListener(onDismissListener)
                .show();
    }

    private void refreshAutoSwitchSettingView(TextView modeView, TextView intervalView, TextView pointsView) {
        int mode = pref.getInt(TianYinWallpaperService.PREF_AUTO_SWITCH_MODE, AUTO_SWITCH_MODE_NONE);
        String modeText = (mode >= AUTO_SWITCH_MODE_NONE && mode < AUTO_SWITCH_MODE_ITEMS.length)
                ? AUTO_SWITCH_MODE_ITEMS[mode]
                : AUTO_SWITCH_MODE_ITEMS[AUTO_SWITCH_MODE_NONE];
        modeView.setText("自动切换模式：" + modeText + "（点击修改）");
        intervalView.setText("自动切换间隔：" + pref.getLong(TianYinWallpaperService.PREF_AUTO_SWITCH_INTERVAL_MINUTES, DEFAULT_AUTO_SWITCH_INTERVAL_MINUTES) + "分钟（点击修改）");
        String points = pref.getString(TianYinWallpaperService.PREF_AUTO_SWITCH_TIME_POINTS, DEFAULT_AUTO_SWITCH_TIME_POINTS);
        points = TextUtils.isEmpty(points) ? DEFAULT_AUTO_SWITCH_TIME_POINTS : points;
        pointsView.setText("自动切换时间点：" + points + "（点击修改）");
    }

    private void showAutoSwitchModeDialog(Runnable onDismiss) {
        int checked = pref.getInt(TianYinWallpaperService.PREF_AUTO_SWITCH_MODE, AUTO_SWITCH_MODE_NONE);
        new AlertDialog.Builder(getContext())
                .setTitle("选择自动切换模式")
                .setSingleChoiceItems(AUTO_SWITCH_MODE_ITEMS, checked, (dialog, which) -> {
                    editor.putInt(TianYinWallpaperService.PREF_AUTO_SWITCH_MODE, which);
                    editor.putLong(TianYinWallpaperService.PREF_AUTO_SWITCH_ANCHOR_AT, System.currentTimeMillis());
                    editor.putLong(TianYinWallpaperService.PREF_AUTO_SWITCH_LAST_SWITCH_AT, 0L);
                    editor.apply();
                    dialog.dismiss();
                })
                .setOnDismissListener(dialog -> onDismiss.run())
                .show();
    }
}
