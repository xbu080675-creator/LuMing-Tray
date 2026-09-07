package com.luming.tray

import android.app.Activity
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
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
import kotlin.math.max

class ModelAvailabilityActivity : Activity() {
    private lateinit var content: LinearLayout
    private lateinit var sourcePill: TextView
    private lateinit var countdownText: TextView
    private lateinit var availabilityTab: Button
    private lateinit var catalogTab: Button

    private val handler = Handler(Looper.getMainLooper())
    private var report: ModelAvailabilityReport? = null
    private var loading = false
    private var mode = MODE_AVAILABILITY
    private var rangeDays = 7
    private var countdown = AUTO_REFRESH_SECONDS

    private val ticker = object : Runnable {
        override fun run() {
            countdown = (countdown - 1).coerceAtLeast(0)
            updateCountdown()
            if (countdown <= 0) loadReport() else handler.postDelayed(this, 1_000L)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = bgColor()
        window.navigationBarColor = bgColor()
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
        setContentView(buildUi())
        loadReport()
    }

    override fun onResume() {
        super.onResume()
        restartTicker()
    }

    override fun onPause() {
        handler.removeCallbacks(ticker)
        super.onPause()
    }

    private fun buildUi(): View {
        val scroll = ScrollView(this).apply {
            setBackgroundColor(bgColor())
            overScrollMode = View.OVER_SCROLL_NEVER
        }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(16), dp(18), dp(34))
            setOnApplyWindowInsetsListener { view, insets ->
                val top = if (Build.VERSION.SDK_INT >= 30) {
                    insets.getInsets(WindowInsets.Type.statusBars()).top
                } else {
                    @Suppress("DEPRECATION") insets.systemWindowInsetTop
                }
                val bottom = if (Build.VERSION.SDK_INT >= 30) {
                    insets.getInsets(WindowInsets.Type.navigationBars()).bottom
                } else {
                    @Suppress("DEPRECATION") insets.systemWindowInsetBottom
                }
                view.setPadding(dp(18), dp(12) + top, dp(18), dp(24) + bottom)
                insets
            }
        }

        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        header.addView(TextView(this).apply {
            text = "‹"
            textSize = 34f
            gravity = Gravity.CENTER
            setTextColor(textPrimary())
            setOnClickListener { finish() }
        }, LinearLayout.LayoutParams(dp(44), dp(48)))

