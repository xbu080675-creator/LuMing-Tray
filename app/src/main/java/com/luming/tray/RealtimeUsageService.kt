package com.luming.tray

import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager

/**
 * Long-lived foreground monitor.
 *
 * The service now supervises its own worker. A transient network/JSON/provider exception must not
 * permanently kill the polling thread while leaving `running = true`; that state looked alive to
 * Android but silently stopped refreshing until the process was recreated.
 */
class RealtimeUsageService : Service() {
    @Volatile private var running = false
    @Volatile private var worker: Thread? = null
    @Volatile private var workerGeneration = 0
    @Volatile private var lastHeartbeatAt = 0L
    @Volatile private var foregroundReady = false

    private val watchdogHandler = Handler(Looper.getMainLooper())
    private val watchdog = object : Runnable {
        override fun run() {
            if (!running) return
            runCatching {
                if (!shouldKeepRunning()) {
                    stopSelf()
                    return
                }

                val now = System.currentTimeMillis()
                val dead = worker?.isAlive != true
                val stale = lastHeartbeatAt > 0L && now - lastHeartbeatAt > WORKER_STALE_MS
                if (dead || stale) startWorker(force = true)
            }
            if (running) watchdogHandler.postDelayed(this, WATCHDOG_INTERVAL_MS)
        }
    }

    override fun onCreate() {
        super.onCreate()
        try {
            TrayNotification.ensureChannel(this)
            startForeground(
                TrayNotification.NOTIFICATION_ID,
                TrayNotification.buildNotification(this, TrayStore.loadStats(this))
            )
            foregroundReady = true
        } catch (_: Exception) {
            // Do not take the whole app process down if a vendor rejects the FGS notification.
            foregroundReady = false
            stopSelf()
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!foregroundReady) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (!shouldKeepRunning()) {
            stopSelf()
            return START_NOT_STICKY
        }

        running = true
        startWorker(force = false)
        watchdogHandler.removeCallbacks(watchdog)
        watchdogHandler.postDelayed(watchdog, WATCHDOG_INTERVAL_MS)
        return START_STICKY
    }

    override fun onDestroy() {
        running = false
        workerGeneration += 1
        watchdogHandler.removeCallbacks(watchdog)
        worker?.interrupt()
        worker = null
        foregroundReady = false
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    @Synchronized
    private fun startWorker(force: Boolean) {
        if (!running) return
        val current = worker
        if (!force && current?.isAlive == true) return

        if (force) current?.interrupt()
        workerGeneration += 1
        val generation = workerGeneration
        lastHeartbeatAt = System.currentTimeMillis()
        worker = Thread({ runLoop(generation) }, "LuMing-Realtime-$generation").apply { start() }
    }

    private fun runLoop(generation: Int) {
        var unchangedRounds = 0
        var nextUsageAt = 0L
        var nextModelAt = 0L
        var nextTrayRefreshAt = 0L

        while (running && generation == workerGeneration) {
            lastHeartbeatAt = System.currentTimeMillis()

            try {
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
                        try {
                            val before = TrayStore.loadStats(this)
                            val result = UsageClient.refreshBlocking(this)
                            val after = result.stats
                            val changed = materiallyChanged(before, after)
                            unchangedRounds = if (changed) 0 else unchangedRounds + 1
                            if (result.success && after != null) {
                                runCatching { TrayNotification.show(this, after) }
                            }

                            val interactive = getSystemService(PowerManager::class.java).isInteractive
                            nextUsageAt = System.currentTimeMillis() +
                                nextUsageDelayMs(interactive, unchangedRounds, result.success)
                        } catch (_: Exception) {
                            // A single provider/network/parser failure should only delay this job.
                            unchangedRounds = 0
                            val interactive = runCatching {
                                getSystemService(PowerManager::class.java).isInteractive
                            }.getOrDefault(false)
                            nextUsageAt = System.currentTimeMillis() + if (interactive) 30_000L else 120_000L
                        }
                    }
                } else {
                    nextUsageAt = Long.MAX_VALUE
                    unchangedRounds = 0
                }

                lastHeartbeatAt = System.currentTimeMillis()
                now = System.currentTimeMillis()

                if (canMonitorModels) {
                    if (nextModelAt == Long.MAX_VALUE || nextModelAt == 0L) {
                        val lastAttempt = runCatching { ModelAvailabilityMonitor.lastAttemptAt(this) }.getOrDefault(0L)
                        nextModelAt = maxOf(now, lastAttempt + MODEL_MONITOR_INTERVAL_MS)
                    }
                    if (now >= nextModelAt) {
                        try {
                            ModelAvailabilityClient.loadSummaryBlocking(this)
                        } catch (_: Exception) {
                            // Isolate model monitoring from usage polling and the service lifetime.
                        }
                        nextModelAt = System.currentTimeMillis() + MODEL_MONITOR_INTERVAL_MS
                    }
                } else {
                    nextModelAt = Long.MAX_VALUE
                }

                lastHeartbeatAt = System.currentTimeMillis()
                now = System.currentTimeMillis()

                if (nextTrayRefreshAt == 0L || now >= nextTrayRefreshAt) {
                    runCatching { TrayNotification.show(this, TrayStore.loadStats(this)) }
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
            } catch (_: Exception) {
                // Supervisor barrier: never let one iteration silently kill the worker forever.
                lastHeartbeatAt = System.currentTimeMillis()
                try {
                    Thread.sleep(LOOP_ERROR_BACKOFF_MS)
                } catch (_: InterruptedException) {
                    return
                }
            }
        }
    }

    private fun shouldKeepRunning(): Boolean {
        val config = TrayStore.loadConfig(this)
        return config.persistentNotificationEnabled ||
            (config.realtimeEnabled && hasCredential(config)) ||
            hasWebCredential(config)
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
        for (value in values) if (value < result) result = value
        return result
    }

    companion object {
        private const val MODEL_MONITOR_INTERVAL_MS = 60_000L
        private const val IDLE_NOTIFICATION_REFRESH_MS = 5L * 60L * 1000L
        private const val MIN_SLEEP_MS = 1_000L
        private const val MAX_SLEEP_MS = 60_000L
        private const val LOOP_ERROR_BACKOFF_MS = 5_000L
        private const val WATCHDOG_INTERVAL_MS = 30_000L
        private const val WORKER_STALE_MS = 3L * 60L * 1000L

        fun start(context: Context) {
            val config = TrayStore.loadConfig(context)
            val shouldRun = config.persistentNotificationEnabled ||
                (config.realtimeEnabled && hasCredential(config)) ||
                hasWebCredential(config)
            if (!shouldRun) return

            val intent = Intent(context, RealtimeUsageService::class.java)
            try {
                if (Build.VERSION.SDK_INT >= 26) context.startForegroundService(intent)
                else context.startService(intent)
            } catch (_: Exception) {
                // WorkManager is the low-frequency fallback; the next foreground app resume retries.
                runCatching { TrayScheduler.ensure(context) }
            }
        }

        fun stop(context: Context) {
            val config = TrayStore.loadConfig(context)
            if (config.persistentNotificationEnabled || hasWebCredential(config)) start(context)
            else context.stopService(Intent(context, RealtimeUsageService::class.java))
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
