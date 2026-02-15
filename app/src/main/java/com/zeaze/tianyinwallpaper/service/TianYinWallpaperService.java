package com.zeaze.tianyinwallpaper.service;

import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.SurfaceTexture;
import android.media.MediaPlayer;
import android.net.Uri;
import android.opengl.GLES11Ext;
import android.opengl.GLES20;
import android.opengl.GLUtils;
import android.opengl.Matrix;
import android.os.Handler;
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

import javax.microedition.khronos.egl.EGL10;
import javax.microedition.khronos.egl.EGLConfig;
import javax.microedition.khronos.egl.EGLContext;
import javax.microedition.khronos.egl.EGLDisplay;
import javax.microedition.khronos.egl.EGLSurface;

public class TianYinWallpaperService extends WallpaperService {
    String TAG = "TianYinSolaWallpaperService";
    private SharedPreferences pref;
    private long lastTime = 0;

    private boolean isOnlyOne = false;
    private boolean needBackgroundPlay = false;
    private boolean wallpaperScroll = false;

    private static final int WALLPAPER_TYPE_IMAGE = 0;
    private static final int WALLPAPER_TYPE_VIDEO = 1;

    @Override
    public Engine onCreateEngine() {
        return new TianYinSolaEngine();
    }

    class TianYinSolaEngine extends WallpaperService.Engine implements SurfaceTexture.OnFrameAvailableListener {
        private MediaPlayer mediaPlayer;
        private List<TianYinWallpaperModel> list;
        private int index = -1;
        private SurfaceHolder surfaceHolder;
        private boolean hasVideo;

        private boolean pageChange = false;
        private float currentXOffset = 0.5f;

        // OpenGL 相关
        private EglThread eglThread;
        private SurfaceTexture videoSurfaceTexture;
        private int videoTextureId = -1;
        private int imageTextureId = -1;
        private boolean updateSurface = false;
        
        private int contentWidth = 0;
        private int contentHeight = 0;
        private int screenWidth = 0;
        private int screenHeight = 0;

        public TianYinSolaEngine() {
            String s = FileUtil.loadData(getApplicationContext(), FileUtil.wallpaperPath);
            list = JSON.parseArray(s, TianYinWallpaperModel.class);
            hasVideo = true;
            lastTime = System.currentTimeMillis() / 1000;
            pref = getSharedPreferences(App.TIANYIN, MODE_PRIVATE);
            pageChange = pref.getBoolean("pageChange", false);
            needBackgroundPlay = pref.getBoolean("needBackgroundPlay", false);
            wallpaperScroll = pref.getBoolean("wallpaperScroll", false);
        }

        private boolean isCurrentWallpaperVideo() {
            return list != null && index >= 0 && index < list.size() && list.get(index).getType() == WALLPAPER_TYPE_VIDEO;
        }

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
            if (i >= list.size()) i = 0;
            while (!isIf(i)) {
                if (i == index) return getNoIfIndex();
                i++;
                if (i >= list.size()) i = 0;
            }
            return i;
        }

        private int getNoIfIndex() {
            int i = index + 1;
            if (i >= list.size()) i = 0;
            while (!((list.get(i).getStartTime() == -1 || list.get(i).getEndTime() == -1))) {
                if (i == index) {
                    i = index + 1;
                    if (i >= list.size()) i = 0;
                    return i;
                }
                i++;
                if (i >= list.size()) i = 0;
            }
            return i;
        }

        private boolean isIf(int index) {
            TianYinWallpaperModel model = list.get(index);
            if (model.getStartTime() == -1 || model.getEndTime() == -1) return false;
            int now = getTime();
            if (model.getStartTime() == model.getEndTime() && model.getStartTime() == now) return true;
            if (model.getStartTime() <= now && now < model.getEndTime()) return true;
            if (now < model.getEndTime() && model.getEndTime() < model.getStartTime()) return true;
            if (model.getEndTime() < model.getStartTime() && model.getStartTime() <= now) return true;
            return false;
        }

        private int getTime() {
            Calendar cal = Calendar.getInstance();
            cal.setTime(new Date());
            return cal.get(Calendar.HOUR_OF_DAY) * 60 + cal.get(Calendar.MINUTE);
        }

        @Override
        public void onSurfaceCreated(SurfaceHolder holder) {
            super.onSurfaceCreated(holder);
            this.surfaceHolder = holder;
            screenWidth = holder.getSurfaceFrame().width();
            screenHeight = holder.getSurfaceFrame().height();
            
            eglThread = new EglThread(holder);
            eglThread.start();
        }

