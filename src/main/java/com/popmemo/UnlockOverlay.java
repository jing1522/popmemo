package com.popmemo;

import android.content.Context;
import android.graphics.Color;
import android.graphics.PixelFormat;
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
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.List;

/**
 * 解锁后盖在最上层的那几张卡片。每条单独滑：
 * 右滑 = 办完（消失），左滑 = 还没（消失，下次再排上来），点旁边空白 = 只看一眼。
 */
public class UnlockOverlay {

    public interface Listener {
        void onDone(Memo memo);

        void onLater(Memo memo);

        void onAdd();
    }

    private static final int CARD_BG = 0xFF1C1C1E;
    private static final int ADD_BG = 0xFF2C2C2E;
    private static final int TEXT_MAIN = 0xFFF2F2F7;
    private static final int TEXT_DIM = 0xFF8E8E93;
    private static final int GREEN = 0xFF34C759;
    private static final int URGENT = 0xFFFF453A;

    private final Context context;
    private final WindowManager wm;
    private View root;
    private LinearLayout stack;
    private TextView hint;
    private boolean dismissed;
    private Listener listener;
    private int screenWidth;

    public UnlockOverlay(Context context) {
        this.context = context;
        this.wm = (WindowManager) context.getSystemService(Context.WINDOW_SERVICE);
    }

    public boolean isShowing() {
        return root != null && !dismissed && root.isAttachedToWindow();
    }

    /** 显示成功返回 true。 */
    public boolean show(List<Memo> memos, Listener listener) {
        if (isShowing() || memos.isEmpty()) {
            return false;
        }
        this.listener = listener;
        this.dismissed = false;
        this.screenWidth = context.getResources().getDisplayMetrics().widthPixels;

        FrameLayout frame = new FrameLayout(context);
        frame.setBackgroundColor(0x99000000);

        LinearLayout column = new LinearLayout(context);
        column.setOrientation(LinearLayout.VERTICAL);

        TextView title = new TextView(context);
        title.setText("别忘了");
        title.setTextColor(TEXT_DIM);
        title.setTextSize(13);
        column.addView(title, marginParams(0, 0, 8));

        stack = new LinearLayout(context);
        stack.setOrientation(LinearLayout.VERTICAL);
        column.addView(stack);

        for (Memo memo : memos) {
            stack.addView(buildCard(memo));
        }

        LinearLayout bottom = new LinearLayout(context);
        bottom.setOrientation(LinearLayout.HORIZONTAL);
        bottom.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams bottomParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        bottomParams.topMargin = dp(6);

        hint = new TextView(context);
        hint.setText("← 还没    办完 →");
        hint.setTextColor(TEXT_DIM);
        hint.setTextSize(12);
        hint.setLayoutParams(new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        bottom.addView(hint);

        TextView add = new TextView(context);
        add.setText("＋");
        add.setTextColor(TEXT_MAIN);
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

        FrameLayout.LayoutParams columnParams = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT);
        columnParams.gravity = Gravity.CENTER;
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
        params.dimAmount = 0.45f;

        root = frame;
        try {
            wm.addView(frame, params);
        } catch (Exception e) {
            root = null;
            return false;
        }
        return true;
    }

    private TextView buildCard(Memo memo) {
        TextView card = new TextView(context);

        long now = System.currentTimeMillis();
        String due = memo.dueLabel(now);
        SpannableStringBuilder sb = new SpannableStringBuilder(memo.text);
        if (due != null) {
            sb.append("\n");
            int start = sb.length();
            sb.append(due);
            sb.setSpan(new RelativeSizeSpan(0.8f), start, sb.length(),
                    Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
            sb.setSpan(new ForegroundColorSpan(memo.daysLeft(now) <= 0 ? URGENT : TEXT_DIM),
                    start, sb.length(), Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
        }

        card.setText(sb);
        card.setTextColor(TEXT_MAIN);
        card.setTextSize(18);
        card.setMaxLines(4);
        card.setEllipsize(TextUtils.TruncateAt.END);
        int pad = dp(18);
        card.setPadding(pad, pad, pad, pad);

        GradientDrawable background = new GradientDrawable();
        background.setColor(CARD_BG);
        background.setCornerRadius(dp(16));
        card.setBackground(background);

        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        params.bottomMargin = dp(10);
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
        try {
            wm.removeView(view);
        } catch (Exception ignored) {
        }
    }

    private void flyOut(View card, Memo memo, boolean done) {
        hint.setText(done ? "办完了" : "还没办");
        card.animate()
                .translationX((done ? 1 : -1) * screenWidth * 1.3f)
                .alpha(0f)
                .setDuration(180)
                .withEndAction(() -> {
                    if (stack != null) {
                        stack.removeView(card);
                    }
                    if (done) {
                        listener.onDone(memo);
                    } else {
                        listener.onLater(memo);
                    }
                    if (stack == null || stack.getChildCount() == 0) {
                        close();
                    } else {
                        hint.setText("← 还没    办完 →");
                    }
                })
                .start();
    }

    private int dp(int value) {
        return Math.round(value * context.getResources().getDisplayMetrics().density);
    }

    /** 单张卡片横向拖动。 */
    private class Drag implements View.OnTouchListener {

        private final View card;
        private final Memo memo;
        private float startX;
        private boolean dragging;

        Drag(View card, Memo memo) {
            this.card = card;
            this.memo = memo;
        }

        @Override
        public boolean onTouch(View v, MotionEvent event) {
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    startX = event.getRawX();
                    dragging = true;
                    card.animate().cancel();
                    return true;
                case MotionEvent.ACTION_MOVE: {
                    if (!dragging) {
                        return true;
                    }
                    float dx = event.getRawX() - startX;
                    card.setTranslationX(dx);
                    card.setAlpha(Math.max(0.35f, 1f - Math.abs(dx) / (float) screenWidth));
                    if (dx > dp(24)) {
                        hint.setText("办完了 →");
                        hint.setTextColor(GREEN);
                    } else if (dx < -dp(24)) {
                        hint.setText("← 还没办");
                        hint.setTextColor(TEXT_DIM);
                    } else {
                        hint.setText("← 还没    办完 →");
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
                        hint.setText("← 还没    办完 →");
                    }
                    return true;
                }
                default:
                    return false;
            }
        }
    }
}
