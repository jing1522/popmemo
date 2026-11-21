package com.popmemo;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;

/**
 * 上课时间：只记「周几第几节有课」，不记课名。
 * 一节 45 分钟、课间 10 分钟；1-4 节接早八，5-8 节接下午第一节，9-12 节接晚课。
 */
public class Timetable {

    public static final int PERIODS = 12;
    private static final int LEN = 45;
    private static final int GAP = 10;

    private final SharedPreferences sp;

    public Timetable(Context c) {
        sp = c.getSharedPreferences("timetable", Context.MODE_PRIVATE);
    }

    /** day 用 Calendar 的值：1=周日，2=周一 … 7=周六 */
    public boolean has(int day, int period) {
        return sp.getBoolean("p" + day + "_" + period, false);
    }

    public void set(int day, int period, boolean on) {
        sp.edit().putBoolean("p" + day + "_" + period, on).apply();
    }

    public int start(int idx) {
        String k = idx == 0 ? "morning" : idx == 1 ? "afternoon" : "evening";
        int def = idx == 0 ? 8 * 60 : idx == 1 ? 14 * 60 : 19 * 60;
        return sp.getInt(k, def);
    }

    public void setStart(int idx, int minutes) {
        String k = idx == 0 ? "morning" : idx == 1 ? "afternoon" : "evening";
        sp.edit().putInt(k, minutes).apply();
    }

    /** 第 period 节（1 起）的开始分钟数 */
    public int startOf(int period) {
        if (period <= 4) {
            return start(0) + (period - 1) * (LEN + GAP);
        }
        if (period <= 8) {
            return start(1) + (period - 5) * (LEN + GAP);
        }
        return start(2) + (period - 9) * (LEN + GAP);
    }

    public int endOf(int period) {
        return startOf(period) + LEN;
    }

    /** 今天有课的分钟区间，相邻/重叠的已合并 */
    public List<int[]> today() {
        int day = Calendar.getInstance().get(Calendar.DAY_OF_WEEK);
        return blocks(day);
    }

    public List<int[]> blocks(int day) {
        List<int[]> out = new ArrayList<>();
        for (int p = 1; p <= PERIODS; p++) {
            if (!has(day, p)) {
                continue;
            }
            int s = startOf(p);
            int e = endOf(p);
            if (!out.isEmpty() && s <= out.get(out.size() - 1)[1]) {
                int[] last = out.get(out.size() - 1);
                last[1] = Math.max(last[1], e);
            } else {
                out.add(new int[]{s, e});
            }
        }
        return out;
    }

    public static String hm(int minutes) {
        return String.format(java.util.Locale.CHINA, "%02d:%02d", minutes / 60, minutes % 60);
    }
}
