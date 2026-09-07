package com.luming.tray

import android.app.Activity
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
import java.util.Locale

/**
 * Lifecycle-isolated model availability screen.
 *
 * The previous screen let raw network callbacks and its 60-second ticker outlive a finished
 * Activity. Re-entering the screen could therefore leave multiple stale render/ticker chains in
 * the same process. This implementation owns one request generation at a time, never renders into
 * a destroyed Activity, and deliberately avoids custom Canvas rendering in the critical path.
 */
class ModelAvailabilitySafeActivity : Activity() {
    private lateinit var content: LinearLayout
    private lateinit var statusText: TextView
    private lateinit var availabilityTab: Button
    private lateinit var catalogTab: Button

    private val handler = Handler(Looper.getMainLooper())
    private var report: ModelAvailabilityReport? = null
    private var loading = false
    private var resumed = false
    private var destroyed = false
    private var generation = 0
    private var mode = MODE_AVAILABILITY
    private var countdown = AUTO_REFRESH_SECONDS

    private val ticker = object : Runnable {
        override fun run() {
            if (!resumed || destroyed || loading) return
            countdown = (countdown - 1).coerceAtLeast(0)
            updateStatus()
            if (countdown == 0) loadReport() else handler.postDelayed(this, 1_000L)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        try {
            applyBarsConservatively()
            setContentView(buildUi())
            renderSafe()
            loadReport()
        } catch (t: Throwable) {
            showEmergency(t)
        }
    }

    override fun onResume() {
        super.onResume()
        resumed = true
        if (::content.isInitialized) renderSafe()
        scheduleTicker()
    }

    override fun onPause() {
        resumed = false
        handler.removeCallbacks(ticker)
        super.onPause()
    }

    override fun onDestroy() {
        destroyed = true
        resumed = false
        generation += 1
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
            setPadding(dp(18), dp(18), dp(18), dp(34))
        }

        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        header.addView(TextView(this).apply {
            text = "‹"
            textSize = 34f
            gravity = Gravity.CENTER
            setTextColor(primary())
            setOnClickListener { finish() }
        }, LinearLayout.LayoutParams(dp(44), dp(48)))

        val titleBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        titleBox.addView(TextView(this).apply {
            text = "模型可用性"
            textSize = 25f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(primary())
        })
        titleBox.addView(TextView(this).apply {
            text = "MODEL AVAILABILITY · SAFE RENDERER"
            textSize = 9.5f
            letterSpacing = 0.08f
            setTextColor(muted())
        })
        header.addView(titleBox, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        root.addView(header)

        val tabs = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        availabilityTab = actionButton("稳定性") {
            mode = MODE_AVAILABILITY
            styleTabs()
            renderSafe()
        }
        catalogTab = actionButton("可用模型") {
            mode = MODE_CATALOG
            styleTabs()
            renderSafe()
        }
        tabs.addView(availabilityTab, weighted(end = 6))
        tabs.addView(catalogTab, weighted(start = 6))
        root.addView(tabs, matchWrap().apply { topMargin = dp(14) })
        styleTabs()

        val statusPanel = panel().apply { setPadding(dp(14), dp(12), dp(14), dp(12)) }
        val statusRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        statusText = TextView(this).apply {
            text = "准备读取模型状态"
            textSize = 10.5f
            setTextColor(secondary())
        }
        statusRow.addView(statusText, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        statusRow.addView(actionButton("刷新") { loadReport() }, LinearLayout.LayoutParams(dp(86), dp(42)))
        statusPanel.addView(statusRow)
        root.addView(statusPanel, matchWrap().apply { topMargin = dp(12) })

        content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(content, matchWrap().apply { topMargin = dp(12) })

        root.addView(TextView(this).apply {
            text = "LuMing Tray 0.17.6.3 · lifecycle-isolated model renderer"
            textSize = 9.5f
            gravity = Gravity.CENTER
            setTextColor(muted())
            setPadding(0, dp(22), 0, 0)
        })

        scroll.addView(root)
        return scroll
    }

    private fun loadReport() {
        if (loading || destroyed) return
        loading = true
        handler.removeCallbacks(ticker)
        countdown = AUTO_REFRESH_SECONDS
        updateStatus("正在同步站点模型数据…")
        val requestGeneration = ++generation
        val app = applicationContext

        Thread {
            val result = try {
                ModelAvailabilityClient.loadBlocking(app)
            } catch (t: Throwable) {
                ModelAvailabilityReport(
                    channels = emptyList(),
                    monitors = emptyList(),
                    message = "模型状态读取异常：${t.javaClass.simpleName}${t.message?.let { ": $it" }.orEmpty()}"
                )
            }

            runOnUiThread {
                if (destroyed || isFinishing || isDestroyed || requestGeneration != generation) return@runOnUiThread
                loading = false
                report = result
                countdown = AUTO_REFRESH_SECONDS
                if (resumed) {
                    renderSafe()
                    scheduleTicker()
                }
            }
        }.apply {
            name = "LuMing-ModelSafe-$requestGeneration"
            isDaemon = true
            start()
        }
    }

    private fun scheduleTicker() {
        handler.removeCallbacks(ticker)
        if (resumed && !destroyed && !loading) handler.postDelayed(ticker, 1_000L)
    }

    private fun updateStatus(override: String? = null) {
        if (!::statusText.isInitialized) return
        statusText.text = override ?: when {
            loading -> "正在同步站点模型数据…"
            report == null -> "等待首次读取"
            else -> "${report?.message.orEmpty()} · ${countdown}s 后刷新"
        }
    }

    private fun renderSafe() {
        if (!::content.isInitialized || destroyed) return
        try {
            content.removeAllViews()
            val current = report
            if (current == null) {
                content.addView(emptyPanel("正在读取可用渠道与模型健康状态…"))
                updateStatus()
                return
            }
            if (mode == MODE_CATALOG) renderCatalog(current) else renderAvailability(current)
            updateStatus()
        } catch (t: Throwable) {
            content.removeAllViews()
            content.addView(emptyPanel("模型页面渲染已被安全拦截\n${t.javaClass.simpleName}\n${t.message.orEmpty().take(220)}"))
            updateStatus("渲染异常已隔离 · 主进程保持运行")
        }
    }

    private fun renderAvailability(current: ModelAvailabilityReport) {
        val monitors = current.monitors
        if (monitors.isEmpty()) {
            content.addView(emptyPanel("${current.message}\n\n当前没有可显示的 channel-monitors 数据。"))
            return
        }

        val normal = monitors.count { severity(it.status) == 0 }
        val degraded = monitors.count { severity(it.status) == 1 }
        val failed = monitors.size - normal - degraded
        val summary = panel().apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(14), dp(13), dp(14), dp(13))
        }
        summary.addView(metric("监控", monitors.size.toString()), LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        summary.addView(metric("正常", normal.toString()), LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        summary.addView(metric("降级", degraded.toString()), LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        summary.addView(metric("故障", failed.toString()), LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        content.addView(summary)

        monitors.take(MAX_MONITORS).forEachIndexed { index, item ->
            val card = panel().apply { setPadding(dp(15), dp(14), dp(15), dp(14)) }
            val head = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            head.addView(TextView(this).apply {
                text = item.name.ifBlank { item.model.ifBlank { "未命名监控" } }
                textSize = 14f
                maxLines = 1
                setTypeface(typeface, Typeface.BOLD)
                setTextColor(primary())
            }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            head.addView(TextView(this).apply {
                text = statusLabel(item.status)
                textSize = 10f
                gravity = Gravity.CENTER
                setTypeface(typeface, Typeface.BOLD)
                setTextColor(statusColor(item.status))
                setPadding(dp(9), dp(5), dp(9), dp(5))
                background = pill(if (severity(item.status) == 0) positiveBg() else neutralBg())
            })
            card.addView(head)

            card.addView(TextView(this).apply {
                text = buildString {
                    append(item.provider.ifBlank { "unknown" })
                    if (item.groupName.isNotBlank()) append(" · ${item.groupName}")
                    if (item.model.isNotBlank()) append("\n${item.model}")
                }
                textSize = 10f
                setTextColor(muted())
                maxLines = 2
                setPadding(0, dp(6), 0, 0)
            })

            val av7 = item.availability(7)?.let(::percent) ?: "--"
            val av15 = item.availability(15)?.let(::percent) ?: "--"
            val av30 = item.availability(30)?.let(::percent) ?: "--"
            card.addView(TextView(this).apply {
                text = "可用率  7天 $av7   ·   15天 $av15   ·   30天 $av30"
                textSize = 11f
                setTextColor(secondary())
                setPadding(0, dp(10), 0, 0)
            })
            card.addView(TextView(this).apply {
                text = "延迟 ${item.latencyMs?.let { "${it}ms" } ?: "--"}   ·   PING ${item.pingLatencyMs?.let { "${it}ms" } ?: "--"}"
                textSize = 10.5f
                setTextColor(secondary())
                setPadding(0, dp(6), 0, 0)
            })

            if (item.timeline.isNotEmpty()) {
                val timeline = item.timeline.takeLast(60)
                val ok = timeline.count { severity(it.status) == 0 }
                val warn = timeline.count { severity(it.status) == 1 }
                val bad = timeline.size - ok - warn
                card.addView(TextView(this).apply {
                    text = "近 ${timeline.size} 次探活：正常 $ok · 降级 $warn · 故障 $bad"
                    textSize = 9.8f
                    setTextColor(muted())
                    setPadding(0, dp(7), 0, 0)
                })
            }
            content.addView(card, matchWrap().apply { topMargin = if (index == 0) dp(12) else dp(10) })
        }

        if (monitors.size > MAX_MONITORS) {
            content.addView(emptyPanel("为保证页面稳定，当前显示前 $MAX_MONITORS / ${monitors.size} 路监控。后台仍会监控全部模型。"), matchWrap().apply { topMargin = dp(10) })
        }
    }

    private fun renderCatalog(current: ModelAvailabilityReport) {
        if (current.channels.isEmpty()) {
            content.addView(emptyPanel("${current.message}\n\n当前没有可显示的 channels/available 数据。"))
            return
        }

        current.channels.take(MAX_CHANNELS).forEachIndexed { index, channel ->
            val card = panel().apply { setPadding(dp(15), dp(14), dp(15), dp(14)) }
            card.addView(TextView(this).apply {
                text = channel.name
                textSize = 15f
                setTypeface(typeface, Typeface.BOLD)
                setTextColor(primary())
            })
            if (channel.description.isNotBlank()) {
                card.addView(TextView(this).apply {
                    text = channel.description.take(500)
                    textSize = 10f
                    setTextColor(secondary())
                    setPadding(0, dp(5), 0, 0)
                })
            }
            channel.platforms.take(MAX_PLATFORMS_PER_CHANNEL).forEach { section ->
                card.addView(TextView(this).apply {
                    val groupText = section.groups.take(12).joinToString(" · ") { group ->
                        "${group.name} ${ModelAvailabilityClient.rateLabel(group.rateMultiplier)}"
                    }.ifBlank { "无分组信息" }
                    val shownModels = section.models.take(MAX_MODELS_PER_PLATFORM)
                    val modelText = shownModels.joinToString(" · ").ifBlank { "暂无模型" }
                    val more = if (section.models.size > shownModels.size) " · … +${section.models.size - shownModels.size}" else ""
                    text = "${section.platform.uppercase(Locale.US)}\n分组：$groupText\n模型：$modelText$more"
                    textSize = 10f
                    setTextColor(secondary())
                    setPadding(0, dp(11), 0, 0)
                })
            }
            content.addView(card, matchWrap().apply { topMargin = if (index == 0) 0 else dp(10) })
        }
    }

    private fun metric(label: String, value: String): View = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER
        addView(TextView(this@ModelAvailabilitySafeActivity).apply {
            text = value
            textSize = 18f
            gravity = Gravity.CENTER
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(primary())
        })
        addView(TextView(this@ModelAvailabilitySafeActivity).apply {
            text = label
            textSize = 9f
            gravity = Gravity.CENTER
            setTextColor(muted())
        })
    }

    private fun emptyPanel(message: String) = TextView(this).apply {
        text = message
        textSize = 11f
        setTextColor(secondary())
        setPadding(dp(16), dp(16), dp(16), dp(16))
        background = panelBackground()
    }

    private fun panel() = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        background = panelBackground()
        elevation = dp(3).toFloat()
    }

    private fun panelBackground() = GradientDrawable().apply {
        setColor(panelColor())
        cornerRadius = dp(20).toFloat()
        setStroke(dp(1), border())
    }

    private fun actionButton(label: String, block: () -> Unit) = Button(this).apply {
        text = label
        isAllCaps = false
        textSize = 11.5f
        setTypeface(typeface, Typeface.BOLD)
        setTextColor(secondary())
        background = buttonBackground(false)
        stateListAnimator = null
        minHeight = 0
        minimumHeight = 0
        setOnClickListener { block() }
    }

    private fun styleTabs() {
        if (!::availabilityTab.isInitialized || !::catalogTab.isInitialized) return
        styleTab(availabilityTab, mode == MODE_AVAILABILITY)
        styleTab(catalogTab, mode == MODE_CATALOG)
    }

    private fun styleTab(button: Button, active: Boolean) {
        button.setTextColor(if (active) accent() else secondary())
        button.background = buttonBackground(active)
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

    private fun weighted(start: Int = 0, end: Int = 0) = LinearLayout.LayoutParams(0, dp(48), 1f).apply {
        leftMargin = dp(start)
        rightMargin = dp(end)
    }

    private fun matchWrap() = LinearLayout.LayoutParams(
        LinearLayout.LayoutParams.MATCH_PARENT,
        LinearLayout.LayoutParams.WRAP_CONTENT
    )

    private fun applyBarsConservatively() {
        runCatching {
            window.statusBarColor = bg()
            window.navigationBarColor = bg()
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = if (LuMingTheme.isDark(this)) 0 else View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
        }
    }

    private fun showEmergency(t: Throwable) {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(22), dp(30), dp(22), dp(30))
            setBackgroundColor(bg())
            addView(TextView(this@ModelAvailabilitySafeActivity).apply {
                text = "模型状态安全模式"
                textSize = 24f
                setTypeface(typeface, Typeface.BOLD)
                setTextColor(primary())
            })
            addView(TextView(this@ModelAvailabilitySafeActivity).apply {
                text = "页面初始化异常已隔离，LuMing 主进程不会因此退出。\n${t.javaClass.simpleName}\n${t.message.orEmpty().take(260)}"
                textSize = 12f
                setTextColor(secondary())
                setPadding(0, dp(14), 0, 0)
            })
        }
        setContentView(root)
    }

    private fun severity(status: String): Int = when (status.trim().lowercase(Locale.US)) {
        "operational", "healthy", "normal" -> 0
        "degraded", "warning" -> 1
        else -> 2
    }

    private fun statusLabel(status: String): String = when (severity(status)) {
        0 -> "正常"
        1 -> "降级"
        else -> "故障"
    }

    private fun statusColor(status: String): Int = when (severity(status)) {
        0 -> accent()
        1 -> Color.rgb(211, 150, 38)
        else -> Color.rgb(204, 67, 75)
    }

    private fun percent(value: Double): String = if (value.isFinite()) {
        String.format(Locale.US, "%.2f%%", value)
    } else {
        "--"
    }

    private fun bg() = LuMingTheme.bg(this)
    private fun panelColor() = LuMingTheme.panel(this)
    private fun panelAlt() = LuMingTheme.panelAlt(this)
    private fun border() = LuMingTheme.border(this)
    private fun primary() = LuMingTheme.textPrimary(this)
    private fun secondary() = LuMingTheme.textSecondary(this)
    private fun muted() = LuMingTheme.textMuted(this)
    private fun accent() = LuMingTheme.accent(this)
    private fun activeBg() = LuMingTheme.activeBg(this)
    private fun activeBorder() = LuMingTheme.activeBorder(this)
    private fun positiveBg() = LuMingTheme.positivePill(this)
    private fun neutralBg() = LuMingTheme.neutralPill(this)
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    companion object {
        private const val MODE_AVAILABILITY = 0
        private const val MODE_CATALOG = 1
        private const val AUTO_REFRESH_SECONDS = 60
        private const val MAX_MONITORS = 40
        private const val MAX_CHANNELS = 24
        private const val MAX_PLATFORMS_PER_CHANNEL = 12
        private const val MAX_MODELS_PER_PLATFORM = 40
    }
}
