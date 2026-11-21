package com.popmemo;

import android.app.Activity;
import android.app.TimePickerDialog;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;

/**
 * 上课时间：点格子标出有课的时段，只分黑（有课）白（空闲），不记课名。
 */
public class TimetableActivity extends Activity {

    private static final int BG = Ui.BG;
    private static final int INK = Ui.INK;
    private static final int DIM = Ui.DIM;
    private static final int RULE = Ui.LINE;
    private static final int ACCENT = Ui.ACCENT;

    /** 时间条画 6:00 到 24:00 */
    private static final int FROM = 6 * 60;
    private static final int TO = 24 * 60;
    /** 周一排到周日（Calendar：2=周一 … 7=周六，1=周日） */
    private static final int[] DAYS = {2, 3, 4, 5, 6, 7, 1};
    private static final String[] DAY_NAMES = {"一", "二", "三", "四", "五", "六", "日"};

    private Timetable tt;
    private BarView bar;
    private LinearLayout settingsBox;
    private LinearLayout gridBox;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        tt = new Timetable(this);
        build();
        refreshBar();
        Ui.lightBars(this, true);
    }

    @Override
    protected void onResume() {
        super.onResume();
        FloatingBall.setAppVisible(true);
    }

    @Override
    protected void onPause() {
        FloatingBall.setAppVisible(false);
        FloatingBall.sync(this);
        super.onPause();
    }

    private void build() {
        ScrollView sv = new ScrollView(this);
        sv.setBackgroundColor(BG);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int p = dp(22);
        root.setPadding(p, dp(46), p, dp(28));
        sv.addView(root, new ScrollView.LayoutParams(-1, -2));
        setContentView(sv);

        // 头部
        LinearLayout head = new LinearLayout(this);
        head.setGravity(Gravity.CENTER_VERTICAL);
        TextView back = text("‹", 26, INK, Typeface.BOLD);
        back.setGravity(Gravity.CENTER);
        back.setBackground(round(Color.WHITE, RULE));
        back.setOnClickListener(v -> finish());
        head.addView(back, new LinearLayout.LayoutParams(dp(36), dp(36)));

        LinearLayout titles = new LinearLayout(this);
        titles.setOrientation(LinearLayout.VERTICAL);
        titles.setPadding(dp(12), 0, 0, 0);
        titles.addView(text("上课时间", 24, INK, Typeface.BOLD));
        titles.addView(text("只点有课的格子，不用填课名", 12, DIM, Typeface.NORMAL));
        head.addView(titles, new LinearLayout.LayoutParams(0, -2, 1));
        root.addView(head);

        // 今天的时间条
        root.addView(section("今天", dp(22)));
        bar = new BarView(this);
        root.addView(bar, new LinearLayout.LayoutParams(-1, dp(40)));
        root.addView(scale(), new LinearLayout.LayoutParams(-1, -2));

        // 作息起点
        root.addView(section("作息起点", dp(24)));
        settingsBox = new LinearLayout(this);
        settingsBox.setOrientation(LinearLayout.VERTICAL);
        root.addView(settingsBox);
        buildSettings();

        // 课表网格
        root.addView(section("一周课表", dp(24)));
        gridBox = new LinearLayout(this);
        gridBox.setOrientation(LinearLayout.VERTICAL);
        root.addView(gridBox);
        buildGrid();
    }

    private void buildSettings() {
        settingsBox.removeAllViews();
        String[] names = {"早八", "下午第一节", "晚课"};
        for (int i = 0; i < 3; i++) {
            final int idx = i;
            LinearLayout row = new LinearLayout(this);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(dp(14), dp(12), dp(14), dp(12));
            row.setBackground(round(Color.WHITE, RULE));
            row.addView(text(names[i], 14, INK, Typeface.NORMAL),
                    new LinearLayout.LayoutParams(0, -2, 1));
            row.addView(text(Timetable.hm(tt.start(i)) + "  ›", 14, ACCENT, Typeface.BOLD));
            row.setOnClickListener(v -> {
                int now = tt.start(idx);
                new TimePickerDialog(this, (tp, h, m) -> {
                    tt.setStart(idx, h * 60 + m);
                    buildSettings();
                    buildGrid();
                    refreshBar();
                }, now / 60, now % 60, true).show();
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
            lp.bottomMargin = dp(8);
            settingsBox.addView(row, lp);
        }
    }

    private void buildGrid() {
        gridBox.removeAllViews();

        // 表头
        LinearLayout head = new LinearLayout(this);
        head.addView(text("", 11, DIM, Typeface.NORMAL),
                new LinearLayout.LayoutParams(dp(56), -2));
        for (String n : DAY_NAMES) {
            TextView d = text(n, 11, DIM, Typeface.BOLD);
            d.setGravity(Gravity.CENTER);
            head.addView(d, new LinearLayout.LayoutParams(0, -2, 1));
        }
        gridBox.addView(head);

        for (int period = 1; period <= Timetable.PERIODS; period++) {
            final int pd = period;
            LinearLayout row = new LinearLayout(this);
            row.setGravity(Gravity.CENTER_VERTICAL);
            LinearLayout.LayoutParams rowLp = new LinearLayout.LayoutParams(-1, -2);
            rowLp.bottomMargin = dp(3);

            LinearLayout label = new LinearLayout(this);
            label.setOrientation(LinearLayout.VERTICAL);
            label.addView(text(String.valueOf(period), 11, INK, Typeface.BOLD));
            label.addView(text(Timetable.hm(tt.startOf(period)), 9, DIM, Typeface.NORMAL));
            row.addView(label, new LinearLayout.LayoutParams(dp(56), -2));

            for (final int day : DAYS) {
                final boolean on = tt.has(day, period);
                TextView cell = new TextView(this);
                cell.setBackground(on
                        ? round(INK, 0)
                        : round(Color.WHITE, RULE));
                cell.setOnClickListener(v -> {
                    tt.set(day, pd, !tt.has(day, pd));
                    buildGrid();
                    refreshBar();
                });
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, dp(26), 1);
                lp.leftMargin = dp(2);
                lp.rightMargin = dp(2);
                row.addView(cell, lp);
            }
            gridBox.addView(row, rowLp);
        }
    }

    private void refreshBar() {
        if (bar != null) {
            bar.set(tt.today());
        }
    }

    /** 6:00 到 24:00 的黑白条，黑块就是有课的时间 */
    private class BarView extends View {
        private List<int[]> blocks = new ArrayList<>();

        BarView(Context c) {
            super(c);
        }

        void set(List<int[]> b) {
            blocks = b;
            invalidate();
        }

        @Override
        protected void onDraw(Canvas cv) {
            int w = getWidth();
            int h = getHeight();
            Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);

            p.setColor(Color.WHITE);
            cv.drawRoundRect(0, 0, w, h, dp(10), dp(10), p);
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(dp(1));
            p.setColor(RULE);
            cv.drawRoundRect(0, 0, w, h, dp(10), dp(10), p);

            p.setStyle(Paint.Style.FILL);
            p.setColor(INK);
            for (int[] b : blocks) {
                float x1 = w * (Math.max(b[0], FROM) - FROM) / (float) (TO - FROM);
                float x2 = w * (Math.min(b[1], TO) - FROM) / (float) (TO - FROM);
                if (x2 <= x1) {
                    continue;
                }
                cv.drawRoundRect(x1, 0, x2, h, dp(6), dp(6), p);
            }
        }
    }

    private LinearLayout scale() {
        LinearLayout row = new LinearLayout(this);
        for (int hour = 6; hour <= 22; hour += 2) {
            TextView t = text(String.valueOf(hour), 9, DIM, Typeface.NORMAL);
            row.addView(t, new LinearLayout.LayoutParams(0, -2, 1));
        }
        return row;
    }

    private TextView section(String title, int top) {
        TextView t = text(title, 12, DIM, Typeface.BOLD);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.topMargin = top;
        lp.bottomMargin = dp(8);
        t.setLayoutParams(lp);
        return t;
    }

    private TextView text(String s, int size, int color, int style) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(size);
        t.setTextColor(color);
        t.setTypeface(Typeface.DEFAULT, style);
        return t;
    }

    private GradientDrawable round(int fill, int stroke) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(fill);
        d.setCornerRadius(dp(8));
        if (stroke != 0) {
            d.setStroke(dp(1), stroke);
        }
        return d;
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density + 0.5f);
    }
}
