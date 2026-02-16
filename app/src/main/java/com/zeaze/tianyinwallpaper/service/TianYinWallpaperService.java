package com.zeaze.tianyinwallpaper.service;

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
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

public class TianYinWallpaperService extends WallpaperService {
    private final String TAG = "TianYinGL";
    private static final String PREF_NAME = "wallpaper_pref";
    private static final String KEY_INDEX = "current_index";

    @Override
    public Engine onCreateEngine() {
        return new TianYinSolaEngine();
    }

    class TianYinSolaEngine extends WallpaperService.Engine implements SurfaceTexture.OnFrameAvailableListener {
        private MediaPlayer mediaPlayer;          // 仅在EGL线程中访问
        private EglThread eglThread;
        private List<TianYinWallpaperModel> list;
        private int index = -1;
        private float currentXOffset = 0.5f;
        private final AtomicBoolean updateSurface = new AtomicBoolean(false);
        private SharedPreferences preferences;

        @Override
        public void onCreate(SurfaceHolder surfaceHolder) {
            super.onCreate(surfaceHolder);
            surfaceHolder.setFormat(PixelFormat.RGBX_8888);
            preferences = getSharedPreferences(PREF_NAME, MODE_PRIVATE);
            index = preferences.getInt(KEY_INDEX, -1);

            try {
                String s = FileUtil.loadData(getApplicationContext(), FileUtil.wallpaperPath);
                list = JSON.parseArray(s, TianYinWallpaperModel.class);
                if (list != null && (index < 0 || index >= list.size())) {
                    index = 0;
                }
            } catch (Exception ignored) {
                list = null;
                index = -1;
            }
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
        public void onSurfaceDestroyed(SurfaceHolder holder) {
            super.onSurfaceDestroyed(holder);
            // Surface销毁，MediaPlayer和EGL线程都需清理（操作已在EGL线程中处理）
            if (eglThread != null) {
                eglThread.finish();
                eglThread = null;
            }
        }

        @Override
        public void onVisibilityChanged(boolean visible) {
            super.onVisibilityChanged(visible);
            // 将可见性变化交给EGL线程处理，保证MediaPlayer操作线程安全
            if (eglThread != null) {
                eglThread.onVisibilityChanged(visible);
            }
        }

        // 对外暴露的切换壁纸方法（可用于定时切换）
        public void nextWallpaper() {
            if (eglThread != null) {
                eglThread.switchToNextWallpaper();
            }
        }

        @Override
        public void onOffsetsChanged(float xOffset, float yOffset, float xOffsetStep, float yOffsetStep, int xPixelOffset, int yPixelOffset) {
            this.currentXOffset = xOffset;
            if (eglThread != null) eglThread.requestRender();
        }

        @Override
        public void onFrameAvailable(SurfaceTexture surfaceTexture) {
            updateSurface.set(true);
            if (eglThread != null) eglThread.requestRender();
        }

        @Override
        public void onDestroy() {
            super.onDestroy();
            if (eglThread != null) {
                eglThread.finish();
                eglThread = null;
            }
        }

        // --- EGL渲染线程（所有MediaPlayer操作均在此线程）---
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

            // 处理可见性变化（在EGL线程中执行）
            public void onVisibilityChanged(boolean visible) {
                postRunnable(() -> {
                    if (visible) {
                        // 解锁/回到桌面：尝试恢复播放
                        if (mediaPlayer != null) {
                            try {
                                // 如果当前是视频且未播放，则启动
                                if (!mediaPlayer.isPlaying() && isCurrentVideo()) {
                                    mediaPlayer.start();
                                }
                            } catch (Exception e) {
                                Log.e(TAG, "resume mediaplayer error", e);
                                // 出错时重新加载当前壁纸
                                loadContent();
                            }
                        } else {
                            // mediaPlayer为null（可能是图片或之前出错），重新加载当前壁纸
                            if (isCurrentValid()) {
                                loadContent();
                            }
                        }
                        requestRender();
                    } else {
                        // 锁屏：暂停当前视频并切换到下一个壁纸
                        if (mediaPlayer != null && mediaPlayer.isPlaying()) {
                            try {
                                mediaPlayer.pause();
                            } catch (Exception e) {
                                Log.e(TAG, "pause error", e);
                            }
                        }
                        // 切换到下一个壁纸（内部已处理索引更新和加载）
                        switchToNextWallpaper();
                    }
                });
            }

            // 切换到下一个壁纸（在EGL线程中执行）
            public void switchToNextWallpaper() {
                postRunnable(() -> {
                    if (list == null || list.isEmpty()) return;
                    index = (index + 1) % list.size();
                    preferences.edit().putInt(KEY_INDEX, index).apply();
                    loadContent();
                });
            }

            // 判断当前索引是否有效且为视频
            private boolean isCurrentVideo() {
                return list != null && index >= 0 && index < list.size() && list.get(index).getType() == 1;
            }

            private boolean isCurrentValid() {
                return list != null && index >= 0 && index < list.size();
            }

            // 加载当前索引的内容（图片或视频）
            private void loadContent() {
                if (!isCurrentValid()) return;
                TianYinWallpaperModel model = list.get(index);
                if (model.getType() == 1) prepareVideo(model);
                else prepareImage(model);
            }

            private void prepareVideo(TianYinWallpaperModel model) {
                try {
                    // 释放旧的MediaPlayer
                    if (mediaPlayer != null) {
                        mediaPlayer.release();
                        mediaPlayer = null;
                    }
                    mediaPlayer = new MediaPlayer();

                    mediaPlayer.setDataSource(getApplicationContext(), Uri.parse(model.getVideoUri()));

                    SurfaceTexture st = getVideoST();
                    if (st == null) return;
                    Surface surface = new Surface(st);
                    mediaPlayer.setSurface(surface);
                    surface.release();

                    mediaPlayer.setLooping(model.isLoop());
                    mediaPlayer.setVolume(0, 0);
                    mediaPlayer.setOnPreparedListener(mp -> {
                        setContentSize(mp.getVideoWidth(), mp.getVideoHeight());
                        mp.start();  // 准备好后自动播放
                    });
                    mediaPlayer.setOnErrorListener((mp, what, extra) -> {
                        Log.e(TAG, "MediaPlayer error: " + what + ", " + extra);
                        // 出错时重新加载当前壁纸
                        postRunnable(this::loadContent);
                        return true;
                    });
                    mediaPlayer.prepareAsync();
                    resetVideoMatrix();
                } catch (Exception e) {
                    Log.e(TAG, "Video error", e);
                }
            }

            private void prepareImage(TianYinWallpaperModel model) {
                // 释放视频资源
                if (mediaPlayer != null) {
                    mediaPlayer.release();
                    mediaPlayer = null;
                }
                try {
                    InputStream is = getApplicationContext().getContentResolver().openInputStream(Uri.parse(model.getImgUri()));
                    Bitmap bitmap = BitmapFactory.decodeStream(is);
                    is.close();
                    if (bitmap != null) {
                        setContentSize(bitmap.getWidth(), bitmap.getHeight());
                        uploadBitmap(bitmap);
                    }
                } catch (Exception e) {
                    Log.e(TAG, "Image error", e);
                }
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

                boolean isVid = isCurrentVideo();
                float[] stMat = new float[16];

                if (isVid) {
                    if (updateSurface.getAndSet(false)) {
                        try {
                            videoST.updateTexImage();
                            videoST.getTransformMatrix(videoSTMatrix);
                        } catch (Exception ignored) {}
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

            // ---------- EGL初始化代码（保持不变）----------
            @Override
            protected void onLooperPrepared() {
                if (!initEGL()) return;
                initGL();
                handler = new Handler(getLooper());
                postRunnable(() -> {
                    if (index == -1 && list != null && !list.isEmpty()) {
                        index = 0;
                        preferences.edit().putInt(KEY_INDEX, index).apply();
                    }
                    loadContent();
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