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
import android.provider.DocumentsContract;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 手机文件：浏览 SAF 已授权文件夹。
 * 点按文件=系统应用预览，长按=圈选，底部「收进匣子 / 直接删除」。
 * 直接删除是真删（DocumentsContract.deleteDocument），有确认框。
 */
public class BrowseActivity extends Activity {

    private static final int REQ_TREE = 43;

    private static class Doc {
        Uri uri; String id; String name; long size; boolean dir; boolean sel;
    }

    private Store store;
    private Box box;

    private Uri tree;                 // 当前进入的授权树（null=还在文件夹列表页）
    private final List<String> idStack = new ArrayList<>();   // 目录 docId 路径
    private final List<String> nameStack = new ArrayList<>(); // 目录名路径
    private final List<Doc> docs = new ArrayList<>();

    private LinearLayout listBox;
    private TextView status;
    private LinearLayout bottom;
    private TextView selInfo;
    private LinearLayout root;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        store = Store.get(this);
        box = new Box(this);
        build();
    }

    private SharedPreferences sp() { return getSharedPreferences("import", 0); }

    private Set<String> trees() {
        return new HashSet<>(sp().getStringSet("trees", new HashSet<>()));
    }

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
        back.setOnClickListener(v -> goBack());
        head.addView(back, new LinearLayout.LayoutParams(Ui.dp(this, 36), Ui.dp(this, 36)));
        LinearLayout tt = new LinearLayout(this);
        tt.setOrientation(LinearLayout.VERTICAL);
        tt.setPadding(Ui.dp(this, 12), 0, 0, 0);
        tt.addView(Ui.tv(this, "手机文件", 24, Ui.INK, android.graphics.Typeface.BOLD));
        tt.addView(Ui.tv(this, "点按预览 · 长按圈选后可归档或真删", 12, Ui.MUTED, 0));
        head.addView(tt, new LinearLayout.LayoutParams(0, -2, 1));

        TextView add = Ui.tv(this, "＋文件夹", 12, Ui.ACCENT, android.graphics.Typeface.BOLD);
        add.setGravity(Gravity.CENTER);
        add.setBackground(Ui.roundStroke(Color.WHITE, 99, Ui.RULE, this));
        add.setPadding(Ui.dp(this, 12), Ui.dp(this, 8), Ui.dp(this, 12), Ui.dp(this, 8));
        add.setOnClickListener(v -> {
            Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
            i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION
                | Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
            startActivityForResult(i, REQ_TREE);
        });
        head.addView(add);
        root.addView(head);

        status = Ui.tv(this, "", 12, Ui.MUTED, 0);
        LinearLayout.LayoutParams stLp = new LinearLayout.LayoutParams(-1, -2);
        stLp.topMargin = Ui.dp(this, 8);
        root.addView(status, stLp);

        ScrollView sv = new ScrollView(this);
        listBox = new LinearLayout(this);
        listBox.setOrientation(LinearLayout.VERTICAL);
        sv.addView(listBox, new ScrollView.LayoutParams(-1, -2));
        root.addView(sv, new LinearLayout.LayoutParams(-1, 0, 1));

        // 底部圈选动作条
        bottom = new LinearLayout(this);
        bottom.setOrientation(LinearLayout.VERTICAL);
        bottom.setBackground(Ui.round(Color.WHITE, 18, this));
        bottom.setPadding(Ui.dp(this, 14), Ui.dp(this, 10), Ui.dp(this, 14), Ui.dp(this, 10));
        bottom.setElevation(Ui.dp(this, 6));
        LinearLayout.LayoutParams btLp = new LinearLayout.LayoutParams(-1, -2);
        btLp.topMargin = Ui.dp(this, 10);
        bottom.setVisibility(View.GONE);
        root.addView(bottom, btLp);

        LinearLayout row1 = new LinearLayout(this);
        row1.setGravity(Gravity.CENTER_VERTICAL);
        selInfo = Ui.tv(this, "", 13, Ui.INK, android.graphics.Typeface.BOLD);
        row1.addView(selInfo, new LinearLayout.LayoutParams(0, -2, 1));
        TextView all = Ui.tv(this, "全选", 13, Ui.ACCENT, android.graphics.Typeface.BOLD);
        all.setPadding(Ui.dp(this, 8), Ui.dp(this, 4), Ui.dp(this, 8), Ui.dp(this, 4));
        all.setOnClickListener(v -> { for (Doc d : docs) if (!d.dir) d.sel = true; renderDocs(); });
        row1.addView(all);
        TextView none = Ui.tv(this, "清空", 13, Ui.MUTED, android.graphics.Typeface.BOLD);
        none.setPadding(Ui.dp(this, 12), Ui.dp(this, 4), Ui.dp(this, 4), Ui.dp(this, 4));
        none.setOnClickListener(v -> { for (Doc d : docs) d.sel = false; renderDocs(); });
        row1.addView(none);
        bottom.addView(row1);

        LinearLayout row2 = new LinearLayout(this);
        LinearLayout.LayoutParams row2Lp = new LinearLayout.LayoutParams(-1, -2);
        row2Lp.topMargin = Ui.dp(this, 8);
        bottom.addView(row2, row2Lp);

        TextView inBtn = Ui.tv(this, "收进匣子", 14, Color.WHITE, android.graphics.Typeface.BOLD);
        inBtn.setGravity(Gravity.CENTER);
        inBtn.setBackground(Ui.grad(Ui.ACCENT, Ui.ACCENT2, 14, this));
        inBtn.setPadding(0, Ui.dp(this, 13), 0, Ui.dp(this, 13));
        inBtn.setOnClickListener(v -> pickCourse());
        row2.addView(inBtn, new LinearLayout.LayoutParams(0, -2, 1));

        TextView delBtn = Ui.tv(this, "直接删除", 14, 0xFFB4544A, android.graphics.Typeface.BOLD);
        delBtn.setGravity(Gravity.CENTER);
        delBtn.setBackground(Ui.roundStroke(Color.WHITE, 14, 0xFFB4544A, this));
        delBtn.setPadding(0, Ui.dp(this, 13), 0, Ui.dp(this, 13));
        LinearLayout.LayoutParams delLp = new LinearLayout.LayoutParams(0, -2, 1);
        delLp.leftMargin = Ui.dp(this, 10);
        delBtn.setOnClickListener(v -> confirmDelete());
        row2.addView(delBtn, delLp);

        render();
    }

    /* ---------- 渲染 ---------- */

    private void render() {
        if (tree == null) renderFolders();
        else queryDocs();
    }

    /** 第一层：已授权文件夹列表 */
    private void renderFolders() {
        listBox.removeAllViews();
        bottom.setVisibility(View.GONE);
        Set<String> ts = trees();
        status.setText(ts.isEmpty() ? "还没授权文件夹，点右上「＋文件夹」选 Download、DCIM 等" : "");
        ContentResolver cr = getContentResolver();
        for (String s : ts) {
            Uri t = Uri.parse(s);
            String name = folderName(cr, t);
            LinearLayout row = new LinearLayout(this);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setBackground(Ui.round(Color.WHITE, 14, this));
            row.setPadding(Ui.dp(this, 16), Ui.dp(this, 14), Ui.dp(this, 16), Ui.dp(this, 14));
            TextView ic = Ui.tv(this, "夹", 14, Color.WHITE, android.graphics.Typeface.BOLD);
            ic.setGravity(Gravity.CENTER);
            ic.setBackground(Ui.grad(Ui.ACCENT, Ui.ACCENT2, 12, this));
            row.addView(ic, new LinearLayout.LayoutParams(Ui.dp(this, 40), Ui.dp(this, 40)));
            TextView n = Ui.tv(this, name, 15, Ui.INK, android.graphics.Typeface.BOLD);
            n.setPadding(Ui.dp(this, 12), 0, 0, 0);
            row.addView(n, new LinearLayout.LayoutParams(0, -2, 1));
            row.addView(Ui.tv(this, "›", 18, Ui.MUTED, 0));
            row.setOnClickListener(v -> {
                tree = t;
                idStack.clear();
                nameStack.clear();
                idStack.add(DocumentsContract.getTreeDocumentId(t));
                nameStack.add(name);
                render();
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
            lp.bottomMargin = Ui.dp(this, 8);
            listBox.addView(row, lp);
        }
    }

    private String folderName(ContentResolver cr, Uri tree) {
        try {
            Uri doc = DocumentsContract.buildDocumentUriUsingTree(
                tree, DocumentsContract.getTreeDocumentId(tree));
            Cursor c = cr.query(doc, new String[]{DocumentsContract.Document.COLUMN_DISPLAY_NAME},
                null, null, null);
            if (c != null) {
                String n = c.moveToFirst() ? c.getString(0) : null;
                c.close();
                if (n != null) return n;
            }
        } catch (Exception ignored) {}
        String seg = tree.getLastPathSegment();
        return seg == null ? "文件夹" : seg;
    }

    /** 查询当前目录单层内容 */
    private void queryDocs() {
        docs.clear();
        status.setText("读取中…");
        new Thread(() -> {
            ContentResolver cr = getContentResolver();
            Uri children = DocumentsContract.buildChildDocumentsUriUsingTree(
                tree, idStack.get(idStack.size() - 1));
            List<Doc> dirs = new ArrayList<>(), files = new ArrayList<>();
            try {
                Cursor c = cr.query(children, new String[]{
                    DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                    DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                    DocumentsContract.Document.COLUMN_MIME_TYPE,
                    DocumentsContract.Document.COLUMN_SIZE}, null, null, null);
                if (c != null) {
                    while (c.moveToNext()) {
                        String name = c.getString(1);
                        if (name == null || name.startsWith(".")) continue;
                        Doc d = new Doc();
                        d.id = c.getString(0);
                        d.name = name;
                        d.dir = DocumentsContract.Document.MIME_TYPE_DIR.equals(c.getString(2));
                        d.size = c.getLong(3);
                        d.uri = DocumentsContract.buildDocumentUriUsingTree(tree, d.id);
                        (d.dir ? dirs : files).add(d);
                    }
                    c.close();
                }
            } catch (Exception ignored) {}
            docs.addAll(dirs);
            docs.addAll(files);
            runOnUiThread(() -> {
                StringBuilder path = new StringBuilder();
                for (String n : nameStack) path.append('/').append(n);
                status.setText(path + " · " + files.size() + " 个文件");
                renderDocs();
            });
        }).start();
    }

    private void renderDocs() {
        listBox.removeAllViews();
        for (final Doc d : docs) {
            LinearLayout row = new LinearLayout(this);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setBackground(d.sel
                ? Ui.roundStroke(0x147C8DA0, 14, Ui.ACCENT, this)
                : Ui.round(Color.WHITE, 14, this));
            row.setPadding(Ui.dp(this, 14), Ui.dp(this, 12), Ui.dp(this, 14), Ui.dp(this, 12));

            TextView ic = Ui.tv(this, d.dir ? "夹" : Ui.typeOf(d.name).toUpperCase().substring(0, 1),
                13, Color.WHITE, android.graphics.Typeface.BOLD);
            ic.setGravity(Gravity.CENTER);
            ic.setBackground(d.dir
                ? Ui.grad(Ui.ACCENT, Ui.ACCENT2, 12, this)
                : Ui.round(0xFFB9B3A8, 12, this));
            row.addView(ic, new LinearLayout.LayoutParams(Ui.dp(this, 36), Ui.dp(this, 36)));

            LinearLayout mid = new LinearLayout(this);
            mid.setOrientation(LinearLayout.VERTICAL);
            mid.setPadding(Ui.dp(this, 10), 0, 0, 0);
            TextView nm = Ui.tv(this, d.name, 14, Ui.INK, android.graphics.Typeface.BOLD);
            nm.setSingleLine();
            nm.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);
            mid.addView(nm);
            mid.addView(Ui.tv(this, d.dir ? "文件夹" : Ui.sizeText(d.size), 11, Ui.MUTED, 0));
            row.addView(mid, new LinearLayout.LayoutParams(0, -2, 1));

            if (d.dir) {
                row.addView(Ui.tv(this, "›", 18, Ui.MUTED, 0));
                row.setOnClickListener(v -> {
                    idStack.add(d.id);
                    nameStack.add(d.name);
                    queryDocs();
                });
            } else {
                if (d.sel) row.addView(Ui.tv(this, "✓", 16, Ui.ACCENT, android.graphics.Typeface.BOLD));
                row.setOnClickListener(v -> {
                    if (selCount() > 0) { d.sel = !d.sel; renderDocs(); }
                    else preview(d);
                });
                row.setOnLongClickListener(v -> { d.sel = !d.sel; renderDocs(); return true; });
            }
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
            lp.bottomMargin = Ui.dp(this, 8);
            listBox.addView(row, lp);
        }
        if (docs.isEmpty()) {
            TextView empty = Ui.tv(this, "这里是空的", 14, Ui.MUTED, 0);
            empty.setGravity(Gravity.CENTER);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
            lp.topMargin = Ui.dp(this, 60);
            listBox.addView(empty, lp);
        }
        int n = selCount();
        bottom.setVisibility(n > 0 ? View.VISIBLE : View.GONE);
        if (n > 0) selInfo.setText("已选 " + n + " 份");
    }

    private int selCount() {
        int n = 0;
        for (Doc d : docs) if (d.sel) n++;
        return n;
    }

    /* ---------- 预览 ---------- */

    private void preview(Doc d) {
        try {
            Intent i = new Intent(Intent.ACTION_VIEW);
            i.setDataAndType(d.uri, FileServer.mimeOf(d.name));
            i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivity(i);
        } catch (android.content.ActivityNotFoundException e) {
            Toast.makeText(this, "没有能打开这种文件的应用", Toast.LENGTH_SHORT).show();
        } catch (Exception e) {
            Toast.makeText(this, "打不开这份文件", Toast.LENGTH_SHORT).show();
        }
    }

    /* ---------- 收进匣子 ---------- */

    private void pickCourse() {
        int n = selCount();
        if (n == 0) return;
        List<Models.Course> cs = store.courses;
        if (cs.isEmpty()) { Toast.makeText(this, "先回首页建一门课", Toast.LENGTH_SHORT).show(); return; }
        String[] names = new String[cs.size()];
        for (int i = 0; i < names.length; i++) names[i] = cs.get(i).name;
        new AlertDialog.Builder(this)
            .setTitle("勾选的 " + n + " 份归到哪门课？")
            .setItems(names, (d, w) -> pickCat(cs.get(w), n))
            .show();
    }

    private void pickCat(Models.Course c, int n) {
        String[] cn = new String[c.cats.size()];
        for (int i = 0; i < cn.length; i++) cn[i] = c.cats.get(i).icon + " " + c.cats.get(i).name;
        new AlertDialog.Builder(this)
            .setTitle("放进「" + c.name + "」哪个格子？")
            .setItems(cn, (d, w) -> runImport(c, c.cats.get(w), n))
            .show();
    }

    private void runImport(Models.Course c, Models.Cat cat, int n) {
        status.setText("收进匣子中…");
        new Thread(() -> {
            int ok = 0, dup = 0, fail = 0;
            ContentResolver cr = getContentResolver();
            List<Doc> done = new ArrayList<>();
            for (Doc d : new ArrayList<>(docs)) {
                if (!d.sel || d.dir) continue;
                try {
                    InputStream in = cr.openInputStream(d.uri);
                    Models.Item it = box.importStream(in, d.name, c, cat);
                    DocumentsContract.deleteDocument(cr, d.uri); // 收进成功/重复 → 删原文件
                    if (it == null) dup++; else ok++;
                    done.add(d);
                } catch (Exception e) {
                    fail++; // 失败绝不删原文件
                }
            }
            final int fOk = ok, fDup = dup, fFail = fail;
            runOnUiThread(() -> {
                for (Doc d : done) docs.remove(d);
                renderDocs();
                status.setText("收进 " + fOk + " 份" + (fDup > 0 ? "，重复清理 " + fDup + " 份" : "")
                    + (fFail > 0 ? "，失败 " + fFail + " 份（原文件未动）" : ""));
                Toast.makeText(this, fFail > 0 ? "有 " + fFail + " 份失败，原文件还在" : "都收好了",
                    Toast.LENGTH_SHORT).show();
            });
        }).start();
    }

    /* ---------- 直接删除 ---------- */

    private void confirmDelete() {
        int n = selCount();
        if (n == 0) return;
        new AlertDialog.Builder(this)
            .setTitle("真删 " + n + " 份文件？")
            .setMessage("从手机存储里彻底删除，不进回收站，删了就找不回来。")
            .setPositiveButton("删除", (d, w) -> runDelete())
            .setNegativeButton("取消", null)
            .show();
    }

    private void runDelete() {
        new Thread(() -> {
            int ok = 0, fail = 0;
            ContentResolver cr = getContentResolver();
            List<Doc> done = new ArrayList<>();
            for (Doc d : new ArrayList<>(docs)) {
                if (!d.sel || d.dir) continue;
                try {
                    if (DocumentsContract.deleteDocument(cr, d.uri)) { ok++; done.add(d); }
                    else fail++;
                } catch (Exception e) {
                    fail++;
                }
            }
            final int fOk = ok, fFail = fail;
            runOnUiThread(() -> {
                for (Doc d : done) docs.remove(d);
                renderDocs();
                status.setText("已删 " + fOk + " 份" + (fFail > 0 ? "，" + fFail + " 份没删掉" : ""));
            });
        }).start();
    }

    /* ---------- 导航 ---------- */

    private void goBack() {
        if (tree == null) finish();
        else if (idStack.size() > 1) {
            idStack.remove(idStack.size() - 1);
            nameStack.remove(nameStack.size() - 1);
            queryDocs();
        } else {
            tree = null;
            render();
        }
    }

    @Override
    public void onBackPressed() {
        goBack();
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (req != REQ_TREE || res != RESULT_OK || data == null) return;
        Uri t = data.getData();
        try {
            getContentResolver().takePersistableUriPermission(t,
                Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
        } catch (Exception ignored) {}
        Set<String> ts = trees();
        ts.add(t.toString());
        sp().edit().putStringSet("trees", ts).apply();
        if (tree == null) renderFolders();
    }
}
