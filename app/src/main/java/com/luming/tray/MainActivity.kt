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
import android.text.InputType
import android.text.method.PasswordTransformationMethod
import android.view.Gravity
import android.view.View
import android.view.WindowInsets
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

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
    private lateinit var baseUrlField: EditText
    private lateinit var apiKeyField: EditText
    private lateinit var accessTokenField: EditText
    private lateinit var balanceAlertThresholdField: EditText
    private lateinit var refreshButton: Button
    private lateinit var realtimeButton: Button
    private lateinit var floatingButton: Button
    private lateinit var balanceAlertButton: Button

    private var autoRefreshInFlight = false
    private var waitingOverlayPermission = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = bgColor()
        window.navigationBarColor = bgColor()
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR

        setContentView(buildUi())
        loadConfigIntoFields()
        requestNotificationPermissionIfNeeded()
        TrayScheduler.ensure(this)
        renderState()
    }

    override fun onResume() {
        super.onResume()
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
                view.setPadding(dp(18), dp(16) + top, dp(18), dp(24) + bottom)
                insets
            }
        }

        root.addView(buildHeader())
        root.addView(buildHero(), matchWrap().apply { topMargin = dp(4) })

        root.addView(sectionTitle("今日概览"))
        val row1 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val costCard = metricCard("今日消费", "USD")
        todayCostValueText = costCard.second
        row1.addView(costCard.first, weightedCardParams(endMargin = 6))
        val requestCard = metricCard("请求次数", "REQUESTS")
        requestsValueText = requestCard.second
        row1.addView(requestCard.first, weightedCardParams(startMargin = 6))
        root.addView(row1)

        val row2 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val tokenCard = metricCard("今日 Token", "TOKENS")
        tokenValueText = tokenCard.second
        row2.addView(tokenCard.first, weightedCardParams(endMargin = 6))
        val responseCard = metricCard("平均响应", "LATENCY")
        responseValueText = responseCard.second
        row2.addView(responseCard.first, weightedCardParams(startMargin = 6))
        root.addView(row2, matchWrap().apply { topMargin = dp(12) })

        val perfPanel = softPanel().apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(14), dp(16), dp(14))
        }
        val ioBox = smallMetricBox("输入 / 输出")
        ioValueText = ioBox.second
        perfPanel.addView(ioBox.first, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        perfPanel.addView(View(this).apply { setBackgroundColor(Color.rgb(215, 224, 228)) },
            LinearLayout.LayoutParams(dp(1), dp(42)).apply { leftMargin = dp(12); rightMargin = dp(12) })
        val rpmBox = smallMetricBox("RPM / TPM")
        perfValueText = rpmBox.second
        perfPanel.addView(rpmBox.first, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        root.addView(perfPanel, matchWrap().apply { topMargin = dp(12) })

        root.addView(sectionTitle("快捷控制"))
        val actions1 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        realtimeButton = actionButton("近实时监控") { toggleRealtime() }
        floatingButton = actionButton("悬浮窗") { toggleFloating() }
        actions1.addView(realtimeButton, weightedActionParams(endMargin = 6))
        actions1.addView(floatingButton, weightedActionParams(startMargin = 6))
        root.addView(actions1)

        val actions2 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        actions2.addView(actionButton("立即充值") {
            val baseUrl = baseUrlField.text.toString().trim().trimEnd('/')
            if (baseUrl.startsWith("https://") || baseUrl.startsWith("http://")) saveFields(baseUrl)
            startActivity(Intent(this@MainActivity, RechargeActivity::class.java))
        }, weightedActionParams(endMargin = 6))
        refreshButton = actionButton("刷新数据") { saveAndRefresh() }
        actions2.addView(refreshButton, weightedActionParams(startMargin = 6))
        root.addView(actions2, matchWrap().apply { topMargin = dp(10) })

        root.addView(actionButton("消费分析 · 看看钱花在哪里") {
            startActivity(Intent(this@MainActivity, AnalysisActivity::class.java))
        }, matchHeight(54).apply { topMargin = dp(10) })

        root.addView(TextView(this).apply {
            text = "消费分析会优先读取站点的趋势、模型与分组明细；站点不支持时自动使用本机历史快照。"
            textSize = 11f
            setTextColor(textMuted())
            setPadding(dp(4), dp(9), dp(4), 0)
        })

        root.addView(sectionTitle("余额预警"))
        root.addView(buildAlertPanel())
        root.addView(sectionTitle("连接与授权"))
        root.addView(buildConnectionPanel())
        root.addView(sectionTitle("高级选项"))
        root.addView(buildAdvancedPanel())

        root.addView(sectionTitle("系统状态"))
        diagnosticText = TextView(this).apply {
            textSize = 12.5f
            setTextColor(textSecondary())
            setPadding(dp(16), dp(15), dp(16), dp(15))
            background = panelBackground()
            elevation = dp(3).toFloat()
        }
        root.addView(diagnosticText, matchWrap())

        root.addView(TextView(this).apply {
            text = "LuMing Tray 0.10.0 · Cost Intelligence"
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
            text = "LM"
            textSize = 15f
            gravity = Gravity.CENTER
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(Color.WHITE)
            background = GradientDrawable().apply {
                setColor(accentColor())
                cornerRadius = dp(16).toFloat()
            }
            elevation = dp(5).toFloat()
        }, LinearLayout.LayoutParams(dp(48), dp(48)))
        val titleBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(12), 0, 0, 0) }
        titleBox.addView(TextView(this).apply {
            text = "LuMing Tray"
            textSize = 25f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(textPrimary())
        })
        titleBox.addView(TextView(this).apply {
            text = "API USAGE DASHBOARD"
            textSize = 10.5f
            letterSpacing = 0.12f
            setTextColor(textMuted())
        })
        header.addView(titleBox, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        header.addView(TextView(this).apply {
            text = "安全存储"
            textSize = 10.5f
            gravity = Gravity.CENTER
            setTextColor(accentDark())
            background = pillBackground(Color.rgb(223, 242, 237))
            setPadding(dp(10), dp(6), dp(10), dp(6))
        })
        return header
    }

    private fun buildHero(): View {
        val hero = softPanel().apply { setPadding(dp(20), dp(18), dp(20), dp(18)) }
        val top = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        top.addView(TextView(this).apply {
            text = "账户余额"
            textSize = 13f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(textMuted())
        }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        loginStateText = TextView(this).apply {
            textSize = 10.5f
            gravity = Gravity.CENTER
            setPadding(dp(10), dp(5), dp(10), dp(5))
        }
        top.addView(loginStateText)
        hero.addView(top)
        balanceValueText = TextView(this).apply {
            text = "--"
            textSize = 38f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(accentColor())
            setPadding(0, dp(8), 0, dp(2))
        }
        hero.addView(balanceValueText)
        updateValueText = TextView(this).apply {
            text = "等待首次读取"
            textSize = 11.5f
            setTextColor(textMuted())
        }
        hero.addView(updateValueText)
        return hero
    }

    private fun buildAlertPanel(): View {
        val panel = softPanel().apply { setPadding(dp(16), dp(16), dp(16), dp(16)) }
        val head = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val title = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        title.addView(TextView(this).apply {
            text = "低余额保护"
            textSize = 14f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(textPrimary())
        })
        title.addView(TextView(this).apply {
            text = "跌破阈值提醒，余额 ≤ 0 自动升级"
            textSize = 10.8f
            setTextColor(textMuted())
            setPadding(0, dp(3), 0, 0)
        })
        head.addView(title, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        balanceAlertButton = actionButton("预警") { toggleBalanceAlert() }.apply { textSize = 11.5f }
        head.addView(balanceAlertButton, LinearLayout.LayoutParams(dp(104), dp(44)))
        panel.addView(head)
        balanceAlertThresholdField = editField("预警阈值，例如 0.50").apply {
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
        }
        panel.addView(balanceAlertThresholdField, matchHeight(52).apply { topMargin = dp(14) })
        return panel
    }

    private fun buildConnectionPanel(): View {
        val panel = softPanel().apply { setPadding(dp(16), dp(16), dp(16), dp(16)) }
        panel.addView(fieldLabel("站点"))
        baseUrlField = editField("API 基地址，例如 https://lmyanyu.com/v1")
        panel.addView(baseUrlField, matchHeight(52))
        panel.addView(fieldLabel("API Key（兼容项）").apply { setPadding(dp(2), dp(14), 0, dp(6)) })
        apiKeyField = secretField("API Key，可选")
        panel.addView(apiKeyField, matchHeight(52))
        panel.addView(actionButton("网页登录并授权统计") {
            val baseUrl = baseUrlField.text.toString().trim().trimEnd('/')
            if (!baseUrl.startsWith("https://") && !baseUrl.startsWith("http://")) {
                diagnosticText.text = "基地址格式不对，需要以 https:// 或 http:// 开头。"
                return@actionButton
            }
            saveFields(baseUrl)
            startActivity(Intent(this@MainActivity, LoginActivity::class.java))
        }, matchHeight(50).apply { topMargin = dp(14) })
        panel.addView(TextView(this).apply {
            text = "凭据落盘：${SecureVault.securityLabel(this@MainActivity)}"
            textSize = 10.5f
            setTextColor(accentDark())
            setPadding(dp(3), dp(10), dp(3), 0)
        })
        return panel
    }

    private fun buildAdvancedPanel(): View {
        val panel = softPanel().apply { setPadding(dp(16), dp(16), dp(16), dp(16)) }
        accessTokenField = secretField("控制台 Access Token（可选）")
        panel.addView(accessTokenField, matchHeight(52))
        panel.addView(TextView(this).apply {
            text = "推荐优先使用网页登录授权。API Key、Access Token、Cookie 与网页登录 Token 均进入本机加密仓库。"
            textSize = 10.8f
            setTextColor(textMuted())
            setPadding(dp(2), dp(10), dp(2), 0)
        })
        return panel
    }

    private fun fieldLabel(textValue: String) = TextView(this).apply {
        text = textValue
        textSize = 11f
        setTypeface(typeface, Typeface.BOLD)
        setTextColor(textMuted())
        setPadding(dp(2), 0, 0, dp(6))
    }

    private fun loadConfigIntoFields() {
        val config = TrayStore.loadConfig(this)
        baseUrlField.setText(config.baseUrl)
        apiKeyField.setText(config.apiKey)
        accessTokenField.setText(config.accessToken)
        balanceAlertThresholdField.setText(String.format(Locale.US, "%.2f", config.balanceAlertThreshold))
    }

    private fun saveFields(baseUrl: String = baseUrlField.text.toString().trim().trimEnd('/')) {
        val old = TrayStore.loadConfig(this)
        val threshold = balanceAlertThresholdField.text.toString().trim().toDoubleOrNull()?.coerceAtLeast(0.0)
            ?: old.balanceAlertThreshold
        val alertChanged = threshold != old.balanceAlertThreshold
        TrayStore.saveConfig(this, old.copy(
            baseUrl = baseUrl,
            apiKey = apiKeyField.text.toString().trim(),
            accessToken = accessTokenField.text.toString().trim(),
            balanceAlertThreshold = threshold
        ))
        if (alertChanged) {
            TrayStore.saveBalanceAlertState(this, 0)
            TrayStore.loadStats(this)?.let { BalanceAlert.evaluate(this, it) }
        }
    }

    private fun saveAndRefresh() {
        val baseUrl = baseUrlField.text.toString().trim().trimEnd('/')
        if (!baseUrl.startsWith("https://") && !baseUrl.startsWith("http://")) {
            diagnosticText.text = "基地址格式不对，需要以 https:// 或 http:// 开头。"
            return
        }
        saveFields(baseUrl)
        TrayScheduler.ensure(this)
        syncRealtimeService()
        syncFloatingService()
        refreshNow(manual = true)
    }

    private fun toggleRealtime() {
        saveFields()
        val old = TrayStore.loadConfig(this)
        val next = !old.realtimeEnabled
        TrayStore.saveConfig(this, old.copy(realtimeEnabled = next))
        if (next) RealtimeUsageService.start(this) else RealtimeUsageService.stop(this)
        renderState(if (next) "近实时监控已开启" else "近实时监控已关闭；仍保留低频后台更新")
    }

    private fun toggleFloating() {
        saveFields()
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
        saveFields()
        val old = TrayStore.loadConfig(this)
        val next = !old.balanceAlertEnabled
        TrayStore.saveConfig(this, old.copy(balanceAlertEnabled = next))
        TrayStore.saveBalanceAlertState(this, 0)
        if (next) TrayStore.loadStats(this)?.let { BalanceAlert.evaluate(this, it) } else BalanceAlert.clear(this)
        renderState(if (next) "余额预警已开启" else "余额预警已关闭")
    }

    private fun syncRealtimeService() {
        if (TrayStore.loadConfig(this).realtimeEnabled) RealtimeUsageService.start(this) else RealtimeUsageService.stop(this)
    }

    private fun syncFloatingService() {
        val config = TrayStore.loadConfig(this)
        if (config.floatingEnabled && Settings.canDrawOverlays(this)) FloatingTrayService.start(this) else FloatingTrayService.stop(this)
    }

    private fun maybeAutoRefresh() {
        if (autoRefreshInFlight) return
        val config = TrayStore.loadConfig(this)
        val hasCredential = config.webAuthToken.isNotBlank() || config.webRefreshToken.isNotBlank() ||
            config.consoleCookie.isNotBlank() || config.accessToken.isNotBlank() || config.apiKey.isNotBlank()
        if (!hasCredential) return
        val stats = TrayStore.loadStats(this)
        if (stats != null && System.currentTimeMillis() - stats.updatedAt < AUTO_REFRESH_MIN_AGE_MS) return
        autoRefreshInFlight = true
        diagnosticText.text = "自动读取最新统计…"
        UsageClient.refresh(this) { result -> autoRefreshInFlight = false; renderState(result.message) }
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
        styleStateButton(realtimeButton, config.realtimeEnabled, if (config.realtimeEnabled) "近实时 · ON" else "近实时 · OFF")
        val floatingReady = config.floatingEnabled && Settings.canDrawOverlays(this)
        styleStateButton(floatingButton, floatingReady, when {
            floatingReady -> "悬浮窗 · ON"
            config.floatingEnabled -> "悬浮窗 · 授权中"
            else -> "悬浮窗 · OFF"
        })
        styleStateButton(balanceAlertButton, config.balanceAlertEnabled,
            if (config.balanceAlertEnabled) "预警 · ON" else "预警 · OFF")

        val loginOk = config.webAuthToken.isNotBlank() || config.consoleCookie.isNotBlank()
        loginStateText.text = when {
            config.webAuthToken.isNotBlank() -> "已授权"
            config.consoleCookie.isNotBlank() -> "兼容登录"
            else -> "未授权"
        }
        loginStateText.setTextColor(if (loginOk) accentDark() else textMuted())
        loginStateText.background = pillBackground(if (loginOk) Color.rgb(222, 243, 237) else Color.rgb(232, 237, 240))

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
        diagnosticText.text = if (last.isBlank()) "系统已就绪 · 等待首次真实数据读取" else "接口状态 · $last"
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

    private fun metricCard(label: String, microLabel: String): Pair<LinearLayout, TextView> {
        val card = softPanel().apply { setPadding(dp(15), dp(14), dp(15), dp(14)); minimumHeight = dp(112) }
        card.addView(TextView(this).apply { text = microLabel; textSize = 8.5f; letterSpacing = 0.1f; setTextColor(textMuted()) })
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

    private fun actionButton(label: String, onClick: () -> Unit): Button = Button(this).apply {
        text = label
        isAllCaps = false
        textSize = 12.5f
        setTypeface(typeface, Typeface.BOLD)
        setTextColor(textSecondary())
        background = buttonBackground(false)
        elevation = dp(4).toFloat()
        stateListAnimator = null
        minHeight = 0
        minimumHeight = 0
        setPadding(dp(10), 0, dp(10), 0)
        setOnClickListener { onClick() }
    }

    private fun styleStateButton(button: Button, active: Boolean, label: String) {
        button.text = label
        button.setTextColor(if (active) accentDark() else textSecondary())
        button.background = buttonBackground(active)
    }

    private fun editField(hintText: String): EditText = EditText(this).apply {
        hint = hintText
        textSize = 13.5f
        setSingleLine(true)
        setPadding(dp(14), 0, dp(14), 0)
        setTextColor(textPrimary())
        setHintTextColor(Color.rgb(145, 154, 164))
        background = inputBackground()
        elevation = dp(2).toFloat()
    }

    private fun secretField(hintText: String): EditText = editField(hintText).apply {
        inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        transformationMethod = PasswordTransformationMethod.getInstance()
    }

    private fun softPanel(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        background = panelBackground()
        elevation = dp(7).toFloat()
    }

    private fun sectionTitle(textValue: String): TextView = TextView(this).apply {
        text = textValue; textSize = 13f; setTextColor(textSecondary()); setTypeface(typeface, Typeface.BOLD); setPadding(dp(3), dp(22), dp(3), dp(9))
    }

    private fun panelBackground() = GradientDrawable().apply {
        setColor(panelColor()); cornerRadius = dp(24).toFloat(); setStroke(dp(1), Color.argb(210, 255, 255, 255))
    }
    private fun buttonBackground(active: Boolean) = GradientDrawable().apply {
        setColor(if (active) Color.rgb(225, 244, 239) else Color.rgb(239, 244, 246)); cornerRadius = dp(17).toFloat()
        setStroke(dp(1), if (active) Color.rgb(199, 231, 222) else Color.rgb(221, 229, 233))
    }
    private fun inputBackground() = GradientDrawable().apply {
        setColor(Color.rgb(235, 241, 244)); cornerRadius = dp(17).toFloat(); setStroke(dp(1), Color.rgb(218, 227, 231))
    }
    private fun pillBackground(color: Int) = GradientDrawable().apply { setColor(color); cornerRadius = dp(999).toFloat() }

    private fun weightedCardParams(startMargin: Int = 0, endMargin: Int = 0) = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
        leftMargin = dp(startMargin); rightMargin = dp(endMargin)
    }
    private fun weightedActionParams(startMargin: Int = 0, endMargin: Int = 0) = LinearLayout.LayoutParams(0, dp(52), 1f).apply {
        leftMargin = dp(startMargin); rightMargin = dp(endMargin)
    }
    private fun matchWrap() = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
    private fun matchHeight(heightDp: Int) = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(heightDp))

    private fun bgColor() = Color.rgb(232, 239, 242)
    private fun panelColor() = Color.rgb(242, 247, 249)
    private fun accentColor() = Color.rgb(25, 157, 130)
    private fun accentDark() = Color.rgb(21, 125, 106)
    private fun textPrimary() = Color.rgb(37, 47, 58)
    private fun textSecondary() = Color.rgb(75, 88, 101)
    private fun textMuted() = Color.rgb(118, 131, 143)
    private fun formatTime(timestamp: Long) = SimpleDateFormat("MM-dd HH:mm:ss", Locale.getDefault()).format(Date(timestamp))
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    companion object {
        private const val REQ_NOTIFICATION = 10
        private const val AUTO_REFRESH_MIN_AGE_MS = 30_000L
    }
}
