package com.popmemo;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;

import java.util.ArrayList;
import java.util.List;

/** 用 SharedPreferences 存一个 JSON 数组。数据量很小，不引数据库。 */
public class MemoStore {

    private static final String PREFS = "popmemo";
    private static final String KEY = "memos";
    private static final String EMPTY = "[]";

    private final SharedPreferences sp;

    /** 服务改了数据之后，让前台界面自己刷新一下。 */
    public interface OnChangeListener {
        void onChange();
    }

    private static OnChangeListener listener;

    public static void setListener(OnChangeListener value) {
        listener = value;
    }

    public static void notifyChanged() {
        OnChangeListener current = listener;
        if (current != null) {
            current.onChange();
        }
    }

    public MemoStore(Context context) {
        this.sp = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public List<Memo> load() {
        List<Memo> out = new ArrayList<>();
        String raw = sp.getString(KEY, EMPTY);
        try {
            JSONArray arr = new JSONArray(raw);
            for (int i = 0; i < arr.length(); i++) {
                out.add(Memo.fromJson(arr.getJSONObject(i)));
            }
        } catch (Exception ignored) {
            // 数据坏了就当空的，不让 App 起不来。
        }
        return out;
    }

    public void save(List<Memo> memos) {
        JSONArray arr = new JSONArray();
        for (Memo m : memos) {
            arr.put(m.toJson());
        }
        sp.edit().putString(KEY, arr.toString()).apply();
    }
}
