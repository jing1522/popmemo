package com.coursebox;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ContentResolver;
import android.content.Intent;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.graphics.Color;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.provider.DocumentsContract;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 批量整理：SAF 选文件夹 → 递归扫描 → 智能分类 → 按「课程·格子」分组确认 →
 * 复制校验成功后删除原文件（校验失败绝不删）。
 */
public class ImportActivity extends Activity {

    private static final int REQ_TREE = 42;
    /** 目录递归上限，防深目录把栈打爆 */
    private static final int MAX_DEPTH = 24;
    /** 每组一次最多铺多少行，剩下的点「展开全部」再建，避免几百个 View 卡主线程 */
    private static final int MAX_ROWS_PER_GROUP = 40;
    /** 只收这些类型 */
    private static final String ALLOW =
        "ppt,pptx,pdf,doc,docx,wps,rtf,txt,md,jpg,jpeg,png,gif,webp,bmp,heic,zip,rar,7z,xls,xlsx,csv";
    private static final int WARN = 0xFFBA7166;

    private static class Row {
        Uri uri; String name; long size; String parent;
        Models.Course course; Models.Cat cat;
        boolean include = true;
        /** 课程是兜底「待整理」猜来的 */
        boolean uncertain;
    }

    private static class Group {
        Models.Course course; Models.Cat cat;
        final List<Row> rows = new ArrayList<>();
        boolean expanded;
        boolean hasUncertain;
        int uncertainCount;
    }

    private Store store;
    private Box box;
    private final Handler ui = new Handler();
    private final List<Row> rows = new ArrayList<>();
    private final List<Group> groups = new ArrayList<>();

