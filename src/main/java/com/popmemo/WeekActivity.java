package com.popmemo;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.TimePickerDialog;
import android.graphics.Typeface;
import android.os.Bundle;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.List;

/**
 * 倒过来的课表：主角是「哪段是空的」，课只是挡在那里的块。
 */
public class WeekActivity extends Activity {

    /** 只关心 07:00 到 23:00 之间的空档，凌晨的空不算数。 */
    private static final int DAY_START = 7 * 60;
    private static final int DAY_END = 23 * 60;
    private static final int MIN_GAP = 30;

    private static final String[] DAY_NAMES = {"周一", "周二", "周三", "周四", "周五", "周六", "周日"};

    private static final int COLOR_BUSY = 0xFFEEEEEE;
    private static final int COLOR_BUSY_TEXT = 0xFF757575;
    private static final int COLOR_FREE = 0xFFE8F5E9;
    private static final int COLOR_FREE_TEXT = 0xFF2E7D32;

    private CourseStore store;
    private List<Course> courses = new ArrayList<>();
    private LinearLayout days;
    private TextView summary;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_week);

        store = new CourseStore(this);
        days = findViewById(R.id.days);
        summary = findViewById(R.id.summary);
        findViewById(R.id.add_course).setOnClickListener(v -> showAddDialog());

        render();
    }

    @Override
    protected void onResume() {
        super.onResume();
        courses = store.load();
        render();
    }

    private void render() {
        days.removeAllViews();
        int today = todayIndex();
        summary.setText(todaySummary(today));

        for (int day = 1; day <= 7; day++) {
            days.addView(dayHeader(day, day == today));
            List<Course> list = coursesOf(day);

            if (list.isEmpty()) {
                addRow(DAY_START + "–" + DAY_END, "整天都空",
                        COLOR_FREE, COLOR_FREE_TEXT, null, true);
                continue;
            }

            int cursor = DAY_START;
            for (Course course : list) {
                int start = Math.max(course.startMin, DAY_START);
                int end = Math.min(course.endMin, DAY_END);
                if (start > cursor) {
                    addGap(cursor, start);
                }
                if (end > cursor) {
                    addRow(spaced(course.startMin) + "–" + spaced(course.endMin),
                            course.title, COLOR_BUSY, COLOR_BUSY_TEXT, course, false);
                    cursor = end;
                }
            }
            if (cursor < DAY_END) {
                addGap(cursor, DAY_END);
            }
        }
    }

    private void addGap(int from, int to) {
        int length = to - from;
        if (length < MIN_GAP) {
            return;
        }
        addRow(spaced(from) + "–" + spaced(to), "空 " + duration(length),
                COLOR_FREE, COLOR_FREE_TEXT, null, true);
    }

    private String todaySummary(int today) {
        Calendar cal = Calendar.getInstance();
        int nowMin = cal.get(Calendar.HOUR_OF_DAY) * 60 + cal.get(Calendar.MINUTE);
        if (nowMin >= DAY_END) {
            return "今天已经过完了";
        }

        int from = Math.max(DAY_START, nowMin);
        int cursor = from;
        int free = 0;
        for (Course course : coursesOf(today)) {
            int start = Math.max(course.startMin, from);
            if (start > cursor) {
                free += start - cursor;
            }
            cursor = Math.max(cursor, Math.min(course.endMin, DAY_END));
        }
        if (cursor < DAY_END) {
            free += DAY_END - cursor;
        }

        if (nowMin < DAY_START) {
            return "今天从 07:00 开始算，现在还没起床";
        }
        if (free < MIN_GAP) {
            return "今天已经没空档了（到 23:00）";
        }
        return "今天还剩 " + duration(free) + " 空着";
    }

    private List<Course> coursesOf(int day) {
        List<Course> out = new ArrayList<>();
        for (Course course : courses) {
            if (course.day == day) {
                out.add(course);
            }
        }
        Collections.sort(out, (a, b) -> Integer.compare(a.startMin, b.startMin));
        return out;
    }

    private View dayHeader(int day, boolean isToday) {
        TextView tv = new TextView(this);
        tv.setText(DAY_NAMES[day - 1] + (isToday ? " · 今天" : ""));
        tv.setTextSize(15);
        tv.setTypeface(null, Typeface.BOLD);
        tv.setTextColor(isToday ? 0xFF1565C0 : 0xFF333333);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(14);
        tv.setLayoutParams(lp);
        return tv;
    }

    private void addRow(String time, String text, int background, int textColor, Course course, boolean bold) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setBackgroundColor(background);
        row.setPadding(dp(10), dp(12), dp(10), dp(12));

        LinearLayout.LayoutParams rowParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        rowParams.topMargin = dp(4);
        days.addView(row, rowParams);

        TextView timeView = new TextView(this);
        timeView.setText(time);
        timeView.setTextSize(12);
        timeView.setTextColor(textColor);
        timeView.setWidth(dp(96));

        TextView textView = new TextView(this);
        textView.setText(text);
        textView.setTextSize(15);
        textView.setTextColor(textColor);
        if (bold) {
            textView.setTypeface(null, Typeface.BOLD);
        }
        textView.setLayoutParams(new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        row.addView(timeView);
        row.addView(textView);

        if (course != null) {
            row.setOnClickListener(v -> confirmDelete(course));
        }
    }

    private void showAddDialog() {
        View view = getLayoutInflater().inflate(R.layout.dialog_add_course, null);
        Spinner spinner = view.findViewById(R.id.day_spinner);
        ArrayAdapter<String> adapter =
                new ArrayAdapter<>(this, android.R.layout.simple_spinner_item, DAY_NAMES);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spinner.setAdapter(adapter);
        spinner.setSelection(Math.max(0, todayIndex() - 1));

        Button startButton = view.findViewById(R.id.start_time);
        Button endButton = view.findViewById(R.id.end_time);
        EditText titleInput = view.findViewById(R.id.course_title);

        final int[] start = {8 * 60};
        final int[] end = {9 * 60 + 40};
        startButton.setText(spaced(start[0]));
        endButton.setText(spaced(end[0]));
        startButton.setOnClickListener(v -> pickTime(startButton, start));
        endButton.setOnClickListener(v -> pickTime(endButton, end));

        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("加一门课")
                .setView(view)
                .setNegativeButton("取消", null)
                .setPositiveButton("加进去", null)
                .create();

        dialog.setOnShowListener(d -> dialog.getButton(AlertDialog.BUTTON_POSITIVE)
                .setOnClickListener(v -> {
                    if (end[0] <= start[0]) {
                        Toast.makeText(this, "结束时间要晚于开始时间", Toast.LENGTH_SHORT).show();
                        return;
                    }
                    String name = titleInput.getText().toString().trim();
                    if (name.isEmpty()) {
                        name = "课";
                    }
                    courses.add(new Course(System.currentTimeMillis(),
                            spinner.getSelectedItemPosition() + 1, start[0], end[0], name));
                    store.save(courses);
                    dialog.dismiss();
                    render();
                }));
        dialog.show();
    }

    private void pickTime(Button target, int[] holder) {
        new TimePickerDialog(this, (v, hour, minute) -> {
            holder[0] = hour * 60 + minute;
            target.setText(spaced(holder[0]));
        }, holder[0] / 60, holder[0] % 60, true).show();
    }

    private void confirmDelete(Course course) {
        new AlertDialog.Builder(this)
                .setMessage("删掉「" + course.title + "」？")
                .setNegativeButton("留着", null)
                .setPositiveButton("删掉", (d, w) -> {
                    courses.remove(course);
                    store.save(courses);
                    render();
                })
                .show();
    }

    private int todayIndex() {
        int weekday = Calendar.getInstance().get(Calendar.DAY_OF_WEEK);
        return (weekday + 5) % 7 + 1;
    }

    /** 把分钟数格式化成 "18:30"。 */
    private String spaced(int minutes) {
        return String.format("%02d:%02d", minutes / 60, minutes % 60);
    }

    private String duration(int minutes) {
        int hours = minutes / 60;
        int rest = minutes % 60;
        if (hours == 0) {
            return rest + " 分钟";
        }
        if (rest == 0) {
            return hours + " 小时";
        }
        return hours + " 小时 " + rest + " 分";
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
