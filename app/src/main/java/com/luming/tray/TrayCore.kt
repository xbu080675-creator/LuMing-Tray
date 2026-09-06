package com.luming.tray

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import java.util.Locale

data class UsageStats(
    val balance: Double? = null,
    val todayCost: Double? = null,
    val requests: Long? = null,
    val totalTokens: Long? = null,
    val inputTokens: Long? = null,
    val outputTokens: Long? = null,
    val avgResponseSeconds: Double? = null,
    val updatedAt: Long = System.currentTimeMillis()
)

object TrayStore {
    private const val NAME = "luming_tray"
    private fun prefs(context: Context) = context.getSharedPreferences(NAME, Context.MODE_PRIVATE)

    fun saveStats(context: Context, stats: UsageStats) {
        prefs(context).edit()
            .putString("balance", stats.balance?.toString())
            .putString("todayCost", stats.todayCost?.toString())
            .putString("requests", stats.requests?.toString())
            .putString("totalTokens", stats.totalTokens?.toString())
            .putLong("updatedAt", stats.updatedAt)
            .apply()
    }

    fun loadStats(context: Context): UsageStats? {
        val p = prefs(context)
        val updatedAt = p.getLong("updatedAt", 0L)
        if (updatedAt == 0L) return null
        return UsageStats(
            balance = p.getString("balance", null)?.toDoubleOrNull(),
            todayCost = p.getString("todayCost", null)?.toDoubleOrNull(),
            requests = p.getString("requests", null)?.toLongOrNull(),
            totalTokens = p.getString("totalTokens", null)?.toLongOrNull(),
            updatedAt = updatedAt
        )
    }
}

object TrayNotification {
    private const val CHANNEL_ID = "luming_usage"
    private const val NOTIFICATION_ID = 1001

    fun show(context: Context, stats: UsageStats? = TrayStore.loadStats(context)) {
        if (Build.VERSION.SDK_INT >= 33 &&
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return

        val manager = context.getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 26) {
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "LuMing API 用量", NotificationManager.IMPORTANCE_LOW).apply {
                    description = "显示 LuMing API 的余额、消费和 Token"
                    setShowBadge(false)
                }
            )
        }

        val open = PendingIntent.getActivity(
            context,
            0,
            Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val title = stats?.balance?.let { "LuMing · 余额 ${money(it)}" } ?: "LuMing Tray"
        val text = stats?.let {
            "今日 ${it.todayCost?.let(::money) ?: "--"} · ${it.requests ?: "--"} 次 · ${it.totalTokens?.let(::tokens) ?: "--"} Token"
        } ?: "托盘已启动，等待接入统计接口"

        val notification = Notification.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .build()

        manager.notify(NOTIFICATION_ID, notification)
    }

    fun money(value: Double): String = String.format(Locale.US, "$%.4f", value).trimEnd('0').trimEnd('.')

    fun tokens(value: Long): String = when {
        value >= 1_000_000 -> String.format(Locale.US, "%.2fM", value / 1_000_000.0).trimEnd('0').trimEnd('.')
        value >= 1_000 -> String.format(Locale.US, "%.1fK", value / 1_000.0).trimEnd('0').trimEnd('.')
        else -> value.toString()
    }
}
