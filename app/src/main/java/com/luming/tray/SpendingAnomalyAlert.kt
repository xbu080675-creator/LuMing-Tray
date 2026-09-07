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
import kotlin.math.max

enum class SpendingAnomalyKind { DAILY_PACE, BURST }

data class SpendingAnomaly(
    val kind: SpendingAnomalyKind,
    val message: String
)

/** Alerts when total API spending departs materially from the user's own recent baseline. */
object SpendingAnomalyAlert {
    private const val CHANNEL_ID = "luming_spending_anomaly"
    private const val NOTIFICATION_ID = 1201
    private const val PREFS = "luming_spending_anomaly_state"
    private const val EVALUATION_INTERVAL_MS = 60_000L
    private const val BURST_COOLDOWN_MS = 4L * 60L * 60L * 1000L
    private const val MAX_BURST_WINDOW_MS = 15L * 60L * 1000L

    fun evaluate(context: Context, stats: UsageStats, previous: UsageStats?) {
        val config = TrayStore.loadConfig(context)
        if (!config.spendingAlertEnabled) return
        val todayCost = stats.todayCost ?: return
        val now = stats.updatedAt.takeIf { it > 0L } ?: System.currentTimeMillis()
        val state = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

        // Realtime mode may refresh every 10 seconds. Cost analytics does not need to scan the
        // local history database that often, so evaluate at most once per minute.
        val lastEvalAt = state.getLong("last_eval_at", 0L)
        if (lastEvalAt > 0L && now - lastEvalAt < EVALUATION_INTERVAL_MS) return
        val lastEvalCost = state.getString("last_eval_cost", null)?.toDoubleOrNull()
        state.edit()
            .putLong("last_eval_at", now)
            .putString("last_eval_cost", todayCost.toString())
            .apply()

        val local = UsageHistory.analyze(context, now)
        val priorDays = local.daily.dropLast(1).filter { it.hasData }
        val priorAverage = priorDays.takeIf { it.size >= 2 }?.map { it.cost }?.average()

        val referenceCost = lastEvalCost ?: previous?.todayCost
        val referenceAt = lastEvalAt.takeIf { it > 0L } ?: previous?.updatedAt
        val anomaly = dailyAnomaly(todayCost, priorAverage, config.spendingAlertDailyMultiplier)
            ?: burstAnomaly(
                todayCost,
                referenceCost,
                referenceAt,
                now,
                priorAverage,
                config.spendingAlertBurstMultiplier
            )
            ?: return

        val today = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date(now))
        val shouldNotify = when (anomaly.kind) {
            SpendingAnomalyKind.DAILY_PACE -> state.getString("daily_alert_day", "") != today
            SpendingAnomalyKind.BURST -> now - state.getLong("burst_alert_at", 0L) >= BURST_COOLDOWN_MS
        }
        if (!shouldNotify || !canNotify(context)) return

        ensureChannel(context)
        context.getSystemService(NotificationManager::class.java).notify(
            NOTIFICATION_ID,
            buildNotification(context, anomaly)
        )
        state.edit().apply {
            when (anomaly.kind) {
                SpendingAnomalyKind.DAILY_PACE -> putString("daily_alert_day", today)
                SpendingAnomalyKind.BURST -> putLong("burst_alert_at", now)
            }
        }.apply()
    }

    fun clear(context: Context) {
        context.getSystemService(NotificationManager::class.java).cancel(NOTIFICATION_ID)
    }

    private fun dailyAnomaly(
        todayCost: Double,
        priorAverage: Double?,
        multiplier: Double
    ): SpendingAnomaly? {
        val baseline = priorAverage?.takeIf { it > 0.0 } ?: return null
        val threshold = max(0.50, max(baseline * multiplier.coerceAtLeast(1.1), baseline + 0.25))
        if (todayCost < threshold) return null
        val percent = ((todayCost / baseline - 1.0) * 100.0).toInt().coerceAtLeast(0)
        return SpendingAnomaly(
            SpendingAnomalyKind.DAILY_PACE,
            "今日已消费 ${money(todayCost)}，比最近有记录日的日均 ${money(baseline)} 高约 $percent%。建议检查模型、分组与最近请求。"
        )
    }

    private fun burstAnomaly(
        todayCost: Double,
        referenceCost: Double?,
        referenceAt: Long?,
        now: Long,
        priorAverage: Double?,
        multiplier: Double
    ): SpendingAnomaly? {
        val previousCost = referenceCost ?: return null
        val previousAt = referenceAt ?: return null
        val elapsed = now - previousAt
        if (elapsed <= 0L || elapsed > MAX_BURST_WINDOW_MS) return null
        val delta = todayCost - previousCost
        if (delta <= 0.0) return null

        // A short burst must be material in absolute terms and relative to the user's normal day.
        // With the default 3x sensitivity this means at least $0.25, or ~15% of a normal day.
        val relativeFloor = priorAverage?.times(0.05 * multiplier.coerceAtLeast(1.0)) ?: 0.50
        val threshold = max(0.25, relativeFloor)
        if (delta < threshold) return null

        val minutes = max(1L, elapsed / 60_000L)
        val baselineText = priorAverage?.let {
            val share = (delta / it * 100.0).toInt().coerceAtLeast(1)
            "，约等于近期日均消费的 $share%"
        }.orEmpty()
        return SpendingAnomaly(
            SpendingAnomalyKind.BURST,
            "最近约 $minutes 分钟消费增加 ${money(delta)}$baselineText。若不是预期的大请求，建议立即查看消费分析。"
        )
    }

    private fun buildNotification(context: Context, anomaly: SpendingAnomaly): Notification {
        val analysis = PendingIntent.getActivity(
            context,
            21,
            Intent(context, AnalysisActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val recharge = PendingIntent.getActivity(
            context,
            22,
            Intent(context, RechargeActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val title = when (anomaly.kind) {
            SpendingAnomalyKind.DAILY_PACE -> "LuMing · 今日消费异常"
            SpendingAnomalyKind.BURST -> "LuMing · 短时消费突增"
        }
        return Notification.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_warning)
            .setContentTitle(title)
            .setContentText(anomaly.message)
            .setStyle(Notification.BigTextStyle().bigText(anomaly.message))
            .setContentIntent(analysis)
            .addAction(
                Notification.Action.Builder(
                    Icon.createWithResource(context, android.R.drawable.ic_menu_view),
                    "查看分析",
                    analysis
                ).build()
            )
            .addAction(
                Notification.Action.Builder(
                    Icon.createWithResource(context, android.R.drawable.ic_input_add),
                    "立即充值",
                    recharge
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
                    "LuMing 异常消费提醒",
                    NotificationManager.IMPORTANCE_HIGH
                ).apply {
                    description = "当日消费或短时消费明显偏离近期基线时提醒"
                }
            )
        }
    }

    private fun money(value: Double): String =
        String.format(Locale.US, "$%.4f", value).trimEnd('0').trimEnd('.')

    private fun canNotify(context: Context): Boolean =
        Build.VERSION.SDK_INT < 33 ||
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
}
