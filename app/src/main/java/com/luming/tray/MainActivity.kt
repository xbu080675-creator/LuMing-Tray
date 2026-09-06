package com.luming.tray

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

class MainActivity : Activity() {
    private lateinit var statusText: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(buildUi())
        requestNotificationPermissionIfNeeded()
        renderState()
    }

    override fun onResume() {
        super.onResume()
        renderState()
    }

    private fun buildUi(): View {
        val scroll = ScrollView(this).apply {
            setBackgroundColor(Color.rgb(244, 250, 250))
        }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(28), dp(20), dp(32))
        }

        root.addView(TextView(this).apply {
            text = "LuMing Tray"
            textSize = 28f
            setTextColor(Color.rgb(30, 38, 50))
            setTypeface(typeface, Typeface.BOLD)
        })
        root.addView(TextView(this).apply {
            text = "手机通知栏里的 API 用量托盘"
            textSize = 15f
            setTextColor(Color.rgb(100, 110, 122))
            setPadding(0, dp(6), 0, dp(22))
        })

        statusText = TextView(this).apply {
            textSize = 17f
            setTextColor(Color.rgb(41, 48, 61))
            setPadding(dp(18), dp(18), dp(18), dp(18))
            background = roundedCard()
        }
        root.addView(statusText, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ))

        root.addView(TextView(this).apply {
            text = "API 基地址\nhttps://lmyanyu.com/v1"
            textSize = 15f
            setTextColor(Color.rgb(70, 79, 92))
            setPadding(dp(18), dp(18), dp(18), dp(18))
            background = roundedCard()
        }, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(14) })

        root.addView(Button(this).apply {
            text = "启动 / 刷新托盘"
            isAllCaps = false
            setOnClickListener {
                requestNotificationPermissionIfNeeded()
                TrayNotification.show(this@MainActivity)
                renderState()
            }
        }, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            dp(52)
        ).apply { topMargin = dp(20) })

        root.addView(TextView(this).apply {
            text = "M0.1 先验证 APK、常驻通知和快捷设置磁贴能否正常运行。真实余额 / 消费 / Token 接口在下一步接入。"
            textSize = 13f
            setTextColor(Color.rgb(112, 120, 132))
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(8), dp(18), dp(8), 0)
        })

        scroll.addView(root)
        return scroll
    }

    private fun renderState() {
        val stats = TrayStore.loadStats(this)
        statusText.text = if (stats == null) {
            "托盘状态：已就绪\n\n余额    --\n今日消费    --\n今日请求    --\nToken    --"
        } else {
            "余额    ${stats.balance?.let(TrayNotification::money) ?: "--"}\n" +
                "今日消费    ${stats.todayCost?.let(TrayNotification::money) ?: "--"}\n" +
                "今日请求    ${stats.requests ?: "--"}\n" +
                "Token    ${stats.totalTokens?.let(TrayNotification::tokens) ?: "--"}"
        }
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQ_NOTIFICATION)
        } else {
            TrayNotification.show(this)
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_NOTIFICATION && grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) {
            TrayNotification.show(this)
        }
    }

    private fun roundedCard(): GradientDrawable = GradientDrawable().apply {
        setColor(Color.WHITE)
        cornerRadius = dp(20).toFloat()
        setStroke(dp(1), Color.rgb(229, 235, 238))
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    companion object {
        private const val REQ_NOTIFICATION = 10
    }
}
