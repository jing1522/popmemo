package com.coursebox;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.util.LruCache;

import java.io.File;

/** 图片缩略图缓存：采样压缩解码，内存 LRU */
public class Thumb {

    private static final LruCache<String, Bitmap> CACHE =
        new LruCache<String, Bitmap>((int) (Runtime.getRuntime().maxMemory() / 8 / 1024)) {
            @Override protected int sizeOf(String k, Bitmap b) {
                return b.getByteCount() / 1024;
            }
        };

    public static Bitmap get(File f, int reqW) {
        String key = f.getAbsolutePath() + "@" + reqW;
        Bitmap b = CACHE.get(key);
        if (b != null) return b;
        try {
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(f.getAbsolutePath(), o);
            int sample = 1;
            while (o.outWidth / sample > reqW * 2) sample *= 2;
            BitmapFactory.Options o2 = new BitmapFactory.Options();
            o2.inSampleSize = sample;
            b = BitmapFactory.decodeFile(f.getAbsolutePath(), o2);
            if (b != null) CACHE.put(key, b);
        } catch (Throwable ignored) {}
        return b;
    }
}
