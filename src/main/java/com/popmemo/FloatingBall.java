package com.popmemo;

import android.animation.ValueAnimator;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.provider.Settings;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * 贴在屏幕边上的小圆球：一直在，数字是今天还剩几件事。
 * 点开是个小面板，列今天到期/过期的事，能直接跳去记一条。
 * 解锁弹窗管「催」，它管「随时看一眼」，俩互补。
 * 哪个服务活着谁就调 sync() 把它摆出来（幂等）；主界面打开时收起来，不碍事。
 */
public class FloatingBall {

    private static final String PREF_ENABLED = "ball_enabled";
    private static final String PREF_RIGHT = "ball_right";
    private static final String PREF_Y = "ball_y";

    private static final int BALL_BG = 0xCC24242A;
    private static final int PANEL_TOP = 0xFFF7F6FB;
    private static final int PANEL_BOTTOM = 0xFFE9E7F2;
    private static final int TEXT_MAIN = 0xFF1C1C1E;
    private static final int TEXT_DIM = 0xFF8E8E93;
    private static final int URGENT = 0xFFFF453A;
    private static final int ACCENT = 0xFF0A84FF;

    private static FloatingBall instance;

    private final Context context;
    private final WindowManager wm;
    private final SharedPreferences prefs;

    private TextView ball;
    private View panel;
    private final int screenWidth;
    private final int screenHeight;

    private FloatingBall(Context context) {
        this.context = context;
        this.wm = (WindowManager) context.getSystemService(Context.WINDOW_SERVICE);
        this.prefs = context.getSharedPreferences("popmemo", Context.MODE_PRIVATE);
        this.screenWidth = context.getResources().getDisplayMetrics().widthPixels;
        this.screenHeight = context.getResources().getDisplayMetrics().heightPixels;
    }

    /** 开关变了、服务起了都叫一声：该摆摆，该收收。 */
    public static synchronized void sync(Context context) {
        boolean enabled = context.getSharedPreferences("popmemo", Context.MODE_PRIVATE)
                .getBoolean(PREF_ENABLED, true);
        if (!enabled || !Settings.canDrawOverlays(context)) {
            hide();
            return;
        }
        if (instance == null) {
            instance = new FloatingBall(context.getApplicationContext());
        }
        instance.showBall();
    }

    public static synchronized void hide() {
        if (instance != null) {
            instance.removePanel();
            instance.removeBall();
        }
    }

    /** 事项有变化时刷新球上的数字；球不在就拉倒。 */
    public static void refreshCount(Context context) {
        if (instance != null && instance.ball != null) {
            instance.updateCount();
        }
    }

    private void showBall() {
        if (ball != null) {
            updateCount();
            return;
        }
        ball = new TextView(context);
        ball.setTextColor(Color.WHITE);
        ball.setTextSize(15);
        ball.setTypeface(Typeface.DEFAULT_BOLD);
        ball.setGravity(Gravity.CENTER);
        GradientDrawable bg = new GradientDrawable();
        bg.setShape(GradientDrawable.OVAL);
        bg.setColor(BALL_BG);
        ball.setBackground(bg);
        ball.setElevation(dp(4));
        ball.setOnTouchListener(new BallDrag());

        WindowManager.LayoutParams params = new WindowManager.LayoutParams(
                dp(46), dp(46),
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT);
        params.gravity = Gravity.TOP | Gravity.START;
        boolean right = prefs.getBoolean(PREF_RIGHT, true);
        params.x = right ? screenWidth - dp(46) : 0;
        params.y = clamp(prefs.getInt(PREF_Y, screenHeight / 3), 0, screenHeight - dp(46));
        try {
            wm.addView(ball, params);
        } catch (Exception e) {
            ball = null;
            return;
        }
        updateCount();
    }

    private void removeBall() {
        if (ball != null) {
            try {
                wm.removeView(ball);
            } catch (Exception ignored) {
            }
            ball = null;
        }
    }

