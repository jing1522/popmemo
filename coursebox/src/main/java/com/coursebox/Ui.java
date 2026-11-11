package com.coursebox;

import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

/** UI 小工具：dp 换算、圆角渐变背景 */
public class Ui {

    public static int dp(Context c, float v) {
        return (int) (v * c.getResources().getDisplayMetrics().density + 0.5f);
    }

    public static final int INK = 0xFF3B3F45;
    public static final int MUTED = 0xFF9A9EA6;
    public static final int RULE = 0xFFEAE7E1;
    public static final int SCREEN = 0xFFFAF9F6;
    public static final int ACCENT = 0xFF7C8DA0;
    public static final int ACCENT2 = 0xFFA9B7C4;

    public static GradientDrawable grad(int c1, int c2, float radiusDp, Context ctx) {
        GradientDrawable g = new GradientDrawable(GradientDrawable.Orientation.TL_BR, new int[]{c1, c2});
        g.setCornerRadius(dp(ctx, radiusDp));
        return g;
    }

    public static GradientDrawable round(int color, float radiusDp, Context ctx) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(dp(ctx, radiusDp));
        return g;
    }

    public static GradientDrawable roundStroke(int color, float radiusDp, int strokeColor, Context ctx) {
        GradientDrawable g = round(color, radiusDp, ctx);
        g.setStroke(dp(ctx, 1.5f), strokeColor);
        return g;
    }

    public static TextView tv(Context ctx, String s, float sp, int color, int style) {
        TextView t = new TextView(ctx);
        t.setText(s);
        t.setTextSize(sp);
        t.setTextColor(color);
        t.setTypeface(null, style);
        return t;
    }

    public static ViewGroup.LayoutParams lp(int w, int h) {
        return new ViewGroup.LayoutParams(w, h);
    }

    /** 文件名后缀 → 类型 */
    public static String typeOf(String name) {
        String n = name.toLowerCase();
        int dot = n.lastIndexOf('.');
        String ext = dot >= 0 ? n.substring(dot + 1) : "";
        if (ext.matches("ppt|pptx|key")) return "ppt";
        if (ext.equals("pdf")) return "pdf";
        if (ext.matches("doc|docx|wps|rtf")) return "doc";
        if (ext.matches("jpg|jpeg|png|gif|webp|bmp|heic")) return "img";
        if (ext.matches("md|txt")) return "note";
        if (ext.matches("zip|rar|7z")) return "zip";
        if (ext.matches("xls|xlsx|csv")) return "xls";
        return "other";
    }

    public static boolean isImg(String name) { return "img".equals(typeOf(name)); }

    public static int[] typeColors(String type) {
        switch (type) {
            case "ppt":  return new int[]{0xFFC08A76, 0xFFD4A893};
            case "pdf":  return new int[]{0xFFBA7166, 0xFFCF968C};
            case "doc":  return new int[]{0xFF7E94AE, 0xFF9DAFC2};
            case "img":  return new int[]{0xFF9B91AF, 0xFFB7AFC4};
            case "note": return new int[]{0xFF82A78E, 0xFFA2BFA9};
            case "zip":  return new int[]{0xFFA2978A, 0xFFBDB4A6};
            case "xls":  return new int[]{0xFF7FA394, 0xFF9EBCAC};
            default:     return new int[]{0xFFA3A7AD, 0xFFBEBFC0};
        }
    }

    public static String typeLabel(String type) {
        switch (type) {
            case "ppt": return "PPT";
            case "pdf": return "PDF";
            case "doc": return "DOC";
            case "note": return "笔记";
            case "zip": return "ZIP";
            case "xls": return "XLS";
            default: return "文件";
        }
    }

    public static String sizeText(long b) {
        if (b >= 1024 * 1024) return String.format("%.1f MB", b / 1048576.0);
        if (b >= 1024) return (b / 1024) + " KB";
        return b + " B";
    }

    public static String dateText(long at) {
        java.text.SimpleDateFormat f = new java.text.SimpleDateFormat("MM-dd");
        return f.format(new java.util.Date(at));
    }
}
