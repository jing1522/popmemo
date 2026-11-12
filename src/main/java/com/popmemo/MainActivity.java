package com.popmemo;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.DatePickerDialog;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Paint;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.LayerDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.provider.Settings;
import android.text.InputType;
import android.text.Spannable;
import android.text.SpannableStringBuilder;
import android.text.style.ForegroundColorSpan;
import android.text.style.RelativeSizeSpan;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.List;

public class MainActivity extends Activity {

    /** 从常驻通知的「写一条」进来时，直接弹键盘。 */
    public static final String EXTRA_FOCUS_INPUT = "focus_input";

    private MemoStore store;

    /** 全部数据。 */
    private final List<Memo> all = new ArrayList<>();
    /** 当前列表显示的东西（待办 + 可能展开的已办完）。 */
    private final List<Memo> visible = new ArrayList<>();

    private MemoAdapter adapter;
    private boolean showDone;
    private boolean showLater;

    private EditText input;
    private ListView list;
    private View root;
    private TextView empty;
    private TextView doneFooter;
    private TextView laterFooter;
    private View permissionBanner;
    private TextView undoBar;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private Memo lastDeleted;
    private int lastDeletedIndex;
    private final Runnable hideUndoRunnable = () -> {
        lastDeleted = null;
        undoBar.setVisibility(View.GONE);
    };

    private static final String[] TAGS = {"学习", "生活", "娱乐", "工作"};

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        store = new MemoStore(this);
        all.addAll(store.load());

        input = findViewById(R.id.input);
        list = findViewById(R.id.list);
        empty = findViewById(R.id.empty);
        doneFooter = findViewById(R.id.done_footer);
        laterFooter = findViewById(R.id.later_footer);
        permissionBanner = findViewById(R.id.permission_banner);
        Button grantOverlay = findViewById(R.id.grant_overlay);

        undoBar = findViewById(R.id.undo_bar);

        adapter = new MemoAdapter();
        list.setAdapter(adapter);

        // Android 15 强制全面屏，不自己留出状态栏的位置就会被时间压住。
        root = findViewById(R.id.root);
        root.setOnApplyWindowInsetsListener((v, insets) -> {
            int top;
            int bottom;
            if (Build.VERSION.SDK_INT >= 30) {
                android.graphics.Insets bars = insets.getInsets(WindowInsets.Type.systemBars());
                top = bars.top;
                bottom = bars.bottom;
            } else {
                top = insets.getSystemWindowInsetTop();
                bottom = insets.getSystemWindowInsetBottom();
            }
            v.setPadding(dp(12), dp(12) + top, dp(12), dp(12) + bottom);
            return insets;
        });
        // 等布局量出尺寸再上背景，好按比例裁图。
        root.post(this::applyBackground);

        input.setOnEditorActionListener((v, actionId, event) -> {
            boolean done = actionId == EditorInfo.IME_ACTION_DONE;
            boolean enter = event != null
                    && event.getKeyCode() == KeyEvent.KEYCODE_ENTER
                    && event.getAction() == KeyEvent.ACTION_DOWN;
            if (done || enter) {
                addCurrentInput();
                return true;
            }
            return false;
        });

        grantOverlay.setOnClickListener(v -> openOverlaySettings());
        findViewById(R.id.overflow).setOnClickListener(v -> showCleanupMenu());
        doneFooter.setOnClickListener(v -> {
            showDone = !showDone;
            refresh();
        });
        laterFooter.setOnClickListener(v -> {
            showLater = !showLater;
            refresh();
        });

        // 点一下 = 改字；长按 = 更多（不弹 / 删掉）
        list.setOnItemClickListener((parent, view, position, id) -> editMemo(visible.get(position)));

        list.setOnItemLongClickListener((parent, view, position, id) -> {
            showActions(visible.get(position));
            return true;
        });

        refresh();
        MemoStore.setListener(this::refresh);

