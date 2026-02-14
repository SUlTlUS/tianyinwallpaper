package com.zeaze.tianyinwallpaper.service;

import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.media.MediaPlayer;
import android.net.Uri;
import android.service.wallpaper.WallpaperService;
import android.view.SurfaceHolder;

import com.alibaba.fastjson.JSON;
import com.zeaze.tianyinwallpaper.App;
import com.zeaze.tianyinwallpaper.model.TianYinWallpaperModel;
import com.zeaze.tianyinwallpaper.utils.FileUtil;

import java.io.IOException;
import java.io.InputStream;
import java.util.Calendar;
import java.util.Date;
import java.util.List;

public class TianYinWallpaperService extends WallpaperService {
    String TAG="TianYinSolaWallpaperService";
    private SharedPreferences pref;
    private long lastTime = 0;

    private boolean isOnlyOne=false;
    private boolean needBackgroundPlay=false;
    private boolean wallpaperScroll=false;
    
    // Wallpaper type constants
    private static final int WALLPAPER_TYPE_IMAGE = 0;
    private static final int WALLPAPER_TYPE_VIDEO = 1;

    @Override
    public Engine onCreateEngine() {
        return new TianYinSolaEngine();
    }

    class TianYinSolaEngine extends WallpaperService.Engine {
        private MediaPlayer mediaPlayer;
        private Paint mPaint;
        private List<TianYinWallpaperModel> list;
        private int index=-1;
        private SurfaceHolder surfaceHolder;
        private boolean hasVideo;

        private boolean pageChange=false;
        private float currentXOffset=0f;
        
        public TianYinSolaEngine(){
            this.mPaint = new Paint();
            String s= FileUtil.loadData(getApplicationContext(),FileUtil.wallpaperPath);
            list= JSON.parseArray(s, TianYinWallpaperModel.class);
            hasVideo=true;
            lastTime = System.currentTimeMillis()/1000;
            pref = getSharedPreferences(App.TIANYIN,MODE_PRIVATE);
            pageChange = pref.getBoolean("pageChange",false);
            needBackgroundPlay = pref.getBoolean("needBackgroundPlay",false);
            wallpaperScroll = pref.getBoolean("wallpaperScroll",false);
        }
        
        // Helper method to check if current wallpaper is a video
        private boolean isCurrentWallpaperVideo() {
            return list != null && index >= 0 && index < list.size() && list.get(index).getType() == WALLPAPER_TYPE_VIDEO;
        }
        
        // Helper method to check if current wallpaper is a static image
        private boolean isCurrentWallpaperImage() {
            return list != null && index >= 0 && index < list.size() && list.get(index).getType() == WALLPAPER_TYPE_IMAGE;
        }

        private boolean getNextIndex(){
            isOnlyOne=false;
            if (index!=-1) {
                int minTime = pref.getInt("minTime", 1);
                if (System.currentTimeMillis() / 1000 - lastTime <= minTime) {
                    isOnlyOne=true;
                    return false;
                }
            }
            lastTime=System.currentTimeMillis()/1000;
            boolean isRand = pref.getBoolean("rand",false);
            int i = 1;
            if (isRand){
                i=(int)(Math.random()*list.size())+1;
            }
            int lastIndex = index;
            while (i>0) {
                if (index == -1) index = list.size() - 1;
                index = getIfIndex();
                if (index == lastIndex){
                    if (index == -1) index = list.size() - 1;
                    index = getIfIndex();
                }
                i = i - 1;
            }
            if (lastIndex==index){
                isOnlyOne=true;
            }
            return true;
        }

        private int getIfIndex(){
            int i=index+1;
            if (i>= list.size()){
                i=0;
            }
            while (!isIf(i)){
                if (i==index){
                    return getNoIfIndex();
                }
                i++;
                if (i>= list.size()){
                    i=0;
                }
            }
            return i;
        }

        private int getNoIfIndex(){
            int i=index+1;
            if (i>= list.size()){
                i=0;
            }
            while (!((list.get(i).getStartTime()==-1||list.get(i).getEndTime()==-1))){
                if (i==index){
                    i=index+1;
                    if (i>= list.size()){
                        i=0;
                    }
                    return i;
                }
                i++;
                if (i>= list.size()){
                    i=0;
                }
            }
            return i;
        }

