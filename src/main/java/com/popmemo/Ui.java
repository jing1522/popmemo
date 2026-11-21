package com.popmemo;

import android.content.Context;
import android.graphics.drawable.GradientDrawable;
import android.util.TypedValue;

/**
 * 视觉底色统一放这里：颜色、圆角、间距都从这取，页面里不要再各写各的。
 * 基调：浅灰底 + 白卡 + 大圆角 + 唯一的蓝色强调，靠留白和层级而不是线框。
 */
public final class Ui {

    public static final int BG = 0xFFF4F6F8;
    public static final int CARD = 0xFFFFFFFF;
    public static final int INK = 0xFF1A1D21;
    public static final int DIM = 0xFF8A9099;
    public static final int FAINT = 0xFFB6BCC4;
    public static final int LINE = 0xFFEDEFF2;
    public static final int ACCENT = 0xFF2F6BFF;
    public static final int ACCENT_SOFT = 0xFFEAF0FF;
    public static final int DANGER = 0xFFD9534F;
    public static final int DANGER_SOFT = 0xFFFDEDEC;

    /** 页面左右留白 */
    public static final int PAGE = 20;
    /** 卡片内边距 */
    public static final int INNER = 16;
    /** 卡片圆角 */
    public static final int RADIUS = 18;

    private Ui() {
    }

    public static int dp(Context c, float v) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v,
                c.getResources().getDisplayMetrics()));
    }

    public static GradientDrawable round(Context c, int fill, float radiusDp, int stroke) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(fill);
        d.setCornerRadius(dp(c, radiusDp));
        if (stroke != 0) {
            d.setStroke(dp(c, 1), stroke);
        }
        return d;
    }

    public static GradientDrawable pill(Context c, int fill) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(fill);
        d.setCornerRadius(dp(c, 99));
        return d;
    }

    public static GradientDrawable dot(Context c, int fill, float sizeDp) {
        GradientDrawable d = new GradientDrawable();
        d.setShape(GradientDrawable.OVAL);
        d.setColor(fill);
        d.setSize(dp(c, sizeDp), dp(c, sizeDp));
        return d;
    }

    /** 浅色页面把状态栏/导航栏图标切成深色，否则白图标看不见。 */
    public static void lightBars(android.app.Activity a, boolean light) {
        if (android.os.Build.VERSION.SDK_INT >= 30) {
            android.view.WindowInsetsController c = a.getWindow().getInsetsController();
            if (c != null) {
                int mask = android.view.WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS
                        | android.view.WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS;
                c.setSystemBarsAppearance(light ? mask : 0, mask);
                return;
            }
        }
        android.view.View v = a.getWindow().getDecorView();
        int flags = v.getSystemUiVisibility();
        if (light) {
            flags |= android.view.View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;
        } else {
            flags &= ~android.view.View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;
        }
        v.setSystemUiVisibility(flags);
    }

    private static String lastWarn;
    private static long lastWarnAt;

    /** 出错时说一句；同一句 3 秒内不重复弹，后台线程调用也安全。 */
    public static void warn(Context c, String msg) {
        if (c == null || msg == null) return;
        long now = System.currentTimeMillis();
        if (msg.equals(lastWarn) && now - lastWarnAt < 3000) return;
        lastWarn = msg;
        lastWarnAt = now;
        final Context app = c.getApplicationContext();
        new android.os.Handler(android.os.Looper.getMainLooper()).post(() ->
                android.widget.Toast.makeText(app, msg, android.widget.Toast.LENGTH_LONG).show());
    }
}
