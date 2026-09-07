package com.luming.tray

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Settings

class RefreshReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val pending = goAsync()
        UsageClient.refresh(context) {
            TrayNotification.show(context)
            pending.finish()
        }
    }
}

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action == Intent.ACTION_BOOT_COMPLETED) {
            TrayNotification.show(context)
            TrayScheduler.ensure(context)
            val config = TrayStore.loadConfig(context)
            if (config.realtimeEnabled || config.persistentNotificationEnabled) {
                RealtimeUsageService.start(context)
            }
            if (config.floatingEnabled && Settings.canDrawOverlays(context)) {
                FloatingTrayService.start(context)
            }
        }
    }
}
