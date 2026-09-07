package com.luming.tray

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Crash-safe launcher shell.
 *
 * This screen deliberately avoids SecureVault, WorkManager, foreground-service startup,
 * custom Application hooks and direct references to feature Activity classes during launch.
 * It only reads non-sensitive cached statistics from the normal preference file.
 */
class MainActivity : Activity() {
    private lateinit var balanceText: TextView
    private lateinit var costText: TextView
    private lateinit var tokenText: TextView
    private lateinit var requestsText: TextView
    private lateinit var responseText: TextView
    private lateinit var statusText: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        runCatching {
            window.statusBarColor = bg()
            window.navigationBarColor = bg()
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
            setContentView(buildUi())
            renderCachedStats()
        }.onFailure { showEmergencyScreen(it) }
    }

    override fun onResume() {
        super.onResume()
        runCatching { if (::balanceText.isInitialized) renderCachedStats() }
    }

    private fun buildUi(): View {
        val scroll = ScrollView(this).apply {
            setBackgroundColor(bg())
            overScrollMode = View.OVER_SCROLL_NEVER
        }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(22), dp(18), dp(30))
        }

        val header = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        header.addView(TextView(this).apply {
            text = "LM"; textSize = 15f; gravity = Gravity.CENTER; setTypeface(typeface, Typeface.BOLD); setTextColor(Color.WHITE)
            background = GradientDrawable().apply { setColor(accent()); cornerRadius = dp(16).toFloat() }
        }, LinearLayout.LayoutParams(dp(48), dp(48)))
        val title = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(12), 0, 0, 0) }
        title.addView(TextView(this).apply { text = "LuMing Tray"; textSize = 25f; setTypeface(typeface, Typeface.BOLD); setTextColor(primary()) })
        title.addView(TextView(this).apply { text = "SAFE UNIFIED API DASHBOARD"; textSize = 9.5f; letterSpacing = 0.08f; setTextColor(muted()) })
        header.addView(title, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        root.addView(header)

        val hero = panel().apply { setPadding(dp(20), dp(18), dp(20), dp(18)) }
        hero.addView(label("账户余额"))
        balanceText = valueText(36f)
        hero.addView(balanceText)
        val heroActions = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        heroActions.addView(action("刷新缓存") {
            sendBroadcast(Intent().setClassName(packageName, "$packageName.RefreshReceiver"))
            statusText.text = "已请求后台刷新 · 稍后返回首页查看"
        }, weight(end = 6))
        heroActions.addView(action("充值") { open("RechargeActivity") }, weight(start = 6))
        hero.addView(heroActions, full().apply { topMargin = dp(12) })
        root.addView(hero, full().apply { topMargin = dp(16) })

        root.addView(section("今日概览"))
        val row1 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val c = metric("今日消费"); costText = c.second
        val r = metric("请求次数"); requestsText = r.second
        row1.addView(c.first, weight(end = 6)); row1.addView(r.first, weight(start = 6)); root.addView(row1)
        val row2 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val t = metric("Token"); tokenText = t.second
        val a = metric("平均响应"); responseText = a.second
        row2.addView(t.first, weight(end = 6)); row2.addView(a.first, weight(start = 6)); root.addView(row2, full().apply { topMargin = dp(12) })

        root.addView(section("工作台"))
        root.addView(toolRow(
            tool("用量中心", "分布 · 趋势 · 请求日志") { open("UsageExplorerActivity") },
            tool("模型状态", "渠道 · 可用率 · 探活") { open("ModelAvailabilityActivity") }
        ))
        root.addView(toolRow(
            tool("API 管理", "Key · 分组 · 配额") { open("ApiKeyManagementActivity") },
            tool("消费分析", "成本 · 异常 · 预测") { open("AnalysisActivity") }
        ), full().apply { topMargin = dp(12) })

        root.addView(section("管理"))
        val manage = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        manage.addView(action("账户中心") { open("AccountActivity") }, weight(end = 6))
        manage.addView(action("应用设置") { open("SettingsActivity") }, weight(start = 6))
        root.addView(manage)

        statusText = TextView(this).apply {
            textSize = 11f; setTextColor(secondary()); setPadding(dp(14), dp(13), dp(14), dp(13)); background = panelBg()
        }
        root.addView(statusText, full().apply { topMargin = dp(16) })

        root.addView(TextView(this).apply {
            text = "LuMing Tray 0.17.1 · Safe Home"
            textSize = 10f; gravity = Gravity.CENTER; setTextColor(muted()); setPadding(0, dp(20), 0, 0)
        })

        scroll.addView(root)
        return scroll
    }

    private fun renderCachedStats() {
        val p = getSharedPreferences("luming_tray", MODE_PRIVATE)
        val updated = p.getLong("updatedAt", 0L)
        val balance = p.getString("balance", null)?.toDoubleOrNull()
        val cost = p.getString("todayCost", null)?.toDoubleOrNull()
        val requests = p.getString("requests", null)?.toLongOrNull()
        val tokens = p.getString("totalTokens", null)?.toLongOrNull()
        val response = p.getString("avgResponseSeconds", null)?.toDoubleOrNull()

        balanceText.text = balance?.let { String.format(Locale.US, "$%.4f", it) } ?: "--"
        balanceText.setTextColor(if (balance != null && balance <= 0.0) Color.rgb(205, 63, 76) else accent())
        costText.text = cost?.let { String.format(Locale.US, "$%.4f", it) } ?: "--"
        requestsText.text = requests?.toString() ?: "--"
        tokenText.text = tokens?.let(::compact) ?: "--"
        responseText.text = response?.let { String.format(Locale.US, "%.2fs", it) } ?: "--"
        statusText.text = if (updated > 0L) {
            "缓存数据正常 · ${SimpleDateFormat("MM-dd HH:mm:ss", Locale.getDefault()).format(Date(updated))}"
        } else {
            "安全启动模式 · 暂无缓存数据。账户、设置和各工作台可单独进入。"
        }
    }

    private fun showEmergencyScreen(error: Throwable) {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(28), dp(20), dp(28)); setBackgroundColor(Color.WHITE)
            addView(TextView(this@MainActivity).apply { text = "LuMing 安全启动"; textSize = 24f; setTypeface(typeface, Typeface.BOLD); setTextColor(Color.BLACK) })
            addView(TextView(this@MainActivity).apply {
                text = "首页初始化被拦截，没有让 App 直接退出。\n\n${error.javaClass.simpleName}: ${error.message ?: "无错误信息"}"
                textSize = 13f; setTextColor(Color.DKGRAY); setPadding(0, dp(16), 0, 0); setTextIsSelectable(true)
            })
        }
        setContentView(root)
    }

    private fun open(simpleName: String) {
        runCatching { startActivity(Intent().setClassName(packageName, "$packageName.$simpleName")) }
            .onFailure { statusText.text = "打开 $simpleName 失败：${it.javaClass.simpleName}" }
    }

    private fun toolRow(left: View, right: View) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL; addView(left, weight(end = 6)); addView(right, weight(start = 6))
    }
    private fun tool(title: String, subtitle: String, click: () -> Unit) = panel().apply {
        setPadding(dp(15), dp(15), dp(15), dp(15)); minimumHeight = dp(108); isClickable = true
        addView(TextView(this@MainActivity).apply { text = title; textSize = 15f; setTypeface(typeface, Typeface.BOLD); setTextColor(primary()) })
        addView(TextView(this@MainActivity).apply { text = subtitle; textSize = 10.5f; setTextColor(muted()); setPadding(0, dp(6), 0, 0) })
        addView(TextView(this@MainActivity).apply { text = "打开 →"; textSize = 11f; setTextColor(accent()); setPadding(0, dp(12), 0, 0) })
        setOnClickListener { click() }
    }
    private fun metric(label: String): Pair<LinearLayout, TextView> {
        val box = panel().apply { setPadding(dp(15), dp(14), dp(15), dp(14)); minimumHeight = dp(104) }
        val v = valueText(22f); box.addView(v); box.addView(label(label).apply { setPadding(0, dp(6), 0, 0) }); return box to v
    }
    private fun action(textValue: String, click: () -> Unit) = Button(this).apply {
        text = textValue; isAllCaps = false; textSize = 12f; setTypeface(typeface, Typeface.BOLD); setTextColor(secondary())
        minHeight = 0; minimumHeight = 0; stateListAnimator = null; background = buttonBg(); setOnClickListener { click() }
    }
    private fun section(textValue: String) = TextView(this).apply {
        text = textValue; textSize = 13f; setTypeface(typeface, Typeface.BOLD); setTextColor(secondary()); setPadding(dp(3), dp(22), dp(3), dp(9))
    }
    private fun label(textValue: String) = TextView(this).apply { text = textValue; textSize = 11.5f; setTextColor(muted()) }
    private fun valueText(size: Float) = TextView(this).apply { text = "--"; textSize = size; setTypeface(typeface, Typeface.BOLD); setTextColor(primary()); setPadding(0, dp(6), 0, dp(2)) }
    private fun panel() = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; background = panelBg(); elevation = dp(5).toFloat() }
    private fun panelBg() = GradientDrawable().apply { setColor(Color.rgb(242,247,249)); cornerRadius = dp(22).toFloat(); setStroke(dp(1), Color.rgb(221,229,233)) }
    private fun buttonBg() = GradientDrawable().apply { setColor(Color.rgb(239,244,246)); cornerRadius = dp(16).toFloat(); setStroke(dp(1), Color.rgb(221,229,233)) }
    private fun full() = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
    private fun weight(start: Int = 0, end: Int = 0) = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { leftMargin = dp(start); rightMargin = dp(end) }
    private fun compact(value: Long): String = when {
        value >= 1_000_000 -> String.format(Locale.US, "%.2fM", value / 1_000_000.0)
        value >= 1_000 -> String.format(Locale.US, "%.1fK", value / 1_000.0)
        else -> value.toString()
    }
    private fun bg() = Color.rgb(232,239,242)
    private fun primary() = Color.rgb(37,47,58)
    private fun secondary() = Color.rgb(75,88,101)
    private fun muted() = Color.rgb(118,131,143)
    private fun accent() = Color.rgb(25,157,130)
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
}