        val titleBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        titleBox.addView(TextView(this).apply {
            text = "模型可用性"
            textSize = 25f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(textPrimary())
        })
        titleBox.addView(TextView(this).apply {
            text = "MODEL AVAILABILITY"
            textSize = 10f
            letterSpacing = 0.12f
            setTextColor(textMuted())
        })
        header.addView(titleBox, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))

        sourcePill = TextView(this).apply {
            text = "读取中…"
            textSize = 10f
            gravity = Gravity.CENTER
            setTextColor(accentDark())
            setPadding(dp(10), dp(6), dp(10), dp(6))
            background = pillBackground(Color.rgb(222, 243, 237))
        }
        header.addView(sourcePill)
        root.addView(header)

        val tabs = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        availabilityTab = tabButton("稳定性") {
            mode = MODE_AVAILABILITY
            styleTabs()
            render()
        }
        catalogTab = tabButton("可用模型") {
            mode = MODE_CATALOG
            styleTabs()
            render()
        }
        tabs.addView(availabilityTab, weighted(end = 6))
        tabs.addView(catalogTab, weighted(start = 6))
        root.addView(tabs, matchWrap().apply { topMargin = dp(12) })
        styleTabs()

        val statusRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(4), dp(12), dp(4), dp(8))
        }
        countdownText = TextView(this).apply {
            textSize = 10.5f
            setTextColor(textMuted())
        }
        statusRow.addView(countdownText, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        statusRow.addView(actionButton("刷新") { loadReport() }, LinearLayout.LayoutParams(dp(88), dp(42)))
        root.addView(statusRow)

        content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(content, matchWrap())

        scroll.addView(root)
        return scroll
    }

    private fun loadReport() {
        if (loading) return
        loading = true
        handler.removeCallbacks(ticker)
        sourcePill.text = "读取中…"
        countdownText.text = "正在同步站点数据"
        ModelAvailabilityClient.load(this) { result ->
            loading = false
            report = result
            countdown = AUTO_REFRESH_SECONDS
            sourcePill.text = if (result.monitors.isNotEmpty() || result.channels.isNotEmpty()) "站点实时" else "暂无数据"
            render()
            restartTicker()
        }
    }

    private fun restartTicker() {
        handler.removeCallbacks(ticker)
        updateCountdown()
        if (!loading) handler.postDelayed(ticker, 1_000L)
    }

    private fun updateCountdown() {
        if (::countdownText.isInitialized && !loading) {
            countdownText.text = "自动刷新：${countdown}s"
        }
    }

    private fun render() {
        if (!::content.isInitialized) return
        content.removeAllViews()
        val current = report
        if (current == null) {
            content.addView(emptyPanel("正在读取可用渠道与模型健康状态…"))
            return
        }

        if (mode == MODE_CATALOG) renderCatalog(current) else renderAvailability(current)
    }

    private fun renderAvailability(current: ModelAvailabilityReport) {
        val monitors = current.monitors
        if (monitors.isEmpty()) {
            content.addView(emptyPanel("${current.message}\n\n当前站点没有返回 /channel-monitors 数据。"))
            return
        }

        val normal = monitors.count { normalizedStatus(it.status) == "operational" }
        val degraded = monitors.count { normalizedStatus(it.status) == "degraded" }
        val failed = monitors.size - normal - degraded

        val summary = softPanel().apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(14), dp(16), dp(14))
        }
        summary.addView(summaryMetric("监控", monitors.size.toString()), LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        summary.addView(summaryMetric("正常", normal.toString()), LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        summary.addView(summaryMetric("降级", degraded.toString()), LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        summary.addView(summaryMetric("故障", failed.toString()), LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        content.addView(summary, matchWrap())

        val rangeRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END
            setPadding(0, dp(12), 0, dp(10))
        }
        listOf(7, 15, 30).forEach { days ->
            rangeRow.addView(rangeButton("${days}天", days), LinearLayout.LayoutParams(dp(74), dp(40)).apply {
                leftMargin = dp(7)
            })
        }
        content.addView(rangeRow)

        monitors.chunked(2).forEachIndexed { rowIndex, pair ->
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            pair.forEachIndexed { index, monitor ->
                row.addView(
                    monitorCard(monitor),
                    LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                        if (index == 0) rightMargin = dp(6) else leftMargin = dp(6)
                    }
                )
            }
            if (pair.size == 1) row.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f).apply { leftMargin = dp(6) })
            content.addView(row, matchWrap().apply { if (rowIndex > 0) topMargin = dp(12) })
        }

        content.addView(TextView(this).apply {
            text = "数据直接读取站点用户侧 channel-monitors 接口。绿=正常，橙=降级，红=失败；时间线取最近 60 次探活记录。"
            textSize = 10.3f
            setTextColor(textMuted())
            setPadding(dp(4), dp(14), dp(4), 0)
        })
    }

    private fun monitorCard(item: ModelMonitor): View {
        val card = softPanel().apply {
            setPadding(dp(13), dp(13), dp(13), dp(13))
            minimumHeight = dp(232)
        }
        val top = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        top.addView(TextView(this).apply {
            text = item.name
            textSize = 13f
            maxLines = 1
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(textPrimary())
        }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        top.addView(statusPill(item.status))
        card.addView(top)

        card.addView(TextView(this).apply {
            text = buildString {
                append(platformLabel(item.provider))
                if (item.groupName.isNotBlank()) append("  ·  ${item.groupName}")
                if (item.model.isNotBlank()) append("\n${item.model}")
            }
            textSize = 9.6f
            setTextColor(textMuted())
            maxLines = 2
            setPadding(0, dp(5), 0, dp(10))
        })

        val metrics = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        metrics.addView(miniMetric("对话延迟", item.latencyMs?.let { "${it}ms" } ?: "--"), LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        metrics.addView(miniMetric("节点 PING", item.pingLatencyMs?.let { "${it}ms" } ?: "--"), LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { leftMargin = dp(6) })
        card.addView(metrics)

        card.addView(TextView(this).apply {
            text = "可用性 · ${rangeDays} 天"
            textSize = 9.2f
            setTextColor(textMuted())
            setPadding(0, dp(13), 0, dp(1))
        })
        card.addView(TextView(this).apply {
            text = item.availability(rangeDays)?.let(::percent) ?: "--"
            textSize = 23f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(availabilityColor(item.availability(rangeDays)))
        })

        val timelineLabel = TextView(this).apply {
            text = "近 ${item.timeline.size.coerceAtMost(60)} 次记录"
            textSize = 8.5f
            setTextColor(textMuted())
            setPadding(0, dp(8), 0, dp(4))
        }
        card.addView(timelineLabel)
        card.addView(AvailabilityTimelineView(this).apply { setData(item.timeline) }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(30)))
        return card
    }

    private fun renderCatalog(current: ModelAvailabilityReport) {
        val channels = current.channels
        if (channels.isEmpty()) {
            content.addView(emptyPanel("${current.message}\n\n当前站点没有返回 /channels/available 数据。"))
            return
        }
        val modelCount = channels.sumOf { channel -> channel.platforms.sumOf { it.models.size } }
        val groupCount = channels.sumOf { channel -> channel.platforms.sumOf { it.groups.size } }

        val summary = softPanel().apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(14), dp(16), dp(14))
        }
        summary.addView(summaryMetric("渠道", channels.size.toString()), LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        summary.addView(summaryMetric("分组", groupCount.toString()), LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        summary.addView(summaryMetric("模型项", modelCount.toString()), LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        content.addView(summary, matchWrap())

        channels.forEachIndexed { index, channel ->
            val card = softPanel().apply { setPadding(dp(16), dp(15), dp(16), dp(15)) }
            card.addView(TextView(this).apply {
                text = channel.name
                textSize = 15f
                setTypeface(typeface, Typeface.BOLD)
                setTextColor(textPrimary())
            })
            if (channel.description.isNotBlank()) {
                card.addView(TextView(this).apply {
                    text = channel.description
                    textSize = 10.5f
                    setTextColor(textSecondary())
                    setPadding(0, dp(5), 0, dp(4))
                })
            }
            channel.platforms.forEach { section -> card.addView(platformSection(section)) }
            content.addView(card, matchWrap().apply { topMargin = if (index == 0) dp(12) else dp(12) })
        }

        content.addView(TextView(this).apply {
            text = "这里就是网页“可用渠道”那张表的数据：渠道说明、平台、你能访问的分组倍率和该渠道支持的模型。"
            textSize = 10.3f
            setTextColor(textMuted())
            setPadding(dp(4), dp(14), dp(4), 0)
        })
    }

    private fun platformSection(section: AvailablePlatformSection): View {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(12), 0, dp(2))
        }
        val head = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        head.addView(TextView(this).apply {
            text = platformLabel(section.platform)
            textSize = 10f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(platformColor(section.platform))
            setPadding(dp(9), dp(5), dp(9), dp(5))
            background = pillBackground(platformBg(section.platform))
        })
        head.addView(TextView(this).apply {
            text = "  ${section.models.size} 个模型"
            textSize = 9.5f
            setTextColor(textMuted())
        })
        box.addView(head)

        if (section.groups.isNotEmpty()) {
            box.addView(TextView(this).apply {
                text = "可访问分组"
                textSize = 9f
                setTypeface(typeface, Typeface.BOLD)
                setTextColor(textMuted())
                setPadding(0, dp(8), 0, dp(3))
            })
            box.addView(TextView(this).apply {
                text = section.groups.joinToString("  ·  ") { group ->
                    val scope = if (group.exclusive) "专属" else "公开"
                    "${group.name} ${rate(group.rateMultiplier)} $scope"
                }
                textSize = 10.2f
                setTextColor(textSecondary())
            })
        }

        box.addView(TextView(this).apply {
            text = "支持模型"
            textSize = 9f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(textMuted())
            setPadding(0, dp(8), 0, dp(3))
        })
        box.addView(TextView(this).apply {
            text = if (section.models.isEmpty()) "暂无模型" else section.models.joinToString("  ·  ")
            textSize = 10.2f
            setTextColor(platformColor(section.platform))
            setLineSpacing(dp(2).toFloat(), 1f)
        })
        return box
    }

    private fun summaryMetric(label: String, value: String) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER
        addView(TextView(this@ModelAvailabilityActivity).apply {
            text = value
            textSize = 18f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(textPrimary())
        })
        addView(TextView(this@ModelAvailabilityActivity).apply {
            text = label
            textSize = 9f
            setTextColor(textMuted())
        })
    }

    private fun miniMetric(label: String, value: String) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(8), dp(7), dp(8), dp(7))
        background = GradientDrawable().apply {
            setColor(Color.rgb(237, 243, 245))
            cornerRadius = dp(12).toFloat()
        }
        addView(TextView(this@ModelAvailabilityActivity).apply {
            text = label
            textSize = 8.2f
            setTextColor(textMuted())
        })
        addView(TextView(this@ModelAvailabilityActivity).apply {
            text = value
            textSize = 12.5f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(textPrimary())
            setPadding(0, dp(3), 0, 0)
        })
    }

    private fun statusPill(statusRaw: String): TextView {
        val status = normalizedStatus(statusRaw)
        val (label, fg, bg) = when (status) {
            "operational" -> Triple("正常", Color.rgb(24, 143, 101), Color.rgb(220, 245, 234))
            "degraded" -> Triple("降级", Color.rgb(190, 125, 28), Color.rgb(252, 239, 210))
            else -> Triple("故障", Color.rgb(190, 61, 70), Color.rgb(251, 224, 226))
        }
        return TextView(this).apply {
            text = label
            textSize = 8.5f
            gravity = Gravity.CENTER
            setTextColor(fg)
            setPadding(dp(8), dp(4), dp(8), dp(4))
            background = pillBackground(bg)
        }
    }

    private fun rangeButton(label: String, days: Int) = Button(this).apply {
        text = label
        isAllCaps = false
        textSize = 10.5f
        minHeight = 0
        minimumHeight = 0
        stateListAnimator = null
        setTypeface(typeface, Typeface.BOLD)
        setOnClickListener {
            rangeDays = days
            render()
        }
        val active = rangeDays == days
        setTextColor(if (active) accentDark() else textSecondary())
        background = GradientDrawable().apply {
            setColor(if (active) Color.rgb(224, 244, 238) else Color.rgb(239, 244, 246))
            cornerRadius = dp(14).toFloat()
            setStroke(dp(1), if (active) Color.rgb(192, 229, 218) else Color.rgb(221, 229, 233))
        }
    }

    private fun tabButton(label: String, action: () -> Unit) = Button(this).apply {
        text = label
        isAllCaps = false
        textSize = 12f
        setTypeface(typeface, Typeface.BOLD)
        minHeight = 0
        minimumHeight = 0
        stateListAnimator = null
        setOnClickListener { action() }
    }

    private fun styleTabs() {
        if (!::availabilityTab.isInitialized) return
        styleTab(availabilityTab, mode == MODE_AVAILABILITY)
        styleTab(catalogTab, mode == MODE_CATALOG)
    }

    private fun styleTab(button: Button, active: Boolean) {
        button.setTextColor(if (active) accentDark() else textSecondary())
        button.background = GradientDrawable().apply {
            setColor(if (active) Color.rgb(222, 243, 237) else Color.rgb(239, 244, 246))
            cornerRadius = dp(17).toFloat()
            setStroke(dp(1), if (active) Color.rgb(195, 230, 220) else Color.rgb(221, 229, 233))
        }
    }

    private fun actionButton(label: String, action: () -> Unit) = Button(this).apply {
        text = label
        isAllCaps = false
        textSize = 11.5f
        setTypeface(typeface, Typeface.BOLD)
        setTextColor(textSecondary())
        minHeight = 0
        minimumHeight = 0
        stateListAnimator = null
        background = GradientDrawable().apply {
            setColor(Color.rgb(239, 244, 246))
            cornerRadius = dp(15).toFloat()
            setStroke(dp(1), Color.rgb(221, 229, 233))
        }
        setOnClickListener { action() }
    }

    private fun emptyPanel(textValue: String) = TextView(this).apply {
        text = textValue
        textSize = 12f
        setTextColor(textSecondary())
        gravity = Gravity.CENTER
        setPadding(dp(20), dp(30), dp(20), dp(30))
        background = panelBackground()
    }

    private fun softPanel() = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        background = panelBackground()
        elevation = dp(6).toFloat()
    }

    private fun panelBackground() = GradientDrawable().apply {
        setColor(panelColor())
        cornerRadius = dp(24).toFloat()
        setStroke(dp(1), Color.argb(210, 255, 255, 255))
    }

    private fun pillBackground(color: Int) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = dp(999).toFloat()
    }

    private fun weighted(start: Int = 0, end: Int = 0) =
        LinearLayout.LayoutParams(0, dp(48), 1f).apply {
            leftMargin = dp(start)
            rightMargin = dp(end)
        }

    private fun matchWrap() = LinearLayout.LayoutParams(
        LinearLayout.LayoutParams.MATCH_PARENT,
        LinearLayout.LayoutParams.WRAP_CONTENT
    )

    private fun normalizedStatus(raw: String): String = when (raw.lowercase(Locale.US)) {
        "operational", "healthy", "ok", "normal" -> "operational"
        "degraded", "warning", "warn" -> "degraded"
        else -> "failed"
    }

    private fun platformLabel(raw: String): String = when (raw.lowercase(Locale.US)) {
        "anthropic" -> "ANTHROPIC"
        "openai" -> "OPENAI"
        "deepseek" -> "DEEPSEEK"
        else -> raw.uppercase(Locale.US)
    }

    private fun platformColor(raw: String): Int = when (raw.lowercase(Locale.US)) {
        "anthropic" -> Color.rgb(195, 104, 35)
        "openai", "deepseek" -> Color.rgb(21, 136, 103)
        else -> accentDark()
    }

    private fun platformBg(raw: String): Int = when (raw.lowercase(Locale.US)) {
        "anthropic" -> Color.rgb(252, 235, 218)
        "openai", "deepseek" -> Color.rgb(222, 243, 237)
        else -> Color.rgb(231, 239, 242)
    }

    private fun availabilityColor(value: Double?): Int = when {
        value == null -> textMuted()
        value >= 95.0 -> Color.rgb(48, 177, 83)
        value >= 80.0 -> Color.rgb(128, 170, 50)
        value >= 65.0 -> Color.rgb(211, 150, 38)
        else -> Color.rgb(204, 67, 75)
    }

    private fun percent(value: Double): String = String.format(Locale.US, "%.2f%%", value)
    private fun rate(value: Double): String = String.format(Locale.US, "%.3f", value).trimEnd('0').trimEnd('.') + "x"
    private fun bgColor() = Color.rgb(232, 239, 242)
    private fun panelColor() = Color.rgb(242, 247, 249)
    private fun accentDark() = Color.rgb(21, 125, 106)
    private fun textPrimary() = Color.rgb(37, 47, 58)
    private fun textSecondary() = Color.rgb(75, 88, 101)
    private fun textMuted() = Color.rgb(118, 131, 143)
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    companion object {
        private const val MODE_AVAILABILITY = 0
        private const val MODE_CATALOG = 1
        private const val AUTO_REFRESH_SECONDS = 60
    }
}

