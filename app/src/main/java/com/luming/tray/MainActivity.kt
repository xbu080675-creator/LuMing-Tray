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
 * 0.17.2 restores the lifecycle/orchestration contract from the last known-good 0.15 dashboard.
 *
 * Important: the home screen is allowed to be visually simpler, but it must still own the same
 * app-entry responsibilities as 0.15: ensure the fallback scheduler, re-attach requested services,
 * re-attach the floating overlay and refresh stale usage data whenever the Activity resumes.
 * Removing those calls made 0.17.1 look healthy for a while while the already-running service was
 * alive, then silently lose monitoring once Android reclaimed it.
 */
class MainActivity : Activity() {
    private lateinit var balanceText: TextView
    private lateinit var costText: TextView
    private lateinit var tokenText: TextView
    private lateinit var requestsText: TextView
    private lateinit var responseText: TextView
    private lateinit var ioText: TextView
    private lateinit var perfText: TextView
    private lateinit var updateText: TextView
    private lateinit var authText: TextView
    private lateinit var statusText: TextView
    private lateinit var refreshButton: Button
    private lateinit var realtimeButton: Button
    private lateinit var floatingButton: Button
    private lateinit var alertButton: Button

    private var autoRefreshInFlight = false
    private var waitingOverlayPermission = false
    private var lastDark = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        lastDark = LuMingTheme.isDark(this)
        LuMingTheme.applySystemBars(this)
        setContentView(buildUi())

        // Keep the exact operational responsibilities the working 0.15 launcher had.
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

