package com.zeaze.tianyinwallpaper.service;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.PixelFormat;
import android.graphics.SurfaceTexture;
import android.media.MediaPlayer;
import android.net.Uri;
import android.opengl.EGL14;
import android.opengl.EGLConfig;
import android.opengl.EGLContext;
import android.opengl.EGLDisplay;
import android.opengl.EGLSurface;
import android.opengl.GLES11Ext;
import android.opengl.GLES20;
import android.opengl.GLUtils;
import android.opengl.Matrix;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.service.wallpaper.WallpaperService;
import android.util.Log;
import android.view.Surface;
import android.view.SurfaceHolder;

import com.alibaba.fastjson.JSON;
import com.zeaze.tianyinwallpaper.App;
import com.zeaze.tianyinwallpaper.model.TianYinWallpaperModel;
import com.zeaze.tianyinwallpaper.utils.FileUtil;

import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

public class TianYinWallpaperService extends WallpaperService {
    private final String TAG = "TianYinGL";
    public static final String PREF_AUTO_SWITCH_MODE = "autoSwitchMode";
    public static final String PREF_AUTO_SWITCH_INTERVAL_MINUTES = "autoSwitchIntervalMinutes";
    public static final String PREF_AUTO_SWITCH_TIME_POINTS = "autoSwitchTimePoints";
    public static final String PREF_AUTO_SWITCH_LAST_SWITCH_AT = "autoSwitchLastSwitchAt";
    public static final String PREF_AUTO_SWITCH_ANCHOR_AT = "autoSwitchAnchorAt";
    public static final String ACTION_AUTO_SWITCH_ALARM = "com.zeaze.tianyinwallpaper.AUTO_SWITCH_ALARM";
    public static final String ACTION_AUTO_SWITCH_ALARM_FIRED = "com.zeaze.tianyinwallpaper.AUTO_SWITCH_ALARM_FIRED";
    private static final int AUTO_SWITCH_MODE_NONE = 0;
    private static final int AUTO_SWITCH_MODE_INTERVAL = 1;
    private static final int AUTO_SWITCH_MODE_DAILY_POINTS = 2;

    @Override
    public Engine onCreateEngine() {
        return new TianYinSolaEngine();
    }

    class TianYinSolaEngine extends WallpaperService.Engine implements SurfaceTexture.OnFrameAvailableListener {
        private MediaPlayer mediaPlayer;
        private EglThread eglThread;
        private List<TianYinWallpaperModel> list;
        private volatile int index = -1;
        private float currentXOffset = 0.5f;
        private final AtomicBoolean initialLoadCompleted = new AtomicBoolean(false);
        private final AtomicBoolean updateSurface = new AtomicBoolean(false);
        private final Object wallpaperSwitchLock = new Object();
        private boolean isMediaPlayerPrepared = false; // 新增：标记 MediaPlayer 是否已准备

        // 滚动开关相关
        private SharedPreferences pref;
        private boolean wallpaperScrollEnabled = true;
        private AlarmManager alarmManager;
        private PendingIntent autoSwitchPendingIntent;
        private SharedPreferences.OnSharedPreferenceChangeListener prefChangeListener;
        private BroadcastReceiver stateReceiver;

