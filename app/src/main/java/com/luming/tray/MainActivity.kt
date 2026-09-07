package com.luming.tray

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.WindowInsets
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 0.17 dashboard shell.
 *
 * The home screen is intentionally a dashboard again, not a settings form. Provider connection,
 * compatibility credentials and thresholds live in SettingsActivity; account/security lives in
 * AccountActivity. This keeps the first screen readable even as provider features keep growing.
 */
class MainActivity : Activity() {
    private lateinit var balanceValueText: TextView
    private lateinit var todayCostValueText: TextView
    private lateinit var requestsValueText: TextView
    private lateinit var tokenValueText: TextView
    private lateinit var responseValueText: TextView
    private lateinit var ioValueText: TextView
    private lateinit var perfValueText: TextView
    private lateinit var updateValueText: TextView
    private lateinit var diagnosticText: TextView
    private lateinit var loginStateText: TextView
    private lateinit var refreshButton: Button
    private lateinit var realtimeButton: Button
    private lateinit var floatingButton: Button
    private lateinit var balanceAlertButton: Button

    private var autoRefreshInFlight = false
    private var waitingOverlayPermission = false
    private var lastDark = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        lastDark = LuMingTheme.isDark(this)
        LuMingTheme.applySystemBars(this)
        setContentView(buildUi())
        requestNotificationPermissionIfNeeded()
        TrayScheduler.ensure(this)
        renderState()
    }

    override fun onResume() {
        super.onResume()
        if (lastDark != LuMingTheme.isDark(this)) {
            recreate()
            return
        }
        if (waitingOverlayPermission && Settings.canDrawOverlays(this)) {
            waitingOverlayPermission = false
            val old = TrayStore.loadConfig(this)
            TrayStore.saveConfig(this, old.copy(floatingEnabled = true))
            FloatingTrayService.start(this)
        }
        renderState()
        syncRealtimeService()
        syncFloatingService()
        maybeAutoRefresh()
    }

    private fun buildUi(): View {
        val scroll = ScrollView(this).apply {
            setBackgroundColor(bgColor())
            overScrollMode = View.OVER_SCROLL_NEVER
        }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(20), dp(18), dp(34))
            setOnApplyWindowInsetsListener { view, insets ->
                val top = if (Build.VERSION.SDK_INT >= 30) insets.getInsets(WindowInsets.Type.statusBars()).top else {
                    @Suppress("DEPRECATION") insets.systemWindowInsetTop
                }
                val bottom = if (Build.VERSION.SDK_INT >= 30) insets.getInsets(WindowInsets.Type.navigationBars()).bottom else {
                    @Suppress("DEPRECATION") insets.systemWindowInsetBottom
                }
                view.setPadding(dp(18), dp(14) + top, dp(18), dp(24) + bottom)
                insets
            }
        }

        root.addView(buildHeader())
        root.addView(buildHero(), matchWrap().apply { topMargin = dp(6) })

        root.addView(sectionTitle("今日概览"))
        val row1 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val cost = metricCard("今日消费", "USD"); todayCostValueText = cost.second
        val req = metricCard("请求次数", "REQUESTS"); requestsValueText = req.second
        row1.addView(cost.first, weightedCardParams(endMargin = 6))
        row1.addView(req.first, weightedCardParams(startMargin = 6))
        root.addView(row1)

        val row2 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val token = metricCard("今日 Token", "TOKENS"); tokenValueText = token.second
        val response = metricCard("平均响应", "LATENCY"); responseValueText = response.second
        row2.addView(token.first, weightedCardParams(endMargin = 6))
        row2.addView(response.first, weightedCardParams(startMargin = 6))
        root.addView(row2, matchWrap().apply { topMargin = dp(12) })

        val perf = softPanel().apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(14), dp(16), dp(14))
        }
        val io = smallMetricBox("输入 / 输出"); ioValueText = io.second
        val rpm = smallMetricBox("RPM / TPM"); perfValueText = rpm.second
        perf.addView(io.first, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        perf.addView(View(this).apply { setBackgroundColor(dividerColor()) }, LinearLayout.LayoutParams(dp(1), dp(42)).apply {
            leftMargin = dp(12); rightMargin = dp(12)
        })
        perf.addView(rpm.first, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        root.addView(perf, matchWrap().apply { topMargin = dp(12) })

        root.addView(sectionTitle("工作台"))
        root.addView(toolRow(
            toolCard("用量中心", "分布 · 趋势 · 请求日志", UsageExplorerActivity::class.java),
            toolCard("模型状态", "渠道 · 可用率 · 后台探活", ModelAvailabilityActivity::class.java)
        ))
        root.addView(toolRow(
            toolCard("API 管理", "Key · 分组 · 配额 · 限速", ApiKeyManagementActivity::class.java),
            toolCard("消费分析", "成本归因 · 异常 · 预测", AnalysisActivity::class.java)
        ), matchWrap().apply { topMargin = dp(12) })

        root.addView(sectionTitle("实时监控"))
        val monitor = softPanel().apply { setPadding(dp(14), dp(14), dp(14), dp(14)) }
        val controlRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        realtimeButton = stateButton("实时") { toggleRealtime() }
        floatingButton = stateButton("悬浮") { toggleFloating() }
        balanceAlertButton = stateButton("余额提醒") { toggleBalanceAlert() }
        controlRow.addView(realtimeButton, weightedActionParams(endMargin = 4))
        controlRow.addView(floatingButton, weightedActionParams(startMargin = 2, endMargin = 2))
        controlRow.addView(balanceAlertButton, weightedActionParams(startMargin = 4))
        monitor.addView(controlRow)
        monitor.addView(TextView(this).apply {
            text = "高级阈值、站点地址与兼容凭据已移到设置页；首页只保留高频开关。"
            textSize = 10.5f
            setTextColor(textMuted())
            setPadding(dp(3), dp(10), dp(3), 0)
        })
        root.addView(monitor)

        root.addView(sectionTitle("系统状态"))
        diagnosticText = TextView(this).apply {
            textSize = 11.5f
            setTextColor(textSecondary())
            setPadding(dp(16), dp(14), dp(16), dp(14))
            background = panelBackground()
            elevation = dp(3).toFloat()
        }
        root.addView(diagnosticText, matchWrap())

        val footerActions = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        footerActions.addView(actionButton("账户中心") {
            startActivity(Intent(this@MainActivity, AccountActivity::class.java))
        }, weightedActionParams(endMargin = 6))
        footerActions.addView(actionButton("应用设置") {
            startActivity(Intent(this@MainActivity, SettingsActivity::class.java))
        }, weightedActionParams(startMargin = 6))
        root.addView(footerActions, matchWrap().apply { topMargin = dp(16) })

        root.addView(TextView(this).apply {
            text = "LuMing Tray 0.17.0 · Stable Dashboard Shell"
            textSize = 10.5f
            setTextColor(textMuted())
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(8), dp(22), dp(8), 0)
        })

        scroll.addView(root)
        return scroll
    }

    private fun buildHeader(): View {
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(2), 0, dp(2), dp(10))
        }
        header.addView(TextView(this).apply {
            text = "LM"; textSize = 15f; gravity = Gravity.CENTER
            setTypeface(typeface, Typeface.BOLD); setTextColor(Color.WHITE)
            background = GradientDrawable().apply { setColor(accentColor()); cornerRadius = dp(16).toFloat() }
            elevation = dp(5).toFloat()
        }, LinearLayout.LayoutParams(dp(48), dp(48)))
        val title = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(12), 0, 0, 0) }
        title.addView(TextView(this).apply {
            text = "LuMing Tray"; textSize = 25f; setTypeface(typeface, Typeface.BOLD); setTextColor(textPrimary())
        })
        title.addView(TextView(this).apply {
            text = "UNIFIED API CONTROL CENTER"; textSize = 9.5f; letterSpacing = 0.10f; setTextColor(textMuted())
        })
        header.addView(title, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        header.addView(TextView(this).apply {
            text = "设置"; textSize = 11f; gravity = Gravity.CENTER; setTextColor(accentDark())
            background = pillBackground(neutralPillColor())
            setPadding(dp(12), dp(7), dp(12), dp(7))
            setOnClickListener { startActivity(Intent(this@MainActivity, SettingsActivity::class.java)) }
        })
        return header
    }

    private fun buildHero(): View {
        val hero = softPanel().apply { setPadding(dp(20), dp(18), dp(20), dp(18)) }
        val top = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        top.addView(TextView(this).apply {
            text = "账户余额"; textSize = 13f; setTypeface(typeface, Typeface.BOLD); setTextColor(textMuted())
        }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        loginStateText = TextView(this).apply { textSize = 10.5f; gravity = Gravity.CENTER; setPadding(dp(10), dp(5), dp(10), dp(5)) }
        top.addView(loginStateText)
        hero.addView(top)

        balanceValueText = TextView(this).apply {
            text = "--"; textSize = 38f; setTypeface(typeface, Typeface.BOLD); setTextColor(accentColor()); setPadding(0, dp(8), 0, dp(2))
        }
        updateValueText = TextView(this).apply { text = "等待首次读取"; textSize = 11.5f; setTextColor(textMuted()) }
        hero.addView(balanceValueText)
        hero.addView(updateValueText)

        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        refreshButton = actionButton("刷新") { refreshNow(manual = true) }
        row.addView(refreshButton, weightedActionParams(endMargin = 6))
        row.addView(actionButton("充值") {
            startActivity(Intent(this@MainActivity, RechargeActivity::class.java))
        }, weightedActionParams(startMargin = 6))
        hero.addView(row, matchWrap().apply { topMargin = dp(14) })
        return hero
    }

    private fun toolRow(left: View, right: View): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        addView(left, weightedCardParams(endMargin = 6))
        addView(right, weightedCardParams(startMargin = 6))
    }

    private fun toolCard(title: String, subtitle: String, target: Class<out Activity>): View {
        return softPanel().apply {
            setPadding(dp(16), dp(15), dp(16), dp(15))
            minimumHeight = dp(112)
            isClickable = true
            isFocusable = true
            addView(TextView(this@MainActivity).apply {
                text = title; textSize = 15f; setTypeface(typeface, Typeface.BOLD); setTextColor(textPrimary())
            })
            addView(TextView(this@MainActivity).apply {
                text = subtitle; textSize = 10.5f; setTextColor(textMuted()); setPadding(0, dp(6), 0, 0)
            })
            addView(TextView(this@MainActivity).apply {
                text = "打开 →"; textSize = 11f; setTextColor(accentDark()); setPadding(0, dp(12), 0, 0)
            })
            setOnClickListener { startActivity(Intent(this@MainActivity, target)) }
        }
    }

    private fun toggleRealtime() {
        val old = TrayStore.loadConfig(this)
        val next = !old.realtimeEnabled
        TrayStore.saveConfig(this, old.copy(realtimeEnabled = next))
        if (next || old.persistentNotificationEnabled) RealtimeUsageService.start(this) else RealtimeUsageService.stop(this)
        renderState(if (next) "近实时监控已开启" else "近实时监控已关闭")
    }

    private fun toggleFloating() {
        val old = TrayStore.loadConfig(this)
        if (old.floatingEnabled) {
            TrayStore.saveConfig(this, old.copy(floatingEnabled = false))
            FloatingTrayService.stop(this)
            renderState("悬浮窗已关闭")
            return
        }
        if (!Settings.canDrawOverlays(this)) {
            waitingOverlayPermission = true
            diagnosticText.text = "请允许 LuMing Tray 显示在其他应用上层，返回后会自动开启悬浮窗。"
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
            return
        }
        TrayStore.saveConfig(this, old.copy(floatingEnabled = true))
        FloatingTrayService.start(this)
        renderState("悬浮窗已开启")
    }

    private fun toggleBalanceAlert() {
        val old = TrayStore.loadConfig(this)
        val next = !old.balanceAlertEnabled
        TrayStore.saveConfig(this, old.copy(balanceAlertEnabled = next))
        TrayStore.saveBalanceAlertState(this, 0)
        if (next) TrayStore.loadStats(this)?.let { BalanceAlert.evaluate(this, it) } else BalanceAlert.clear(this)
        renderState(if (next) "余额预警已开启" else "余额预警已关闭")
    }

    private fun syncRealtimeService() {
        val config = TrayStore.loadConfig(this)
        if (config.realtimeEnabled || config.persistentNotificationEnabled) RealtimeUsageService.start(this)
        else RealtimeUsageService.stop(this)
    }

    private fun syncFloatingService() {
        val config = TrayStore.loadConfig(this)
        if (config.floatingEnabled && Settings.canDrawOverlays(this)) FloatingTrayService.start(this)
        else FloatingTrayService.stop(this)
    }

    private fun maybeAutoRefresh() {
        if (autoRefreshInFlight) return
        val config = TrayStore.loadConfig(this)
        val hasCredential = config.webAuthToken.isNotBlank() || config.webRefreshToken.isNotBlank() ||
            config.consoleCookie.isNotBlank() || config.accessToken.isNotBlank() || config.apiKey.isNotBlank()
        if (!hasCredential) return
        val stats = TrayStore.loadStats(this)
        if (stats != null && System.currentTimeMillis() - stats.updatedAt < AUTO_REFRESH_MIN_AGE_MS) return
        refreshNow(manual = false)
    }

    private fun refreshNow(manual: Boolean) {
        if (autoRefreshInFlight) return
        autoRefreshInFlight = true
        refreshButton.isEnabled = false
        diagnosticText.text = if (manual) "正在读取最新统计…" else "自动读取最新统计…"
        UsageClient.refresh(this) { result ->
            autoRefreshInFlight = false
            refreshButton.isEnabled = true
            renderState(result.message)
        }
    }

    private fun renderState(message: String? = null) {
        val config = TrayStore.loadConfig(this)
        styleStateButton(realtimeButton, config.realtimeEnabled, if (config.realtimeEnabled) "实时 · ON" else "实时 · OFF")
        val floatingReady = config.floatingEnabled && Settings.canDrawOverlays(this)
        styleStateButton(floatingButton, floatingReady, when {
            floatingReady -> "悬浮 · ON"
            config.floatingEnabled -> "悬浮 · 授权"
            else -> "悬浮 · OFF"
        })
        styleStateButton(balanceAlertButton, config.balanceAlertEnabled, if (config.balanceAlertEnabled) "余额提醒 · ON" else "余额提醒 · OFF")

        val loginOk = config.webAuthToken.isNotBlank() || config.consoleCookie.isNotBlank()
        loginStateText.text = when {
            config.webAuthToken.isNotBlank() -> "已授权"
            config.consoleCookie.isNotBlank() -> "兼容登录"
            else -> "未授权"
        }
        loginStateText.setTextColor(if (loginOk) accentDark() else textMuted())
        loginStateText.background = pillBackground(if (loginOk) positivePillColor() else neutralPillColor())

        val stats = TrayStore.loadStats(this)
        if (stats == null) {
            balanceValueText.text = "--"
            todayCostValueText.text = "--"
            requestsValueText.text = "--"
            tokenValueText.text = "--"
            responseValueText.text = "--"
            ioValueText.text = "-- / --"
            perfValueText.text = "-- / --"
            updateValueText.text = "等待首次真实数据读取"
        } else {
            val balance = stats.balance
            balanceValueText.text = balance?.let(TrayNotification::money) ?: "--"
            balanceValueText.setTextColor(when {
                balance == null -> textPrimary()
                balance <= 0.0 -> Color.rgb(205, 63, 76)
                balance <= config.balanceAlertThreshold -> Color.rgb(210, 129, 35)
                else -> accentColor()
            })
            todayCostValueText.text = stats.todayCost?.let(TrayNotification::money) ?: "--"
            requestsValueText.text = stats.requests?.toString() ?: "--"
            tokenValueText.text = stats.totalTokens?.let(TrayNotification::tokens) ?: "--"
            responseValueText.text = stats.avgResponseSeconds?.let(TrayNotification::seconds) ?: "--"
            ioValueText.text = "${stats.inputTokens?.let(TrayNotification::tokens) ?: "--"} / ${stats.outputTokens?.let(TrayNotification::tokens) ?: "--"}"
            perfValueText.text = "${stats.rpm?.let(TrayNotification::rate) ?: "--"} / ${stats.tpm?.let(TrayNotification::rate) ?: "--"}"
            updateValueText.text = "最后更新 ${formatTime(stats.updatedAt)}"
        }
        val last = message ?: TrayStore.loadLastMessage(this)
        diagnosticText.text = if (last.isBlank()) {
            "系统已就绪 · 账户、授权和阈值设置已从首页移出"
        } else {
            "接口状态 · $last"
        }
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQ_NOTIFICATION)
        } else TrayNotification.show(this)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_NOTIFICATION && grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) {
            TrayNotification.show(this)
            syncRealtimeService()
            TrayStore.loadStats(this)?.let { BalanceAlert.evaluate(this, it) }
        }
    }

    private fun metricCard(label: String, micro: String): Pair<LinearLayout, TextView> {
        val card = softPanel().apply { setPadding(dp(15), dp(14), dp(15), dp(14)); minimumHeight = dp(112) }
        card.addView(TextView(this).apply { text = micro; textSize = 8.5f; letterSpacing = 0.1f; setTextColor(textMuted()) })
        val value = TextView(this).apply {
            text = "--"; textSize = 23f; setTypeface(typeface, Typeface.BOLD); setTextColor(textPrimary()); setPadding(0, dp(8), 0, dp(4))
        }
        card.addView(value)
        card.addView(TextView(this).apply { text = label; textSize = 11.5f; setTextColor(textSecondary()) })
        return card to value
    }

    private fun smallMetricBox(label: String): Pair<LinearLayout, TextView> {
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        box.addView(TextView(this).apply { text = label; textSize = 9.8f; setTextColor(textMuted()) })
        val value = TextView(this).apply {
            text = "--"; textSize = 16f; setTypeface(typeface, Typeface.BOLD); setTextColor(textPrimary()); setPadding(0, dp(5), 0, 0)
        }
        box.addView(value)
        return box to value
    }

    private fun stateButton(label: String, onClick: () -> Unit) = actionButton(label, onClick).apply { textSize = 10.8f }

    private fun actionButton(label: String, onClick: () -> Unit): Button = Button(this).apply {
        text = label
        isAllCaps = false
        textSize = 12.5f
        setTypeface(typeface, Typeface.BOLD)
        setTextColor(textSecondary())
        background = buttonBackground(false)
        elevation = dp(3).toFloat()
        stateListAnimator = null
        minHeight = 0
        minimumHeight = 0
        setPadding(dp(8), 0, dp(8), 0)
        setOnClickListener { onClick() }
    }

    private fun styleStateButton(button: Button, active: Boolean, label: String) {
        button.text = label
        button.setTextColor(if (active) accentDark() else textSecondary())
        button.background = buttonBackground(active)
    }

    private fun softPanel(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        background = panelBackground()
        elevation = dp(6).toFloat()
    }

    private fun sectionTitle(value: String): TextView = TextView(this).apply {
        text = value; textSize = 13f; setTextColor(textSecondary()); setTypeface(typeface, Typeface.BOLD)
        setPadding(dp(3), dp(22), dp(3), dp(9))
    }

    private fun panelBackground() = GradientDrawable().apply {
        setColor(panelColor()); cornerRadius = dp(24).toFloat(); setStroke(dp(1), borderColor())
    }

    private fun buttonBackground(active: Boolean) = GradientDrawable().apply {
        setColor(if (active) activeBgColor() else panelAltColor())
        cornerRadius = dp(17).toFloat()
        setStroke(dp(1), if (active) activeBorderColor() else borderColor())
    }

    private fun pillBackground(color: Int) = GradientDrawable().apply { setColor(color); cornerRadius = dp(999).toFloat() }

    private fun weightedCardParams(startMargin: Int = 0, endMargin: Int = 0) =
        LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
            leftMargin = dp(startMargin); rightMargin = dp(endMargin)
        }

    private fun weightedActionParams(startMargin: Int = 0, endMargin: Int = 0) =
        LinearLayout.LayoutParams(0, dp(50), 1f).apply {
            leftMargin = dp(startMargin); rightMargin = dp(endMargin)
        }

    private fun matchWrap() = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)

    private fun bgColor() = LuMingTheme.bg(this)
    private fun panelColor() = LuMingTheme.panel(this)
    private fun panelAltColor() = LuMingTheme.panelAlt(this)
    private fun borderColor() = LuMingTheme.border(this)
    private fun dividerColor() = LuMingTheme.divider(this)
    private fun accentColor() = LuMingTheme.accent(this)
    private fun accentDark() = LuMingTheme.accentDark(this)
    private fun textPrimary() = LuMingTheme.textPrimary(this)
    private fun textSecondary() = LuMingTheme.textSecondary(this)
    private fun textMuted() = LuMingTheme.textMuted(this)
    private fun activeBgColor() = LuMingTheme.activeBg(this)
    private fun activeBorderColor() = LuMingTheme.activeBorder(this)
    private fun positivePillColor() = LuMingTheme.positivePill(this)
    private fun neutralPillColor() = LuMingTheme.neutralPill(this)
    private fun formatTime(timestamp: Long) = SimpleDateFormat("MM-dd HH:mm:ss", Locale.getDefault()).format(Date(timestamp))
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    companion object {
        private const val REQ_NOTIFICATION = 10
        private const val AUTO_REFRESH_MIN_AGE_MS = 30_000L
    }
}
