package com.popmemo;

import org.json.JSONException;
import org.json.JSONObject;

/** 一门课：哪天、从几点到几点。时间用「从 0:00 起的分钟数」存，方便算空档。 */
public class Course {

    public final long id;
    /** 1 = 周一 … 7 = 周日 */
    public int day;
    public int startMin;
    public int endMin;
    public String title;

    public Course(long id, int day, int startMin, int endMin, String title) {
        this.id = id;
        this.day = day;
        this.startMin = startMin;
        this.endMin = endMin;
        this.title = title;
    }

    public JSONObject toJson() {
        JSONObject o = new JSONObject();
        try {
            o.put("id", id);
            o.put("day", day);
            o.put("startMin", startMin);
            o.put("endMin", endMin);
            o.put("title", title);
        } catch (JSONException ignored) {
        }
        return o;
    }

    public static Course fromJson(JSONObject o) {
        return new Course(
                o.optLong("id"),
                o.optInt("day", 1),
                o.optInt("startMin"),
                o.optInt("endMin"),
                o.optString("title"));
    }
}