        // These four calls are intentionally restored from the known-good launcher contract.
        renderState()
        syncRealtimeService()
        syncFloatingService()
        maybeAutoRefresh()
    }

    private fun buildUi(): View {
        val scroll = ScrollView(this).apply {
            setBackgroundColor(bg())
            overScrollMode = View.OVER_SCROLL_NEVER
        }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(18), dp(18), dp(32))
            setOnApplyWindowInsetsListener { view, insets ->
                val top = if (Build.VERSION.SDK_INT >= 30) {
                    insets.getInsets(WindowInsets.Type.statusBars()).top
                } else {
                    @Suppress("DEPRECATION")
                    insets.systemWindowInsetTop
                }
                val bottom = if (Build.VERSION.SDK_INT >= 30) {
                    insets.getInsets(WindowInsets.Type.navigationBars()).bottom
                } else {
                    @Suppress("DEPRECATION")
                    insets.systemWindowInsetBottom
                }
                view.setPadding(dp(18), dp(14) + top, dp(18), dp(22) + bottom)
                insets
            }
        }

        root.addView(buildHeader())
        root.addView(buildHero(), matchWrap().apply { topMargin = dp(6) })

        root.addView(sectionTitle("今日概览"))
        val row1 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val cost = metricCard("今日消费", "USD")
        costText = cost.second
        val requests = metricCard("请求次数", "REQUESTS")
        requestsText = requests.second
        row1.addView(cost.first, weightedCard(end = 6))
        row1.addView(requests.first, weightedCard(start = 6))
        root.addView(row1)

        val row2 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val tokens = metricCard("今日 Token", "TOKENS")
        tokenText = tokens.second
        val response = metricCard("平均响应", "LATENCY")
        responseText = response.second
        row2.addView(tokens.first, weightedCard(end = 6))
        row2.addView(response.first, weightedCard(start = 6))
        root.addView(row2, matchWrap().apply { topMargin = dp(12) })

        val performance = panel().apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(14), dp(16), dp(14))
        }
        val io = miniMetric("输入 / 输出")
        ioText = io.second
        val perf = miniMetric("RPM / TPM")
        perfText = perf.second
        performance.addView(io.first, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        performance.addView(View(this).apply { setBackgroundColor(divider()) }, LinearLayout.LayoutParams(dp(1), dp(42)).apply {
            leftMargin = dp(12)
            rightMargin = dp(12)
        })
        performance.addView(perf.first, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        root.addView(performance, matchWrap().apply { topMargin = dp(12) })

        root.addView(sectionTitle("工作台"))
        root.addView(toolRow(
            toolCard("用量中心", "分布 · 趋势 · 请求日志") { startActivity(Intent(this, UsageExplorerActivity::class.java)) },
            toolCard("模型状态", "渠道 · 可用率 · 后台探活") { startActivity(Intent(this, ModelAvailabilityActivity::class.java)) }
        ))
        root.addView(toolRow(
            toolCard("API 管理", "Key · 分组 · 配额 · 限速") { startActivity(Intent(this, ApiKeyManagementActivity::class.java)) },
            toolCard("消费分析", "成本归因 · 异常 · 预测") { startActivity(Intent(this, AnalysisActivity::class.java)) }
        ), matchWrap().apply { topMargin = dp(12) })

        root.addView(sectionTitle("监控"))
        val controls = panel().apply { setPadding(dp(14), dp(14), dp(14), dp(14)) }
        val controlRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        realtimeButton = stateButton("实时") { toggleRealtime() }
        floatingButton = stateButton("悬浮") { toggleFloating() }
        alertButton = stateButton("余额提醒") { toggleBalanceAlert() }
        controlRow.addView(realtimeButton, weightedAction(end = 4))
        controlRow.addView(floatingButton, weightedAction(start = 2, end = 2))
        controlRow.addView(alertButton, weightedAction(start = 4))
        controls.addView(controlRow)
        controls.addView(TextView(this).apply {
            text = "站点地址、凭据、余额阈值和主题放在设置页；首页只保留高频控制。"
            textSize = 10.5f
            setTextColor(muted())
            setPadding(dp(2), dp(10), dp(2), 0)
        })
        root.addView(controls)

        root.addView(sectionTitle("状态"))
        statusText = TextView(this).apply {
            textSize = 11.5f
            setTextColor(secondary())
            setPadding(dp(16), dp(14), dp(16), dp(14))
            background = panelBackground()
            elevation = dp(3).toFloat()
        }
        root.addView(statusText)

        val footer = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        footer.addView(actionButton("账户中心") {
            startActivity(Intent(this@MainActivity, AccountActivity::class.java))
        }, weightedAction(end = 6))
        footer.addView(actionButton("应用设置") {
            startActivity(Intent(this@MainActivity, SettingsActivity::class.java))
        }, weightedAction(start = 6))
        root.addView(footer, matchWrap().apply { topMargin = dp(16) })

        root.addView(TextView(this).apply {
            text = "LuMing Tray 0.17.2 · Stable Core"
            textSize = 10.5f
            gravity = Gravity.CENTER
            setTextColor(muted())
            setPadding(0, dp(22), 0, 0)
        })

        scroll.addView(root)
        return scroll
    }

    private fun buildHeader(): View {
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL

            addView(TextView(this@MainActivity).apply {
                text = "LM"
                textSize = 15f
                gravity = Gravity.CENTER
                setTypeface(typeface, Typeface.BOLD)
                setTextColor(Color.WHITE)
                background = GradientDrawable().apply {
                    setColor(accent())
                    cornerRadius = dp(16).toFloat()
                }
            }, LinearLayout.LayoutParams(dp(48), dp(48)))

            val titles = LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(12), 0, 0, 0)
                addView(TextView(this@MainActivity).apply {
                    text = "LuMing Tray"
                    textSize = 25f
                    setTypeface(typeface, Typeface.BOLD)
                    setTextColor(primary())
                })
                addView(TextView(this@MainActivity).apply {
                    text = "UNIFIED API CONTROL CENTER"
                    textSize = 9.5f
                    letterSpacing = 0.10f
                    setTextColor(muted())
                })
            }
            addView(titles, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))

            addView(TextView(this@MainActivity).apply {
                text = "设置"
                textSize = 11f
                gravity = Gravity.CENTER
                setTextColor(accentDark())
                setPadding(dp(12), dp(7), dp(12), dp(7))
                background = pill(neutralPill())
                setOnClickListener { startActivity(Intent(this@MainActivity, SettingsActivity::class.java)) }
            })
        }
    }

    private fun buildHero(): View {
        return panel().apply {
            setPadding(dp(20), dp(18), dp(20), dp(18))

            val top = LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            top.addView(TextView(this@MainActivity).apply {
                text = "账户余额"
                textSize = 13f
                setTypeface(typeface, Typeface.BOLD)
                setTextColor(muted())
            }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            authText = TextView(this@MainActivity).apply {
                textSize = 10.5f
                gravity = Gravity.CENTER
                setPadding(dp(10), dp(5), dp(10), dp(5))
            }
            top.addView(authText)
            addView(top)

            balanceText = TextView(this@MainActivity).apply {
                text = "--"
                textSize = 38f
                setTypeface(typeface, Typeface.BOLD)
                setTextColor(accent())
                setPadding(0, dp(8), 0, 0)
            }
            updateText = TextView(this@MainActivity).apply {
                text = "等待首次读取"
                textSize = 11f
                setTextColor(muted())
            }
            addView(balanceText)
            addView(updateText)

            val actions = LinearLayout(this@MainActivity).apply { orientation = LinearLayout.HORIZONTAL }
            refreshButton = actionButton("刷新数据") { refreshNow(manual = true) }
            actions.addView(refreshButton, weightedAction(end = 6))
            actions.addView(actionButton("立即充值") {
                startActivity(Intent(this@MainActivity, RechargeActivity::class.java))
            }, weightedAction(start = 6))
            addView(actions, matchWrap().apply { topMargin = dp(14) })
        }
    }

    private fun maybeAutoRefresh() {
        if (autoRefreshInFlight) return
        val config = TrayStore.loadConfig(this)
        val hasCredential = config.webAuthToken.isNotBlank() ||
            config.webRefreshToken.isNotBlank() ||
            config.consoleCookie.isNotBlank() ||
            config.accessToken.isNotBlank() ||
            config.apiKey.isNotBlank()
        if (!hasCredential) return

        val stats = TrayStore.loadStats(this)
        if (stats != null && System.currentTimeMillis() - stats.updatedAt < AUTO_REFRESH_MIN_AGE_MS) return
        refreshNow(manual = false)
    }

    private fun refreshNow(manual: Boolean) {
        if (autoRefreshInFlight) return
        autoRefreshInFlight = true
        refreshButton.isEnabled = false
        statusText.text = if (manual) "正在读取最新统计…" else "自动读取最新统计…"
        UsageClient.refresh(this) { result ->
            autoRefreshInFlight = false
            refreshButton.isEnabled = true
            renderState(result.message)
        }
    }

    private fun syncRealtimeService() {
        val config = TrayStore.loadConfig(this)
        if (config.realtimeEnabled || config.persistentNotificationEnabled) {
            RealtimeUsageService.start(this)
        } else {
            RealtimeUsageService.stop(this)
        }
    }

    private fun syncFloatingService() {
        val config = TrayStore.loadConfig(this)
        if (config.floatingEnabled && Settings.canDrawOverlays(this)) {
            FloatingTrayService.start(this)
        } else {
            FloatingTrayService.stop(this)
        }
    }

    private fun toggleRealtime() {
        val old = TrayStore.loadConfig(this)
        val next = !old.realtimeEnabled
        TrayStore.saveConfig(this, old.copy(realtimeEnabled = next))
        syncRealtimeService()
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
            statusText.text = "请允许 LuMing 显示在其他应用上层，返回后会自动开启。"
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
        if (next) {
            TrayStore.loadStats(this)?.let { BalanceAlert.evaluate(this, it) }
        } else {
            BalanceAlert.clear(this)
        }
        renderState(if (next) "余额预警已开启" else "余额预警已关闭")
    }

    private fun renderState(message: String? = null) {
        val config = TrayStore.loadConfig(this)
        val stats = TrayStore.loadStats(this)

        styleState(realtimeButton, config.realtimeEnabled, if (config.realtimeEnabled) "实时 · ON" else "实时 · OFF")
        val floatingReady = config.floatingEnabled && Settings.canDrawOverlays(this)
        styleState(floatingButton, floatingReady, when {
            floatingReady -> "悬浮 · ON"
            config.floatingEnabled -> "悬浮 · 授权"
            else -> "悬浮 · OFF"
        })
        styleState(alertButton, config.balanceAlertEnabled, if (config.balanceAlertEnabled) "余额提醒 · ON" else "余额提醒 · OFF")

        val authOk = config.webAuthToken.isNotBlank() || config.consoleCookie.isNotBlank()
        authText.text = when {
            config.webAuthToken.isNotBlank() -> "已授权"
            config.consoleCookie.isNotBlank() -> "兼容登录"
            else -> "未授权"
        }
        authText.setTextColor(if (authOk) accentDark() else muted())
        authText.background = pill(if (authOk) positivePill() else neutralPill())

        if (stats == null) {
            balanceText.text = "--"
            costText.text = "--"
            tokenText.text = "--"
            requestsText.text = "--"
            responseText.text = "--"
            ioText.text = "-- / --"
            perfText.text = "-- / --"
            updateText.text = "等待首次真实数据读取"
        } else {
            balanceText.text = stats.balance?.let(TrayNotification::money) ?: "--"
            balanceText.setTextColor(when {
                stats.balance == null -> primary()
                stats.balance <= 0.0 -> Color.rgb(205, 63, 76)
                stats.balance <= config.balanceAlertThreshold -> Color.rgb(210, 129, 35)
                else -> accent()
            })
            costText.text = stats.todayCost?.let(TrayNotification::money) ?: "--"
            tokenText.text = stats.totalTokens?.let(TrayNotification::tokens) ?: "--"
            requestsText.text = stats.requests?.toString() ?: "--"
            responseText.text = stats.avgResponseSeconds?.let(TrayNotification::seconds) ?: "--"
            ioText.text = "${stats.inputTokens?.let(TrayNotification::tokens) ?: "--"} / ${stats.outputTokens?.let(TrayNotification::tokens) ?: "--"}"
            perfText.text = "${stats.rpm?.let(TrayNotification::rate) ?: "--"} / ${stats.tpm?.let(TrayNotification::rate) ?: "--"}"
            updateText.text = "最后更新 ${formatTime(stats.updatedAt)}"
        }

        val last = message ?: TrayStore.loadLastMessage(this)
        statusText.text = if (last.isBlank()) "系统已就绪 · 后台编排已恢复" else "接口状态 · $last"
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQ_NOTIFICATION)
        } else {
            TrayNotification.show(this)
            syncRealtimeService()
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_NOTIFICATION) {
            if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) {
                TrayNotification.show(this)
            }
            // The monitor itself must keep working even when notification permission is denied.
            syncRealtimeService()
        }
    }

    private fun metricCard(label: String, micro: String): Pair<LinearLayout, TextView> {
        val card = panel().apply {
            setPadding(dp(15), dp(14), dp(15), dp(14))
            minimumHeight = dp(108)
        }
        card.addView(TextView(this).apply {
            text = micro
            textSize = 8.5f
            letterSpacing = 0.10f
            setTextColor(muted())
        })
        val value = TextView(this).apply {
            text = "--"
            textSize = 22f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(primary())
            setPadding(0, dp(8), 0, dp(4))
        }
        card.addView(value)
        card.addView(TextView(this).apply {
            text = label
            textSize = 11f
            setTextColor(secondary())
        })
        return card to value
    }

    private fun miniMetric(label: String): Pair<LinearLayout, TextView> {
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        box.addView(TextView(this).apply { text = label; textSize = 9.8f; setTextColor(muted()) })
        val value = TextView(this).apply {
            text = "--"
            textSize = 16f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(primary())
            setPadding(0, dp(5), 0, 0)
        }
        box.addView(value)
        return box to value
    }

    private fun toolRow(left: View, right: View): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        addView(left, weightedCard(end = 6))
        addView(right, weightedCard(start = 6))
    }

    private fun toolCard(title: String, subtitle: String, click: () -> Unit): View = panel().apply {
        setPadding(dp(16), dp(15), dp(16), dp(15))
        minimumHeight = dp(106)
        isClickable = true
        isFocusable = true
        addView(TextView(this@MainActivity).apply {
            text = title
            textSize = 15f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(primary())
        })
        addView(TextView(this@MainActivity).apply {
            text = subtitle
            textSize = 10f
            setTextColor(muted())
            setPadding(0, dp(5), 0, 0)
        })
        addView(TextView(this@MainActivity).apply {
            text = "打开 →"
            textSize = 10.5f
            setTextColor(accentDark())
            setPadding(0, dp(10), 0, 0)
        })
        setOnClickListener { click() }
    }

    private fun panel(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        background = panelBackground()
        elevation = dp(6).toFloat()
    }

    private fun sectionTitle(value: String): TextView = TextView(this).apply {
        text = value
        textSize = 13f
        setTypeface(typeface, Typeface.BOLD)
        setTextColor(secondary())
        setPadding(dp(3), dp(21), dp(3), dp(9))
    }

    private fun actionButton(label: String, click: () -> Unit): Button = Button(this).apply {
        text = label
        isAllCaps = false
        textSize = 11.5f
        setTypeface(typeface, Typeface.BOLD)
        setTextColor(secondary())
        background = buttonBackground(false)
        elevation = dp(3).toFloat()
        stateListAnimator = null
        minHeight = 0
        minimumHeight = 0
        setOnClickListener { click() }
    }

    private fun stateButton(label: String, click: () -> Unit): Button = actionButton(label, click)

    private fun styleState(button: Button, active: Boolean, label: String) {
        button.text = label
        button.setTextColor(if (active) accentDark() else secondary())
        button.background = buttonBackground(active)
    }

    private fun panelBackground() = GradientDrawable().apply {
        setColor(panelColor())
        cornerRadius = dp(22).toFloat()
        setStroke(dp(1), border())
    }

    private fun buttonBackground(active: Boolean) = GradientDrawable().apply {
        setColor(if (active) LuMingTheme.activeBg(this@MainActivity) else panelAlt())
        cornerRadius = dp(16).toFloat()
        setStroke(dp(1), if (active) LuMingTheme.activeBorder(this@MainActivity) else border())
    }

    private fun pill(color: Int) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = dp(999).toFloat()
    }

    private fun weightedCard(start: Int = 0, end: Int = 0) =
        LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
            leftMargin = dp(start)
            rightMargin = dp(end)
        }

    private fun weightedAction(start: Int = 0, end: Int = 0) =
        LinearLayout.LayoutParams(0, dp(50), 1f).apply {
            leftMargin = dp(start)
            rightMargin = dp(end)
        }

    private fun matchWrap() = LinearLayout.LayoutParams(
        LinearLayout.LayoutParams.MATCH_PARENT,
        LinearLayout.LayoutParams.WRAP_CONTENT
    )

    private fun bg() = LuMingTheme.bg(this)
    private fun panelColor() = LuMingTheme.panel(this)
    private fun panelAlt() = LuMingTheme.panelAlt(this)
    private fun border() = LuMingTheme.border(this)
    private fun divider() = LuMingTheme.divider(this)
    private fun primary() = LuMingTheme.textPrimary(this)
    private fun secondary() = LuMingTheme.textSecondary(this)
    private fun muted() = LuMingTheme.textMuted(this)
    private fun accent() = LuMingTheme.accent(this)
    private fun accentDark() = LuMingTheme.accentDark(this)
    private fun positivePill() = LuMingTheme.positivePill(this)
    private fun neutralPill() = LuMingTheme.neutralPill(this)

    private fun formatTime(timestamp: Long) =
        SimpleDateFormat("MM-dd HH:mm:ss", Locale.getDefault()).format(Date(timestamp))

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    companion object {
        private const val REQ_NOTIFICATION = 10
        private const val AUTO_REFRESH_MIN_AGE_MS = 30_000L
    }
}
