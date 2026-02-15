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

import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.util.Calendar;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import javax.microedition.khronos.egl.EGL10;
import javax.microedition.khronos.egl.EGLConfig;
import javax.microedition.khronos.egl.EGLContext;
import javax.microedition.khronos.egl.EGLDisplay;
import javax.microedition.khronos.egl.EGLSurface;

public class TianYinWallpaperService extends WallpaperService {
    private final String TAG = "TianYinGL";
    private SharedPreferences pref;
    private long lastTime = 0;
    private List<TianYinWallpaperModel> list;
    private int index = -1;
    private boolean wallpaperScroll = false;

    @Override
    public Engine onCreateEngine() {
        return new TianYinSolaEngine();
    }

    class TianYinSolaEngine extends WallpaperService.Engine implements SurfaceTexture.OnFrameAvailableListener {
        private MediaPlayer mediaPlayer;
        private EglThread eglThread;
        private int screenWidth, screenHeight;
        private float currentXOffset = 0.5f;
        private final AtomicBoolean updateSurface = new AtomicBoolean(false);

        public TianYinSolaEngine() {
            try {
                String s = FileUtil.loadData(getApplicationContext(), FileUtil.wallpaperPath);
                list = JSON.parseArray(s, TianYinWallpaperModel.class);
            } catch (Exception ignored) {}
            pref = getSharedPreferences(App.TIANYIN, MODE_PRIVATE);
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
                if (eglThread != null) eglThread.requestRender();
            }
        }

        @Override
        public void onVisibilityChanged(boolean visible) {
            super.onVisibilityChanged(visible);
            if (visible) {
                if (mediaPlayer != null) mediaPlayer.start();
                if (eglThread != null) eglThread.requestRender();
            } else {
                if (mediaPlayer != null && mediaPlayer.isPlaying()) mediaPlayer.pause();
                if (getNextIndex()) {
                    if (eglThread != null) eglThread.postRunnable(this::updateCurrentContent);
                }
            }
        }

