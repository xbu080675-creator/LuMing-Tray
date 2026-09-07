package com.luming.tray

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Settings

class RefreshReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val pending = goAsync()
        UsageClient.refresh(context) {
            runCatching { TrayNotification.show(context) }
            pending.finish()
        }
    }
}

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action ?: return
        if (action != Intent.ACTION_BOOT_COMPLETED && action != Intent.ACTION_MY_PACKAGE_REPLACED) return

        runCatching { TrayNotification.show(context) }
        runCatching { TrayScheduler.ensure(context) }
        val config = TrayStore.loadConfig(context)
        if (
            config.realtimeEnabled ||
            config.persistentNotificationEnabled ||
            config.floatingEnabled ||
            config.webAuthToken.isNotBlank() ||
            config.webRefreshToken.isNotBlank()
        ) {
            RealtimeUsageService.start(context)
        }
        if (config.floatingEnabled && Settings.canDrawOverlays(context)) {
            FloatingTrayService.start(context)
        }
    }
}
