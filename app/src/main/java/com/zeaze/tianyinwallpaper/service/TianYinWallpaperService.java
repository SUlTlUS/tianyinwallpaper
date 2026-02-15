package com.zeaze.tianyinwallpaper.service;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.SurfaceTexture;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.net.Uri;
import android.opengl.EGL14;
import android.opengl.EGLConfig;
import android.opengl.EGLContext;
import android.opengl.EGLDisplay;
import android.opengl.EGLExt;
import android.opengl.EGLSurface;
import android.opengl.GLES11Ext;
import android.opengl.GLES20;
import android.opengl.Matrix;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.service.wallpaper.WallpaperService;
import android.util.Log;
import android.view.Surface;
import android.view.SurfaceHolder;

import com.alibaba.fastjson.JSON;
import com.zeaze.tianyinwallpaper.App;
import com.zeaze.tianyinwallpaper.model.TianYinWallpaperModel;
import com.zeaze.tianyinwallpaper.utils.FileUtil;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.util.Calendar;
import java.util.Date;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

public class TianYinWallpaperService extends WallpaperService {
    private static final String TAG = "TianYinWallpaperService";

    private static final int WALLPAPER_TYPE_IMAGE = 0;
    private static final int WALLPAPER_TYPE_VIDEO = 1;

    @Override
    public Engine onCreateEngine() {
        return new TianYinSolaEngine();
    }

    class TianYinSolaEngine extends WallpaperService.Engine {
        // 配置相关
        private SharedPreferences pref;
        private List<TianYinWallpaperModel> wallpaperList;
        private int currentIndex = -1;
        private boolean hasVideo = false;
        private boolean pageChange;
        private boolean wallpaperScroll;
        private boolean randomMode;
        private int minSwitchTime; // 秒

        // 状态
        private long lastSwitchTime = 0;
        private long lastPausePosition = 0;   // 视频暂停时的位置（微秒）
        private long lastPauseTime = 0;        // 视频暂停时的系统时间（毫秒）
        private boolean isOnlyOne;

        // 图片相关
        private Bitmap currentBitmap;
        private Paint paint;

        // 视频相关
        private VideoRenderer videoRenderer;
        private boolean useVideoRenderer = true; // 是否使用OpenGL渲染视频

        // 桌面滑动
        private float currentXOffset = 0f;
        private int currentPage = -1;

        // 后台线程
        private HandlerThread backgroundThread;
        private Handler backgroundHandler;

        public TianYinSolaEngine() {
            paint = new Paint();
            loadWallpaperList();
            lastSwitchTime = System.currentTimeMillis() / 1000;
            reloadPreferences();

            // 检查是否有视频壁纸
            if (wallpaperList != null) {
                for (TianYinWallpaperModel model : wallpaperList) {
                    if (model.getType() == WALLPAPER_TYPE_VIDEO) {
                        hasVideo = true;
                        break;
                    }
                }
            }

            backgroundThread = new HandlerThread("WallpaperWorker");
            backgroundThread.start();
            backgroundHandler = new Handler(backgroundThread.getLooper());
        }

        private void loadWallpaperList() {
            String json = FileUtil.loadData(getApplicationContext(), FileUtil.wallpaperPath);
            wallpaperList = JSON.parseArray(json, TianYinWallpaperModel.class);
            if (wallpaperList == null || wallpaperList.isEmpty()) {
                Log.e(TAG, "壁纸列表为空");
                wallpaperList = null;
            }
        }

        private void reloadPreferences() {
            pref = getSharedPreferences(App.TIANYIN, MODE_PRIVATE);
            pageChange = pref.getBoolean("pageChange", false);
            wallpaperScroll = pref.getBoolean("wallpaperScroll", false);
            randomMode = pref.getBoolean("rand", false);
            minSwitchTime = pref.getInt("minTime", 1);
        }

        // ==================== 壁纸切换逻辑 ====================

        private boolean getNextIndex() {
            if (wallpaperList == null || wallpaperList.isEmpty()) return false;

            long now = System.currentTimeMillis() / 1000;
            if (currentIndex != -1 && (now - lastSwitchTime) <= minSwitchTime) {
                isOnlyOne = true;
                return false;
            }
            lastSwitchTime = now;

            int newIndex;
            if (randomMode) {
                if (wallpaperList.size() == 1) {
                    newIndex = 0;
                } else {
                    do {
                        newIndex = (int) (Math.random() * wallpaperList.size());
                    } while (newIndex == currentIndex);
                }
            } else {
                if (currentIndex == -1) {
                    newIndex = findNextValidIndex(0);
                } else {
                    newIndex = findNextValidIndex(currentIndex + 1);
                }
            }

            if (newIndex == -1) {
                newIndex = findAnyUnrestrictedIndex();
            }
            if (newIndex == -1 && wallpaperList.size() > 0) {
                newIndex = 0;
            }

            if (newIndex != currentIndex) {
                currentIndex = newIndex;
                isOnlyOne = false;
                return true;
            } else {
                isOnlyOne = true;
                return false;
            }
        }

