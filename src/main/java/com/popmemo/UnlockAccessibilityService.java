package com.popmemo;

import android.accessibilityservice.AccessibilityService;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
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
    private String homePackage;
    private long lastFlash;

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
        if (event.getEventType() != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            return;
        }
        CharSequence pkg = event.getPackageName();
        if (pkg == null) {
            return;
        }
        if (homePackage == null) {
            Intent home = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME);
            ResolveInfo ri = getPackageManager()
                    .resolveActivity(home, PackageManager.MATCH_DEFAULT_ONLY);
            homePackage = ri == null || ri.activityInfo == null
                    ? "" : ri.activityInfo.packageName;
        }
        // 回到桌面轻闪一下球，提醒它还在；30 秒内只闪一次，别烦人
        long now = System.currentTimeMillis();
        if (homePackage.equals(pkg.toString()) && now - lastFlash > 30000) {
            lastFlash = now;
            FloatingBall.flash();
        }
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
