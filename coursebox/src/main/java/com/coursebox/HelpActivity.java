package com.coursebox;

import android.app.Activity;
import android.graphics.Color;
import android.os.Bundle;
import android.view.Gravity;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

/** 使用指南：一页静态说明，想看的就看 */
public class HelpActivity extends Activity {

    private static final String[][] SECTIONS = {
        {"首页", "＋：新建课程（格子名可自己删改，一行一个）\n"
            + "⚙：换背景图、恢复默认、打开本指南\n"
            + "手机文件：浏览手机里的文件夹，归档或真删\n"
            + "批量整理：自动识别文件该进哪门课哪个格子\n"
            + "搜索条：输入即搜，跨全部课程格子，点结果直接预览"},
        {"课程页", "点课程卡进入。顶部色点可换课程颜色\n"
            + "点格子 chip 切换格子，「＋ 格子」加新格子\n"
            + "长按格子 chip：重命名 / 删除格子（有文件会二次确认）\n"
            + "格子内搜索条：只搜当前格子的文件名"},
        {"文件操作", "点按文件：调系统应用预览\n"
            + "长按文件：进入圈选，继续点可加选\n"
            + "圈选后底部操作条：全选 / 移动格子 / 导出到 Download/课匣导出 / 删除\n"
            + "按返回键退出圈选"},
        {"手机文件页", "＋文件夹：授权一个手机文件夹（Download、DCIM、微信等）\n"
            + "点文件夹下钻，返回键逐级退回\n"
            + "点按文件预览，长按圈选\n"
            + "收进匣子：选课程 → 选格子，校验入库后删原文件，失败绝不删\n"
            + "直接删除：从手机存储真删，不进回收站，有确认框"},
        {"收文件进匣子", "任何 App 里分享文件 → 选「课匣」→ 选课程格子即可\n"
            + "重复内容按 SHA-256 去重，不会收两份"},
    };

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
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
        tt.addView(Ui.tv(this, "使用指南", 24, Ui.INK, android.graphics.Typeface.BOLD));
        tt.addView(Ui.tv(this, "记住两个手势就够：点按预览，长按圈选", 12, Ui.MUTED, 0));
        head.addView(tt);
        root.addView(head);

        ScrollView sv = new ScrollView(this);
        LinearLayout list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        sv.addView(list, new ScrollView.LayoutParams(-1, -2));
        LinearLayout.LayoutParams svLp = new LinearLayout.LayoutParams(-1, 0, 1);
        svLp.topMargin = Ui.dp(this, 14);
        root.addView(sv, svLp);

        for (String[] sec : SECTIONS) {
            LinearLayout card = new LinearLayout(this);
            card.setOrientation(LinearLayout.VERTICAL);
            card.setBackground(Ui.round(Color.WHITE, 16, this));
            card.setPadding(Ui.dp(this, 16), Ui.dp(this, 14), Ui.dp(this, 16), Ui.dp(this, 14));
            card.addView(Ui.tv(this, sec[0], 15, Ui.ACCENT, android.graphics.Typeface.BOLD));
            TextView body = Ui.tv(this, sec[1], 13, Ui.INK, 0);
            body.setLineSpacing(Ui.dp(this, 6), 1);
            LinearLayout.LayoutParams bodyLp = new LinearLayout.LayoutParams(-1, -2);
            bodyLp.topMargin = Ui.dp(this, 8);
            card.addView(body, bodyLp);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
            lp.bottomMargin = Ui.dp(this, 10);
            list.addView(card, lp);
        }
    }
}