        private int findNextValidIndex(int start) {
            if (wallpaperList == null) return -1;
            int size = wallpaperList.size();
            for (int i = 0; i < size; i++) {
                int idx = (start + i) % size;
                if (isTimeValid(idx)) return idx;
            }
            return -1;
        }

        private int findAnyUnrestrictedIndex() {
            if (wallpaperList == null) return -1;
            for (int i = 0; i < wallpaperList.size(); i++) {
                TianYinWallpaperModel model = wallpaperList.get(i);
                if (model.getStartTime() == -1 && model.getEndTime() == -1) {
                    return i;
                }
            }
            return -1;
        }

        private boolean isTimeValid(int index) {
            TianYinWallpaperModel model = wallpaperList.get(index);
            int start = model.getStartTime();
            int end = model.getEndTime();
            if (start == -1 && end == -1) return true;
            int now = getCurrentMinuteOfDay();
            if (start <= end) {
                return now >= start && now < end;
            } else {
                return now >= start || now < end;
            }
        }

        private int getCurrentMinuteOfDay() {
            Calendar cal = Calendar.getInstance();
            cal.setTime(new Date());
            return cal.get(Calendar.HOUR_OF_DAY) * 60 + cal.get(Calendar.MINUTE);
        }

        // ==================== 生命周期 ====================

        @Override
        public void onSurfaceCreated(SurfaceHolder holder) {
            super.onSurfaceCreated(holder);
            if (hasVideo && useVideoRenderer) {
                videoRenderer = new VideoRenderer(getApplicationContext(), holder.getSurface());
                videoRenderer.start();
            }
        }

        @Override
        public void onSurfaceChanged(SurfaceHolder holder, int format, int width, int height) {
            super.onSurfaceChanged(holder, format, width, height);
            if (videoRenderer != null) {
                videoRenderer.setSurfaceSize(width, height);
            }
        }

        @Override
        public void onSurfaceDestroyed(SurfaceHolder holder) {
            super.onSurfaceDestroyed(holder);
            if (videoRenderer != null) {
                videoRenderer.release();
                videoRenderer = null;
            }
        }

        @Override
        public void onVisibilityChanged(boolean visible) {
            super.onVisibilityChanged(visible);

            if (visible) {
                reloadPreferences();

                if (currentIndex == -1 && wallpaperList != null && !wallpaperList.isEmpty()) {
                    if (getNextIndex()) {
                        applyCurrentWallpaper();
                    }
                } else {
                    // 如果当前是视频，恢复播放
                    if (isCurrentWallpaperVideo() && videoRenderer != null) {
                        videoRenderer.play();
                        if (needBackgroundPlay() && lastPausePosition > 0 && lastPauseTime > 0) {
                            long elapsed = System.currentTimeMillis() - lastPauseTime;
                            videoRenderer.seekTo(lastPausePosition + elapsed * 1000); // 微秒
                        }
                    }
                }
            } else {
                // 不可见时，暂停视频并记录位置
                if (isCurrentWallpaperVideo() && videoRenderer != null && videoRenderer.isPlaying()) {
                    lastPausePosition = videoRenderer.getCurrentPosition();
                    lastPauseTime = System.currentTimeMillis();
                    videoRenderer.pause();
                }

                if (getNextIndex()) {
                    applyCurrentWallpaper();
                }
            }
        }

        private boolean needBackgroundPlay() {
            return pref.getBoolean("needBackgroundPlay", false);
        }

        @Override
        public void onOffsetsChanged(float xOffset, float yOffset, float xOffsetStep, float yOffsetStep,
                                     int xPixelOffset, int yPixelOffset) {
            super.onOffsetsChanged(xOffset, yOffset, xOffsetStep, yOffsetStep, xPixelOffset, yPixelOffset);

            if (wallpaperScroll) {
                currentXOffset = xOffset;
                if (isCurrentWallpaperImage()) {
                    if (currentBitmap != null && !currentBitmap.isRecycled()) {
                        drawImageWallpaper();
                    }
                } else if (isCurrentWallpaperVideo() && videoRenderer != null) {
                    videoRenderer.setXOffset(xOffset);
                }
            }

            if (pageChange && xOffsetStep > 0) {
                handlePageChange(xOffset, xOffsetStep);
            }
        }