    private LinearLayout listBox;
    private TextView status;
    private LinearLayout root;
    private TextView scanBtn;
    private LinearLayout bulkHolder;
    private TextView bulkText;
    private TextView runBtn;
    private volatile boolean scanning;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        Thumb.init(this);
        store = Store.get(this);
        box = new Box(this);
        build();
    }

    private SharedPreferences sp() { return getSharedPreferences("import", 0); }

    /* ---------- 界面骨架 ---------- */

    private void build() {
        root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Ui.SCREEN);
        int p = Ui.dp(this, 22);
        root.setPadding(p, Ui.dp(this, 46), p, Ui.dp(this, 12));
        setContentView(root);

        root.addView(head());

        LinearLayout btns = new LinearLayout(this);
        LinearLayout.LayoutParams btnsLp = new LinearLayout.LayoutParams(-1, -2);
        btnsLp.topMargin = Ui.dp(this, 16);
        root.addView(btns, btnsLp);

        TextView add = actionBtn("＋ 添加文件夹", false);
        add.setOnClickListener(v -> {
            Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
            i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION
                | Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
            startActivityForResult(i, REQ_TREE);
        });
        btns.addView(add, new LinearLayout.LayoutParams(0, -2, 1));

        scanBtn = actionBtn("开始扫描", true);
        LinearLayout.LayoutParams scanLp = new LinearLayout.LayoutParams(0, -2, 1);
        scanLp.leftMargin = Ui.dp(this, 10);
        scanBtn.setOnClickListener(v -> startScan());
        btns.addView(scanBtn, scanLp);

        status = Ui.tv(this, treesInfo(), 12, Ui.MUTED, 0);
        status.setLineSpacing(Ui.dp(this, 2), 1f);
        LinearLayout.LayoutParams stLp = new LinearLayout.LayoutParams(-1, -2);
        stLp.topMargin = Ui.dp(this, 10);
        root.addView(status, stLp);

        ScrollView sv = new ScrollView(this);
        sv.setClipToPadding(false);
        listBox = new LinearLayout(this);
        listBox.setOrientation(LinearLayout.VERTICAL);
        sv.addView(listBox, new ScrollView.LayoutParams(-1, -2));
        LinearLayout.LayoutParams svLp = new LinearLayout.LayoutParams(-1, 0, 1f);
        svLp.topMargin = Ui.dp(this, 12);
        root.addView(sv, svLp);

        root.addView(bottomBar());
        renderRows();
    }

    private View head() {
        LinearLayout head = new LinearLayout(this);
        head.setGravity(Gravity.CENTER_VERTICAL);

        TextView back = Ui.tv(this, "‹", 26, Ui.INK, Typeface.BOLD);
        back.setGravity(Gravity.CENTER);
        back.setBackground(Ui.round(Color.WHITE, 12, this));
        back.setOnClickListener(v -> finish());
        head.addView(back, new LinearLayout.LayoutParams(Ui.dp(this, 36), Ui.dp(this, 36)));

        LinearLayout tt = new LinearLayout(this);
        tt.setOrientation(LinearLayout.VERTICAL);
        tt.setPadding(Ui.dp(this, 12), 0, 0, 0);
        tt.addView(Ui.tv(this, "批量整理", 24, Ui.INK, Typeface.BOLD));
        tt.addView(Ui.tv(this, "扫描只读；确认识别结果后才移动，原文件会删除", 11, Ui.MUTED, 0));
        head.addView(tt);
        return head;
    }

    private TextView actionBtn(String s, boolean main) {
        TextView t = Ui.tv(this, s, 14, main ? Color.WHITE : Ui.INK, Typeface.BOLD);
        t.setGravity(Gravity.CENTER);
        t.setBackground(main ? Ui.grad(Ui.ACCENT, Ui.ACCENT2, 14, this)
            : Ui.roundStroke(Color.WHITE, 14, Ui.RULE, this));
        t.setPadding(0, Ui.dp(this, 13), 0, Ui.dp(this, 13));
        return t;
    }

    /** 底部常驻：批量条 + 执行按钮，滚列表时也够得着 */
    private View bottomBar() {
        LinearLayout bottom = new LinearLayout(this);
        bottom.setOrientation(LinearLayout.VERTICAL);
        bottom.setPadding(0, Ui.dp(this, 8), 0, 0);

        bulkHolder = new LinearLayout(this);
        bulkHolder.setGravity(Gravity.CENTER_VERTICAL);
        bulkHolder.setBackground(Ui.roundStroke(Color.WHITE, 14, Ui.RULE, this));
        bulkHolder.setPadding(Ui.dp(this, 12), Ui.dp(this, 8), Ui.dp(this, 10), Ui.dp(this, 8));
        bulkHolder.setVisibility(View.GONE);
        LinearLayout.LayoutParams bulkLp = new LinearLayout.LayoutParams(-1, -2);
        bulkLp.bottomMargin = Ui.dp(this, 8);
        bottom.addView(bulkHolder, bulkLp);

        TextView selAll = Ui.tv(this, "全选", 13, Ui.ACCENT, Typeface.BOLD);
        selAll.setPadding(Ui.dp(this, 4), Ui.dp(this, 6), Ui.dp(this, 8), Ui.dp(this, 6));
        selAll.setOnClickListener(v -> setAll(true));
        bulkHolder.addView(selAll);

        TextView selNone = Ui.tv(this, "全不选", 13, Ui.MUTED, Typeface.BOLD);
        selNone.setPadding(Ui.dp(this, 4), Ui.dp(this, 6), Ui.dp(this, 8), Ui.dp(this, 6));
        selNone.setOnClickListener(v -> setAll(false));
        bulkHolder.addView(selNone);

        bulkText = Ui.tv(this, "", 12, Ui.INK, 0);
        bulkText.setGravity(Gravity.CENTER);
        bulkHolder.addView(bulkText, new LinearLayout.LayoutParams(0, -2, 1f));

        TextView assign = Ui.tv(this, "归到课程 ›", 13, Color.WHITE, Typeface.BOLD);
        assign.setBackground(Ui.grad(Ui.ACCENT, Ui.ACCENT2, 99, this));
        assign.setPadding(Ui.dp(this, 14), Ui.dp(this, 8), Ui.dp(this, 14), Ui.dp(this, 8));
        assign.setOnClickListener(v -> assignCourse());
        bulkHolder.addView(assign);

        runBtn = Ui.tv(this, "开始整理", 15, Color.WHITE, Typeface.BOLD);
        runBtn.setGravity(Gravity.CENTER);
        runBtn.setBackground(Ui.grad(Ui.ACCENT, Ui.ACCENT2, 16, this));
        runBtn.setPadding(0, Ui.dp(this, 15), 0, Ui.dp(this, 15));
        runBtn.setOnClickListener(v -> confirmRun());
        bottom.addView(runBtn, new LinearLayout.LayoutParams(-1, -2));
        return bottom;
    }

    private String treesInfo() {
        Set<String> ts = sp().getStringSet("trees", null);
        int n = ts == null ? 0 : ts.size();
        return n == 0 ? "还没选文件夹。建议选 Download、Pictures、DCIM、微信保存目录"
            : "已选 " + n + " 个文件夹，点「开始扫描」";
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (req != REQ_TREE || res != RESULT_OK || data == null) return;
        Uri tree = data.getData();
        try {
            getContentResolver().takePersistableUriPermission(tree,
                Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
        } catch (Exception e) {
            Ui.warn(this, "文件夹授权没记住，下次启动可能要重新选一次");
        }
        Set<String> ts = new HashSet<>(sp().getStringSet("trees", new HashSet<>()));
        ts.add(tree.toString());
        sp().edit().putStringSet("trees", ts).apply();
        status.setText(treesInfo());
    }

    /* ---------- 扫描 ---------- */

    private void startScan() {
        if (scanning) return;
        Set<String> ts = sp().getStringSet("trees", null);
        if (ts == null || ts.isEmpty()) {
            Toast.makeText(this, "先添加文件夹", Toast.LENGTH_SHORT).show();
            return;
        }
        scanning = true;
        scanBtn.setText("扫描中…");
        scanBtn.setAlpha(0.55f);
        rows.clear();
        groups.clear();
        renderRows();
        status.setText("正在扫描…");
        List<String> trees = new ArrayList<>(ts);

        new Thread(() -> {
            List<Row> found = new ArrayList<>();
            int[] tick = {0};
            int errors = 0;
            try {
                for (String s : trees) {
                    Uri tree = Uri.parse(s);
                    try {
                        walk(tree, DocumentsContract.getTreeDocumentId(tree), "", 0, found, tick);
                    } catch (Throwable t) {
                        // 单个文件夹失败不拖垮整轮；Error 也接住，否则线程静默死亡
                        errors++;
                    }
                }
            } finally {
                final int err = errors;
                ui.post(() -> finishScan(found, err));
            }
        }).start();
    }

    private void walk(Uri tree, String docId, String path, int depth,
                      List<Row> found, int[] tick) {
        if (depth > MAX_DEPTH) return;
        if (isFinishing()) return;
        ContentResolver cr = getContentResolver();
        Uri children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, docId);
        Cursor c = null;
        try {
            c = cr.query(children, new String[]{
                DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                DocumentsContract.Document.COLUMN_MIME_TYPE,
                DocumentsContract.Document.COLUMN_SIZE}, null, null, null);
            if (c == null) return;
            while (c.moveToNext()) {
                String id = c.getString(0);
                String name = c.getString(1);
                String mime = c.getString(2);
                long size = c.getLong(3);
                if (name == null || name.startsWith(".")) continue;
                if (DocumentsContract.Document.MIME_TYPE_DIR.equals(mime)) {
                    walk(tree, id, path + "/" + name, depth + 1, found, tick);
                } else {
                    int dot = name.lastIndexOf('.');
                    String ext = dot >= 0 ? name.substring(dot + 1).toLowerCase() : "";
                    if (!ALLOW.contains(ext)) continue;
                    Row r = new Row();
                    r.uri = DocumentsContract.buildDocumentUriUsingTree(tree, id);
                    r.name = name;
                    r.size = size;
                    r.parent = path;
                    Classifier.Guess g = Classifier.guess(store.courses, name, path, store.inboxCourse());
                    r.course = g.course;
                    r.cat = g.cat;
                    r.uncertain = !g.matched;
                    found.add(r);
                    if (++tick[0] % 40 == 0) {
                        final int n = found.size();
                        ui.post(() -> {
                            if (scanning) status.setText("正在扫描… 已找到 " + n + " 份");
                        });
                    }
                }
            }
        } finally {
            if (c != null) c.close();
        }
    }

    private void finishScan(List<Row> found, int errors) {
        scanning = false;
        if (isFinishing()) return;
        scanBtn.setText("开始扫描");
        scanBtn.setAlpha(1f);
        rows.clear();
        rows.addAll(found);
        renderRows();

        if (rows.isEmpty()) {
            status.setText(errors > 0
                ? "没找到可整理的文件；有 " + errors + " 个文件夹读取失败，可换个目录再试"
                : "没找到可整理的文件。换个文件夹，或确认里面有 ppt/pdf/图片等");
            return;
        }
        int unc = 0;
        for (Group g : groups) if (g.hasUncertain) unc++;
        String s = "找到 " + rows.size() + " 份，识别为 " + groups.size() + " 组";
        if (unc > 0) s += "；" + unc + " 组里有靠猜的，已展开";
        if (errors > 0) s += "；" + errors + " 个文件夹读取失败";
        status.setText(s);
    }

    /* ---------- 结果：按「课程·格子」分组 ---------- */

    private void renderRows() {
        listBox.removeAllViews();
        groups.clear();

        if (rows.isEmpty()) {
            bulkHolder.setVisibility(View.GONE);
            if (!scanning) listBox.addView(emptyCard());
            updateBulk();
            return;
        }

        Map<String, Group> map = new LinkedHashMap<>();
        for (Row r : rows) {
            String key = r.course.id + "|" + r.cat.name;
            Group g = map.get(key);
            if (g == null) {
                g = new Group();
                g.course = r.course;
                g.cat = r.cat;
                map.put(key, g);
                groups.add(g);
            }
            g.rows.add(r);
            if (r.uncertain) {
                g.hasUncertain = true;
                g.uncertainCount++;
            }
        }

        for (Group g : groups) {
            // 只自动展开需要人看一眼的组，其余收起，先把注意力留给「靠猜」
            g.expanded = g.hasUncertain;
            listBox.addView(groupView(g));
        }
        bulkHolder.setVisibility(View.VISIBLE);
        updateBulk();
    }

    private View groupView(Group g) {
        LinearLayout wrap = new LinearLayout(this);
        wrap.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams wrapLp = new LinearLayout.LayoutParams(-1, -2);
        wrapLp.bottomMargin = Ui.dp(this, 10);
        wrap.setLayoutParams(wrapLp);

        LinearLayout head = new LinearLayout(this);
        head.setGravity(Gravity.CENTER_VERTICAL);
        head.setBackground(Ui.round(Color.WHITE, 14, this));
        head.setPadding(Ui.dp(this, 14), Ui.dp(this, 12), Ui.dp(this, 14), Ui.dp(this, 12));

        View dot = new View(this);
        dot.setBackground(Ui.round(Ui.ACCENT, 6, this));
        head.addView(dot, new LinearLayout.LayoutParams(Ui.dp(this, 8), Ui.dp(this, 8)));

        LinearLayout titles = new LinearLayout(this);
        titles.setOrientation(LinearLayout.VERTICAL);
        titles.setPadding(Ui.dp(this, 10), 0, 0, 0);
        titles.addView(Ui.tv(this, g.course.name + " · " + g.cat.name, 14, Ui.INK, Typeface.BOLD));
        String sub = g.rows.size() + " 份";
        if (g.uncertainCount > 0) sub += " · " + g.uncertainCount + " 份靠猜";
        titles.addView(Ui.tv(this, sub, 11,
            g.uncertainCount > 0 ? WARN : Ui.MUTED, 0));
        head.addView(titles, new LinearLayout.LayoutParams(0, -2, 1f));

        TextView arrow = Ui.tv(this, g.expanded ? "⌄" : "›", 16,
            g.expanded ? Ui.INK : Ui.MUTED, Typeface.BOLD);
        head.addView(arrow);

        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setBackground(Ui.round(Color.WHITE, 14, this));
        content.setPadding(0, Ui.dp(this, 2), 0, Ui.dp(this, 2));
        content.setVisibility(g.expanded ? View.VISIBLE : View.GONE);
        LinearLayout.LayoutParams contentLp = new LinearLayout.LayoutParams(-1, -2);
        contentLp.topMargin = Ui.dp(this, 4);
        wrap.addView(head, new LinearLayout.LayoutParams(-1, -2));
        wrap.addView(content, contentLp);

        head.setOnClickListener(v -> {
            g.expanded = !g.expanded;
            arrow.setText(g.expanded ? "⌄" : "›");
            arrow.setTextColor(g.expanded ? Ui.INK : Ui.MUTED);
            content.setVisibility(g.expanded ? View.VISIBLE : View.GONE);
        });

        if (g.expanded) renderGroupRows(g, content);
        return wrap;
    }

    private void renderGroupRows(Group g, LinearLayout content) {
        content.removeAllViews();
        int n = Math.min(g.rows.size(), MAX_ROWS_PER_GROUP);
        for (int i = 0; i < n; i++) content.addView(rowView(g.rows.get(i), i == 0));
        if (g.rows.size() > n) {
            TextView more = Ui.tv(this, "还有 " + (g.rows.size() - n) + " 份，展开全部", 12,
                Ui.ACCENT, Typeface.BOLD);
            more.setGravity(Gravity.CENTER);
            more.setPadding(0, Ui.dp(this, 10), 0, Ui.dp(this, 10));
            more.setOnClickListener(v -> {
                content.removeView(more);
                for (int i = n; i < g.rows.size(); i++) {
                    content.addView(rowView(g.rows.get(i), false));
                }
            });
            content.addView(more, new LinearLayout.LayoutParams(-1, -2));
        }
    }

    private View rowView(Row r, boolean first) {
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(Ui.dp(this, 12), Ui.dp(this, 9), Ui.dp(this, 12), Ui.dp(this, 9));
        LinearLayout.LayoutParams rowLp = new LinearLayout.LayoutParams(-1, -2);
        row.setLayoutParams(rowLp);

        // 勾选：收进 / 跳过
        TextView tg = Ui.tv(this, r.include ? "✓" : "", 13,
            r.include ? Color.WHITE : Ui.MUTED, Typeface.BOLD);
        tg.setGravity(Gravity.CENTER);
        tg.setBackground(r.include
            ? Ui.round(Ui.ACCENT, 99, this)
            : Ui.roundStroke(0, 99, Ui.RULE, this));
        tg.setOnClickListener(v -> {
            r.include = !r.include;
            tg.setText(r.include ? "✓" : "");
            tg.setTextColor(r.include ? Color.WHITE : Ui.MUTED);
            tg.setBackground(r.include
                ? Ui.round(Ui.ACCENT, 99, this)
                : Ui.roundStroke(0, 99, Ui.RULE, this));
            updateBulk();
        });
        row.addView(tg, new LinearLayout.LayoutParams(Ui.dp(this, 24), Ui.dp(this, 24)));

        if (Ui.isImg(r.name)) {
            ImageView iv = new ImageView(this);
            iv.setScaleType(ImageView.ScaleType.CENTER_CROP);
            iv.setClipToOutline(true);
            iv.setBackground(Ui.round(0x14000000, 8, this));
            Thumb.load(getContentResolver(), r.uri, Ui.dp(this, 120), iv);
            LinearLayout.LayoutParams ilp = new LinearLayout.LayoutParams(Ui.dp(this, 40), Ui.dp(this, 40));
            ilp.leftMargin = Ui.dp(this, 10);
            row.addView(iv, ilp);
        }

        LinearLayout mid = new LinearLayout(this);
        mid.setOrientation(LinearLayout.VERTICAL);
        mid.setPadding(Ui.dp(this, 10), 0, 0, 0);
        TextView nm = Ui.tv(this, r.name, 13, Ui.INK, Typeface.BOLD);
        nm.setSingleLine();
        nm.setEllipsize(TextUtils.TruncateAt.MIDDLE);
        mid.addView(nm);
        String sub = Ui.sizeText(r.size) + " · " + (r.parent.isEmpty() ? "根目录" : r.parent);
        if (r.uncertain) sub += " · 靠猜";
        mid.addView(Ui.tv(this, sub, 11, r.uncertain ? WARN : Ui.MUTED, 0));
        row.addView(mid, new LinearLayout.LayoutParams(0, -2, 1f));

        TextView target = Ui.tv(this, r.course.name + "·" + r.cat.name + " ›", 12, Ui.ACCENT, Typeface.BOLD);
        target.setSingleLine();
        target.setEllipsize(TextUtils.TruncateAt.END);
        target.setMaxWidth(Ui.dp(this, 110));
        target.setPadding(Ui.dp(this, 8), 0, 0, 0);
        target.setOnClickListener(v -> Pick.target(this, store, "这份文件归到哪门课·格子？",
            true, null, null, (c, cat) -> {
                r.course = c;
                r.cat = cat;
                r.uncertain = false;
                target.setText(c.name + "·" + cat.name + " ›");
            }));
        row.addView(target);

        if (!first) {
            // 组内分隔线，让一组文件看起来是一张卡而不是一堆卡
            LinearLayout holder = new LinearLayout(this);
            holder.setOrientation(LinearLayout.VERTICAL);
            holder.setLayoutParams(new LinearLayout.LayoutParams(-1, -2));
            LinearLayout divider = new LinearLayout(this);
            divider.setBackgroundColor(Ui.RULE);
            LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(-1, Ui.dp(this, 1));
            dlp.leftMargin = Ui.dp(this, 46);
            holder.addView(divider, dlp);
            holder.addView(row);
            return holder;
        }
        return row;
    }

    private View emptyCard() {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackground(Ui.roundStroke(Color.WHITE, 16, Ui.RULE, this));
        card.setPadding(Ui.dp(this, 18), Ui.dp(this, 22), Ui.dp(this, 18), Ui.dp(this, 22));
        card.addView(Ui.tv(this, "还没扫描", 15, Ui.INK, Typeface.BOLD));
        TextView t = Ui.tv(this,
            "先点「添加文件夹」选 Download / Pictures / 微信保存目录，再点「开始扫描」。\n"
                + "扫描只读；确认识别结果后才会移动文件。",
            12, Ui.MUTED, 0);
        t.setLineSpacing(Ui.dp(this, 4), 1f);
        LinearLayout.LayoutParams tLp = new LinearLayout.LayoutParams(-1, -2);
        tLp.topMargin = Ui.dp(this, 8);
        card.addView(t, tLp);
        return card;
    }

    private void updateBulk() {
        int n = 0;
        for (Row r : rows) if (r.include) n++;
        if (bulkText != null) bulkText.setText("已选 " + n + " / " + rows.size());
        if (runBtn != null) {
            runBtn.setText(n == 0 ? "还没选文件" : "确认 " + n + " 份，开始整理");
            runBtn.setAlpha(n == 0 ? 0.5f : 1f);
        }
    }

    private void setAll(boolean inc) {
        for (Row r : rows) r.include = inc;
        renderRows();
    }

    /** 把所有勾选的文件统一归到某门课，格子仍按各自文件名自动猜 */
    private void assignCourse() {
        int n = 0;
        for (Row r : rows) if (r.include) n++;
        if (n == 0) { Toast.makeText(this, "先勾选文件", Toast.LENGTH_SHORT).show(); return; }
        String[] names = new String[store.courses.size()];
        for (int i = 0; i < names.length; i++) names[i] = store.courses.get(i).name;
        new AlertDialog.Builder(this)
            .setTitle("勾选的 " + n + " 份归到哪门课？")
            .setItems(names, (d, w) -> {
                Models.Course c = store.courses.get(w);
                for (Row r : rows) {
                    if (!r.include) continue;
                    r.course = c;
                    r.cat = Classifier.guessCat(c, r.name, r.parent);
                    r.uncertain = false;
                }
                renderRows();
                Toast.makeText(this, "已归到「" + c.name + "」，不满意的再单独点右侧改",
                    Toast.LENGTH_SHORT).show();
            })
            .show();
    }

    /* ---------- 执行：复制 → 校验 → 删原文件 ---------- */

    /** 按钮上已写明会删原文件，不再弹第二次确认 */
    private void confirmRun() {
        int n = 0;
        for (Row r : rows) if (r.include) n++;
        if (n == 0) { Toast.makeText(this, "清单是空的", Toast.LENGTH_SHORT).show(); return; }
        runImport(n);
    }

    private void runImport(int total) {
        status.setText("整理中… 0 / " + total);
        new Thread(() -> {
            int ok = 0, dup = 0, fail = 0, done = 0;
            ContentResolver cr = getContentResolver();
            for (Row r : rows) {
                if (!r.include) continue;
                try {
                    InputStream in = cr.openInputStream(r.uri);
                    Models.Item it = box.importStream(in, r.name, r.course, r.cat);
                    // 收进成功 or 内容重复（匣子里已有一份一样的）→ 原文件都可删
                    DocumentsContract.deleteDocument(cr, r.uri);
                    if (it == null) dup++; else ok++;
                } catch (Exception e) {
                    fail++; // 失败绝不删原文件
                }
                done++;
                final int p = done;
                ui.post(() -> {
                    if (!isFinishing()) status.setText("整理中… " + p + " / " + total);
                });
            }
            final int fOk = ok, fDup = dup, fFail = fail;
            ui.post(() -> {
                if (isFinishing()) return;
                rows.clear();
                renderRows();
                status.setText(treesInfo());
                Toast.makeText(this,
                    "收进 " + fOk + " 份，清理重复 " + fDup + " 份"
                        + (fFail > 0 ? "，失败 " + fFail + " 份（原文件未动）" : ""),
                    Toast.LENGTH_LONG).show();
            });
        }).start();
    }
}
