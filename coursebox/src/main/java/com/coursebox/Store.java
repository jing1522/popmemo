package com.coursebox;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** 课程索引：filesDir/index.json，随写随存 */
public class Store {

    private static Store ins;
    public static Store get(Context ctx) {
        if (ins == null) ins = new Store(ctx.getApplicationContext());
        return ins;
    }

    /** 课程默认五格（标记用单字，不用 emoji） */
    public static final String[][] DEF_CATS = {
        {"课件PPT", "课"}, {"实验报告", "报"}, {"作业", "作"}, {"截图", "图"}, {"笔记", "记"}
    };

    /** 课程渐变色（低饱和素色） */
    public static final int[][] PALETTES = {
        {0xFF7C9CB4, 0xFFA6BECC}, {0xFF9B8FAB, 0xFFBDAFC6}, {0xFFC79B7E, 0xFFDCB99E},
        {0xFF8CAE96, 0xFFAEC8B4}, {0xFFC4909C, 0xFFDBB3BB}, {0xFFBEA87E, 0xFFD6C29E}
    };

    private final Context ctx;
    public final List<Models.Course> courses = new ArrayList<>();
    public final List<Models.TrashItem> trash = new ArrayList<>();

    private Store(Context ctx) {
        this.ctx = ctx;
        load();
    }

    public Models.Course course(String cid) {
        for (Models.Course c : courses) if (c.id.equals(cid)) return c;
        return null;
    }

    public Models.Course addCourse(String name, int color, List<String> catNames) {
        Models.Course c = new Models.Course();
        c.id = UUID.randomUUID().toString().substring(0, 8);
        c.name = name;
        c.color = color;
        for (String cn : catNames) {
            Models.Cat cat = new Models.Cat();
            cat.id = UUID.randomUUID().toString().substring(0, 8);
            cat.name = cn;
            cat.icon = cn.substring(0, 1);
            c.cats.add(cat);
        }
        courses.add(c);
        save();
        return c;
    }

    public Models.Cat addCat(Models.Course c, String name, String icon) {
        Models.Cat cat = new Models.Cat();
        cat.id = UUID.randomUUID().toString().substring(0, 8);
        cat.name = name;
        cat.icon = icon;
        c.cats.add(cat);
        save();
        return cat;
    }

    /** 待整理课程（没有匹配课程的导入文件先进这里） */
    public Models.Course inboxCourse() {
        for (Models.Course c : courses) if ("inbox".equals(c.id)) return c;
        Models.Course c = new Models.Course();
        c.id = "inbox";
        c.name = "待整理";
        c.color = 1;
        for (String[] d : DEF_CATS) {
            Models.Cat cat = new Models.Cat();
            cat.id = "inbox_" + d[0];
            cat.name = d[0];
            cat.icon = d[1];
            c.cats.add(cat);
        }
        courses.add(c);
        return c;
    }

    /** 旧数据的 emoji 格子图标迁移成单字 */
    private static String migrateIcon(String icon) {
        switch (icon) {
            case "📄": return "课";
            case "🧪": return "报";
            case "📝": return "作";
            case "📸": return "图";
            case "📓": return "记";
            case "🗂️": case "🗂": return "·";
            default: return icon;
        }
    }

    /* ---------- 持久化 ---------- */

    private File file() { return new File(ctx.getFilesDir(), "index.json"); }

    public synchronized void save() {
        try {
            JSONObject root = new JSONObject();
            JSONArray cs = new JSONArray();
            for (Models.Course c : courses) {
                JSONObject co = new JSONObject();
                co.put("id", c.id).put("name", c.name).put("color", c.color);
                JSONArray ca = new JSONArray();
                for (Models.Cat cat : c.cats) {
                    JSONObject cto = new JSONObject();
                    cto.put("id", cat.id).put("name", cat.name).put("icon", cat.icon);
                    JSONArray ia = new JSONArray();
                    for (Models.Item it : cat.items) {
                        JSONObject io = new JSONObject();
                        io.put("name", it.name).put("rel", it.rel)
                          .put("size", it.size).put("at", it.at).put("sha", it.sha);
                        ia.put(io);
                    }
                    cto.put("items", ia);
                    ca.put(cto);
                }
                co.put("cats", ca);
                cs.put(co);
            }
            root.put("courses", cs);
            JSONArray ta = new JSONArray();
            for (Models.TrashItem t : trash) {
                JSONObject to = new JSONObject();
                to.put("name", t.name).put("rel", t.rel).put("size", t.size)
                  .put("at", t.at).put("sha", t.sha).put("deletedAt", t.deletedAt)
                  .put("courseId", t.courseId).put("catId", t.catId)
                  .put("courseName", t.courseName).put("catName", t.catName);
                ta.put(to);
            }
            root.put("trash", ta);
            FileOutputStream fos = new FileOutputStream(file());
            fos.write(root.toString().getBytes("UTF-8"));
            fos.close();
        } catch (Exception ignored) {}
    }

    private void load() {
        try {
            File f = file();
            if (!f.exists()) return;
            FileInputStream fis = new FileInputStream(f);
            byte[] buf = new byte[(int) f.length()];
            int off = 0, r;
            while (off < buf.length && (r = fis.read(buf, off, buf.length - off)) > 0) off += r;
            fis.close();
            JSONObject root = new JSONObject(new String(buf, "UTF-8"));
            JSONArray cs = root.getJSONArray("courses");
            for (int i = 0; i < cs.length(); i++) {
                JSONObject co = cs.getJSONObject(i);
                Models.Course c = new Models.Course();
                c.id = co.getString("id");
                c.name = co.getString("name");
                c.color = co.optInt("color");
                JSONArray ca = co.getJSONArray("cats");
                for (int j = 0; j < ca.length(); j++) {
                    JSONObject cto = ca.getJSONObject(j);
                    Models.Cat cat = new Models.Cat();
                    cat.id = cto.getString("id");
                    cat.name = cto.getString("name");
                    cat.icon = migrateIcon(cto.optString("icon", "·"));
                    JSONArray ia = cto.optJSONArray("items");
                    if (ia != null) for (int k = 0; k < ia.length(); k++) {
                        JSONObject io = ia.getJSONObject(k);
                        Models.Item it = new Models.Item();
                        it.name = io.getString("name");
                        it.rel = io.getString("rel");
                        it.size = io.optLong("size");
                        it.at = io.optLong("at");
                        it.sha = io.optString("sha", "");
                        cat.items.add(it);
                    }
                    c.cats.add(cat);
                }
                courses.add(c);
            }
            JSONArray ta = root.optJSONArray("trash");
            if (ta != null) for (int i = 0; i < ta.length(); i++) {
                JSONObject to = ta.getJSONObject(i);
                Models.TrashItem t = new Models.TrashItem();
                t.name = to.getString("name");
                t.rel = to.getString("rel");
                t.size = to.optLong("size");
                t.at = to.optLong("at");
                t.sha = to.optString("sha", "");
                t.deletedAt = to.optLong("deletedAt");
                t.courseId = to.optString("courseId", "");
                t.catId = to.optString("catId", "");
                t.courseName = to.optString("courseName", "");
                t.catName = to.optString("catName", "");
                trash.add(t);
            }
        } catch (Exception ignored) {}
    }
}