    private void updateCount() {
        if (ball == null) {
            return;
        }
        int n = today().size();
        ball.setText(n > 0 ? String.valueOf(n) : "✓");
        ball.setAlpha(n > 0 ? 1f : 0.55f);
    }

    /** 今天的主角：设了截止日、已经到期或今天到期的待办。 */
    private List<Memo> today() {
        List<Memo> out = new ArrayList<>();
        long now = System.currentTimeMillis();
        for (Memo memo : DueShower.pendingOf(new MemoStore(context).load())) {
            if (memo.dueAt > 0 && memo.daysLeft(now) <= 0) {
                out.add(memo);
            }
        }
        out.sort((a, b) -> Long.compare(a.dueAt, b.dueAt));
        return out;
    }

    // ---- 面板 ----

    private void togglePanel() {
        if (panel != null) {
            removePanel();
            return;
        }
        List<Memo> today = today();

        FrameLayout backdrop = new FrameLayout(context);
        backdrop.setBackgroundColor(0x1A000000);
        backdrop.setOnClickListener(v -> removePanel());

        LinearLayout box = new LinearLayout(context);
        box.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(14);
        box.setPadding(pad, pad, pad, pad);
        GradientDrawable boxBg = new GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{PANEL_TOP, PANEL_BOTTOM});
        boxBg.setCornerRadius(dp(20));
        boxBg.setStroke(dp(1), 0x12000000);
        box.setBackground(boxBg);
        box.setElevation(dp(6));
        // 点面板自己不关，只点外面才关。
        box.setOnClickListener(v -> {
        });

        TextView title = new TextView(context);
        String date = new SimpleDateFormat("M月d日 EEEE", Locale.getDefault()).format(new Date());
        title.setText("今天 · " + date);
        title.setTextColor(TEXT_DIM);
        title.setTextSize(13);
        LinearLayout.LayoutParams titleParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        titleParams.bottomMargin = dp(6);
        box.addView(title, titleParams);

