package com.luming.tray

import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import kotlin.math.min

/**
 * Foreground service shared by three long-lived jobs:
 * 1) the system ongoing notification,
 * 2) adaptive API-usage polling,
 * 3) lightweight model-availability polling every ~60 seconds after web login.
 *
 * Model monitoring deliberately uses only the channel-monitor summary endpoint in background;
 * the heavier 7/15/30-day detail calls remain foreground UI work.
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
        val canRefreshUsage = config.realtimeEnabled && hasCredential(config)
        val canMonitorModels = hasWebCredential(config)
        if (!config.persistentNotificationEnabled && !canRefreshUsage && !canMonitorModels) {
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
        var nextUsageAt = 0L
        var nextModelAt = 0L
        var nextTrayRefreshAt = 0L

        while (running) {
            val config = TrayStore.loadConfig(this)
            val canRefreshUsage = config.realtimeEnabled && hasCredential(config)
            val canMonitorModels = hasWebCredential(config)
            if (!config.persistentNotificationEnabled && !canRefreshUsage && !canMonitorModels) {
                stopSelf()
                return
            }

            var now = System.currentTimeMillis()

            if (canRefreshUsage) {
                if (nextUsageAt == Long.MAX_VALUE) nextUsageAt = now
                if (now >= nextUsageAt) {
                    val before = TrayStore.loadStats(this)
                    val result = UsageClient.refreshBlocking(this)
                    val after = result.stats
                    val changed = materiallyChanged(before, after)
                    unchangedRounds = if (changed) 0 else unchangedRounds + 1
                    if (result.success && after != null) TrayNotification.show(this, after)

                    val interactive = getSystemService(PowerManager::class.java).isInteractive
                    nextUsageAt = System.currentTimeMillis() +
                        nextUsageDelayMs(interactive, unchangedRounds, result.success)
                }
            } else {
                nextUsageAt = Long.MAX_VALUE
                unchangedRounds = 0
            }

            now = System.currentTimeMillis()
            if (canMonitorModels) {
                if (nextModelAt == Long.MAX_VALUE || nextModelAt == 0L) {
                    val lastAttempt = ModelAvailabilityMonitor.lastAttemptAt(this)
                    nextModelAt = maxOf(now, lastAttempt + MODEL_MONITOR_INTERVAL_MS)
                }
                if (now >= nextModelAt) {
                    ModelAvailabilityClient.loadSummaryBlocking(this)
                    nextModelAt = System.currentTimeMillis() + MODEL_MONITOR_INTERVAL_MS
                }
            } else {
                nextModelAt = Long.MAX_VALUE
            }

            now = System.currentTimeMillis()
            if (nextTrayRefreshAt == 0L || now >= nextTrayRefreshAt) {
                TrayNotification.show(this, TrayStore.loadStats(this))
                nextTrayRefreshAt = now + IDLE_NOTIFICATION_REFRESH_MS
            }

            val wakeAt = minOfFinite(
                nextUsageAt,
                nextModelAt,
                nextTrayRefreshAt,
                now + MAX_SLEEP_MS
            )
            val sleepMs = (wakeAt - System.currentTimeMillis())
                .coerceIn(MIN_SLEEP_MS, MAX_SLEEP_MS)
            try {
                Thread.sleep(sleepMs)
            } catch (_: InterruptedException) {
                return
            }
        }
    }

    private fun nextUsageDelayMs(interactive: Boolean, unchangedRounds: Int, success: Boolean): Long {
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

    private fun minOfFinite(vararg values: Long): Long {
        var result = Long.MAX_VALUE
        for (value in values) {
            if (value < result) result = value
        }
        return result
    }

    companion object {
        private const val MODEL_MONITOR_INTERVAL_MS = 60_000L
        private const val IDLE_NOTIFICATION_REFRESH_MS = 5L * 60L * 1000L
        private const val MIN_SLEEP_MS = 1_000L
        private const val MAX_SLEEP_MS = 60_000L

        fun start(context: Context) {
            val config = TrayStore.loadConfig(context)
            val shouldRun = config.persistentNotificationEnabled ||
                (config.realtimeEnabled && hasCredential(config)) ||
                hasWebCredential(config)
            if (!shouldRun) return

            val intent = Intent(context, RealtimeUsageService::class.java)
            try {
                if (Build.VERSION.SDK_INT >= 26) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
            } catch (_: Exception) {
                // WorkManager remains the low-frequency usage fallback. Android does not allow
                // one-minute periodic WorkManager jobs, so model monitoring resumes with the FGS.
                TrayScheduler.ensure(context)
            }
        }

        fun stop(context: Context) {
            val config = TrayStore.loadConfig(context)
            if (config.persistentNotificationEnabled || hasWebCredential(config)) {
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

        private fun hasWebCredential(config: TrayConfig): Boolean =
            config.webAuthToken.isNotBlank() || config.webRefreshToken.isNotBlank()
    }
}
