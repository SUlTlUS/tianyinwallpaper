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
    @Override
    public Engine onCreateEngine() {
        return new TianYinSolaEngine();
    }

    class TianYinSolaEngine extends WallpaperService.Engine implements SurfaceTexture.OnFrameAvailableListener {
        private MediaPlayer mediaPlayer;
        private EglThread eglThread;
        private List<TianYinWallpaperModel> list;
        private int index = -1;
        private volatile float xOffset = 0.5f;
        private final AtomicBoolean updateSurface = new AtomicBoolean(false);

        @Override
        public void onCreate(SurfaceHolder surfaceHolder) {
            super.onCreate(surfaceHolder);
            // 核心修复1：显式声明像素格式，解决文件夹模糊背景显示旧壁纸的问题
            surfaceHolder.setFormat(PixelFormat.RGBX_8888);
            try {
                String s = FileUtil.loadData(getApplicationContext(), FileUtil.wallpaperPath);
                list = JSON.parseArray(s, TianYinWallpaperModel.class);
            } catch (Exception ignored) {}
        }

        @Override
        public void onSurfaceCreated(SurfaceHolder holder) {
            super.onSurfaceCreated(holder);
            eglThread = new EglThread(holder);
            eglThread.start();
        }

        @Override
        public void onSurfaceChanged(SurfaceHolder holder, int format, int width, int height) {
            if (eglThread != null) eglThread.onSizeChanged(width, height);
        }

        @Override
        public void onVisibilityChanged(boolean visible) {
            if (visible) {
                if (mediaPlayer != null) mediaPlayer.start();
                if (eglThread != null) eglThread.requestRender();
            } else {
                if (mediaPlayer != null && mediaPlayer.isPlaying()) mediaPlayer.pause();
                next();
            }
        }

        private void next() {
            if (list == null || list.isEmpty()) return;
            index = (index + 1) % list.size();
            if (eglThread != null) eglThread.post(() -> load());
        }

        private void load() {
            TianYinWallpaperModel m = list.get(index);
            try {
                if (m.getType() == 1) { // 视频
                    if (mediaPlayer == null) mediaPlayer = new MediaPlayer();
                    mediaPlayer.reset();
                    mediaPlayer.setDataSource(getApplicationContext(), Uri.parse(m.getVideoUri()));
                    Surface s = new Surface(eglThread.getVideoST());
                    mediaPlayer.setSurface(s);
                    s.release();
                    mediaPlayer.setVolume(0,0);
                    mediaPlayer.setLooping(m.isLoop());
                    mediaPlayer.setOnPreparedListener(mp -> {
                        eglThread.setSourceSize(mp.getVideoWidth(), mp.getVideoHeight());
                        mp.start();
                    });
                    mediaPlayer.prepareAsync();
                } else { // 图片
                    if (mediaPlayer != null) mediaPlayer.reset();
                    InputStream is = getApplicationContext().getContentResolver().openInputStream(Uri.parse(m.getImgUri()));
                    Bitmap b = BitmapFactory.decodeStream(is);
                    is.close();
                    if (b != null) {
                        eglThread.setSourceSize(b.getWidth(), b.getHeight());
                        eglThread.updateBitmap(b);
                    }
                }
            } catch (Exception e) { e.printStackTrace(); }
        }

        @Override
        public void onOffsetsChanged(float x, float y, float xs, float ys, int xo, int yo) {
            this.xOffset = x;
            if (eglThread != null) eglThread.requestRender();
        }

        @Override
        public void onFrameAvailable(SurfaceTexture st) {
            updateSurface.set(true);
            if (eglThread != null) eglThread.requestRender();
        }

        @Override
        public void onDestroy() {
            super.onDestroy();
            if (mediaPlayer != null) mediaPlayer.release();
            if (eglThread != null) eglThread.finish();
        }

        // --- 内部渲染类 ---
        private class EglThread extends Thread {
            private final SurfaceHolder holder;
            private EGL10 egl;
            private EGLDisplay dpy;
            private EGLContext ctx;
            private EGLSurface surf;
            private Handler handler;
            private SurfaceTexture videoST;
            private int vTex, iTex, vProg, iProg;
            private FloatBuffer vBuf, tBuf;
            private int sW, sH, cW=1, cH=1;
            private final float[] vMat = new float[16], iMat = new float[16], mvp = new float[16];

            public EglThread(SurfaceHolder h) {
                this.holder = h;
                Matrix.setIdentityM(vMat, 0);
                Matrix.setIdentityM(iMat, 0);
                Matrix.translateM(iMat, 0, 0, 1, 0);
                Matrix.scaleM(iMat, 0, 1, -1, 1);
                float[] vd = {-1,-1, 1,-1, -1,1, 1,1};
                vBuf = ByteBuffer.allocateDirect(vd.length*4).order(ByteOrder.nativeOrder()).asFloatBuffer().put(vd);
                vBuf.position(0);
                float[] td = {0,0, 1,0, 0,1, 1,1};
                tBuf = ByteBuffer.allocateDirect(td.length*4).order(ByteOrder.nativeOrder()).asFloatBuffer().put(td);
                tBuf.position(0);
            }

            public void onSizeChanged(int w, int h) { sW = w; sH = h; requestRender(); }
            public void setSourceSize(int w, int h) { cW = w; cH = h; }
            public SurfaceTexture getVideoST() { return videoST; }
            public void post(Runnable r) { if (handler != null) handler.post(r); }

            @Override
            public void run() {
                initEGL();
                initGL();
                Looper.prepare();
                handler = new Handler();
                post(() -> { if (index == -1) next(); else load(); });
                Looper.loop();
            }

            private void initEGL() {
                egl = (EGL10) EGLContext.getEGL();
                dpy = egl.eglGetDisplay(EGL10.EGL_DEFAULT_DISPLAY);
                egl.eglInitialize(dpy, null);
                int[] attr = {0x3024,8, 0x3023,8, 0x3022,8, 0x3038};
                EGLConfig[] configs = new EGLConfig[1];
                int[] n = new int[1];
                egl.eglChooseConfig(dpy, attr, configs, 1, n);
                ctx = egl.eglCreateContext(dpy, configs[0], EGL10.EGL_NO_CONTEXT, new int[]{0x3098, 2, 0x3038});
                surf = egl.eglCreateWindowSurface(dpy, configs[0], holder, null);
                egl.eglMakeCurrent(dpy, surf, surf, ctx);
            }

            private void initGL() {
                String vs = "attribute vec4 aPos; attribute vec2 aTex; varying vec2 vTex; uniform mat4 uMVP; uniform mat4 uST; void main(){ gl_Position = uMVP * aPos; vTex = (uST * vec4(aTex,0,1)).xy; }";
                String fv = "#extension GL_OES_EGL_image_external : require\n precision mediump float; varying vec2 vTex; uniform samplerExternalOES s; void main(){ gl_FragColor = texture2D(s, vTex); }";
                String fi = "precision mediump float; varying vec2 vTex; uniform sampler2D s; void main(){ gl_FragColor = texture2D(s, vTex); }";
                vProg = create(vs, fv); iProg = create(vs, fi);
                int[] t = new int[2]; GLES20.glGenTextures(2, t, 0);
                vTex = t[0]; iTex = t[1];
                GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, vTex);
                GLES20.glTexParameterf(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, 9729, 9729);
                videoST = new SurfaceTexture(vTex);
                videoST.setOnFrameAvailableListener(TianYinSolaEngine.this);
                GLES20.glBindTexture(3553, iTex);
                GLES20.glTexParameterf(3553, 9729, 9729);
            }

            public void updateBitmap(Bitmap b) {
                post(() -> { GLES20.glBindTexture(3553, iTex); GLUtils.texImage2D(3553, 0, b, 0); b.recycle(); requestRender(); });
            }

            public void requestRender() { post(this::draw); }

            private void draw() {
                if (surf == null || !egl.eglMakeCurrent(dpy, surf, surf, ctx)) return;
                boolean isV = (list != null && index >= 0 && list.get(index).getType() == 1);
                if (isV && updateSurface.getAndSet(false)) {
                    try { videoST.updateTexImage(); videoST.getTransformMatrix(vMat); } catch (Exception ignored) {}
                }
                GLES20.glViewport(0, 0, sW, sH);
                // 关键点：使用极深灰色，让合成器知道这里有像素输出
                GLES20.glClearColor(0.01f, 0.01f, 0.01f, 1.0f);
                GLES20.glClear(16384);
                int p = isV ? vProg : iProg;
                GLES20.glUseProgram(p);
                Matrix.setIdentityM(mvp, 0);
                float ca = (float)cW/cH, sa = (float)sW/sH;
                if (ca > sa) {
                    float s = ca/sa; float tx = (s-1f)*(1f-xOffset*2f);
                    Matrix.scaleM(mvp, 0, s, 1f, 1f); Matrix.translateM(mvp, 0, tx/s, 0, 0);
                } else { Matrix.scaleM(mvp, 0, 1f, sa/ca, 1f); }
                int ap = GLES20.glGetAttribLocation(p, "aPos"), at = GLES20.glGetAttribLocation(p, "aTex");
                GLES20.glEnableVertexAttribArray(ap); GLES20.glVertexAttribPointer(ap, 2, 5126, false, 8, vBuf);
                GLES20.glEnableVertexAttribArray(at); GLES20.glVertexAttribPointer(at, 2, 5126, false, 8, tBuf);
                GLES20.glUniformMatrix4fv(GLES20.glGetUniformLocation(p, "uMVP"), 1, false, mvp, 0);
                GLES20.glUniformMatrix4fv(GLES20.glGetUniformLocation(p, "uST"), 1, false, isV ? vMat : iMat, 0);
                GLES20.glActiveTexture(33984);
                GLES20.glBindTexture(isV ? 36197 : 3553, isV ? vTex : iTex);
                GLES20.glDrawArrays(5, 0, 4);
                egl.eglSwapBuffers(dpy, surf);
            }

            private int create(String v, String f) {
                int vs = GLES20.glCreateShader(35633); GLES20.glShaderSource(vs, v); GLES20.glCompileShader(vs);
                int fs = GLES20.glCreateShader(35632); GLES20.glShaderSource(fs, f); GLES20.glCompileShader(fs);
                int p = GLES20.glCreateProgram(); GLES20.glAttachShader(p, vs); GLES20.glAttachShader(p, fs);
                GLES20.glLinkProgram(p); return p;
            }

            public void finish() { if (handler != null) handler.getLooper().quit(); }
            private void releaseEGL() { egl.eglMakeCurrent(dpy, EGL10.EGL_NO_SURFACE, EGL10.EGL_NO_SURFACE, EGL10.EGL_NO_CONTEXT); egl.eglDestroySurface(dpy, surf); egl.eglDestroyContext(dpy, ctx); egl.eglTerminate(dpy); }
        }
    }
}