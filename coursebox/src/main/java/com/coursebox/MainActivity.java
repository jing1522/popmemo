package com.coursebox;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.GridLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;

/** 首页：课程色卡墙，支持自定义背景图（长按标题更换） */
public class MainActivity extends Activity {

    private static final int REQ_BG = 7;

    private Store store;
    private Box box;
    private LinearLayout root;
    private String query = "";

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        store = Store.get(this);
        box = new Box(this);
        box.purgeTrash(); // 回收站只留 7 天，开 App 顺手清
        build();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (root != null) build(); // 从详情/整理页回来刷新
    }

    private void build() {
        FrameLayout frame = new FrameLayout(this);

        // 自定义背景图 + 浅色遮罩保证文字可读
        Bitmap bg = loadBg();
        if (bg != null) {
            ImageView iv = new ImageView(this);
            iv.setScaleType(ImageView.ScaleType.CENTER_CROP);
            iv.setImageBitmap(bg);
            frame.addView(iv, new FrameLayout.LayoutParams(-1, -1));
            View scrim = new View(this);
            scrim.setBackgroundColor(0xB3FAF9F6);
            frame.addView(scrim, new FrameLayout.LayoutParams(-1, -1));
        }

        root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(bg == null ? Ui.SCREEN : Color.TRANSPARENT);
        int p = Ui.dp(this, 22);
        root.setPadding(p, Ui.dp(this, 46), p, Ui.dp(this, 16));
        frame.addView(root, new FrameLayout.LayoutParams(-1, -1));
        setContentView(frame);

        // 顶栏
        LinearLayout head = new LinearLayout(this);
        head.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout tt = new LinearLayout(this);
        tt.setOrientation(LinearLayout.VERTICAL);
        LinearLayout titleRow = new LinearLayout(this);
        titleRow.setGravity(Gravity.CENTER_VERTICAL);
        TextView title = Ui.tv(this, "课匣", 30, Ui.INK, android.graphics.Typeface.BOLD);
        titleRow.addView(title);
        TextView gear = Ui.tv(this, "⚙", 18, Ui.MUTED, 0);
        gear.setGravity(Gravity.CENTER);
        gear.setPadding(Ui.dp(this, 8), 0, 0, 0);
        gear.setOnClickListener(v -> bgDialog());
        titleRow.addView(gear, new LinearLayout.LayoutParams(Ui.dp(this, 40), Ui.dp(this, 40)));
        tt.addView(titleRow);
        tt.addView(Ui.tv(this, store.courses.size() + " 门课 · 材料各有其格", 13, Ui.MUTED, 0));
        head.addView(tt, new LinearLayout.LayoutParams(0, -2, 1));

        TextView btnBrowse = Ui.tv(this, "手机文件", 13, Ui.INK, android.graphics.Typeface.BOLD);
        btnBrowse.setGravity(Gravity.CENTER);
        btnBrowse.setBackground(Ui.roundStroke(Color.WHITE, 99, Ui.RULE, this));
        btnBrowse.setPadding(Ui.dp(this, 14), Ui.dp(this, 10), Ui.dp(this, 14), Ui.dp(this, 10));
        btnBrowse.setOnClickListener(v -> startActivity(new Intent(this, BrowseActivity.class)));
        head.addView(btnBrowse);

        TextView btnImport = Ui.tv(this, "批量整理", 13, Color.WHITE, android.graphics.Typeface.BOLD);
        btnImport.setGravity(Gravity.CENTER);
        btnImport.setBackground(Ui.grad(Ui.ACCENT, Ui.ACCENT2, 99, this));
        btnImport.setPadding(Ui.dp(this, 16), Ui.dp(this, 10), Ui.dp(this, 16), Ui.dp(this, 10));
        LinearLayout.LayoutParams impLp = new LinearLayout.LayoutParams(-2, -2);
        impLp.leftMargin = Ui.dp(this, 10);
        btnImport.setOnClickListener(v -> startActivity(new Intent(this, ImportActivity.class)));
        head.addView(btnImport, impLp);

        TextView btnAdd = Ui.tv(this, "＋", 20, Ui.INK, android.graphics.Typeface.BOLD);
        btnAdd.setGravity(Gravity.CENTER);
        btnAdd.setBackground(Ui.round(Color.WHITE, 14, this));
        LinearLayout.LayoutParams addLp = new LinearLayout.LayoutParams(Ui.dp(this, 44), Ui.dp(this, 44));
        addLp.leftMargin = Ui.dp(this, 10);
        btnAdd.setElevation(Ui.dp(this, 3));
        btnAdd.setOnClickListener(v -> newCourseDialog());
        head.addView(btnAdd, addLp);
        root.addView(head);

        // 统计条
        int files = 0, cats = 0;
        for (Models.Course c : store.courses) {
            files += c.count();
            cats += c.cats.size();
        }
        TextView stat = Ui.tv(this, files + " 份材料 · " + cats + " 个格子 · 点标题旁的 ⚙ 换背景", 13, Ui.INK, 0);
        stat.setBackground(Ui.roundStroke(0x147C8DA0, 14, 0x2E7C8DA0, this));
        stat.setPadding(Ui.dp(this, 16), Ui.dp(this, 12), Ui.dp(this, 16), Ui.dp(this, 12));
        LinearLayout.LayoutParams statLp = new LinearLayout.LayoutParams(-1, -2);
        statLp.topMargin = Ui.dp(this, 14);
        root.addView(stat, statLp);

        // 搜索条：跨全部课程格子搜材料名
        EditText search = new EditText(this);
        search.setHint("搜索材料名");
        search.setSingleLine();
        search.setTextSize(14);
        search.setTextColor(Ui.INK);
        search.setHintTextColor(Ui.MUTED);
        search.setBackground(Ui.roundStroke(Color.WHITE, 14, Ui.RULE, this));
        search.setPadding(Ui.dp(this, 16), Ui.dp(this, 10), Ui.dp(this, 16), Ui.dp(this, 10));
        LinearLayout.LayoutParams sLp = new LinearLayout.LayoutParams(-1, -2);
        sLp.topMargin = Ui.dp(this, 12);
        root.addView(search, sLp);

        // 课程墙（输入搜索词时切换为结果列表）
        final ScrollView sv = new ScrollView(this);
        sv.addView(wallView(), new ScrollView.LayoutParams(-1, -2));
        LinearLayout.LayoutParams svLp = new LinearLayout.LayoutParams(-1, 0, 1);
        svLp.topMargin = Ui.dp(this, 14);
        root.addView(sv, svLp);

        search.addTextChangedListener(new android.text.TextWatcher() {
            public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
            public void afterTextChanged(android.text.Editable e) {}
            public void onTextChanged(CharSequence s, int a, int b, int c) {
                query = s.toString().trim().toLowerCase();
                sv.removeAllViews();
                sv.addView(query.isEmpty() ? wallView() : resultsView(),
                    new ScrollView.LayoutParams(-1, -2));
            }
        });
    }

    private View wallView() {
        GridLayout grid = new GridLayout(this);
        grid.setColumnCount(2);
        if (store.courses.isEmpty()) {
            TextView empty = Ui.tv(this, "还没有课程\n\n点右上角 ＋ 新建一门课\n或点「批量整理」直接导入旧文件", 15, Ui.MUTED, 0);
            empty.setGravity(Gravity.CENTER);
            empty.setLineSpacing(Ui.dp(this, 6), 1);
            grid.addView(empty, cellLp(-1));
        }
        for (Models.Course c : store.courses) grid.addView(courseCard(c), cellLp(0));
        return grid;
    }

    /** 全局搜索结果：跨课程跨格子按文件名匹配，点击直接预览 */
    private View resultsView() {
        LinearLayout list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        int hit = 0;
        for (Models.Course c : store.courses)
            for (Models.Cat cat : c.cats)
                for (Models.Item it : cat.items) {
                    if (!it.name.toLowerCase().contains(query)) continue;
                    hit++;
                    LinearLayout row = new LinearLayout(this);
                    row.setOrientation(LinearLayout.VERTICAL);
                    row.setBackground(Ui.round(Color.WHITE, 14, this));
                    row.setPadding(Ui.dp(this, 14), Ui.dp(this, 10), Ui.dp(this, 14), Ui.dp(this, 10));
                    TextView n = Ui.tv(this, it.name, 14, Ui.INK, android.graphics.Typeface.BOLD);
                    n.setMaxLines(1);
                    n.setEllipsize(android.text.TextUtils.TruncateAt.END);
                    row.addView(n);
                    row.addView(Ui.tv(this, c.name + " · " + cat.name + " · " + Ui.sizeText(it.size), 11, Ui.MUTED, 0));
                    row.setOnClickListener(v -> FileServer.view(this, it));
                    LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
                    lp.bottomMargin = Ui.dp(this, 8);
                    list.addView(row, lp);
                }
        if (hit == 0) {
            TextView empty = Ui.tv(this, "没有找到名字里带「" + query + "」的材料", 14, Ui.MUTED, 0);
            empty.setGravity(Gravity.CENTER);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
            lp.topMargin = Ui.dp(this, 60);
            list.addView(empty, lp);
        }
        return list;
    }

    private GridLayout.LayoutParams cellLp(int w) {
        GridLayout.LayoutParams lp = new GridLayout.LayoutParams();
        lp.width = w == -1 ? -1 : 0;
        lp.height = -2;
        lp.columnSpec = GridLayout.spec(GridLayout.UNDEFINED, 1f);
        if (w == -1) lp.columnSpec = GridLayout.spec(0, 2, 1f);
        int m = Ui.dp(this, 7);
        lp.setMargins(m, m, m, m);
        return lp;
    }

    private View courseCard(Models.Course c) {
        int[] pal = Store.PALETTES[c.color % Store.PALETTES.length];
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackground(Ui.grad(pal[0], pal[1], 20, this));
        card.setElevation(Ui.dp(this, 5));
        int p = Ui.dp(this, 16);
        card.setPadding(p, p, p, Ui.dp(this, 12));
        card.setMinimumHeight(Ui.dp(this, 140));

        card.addView(Ui.tv(this, c.name, 17, Color.WHITE, android.graphics.Typeface.BOLD));
        TextView cnt = Ui.tv(this, c.count() + " 份材料 · " + c.cats.size() + " 格", 12, 0xDDFFFFFF, 0);
        LinearLayout.LayoutParams cntLp = new LinearLayout.LayoutParams(-1, -2);
        cntLp.topMargin = Ui.dp(this, 4);
        card.addView(cnt, cntLp);

        // 非空格子迷你计数
        LinearLayout minis = new LinearLayout(this);
        minis.setGravity(Gravity.BOTTOM);
        LinearLayout.LayoutParams minisLp = new LinearLayout.LayoutParams(-1, 0, 1);
        minisLp.topMargin = Ui.dp(this, 10);
        card.addView(minis, minisLp);
        for (Models.Cat cat : c.cats) {
            if (cat.items.isEmpty()) continue;
            TextView m = Ui.tv(this, cat.icon + " " + cat.items.size(), 10, Color.WHITE, 0);
            m.setBackground(Ui.round(0x3DFFFFFF, 8, this));
            m.setPadding(Ui.dp(this, 7), Ui.dp(this, 3), Ui.dp(this, 7), Ui.dp(this, 3));
            LinearLayout.LayoutParams mlp = new LinearLayout.LayoutParams(-2, -2);
            mlp.rightMargin = Ui.dp(this, 5);
            minis.addView(m, mlp);
        }

        card.setOnClickListener(v -> {
            Intent i = new Intent(this, CourseActivity.class);
            i.putExtra("cid", c.id);
            startActivity(i);
        });
        return card;
    }

    /* ---------- 自定义背景图 ---------- */

    private File bgFile() { return new File(getFilesDir(), "bg.jpg"); }

    private Bitmap loadBg() {
        try {
            File f = bgFile();
            if (!f.exists()) return null;
            // 先量尺寸再按屏宽降采样，避免大图撑爆内存
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(f.getAbsolutePath(), o);
            int w = getResources().getDisplayMetrics().widthPixels;
            int sample = 1;
            while (o.outWidth / sample > w * 2) sample *= 2;
            BitmapFactory.Options o2 = new BitmapFactory.Options();
            o2.inSampleSize = sample;
            return BitmapFactory.decodeFile(f.getAbsolutePath(), o2);
        } catch (Exception e) {
            return null;
        }
    }

    private void bgDialog() {
        new AlertDialog.Builder(this)
            .setTitle("设置")
            .setItems(new String[]{"换一张背景图", "恢复默认背景", "回收站", "使用指南"}, (d, w) -> {
                if (w == 0) {
                    Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
                    i.addCategory(Intent.CATEGORY_OPENABLE);
                    i.setType("image/*");
                    startActivityForResult(i, REQ_BG);
                } else if (w == 1) {
                    bgFile().delete();
                    build();
                } else if (w == 2) {
                    trashDialog();
                } else {
                    startActivity(new Intent(this, HelpActivity.class));
                }
            })
            .show();
    }

    /* ---------- 回收站 ---------- */

    private void trashDialog() {
        if (store.trash.isEmpty()) {
            new AlertDialog.Builder(this)
                .setTitle("回收站")
                .setMessage("回收站是空的。\n\n删掉的文件会在这里躺 7 天，超时自动清掉。")
                .setPositiveButton("知道了", null)
                .show();
            return;
        }
        String[] labels = new String[store.trash.size()];
        for (int i = 0; i < labels.length; i++) {
            Models.TrashItem t = store.trash.get(i);
            long days = (System.currentTimeMillis() - t.deletedAt) / 86400000L;
            labels[i] = t.name + "\n来自 " + t.courseName + " / " + t.catName
                    + (days <= 0 ? " · 今天删的" : " · " + days + " 天前删的");
        }
        new AlertDialog.Builder(this)
            .setTitle("回收站（留 7 天）")
            .setItems(labels, (d, which) -> trashItemDialog(store.trash.get(which)))
            .setNeutralButton("清空回收站", (d, w) -> confirmEmptyTrash())
            .setNegativeButton("关闭", null)
            .show();
    }

    private void trashItemDialog(Models.TrashItem t) {
        new AlertDialog.Builder(this)
            .setTitle(t.name)
            .setItems(new String[]{"放回原处", "彻底删除"}, (d, w) -> {
                if (w == 0) {
                    boolean ok = box.restore(t);
                    Toast.makeText(this, ok ? "已放回原处" : "文件已经不在了", Toast.LENGTH_SHORT).show();
                    build();
                } else {
                    box.destroy(t);
                    Toast.makeText(this, "已彻底删除", Toast.LENGTH_SHORT).show();
                }
                trashDialog();
            })
            .setNegativeButton("返回", (d, w) -> trashDialog())
            .show();
    }

    private void confirmEmptyTrash() {
        new AlertDialog.Builder(this)
            .setTitle("清空回收站？")
            .setMessage(store.trash.size() + " 份文件会彻底删掉，这一步没有后悔药。")
            .setPositiveButton("清空", (d, w) -> {
                for (int i = store.trash.size() - 1; i >= 0; i--) box.destroy(store.trash.get(i));
                Toast.makeText(this, "回收站已清空", Toast.LENGTH_SHORT).show();
            })
            .setNegativeButton("算了", (d, w) -> trashDialog())
            .show();
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (req != REQ_BG || res != RESULT_OK || data == null) return;
        try {
            Uri uri = data.getData();
            InputStream in = getContentResolver().openInputStream(uri);
            FileOutputStream fos = new FileOutputStream(bgFile());
            byte[] buf = new byte[64 * 1024];
            int r;
            while ((r = in.read(buf)) > 0) fos.write(buf, 0, r);
            in.close();
            fos.close();
            build();
        } catch (Exception e) {
            Toast.makeText(this, "背景图设置失败", Toast.LENGTH_SHORT).show();
        }
    }

    /* ---------- 新建课程 ---------- */

    private int pickedColor = 0;

    private void paintDots(LinearLayout dots) {
        dots.removeAllViews();
        for (int i = 0; i < Store.PALETTES.length; i++) {
            int[] pal = Store.PALETTES[i];
            View d = new View(this);
            d.setBackground(Ui.grad(pal[0], pal[1], 99, this));
            int s = Ui.dp(this, 34);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(s, s);
            lp.rightMargin = Ui.dp(this, 10);
            d.setAlpha(i == pickedColor ? 1f : 0.35f);
            final int idx = i;
            d.setOnClickListener(v -> { pickedColor = idx; paintDots(dots); });
            dots.addView(d, lp);
        }
    }

    private void newCourseDialog() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        int p = Ui.dp(this, 20);
        box.setPadding(p, p, p, Ui.dp(this, 8));

        EditText et = new EditText(this);
        et.setHint("课程名称，如：数据结构");
        et.setSingleLine();
        box.addView(et, new LinearLayout.LayoutParams(-1, -2));

        LinearLayout dots = new LinearLayout(this);
        LinearLayout.LayoutParams dotsLp = new LinearLayout.LayoutParams(-1, -2);
        dotsLp.topMargin = Ui.dp(this, 16);
        box.addView(dots, dotsLp);
        pickedColor = store.courses.size() % Store.PALETTES.length;
        paintDots(dots);
        box.setTag(dots);

        EditText cats = new EditText(this);
        cats.setHint("格子名，一行一个，可删改");
        cats.setMinLines(3);
        cats.setGravity(Gravity.TOP);
        cats.setTextSize(14);
        StringBuilder def = new StringBuilder();
        for (String[] d : Store.DEF_CATS) {
            if (def.length() > 0) def.append('\n');
            def.append(d[0]);
        }
        cats.setText(def.toString());
        LinearLayout.LayoutParams catsLp = new LinearLayout.LayoutParams(-1, -2);
        catsLp.topMargin = Ui.dp(this, 14);
        box.addView(cats, catsLp);

        new AlertDialog.Builder(this)
            .setTitle("新建课程")
            .setView(box)
            .setPositiveButton("创建", (d, w) -> {
                String name = et.getText().toString().trim();
                if (name.isEmpty()) { Toast.makeText(this, "先起个名", Toast.LENGTH_SHORT).show(); return; }
                java.util.List<String> names = new java.util.ArrayList<>();
                for (String line : cats.getText().toString().split("\n")) {
                    String s = line.trim();
                    if (!s.isEmpty() && !names.contains(s)) names.add(s);
                }
                store.addCourse(name, pickedColor, names);
                build();
            })
            .setNegativeButton("取消", null)
            .show();
    }
}
