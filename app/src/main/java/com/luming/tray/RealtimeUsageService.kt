package com.luming.tray

import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings

/**
 * Single long-lived foreground runtime for LuMing continuous work.
 *
 * It supervises the polling worker and also keeps the optional floating overlay attached. A dead
 * worker or independently reclaimed overlay is repaired without requiring the user to reopen the
 * dashboard.
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
                if (dead || stale) {
                    RuntimeHealth.markError(this@RealtimeUsageService, if (dead) "worker_dead" else "worker_stale")
                    startWorker(force = true)
                }
                syncFloatingOverlay()
                RuntimeHealth.markServiceHeartbeat(this@RealtimeUsageService, now)
            }.onFailure {
                RuntimeHealth.markError(this@RealtimeUsageService, "watchdog:${it.javaClass.simpleName}")
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
            RuntimeHealth.markServiceStarted(this)
        } catch (e: Exception) {
            foregroundReady = false
            RuntimeHealth.markError(this, "foreground:${e.javaClass.simpleName}")
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
        syncFloatingOverlay()
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
        RuntimeHealth.markServiceStopped(this)
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
        RuntimeHealth.markWorkerStarted(this, generation, lastHeartbeatAt)
        worker = Thread({ runLoop(generation) }, "LuMing-Realtime-$generation").apply { start() }
    }

    private fun runLoop(generation: Int) {
        var unchangedRounds = 0
        var nextUsageAt = 0L
        var nextModelAt = 0L
        var nextTrayRefreshAt = 0L

        while (running && generation == workerGeneration) {
            lastHeartbeatAt = System.currentTimeMillis()
            RuntimeHealth.markWorkerHeartbeat(this, generation, lastHeartbeatAt)

            try {
                val config = TrayStore.loadConfig(this)
                val canRefreshUsage = config.realtimeEnabled && hasCredential(config)
                val canMonitorModels = hasWebCredential(config)
                if (!config.persistentNotificationEnabled && !config.floatingEnabled &&
                    !canRefreshUsage && !canMonitorModels
                ) {
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
                                RuntimeHealth.markUsageSuccess(this)
                                runCatching { TrayNotification.show(this, after) }
                            } else {
                                RuntimeHealth.markUsageFailure(this, result.message)
                            }

                            val interactive = getSystemService(PowerManager::class.java).isInteractive
                            nextUsageAt = System.currentTimeMillis() +
                                nextUsageDelayMs(interactive, unchangedRounds, result.success)
                        } catch (e: Exception) {
                            unchangedRounds = 0
                            RuntimeHealth.markUsageFailure(this, e.javaClass.simpleName)
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
                RuntimeHealth.markWorkerHeartbeat(this, generation, lastHeartbeatAt)
                now = System.currentTimeMillis()

                if (canMonitorModels) {
                    if (nextModelAt == Long.MAX_VALUE || nextModelAt == 0L) {
                        val lastAttempt = runCatching { ModelAvailabilityMonitor.lastAttemptAt(this) }.getOrDefault(0L)
                        nextModelAt = maxOf(now, lastAttempt + MODEL_MONITOR_INTERVAL_MS)
                    }
                    if (now >= nextModelAt) {
                        try {
                            val report = ModelAvailabilityClient.loadSummaryBlocking(this)
                            if (report.monitors.isNotEmpty()) RuntimeHealth.markModelSuccess(this)
                            else RuntimeHealth.markModelFailure(this, report.message)
                        } catch (e: Exception) {
                            RuntimeHealth.markModelFailure(this, e.javaClass.simpleName)
                        }
                        nextModelAt = System.currentTimeMillis() + MODEL_MONITOR_INTERVAL_MS
                    }
                } else {
                    nextModelAt = Long.MAX_VALUE
                }

                lastHeartbeatAt = System.currentTimeMillis()
                RuntimeHealth.markWorkerHeartbeat(this, generation, lastHeartbeatAt)
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
            } catch (e: Exception) {
                RuntimeHealth.markError(this, "loop:${e.javaClass.simpleName}")
                lastHeartbeatAt = System.currentTimeMillis()
                try {
                    Thread.sleep(LOOP_ERROR_BACKOFF_MS)
                } catch (_: InterruptedException) {
                    return
                }
            }
        }
    }

    private fun syncFloatingOverlay() {
        val config = TrayStore.loadConfig(this)
        if (config.floatingEnabled && Settings.canDrawOverlays(this)) {
            runCatching { startService(Intent(this, FloatingTrayService::class.java)) }
                .onFailure { RuntimeHealth.markError(this, "overlay_start:${it.javaClass.simpleName}") }
        } else {
            runCatching { stopService(Intent(this, FloatingTrayService::class.java)) }
        }
    }

    private fun shouldKeepRunning(): Boolean {
        val config = TrayStore.loadConfig(this)
        return config.persistentNotificationEnabled ||
            config.floatingEnabled ||
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
            val app = context.applicationContext
            val config = TrayStore.loadConfig(app)
            val shouldRun = config.persistentNotificationEnabled ||
                config.floatingEnabled ||
                (config.realtimeEnabled && hasCredential(config)) ||
                hasWebCredential(config)
            if (!shouldRun) return

            val intent = Intent(app, RealtimeUsageService::class.java)
            try {
                if (Build.VERSION.SDK_INT >= 26) app.startForegroundService(intent)
                else app.startService(intent)
            } catch (e: Exception) {
                RuntimeHealth.markError(app, "service_start:${e.javaClass.simpleName}")
                runCatching { TrayScheduler.ensure(app) }
            }
        }

        fun stop(context: Context) {
            val app = context.applicationContext
            val config = TrayStore.loadConfig(app)
            val shouldRun = config.persistentNotificationEnabled ||
                config.floatingEnabled ||
                (config.realtimeEnabled && hasCredential(config)) ||
                hasWebCredential(config)
            if (shouldRun) start(app)
            else app.stopService(Intent(app, RealtimeUsageService::class.java))
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

/** Lightweight persistent diagnostics for long-running behavior. */
object RuntimeHealth {
    private const val PREFS = "luming_runtime_health_v1"
    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun markServiceStarted(context: Context) {
        prefs(context).edit()
            .putBoolean("service_running", true)
            .putLong("service_started_at", System.currentTimeMillis())
            .remove("last_error")
            .apply()
    }

