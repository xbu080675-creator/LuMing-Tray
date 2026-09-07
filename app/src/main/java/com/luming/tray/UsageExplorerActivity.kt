package com.luming.tray

import android.app.Activity
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.WindowInsets
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.max

class UsageExplorerActivity : Activity() {
    private lateinit var statusPill: TextView
    private lateinit var summaryText: TextView
    private lateinit var daysSpinner: Spinner
    private lateinit var keySpinner: Spinner
    private lateinit var groupSpinner: Spinner
    private lateinit var modelSpinner: Spinner
    private lateinit var modelSection: LinearLayout
    private lateinit var groupSection: LinearLayout
    private lateinit var platformSection: LinearLayout
    private lateinit var trendChart: InteractiveTokenTrendView
    private lateinit var logsContainer: LinearLayout
    private lateinit var refreshButton: Button

    private var report: UsageExplorerReport? = null
    private var loading = false
    private var currentFilter = UsageExplorerFilter()

    override fun onCreate(savedInstanceState: Bundle?) {
        LuMingTheme.applyActivityTheme(this)
        super.onCreate(savedInstanceState)
        LuMingTheme.applySystemBars(this)
        setContentView(buildUi())
        loadData(currentFilter)
    }

    private fun buildUi(): View {
        val scroll = ScrollView(this).apply {
            setBackgroundColor(bgColor())
            overScrollMode = View.OVER_SCROLL_NEVER
        }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(16), dp(18), dp(36))
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

        root.addView(buildHeader())
        root.addView(buildOverview(), matchWrap().apply { topMargin = dp(10) })
        root.addView(buildRangePanel(), matchWrap().apply { topMargin = dp(12) })

        root.addView(sectionTitle("分布分析"))
        modelSection = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        groupSection = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        platformSection = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(modelSection)
        root.addView(groupSection)
        root.addView(platformSection)