        private void handlePageChange(float xOffset, float xOffsetStep) {
            int newPage = Math.round(xOffset / xOffsetStep);
            if (currentPage == -1) {
                currentPage = newPage;
                return;
            }
            if (newPage != currentPage) {
                lastSwitchTime = 0;
                currentPage = newPage;
                if (getNextIndex()) {
                    applyCurrentWallpaper();
                }
            }
        }

        @Override
        public void onDestroy() {
            backgroundThread.quitSafely();
            if (videoRenderer != null) {
                videoRenderer.release();
                videoRenderer = null;
            }
            recycleBitmap();
            super.onDestroy();
        }

        // ==================== 壁纸应用 ====================

        private void applyCurrentWallpaper() {
            if (currentIndex == -1 || wallpaperList == null) return;

            if (isCurrentWallpaperVideo()) {
                if (videoRenderer == null) {
                    // 等待 surface 创建后自动启动
                    return;
                }
                backgroundHandler.post(() -> {
                    TianYinWallpaperModel model = wallpaperList.get(currentIndex);
                    videoRenderer.setDataSource(model, needBackgroundPlay() ? lastPausePosition : 0);
                });
            } else {
                backgroundHandler.post(() -> {
                    loadBitmapForCurrent();
                    runOnUiThread(this::drawImageWallpaper);
                });
            }
        }

        // ==================== 图片处理 ====================

        private void loadBitmapForCurrent() {
            recycleBitmap();
            TianYinWallpaperModel model = wallpaperList.get(currentIndex);
            Bitmap bitmap = null;

            if (model.getImgUri() != null && !model.getImgUri().isEmpty()) {
                try (InputStream is = getContentResolver().openInputStream(Uri.parse(model.getImgUri()))) {
                    bitmap = decodeSampledBitmapFromStream(is);
                } catch (Exception e) {
                    Log.e(TAG, "从 URI 加载图片失败", e);
                }
            }

            if (bitmap == null && model.getImgPath() != null) {
                bitmap = decodeSampledBitmapFromFile(model.getImgPath());
            }

            currentBitmap = bitmap;
        }

        private Bitmap decodeSampledBitmapFromStream(InputStream is) {
            BitmapFactory.Options options = new BitmapFactory.Options();
            options.inJustDecodeBounds = true;
            BitmapFactory.decodeStream(is, null, options);

            int screenWidth = getResources().getDisplayMetrics().widthPixels;
            int screenHeight = getResources().getDisplayMetrics().heightPixels;
            options.inSampleSize = calculateInSampleSize(options, screenWidth, screenHeight);

            options.inJustDecodeBounds = false;
            try (InputStream is2 = getContentResolver().openInputStream(Uri.parse(wallpaperList.get(currentIndex).getImgUri()))) {
                return BitmapFactory.decodeStream(is2, null, options);
            } catch (Exception e) {
                Log.e(TAG, "采样解码失败", e);
                return null;
            }
        }

        private Bitmap decodeSampledBitmapFromFile(String path) {
            BitmapFactory.Options options = new BitmapFactory.Options();
            options.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(path, options);

            int screenWidth = getResources().getDisplayMetrics().widthPixels;
            int screenHeight = getResources().getDisplayMetrics().heightPixels;
            options.inSampleSize = calculateInSampleSize(options, screenWidth, screenHeight);

            options.inJustDecodeBounds = false;
            return BitmapFactory.decodeFile(path, options);
        }

        private int calculateInSampleSize(BitmapFactory.Options options, int reqWidth, int reqHeight) {
            int height = options.outHeight;
            int width = options.outWidth;
            int inSampleSize = 1;
            if (height > reqHeight || width > reqWidth) {
                int halfHeight = height / 2;
                int halfWidth = width / 2;
                while ((halfHeight / inSampleSize) >= reqHeight && (halfWidth / inSampleSize) >= reqWidth) {
                    inSampleSize *= 2;
                }
            }
            return inSampleSize;
        }

        private void drawImageWallpaper() {
            SurfaceHolder holder = getSurfaceHolder();
            if (holder == null) return;

            Canvas canvas = holder.lockCanvas();
            if (canvas == null) return;

            try {
                canvas.drawColor(Color.WHITE);
                if (currentBitmap != null && !currentBitmap.isRecycled()) {
                    if (wallpaperScroll) {
                        drawImageWithScroll(canvas);
                    } else {
                        drawImageCenterCrop(canvas);
                    }
                }
            } finally {
                holder.unlockCanvasAndPost(canvas);
            }
        }