        private boolean isIf(int index){
            TianYinWallpaperModel model=list.get(index);
            if (model.getStartTime()==-1||model.getEndTime()==-1){
                return false;
            }
            int now=getTime();
            if (model.getStartTime()==model.getEndTime()&&model.getStartTime()==now){
                return true;
            }
            if (model.getStartTime()<=now&&now<model.getEndTime()){
                return true;
            }
            if (now<model.getEndTime()&&model.getEndTime()<model.getStartTime()){
                return true;
            }
            if (model.getEndTime()<model.getStartTime()&&model.getStartTime()<=now){
                return true;
            }
            return false;
        }

        private int getTime(){
            Calendar cal = Calendar.getInstance();
            cal.setTime(new Date());
            int nowHour = cal.get(Calendar.HOUR_OF_DAY);
            int nowMin = cal.get(Calendar.MINUTE);
            return nowHour*60+nowMin;
        }

        @Override
        public void onSurfaceCreated(SurfaceHolder holder) {
            super.onSurfaceCreated(holder);
            surfaceHolder = holder;
            // 不要在这里创建MediaPlayer，等真正需要视频时才创建
            // 并且不要设置surface，等视频准备时再设置
        }

        @Override
        public void onSurfaceChanged(SurfaceHolder holder, int format, int width, int height) {
            super.onSurfaceChanged(holder, format, width, height);
            // 如果已经有MediaPlayer，需要重新设置surface
            if (mediaPlayer != null) {
                mediaPlayer.setSurface(holder.getSurface());
            }
        }

        Bitmap bitmap;
        private void setWallpaper(){
            setWallpaper(true);
        }
        
        private void setWallpaper(boolean reloadBitmap){
            if (surfaceHolder == null) return;
            
            Canvas localCanvas = null;
            try {
                localCanvas = surfaceHolder.lockCanvas();
                if (localCanvas != null) {
                    localCanvas.drawColor(Color.WHITE);
                    
                    // 如果是视频壁纸且mediaPlayer正在播放，应该显示视频
                    if (isCurrentWallpaperVideo() && mediaPlayer != null && mediaPlayer.isPlaying()) {
                        // 视频内容会通过Surface自动显示，不需要额外绘制
                        // 但为了防止白屏，我们可以不绘制任何东西，或者绘制黑色背景
                        localCanvas.drawColor(Color.BLACK);
                    } else {
                        // 图片壁纸或视频未播放时显示图片
                        if (reloadBitmap) {
                            if (bitmap != null){
                                bitmap.recycle();
                            }
                            bitmap = getBitmap();
                        }
                        
                        // Only apply scrolling to static image wallpapers (type 0)
                        if (bitmap != null && wallpaperScroll && isCurrentWallpaperImage()) {
                            int canvasWidth = localCanvas.getWidth();
                            int canvasHeight = localCanvas.getHeight();
                            int bitmapWidth = bitmap.getWidth();
                            int bitmapHeight = bitmap.getHeight();
                            
                            // Calculate scaled dimensions to fit height
                            float bitmapAspect = (float) bitmapWidth / bitmapHeight;
                            int scaledWidth = (int) (canvasHeight * bitmapAspect);
                            
                            // Only apply scrolling if bitmap is wider than screen
                            if (scaledWidth > canvasWidth) {
                                // Calculate horizontal offset based on xOffset (0.0 to 1.0)
                                int maxOffset = scaledWidth - canvasWidth;
                                int xOffset = (int) (maxOffset * currentXOffset);
                                
                                // Create source and destination rectangles
                                Rect srcRect = new Rect();
                                srcRect.left = (int) ((float) xOffset * bitmapWidth / scaledWidth);
                                srcRect.top = 0;
                                srcRect.right = (int) (((float) xOffset + canvasWidth) * bitmapWidth / scaledWidth);
                                srcRect.bottom = bitmapHeight;
                                
                                Rect dstRect = new Rect();
                                dstRect.left = 0;
                                dstRect.top = 0;
                                dstRect.bottom = canvasHeight;
                                dstRect.right = canvasWidth;
                                
                                localCanvas.drawBitmap(bitmap, srcRect, dstRect, this.mPaint);
                            } else {
                                // Bitmap is not wider than screen, draw normally
                                Rect rect = new Rect();
                                rect.left = rect.top = 0;
                                rect.bottom = canvasHeight;
                                rect.right = canvasWidth;
                                localCanvas.drawBitmap(bitmap, null, rect, this.mPaint);
                            }
                        } else {
                            // Scrolling disabled or not a static image wallpaper, draw normally
                            if (bitmap != null) {
                                Rect rect = new Rect();
                                rect.left = rect.top = 0;
                                rect.bottom = localCanvas.getHeight();
                                rect.right = localCanvas.getWidth();
                                localCanvas.drawBitmap(bitmap, null, rect, this.mPaint);
                            }
                        }
                    }
                }
            } catch (Exception e) {
                e.printStackTrace();
            } finally {
                if (localCanvas != null && surfaceHolder != null) {
                    try {
                        surfaceHolder.unlockCanvasAndPost(localCanvas);
                    } catch (Exception e) {
                        e.printStackTrace();
                    }
                }
            }
        }

