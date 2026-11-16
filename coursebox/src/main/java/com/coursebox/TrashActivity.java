package com.coursebox;

import android.app.Activity;
import android.app.AlertDialog;
import android.graphics.Color;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

/** 回收站：删掉的文件留 7 天，一页看完，不用在弹窗里钻来钻去 */
public class TrashActivity extends Activity {

    private Store store;
    private Box box;
    private LinearLayout listBox;
    private TextView clear;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        store = Store.get(this);
        box = new Box(this);
        build();
        render();
    }

    private void build() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Ui.SCREEN);
        int p = Ui.dp(this, 22);
        root.setPadding(p, Ui.dp(this, 46), p, Ui.dp(this, 16));
        setContentView(root);

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
        tt.addView(Ui.tv(this, "回收站", 24, Ui.INK, android.graphics.Typeface.BOLD));
        tt.addView(Ui.tv(this, "删掉的留 7 天，之后自动清掉", 12, Ui.MUTED, 0));
        head.addView(tt, new LinearLayout.LayoutParams(0, -2, 1));

        clear = Ui.tv(this, "清空", 12, Ui.MUTED, android.graphics.Typeface.BOLD);
        clear.setGravity(Gravity.CENTER);
        clear.setBackground(Ui.roundStroke(Color.WHITE, 99, Ui.RULE, this));
        clear.setPadding(Ui.dp(this, 12), Ui.dp(this, 8), Ui.dp(this, 12), Ui.dp(this, 8));
        clear.setOnClickListener(v -> confirmClear());
        head.addView(clear);
        root.addView(head);

        ScrollView sv = new ScrollView(this);
        listBox = new LinearLayout(this);
        listBox.setOrientation(LinearLayout.VERTICAL);
        sv.addView(listBox, new ScrollView.LayoutParams(-1, -2));
        LinearLayout.LayoutParams svLp = new LinearLayout.LayoutParams(-1, 0, 1);
        svLp.topMargin = Ui.dp(this, 14);
        root.addView(sv, svLp);
    }

    private void render() {
        listBox.removeAllViews();
        clear.setVisibility(store.trash.isEmpty() ? View.GONE : View.VISIBLE);
        if (store.trash.isEmpty()) {
            TextView empty = Ui.tv(this, "回收站是空的\n\n在课程里删掉的文件会先来这里躺 7 天", 14, Ui.MUTED, 0);
            empty.setGravity(Gravity.CENTER);
            empty.setLineSpacing(Ui.dp(this, 6), 1);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
            lp.topMargin = Ui.dp(this, 60);
            listBox.addView(empty, lp);
            return;
        }
        for (Models.TrashItem t : new java.util.ArrayList<>(store.trash)) {
            LinearLayout card = new LinearLayout(this);
            card.setOrientation(LinearLayout.VERTICAL);
            card.setBackground(Ui.round(Color.WHITE, 14, this));
            card.setElevation(Ui.dp(this, 2));
            card.setPadding(Ui.dp(this, 14), Ui.dp(this, 11), Ui.dp(this, 14), Ui.dp(this, 11));

            TextView nm = Ui.tv(this, t.name, 14, Ui.INK, android.graphics.Typeface.BOLD);
            nm.setSingleLine();
            nm.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);
            card.addView(nm);

            long days = (System.currentTimeMillis() - t.deletedAt) / 86400000L;
            String when = days <= 0 ? "今天删的" : days + " 天前删的";
            card.addView(Ui.tv(this, "来自 " + t.courseName + " / " + t.catName + " · " + when, 11, Ui.MUTED, 0));

            card.setOnClickListener(v -> itemDialog(t));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
            lp.bottomMargin = Ui.dp(this, 8);
            listBox.addView(card, lp);
        }
    }

    private void itemDialog(Models.TrashItem t) {
        new AlertDialog.Builder(this)
            .setTitle(t.name)
            .setItems(new String[]{"放回原处", "彻底删除"}, (d, w) -> {
                if (w == 0) {
                    boolean ok = box.restore(t);
                    Toast.makeText(this,
                        ok ? "已放回「" + t.courseName + "·" + t.catName + "」" : "文件已经不在了",
                        Toast.LENGTH_SHORT).show();
                } else {
                    box.destroy(t);
                    Toast.makeText(this, "已彻底删除", Toast.LENGTH_SHORT).show();
                }
                render();
            })
            .setNegativeButton("取消", null)
            .show();
    }

    private void confirmClear() {
        new AlertDialog.Builder(this)
            .setTitle("清空回收站？")
            .setMessage(store.trash.size() + " 份文件会彻底删掉，这一步没有后悔药。")
            .setPositiveButton("清空", (d, w) -> {
                for (int i = store.trash.size() - 1; i >= 0; i--) box.destroy(store.trash.get(i));
                Toast.makeText(this, "回收站已清空", Toast.LENGTH_SHORT).show();
                render();
            })
            .setNegativeButton("算了", null)
            .show();
    }
}
