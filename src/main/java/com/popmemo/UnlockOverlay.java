package com.popmemo;

import android.content.Context;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.drawable.GradientDrawable;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.List;

/** 解锁后盖在最上层的那张卡片：左右一划就消失。 */
public class UnlockOverlay {

    private static final int MAX_ITEMS = 5;

    private final Context context;
    private final WindowManager wm;
    private View root;
    private boolean dismissed;

    public UnlockOverlay(Context context) {
        this.context = context;
        this.wm = (WindowManager) context.getSystemService(Context.WINDOW_SERVICE);
    }

    public boolean isShowing() {
        return root != null && !dismissed && root.isAttachedToWindow();
    }

    /** 显示成功返回 true。 */
    public boolean show(List<Memo> memos) {
        if (isShowing() || memos.isEmpty()) {
            return false;
        }
        dismissed = false;
        final int screenWidth = context.getResources().getDisplayMetrics().widthPixels;

        FrameLayout frame = new FrameLayout(context);
        frame.setBackgroundColor(0x66000000);

        LinearLayout card = buildCard(memos);
        FrameLayout.LayoutParams cardParams = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT);
        cardParams.gravity = Gravity.CENTER;
        cardParams.leftMargin = dp(24);
        cardParams.rightMargin = dp(24);
        frame.addView(card, cardParams);

        card.setOnTouchListener(new SwipeDismiss(card, screenWidth));
        frame.setOnClickListener(v -> close());

        WindowManager.LayoutParams params = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                        | WindowManager.LayoutParams.FLAG_DIM_BEHIND,
                PixelFormat.TRANSLUCENT);
        params.dimAmount = 0.35f;

        root = frame;
        try {
            wm.addView(frame, params);
        } catch (Exception e) {
            root = null;
            return false;
        }
        return true;
    }

    private LinearLayout buildCard(List<Memo> memos) {
        LinearLayout card = new LinearLayout(context);
        card.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(20);
        card.setPadding(pad, pad, pad, pad);

        GradientDrawable background = new GradientDrawable();
        background.setColor(Color.WHITE);
        background.setCornerRadius(dp(20));
        card.setBackground(background);

        TextView title = new TextView(context);
        title.setText("别忘了");
        title.setTextColor(0xFF9AA0A6);
        title.setTextSize(13);
        card.addView(title);

        int count = Math.min(memos.size(), MAX_ITEMS);
        for (int i = 0; i < count; i++) {
            TextView item = new TextView(context);
            item.setText(memos.get(i).text);
            item.setTextColor(0xFF111111);
            item.setTextSize(18);
            item.setMaxLines(2);
            item.setEllipsize(TextUtils.TruncateAt.END);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT);
            lp.topMargin = dp(i == 0 ? 12 : 10);
            card.addView(item, lp);
        }

        if (memos.size() > MAX_ITEMS) {
            TextView more = new TextView(context);
            more.setText("还有 " + (memos.size() - MAX_ITEMS) + " 条");
            more.setTextColor(0xFF9AA0A6);
            more.setTextSize(13);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT);
            lp.topMargin = dp(10);
            card.addView(more, lp);
        }

        TextView hint = new TextView(context);
        hint.setText("← 左右滑动关闭 →");
        hint.setTextColor(0xFFB0B4B8);
        hint.setTextSize(12);
        hint.setGravity(Gravity.CENTER_HORIZONTAL);
        LinearLayout.LayoutParams hintParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        hintParams.topMargin = dp(18);
        card.addView(hint, hintParams);

        return card;
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

    private void slideOut(View card, int direction) {
        card.animate()
                .translationX(direction * context.getResources().getDisplayMetrics().widthPixels * 1.3f)
                .alpha(0f)
                .setDuration(180)
                .withEndAction(this::close)
                .start();
    }

    private int dp(int value) {
        return Math.round(value * context.getResources().getDisplayMetrics().density);
    }

    private class SwipeDismiss implements View.OnTouchListener {

        private final View card;
        private final int screenWidth;
        private float startX;
        private boolean dragging;

        SwipeDismiss(View card, int screenWidth) {
            this.card = card;
            this.screenWidth = screenWidth;
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
                    card.setAlpha(Math.max(0.2f, 1f - Math.abs(dx) / (float) screenWidth));
                    return true;
                }
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL: {
                    if (!dragging) {
                        return true;
                    }
                    dragging = false;
                    float dx = card.getTranslationX();
                    if (Math.abs(dx) > screenWidth * 0.25f) {
                        slideOut(card, dx > 0 ? 1 : -1);
                    } else {
                        card.animate().translationX(0f).alpha(1f).setDuration(140).start();
                    }
                    return true;
                }
                default:
                    return false;
            }
        }
    }
}