        private void setLiveWallpaper() {
            try {
                // 释放旧的MediaPlayer
                releaseMediaPlayer();
                
                // 创建新的MediaPlayer
                mediaPlayer = new MediaPlayer();
                
                // 设置Surface
                if (surfaceHolder != null && surfaceHolder.getSurface() != null && surfaceHolder.getSurface().isValid()) {
                    mediaPlayer.setSurface(surfaceHolder.getSurface());
                }
                
                TianYinWallpaperModel currentModel = list.get(index);
                
                // Use URI if available, otherwise fall back to file path
                if (currentModel.getVideoUri() != null && !currentModel.getVideoUri().isEmpty()) {
                    mediaPlayer.setDataSource(getApplicationContext(), Uri.parse(currentModel.getVideoUri()));
                } else {
                    mediaPlayer.setDataSource(currentModel.getVideoPath());
                }
                
                mediaPlayer.setOnPreparedListener(new MediaPlayer.OnPreparedListener() {
                    @Override
                    public void onPrepared(MediaPlayer mp) {
                        // Use individual wallpaper loop setting
                        mp.setLooping(currentModel.isLoop());
                        mp.setVolume(0, 0);
                        mp.start();
                        
                        // 视频准备好后，强制重绘一次以清除白色背景
                        setWallpaper(false);
                    }
                });
                
                mediaPlayer.setOnVideoSizeChangedListener(new MediaPlayer.OnVideoSizeChangedListener() {
                    @Override
                    public void onVideoSizeChanged(MediaPlayer mp, int width, int height) {
                        // 设置缩放模式
                        mp.setVideoScalingMode(MediaPlayer.VIDEO_SCALING_MODE_SCALE_TO_FIT_WITH_CROPPING);
                    }
                });
                
                mediaPlayer.setOnErrorListener(new MediaPlayer.OnErrorListener() {
                    @Override
                    public boolean onError(MediaPlayer mp, int what, int extra) {
                        // 出错时回退到图片
                        setWallpaper(true);
                        return true;
                    }
                });
                
                mediaPlayer.prepareAsync();
                
            } catch (IOException e) {
                e.printStackTrace();
                // 出错时回退到图片
                setWallpaper(true);
            }
        }

        private Bitmap getBitmap(){
            TianYinWallpaperModel currentModel = list.get(index);
            // Use URI if available, otherwise fall back to file path
            if (currentModel.getImgUri() != null && !currentModel.getImgUri().isEmpty()) {
                InputStream is = null;
                try {
                    is = getApplicationContext().getContentResolver().openInputStream(Uri.parse(currentModel.getImgUri()));
                    Bitmap bitmap = BitmapFactory.decodeStream(is);
                    if (bitmap == null) {
                        android.util.Log.e("TianYinWallpaperService", "Failed to decode bitmap from URI: " + currentModel.getImgUri());
                    } else {
                        return bitmap;
                    }
                } catch (Exception e) {
                    android.util.Log.e("TianYinWallpaperService", "Error reading bitmap from URI: " + currentModel.getImgUri(), e);
                } finally {
                    if (is != null) {
                        try {
                            is.close();
                        } catch (IOException e) {
                            android.util.Log.e("TianYinWallpaperService", "Error closing input stream", e);
                        }
                    }
                }
            }
            // Fall back to file path
            return BitmapFactory.decodeFile(currentModel.getImgPath());
        }

