package com.popmemo;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.PowerManager;
import android.provider.Settings;
import android.text.InputType;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.WindowInsets;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * 主界面 ⋯ 打开的设置页：分组列表，不再把功能全堆在一条平铺菜单里。
 * 在这里改了背景 / 悬浮球 / 频率，回到主界面就生效（主界面 onResume 会重读）。
 */
public class SettingsActivity extends Activity {

    private static final int REQ_PICK_BG = 7;
    private static final int REQ_PICK_AVATAR = 8;
    private static final String BG_FILE = "bg.jpg";
    private static final String AVATAR_FILE = "character.jpg";
    private static final String[] CHAR_STYLES = {"傲娇", "温柔", "严厉", "中二"};

    private static final int TEXT_MAIN = 0xFF1C1C1E;
    private static final int TEXT_DIM = 0xFF8E8E93;

    private LinearLayout content;
    private SharedPreferences prefs;
    /** 角色对话框开着的时候指向里面的头像状态，好换完图马上刷新。 */
    private TextView avatarStateView;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        prefs = getSharedPreferences("popmemo", MODE_PRIVATE);

        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(0xFFF2F2F7);
        content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(content);
        setContentView(scroll);

        // Android 15 强制全面屏，不自己让位就会被状态栏压住。
        scroll.setOnApplyWindowInsetsListener((v, insets) -> {
            int top;
            if (Build.VERSION.SDK_INT >= 30) {
                top = insets.getInsets(WindowInsets.Type.systemBars()).top;
            } else {
                top = insets.getSystemWindowInsetTop();
            }
            v.setPadding(0, top, 0, 0);
            return insets;
        });
        Ui.lightBars(this, true);
    }

    @Override
    protected void onPause() {
        FloatingBall.setAppVisible(false);
        FloatingBall.sync(this);
        super.onPause();
    }

    @Override
    protected void onResume() {
        super.onResume();
        FloatingBall.setAppVisible(true);
        // 状态类条目（背景、频率、悬浮球）可能刚变过，回来重画一遍。
        buildContent();
    }

    private void buildContent() {
        content.removeAllViews();
        content.setPadding(dp(16), dp(8), dp(16), dp(24));

        TextView back = new TextView(this);
        back.setText("‹ 设置");
        back.setTextSize(22f);
        back.setTextColor(TEXT_MAIN);
        back.setTypeface(Typeface.DEFAULT_BOLD);
        back.setPadding(dp(4), dp(8), dp(4), dp(14));
        back.setOnClickListener(v -> finish());
        content.addView(back);

        boolean hasBg = new File(getFilesDir(), BG_FILE).exists();
        LinearLayout look = section("外观");
        row(look, "背景：换张图…", hasBg ? "现在是自己的图" : "现在是默认渐变",
                this::pickBackgroundImage);
        row(look, "背景：恢复默认", null, this::resetBackground);

        String name = prefs.getString("character_name", "");
        boolean popupOn = prefs.getBoolean("popup_enabled", true);
        int every = Math.max(1, prefs.getInt("popup_every", 5));
        String everyLabel = every == 1 ? "每次解锁都弹" : "每 " + every + " 次解锁弹一次";
        boolean ballOn = prefs.getBoolean("ball_enabled", true);
        LinearLayout remind = section("提醒");
        row(remind, "角色提醒…", name.isEmpty() ? "没用角色" : name, this::showCharacterDialog);
        row(remind, "解锁弹窗", popupOn ? "开" : "关", this::togglePopup);
        row(remind, "弹窗频率", everyLabel, this::pickPopupEvery);
        row(remind, "悬浮球", ballOn ? "开" : "关", this::toggleBall);
        row(remind, "上课时间…", "标出有课的时段", () ->
                startActivity(new Intent(this, TimetableActivity.class)));

        LinearLayout system = section("系统");
        row(system, "后台保活…", "解锁监听 / 电池优化 / 自启动", this::showKeepAlive);

        LinearLayout data = section("数据");
        row(data, "清空已经办完的", null, () -> confirmClear(true));
        row(data, "清空全部记录", null, () -> confirmClear(false));

        LinearLayout about = section("关于");
        row(about, "关于 PopMemo", version(), this::showAbout);
    }

    // ---- 分组列表的拼装 ----

    private LinearLayout section(String title) {
        TextView header = new TextView(this);
        header.setText(title);
        header.setTextSize(13f);
        header.setTextColor(TEXT_DIM);
        header.setPadding(dp(8), dp(14), dp(8), dp(6));
        content.addView(header);

        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(Color.WHITE);
        bg.setCornerRadius(dp(14));
        card.setBackground(bg);
        content.addView(card);
        return card;
    }

    private void row(LinearLayout card, String label, String state, Runnable action) {
        if (card.getChildCount() > 0) {
            View divider = new View(this);
            divider.setBackgroundColor(0x14000000);
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, 1);
            params.leftMargin = dp(16);
            card.addView(divider, params);
        }

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(16), dp(14), dp(16), dp(14));
        TypedValue tv = new TypedValue();
        getTheme().resolveAttribute(android.R.attr.selectableItemBackground, tv, true);
        row.setBackgroundResource(tv.resourceId);

        TextView labelView = new TextView(this);
        labelView.setText(label);
        labelView.setTextSize(16f);
        labelView.setTextColor(TEXT_MAIN);
        row.addView(labelView, new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        if (state != null) {
            TextView stateView = new TextView(this);
            stateView.setText(state);
            stateView.setTextSize(14f);
            stateView.setTextColor(TEXT_DIM);
            row.addView(stateView);
        }

        row.setOnClickListener(v -> action.run());
        card.addView(row);
    }

    // ---- 提醒 ----

    /** 屏幕边上的小球：点开看今天的事。 */
    private void toggleBall() {
        boolean now = !prefs.getBoolean("ball_enabled", true);
        prefs.edit().putBoolean("ball_enabled", now).apply();
        Toast.makeText(this, now ? "小球摆出来了" : "小球收起来了", Toast.LENGTH_SHORT).show();
        buildContent();
    }

    /** 解锁弹窗总开关：关了也不删功能，想开随时开回来。 */
    private void togglePopup() {
        boolean now = !prefs.getBoolean("popup_enabled", true);
        prefs.edit().putBoolean("popup_enabled", now).apply();
        Toast.makeText(this, now ? "解锁弹窗开了" : "解锁弹窗关了，靠小球提醒", Toast.LENGTH_SHORT)
                .show();
        buildContent();
    }

    /** 解锁几次才弹一次。红卡（今天到期/已过期）不受这个限制，照样弹。 */
    private void pickPopupEvery() {
        String[] options = {"1 次（每次都弹）", "2 次", "3 次", "5 次", "10 次"};
        int[] values = {1, 2, 3, 5, 10};
        new AlertDialog.Builder(this)
                .setTitle("解锁几次才弹一次？")
                .setItems(options, (dialog, which) -> {
                    prefs.edit().putInt("popup_every", values[which]).apply();
                    buildContent();
                })
                .show();
    }

    // ---- 背景 ----

    private void pickBackgroundImage() {
        Intent intent = new Intent(Intent.ACTION_PICK,
                android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI);
        try {
            startActivityForResult(intent, REQ_PICK_BG);
        } catch (Exception e) {
            Toast.makeText(this, "打不开相册", Toast.LENGTH_SHORT).show();
        }
    }

    private void resetBackground() {
        //noinspection ResultOfMethodCallIgnored
        new File(getFilesDir(), BG_FILE).delete();
        Toast.makeText(this, "恢复成默认渐变了", Toast.LENGTH_SHORT).show();
        buildContent();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (resultCode != RESULT_OK || data == null || data.getData() == null) {
            return;
        }
        if (requestCode == REQ_PICK_BG) {
            if (savePickedImage(data.getData(), BG_FILE)) {
                buildContent();
            } else {
                Toast.makeText(this, "这张图读不出来", Toast.LENGTH_SHORT).show();
            }
        } else if (requestCode == REQ_PICK_AVATAR) {
            if (savePickedImage(data.getData(), AVATAR_FILE)) {
                updateAvatarState();
            } else {
                Toast.makeText(this, "这张图读不出来", Toast.LENGTH_SHORT).show();
            }
        }
    }

    /** 选中的图复制进私有目录，之后不依赖相册权限也能用。 */
    private boolean savePickedImage(Uri uri, String fileName) {
        try (InputStream in = getContentResolver().openInputStream(uri);
             FileOutputStream out = new FileOutputStream(new File(getFilesDir(), fileName))) {
            if (in == null) {
                return false;
            }
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) {
                out.write(buf, 0, n);
            }
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    // ---- 角色提醒 ----

    /** 让喜欢的角色来催：名字 + 口吻 + 头像，都塞进解锁弹窗。名字留空 = 不用角色。 */
    private void showCharacterDialog() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(20), dp(4), dp(20), 0);

        final EditText nameInput = new EditText(this);
        nameInput.setHint("角色名（留空 = 不用角色）");
        nameInput.setText(prefs.getString("character_name", ""));
        nameInput.setSingleLine();
        box.addView(nameInput);

        final int[] style = {prefs.getInt("character_style", 0)};
        final TextView styleValue = new TextView(this);
        styleValue.setTextSize(15f);
        styleValue.setPadding(0, dp(16), 0, 0);
        styleValue.setText("口吻风格：" + CHAR_STYLES[style[0]] + "（点我换）");
        styleValue.setOnClickListener(v -> new AlertDialog.Builder(this)
                .setTitle("口吻风格")
                .setItems(CHAR_STYLES, (d, which) -> {
                    style[0] = which;
                    styleValue.setText("口吻风格：" + CHAR_STYLES[which] + "（点我换）");
                })
                .show());
        box.addView(styleValue);

        final EditText linesInput = new EditText(this);
        linesInput.setHint("自己写台词，一行一句，{事项} 会换成内容。\n写了就不用内置风格。");
        linesInput.setText(prefs.getString("character_lines", ""));
        linesInput.setMinLines(3);
        linesInput.setGravity(Gravity.TOP);
        box.addView(linesInput);

        final EditText keyInput = new EditText(this);
        keyInput.setHint("DeepSeek API Key（填了就用 AI 现场编台词，事项会发给 DeepSeek）");
        keyInput.setText(prefs.getString("deepseek_key", ""));
        keyInput.setSingleLine();
        keyInput.setInputType(InputType.TYPE_CLASS_TEXT
                | InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD);
        box.addView(keyInput);

        LinearLayout avatarRow = new LinearLayout(this);
        avatarRow.setOrientation(LinearLayout.HORIZONTAL);
        avatarRow.setGravity(Gravity.CENTER_VERTICAL);
        avatarRow.setPadding(0, dp(8), 0, 0);
        Button pickAvatar = new Button(this);
        pickAvatar.setText("换头像");
        pickAvatar.setOnClickListener(v -> {
            Intent intent = new Intent(Intent.ACTION_PICK,
                    android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI);
            try {
                startActivityForResult(intent, REQ_PICK_AVATAR);
            } catch (Exception e) {
                Toast.makeText(this, "打不开相册", Toast.LENGTH_SHORT).show();
            }
        });
        avatarRow.addView(pickAvatar);
        Button clearAvatar = new Button(this);
        clearAvatar.setText("去掉");
        clearAvatar.setOnClickListener(v -> {
            //noinspection ResultOfMethodCallIgnored
            new File(getFilesDir(), AVATAR_FILE).delete();
            updateAvatarState();
        });
        avatarRow.addView(clearAvatar);
        avatarStateView = new TextView(this);
        avatarStateView.setPadding(dp(12), 0, 0, 0);
        avatarStateView.setTextSize(13f);
        avatarStateView.setTextColor(0xFF9AA0A6);
        avatarRow.addView(avatarStateView);
        box.addView(avatarRow);
        updateAvatarState();

        new AlertDialog.Builder(this)
                .setTitle("角色提醒")
                .setView(box)
                .setNegativeButton("算了", null)
                .setPositiveButton("保存", (d, w) -> {
                    prefs.edit()
                            .putString("character_name", nameInput.getText().toString().trim())
                            .putInt("character_style", style[0])
                            .putString("character_lines", linesInput.getText().toString())
                            .putString("deepseek_key", keyInput.getText().toString().trim())
                            .apply();
                    buildContent();
                })
                .show();
    }

    private void updateAvatarState() {
        if (avatarStateView != null) {
            avatarStateView.setText(
                    new File(getFilesDir(), AVATAR_FILE).exists() ? "已有头像" : "还没头像");
        }
    }

    // ---- 系统 ----

    /** 后台保活三件事：解锁监听走哪条路、电池优化关没关、自启动开没开。 */
    private void showKeepAlive() {
        boolean a11y = UnlockAccessibilityService.isEnabled(this);
        PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
        boolean batteryFree = pm != null && pm.isIgnoringBatteryOptimizations(getPackageName());

        String[] items = {
                a11y ? "解锁监听：无障碍保活中（省电，推荐）"
                        : "解锁监听：前台服务中 · 点我开无障碍",
                batteryFree ? "电池优化：已关闭（不怕被杀）"
                        : "电池优化：还开着 · 点我申请关闭",
                "自启动：点我去系统设置里确认已允许",
        };
        new AlertDialog.Builder(this)
                .setTitle("后台保活")
                .setItems(items, (dialog, which) -> {
                    if (which == 0) {
                        startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));
                    } else if (which == 1) {
                        try {
                            startActivity(new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                                    Uri.parse("package:" + getPackageName())));
                        } catch (Exception e) {
                            startActivity(new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS));
                        }
                    } else {
                        startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                Uri.parse("package:" + getPackageName())));
                    }
                })
                .setPositiveButton("知道了", null)
                .show();
    }

    // ---- 数据 ----

    private void confirmClear(boolean onlyDone) {
        String what = onlyDone ? "所有办完的记录" : "全部备忘录";
        new AlertDialog.Builder(this)
                .setTitle("清掉" + what + "？")
                .setMessage("这一步没有撤销。")
                .setNegativeButton("算了", null)
                .setPositiveButton("清掉", (dialog, which) -> {
                    MemoStore store = new MemoStore(this);
                    List<Memo> all = new ArrayList<>(store.load());
                    if (onlyDone) {
                        for (int i = all.size() - 1; i >= 0; i--) {
                            if (all.get(i).isDone()) {
                                all.remove(i);
                            }
                        }
                    } else {
                        all.clear();
                    }
                    store.save(all);
                    FloatingBall.refreshCount(this);
                    Toast.makeText(this, "清掉了", Toast.LENGTH_SHORT).show();
                })
                .show();
    }

    // ---- 关于 ----

    private String version() {
        try {
            return getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
        } catch (Exception e) {
            return "?";
        }
    }

    private void showAbout() {
        new AlertDialog.Builder(this)
                .setTitle("PopMemo " + version())
                .setMessage("解锁就摆出没办完的事，一划处理掉。\n\n"
                        + "数据只存在这台手机上，不联网、不上传。")
                .setPositiveButton("知道了", null)
                .show();
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
