package com.popmemo;

import android.animation.ValueAnimator;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.provider.Settings;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * 贴在屏幕边上的小圆球：一直在，数字是今天还剩几件事。
 * 点开是个小面板，左右滑翻两页：今天到期/过期的事、一周以外的长期计划，能直接跳去记一条。
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
    private TextView title;
    private FrameLayout pageHolder;
    private FrameLayout bottomHolder;
    private TextView dotToday;
    private TextView dotLater;
    private int currentPage = -1;
    private final int screenWidth;
    private final int screenHeight;
    /** 主界面是否在前台（自己家不摆球） */
    private static boolean appVisible;

    /** 删掉的条目先在内存里留 5 秒，期间能撤销，超时才真正写盘 */
    private List<Memo> pendingDelete;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private BroadcastReceiver screenReceiver;

    private FloatingBall(Context context) {
        this.context = context;
        this.wm = (WindowManager) context.getSystemService(Context.WINDOW_SERVICE);
        this.prefs = context.getSharedPreferences("popmemo", Context.MODE_PRIVATE);
        this.screenWidth = context.getResources().getDisplayMetrics().widthPixels;
        this.screenHeight = context.getResources().getDisplayMetrics().heightPixels;
    }

    /** 开关变了、服务起了都叫一声：该摆摆，该收收。 */
    public static synchronized void sync(Context context) {
        // 自己家页面开着的时候不摆球；静态标记之外再用进程重要级兜一层
        if (appVisible || selfInForeground(context)) {
            hide();
            return;
        }
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

    /** 主界面在前台时置 true，服务那边就不再把球摆出来。 */
    public static synchronized void setAppVisible(boolean visible) {
        appVisible = visible;
        if (visible) {
            hide();
        }
    }

    /** 进程自己的 Activity 是否在前台；服务被窗口事件叫醒时也能判断。 */
    private static boolean selfInForeground(Context c) {
        android.app.ActivityManager am =
                (android.app.ActivityManager) c.getSystemService(Context.ACTIVITY_SERVICE);
        if (am == null) {
            return false;
        }
        java.util.List<android.app.ActivityManager.RunningAppProcessInfo> list =
                am.getRunningAppProcesses();
        if (list == null) {
            return false;
        }
        for (android.app.ActivityManager.RunningAppProcessInfo p : list) {
            if (p.processName.equals(c.getPackageName())) {
                return p.importance ==
                        android.app.ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND;
            }
        }
        return false;
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

    /** 回到桌面时轻闪一下：常驻的东西看久了会被眼睛当装饰，得偶尔动一下。 */
    public static void flash() {
        FloatingBall fb = instance;
        if (fb == null || fb.ball == null) {
            return;
        }
        fb.ball.animate().alpha(0.2f).setDuration(110)
                .withEndAction(() -> {
                    if (fb.ball != null) {
                        fb.updateCount(); // 回到正常透明度（有事项全亮，没有半透明）
                    }
                })
                .start();
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
        watchScreen();
        updateCount();
    }

    /** 熄屏就把球收起来（省电模式下常驻悬浮很吃电），亮屏再放回来。 */
    private void watchScreen() {
        if (screenReceiver != null) {
            return;
        }
        screenReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context c, Intent intent) {
                if (ball == null) {
                    return;
                }
                if (Intent.ACTION_SCREEN_OFF.equals(intent.getAction())) {
                    removePanel();
                    ball.setVisibility(View.GONE);
                } else if (Intent.ACTION_SCREEN_ON.equals(intent.getAction())) {
                    ball.setVisibility(View.VISIBLE);
                    updateCount();
                }
            }
        };
        IntentFilter filter = new IntentFilter();
        filter.addAction(Intent.ACTION_SCREEN_OFF);
        filter.addAction(Intent.ACTION_SCREEN_ON);
        try {
            context.registerReceiver(screenReceiver, filter);
        } catch (Exception e) {
            screenReceiver = null;
        }
    }

    private void unwatchScreen() {
        if (screenReceiver == null) {
            return;
        }
        try {
            context.unregisterReceiver(screenReceiver);
        } catch (Exception ignored) {
        }
        screenReceiver = null;
    }

    private void removeBall() {
        unwatchScreen();
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

        TextView titleView = new TextView(context);
        titleView.setTextColor(TEXT_DIM);
        titleView.setTextSize(13);
        title = titleView;
        LinearLayout.LayoutParams titleParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        titleParams.bottomMargin = dp(6);
        box.addView(titleView, titleParams);

        // 两页内容都放这里。条目上横滑归条目（办结/删除），空白处横滑才翻页：
        // 靠 onInterceptTouchEvent 里按下手位置判断落在哪，两边不抢。
        pageHolder = new FrameLayout(context) {
            private float downX;
            private float downY;
            private boolean onRow;

            @Override
            public boolean onInterceptTouchEvent(MotionEvent ev) {
                switch (ev.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        downX = ev.getRawX();
                        downY = ev.getRawY();
                        onRow = hitRow(this, ev.getX(), ev.getY());
                        break;
                    case MotionEvent.ACTION_MOVE:
                        if (!onRow) {
                            float dx = ev.getRawX() - downX;
                            float dy = ev.getRawY() - downY;
                            if (Math.abs(dx) > dp(32) && Math.abs(dx) > Math.abs(dy) * 1.2f) {
                                return true;
                            }
                        }
                        break;
                    default:
                        break;
                }
                return super.onInterceptTouchEvent(ev);
            }

            @Override
            public boolean onTouchEvent(MotionEvent ev) {
                if (ev.getActionMasked() == MotionEvent.ACTION_UP) {
                    float dx = ev.getRawX() - downX;
                    if (Math.abs(dx) > dp(32)) {
                        // 只有两页，不管往哪滑都是去另一页。
                        showPage((currentPage + 1) % 2);
                    }
                }
                return true;
            }
        };
        box.addView(pageHolder, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(280)));

        // 两个小圆点：当前页亮着，点一下也能翻页。
        LinearLayout dots = new LinearLayout(context);
        dots.setOrientation(LinearLayout.HORIZONTAL);
        dots.setGravity(Gravity.CENTER);
        dotToday = dot();
        dotLater = dot();
        dotToday.setOnClickListener(v -> showPage(0));
        dotLater.setOnClickListener(v -> showPage(1));
        dots.addView(dotToday);
        dots.addView(dotLater);
        box.addView(dots);

        currentPage = -1;
        showPage(0);

        // 底部：默认「全部 / 记一条」；点记一条原地换成输入框，不跳主界面。
        bottomHolder = new FrameLayout(context);
        LinearLayout.LayoutParams bottomParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        bottomParams.topMargin = dp(4);
        box.addView(bottomHolder, bottomParams);
        showActionsRow();

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
        // 关面板时把还没超时的删除落盘，别让「删了」变成「没删」
        flushPendingDelete();
        if (panel != null) {
            // 输入框还开着的话，键盘一起收。
            InputMethodManager imm =
                    (InputMethodManager) context.getSystemService(Context.INPUT_METHOD_SERVICE);
            if (imm != null) {
                imm.hideSoftInputFromWindow(panel.getWindowToken(), 0);
            }
            try {
                wm.removeView(panel);
            } catch (Exception ignored) {
            }
            panel = null;
        }
    }

    private TextView dot() {
        TextView dot = new TextView(context);
        dot.setText("●");
        dot.setTextSize(8);
        dot.setPadding(dp(4), 0, dp(4), 0);
        return dot;
    }

    /** 以后：今天以外的所有待办（明天起的、没设日子的都算），按日子近的排前面。 */
    private List<Memo> later() {
        List<Memo> out = new ArrayList<>();
        long now = System.currentTimeMillis();
        for (Memo memo : DueShower.pendingOf(new MemoStore(context).load())) {
            if (memo.dueAt <= 0 || memo.daysLeft(now) > 0) {
                out.add(memo);
            }
        }
        out.sort((a, b) -> Long.compare(a.dueAt, b.dueAt));
        return out;
    }

    /** 翻到指定页：带左右滑入滑出的小动画；圆点和标题跟着换。 */
    private void showPage(int page) {
        if (page == currentPage || pageHolder == null) {
            return;
        }
        int dir = currentPage < 0 ? 0 : (page > currentPage ? 1 : -1);
        currentPage = page;

        String date = new SimpleDateFormat("M月d日 EEEE", Locale.getDefault()).format(new Date());
        title.setText(page == 0 ? "今天 · " + date : "以后 · " + later().size() + " 件");
        dotToday.setTextColor(page == 0 ? ACCENT : TEXT_DIM);
        dotLater.setTextColor(page == 1 ? ACCENT : TEXT_DIM);

        // 面板跟着内容长高：事少就小，顶天 dp(280) 再自己滚。
        int count = page == 0 ? today().size() : later().size();
        ViewGroup.LayoutParams holderParams = pageHolder.getLayoutParams();
        holderParams.height = count <= 0 ? dp(88) : Math.min(dp(280), dp(38) * count + dp(16));
        pageHolder.setLayoutParams(holderParams);

        final View next = page == 0 ? buildTodayPage() : buildLaterPage();
        View old = pageHolder.getChildCount() > 0 ? pageHolder.getChildAt(0) : null;
        if (old == null || dir == 0) {
            pageHolder.removeAllViews();
            pageHolder.addView(next);
            return;
        }
        float dist = screenWidth * 0.5f;
        next.setTranslationX(dir * dist);
        next.setAlpha(0f);
        pageHolder.addView(next);
        next.animate().translationX(0f).alpha(1f).setDuration(160).start();
        final View oldRef = old;
        old.animate().translationX(-dir * dist).alpha(0f).setDuration(160)
                .withEndAction(() -> pageHolder.removeView(oldRef))
                .start();
    }

    private View buildTodayPage() {
        List<Memo> today = today();
        if (today.isEmpty()) {
            return emptyHint("今天没安排事");
        }
        LinearLayout rows = new LinearLayout(context);
        rows.setOrientation(LinearLayout.VERTICAL);
        long now = System.currentTimeMillis();
        for (Memo memo : today) {
            String label = memo.dueLabel(now);
            rows.addView(swipeRow(memo, (label == null ? "" : label + " · ") + memo.text,
                    memo.daysLeft(now) < 0 ? URGENT : TEXT_MAIN));
        }
        return inScroll(rows);
    }

    private View buildLaterPage() {
        List<Memo> later = later();
        if (later.isEmpty()) {
            return emptyHint("没有长期计划");
        }
        LinearLayout rows = new LinearLayout(context);
        rows.setOrientation(LinearLayout.VERTICAL);
        long now = System.currentTimeMillis();
        for (Memo memo : later) {
            String suffix = memo.dueAt > 0 ? " · " + memo.daysLeft(now) + " 天后" : "";
            rows.addView(swipeRow(memo, memo.text + suffix, TEXT_MAIN));
        }
        return inScroll(rows);
    }

    /** 能滑走的条目：右滑 = 办结，左滑 = 删除。 */
    private TextView swipeRow(Memo memo, String text, int color) {
        TextView row = row(text, color);
        row.setTag("memo_row");
        row.setOnTouchListener(new RowSwipe(row, memo));
        return row;
    }

    /** 按下位置是不是落在条目上：是就横滑归条目，不是就留给翻页。 */
    private boolean hitRow(ViewGroup parent, float x, float y) {
        for (int i = 0; i < parent.getChildCount(); i++) {
            View child = parent.getChildAt(i);
            if (x >= child.getLeft() && x < child.getRight()
                    && y >= child.getTop() && y < child.getBottom()) {
                if ("memo_row".equals(child.getTag())) {
                    return true;
                }
                if (child instanceof ViewGroup
                        && hitRow((ViewGroup) child, x - child.getLeft(), y - child.getTop())) {
                    return true;
                }
            }
        }
        return false;
    }

    private TextView row(String text, int color) {
        TextView row = new TextView(context);
        row.setText(text);
        row.setTextColor(color);
        row.setTextSize(15);
        row.setMaxLines(2);
        row.setEllipsize(TextUtils.TruncateAt.END);
        row.setPadding(0, dp(6), 0, dp(6));
        return row;
    }

    private View emptyHint(String text) {
        FrameLayout wrap = new FrameLayout(context);
        TextView empty = new TextView(context);
        empty.setText(text);
        empty.setTextColor(TEXT_DIM);
        empty.setTextSize(14);
        empty.setGravity(Gravity.CENTER);
        wrap.addView(empty, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        return wrap;
    }

    private View inScroll(View content) {
        ScrollView scroll = new ScrollView(context);
        scroll.addView(content);
        return scroll;
    }

    /** 数据变了原地重画当前页（不走翻页动画），标题和球上的数字一起更新。 */
    private void refreshPage() {
        if (pageHolder == null || currentPage < 0) {
            return;
        }
        int page = currentPage;
        currentPage = -1;
        showPage(page);
    }

    // ---- 底部：全部 / 记一条 ⇄ 输入框 ----

    private void showActionsRow() {
        flushPendingDelete();
        bottomHolder.removeAllViews();
        LinearLayout actions = new LinearLayout(context);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        actions.setGravity(Gravity.CENTER_VERTICAL);

        TextView all = actionText("全部 →", TEXT_DIM);
        all.setOnClickListener(v -> {
            removePanel();
            openMain(false);
        });
        actions.addView(all, new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        TextView add = actionText("＋ 记一条", ACCENT);
        add.setGravity(Gravity.END);
        add.setOnClickListener(v -> showAddRow());
        actions.addView(add, new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        bottomHolder.addView(actions);
    }

    /** 直接在面板里写一条：面板得先拿到焦点，键盘才起得来。 */
    private void showAddRow() {
        bottomHolder.removeAllViews();
        LinearLayout row = new LinearLayout(context);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);

        final EditText input = new EditText(context);
        // 哪页记的归哪页：今天页算今天到期，以后页默认 3 天后。
        input.setHint(currentPage == 1 ? "写一句，回车就存（3 天后到期）" : "写一句，回车就存（算今天的）");
        input.setTextSize(15);
        input.setSingleLine();
        input.setOnEditorActionListener((v, actionId, event) -> {
            boolean done = actionId == EditorInfo.IME_ACTION_DONE;
            boolean enter = event != null
                    && event.getKeyCode() == KeyEvent.KEYCODE_ENTER
                    && event.getAction() == KeyEvent.ACTION_DOWN;
            if (done || enter) {
                commitAdd(input);
                return true;
            }
            return false;
        });
        row.addView(input, new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        TextView save = actionText("存", ACCENT);
        save.setGravity(Gravity.END);
        save.setOnClickListener(v -> commitAdd(input));
        row.addView(save);

        bottomHolder.addView(row);
        setPanelFocusable(true);
        input.requestFocus();
        InputMethodManager imm =
                (InputMethodManager) context.getSystemService(Context.INPUT_METHOD_SERVICE);
        if (imm != null) {
            imm.showSoftInput(input, InputMethodManager.SHOW_IMPLICIT);
        }
    }

    private void commitAdd(EditText input) {
        flushPendingDelete();
        String text = input.getText().toString().trim();
        if (!text.isEmpty()) {
            long now = System.currentTimeMillis();
            long dueAt = currentPage == 1 ? Memo.dayOffset(3) : Memo.dayOffset(0);
            MemoStore store = new MemoStore(context);
            List<Memo> all = new ArrayList<>(store.load());
            all.add(0, new Memo(now, text, false, now, 0L, 0L, dueAt));
            store.save(all);
            MemoStore.notifyChanged();
        }
        InputMethodManager imm =
                (InputMethodManager) context.getSystemService(Context.INPUT_METHOD_SERVICE);
        if (imm != null) {
            imm.hideSoftInputFromWindow(input.getWindowToken(), 0);
        }
        setPanelFocusable(false);
        showActionsRow();
        refreshPage();
        updateCount();
    }

    /** 点条目原地改：写错了不用删掉重写一条。 */
    private void editMemo(Memo memo) {
        flushPendingDelete();
        bottomHolder.removeAllViews();
        LinearLayout row = new LinearLayout(context);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);

        final EditText input = new EditText(context);
        input.setText(memo.text);
        input.setSelection(input.getText().length());
        input.setTextSize(15);
        input.setSingleLine();
        input.setOnEditorActionListener((v, actionId, event) -> {
            boolean done = actionId == EditorInfo.IME_ACTION_DONE;
            boolean enter = event != null
                    && event.getKeyCode() == KeyEvent.KEYCODE_ENTER
                    && event.getAction() == KeyEvent.ACTION_DOWN;
            if (done || enter) {
                commitEdit(memo, input);
                return true;
            }
            return false;
        });
        row.addView(input, new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        TextView save = actionText("存", ACCENT);
        save.setGravity(Gravity.END);
        save.setOnClickListener(v -> commitEdit(memo, input));
        row.addView(save);

        bottomHolder.addView(row);
        setPanelFocusable(true);
        input.requestFocus();
        InputMethodManager imm =
                (InputMethodManager) context.getSystemService(Context.INPUT_METHOD_SERVICE);
        if (imm != null) {
            imm.showSoftInput(input, InputMethodManager.SHOW_IMPLICIT);
        }
    }

    private void commitEdit(Memo memo, EditText input) {
        String text = input.getText().toString().trim();
        if (!text.isEmpty() && !text.equals(memo.text)) {
            MemoStore store = new MemoStore(context);
            List<Memo> all = new ArrayList<>(store.load());
            for (Memo m : all) {
                if (m.id == memo.id) {
                    m.text = text;
                    break;
                }
            }
            store.save(all);
            MemoStore.notifyChanged();
        }
        InputMethodManager imm =
                (InputMethodManager) context.getSystemService(Context.INPUT_METHOD_SERVICE);
        if (imm != null) {
            imm.hideSoftInputFromWindow(input.getWindowToken(), 0);
        }
        setPanelFocusable(false);
        showActionsRow();
        refreshPage();
        updateCount();
    }

    /** 面板平时不吃焦点（免得挡后面的 App），要打字时临时放开。 */
    private void setPanelFocusable(boolean focusable) {
        if (panel == null) {
            return;
        }
        WindowManager.LayoutParams params = (WindowManager.LayoutParams) panel.getLayoutParams();
        params.flags = focusable
                ? WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                : WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN;
        try {
            wm.updateViewLayout(panel, params);
        } catch (Exception ignored) {
        }
    }

    // ---- 条目滑走：右滑办结，左滑删除 ----

    private void applyRowAction(Memo memo, boolean done) {
        flushPendingDelete();
        MemoStore store = new MemoStore(context);
        List<Memo> all = new ArrayList<>(store.load());
        boolean found = false;
        if (done) {
            for (Memo m : all) {
                if (m.id == memo.id) {
                    m.doneAt = System.currentTimeMillis();
                    found = true;
                    break;
                }
            }
        } else {
            for (int i = 0; i < all.size(); i++) {
                if (all.get(i).id == memo.id) {
                    all.remove(i);
                    found = true;
                    break;
                }
            }
        }
        if (found && done) {
            store.save(all);
            MemoStore.notifyChanged();
            Toast.makeText(context, "办完了：" + memo.text, Toast.LENGTH_SHORT).show();
        } else if (found) {
            // 删除先不落盘：底部给 5 秒后悔时间，点「撤销」就当没删过
            pendingDelete = all;
            showUndoBar(memo);
        }
        updateCount();
        refreshPage();
    }

    /** 删除后的后悔条，5 秒内点「撤销」即可，超时才真正写进存储。 */
    private void showUndoBar(Memo memo) {
        bottomHolder.removeAllViews();
        LinearLayout row = new LinearLayout(context);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);

        TextView tip = actionText("删掉了：" + memo.text, TEXT_DIM);
        tip.setSingleLine();
        tip.setEllipsize(TextUtils.TruncateAt.END);
        row.addView(tip, new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        TextView undo = actionText("撤销", ACCENT);
        undo.setPadding(dp(12), dp(6), 0, dp(6));
        undo.setOnClickListener(v -> undoDelete());
        row.addView(undo);

        bottomHolder.addView(row);
        ui.removeCallbacks(commitUndo);
        ui.postDelayed(commitUndo, 5000);
    }

    private final Runnable commitUndo = new Runnable() {
        @Override
        public void run() {
            commitPendingDelete();
            if (panel != null) {
                showActionsRow();
            }
        }
    };

    private void undoDelete() {
        ui.removeCallbacks(commitUndo);
        pendingDelete = null; // 存储里本来就没删，丢掉内存里的删除结果就行
        if (bottomHolder != null) {
            showActionsRow();
        }
        refreshPage();
        updateCount();
        Toast.makeText(context, "已恢复", Toast.LENGTH_SHORT).show();
    }

    private void commitPendingDelete() {
        if (pendingDelete == null) {
            return;
        }
        new MemoStore(context).save(pendingDelete);
        MemoStore.notifyChanged();
        pendingDelete = null;
    }

    /** 要写别的数据前先把删除落盘，免得两次保存互相覆盖。 */
    private void flushPendingDelete() {
        if (pendingDelete != null) {
            ui.removeCallbacks(commitUndo);
            commitPendingDelete();
        }
    }

    /**
     * 条目的横滑手势：跟着手指走，松手过阈值就飞出去办结/删除，不过就弹回来。
     * 竖着的动作不抢，留给列表自己滚；翻页手势在条目以外的空白处生效。
     */
    private class RowSwipe implements View.OnTouchListener {

        private final View row;
        private final Memo memo;
        private float startX;
        private float startY;
        private boolean swiping;

        RowSwipe(View row, Memo memo) {
            this.row = row;
            this.memo = memo;
        }

        @Override
        public boolean onTouch(View v, MotionEvent event) {
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    startX = event.getRawX();
                    startY = event.getRawY();
                    swiping = false;
                    return true;
                case MotionEvent.ACTION_MOVE: {
                    float dx = event.getRawX() - startX;
                    float dy = event.getRawY() - startY;
                    if (!swiping) {
                        if (Math.abs(dx) > dp(16) && Math.abs(dx) > Math.abs(dy) * 1.2f) {
                            swiping = true;
                            row.getParent().requestDisallowInterceptTouchEvent(true);
                        } else {
                            return true;
                        }
                    }
                    row.setTranslationX(dx);
                    row.setAlpha(Math.max(0.35f, 1f - Math.abs(dx) / dp(220)));
                    return true;
                }
                case MotionEvent.ACTION_UP: {
                    if (!swiping) {
                        // 没滑动就是一次点击：改内容
                        editMemo(memo);
                        return true;
                    }
                    final float dx = event.getRawX() - startX;
                    // 阈值给足：翻页时手指从条目上起手，不再一滑就删
                    if (Math.abs(dx) > dp(140)) {
                        final boolean done = dx > 0;
                        row.animate()
                                .translationX(dx > 0 ? screenWidth * 0.5f : -screenWidth * 0.5f)
                                .alpha(0f)
                                .setDuration(140)
                                .withEndAction(() -> applyRowAction(memo, done))
                                .start();
                    } else {
                        row.animate().translationX(0f).alpha(1f).setDuration(120).start();
                    }
                    return true;
                }
                case MotionEvent.ACTION_CANCEL:
                    // 列表抢去竖滚了，把条目摆回原位。
                    row.animate().translationX(0f).alpha(1f).setDuration(120).start();
                    return true;
                default:
                    return true;
            }
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
