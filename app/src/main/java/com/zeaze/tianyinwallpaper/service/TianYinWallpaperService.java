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

    @Override
    public Engine onCreateEngine() {
        return new TianYinSolaEngine();
    }

    class TianYinSolaEngine extends WallpaperService.Engine implements SurfaceTexture.OnFrameAvailableListener {
        private MediaPlayer mediaPlayer;
        private EglThread eglThread;
        private List<TianYinWallpaperModel> list;
        private int index = -1;
        private float currentXOffset = 0.5f;
        private final AtomicBoolean updateSurface = new AtomicBoolean(false);

        @Override
        public void onCreate(SurfaceHolder surfaceHolder) {
            super.onCreate(surfaceHolder);
            surfaceHolder.setFormat(PixelFormat.RGBX_8888);
            try {
                String s = FileUtil.loadData(getApplicationContext(), FileUtil.wallpaperPath);
                list = JSON.parseArray(s, TianYinWallpaperModel.class);
            } catch (Exception ignored) {}
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
        public void onVisibilityChanged(boolean visible) {
            super.onVisibilityChanged(visible);
            if (visible) {
                if (mediaPlayer != null) mediaPlayer.start();
                if (eglThread != null) eglThread.requestRender();
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
                });
                mediaPlayer.prepareAsync();
                eglThread.resetVideoMatrix(); // 切换视频时重置矩阵
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

        // --- 优化后的 EGL 渲染线程 ---
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

            // 持久化视频纹理矩阵
            private final float[] videoSTMatrix = new float[16];
            // 预计算的图片翻转矩阵（避免每帧计算）
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

                // 预计算图片矩阵：先单位阵，然后做 Y 轴翻转
                Matrix.setIdentityM(imageMatrix, 0);
                Matrix.translateM(imageMatrix, 0, 0, 1, 0);
                Matrix.scaleM(imageMatrix, 0, 1, -1, 1);
            }

            public void onSizeChanged(int w, int h) { sW = w; sH = h; requestRender(); }
            public void setContentSize(int w, int h) { cW = w > 0 ? w : 1; cH = h > 0 ? h : 1; }
            public SurfaceTexture getVideoST() { return videoST; }
            public void postRunnable(Runnable r) { if (handler != null) handler.post(r); }
            public void resetVideoMatrix() {
                postRunnable(() -> Matrix.setIdentityM(videoSTMatrix, 0));
            }

            @Override
            protected void onLooperPrepared() {
                if (!initEGL()) return;
                initGL();
                handler = new Handler(getLooper());
                postRunnable(() -> {
                    if (index == -1) nextWallpaper();
                    else loadContent();
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

                boolean isVid = (list != null && index >= 0 && index < list.size() && list.get(index).getType() == 1);
                float[] stMat = new float[16];

                if (isVid) {
                    // 视频：使用持久化矩阵，有新帧时更新
                    if (updateSurface.getAndSet(false)) {
                        try {
                            videoST.updateTexImage();
                            videoST.getTransformMatrix(videoSTMatrix);
                        } catch (Exception ignored) {}
                    }
                    System.arraycopy(videoSTMatrix, 0, stMat, 0, 16);
                } else {
                    // 图片：直接使用预计算的翻转矩阵（避免每帧矩阵运算）
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