        int page=-1;
        @Override
        public void onOffsetsChanged(float xOffset, float yOffset, float xOffsetStep, float yOffsetStep, int xPixelOffset, int yPixelOffset) {
            super.onOffsetsChanged(xOffset, yOffset, xOffsetStep, yOffsetStep, xPixelOffset, yPixelOffset);
            
            // Handle wallpaper scrolling
            // Check if current wallpaper is static image (type 0) before applying scroll
            if (wallpaperScroll && isCurrentWallpaperImage()) {
                currentXOffset = xOffset;
                setWallpaper(false); // Don't reload bitmap, just redraw
            }
            
            // Handle page change detection
            if (!pageChange){
                return;
            }
            float dx=xOffset;
            while (dx>xOffsetStep){
                dx=dx-xOffset;
            }
            dx=dx/xOffsetStep;
            if (page==-1){
                if (dx<0.1||dx>0.9) {
                    page = Math.round(xOffset / xOffsetStep);
                }
                return;
            }
            if (dx<0.1||dx>0.9) {
                int newPage = Math.round(xOffset / xOffsetStep);
                if (newPage!=page){
                    lastTime=0;
                    onVisibilityChanged(false);
                    onVisibilityChanged(true);
                    page=newPage;
                }
            }
        }

        private long lastPlayTime;
        @Override
        public void onVisibilityChanged(boolean visible) {
            super.onVisibilityChanged(visible);
            if(visible){
                // Check if current wallpaper is video (type 1)
                if (isCurrentWallpaperVideo()) {
                    if (mediaPlayer == null) {
                        // 如果mediaPlayer为null，创建新的
                        setLiveWallpaper();
                    } else {
                        try {
                            // Use individual wallpaper loop setting
                            mediaPlayer.setLooping(list.get(index).isLoop());
                            if (!mediaPlayer.isPlaying()){
                                mediaPlayer.start();
                            }
                            if (isOnlyOne && lastPlayTime > 0 && needBackgroundPlay){
                                long nowTime = (lastPlayTime + System.currentTimeMillis() - lastTime * 1000) % (mediaPlayer.getDuration());
                                mediaPlayer.seekTo((int)nowTime);
                            }
                        } catch (Exception e) {
                            // 如果播放出错，重新创建
                            setLiveWallpaper();
                        }
                    }
                }
            } else {
                if (mediaPlayer != null){
                    try {
                        lastPlayTime = mediaPlayer.getCurrentPosition();
                    } catch (Exception e) {
                        e.printStackTrace();
                    }
                }
                
                if (getNextIndex()) {
                    // Check current wallpaper type to decide which method to call
                    if (isCurrentWallpaperVideo()) {
                        setLiveWallpaper();
                    } else {
                        this.setWallpaper();
                    }
                } else {
                    // Check if current wallpaper is video before pausing
                    if (isCurrentWallpaperVideo() && mediaPlayer != null) {
                        try {
                            mediaPlayer.setLooping(false);
                            mediaPlayer.pause();
                        } catch (Exception e) {
                            e.printStackTrace();
                        }
                    }
                }
            }
        }

        @Override
        public void onSurfaceDestroyed(SurfaceHolder holder) {
            super.onSurfaceDestroyed(holder);
            // 不在这里释放MediaPlayer，因为surface销毁时可能只是暂时不可见
            // 但需要清除surface引用
            if (mediaPlayer != null) {
                mediaPlayer.setSurface(null);
            }
        }

        @Override
        public void onDestroy() {
            super.onDestroy();
            releaseMediaPlayer();
            if (bitmap != null) {
                bitmap.recycle();
                bitmap = null;
            }
        }

        private void releaseMediaPlayer(){
            if (mediaPlayer != null) {
                try {
                    if (mediaPlayer.isPlaying()) {
                        mediaPlayer.stop();
                    }
                    mediaPlayer.reset();
                    mediaPlayer.release();
                } catch (Exception e) {
                    e.printStackTrace();
                }
                mediaPlayer = null;
            }
        }
    }
}
