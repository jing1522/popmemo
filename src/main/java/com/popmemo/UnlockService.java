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
import android.net.Uri;
import android.os.IBinder;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * 常驻前台服务：监听「解锁完成」，解锁后把最该看见的几条摆出来。
 * 弹窗频率在设置里可调（默认每 5 次解锁弹一次），红卡不受限。
 * 这是兜底方案：用户开了无障碍服务之后，改由 UnlockAccessibilityService 监听，
 * 这个服务就会被停掉（常驻通知一起收走），省电。
 */
public class UnlockService extends Service {

    private static final String CHANNEL_ID = "unlock";

    /** 通知上的两个动作。 */
    public static final String ACTION_SNOOZE = "com.popmemo.SNOOZE";
    public static final String ACTION_UNSNOOZE = "com.popmemo.UNSNOOZE";

    private static final String PREF_SNOOZE_UNTIL = "snooze_until";
    private static final long SNOOZE_MS = 60 * 60 * 1000L;

    /** 「看网课」跳这里。换平台只改这一行。 */
    private static final String COURSE_URL = "https://www.bilibili.com/watchlater/";

    private BroadcastReceiver unlockReceiver;

    @Override
    public void onCreate() {
        super.onCreate();
        unlockReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                if (Intent.ACTION_USER_PRESENT.equals(intent.getAction())) {
                    DueShower.show(context, pending -> updateNotification(pending));
                }
            }
        };
        registerReceiver(unlockReceiver, new IntentFilter(Intent.ACTION_USER_PRESENT));
        FloatingBall.sync(this);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent == null ? null : intent.getAction();
        if (ACTION_SNOOZE.equals(action)) {
            prefs().edit().putLong(PREF_SNOOZE_UNTIL, System.currentTimeMillis() + SNOOZE_MS).apply();
        } else if (ACTION_UNSNOOZE.equals(action)) {
            prefs().edit().remove(PREF_SNOOZE_UNTIL).apply();
        }
        startForeground(1, buildNotification(DueShower.pendingOf(new MemoStore(this).load())));
        return START_STICKY;
    }

    private android.content.SharedPreferences prefs() {
        return getSharedPreferences("popmemo", MODE_PRIVATE);
    }

    private long snoozeUntil() {
        return prefs().getLong(PREF_SNOOZE_UNTIL, 0L);
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

        long until = snoozeUntil();
        boolean snoozed = until > System.currentTimeMillis();

        String text;
        if (snoozed) {
            String when = new SimpleDateFormat("HH:mm", Locale.getDefault()).format(new Date(until));
            text = "静默到 " + when + " · 还有 " + pending.size() + " 件没办";
        } else if (pending.isEmpty()) {
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

        Intent snooze = new Intent(this, UnlockService.class);
        snooze.setAction(snoozed ? ACTION_UNSNOOZE : ACTION_SNOOZE);
        PendingIntent snoozeIntent = PendingIntent.getService(this, 3, snooze,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        Intent course = new Intent(Intent.ACTION_VIEW, Uri.parse(COURSE_URL));
        course.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        PendingIntent courseIntent = PendingIntent.getActivity(this, 4, course,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        return new Notification.Builder(this, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
                .setContentTitle("PopMemo")
                .setContentText(text)
                .setContentIntent(openIntent)
                .addAction(android.R.drawable.ic_menu_add, "写一条", quickIntent)
                .addAction(android.R.drawable.ic_menu_view, "看网课", courseIntent)
                .addAction(android.R.drawable.ic_menu_close_clear_cancel,
                        snoozed ? "取消静默" : "静默 1 小时", snoozeIntent)
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
        // 浮层是共享的：这里不关，可能正由无障碍服务摆着。
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
