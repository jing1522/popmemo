package com.coursebox;

import android.app.Activity;
import android.app.AlertDialog;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.GridLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.util.HashSet;
import java.util.Set;

/** 课程详情：格子 chips + 文件网格 + 圈选操作 */
public class CourseActivity extends Activity {

    private Store store;
    private Box box;
    private Models.Course course;
    private int catIdx = 0;
    private String query = "";
    private final Set<Integer> sel = new HashSet<>();

    private LinearLayout root;
    private LinearLayout band;
    private LinearLayout chips;
    private GridLayout grid;
    private LinearLayout selbar;
    private TextView selN;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        store = Store.get(this);
        box = new Box(this);
        course = store.course(getIntent().getStringExtra("cid"));
        if (course == null) { finish(); return; }
        build();
    }

    private Models.Cat cat() { return course.cats.get(catIdx); }

    private void build() {
        root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Ui.SCREEN);
        setContentView(root);
        buildBand();
        buildChips();
        buildSearch();
        buildFiles();
        buildSelbar();
    }

    /* ---------- 格子内搜索 ---------- */

    private void buildSearch() {
        EditText search = new EditText(this);
        search.setHint("搜这个格子");
        search.setSingleLine();
        search.setTextSize(13);
        search.setTextColor(Ui.INK);
        search.setHintTextColor(Ui.MUTED);
        search.setBackground(Ui.roundStroke(Color.WHITE, 99, Ui.RULE, this));
        search.setPadding(Ui.dp(this, 16), Ui.dp(this, 7), Ui.dp(this, 16), Ui.dp(this, 7));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.leftMargin = Ui.dp(this, 22);
        lp.rightMargin = Ui.dp(this, 22);
        lp.topMargin = Ui.dp(this, 6);
        root.addView(search, lp); // 固定在第 3 位（band、chips 之后）
        search.addTextChangedListener(new android.text.TextWatcher() {
            public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
            public void afterTextChanged(android.text.Editable e) {}
            public void onTextChanged(CharSequence s, int a, int b, int c) {
                query = s.toString().trim().toLowerCase();
                buildFiles();
            }
        });
    }

    /* ---------- 顶部色带 ---------- */

    private void buildBand() {
        if (band != null) root.removeView(band);
        int[] pal = Store.PALETTES[course.color % Store.PALETTES.length];
        band = new LinearLayout(this);
        band.setOrientation(LinearLayout.VERTICAL);
        band.setBackground(Ui.grad(pal[0], pal[1], 0, this));
        band.setPadding(Ui.dp(this, 22), Ui.dp(this, 46), Ui.dp(this, 22), Ui.dp(this, 18));
        // 底部圆角
        android.graphics.drawable.GradientDrawable bg = Ui.grad(pal[0], pal[1], 28, this);
        bg.setCornerRadii(new float[]{0, 0, 0, 0,
            Ui.dp(this, 28), Ui.dp(this, 28), Ui.dp(this, 28), Ui.dp(this, 28)});
        band.setBackground(bg);

        LinearLayout nav = new LinearLayout(this);
        nav.setGravity(Gravity.CENTER_VERTICAL);
        TextView back = Ui.tv(this, "‹", 26, Color.WHITE, android.graphics.Typeface.BOLD);
        back.setGravity(Gravity.CENTER);
        back.setBackground(Ui.round(0x3DFFFFFF, 12, this));
        back.setOnClickListener(v -> finish());
        nav.addView(back, new LinearLayout.LayoutParams(Ui.dp(this, 36), Ui.dp(this, 36)));

        LinearLayout dots = new LinearLayout(this);
        dots.setGravity(Gravity.RIGHT | Gravity.CENTER_VERTICAL);
        for (int i = 0; i < Store.PALETTES.length; i++) {
            int[] p = Store.PALETTES[i];
            View d = new View(this);
            d.setBackground(Ui.grad(p[0], p[1], 99, this));
            int s = Ui.dp(this, 20);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(s, s);
            lp.leftMargin = Ui.dp(this, 7);
            d.setAlpha(i == course.color ? 1f : 0.45f);
            final int idx = i;
            d.setOnClickListener(v -> {
                course.color = idx;
                store.save();
                buildBand();
            });
            dots.addView(d, lp);
        }
        nav.addView(dots, new LinearLayout.LayoutParams(0, -2, 1));
        band.addView(nav);

        TextView name = Ui.tv(this, course.name, 25, Color.WHITE, android.graphics.Typeface.BOLD);
        LinearLayout.LayoutParams nameLp = new LinearLayout.LayoutParams(-1, -2);
        nameLp.topMargin = Ui.dp(this, 12);
        band.addView(name, nameLp);
        band.addView(Ui.tv(this, course.count() + " 份材料 · " + course.cats.size() + " 个格子", 12, 0xE0FFFFFF, 0));
        root.addView(band);
    }

    /* ---------- 格子 chips ---------- */

    private void buildChips() {
        if (chips != null) root.removeView(chips.getParent() instanceof View ? (View) chips.getParent() : chips);
        if (catIdx >= course.cats.size()) catIdx = Math.max(0, course.cats.size() - 1);
        HorizontalScrollView hsv = new HorizontalScrollView(this);
        hsv.setHorizontalScrollBarEnabled(false);
        chips = new LinearLayout(this);
        chips.setPadding(Ui.dp(this, 22), Ui.dp(this, 14), Ui.dp(this, 22), Ui.dp(this, 4));
        hsv.addView(chips);
        root.addView(hsv, 1); // 固定在色带之下，避免重建时被排到页面底部

        for (int i = 0; i < course.cats.size(); i++) {
            Models.Cat c = course.cats.get(i);
            boolean on = i == catIdx;
            TextView t = Ui.tv(this, c.icon + " " + c.name + "  " + c.items.size(), 13,
                on ? Color.WHITE : Ui.INK, android.graphics.Typeface.BOLD);
            t.setBackground(on ? Ui.round(Ui.INK, 99, this) : Ui.round(Color.WHITE, 99, this));
            t.setElevation(Ui.dp(this, 2));
            t.setPadding(Ui.dp(this, 14), Ui.dp(this, 9), Ui.dp(this, 14), Ui.dp(this, 9));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
            lp.rightMargin = Ui.dp(this, 8);
            final int idx = i;
            t.setOnClickListener(v -> {
                catIdx = idx;
                sel.clear();
                buildChips();
                buildFiles();
                updateSelbar();
            });
            t.setOnLongClickListener(v -> { catOptions(c); return true; });
            chips.addView(t, lp);
        }

        TextView add = Ui.tv(this, "＋ 格子", 13, Ui.MUTED, android.graphics.Typeface.BOLD);
        add.setBackground(Ui.roundStroke(0, 99, 0xFFCDD3E4, this));
        add.setPadding(Ui.dp(this, 14), Ui.dp(this, 9), Ui.dp(this, 14), Ui.dp(this, 9));
        add.setOnClickListener(v -> newCatDialog());
        chips.addView(add);
    }

    /* ---------- 文件网格 ---------- */

    private void buildFiles() {
        if (grid != null) root.removeView((View) grid.getParent());
        ScrollView sv = new ScrollView(this);
        grid = new GridLayout(this);
        grid.setColumnCount(2);
        grid.setPadding(Ui.dp(this, 15), Ui.dp(this, 8), Ui.dp(this, 15), Ui.dp(this, 120));
        sv.addView(grid, new ScrollView.LayoutParams(-1, -2));
        root.addView(sv, 3, new LinearLayout.LayoutParams(-1, 0, 1)); // 固定在第 4 位

        Models.Cat c = course.cats.isEmpty() ? null : cat();
        int shown = 0;
        if (c != null)
        for (int i = 0; i < c.items.size(); i++) {
            if (!query.isEmpty() && !c.items.get(i).name.toLowerCase().contains(query)) continue;
            grid.addView(fileCard(c.items.get(i), i), cellLp());
            shown++;
        }
        if (shown == 0) {
            TextView empty = Ui.tv(this,
                c == null ? "还没有格子\n\n点上面的「＋ 格子」建一个"
                : query.isEmpty() ? "这个格子还是空的\n\n系统里分享文件到「课匣」就能收进来"
                                : "这格里没有名字带「" + query + "」的材料", 14, Ui.MUTED, 0);
            empty.setGravity(Gravity.CENTER);
            empty.setLineSpacing(Ui.dp(this, 6), 1);
            GridLayout.LayoutParams lp = new GridLayout.LayoutParams();
            lp.columnSpec = GridLayout.spec(0, 2, 1f);
            lp.topMargin = Ui.dp(this, 60);
            grid.addView(empty, lp);
        }
    }

    private GridLayout.LayoutParams cellLp() {
        GridLayout.LayoutParams lp = new GridLayout.LayoutParams();
        lp.width = 0;
        lp.height = -2;
        lp.columnSpec = GridLayout.spec(GridLayout.UNDEFINED, 1f);
        int m = Ui.dp(this, 6);
        lp.setMargins(m, m, m, m);
        return lp;
    }

    private View fileCard(Models.Item it, int idx) {
        FrameLayout wrap = new FrameLayout(this);

        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        boolean on = sel.contains(idx);
        card.setBackground(Ui.roundStroke(Color.WHITE, 14, on ? Ui.ACCENT : Color.TRANSPARENT, this));
        card.setElevation(Ui.dp(this, 3));

        // 缩略区
        String type = Ui.typeOf(it.name);
        FrameLayout thumb = new FrameLayout(this);
        int[] tc = Ui.typeColors(type);
        thumb.setBackground(Ui.grad(tc[0], tc[1], 0, this));
        if (Ui.isImg(it.name)) {
            ImageView iv = new ImageView(this);
            iv.setScaleType(ImageView.ScaleType.CENTER_CROP);
            File f = box.fileOf(course, cat(), it);
            Bitmap bm = Thumb.get(f, Ui.dp(this, 180));
            if (bm != null) iv.setImageBitmap(bm);
            thumb.addView(iv, new FrameLayout.LayoutParams(-1, -1));
        } else {
            TextView tl = Ui.tv(this, Ui.typeLabel(type), 15, Color.WHITE, android.graphics.Typeface.BOLD);
            tl.setGravity(Gravity.CENTER);
            thumb.addView(tl, new FrameLayout.LayoutParams(-1, -1));
        }
        card.addView(thumb, new LinearLayout.LayoutParams(-1, Ui.dp(this, 86)));

        TextView name = Ui.tv(this, it.name, 12, Ui.INK, android.graphics.Typeface.BOLD);
        name.setMaxLines(2);
        name.setEllipsize(android.text.TextUtils.TruncateAt.END);
        name.setMinHeight(Ui.dp(this, 38));
        name.setPadding(Ui.dp(this, 10), Ui.dp(this, 8), Ui.dp(this, 10), 0);
        card.addView(name);

        TextView meta = Ui.tv(this, Ui.sizeText(it.size) + " · " + Ui.dateText(it.at), 10, Ui.MUTED, 0);
        meta.setPadding(Ui.dp(this, 10), Ui.dp(this, 2), Ui.dp(this, 10), Ui.dp(this, 10));
        card.addView(meta);

        wrap.addView(card, new FrameLayout.LayoutParams(-1, -2));

        TextView tick = Ui.tv(this, "✓", 13, on ? Color.WHITE : Color.TRANSPARENT, android.graphics.Typeface.BOLD);
        tick.setGravity(Gravity.CENTER);
        tick.setBackground(Ui.roundStroke(on ? Ui.ACCENT : 0xD9FFFFFF, 99, on ? Ui.ACCENT : 0x401C2130, this));
        tick.setVisibility(sel.isEmpty() ? View.GONE : View.VISIBLE);
        FrameLayout.LayoutParams tickLp = new FrameLayout.LayoutParams(Ui.dp(this, 24), Ui.dp(this, 24));
        tickLp.gravity = Gravity.TOP | Gravity.RIGHT;
        tickLp.topMargin = Ui.dp(this, 8);
        tickLp.rightMargin = Ui.dp(this, 8);
        wrap.addView(tick, tickLp);

        // 点按：已在圈选就继续圈选，否则调系统应用打开预览；长按进入圈选
        wrap.setOnClickListener(v -> {
            if (!sel.isEmpty()) {
                if (sel.contains(idx)) sel.remove(idx); else sel.add(idx);
                buildFiles();
                updateSelbar();
            } else {
                openItem(it);
            }
        });
        wrap.setOnLongClickListener(v -> {
            if (sel.contains(idx)) sel.remove(idx); else sel.add(idx);
            buildFiles();
            updateSelbar();
            return true;
        });
        return wrap;
    }

    /* ---------- 预览 ---------- */

    private void openItem(Models.Item it) {
        FileServer.view(this, it);
    }

    @Override
    public void onBackPressed() {
        if (!sel.isEmpty()) {
            sel.clear();
            buildFiles();
            updateSelbar();
        } else {
            super.onBackPressed();
        }
    }

    /* ---------- 选择操作条 ---------- */

    private void buildSelbar() {
        selbar = new LinearLayout(this);
        selbar.setGravity(Gravity.CENTER_VERTICAL);
        selbar.setBackground(Ui.round(Ui.INK, 99, this));
        selbar.setElevation(Ui.dp(this, 10));
        selbar.setPadding(Ui.dp(this, 20), Ui.dp(this, 4), Ui.dp(this, 8), Ui.dp(this, 4));
        selbar.setVisibility(View.GONE);

        selN = Ui.tv(this, "已选 0 项", 13, Color.WHITE, android.graphics.Typeface.BOLD);
        selbar.addView(selN, new LinearLayout.LayoutParams(0, -2, 1));
        addSelBtn("全选", false, v -> {
            Models.Cat c = cat();
            if (sel.size() == c.items.size()) sel.clear();
            else { sel.clear(); for (int i = 0; i < c.items.size(); i++) sel.add(i); }
            buildFiles(); updateSelbar();
        });
        addSelBtn("移动", false, v -> moveDialog());
        addSelBtn("导出", false, v -> {
            int ok = 0;
            Models.Cat c = cat();
            for (int i : sel) if (box.export(c.items.get(i))) ok++;
            Toast.makeText(this, "已导出 " + ok + " 份到 Download/课匣导出", Toast.LENGTH_LONG).show();
        });
        addSelBtn("删除", true, v -> {
            Models.Cat c = cat();
            Integer[] idx = sel.toArray(new Integer[0]);
            java.util.Arrays.sort(idx, java.util.Collections.reverseOrder());
            for (int i : idx) box.delete(course, c, c.items.get(i));
            int n = idx.length;
            sel.clear();
            buildChips(); buildFiles(); buildBand(); updateSelbar();
            Toast.makeText(this, "已删除 " + n + " 份", Toast.LENGTH_SHORT).show();
        });

        FrameLayout overlay = new FrameLayout(this);
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(-1, Ui.dp(this, 52));
        lp.gravity = Gravity.BOTTOM;
        lp.leftMargin = Ui.dp(this, 18);
        lp.rightMargin = Ui.dp(this, 18);
        lp.bottomMargin = Ui.dp(this, 24);
        overlay.addView(selbar, lp);
        addContentView(overlay, new FrameLayout.LayoutParams(-1, -1));
    }

    private void addSelBtn(String text, boolean main, View.OnClickListener l) {
        TextView t = Ui.tv(this, text, 13, Color.WHITE, android.graphics.Typeface.BOLD);
        if (main) t.setBackground(Ui.grad(Ui.ACCENT, Ui.ACCENT2, 99, this));
        t.setPadding(Ui.dp(this, 13), Ui.dp(this, 10), Ui.dp(this, 13), Ui.dp(this, 10));
        t.setOnClickListener(l);
        selbar.addView(t);
    }

    private void updateSelbar() {
        selN.setText("已选 " + sel.size() + " 项");
        selbar.setVisibility(sel.isEmpty() ? View.GONE : View.VISIBLE);
    }

    /* ---------- 弹窗 ---------- */

    private void moveDialog() {
        Models.Cat from = cat();
        String[] names = new String[course.cats.size() - 1];
        final Models.Cat[] targets = new Models.Cat[course.cats.size() - 1];
        int k = 0;
        for (Models.Cat c : course.cats) {
            if (c == from) continue;
            names[k] = c.icon + " " + c.name;
            targets[k++] = c;
        }
        new AlertDialog.Builder(this)
            .setTitle("移动 " + sel.size() + " 份到…")
            .setItems(names, (d, w) -> {
                Models.Cat to = targets[w];
                Integer[] idx = sel.toArray(new Integer[0]);
                java.util.Arrays.sort(idx, java.util.Collections.reverseOrder());
                for (int i : idx) box.move(course, from, to, from.items.get(i));
                sel.clear();
                buildChips(); buildFiles(); updateSelbar();
                Toast.makeText(this, "已移到「" + to.name + "」", Toast.LENGTH_SHORT).show();
            })
            .show();
    }

    private static final String[] EMOJIS = {"课", "报", "作", "图", "记", "★"};
    private int emojiIdx = 0;

    private void paintEmojis(LinearLayout row) {
        row.removeAllViews();
        for (int i = 0; i < EMOJIS.length; i++) {
            TextView e = Ui.tv(this, EMOJIS[i], 20, Ui.INK, 0);
            e.setGravity(Gravity.CENTER);
            e.setBackground(Ui.roundStroke(i == emojiIdx ? 0x1A7C8DA0 : 0, 14,
                i == emojiIdx ? Ui.ACCENT : Ui.RULE, this));
            int s = Ui.dp(this, 44);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(s, s);
            lp.rightMargin = Ui.dp(this, 8);
            final int idx = i;
            e.setOnClickListener(v -> { emojiIdx = idx; paintEmojis(row); });
            row.addView(e, lp);
        }
    }

    /* ---------- 格子改名/删除 ---------- */

    private void catOptions(Models.Cat c) {
        new AlertDialog.Builder(this)
            .setTitle(c.icon + " " + c.name)
            .setItems(new String[]{"重命名", "删除格子"}, (d, w) -> {
                if (w == 0) renameCatDialog(c);
                else confirmDeleteCat(c);
            })
            .show();
    }

    private void renameCatDialog(Models.Cat c) {
        final EditText et = new EditText(this);
        et.setText(c.name);
        et.setSingleLine();
        int p = Ui.dp(this, 20);
        et.setPadding(p, p, p, 0);
        new AlertDialog.Builder(this)
            .setTitle("格子改名")
            .setView(et)
            .setPositiveButton("保存", (d, w) -> {
                String name = et.getText().toString().trim();
                if (name.isEmpty()) { Toast.makeText(this, "名字不能为空", Toast.LENGTH_SHORT).show(); return; }
                c.name = name;
                c.icon = name.substring(0, 1);
                store.save();
                buildChips(); buildFiles();
            })
            .setNegativeButton("取消", null)
            .show();
    }

    private void confirmDeleteCat(Models.Cat c) {
        int n = c.items.size();
        new AlertDialog.Builder(this)
            .setTitle("删掉格子「" + c.name + "」？")
            .setMessage(n == 0 ? "格子是空的，直接删。" : "格子里的 " + n + " 份文件会一起从匣子里删掉，找不回来。")
            .setPositiveButton("删除", (d, w) -> {
                for (Models.Item it : new java.util.ArrayList<>(c.items)) box.delete(course, c, it);
                course.cats.remove(c);
                store.save();
                sel.clear();
                buildBand(); buildChips(); buildFiles(); updateSelbar();
            })
            .setNegativeButton("取消", null)
            .show();
    }

    private void newCatDialog() {
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        int p = Ui.dp(this, 20);
        layout.setPadding(p, p, p, Ui.dp(this, 8));

        EditText et = new EditText(this);
        et.setHint("格子名称，如：往年试卷");
        et.setSingleLine();
        layout.addView(et, new LinearLayout.LayoutParams(-1, -2));

        LinearLayout row = new LinearLayout(this);
        LinearLayout.LayoutParams rowLp = new LinearLayout.LayoutParams(-1, -2);
        rowLp.topMargin = Ui.dp(this, 14);
        layout.addView(row, rowLp);
        emojiIdx = 0;
        paintEmojis(row);

        new AlertDialog.Builder(this)
            .setTitle("给这门课加个格子")
            .setView(layout)
            .setPositiveButton("添加", (d, w) -> {
                String name = et.getText().toString().trim();
                if (name.isEmpty()) { Toast.makeText(this, "先起个名", Toast.LENGTH_SHORT).show(); return; }
                store.addCat(course, name, EMOJIS[emojiIdx]);
                catIdx = course.cats.size() - 1;
                sel.clear();
                buildChips(); buildFiles(); buildBand();
            })
            .setNegativeButton("取消", null)
            .show();
    }
}