        private void drawImageWithScroll(Canvas canvas) {
            int canvasWidth = canvas.getWidth();
            int canvasHeight = canvas.getHeight();
            int bitmapWidth = currentBitmap.getWidth();
            int bitmapHeight = currentBitmap.getHeight();

            float scale = (float) canvasHeight / bitmapHeight;
            int scaledWidth = (int) (bitmapWidth * scale);

            if (scaledWidth <= canvasWidth) {
                drawImageCenterCrop(canvas);
                return;
            }

            int maxOffset = scaledWidth - canvasWidth;
            int xOffset = (int) (maxOffset * currentXOffset);

            Matrix matrix = new Matrix();
            matrix.postScale(scale, scale);
            matrix.postTranslate(-xOffset, 0);
            canvas.drawBitmap(currentBitmap, matrix, paint);
        }

        private void drawImageCenterCrop(Canvas canvas) {
            int canvasWidth = canvas.getWidth();
            int canvasHeight = canvas.getHeight();
            int bitmapWidth = currentBitmap.getWidth();
            int bitmapHeight = currentBitmap.getHeight();

            float scale = Math.max((float) canvasWidth / bitmapWidth, (float) canvasHeight / bitmapHeight);
            int scaledWidth = (int) (bitmapWidth * scale);
            int scaledHeight = (int) (bitmapHeight * scale);
            int left = (canvasWidth - scaledWidth) / 2;
            int top = (canvasHeight - scaledHeight) / 2;

            Rect srcRect = new Rect(0, 0, bitmapWidth, bitmapHeight);
            Rect dstRect = new Rect(left, top, left + scaledWidth, top + scaledHeight);
            canvas.drawBitmap(currentBitmap, srcRect, dstRect, paint);
        }

        private void recycleBitmap() {
            if (currentBitmap != null && !currentBitmap.isRecycled()) {
                currentBitmap.recycle();
            }
            currentBitmap = null;
        }

        private boolean isCurrentWallpaperVideo() {
            return wallpaperList != null && currentIndex >= 0 && currentIndex < wallpaperList.size()
                    && wallpaperList.get(currentIndex).getType() == WALLPAPER_TYPE_VIDEO;
        }

        private boolean isCurrentWallpaperImage() {
            return wallpaperList != null && currentIndex >= 0 && currentIndex < wallpaperList.size()
                    && wallpaperList.get(currentIndex).getType() == WALLPAPER_TYPE_IMAGE;
        }

        private void runOnUiThread(Runnable action) {
            Handler mainHandler = new Handler(Looper.getMainLooper());
            mainHandler.post(action);
        }
    }

    // ==================== 视频渲染器（集成轻量引擎）====================

    private static class VideoRenderer implements SurfaceTexture.OnFrameAvailableListener {
        private static final String TAG = "VideoRenderer";

        // 顶点着色器
        private static final String VERTEX_SHADER =
                "uniform mat4 uSTMatrix;\n" +
                "uniform mat4 uMVPMatrix;\n" +
                "attribute vec4 position;\n" +
                "attribute vec4 texCoords;\n" +
                "varying vec2 outTexCoords;\n" +
                "void main(void) {\n" +
                "    outTexCoords = (uSTMatrix * texCoords).xy;\n" +
                "    gl_Position = uMVPMatrix * position;\n" +
                "}\n";

        // 片段着色器（外部纹理）
        private static final String FRAGMENT_SHADER =
                "#extension GL_OES_EGL_image_external : require\n" +
                "precision mediump float;\n" +
                "varying vec2 outTexCoords;\n" +
                "uniform samplerExternalOES texture;\n" +
                "uniform float light;\n" +
                "void main(void) {\n" +
                "  gl_FragColor = texture2D(texture, outTexCoords) * light;\n" +
                "}\n";

        // 顶点数据（两个三角形组成矩形）
        private static final float[] VERTEX_DATA = {
                -1.0f, -1.0f, 0.0f,
                 1.0f, -1.0f, 0.0f,
                -1.0f,  1.0f, 0.0f,
                 1.0f,  1.0f, 0.0f
        };

        private static final float[] TEX_COORD_DATA = {
                0.0f, 1.0f,
                1.0f, 1.0f,
                0.0f, 0.0f,
                1.0f, 0.0f
        };

        // OpenGL 相关
        private FloatBuffer vertexBuffer;
        private FloatBuffer texCoordBuffer;
        private int program;
        private int maPositionHandle;
        private int maTexCoordHandle;
        private int muSTMatrixHandle;
        private int muMVPMatrixHandle;
        private int muLightHandle;
        private float[] mSTMatrix = new float[16];
        private float[] mMVPMatrix = new float[16];
        private int textureId = -1;

