package com.coursebox;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ContentResolver;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.widget.Toast;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

/** 系统分享入口：两步（课程→格子）把分享来的文件收进匣子 */
public class CaptureActivity extends Activity {

    private List<Uri> uris = new ArrayList<>();
    private Store store;
    private Box box;
    private Handler ui = new Handler();

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        store = Store.get(this);
        box = new Box(this);

        Intent it = getIntent();
        if (Intent.ACTION_SEND.equals(it.getAction())) {
            Uri u = it.getParcelableExtra(Intent.EXTRA_STREAM);
            if (u != null) uris.add(u);
        } else if (Intent.ACTION_SEND_MULTIPLE.equals(it.getAction())) {
            List<Uri> us = it.getParcelableArrayListExtra(Intent.EXTRA_STREAM);
            if (us != null) uris.addAll(us);
        }
        if (uris.isEmpty()) { finish(); return; }
        pickCourse();
    }

    private void pickCourse() {
        List<Models.Course> cs = new ArrayList<>();
        for (Models.Course c : store.courses) if (!"inbox".equals(c.id)) cs.add(c);
        if (cs.isEmpty()) {
            new AlertDialog.Builder(this)
                .setTitle("课匣")
                .setMessage("还没有课程，先打开课匣新建一门课吧")
                .setPositiveButton("好", (d, w) -> finish())
                .setOnCancelListener(d -> finish())
                .show();
            return;
        }
        String[] names = new String[cs.size()];
        for (int i = 0; i < cs.size(); i++) names[i] = cs.get(i).name;
        new AlertDialog.Builder(this)
            .setTitle("收进哪门课？（" + uris.size() + " 份）")
            .setItems(names, (d, w) -> pickCat(cs.get(w)))
            .setOnCancelListener(d -> finish())
            .show();
    }

    private void pickCat(Models.Course c) {
        String[] names = new String[c.cats.size()];
        for (int i = 0; i < c.cats.size(); i++) names[i] = c.cats.get(i).icon + " " + c.cats.get(i).name;
        new AlertDialog.Builder(this)
            .setTitle("放进「" + c.name + "」哪个格子？")
            .setItems(names, (d, w) -> doImport(c, c.cats.get(w)))
            .setOnCancelListener(d -> finish())
            .show();
    }

    private void doImport(Models.Course c, Models.Cat cat) {
        Toast.makeText(this, "收纳中…", Toast.LENGTH_SHORT).show();
        new Thread(() -> {
            int ok = 0, dup = 0, fail = 0;
            ContentResolver cr = getContentResolver();
            for (Uri u : uris) {
                try {
                    InputStream in = cr.openInputStream(u);
                    String name = Box.displayName(cr, u);
                    Models.Item item = box.importStream(in, name, c, cat);
                    if (item == null) dup++; else ok++;
                } catch (Exception e) {
                    android.util.Log.e("CaptureActivity", "import fail: " + u, e);
                    fail++;
                }
            }
            final int fOk = ok, fDup = dup, fFail = fail;
            final String cn = c.name, gn = cat.name;
            ui.post(() -> {
                String msg = "已收 " + fOk + " 份到 " + cn + "·" + gn;
                if (fDup > 0) msg += "，跳过重复 " + fDup;
                if (fFail > 0) msg += "，失败 " + fFail;
                Toast.makeText(this, msg, Toast.LENGTH_LONG).show();
                finish();
            });
        }).start();
    }
}
