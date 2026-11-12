package com.popmemo;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.text.Spannable;
import android.text.SpannableStringBuilder;
import android.text.TextUtils;
import android.text.style.ForegroundColorSpan;
import android.text.style.RelativeSizeSpan;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Random;

/**
 * 解锁后盖在最上层的那几张卡片。每条单独滑：
 * 右滑 = 办完，左滑 = 还没，下滑 = 换一批非红卡，点旁边空白 = 只看一眼。
 * 红卡（今天到期/已过期）每次固定占最前面的位置；非红卡随机抽。
 * 一次最多摆 5 张，滑掉一张就补一张，补到没有为止。
 */
public class UnlockOverlay {

    public interface Listener {
        void onShown(Memo memo);

        void onDone(Memo memo);

        void onLater(Memo memo);

        void onAdd();
    }

    private static final int VISIBLE_MAX = 5;

    private static final int CARD_BG = 0xFFFFFFFF;
    private static final int PANEL_TOP = 0xFFF7F6FB;
    private static final int PANEL_BOTTOM = 0xFFE9E7F2;
    private static final int ADD_BG = 0xFF0A84FF;
    private static final int TEXT_MAIN = 0xFF1C1C1E;
    private static final int TEXT_DIM = 0xFF8E8E93;
    private static final int GREEN = 0xFF34C759;
    private static final int URGENT = 0xFFFF453A;

    /** 内置口吻，必须带 {事项}。自己写了台词就不用这些。 */
    private static final String[][] STYLE_LINES = {
            {"哼，{事项}这种事还要我提醒？", "才、才不是担心你，{事项}别忘了！", "{事项}还没做？真是拿你没办法。"},
            {"别忘了{事项}哦，我等你做完～", "{事项}，慢慢来，但别拖太久呀。", "提醒一下：{事项}。做完奖励自己一下吧。"},
            {"{事项}。现在，立刻。", "别找借口，{事项}还没完成。", "{事项}！拖一天算一天？"},
            {"命运之书记载：{事项}，必须完成！", "哼，{事项}……这就是你的试炼吗。", "觉醒吧！{事项}的封印等着被解除！"},
    };

    private final Context context;
    private final WindowManager wm;
    /** 红卡（今天到期/已过期）：固定先上，不占随机名额。 */
    private final List<Memo> pinned = new ArrayList<>();
    /** 非红卡：洗乱后从头上，换下来的回队尾。 */
    private final List<Memo> pool = new ArrayList<>();
    private int pinnedIndex;

    private View root;
    private LinearLayout stack;
    private TextView hint;
    private boolean dismissed;
    private Listener listener;
    private int screenWidth;

    /** 角色提醒：名字非空才算启用。 */
    private String charName = "";
    private int charStyle;
    private final List<String> charLines = new ArrayList<>();
    private final Random random = new Random();

    public UnlockOverlay(Context context) {
        this.context = context;
        this.wm = (WindowManager) context.getSystemService(Context.WINDOW_SERVICE);
    }

    public boolean isShowing() {
        return root != null && !dismissed && root.isAttachedToWindow();
    }

