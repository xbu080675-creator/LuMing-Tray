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

object BalanceAlert {
    private const val CHANNEL_ID = "luming_balance_alert"
    private const val NOTIFICATION_ID = 1101
    private const val STATE_NORMAL = 0
    private const val STATE_WARNING = 1
    private const val STATE_CRITICAL = 2

    fun evaluate(context: Context, stats: UsageStats) {
        val config = TrayStore.loadConfig(context)
        if (!config.balanceAlertEnabled) {
            clear(context)
            return
        }

        val balance = stats.balance ?: return
        val threshold = config.balanceAlertThreshold.coerceAtLeast(0.0)
        val nextState = when {
            balance <= 0.0 -> STATE_CRITICAL
            balance <= threshold -> STATE_WARNING
            else -> STATE_NORMAL
        }
        val previousState = TrayStore.loadBalanceAlertState(context)

        if (nextState == STATE_NORMAL) {
            if (previousState != STATE_NORMAL) {
                clear(context)
            }
            return
        }

        if (!canNotify(context)) return

        val shouldNotify = when (nextState) {
            STATE_CRITICAL -> previousState != STATE_CRITICAL
            STATE_WARNING -> previousState == STATE_NORMAL
            else -> false
        }
        if (!shouldNotify) return

        ensureChannel(context)
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.notify(
            NOTIFICATION_ID,
            buildNotification(context, balance, threshold, nextState)
        )
        TrayStore.saveBalanceAlertState(context, nextState)
    }

    fun clear(context: Context) {
        context.getSystemService(NotificationManager::class.java).cancel(NOTIFICATION_ID)
        TrayStore.saveBalanceAlertState(context, STATE_NORMAL)
    }

    private fun buildNotification(
        context: Context,
        balance: Double,
        threshold: Double,
        state: Int
    ): Notification {
        val open = PendingIntent.getActivity(
            context,
            11,
            Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val recharge = PendingIntent.getActivity(
            context,
            12,
            Intent(context, RechargeActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val critical = state == STATE_CRITICAL
        val title = if (critical) "LuMing · 余额不足" else "LuMing · 余额偏低"
        val text = if (critical) {
            "当前余额 ${TrayNotification.money(balance)}，建议立即充值。"
        } else {
            "当前余额 ${TrayNotification.money(balance)}，已低于预警值 ${TrayNotification.money(threshold)}。"
        }

        return Notification.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_warning)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(Notification.BigTextStyle().bigText(text))
            .setContentIntent(open)
            .addAction(
                Notification.Action.Builder(
                    Icon.createWithResource(context, android.R.drawable.ic_menu_view),
                    "查看用量",
                    open
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
            val manager = context.getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    "LuMing 余额预警",
                    NotificationManager.IMPORTANCE_HIGH
                ).apply {
                    description = "余额跌破设定阈值或余额不足时提醒"
                }
            )
        }
    }

    private fun canNotify(context: Context): Boolean =
        Build.VERSION.SDK_INT < 33 ||
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
}