        @Override
        public void onCreate(SurfaceHolder surfaceHolder) {
            super.onCreate(surfaceHolder);
            surfaceHolder.setFormat(PixelFormat.RGBX_8888);

            pref = getSharedPreferences(App.TIANYIN, MODE_PRIVATE);
            alarmManager = (AlarmManager) getSystemService(Context.ALARM_SERVICE);
            wallpaperScrollEnabled = pref.getBoolean("wallpaperScroll", true);
            prefChangeListener = (sharedPreferences, key) -> {
                if ("wallpaperScroll".equals(key)) {
                    wallpaperScrollEnabled = sharedPreferences.getBoolean(key, true);
                    if (eglThread != null) eglThread.requestRender();
                }
                if (PREF_AUTO_SWITCH_MODE.equals(key) || PREF_AUTO_SWITCH_INTERVAL_MINUTES.equals(key) || PREF_AUTO_SWITCH_TIME_POINTS.equals(key)) {
                    ensureAutoSwitchAnchor();
                    scheduleNextAutoSwitch("pref_changed");
                    maybeAdvanceWallpaperIfDue("pref_changed");
                }
            };
            pref.registerOnSharedPreferenceChangeListener(prefChangeListener);
            registerStateReceiver();
            ensureAutoSwitchAnchor();
            scheduleNextAutoSwitch("engine_create");
            maybeAdvanceWallpaperIfDue("engine_create");

            try {
                String s = FileUtil.loadData(getApplicationContext(), FileUtil.wallpaperPath);
                list = JSON.parseArray(s, TianYinWallpaperModel.class);
            } catch (Exception ignored) {}
            initialLoadCompleted.set(false);
        }

        @Override
        public void onSurfaceCreated(SurfaceHolder holder) {
            super.onSurfaceCreated(holder);
            if (eglThread != null) eglThread.finish();
            eglThread = new EglThread(holder);
            eglThread.start();
        }

        @Override
        public void onSurfaceChanged(SurfaceHolder holder, int format, int width, int height) {
            super.onSurfaceChanged(holder, format, width, height);
            if (eglThread != null) eglThread.onSizeChanged(width, height);
        }

        @Override
        public void onVisibilityChanged(boolean visible) {
            super.onVisibilityChanged(visible);
            if (visible) {
                // 可见时，如果 MediaPlayer 已准备则播放
                if (mediaPlayer != null && isMediaPlayerPrepared && !mediaPlayer.isPlaying()) {
                    mediaPlayer.start();
                }
                maybeAdvanceWallpaperIfDue("visible");
                scheduleNextAutoSwitch("visible");
                if (eglThread != null) eglThread.requestRender();
            } else {
                // 不可见时，暂停播放
                if (mediaPlayer != null && mediaPlayer.isPlaying()) {
                    mediaPlayer.pause();
                }
                // 仅在手动模式下才在返回桌面/锁屏时切换壁纸
                // 固定间隔和每日时间点模式依赖定时器和补偿机制
                if (initialLoadCompleted.get()) {
                    int mode = pref != null ? pref.getInt(PREF_AUTO_SWITCH_MODE, AUTO_SWITCH_MODE_NONE) : AUTO_SWITCH_MODE_NONE;
                    if (mode == AUTO_SWITCH_MODE_NONE) {
                        // 手动模式：在不可见时切换到下一张壁纸
                        new Handler(getMainLooper()).postDelayed(() -> nextWallpaper(), 100);
                    }
                    // 固定间隔模式和每日时间点模式：不在此处切换，由定时器控制
                }
                scheduleNextAutoSwitch("invisible");
            }
        }

        @Override
        public void onOffsetsChanged(float xOffset, float yOffset, float xOffsetStep,
                                     float yOffsetStep, int xPixelOffset, int yPixelOffset) {
            if (wallpaperScrollEnabled) {
                this.currentXOffset = xOffset;
            } else {
                this.currentXOffset = 0.5f;
            }
            if (eglThread != null) eglThread.requestRender();
        }

        private void nextWallpaper() {
            advanceWallpaperBy(1, true);
        }

        private void advanceWallpaperBy(int step, boolean persistLastSwitchTime) {
            if (list == null || list.isEmpty() || step <= 0) return;
            synchronized (wallpaperSwitchLock) {
                int size = list.size();
                index = ((index + (step % size)) % size + size) % size;
            }
            if (persistLastSwitchTime) {
                pref.edit().putLong(PREF_AUTO_SWITCH_LAST_SWITCH_AT, System.currentTimeMillis()).apply();
            }
            // 确保在 EGL 线程中加载内容
            if (eglThread != null) eglThread.postRunnable(this::loadContent);
        }