        root.addView(sectionTitle("Token 使用趋势"))
        val trendPanel = softPanel().apply { setPadding(dp(14), dp(14), dp(14), dp(10)) }
        trendChart = InteractiveTokenTrendView(this)
        trendPanel.addView(trendChart, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(220)))
        trendPanel.addView(TextView(this).apply {
            text = "输入 · 输出 · Cache Read · Cache Create"
            textSize = 10f
            gravity = Gravity.CENTER
            setTextColor(textMuted())
            setPadding(0, dp(6), 0, 0)
        })
        root.addView(trendPanel)

        root.addView(sectionTitle("API 筛选"))
        root.addView(buildFilterPanel())

        root.addView(sectionTitle("最近请求"))
        logsContainer = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(logsContainer)

        root.addView(TextView(this).apply {
            text = "LuMing Tray 0.15.0 · Usage Explorer"
            textSize = 10.5f
            gravity = Gravity.CENTER
            setTextColor(textMuted())
            setPadding(0, dp(24), 0, 0)
        })
        scroll.addView(root)
        return scroll
    }

    private fun buildHeader(): View {
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        row.addView(TextView(this).apply {
            text = "‹"
            textSize = 34f
            gravity = Gravity.CENTER
            setTextColor(textPrimary())
            setOnClickListener { finish() }
        }, LinearLayout.LayoutParams(dp(44), dp(48)))
        val title = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        title.addView(TextView(this).apply {
            text = "用量中心"
            textSize = 25f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(textPrimary())
        })
        title.addView(TextView(this).apply {
            text = "DISTRIBUTION · TREND · REQUEST LOGS"
            textSize = 9.5f
            letterSpacing = 0.08f
            setTextColor(textMuted())
        })
        row.addView(title, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        statusPill = TextView(this).apply {
            text = "同步中…"
            textSize = 10f
            gravity = Gravity.CENTER
            setTextColor(accentDark())
            setPadding(dp(10), dp(6), dp(10), dp(6))
            background = pillBackground(LuMingTheme.positivePill(this@UsageExplorerActivity))
        }
        row.addView(statusPill)
        return row
    }

    private fun buildOverview(): View {
        val panel = softPanel().apply { setPadding(dp(16), dp(14), dp(16), dp(14)) }
        summaryText = TextView(this).apply {
            text = "正在读取用量…"
            textSize = 12.5f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(textPrimary())
        }
        panel.addView(summaryText)
        val stats = TrayStore.loadStats(this)
        val row1 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, dp(12), 0, 0) }
        row1.addView(metric("今日请求", stats?.requests?.toString() ?: "--"), weighted(end = 5))
        row1.addView(metric("今日 Token", stats?.totalTokens?.let(TrayNotification::tokens) ?: "--"), weighted(start = 5))
        panel.addView(row1)
        val row2 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, dp(10), 0, 0) }
        row2.addView(metric("实际消费", stats?.todayCost?.let(TrayNotification::money) ?: "--"), weighted(end = 5))
        row2.addView(metric("平均响应", stats?.avgResponseSeconds?.let(TrayNotification::seconds) ?: "--"), weighted(start = 5))
        panel.addView(row2)
        return panel
    }

    private fun buildRangePanel(): View {
        val panel = softPanel().apply { setPadding(dp(14), dp(12), dp(14), dp(12)) }
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        row.addView(TextView(this).apply {
            text = "统计范围"
            textSize = 11.5f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(textSecondary())
        }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        daysSpinner = spinner(arrayOf("近 7 天", "近 15 天", "近 30 天"))
        row.addView(daysSpinner, LinearLayout.LayoutParams(dp(130), dp(46)))
        panel.addView(row)
        refreshButton = actionButton("按此范围刷新") { applyFilters() }
        panel.addView(refreshButton, matchHeight(48).apply { topMargin = dp(8) })
        return panel
    }

    private fun buildFilterPanel(): View {
        val panel = softPanel().apply { setPadding(dp(14), dp(14), dp(14), dp(14)) }
        panel.addView(fieldLabel("API Key"))
        keySpinner = spinner(arrayOf("全部 API Key"))
        panel.addView(keySpinner, matchHeight(48))
        panel.addView(fieldLabel("分组").apply { setPadding(0, dp(10), 0, dp(5)) })
        groupSpinner = spinner(arrayOf("全部分组"))
        panel.addView(groupSpinner, matchHeight(48))
        panel.addView(fieldLabel("模型").apply { setPadding(0, dp(10), 0, dp(5)) })
        modelSpinner = spinner(arrayOf("全部模型"))
        panel.addView(modelSpinner, matchHeight(48))

        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, dp(12), 0, 0) }
        row.addView(actionButton("重置") {
            daysSpinner.setSelection(0)
            keySpinner.setSelection(0)
            groupSpinner.setSelection(0)
            modelSpinner.setSelection(0)
            currentFilter = UsageExplorerFilter()
            loadData(currentFilter)
        }, weighted(end = 5))
        row.addView(actionButton("应用筛选") { applyFilters() }, weighted(start = 5))
        panel.addView(row)
        return panel
    }

    private fun applyFilters() {
        val current = report
        val days = when (daysSpinner.selectedItemPosition) { 1 -> 15; 2 -> 30; else -> 7 }
        val keyId = current?.keys?.getOrNull(keySpinner.selectedItemPosition - 1)?.id
        val groupId = current?.groups?.getOrNull(groupSpinner.selectedItemPosition - 1)?.id
        val model = current?.modelOptions?.getOrNull(modelSpinner.selectedItemPosition - 1)
        currentFilter = UsageExplorerFilter(days, keyId, groupId, model)
        loadData(currentFilter)
    }

    private fun loadData(filter: UsageExplorerFilter) {
        if (loading) return
        loading = true
        statusPill.text = "同步中…"
        refreshButton.isEnabled = false
        UsageExplorerClient.load(this, filter) { result ->
            loading = false
            refreshButton.isEnabled = true
            report = result
            render(result)
        }
    }

    private fun render(result: UsageExplorerReport) {
        statusPill.text = if (TrayStore.loadConfig(this).webAuthToken.isNotBlank()) "已授权" else "未授权"
        summaryText.text = result.message
        syncSpinner(daysSpinner, arrayOf("近 7 天", "近 15 天", "近 30 天"), when (result.filter.days) { 15 -> 1; 30 -> 2; else -> 0 })
        syncSpinner(keySpinner, arrayOf("全部 API Key") + result.keys.map { it.name }, result.filter.apiKeyId?.let { id -> result.keys.indexOfFirst { it.id == id }.takeIf { it >= 0 }?.plus(1) } ?: 0)
        syncSpinner(groupSpinner, arrayOf("全部分组") + result.groups.map { "${it.name} · ${rate(it.effectiveRateMultiplier)}" }, result.filter.groupId?.let { id -> result.groups.indexOfFirst { it.id == id }.takeIf { it >= 0 }?.plus(1) } ?: 0)
        syncSpinner(modelSpinner, arrayOf("全部模型") + result.modelOptions, result.filter.model?.let { model -> result.modelOptions.indexOf(model).takeIf { it >= 0 }?.plus(1) } ?: 0)

        renderDistribution(modelSection, "模型分布", result.models)
        renderDistribution(groupSection, "分组使用分布", result.groupsBreakdown)
        renderDistribution(platformSection, "平台拆分", result.platforms)
        trendChart.setData(result.trend)
        renderLogs(result.logs)
    }

    private fun renderDistribution(container: LinearLayout, title: String, items: List<UsageBreakdownItem>) {
        container.removeAllViews()
        val panel = softPanel().apply { setPadding(dp(15), dp(14), dp(15), dp(14)) }
        panel.addView(TextView(this).apply {
            text = title
            textSize = 13f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(textPrimary())
        })
        if (items.isEmpty() || items.sumOf { it.tokens } <= 0L) {
            panel.addView(TextView(this).apply {
                text = "暂无分布数据"
                textSize = 11f
                setTextColor(textMuted())
                setPadding(0, dp(14), 0, dp(8))
            })
        } else {
            val chart = InteractiveDonutChartView(this)
            chart.setData(items)
            panel.addView(chart, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(180)).apply { topMargin = dp(8) })
            val total = items.sumOf { it.tokens }.coerceAtLeast(1L)
            items.take(6).forEachIndexed { index, item ->
                panel.addView(TextView(this).apply {
                    val share = item.tokens.toDouble() / total.toDouble()
                    text = "${index + 1}. ${item.name}   ${percent(share)} · ${TrayNotification.tokens(item.tokens)}"
                    textSize = 10.5f
                    maxLines = 1
                    setTextColor(if (index == 0) textPrimary() else textSecondary())
                    setPadding(dp(4), dp(5), dp(4), 0)
                })
            }
        }
        container.addView(panel, matchWrap().apply { bottomMargin = dp(12) })
    }

    private fun renderLogs(logs: List<UsageLogItem>) {
        logsContainer.removeAllViews()
        if (logs.isEmpty()) {
            logsContainer.addView(softPanel().apply {
                setPadding(dp(16), dp(18), dp(16), dp(18))
                addView(TextView(this@UsageExplorerActivity).apply {
                    text = "当前筛选条件下暂无请求记录。"
                    textSize = 11.5f
                    setTextColor(textMuted())
                })
            })
            return
        }
        logs.forEachIndexed { index, item ->
            logsContainer.addView(logCard(item), matchWrap().apply { if (index > 0) topMargin = dp(10) })
        }
    }

    private fun logCard(item: UsageLogItem): View {
        val card = softPanel().apply { setPadding(dp(15), dp(14), dp(15), dp(14)) }
        val head = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val title = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        title.addView(TextView(this).apply {
            text = item.model
            textSize = 14f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(textPrimary())
        })
        title.addView(TextView(this).apply {
            text = "${item.apiKeyName} · ${item.groupName}"
            textSize = 10.2f
            maxLines = 1
            setTextColor(textMuted())
            setPadding(0, dp(3), 0, 0)
        })
        head.addView(title, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        head.addView(TextView(this).apply {
            text = money(item.actualCost)
            textSize = 12f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(accentDark())
        })
        card.addView(head)

        val meta = mutableListOf<String>()
        if (item.platform != "--") meta += item.platform
        meta += item.requestType
        item.stream?.let { meta += if (it) "Stream" else "非流式" }
        if (item.durationMs > 0) meta += duration(item.durationMs)
        card.addView(info(meta.joinToString(" · ")).apply { setPadding(0, dp(9), 0, 0) })

        card.addView(info("输入 ${tokens(item.inputTokens)} · 输出 ${tokens(item.outputTokens)} · Cache Read ${tokens(item.cacheReadTokens)} · Create ${tokens(item.cacheCreationTokens)}").apply { setPadding(0, dp(6), 0, 0) })
        val hit = item.cacheHitRate?.let(::percent) ?: "--"
        card.addView(info("总 Token ${tokens(item.totalTokens)} · 缓存命中 $hit").apply { setPadding(0, dp(5), 0, 0) })
        if (item.createdAt.isNotBlank()) card.addView(info(shortTime(item.createdAt)).apply { setPadding(0, dp(5), 0, 0) })
        return card
    }

    private fun metric(label: String, value: String): View = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        background = miniBackground()
        setPadding(dp(12), dp(10), dp(12), dp(10))
        addView(TextView(this@UsageExplorerActivity).apply { text = label; textSize = 9.5f; setTextColor(textMuted()) })
        addView(TextView(this@UsageExplorerActivity).apply {
            text = value; textSize = 17f; setTypeface(typeface, Typeface.BOLD); setTextColor(textPrimary()); setPadding(0, dp(4), 0, 0)
        })
    }

    private fun spinner(items: Array<String>): Spinner = Spinner(this).apply {
        adapter = ArrayAdapter(this@UsageExplorerActivity, android.R.layout.simple_spinner_dropdown_item, items)
        background = inputBackground()
        setPadding(dp(12), 0, dp(8), 0)
    }

    private fun syncSpinner(spinner: Spinner, items: Array<String>, selection: Int) {
        spinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, items)
        spinner.setSelection(selection.coerceIn(0, max(0, items.lastIndex)), false)
    }

    private fun actionButton(label: String, onClick: () -> Unit): Button = Button(this).apply {
        text = label
        isAllCaps = false
        textSize = 12f
        setTypeface(typeface, Typeface.BOLD)
        setTextColor(textSecondary())
        background = buttonBackground()
        elevation = dp(3).toFloat()
        stateListAnimator = null
        minHeight = 0
        minimumHeight = 0
        setOnClickListener { onClick() }
    }

    private fun fieldLabel(value: String) = TextView(this).apply {
        text = value
        textSize = 10.5f
        setTypeface(typeface, Typeface.BOLD)
        setTextColor(textMuted())
        setPadding(0, 0, 0, dp(5))
    }

    private fun info(value: String) = TextView(this).apply { text = value; textSize = 10.3f; setTextColor(textSecondary()) }
    private fun sectionTitle(value: String) = TextView(this).apply {
        text = value; textSize = 13f; setTypeface(typeface, Typeface.BOLD); setTextColor(textSecondary()); setPadding(dp(3), dp(22), dp(3), dp(9))
    }
    private fun softPanel() = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; background = panelBackground(); elevation = dp(7).toFloat() }
    private fun panelBackground() = GradientDrawable().apply { setColor(panelColor()); cornerRadius = dp(24).toFloat(); setStroke(dp(1), LuMingTheme.border(this@UsageExplorerActivity)) }
    private fun miniBackground() = GradientDrawable().apply { setColor(LuMingTheme.panelAlt(this@UsageExplorerActivity)); cornerRadius = dp(16).toFloat() }
    private fun buttonBackground() = GradientDrawable().apply { setColor(LuMingTheme.panelAlt(this@UsageExplorerActivity)); cornerRadius = dp(16).toFloat(); setStroke(dp(1), LuMingTheme.border(this@UsageExplorerActivity)) }
    private fun inputBackground() = GradientDrawable().apply { setColor(LuMingTheme.input(this@UsageExplorerActivity)); cornerRadius = dp(16).toFloat(); setStroke(dp(1), LuMingTheme.border(this@UsageExplorerActivity)) }
    private fun pillBackground(color: Int) = GradientDrawable().apply { setColor(color); cornerRadius = dp(999).toFloat() }
    private fun weighted(start: Int = 0, end: Int = 0) = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { leftMargin = dp(start); rightMargin = dp(end) }
    private fun matchWrap() = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
    private fun matchHeight(dpValue: Int) = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(dpValue))
    private fun bgColor() = LuMingTheme.bg(this)
    private fun panelColor() = LuMingTheme.panel(this)
    private fun accentColor() = LuMingTheme.accent(this)
    private fun accentDark() = LuMingTheme.accentDark(this)
    private fun textPrimary() = LuMingTheme.textPrimary(this)
    private fun textSecondary() = LuMingTheme.textSecondary(this)
    private fun textMuted() = LuMingTheme.textMuted(this)
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
    private fun money(value: Double) = String.format(Locale.US, "$%.4f", value.coerceAtLeast(0.0)).trimEnd('0').trimEnd('.')
    private fun rate(value: Double) = String.format(Locale.US, "%.3gx", value)
    private fun percent(value: Double) = String.format(Locale.US, "%.1f%%", value.coerceIn(0.0, 1.0) * 100.0)
    private fun tokens(value: Long) = TrayNotification.tokens(value)
    private fun duration(ms: Long) = if (ms >= 1000) String.format(Locale.US, "%.2fs", ms / 1000.0) else "${ms}ms"
    private fun shortTime(value: String): String {
        val normalized = value.replace('T', ' ')
        return if (normalized.length >= 19) normalized.take(19) else normalized
    }
}

