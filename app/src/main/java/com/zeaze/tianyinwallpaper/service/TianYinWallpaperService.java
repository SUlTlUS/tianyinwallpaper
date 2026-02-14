package com.zeaze.tianyinwallpaper.service;

import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.SurfaceTexture;
import android.media.MediaPlayer;
import android.net.Uri;
import android.service.wallpaper.WallpaperService;
import android.view.Surface;
import android.view.TextureView;
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
    String TAG = "TianYinSolaWallpaperService";
    private SharedPreferences pref;
    private long lastTime = 0;
    private boolean isOnlyOne = false;
    private boolean needBackgroundPlay = false;
    private boolean wallpaperScroll = false;

    @Override
    public Engine onCreateEngine() {
        return new TianYinSolaEngine();
    }

    class TianYinSolaEngine extends Engine implements TextureView.SurfaceTextureListener {
        private MediaPlayer mediaPlayer;
        private Paint mPaint;
        private List<TianYinWallpaperModel> list;
        private int index = -1;
        private SurfaceHolder surfaceHolder;
        private boolean hasVideo;
        private TextureView textureView;
        private Surface videoSurface;
        private boolean isTextureViewReady = false;
        private boolean pageChange = false;
        private float currentXOffset = 0f;
        private Bitmap currentBitmap;
        private int videoWidth = 0;
        private int videoHeight = 0;
        private Rect videoSrcRect = new Rect();
        private Rect videoDstRect = new Rect();
        private float[] transformMatrix = new float[16];

        public TianYinSolaEngine() {
            this.mPaint = new Paint();
            String s = FileUtil.loadData(getApplicationContext(), FileUtil.wallpaperPath);
            list = JSON.parseArray(s, TianYinWallpaperModel.class);
            hasVideo = true;
            lastTime = System.currentTimeMillis() / 1000;
            pref = getSharedPreferences(App.TIANYIN, MODE_PRIVATE);
            pageChange = pref.getBoolean("pageChange", false);
            needBackgroundPlay = pref.getBoolean("needBackgroundPlay", false);
            wallpaperScroll = pref.getBoolean("wallpaperScroll", false);
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
        public void onCreate(SurfaceHolder surfaceHolder) {
            super.onCreate(surfaceHolder);
            this.surfaceHolder = surfaceHolder;
            
            // 创建TextureView
            textureView = new TextureView(getApplicationContext());
            textureView.setSurfaceTextureListener(this);
        }

        @Override
        public void onSurfaceCreated(SurfaceHolder holder) {
            super.onSurfaceCreated(holder);
            // SurfaceHolder创建时不做视频初始化，等待TextureView
        }

        @Override
        public void onSurfaceTextureAvailable(SurfaceTexture surface, int width, int height) {
            isTextureViewReady = true;
            videoSurface = new Surface(surface);
            
            if (hasVideo && index != -1) {
                initMediaPlayer();
            }
        }

        @Override
        public void onSurfaceTextureSizeChanged(SurfaceTexture surface, int width, int height) {
            updateVideoTransform();
        }

        @Override
        public boolean onSurfaceTextureDestroyed(SurfaceTexture surface) {
            isTextureViewReady = false;
            if (videoSurface != null) {
                videoSurface.release();
                videoSurface = null;
            }
            return false;
        }

        @Override
        public void onSurfaceTextureUpdated(SurfaceTexture surface) {
            // 不需要处理
        }

        private void initMediaPlayer() {
            releaseMediaPlayer();
            mediaPlayer = new MediaPlayer();
            
            try {
                TianYinWallpaperModel currentModel = list.get(index);
                
                // 设置数据源
                if (currentModel.getVideoUri() != null && !currentModel.getVideoUri().isEmpty()) {
                    mediaPlayer.setDataSource(getApplicationContext(), Uri.parse(currentModel.getVideoUri()));
                } else {
                    mediaPlayer.setDataSource(currentModel.getVideoPath());
                }
                
                // 设置Surface
                if (videoSurface != null && videoSurface.isValid()) {
                    mediaPlayer.setSurface(videoSurface);
                }
                
                mediaPlayer.setOnVideoSizeChangedListener(new MediaPlayer.OnVideoSizeChangedListener() {
                    @Override
                    public void onVideoSizeChanged(MediaPlayer mp, int width, int height) {
                        videoWidth = width;
                        videoHeight = height;
                        updateVideoTransform();
                    }
                });
                
                mediaPlayer.prepareAsync();
                mediaPlayer.setOnPreparedListener(new MediaPlayer.OnPreparedListener() {
                    @Override
                    public void onPrepared(MediaPlayer mp) {
                        videoWidth = mp.getVideoWidth();
                        videoHeight = mp.getVideoHeight();
                        mp.setLooping(list.get(index).isLoop());
                        mp.setVolume(0, 0);
                        mp.start();
                        updateVideoTransform();
                    }
                });
                
                mediaPlayer.setOnErrorListener(new MediaPlayer.OnErrorListener() {
                    @Override
                    public boolean onError(MediaPlayer mp, int what, int extra) {
                        return true;
                    }
                });
                
            } catch (IOException e) {
                e.printStackTrace();
            }
        }

        private void updateVideoTransform() {
            if (textureView == null || !isTextureViewReady || videoWidth <= 0 || videoHeight <= 0) {
                return;
            }
            
            int viewWidth = textureView.getWidth();
            int viewHeight = textureView.getHeight();
            
            if (viewWidth <= 0 || viewHeight <= 0) {
                return;
            }
            
            float videoAspect = (float) videoWidth / videoHeight;
            float viewAspect = (float) viewWidth / viewHeight;
            
            if (wallpaperScroll) {
                // 滚动模式：视频适应高度，宽度可滚动
                int scaledWidth = (int) (viewHeight * videoAspect);
                
                if (scaledWidth > viewWidth) {
                    // 视频比屏幕宽，需要滚动
                    float scaleX = (float) viewHeight / videoHeight;
                    float scaleY = (float) viewHeight / videoHeight;
                    
                    // 计算偏移量
                    float maxOffset = scaledWidth - viewWidth;
                    float translateX = -maxOffset * currentXOffset;
                    
                    // 应用变换矩阵
                    android.graphics.Matrix matrix = new android.graphics.Matrix();
                    matrix.setScale(scaleX, scaleY);
                    matrix.postTranslate(translateX, 0);
                    textureView.setTransform(matrix);
                } else {
                    // 视频不比屏幕宽，居中显示
                    android.graphics.Matrix matrix = new android.graphics.Matrix();
                    float scale = (float) viewHeight / videoHeight;
                    matrix.setScale(scale, scale);
                    matrix.postTranslate((viewWidth - scaledWidth) / 2f, 0);
                    textureView.setTransform(matrix);
                }
            } else {
                // 普通模式：视频填充屏幕（可能裁剪）
                if (videoAspect > viewAspect) {
                    // 视频更宽，适应高度，裁剪左右
                    float scale = (float) viewHeight / videoHeight;
                    float scaledWidth = videoWidth * scale;
                    float translateX = (viewWidth - scaledWidth) / 2f;
                    
                    android.graphics.Matrix matrix = new android.graphics.Matrix();
                    matrix.setScale(scale, scale);
                    matrix.postTranslate(translateX, 0);
                    textureView.setTransform(matrix);
                } else {
                    // 视频更高，适应宽度，裁剪上下
                    float scale = (float) viewWidth / videoWidth;
                    float scaledHeight = videoHeight * scale;
                    float translateY = (viewHeight - scaledHeight) / 2f;
                    
                    android.graphics.Matrix matrix = new android.graphics.Matrix();
                    matrix.setScale(scale, scale);
                    matrix.postTranslate(0, translateY);
                    textureView.setTransform(matrix);
                }
            }
        }

        private void setWallpaper() {
            setWallpaper(true);
        }

        private void setWallpaper(boolean reloadBitmap) {
            if (surfaceHolder == null) return;
            
            Canvas localCanvas = surfaceHolder.lockCanvas();
            if (localCanvas != null) {
                localCanvas.drawColor(Color.WHITE);
                
                if (!hasVideo) {
                    // 图片壁纸处理
                    if (reloadBitmap) {
                        if (currentBitmap != null) {
                            currentBitmap.recycle();
                        }
                        currentBitmap = getBitmap();
                    }
                    
                    drawBitmap(localCanvas);
                } else {
                    // 视频壁纸：绘制TextureView的内容
                    if (textureView != null && isTextureViewReady) {
                        textureView.draw(localCanvas);
                    }
                }
                
                surfaceHolder.unlockCanvasAndPost(localCanvas);
            }
        }

        private void drawBitmap(Canvas canvas) {
            if (currentBitmap == null || currentBitmap.isRecycled()) return;
            
            int canvasWidth = canvas.getWidth();
            int canvasHeight = canvas.getHeight();
            int bitmapWidth = currentBitmap.getWidth();
            int bitmapHeight = currentBitmap.getHeight();
            
            // 计算缩放后的尺寸（适应高度）
            float bitmapAspect = (float) bitmapWidth / bitmapHeight;
            int scaledWidth = (int) (canvasHeight * bitmapAspect);
            
            if (wallpaperScroll && scaledWidth > canvasWidth) {
                // 计算滚动偏移
                int maxOffset = scaledWidth - canvasWidth;
                int xOffset = (int) (maxOffset * currentXOffset);
                
                // 计算源矩形（在原图上裁剪）
                Rect srcRect = new Rect();
                srcRect.left = (int) ((float) xOffset * bitmapWidth / scaledWidth);
                srcRect.top = 0;
                srcRect.right = (int) (((float) xOffset + canvasWidth) * bitmapWidth / scaledWidth);
                srcRect.bottom = bitmapHeight;
                
                // 目标矩形（画布上显示的区域）
                Rect dstRect = new Rect(0, 0, canvasWidth, canvasHeight);
                
                canvas.drawBitmap(currentBitmap, srcRect, dstRect, mPaint);
            } else {
                // 不滚动或图片不宽于屏幕，直接绘制
                Rect dstRect = new Rect(0, 0, canvasWidth, canvasHeight);
                canvas.drawBitmap(currentBitmap, null, dstRect, mPaint);
            }
        }

        private void setLiveWallpaper() {
            if (hasVideo && isTextureViewReady) {
                initMediaPlayer();
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
            
            // 更新滚动偏移
            if (wallpaperScroll) {
                currentXOffset = xOffset;
                
                if (hasVideo) {
                    // 更新视频的变换矩阵
                    updateVideoTransform();
                    // 重绘
                    setWallpaper(false);
                } else {
                    // 重绘图片
                    setWallpaper(false);
                }
            }
            
            // 处理页面切换检测（保持原有逻辑）
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
                if (hasVideo && mediaPlayer != null) {
                    if (index != -1) {
                        mediaPlayer.setLooping(list.get(index).isLoop());
                        if (!mediaPlayer.isPlaying()) {
                            mediaPlayer.start();
                        }
                        if (isOnlyOne && lastPlayTime > 0 && needBackgroundPlay) {
                            long nowTime = (lastPlayTime + System.currentTimeMillis() - lastTime * 1000) 
                                         % (mediaPlayer.getDuration());
                            mediaPlayer.seekTo((int) nowTime);
                        }
                    }
                }
            } else {
                if (mediaPlayer != null) {
                    lastPlayTime = mediaPlayer.getCurrentPosition();
                }
                
                if (getNextIndex()) {
                    if (hasVideo) {
                        setLiveWallpaper();
                    } else {
                        this.setWallpaper();
                    }
                } else {
                    if (hasVideo && mediaPlayer != null) {
                        mediaPlayer.setLooping(false);
                        mediaPlayer.pause();
                    }
                }
            }
        }

        @Override
        public void onSurfaceDestroyed(SurfaceHolder holder) {
            super.onSurfaceDestroyed(holder);
            releaseMediaPlayer();
            
            if (textureView != null) {
                textureView.setSurfaceTextureListener(null);
            }
            
            if (currentBitmap != null) {
                currentBitmap.recycle();
                currentBitmap = null;
            }
        }

        @Override
        public void onDestroy() {
            super.onDestroy();
            releaseMediaPlayer();
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