        if (getIntent().getBooleanExtra(EXTRA_FOCUS_INPUT, false)) {
            focusInput();
        }
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        if (intent != null && intent.getBooleanExtra(EXTRA_FOCUS_INPUT, false)) {
            focusInput();
        }
    }

    private void focusInput() {
        input.requestFocus();
        InputMethodManager imm = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
        if (imm != null) {
            imm.showSoftInput(input, InputMethodManager.SHOW_IMPLICIT);
        }
    }

    @Override
    protected void onDestroy() {
        handler.removeCallbacks(hideUndoRunnable);
        MemoStore.setListener(null);
        super.onDestroy();
    }

    @Override
    protected void onResume() {
        super.onResume();
        refresh();
        ensureService();
        // 自己家里不摆球，碍事；退出去再摆回来。
        FloatingBall.hide();
    }

    @Override
    protected void onPause() {
        FloatingBall.sync(this);
        super.onPause();
    }

    /** 无障碍开着就用它监听解锁（省电、系统保活），没开就前台服务兜底。 */
    private void ensureService() {
        boolean canDraw = Settings.canDrawOverlays(this);
        permissionBanner.setVisibility(canDraw ? View.GONE : View.VISIBLE);
        if (!canDraw) {
            return;
        }
        if (UnlockAccessibilityService.isEnabled(this)) {
            // 无障碍接管了，前台服务熄火，常驻通知一起收走。
            stopService(new Intent(this, UnlockService.class));
            return;
        }
        requestNotificationPermissionIfNeeded();
        startForegroundService(new Intent(this, UnlockService.class));
    }

    /** 截止日：只给几个最常用的档，不用去翻日历。 */
    private void pickDeadline(Memo memo) {
        String[] options = {"今天", "明天", "三天后", "一周后", "选个日期…", "不要日期"};
        new AlertDialog.Builder(this)
                .setTitle("哪天之前要办完？")
                .setItems(options, (dialog, which) -> {
                    switch (which) {
                        case 0:
                            memo.dueAt = Memo.dayOffset(0);
                            break;
                        case 1:
                            memo.dueAt = Memo.dayOffset(1);
                            break;
                        case 2:
                            memo.dueAt = Memo.dayOffset(3);
                            break;
                        case 3:
                            memo.dueAt = Memo.dayOffset(7);
                            break;
                        case 4:
                            pickCustomDate(memo);
                            return;
                        default:
                            memo.dueAt = 0;
                            break;
                    }
                    store.save(all);
                    refresh();
                })
                .show();
    }

    private void pickCustomDate(Memo memo) {
        Calendar c = Calendar.getInstance();
        if (memo.dueAt > 0) {
            c.setTimeInMillis(memo.dueAt);
        }
        new DatePickerDialog(this, (view, year, month, dayOfMonth) -> {
            Calendar picked = Calendar.getInstance();
            picked.set(year, month, dayOfMonth, 0, 0, 0);
            picked.set(Calendar.MILLISECOND, 0);
            memo.dueAt = picked.getTimeInMillis();
            store.save(all);
            refresh();
        }, c.get(Calendar.YEAR), c.get(Calendar.MONTH), c.get(Calendar.DAY_OF_MONTH)).show();
    }

    /** 点一下：改字。写备忘录最常发生的错就是「写错一个字」，得能改。 */
    private void editMemo(Memo memo) {
        EditText editor = new EditText(this);
        editor.setText(memo.text);
        editor.setSelection(editor.getText().length());
        editor.setPadding(dp(16), dp(12), dp(16), dp(12));

        new AlertDialog.Builder(this)
                .setTitle("改一下")
                .setView(editor)
                .setNegativeButton("算了", null)
                .setPositiveButton("保存", (d, w) -> {
                    String text = editor.getText().toString().trim();
                    if (text.isEmpty()) {
                        return;
                    }
                    memo.text = text;
                    store.save(all);
                    refresh();
                })
                .show();
    }

    /** 长按：截止日、分类、删除都放这里。 */
    private void showActions(Memo memo) {
        String[] items;
        if (memo.isDone()) {
            items = new String[]{"放回待办", "设个分类", "删掉"};
        } else {
            items = new String[]{
                    "设个截止日",
                    "设个分类",
                    memo.muted ? "让它也弹出来" : "不弹了，只在列表里",
                    "删掉"};
        }

        new AlertDialog.Builder(this)
                .setTitle(memo.text)
                .setItems(items, (dialog, which) -> {
                    if (memo.isDone()) {
                        if (which == 0) {
                            memo.doneAt = 0;
                        } else if (which == 1) {
                            pickTag(memo);
                            return;
                        } else {
                            deleteMemo(memo);
                            return;
                        }
                    } else {
                        if (which == 0) {
                            pickDeadline(memo);
                            return;
                        }
                        if (which == 1) {
                            pickTag(memo);
                            return;
                        }
                        if (which == 2) {
                            memo.muted = !memo.muted;
                        } else {
                            deleteMemo(memo);
                            return;
                        }
                    }
                    store.save(all);
                    refresh();
                })
                .show();
    }

    private void pickTag(Memo memo) {
        String[] options = new String[TAGS.length + 1];
        System.arraycopy(TAGS, 0, options, 0, TAGS.length);
        options[TAGS.length] = "不要分类";

        new AlertDialog.Builder(this)
                .setTitle("归到哪一类？")
                .setItems(options, (dialog, which) -> {
                    memo.tag = which < TAGS.length ? TAGS[which] : "";
                    store.save(all);
                    refresh();
                })
                .show();
    }

    /** 删除要能后悔：6 秒内点一下就能找回来。 */
    private void deleteMemo(Memo memo) {
        int index = all.indexOf(memo);
        if (index < 0) {
            return;
        }
        all.remove(index);
        store.save(all);
        refresh();

        lastDeleted = memo;
        lastDeletedIndex = index;
        undoBar.setText("已删除「" + memo.text + "」　点这里撤销");
        undoBar.setVisibility(View.VISIBLE);
        undoBar.setOnClickListener(v -> {
            if (lastDeleted != null) {
                all.add(Math.min(lastDeletedIndex, all.size()), lastDeleted);
                lastDeleted = null;
                store.save(all);
                refresh();
            }
            hideUndoBar();
        });
        handler.removeCallbacks(hideUndoRunnable);
        handler.postDelayed(hideUndoRunnable, 6000);
    }

    private void hideUndoBar() {
        handler.removeCallbacks(hideUndoRunnable);
        lastDeleted = null;
        undoBar.setVisibility(View.GONE);
    }

    /** 「⋯」里的清理动作。 */
    private void showCleanupMenu() {
        SharedPreferences prefs = getSharedPreferences("popmemo", MODE_PRIVATE);
        boolean ballOn = prefs.getBoolean("ball_enabled", true);
        int every = Math.max(1, prefs.getInt("popup_every", 5));
        String everyLabel = every == 1 ? "弹窗频率：每次解锁都弹" : "弹窗频率：每 " + every + " 次解锁";
        new AlertDialog.Builder(this)
                .setTitle("设置")
                .setItems(new String[]{"背景：换张图…", "背景：恢复默认", "角色提醒…", "后台保活…",
                                "悬浮球：" + (ballOn ? "开" : "关"), everyLabel,
                                "清空已经办完的", "清空全部记录", "关于 PopMemo"},
                        (dialog, which) -> {
                            if (which == 0) {
                                pickBackgroundImage();
                            } else if (which == 1) {
                                resetBackground();
                            } else if (which == 2) {
                                showCharacterDialog();
                            } else if (which == 3) {
                                showKeepAlive();
                            } else if (which == 4) {
                                toggleBall();
                            } else if (which == 5) {
                                pickPopupEvery();
                            } else if (which == 8) {
                                showAbout();
                            } else {
                                confirmClear(which == 6);
                            }
                        })
                .show();
    }

    /** 屏幕边上的小球：点开看今天的事。 */
    private void toggleBall() {
        SharedPreferences prefs = getSharedPreferences("popmemo", MODE_PRIVATE);
        boolean now = !prefs.getBoolean("ball_enabled", true);
        prefs.edit().putBoolean("ball_enabled", now).apply();
        Toast.makeText(this, now ? "小球摆出来了" : "小球收起来了", Toast.LENGTH_SHORT).show();
    }

    /** 解锁几次才弹一次。红卡（今天到期/已过期）不受这个限制，照样弹。 */
    private void pickPopupEvery() {
        String[] options = {"1 次（每次都弹）", "2 次", "3 次", "5 次", "10 次"};
        int[] values = {1, 2, 3, 5, 10};
        new AlertDialog.Builder(this)
                .setTitle("解锁几次才弹一次？")
                .setItems(options, (dialog, which) -> getSharedPreferences("popmemo", MODE_PRIVATE)
                        .edit().putInt("popup_every", values[which]).apply())
                .show();
    }

    // ---- 背景 ----

    private static final int REQ_PICK_BG = 7;
    private static final String BG_FILE = "bg.jpg";

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
        applyBackground();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (resultCode != RESULT_OK || data == null || data.getData() == null) {
            return;
        }
        if (requestCode == REQ_PICK_BG) {
            if (savePickedImage(data.getData(), BG_FILE)) {
                applyBackground();
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

    /** 有自己选的图就垫图 + 白色蒙版；没有就用柔和渐变。 */
    private void applyBackground() {
        File file = new File(getFilesDir(), BG_FILE);
        if (file.exists()) {
            Bitmap bitmap = decodeBackground(file.getAbsolutePath());
            if (bitmap != null) {
                BitmapDrawable photo = new BitmapDrawable(getResources(), bitmap);
                // 罩一层半透明白，底下的字照样清楚。
                LayerDrawable layers = new LayerDrawable(
                        new Drawable[]{photo, new ColorDrawable(0xB3FFFFFF)});
                root.setBackground(layers);
                return;
            }
        }
        GradientDrawable gradient = new GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{0xFFF4F7FF, 0xFFFFF4EC});
        root.setBackground(gradient);
    }

    /** 缩到屏幕宽左右再居中裁成屏幕比例，省内存也不变形。 */
    private Bitmap decodeBackground(String path) {
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(path, bounds);
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
            return null;
        }
        int maxW = getResources().getDisplayMetrics().widthPixels;
        int sample = 1;
        while (bounds.outWidth / (sample * 2) >= maxW) {
            sample *= 2;
        }
        BitmapFactory.Options opts = new BitmapFactory.Options();
        opts.inSampleSize = sample;
        Bitmap src = BitmapFactory.decodeFile(path, opts);
        if (src == null) {
            return null;
        }
        int vw = root.getWidth();
        int vh = root.getHeight();
        if (vw <= 0 || vh <= 0) {
            return src;
        }
        float viewAspect = (float) vw / vh;
        float imgAspect = (float) src.getWidth() / src.getHeight();
        if (Math.abs(viewAspect - imgAspect) < 0.01f) {
            return src;
        }
        int cropW;
        int cropH;
        int x = 0;
        int y = 0;
        if (imgAspect > viewAspect) {
            cropH = src.getHeight();
            cropW = Math.round(cropH * viewAspect);
            x = (src.getWidth() - cropW) / 2;
        } else {
            cropW = src.getWidth();
            cropH = Math.round(cropW / viewAspect);
            y = (src.getHeight() - cropH) / 2;
        }
        return Bitmap.createBitmap(src, x, y, cropW, cropH);
    }

    // ---- 角色提醒 ----

    private static final int REQ_PICK_AVATAR = 8;
    private static final String AVATAR_FILE = "character.jpg";
    private static final String[] CHAR_STYLES = {"傲娇", "温柔", "严厉", "中二"};

    /** 角色对话框开着的时候指向里面的头像状态，好换完图马上刷新。 */
    private TextView avatarStateView;

    /** 让喜欢的角色来催：名字 + 口吻 + 头像，都塞进解锁弹窗。名字留空 = 不用角色。 */
    private void showCharacterDialog() {
        final SharedPreferences prefs = getSharedPreferences("popmemo", MODE_PRIVATE);

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
                .setPositiveButton("保存", (d, w) -> prefs.edit()
                        .putString("character_name", nameInput.getText().toString().trim())
                        .putInt("character_style", style[0])
                        .putString("character_lines", linesInput.getText().toString())
                        .putString("deepseek_key", keyInput.getText().toString().trim())
                        .apply())
                .show();
    }

    private void updateAvatarState() {
        if (avatarStateView != null) {
            avatarStateView.setText(
                    new File(getFilesDir(), AVATAR_FILE).exists() ? "已有头像" : "还没头像");
        }
    }

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

    private void showAbout() {
        String version;
        try {
            version = getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
        } catch (Exception e) {
            version = "?";
        }
        new AlertDialog.Builder(this)
                .setTitle("PopMemo " + version)
                .setMessage("解锁就摆出没办完的事，一划处理掉。\n\n"
                        + "数据只存在这台手机上，不联网、不上传。")
                .setPositiveButton("知道了", null)
                .show();
    }

    private void confirmClear(boolean onlyDone) {
        String what = onlyDone ? "所有办完的记录" : "全部备忘录";
        new AlertDialog.Builder(this)
                .setTitle("清掉" + what + "？")
                .setMessage("这一步没有撤销。")
                .setNegativeButton("算了", null)
                .setPositiveButton("清掉", (dialog, which) -> {
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
                    refresh();
                })
                .show();
    }

    private void addCurrentInput() {
        String text = input.getText().toString().trim();
        if (text.isEmpty()) {
            return;
        }
        long now = System.currentTimeMillis();
        all.add(0, new Memo(now, text, false, now, 0L, 0L, 0L));
        store.save(all);
        input.setText("");
        refresh();
        list.setSelection(0);
    }

    private void refresh() {
        // 一定要重新读一遍：服务在解锁浮层里改了数据，内存里这份是旧的。
        all.clear();
        all.addAll(store.load());

        visible.clear();
        List<Memo> active = new ArrayList<>();
        List<Memo> done = new ArrayList<>();
        for (Memo memo : all) {
            if (memo.isDone()) {
                done.add(memo);
            } else {
                active.add(memo);
            }
        }
        // 有截止日的排前面（越近越前），其余按新写的在上。
        Collections.sort(active, (a, b) -> {
            boolean aDue = a.dueAt > 0;
            boolean bDue = b.dueAt > 0;
            if (aDue != bDue) {
                return aDue ? -1 : 1;
            }
            if (aDue) {
                return Long.compare(a.dueAt, b.dueAt);
            }
            return Long.compare(b.createdAt, a.createdAt);
        });
        Collections.sort(done, (a, b) -> Long.compare(b.doneAt, a.doneAt));

        // 长期计划（一周以后才到期）折进「以后」，平时不碍眼；弹窗那边也不弹它们。
        long now = System.currentTimeMillis();
        List<Memo> later = new ArrayList<>();
        for (int i = active.size() - 1; i >= 0; i--) {
            Memo memo = active.get(i);
            if (memo.dueAt > 0 && memo.daysLeft(now) > 7) {
                later.add(0, active.remove(i));
            }
        }

        visible.addAll(active);
        if (showLater) {
            visible.addAll(later);
        }
        if (showDone) {
            visible.addAll(done);
        }

        adapter.notifyDataSetChanged();
        empty.setVisibility(active.isEmpty() && later.isEmpty() && !showDone
                ? View.VISIBLE : View.GONE);

        if (later.isEmpty()) {
            laterFooter.setVisibility(View.GONE);
        } else {
            laterFooter.setVisibility(View.VISIBLE);
            laterFooter.setText(showLater
                    ? "收起"
                    : "以后 " + later.size() + " 件 · 点开看");
        }

        if (done.isEmpty()) {
            doneFooter.setVisibility(View.GONE);
        } else {
            doneFooter.setVisibility(View.VISIBLE);
            doneFooter.setText(showDone
                    ? "收起"
                    : "办完了 " + done.size() + " 件 · 点开看");
        }
    }

    private void openOverlaySettings() {
        try {
            startActivity(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:" + getPackageName())));
        } catch (Exception e) {
            startActivity(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION));
        }
    }

    private void requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 3);
        }
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private class MemoAdapter extends BaseAdapter {

        @Override
        public int getCount() {
            return visible.size();
        }

        @Override
        public Memo getItem(int position) {
            return visible.get(position);
        }

        @Override
        public long getItemId(int position) {
            return visible.get(position).id;
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            TextView tv;
            if (convertView instanceof TextView) {
                tv = (TextView) convertView;
            } else {
                tv = new TextView(MainActivity.this);
                tv.setPadding(dp(8), dp(14), dp(8), dp(14));
                tv.setTextSize(17f);
            }
            Memo memo = getItem(position);
            long now = System.currentTimeMillis();
            String due = memo.dueLabel(now);

            SpannableStringBuilder sb = new SpannableStringBuilder();
            if (!memo.tag.isEmpty()) {
                int tagStart = sb.length();
                sb.append(memo.tag);
                sb.setSpan(new RelativeSizeSpan(0.75f), tagStart, sb.length(),
                        Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
                sb.setSpan(new ForegroundColorSpan(0xFF3B82F6), tagStart, sb.length(),
                        Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
                sb.append("  ");
            }
            sb.append(memo.text);
            if (due != null && !memo.isDone()) {
                sb.append("   ");
                int start = sb.length();
                sb.append(due);
                sb.setSpan(new RelativeSizeSpan(0.78f), start, sb.length(),
                        Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
                boolean urgent = memo.daysLeft(now) <= 0;
                sb.setSpan(new ForegroundColorSpan(urgent ? 0xFFD93025 : 0xFF9AA0A6),
                        start, sb.length(), Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
            }
            tv.setText(sb);
            if (memo.isDone()) {
                tv.setTextColor(0xFFB0B4B8);
                tv.setPaintFlags(tv.getPaintFlags() | Paint.STRIKE_THRU_TEXT_FLAG);
            } else if (memo.muted) {
                tv.setTextColor(0xFF9AA0A6);
                tv.setPaintFlags(tv.getPaintFlags() & ~Paint.STRIKE_THRU_TEXT_FLAG);
            } else {
                tv.setTextColor(0xFF111111);
                tv.setPaintFlags(tv.getPaintFlags() & ~Paint.STRIKE_THRU_TEXT_FLAG);
            }
            return tv;
        }
    }
}
