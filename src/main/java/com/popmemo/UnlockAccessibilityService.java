package com.popmemo;

import android.accessibilityservice.AccessibilityService;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.provider.Settings;
import android.text.TextUtils;
import android.view.accessibility.AccessibilityEvent;

/**
 * 省电的解锁监听：不挂前台、没有常驻通知，
 * 靠系统对无障碍服务的保活撑着，进程不容易被杀。
 * 用户没开它的时候，UnlockService 前台服务兜底。
 */
public class UnlockAccessibilityService extends AccessibilityService {

    private BroadcastReceiver unlockReceiver;

    @Override
    public void onServiceConnected() {
        unlockReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                if (Intent.ACTION_USER_PRESENT.equals(intent.getAction())) {
                    DueShower.show(context, null);
                }
            }
        };
        registerReceiver(unlockReceiver, new IntentFilter(Intent.ACTION_USER_PRESENT));
        FloatingBall.sync(this);
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        // 不靠无障碍事件干活，挂着只是为了系统保活。
    }

    @Override
    public void onInterrupt() {
    }

    @Override
    public void onDestroy() {
        if (unlockReceiver != null) {
            unregisterReceiver(unlockReceiver);
            unlockReceiver = null;
        }
        super.onDestroy();
    }

    /** 用户有没有在系统设置里把我们这个无障碍服务打开。 */
    public static boolean isEnabled(Context context) {
        String enabled = Settings.Secure.getString(context.getContentResolver(),
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
        if (TextUtils.isEmpty(enabled)) {
            return false;
        }
        String full = context.getPackageName() + "/" + UnlockAccessibilityService.class.getName();
        String flat = context.getPackageName() + "/." + UnlockAccessibilityService.class.getSimpleName();
        for (String entry : enabled.split(":")) {
            if (entry.equalsIgnoreCase(full) || entry.equalsIgnoreCase(flat)) {
                return true;
            }
        }
        return false;
    }
}
