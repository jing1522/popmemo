package com.coursebox;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 选「课程 · 格子」：课程是折叠的抽屉，点开才列出它的格子。
 * 这样一屏只放几门课，不用在几十个「课程·格子」里滑半天。
 */
public class Pick {

    public interface OnPick { void pick(Models.Course c, Models.Cat cat); }

    private interface OnName { void ok(String name); }

    private static class Group {
        LinearLayout kids;
        TextView arrow;
    }

    /**
     * @param withInbox false 时不列「待整理」（分享入库不该收进待整理）
     * @param exCourse  不为 null 时跳过这个格子（移动时不能移到自己）
     */
    public static AlertDialog target(Activity a, Store store, String title, boolean withInbox,
                                     Models.Course exCourse, Models.Cat exCat, OnPick cb) {
        final AlertDialog[] dlg = new AlertDialog[1];

        LinearLayout box = new LinearLayout(a);
        box.setOrientation(LinearLayout.VERTICAL);
        int pad = Ui.dp(a, 6);
        box.setPadding(pad, Ui.dp(a, 2), pad, pad);

        List<Group> groups = new ArrayList<>();

        for (Models.Course c : store.courses) {
            if (!withInbox && "inbox".equals(c.id)) continue;
            List<Models.Cat> cats = new ArrayList<>();
            for (Models.Cat cat : c.cats) {
                if (c == exCourse && cat == exCat) continue;
                cats.add(cat);
            }
            if (cats.isEmpty()) continue;

            final Group g = new Group();

            final LinearLayout row = new LinearLayout(a);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(Ui.dp(a, 12), Ui.dp(a, 13), Ui.dp(a, 12), Ui.dp(a, 13));
            row.setBackground(clickable(a));

            TextView nm = Ui.tv(a, c.name, 15, Ui.INK, Typeface.BOLD);
            row.addView(nm, new LinearLayout.LayoutParams(0, -2, 1));
            row.addView(Ui.tv(a, cats.size() + " 格", 12, Ui.MUTED, 0));
            g.arrow = Ui.tv(a, "›", 16, Ui.MUTED, 0);
            g.arrow.setPadding(Ui.dp(a, 9), 0, 0, 0);
            row.addView(g.arrow);
            box.addView(row);

            g.kids = new LinearLayout(a);
            g.kids.setOrientation(LinearLayout.VERTICAL);
            g.kids.setVisibility(View.GONE);
            for (final Models.Cat cat : cats) {
                String ic = cat.icon == null || cat.icon.isEmpty() ? "" : cat.icon + "  ";
                TextView kid = Ui.tv(a, ic + cat.name, 14, Ui.INK, 0);
                kid.setPadding(Ui.dp(a, 32), Ui.dp(a, 11), Ui.dp(a, 12), Ui.dp(a, 11));
                kid.setBackground(clickable(a));
                kid.setOnClickListener(v -> {
                    if (dlg[0] != null) dlg[0].dismiss();
                    cb.pick(c, cat);
                });
                g.kids.addView(kid);
            }
            box.addView(g.kids);
            groups.add(g);

            row.setOnClickListener(v -> {
                boolean open = g.kids.getVisibility() == View.VISIBLE;
                for (Group x : groups) {
                    x.kids.setVisibility(View.GONE);
                    x.arrow.setText("›");
                }
                if (!open) {
                    g.kids.setVisibility(View.VISIBLE);
                    g.arrow.setText("⌄");
                }
            });
        }

        // 扫描/整理时常常发现缺格子、缺课，就地补，不用退出去建
        TextView nc = Ui.tv(a, "＋ 新建格子…", 14, Ui.ACCENT, 0);
        nc.setPadding(Ui.dp(a, 12), Ui.dp(a, 13), Ui.dp(a, 12), Ui.dp(a, 13));
        nc.setBackground(clickable(a));
        nc.setOnClickListener(v -> {
            if (dlg[0] != null) dlg[0].dismiss();
            newCatFlow(a, store, cb);
        });
        box.addView(nc);

        TextView nk = Ui.tv(a, "＋ 新建课程…", 14, Ui.ACCENT, 0);
        nk.setPadding(Ui.dp(a, 12), Ui.dp(a, 13), Ui.dp(a, 12), Ui.dp(a, 13));
        nk.setBackground(clickable(a));
        nk.setOnClickListener(v -> {
            if (dlg[0] != null) dlg[0].dismiss();
            newCourseFlow(a, store, cb);
        });
        box.addView(nk);

        ScrollView sv = new ScrollView(a);
        sv.addView(box);

        dlg[0] = new AlertDialog.Builder(a)
            .setTitle(title)
            .setView(sv)
            .setNegativeButton("取消", null)
            .create();
        dlg[0].show();

        // 只有一门课时没得选，直接展开，省一次点击
        if (groups.size() == 1) {
            Group only = groups.get(0);
            only.kids.setVisibility(View.VISIBLE);
            only.arrow.setText("⌄");
        }
        return dlg[0];
    }

    private static Drawable clickable(Context c) {
        TypedValue out = new TypedValue();
        c.getTheme().resolveAttribute(android.R.attr.selectableItemBackground, out, true);
        return out.resourceId == 0 ? null : c.getDrawable(out.resourceId);
    }

    /** 先选建在哪门课，再起格子名 */
    private static void newCatFlow(Activity a, Store store, OnPick cb) {
        List<Models.Course> real = new ArrayList<>();
        List<String> names = new ArrayList<>();
        for (Models.Course c : store.courses) {
            if ("inbox".equals(c.id)) continue;
            real.add(c);
            names.add(c.name);
        }
        if (real.isEmpty()) {
            Toast.makeText(a, "还没有课程，先建一门", Toast.LENGTH_SHORT).show();
            newCourseFlow(a, store, cb);
            return;
        }
        new AlertDialog.Builder(a)
            .setTitle("新格子建在哪门课？")
            .setItems(names.toArray(new String[0]), (d, w) -> {
                Models.Course c = real.get(w);
                askName(a, "新格子叫什么", "例：实验报告", name ->
                    cb.pick(c, store.addCat(c, name, name.substring(0, 1))));
            })
            .show();
    }

    /** 新课 + 一个「资料」格子，建完直接可用 */
    private static void newCourseFlow(Activity a, Store store, OnPick cb) {
        askName(a, "新课程叫什么", "例：数据结构", name -> {
            Models.Course c = store.addCourse(name,
                (int) (Math.random() * Store.PALETTES.length), Collections.singletonList("资料"));
            cb.pick(c, c.cats.get(0));
        });
    }

    private static void askName(Activity a, String title, String hint, OnName cb) {
        EditText et = new EditText(a);
        et.setHint(hint);
        et.setSingleLine();
        int p = Ui.dp(a, 20);
        et.setPadding(p, p, p, 0);
        new AlertDialog.Builder(a)
            .setTitle(title)
            .setView(et)
            .setNegativeButton("取消", null)
            .setPositiveButton("建", (d, w) -> {
                String name = et.getText().toString().trim();
                if (name.isEmpty()) {
                    Toast.makeText(a, "名字不能为空", Toast.LENGTH_SHORT).show();
                    return;
                }
                cb.ok(name);
            })
            .show();
    }
}