private class DonutChartView(context: android.content.Context) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 42f; strokeCap = Paint.Cap.BUTT }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = LuMingTheme.textPrimary(context); textAlign = Paint.Align.CENTER; typeface = Typeface.DEFAULT_BOLD }
    private var items: List<UsageBreakdownItem> = emptyList()
    private val colors = intArrayOf(
        Color.rgb(62, 132, 238), Color.rgb(32, 181, 134), Color.rgb(246, 166, 35),
        Color.rgb(155, 111, 221), Color.rgb(229, 83, 94), Color.rgb(63, 174, 188), Color.rgb(120, 146, 164)
    )

    fun setData(value: List<UsageBreakdownItem>) { items = value; invalidate() }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val total = items.sumOf { it.tokens }.toFloat()
        if (total <= 0f) return
        val size = minOf(width, height) * 0.62f
        val left = (width - size) / 2f
        val top = (height - size) / 2f
        val rect = RectF(left, top, left + size, top + size)
        paint.strokeWidth = max(22f, size * 0.18f)
        var start = -90f
        items.take(colors.size).forEachIndexed { index, item ->
            val sweep = 360f * item.tokens.toFloat() / total
            paint.color = colors[index % colors.size]
            canvas.drawArc(rect, start, sweep, false, paint)
            start += sweep
        }
        textPaint.textSize = size * 0.13f
        canvas.drawText(TrayNotification.tokens(total.toLong()), width / 2f, height / 2f + textPaint.textSize * 0.35f, textPaint)
    }
}

