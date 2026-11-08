package com.popmemo;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.Calendar;

/** 一条备忘录。字段刻意保持少，方便存成 JSON。 */
public class Memo {

    public final long id;
    public String text;
    /** 顺手记的琐事：标了就只在列表里躺着，不弹。默认 false = 会弹。 */
    public boolean muted;
    public long createdAt;
    /** 上次出现在弹窗里的时间。越久没露面，下次越靠前。 */
    public long lastSeenAt;
    /** 办完的时间。0 表示还没办。 */
    public long doneAt;
    /** 截止日（那一天的零点）。0 表示没设。越近，露面优先级越高。 */
    public long dueAt;

    public Memo(long id, String text, boolean muted, long createdAt, long lastSeenAt, long doneAt, long dueAt) {
        this.id = id;
        this.text = text;
        this.muted = muted;
        this.createdAt = createdAt;
        this.lastSeenAt = lastSeenAt;
        this.doneAt = doneAt;
        this.dueAt = dueAt;
    }

    public boolean isDone() {
        return doneAt > 0;
    }

    public static long startOfDay(long time) {
        Calendar c = Calendar.getInstance();
        c.setTimeInMillis(time);
        c.set(Calendar.HOUR_OF_DAY, 0);
        c.set(Calendar.MINUTE, 0);
        c.set(Calendar.SECOND, 0);
        c.set(Calendar.MILLISECOND, 0);
        return c.getTimeInMillis();
    }

    /** 今天往后第几天（0 = 今天）的零点，用来快速设截止日。 */
    public static long dayOffset(int days) {
        return startOfDay(System.currentTimeMillis()) + days * 86400000L;
    }

    /** 还剩几天：0 = 今天到期，负数 = 已经过了。 */
    public int daysLeft(long now) {
        if (dueAt <= 0) {
            return Integer.MIN_VALUE;
        }
        return (int) Math.round((startOfDay(dueAt) - startOfDay(now)) / 86400000.0);
    }

    /** 列表和卡片上显示的那行小字。没设截止日返回 null。 */
    public String dueLabel(long now) {
        if (dueAt <= 0) {
            return null;
        }
        int days = daysLeft(now);
        if (days < 0) {
            return "过期 " + (-days) + " 天";
        }
        if (days == 0) {
            return "今天到期";
        }
        if (days == 1) {
            return "明天到期";
        }
        if (days <= 7) {
            return days + " 天后到期";
        }
        Calendar c = Calendar.getInstance();
        c.setTimeInMillis(dueAt);
        return (c.get(Calendar.MONTH) + 1) + "月" + c.get(Calendar.DAY_OF_MONTH) + "日";
    }

    public JSONObject toJson() {
        JSONObject o = new JSONObject();
        try {
            o.put("id", id);
            o.put("text", text);
            o.put("muted", muted);
            o.put("createdAt", createdAt);
            o.put("lastSeenAt", lastSeenAt);
            o.put("doneAt", doneAt);
            o.put("dueAt", dueAt);
        } catch (JSONException ignored) {
        }
        return o;
    }

    public static Memo fromJson(JSONObject o) {
        return new Memo(
                o.optLong("id"),
                o.optString("text"),
                o.optBoolean("muted"),
                o.optLong("createdAt"),
                o.optLong("lastSeenAt"),
                o.optLong("doneAt"),
                o.optLong("dueAt"));
    }
}