    /** queue 是全部待办。红卡固定占位，非红卡随机抽。 */
    public boolean show(List<Memo> queue, Listener listener) {
        if (isShowing() || queue.isEmpty()) {
            return false;
        }
        this.listener = listener;
        this.dismissed = false;
        this.screenWidth = context.getResources().getDisplayMetrics().widthPixels;

        SharedPreferences prefs = context.getSharedPreferences("popmemo", Context.MODE_PRIVATE);
        charName = prefs.getString("character_name", "").trim();
        charStyle = prefs.getInt("character_style", 0);
        charLines.clear();
        for (String line : prefs.getString("character_lines", "").split("\n")) {
            String trimmed = line.trim();
            if (!trimmed.isEmpty()) {
                charLines.add(trimmed);
            }
        }

        long now = System.currentTimeMillis();
        List<Memo> reds = new ArrayList<>();
        List<Memo> rest = new ArrayList<>();
        for (Memo memo : queue) {
            if (memo.dueAt > 0 && memo.daysLeft(now) <= 0) {
                reds.add(memo);
            } else {
                rest.add(memo);
            }
        }
        pinned.clear();
        pool.clear();
        // 红卡装不下 5 张时，多出来的进随机池，不丢。
        if (reds.size() > VISIBLE_MAX) {
            pinned.addAll(reds.subList(0, VISIBLE_MAX));
            pool.addAll(reds.subList(VISIBLE_MAX, reds.size()));
        } else {
            pinned.addAll(reds);
        }
        pool.addAll(rest);
        Collections.shuffle(pool);
        pinnedIndex = 0;

        FrameLayout frame = new FrameLayout(context);
        frame.setBackgroundColor(0x2E000000);

        LinearLayout column = new LinearLayout(context);
        column.setOrientation(LinearLayout.VERTICAL);
        int panelPad = dp(14);
        column.setPadding(panelPad, panelPad, panelPad, panelPad);
        // 面板不是死黑一块：纵向渐变 + 一圈细描边，看着不那么闷。
        GradientDrawable panelBg = new GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{PANEL_TOP, PANEL_BOTTOM});
        panelBg.setCornerRadius(dp(24));
        panelBg.setStroke(dp(1), 0x12000000);
        column.setBackground(panelBg);

