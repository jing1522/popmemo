package com.popmemo;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.DatePickerDialog;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Paint;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.LayerDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.Spannable;
import android.text.SpannableStringBuilder;
import android.text.style.ForegroundColorSpan;
import android.text.style.RelativeSizeSpan;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.BaseAdapter;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;

import java.io.File;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.List;

public class MainActivity extends Activity {

    /** 从常驻通知的「写一条」进来时，直接弹键盘。 */
    public static final String EXTRA_FOCUS_INPUT = "focus_input";

    private MemoStore store;

    /** 全部数据。 */
    private final List<Memo> all = new ArrayList<>();
    /** 当前列表显示的东西（待办 + 可能展开的已办完）。 */
    private final List<Memo> visible = new ArrayList<>();

    private MemoAdapter adapter;
    private boolean showDone;
    private boolean showLater;

    private EditText input;
    private ListView list;
    private View root;
    private TextView empty;
    private TextView doneFooter;
    private TextView laterFooter;
    private View permissionBanner;
    private TextView undoBar;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private Memo lastDeleted;
    private int lastDeletedIndex;
    private final Runnable hideUndoRunnable = () -> {
        lastDeleted = null;
        undoBar.setVisibility(View.GONE);
    };

    private static final String[] TAGS = {"学习", "生活", "娱乐", "工作"};

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        store = new MemoStore(this);
        all.addAll(store.load());

        input = findViewById(R.id.input);
        list = findViewById(R.id.list);
        empty = findViewById(R.id.empty);
        doneFooter = findViewById(R.id.done_footer);
        laterFooter = findViewById(R.id.later_footer);
        permissionBanner = findViewById(R.id.permission_banner);
        View grantOverlay = findViewById(R.id.grant_overlay);

        undoBar = findViewById(R.id.undo_bar);

        adapter = new MemoAdapter();
        list.setAdapter(adapter);

        // Android 15 强制全面屏，不自己留出状态栏的位置就会被时间压住。
        root = findViewById(R.id.root);
        root.setOnApplyWindowInsetsListener((v, insets) -> {
            int top;
            int bottom;
            if (Build.VERSION.SDK_INT >= 30) {
                android.graphics.Insets bars = insets.getInsets(WindowInsets.Type.systemBars());
                top = bars.top;
                bottom = bars.bottom;
            } else {
                top = insets.getSystemWindowInsetTop();
                bottom = insets.getSystemWindowInsetBottom();
            }
            View page = findViewById(R.id.page_today);
            page.setPadding(0, top, 0, dp(62) + bottom);
            View nav = findViewById(R.id.nav);
            android.widget.FrameLayout.LayoutParams nlp =
                    (android.widget.FrameLayout.LayoutParams) nav.getLayoutParams();
            nlp.height = dp(62) + bottom;
            nav.setLayoutParams(nlp);
            nav.setPadding(0, 0, 0, bottom);
            return insets;
        });

        // 顶栏就一行日期，多了反而乱
        TextView dateText = findViewById(R.id.date_text);
        Calendar cal = Calendar.getInstance();
        String[] week = {"日", "一", "二", "三", "四", "五", "六"};
        dateText.setText((cal.get(Calendar.MONTH) + 1) + " 月 "
                + cal.get(Calendar.DAY_OF_MONTH) + " 日 · 周"
                + week[cal.get(Calendar.DAY_OF_WEEK) - 1]);
        // 等布局量出尺寸再上背景，好按比例裁图。
        root.post(this::applyBackground);

        input.setOnEditorActionListener((v, actionId, event) -> {
            boolean done = actionId == EditorInfo.IME_ACTION_DONE;
            boolean enter = event != null
                    && event.getKeyCode() == KeyEvent.KEYCODE_ENTER
                    && event.getAction() == KeyEvent.ACTION_DOWN;
            if (done || enter) {
                addCurrentInput();
                return true;
            }
            return false;
        });

        grantOverlay.setOnClickListener(v -> openOverlaySettings());
        findViewById(R.id.add_btn).setOnClickListener(v -> addCurrentInput());
        // 底部两个标签是平级页面，直接进去；待办这一页就是当前页
        findViewById(R.id.nav_time).setOnClickListener(v ->
                startActivity(new Intent(this, TimetableActivity.class)));
        findViewById(R.id.nav_settings).setOnClickListener(v ->
                startActivity(new Intent(this, SettingsActivity.class)));
        doneFooter.setOnClickListener(v -> {
            showDone = !showDone;
            refresh();
        });
        laterFooter.setOnClickListener(v -> {
            showLater = !showLater;
            refresh();
        });

