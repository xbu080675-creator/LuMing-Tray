package com.luming.tray

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
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
 * Minimal launcher built around the last known-good 0.15 startup contract.
 *
 * The first frame intentionally does not touch SecureVault, WorkManager, network clients,
 * foreground services or feature Activity classes. Runtime recovery is requested only after the
 * UI is already visible, so a provider/keystore/vendor failure cannot take the launcher down.
 */
class SafeMainActivity : Activity() {
    private lateinit var balanceText: TextView
    private lateinit var costText: TextView
    private lateinit var requestsText: TextView
    private lateinit var tokenText: TextView
    private lateinit var updatedText: TextView
    private lateinit var statusText: TextView

    private val handler = Handler(Looper.getMainLooper())
    private val restoreRunnable = Runnable { restoreRuntime() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = BG
        window.navigationBarColor = BG
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR

        try {
            setContentView(buildUi())
            renderCachedStats()
            statusText.text = "安全启动完成 · 正在恢复后台组件"
            handler.postDelayed(restoreRunnable, 900L)
        } catch (t: Throwable) {
            showEmergencyScreen(t)
        }
    }

    override fun onResume() {
        super.onResume()
        if (::balanceText.isInitialized) runCatching { renderCachedStats() }
    }

    override fun onDestroy() {
        handler.removeCallbacks(restoreRunnable)
        super.onDestroy()
    }

