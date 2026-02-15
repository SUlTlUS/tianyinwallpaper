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
import android.os.HandlerThread;
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
        private boolean pageChange = false;
        private volatile float currentXOffset = 0.5f;

        private EglThread eglThread;
        private int screenWidth, screenHeight;
        private final AtomicBoolean updateSurface = new AtomicBoolean(false);

        public TianYinSolaEngine() {
            String s = FileUtil.loadData(getApplicationContext(), FileUtil.wallpaperPath);
            list = JSON.parseArray(s, TianYinWallpaperModel.class);
            lastTime = System.currentTimeMillis() / 1000;
            pref = getSharedPreferences(App.TIANYIN, MODE_PRIVATE);
            pageChange = pref.getBoolean("pageChange", false);
            needBackgroundPlay = pref.getBoolean("needBackgroundPlay", false);
            wallpaperScroll = pref.getBoolean("wallpaperScroll", false);
        }

        @Override
        public void onSurfaceCreated(SurfaceHolder holder) {
            super.onSurfaceCreated(holder);
            screenWidth = holder.getSurfaceFrame().width();
            screenHeight = holder.getSurfaceFrame().height();
            eglThread = new EglThread(holder);
            eglThread.start();
        }

        @Override
        public void onOffsetsChanged(float xOffset, float yOffset, float xOffsetStep, float yOffsetStep, int xPixelOffset, int yPixelOffset) {
            if (wallpaperScroll) {
                currentXOffset = xOffset;
                // 滑动时不再 postRunnable，而是由 GL 循环或 requestRender 合并请求
                if (eglThread != null) eglThread.requestRender();
            }
        }

        @Override
        public void onVisibilityChanged(boolean visible) {
            super.onVisibilityChanged(visible);
            if (visible) {
                if (mediaPlayer != null && isCurrentWallpaperVideo()) {
                    mediaPlayer.start();
                }
                // 解决返回桌面暂停的关键：强制刷新 GL 环境
                if (eglThread != null) eglThread.requestRender();
            } else {
                // 如果设置了需要后台运行且当前是视频，则不暂停
                if (mediaPlayer != null && isCurrentWallpaperVideo()) {
                    if (!needBackgroundPlay) {
                        mediaPlayer.pause();
                    }
                }
                if (getNextIndex()) {
                    if (eglThread != null) eglThread.postRunnable(this::updateCurrentContent);
                }
            }
        }

        private boolean isCurrentWallpaperVideo() {
            return list != null && index >= 0 && index < list.size() && list.get(index).getType() == WALLPAPER_TYPE_VIDEO;
        }

        private void updateCurrentContent() {
            if (index < 0 || index >= list.size()) return;
            TianYinWallpaperModel model = list.get(index);
            if (model.getType() == WALLPAPER_TYPE_VIDEO) {
                prepareVideo(model);
            } else {
                prepareImage(model);
            }
        }

        private void prepareVideo(TianYinWallpaperModel model) {
            try {
                if (mediaPlayer == null) mediaPlayer = new MediaPlayer();
                mediaPlayer.reset();
                if (model.getVideoUri() != null && !model.getVideoUri().isEmpty()) {
                    mediaPlayer.setDataSource(getApplicationContext(), Uri.parse(model.getVideoUri()));
                } else {
                    mediaPlayer.setDataSource(model.getVideoPath());
                }
                
                Surface surface = new Surface(eglThread.getVideoST());
                mediaPlayer.setSurface(surface);
                surface.release();

                mediaPlayer.setLooping(model.isLoop());
                mediaPlayer.setVolume(0, 0);
                mediaPlayer.setOnPreparedListener(mp -> {
                    eglThread.setContentSize(mp.getVideoWidth(), mp.getVideoHeight());
                    mp.start();
                });
                mediaPlayer.prepareAsync();
            } catch (Exception e) {
                Log.e(TAG, "Video prepare failed", e);
            }
        }

        private void prepareImage(TianYinWallpaperModel model) {
            if (mediaPlayer != null) mediaPlayer.reset();
            Bitmap bitmap = null;
            try {
                if (model.getImgUri() != null && !model.getImgUri().isEmpty()) {
                    InputStream is = getApplicationContext().getContentResolver().openInputStream(Uri.parse(model.getImgUri()));
                    bitmap = BitmapFactory.decodeStream(is);
                    if (is != null) is.close();
                } else {
                    bitmap = BitmapFactory.decodeFile(model.getImgPath());
                }
            } catch (Exception e) {
                Log.e(TAG, "Image decode failed", e);
            }

            if (bitmap != null) {
                eglThread.setContentSize(bitmap.getWidth(), bitmap.getHeight());
                eglThread.updateImageTexture(bitmap);
            }
        }

        private boolean getNextIndex() {
            if (index != -1) {
                int minTime = pref.getInt("minTime", 1);
                if (System.currentTimeMillis() / 1000 - lastTime <= minTime) {
                    isOnlyOne = true;
                    return false;
                }
            }
            lastTime = System.currentTimeMillis() / 1000;
            boolean isRand = pref.getBoolean("rand", false);
            int step = isRand ? (int) (Math.random() * list.size()) + 1 : 1;
            int lastIndex = index;
            while (step > 0) {
                if (index == -1) index = list.size() - 1;
                index = getIfIndex();
                step--;
            }
            isOnlyOne = (lastIndex == index);
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
                if (i == index) return (index + 1) % list.size();
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
            if (model.getEndTime() < model.getStartTime() && (now >= model.getStartTime() || now < model.getEndTime())) return true;
            return false;
        }

        private int getTime() {
            Calendar cal = Calendar.getInstance();
            return cal.get(Calendar.HOUR_OF_DAY) * 60 + cal.get(Calendar.MINUTE);
        }

        @Override
        public void onFrameAvailable(SurfaceTexture surfaceTexture) {
            updateSurface.set(true);
            if (eglThread != null) eglThread.requestRender();
        }

        @Override
        public void onDestroy() {
            super.onDestroy();
            if (mediaPlayer != null) mediaPlayer.release();
            if (eglThread != null) eglThread.finish();
        }

        // --- 深度优化 GL 渲染引擎 ---
        private class EglThread extends HandlerThread {
            private final SurfaceHolder holder;
            private EGL10 egl;
            private EGLDisplay display;
            private EGLContext context;
            private EGLSurface eglSurface;
            private Handler handler;

            private SurfaceTexture videoST;
            private int videoTexId = -1, imageTexId = -1;
            private int vProg, iProg;
            private int vPh, vTh, vMvph, vSth; // Video 句柄缓存
            private int iPh, iTh, iMvph, iSth; // Image 句柄缓存
            private FloatBuffer vBuf, tBuf;
            private int mContentW, mContentH;
            private final float[] stMat = new float[16];
            private final float[] mvpMat = new float[16];
            
            private final AtomicBoolean isRenderPending = new AtomicBoolean(false);

            public EglThread(SurfaceHolder holder) {
                super("EglThread", android.os.Process.THREAD_PRIORITY_DISPLAY);
                this.holder = holder;
                Matrix.setIdentityM(stMat, 0);
                float[] vData = {-1,-1,0, 1,-1,0, -1,1,0, 1,1,0};
                vBuf = ByteBuffer.allocateDirect(vData.length*4).order(ByteOrder.nativeOrder()).asFloatBuffer().put(vData);
                vBuf.position(0);
                float[] tData = {0,0, 1,0, 0,1, 1,1};
                tBuf = ByteBuffer.allocateDirect(tData.length*4).order(ByteOrder.nativeOrder()).asFloatBuffer().put(tData);
                tBuf.position(0);
            }

            public SurfaceTexture getVideoST() { return videoST; }
            public void setContentSize(int w, int h) { mContentW = w; mContentH = h; }

            @Override
            protected void onLooperPrepared() {
                initEGL();
                initGL();
                handler = new Handler(getLooper());
                postRunnable(() -> {
                    if (index == -1) getNextIndex();
                    updateCurrentContent();
                });
            }

            public void postRunnable(Runnable r) {
                if (handler != null) handler.post(r);
            }

            // 核心优化：合并渲染请求，解决滑动卡顿
            public void requestRender() {
                if (isRenderPending.compareAndSet(false, true)) {
                    if (handler != null) handler.post(() -> {
                        isRenderPending.set(false);
                        draw();
                    });
                }
            }

            public void updateImageTexture(Bitmap bitmap) {
                postRunnable(() -> {
                    GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, imageTexId);
                    GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bitmap, 0);
                    bitmap.recycle();
                    requestRender();
                });
            }

            private void initEGL() {
                egl = (EGL10) EGLContext.getEGL();
                display = egl.eglGetDisplay(EGL10.EGL_DEFAULT_DISPLAY);
                egl.eglInitialize(display, null);
                int[] attr = {0x3040, 4, 0x3024, 8, 0x3023, 8, 0x3022, 8, 0x3038};
                EGLConfig[] configs = new EGLConfig[1];
                int[] num = new int[1];
                egl.eglChooseConfig(display, attr, configs, 1, num);
                context = egl.eglCreateContext(display, configs[0], EGL10.EGL_NO_CONTEXT, new int[]{0x3098, 2, 0x3038});
                eglSurface = egl.eglCreateWindowSurface(display, configs[0], holder, null);
                egl.eglMakeCurrent(display, eglSurface, eglSurface, context);
            }

            private void initGL() {
                String vs = "uniform mat4 uMVP; uniform mat4 uST; attribute vec4 aPos; attribute vec2 aTex; varying vec2 vTex; void main(){ gl_Position = uMVP * aPos; vTex = (uST * vec4(aTex,0,1)).xy; }";
                String fsV = "#extension GL_OES_EGL_image_external : require\n precision mediump float; varying vec2 vTex; uniform samplerExternalOES sTex; void main(){ gl_FragColor = texture2D(sTex, vTex); }";
                String fsI = "precision mediump float; varying vec2 vTex; uniform sampler2D sTex; void main(){ gl_FragColor = texture2D(sTex, vTex); }";
                
                vProg = createProg(vs, fsV);
                vPh = GLES20.glGetAttribLocation(vProg, "aPos");
                vTh = GLES20.glGetAttribLocation(vProg, "aTex");
                vMvph = GLES20.glGetUniformLocation(vProg, "uMVP");
                vSth = GLES20.glGetUniformLocation(vProg, "uST");

                iProg = createProg(vs, fsI);
                iPh = GLES20.glGetAttribLocation(iProg, "aPos");
                iTh = GLES20.glGetAttribLocation(iProg, "aTex");
                iMvph = GLES20.glGetUniformLocation(iProg, "uMVP");
                iSth = GLES20.glGetUniformLocation(iProg, "uST");

                int[] tex = new int[2];
                GLES20.glGenTextures(2, tex, 0);
                videoTexId = tex[0];
                imageTexId = tex[1];

                GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, videoTexId);
                GLES20.glTexParameterf(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR);
                GLES20.glTexParameterf(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);
                
                videoST = new SurfaceTexture(videoTexId);
                videoST.setOnFrameAvailableListener(TianYinSolaEngine.this);

                GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, imageTexId);
                GLES20.glTexParameterf(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR);
                GLES20.glTexParameterf(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);
            }

            private void draw() {
                // 检查 EGL 上下文，防止锁屏后的状态丢失
                if (!egl.eglMakeCurrent(display, eglSurface, eglSurface, context)) return;

                boolean isVid = isCurrentWallpaperVideo();
                if (isVid && updateSurface.getAndSet(false)) {
                    try {
                        videoST.updateTexImage();
                        videoST.getTransformMatrix(stMat);
                    } catch (Exception ignored) {}
                } else if (!isVid) {
                    Matrix.setIdentityM(stMat, 0);
                    Matrix.translateM(stMat, 0, 0, 1, 0);
                    Matrix.scaleM(stMat, 0, 1, -1, 1);
                }

                GLES20.glViewport(0, 0, screenWidth, screenHeight);
                GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);

                int prog = isVid ? vProg : iProg;
                int tex = isVid ? videoTexId : imageTexId;
                int target = isVid ? GLES11Ext.GL_TEXTURE_EXTERNAL_OES : GLES20.GL_TEXTURE_2D;
                int ph = isVid ? vPh : iPh;
                int th = isVid ? vTh : iTh;
                int mvpHandle = isVid ? vMvph : iMvph;
                int stHandle = isVid ? vSth : iSth;

                GLES20.glUseProgram(prog);
                
                Matrix.setIdentityM(mvpMat, 0);
                if (mContentW > 0 && mContentH > 0) {
                    float cAspect = (float) mContentW / mContentH;
                    float sAspect = (float) screenWidth / screenHeight;
                    float sX = 1f, sY = 1f, tX = 0f;

                    if (cAspect > sAspect) {
                        sX = cAspect / sAspect;
                        if (wallpaperScroll) {
                            float maxT = sX - 1.0f;
                            tX = maxT - (currentXOffset * 2.0f * maxT);
                        }
                    } else {
                        sY = sAspect / cAspect;
                    }
                    Matrix.scaleM(mvpMat, 0, sX, sY, 1f);
                    Matrix.translateM(mvpMat, 0, tX / sX, 0, 0);
                }

                GLES20.glUniformMatrix4fv(mvpHandle, 1, false, mvpMat, 0);
                GLES20.glUniformMatrix4fv(stHandle, 1, false, stMat, 0);

                GLES20.glEnableVertexAttribArray(ph);
                GLES20.glVertexAttribPointer(ph, 3, GLES20.GL_FLOAT, false, 12, vBuf);
                GLES20.glEnableVertexAttribArray(th);
                GLES20.glVertexAttribPointer(th, 2, GLES20.GL_FLOAT, false, 8, tBuf);

                GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
                GLES20.glBindTexture(target, tex);
                GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);

                egl.eglSwapBuffers(display, eglSurface);
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
                releaseEGL();
            }

            private void releaseEGL() {
                if (display != null) {
                    egl.eglMakeCurrent(display, EGL10.EGL_NO_SURFACE, EGL10.EGL_NO_SURFACE, EGL10.EGL_NO_CONTEXT);
                    egl.eglDestroySurface(display, eglSurface);
                    egl.eglDestroyContext(display, context);
                    egl.eglTerminate(display);
                }
            }
        }
    }
}