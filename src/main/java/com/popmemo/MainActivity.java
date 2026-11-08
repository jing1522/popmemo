package com.popmemo;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.DatePickerDialog;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Paint;
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
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

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

    private EditText input;
    private ListView list;
    private TextView empty;
    private TextView doneFooter;
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
        permissionBanner = findViewById(R.id.permission_banner);
        Button grantOverlay = findViewById(R.id.grant_overlay);

        undoBar = findViewById(R.id.undo_bar);

        adapter = new MemoAdapter();
        list.setAdapter(adapter);

        // Android 15 强制全面屏，不自己留出状态栏的位置就会被时间压住。
        View rootView = findViewById(R.id.root);
        rootView.setOnApplyWindowInsetsListener((v, insets) -> {
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
            v.setPadding(dp(12), dp(12) + top, dp(12), dp(12) + bottom);
            return insets;
        });

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
        findViewById(R.id.overflow).setOnClickListener(v -> showCleanupMenu());
        doneFooter.setOnClickListener(v -> {
            showDone = !showDone;
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
    }

    /** 没有开关：只要能弹，就一直开着。 */
    private void ensureService() {
        boolean canDraw = Settings.canDrawOverlays(this);
        permissionBanner.setVisibility(canDraw ? View.GONE : View.VISIBLE);
        if (!canDraw) {
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

    /** 「⋯」里的清理动作。 */
    private void showCleanupMenu() {
        new AlertDialog.Builder(this)
                .setTitle("清理")
                .setItems(new String[]{"清空已经办完的", "清空全部记录"}, (dialog, which) ->
                        confirmClear(which == 0))
                .show();
    }

    private void confirmClear(boolean onlyDone) {
        String what = onlyDone ? "所有办完的记录" : "全部备忘录";
        new AlertDialog.Builder(this)
                .setTitle("清掉" + what + "？")
                .setMessage("这一步没有撤销。")
                .setNegativeButton("算了", null)
                .setPositiveButton("清掉", (dialog, which) -> {
                    if (onlyDone) {
                        for (int i = all.size() - 1; i >= 0; i--) {
                            if (all.get(i).isDone()) {
                                all.remove(i);
                            }
                        }
                    } else {
                        all.clear();
                    }
                    store.save(all);
                    refresh();
                })
                .show();
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

        visible.addAll(active);
        if (showDone) {
            visible.addAll(done);
        }

        adapter.notifyDataSetChanged();
        empty.setVisibility(active.isEmpty() && !showDone ? View.VISIBLE : View.GONE);

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
            TextView tv;
            if (convertView instanceof TextView) {
                tv = (TextView) convertView;
            } else {
                tv = new TextView(MainActivity.this);
                tv.setPadding(dp(8), dp(14), dp(8), dp(14));
                tv.setTextSize(17f);
            }
            Memo memo = getItem(position);
            long now = System.currentTimeMillis();
            String due = memo.dueLabel(now);

            SpannableStringBuilder sb = new SpannableStringBuilder();
            if (!memo.tag.isEmpty()) {
                int tagStart = sb.length();
                sb.append(memo.tag);
                sb.setSpan(new RelativeSizeSpan(0.75f), tagStart, sb.length(),
                        Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
                sb.setSpan(new ForegroundColorSpan(0xFF3B82F6), tagStart, sb.length(),
                        Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
                sb.append("  ");
            }
            sb.append(memo.text);
            if (due != null && !memo.isDone()) {
                sb.append("   ");
                int start = sb.length();
                sb.append(due);
                sb.setSpan(new RelativeSizeSpan(0.78f), start, sb.length(),
                        Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
                boolean urgent = memo.daysLeft(now) <= 0;
                sb.setSpan(new ForegroundColorSpan(urgent ? 0xFFD93025 : 0xFF9AA0A6),
                        start, sb.length(), Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
            }
            tv.setText(sb);
            if (memo.isDone()) {
                tv.setTextColor(0xFFB0B4B8);
                tv.setPaintFlags(tv.getPaintFlags() | Paint.STRIKE_THRU_TEXT_FLAG);
            } else if (memo.muted) {
                tv.setTextColor(0xFF9AA0A6);
                tv.setPaintFlags(tv.getPaintFlags() & ~Paint.STRIKE_THRU_TEXT_FLAG);
            } else {
                tv.setTextColor(0xFF111111);
                tv.setPaintFlags(tv.getPaintFlags() & ~Paint.STRIKE_THRU_TEXT_FLAG);
            }
            return tv;
        }
    }
}
