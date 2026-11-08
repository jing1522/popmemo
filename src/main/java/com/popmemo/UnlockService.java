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
import java.util.List;

/**
 * 常驻前台服务：监听「解锁完成」这个广播，到点就弹卡片。
 * 前台服务必须有通知，那是安卓的硬性要求，不是我们想打扰你。
 */
public class UnlockService extends Service {

    public static final String PREF_ENABLED = "service_enabled";
    private static final String CHANNEL_ID = "unlock";
    private static final long THROTTLE_MS = 30 * 60 * 1000L;

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
                    showIfDue();
                }
            }
        };
        registerReceiver(unlockReceiver, new IntentFilter(Intent.ACTION_USER_PRESENT));
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        startForeground(1, buildNotification());
        return START_STICKY;
    }

    private Notification buildNotification() {
        NotificationChannel channel =
                new NotificationChannel(CHANNEL_ID, "解锁提醒", NotificationManager.IMPORTANCE_LOW);
        channel.setDescription("解锁时弹出重要事项");
        NotificationManager manager = getSystemService(NotificationManager.class);
        if (manager != null) {
            manager.createNotificationChannel(channel);
        }
        return new Notification.Builder(this, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
                .setContentTitle("解锁时会提醒你")
                .setContentText("重要的事，解锁就弹")
                .setOngoing(true)
                .build();
    }

    /** 只弹「重要」且距上次弹出超过 30 分钟的那些。 */
    private void showIfDue() {
        if (!Settings.canDrawOverlays(this)) {
            return;
        }
        if (overlay.isShowing()) {
            return;
        }

        MemoStore store = new MemoStore(this);
        List<Memo> all = store.load();
        long now = System.currentTimeMillis();

        List<Memo> due = new ArrayList<>();
        for (Memo memo : all) {
            if (memo.important && now - memo.lastShownAt >= THROTTLE_MS) {
                due.add(memo);
            }
        }
        if (due.isEmpty()) {
            return;
        }

        if (!overlay.show(due)) {
            return;
        }
        for (Memo memo : due) {
            memo.lastShownAt = now;
        }
        store.save(all);
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
