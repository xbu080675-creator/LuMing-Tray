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

/** Alerts when total API spending departs materially from the user's own recent baseline. */
object SpendingAnomalyAlert {
    private const val CHANNEL_ID = "luming_spending_anomaly"
    private const val NOTIFICATION_ID = 1201
    private const val PREFS = "luming_spending_anomaly_state"
    private const val BURST_COOLDOWN_MS = 4L * 60L * 60L * 1000L

    fun evaluate(context: Context, stats: UsageStats) {
        val config = TrayStore.loadConfig(context)
        if (!config.spendingAlertEnabled) return
        val todayCost = stats.todayCost ?: return

        val anomaly = UsageHistory.detectSpendingAnomaly(
            context = context,
            todayCost = todayCost,
            dailyMultiplier = config.spendingAlertDailyMultiplier,
            burstMultiplier = config.spendingAlertBurstMultiplier
        ) ?: return

        val state = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val now = System.currentTimeMillis()
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
        val text = anomaly.message

        return Notification.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_warning)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(Notification.BigTextStyle().bigText(text))
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
                    description = "消费速度或当日累计消费明显偏离近期基线时提醒"
                }
            )
        }
    }

    private fun canNotify(context: Context): Boolean =
        Build.VERSION.SDK_INT < 33 ||
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
}