        @Override
        public void onSurfaceChanged(SurfaceHolder holder, int format, int width, int height) {
            super.onSurfaceChanged(holder, format, width, height);
            screenWidth = width;
            screenHeight = height;
        }

        private void updateCurrentContent() {
            if (isCurrentWallpaperVideo()) {
                prepareVideo();
            } else {
                prepareImage();
            }
        }

        private void prepareVideo() {
            try {
                if (mediaPlayer == null) mediaPlayer = new MediaPlayer();
                mediaPlayer.reset();
                TianYinWallpaperModel currentModel = list.get(index);
                if (currentModel.getVideoUri() != null && !currentModel.getVideoUri().isEmpty()) {
                    mediaPlayer.setDataSource(getApplicationContext(), Uri.parse(currentModel.getVideoUri()));
                } else {
                    mediaPlayer.setDataSource(currentModel.getVideoPath());
                }

                Surface surface = new Surface(videoSurfaceTexture);
                mediaPlayer.setSurface(surface);
                surface.release();

                mediaPlayer.setLooping(currentModel.isLoop());
                mediaPlayer.setVolume(0, 0);
                mediaPlayer.setOnVideoSizeChangedListener((mp, width, height) -> {
                    contentWidth = width;
                    contentHeight = height;
                });
                mediaPlayer.prepare();
                mediaPlayer.start();
            } catch (IOException e) {
                e.printStackTrace();
            }
        }

        private void prepareImage() {
            if (mediaPlayer != null && mediaPlayer.isPlaying()) mediaPlayer.stop();
            Bitmap bitmap = getBitmap();
            if (bitmap != null) {
                contentWidth = bitmap.getWidth();
                contentHeight = bitmap.getHeight();
                eglThread.updateImageTexture(bitmap);
            }
        }

        private Bitmap getBitmap() {
            TianYinWallpaperModel currentModel = list.get(index);
            if (currentModel.getImgUri() != null && !currentModel.getImgUri().isEmpty()) {
                InputStream is = null;
                try {
                    is = getApplicationContext().getContentResolver().openInputStream(Uri.parse(currentModel.getImgUri()));
                    return BitmapFactory.decodeStream(is);
                } catch (Exception e) {
                    Log.e(TAG, "Error reading bitmap from URI", e);
                } finally {
                    if (is != null) try { is.close(); } catch (IOException ignored) {}
                }
            }
            return BitmapFactory.decodeFile(currentModel.getImgPath());
        }

