package com.coursebox;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.net.Uri;
import android.os.Build;
import android.provider.MediaStore;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.security.MessageDigest;

/**
 * 匣子：文件实体放在 App 私有目录 filesDir/box/<课程id>/<格子id>/，
 * 文件管理器看不到；导入 = 复制 → 校验哈希 → 删原文件，校验失败绝不删。
 */
public class Box {

    private final Context ctx;
    private final Store store;

    public Box(Context ctx) {
        this.ctx = ctx.getApplicationContext();
        this.store = Store.get(ctx);
    }

    public File root() {
        File d = new File(ctx.getFilesDir(), "box");
        if (!d.exists()) d.mkdirs();
        return d;
    }

    public File fileOf(Models.Course c, Models.Cat cat, Models.Item it) {
        return new File(root(), it.rel);
    }

    /** 是否已收过（按内容哈希） */
    public boolean dup(String sha) {
        if (sha == null || sha.isEmpty()) return false;
        for (Models.Course c : store.courses)
            for (Models.Cat cat : c.cats)
                for (Models.Item it : cat.items)
                    if (sha.equals(it.sha)) return true;
        return false;
    }

    /**
     * 把输入流收进指定格子。返回收好的 Item；重复返回 null；失败抛异常。
     * 调用方负责在成功后删除原文件。
     */
    public Models.Item importStream(InputStream in, String name, Models.Course c, Models.Cat cat) throws Exception {
        File dir = new File(root(), c.id + "/" + cat.id);
        if (!dir.exists()) dir.mkdirs();

        // 先写临时文件，同时算哈希
        File tmp = File.createTempFile("imp", ".tmp", ctx.getCacheDir());
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        FileOutputStream fos = new FileOutputStream(tmp);
        byte[] buf = new byte[64 * 1024];
        int r;
        long size = 0;
        while ((r = in.read(buf)) > 0) {
            fos.write(buf, 0, r);
            md.update(buf, 0, r);
            size += r;
        }
        fos.close();
        in.close();

        StringBuilder sb = new StringBuilder();
        for (byte b : md.digest()) sb.append(String.format("%02x", b));
        String sha = sb.toString();

        if (dup(sha)) { tmp.delete(); return null; }

        // 重名加序号
        File dst = new File(dir, name);
        int n = 1;
        String base = name, ext = "";
        int dot = name.lastIndexOf('.');
        if (dot > 0) { base = name.substring(0, dot); ext = name.substring(dot); }
        while (dst.exists()) dst = new File(dir, base + "(" + (n++) + ")" + ext);

        // 校验：临时文件字节数与流读到的字节数一致才落位
        if (tmp.length() != size) { tmp.delete(); throw new Exception("copy size mismatch"); }
        if (!tmp.renameTo(dst)) {
            copy(tmp, dst);
            tmp.delete();
        }

        Models.Item it = new Models.Item();
        it.name = dst.getName();
        it.rel = c.id + "/" + cat.id + "/" + dst.getName();
        it.size = size;
        it.at = System.currentTimeMillis();
        it.sha = sha;
        cat.items.add(0, it);
        store.save();
        return it;
    }

    /** 删除材料：删实体 + 删索引 */
    public void delete(Models.Course c, Models.Cat cat, Models.Item it) {
        new File(root(), it.rel).delete();
        cat.items.remove(it);
        store.save();
    }

    /** 跨格子移动 */
    public void move(Models.Course c, Models.Cat from, Models.Cat to, Models.Item it) {
        File src = new File(root(), it.rel);
        File dir = new File(root(), c.id + "/" + to.id);
        if (!dir.exists()) dir.mkdirs();
        File dst = new File(dir, src.getName());
        if (src.exists() && !dst.exists()) src.renameTo(dst);
        from.items.remove(it);
        it.rel = c.id + "/" + to.id + "/" + dst.getName();
        to.items.add(0, it);
        store.save();
    }

    /** 导出到公共 Download/课匣导出（MediaStore，免权限） */
    public boolean export(Models.Item it) {
        try {
            File src = new File(root(), it.rel);
            ContentResolver cr = ctx.getContentResolver();
            ContentValues v = new ContentValues();
            v.put(MediaStore.Downloads.DISPLAY_NAME, it.name);
            v.put(MediaStore.Downloads.RELATIVE_PATH, "Download/课匣导出");
            Uri uri = cr.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, v);
            if (uri == null) return false;
            OutputStream os = cr.openOutputStream(uri);
            FileInputStream fis = new FileInputStream(src);
            byte[] buf = new byte[64 * 1024];
            int r;
            while ((r = fis.read(buf)) > 0) os.write(buf, 0, r);
            fis.close();
            os.close();
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private static void copy(File a, File b) throws Exception {
        FileInputStream fis = new FileInputStream(a);
        FileOutputStream fos = new FileOutputStream(b);
        byte[] buf = new byte[64 * 1024];
        int r;
        while ((r = fis.read(buf)) > 0) fos.write(buf, 0, r);
        fis.close();
        fos.close();
    }

    /** 从 content:// 读文件名 */
    public static String displayName(ContentResolver cr, Uri uri) {
        String name = null;
        try {
            android.database.Cursor c = cr.query(uri, null, null, null, null);
            if (c != null) {
                int i = c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME);
                if (c.moveToFirst() && i >= 0) name = c.getString(i);
                c.close();
            }
        } catch (Exception ignored) {}
        if (name == null) {
            name = uri.getLastPathSegment();
            if (name == null) name = "未命名";
            int slash = name.lastIndexOf('/');
            if (slash >= 0) name = name.substring(slash + 1);
            int colon = name.lastIndexOf(':');
            if (colon >= 0) name = name.substring(colon + 1);
        }
        return name;
    }
}
