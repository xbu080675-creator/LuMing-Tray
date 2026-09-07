package com.luming.tray

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Settings

class RefreshReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val pending = goAsync()
        runCatching {
            UsageClient.refresh(context) {
                runCatching { TrayNotification.show(context) }
                pending.finish()
            }
        }.onFailure {
            pending.finish()
        }
    }
}

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action ?: return
        if (
            action != Intent.ACTION_BOOT_COMPLETED &&
            action != Intent.ACTION_MY_PACKAGE_REPLACED &&
            action != ACTION_RESTORE_RUNTIME
        ) return

        val pending = goAsync()
        Thread {
            try {
                runCatching { TrayNotification.show(context) }
                runCatching { TrayScheduler.ensure(context) }
                val config = runCatching { TrayStore.loadConfig(context) }.getOrNull() ?: return@Thread

                if (
                    config.realtimeEnabled ||
                    config.persistentNotificationEnabled ||
                    config.floatingEnabled ||
                    config.webAuthToken.isNotBlank() ||
                    config.webRefreshToken.isNotBlank()
                ) {
                    runCatching { RealtimeUsageService.start(context) }
                }
                if (config.floatingEnabled && Settings.canDrawOverlays(context)) {
                    runCatching { FloatingTrayService.start(context) }
                }
            } finally {
                pending.finish()
            }
        }.apply {
            name = "LuMing-RuntimeRestore"
            start()
        }
    }

    companion object {
        const val ACTION_RESTORE_RUNTIME = "com.luming.tray.action.RESTORE_RUNTIME"
    }
}