        // 点一下 = 改字；长按 = 更多（不弹 / 删掉）
        list.setOnItemClickListener((parent, view, position, id) -> editMemo(visible.get(position)));

        list.setOnItemLongClickListener((parent, view, position, id) -> {
            showActions(visible.get(position));
            return true;
        });

        refresh();
        MemoStore.setListener(this::refresh);

        if (getIntent().getBooleanExtra(EXTRA_FOCUS_INPUT, false)) {
            focusInput();
        }
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        if (intent != null && intent.getBooleanExtra(EXTRA_FOCUS_INPUT, false)) {
            focusInput();
        }
    }

    private void focusInput() {
        input.requestFocus();
        InputMethodManager imm = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
        if (imm != null) {
            imm.showSoftInput(input, InputMethodManager.SHOW_IMPLICIT);
        }
    }

    @Override
    protected void onDestroy() {
        handler.removeCallbacks(hideUndoRunnable);
        MemoStore.setListener(null);
        super.onDestroy();
    }

    @Override
    protected void onResume() {
        super.onResume();
        refresh();
        ensureService();
        // 设置页里可能换了背景，回来重新铺一次。
        applyBackground();
        // 自己家里不摆球，碍事；退出去再摆回来。
        FloatingBall.setAppVisible(true);
    }

    @Override
    protected void onPause() {
        FloatingBall.setAppVisible(false);
        FloatingBall.sync(this);
        super.onPause();
    }

    /** 无障碍开着就用它监听解锁（省电、系统保活），没开就前台服务兜底。 */
    private void ensureService() {
        boolean canDraw = Settings.canDrawOverlays(this);
        permissionBanner.setVisibility(canDraw ? View.GONE : View.VISIBLE);
        if (!canDraw) {
            return;
        }
        if (UnlockAccessibilityService.isEnabled(this)) {
            // 无障碍接管了，前台服务熄火，常驻通知一起收走。
            stopService(new Intent(this, UnlockService.class));
            return;
        }
        requestNotificationPermissionIfNeeded();
        startForegroundService(new Intent(this, UnlockService.class));
    }

    /** 截止日：只给几个最常用的档，不用去翻日历。 */
    private void pickDeadline(Memo memo) {
        String[] options = {"今天", "明天", "三天后", "一周后", "选个日期…", "不要日期"};
        new AlertDialog.Builder(this)
                .setTitle("哪天之前要办完？")
                .setItems(options, (dialog, which) -> {
                    switch (which) {
                        case 0:
                            memo.dueAt = Memo.dayOffset(0);
                            break;
                        case 1:
                            memo.dueAt = Memo.dayOffset(1);
                            break;
                        case 2:
                            memo.dueAt = Memo.dayOffset(3);
                            break;
                        case 3:
                            memo.dueAt = Memo.dayOffset(7);
                            break;
                        case 4:
                            pickCustomDate(memo);
                            return;
                        default:
                            memo.dueAt = 0;
                            break;
                    }
                    store.save(all);
                    refresh();
                })
                .show();
    }

    private void pickCustomDate(Memo memo) {
        Calendar c = Calendar.getInstance();
        if (memo.dueAt > 0) {
            c.setTimeInMillis(memo.dueAt);
        }
        new DatePickerDialog(this, (view, year, month, dayOfMonth) -> {
            Calendar picked = Calendar.getInstance();
            picked.set(year, month, dayOfMonth, 0, 0, 0);
            picked.set(Calendar.MILLISECOND, 0);
            memo.dueAt = picked.getTimeInMillis();
            store.save(all);
            refresh();
        }, c.get(Calendar.YEAR), c.get(Calendar.MONTH), c.get(Calendar.DAY_OF_MONTH)).show();
    }

    /** 点一下：改字。写备忘录最常发生的错就是「写错一个字」，得能改。 */
    private void editMemo(Memo memo) {
        EditText editor = new EditText(this);
        editor.setText(memo.text);
        editor.setSelection(editor.getText().length());
        editor.setPadding(dp(16), dp(12), dp(16), dp(12));

        new AlertDialog.Builder(this)
                .setTitle("改一下")
                .setView(editor)
                .setNegativeButton("算了", null)
                .setPositiveButton("保存", (d, w) -> {
                    String text = editor.getText().toString().trim();
                    if (text.isEmpty()) {
                        return;
                    }
                    memo.text = text;
                    store.save(all);
                    refresh();
                })
                .show();
    }

    /** 长按：截止日、分类、删除都放这里。 */
    private void showActions(Memo memo) {
        String[] items;
        if (memo.isDone()) {
            items = new String[]{"放回待办", "设个分类", "删掉"};
        } else {
            items = new String[]{
                    "设个截止日",
                    "设个分类",
                    memo.muted ? "让它也弹出来" : "不弹了，只在列表里",
                    "删掉"};
        }

        new AlertDialog.Builder(this)
                .setTitle(memo.text)
                .setItems(items, (dialog, which) -> {
                    if (memo.isDone()) {
                        if (which == 0) {
                            memo.doneAt = 0;
                        } else if (which == 1) {
                            pickTag(memo);
                            return;
                        } else {
                            deleteMemo(memo);
                            return;
                        }
                    } else {
                        if (which == 0) {
                            pickDeadline(memo);
                            return;
                        }
                        if (which == 1) {
                            pickTag(memo);
                            return;
                        }
                        if (which == 2) {
                            memo.muted = !memo.muted;
                        } else {
                            deleteMemo(memo);
                            return;
                        }
                    }
                    store.save(all);
                    refresh();
                })
                .show();
    }

    private void pickTag(Memo memo) {
        String[] options = new String[TAGS.length + 1];
        System.arraycopy(TAGS, 0, options, 0, TAGS.length);
        options[TAGS.length] = "不要分类";

        new AlertDialog.Builder(this)
                .setTitle("归到哪一类？")
                .setItems(options, (dialog, which) -> {
                    memo.tag = which < TAGS.length ? TAGS[which] : "";
                    store.save(all);
                    refresh();
                })
                .show();
    }

    /** 删除要能后悔：6 秒内点一下就能找回来。 */
    private void deleteMemo(Memo memo) {
        int index = all.indexOf(memo);
        if (index < 0) {
            return;
        }
        all.remove(index);
        store.save(all);
        refresh();

        lastDeleted = memo;
        lastDeletedIndex = index;
        undoBar.setText("已删除「" + memo.text + "」　点这里撤销");
        undoBar.setVisibility(View.VISIBLE);
        undoBar.setOnClickListener(v -> {
            if (lastDeleted != null) {
                all.add(Math.min(lastDeletedIndex, all.size()), lastDeleted);
                lastDeleted = null;
                store.save(all);
                refresh();
            }
            hideUndoBar();
        });
        handler.removeCallbacks(hideUndoRunnable);
        handler.postDelayed(hideUndoRunnable, 6000);
    }

    private void hideUndoBar() {
        handler.removeCallbacks(hideUndoRunnable);
        lastDeleted = null;
        undoBar.setVisibility(View.GONE);
    }

    // ---- 背景 ----

    private static final String BG_FILE = "bg.jpg";

    /** 有自己选的图就垫图 + 白色蒙版；没有就用柔和渐变。 */
    private void applyBackground() {
        File file = new File(getFilesDir(), BG_FILE);
        if (file.exists()) {
            Bitmap bitmap = decodeBackground(file.getAbsolutePath());
            if (bitmap != null) {
                BitmapDrawable photo = new BitmapDrawable(getResources(), bitmap);
                // 罩一层半透明白，底下的字照样清楚。
                LayerDrawable layers = new LayerDrawable(
                        new Drawable[]{photo, new ColorDrawable(0xB3FFFFFF)});
                root.setBackground(layers);
                setLightBars(false);
                return;
            }
        }
        GradientDrawable gradient = new GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{0xFFF4F7FF, 0xFFFFF4EC});
        root.setBackground(gradient);
        // 淡色底配白图标看不清，切成深色
        setLightBars(true);
    }

    /** 浅色底要深色状态栏图标；铺了背景图就交回系统默认。 */
    private void setLightBars(boolean light) {
        Ui.lightBars(this, light);
    }

    /** 缩到屏幕宽左右再居中裁成屏幕比例，省内存也不变形。 */
    private Bitmap decodeBackground(String path) {
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(path, bounds);
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
            return null;
        }
        int maxW = getResources().getDisplayMetrics().widthPixels;
        int sample = 1;
        while (bounds.outWidth / (sample * 2) >= maxW) {
            sample *= 2;
        }
        BitmapFactory.Options opts = new BitmapFactory.Options();
        opts.inSampleSize = sample;
        Bitmap src = BitmapFactory.decodeFile(path, opts);
        if (src == null) {
            return null;
        }
        int vw = root.getWidth();
        int vh = root.getHeight();
        if (vw <= 0 || vh <= 0) {
            return src;
        }
        float viewAspect = (float) vw / vh;
        float imgAspect = (float) src.getWidth() / src.getHeight();
        if (Math.abs(viewAspect - imgAspect) < 0.01f) {
            return src;
        }
        int cropW;
        int cropH;
        int x = 0;
        int y = 0;
        if (imgAspect > viewAspect) {
            cropH = src.getHeight();
            cropW = Math.round(cropH * viewAspect);
            x = (src.getWidth() - cropW) / 2;
        } else {
            cropW = src.getWidth();
            cropH = Math.round(cropW / viewAspect);
            y = (src.getHeight() - cropH) / 2;
        }
        return Bitmap.createBitmap(src, x, y, cropW, cropH);
    }

    private void addCurrentInput() {
        String text = input.getText().toString().trim();
        if (text.isEmpty()) {
            return;
        }
        long now = System.currentTimeMillis();
        all.add(0, new Memo(now, text, false, now, 0L, 0L, 0L));
        store.save(all);
        input.setText("");
        refresh();
        list.setSelection(0);
    }

    private void refresh() {
        // 一定要重新读一遍：服务在解锁浮层里改了数据，内存里这份是旧的。
        all.clear();
        all.addAll(store.load());

        visible.clear();
        List<Memo> active = new ArrayList<>();
        List<Memo> done = new ArrayList<>();
        for (Memo memo : all) {
            if (memo.isDone()) {
                done.add(memo);
            } else {
                active.add(memo);
            }
        }
        // 有截止日的排前面（越近越前），其余按新写的在上。
        Collections.sort(active, (a, b) -> {
            boolean aDue = a.dueAt > 0;
            boolean bDue = b.dueAt > 0;
            if (aDue != bDue) {
                return aDue ? -1 : 1;
            }
            if (aDue) {
                return Long.compare(a.dueAt, b.dueAt);
            }
            return Long.compare(b.createdAt, a.createdAt);
        });
        Collections.sort(done, (a, b) -> Long.compare(b.doneAt, a.doneAt));

        // 长期计划（一周以后才到期）折进「以后」，平时不碍眼；弹窗那边也不弹它们。
        long now = System.currentTimeMillis();
        List<Memo> later = new ArrayList<>();
        for (int i = active.size() - 1; i >= 0; i--) {
            Memo memo = active.get(i);
            if (memo.dueAt > 0 && memo.daysLeft(now) > 7) {
                later.add(0, active.remove(i));
            }
        }

        visible.addAll(active);
        if (showLater) {
            visible.addAll(later);
        }
        if (showDone) {
            visible.addAll(done);
        }

        adapter.notifyDataSetChanged();
        empty.setVisibility(active.isEmpty() && later.isEmpty() && !showDone
                ? View.VISIBLE : View.GONE);

        if (later.isEmpty()) {
            laterFooter.setVisibility(View.GONE);
        } else {
            laterFooter.setVisibility(View.VISIBLE);
            laterFooter.setText(showLater
                    ? "收起"
                    : "以后 " + later.size() + " 件 · 点开看");
        }

        if (done.isEmpty()) {
            doneFooter.setVisibility(View.GONE);
        } else {
            doneFooter.setVisibility(View.VISIBLE);
            doneFooter.setText(showDone
                    ? "收起"
                    : "办完了 " + done.size() + " 件 · 点开看");
        }
    }

    private void openOverlaySettings() {
        try {
            startActivity(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:" + getPackageName())));
        } catch (Exception e) {
            startActivity(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION));
        }
    }

    private void requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 3);
        }
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    /** 一条待办 = 一张白卡：左边状态点，中间文字，右边截止小胶囊 */
    private class Row {
        LinearLayout outer;
        View dot;
        TextView tag;
        TextView text;
        TextView due;
    }

    private class MemoAdapter extends BaseAdapter {

        @Override
        public int getCount() {
            return visible.size();
        }

        @Override
        public Memo getItem(int position) {
            return visible.get(position);
        }

        @Override
        public long getItemId(int position) {
            return visible.get(position).id;
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            Row r;
            if (convertView != null && convertView.getTag() instanceof Row) {
                r = (Row) convertView.getTag();
            } else {
                r = new Row();
                r.outer = new LinearLayout(MainActivity.this);
                r.outer.setOrientation(LinearLayout.VERTICAL);
                r.outer.setPadding(dp(Ui.PAGE), dp(2), dp(Ui.PAGE), dp(2));

                LinearLayout card = new LinearLayout(MainActivity.this);
                card.setOrientation(LinearLayout.HORIZONTAL);
                card.setGravity(Gravity.CENTER_VERTICAL);
                card.setPadding(dp(Ui.INNER), dp(10), dp(Ui.INNER), dp(10));
                card.setBackground(Ui.round(MainActivity.this, Ui.CARD, 12, 0));

                r.dot = new View(MainActivity.this);
                LinearLayout.LayoutParams dlp =
                        new LinearLayout.LayoutParams(dp(8), dp(8));
                dlp.rightMargin = dp(12);
                card.addView(r.dot, dlp);

                LinearLayout col = new LinearLayout(MainActivity.this);
                col.setOrientation(LinearLayout.VERTICAL);

                r.tag = new TextView(MainActivity.this);
                r.tag.setTextSize(11);
                r.tag.setTextColor(Ui.ACCENT);
                r.tag.setVisibility(View.GONE);
                col.addView(r.tag);

                r.text = new TextView(MainActivity.this);
                r.text.setTextSize(15);
                r.text.setTextColor(Ui.INK);
                r.text.setLineSpacing(dp(2), 1f);
                col.addView(r.text);

                card.addView(col, new LinearLayout.LayoutParams(0,
                        LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

                r.due = new TextView(MainActivity.this);
                r.due.setTextSize(11);
                r.due.setGravity(Gravity.CENTER);
                r.due.setPadding(dp(8), dp(4), dp(8), dp(4));
                LinearLayout.LayoutParams ulp = new LinearLayout.LayoutParams(-2, -2);
                ulp.leftMargin = dp(10);
                card.addView(r.due, ulp);

                r.outer.addView(card, new LinearLayout.LayoutParams(-1, -2));
                r.outer.setTag(r);
            }

            Memo memo = getItem(position);
            long now = System.currentTimeMillis();
            String due = memo.dueLabel(now);

            r.tag.setVisibility(memo.tag.isEmpty() ? View.GONE : View.VISIBLE);
            r.tag.setText(memo.tag);

            r.text.setText(memo.text);
            r.text.setTextColor(memo.isDone() ? Ui.FAINT : memo.muted ? Ui.DIM : Ui.INK);
            r.text.setPaintFlags(memo.isDone()
                    ? (r.text.getPaintFlags() | Paint.STRIKE_THRU_TEXT_FLAG)
                    : (r.text.getPaintFlags() & ~Paint.STRIKE_THRU_TEXT_FLAG));

            boolean urgent = due != null && memo.daysLeft(now) <= 0;
            if (due == null || memo.isDone()) {
                r.due.setVisibility(View.GONE);
            } else {
                r.due.setVisibility(View.VISIBLE);
                r.due.setText(due);
                r.due.setTextColor(urgent ? Ui.DANGER : Ui.ACCENT);
                r.due.setBackground(Ui.round(MainActivity.this,
                        urgent ? Ui.DANGER_SOFT : Ui.ACCENT_SOFT, 9, 0));
            }

            int dotColor = memo.isDone() ? Ui.LINE : urgent ? Ui.DANGER : Ui.ACCENT;
            r.dot.setBackground(Ui.dot(MainActivity.this, dotColor, 8));
            return r.outer;
        }
    }
}