        int page = -1;
        @Override
        public void onOffsetsChanged(float xOffset, float yOffset, float xOffsetStep, float yOffsetStep, int xPixelOffset, int yPixelOffset) {
            super.onOffsetsChanged(xOffset, yOffset, xOffsetStep, yOffsetStep, xPixelOffset, yPixelOffset);
            
            if (wallpaperScroll) {
                currentXOffset = xOffset;
                if (eglThread != null) eglThread.requestRender();
            }

            if (!pageChange) return;
            float dx = xOffset;
            while (dx > xOffsetStep) dx = dx - xOffset;
            dx = dx / xOffsetStep;
            if (page == -1) {
                if (dx < 0.1 || dx > 0.9) page = Math.round(xOffset / xOffsetStep);
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

        private long lastPlayTime;
        @Override
        public void onVisibilityChanged(boolean visible) {
            super.onVisibilityChanged(visible);
            if (visible) {
                if (isCurrentWallpaperVideo() && mediaPlayer != null) {
                    mediaPlayer.setLooping(list.get(index).isLoop());
                    if (!mediaPlayer.isPlaying()) mediaPlayer.start();
                    if (isOnlyOne && lastPlayTime > 0 && needBackgroundPlay) {
                        long nowTime = (lastPlayTime + System.currentTimeMillis() - lastTime * 1000) % (mediaPlayer.getDuration());
                        mediaPlayer.seekTo((int) nowTime);
                    }
                }
            } else {
                if (mediaPlayer != null) lastPlayTime = mediaPlayer.getCurrentPosition();
                if (getNextIndex()) {
                    updateCurrentContent();
                } else {
                    if (isCurrentWallpaperVideo() && mediaPlayer != null) {
                        mediaPlayer.setLooping(false);
                        mediaPlayer.pause();
                    }
                }
            }
        }

        @Override
        public void onFrameAvailable(SurfaceTexture surfaceTexture) {
            updateSurface = true;
            if (eglThread != null) eglThread.requestRender();
        }

        @Override
        public void onDestroy() {
            super.onDestroy();
            if (mediaPlayer != null) {
                mediaPlayer.release();
                mediaPlayer = null;
            }
            if (eglThread != null) eglThread.finish();
        }

        // --- OpenGL 渲染线程 ---
        private class EglThread extends Thread {
            private SurfaceHolder surfaceHolder;
            private EGL10 egl;
            private EGLDisplay eglDisplay;
            private EGLConfig eglConfig;
            private EGLContext eglContext;
            private EGLSurface eglSurface;
            private boolean running = true;

            private int videoProgram, imageProgram;
            private FloatBuffer vertexBuffer, textureBuffer;
            private float[] mSTMatrix = new float[16];
            private float[] mMVPMatrix = new float[16];

            public EglThread(SurfaceHolder holder) {
                this.surfaceHolder = holder;
                float[] vertexData = {-1f, -1f, 0f, 1f, -1f, 0f, -1f, 1f, 0f, 1f, 1f, 0f};
                vertexBuffer = ByteBuffer.allocateDirect(vertexData.length * 4).order(ByteOrder.nativeOrder()).asFloatBuffer().put(vertexData);
                vertexBuffer.position(0);

                float[] textureData = {0f, 0f, 1f, 0f, 0f, 1f, 1f, 1f};
                textureBuffer = ByteBuffer.allocateDirect(textureData.length * 4).order(ByteOrder.nativeOrder()).asFloatBuffer().put(textureData);
                textureBuffer.position(0);
            }

            @Override
            public void run() {
                initEGL();
                initGL();
                new Handler(Looper.getMainLooper()).post(() -> {
                    videoSurfaceTexture = new SurfaceTexture(videoTextureId);
                    videoSurfaceTexture.setOnFrameAvailableListener(TianYinSolaEngine.this);
                    if (index == -1) getNextIndex();
                    updateCurrentContent();
                });

                while (running) {
                    synchronized (this) {
                        try { wait(); } catch (InterruptedException e) { break; }
                    }
                    if (running) draw();
                }
                releaseEGL();
            }

            public void requestRender() { synchronized (this) { notify(); } }
            public void finish() { running = false; requestRender(); }

            private void initEGL() {
                egl = (EGL10) EGLContext.getEGL();
                eglDisplay = egl.eglGetDisplay(EGL10.EGL_DEFAULT_DISPLAY);
                egl.eglInitialize(eglDisplay, null);
                int[] configAttribs = {EGL10.EGL_RENDERABLE_TYPE, 4, EGL10.EGL_RED_SIZE, 8, EGL10.EGL_GREEN_SIZE, 8, EGL10.EGL_BLUE_SIZE, 8, EGL10.EGL_ALPHA_SIZE, 8, EGL10.EGL_NONE};
                EGLConfig[] configs = new EGLConfig[1];
                int[] numConfigs = new int[1];
                egl.eglChooseConfig(eglDisplay, configAttribs, configs, 1, numConfigs);
                eglConfig = configs[0];
                eglContext = egl.eglCreateContext(eglDisplay, eglConfig, EGL10.EGL_NO_CONTEXT, new int[]{0x3098, 2, EGL10.EGL_NONE});
                eglSurface = egl.eglCreateWindowSurface(eglDisplay, eglConfig, surfaceHolder, null);
                egl.eglMakeCurrent(eglDisplay, eglSurface, eglSurface, eglContext);
            }

            private void initGL() {
                String vs = "uniform mat4 uMVPMatrix; uniform mat4 uSTMatrix; attribute vec4 aPosition; attribute vec4 aTextureCoord; varying vec2 vTextureCoord; void main() { gl_Position = uMVPMatrix * aPosition; vTextureCoord = (uSTMatrix * aTextureCoord).xy; }";
                String fsVideo = "#extension GL_OES_EGL_image_external : require\n precision mediump float; varying vec2 vTextureCoord; uniform samplerExternalOES sTexture; void main() { gl_FragColor = texture2D(sTexture, vTextureCoord); }";
                String fsImage = "precision mediump float; varying vec2 vTextureCoord; uniform sampler2D sTexture; void main() { gl_FragColor = texture2D(sTexture, vTextureCoord); }";

                videoProgram = createProgram(vs, fsVideo);
                imageProgram = createProgram(vs, fsImage);

                int[] tex = new int[2];
                GLES20.glGenTextures(2, tex, 0);
                videoTextureId = tex[0];
                imageTextureId = tex[1];

                GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, videoTextureId);
                GLES20.glTexParameterf(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR);
                GLES20.glTexParameterf(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);

                GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, imageTextureId);
                GLES20.glTexParameterf(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR);
                GLES20.glTexParameterf(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);
            }

            public void updateImageTexture(Bitmap bitmap) {
                GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, imageTextureId);
                GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bitmap, 0);
                bitmap.recycle();
                requestRender();
            }

            private void draw() {
                if (isCurrentWallpaperVideo() && updateSurface) {
                    videoSurfaceTexture.updateTexImage();
                    videoSurfaceTexture.getTransformMatrix(mSTMatrix);
                    updateSurface = false;
                } else if (!isCurrentWallpaperVideo()) {
                    Matrix.setIdentityM(mSTMatrix, 0);
                }

                GLES20.glViewport(0, 0, screenWidth, screenHeight);
                GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);

                int activeProgram = isCurrentWallpaperVideo() ? videoProgram : imageProgram;
                int activeTexture = isCurrentWallpaperVideo() ? videoTextureId : imageTextureId;
                int textureTarget = isCurrentWallpaperVideo() ? GLES11Ext.GL_TEXTURE_EXTERNAL_OES : GLES20.GL_TEXTURE_2D;

                GLES20.glUseProgram(activeProgram);
                
                // 计算滚动和缩放矩阵
                Matrix.setIdentityM(mMVPMatrix, 0);
                if (contentWidth > 0 && contentHeight > 0) {
                    float contentAspect = (float) contentWidth / contentHeight;
                    float screenAspect = (float) screenWidth / screenHeight;
                    float scaleX = 1.0f, scaleY = 1.0f;
                    float tx = 0.0f;

                    if (contentAspect > screenAspect) {
                        scaleX = contentAspect / screenAspect;
                        if (wallpaperScroll) {
                            float maxTransX = scaleX - 1.0f;
                            tx = maxTransX - (currentXOffset * 2.0f * maxTransX);
                        }
                    } else {
                        scaleY = screenAspect / contentAspect;
                    }
                    Matrix.scaleM(mMVPMatrix, 0, scaleX, scaleY, 1.0f);
                    Matrix.translateM(mMVPMatrix, 0, tx / scaleX, 0, 0);
                }

                int ph = GLES20.glGetAttribLocation(activeProgram, "aPosition");
                int tch = GLES20.glGetAttribLocation(activeProgram, "aTextureCoord");
                int mvph = GLES20.glGetUniformLocation(activeProgram, "uMVPMatrix");
                int sth = GLES20.glGetUniformLocation(activeProgram, "uSTMatrix");

                GLES20.glUniformMatrix4fv(mvph, 1, false, mMVPMatrix, 0);
                GLES20.glUniformMatrix4fv(sth, 1, false, mSTMatrix, 0);

                GLES20.glVertexAttribPointer(ph, 3, GLES20.GL_FLOAT, false, 12, vertexBuffer);
                GLES20.glEnableVertexAttribArray(ph);
                GLES20.glVertexAttribPointer(tch, 2, GLES20.GL_FLOAT, false, 8, textureBuffer);
                GLES20.glEnableVertexAttribArray(tch);

                GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
                GLES20.glBindTexture(textureTarget, activeTexture);
                GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);

                egl.eglSwapBuffers(eglDisplay, eglSurface);
            }

            private int createProgram(String vs, String fs) {
                int vShader = GLES20.glCreateShader(GLES20.GL_VERTEX_SHADER);
                GLES20.glShaderSource(vShader, vs);
                GLES20.glCompileShader(vShader);
                int fShader = GLES20.glCreateShader(GLES20.GL_FRAGMENT_SHADER);
                GLES20.glShaderSource(fShader, fs);
                GLES20.glCompileShader(fShader);
                int prog = GLES20.glCreateProgram();
                GLES20.glAttachShader(prog, vShader);
                GLES20.glAttachShader(prog, fShader);
                GLES20.glLinkProgram(prog);
                return prog;
            }

            private void releaseEGL() {
                if (eglDisplay != null) {
                    egl.eglMakeCurrent(eglDisplay, EGL10.EGL_NO_SURFACE, EGL10.EGL_NO_SURFACE, EGL10.EGL_NO_CONTEXT);
                    egl.eglDestroySurface(eglDisplay, eglSurface);
                    egl.eglDestroyContext(eglDisplay, eglContext);
                    egl.eglTerminate(eglDisplay);
                }
            }
        }
    }
}