package com.zeaze.tianyinwallpaper.service;

import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.PixelFormat;
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

import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import javax.microedition.khronos.egl.EGL10;
import javax.microedition.khronos.egl.EGLConfig;
import javax.microedition.khronos.egl.EGLContext;
import javax.microedition.khronos.egl.EGLDisplay;
import javax.microedition.khronos.egl.EGLSurface;

public class TianYinWallpaperService extends WallpaperService {
    private final String TAG = "TianYinGL";

    @Override
    public Engine onCreateEngine() {
        return new TianYinSolaEngine();
    }

    class TianYinSolaEngine extends WallpaperService.Engine implements SurfaceTexture.OnFrameAvailableListener {
        private MediaPlayer mediaPlayer;
        private EglThread eglThread;
        private List<TianYinWallpaperModel> list;
        private int index = -1;
        private volatile float currentXOffset = 0.5f;
        private final AtomicBoolean updateSurface = new AtomicBoolean(false);
        private boolean wallpaperScroll = false;

        @Override
        public void onCreate(SurfaceHolder surfaceHolder) {
            super.onCreate(surfaceHolder);
            // 必须：ColorOS 合成器强制要求
            surfaceHolder.setFormat(PixelFormat.RGBX_8888);
            try {
                String s = FileUtil.loadData(getApplicationContext(), FileUtil.wallpaperPath);
                list = JSON.parseArray(s, TianYinWallpaperModel.class);
            } catch (Exception ignored) {}
            SharedPreferences pref = getSharedPreferences(App.TIANYIN, MODE_PRIVATE);
            wallpaperScroll = pref.getBoolean("wallpaperScroll", false);
        }

        @Override
        public void onSurfaceCreated(SurfaceHolder holder) {
            super.onSurfaceCreated(holder);
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
                if (mediaPlayer != null) mediaPlayer.start();
                if (eglThread != null) eglThread.forceRender(3);
            } else {
                if (mediaPlayer != null && mediaPlayer.isPlaying()) mediaPlayer.pause();
                nextWallpaper();
            }
        }

        private void nextWallpaper() {
            if (list == null || list.isEmpty()) return;
            index = (index + 1) % list.size();
            if (eglThread != null) eglThread.postRunnable(this::loadContent);
        }

