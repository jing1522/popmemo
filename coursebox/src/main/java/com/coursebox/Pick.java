package com.coursebox;

import android.app.Activity;
import android.app.AlertDialog;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;

/** 一步选「课程 · 格子」：把原来「先选课、再选格」的两段弹窗压成一列 */
public class Pick {

    public interface OnPick { void pick(Models.Course c, Models.Cat cat); }

    /**
     * @param withInbox false 时不列「待整理」（分享入库不该收进待整理）
     * @param exCourse  不为 null 时跳过这个格子（移动时不能移到自己）
     */
    public static AlertDialog target(Activity a, Store store, String title, boolean withInbox,
                                     Models.Course exCourse, Models.Cat exCat, OnPick cb) {
        List<Models.Course> cs = new ArrayList<>();
        List<Models.Cat> cats = new ArrayList<>();
        List<String> labels = new ArrayList<>();
        for (Models.Course c : store.courses) {
            if (!withInbox && "inbox".equals(c.id)) continue;
            for (Models.Cat cat : c.cats) {
                if (c == exCourse && cat == exCat) continue;
                cs.add(c);
                cats.add(cat);
                labels.add(c.name + " · " + cat.icon + " " + cat.name);
            }
        }
        if (labels.isEmpty()) {
            Toast.makeText(a, "还没有可选的课程格子，先去建一个", Toast.LENGTH_SHORT).show();
            return null;
        }
        AlertDialog dialog = new AlertDialog.Builder(a)
            .setTitle(title)
            .setItems(labels.toArray(new String[0]), (d, w) -> cb.pick(cs.get(w), cats.get(w)))
            .setNegativeButton("取消", null)
            .show();
        return dialog;
    }
}