        // SurfaceTexture 相关
        private SurfaceTexture surfaceTexture;
        private Surface surface;
        private final Object frameLock = new Object();
        private boolean frameAvailable = false;

        // 解码器
        private MediaCodecDecoder decoder;
        private Thread renderThread;
        private volatile boolean rendering = false;
        private Context appContext;
        private Surface targetSurface; // 壁纸的 Surface

        // 滚动相关
        private float xOffset = 0f;
        private boolean scrollEnabled = false;
        private int videoWidth, videoHeight;
        private int surfaceWidth, surfaceHeight;

        // 生命周期控制
        private final AtomicBoolean isPlaying = new AtomicBoolean(false);

        public VideoRenderer(Context context, Surface surface) {
            this.appContext = context.getApplicationContext();
            this.targetSurface = surface;

            // 初始化缓冲区
            vertexBuffer = ByteBuffer.allocateDirect(VERTEX_DATA.length * 4)
                    .order(ByteOrder.nativeOrder()).asFloatBuffer();
            vertexBuffer.put(VERTEX_DATA).position(0);

            texCoordBuffer = ByteBuffer.allocateDirect(TEX_COORD_DATA.length * 4)
                    .order(ByteOrder.nativeOrder()).asFloatBuffer();
            texCoordBuffer.put(TEX_COORD_DATA).position(0);

            Matrix.setIdentityM(mSTMatrix, 0);
            Matrix.setIdentityM(mMVPMatrix, 0);
        }

        public void start() {
            if (renderThread != null) return;
            rendering = true;
            renderThread = new Thread(this::renderLoop, "VideoRenderThread");
            renderThread.start();
        }

        private void renderLoop() {
            // 初始化 EGL
            EglHelper eglHelper = new EglHelper();
            if (!eglHelper.init(targetSurface)) {
                Log.e(TAG, "EGL init failed");
                return;
            }

            // OpenGL 初始化
            initGL();

            while (rendering) {
                // 等待新帧可用（或超时）
                synchronized (frameLock) {
                    try {
                        while (!frameAvailable && rendering) {
                            frameLock.wait(100); // 避免无限等待
                        }
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                    if (frameAvailable) {
                        surfaceTexture.updateTexImage();
                        surfaceTexture.getTransformMatrix(mSTMatrix);
                        frameAvailable = false;
                    }
                }

                // 绘制一帧
                drawFrame();

                // 交换缓冲区
                eglHelper.swap();
            }

            // 清理
            eglHelper.release();
        }

        private void initGL() {
            // 创建纹理
            int[] textures = new int[1];
            GLES20.glGenTextures(1, textures, 0);
            textureId = textures[0];
            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureId);
            GLES20.glTexParameterf(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR);
            GLES20.glTexParameterf(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);

            // 创建 SurfaceTexture
            surfaceTexture = new SurfaceTexture(textureId);
            surfaceTexture.setOnFrameAvailableListener(this);

            // 编译着色器
            program = createProgram(VERTEX_SHADER, FRAGMENT_SHADER);
            if (program == 0) {
                throw new RuntimeException("Failed to create program");
            }

            // 获取句柄
            maPositionHandle = GLES20.glGetAttribLocation(program, "position");
            maTexCoordHandle = GLES20.glGetAttribLocation(program, "texCoords");
            muSTMatrixHandle = GLES20.glGetUniformLocation(program, "uSTMatrix");
            muMVPMatrixHandle = GLES20.glGetUniformLocation(program, "uMVPMatrix");
            muLightHandle = GLES20.glGetUniformLocation(program, "light");

            // 解码器将使用此 SurfaceTexture 的 Surface
            decoder = new MediaCodecDecoder(appContext, new Surface(surfaceTexture));
            decoder.setLooping(true); // 默认循环，具体由壁纸配置决定
        }

        private void drawFrame() {
            GLES20.glClearColor(0, 0, 0, 1);
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);

            GLES20.glUseProgram(program);

            // 设置顶点属性
            GLES20.glEnableVertexAttribArray(maPositionHandle);
            GLES20.glVertexAttribPointer(maPositionHandle, 3, GLES20.GL_FLOAT, false, 12, vertexBuffer);

            GLES20.glEnableVertexAttribArray(maTexCoordHandle);
            GLES20.glVertexAttribPointer(maTexCoordHandle, 2, GLES20.GL_FLOAT, false, 8, texCoordBuffer);