    private fun buildUi(): View {
        val scroll = ScrollView(this).apply {
            setBackgroundColor(BG)
            overScrollMode = View.OVER_SCROLL_NEVER
        }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(22), dp(18), dp(30))
        }

        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        header.addView(TextView(this).apply {
            text = "LM"
            textSize = 15f
            gravity = Gravity.CENTER
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(Color.WHITE)
            background = GradientDrawable().apply {
                setColor(ACCENT)
                cornerRadius = dp(16).toFloat()
            }
        }, LinearLayout.LayoutParams(dp(48), dp(48)))
        val titles = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), 0, 0, 0)
            addView(TextView(this@SafeMainActivity).apply {
                text = "LuMing Tray"
                textSize = 25f
                setTypeface(typeface, Typeface.BOLD)
                setTextColor(PRIMARY)
            })
            addView(TextView(this@SafeMainActivity).apply {
                text = "SAFE START · 0.15 BASELINE"
                textSize = 9.5f
                letterSpacing = 0.08f
                setTextColor(MUTED)
            })
        }
        header.addView(titles, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        root.addView(header)

        val hero = panel().apply { setPadding(dp(20), dp(18), dp(20), dp(18)) }
        hero.addView(label("账户余额"))
        balanceText = TextView(this).apply {
            text = "--"
            textSize = 38f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(ACCENT)
            setPadding(0, dp(8), 0, 0)
        }
        updatedText = TextView(this).apply {
            text = "等待缓存"
            textSize = 11f
            setTextColor(MUTED)
        }
        hero.addView(balanceText)
        hero.addView(updatedText)
        root.addView(hero, full().apply { topMargin = dp(16) })

        root.addView(section("今日概览"))
        val row1 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val cost = metric("今日消费")
        costText = cost.second
        val requests = metric("请求次数")
        requestsText = requests.second
        row1.addView(cost.first, weight(end = 6))
        row1.addView(requests.first, weight(start = 6))
        root.addView(row1)

        val row2 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val tokens = metric("今日 Token")
        tokenText = tokens.second
        row2.addView(tokens.first, weight(end = 6))
        row2.addView(action("刷新数据") { requestRefresh() }, weight(start = 6))
        root.addView(row2, full().apply { topMargin = dp(12) })

        root.addView(section("工作台"))
        root.addView(toolRow(
            tool("用量中心", "分布 · 趋势 · 请求日志") { open("UsageExplorerActivity") },
            tool("模型状态", "渠道 · 可用率 · 后台探活") { open("ModelAvailabilityActivity") }
        ))
        root.addView(toolRow(
            tool("API 管理", "Key · 分组 · 配额 · 限速") { open("ApiKeyManagementActivity") },
            tool("消费分析", "成本归因 · 异常 · 预测") { open("AnalysisActivity") }
        ), full().apply { topMargin = dp(12) })

        root.addView(section("管理"))
        val manage = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        manage.addView(action("账户中心") { open("AccountActivity") }, weight(end = 6))
        manage.addView(action("应用设置") { open("SettingsActivity") }, weight(start = 6))
        root.addView(manage)

        statusText = TextView(this).apply {
            textSize = 11f
            setTextColor(SECONDARY)
            setPadding(dp(14), dp(13), dp(14), dp(13))
            background = panelBackground()
        }
        root.addView(statusText, full().apply { topMargin = dp(16) })

        root.addView(TextView(this).apply {
            text = "LuMing Tray 0.17.5 · 0.15 Safe Launcher"
            textSize = 10f
            gravity = Gravity.CENTER
            setTextColor(MUTED)
            setPadding(0, dp(20), 0, 0)
        })

        scroll.addView(root)
        return scroll
    }

    private fun renderCachedStats() {
        val p = getSharedPreferences("luming_tray", MODE_PRIVATE)
        val updatedAt = p.getLong("updatedAt", 0L)
        val balance = p.getString("balance", null)?.toDoubleOrNull()
        val cost = p.getString("todayCost", null)?.toDoubleOrNull()
        val requests = p.getString("requests", null)?.toLongOrNull()
        val tokens = p.getString("totalTokens", null)?.toLongOrNull()

        balanceText.text = balance?.let { money(it) } ?: "--"
        costText.text = cost?.let { money(it) } ?: "--"
        requestsText.text = requests?.toString() ?: "--"
        tokenText.text = tokens?.let(::compact) ?: "--"
        updatedText.text = if (updatedAt > 0L) {
            "缓存更新 ${SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(updatedAt))}"
        } else {
            "尚无缓存 · 打开设置完成网页登录后即可读取"
        }
    }

    private fun restoreRuntime() {
        runCatching {
            val intent = Intent("com.luming.tray.action.RESTORE_RUNTIME")
                .setClassName(packageName, "$packageName.BootReceiver")
            sendBroadcast(intent)
            statusText.text = "首页稳定 · 后台恢复请求已发送"
        }.onFailure {
            statusText.text = "首页稳定 · 后台恢复失败：${it.javaClass.simpleName}"
        }
    }

    private fun requestRefresh() {
        runCatching {
            sendBroadcast(Intent().setClassName(packageName, "$packageName.RefreshReceiver"))
            statusText.text = "已请求刷新 · 后台完成后缓存会自动更新"
            handler.postDelayed({ runCatching { renderCachedStats() } }, 1800L)
        }.onFailure {
            statusText.text = "刷新请求失败：${it.javaClass.simpleName}"
        }
    }

    private fun open(simpleName: String) {
        runCatching {
            startActivity(Intent().setClassName(packageName, "$packageName.$simpleName"))
        }.onFailure {
            statusText.text = "$simpleName 打开失败：${it.javaClass.simpleName}"
        }
    }

    private fun showEmergencyScreen(t: Throwable) {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(28), dp(20), dp(28))
            setBackgroundColor(BG)
            addView(TextView(this@SafeMainActivity).apply {
                text = "LuMing 安全启动模式"
                textSize = 24f
                setTypeface(typeface, Typeface.BOLD)
                setTextColor(PRIMARY)
            })
            addView(TextView(this@SafeMainActivity).apply {
                text = "启动 UI 出错，但进程没有退出。\n${t.javaClass.name}\n${t.message.orEmpty().take(240)}"
                textSize = 12f
                setTextColor(SECONDARY)
                setPadding(0, dp(16), 0, 0)
            })
        }
        setContentView(root)
    }

    private fun panel() = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        background = panelBackground()
        elevation = dp(4).toFloat()
    }

    private fun panelBackground() = GradientDrawable().apply {
        setColor(PANEL)
        cornerRadius = dp(22).toFloat()
        setStroke(dp(1), BORDER)
    }

    private fun label(value: String) = TextView(this).apply {
        text = value
        textSize = 11f
        setTypeface(typeface, Typeface.BOLD)
        setTextColor(MUTED)
    }

    private fun section(value: String) = TextView(this).apply {
        text = value
        textSize = 13f
        setTypeface(typeface, Typeface.BOLD)
        setTextColor(SECONDARY)
        setPadding(dp(3), dp(22), dp(3), dp(9))
    }

    private fun metric(title: String): Pair<View, TextView> {
        val box = panel().apply { setPadding(dp(15), dp(14), dp(15), dp(14)) }
        box.addView(label(title))
        val value = TextView(this).apply {
            text = "--"
            textSize = 22f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(PRIMARY)
            setPadding(0, dp(6), 0, 0)
        }
        box.addView(value)
        return box to value
    }

    private fun action(textValue: String, block: () -> Unit) = Button(this).apply {
        text = textValue
        isAllCaps = false
        textSize = 12f
        setTypeface(typeface, Typeface.BOLD)
        setTextColor(SECONDARY)
        background = GradientDrawable().apply {
            setColor(PANEL)
            cornerRadius = dp(18).toFloat()
            setStroke(dp(1), BORDER)
        }
        stateListAnimator = null
        minHeight = 0
        minimumHeight = 0
        setOnClickListener { block() }
    }

    private fun tool(title: String, sub: String, block: () -> Unit): View = panel().apply {
        setPadding(dp(14), dp(14), dp(14), dp(14))
        addView(TextView(this@SafeMainActivity).apply {
            text = title
            textSize = 14f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(PRIMARY)
        })
        addView(TextView(this@SafeMainActivity).apply {
            text = sub
            textSize = 10f
            setTextColor(MUTED)
            setPadding(0, dp(5), 0, 0)
        })
        setOnClickListener { block() }
    }

    private fun toolRow(left: View, right: View) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        addView(left, weight(end = 6))
        addView(right, weight(start = 6))
    }

    private fun full() = LinearLayout.LayoutParams(
        LinearLayout.LayoutParams.MATCH_PARENT,
        LinearLayout.LayoutParams.WRAP_CONTENT
    )

    private fun weight(start: Int = 0, end: Int = 0) = LinearLayout.LayoutParams(
        0,
        LinearLayout.LayoutParams.WRAP_CONTENT,
        1f
    ).apply {
        leftMargin = dp(start)
        rightMargin = dp(end)
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    private fun money(value: Double) = String.format(Locale.US, "$%.4f", value).trimEnd('0').trimEnd('.')
    private fun compact(value: Long): String = when {
        value >= 1_000_000 -> String.format(Locale.US, "%.2fM", value / 1_000_000.0).trimEnd('0').trimEnd('.')
        value >= 1_000 -> String.format(Locale.US, "%.1fK", value / 1_000.0).trimEnd('0').trimEnd('.')
        else -> value.toString()
    }

    companion object {
        private val BG = Color.rgb(232, 239, 242)
        private val PANEL = Color.rgb(242, 247, 249)
        private val BORDER = Color.rgb(221, 229, 233)
        private val PRIMARY = Color.rgb(37, 47, 58)
        private val SECONDARY = Color.rgb(75, 88, 101)
        private val MUTED = Color.rgb(118, 131, 143)
        private val ACCENT = Color.rgb(25, 157, 130)
    }
}