        LinearLayout header = new LinearLayout(context);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);

        Bitmap avatar = charName.isEmpty() ? null : decodeAvatar();
        if (avatar != null) {
            ImageView avatarView = new ImageView(context);
            avatarView.setImageBitmap(avatar);
            LinearLayout.LayoutParams avatarParams = new LinearLayout.LayoutParams(dp(32), dp(32));
            avatarParams.rightMargin = dp(8);
            avatarParams.gravity = Gravity.CENTER_VERTICAL;
            header.addView(avatarView, avatarParams);
        }

        TextView title = new TextView(context);
        title.setText(charName.isEmpty() ? "别忘了" : charName + " 来催了");
        title.setTextColor(TEXT_DIM);
        title.setTextSize(13);
        title.setLayoutParams(new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        header.addView(title);

        TextView left = new TextView(context);
        left.setText("今天还剩 " + remainingToday());
        left.setTextColor(TEXT_DIM);
        left.setTextSize(13);
        header.addView(left);

        column.addView(header, marginParams(0, 0, 10));

        stack = new LinearLayout(context);
        stack.setOrientation(LinearLayout.VERTICAL);
        column.addView(stack);

        LinearLayout bottom = new LinearLayout(context);
        bottom.setOrientation(LinearLayout.HORIZONTAL);
        bottom.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams bottomParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        bottomParams.topMargin = dp(6);

        hint = new TextView(context);
        hint.setText("← 还没   ↓ 换一批   办完 →");
        hint.setTextColor(TEXT_DIM);
        hint.setTextSize(12);
        hint.setLayoutParams(new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        bottom.addView(hint);

        TextView add = new TextView(context);
        add.setText("＋");
        add.setTextColor(Color.WHITE);
        add.setTextSize(22);
        add.setTypeface(Typeface.DEFAULT_BOLD);
        add.setGravity(Gravity.CENTER);
        add.setWidth(dp(48));
        add.setHeight(dp(48));
        GradientDrawable addBg = new GradientDrawable();
        addBg.setColor(ADD_BG);
        addBg.setCornerRadius(dp(24));
        add.setBackground(addBg);
        add.setOnClickListener(v -> {
            close();
            listener.onAdd();
        });
        bottom.addView(add);

        column.addView(bottom, bottomParams);

        // 从卡片以外的空白处（边距、标题栏）下滑，也算换一批。
        column.setOnTouchListener(new SwapDown());

        FrameLayout.LayoutParams columnParams = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT);
        // 整体靠上，不在屏幕正中。
        columnParams.gravity = Gravity.TOP | Gravity.CENTER_HORIZONTAL;
        columnParams.topMargin = Math.round(
                context.getResources().getDisplayMetrics().heightPixels * 0.13f);
        columnParams.leftMargin = dp(20);
        columnParams.rightMargin = dp(20);
        frame.addView(column, columnParams);

        frame.setOnClickListener(v -> close());

        WindowManager.LayoutParams params = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                        | WindowManager.LayoutParams.FLAG_DIM_BEHIND,
                PixelFormat.TRANSLUCENT);
        params.dimAmount = 0.25f;

        root = frame;
        try {
            wm.addView(frame, params);
        } catch (Exception e) {
            root = null;
            return false;
        }

        fillUp(false);
        maybeFetchAiLines();
        return true;
    }

    /** 红卡先上，上完从随机池里抽。 */
    private boolean drawNext(boolean animate) {
        Memo memo;
        if (pinnedIndex < pinned.size()) {
            memo = pinned.get(pinnedIndex++);
        } else if (!pool.isEmpty()) {
            memo = pool.remove(0);
        } else {
            return false;
        }
        TextView card = buildCard(memo);
        card.setTag(memo);
        stack.addView(card);

        if (animate) {
            card.setAlpha(0f);
            card.setTranslationY(-dp(14));
            card.animate().alpha(1f).translationY(0f).setDuration(160).start();
        }
        if (listener != null) {
            listener.onShown(memo);
        }
        return true;
    }

    /** 把卡位补满：能摆几张摆几张，没存货就停。 */
    private void fillUp(boolean animate) {
        while (stack.getChildCount() < VISIBLE_MAX && drawNext(animate)) {
            // 补满或抽空为止
        }
    }

    /** 下滑换一批：屏上的非红卡挪回队尾（洗一下），从池子里换新的上来。红卡不动。 */
    private void swapBatch() {
        List<Memo> back = new ArrayList<>();
        for (int i = stack.getChildCount() - 1; i >= 0; i--) {
            View child = stack.getChildAt(i);
            Object tag = child.getTag();
            if (tag instanceof Memo && !pinned.contains(tag)) {
                back.add((Memo) tag);
                stack.removeViewAt(i);
            }
        }
        if (back.isEmpty()) {
            // 屏上全是红卡，没有可换的。
            return;
        }
        Collections.shuffle(back);
        pool.addAll(back);
        fillUp(true);
        hint.setText("换了一批 ↑");
        hint.setTextColor(TEXT_DIM);
    }

    /** 角色口吻包装：没启用就原样；自己写了台词用自己的，否则用内置风格。 */
    private String speak(String text) {
        if (charName.isEmpty()) {
            return text;
        }
        List<String> pool = charLines.isEmpty()
                ? Arrays.asList(STYLE_LINES[Math.min(charStyle, STYLE_LINES.length - 1)])
                : charLines;
        return pool.get(random.nextInt(pool.size())).replace("{事项}", text);
    }

    /** 头像解码过一次就留着，文件没变就直接用，免得每次弹窗都现裁一遍。 */
    private Bitmap avatarCache;
    private long avatarStamp = -1;

    /** 头像：裁成圆形小图，没设过就返回 null。 */
    private Bitmap decodeAvatar() {
        File file = new File(context.getFilesDir(), "character.jpg");
        if (!file.exists()) {
            return null;
        }
        if (avatarCache != null && avatarStamp == file.lastModified()) {
            return avatarCache;
        }
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(file.getAbsolutePath(), bounds);
        if (bounds.outWidth <= 0) {
            return null;
        }
        int sample = 1;
        while (bounds.outWidth / (sample * 2) >= dp(64)) {
            sample *= 2;
        }
        BitmapFactory.Options opts = new BitmapFactory.Options();
        opts.inSampleSize = sample;
        Bitmap src = BitmapFactory.decodeFile(file.getAbsolutePath(), opts);
        if (src == null) {
            return null;
        }
        int side = Math.min(src.getWidth(), src.getHeight());
        Bitmap square = Bitmap.createBitmap(src,
                (src.getWidth() - side) / 2, (src.getHeight() - side) / 2, side, side);
        Bitmap out = Bitmap.createBitmap(side, side, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(out);
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        paint.setShader(new BitmapShader(square, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP));
        canvas.drawCircle(side / 2f, side / 2f, side / 2f, paint);
        avatarCache = out;
        avatarStamp = file.lastModified();
        return out;
    }

    /** AI 台词回来之前先按模板弹；回来后按这个序号比对，过期批次直接丢。 */
    private int aiGeneration;

    /** 填了 DeepSeek key 且启用了角色，就异步给屏上这批卡各要一句台词。 */
    private void maybeFetchAiLines() {
        SharedPreferences prefs = context.getSharedPreferences("popmemo", Context.MODE_PRIVATE);
        String key = prefs.getString("deepseek_key", "").trim();
        if (charName.isEmpty() || key.isEmpty()) {
            return;
        }
        final List<Memo> batch = new ArrayList<>();
        for (int i = 0; i < stack.getChildCount(); i++) {
            Object tag = stack.getChildAt(i).getTag();
            if (tag instanceof Memo) {
                batch.add((Memo) tag);
            }
        }
        if (batch.isEmpty()) {
            return;
        }
        final int generation = ++aiGeneration;
        CharacterAi.fetchLines(key, charName, charStyle, charLines, batch, lines -> {
            if (generation != aiGeneration || !isShowing()) {
                return;
            }
            for (int i = 0; i < stack.getChildCount(); i++) {
                View child = stack.getChildAt(i);
                Object tag = child.getTag();
                int index = batch.indexOf(tag);
                if (index >= 0 && index < lines.size() && !lines.get(index).isEmpty()
                        && child instanceof TextView) {
                    ((TextView) child).setText(cardText((Memo) tag, lines.get(index)));
                }
            }
        });
    }

    private CharSequence cardText(Memo memo, String spoken) {
        long now = System.currentTimeMillis();
        String due = memo.dueLabel(now);
        SpannableStringBuilder sb = new SpannableStringBuilder();
        if (!memo.tag.isEmpty()) {
            int tagStart = sb.length();
            sb.append(memo.tag);
            sb.setSpan(new RelativeSizeSpan(0.75f), tagStart, sb.length(),
                    Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
            sb.setSpan(new ForegroundColorSpan(0xFF3B6FD4), tagStart, sb.length(),
                    Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
            sb.append("  ");
        }
        sb.append(spoken);
        if (due != null) {
            sb.append("\n");
            int start = sb.length();
            sb.append(due);
            sb.setSpan(new RelativeSizeSpan(0.8f), start, sb.length(),
                    Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
            sb.setSpan(new ForegroundColorSpan(memo.daysLeft(now) <= 0 ? URGENT : TEXT_DIM),
                    start, sb.length(), Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
        return sb;
    }

    private TextView buildCard(Memo memo) {
        TextView card = new TextView(context);

        card.setText(cardText(memo, speak(memo.text)));
        card.setTextColor(TEXT_MAIN);
        card.setTextSize(18);
        card.setMaxLines(4);
        card.setEllipsize(TextUtils.TruncateAt.END);
        int pad = dp(16);
        card.setPadding(pad, pad, pad, pad);

        GradientDrawable background = new GradientDrawable();
        background.setColor(CARD_BG);
        background.setCornerRadius(dp(16));
        // 白卡垫在浅色面板上，靠一圈细边分出层次。
        background.setStroke(dp(1), 0x12000000);
        card.setBackground(background);

        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        params.bottomMargin = dp(8);
        card.setLayoutParams(params);
        card.setOnTouchListener(new Drag(card, memo));
        return card;
    }

    private LinearLayout.LayoutParams marginParams(int left, int top, int bottom) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        params.leftMargin = left;
        params.topMargin = top;
        params.bottomMargin = bottom;
        return params;
    }

    public void close() {
        if (root == null) {
            return;
        }
        dismissed = true;
        View view = root;
        root = null;
        pinned.clear();
        pool.clear();
        try {
            wm.removeView(view);
        } catch (Exception ignored) {
        }
    }

    private void flyOut(View card, Memo memo, boolean done) {
        card.animate()
                .translationX((done ? 1 : -1) * screenWidth * 1.3f)
                .alpha(0f)
                .setDuration(170)
                .withEndAction(() -> {
                    if (stack != null) {
                        stack.removeView(card);
                    }
                    if (done) {
                        listener.onDone(memo);
                    } else {
                        listener.onLater(memo);
                    }
                    if (stack != null) {
                        fillUp(true);
                    }
                    if (hint != null) {
                        hint.setText("← 还没   ↓ 换一批   办完 →");
                        hint.setTextColor(TEXT_DIM);
                    }
                    if (stack == null || stack.getChildCount() == 0) {
                        close();
                    }
                })
                .start();
    }

    /** 今天还剩多久。不需要你填任何作息，纯粹按钟点算。 */
    private String remainingToday() {
        long now = System.currentTimeMillis();
        long midnight = Memo.startOfDay(now) + 86400000L;
        int minutes = (int) Math.max(0, (midnight - now) / 60000L);
        int hours = minutes / 60;
        int rest = minutes % 60;
        if (hours == 0) {
            return rest + " 分钟";
        }
        if (rest == 0) {
            return hours + " 小时";
        }
        return hours + " 小时 " + rest + " 分";
    }

    private int dp(int value) {
        return Math.round(value * context.getResources().getDisplayMetrics().density);
    }

    /** 下拉手势判定共用：幅度够深、明显是竖着拉，才算换一批。 */
    private boolean isSwapPull(float dx, float dy) {
        return dy > dp(56) && Math.abs(dy) > 2 * Math.abs(dx);
    }

    /** 单张卡片：横滑 = 办完/还没，竖着下拉 = 换一批。 */
    private class Drag implements View.OnTouchListener {

        private final View card;
        private final Memo memo;
        private float startX;
        private float startY;
        private boolean dragging;
        private boolean swapped;

        Drag(View card, Memo memo) {
            this.card = card;
            this.memo = memo;
        }

        @Override
        public boolean onTouch(View v, MotionEvent event) {
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    startX = event.getRawX();
                    startY = event.getRawY();
                    dragging = true;
                    swapped = false;
                    card.animate().cancel();
                    return true;
                case MotionEvent.ACTION_MOVE: {
                    if (!dragging || swapped) {
                        return true;
                    }
                    float dx = event.getRawX() - startX;
                    float dy = event.getRawY() - startY;
                    if (isSwapPull(dx, dy)) {
                        swapped = true;
                        dragging = false;
                        card.setTranslationX(0f);
                        card.setAlpha(1f);
                        swapBatch();
                        return true;
                    }
                    card.setTranslationX(dx);
                    card.setAlpha(Math.max(0.35f, 1f - Math.abs(dx) / (float) screenWidth));
                    if (dx > dp(24)) {
                        hint.setText("办完了 →");
                        hint.setTextColor(GREEN);
                    } else if (dx < -dp(24)) {
                        hint.setText("← 还没办");
                        hint.setTextColor(TEXT_DIM);
                    } else {
                        hint.setText("← 还没   ↓ 换一批   办完 →");
                        hint.setTextColor(TEXT_DIM);
                    }
                    return true;
                }
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL: {
                    if (!dragging) {
                        return true;
                    }
                    dragging = false;
                    float dx = card.getTranslationX();
                    hint.setTextColor(TEXT_DIM);
                    if (Math.abs(dx) > screenWidth * 0.3f) {
                        flyOut(card, memo, dx > 0);
                    } else {
                        card.animate().translationX(0f).alpha(1f).setDuration(140).start();
                        hint.setText("← 还没   ↓ 换一批   办完 →");
                    }
                    return true;
                }
                default:
                    return false;
            }
        }
    }

    /** 面板上没卡片的地方（边距、标题栏）也能下滑换一批。 */
    private class SwapDown implements View.OnTouchListener {

        private float startX;
        private float startY;
        private boolean done;

        @Override
        public boolean onTouch(View v, MotionEvent event) {
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    startX = event.getRawX();
                    startY = event.getRawY();
                    done = false;
                    return true;
                case MotionEvent.ACTION_MOVE:
                    if (!done && isSwapPull(event.getRawX() - startX, event.getRawY() - startY)) {
                        done = true;
                        swapBatch();
                    }
                    return true;
                case MotionEvent.ACTION_UP:
                    // 没滑成，只是点了一下空白：跟点弹窗外一样，看一眼就走。
                    if (!done) {
                        close();
                    }
                    return true;
                default:
                    return true;
            }
        }
    }
}
