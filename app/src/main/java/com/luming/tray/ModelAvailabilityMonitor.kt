package com.luming.tray

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.drawable.Icon
import android.os.Build
import org.json.JSONObject

/** Persists model-monitor state and runtime health across process/service restarts. */
object ModelAvailabilityMonitor {
    private const val PREFS = "luming_model_availability_monitor_v1"
    private const val CHANNEL_ID = "luming_model_availability_alert"
    private const val NOTIFICATION_ID = 1301
    private const val KEY_STATES = "states"
    private const val KEY_INITIALIZED = "initialized"
    private const val KEY_LAST_ATTEMPT_AT = "last_attempt_at"
    private const val KEY_LAST_SUCCESS_AT = "last_success_at"
    private const val KEY_LAST_ERROR = "last_error"
    private const val KEY_CONSECUTIVE_FAILURES = "consecutive_failures"

    fun lastAttemptAt(context: Context): Long =
        prefs(context).getLong(KEY_LAST_ATTEMPT_AT, 0L)

    fun lastSuccessAt(context: Context): Long =
        prefs(context).getLong(KEY_LAST_SUCCESS_AT, 0L)

    fun lastError(context: Context): String =
        prefs(context).getString(KEY_LAST_ERROR, "").orEmpty()

    fun consecutiveFailures(context: Context): Int =
        prefs(context).getInt(KEY_CONSECUTIVE_FAILURES, 0)

    fun markAttempt(context: Context, at: Long = System.currentTimeMillis()) {
        prefs(context).edit().putLong(KEY_LAST_ATTEMPT_AT, at).apply()
    }

    fun markFailure(context: Context, message: String, at: Long = System.currentTimeMillis()) {
        val p = prefs(context)
        val failures = (p.getInt(KEY_CONSECUTIVE_FAILURES, 0) + 1).coerceAtMost(10_000)
        p.edit()
            .putLong(KEY_LAST_ATTEMPT_AT, at)
            .putString(KEY_LAST_ERROR, message.take(180))
            .putInt(KEY_CONSECUTIVE_FAILURES, failures)
            .apply()
    }

    fun process(context: Context, monitors: List<ModelMonitor>, at: Long = System.currentTimeMillis()) {
        if (monitors.isEmpty()) return
        val p = prefs(context)
        val previous = try {
            JSONObject(p.getString(KEY_STATES, "{}") ?: "{}")
        } catch (_: Exception) {
            JSONObject()
        }
        val initialized = p.getBoolean(KEY_INITIALIZED, false)
        val current = JSONObject()
        val worsened = mutableListOf<ModelMonitor>()

        monitors.forEach { monitor ->
            val key = monitor.id.toString()
            val next = normalize(monitor.status)
            current.put(key, next)
            val old = normalize(previous.optString(key, ""))
            if (initialized && old.isNotBlank() && severity(next) > severity(old) && severity(next) > 0) {
                worsened += monitor
            }
        }

        p.edit()
            .putString(KEY_STATES, current.toString())
            .putBoolean(KEY_INITIALIZED, true)
            .putLong(KEY_LAST_SUCCESS_AT, at)
            .putLong(KEY_LAST_ATTEMPT_AT, at)
            .remove(KEY_LAST_ERROR)
            .putInt(KEY_CONSECUTIVE_FAILURES, 0)
            .apply()

        if (worsened.isNotEmpty() && canNotify(context)) {
            ensureChannel(context)
            context.getSystemService(NotificationManager::class.java)
                .notify(NOTIFICATION_ID, buildNotification(context, worsened))
        }
    }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun buildNotification(context: Context, changed: List<ModelMonitor>): Notification {
        val open = PendingIntent.getActivity(
            context,
            31,
            Intent(context, ModelAvailabilityActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val title = if (changed.size == 1) {
            "LuMing · ${changed.first().model.ifBlank { changed.first().name }} 状态下降"
        } else {
            "LuMing · ${changed.size} 个模型状态下降"
        }
        val text = changed.take(4).joinToString(" · ") {
            "${it.model.ifBlank { it.name }} ${statusLabel(it.status)}"
        } + if (changed.size > 4) " · …" else ""

        return Notification.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_warning)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(Notification.BigTextStyle().bigText(text))
            .setContentIntent(open)
            .addAction(
                Notification.Action.Builder(
                    Icon.createWithResource(context, android.R.drawable.ic_menu_view),
                    "查看模型状态",
                    open
                ).build()
            )
            .setAutoCancel(true)
            .setOnlyAlertOnce(false)
            .build()
    }

    private fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT >= 26) {
            context.getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    "LuMing 模型可用性提醒",
                    NotificationManager.IMPORTANCE_HIGH
                ).apply {
                    description = "当已监控模型从正常状态下降为降级、故障或错误时提醒"
                }
            )
        }
    }

    private fun normalize(status: String): String = status.trim().lowercase()

    private fun severity(status: String): Int = when (normalize(status)) {
        "operational", "healthy", "normal" -> 0
        "degraded", "warning" -> 1
        "failed", "critical" -> 2
        "error" -> 3
        else -> 0
    }

    private fun statusLabel(status: String): String = when (normalize(status)) {
        "operational", "healthy", "normal" -> "正常"
        "degraded", "warning" -> "降级"
        "failed", "critical" -> "故障"
        "error" -> "错误"
        else -> status.ifBlank { "未知" }
    }

    private fun canNotify(context: Context): Boolean =
        Build.VERSION.SDK_INT < 33 ||
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
}