        if (today.isEmpty()) {
            TextView empty = new TextView(context);
            empty.setText("今天没安排事");
            empty.setTextColor(TEXT_DIM);
            empty.setTextSize(14);
            empty.setPadding(0, dp(8), 0, dp(8));
            box.addView(empty);
        } else {
            LinearLayout rows = new LinearLayout(context);
            rows.setOrientation(LinearLayout.VERTICAL);
            long now = System.currentTimeMillis();
            for (Memo memo : today) {
                TextView row = new TextView(context);
                String label = memo.dueLabel(now);
                row.setText((label == null ? "" : label + " · ") + memo.text);
                row.setTextColor(memo.daysLeft(now) < 0 ? URGENT : TEXT_MAIN);
                row.setTextSize(15);
                row.setMaxLines(2);
                row.setEllipsize(TextUtils.TruncateAt.END);
                row.setPadding(0, dp(6), 0, dp(6));
                rows.addView(row);
            }
            ScrollView scroll = new ScrollView(context);
            scroll.addView(rows);
            // 多了才限高滚动，少了随内容。
            int height = today.size() > 5 ? dp(300)
                    : LinearLayout.LayoutParams.WRAP_CONTENT;
            box.addView(scroll, new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, height));
        }

        LinearLayout actions = new LinearLayout(context);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        actions.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams actionsParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        actionsParams.topMargin = dp(4);

        TextView all = actionText("全部 →", TEXT_DIM);
        all.setOnClickListener(v -> {
            removePanel();
            openMain(false);
        });
        actions.addView(all, new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        TextView add = actionText("＋ 记一条", ACCENT);
        add.setGravity(Gravity.END);
        add.setOnClickListener(v -> {
            removePanel();
            openMain(true);
        });
        actions.addView(add, new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        box.addView(actions, actionsParams);

        FrameLayout.LayoutParams boxParams = new FrameLayout.LayoutParams(
                Math.round(screenWidth * 0.8f), FrameLayout.LayoutParams.WRAP_CONTENT);
        boolean right = prefs.getBoolean(PREF_RIGHT, true);
        boxParams.gravity = Gravity.TOP | (right ? Gravity.END : Gravity.START);
        // 贴着球的高度出来，但别顶到天花板也别掉出下半屏。
        boxParams.topMargin = clamp(prefs.getInt(PREF_Y, screenHeight / 3) - dp(30),
                dp(24), screenHeight / 2);
        int sideMargin = dp(56);
        if (right) {
            boxParams.rightMargin = sideMargin;
        } else {
            boxParams.leftMargin = sideMargin;
        }
        backdrop.addView(box, boxParams);

        WindowManager.LayoutParams params = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT);
        panel = backdrop;
        try {
            wm.addView(backdrop, params);
        } catch (Exception e) {
            panel = null;
        }
    }

    private void removePanel() {
        if (panel != null) {
            try {
                wm.removeView(panel);
            } catch (Exception ignored) {
            }
            panel = null;
        }
    }

    private TextView actionText(String text, int color) {
        TextView view = new TextView(context);
        view.setText(text);
        view.setTextColor(color);
        view.setTextSize(14);
        view.setTypeface(Typeface.DEFAULT_BOLD);
        view.setPadding(0, dp(8), 0, dp(2));
        return view;
    }

    private void openMain(boolean focusInput) {
        Intent intent = new Intent(context, MainActivity.class);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        if (focusInput) {
            intent.putExtra(MainActivity.EXTRA_FOCUS_INPUT, true);
        }
        context.startActivity(intent);
    }

    // ---- 拖动 ----

    /** 点一下 = 开/关面板；拖 = 换位置，松手自动吸到最近的边。 */
    private class BallDrag implements View.OnTouchListener {

        private float startRawX;
        private float startRawY;
        private int startX;
        private int startY;
        private boolean moved;

        @Override
        public boolean onTouch(View v, MotionEvent event) {
            WindowManager.LayoutParams params =
                    (WindowManager.LayoutParams) ball.getLayoutParams();
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    startRawX = event.getRawX();
                    startRawY = event.getRawY();
                    startX = params.x;
                    startY = params.y;
                    moved = false;
                    return true;
                case MotionEvent.ACTION_MOVE: {
                    float dx = event.getRawX() - startRawX;
                    float dy = event.getRawY() - startRawY;
                    if (!moved && Math.abs(dx) < dp(8) && Math.abs(dy) < dp(8)) {
                        return true;
                    }
                    moved = true;
                    params.x = startX + Math.round(dx);
                    params.y = clamp(startY + Math.round(dy), 0, screenHeight - ball.getHeight());
                    try {
                        wm.updateViewLayout(ball, params);
                    } catch (Exception ignored) {
                    }
                    return true;
                }
                case MotionEvent.ACTION_UP:
                    if (!moved) {
                        togglePanel();
                        return true;
                    }
                    boolean right = params.x + ball.getWidth() / 2 >= screenWidth / 2;
                    snapToEdge(params, right);
                    prefs.edit()
                            .putBoolean(PREF_RIGHT, right)
                            .putInt(PREF_Y, params.y)
                            .apply();
                    return true;
                default:
                    return false;
            }
        }

        private void snapToEdge(WindowManager.LayoutParams params, boolean right) {
            int from = params.x;
            int to = right ? screenWidth - ball.getWidth() : 0;
            ValueAnimator anim = ValueAnimator.ofInt(from, to);
            anim.setDuration(140);
            anim.addUpdateListener(a -> {
                params.x = (Integer) a.getAnimatedValue();
                try {
                    wm.updateViewLayout(ball, params);
                } catch (Exception ignored) {
                }
            });
            anim.start();
        }
    }

    private int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private int dp(int value) {
        return Math.round(value * context.getResources().getDisplayMetrics().density);
    }
}
