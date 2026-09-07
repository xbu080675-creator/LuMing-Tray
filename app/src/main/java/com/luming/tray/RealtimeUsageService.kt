package com.luming.tray

import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.os.PowerManager

/**
 * Foreground service that owns LuMing's system-level ongoing notification.
 * When realtime monitoring is enabled it also performs adaptive polling; when it is disabled,
 * the service stays idle so the notification remains a true foreground-service notification.
 */
class RealtimeUsageService : Service() {
    @Volatile
    private var running = false
    private var worker: Thread? = null

    override fun onCreate() {
        super.onCreate()
        TrayNotification.ensureChannel(this)
        startForeground(
            TrayNotification.NOTIFICATION_ID,
            TrayNotification.buildNotification(this, TrayStore.loadStats(this))
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val config = TrayStore.loadConfig(this)
        val canRefresh = config.realtimeEnabled && hasCredential(config)
        if (!config.persistentNotificationEnabled && !canRefresh) {
            stopSelf()
            return START_NOT_STICKY
        }

        if (!running) {
            running = true
            worker = Thread({ runLoop() }, "LuMing-Realtime").apply { start() }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        running = false
        worker?.interrupt()
        worker = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun runLoop() {
        var unchangedRounds = 0
        while (running) {
            val config = TrayStore.loadConfig(this)
            val canRefresh = config.realtimeEnabled && hasCredential(config)

            if (!config.persistentNotificationEnabled && !canRefresh) {
                stopSelf()
                return
            }

            if (!canRefresh) {
                // Keep the FGS alive only for the persistent system tray notification.
                TrayNotification.show(this, TrayStore.loadStats(this))
                try {
                    Thread.sleep(IDLE_NOTIFICATION_REFRESH_MS)
                } catch (_: InterruptedException) {
                    return
                }
                continue
            }

            val before = TrayStore.loadStats(this)
            val result = UsageClient.refreshBlocking(this)
            val after = result.stats
            val changed = materiallyChanged(before, after)
            unchangedRounds = if (changed) 0 else unchangedRounds + 1

            if (result.success && after != null) {
                TrayNotification.show(this, after)
            }

            val interactive = getSystemService(PowerManager::class.java).isInteractive
            val delay = nextDelayMs(interactive, unchangedRounds, result.success)
            try {
                Thread.sleep(delay)
            } catch (_: InterruptedException) {
                return
            }
        }
    }

    private fun nextDelayMs(interactive: Boolean, unchangedRounds: Int, success: Boolean): Long {
        if (!success) return if (interactive) 30_000L else 120_000L

        return if (interactive) {
            when {
                unchangedRounds < 12 -> 10_000L
                unchangedRounds < 36 -> 30_000L
                else -> 120_000L
            }
        } else {
            when {
                unchangedRounds < 10 -> 60_000L
                unchangedRounds < 30 -> 120_000L
                else -> 300_000L
            }
        }
    }

    private fun materiallyChanged(a: UsageStats?, b: UsageStats?): Boolean {
        if (a == null || b == null) return a != b
        return a.balance != b.balance ||
            a.todayCost != b.todayCost ||
            a.requests != b.requests ||
            a.totalTokens != b.totalTokens ||
            a.inputTokens != b.inputTokens ||
            a.outputTokens != b.outputTokens ||
            a.rpm != b.rpm ||
            a.tpm != b.tpm ||
            a.avgResponseSeconds != b.avgResponseSeconds
    }

    companion object {
        private const val IDLE_NOTIFICATION_REFRESH_MS = 5L * 60L * 1000L

        fun start(context: Context) {
            val config = TrayStore.loadConfig(context)
            val shouldRun = config.persistentNotificationEnabled ||
                (config.realtimeEnabled && hasCredential(config))
            if (!shouldRun) return

            val intent = Intent(context, RealtimeUsageService::class.java)
            try {
                if (Build.VERSION.SDK_INT >= 26) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
            } catch (_: Exception) {
                // WorkManager remains as the low-frequency fallback if the ROM blocks FGS startup.
                TrayScheduler.ensure(context)
            }
        }

        fun stop(context: Context) {
            // Realtime monitoring can be turned off without tearing down the permanent tray.
            val config = TrayStore.loadConfig(context)
            if (config.persistentNotificationEnabled) {
                start(context)
            } else {
                context.stopService(Intent(context, RealtimeUsageService::class.java))
            }
        }

        private fun hasCredential(config: TrayConfig): Boolean =
            config.webAuthToken.isNotBlank() ||
                config.webRefreshToken.isNotBlank() ||
                config.consoleCookie.isNotBlank() ||
                config.accessToken.isNotBlank() ||
                config.apiKey.isNotBlank()
    }
}