        private void loadContent() {
            if (index < 0 || index >= list.size()) return;
            TianYinWallpaperModel model = list.get(index);
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
                
                mediaPlayer.setLooping(model.isLoop());
                mediaPlayer.setVolume(0, 0);
                mediaPlayer.setOnPreparedListener(mp -> {
                    eglThread.setContentSize(mp.getVideoWidth(), mp.getVideoHeight());
                    mp.start();
                    eglThread.forceRender(5);
                });
                mediaPlayer.prepareAsync();
            } catch (Exception e) { Log.e(TAG, "Video error", e); }
        }

        private void prepareImage(TianYinWallpaperModel model) {
            if (mediaPlayer != null) mediaPlayer.reset();
            try {
                InputStream is = getApplicationContext().getContentResolver().openInputStream(Uri.parse(model.getImgUri()));
                Bitmap bitmap = BitmapFactory.decodeStream(is);
                is.close();
                if (bitmap != null) {
                    eglThread.setContentSize(bitmap.getWidth(), bitmap.getHeight());
                    eglThread.uploadBitmap(bitmap);
                }
            } catch (Exception e) { Log.e(TAG, "Image error", e); }
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
            if (mediaPlayer != null) mediaPlayer.release();
            if (eglThread != null) eglThread.finish();
        }

        // --- 基于 EGL10 的深度兼容渲染引擎 ---
        private class EglThread extends Thread {
            private final SurfaceHolder holder;
            private EGL10 egl;
            private EGLDisplay display;
            private EGLContext context;
            private EGLSurface eglSurface;
            private Handler handler;
            
            private SurfaceTexture videoST;
            private int vTexId, iTexId, vProg, iProg;
            private FloatBuffer vBuf, tBuf;
            private int sW, sH, cW = 1, cH = 1;

            private final float[] videoStMat = new float[16];
            private final float[] imageStMat = new float[16];
            private final float[] mvpMat = new float[16];
            private final AtomicBoolean isPending = new AtomicBoolean(false);

            public EglThread(SurfaceHolder holder) {
                this.holder = holder;
                Matrix.setIdentityM(videoStMat, 0);
                Matrix.setIdentityM(imageStMat, 0);
                Matrix.translateM(imageStMat, 0, 0, 1, 0);
                Matrix.scaleM(imageStMat, 0, 1, -1, 1);

                float[] vData = {-1,-1, 1,-1, -1,1, 1,1};
                vBuf = ByteBuffer.allocateDirect(vData.length*4).order(ByteOrder.nativeOrder()).asFloatBuffer().put(vData);
                vBuf.position(0);
                float[] tData = {0,0, 1,0, 0,1, 1,1};
                tBuf = ByteBuffer.allocateDirect(tData.length*4).order(ByteOrder.nativeOrder()).asFloatBuffer().put(tData);
                tBuf.position(0);
            }

            @Override
            public void run() {
                initEGL();
                initGL();
                Looper.prepare();
                handler = new Handler();
                postRunnable(() -> {
                    if (index == -1) nextWallpaper();
                    else loadContent();
                });
                Looper.loop();
                releaseEGL();
            }

            public void onSizeChanged(int w, int h) { sW = w; sH = h; requestRender(); }
            public void setContentSize(int w, int h) { cW = w > 0 ? w : 1; cH = h > 0 ? h : 1; }
            public SurfaceTexture getVideoST() { return videoST; }
            public void postRunnable(Runnable r) { if (handler != null) handler.post(r); }

            private void initEGL() {
                egl = (EGL10) EGLContext.getEGL();
                display = egl.eglGetDisplay(EGL10.EGL_DEFAULT_DISPLAY);
                egl.eglInitialize(display, null);
                int[] attr = { EGL10.EGL_RED_SIZE, 8, EGL10.EGL_GREEN_SIZE, 8, EGL10.EGL_BLUE_SIZE, 8, EGL10.EGL_ALPHA_SIZE, 8, EGL10.EGL_NONE };
                EGLConfig[] configs = new EGLConfig[1];
                int[] num = new int[1];
                egl.eglChooseConfig(display, attr, configs, 1, num);
                context = egl.eglCreateContext(display, configs[0], EGL10.EGL_NO_CONTEXT, new int[]{0x3098, 2, EGL10.EGL_NONE});
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
                videoST = new SurfaceTexture(vTexId);
                videoST.setOnFrameAvailableListener(TianYinSolaEngine.this);
                GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, iTexId);
                GLES20.glTexParameterf(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR);
            }

            public void uploadBitmap(Bitmap b) {
                postRunnable(() -> {
                    GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, iTexId);
                    GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, b, 0);
                    b.recycle();
                    forceRender(3);
                });
            }

            public void requestRender() {
                if (isPending.compareAndSet(false, true)) {
                    postRunnable(() -> { isPending.set(false); draw(); });
                }
            }

            public void forceRender(int frames) {
                for (int i = 0; i < frames; i++) requestRender();
            }

            private void draw() {
                if (eglSurface == null || !egl.eglMakeCurrent(display, eglSurface, eglSurface, context)) return;

                boolean isVid = (list != null && index >= 0 && index < list.size() && list.get(index).getType() == 1);
                float[] currentStMat;
                if (isVid) {
                    if (updateSurface.getAndSet(false)) {
                        try { videoST.updateTexImage(); videoST.getTransformMatrix(videoStMat); } catch (Exception ignored) {}
                    }
                    currentStMat = videoStMat;
                } else {
                    currentStMat = imageStMat;
                }

                GLES20.glViewport(0, 0, sW, sH);
                // 调试：深红色背景。如果你看到红色，说明 GL 没坏，内容坏了。
                GLES20.glClearColor(0.2f, 0.0f, 0.0f, 1.0f); 
                GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);

                int prog = isVid ? vProg : iProg;
                GLES20.glUseProgram(prog);

                Matrix.setIdentityM(mvpMat, 0);
                float cAsp = (float) cW / cH;
                float sAsp = (float) sW / sH;
                if (cAsp > sAsp) {
                    float scale = cAsp / sAsp;
                    float tx = wallpaperScroll ? (scale - 1.0f) * (1.0f - currentXOffset * 2.0f) : 0;
                    Matrix.scaleM(mvpMat, 0, scale, 1.0f, 1.0f);
                    Matrix.translateM(mvpMat, 0, tx / scale, 0, 0);
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
                GLES20.glUniformMatrix4fv(GLES20.glGetUniformLocation(prog, "uST"), 1, false, currentStMat, 0);

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

            public void finish() {
                if (handler != null) handler.getLooper().quit();
            }

            private void releaseEGL() {
                egl.eglMakeCurrent(display, EGL10.EGL_NO_SURFACE, EGL10.EGL_NO_SURFACE, EGL10.EGL_NO_CONTEXT);
                egl.eglDestroySurface(display, eglSurface);
                egl.eglDestroyContext(display, context);
                egl.eglTerminate(display);
            }
        }
    }
}