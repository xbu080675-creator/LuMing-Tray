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
    val webTokenExpiresAt: Long = 0L,
    val realtimeEnabled: Boolean = true,
    val persistentNotificationEnabled: Boolean = true,
    val floatingEnabled: Boolean = false,
    val floatingWidthDp: Int = 230,
    val floatingHeightDp: Int = 126,
    val floatingX: Int = 24,
    val floatingY: Int = 220,
    val balanceAlertEnabled: Boolean = true,
    val balanceAlertThreshold: Double = 0.50,
    val spendingAlertEnabled: Boolean = true,
    val spendingAlertDailyMultiplier: Double = 1.50,
    val spendingAlertBurstMultiplier: Double = 3.00
)

object TrayStore {
    private const val NAME = "luming_tray"
    private fun prefs(context: Context) = context.getSharedPreferences(NAME, Context.MODE_PRIVATE)

    fun saveStats(context: Context, stats: UsageStats) {
        val previous = loadStats(context)
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

        UsageHistory.recordSnapshot(context, stats)
        BalanceAlert.evaluate(context, stats)
        SpendingAnomalyAlert.evaluate(context, stats, previous)
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

    /**
     * Persists settings synchronously because callers often start/stop services immediately after
     * saving. Using apply() here allowed a newly-started service to read the previous configuration.
     * Secrets are rewritten only when their plaintext value actually changed, avoiding repeated
     * Keystore work while the floating window merely saves its geometry.
     */
    fun saveConfig(context: Context, config: TrayConfig): Boolean {
        val app = context.applicationContext
        val p = prefs(app)
        val oldFloating = p.getBoolean("floatingEnabled", false)

        fun saveSecret(name: String, value: String): Boolean {
            val next = value.trim()
            val current = SecureVault.get(app, name)
            return if (current == next) true else SecureVault.put(app, name, next)
        }

        val vaultOk = listOf(
            saveSecret("apiKey", config.apiKey),
            saveSecret("accessToken", config.accessToken),
            saveSecret("consoleCookie", config.consoleCookie),
            saveSecret("webAuthToken", config.webAuthToken),
            saveSecret("webRefreshToken", config.webRefreshToken)
        ).all { it }

        val normalizedBase = normalizeBaseUrl(config.baseUrl)
        val settingsOk = p.edit()
            .putString("baseUrl", normalizedBase)
            .remove("apiKey")
            .remove("accessToken")
            .remove("consoleCookie")
            .remove("webAuthToken")
            .remove("webRefreshToken")
            .putLong("webTokenExpiresAt", normalizeEpochMillis(config.webTokenExpiresAt))
            .putBoolean("realtimeEnabled", config.realtimeEnabled)
            .putBoolean("persistentNotificationEnabled", config.persistentNotificationEnabled)
            .putBoolean("floatingEnabled", config.floatingEnabled)
            .putInt("floatingWidthDp", config.floatingWidthDp)
            .putInt("floatingHeightDp", config.floatingHeightDp)
            .putInt("floatingX", config.floatingX)
            .putInt("floatingY", config.floatingY)
            .putBoolean("balanceAlertEnabled", config.balanceAlertEnabled)
            .putString("balanceAlertThreshold", config.balanceAlertThreshold.toString())
            .putBoolean("spendingAlertEnabled", config.spendingAlertEnabled)
            .putString("spendingAlertDailyMultiplier", config.spendingAlertDailyMultiplier.toString())
            .putString("spendingAlertBurstMultiplier", config.spendingAlertBurstMultiplier.toString())
            .putBoolean("secureVaultMigratedV1", vaultOk)
            .putBoolean("secureVaultWriteFailed", !vaultOk)
            .commit()

        val ok = vaultOk && settingsOk
        if (!ok) {
            saveLastMessage(app, "安全存储写入失败；旧凭据已保留，请重新打开应用检查授权")
        }

        // Floating mode is continuous work. Ensure the foreground runtime is attached as soon as
        // the setting changes, even when realtime/persistent toggles are otherwise off.
        if (settingsOk && oldFloating != config.floatingEnabled) {
            if (config.floatingEnabled) RealtimeUsageService.start(app)
            else RealtimeUsageService.stop(app)
        }
        return ok
    }

    fun loadConfig(context: Context): TrayConfig {
        val app = context.applicationContext
        val p = prefs(app)
        SecureVault.migrateLegacy(app, p)

        return TrayConfig(
            baseUrl = normalizeBaseUrl(p.getString("baseUrl", null) ?: TrayConfig().baseUrl),
            apiKey = SecureVault.get(app, "apiKey"),
            accessToken = SecureVault.get(app, "accessToken"),
            consoleCookie = SecureVault.get(app, "consoleCookie"),
            webAuthToken = SecureVault.get(app, "webAuthToken"),
            webRefreshToken = SecureVault.get(app, "webRefreshToken"),
            webTokenExpiresAt = normalizeEpochMillis(p.getLong("webTokenExpiresAt", 0L)),
            realtimeEnabled = p.getBoolean("realtimeEnabled", true),
            persistentNotificationEnabled = p.getBoolean("persistentNotificationEnabled", true),
            floatingEnabled = p.getBoolean("floatingEnabled", false),
            floatingWidthDp = p.getInt("floatingWidthDp", 230),
            floatingHeightDp = p.getInt("floatingHeightDp", 126),
            floatingX = p.getInt("floatingX", 24),
            floatingY = p.getInt("floatingY", 220),
            balanceAlertEnabled = p.getBoolean("balanceAlertEnabled", true),
            balanceAlertThreshold = p.getString("balanceAlertThreshold", "0.50")?.toDoubleOrNull() ?: 0.50,
            spendingAlertEnabled = p.getBoolean("spendingAlertEnabled", true),
            spendingAlertDailyMultiplier = p.getString("spendingAlertDailyMultiplier", "1.50")?.toDoubleOrNull() ?: 1.50,
            spendingAlertBurstMultiplier = p.getString("spendingAlertBurstMultiplier", "3.00")?.toDoubleOrNull() ?: 3.00
        )
    }

    fun secureVaultHealthy(context: Context): Boolean =
        !prefs(context.applicationContext).getBoolean("secureVaultWriteFailed", false) &&
            SecureVault.lastError(context.applicationContext).isBlank()

    fun saveBalanceAlertState(context: Context, state: Int) {
        prefs(context).edit().putInt("balanceAlertState", state).apply()
    }

    fun loadBalanceAlertState(context: Context): Int =
        prefs(context).getInt("balanceAlertState", 0)

    fun saveLastMessage(context: Context, message: String) {
        prefs(context).edit().putString("lastMessage", message).apply()
    }

    fun loadLastMessage(context: Context): String =
        prefs(context).getString("lastMessage", "") ?: ""

    private fun normalizeBaseUrl(value: String): String {
        val clean = value.trim().trimEnd('/').ifBlank { TrayConfig().baseUrl }
        return when {
            clean.endsWith("/api/v1") -> clean.removeSuffix("/api/v1") + "/v1"
            else -> clean
        }
    }

    private fun normalizeEpochMillis(value: Long): Long = when {
        value <= 0L -> 0L
        value < 1_000_000_000_000L -> value * 1000L
        else -> value
    }
}

object TrayNotification {
    const val CHANNEL_ID = "luming_usage"
    const val NOTIFICATION_ID = 1001

    fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT >= 26) {
            context.getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "LuMing API 用量", NotificationManager.IMPORTANCE_LOW).apply {
                    description = "常驻显示 LuMing API 的余额、消费和 Token"
                    setShowBadge(false)
                }
            )
        }
    }

    fun buildNotification(context: Context, stats: UsageStats? = TrayStore.loadStats(context)): Notification {
        ensureChannel(context)

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

        val recharge = PendingIntent.getActivity(
            context,
            2,
            Intent(context, RechargeActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val title = stats?.balance?.let { "LuMing · 余额 ${money(it)}" } ?: "LuMing Tray"
        val text = stats?.let {
            "今日 ${it.todayCost?.let(::money) ?: "--"} · ${it.requests ?: "--"} 次 · ${it.totalTokens?.let(::tokens) ?: "--"} Token"
        } ?: "托盘已启动，等待首次读取"

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
            "打开 LuMing Tray，网页登录后即可自动读取。"
        }

        val builder = Notification.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(Notification.BigTextStyle().bigText(expanded))
            .setContentIntent(open)
            .addAction(Notification.Action.Builder(Icon.createWithResource(context, android.R.drawable.ic_popup_sync), "刷新", refresh).build())
            .addAction(Notification.Action.Builder(Icon.createWithResource(context, android.R.drawable.ic_menu_view), "充值", recharge).build())
            .setOngoing(true)
            .setCategory(Notification.CATEGORY_SERVICE)
            .setLocalOnly(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)

        if (Build.VERSION.SDK_INT >= 31) {
            builder.setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE)
        }
        return builder.build()
    }

    fun show(context: Context, stats: UsageStats? = TrayStore.loadStats(context)) {
        if (Build.VERSION.SDK_INT >= 33 &&
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return
        context.getSystemService(NotificationManager::class.java)
            .notify(NOTIFICATION_ID, buildNotification(context, stats))
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
        String.format(Locale.US, "%.2fs", value)

    private fun time(timestamp: Long): String =
        SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(timestamp))
}