            // 应用滚动偏移
            float[] finalSTMatrix = new float[16];
            System.arraycopy(mSTMatrix, 0, finalSTMatrix, 0, 16);
            if (scrollEnabled && videoWidth > 0 && videoHeight > 0 && surfaceWidth > 0 && surfaceHeight > 0) {
                // 计算视频缩放后的宽度（高度填满屏幕）
                float videoAspect = (float) videoWidth / videoHeight;
                int scaledWidth = (int) (surfaceHeight * videoAspect);
                if (scaledWidth > surfaceWidth) {
                    // 可滚动的范围（纹理坐标偏移量）
                    float maxTexOffset = (float) (scaledWidth - surfaceWidth) / scaledWidth; // 范围 0 ~ 1
                    // xOffset 范围 0~1，映射到 0~maxTexOffset，并转化为平移量（纹理坐标范围 0~1）
                    float texOffset = xOffset * maxTexOffset;
                    // 构建平移矩阵并右乘到 ST 矩阵
                    Matrix.translateM(finalSTMatrix, 0, texOffset, 0, 0);
                }
            }

            GLES20.glUniformMatrix4fv(muSTMatrixHandle, 1, false, finalSTMatrix, 0);
            GLES20.glUniformMatrix4fv(muMVPMatrixHandle, 1, false, mMVPMatrix, 0);
            GLES20.glUniform1f(muLightHandle, 1.0f);

            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);

