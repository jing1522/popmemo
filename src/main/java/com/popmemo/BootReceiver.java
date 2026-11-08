package com.popmemo;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.provider.Settings;

/** 开机后自己把服务拉起来，免得每次重启都要开一次 App。 */
public class BootReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context context, Intent intent) {
        if (!Settings.canDrawOverlays(context)) {
            return;
        }
        try {
            context.startForegroundService(new Intent(context, UnlockService.class));
        } catch (Exception ignored) {
            // 新版安卓可能不让开机直接起前台服务，那就等用户打开 App。
        }
    }
}
