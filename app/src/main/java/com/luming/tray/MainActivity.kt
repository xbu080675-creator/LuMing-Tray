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
    private lateinit var statusText: TextView
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
            setBackgroundColor(Color.rgb(244, 250, 250))
        }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(28), dp(20), dp(32))
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
                view.setPadding(dp(20), dp(20) + top, dp(20), dp(24) + bottom)
                insets
            }
        }

        root.addView(TextView(this).apply {
            text = "LuMing Tray"
            textSize = 28f
            setTextColor(Color.rgb(30, 38, 50))
            setTypeface(typeface, Typeface.BOLD)
        })
        root.addView(TextView(this).apply {
            text = "手机通知栏与悬浮窗里的 API 用量托盘"
            textSize = 15f
            setTextColor(Color.rgb(100, 110, 122))
            setPadding(0, dp(6), 0, dp(22))
        })

        statusText = TextView(this).apply {
            textSize = 16f
            setTextColor(Color.rgb(41, 48, 61))
            setPadding(dp(18), dp(18), dp(18), dp(18))
            background = roundedCard()
        }
        root.addView(statusText, matchWrap())

        realtimeButton = Button(this).apply {
            isAllCaps = false
            setOnClickListener { toggleRealtime() }
        }
        root.addView(realtimeButton, matchHeight(50).apply { topMargin = dp(12) })

        floatingButton = Button(this).apply {
            isAllCaps = false
            setOnClickListener { toggleFloating() }
        }
        root.addView(floatingButton, matchHeight(50).apply { topMargin = dp(8) })

        root.addView(Button(this).apply {
            text = "充值"
            isAllCaps = false
            setOnClickListener {
                val baseUrl = baseUrlField.text.toString().trim().trimEnd('/')
                if (baseUrl.startsWith("https://") || baseUrl.startsWith("http://")) {
                    saveFields(baseUrl)
                }
                startActivity(Intent(this@MainActivity, RechargeActivity::class.java))
            }
        }, matchHeight(50).apply { topMargin = dp(8) })

        root.addView(TextView(this).apply {
            text = "悬浮窗不会额外请求 API，只显示近实时服务已经保存的数据。拖动标题移动位置，拖动右下角 ↘ 可自由缩放，位置和大小会自动记住。"
            textSize = 12.5f
            setTextColor(Color.rgb(86, 96, 110))
            setPadding(dp(4), dp(10), dp(4), 0)
        })

        root.addView(sectionTitle("余额预警"))

        balanceAlertButton = Button(this).apply {
            isAllCaps = false
            setOnClickListener { toggleBalanceAlert() }
        }
        root.addView(balanceAlertButton, matchHeight(48))

        balanceAlertThresholdField = editField("预警阈值，例如 0.50").apply {
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
        }
        root.addView(balanceAlertThresholdField, matchHeight(54).apply { topMargin = dp(8) })

        root.addView(TextView(this).apply {
            text = "余额首次跌破阈值时提醒一次；继续低于阈值不会反复轰炸。充值回阈值以上后自动复位；余额 ≤ 0 会升级为“余额不足”提醒。"
            textSize = 12.5f
            setTextColor(Color.rgb(86, 96, 110))
            setPadding(dp(4), dp(8), dp(4), 0)
        })

        root.addView(sectionTitle("连接设置"))

        baseUrlField = editField("API 基地址，例如 https://lmyanyu.com/v1")
        root.addView(baseUrlField, matchHeight(54))

        apiKeyField = secretField("API Key（sk-...，可选）")
        root.addView(apiKeyField, matchHeight(54).apply { topMargin = dp(10) })

        root.addView(Button(this).apply {
            text = "网页登录并授权统计"
            isAllCaps = false
            setOnClickListener {
                val baseUrl = baseUrlField.text.toString().trim().trimEnd('/')
                if (!baseUrl.startsWith("https://") && !baseUrl.startsWith("http://")) {
                    diagnosticText.text = "基地址格式不对，需要以 https:// 或 http:// 开头。"
                    return@setOnClickListener
                }
                saveFields(baseUrl)
                startActivity(Intent(this@MainActivity, LoginActivity::class.java))
            }
        }, matchHeight(52).apply { topMargin = dp(12) })

        loginStateText = TextView(this).apply {
            textSize = 12.5f
            setTextColor(Color.rgb(86, 96, 110))
            setPadding(dp(4), dp(8), dp(4), 0)
        }
        root.addView(loginStateText)

        root.addView(sectionTitle("高级选项"))

        accessTokenField = secretField("控制台 Access Token（可选）")
        root.addView(accessTokenField, matchHeight(54))

        root.addView(TextView(this).apply {
            text = "推荐直接使用“网页登录并授权统计”。登录成功后会自动保存站点令牌；近实时服务和后台定时任务都会复用该令牌。API Key 和手动 Access Token 继续作为兼容兜底。所有凭据只保存在本机应用私有数据中，不写入 GitHub。"
            textSize = 12.5f
            setTextColor(Color.rgb(112, 120, 132))
            setPadding(dp(4), dp(10), dp(4), 0)
        })

        refreshButton = Button(this).apply {
            text = "保存并立即读取"
            isAllCaps = false
            setOnClickListener { saveAndRefresh() }
        }
        root.addView(refreshButton, matchHeight(52).apply { topMargin = dp(18) })

        root.addView(Button(this).apply {
            text = "手动读取（备用）"
            isAllCaps = false
            setOnClickListener { refreshNow(manual = true) }
        }, matchHeight(48).apply { topMargin = dp(8) })

        diagnosticText = TextView(this).apply {
            textSize = 13f
            setTextColor(Color.rgb(86, 96, 110))
            setPadding(dp(16), dp(14), dp(16), dp(14))
            background = roundedCard()
        }
        root.addView(diagnosticText, matchWrap().apply { topMargin = dp(16) })

        root.addView(TextView(this).apply {
            text = "M0.7 · 可缩放悬浮窗 / 余额预警 / 近实时监控 / 一键充值"
            textSize = 12.5f
            setTextColor(Color.rgb(112, 120, 132))
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(8), dp(18), dp(8), 0)
        })

        scroll.addView(root)
        return scroll
    }

    private fun loadConfigIntoFields() {
        val config = TrayStore.loadConfig(this)
        baseUrlField.setText(config.baseUrl)
        apiKeyField.setText(config.apiKey)
        accessTokenField.setText(config.accessToken)
        balanceAlertThresholdField.setText(
            String.format(Locale.US, "%.2f", config.balanceAlertThreshold)
        )
    }

    private fun saveFields(baseUrl: String = baseUrlField.text.toString().trim().trimEnd('/')) {
        val old = TrayStore.loadConfig(this)
        val threshold = balanceAlertThresholdField.text.toString().trim()
            .toDoubleOrNull()?.coerceAtLeast(0.0) ?: old.balanceAlertThreshold
        val alertSettingsChanged = threshold != old.balanceAlertThreshold

        TrayStore.saveConfig(
            this,
            old.copy(
                baseUrl = baseUrl,
                apiKey = apiKeyField.text.toString().trim(),
                accessToken = accessTokenField.text.toString().trim(),
                balanceAlertThreshold = threshold
            )
        )
        if (alertSettingsChanged) {
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
        if (next) {
            RealtimeUsageService.start(this)
        } else {
            RealtimeUsageService.stop(this)
        }
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
            startActivity(
                Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:$packageName")
                )
            )
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
        if (next) {
            TrayStore.loadStats(this)?.let { BalanceAlert.evaluate(this, it) }
        } else {
            BalanceAlert.clear(this)
        }
        renderState(if (next) "余额预警已开启" else "余额预警已关闭")
    }

    private fun syncRealtimeService() {
        val config = TrayStore.loadConfig(this)
        if (config.realtimeEnabled) {
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
        val stale = stats == null || System.currentTimeMillis() - stats.updatedAt >= AUTO_REFRESH_MIN_AGE_MS
        if (!stale) return

        autoRefreshInFlight = true
        diagnosticText.text = "自动读取最新统计…"
        UsageClient.refresh(this) { result ->
            autoRefreshInFlight = false
            renderState(result.message)
        }
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
        realtimeButton.text = if (config.realtimeEnabled) {
            "近实时监控：已开启"
        } else {
            "近实时监控：已关闭"
        }

        floatingButton.text = when {
            config.floatingEnabled && Settings.canDrawOverlays(this) -> "悬浮窗：已开启"
            config.floatingEnabled -> "悬浮窗：等待系统授权"
            else -> "悬浮窗：已关闭"
        }

        balanceAlertButton.text = if (config.balanceAlertEnabled) {
            "余额预警：已开启"
        } else {
            "余额预警：已关闭"
        }

        loginStateText.text = when {
            config.webAuthToken.isNotBlank() -> "网页登录：已授权（可自动续期）"
            config.consoleCookie.isNotBlank() -> "网页登录：已保存 Cookie（兼容模式）"
            else -> "网页登录：未授权"
        }

        val stats = TrayStore.loadStats(this)
        statusText.text = if (stats == null) {
            "托盘状态：已就绪\n\n" +
                "余额            --\n" +
                "今日消费        --\n" +
                "今日请求        --\n" +
                "今日 Token      --\n" +
                "输入 / 输出     -- / --\n" +
                "RPM / TPM       -- / --\n" +
                "平均响应        --"
        } else {
            buildString {
                append("余额            ${stats.balance?.let(TrayNotification::money) ?: "--"}")
                append("\n今日消费        ${stats.todayCost?.let(TrayNotification::money) ?: "--"}")
                append("\n今日请求        ${stats.requests ?: "--"}")
                append("\n今日 Token      ${stats.totalTokens?.let(TrayNotification::tokens) ?: "--"}")
                append("\n输入 / 输出     ${stats.inputTokens?.let(TrayNotification::tokens) ?: "--"} / ${stats.outputTokens?.let(TrayNotification::tokens) ?: "--"}")
                append("\nRPM / TPM       ${stats.rpm?.let(TrayNotification::rate) ?: "--"} / ${stats.tpm?.let(TrayNotification::rate) ?: "--"}")
                append("\n平均响应        ${stats.avgResponseSeconds?.let(TrayNotification::seconds) ?: "--"}")
                append("\n\n最后更新        ${formatTime(stats.updatedAt)}")
            }
        }

        val last = message ?: TrayStore.loadLastMessage(this)
        diagnosticText.text = if (last.isBlank()) {
            "等待首次真实数据读取。"
        } else {
            "接口状态：$last"
        }
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQ_NOTIFICATION)
        } else {
            TrayNotification.show(this)
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_NOTIFICATION &&
            grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED
        ) {
            TrayNotification.show(this)
            syncRealtimeService()
            TrayStore.loadStats(this)?.let { BalanceAlert.evaluate(this, it) }
        }
    }

    private fun editField(hintText: String): EditText = EditText(this).apply {
        hint = hintText
        textSize = 14f
        setSingleLine(true)
        setPadding(dp(14), 0, dp(14), 0)
        setTextColor(Color.rgb(41, 48, 61))
        setHintTextColor(Color.rgb(145, 153, 164))
        background = roundedCard()
    }

    private fun secretField(hintText: String): EditText = editField(hintText).apply {
        inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        transformationMethod = PasswordTransformationMethod.getInstance()
    }

    private fun sectionTitle(textValue: String): TextView = TextView(this).apply {
        text = textValue
        textSize = 14f
        setTextColor(Color.rgb(78, 88, 102))
        setTypeface(typeface, Typeface.BOLD)
        setPadding(dp(4), dp(20), dp(4), dp(8))
    }

    private fun roundedCard(): GradientDrawable = GradientDrawable().apply {
        setColor(Color.WHITE)
        cornerRadius = dp(18).toFloat()
        setStroke(dp(1), Color.rgb(229, 235, 238))
    }

    private fun matchWrap() = LinearLayout.LayoutParams(
        LinearLayout.LayoutParams.MATCH_PARENT,
        LinearLayout.LayoutParams.WRAP_CONTENT
    )

    private fun matchHeight(heightDp: Int) = LinearLayout.LayoutParams(
        LinearLayout.LayoutParams.MATCH_PARENT,
        dp(heightDp)
    )

    private fun formatTime(timestamp: Long): String =
        SimpleDateFormat("MM-dd HH:mm:ss", Locale.getDefault()).format(Date(timestamp))

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()

    companion object {
        private const val REQ_NOTIFICATION = 10
        private const val AUTO_REFRESH_MIN_AGE_MS = 30_000L
    }
}
