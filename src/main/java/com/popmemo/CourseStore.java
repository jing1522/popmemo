package com.popmemo;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;

import java.util.ArrayList;
import java.util.List;

/** 课表存储。跟备忘录一样，一个 JSON 数组就够了。 */
public class CourseStore {

    private static final String PREFS = "popmemo";
    private static final String KEY = "courses";

    private final SharedPreferences sp;

    public CourseStore(Context context) {
        this.sp = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public List<Course> load() {
        List<Course> out = new ArrayList<>();
        try {
            JSONArray arr = new JSONArray(sp.getString(KEY, "[]"));
            for (int i = 0; i < arr.length(); i++) {
                out.add(Course.fromJson(arr.getJSONObject(i)));
            }
        } catch (Exception ignored) {
        }
        return out;
    }

    public void save(List<Course> courses) {
        JSONArray arr = new JSONArray();
        for (Course c : courses) {
            arr.put(c.toJson());
        }
        sp.edit().putString(KEY, arr.toString()).apply();
    }
}
