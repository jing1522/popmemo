package com.popmemo;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.provider.Settings;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 解锁后要干的事，前台服务和无障碍服务共用同一套。
 * 浮层全进程只有一个实例：两个入口同时触发，也只会弹一次。
 */
class DueShower {

    /** 每次弹完后叫一声，让入口方刷新自己的状态（比如常驻通知的文案）。 */
    interface After {
        void onChanged(List<Memo> pending);
    }

    private static final String PREF_SNOOZE_UNTIL = "snooze_until";

    private static UnlockOverlay overlay;

    static void show(Context context, After after) {
        SharedPreferences prefs = context.getSharedPreferences("popmemo", Context.MODE_PRIVATE);
        if (System.currentTimeMillis() < prefs.getLong(PREF_SNOOZE_UNTIL, 0L)) {
            return;
        }
        if (!Settings.canDrawOverlays(context)) {
            return;
        }
        if (overlay == null) {
            overlay = new UnlockOverlay(context.getApplicationContext());
        }
        if (overlay.isShowing()) {
            return;
        }

        final MemoStore store = new MemoStore(context);
        final List<Memo> all = store.load();
        long now = System.currentTimeMillis();
        List<Memo> active = new ArrayList<>();
        for (Memo memo : pendingOf(all)) {
            // 长期计划（一周以后才到期）不进弹窗，躺列表里；进了 7 天窗口自己冒出来。
            if (memo.dueAt > 0 && memo.daysLeft(now) > 7) {
                continue;
            }
            active.add(memo);
        }
        if (active.isEmpty()) {
            if (after != null) {
                after.onChanged(active);
            }
            return;
        }

        // 弹窗频率：每 every 次解锁才弹一次（默认 5）。
        // 红卡（今天到期/已过期）不受限——急事照样每次弹，不压。
        int every = Math.max(1, prefs.getInt("popup_every", 5));
        if (every > 1) {
            boolean hasRed = false;
            for (Memo memo : active) {
                if (memo.dueAt > 0 && memo.daysLeft(now) <= 0) {
                    hasRed = true;
                    break;
                }
            }
            if (!hasRed) {
                int count = prefs.getInt("unlock_count", 0) + 1;
                if (count < every) {
                    prefs.edit().putInt("unlock_count", count).apply();
                    if (after != null) {
                        after.onChanged(active);
                    }
                    return;
                }
                prefs.edit().putInt("unlock_count", 0).apply();
            }
        }

        Collections.sort(active, (a, b) -> {
            int ta = tier(a, now);
            int tb = tier(b, now);
            if (ta != tb) {
                return Integer.compare(ta, tb);
            }
            // 同一档里，有截止日的先于没截止日的。
            boolean aDue = a.dueAt > 0;
            boolean bDue = b.dueAt > 0;
            if (aDue != bDue) {
                return aDue ? -1 : 1;
            }
            if (aDue) {
                int byDue = Long.compare(a.dueAt, b.dueAt);
                if (byDue != 0) {
                    return byDue;
                }
            }
            return Long.compare(a.lastSeenAt, b.lastSeenAt);
        });

        boolean shown = overlay.show(active, new UnlockOverlay.Listener() {
            @Override
            public void onShown(Memo memo) {
                memo.lastSeenAt = System.currentTimeMillis();
                store.save(all);
            }

            @Override
            public void onDone(Memo memo) {
                memo.doneAt = System.currentTimeMillis();
                store.save(all);
                MemoStore.notifyChanged();
                FloatingBall.refreshCount(context);
                if (after != null) {
                    after.onChanged(pendingOf(all));
                }
            }

            @Override
            public void onLater(Memo memo) {
                memo.lastSeenAt = System.currentTimeMillis();
                store.save(all);
                MemoStore.notifyChanged();
            }

            @Override
            public void onAdd() {
                Intent intent = new Intent(context, MainActivity.class);
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                intent.putExtra(MainActivity.EXTRA_FOCUS_INPUT, true);
                context.startActivity(intent);
            }
        });
        if (shown) {
            FloatingBall.refreshCount(context);
            if (after != null) {
                after.onChanged(active);
            }
        }
    }

    static List<Memo> pendingOf(List<Memo> memos) {
        List<Memo> out = new ArrayList<>();
        for (Memo memo : memos) {
            if (!memo.muted && !memo.isDone()) {
                out.add(memo);
            }
        }
        return out;
    }

    /** 0 = 已过期/今天，1 = 三天内，2 = 一周内，3 = 更远或没设。 */
    private static int tier(Memo memo, long now) {
        if (memo.dueAt <= 0) {
            return 3;
        }
        int days = memo.daysLeft(now);
        if (days <= 0) {
            return 0;
        }
        if (days <= 3) {
            return 1;
        }
        if (days <= 7) {
            return 2;
        }
        return 3;
    }
}
