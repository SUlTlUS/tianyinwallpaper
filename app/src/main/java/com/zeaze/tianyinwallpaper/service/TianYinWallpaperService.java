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

    class TianYinSolaEngine extends Engine {
        private MediaPlayer mediaPlayer;
        private Paint mPaint;
        private List<TianYinWallpaperModel> list;
        private int index = -1;
        private SurfaceHolder surfaceHolder;
        
        private boolean pageChange = false;
        private float currentXOffset = 0f;
        private Bitmap currentBitmap;
        
        // 视频尺寸
        private int videoWidth = 0;
        private int videoHeight = 0;
        
        public TianYinSolaEngine() {
            this.mPaint = new Paint();
            String s = FileUtil.loadData(getApplicationContext(), FileUtil.wallpaperPath);
            list = JSON.parseArray(s, TianYinWallpaperModel.class);
            lastTime = System.currentTimeMillis() / 1000;
            pref = getSharedPreferences(App.TIANYIN, MODE_PRIVATE);
            pageChange = pref.getBoolean("pageChange", false);
            needBackgroundPlay = pref.getBoolean("needBackgroundPlay", false);
            wallpaperScroll = pref.getBoolean("wallpaperScroll", false);
        }
        
        // Helper method to check if current wallpaper should use video playback
        // Both static (type=0) and dynamic (type=1) wallpapers now use video playback
        private boolean isCurrentWallpaperVideo() {
            return list != null && index >= 0 && index < list.size();
        }
        
        // Helper method to check if current wallpaper is a static image (legacy support)
        // Note: Static images are now played as videos, but this helps identify the original type
        private boolean isCurrentWallpaperImage() {
            return list != null && index >= 0 && index < list.size() && list.get(index).getType() == WALLPAPER_TYPE_IMAGE;
        }

        private boolean getNextIndex() {
            isOnlyOne = false;
            if (index != -1) {
                int minTime = pref.getInt("minTime", 1);
                if (System.currentTimeMillis() / 1000 - lastTime <= minTime) {
                    isOnlyOne = true;
                    return false;
                }
            }
            lastTime = System.currentTimeMillis() / 1000;
            boolean isRand = pref.getBoolean("rand", false);
            int i = 1;
            if (isRand) {
                i = (int) (Math.random() * list.size()) + 1;
            }
            int lastIndex = index;
            while (i > 0) {
                if (index == -1) index = list.size() - 1;
                index = getIfIndex();
                if (index == lastIndex) {
                    if (index == -1) index = list.size() - 1;
                    index = getIfIndex();
                }
                i = i - 1;
            }
            if (lastIndex == index) {
                isOnlyOne = true;
            }
            return true;
        }

        private int getIfIndex() {
            int i = index + 1;
            if (i >= list.size()) {
                i = 0;
            }
            while (!isIf(i)) {
                if (i == index) {
                    return getNoIfIndex();
                }
                i++;
                if (i >= list.size()) {
                    i = 0;
                }
            }
            return i;
        }

        private int getNoIfIndex() {
            int i = index + 1;
            if (i >= list.size()) {
                i = 0;
            }
            while (!((list.get(i).getStartTime() == -1 || list.get(i).getEndTime() == -1))) {
                if (i == index) {
                    i = index + 1;
                    if (i >= list.size()) {
                        i = 0;
                    }
                    return i;
                }
                i++;
                if (i >= list.size()) {
                    i = 0;
                }
            }
            return i;
        }

        private boolean isIf(int index) {
            TianYinWallpaperModel model = list.get(index);
            if (model.getStartTime() == -1 || model.getEndTime() == -1) {
                return false;
            }
            int now = getTime();
            if (model.getStartTime() == model.getEndTime() && model.getStartTime() == now) {
                return true;
            }
            if (model.getStartTime() <= now && now < model.getEndTime()) {
                return true;
            }
            if (now < model.getEndTime() && model.getEndTime() < model.getStartTime()) {
                return true;
            }
            if (model.getEndTime() < model.getStartTime() && model.getStartTime() <= now) {
                return true;
            }
            return false;
        }

        private int getTime() {
            Calendar cal = Calendar.getInstance();
            cal.setTime(new Date());
            int nowHour = cal.get(Calendar.HOUR_OF_DAY);
            int nowMin = cal.get(Calendar.MINUTE);
            return nowHour * 60 + nowMin;
        }

        @Override
        public void onSurfaceCreated(SurfaceHolder holder) {
            super.onSurfaceCreated(holder);
            surfaceHolder = holder;
        }

        @Override
        public void onSurfaceChanged(SurfaceHolder holder, int format, int width, int height) {
            super.onSurfaceChanged(holder, format, width, height);
            // 重新绘制当前壁纸 - 所有壁纸类型现在都使用视频播放
            if (isCurrentWallpaperVideo() && mediaPlayer != null) {
                try {
                    mediaPlayer.setSurface(holder.getSurface());
                    if (!mediaPlayer.isPlaying()) {
                        mediaPlayer.start();
                    }
                } catch (Exception e) {
                    e.printStackTrace();
                }
            }
        }

        private void setWallpaper() {
            setWallpaper(true);
        }
        
        private void setWallpaper(boolean reloadBitmap) {
            if (surfaceHolder == null) return;
            
            Canvas localCanvas = null;
            try {
                localCanvas = surfaceHolder.lockCanvas();
                if (localCanvas != null) {
                    localCanvas.drawColor(Color.BLACK);
                    
                    // 所有壁纸类型现在都使用视频播放系统
                    if (isCurrentWallpaperVideo()) {
                        if (mediaPlayer == null || !mediaPlayer.isPlaying()) {
                            // 视频未播放，显示预览图
                            if (currentBitmap == null || reloadBitmap) {
                                if (currentBitmap != null) {
                                    currentBitmap.recycle();
                                }
                                currentBitmap = getBitmap();
                            }
                            if (currentBitmap != null) {
                                drawImageWallpaper(localCanvas);
                            }
                        } else {
                            // 视频正在播放，清除画布让视频显示
                            localCanvas.drawColor(Color.TRANSPARENT, android.graphics.PorterDuff.Mode.CLEAR);
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

        private void drawImageWallpaper(Canvas canvas) {
            if (currentBitmap == null || currentBitmap.isRecycled()) return;
            
            int canvasWidth = canvas.getWidth();
            int canvasHeight = canvas.getHeight();
            int bitmapWidth = currentBitmap.getWidth();
            int bitmapHeight = currentBitmap.getHeight();
            
            if (canvasWidth <= 0 || canvasHeight <= 0) return;
            
            // 计算缩放比例，保持图片比例
            float bitmapAspect = (float) bitmapWidth / bitmapHeight;
            float canvasAspect = (float) canvasWidth / canvasHeight;
            
            Rect dstRect = new Rect();
            
            // 注意：静态壁纸现在作为视频播放，不支持滚动模式
            // 滚动功能仅在预览图显示时有效
            if (wallpaperScroll && isCurrentWallpaperImage() && (mediaPlayer == null || !mediaPlayer.isPlaying())) {
                // 滚动模式：适应高度，宽度可滚动（仅在视频未播放的预览状态）
                int scaledWidth = (int) (canvasHeight * bitmapAspect);
                
                if (scaledWidth > canvasWidth) {
                    // 图片比屏幕宽，需要滚动
                    int maxOffset = scaledWidth - canvasWidth;
                    int xOffset = (int) (maxOffset * currentXOffset);
                    
                    Rect srcRect = new Rect();
                    srcRect.left = (int) ((float) xOffset * bitmapWidth / scaledWidth);
                    srcRect.top = 0;
                    srcRect.right = (int) (((float) xOffset + canvasWidth) * bitmapWidth / scaledWidth);
                    srcRect.bottom = bitmapHeight;
                    
                    dstRect.set(0, 0, canvasWidth, canvasHeight);
                    canvas.drawBitmap(currentBitmap, srcRect, dstRect, mPaint);
                    return;
                } else {
                    // 图片不比屏幕宽，居中显示
                    int offsetX = (canvasWidth - scaledWidth) / 2;
                    dstRect.set(offsetX, 0, offsetX + scaledWidth, canvasHeight);
                }
            } else {
                // 普通模式：保持比例，可能留黑边
                if (bitmapAspect > canvasAspect) {
                    // 图片更宽，适应宽度，上下留黑边
                    int scaledHeight = (int) (canvasWidth / bitmapAspect);
                    int offsetY = (canvasHeight - scaledHeight) / 2;
                    dstRect.set(0, offsetY, canvasWidth, offsetY + scaledHeight);
                } else {
                    // 图片更高，适应高度，左右留黑边
                    int scaledWidth = (int) (canvasHeight * bitmapAspect);
                    int offsetX = (canvasWidth - scaledWidth) / 2;
                    dstRect.set(offsetX, 0, offsetX + scaledWidth, canvasHeight);
                }
            }
            
            canvas.drawBitmap(currentBitmap, null, dstRect, mPaint);
        }

        private void initVideoWallpaper() {
            releaseMediaPlayer();
            
            try {
                mediaPlayer = new MediaPlayer();
                
                // 设置Surface
                if (surfaceHolder != null && surfaceHolder.getSurface() != null && surfaceHolder.getSurface().isValid()) {
                    mediaPlayer.setSurface(surfaceHolder.getSurface());
                }
                
                TianYinWallpaperModel currentModel = list.get(index);
                
                // 设置数据源 - 支持静态和动态壁纸
                // 静态壁纸(type=0)使用生成的视频文件，动态壁纸(type=1)使用原始视频
                if (currentModel.getType() == WALLPAPER_TYPE_IMAGE) {
                    // 静态壁纸：使用生成的视频文件
                    if (currentModel.getVideoPath() != null && !currentModel.getVideoPath().isEmpty()) {
                        mediaPlayer.setDataSource(currentModel.getVideoPath());
                    } else {
                        // 视频文件缺失，无法播放静态壁纸
                        throw new IOException("Static wallpaper (index=" + index + ", uuid=" + currentModel.getUuid() + ") missing video path");
                    }
                } else {
                    // 动态壁纸：使用原始视频
                    if (currentModel.getVideoUri() != null && !currentModel.getVideoUri().isEmpty()) {
                        mediaPlayer.setDataSource(getApplicationContext(), Uri.parse(currentModel.getVideoUri()));
                    } else if (currentModel.getVideoPath() != null && !currentModel.getVideoPath().isEmpty()) {
                        mediaPlayer.setDataSource(currentModel.getVideoPath());
                    } else {
                        throw new IOException("Dynamic wallpaper (index=" + index + ", uuid=" + currentModel.getUuid() + ") missing video source");
                    }
                }
                
                mediaPlayer.setOnVideoSizeChangedListener(new MediaPlayer.OnVideoSizeChangedListener() {
                    @Override
                    public void onVideoSizeChanged(MediaPlayer mp, int width, int height) {
                        videoWidth = width;
                        videoHeight = height;
                    }
                });
                
                mediaPlayer.setOnPreparedListener(new MediaPlayer.OnPreparedListener() {
                    @Override
                    public void onPrepared(MediaPlayer mp) {
                        videoWidth = mp.getVideoWidth();
                        videoHeight = mp.getVideoHeight();
                        mp.setLooping(currentModel.isLoop());
                        mp.setVolume(0, 0);
                        
                        // 重要：使用SCALE_TO_FIT_WITH_CROPPING保持比例并填充整个屏幕，避免横向压缩
                        mp.setVideoScalingMode(MediaPlayer.VIDEO_SCALING_MODE_SCALE_TO_FIT_WITH_CROPPING);
                        
                        mp.start();
                        
                        // 清除画布，让视频显示
                        setWallpaper(false);
                    }
                });
                
                mediaPlayer.setOnErrorListener(new MediaPlayer.OnErrorListener() {
                    @Override
                    public boolean onError(MediaPlayer mp, int what, int extra) {
                        // 视频播放出错，回退到图片
                        setWallpaper(true);
                        return true;
                    }
                });
                
                mediaPlayer.prepareAsync();
                
            } catch (IOException e) {
                e.printStackTrace();
                setWallpaper(true);
            }
        }

        private Bitmap getBitmap() {
            TianYinWallpaperModel currentModel = list.get(index);
            
            // 先尝试从URI加载
            if (currentModel.getImgUri() != null && !currentModel.getImgUri().isEmpty()) {
                InputStream is = null;
                try {
                    is = getApplicationContext().getContentResolver().openInputStream(Uri.parse(currentModel.getImgUri()));
                    Bitmap bitmap = BitmapFactory.decodeStream(is);
                    if (bitmap != null) {
                        return bitmap;
                    }
                } catch (Exception e) {
                    e.printStackTrace();
                } finally {
                    if (is != null) {
                        try {
                            is.close();
                        } catch (IOException e) {
                            e.printStackTrace();
                        }
                    }
                }
            }
            
            // 降级到文件路径
            return BitmapFactory.decodeFile(currentModel.getImgPath());
        }

        int page = -1;
        private long lastPlayTime;

        @Override
        public void onOffsetsChanged(float xOffset, float yOffset, float xOffsetStep, 
                                     float yOffsetStep, int xPixelOffset, int yPixelOffset) {
            super.onOffsetsChanged(xOffset, yOffset, xOffsetStep, yOffsetStep, xPixelOffset, yPixelOffset);
            
            // 更新滚动偏移 - 仅在预览图显示时生效（视频未播放状态）
            // 静态壁纸作为视频播放时不支持滚动
            if (wallpaperScroll && isCurrentWallpaperImage() && (mediaPlayer == null || !mediaPlayer.isPlaying())) {
                currentXOffset = xOffset;
                setWallpaper(false);
            }
            
            // Handle page change detection
            if (!pageChange) {
                return;
            }
            
            float dx = xOffset;
            while (dx > xOffsetStep) {
                dx = dx - xOffset;
            }
            dx = dx / xOffsetStep;
            
            if (page == -1) {
                if (dx < 0.1 || dx > 0.9) {
                    page = Math.round(xOffset / xOffsetStep);
                }
                return;
            }
            
            if (dx < 0.1 || dx > 0.9) {
                int newPage = Math.round(xOffset / xOffsetStep);
                if (newPage != page) {
                    lastTime = 0;
                    onVisibilityChanged(false);
                    onVisibilityChanged(true);
                    page = newPage;
                }
            }
        }

        @Override
        public void onVisibilityChanged(boolean visible) {
            super.onVisibilityChanged(visible);
            
            if (visible) {
                // 壁纸可见 - 所有类型都使用视频播放
                if (isCurrentWallpaperVideo()) {
                    if (mediaPlayer == null) {
                        // 首次播放
                        initVideoWallpaper();
                    } else {
                        try {
                            // 确保Surface设置正确
                            if (surfaceHolder != null && surfaceHolder.getSurface() != null) {
                                mediaPlayer.setSurface(surfaceHolder.getSurface());
                            }
                            
                            // 确保缩放模式正确，使用SCALE_TO_FIT_WITH_CROPPING避免横向压缩
                            mediaPlayer.setVideoScalingMode(MediaPlayer.VIDEO_SCALING_MODE_SCALE_TO_FIT_WITH_CROPPING);
                            
                            if (!mediaPlayer.isPlaying()) {
                                mediaPlayer.start();
                            }
                            
                            if (isOnlyOne && lastPlayTime > 0 && needBackgroundPlay) {
                                long duration = mediaPlayer.getDuration();
                                if (duration > 0) {
                                    long nowTime = (lastPlayTime + System.currentTimeMillis() - lastTime * 1000) % duration;
                                    mediaPlayer.seekTo((int) nowTime);
                                }
                            }
                            
                            // 清除画布，让视频显示
                            setWallpaper(false);
                        } catch (Exception e) {
                            e.printStackTrace();
                            initVideoWallpaper();
                        }
                    }
                }
            } else {
                // 壁纸不可见
                if (mediaPlayer != null) {
                    try {
                        if (mediaPlayer.isPlaying()) {
                            lastPlayTime = mediaPlayer.getCurrentPosition();
                            mediaPlayer.pause();
                        }
                    } catch (Exception e) {
                        e.printStackTrace();
                    }
                }
                
                // 准备下一个壁纸
                if (getNextIndex()) {
                    // 切换到下一个壁纸 - 所有类型都初始化视频播放
                    initVideoWallpaper();
                } else {
                    // 没有切换，暂停视频
                    if (mediaPlayer != null) {
                        try {
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
            if (mediaPlayer != null) {
                mediaPlayer.setSurface(null);
            }
        }

        @Override
        public void onDestroy() {
            super.onDestroy();
            releaseMediaPlayer();
            
            if (currentBitmap != null) {
                currentBitmap.recycle();
                currentBitmap = null;
            }
        }

        private void releaseMediaPlayer() {
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