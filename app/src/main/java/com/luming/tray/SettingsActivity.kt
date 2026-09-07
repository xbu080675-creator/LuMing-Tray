package com.luming.tray

import android.app.Activity
import android.content.Intent
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
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
import android.widget.Toast
import java.util.Locale

/** Low-frequency configuration moved out of the dashboard in 0.17.0. */
class SettingsActivity : Activity() {
    private lateinit var baseUrlField: EditText
    private lateinit var apiKeyField: EditText
    private lateinit var accessTokenField: EditText
    private lateinit var thresholdField: EditText
    private lateinit var persistentButton: Button
    private lateinit var spendingButton: Button
    private lateinit var themeSystem: Button
    private lateinit var themeLight: Button
    private lateinit var themeDark: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        LuMingTheme.applySystemBars(this)
        setContentView(buildUi())
        loadState()
    }

    private fun buildUi(): View {
        val scroll = ScrollView(this).apply { setBackgroundColor(bgColor()); overScrollMode = View.OVER_SCROLL_NEVER }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(16), dp(18), dp(34))
            setOnApplyWindowInsetsListener { view, insets ->
                val top = if (Build.VERSION.SDK_INT >= 30) insets.getInsets(WindowInsets.Type.statusBars()).top else {
                    @Suppress("DEPRECATION") insets.systemWindowInsetTop
                }
                val bottom = if (Build.VERSION.SDK_INT >= 30) insets.getInsets(WindowInsets.Type.navigationBars()).bottom else {
                    @Suppress("DEPRECATION") insets.systemWindowInsetBottom
                }
                view.setPadding(dp(18), dp(12) + top, dp(18), dp(24) + bottom)
                insets
            }
        }

        val header = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        header.addView(TextView(this).apply {
            text = "‹"; textSize = 34f; gravity = Gravity.CENTER; setTextColor(textPrimary()); setOnClickListener { finish() }
        }, LinearLayout.LayoutParams(dp(44), dp(48)))
        val title = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        title.addView(TextView(this).apply {
            text = "应用设置"; textSize = 25f; setTypeface(typeface, Typeface.BOLD); setTextColor(textPrimary())
        })
        title.addView(TextView(this).apply {
            text = "PROVIDER · ALERTS · APPEARANCE"; textSize = 9.5f; letterSpacing = 0.08f; setTextColor(textMuted())
        })
        header.addView(title, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        root.addView(header)

        root.addView(sectionTitle("站点与授权"))
        val provider = panel()
        provider.addView(label("站点 / API 基地址"))
        baseUrlField = editField("https://example.com/v1")
        provider.addView(baseUrlField, matchHeight(52))
        provider.addView(actionButton("网页登录并授权") {
            saveNonSecretSettings()
            startActivity(Intent(this, LoginActivity::class.java))
        }, matchHeight(50).apply { topMargin = dp(12) })
        provider.addView(TextView(this).apply {
            text = "网页登录会话由 SecureVault 加密保存；登录/支付仍由站点自己的 WebView 流程处理。"
            textSize = 10.5f; setTextColor(textMuted()); setPadding(dp(2), dp(9), dp(2), 0)
        })
        root.addView(provider)

        root.addView(sectionTitle("兼容凭据"))
        val secrets = panel()
        secrets.addView(TextView(this).apply {
            val cfg = TrayStore.loadConfig(this@SettingsActivity)
            text = buildString {
                append("API Key："); append(if (cfg.apiKey.isBlank()) "未保存" else "已安全保存")
                append("\nAccess Token："); append(if (cfg.accessToken.isBlank()) "未保存" else "已安全保存")
            }
            textSize = 11f; setTextColor(textSecondary())
        })
        secrets.addView(label("替换 API Key").apply { setPadding(0, dp(12), 0, dp(6)) })
        apiKeyField = secretField("留空 = 不修改现有 Key")
        secrets.addView(apiKeyField, matchHeight(52))
        secrets.addView(label("替换 Access Token").apply { setPadding(0, dp(12), 0, dp(6)) })
        accessTokenField = secretField("留空 = 不修改现有 Token")
        secrets.addView(accessTokenField, matchHeight(52))
        secrets.addView(TextView(this).apply {
            text = "这里永远不会回填或显示已保存密钥；输入新值只代表替换。"
            textSize = 10.5f; setTextColor(textMuted()); setPadding(dp(2), dp(9), dp(2), 0)
        })
        root.addView(secrets)

        root.addView(sectionTitle("提醒与后台"))
        val alerts = panel()
        alerts.addView(label("LuMing 本地低余额阈值（USD）"))
        thresholdField = editField("0.50").apply { inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL }
        alerts.addView(thresholdField, matchHeight(52))
        val toggles = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        persistentButton = actionButton("常驻通知") { togglePersistent() }
        spendingButton = actionButton("异常消费") { toggleSpending() }
        toggles.addView(persistentButton, weighted(end = 6))
        toggles.addView(spendingButton, weighted(start = 6))
        alerts.addView(toggles, matchWrap().apply { topMargin = dp(12) })
        alerts.addView(TextView(this).apply {
            text = "实时监控、悬浮窗和余额提醒的高频开关保留在首页；这里只放低频参数。"
            textSize = 10.5f; setTextColor(textMuted()); setPadding(dp(2), dp(9), dp(2), 0)
        })
        root.addView(alerts)

        root.addView(sectionTitle("外观"))
        val appearance = panel()
        appearance.addView(TextView(this).apply {
            text = "显示模式"; textSize = 14f; setTypeface(typeface, Typeface.BOLD); setTextColor(textPrimary())
        })
        val themeRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        themeSystem = actionButton("跟随系统") { changeTheme(LuMingThemeMode.SYSTEM) }
        themeLight = actionButton("浅色") { changeTheme(LuMingThemeMode.LIGHT) }
        themeDark = actionButton("深色") { changeTheme(LuMingThemeMode.DARK) }
        themeRow.addView(themeSystem, weighted(end = 4))
        themeRow.addView(themeLight, weighted(start = 2, end = 2))
        themeRow.addView(themeDark, weighted(start = 4))
        appearance.addView(themeRow, matchWrap().apply { topMargin = dp(10) })
        root.addView(appearance)

        root.addView(actionButton("保存设置") { saveAll() }, matchHeight(52).apply { topMargin = dp(20) })
        root.addView(TextView(this).apply {
            text = "LuMing Tray 0.17.0 · Settings"
            textSize = 10f; gravity = Gravity.CENTER; setTextColor(textMuted()); setPadding(0, dp(20), 0, 0)
        })

        scroll.addView(root)
        return scroll
    }

    private fun loadState() {
        val config = TrayStore.loadConfig(this)
        baseUrlField.setText(config.baseUrl)
        thresholdField.setText(String.format(Locale.US, "%.2f", config.balanceAlertThreshold))
        styleState(persistentButton, config.persistentNotificationEnabled, if (config.persistentNotificationEnabled) "常驻通知 · ON" else "常驻通知 · OFF")
        styleState(spendingButton, config.spendingAlertEnabled, if (config.spendingAlertEnabled) "异常消费 · ON" else "异常消费 · OFF")
        updateThemeButtons()
    }

    private fun saveNonSecretSettings() {
        val old = TrayStore.loadConfig(this)
        val base = baseUrlField.text.toString().trim().trimEnd('/')
        val threshold = thresholdField.text.toString().trim().toDoubleOrNull()?.coerceAtLeast(0.0) ?: old.balanceAlertThreshold
        TrayStore.saveConfig(this, old.copy(baseUrl = if (base.isBlank()) old.baseUrl else base, balanceAlertThreshold = threshold))
    }

    private fun saveAll() {
        val old = TrayStore.loadConfig(this)
        val base = baseUrlField.text.toString().trim().trimEnd('/')
        if (!base.startsWith("https://") && !base.startsWith("http://")) {
            baseUrlField.error = "需要以 https:// 或 http:// 开头"
            return
        }
        val threshold = thresholdField.text.toString().trim().toDoubleOrNull()?.coerceAtLeast(0.0)
            ?: old.balanceAlertThreshold
        val newApiKey = apiKeyField.text.toString().trim()
        val newAccess = accessTokenField.text.toString().trim()
        val changedThreshold = threshold != old.balanceAlertThreshold
        TrayStore.saveConfig(this, old.copy(
            baseUrl = base,
            apiKey = if (newApiKey.isBlank()) old.apiKey else newApiKey,
            accessToken = if (newAccess.isBlank()) old.accessToken else newAccess,
            balanceAlertThreshold = threshold
        ))
        apiKeyField.text?.clear()
        accessTokenField.text?.clear()
        if (changedThreshold) {
            TrayStore.saveBalanceAlertState(this, 0)
            TrayStore.loadStats(this)?.let { BalanceAlert.evaluate(this, it) }
        }
        TrayScheduler.ensure(this)
        Toast.makeText(this, "设置已保存", Toast.LENGTH_SHORT).show()
    }

    private fun togglePersistent() {
        val old = TrayStore.loadConfig(this)
        val next = !old.persistentNotificationEnabled
        TrayStore.saveConfig(this, old.copy(persistentNotificationEnabled = next))
        if (next || old.realtimeEnabled) RealtimeUsageService.start(this) else RealtimeUsageService.stop(this)
        loadState()
    }

    private fun toggleSpending() {
        val old = TrayStore.loadConfig(this)
        TrayStore.saveConfig(this, old.copy(spendingAlertEnabled = !old.spendingAlertEnabled))
        loadState()
    }

    private fun changeTheme(mode: LuMingThemeMode) {
        LuMingTheme.setMode(this, mode)
        recreate()
    }

    private fun updateThemeButtons() {
        val current = LuMingTheme.mode(this)
        styleState(themeSystem, current == LuMingThemeMode.SYSTEM, "跟随系统")
        styleState(themeLight, current == LuMingThemeMode.LIGHT, "浅色")
        styleState(themeDark, current == LuMingThemeMode.DARK, "深色")
    }

    private fun styleState(button: Button, active: Boolean, textValue: String) {
        button.text = textValue
        button.setTextColor(if (active) accentDark() else textSecondary())
        button.background = buttonBackground(active)
    }

    private fun panel() = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(15), dp(16), dp(15)); background = panelBackground(); elevation = dp(6).toFloat()
    }

    private fun label(value: String) = TextView(this).apply {
        text = value; textSize = 11f; setTypeface(typeface, Typeface.BOLD); setTextColor(textMuted()); setPadding(dp(2), 0, 0, dp(6))
    }

    private fun sectionTitle(value: String) = TextView(this).apply {
        text = value; textSize = 13f; setTypeface(typeface, Typeface.BOLD); setTextColor(textSecondary()); setPadding(dp(3), dp(22), dp(3), dp(9))
    }

    private fun editField(hintValue: String) = EditText(this).apply {
        hint = hintValue; textSize = 13.5f; setSingleLine(true); setPadding(dp(14), 0, dp(14), 0)
        setTextColor(textPrimary()); setHintTextColor(LuMingTheme.hint(this@SettingsActivity)); background = inputBackground()
    }

    private fun secretField(hintValue: String) = editField(hintValue).apply {
        inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        transformationMethod = PasswordTransformationMethod.getInstance()
    }

    private fun actionButton(label: String, onClick: () -> Unit) = Button(this).apply {
        text = label; isAllCaps = false; textSize = 12f; setTypeface(typeface, Typeface.BOLD); setTextColor(textSecondary())
        background = buttonBackground(false); stateListAnimator = null; minHeight = 0; minimumHeight = 0; setOnClickListener { onClick() }
    }

    private fun panelBackground() = GradientDrawable().apply {
        setColor(LuMingTheme.panel(this@SettingsActivity)); cornerRadius = dp(24).toFloat(); setStroke(dp(1), LuMingTheme.border(this@SettingsActivity))
    }
    private fun inputBackground() = GradientDrawable().apply {
        setColor(LuMingTheme.input(this@SettingsActivity)); cornerRadius = dp(17).toFloat(); setStroke(dp(1), LuMingTheme.border(this@SettingsActivity))
    }
    private fun buttonBackground(active: Boolean) = GradientDrawable().apply {
        setColor(if (active) LuMingTheme.activeBg(this@SettingsActivity) else LuMingTheme.panelAlt(this@SettingsActivity))
        cornerRadius = dp(17).toFloat(); setStroke(dp(1), if (active) LuMingTheme.activeBorder(this@SettingsActivity) else LuMingTheme.border(this@SettingsActivity))
    }

    private fun weighted(start: Int = 0, end: Int = 0) = LinearLayout.LayoutParams(0, dp(48), 1f).apply {
        leftMargin = dp(start); rightMargin = dp(end)
    }
    private fun matchWrap() = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
    private fun matchHeight(h: Int) = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(h))
    private fun bgColor() = LuMingTheme.bg(this)
    private fun textPrimary() = LuMingTheme.textPrimary(this)
    private fun textSecondary() = LuMingTheme.textSecondary(this)
    private fun textMuted() = LuMingTheme.textMuted(this)
    private fun accentDark() = LuMingTheme.accentDark(this)
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
}