            GLES20.glDisableVertexAttribArray(maPositionHandle);
            GLES20.glDisableVertexAttribArray(maTexCoordHandle);
        }

        @Override
        public void onFrameAvailable(SurfaceTexture surfaceTexture) {
            synchronized (frameLock) {
                frameAvailable = true;
                frameLock.notify();
            }
        }

        public void setDataSource(TianYinWallpaperModel model, long seekUs) {
            if (decoder == null) return;
            decoder.setDataSource(model, seekUs);
            decoder.setLooping(model.isLoop());
            videoWidth = decoder.getVideoWidth();
            videoHeight = decoder.getVideoHeight();
        }

        public void play() {
            if (decoder != null) decoder.play();
            isPlaying.set(true);
        }

        public void pause() {
            if (decoder != null) decoder.pause();
            isPlaying.set(false);
        }

        public boolean isPlaying() {
            return isPlaying.get();
        }

        public long getCurrentPosition() {
            return decoder != null ? decoder.getCurrentPosition() : 0;
        }

        public void seekTo(long positionUs) {
            if (decoder != null) decoder.seekTo(positionUs);
        }

        public void setXOffset(float offset) {
            this.xOffset = offset;
        }

        public void setSurfaceSize(int width, int height) {
            this.surfaceWidth = width;
            this.surfaceHeight = height;
            GLES20.glViewport(0, 0, width, height);
            // 更新投影矩阵（此处使用正交投影，使得顶点坐标 -1..1 对应屏幕）
            Matrix.setIdentityM(mMVPMatrix, 0);
        }

        public void setScrollEnabled(boolean enabled) {
            this.scrollEnabled = enabled;
        }

        public void release() {
            rendering = false;
            synchronized (frameLock) {
                frameLock.notify();
            }
            if (renderThread != null) {
                try {
                    renderThread.join(1000);
                } catch (InterruptedException e) {
                    e.printStackTrace();
                }
                renderThread = null;
            }
            if (decoder != null) {
                decoder.release();
                decoder = null;
            }
            if (surfaceTexture != null) {
                surfaceTexture.release();
                surfaceTexture = null;
            }
            if (textureId != -1) {
                GLES20.glDeleteTextures(1, new int[]{textureId}, 0);
                textureId = -1;
            }
        }

        private int createProgram(String vertexSource, String fragmentSource) {
            int vertexShader = loadShader(GLES20.GL_VERTEX_SHADER, vertexSource);
            if (vertexShader == 0) return 0;
            int fragmentShader = loadShader(GLES20.GL_FRAGMENT_SHADER, fragmentSource);
            if (fragmentShader == 0) return 0;

            int program = GLES20.glCreateProgram();
            GLES20.glAttachShader(program, vertexShader);
            GLES20.glAttachShader(program, fragmentShader);
            GLES20.glLinkProgram(program);
            int[] linkStatus = new int[1];
            GLES20.glGetProgramiv(program, GLES20.GL_LINK_STATUS, linkStatus, 0);
            if (linkStatus[0] != GLES20.GL_TRUE) {
                Log.e(TAG, "Link error: " + GLES20.glGetProgramInfoLog(program));
                GLES20.glDeleteProgram(program);
                return 0;
            }
            return program;
        }

        private int loadShader(int type, String source) {
            int shader = GLES20.glCreateShader(type);
            GLES20.glShaderSource(shader, source);
            GLES20.glCompileShader(shader);
            int[] compiled = new int[1];
            GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, compiled, 0);
            if (compiled[0] == 0) {
                Log.e(TAG, "Shader compile error: " + type + " : " + GLES20.glGetShaderInfoLog(shader));
                GLES20.glDeleteShader(shader);
                return 0;
            }
            return shader;
        }

        // 简单的 EGL 辅助类（基于 EGL14）
        private static class EglHelper {
            private EGLDisplay eglDisplay;
            private EGLContext eglContext;
            private EGLSurface eglSurface;

            public boolean init(Surface surface) {
                eglDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY);
                if (eglDisplay == EGL14.EGL_NO_DISPLAY) {
                    Log.e(TAG, "eglGetDisplay failed");
                    return false;
                }

                int[] version = new int[2];
                if (!EGL14.eglInitialize(eglDisplay, version, 0, version, 1)) {
                    Log.e(TAG, "eglInitialize failed");
                    return false;
                }

                int[] configAttribs = {
                        EGL14.EGL_RED_SIZE, 8,
                        EGL14.EGL_GREEN_SIZE, 8,
                        EGL14.EGL_BLUE_SIZE, 8,
                        EGL14.EGL_ALPHA_SIZE, 8,
                        EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
                        EGL14.EGL_SURFACE_TYPE, EGL14.EGL_WINDOW_BIT,
                        EGL14.EGL_NONE
                };
                EGLConfig[] configs = new EGLConfig[1];
                int[] numConfigs = new int[1];
                if (!EGL14.eglChooseConfig(eglDisplay, configAttribs, 0, configs, 0, 1, numConfigs, 0)) {
                    Log.e(TAG, "eglChooseConfig failed");
                    return false;
                }
                if (numConfigs[0] == 0) {
                    Log.e(TAG, "No matching config");
                    return false;
                }

                int[] contextAttribs = {
                        EGL14.EGL_CONTEXT_CLIENT_VERSION, 2,
                        EGL14.EGL_NONE
                };
                eglContext = EGL14.eglCreateContext(eglDisplay, configs[0], EGL14.EGL_NO_CONTEXT, contextAttribs, 0);
                if (eglContext == EGL14.EGL_NO_CONTEXT) {
                    Log.e(TAG, "eglCreateContext failed");
                    return false;
                }

                eglSurface = EGL14.eglCreateWindowSurface(eglDisplay, configs[0], surface, null, 0);
                if (eglSurface == EGL14.EGL_NO_SURFACE) {
                    Log.e(TAG, "eglCreateWindowSurface failed");
                    return false;
                }

                if (!EGL14.eglMakeCurrent(eglDisplay, eglSurface, eglSurface, eglContext)) {
                    Log.e(TAG, "eglMakeCurrent failed");
                    return false;
                }

                return true;
            }

            public void swap() {
                EGL14.eglSwapBuffers(eglDisplay, eglSurface);
            }

            public void release() {
                if (eglDisplay != EGL14.EGL_NO_DISPLAY) {
                    EGL14.eglMakeCurrent(eglDisplay, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT);
                    if (eglSurface != EGL14.EGL_NO_SURFACE) {
                        EGL14.eglDestroySurface(eglDisplay, eglSurface);
                        eglSurface = EGL14.EGL_NO_SURFACE;
                    }
                    if (eglContext != EGL14.EGL_NO_CONTEXT) {
                        EGL14.eglDestroyContext(eglDisplay, eglContext);
                        eglContext = EGL14.EGL_NO_CONTEXT;
                    }
                    EGL14.eglTerminate(eglDisplay);
                    eglDisplay = EGL14.EGL_NO_DISPLAY;
                }
            }
        }
    }

    // ==================== MediaCodec 解码器（轻量引擎核心）====================

    private static class MediaCodecDecoder {
        private static final String TAG = "MediaCodecDecoder";
        private static final long TIMEOUT_US = 10000;

        private Context context;
        private MediaExtractor extractor;
        private MediaCodec codec;
        private Surface outputSurface;
        private Thread decodeThread;
        private volatile boolean decoding = false;
        private volatile boolean paused = false;
        private final Object pauseLock = new Object();

        private int videoTrackIndex = -1;
        private volatile long presentationTimeUs;
        private volatile long seekPositionUs = -1;
        private boolean inputEOS = false;
        private boolean outputEOS = false;
        private boolean loop = false;

        private int videoWidth, videoHeight;

        public MediaCodecDecoder(Context context, Surface surface) {
            this.context = context;
            this.outputSurface = surface;
        }

        public void setDataSource(TianYinWallpaperModel model, long seekUs) {
            release(); // 释放之前的资源

            extractor = new MediaExtractor();
            try {
                if (model.getVideoUri() != null && !model.getVideoUri().isEmpty()) {
                    extractor.setDataSource(context, Uri.parse(model.getVideoUri()), null);
                } else {
                    extractor.setDataSource(model.getVideoPath());
                }
            } catch (IOException e) {
                Log.e(TAG, "setDataSource failed", e);
                return;
            }

            // 选择视频轨道
            for (int i = 0; i < extractor.getTrackCount(); i++) {
                MediaFormat format = extractor.getTrackFormat(i);
                String mime = format.getString(MediaFormat.KEY_MIME);
                if (mime != null && mime.startsWith("video/")) {
                    videoTrackIndex = i;
                    videoWidth = format.getInteger(MediaFormat.KEY_WIDTH);
                    videoHeight = format.getInteger(MediaFormat.KEY_HEIGHT);
                    break;
                }
            }

            if (videoTrackIndex == -1) {
                Log.e(TAG, "No video track found");
                extractor.release();
                extractor = null;
                return;
            }

            extractor.selectTrack(videoTrackIndex);

            // 创建解码器
            try {
                MediaFormat format = extractor.getTrackFormat(videoTrackIndex);
                String mime = format.getString(MediaFormat.KEY_MIME);
                codec = MediaCodec.createDecoderByType(mime);
                codec.configure(format, outputSurface, null, 0);
                codec.start();
            } catch (IOException e) {
                Log.e(TAG, "Codec creation failed", e);
                if (extractor != null) {
                    extractor.release();
                    extractor = null;
                }
                return;
            }

            if (seekUs > 0) {
                extractor.seekTo(seekUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC);
            }

            decoding = true;
            decodeThread = new Thread(this::decodeLoop, "VideoDecodeThread");
            decodeThread.start();
        }

        private void decodeLoop() {
            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
            while (decoding) {
                synchronized (pauseLock) {
                    while (paused && decoding) {
                        try {
                            pauseLock.wait();
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                            return;
                        }
                    }
                }
                if (!decoding) break;

                // 处理 seek
                if (seekPositionUs >= 0) {
                    extractor.seekTo(seekPositionUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC);
                    codec.flush();
                    inputEOS = false;
                    outputEOS = false;
                    seekPositionUs = -1;
                }

                if (!inputEOS) {
                    int inputBufIndex = codec.dequeueInputBuffer(TIMEOUT_US);
                    if (inputBufIndex >= 0) {
                        ByteBuffer inputBuf = codec.getInputBuffer(inputBufIndex);
                        int sampleSize = extractor.readSampleData(inputBuf, 0);
                        if (sampleSize < 0) {
                            // End of stream
                            codec.queueInputBuffer(inputBufIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                            inputEOS = true;
                        } else {
                            long presentationTime = extractor.getSampleTime();
                            codec.queueInputBuffer(inputBufIndex, 0, sampleSize, presentationTime, 0);
                            extractor.advance();
                        }
                    }
                }

                // 处理输出
                int outputBufIndex = codec.dequeueOutputBuffer(info, TIMEOUT_US);
                if (outputBufIndex >= 0) {
                    if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                        outputEOS = true;
                        if (loop) {
                            // 循环：重新开始
                            extractor.seekTo(0, MediaExtractor.SEEK_TO_PREVIOUS_SYNC);
                            codec.flush();
                            inputEOS = false;
                            outputEOS = false;
                        }
                    }
                    codec.releaseOutputBuffer(outputBufIndex, true);
                    presentationTimeUs = info.presentationTimeUs;
                } else if (outputBufIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    // 可忽略
                }
            }
        }

        public void play() {
            synchronized (pauseLock) {
                paused = false;
                pauseLock.notifyAll();
            }
        }

        public void pause() {
            synchronized (pauseLock) {
                paused = true;
            }
        }

        public void seekTo(long positionUs) {
            this.seekPositionUs = positionUs;
        }

        public long getCurrentPosition() {
            return presentationTimeUs;
        }

        public void setLooping(boolean loop) {
            this.loop = loop;
        }

        public int getVideoWidth() {
            return videoWidth;
        }

        public int getVideoHeight() {
            return videoHeight;
        }

        public void release() {
            decoding = false;
            synchronized (pauseLock) {
                pauseLock.notifyAll();
            }
            if (decodeThread != null) {
                try {
                    decodeThread.join(1000);
                } catch (InterruptedException e) {
                    e.printStackTrace();
                }
                decodeThread = null;
            }
            if (codec != null) {
                codec.stop();
                codec.release();
                codec = null;
            }
            if (extractor != null) {
                extractor.release();
                extractor = null;
            }
        }
    }
}