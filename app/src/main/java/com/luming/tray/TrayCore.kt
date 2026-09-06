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
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class UsageStats(
    val balance: Double? = null,
    val todayCost: Double? = null,
    val requests: Long? = null,
    val totalTokens: Long? = null,
    val inputTokens: Long? = null,
    val outputTokens: Long? = null,
    val avgResponseSeconds: Double? = null,
    val rpm: Double? = null,
    val tpm: Double? = null,
    val updatedAt: Long = System.currentTimeMillis()
)

data class TrayConfig(
    val baseUrl: String = "https://lmyanyu.com/v1",
    val apiKey: String = "",
    val accessToken: String = "",
    val consoleCookie: String = "",
    val webAuthToken: String = "",
    val webRefreshToken: String = "",
    val webTokenExpiresAt: Long = 0L
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
            .putString("inputTokens", stats.inputTokens?.toString())
            .putString("outputTokens", stats.outputTokens?.toString())
            .putString("avgResponseSeconds", stats.avgResponseSeconds?.toString())
            .putString("rpm", stats.rpm?.toString())
            .putString("tpm", stats.tpm?.toString())
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
            inputTokens = p.getString("inputTokens", null)?.toLongOrNull(),
            outputTokens = p.getString("outputTokens", null)?.toLongOrNull(),
            avgResponseSeconds = p.getString("avgResponseSeconds", null)?.toDoubleOrNull(),
            rpm = p.getString("rpm", null)?.toDoubleOrNull(),
            tpm = p.getString("tpm", null)?.toDoubleOrNull(),
            updatedAt = updatedAt
        )
    }

    fun saveConfig(context: Context, config: TrayConfig) {
        prefs(context).edit()
            .putString("baseUrl", config.baseUrl.trim().ifBlank { TrayConfig().baseUrl })
            .putString("apiKey", config.apiKey.trim())
            .putString("accessToken", config.accessToken.trim())
            .putString("consoleCookie", config.consoleCookie.trim())
            .putString("webAuthToken", config.webAuthToken.trim())
            .putString("webRefreshToken", config.webRefreshToken.trim())
            .putLong("webTokenExpiresAt", config.webTokenExpiresAt)
            .apply()
    }

    fun loadConfig(context: Context): TrayConfig {
        val p = prefs(context)
        return TrayConfig(
            baseUrl = p.getString("baseUrl", null)?.takeIf { it.isNotBlank() } ?: TrayConfig().baseUrl,
            apiKey = p.getString("apiKey", "") ?: "",
            accessToken = p.getString("accessToken", "") ?: "",
            consoleCookie = p.getString("consoleCookie", "") ?: "",
            webAuthToken = p.getString("webAuthToken", "") ?: "",
            webRefreshToken = p.getString("webRefreshToken", "") ?: "",
            webTokenExpiresAt = p.getLong("webTokenExpiresAt", 0L)
        )
    }

    fun saveLastMessage(context: Context, message: String) {
        prefs(context).edit().putString("lastMessage", message).apply()
    }

    fun loadLastMessage(context: Context): String =
        prefs(context).getString("lastMessage", "") ?: ""
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

        val refresh = PendingIntent.getBroadcast(
            context,
            1,
            Intent(context, RefreshReceiver::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val title = stats?.balance?.let { "LuMing · 余额 ${money(it)}" } ?: "LuMing Tray"
        val text = stats?.let {
            "今日 ${it.todayCost?.let(::money) ?: "--"} · ${it.requests ?: "--"} 次 · ${it.totalTokens?.let(::tokens) ?: "--"} Token"
        } ?: "托盘已启动，等待首次刷新"

        val expanded = if (stats != null) {
            buildString {
                append("余额 ${stats.balance?.let(::money) ?: "--"}")
                append("\n今日 ${stats.todayCost?.let(::money) ?: "--"} · ${stats.requests ?: "--"} 次")
                append("\nToken ${stats.totalTokens?.let(::tokens) ?: "--"}")
                if (stats.inputTokens != null || stats.outputTokens != null) {
                    append("（入 ${stats.inputTokens?.let(::tokens) ?: "--"} / 出 ${stats.outputTokens?.let(::tokens) ?: "--"}）")
                }
                if (stats.rpm != null || stats.tpm != null) {
                    append("\n性能 ${stats.rpm?.let(::rate) ?: "--"} RPM · ${stats.tpm?.let(::rate) ?: "--"} TPM")
                }
                if (stats.avgResponseSeconds != null) {
                    append("\n平均响应 ${seconds(stats.avgResponseSeconds)}")
                }
                append("\n更新 ${time(stats.updatedAt)}")
            }
        } else {
            "打开 LuMing Tray，网页登录或填写凭据后刷新。"
        }

        val notification = Notification.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(Notification.BigTextStyle().bigText(expanded))
            .setContentIntent(open)
            .addAction(Notification.Action.Builder(Icon.createWithResource(context, android.R.drawable.ic_popup_sync), "刷新", refresh).build())
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .build()

        manager.notify(NOTIFICATION_ID, notification)
    }

    fun money(value: Double): String =
        String.format(Locale.US, "$%.4f", value).trimEnd('0').trimEnd('.')

    fun tokens(value: Long): String = when {
        value >= 1_000_000 -> String.format(Locale.US, "%.2fM", value / 1_000_000.0).trimEnd('0').trimEnd('.')
        value >= 1_000 -> String.format(Locale.US, "%.1fK", value / 1_000.0).trimEnd('0').trimEnd('.')
        else -> value.toString()
    }

    fun rate(value: Double): String = when {
        value >= 1_000_000 -> String.format(Locale.US, "%.2fM", value / 1_000_000.0).trimEnd('0').trimEnd('.')
        value >= 1_000 -> String.format(Locale.US, "%.1fK", value / 1_000.0).trimEnd('0').trimEnd('.')
        value % 1.0 == 0.0 -> value.toLong().toString()
        else -> String.format(Locale.US, "%.2f", value).trimEnd('0').trimEnd('.')
    }

    fun seconds(value: Double): String =
        if (value >= 10) String.format(Locale.US, "%.2fs", value) else String.format(Locale.US, "%.2fs", value)

    private fun time(timestamp: Long): String =
        SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(timestamp))
}
