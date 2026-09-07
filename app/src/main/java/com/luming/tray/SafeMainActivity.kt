package com.luming.tray

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Crash-isolated production launcher.
 *
 * The first frame only reads ordinary SharedPreferences and renders Android Views. It deliberately
 * does not open SecureVault, initialize WorkManager, perform network I/O, or start foreground
 * services. Runtime recovery is requested only after the dashboard is already visible.
 */
class SafeMainActivity : Activity() {
    private lateinit var balanceText: TextView
    private lateinit var costText: TextView
    private lateinit var requestsText: TextView
    private lateinit var tokenText: TextView
    private lateinit var responseText: TextView
    private lateinit var ioText: TextView
    private lateinit var perfText: TextView
    private lateinit var updatedText: TextView
    private lateinit var authText: TextView
    private lateinit var statusText: TextView
    private lateinit var refreshButton: Button
    private lateinit var realtimeButton: Button
    private lateinit var floatingButton: Button
    private lateinit var alertButton: Button

    private val handler = Handler(Looper.getMainLooper())
    private val medium by lazy { Typeface.create("sans-serif-medium", Typeface.NORMAL) }
    private val regular by lazy { Typeface.create("sans-serif", Typeface.NORMAL) }

    private var waitingOverlayPermission = false
    private var refreshStartedAt = 0L
    private var refreshPollCount = 0
    private var lastDark = false

    private val restoreRunnable = Runnable {
        restoreRuntime()
        requestNotificationPermissionIfNeeded()
        inspectAuthStateDeferred()
        maybeAutoRefresh()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        lastDark = isDark()
        applySystemBars()

        try {
            setContentView(buildUi())
            renderCachedState()
            statusText.text = "系统已就绪 · 正在接管后台监控"
            handler.postDelayed(restoreRunnable, 900L)
        } catch (t: Throwable) {
            showEmergencyScreen(t)
        }
    }