private class AvailabilityTimelineView(context: android.content.Context) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var points: List<AvailabilityTimelinePoint> = emptyList()

    fun setData(value: List<AvailabilityTimelinePoint>) {
        points = value.takeLast(60)
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (points.isEmpty()) return
        val maxLatency = max(1L, points.mapNotNull { it.latencyMs }.maxOrNull() ?: 1L)
        val slot = width.toFloat() / points.size.coerceAtLeast(1)
        val gap = max(1f, slot * 0.20f)
        points.forEachIndexed { index, point ->
            paint.color = when (point.status.lowercase(Locale.US)) {
                "operational", "healthy", "ok", "normal" -> Color.rgb(50, 184, 127)
                "degraded", "warning", "warn" -> Color.rgb(230, 165, 44)
                "failed", "error", "critical" -> Color.rgb(220, 72, 77)
                else -> Color.rgb(185, 196, 202)
            }
            val ratio = point.latencyMs?.let { (it.toFloat() / maxLatency).coerceIn(0f, 1f) } ?: 0.35f
            val barHeight = height * (0.38f + ratio * 0.62f)
            val left = index * slot + gap / 2f
            val right = (index + 1) * slot - gap / 2f
            canvas.drawRoundRect(left, height - barHeight, right, height.toFloat(), 2f, 2f, paint)
        }
    }
}