        private void updateCurrentContent() {
            if (index < 0 || list == null || index >= list.size()) return;
            TianYinWallpaperModel model = list.get(index);
            if (model.getType() == 1) { // 视频
                prepareVideo(model);
            } else { // 图片
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
                    eglThread.requestRender();
                });
                mediaPlayer.prepareAsync();
            } catch (Exception e) {
                Log.e(TAG, "Video error: " + e.getMessage());
            }
        }

        private void prepareImage(TianYinWallpaperModel model) {
            if (mediaPlayer != null) mediaPlayer.reset();
            try {
                Bitmap bitmap;
                if (model.getImgUri() != null && !model.getImgUri().isEmpty()) {
                    InputStream is = getApplicationContext().getContentResolver().openInputStream(Uri.parse(model.getImgUri()));
                    bitmap = BitmapFactory.decodeStream(is);
                    is.close();
                } else {
                    bitmap = BitmapFactory.decodeFile(model.getImgPath());
                }
                if (bitmap != null) {
                    eglThread.setContentSize(bitmap.getWidth(), bitmap.getHeight());
                    eglThread.updateImageTexture(bitmap);
                }
            } catch (Exception e) {
                Log.e(TAG, "Image error: " + e.getMessage());
            }
        }

        private boolean getNextIndex() {
            if (list == null || list.isEmpty()) return false;
            // 简化逻辑：每次可见性改变都尝试切下一个，此处逻辑按你原本的即可
            lastTime = System.currentTimeMillis() / 1000;
            index = (index + 1) % list.size();
            return true;
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

        // --- GL 线程 ---
        private class EglThread extends HandlerThread {
            private final SurfaceHolder holder;
            private EGL10 egl;
            private EGLDisplay display;
            private EGLContext context;
            private EGLSurface eglSurface;
            private Handler handler;
            private SurfaceTexture videoST;
            private int vTexId = -1, iTexId = -1;
            private int vProg, iProg;
            private FloatBuffer vBuf, tBuf;
            private int mCW = 1, mCH = 1;
            private final float[] stMat = new float[16];
            private final float[] mvpMat = new float[16];
            private final AtomicBoolean isPending = new AtomicBoolean(false);

            public EglThread(SurfaceHolder holder) {
                super("TianYinGL");
                this.holder = holder;
                Matrix.setIdentityM(stMat, 0);
                float[] vData = {-1,-1, 1,-1, -1,1, 1,1};
                vBuf = ByteBuffer.allocateDirect(vData.length*4).order(ByteOrder.nativeOrder()).asFloatBuffer().put(vData);
                vBuf.position(0);
                float[] tData = {0,0, 1,0, 0,1, 1,1};
                tBuf = ByteBuffer.allocateDirect(tData.length*4).order(ByteOrder.nativeOrder()).asFloatBuffer().put(tData);
                tBuf.position(0);
            }

            public SurfaceTexture getVideoST() { return videoST; }
            public void setContentSize(int w, int h) { mCW = w > 0 ? w : 1; mCH = h > 0 ? h : 1; }

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

            public void postRunnable(Runnable r) { if (handler != null) handler.post(r); }

            public void requestRender() {
                if (isPending.compareAndSet(false, true)) {
                    postRunnable(() -> { isPending.set(false); draw(); });
                }
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
                GLES20.glTexParameterf(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);
                videoST = new SurfaceTexture(vTexId);
                videoST.setOnFrameAvailableListener(TianYinSolaEngine.this);
                GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, iTexId);
                GLES20.glTexParameterf(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR);
                GLES20.glTexParameterf(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);
            }

            public void updateImageTexture(Bitmap bitmap) {
                postRunnable(() -> {
                    GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, iTexId);
                    GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bitmap, 0);
                    bitmap.recycle();
                    requestRender();
                });
            }

            private void draw() {
                if (!egl.eglMakeCurrent(display, eglSurface, eglSurface, context)) return;

                boolean isVid = (list != null && index >= 0 && list.get(index).getType() == 1);
                if (isVid && updateSurface.getAndSet(false)) {
                    try { videoST.updateTexImage(); videoST.getTransformMatrix(stMat); } catch (Exception ignored) {}
                } else if (!isVid) {
                    Matrix.setIdentityM(stMat, 0);
                    Matrix.translateM(stMat, 0, 0, 1, 0);
                    Matrix.scaleM(stMat, 0, 1, -1, 1);
                }

                GLES20.glViewport(0, 0, screenWidth, screenHeight);
                // 调试：设置背景为深灰色。如果屏幕变灰，说明 GL 渲染有效，纹理有问题。
                GLES20.glClearColor(0.1f, 0.1f, 0.1f, 1.0f);
                GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);

                int prog = isVid ? vProg : iProg;
                GLES20.glUseProgram(prog);

                // 简化 MVP 矩阵计算
                Matrix.setIdentityM(mvpMat, 0);
                float cAsp = (float) mCW / mCH;
                float sAsp = (float) screenWidth / screenHeight;
                if (cAsp > sAsp) {
                    float scale = cAsp / sAsp;
                    float tx = wallpaperScroll ? (scale - 1.0f) * (1.0f - currentXOffset * 2.0f) : 0;
                    Matrix.scaleM(mvpMat, 0, scale, 1.0f, 1.0f);
                    Matrix.translateM(mvpMat, 0, tx/scale, 0, 0);
                } else {
                    Matrix.scaleM(mvpMat, 0, 1.0f, sAsp / cAsp, 1.0f);
                }

                int aPos = GLES20.glGetAttribLocation(prog, "aPos");
                int aTex = GLES20.glGetAttribLocation(prog, "aTex");
                GLES20.glEnableVertexAttribArray(aPos);
                GLES20.glVertexAttribPointer(aPos, 2, GLES20.GL_FLOAT, false, 8, vBuf);
                GLES20.glEnableVertexAttribArray(aTex);
                GLES20.glVertexAttribPointer(aTex, 2, GLES20.GL_FLOAT, false, 8, tBuf);
                GLES20.glUniformMatrix4fv(GLES20.glGetUniformLocation(prog, "uMVP"), 1, false, mvpMat, 0);
                GLES20.glUniformMatrix4fv(GLES20.glGetUniformLocation(prog, "uST"), 1, false, stMat, 0);

                GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
                GLES20.glBindTexture(isVid ? GLES11Ext.GL_TEXTURE_EXTERNAL_OES : GLES20.GL_TEXTURE_2D, isVid ? vTexId : iTexId);
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

            public void finish() { quitSafely(); releaseEGL(); }
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