    override fun onResume() {
        super.onResume()
        if (!::balanceText.isInitialized) return

        if (lastDark != isDark()) {
            recreate()
            return
        }

        if (waitingOverlayPermission) {
            waitingOverlayPermission = false
            if (Settings.canDrawOverlays(this)) {
                plainPrefs().edit().putBoolean("floatingEnabled", true).commit()
                renderControlState()
                requestRuntimeRestore()
                statusText.text = "悬浮窗已开启"
            }
        }

        runCatching { renderCachedState() }
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    private fun buildUi(): View {
        val scroll = ScrollView(this).apply {
            setBackgroundColor(bg())
            overScrollMode = View.OVER_SCROLL_NEVER
            isVerticalScrollBarEnabled = false
        }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(16), dp(18), dp(32))
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
                view.setPadding(dp(18), dp(12) + top, dp(18), dp(22) + bottom)
                insets
            }
        }

        root.addView(buildHeader())
        root.addView(buildHero(), matchWrap().apply { topMargin = dp(10) })

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
        performance.addView(
            View(this).apply { setBackgroundColor(divider()) },
            LinearLayout.LayoutParams(dp(1), dp(40)).apply {
                leftMargin = dp(12)
                rightMargin = dp(12)
            }
        )
        performance.addView(perf.first, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        root.addView(performance, matchWrap().apply { topMargin = dp(12) })

        root.addView(sectionTitle("工作台"))
        root.addView(
            toolRow(
                toolCard("用量中心", "分布 · 趋势 · 请求日志") { open("UsageExplorerActivity") },
                toolCard("模型状态", "渠道 · 可用率 · 后台探活") { open("ModelAvailabilityActivity") }
            )
        )
        root.addView(
            toolRow(
                toolCard("API 管理", "Key · 分组 · 配额 · 限速") { open("ApiKeyManagementActivity") },
                toolCard("消费分析", "成本归因 · 异常 · 预测") { open("AnalysisActivity") }
            ),
            matchWrap().apply { topMargin = dp(12) }
        )

        root.addView(sectionTitle("监控"))
        val controls = panel().apply { setPadding(dp(14), dp(14), dp(14), dp(14)) }
        val controlRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        realtimeButton = stateButton("实时") { toggleRealtime() }
        floatingButton = stateButton("悬浮") { toggleFloating() }
        alertButton = stateButton("余额提醒") { toggleAlert() }
        controlRow.addView(realtimeButton, weightedAction(end = 4))
        controlRow.addView(floatingButton, weightedAction(start = 2, end = 2))
        controlRow.addView(alertButton, weightedAction(start = 4))
        controls.addView(controlRow)
        controls.addView(TextView(this).apply {
            text = "实时统计、模型探活与悬浮窗由后台运行核心持续接管"
            textSize = 10.5f
            typeface = regular
            setTextColor(muted())
            setPadding(dp(2), dp(10), dp(2), 0)
        })
        root.addView(controls)

        root.addView(sectionTitle("状态"))
        statusText = TextView(this).apply {
            textSize = 11.5f
            typeface = regular
            setTextColor(secondary())
            setPadding(dp(16), dp(14), dp(16), dp(14))
            background = panelBackground(radius = 18)
        }
        root.addView(statusText)

        val footer = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        footer.addView(actionButton("账户中心") { open("AccountActivity") }, weightedAction(end = 6))
        footer.addView(actionButton("应用设置") { open("SettingsActivity") }, weightedAction(start = 6))
        root.addView(footer, matchWrap().apply { topMargin = dp(16) })

        root.addView(TextView(this).apply {
            text = "LuMing Tray 0.17.6"
            textSize = 10f
            typeface = regular
            gravity = Gravity.CENTER
            setTextColor(muted())
            setPadding(0, dp(22), 0, 0)
        })

        scroll.addView(root)
        return scroll
    }

    private fun buildHeader(): View = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL

        addView(TextView(this@SafeMainActivity).apply {
            text = "LM"
            textSize = 15f
            gravity = Gravity.CENTER
            typeface = medium
            setTextColor(Color.WHITE)
            background = GradientDrawable().apply {
                setColor(accent())
                cornerRadius = dp(16).toFloat()
            }
        }, LinearLayout.LayoutParams(dp(48), dp(48)))

        val titles = LinearLayout(this@SafeMainActivity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), 0, 0, 0)
            addView(TextView(this@SafeMainActivity).apply {
                text = "LuMing Tray"
                textSize = 25f
                typeface = medium
                setTextColor(primary())
            })
            addView(TextView(this@SafeMainActivity).apply {
                text = "UNIFIED API CONTROL CENTER"
                textSize = 9.2f
                letterSpacing = 0.09f
                typeface = regular
                setTextColor(muted())
            })
        }
        addView(titles, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))

        addView(TextView(this@SafeMainActivity).apply {
            text = "设置"
            textSize = 11f
            gravity = Gravity.CENTER
            typeface = medium
            setTextColor(accentDark())
            setPadding(dp(12), dp(7), dp(12), dp(7))
            background = pill(neutralPill())
            setOnClickListener { open("SettingsActivity") }
        })
    }

    private fun buildHero(): View = panel(radius = 24).apply {
        setPadding(dp(20), dp(18), dp(20), dp(18))

        val top = LinearLayout(this@SafeMainActivity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        top.addView(TextView(this@SafeMainActivity).apply {
            text = "账户余额"
            textSize = 12.5f
            typeface = medium
            setTextColor(muted())
        }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        authText = TextView(this@SafeMainActivity).apply {
            text = "本地缓存"
            textSize = 10f
            typeface = medium
            gravity = Gravity.CENTER
            setPadding(dp(10), dp(5), dp(10), dp(5))
            setTextColor(secondary())
            background = pill(neutralPill())
        }
        top.addView(authText)
        addView(top)

        balanceText = TextView(this@SafeMainActivity).apply {
            text = "--"
            textSize = 38f
            typeface = medium
            setTextColor(accent())
            setPadding(0, dp(7), 0, 0)
        }
        updatedText = TextView(this@SafeMainActivity).apply {
            text = "等待首次读取"
            textSize = 11f
            typeface = regular
            setTextColor(muted())
        }
        addView(balanceText)
        addView(updatedText)

        val actions = LinearLayout(this@SafeMainActivity).apply { orientation = LinearLayout.HORIZONTAL }
        refreshButton = actionButton("刷新数据") { requestRefresh(manual = true) }
        actions.addView(refreshButton, weightedAction(end = 6))
        actions.addView(actionButton("立即充值") { open("RechargeActivity") }, weightedAction(start = 6))
        addView(actions, matchWrap().apply { topMargin = dp(14) })
    }

    private fun renderCachedState(message: String? = null) {
        val p = plainPrefs()
        val updatedAt = p.getLong("updatedAt", 0L)
        val balance = p.getString("balance", null)?.toDoubleOrNull()
        val cost = p.getString("todayCost", null)?.toDoubleOrNull()
        val requests = p.getString("requests", null)?.toLongOrNull()
        val totalTokens = p.getString("totalTokens", null)?.toLongOrNull()
        val inputTokens = p.getString("inputTokens", null)?.toLongOrNull()
        val outputTokens = p.getString("outputTokens", null)?.toLongOrNull()
        val avgResponse = p.getString("avgResponseSeconds", null)?.toDoubleOrNull()
        val rpm = p.getString("rpm", null)?.toDoubleOrNull()
        val tpm = p.getString("tpm", null)?.toDoubleOrNull()

        balanceText.text = balance?.let(::money) ?: "--"
        costText.text = cost?.let(::money) ?: "--"
        requestsText.text = requests?.toString() ?: "--"
        tokenText.text = totalTokens?.let(::compact) ?: "--"
        responseText.text = avgResponse?.let { String.format(Locale.US, "%.2fs", it) } ?: "--"
        ioText.text = if (inputTokens != null || outputTokens != null) {
            "${compact(inputTokens ?: 0L)} / ${compact(outputTokens ?: 0L)}"
        } else {
            "-- / --"
        }
        perfText.text = if (rpm != null || tpm != null) {
            "${formatRate(rpm)} / ${formatRate(tpm)}"
        } else {
            "-- / --"
        }
        updatedText.text = if (updatedAt > 0L) {
            "更新于 ${SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(updatedAt))}"
        } else {
            "尚无统计缓存 · 完成网页登录后即可读取"
        }

        renderControlState()
        if (message != null) statusText.text = message
    }

    private fun renderControlState() {
        val p = plainPrefs()
        val realtime = p.getBoolean("realtimeEnabled", true)
        val floating = p.getBoolean("floatingEnabled", false)
        val alert = p.getBoolean("balanceAlertEnabled", true)
        val floatingReady = floating && Settings.canDrawOverlays(this)

        styleState(realtimeButton, realtime, if (realtime) "实时 · ON" else "实时 · OFF")
        styleState(floatingButton, floatingReady, when {
            floatingReady -> "悬浮 · ON"
            floating -> "悬浮 · 授权"
            else -> "悬浮 · OFF"
        })
        styleState(alertButton, alert, if (alert) "提醒 · ON" else "提醒 · OFF")
    }

    private fun restoreRuntime() {
        requestRuntimeRestore()
        statusText.text = "运行核心已接管 · 后台监控按设置恢复"
    }

    private fun requestRuntimeRestore() {
        runCatching {
            sendBroadcast(
                Intent(BootReceiver.ACTION_RESTORE_RUNTIME)
                    .setClassName(packageName, "$packageName.BootReceiver")
            )
        }.onFailure {
            if (::statusText.isInitialized) statusText.text = "后台恢复异常 · 首页保持可用"
        }
    }

    private fun inspectAuthStateDeferred() {
        Thread {
            val state = runCatching {
                val config = TrayStore.loadConfig(applicationContext)
                when {
                    config.webAuthToken.isNotBlank() || config.webRefreshToken.isNotBlank() -> 2
                    config.consoleCookie.isNotBlank() || config.accessToken.isNotBlank() || config.apiKey.isNotBlank() -> 1
                    else -> 0
                }
            }.getOrDefault(-1)

            runOnUiThread {
                if (!::authText.isInitialized || isFinishing) return@runOnUiThread
                when (state) {
                    2 -> styleAuth("已授权", true)
                    1 -> styleAuth("已配置", true)
                    0 -> styleAuth("待授权", false)
                    else -> styleAuth("本地缓存", false)
                }
            }
        }.apply {
            name = "LuMing-AuthProbe"
            isDaemon = true
            start()
        }
    }

    private fun maybeAutoRefresh() {
        val updatedAt = plainPrefs().getLong("updatedAt", 0L)
        val age = if (updatedAt <= 0L) Long.MAX_VALUE else System.currentTimeMillis() - updatedAt
        if (age > AUTO_REFRESH_MIN_AGE_MS) requestRefresh(manual = false)
    }

    private fun requestRefresh(manual: Boolean) {
        if (!::refreshButton.isInitialized || !refreshButton.isEnabled) return
        refreshButton.isEnabled = false
        refreshStartedAt = plainPrefs().getLong("updatedAt", 0L)
        refreshPollCount = 0
        statusText.text = if (manual) "正在刷新最新统计…" else "正在同步最新统计…"

        runCatching {
            sendBroadcast(Intent().setClassName(packageName, "$packageName.RefreshReceiver"))
            handler.postDelayed(::pollRefreshResult, 900L)
        }.onFailure {
            refreshButton.isEnabled = true
            statusText.text = "刷新请求失败 · ${it.javaClass.simpleName}"
        }
    }

    private fun pollRefreshResult() {
        if (!::refreshButton.isInitialized || isFinishing) return
        val now = plainPrefs().getLong("updatedAt", 0L)
        if (now > refreshStartedAt) {
            refreshButton.isEnabled = true
            renderCachedState("统计已更新 · 后台监控正常")
            return
        }
        refreshPollCount++
        if (refreshPollCount >= 9) {
            refreshButton.isEnabled = true
            renderCachedState("刷新已交给后台 · 当前继续显示最近缓存")
            return
        }
        handler.postDelayed(::pollRefreshResult, 900L)
    }

    private fun toggleRealtime() {
        val p = plainPrefs()
        val next = !p.getBoolean("realtimeEnabled", true)
        p.edit().putBoolean("realtimeEnabled", next).commit()
        renderControlState()
        requestRuntimeRestore()
        statusText.text = if (next) "近实时监控已开启" else "近实时监控已关闭"
    }

    private fun toggleFloating() {
        val p = plainPrefs()
        if (p.getBoolean("floatingEnabled", false)) {
            p.edit().putBoolean("floatingEnabled", false).commit()
            runCatching { FloatingTrayService.stop(applicationContext) }
            renderControlState()
            requestRuntimeRestore()
            statusText.text = "悬浮窗已关闭"
            return
        }

        if (!Settings.canDrawOverlays(this)) {
            waitingOverlayPermission = true
            statusText.text = "请允许 LuMing 显示在其他应用上层"
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
            return
        }

        p.edit().putBoolean("floatingEnabled", true).commit()
        renderControlState()
        requestRuntimeRestore()
        statusText.text = "悬浮窗已开启"
    }

    private fun toggleAlert() {
        val p = plainPrefs()
        val next = !p.getBoolean("balanceAlertEnabled", true)
        p.edit().putBoolean("balanceAlertEnabled", next).commit()
        renderControlState()
        statusText.text = if (next) "余额提醒已开启" else "余额提醒已关闭"
    }

    private fun open(simpleName: String) {
        runCatching {
            startActivity(Intent().setClassName(packageName, "$packageName.$simpleName"))
        }.onFailure {
            statusText.text = "页面打开失败 · ${it.javaClass.simpleName}"
        }
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            runCatching { requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQ_NOTIFICATION) }
        }
    }

    private fun styleAuth(textValue: String, active: Boolean) {
        authText.text = textValue
        authText.setTextColor(if (active) accentDark() else secondary())
        authText.background = pill(if (active) positivePill() else neutralPill())
    }

    private fun styleState(button: Button, active: Boolean, textValue: String) {
        button.text = textValue
        button.setTextColor(if (active) accentDark() else secondary())
        button.background = buttonBackground(active)
    }

    private fun showEmergencyScreen(t: Throwable) {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(22), dp(34), dp(22), dp(28))
            setBackgroundColor(bg())
            addView(TextView(this@SafeMainActivity).apply {
                text = "LuMing 安全启动模式"
                textSize = 24f
                typeface = medium
                setTextColor(primary())
            })
            addView(TextView(this@SafeMainActivity).apply {
                text = "首页渲染出现异常，但进程没有退出。\n${t.javaClass.simpleName}\n${t.message.orEmpty().take(240)}"
                textSize = 12f
                typeface = regular
                setTextColor(secondary())
                setPadding(0, dp(16), 0, 0)
            })
            addView(actionButton("打开应用设置") { open("SettingsActivity") }, matchHeight(50).apply {
                topMargin = dp(18)
            })
        }
        setContentView(root)
    }

    private fun metricCard(title: String, unit: String): Pair<View, TextView> {
        val box = panel(radius = 20).apply { setPadding(dp(15), dp(14), dp(15), dp(14)) }
        box.addView(TextView(this).apply {
            text = title
            textSize = 11.5f
            typeface = medium
            setTextColor(muted())
        })
        val value = TextView(this).apply {
            text = "--"
            textSize = 23f
            typeface = medium
            setTextColor(primary())
            setPadding(0, dp(6), 0, 0)
        }
        box.addView(value)
        box.addView(TextView(this).apply {
            text = unit
            textSize = 8.5f
            letterSpacing = 0.08f
            typeface = regular
            setTextColor(muted())
            setPadding(0, dp(3), 0, 0)
        })
        return box to value
    }

    private fun miniMetric(title: String): Pair<View, TextView> {
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        box.addView(TextView(this).apply {
            text = title
            textSize = 10.5f
            typeface = medium
            setTextColor(muted())
        })
        val value = TextView(this).apply {
            text = "-- / --"
            textSize = 15f
            typeface = medium
            setTextColor(primary())
            setPadding(0, dp(5), 0, 0)
        }
        box.addView(value)
        return box to value
    }

    private fun toolCard(title: String, subtitle: String, block: () -> Unit): View = panel(radius = 20).apply {
        setPadding(dp(15), dp(15), dp(15), dp(15))
        addView(TextView(this@SafeMainActivity).apply {
            text = title
            textSize = 14f
            typeface = medium
            setTextColor(primary())
        })
        addView(TextView(this@SafeMainActivity).apply {
            text = subtitle
            textSize = 10.2f
            typeface = regular
            setTextColor(muted())
            setPadding(0, dp(5), 0, 0)
        })
        isClickable = true
        isFocusable = true
        setOnClickListener { block() }
    }

    private fun toolRow(left: View, right: View) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        addView(left, weightedCard(end = 6))
        addView(right, weightedCard(start = 6))
    }

    private fun sectionTitle(value: String) = TextView(this).apply {
        text = value
        textSize = 13f
        typeface = medium
        setTextColor(secondary())
        setPadding(dp(3), dp(22), dp(3), dp(9))
    }

    private fun actionButton(label: String, block: () -> Unit) = Button(this).apply {
        text = label
        isAllCaps = false
        textSize = 12f
        typeface = medium
        setTextColor(secondary())
        background = buttonBackground(false)
        stateListAnimator = null
        minHeight = 0
        minimumHeight = 0
        setOnClickListener { block() }
    }

    private fun stateButton(label: String, block: () -> Unit) = actionButton(label, block).apply {
        textSize = 11.5f
    }

    private fun panel(radius: Int = 22) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        background = panelBackground(radius)
        elevation = dp(3).toFloat()
    }

    private fun panelBackground(radius: Int = 22) = GradientDrawable().apply {
        setColor(panelColor())
        cornerRadius = dp(radius).toFloat()
        setStroke(dp(1), border())
    }

    private fun buttonBackground(active: Boolean) = GradientDrawable().apply {
        setColor(if (active) activeBg() else panelAlt())
        cornerRadius = dp(17).toFloat()
        setStroke(dp(1), if (active) activeBorder() else border())
    }

    private fun pill(color: Int) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = dp(20).toFloat()
    }

    private fun weightedCard(start: Int = 0, end: Int = 0) = LinearLayout.LayoutParams(
        0,
        LinearLayout.LayoutParams.WRAP_CONTENT,
        1f
    ).apply {
        leftMargin = dp(start)
        rightMargin = dp(end)
    }

    private fun weightedAction(start: Int = 0, end: Int = 0) = LinearLayout.LayoutParams(
        0,
        dp(48),
        1f
    ).apply {
        leftMargin = dp(start)
        rightMargin = dp(end)
    }

    private fun matchWrap() = LinearLayout.LayoutParams(
        LinearLayout.LayoutParams.MATCH_PARENT,
        LinearLayout.LayoutParams.WRAP_CONTENT
    )

    private fun matchHeight(height: Int) = LinearLayout.LayoutParams(
        LinearLayout.LayoutParams.MATCH_PARENT,
        dp(height)
    )

    private fun plainPrefs() = getSharedPreferences("luming_tray", MODE_PRIVATE)

    private fun isDark(): Boolean {
        val mode = getSharedPreferences("luming_ui", MODE_PRIVATE).getString("theme_mode", "system") ?: "system"
        return when (mode) {
            "dark" -> true
            "light" -> false
            else -> (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
        }
    }

    private fun applySystemBars() {
        val dark = isDark()
        window.statusBarColor = bg()
        window.navigationBarColor = bg()
        if (Build.VERSION.SDK_INT >= 30) {
            window.insetsController?.setSystemBarsAppearance(
                if (dark) 0 else WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS,
                WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS
            )
        } else {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = if (dark) 0 else View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
        }
    }

    private fun bg() = if (isDark()) Color.rgb(16, 21, 26) else Color.rgb(232, 239, 242)
    private fun panelColor() = if (isDark()) Color.rgb(26, 33, 40) else Color.rgb(242, 247, 249)
    private fun panelAlt() = if (isDark()) Color.rgb(31, 39, 46) else Color.rgb(239, 244, 246)
    private fun border() = if (isDark()) Color.rgb(55, 66, 75) else Color.rgb(221, 229, 233)
    private fun divider() = if (isDark()) Color.rgb(59, 69, 77) else Color.rgb(215, 224, 228)
    private fun primary() = if (isDark()) Color.rgb(235, 240, 244) else Color.rgb(37, 47, 58)
    private fun secondary() = if (isDark()) Color.rgb(185, 195, 204) else Color.rgb(75, 88, 101)
    private fun muted() = if (isDark()) Color.rgb(137, 151, 164) else Color.rgb(118, 131, 143)
    private fun accent() = if (isDark()) Color.rgb(58, 199, 165) else Color.rgb(25, 157, 130)
    private fun accentDark() = if (isDark()) Color.rgb(91, 205, 174) else Color.rgb(21, 125, 106)
    private fun activeBg() = if (isDark()) Color.rgb(21, 50, 44) else Color.rgb(225, 244, 239)
    private fun activeBorder() = if (isDark()) Color.rgb(43, 92, 79) else Color.rgb(199, 231, 222)
    private fun positivePill() = if (isDark()) Color.rgb(21, 52, 45) else Color.rgb(222, 243, 237)
    private fun neutralPill() = if (isDark()) Color.rgb(38, 46, 53) else Color.rgb(232, 237, 240)

    private fun money(value: Double) = String.format(Locale.US, "$%.4f", value).trimEnd('0').trimEnd('.')

    private fun compact(value: Long): String = when {
        value >= 1_000_000_000L -> trimRate(value / 1_000_000_000.0) + "B"
        value >= 1_000_000L -> trimRate(value / 1_000_000.0) + "M"
        value >= 1_000L -> trimRate(value / 1_000.0) + "K"
        else -> value.toString()
    }

    private fun formatRate(value: Double?): String = when {
        value == null -> "--"
        value >= 1_000_000_000.0 -> trimRate(value / 1_000_000_000.0) + "B"
        value >= 1_000_000.0 -> trimRate(value / 1_000_000.0) + "M"
        value >= 1_000.0 -> trimRate(value / 1_000.0) + "K"
        else -> trimRate(value)
    }

    private fun trimRate(value: Double): String = String.format(Locale.US, "%.2f", value).trimEnd('0').trimEnd('.')

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    companion object {
        private const val AUTO_REFRESH_MIN_AGE_MS = 60_000L
        private const val REQ_NOTIFICATION = 100
    }
}
