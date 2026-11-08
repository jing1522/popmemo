package com.popmemo;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
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
 * 常驻前台服务：监听「解锁完成」，每次解锁都把最久没露面的 3 条摆出来。
 * 不节流、不开关——没办完就一直出现，这是这个 App 的全部意义。
 */
public class UnlockService extends Service {

    private static final String CHANNEL_ID = "unlock";
    private static final int MAX_ITEMS = 3;

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
        startForeground(1, buildNotification(pendingCount()));
        return START_STICKY;
    }

    private List<Memo> pending() {
        List<Memo> out = new ArrayList<>();
        for (Memo memo : new MemoStore(this).load()) {
            if (!memo.muted && !memo.isDone()) {
                out.add(memo);
            }
        }
        return out;
    }

    private int pendingCount() {
        return pending().size();
    }

    /** 每次解锁都摆一次：取最久没露面的 3 条。 */
    private void showDue() {
        if (!Settings.canDrawOverlays(this) || overlay.isShowing()) {
            return;
        }

        MemoStore store = new MemoStore(this);
        List<Memo> all = store.load();
        List<Memo> active = new ArrayList<>();
        for (Memo memo : all) {
            if (!memo.muted && !memo.isDone()) {
                active.add(memo);
            }
        }
        if (active.isEmpty()) {
            updateNotification(0);
            return;
        }

        Collections.sort(active, (a, b) -> Long.compare(a.lastSeenAt, b.lastSeenAt));
        List<Memo> due = new ArrayList<>(active.subList(0, Math.min(MAX_ITEMS, active.size())));

        if (!overlay.show(due)) {
            return;
        }

        long now = System.currentTimeMillis();
        for (Memo memo : due) {
            memo.lastSeenAt = now;
        }
        store.save(all);
        updateNotification(active.size());
    }

    private Notification buildNotification(int pending) {
        NotificationChannel channel =
                new NotificationChannel(CHANNEL_ID, "解锁提醒", NotificationManager.IMPORTANCE_LOW);
        channel.setDescription("解锁时摆出没办完的事");
        NotificationManager manager = getSystemService(NotificationManager.class);
        if (manager != null) {
            manager.createNotificationChannel(channel);
        }
        String text = pending > 0 ? "还有 " + pending + " 件事没办" : "都办完了";
        return new Notification.Builder(this, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
                .setContentTitle("PopMemo")
                .setContentText(text)
                .setOngoing(true)
                .build();
    }

    private void updateNotification(int pending) {
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
