package com.coursebox;

import android.content.ContentResolver;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.util.LruCache;
import android.widget.ImageView;

import java.io.File;
import java.io.FileOutputStream;
import java.security.MessageDigest;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** 图片缩略图：内存 LRU + 磁盘缓存，解码只走后台线程 */
public class Thumb {

    private static final LruCache<String, Bitmap> CACHE =
        new LruCache<String, Bitmap>((int) (Runtime.getRuntime().maxMemory() / 8 / 1024)) {
            @Override protected int sizeOf(String k, Bitmap b) {
                return b.getByteCount() / 1024;
            }
        };

    /** 只开两条解码线程：够用，又不会把几百行清单一次性压满 CPU */
    private static final ExecutorService POOL = Executors.newFixedThreadPool(2);

    private static File dir;

    /** 应用启动时调一次，开启磁盘缓存（杀进程后不用重解码） */
    public static void init(Context ctx) {
        if (dir != null) return;
        dir = new File(ctx.getCacheDir(), "thumb");
        if (!dir.exists()) dir.mkdirs();
    }

    /* ---------- 后台加载到 ImageView ---------- */

    /** SAF Uri 版：批量整理清单里的文件还是 content:// */
    public static void load(final ContentResolver cr, final Uri uri, final int reqW, final ImageView iv) {
        final String key = uri.toString() + "@" + reqW;
        final String tag = uri.toString();
        Bitmap hit = CACHE.get(key);
        if (hit != null) { iv.setImageBitmap(hit); return; } // 内存里有就直接贴，重建列表不闪
        iv.setTag(tag);
        POOL.execute(() -> {
            final Bitmap b = fetch(key, null, uri, cr, reqW);
            if (b == null) return;
            iv.post(() -> {
                if (tag.equals(iv.getTag())) iv.setImageBitmap(b); // 列表已滚走/换图就别贴
            });
        });
    }

    public static void load(final File f, final int reqW, final ImageView iv) {
        final String tag = f.getAbsolutePath() + "|" + f.lastModified();
        final String key = tag + "@" + reqW;
        Bitmap hit = CACHE.get(key);
        if (hit != null) { iv.setImageBitmap(hit); return; }
        iv.setTag(tag);
        POOL.execute(() -> {
            final Bitmap b = fetch(key, f, null, null, reqW);
            if (b == null) return;
            iv.post(() -> {
                if (tag.equals(iv.getTag())) iv.setImageBitmap(b);
            });
        });
    }

    /* ---------- 同步取图（已有缓存时很快；不要在 UI 线程对未缓存图调用） ---------- */

    /** SAF Uri 版：批量整理扫描出来的文件还不是 File，从 content:// 解码 */
    public static Bitmap get(ContentResolver cr, Uri uri, int reqW) {
        return fetch(uri.toString() + "@" + reqW, null, uri, cr, reqW);
    }

    public static Bitmap get(File f, int reqW) {
        return fetch(f.getAbsolutePath() + "|" + f.lastModified() + "@" + reqW, f, null, null, reqW);
    }

    /* ---------- 内部：内存 → 磁盘 → 解码 ---------- */

    private static Bitmap fetch(String key, File src, Uri uri, ContentResolver cr, int reqW) {
        Bitmap b = CACHE.get(key);
        if (b != null) return b;

        File df = diskFile(key);
        if (df != null && df.exists()) {
            b = BitmapFactory.decodeFile(df.getAbsolutePath());
            if (b != null) { CACHE.put(key, b); return b; }
        }

        b = src != null ? decode(src, reqW) : decode(cr, uri, reqW);
        if (b != null) {
            CACHE.put(key, b);
            save(df, b);
        }
        return b;
    }

    private static Bitmap decode(File f, int reqW) {
        try {
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(f.getAbsolutePath(), o);
            BitmapFactory.Options o2 = new BitmapFactory.Options();
            o2.inSampleSize = sampleOf(o.outWidth, reqW);
            return BitmapFactory.decodeFile(f.getAbsolutePath(), o2);
        } catch (Throwable ignored) { return null; }
    }

    private static Bitmap decode(ContentResolver cr, Uri uri, int reqW) {
        try {
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inJustDecodeBounds = true;
            java.io.InputStream in = cr.openInputStream(uri);
            BitmapFactory.decodeStream(in, null, o);
            if (in != null) in.close();

            BitmapFactory.Options o2 = new BitmapFactory.Options();
            o2.inSampleSize = sampleOf(o.outWidth, reqW);
            in = cr.openInputStream(uri);
            Bitmap b = BitmapFactory.decodeStream(in, null, o2);
            if (in != null) in.close();
            return b;
        } catch (Throwable ignored) { return null; }
    }

    private static int sampleOf(int w, int reqW) {
        int sample = 1;
        while (w > 0 && w / sample > reqW * 2) sample *= 2;
        return sample;
    }

    /** 磁盘缓存放 cacheDir/thumb，文件名是 key 的摘要（中文/特殊字符也不会出问题） */
    private static File diskFile(String key) {
        if (dir == null) return null;
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-1");
            byte[] h = md.digest(key.getBytes("UTF-8"));
            StringBuilder sb = new StringBuilder(h.length * 2);
            for (byte x : h) sb.append(String.format("%02x", x));
            return new File(dir, sb.toString());
        } catch (Throwable ignored) { return null; }
    }

    private static void save(File df, Bitmap b) {
        if (df == null) return;
        FileOutputStream out = null;
        try {
            out = new FileOutputStream(df);
            // 有透明通道的（截图/图标）存 PNG，照片存 JPEG 省空间
            boolean alpha = b.hasAlpha();
            b.compress(alpha ? Bitmap.CompressFormat.PNG : Bitmap.CompressFormat.JPEG, 82, out);
        } catch (Throwable ignored) {
        } finally {
            if (out != null) try { out.close(); } catch (Exception ignored) {}
        }
    }
}