        private void loadContent() {
            if (index < 0 || index >= list.size()) return;
            TianYinWallpaperModel model = list.get(index);

            // 重置 MediaPlayer 状态
            if (mediaPlayer != null) {
                mediaPlayer.reset();
                isMediaPlayerPrepared = false;
            }

            if (model.getType() == 1) prepareVideo(model);
            else prepareImage(model);
        }

        private void prepareVideo(TianYinWallpaperModel model) {
            try {
                if (mediaPlayer == null) mediaPlayer = new MediaPlayer();
                mediaPlayer.reset();
                mediaPlayer.setDataSource(getApplicationContext(), Uri.parse(model.getVideoUri()));

                SurfaceTexture st = eglThread.getVideoST();
                if (st == null) return;

                Surface surface = new Surface(st);
                mediaPlayer.setSurface(surface);
                surface.release();

                mediaPlayer.setVolume(0, 0);

                if (model.isLoop()) {
                    mediaPlayer.setOnSeekCompleteListener(mp -> {
                        if (isVisible()) {
                            mp.start();
                        }
                    });
                    mediaPlayer.setOnCompletionListener(mp -> {
                        try {
                            mp.seekTo(0);
                        } catch (IllegalStateException e) {
                            Log.w(TAG, "seekTo(0) failed on loop completion", e);
                        }
                    });
                } else {
                    mediaPlayer.setOnSeekCompleteListener(null);
                    mediaPlayer.setOnCompletionListener(mp ->
                            new Handler(getMainLooper()).post(() -> nextWallpaper())
                    );
                }

                // 添加错误监听器
                mediaPlayer.setOnErrorListener((mp, what, extra) -> {
                    Log.e(TAG, "MediaPlayer error: what=" + what + ", extra=" + extra);
                    isMediaPlayerPrepared = false;
                    // 切换到下一张壁纸
                    new Handler(getMainLooper()).post(() -> nextWallpaper());
                    return true;
                });

                mediaPlayer.setOnInfoListener((mp, what, extra) -> {
                    if (what == MediaPlayer.MEDIA_INFO_VIDEO_RENDERING_START) {
                        // 视频开始渲染，可以标记就绪
                        isMediaPlayerPrepared = true;
                    }
                    return false;
                });

                mediaPlayer.setOnPreparedListener(mp -> {
                    int w = mp.getVideoWidth();
                    int h = mp.getVideoHeight();
                    eglThread.setContentSize(w, h);
                    SurfaceTexture videoST = eglThread.getVideoST();
                    if (videoST != null) {
                        videoST.setDefaultBufferSize(w, h);
                    }
                    isMediaPlayerPrepared = true;
                    // 如果当前可见，立即播放
                    if (isVisible()) {
                        mp.start();
                    }
                    markInitialLoadComplete();
                });
                mediaPlayer.prepareAsync();
                eglThread.resetVideoMatrix(); // 切换视频时重置矩阵
            } catch (Exception e) {
                Log.e(TAG, "Video error", e);
                // 加载失败则尝试下一张
                markInitialLoadComplete();
                new Handler(getMainLooper()).post(() -> nextWallpaper());
            }
        }

        private void prepareImage(TianYinWallpaperModel model) {
            if (mediaPlayer != null) {
                mediaPlayer.reset();
                isMediaPlayerPrepared = false;
            }
            try {
                InputStream is = getApplicationContext().getContentResolver().openInputStream(Uri.parse(model.getImgUri()));
                Bitmap bitmap = BitmapFactory.decodeStream(is);
                is.close();
                if (bitmap != null) {
                    eglThread.setContentSize(bitmap.getWidth(), bitmap.getHeight());
                    eglThread.uploadBitmap(bitmap);
                    markInitialLoadComplete();
                } else {
                    // Image decode failed, skip
                    markInitialLoadComplete();
                    new Handler(getMainLooper()).post(() -> nextWallpaper());
                }
            } catch (Exception e) {
                Log.e(TAG, "Image error", e);
                markInitialLoadComplete();
                new Handler(getMainLooper()).post(() -> nextWallpaper());
            }
        }

