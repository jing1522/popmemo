package com.popmemo;

import org.json.JSONException;
import org.json.JSONObject;

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

    public Memo(long id, String text, boolean muted, long createdAt, long lastSeenAt, long doneAt) {
        this.id = id;
        this.text = text;
        this.muted = muted;
        this.createdAt = createdAt;
        this.lastSeenAt = lastSeenAt;
        this.doneAt = doneAt;
    }

    public boolean isDone() {
        return doneAt > 0;
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
                o.optLong("doneAt"));
    }
}
