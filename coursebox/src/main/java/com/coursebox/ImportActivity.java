package com.coursebox;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ContentResolver;
import android.content.Intent;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.provider.DocumentsContract;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * 批量整理：SAF 选文件夹 → 递归扫描 → 智能分类 → 确认清单 →
 * 复制校验成功后删除原文件（校验失败绝不删）。
 */
public class ImportActivity extends Activity {

    private static final int REQ_TREE = 42;
    /** 只收这些类型 */
    private static final String ALLOW =
        "ppt,pptx,pdf,doc,docx,wps,rtf,txt,md,jpg,jpeg,png,gif,webp,bmp,heic,zip,rar,7z,xls,xlsx,csv";

    private static class Row {
        Uri uri; String name; long size; String parent;
        Models.Course course; Models.Cat cat;
        boolean include = true;
    }

    private Store store;
    private Box box;
    private Handler ui = new Handler();
    private final List<Row> rows = new ArrayList<>();

    private LinearLayout listBox;
    private TextView status;
    private LinearLayout root;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        store = Store.get(this);
        box = new Box(this);
        build();
    }

    private SharedPreferences sp() { return getSharedPreferences("import", 0); }

    private void build() {
        root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Ui.SCREEN);
        int p = Ui.dp(this, 22);
        root.setPadding(p, Ui.dp(this, 46), p, Ui.dp(this, 16));
        setContentView(root);

        // 顶栏
        LinearLayout head = new LinearLayout(this);
        head.setGravity(Gravity.CENTER_VERTICAL);
        TextView back = Ui.tv(this, "‹", 26, Ui.INK, android.graphics.Typeface.BOLD);
        back.setGravity(Gravity.CENTER);
        back.setBackground(Ui.round(Color.WHITE, 12, this));
        back.setOnClickListener(v -> finish());
        head.addView(back, new LinearLayout.LayoutParams(Ui.dp(this, 36), Ui.dp(this, 36)));
        LinearLayout tt = new LinearLayout(this);
        tt.setOrientation(LinearLayout.VERTICAL);
        tt.setPadding(Ui.dp(this, 12), 0, 0, 0);
        tt.addView(Ui.tv(this, "批量整理", 24, Ui.INK, android.graphics.Typeface.BOLD));
        tt.addView(Ui.tv(this, "确认识别结果后才移动，原文件会真删除", 12, Ui.MUTED, 0));
        head.addView(tt);
        root.addView(head);

        // 按钮行
        LinearLayout btns = new LinearLayout(this);
        LinearLayout.LayoutParams btnsLp = new LinearLayout.LayoutParams(-1, -2);
        btnsLp.topMargin = Ui.dp(this, 14);
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

        TextView scan = actionBtn("开始扫描", true);
        LinearLayout.LayoutParams scanLp = new LinearLayout.LayoutParams(0, -2, 1);
        scanLp.leftMargin = Ui.dp(this, 10);
        scan.setOnClickListener(v -> startScan());
        btns.addView(scan, scanLp);

        status = Ui.tv(this, treesInfo(), 12, Ui.MUTED, 0);
        LinearLayout.LayoutParams stLp = new LinearLayout.LayoutParams(-1, -2);
        stLp.topMargin = Ui.dp(this, 10);
        root.addView(status, stLp);

        // 清单
        ScrollView sv = new ScrollView(this);
        listBox = new LinearLayout(this);
        listBox.setOrientation(LinearLayout.VERTICAL);
        sv.addView(listBox, new ScrollView.LayoutParams(-1, -2));
        root.addView(sv, new LinearLayout.LayoutParams(-1, 0, 1));

        // 执行按钮
        TextView run = Ui.tv(this, "确认识别结果，开始整理", 15, Color.WHITE, android.graphics.Typeface.BOLD);
        run.setGravity(Gravity.CENTER);
        run.setBackground(Ui.grad(Ui.ACCENT, Ui.ACCENT2, 99, this));
        run.setPadding(0, Ui.dp(this, 15), 0, Ui.dp(this, 15));
        LinearLayout.LayoutParams runLp = new LinearLayout.LayoutParams(-1, -2);
        runLp.topMargin = Ui.dp(this, 10);
        run.setOnClickListener(v -> confirmRun());
        root.addView(run, runLp);
    }

    private TextView actionBtn(String s, boolean main) {
        TextView t = Ui.tv(this, s, 14, main ? Color.WHITE : Ui.INK, android.graphics.Typeface.BOLD);
        t.setGravity(Gravity.CENTER);
        t.setBackground(main ? Ui.grad(Ui.ACCENT, Ui.ACCENT2, 14, this)
            : Ui.roundStroke(Color.WHITE, 14, Ui.RULE, this));
        t.setPadding(0, Ui.dp(this, 13), 0, Ui.dp(this, 13));
        return t;
    }

    private String treesInfo() {
        Set<String> ts = sp().getStringSet("trees", null);
        int n = ts == null ? 0 : ts.size();
        return n == 0 ? "还没选文件夹。建议选：Download、Pictures、DCIM、微信保存目录"
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
        } catch (Exception ignored) {}
        Set<String> ts = new java.util.HashSet<>(sp().getStringSet("trees", new java.util.HashSet<>()));
        ts.add(tree.toString());
        sp().edit().putStringSet("trees", ts).apply();
        status.setText(treesInfo());
    }

    /* ---------- 扫描 ---------- */

    private void startScan() {
        Set<String> ts = sp().getStringSet("trees", null);
        if (ts == null || ts.isEmpty()) {
            Toast.makeText(this, "先添加文件夹", Toast.LENGTH_SHORT).show();
            return;
        }
        status.setText("扫描中…");
        rows.clear();
        renderRows();
        new Thread(() -> {
            for (String s : ts) {
                Uri tree = Uri.parse(s);
                try {
                    walk(tree, DocumentsContract.getTreeDocumentId(tree), "");
                } catch (Exception ignored) {}
            }
            ui.post(() -> {
                status.setText("找到 " + rows.size() + " 份文件，逐条核对下面的归类");
                renderRows();
            });
        }).start();
    }

    private void walk(Uri tree, String docId, String path) {
        ContentResolver cr = getContentResolver();
        Uri children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, docId);
        Cursor c = cr.query(children, new String[]{
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
                walk(tree, id, path + "/" + name);
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
                rows.add(r);
            }
        }
        c.close();
    }

    /* ---------- 确认清单 ---------- */

    private void renderRows() {
        listBox.removeAllViews();
        if (rows.isEmpty()) return;

        // 批量操作条：先勾选文件，再统一选课程
        LinearLayout bulk = new LinearLayout(this);
        bulk.setGravity(Gravity.CENTER_VERTICAL);
        bulk.setBackground(Ui.roundStroke(Color.WHITE, 12, Ui.RULE, this));
        bulk.setPadding(Ui.dp(this, 12), Ui.dp(this, 8), Ui.dp(this, 12), Ui.dp(this, 8));
        LinearLayout.LayoutParams bulkLp = new LinearLayout.LayoutParams(-1, -2);
        bulkLp.bottomMargin = Ui.dp(this, 10);
        listBox.addView(bulk, bulkLp);

        TextView selAll = Ui.tv(this, "全选", 13, Ui.ACCENT, android.graphics.Typeface.BOLD);
        selAll.setPadding(Ui.dp(this, 6), Ui.dp(this, 4), Ui.dp(this, 6), Ui.dp(this, 4));
        selAll.setOnClickListener(v -> setAll(true));
        bulk.addView(selAll);
        TextView selNone = Ui.tv(this, "清空", 13, Ui.MUTED, android.graphics.Typeface.BOLD);
        selNone.setPadding(Ui.dp(this, 12), Ui.dp(this, 4), Ui.dp(this, 6), Ui.dp(this, 4));
        selNone.setOnClickListener(v -> setAll(false));
        bulk.addView(selNone);

        TextView hint = Ui.tv(this, "勾好后 →", 12, Ui.MUTED, 0);
        hint.setGravity(Gravity.RIGHT | Gravity.CENTER_VERTICAL);
        bulk.addView(hint, new LinearLayout.LayoutParams(0, -2, 1));

        TextView assign = Ui.tv(this, "归到课程 ›", 13, Color.WHITE, android.graphics.Typeface.BOLD);
        assign.setBackground(Ui.grad(Ui.ACCENT, Ui.ACCENT2, 99, this));
        assign.setPadding(Ui.dp(this, 14), Ui.dp(this, 7), Ui.dp(this, 14), Ui.dp(this, 7));
        assign.setOnClickListener(v -> assignCourse());
        bulk.addView(assign);

        for (int i = 0; i < rows.size(); i++) {
            Row r = rows.get(i);
            LinearLayout row = new LinearLayout(this);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setBackground(Ui.round(Color.WHITE, 12, this));
            row.setPadding(Ui.dp(this, 12), Ui.dp(this, 10), Ui.dp(this, 12), Ui.dp(this, 10));
            LinearLayout.LayoutParams rowLp = new LinearLayout.LayoutParams(-1, -2);
            rowLp.bottomMargin = Ui.dp(this, 8);
            listBox.addView(row, rowLp);

            // 收进/跳过开关
            TextView tg = Ui.tv(this, r.include ? "✓" : "—", 14,
                r.include ? Ui.ACCENT : Ui.MUTED, android.graphics.Typeface.BOLD);
            tg.setGravity(Gravity.CENTER);
            tg.setBackground(Ui.roundStroke(0, 99, r.include ? Ui.ACCENT : Ui.RULE, this));
            final Row fr = r;
            final TextView ftg = tg;
            tg.setOnClickListener(v -> {
                fr.include = !fr.include;
                ftg.setText(fr.include ? "✓" : "—");
                ftg.setTextColor(fr.include ? Ui.ACCENT : Ui.MUTED);
            });
            row.addView(tg, new LinearLayout.LayoutParams(Ui.dp(this, 28), Ui.dp(this, 28)));

            LinearLayout mid = new LinearLayout(this);
            mid.setOrientation(LinearLayout.VERTICAL);
            mid.setPadding(Ui.dp(this, 10), 0, 0, 0);
            TextView nm = Ui.tv(this, r.name, 13, Ui.INK, android.graphics.Typeface.BOLD);
            nm.setSingleLine();
            nm.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);
            mid.addView(nm);
            mid.addView(Ui.tv(this, Ui.sizeText(r.size) + " · 来自" + (r.parent.isEmpty() ? "根目录" : r.parent), 11, Ui.MUTED, 0));
            row.addView(mid, new LinearLayout.LayoutParams(0, -2, 1));

            TextView target = Ui.tv(this, r.course.name + "·" + r.cat.name + " ›", 12, Ui.ACCENT, android.graphics.Typeface.BOLD);
            target.setPadding(Ui.dp(this, 8), 0, 0, 0);
            final TextView ft = target;
            target.setOnClickListener(v -> changeTarget(fr, ft));
            row.addView(target);
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
                }
                renderRows();
                Toast.makeText(this, "已归到「" + c.name + "」，个别不对的再单独改", Toast.LENGTH_SHORT).show();
            })
            .show();
    }

    private void changeTarget(Row r, TextView tv) {
        List<Models.Course> cs = store.courses;
        String[] names = new String[cs.size()];
        for (int i = 0; i < cs.size(); i++) names[i] = cs.get(i).name;
        new AlertDialog.Builder(this)
            .setTitle("这门文件属于哪门课？")
            .setItems(names, (d, w) -> {
                r.course = cs.get(w);
                String[] cn = new String[r.course.cats.size()];
                for (int i = 0; i < cn.length; i++) cn[i] = r.course.cats.get(i).icon + " " + r.course.cats.get(i).name;
                new AlertDialog.Builder(this)
                    .setTitle("放进哪个格子？")
                    .setItems(cn, (d2, w2) -> {
                        r.cat = r.course.cats.get(w2);
                        tv.setText(r.course.name + "·" + r.cat.name + " ›");
                    })
                    .show();
            })
            .show();
    }

    /* ---------- 执行：复制 → 校验 → 删原文件 ---------- */

    private void confirmRun() {
        int n = 0;
        for (Row r : rows) if (r.include) n++;
        if (n == 0) { Toast.makeText(this, "清单是空的", Toast.LENGTH_SHORT).show(); return; }
        final int fn = n;
        new AlertDialog.Builder(this)
            .setTitle("确认真整理？")
            .setMessage("将移动 " + fn + " 份文件进课匣，并删除原位置的文件。\n每份都会校验，失败的不会删。")
            .setPositiveButton("开始", (d, w) -> runImport())
            .setNegativeButton("再看看", null)
            .show();
    }

    private void runImport() {
        status.setText("整理中…");
        new Thread(() -> {
            int ok = 0, dup = 0, fail = 0;
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
                final int p = ok + dup + fail;
                ui.post(() -> status.setText("整理中… " + p));
            }
            final int fOk = ok, fDup = dup, fFail = fail;
            ui.post(() -> {
                rows.clear();
                renderRows();
                status.setText(treesInfo());
                Toast.makeText(this,
                    "收进 " + fOk + " 份，清理重复 " + fDup + " 份" + (fFail > 0 ? "，失败 " + fFail + " 份（原文件未动）" : ""),
                    Toast.LENGTH_LONG).show();
            });
        }).start();
    }
}
