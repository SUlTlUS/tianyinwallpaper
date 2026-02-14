package com.zeaze.tianyinwallpaper.utils;

import static android.content.Context.MODE_PRIVATE;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Matrix;
import android.net.Uri;
import android.util.DisplayMetrics;
import android.util.Log;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;

public class FileUtil {
    public static String dataPath="data.json";
    public static String wallpaperPath="wallpaper.json";
    public static String wallpaperFilePath="/wallpaper/";
    public static int width=9,height=16;

    public static Bitmap ImageSizeCompress(Context context,Uri uri){
        InputStream Stream = null;
        InputStream inputStream = null;
        try {
            Stream = context.getContentResolver().openInputStream(uri);
            Bitmap bitmap = BitmapFactory.decodeStream(Stream, null, new BitmapFactory.Options());
            return bitmap;
        } catch (Exception e) {
            e.printStackTrace();
        }finally {
            try {
                if(inputStream != null) {
                    inputStream.close();
                }
                if(Stream != null){
                    Stream.close();
                }
            } catch (IOException e) {
                e.printStackTrace();
            }

        }
        return  null;
    }

    public static Bitmap getVideoThumbnailFromUri(Context context, Uri uri) {
        InputStream is = null;
        FileOutputStream os = null;
        File tempFile = null;
        try {
            // Create temporary file for video thumbnail generation
            tempFile = File.createTempFile("temp_video", ".mp4", context.getCacheDir());
            is = context.getContentResolver().openInputStream(uri);
            os = new FileOutputStream(tempFile);
            byte[] buffer = new byte[1024];
            int len;
            while ((len = is.read(buffer)) != -1) {
                os.write(buffer, 0, len);
            }
            
            Bitmap thumbnail = android.media.ThumbnailUtils.createVideoThumbnail(
                tempFile.getAbsolutePath(), 
                android.provider.MediaStore.Video.Thumbnails.FULL_SCREEN_KIND
            );
            
            return thumbnail;
        } catch (Exception e) {
            e.printStackTrace();
        } finally {
            try {
                if (is != null) {
                    is.close();
                }
                if (os != null) {
                    os.close();
                }
                if (tempFile != null) {
                    tempFile.delete();
                }
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
        return null;
    }

    public static Bitmap bitmap2Path(Bitmap bitmap, String path) {
        if (bitmap.getWidth()*height==width*bitmap.getHeight()){

        }
        else if (bitmap.getWidth()*height>width*bitmap.getHeight()){
            Matrix matrix = new Matrix();
            if (bitmap.getWidth()>width){
                matrix.postScale((float)height/ bitmap.getHeight(), (float)height/bitmap.getHeight());
            }
            bitmap=Bitmap.createBitmap(bitmap,(bitmap.getWidth()-bitmap.getHeight()*width/height)/2,0,
                    bitmap.getHeight()*width/height
                    ,bitmap.getHeight(),matrix,true);
        }
        else{
            Matrix matrix = new Matrix();
            if (bitmap.getWidth()>width){
                matrix.postScale((float)width/bitmap.getWidth(), (float)width/bitmap.getWidth());
            }
            bitmap=Bitmap.createBitmap(bitmap,0,(bitmap.getHeight()-bitmap.getWidth()*height/width)/2
                    ,bitmap.getWidth()
                    ,bitmap.getWidth()*height/width,matrix,true);
        }
        try {
            OutputStream os = new FileOutputStream(path);
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, os);
            os.flush();
            os.close();
        } catch (Exception e) {
            e.printStackTrace();
        }
        return bitmap;
    }

    public static String uri2Path(Context context,Uri uri, String path) {
        InputStream is = null;
        OutputStream os = null;
        try {
            is = context.getContentResolver().openInputStream(uri);
            os = new FileOutputStream(path);
            byte[] buffer = new byte[1024];
            int len = is.read(buffer);
            while (len != -1) {
                os.write(buffer, 0, len);
                len = is.read(buffer);
            }
            is.close();
            os.close();
        } catch (Exception e) {
            e.printStackTrace();
        }finally {
            try {
                if(is != null) {
                    is.close();
                }
                if(os != null){
                    os.close();
                }
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
        return path;
    }

    static public void save(Context context,String data,String dataPath,onSave onSave) {
        FileOutputStream out = null;
        BufferedWriter writer = null;
        try {
            out = new FileOutputStream(context.getExternalFilesDir(null)+"/"+dataPath);
            writer = new BufferedWriter(new OutputStreamWriter(out));
            writer.write(data);

        } catch (IOException e) {
            e.printStackTrace();
        } finally {
            try {
                writer.close();
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
        onSave.onSave();
    }

    static public String loadData(Context context,String dataPath) {
        FileInputStream in = null;
        BufferedReader reader = null;
        StringBuilder content = new StringBuilder();
        try {
            in = new FileInputStream(context.getExternalFilesDir(null)+"/"+dataPath);
            reader = new BufferedReader(new InputStreamReader(in));
            String line = "";
            while ((line = reader.readLine()) != null) {
                content.append(line);
            }

        } catch (IOException e) {
            e.printStackTrace();
        } finally {
            if (reader != null) {
                try {
                    reader.close();
                } catch (IOException e) {
                    e.printStackTrace();
                }
            }
        }
        String s=content.toString();
        if ("".equals(s)){
            s="[]";
        }
        return s;
    }


    public interface onSave{
        void  onSave();
    }
}