    fun markServiceStopped(context: Context) {
        prefs(context).edit()
            .putBoolean("service_running", false)
            .putLong("service_stopped_at", System.currentTimeMillis())
            .apply()
    }

    fun markServiceHeartbeat(context: Context, at: Long = System.currentTimeMillis()) {
        prefs(context).edit().putLong("service_heartbeat_at", at).apply()
    }

    fun markWorkerStarted(context: Context, generation: Int, at: Long) {
        prefs(context).edit()
            .putInt("worker_generation", generation)
            .putLong("worker_started_at", at)
            .putLong("worker_heartbeat_at", at)
            .apply()
    }

    fun markWorkerHeartbeat(context: Context, generation: Int, at: Long) {
        prefs(context).edit()
            .putInt("worker_generation", generation)
            .putLong("worker_heartbeat_at", at)
            .apply()
    }

    fun markUsageSuccess(context: Context) {
        prefs(context).edit()
            .putLong("usage_success_at", System.currentTimeMillis())
            .remove("usage_error")
            .apply()
    }

    fun markUsageFailure(context: Context, message: String) {
        prefs(context).edit()
            .putLong("usage_failure_at", System.currentTimeMillis())
            .putString("usage_error", message.take(180))
            .apply()
    }

    fun markModelSuccess(context: Context) {
        prefs(context).edit()
            .putLong("model_success_at", System.currentTimeMillis())
            .remove("model_error")
            .apply()
    }

    fun markModelFailure(context: Context, message: String) {
        prefs(context).edit()
            .putLong("model_failure_at", System.currentTimeMillis())
            .putString("model_error", message.take(180))
            .apply()
    }

    fun markError(context: Context, message: String) {
        prefs(context).edit()
            .putLong("last_error_at", System.currentTimeMillis())
            .putString("last_error", message.take(180))
            .apply()
    }

    fun summary(context: Context): String {
        val p = prefs(context)
        val running = p.getBoolean("service_running", false)
        val workerAt = p.getLong("worker_heartbeat_at", 0L)
        val usageAt = p.getLong("usage_success_at", 0L)
        val modelAt = p.getLong("model_success_at", 0L)
        val error = p.getString("last_error", "").orEmpty()
        return buildString {
            append(if (running) "Runtime ON" else "Runtime OFF")
            if (workerAt > 0L) append(" · worker ${age(workerAt)}")
            if (usageAt > 0L) append(" · usage ${age(usageAt)}")
            if (modelAt > 0L) append(" · model ${age(modelAt)}")
            if (error.isNotBlank()) append(" · $error")
        }
    }

    private fun age(at: Long): String {
        val seconds = ((System.currentTimeMillis() - at).coerceAtLeast(0L) / 1000L)
        return when {
            seconds < 60 -> "${seconds}s"
            seconds < 3600 -> "${seconds / 60}m"
            else -> "${seconds / 3600}h"
        }
    }
}
