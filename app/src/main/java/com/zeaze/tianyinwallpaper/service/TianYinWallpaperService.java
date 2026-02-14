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

    @Override
    public Engine onCreateEngine() {
        return new TianYinSolaEngine();
    }

    class TianYinSolaEngine extends WallpaperService.Engine {
        private MediaPlayer mediaPlayer;
        private Paint mPaint;
        private List<TianYinWallpaperModel> list;
        private int index=-1;
        private SurfaceHolder surfaceHolder;
        private boolean hasVideo;

        private boolean pageChange=false;
        public TianYinSolaEngine(){
            this.mPaint = new Paint();
            String s= FileUtil.loadData(getApplicationContext(),FileUtil.wallpaperPath);
            list= JSON.parseArray(s, TianYinWallpaperModel.class);
            hasVideo=true;
            lastTime = System.currentTimeMillis()/1000;
            pref = getSharedPreferences(App.TIANYIN,MODE_PRIVATE);
            pageChange = pref.getBoolean("pageChange",false);
            needBackgroundPlay = pref.getBoolean("needBackgroundPlay",false);
//            for (TianYinWallpaperModel model:list){
//                if (model.getType()==1){
//                    hasVideo=true;
//                    break;
//                }
//            }
        }

        private boolean getNextIndex(){
            isOnlyOne=false;
            if (index!=-1) {
                int minTime = pref.getInt("minTime", 1);
                if (System.currentTimeMillis() / 1000 - lastTime <= minTime) {
                    isOnlyOne=true;
                    return false;
                }
            }
            lastTime=System.currentTimeMillis()/1000;
            boolean isRand = pref.getBoolean("rand",false);
            int i = 1;
            if (isRand){
                i=(int)(Math.random()*list.size())+1;
            }
            int lastIndex = index;
            while (i>0) {
                if (index == -1) index = list.size() - 1;
                index = getIfIndex();
                if (index == lastIndex){
                    if (index == -1) index = list.size() - 1;
                    index = getIfIndex();
                }
                i = i - 1;
            }
            if (lastIndex==index){
                isOnlyOne=true;
            }
            return true;
        }



        private int getIfIndex(){
            int i=index+1;
            if (i>= list.size()){
                i=0;
            }
            while (!isIf(i)){
                if (i==index){
                    return getNoIfIndex();
                }
                i++;
                if (i>= list.size()){
                    i=0;
                }
            }
            return i;
        }

        private int getNoIfIndex(){
            int i=index+1;
            if (i>= list.size()){
                i=0;
            }
            while (!((list.get(i).getStartTime()==-1||list.get(i).getEndTime()==-1))){
                if (i==index){
                    i=index+1;
                    if (i>= list.size()){
                        i=0;
                    }
                    return i;
                }
                i++;
                if (i>= list.size()){
                    i=0;
                }
            }
            return i;
        }

        private boolean isIf(int index){
            TianYinWallpaperModel model=list.get(index);
            if (model.getStartTime()==-1||model.getEndTime()==-1){
                return false;
            }
            int now=getTime();
            if (model.getStartTime()==model.getEndTime()&&model.getStartTime()==now){
                return true;
            }
            if (model.getStartTime()<=now&&now<model.getEndTime()){
                return true;
            }
            if (now<model.getEndTime()&&model.getEndTime()<model.getStartTime()){
                return true;
            }
            if (model.getEndTime()<model.getStartTime()&&model.getStartTime()<=now){
                return true;
            }
            return false;
        }

        private int getTime(){
            Calendar cal = Calendar.getInstance();
            cal.setTime(new Date());
            int nowHour = cal.get(Calendar.HOUR_OF_DAY);
            int nowMin = cal.get(Calendar.MINUTE);
            return nowHour*60+nowMin;
        }

        @Override
        public void onSurfaceCreated(SurfaceHolder holder) {
            super.onSurfaceCreated(holder);
            surfaceHolder=holder;
            if (hasVideo) {
                mediaPlayer = new MediaPlayer();
                mediaPlayer.setSurface(holder.getSurface());
            }
        }
        Bitmap bitmap;
        private void setWallpaper(){
            Canvas localCanvas=surfaceHolder.lockCanvas();
            if (localCanvas != null) {
                Rect rect = new Rect();
                rect.left = rect.top = 0;
                rect.bottom = localCanvas.getHeight();
                rect.right = localCanvas.getWidth();
                localCanvas.drawColor(Color.WHITE);
                if (bitmap!=null){
                    bitmap.recycle();
                }
                bitmap=getBitmap();
                localCanvas.drawBitmap(bitmap, null, rect, this.mPaint);
                surfaceHolder.unlockCanvasAndPost(localCanvas);
            }
        }

        private void setLiveWallpaper() {
            try {
                mediaPlayer.reset();
                TianYinWallpaperModel currentModel = list.get(index);
                // Use URI if available, otherwise fall back to file path
                if (currentModel.getVideoUri() != null && !currentModel.getVideoUri().isEmpty()) {
                    mediaPlayer.setDataSource(getApplicationContext(), Uri.parse(currentModel.getVideoUri()));
                } else {
                    mediaPlayer.setDataSource(currentModel.getVideoPath());
                }
                mediaPlayer.prepare();
                mediaPlayer.setLooping(false);
                mediaPlayer.setVideoScalingMode(MediaPlayer.VIDEO_SCALING_MODE_SCALE_TO_FIT_WITH_CROPPING);
                mediaPlayer.setVolume(0,0);
                mediaPlayer.start();
            } catch (IOException e) {
                e.printStackTrace();
            }
        }

        private Bitmap getBitmap(){
            TianYinWallpaperModel currentModel = list.get(index);
            // Use URI if available, otherwise fall back to file path
            if (currentModel.getImgUri() != null && !currentModel.getImgUri().isEmpty()) {
                InputStream is = null;
                try {
                    is = getApplicationContext().getContentResolver().openInputStream(Uri.parse(currentModel.getImgUri()));
                    Bitmap bitmap = BitmapFactory.decodeStream(is);
                    return bitmap;
                } catch (Exception e) {
                    e.printStackTrace();
                    // Fall back to file path if URI fails
                    return BitmapFactory.decodeFile(currentModel.getImgPath());
                } finally {
                    if (is != null) {
                        try {
                            is.close();
                        } catch (IOException e) {
                            e.printStackTrace();
                        }
                    }
                }
            } else {
                return BitmapFactory.decodeFile(currentModel.getImgPath());
            }
        }

        int page=-1;
        @Override
        public void onOffsetsChanged(float xOffset, float yOffset, float xOffsetStep, float yOffsetStep, int xPixelOffset, int yPixelOffset) {
            super.onOffsetsChanged(xOffset, yOffset, xOffsetStep, yOffsetStep, xPixelOffset, yPixelOffset);
            if (!pageChange){
                return;
            }
            float dx=xOffset;
            while (dx>xOffsetStep){
                dx=dx-xOffset;
            }
            dx=dx/xOffsetStep;
            if (page==-1){
                if (dx<0.1||dx>0.9) {
                    page = Math.round(xOffset / xOffsetStep);
                }
                return;
            }
            if (dx<0.1||dx>0.9) {
                int newPage = Math.round(xOffset / xOffsetStep);
                if (newPage!=page){
                    lastTime=0;
                    onVisibilityChanged(false);
                    onVisibilityChanged(true);
                    page=newPage;
                }
            }
        }

        private long lastPlayTime;
        @Override
        public void onVisibilityChanged(boolean visible) {
            super.onVisibilityChanged(visible);
            if(visible){
                if (hasVideo) {
                    if (mediaPlayer != null) {
                        if (index!=-1) {
                            mediaPlayer.setLooping(true);
                            if (!mediaPlayer.isPlaying()){
                                mediaPlayer.start();
                            }
                            if (isOnlyOne &&lastPlayTime>0&&needBackgroundPlay){
                                long nowTime=(lastPlayTime+System.currentTimeMillis()-lastTime*1000)%(mediaPlayer.getDuration());
                                mediaPlayer.seekTo((int)nowTime);
                            }
                        }
                    }
                }
            }else{
                if (mediaPlayer!=null){
                    lastPlayTime=mediaPlayer.getCurrentPosition();
                }
                if (getNextIndex()) {
                    if (hasVideo) {
                        if (mediaPlayer != null) {
                            setLiveWallpaper();
                        }
                    } else {
                        this.setWallpaper();
                    }
                }
                else{
                    if (hasVideo) {
                        if (mediaPlayer != null) {
                            mediaPlayer.setLooping(false);
                            mediaPlayer.pause();
                        }
                    }
                }
            }
        }

        @Override
        public void onDestroy() {
            super.onDestroy();
            releaseMediaPlayer();
        }

        private void releaseMediaPlayer(){
            if (mediaPlayer!=null) {
                if (mediaPlayer.isPlaying()) {
                    mediaPlayer.stop();
                }
                mediaPlayer.release();
                mediaPlayer = null;
            }
        }

    }
}
