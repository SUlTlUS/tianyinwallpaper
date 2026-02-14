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
import android.os.Handler;
import android.os.Looper;
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
    private boolean autoScrollEnabled=false;

    @Override
    public Engine onCreateEngine() {
        return new TianYinSolaEngine();
    }

    class TianYinSolaEngine extends WallpaperService.Engine implements SharedPreferences.OnSharedPreferenceChangeListener {
        private MediaPlayer mediaPlayer;
        private Paint mPaint;
        private List<TianYinWallpaperModel> list;
        private int index=-1;
        private SurfaceHolder surfaceHolder;
        private boolean hasVideo;

        private boolean pageChange=false;
        private float currentXOffset=0f;
        
        // Auto-scroll RecyclerView-like horizontal scrolling
        private Handler autoScrollHandler;
        private Runnable autoScrollRunnable;
        private float carouselScrollOffset = 0f;
        private static final float SCROLL_SPEED = 1.5f; // pixels per frame
        private List<Bitmap> carouselBitmaps;
        private final Object carouselLock = new Object(); // Synchronization lock
        public TianYinSolaEngine(){
            this.mPaint = new Paint();
            // Add Paint flags for better bitmap rendering quality
            this.mPaint.setFilterBitmap(true);
            this.mPaint.setDither(true);
            this.mPaint.setAntiAlias(true);
            String s= FileUtil.loadData(getApplicationContext(),FileUtil.wallpaperPath);
            list= JSON.parseArray(s, TianYinWallpaperModel.class);
            hasVideo=true;
            lastTime = System.currentTimeMillis()/1000;
            pref = getSharedPreferences(App.TIANYIN,MODE_PRIVATE);
            pageChange = pref.getBoolean("pageChange",false);
            needBackgroundPlay = pref.getBoolean("needBackgroundPlay",false);
            wallpaperScroll = pref.getBoolean("wallpaperScroll",false);
            autoScrollEnabled = pref.getBoolean("autoScroll",false);
            
            // Register preference change listener
            pref.registerOnSharedPreferenceChangeListener(this);
            
            // Initialize auto-scroll Handler and Runnable
            autoScrollHandler = new Handler(Looper.getMainLooper());
            autoScrollRunnable = new Runnable() {
                @Override
                public void run() {
                    if (autoScrollEnabled && !hasVideo) {
                        // Update scroll offset for smooth animation
                        carouselScrollOffset += SCROLL_SPEED;
                        drawCarousel();
                        // Continue animation
                        autoScrollHandler.postDelayed(this, 16); // ~60 FPS
                    }
                }
            };
            
            carouselBitmaps = new java.util.ArrayList<>();
//            for (TianYinWallpaperModel model:list){
//                if (model.getType()==1){
//                    hasVideo=true;
//                    break;
//                }
//            }
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
            surfaceHolder=holder;
            if (hasVideo) {
                mediaPlayer = new MediaPlayer();
                mediaPlayer.setSurface(holder.getSurface());
            }
        }
        Bitmap bitmap;
        private void setWallpaper(){
            setWallpaper(true);
        }
        
        private void setWallpaper(boolean reloadBitmap){
            Canvas localCanvas=surfaceHolder.lockCanvas();
            if (localCanvas != null) {
                localCanvas.drawColor(Color.BLACK); // Use black background for better visual effect
                if (reloadBitmap) {
                    if (bitmap!=null){
                        bitmap.recycle();
                    }
                    bitmap=getBitmap();
                }
                
                if (bitmap != null && wallpaperScroll) {
                    int canvasWidth = localCanvas.getWidth();
                    int canvasHeight = localCanvas.getHeight();
                    int bitmapWidth = bitmap.getWidth();
                    int bitmapHeight = bitmap.getHeight();
                    
                    // Scale bitmap to fit screen height
                    float scale = (float) canvasHeight / bitmapHeight;
                    int scaledWidth = (int) (bitmapWidth * scale);
                    
                    // Only apply parallax scrolling if bitmap is wider than screen
                    if (scaledWidth > canvasWidth) {
                        // Calculate the full offset range
                        int maxOffset = scaledWidth - canvasWidth;
                        // Apply parallax factor: background moves slower than the scroll
                        // This creates depth perception - typical parallax uses 0.3-0.5 factor
                        float parallaxFactor = 0.3f;
                        float scrollRange = maxOffset * (1.0f - parallaxFactor);
                        float offsetX = scrollRange * currentXOffset;
                        
                        // Draw bitmap with offset
                        localCanvas.save();
                        localCanvas.scale(scale, scale);
                        localCanvas.drawBitmap(bitmap, -offsetX / scale, 0f, this.mPaint);
                        localCanvas.restore();
                    } else {
                        // Bitmap is not wider than screen, draw it scaled to fit without stretching
                        localCanvas.save();
                        localCanvas.scale(scale, scale);
                        float xPos = (canvasWidth / scale - bitmapWidth) / 2f;
                        localCanvas.drawBitmap(bitmap, xPos, 0f, this.mPaint);
                        localCanvas.restore();
                    }
                } else {
                    // Scrolling disabled, draw normally to fit screen
                    if (bitmap != null) {
                        Rect rect = new Rect();
                        rect.left = rect.top = 0;
                        rect.bottom = localCanvas.getHeight();
                        rect.right = localCanvas.getWidth();
                        localCanvas.drawBitmap(bitmap, null, rect, this.mPaint);
                    }
                }
                
                surfaceHolder.unlockCanvasAndPost(localCanvas);
            }
        }

        private void setLiveWallpaper() {
            try {
                mediaPlayer.reset();
                TianYinWallpaperModel currentModel = list.get(index);
                // Use URI if available, otherwise fall back to file path
                if (currentModel.getVideoUri() != null && !currentModel.getVideoUri().isEmpty()) {
                    mediaPlayer.setDataSource(getApplicationContext(), Uri.parse(currentModel.getVideoUri()));
                } else {
                    mediaPlayer.setDataSource(currentModel.getVideoPath());
                }
                mediaPlayer.prepare();
                // Use individual wallpaper loop setting
                mediaPlayer.setLooping(currentModel.isLoop());
                mediaPlayer.setVideoScalingMode(MediaPlayer.VIDEO_SCALING_MODE_SCALE_TO_FIT_WITH_CROPPING);
                mediaPlayer.setVolume(0,0);
                mediaPlayer.start();
            } catch (IOException e) {
                e.printStackTrace();
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
            
            // Handle wallpaper scrolling - only update offset and trigger redraw
            // Don't do heavy operations here as this is called on main thread
            if (wallpaperScroll && !hasVideo) {
                currentXOffset = xOffset;
                // Skip redraw in preview mode for performance
                if (!isPreview()) {
                    setWallpaper(false); // Don't reload bitmap, just redraw with new offset
                }
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
                // Start auto-scroll when wallpaper becomes visible
                if (autoScrollEnabled && !hasVideo) {
                    loadCarouselBitmaps();
                    autoScrollHandler.post(autoScrollRunnable);
                }
                
                if (hasVideo) {
                    if (mediaPlayer != null) {
                        if (index!=-1) {
                            // Use individual wallpaper loop setting
                            mediaPlayer.setLooping(list.get(index).isLoop());
                            if (!mediaPlayer.isPlaying()){
                                mediaPlayer.start();
                            }
                            if (isOnlyOne &&lastPlayTime>0&&needBackgroundPlay){
                                long nowTime=(lastPlayTime+System.currentTimeMillis()-lastTime*1000)%(mediaPlayer.getDuration());
                                mediaPlayer.seekTo((int)nowTime);
                            }
                        }
                    }
                }
            }else{
                // Stop auto-scroll when wallpaper is not visible
                if (autoScrollEnabled) {
                    autoScrollHandler.removeCallbacks(autoScrollRunnable);
                }
                
                if (mediaPlayer!=null){
                    lastPlayTime=mediaPlayer.getCurrentPosition();
                }
                if (getNextIndex()) {
                    if (hasVideo) {
                        if (mediaPlayer != null) {
                            setLiveWallpaper();
                        }
                    } else {
                        this.setWallpaper();
                    }
                }
                else{
                    if (hasVideo) {
                        if (mediaPlayer != null) {
                            mediaPlayer.setLooping(false);
                            mediaPlayer.pause();
                        }
                    }
                }
            }
        }

        @Override
        public void onDestroy() {
            super.onDestroy();
            // Stop auto-scroll
            if (autoScrollHandler != null) {
                autoScrollHandler.removeCallbacks(autoScrollRunnable);
            }
            // Unregister preference change listener
            pref.unregisterOnSharedPreferenceChangeListener(this);
            // Recycle bitmap to free memory
            if (bitmap != null && !bitmap.isRecycled()) {
                bitmap.recycle();
                bitmap = null;
            }
            // Recycle carousel bitmaps
            recycleCarouselBitmaps();
            releaseMediaPlayer();
        }

        private void releaseMediaPlayer(){
            if (mediaPlayer!=null) {
                if (mediaPlayer.isPlaying()) {
                    mediaPlayer.stop();
                }
                mediaPlayer.release();
                mediaPlayer = null;
            }
        }
        
        @Override
        public void onSharedPreferenceChanged(SharedPreferences sharedPreferences, String key) {
            if ("wallpaperScroll".equals(key)) {
                wallpaperScroll = sharedPreferences.getBoolean(key, false);
            } else if ("autoScroll".equals(key)) {
                autoScrollEnabled = sharedPreferences.getBoolean(key, false);
                if (autoScrollEnabled && !hasVideo) {
                    loadCarouselBitmaps();
                    autoScrollHandler.post(autoScrollRunnable);
                } else {
                    autoScrollHandler.removeCallbacks(autoScrollRunnable);
                }
            }
        }
        
        /**
         * Load bitmaps for carousel display
         */
        private void loadCarouselBitmaps() {
            synchronized (carouselLock) {
                recycleCarouselBitmaps();
                
                if (list == null || list.isEmpty()) {
                    return;
                }
                
                // Load up to 5 wallpapers for carousel (or all if less than 5)
                int maxCount = Math.min(5, list.size());
                for (int i = 0; i < maxCount; i++) {
                    TianYinWallpaperModel model = list.get(i);
                    if (model.getType() == 0) { // Only static images for carousel
                        Bitmap bmp = loadBitmapFromModel(model);
                        if (bmp != null) {
                            carouselBitmaps.add(bmp);
                        }
                    }
                }
            }
        }
        
        /**
         * Load bitmap from model (similar to getBitmap but for any index)
         */
        private Bitmap loadBitmapFromModel(TianYinWallpaperModel model) {
            if (model.getImgUri() != null && !model.getImgUri().isEmpty()) {
                java.io.InputStream is = null;
                try {
                    is = getApplicationContext().getContentResolver().openInputStream(Uri.parse(model.getImgUri()));
                    return BitmapFactory.decodeStream(is);
                } catch (Exception e) {
                    android.util.Log.e("TianYinWallpaperService", "Error reading bitmap from URI", e);
                } finally {
                    if (is != null) {
                        try {
                            is.close();
                        } catch (java.io.IOException e) {
                            // Ignore
                        }
                    }
                }
            }
            // Fall back to file path
            return BitmapFactory.decodeFile(model.getImgPath());
        }
        
        /**
         * Recycle all carousel bitmaps to free memory
         */
        private void recycleCarouselBitmaps() {
            synchronized (carouselLock) {
                if (carouselBitmaps != null) {
                    for (Bitmap bmp : carouselBitmaps) {
                        if (bmp != null && !bmp.isRecycled()) {
                            bmp.recycle();
                        }
                    }
                    carouselBitmaps.clear();
                }
            }
        }
        
        /**
         * Draw the horizontal scrolling carousel
         */
        private void drawCarousel() {
            synchronized (carouselLock) {
                if (surfaceHolder == null || carouselBitmaps == null || carouselBitmaps.isEmpty()) {
                    return;
                }
                
                Canvas canvas = surfaceHolder.lockCanvas();
                if (canvas == null) {
                    return;
                }
                
                try {
                    // Clear canvas
                    canvas.drawColor(Color.BLACK);
                    
                    int canvasWidth = canvas.getWidth();
                    int canvasHeight = canvas.getHeight();
                    
                    // Calculate total width needed for all images
                    float totalWidth = 0;
                    for (Bitmap bmp : carouselBitmaps) {
                        float scale = (float) canvasHeight / bmp.getHeight();
                        totalWidth += bmp.getWidth() * scale;
                    }
                    
                    // Reset scroll offset when we've scrolled past all images
                    if (carouselScrollOffset >= totalWidth) {
                        carouselScrollOffset = 0;
                    }
                    
                    // Draw images that are visible on screen
                    // We may need to draw images twice to create seamless loop effect
                    float currentX = -carouselScrollOffset;
                    
                    // First pass: draw all images from current offset
                    for (Bitmap bmp : carouselBitmaps) {
                        float scale = (float) canvasHeight / bmp.getHeight();
                        float scaledWidth = bmp.getWidth() * scale;
                        
                        // Only draw if bitmap is visible on screen
                        if (currentX + scaledWidth > 0 && currentX < canvasWidth) {
                            canvas.save();
                            canvas.translate(currentX, 0);
                            canvas.scale(scale, scale);
                            canvas.drawBitmap(bmp, 0, 0, mPaint);
                            canvas.restore();
                        }
                        
                        currentX += scaledWidth;
                    }
                    
                    // Second pass: if there's empty space on the right, loop back and draw from beginning
                    if (currentX < canvasWidth && totalWidth > 0) {
                        // Start drawing from the beginning to fill the gap
                        for (Bitmap bmp : carouselBitmaps) {
                            float scale = (float) canvasHeight / bmp.getHeight();
                            float scaledWidth = bmp.getWidth() * scale;
                            
                            if (currentX + scaledWidth > 0 && currentX < canvasWidth) {
                                canvas.save();
                                canvas.translate(currentX, 0);
                                canvas.scale(scale, scale);
                                canvas.drawBitmap(bmp, 0, 0, mPaint);
                                canvas.restore();
                            }
                            
                            currentX += scaledWidth;
                            
                            // Stop if we've filled the screen
                            if (currentX >= canvasWidth) {
                                break;
                            }
                        }
                    }
                } finally {
                    surfaceHolder.unlockCanvasAndPost(canvas);
                }
            }
        }

    }
}
