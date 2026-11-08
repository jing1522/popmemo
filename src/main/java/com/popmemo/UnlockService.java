package com.popmemo;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.IBinder;
import android.provider.Settings;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 常驻前台服务：监听「解锁完成」，每次解锁都把最该看见的 3 条摆出来。
 * 不节流、不开关——没办完就一直出现。
 * 截止日越近，越优先占卡位；没设日期的按「最久没露面」轮转。
 */
public class UnlockService extends Service {

    private static final String CHANNEL_ID = "unlock";
    private static final int MAX_ITEMS = 5;

    private UnlockOverlay overlay;
    private BroadcastReceiver unlockReceiver;

    @Override
    public void onCreate() {
        super.onCreate();
        overlay = new UnlockOverlay(this);
        unlockReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                if (Intent.ACTION_USER_PRESENT.equals(intent.getAction())) {
                    showDue();
                }
            }
        };
        registerReceiver(unlockReceiver, new IntentFilter(Intent.ACTION_USER_PRESENT));
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        startForeground(1, buildNotification(pendingOf(new MemoStore(this).load())));
        return START_STICKY;
    }

    private List<Memo> pendingOf(List<Memo> memos) {
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

    private void showDue() {
        if (!Settings.canDrawOverlays(this) || overlay.isShowing()) {
            return;
        }

        MemoStore store = new MemoStore(this);
        List<Memo> all = store.load();
        List<Memo> active = pendingOf(all);
        if (active.isEmpty()) {
            updateNotification(active);
            return;
        }

        long now = System.currentTimeMillis();
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

        if (!overlay.show(active, new UnlockOverlay.Listener() {
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
                updateNotification(pendingOf(all));
            }

            @Override
            public void onLater(Memo memo) {
                memo.lastSeenAt = System.currentTimeMillis();
                store.save(all);
                MemoStore.notifyChanged();
            }

            @Override
            public void onAdd() {
                Intent intent = new Intent(UnlockService.this, MainActivity.class);
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                intent.putExtra(MainActivity.EXTRA_FOCUS_INPUT, true);
                startActivity(intent);
            }
        })) {
            return;
        }

        updateNotification(active);
    }

    private Notification buildNotification(List<Memo> pending) {
        NotificationChannel channel =
                new NotificationChannel(CHANNEL_ID, "解锁提醒", NotificationManager.IMPORTANCE_LOW);
        channel.setDescription("解锁时摆出没办完的事");
        NotificationManager manager = getSystemService(NotificationManager.class);
        if (manager != null) {
            manager.createNotificationChannel(channel);
        }

        long now = System.currentTimeMillis();
        int dueToday = 0;
        for (Memo memo : pending) {
            if (memo.dueAt > 0 && memo.daysLeft(now) <= 0) {
                dueToday++;
            }
        }

        String text;
        if (pending.isEmpty()) {
            text = "都办完了";
        } else if (dueToday > 0) {
            text = "还有 " + pending.size() + " 件事，其中 " + dueToday + " 件今天要交";
        } else {
            text = "还有 " + pending.size() + " 件事没办";
        }

        Intent open = new Intent(this, MainActivity.class);
        open.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent openIntent = PendingIntent.getActivity(this, 1, open,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        Intent quick = new Intent(this, MainActivity.class);
        quick.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        quick.putExtra(MainActivity.EXTRA_FOCUS_INPUT, true);
        PendingIntent quickIntent = PendingIntent.getActivity(this, 2, quick,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        return new Notification.Builder(this, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
                .setContentTitle("PopMemo")
                .setContentText(text)
                .setContentIntent(openIntent)
                .addAction(android.R.drawable.ic_menu_add, "写一条", quickIntent)
                .setOngoing(true)
                .build();
    }

    private void updateNotification(List<Memo> pending) {
        NotificationManager manager = getSystemService(NotificationManager.class);
        if (manager != null) {
            manager.notify(1, buildNotification(pending));
        }
    }

    @Override
    public void onDestroy() {
        if (unlockReceiver != null) {
            unregisterReceiver(unlockReceiver);
            unlockReceiver = null;
        }
        if (overlay != null) {
            overlay.close();
        }
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