private class TokenTrendView(context: android.content.Context) : View(context) {
    private var points: List<UsageTrendPoint> = emptyList()
    private val axisPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = LuMingTheme.divider(context); strokeWidth = 1.5f }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = LuMingTheme.textMuted(context); textSize = 23f }
    private val seriesPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 4f; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND }
    private val colors = intArrayOf(Color.rgb(62, 132, 238), Color.rgb(34, 174, 133), Color.rgb(242, 162, 39), Color.rgb(154, 109, 218))

    fun setData(value: List<UsageTrendPoint>) { points = value; invalidate() }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val left = 18f
        val right = width - 18f
        val top = 18f
        val bottom = height - 42f
        canvas.drawLine(left, bottom, right, bottom, axisPaint)
        canvas.drawLine(left, top, left, bottom, axisPaint)
        if (points.isEmpty()) {
            labelPaint.textAlign = Paint.Align.CENTER
            canvas.drawText("暂无趋势数据", width / 2f, height / 2f, labelPaint)
            return
        }
        val maxValue = points.maxOf { max(max(it.inputTokens, it.outputTokens), max(it.cacheReadTokens, it.cacheCreationTokens)) }.coerceAtLeast(1L)
        val count = points.size
        fun x(index: Int) = if (count <= 1) (left + right) / 2f else left + (right - left) * index / (count - 1).toFloat()
        fun y(value: Long) = bottom - (bottom - top) * value.toFloat() / maxValue.toFloat()
        val selectors: List<(UsageTrendPoint) -> Long> = listOf({ it.inputTokens }, { it.outputTokens }, { it.cacheReadTokens }, { it.cacheCreationTokens })
        selectors.forEachIndexed { series, selector ->
            seriesPaint.color = colors[series]
            for (i in 1 until count) canvas.drawLine(x(i - 1), y(selector(points[i - 1])), x(i), y(selector(points[i])), seriesPaint)
        }
        labelPaint.textAlign = Paint.Align.LEFT
        labelPaint.textSize = 22f
        val first = short(points.first().label)
        canvas.drawText(first, left, height - 12f, labelPaint)
        if (count > 1) {
            labelPaint.textAlign = Paint.Align.RIGHT
            canvas.drawText(short(points.last().label), right, height - 12f, labelPaint)
        }
    }

    private fun short(value: String): String {
        if (value.length >= 10) return value.take(10).substring(5)
        return value.take(8)
    }
}
