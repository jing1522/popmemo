package com.coursebox;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;
import android.webkit.MimeTypeMap;

import java.io.File;

/**
 * 给系统其它 App 临时读私有目录里的文件（点击预览用）。
 * 只能用系统自带 ContentProvider 子类（不引 androidx FileProvider）。
 * URI 形式：content://com.coursebox.files/<相对路径>
 */
public class FileServer extends ContentProvider {

    public static final String AUTH = "com.coursebox.files";

    public static Uri uriOf(String rel) {
        Uri.Builder b = new Uri.Builder().scheme("content").authority(AUTH);
        for (String seg : rel.split("/")) b.appendPath(seg);
        return b.build();
    }

    /** 调系统应用预览匣内文件（没装对应应用时提示，不崩） */
    public static void view(android.app.Activity a, Models.Item it) {
        try {
            android.content.Intent i = new android.content.Intent(android.content.Intent.ACTION_VIEW);
            i.setDataAndType(uriOf(it.rel), mimeOf(it.name));
            i.addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION);
            a.startActivity(i);
        } catch (android.content.ActivityNotFoundException e) {
            android.widget.Toast.makeText(a, "没有能打开这种文件的应用", android.widget.Toast.LENGTH_SHORT).show();
        }
    }

    public static String mimeOf(String name) {
        int dot = name.lastIndexOf('.');
        if (dot >= 0) {
            String mime = MimeTypeMap.getSingleton()
                .getMimeTypeFromExtension(name.substring(dot + 1).toLowerCase());
            if (mime != null) return mime;
        }
        return "application/octet-stream";
    }

    /** 解析 URI → 匣子内文件；越界（..）一律拒绝 */
    private File resolve(Uri uri) {
        File root = new File(getContext().getFilesDir(), "box");
        StringBuilder rel = new StringBuilder();
        for (String seg : uri.getPathSegments()) {
            if (seg.contains("..")) return null;
            if (rel.length() > 0) rel.append('/');
            rel.append(seg);
        }
        File f = new File(root, rel.toString());
        try {
            if (!f.getCanonicalPath().startsWith(root.getCanonicalPath())) return null;
        } catch (Exception e) {
            return null;
        }
        return f.exists() ? f : null;
    }

    @Override
    public ParcelFileDescriptor openFile(Uri uri, String mode) {
        if (mode != null && mode.contains("w")) return null; // 只读
        File f = resolve(uri);
        if (f == null) return null;
        try {
            return ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_READ_ONLY);
        } catch (Exception e) {
            return null;
        }
    }

    @Override
    public String getType(Uri uri) {
        String last = uri.getLastPathSegment();
        return mimeOf(last == null ? "" : last);
    }

    @Override
    public Cursor query(Uri uri, String[] proj, String sel, String[] args, String order) {
        File f = resolve(uri);
        if (f == null) return null;
        String[] cols = proj != null ? proj
            : new String[]{OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE};
        MatrixCursor c = new MatrixCursor(cols, 1);
        Object[] row = new Object[cols.length];
        for (int i = 0; i < cols.length; i++) {
            if (OpenableColumns.DISPLAY_NAME.equals(cols[i])) row[i] = f.getName();
            else if (OpenableColumns.SIZE.equals(cols[i])) row[i] = f.length();
        }
        c.addRow(row);
        return c;
    }

    @Override public boolean onCreate() { return true; }
    @Override public Uri insert(Uri u, ContentValues v) { return null; }
    @Override public int delete(Uri u, String s, String[] a) { return 0; }
    @Override public int update(Uri u, ContentValues v, String s, String[] a) { return 0; }
}
