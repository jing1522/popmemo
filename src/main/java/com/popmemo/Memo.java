package com.popmemo;

import org.json.JSONException;
import org.json.JSONObject;

/** 一条备忘录。字段刻意保持少，方便存成 JSON。 */
public class Memo {

    public final long id;
    public String text;
    /** 重要 → 解锁时才会弹出来。 */
    public boolean important;
    public long createdAt;
    /** 上次弹窗时间，用于 30 分钟节流。 */
    public long lastShownAt;

    public Memo(long id, String text, boolean important, long createdAt, long lastShownAt) {
        this.id = id;
        this.text = text;
        this.important = important;
        this.createdAt = createdAt;
        this.lastShownAt = lastShownAt;
    }

    public JSONObject toJson() {
        JSONObject o = new JSONObject();
        try {
            o.put("id", id);
            o.put("text", text);
            o.put("important", important);
            o.put("createdAt", createdAt);
            o.put("lastShownAt", lastShownAt);
        } catch (JSONException ignored) {
        }
        return o;
    }

    public static Memo fromJson(JSONObject o) {
        return new Memo(
                o.optLong("id"),
                o.optString("text"),
                o.optBoolean("important"),
                o.optLong("createdAt"),
                o.optLong("lastShownAt"));
    }
}