        private void markInitialLoadComplete() {
            initialLoadCompleted.set(true);
        }

        @Override
        public void onFrameAvailable(SurfaceTexture surfaceTexture) {
            updateSurface.set(true);
            if (eglThread != null) eglThread.requestRender();
        }

        @Override
        public void onDestroy() {
            super.onDestroy();
            cancelAutoSwitchAlarm();
            if (stateReceiver != null) {
                try {
                    unregisterReceiver(stateReceiver);
                } catch (Exception ignored) {}
                stateReceiver = null;
            }
            if (pref != null && prefChangeListener != null) {
                pref.unregisterOnSharedPreferenceChangeListener(prefChangeListener);
                prefChangeListener = null;
            }
            if (mediaPlayer != null) {
                mediaPlayer.release();
                mediaPlayer = null;
            }
            if (eglThread != null) eglThread.finish();
        }

        private void registerStateReceiver() {
            stateReceiver = new BroadcastReceiver() {
                @Override
                public void onReceive(Context context, Intent intent) {
                    if (intent == null || intent.getAction() == null) return;
                    String action = intent.getAction();
                    if (Intent.ACTION_SCREEN_ON.equals(action) || Intent.ACTION_USER_PRESENT.equals(action) || ACTION_AUTO_SWITCH_ALARM_FIRED.equals(action)) {
                        maybeAdvanceWallpaperIfDue(action);
                        scheduleNextAutoSwitch(action);
                    }
                }
            };
            IntentFilter filter = new IntentFilter();
            filter.addAction(Intent.ACTION_SCREEN_ON);
            filter.addAction(Intent.ACTION_USER_PRESENT);
            filter.addAction(ACTION_AUTO_SWITCH_ALARM_FIRED);
            registerReceiver(stateReceiver, filter);
        }

        private void ensureAutoSwitchAnchor() {
            if (pref == null) return;
            int mode = pref.getInt(PREF_AUTO_SWITCH_MODE, AUTO_SWITCH_MODE_NONE);
            if (mode == AUTO_SWITCH_MODE_NONE) return;
            long anchorAt = pref.getLong(PREF_AUTO_SWITCH_ANCHOR_AT, 0L);
            if (anchorAt <= 0L) {
                pref.edit().putLong(PREF_AUTO_SWITCH_ANCHOR_AT, System.currentTimeMillis()).apply();
            }
        }

        private void maybeAdvanceWallpaperIfDue(String reason) {
            if (pref == null || list == null || list.isEmpty()) return;
            int mode = pref.getInt(PREF_AUTO_SWITCH_MODE, AUTO_SWITCH_MODE_NONE);
            if (mode == AUTO_SWITCH_MODE_NONE) return;
            long now = System.currentTimeMillis();
            long lastSwitchAt = pref.getLong(PREF_AUTO_SWITCH_LAST_SWITCH_AT, 0L);
            long anchorAt = pref.getLong(PREF_AUTO_SWITCH_ANCHOR_AT, now);
            if (anchorAt <= 0L) anchorAt = now;

            int dueCount = 0;
            long newLastSwitchAt = lastSwitchAt;
            if (mode == AUTO_SWITCH_MODE_INTERVAL) {
                long intervalMs = Math.max(1L, pref.getLong(PREF_AUTO_SWITCH_INTERVAL_MINUTES, 60L)) * 60_000L;
                long baseAt = lastSwitchAt > 0L ? lastSwitchAt : anchorAt;
                if (now > baseAt) {
                    dueCount = (int) ((now - baseAt) / intervalMs);
                    newLastSwitchAt = baseAt + (dueCount * intervalMs);
                }
            } else if (mode == AUTO_SWITCH_MODE_DAILY_POINTS) {
                List<Integer> points = parseTimePointsToMinutes(pref.getString(PREF_AUTO_SWITCH_TIME_POINTS, ""));
                long from = lastSwitchAt > 0L ? lastSwitchAt : anchorAt;
                dueCount = countDailyTriggers(from, now, points);
                if (dueCount > 0) {
                    newLastSwitchAt = now;
                }
            }

            if (dueCount <= 0) return;
            int step = dueCount % list.size();
            if (step == 0 && dueCount > 0) {
                // Completed full rotation(s); no visual change is needed, only update time baseline.
                pref.edit().putLong(PREF_AUTO_SWITCH_LAST_SWITCH_AT, newLastSwitchAt).apply();
                return;
            }
            Log.d(TAG, "Auto switch due count=" + dueCount + ", reason=" + reason);
            advanceWallpaperBy(step, false);
            pref.edit()
                    .putLong(PREF_AUTO_SWITCH_LAST_SWITCH_AT, newLastSwitchAt)
                    .apply();
        }

        private void scheduleNextAutoSwitch(String reason) {
            if (pref == null || alarmManager == null) return;
            cancelAutoSwitchAlarm();
            int mode = pref.getInt(PREF_AUTO_SWITCH_MODE, AUTO_SWITCH_MODE_NONE);
            if (mode == AUTO_SWITCH_MODE_NONE) return;
            long now = System.currentTimeMillis();
            long triggerAt = computeNextTriggerAt(now, mode);
            if (triggerAt <= now) return;

            Intent intent = new Intent(getApplicationContext(), AutoSwitchAlarmReceiver.class);
            intent.setAction(ACTION_AUTO_SWITCH_ALARM);
            autoSwitchPendingIntent = PendingIntent.getBroadcast(
                    getApplicationContext(),
                    1001,
                    intent,
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
            );
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    boolean canUseExactAlarm = true;
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                        try {
                            canUseExactAlarm = alarmManager.canScheduleExactAlarms();
                        } catch (SecurityException e) {
                            canUseExactAlarm = false;
                        }
                    }
                    if (canUseExactAlarm) {
                        alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, autoSwitchPendingIntent);
                    } else {
                        Log.w(TAG, "Exact alarm permission unavailable, fallback to inexact alarm.");
                        alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, autoSwitchPendingIntent);
                    }
                } else {
                    alarmManager.setExact(AlarmManager.RTC_WAKEUP, triggerAt, autoSwitchPendingIntent);
                }
                Log.d(TAG, "scheduleNextAutoSwitch(" + reason + "), triggerAt=" + triggerAt);
            } catch (SecurityException e) {
                Log.w(TAG, "Exact alarm unavailable, fallback to set()", e);
                alarmManager.set(AlarmManager.RTC_WAKEUP, triggerAt, autoSwitchPendingIntent);
            }
        }

        private long computeNextTriggerAt(long now, int mode) {
            long lastSwitchAt = pref.getLong(PREF_AUTO_SWITCH_LAST_SWITCH_AT, 0L);
            long anchorAt = pref.getLong(PREF_AUTO_SWITCH_ANCHOR_AT, now);
            if (anchorAt <= 0L) anchorAt = now;

            if (mode == AUTO_SWITCH_MODE_INTERVAL) {
                long intervalMs = Math.max(1L, pref.getLong(PREF_AUTO_SWITCH_INTERVAL_MINUTES, 60L)) * 60_000L;
                long baseAt = lastSwitchAt > 0L ? lastSwitchAt : anchorAt;
                long next = baseAt + intervalMs;
                while (next <= now) next += intervalMs;
                return next;
            }
            if (mode == AUTO_SWITCH_MODE_DAILY_POINTS) {
                List<Integer> points = parseTimePointsToMinutes(pref.getString(PREF_AUTO_SWITCH_TIME_POINTS, ""));
                if (points.isEmpty()) return -1L;
                Calendar calendar = Calendar.getInstance();
                calendar.setTimeInMillis(now);
                int nowMinute = calendar.get(Calendar.HOUR_OF_DAY) * 60 + calendar.get(Calendar.MINUTE);
                for (int minute : points) {
                    if (minute > nowMinute) {
                        Calendar target = Calendar.getInstance();
                        target.setTimeInMillis(now);
                        target.set(Calendar.HOUR_OF_DAY, minute / 60);
                        target.set(Calendar.MINUTE, minute % 60);
                        target.set(Calendar.SECOND, 0);
                        target.set(Calendar.MILLISECOND, 0);
                        return target.getTimeInMillis();
                    }
                }
                Calendar tomorrow = Calendar.getInstance();
                tomorrow.setTimeInMillis(now);
                tomorrow.add(Calendar.DAY_OF_YEAR, 1);
                tomorrow.set(Calendar.HOUR_OF_DAY, points.get(0) / 60);
                tomorrow.set(Calendar.MINUTE, points.get(0) % 60);
                tomorrow.set(Calendar.SECOND, 0);
                tomorrow.set(Calendar.MILLISECOND, 0);
                return tomorrow.getTimeInMillis();
            }
            return -1L;
        }

        private int countDailyTriggers(long fromExclusive, long toInclusive, List<Integer> points) {
            if (points == null || points.isEmpty() || toInclusive <= fromExclusive) return 0;
            Calendar startDay = Calendar.getInstance();
            startDay.setTimeInMillis(fromExclusive);
            startDay.set(Calendar.HOUR_OF_DAY, 0);
            startDay.set(Calendar.MINUTE, 0);
            startDay.set(Calendar.SECOND, 0);
            startDay.set(Calendar.MILLISECOND, 0);

            Calendar endDay = Calendar.getInstance();
            endDay.setTimeInMillis(toInclusive);
            endDay.set(Calendar.HOUR_OF_DAY, 0);
            endDay.set(Calendar.MINUTE, 0);
            endDay.set(Calendar.SECOND, 0);
            endDay.set(Calendar.MILLISECOND, 0);

            int count = 0;
            Calendar cursor = (Calendar) startDay.clone();
            while (!cursor.after(endDay)) {
                for (int minute : points) {
                    Calendar trigger = (Calendar) cursor.clone();
                    trigger.set(Calendar.HOUR_OF_DAY, minute / 60);
                    trigger.set(Calendar.MINUTE, minute % 60);
                    long triggerAt = trigger.getTimeInMillis();
                    if (triggerAt > fromExclusive && triggerAt <= toInclusive) {
                        count++;
                    }
                }
                cursor.add(Calendar.DAY_OF_YEAR, 1);
            }
            return count;
        }

        private List<Integer> parseTimePointsToMinutes(String pointsRaw) {
            if (pointsRaw == null || pointsRaw.trim().isEmpty()) return Collections.emptyList();
            String[] items = pointsRaw.split(",");
            List<Integer> points = new ArrayList<>();
            for (String item : items) {
                String t = item.trim();
                if (!t.matches("^([01]?\\d|2[0-3]):[0-5]\\d$")) continue;
                String[] hm = t.split(":");
                int hour = Integer.parseInt(hm[0]);
                int minute = Integer.parseInt(hm[1]);
                points.add(hour * 60 + minute);
            }
            Collections.sort(points);
            return points;
        }

        private void cancelAutoSwitchAlarm() {
            if (alarmManager != null && autoSwitchPendingIntent != null) {
                alarmManager.cancel(autoSwitchPendingIntent);
                autoSwitchPendingIntent.cancel();
                autoSwitchPendingIntent = null;
            }
        }

        // --- EGL 渲染线程（保持不变，与上一版相同）---
        private class EglThread extends HandlerThread {
            private final SurfaceHolder holder;
            private EGLDisplay display = EGL14.EGL_NO_DISPLAY;
            private EGLContext context = EGL14.EGL_NO_CONTEXT;
            private EGLSurface eglSurface = EGL14.EGL_NO_SURFACE;
            private Handler handler;
            private SurfaceTexture videoST;
            private int vTexId, iTexId, vProg, iProg;
            private FloatBuffer vBuf, tBuf;
            private int sW, sH, cW = 1, cH = 1;
            private final float[] videoSTMatrix = new float[16];
            private final float[] imageMatrix = new float[16];

            public EglThread(SurfaceHolder holder) {
                super("TianYinEGL");
                this.holder = holder;
                float[] vData = {-1,-1, 1,-1, -1,1, 1,1};
                vBuf = ByteBuffer.allocateDirect(vData.length*4).order(ByteOrder.nativeOrder()).asFloatBuffer().put(vData);
                vBuf.position(0);
                float[] tData = {0,0, 1,0, 0,1, 1,1};
                tBuf = ByteBuffer.allocateDirect(tData.length*4).order(ByteOrder.nativeOrder()).asFloatBuffer().put(tData);
                tBuf.position(0);

                Matrix.setIdentityM(imageMatrix, 0);
                Matrix.translateM(imageMatrix, 0, 0, 1, 0);
                Matrix.scaleM(imageMatrix, 0, 1, -1, 1);
            }

            public void onSizeChanged(int w, int h) { sW = w; sH = h; requestRender(); }
            public void setContentSize(int w, int h) { cW = w > 0 ? w : 1; cH = h > 0 ? h : 1; }
            public SurfaceTexture getVideoST() { return videoST; }
            public void postRunnable(Runnable r) { if (handler != null) handler.post(r); }
            public void resetVideoMatrix() { postRunnable(() -> Matrix.setIdentityM(videoSTMatrix, 0)); }

            @Override
            protected void onLooperPrepared() {
                if (!initEGL()) return;
                initGL();
                handler = new Handler(getLooper());
                postRunnable(() -> {
                    if (index == -1) nextWallpaper();
                    else loadContent();
                });
            }

            private boolean initEGL() {
                display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY);
                int[] version = new int[2];
                EGL14.eglInitialize(display, version, 0, version, 1);
                int[] attr = {
                        EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8, EGL14.EGL_BLUE_SIZE, 8,
                        EGL14.EGL_ALPHA_SIZE, 8,
                        EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
                        EGL14.EGL_NONE
                };
                EGLConfig[] configs = new EGLConfig[1];
                int[] numConfigs = new int[1];
                EGL14.eglChooseConfig(display, attr, 0, configs, 0, 1, numConfigs, 0);
                context = EGL14.eglCreateContext(display, configs[0], EGL14.EGL_NO_CONTEXT,
                        new int[]{EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE}, 0);
                eglSurface = EGL14.eglCreateWindowSurface(display, configs[0], holder.getSurface(),
                        new int[]{EGL14.EGL_NONE}, 0);
                return EGL14.eglMakeCurrent(display, eglSurface, eglSurface, context);
            }

            private void initGL() {
                String vs = "attribute vec4 aPos; attribute vec2 aTex; varying vec2 vTex; uniform mat4 uMVP; uniform mat4 uST; void main(){ gl_Position = uMVP * aPos; vTex = (uST * vec4(aTex,0,1)).xy; }";
                String fsV = "#extension GL_OES_EGL_image_external : require\n precision mediump float; varying vec2 vTex; uniform samplerExternalOES sTex; void main(){ gl_FragColor = texture2D(sTex, vTex); }";
                String fsI = "precision mediump float; varying vec2 vTex; uniform sampler2D sTex; void main(){ gl_FragColor = texture2D(sTex, vTex); }";
                vProg = createProg(vs, fsV);
                iProg = createProg(vs, fsI);

                int[] tex = new int[2];
                GLES20.glGenTextures(2, tex, 0);
                vTexId = tex[0]; iTexId = tex[1];

                GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, vTexId);
                GLES20.glTexParameterf(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR);
                videoST = new SurfaceTexture(vTexId);
                videoST.setOnFrameAvailableListener(TianYinSolaEngine.this);

                GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, iTexId);
                GLES20.glTexParameterf(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR);

                Matrix.setIdentityM(videoSTMatrix, 0);
            }

            public void uploadBitmap(Bitmap b) {
                postRunnable(() -> {
                    GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, iTexId);
                    GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, b, 0);
                    b.recycle();
                    requestRender();
                });
            }

            public void requestRender() {
                if (handler != null) {
                    handler.removeCallbacks(drawRunnable);
                    handler.post(drawRunnable);
                }
            }

            private final Runnable drawRunnable = this::draw;

            private void draw() {
                if (eglSurface == EGL14.EGL_NO_SURFACE) return;
                EGL14.eglMakeCurrent(display, eglSurface, eglSurface, context);

                boolean isVid = (list != null && index >= 0 && index < list.size() && list.get(index).getType() == 1);
                float[] stMat = new float[16];

                if (isVid) {
                    if (updateSurface.getAndSet(false)) {
                        try {
                            videoST.updateTexImage();
                            videoST.getTransformMatrix(videoSTMatrix);
                        } catch (Exception e) {
                            Log.w(TAG, "updateTexImage failed, using old frame", e);
                        }
                    }
                    System.arraycopy(videoSTMatrix, 0, stMat, 0, 16);
                } else {
                    System.arraycopy(imageMatrix, 0, stMat, 0, 16);
                }

                GLES20.glViewport(0, 0, sW, sH);
                GLES20.glClearColor(0.01f, 0.01f, 0.01f, 1.0f);
                GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);

                int prog = isVid ? vProg : iProg;
                GLES20.glUseProgram(prog);

                float[] mvp = new float[16];
                Matrix.setIdentityM(mvp, 0);
                float cAsp = (float) cW / cH;
                float sAsp = (float) sW / sH;

                if (cAsp > sAsp) {
                    float scale = cAsp / sAsp;
                    float tx = (scale - 1.0f) * (1.0f - currentXOffset * 2.0f);
                    Matrix.scaleM(mvp, 0, scale, 1.0f, 1.0f);
                    Matrix.translateM(mvp, 0, tx / scale, 0, 0);
                } else {
                    Matrix.scaleM(mvp, 0, 1.0f, sAsp / cAsp, 1.0f);
                }

                int aPos = GLES20.glGetAttribLocation(prog, "aPos");
                int aTex = GLES20.glGetAttribLocation(prog, "aTex");
                GLES20.glEnableVertexAttribArray(aPos);
                GLES20.glVertexAttribPointer(aPos, 2, GLES20.GL_FLOAT, false, 8, vBuf);
                GLES20.glEnableVertexAttribArray(aTex);
                GLES20.glVertexAttribPointer(aTex, 2, GLES20.GL_FLOAT, false, 8, tBuf);

                GLES20.glUniformMatrix4fv(GLES20.glGetUniformLocation(prog, "uMVP"), 1, false, mvp, 0);
                GLES20.glUniformMatrix4fv(GLES20.glGetUniformLocation(prog, "uST"), 1, false, stMat, 0);

                GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
                GLES20.glBindTexture(isVid ? GLES11Ext.GL_TEXTURE_EXTERNAL_OES : GLES20.GL_TEXTURE_2D, isVid ? vTexId : iTexId);
                GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);

                if (!EGL14.eglSwapBuffers(display, eglSurface)) {
                    Log.e(TAG, "SwapBuffers failed");
                }
            }

            private int createProg(String v, String f) {
                int vs = GLES20.glCreateShader(GLES20.GL_VERTEX_SHADER);
                GLES20.glShaderSource(vs, v); GLES20.glCompileShader(vs);
                int fs = GLES20.glCreateShader(GLES20.GL_FRAGMENT_SHADER);
                GLES20.glShaderSource(fs, f); GLES20.glCompileShader(fs);
                int p = GLES20.glCreateProgram();
                GLES20.glAttachShader(p, vs); GLES20.glAttachShader(p, fs);
                GLES20.glLinkProgram(p); return p;
            }

            public void finish() {
                quitSafely();
                if (display != EGL14.EGL_NO_DISPLAY) {
                    EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT);
                    EGL14.eglDestroySurface(display, eglSurface);
                    EGL14.eglDestroyContext(display, context);
                    EGL14.eglTerminate(display);
                }
            }
        }
    }
}
