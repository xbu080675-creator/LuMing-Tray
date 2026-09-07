package com.luming.tray

import android.app.Activity
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.WindowInsets
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.util.Locale
import kotlin.math.max

class AnalysisActivity : Activity() {
    private lateinit var sourcePill: TextView
    private lateinit var total30Value: TextView
    private lateinit var projectionText: TextView
    private lateinit var todayValue: TextView
    private lateinit var yesterdayValue: TextView
    private lateinit var sevenValue: TextView
    private lateinit var perRequestValue: TextView
    private lateinit var perMillionValue: TextView
    private lateinit var avg7Value: TextView
    private lateinit var peakValue: TextView
    private lateinit var chart: InteractiveSpendBarChart
    private lateinit var modelsContainer: LinearLayout
    private lateinit var groupsSection: LinearLayout
    private lateinit var groupsContainer: LinearLayout
    private lateinit var insightsText: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        LuMingTheme.applyActivityTheme(this)
        super.onCreate(savedInstanceState)
        LuMingTheme.applySystemBars(this)
        setContentView(buildUi())
        loadReport()
    }

    private fun loadReport() {
        sourcePill.text = "分析中…"
        CostAnalyticsClient.load(this) { report -> render(report) }
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
                val top = if (Build.VERSION.SDK_INT >= 30) insets.getInsets(WindowInsets.Type.statusBars()).top else {
                    @Suppress("DEPRECATION")
                    insets.systemWindowInsetTop
                }
                val bottom = if (Build.VERSION.SDK_INT >= 30) insets.getInsets(WindowInsets.Type.navigationBars()).bottom else {
                    @Suppress("DEPRECATION")
                    insets.systemWindowInsetBottom
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
            text = "消费分析"
            textSize = 25f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(textPrimary())
        })
        titleBox.addView(TextView(this).apply {
            text = "COST INTELLIGENCE"
            textSize = 10f
            letterSpacing = 0.12f
            setTextColor(textMuted())
        })
        header.addView(titleBox, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        sourcePill = TextView(this).apply {
            text = "分析中…"
            textSize = 10f
            setTextColor(accentDark())
            gravity = Gravity.CENTER
            setPadding(dp(10), dp(6), dp(10), dp(6))
            background = pillBackground(LuMingTheme.positivePill(this@AnalysisActivity))
        }
        header.addView(sourcePill)
        root.addView(header)

        val hero = softPanel().apply { setPadding(dp(20), dp(18), dp(20), dp(18)) }
        hero.addView(labelText("近 30 日实际消费"))
        total30Value = TextView(this).apply {
            text = "--"
            textSize = 38f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(accentColor())
            setPadding(0, dp(7), 0, dp(3))
        }
        hero.addView(total30Value)
        projectionText = TextView(this).apply {
            text = "正在计算消费节奏…"
            textSize = 11.5f
            setTextColor(textMuted())
        }
        hero.addView(projectionText)
        root.addView(hero, matchWrap().apply { topMargin = dp(8) })

        root.addView(sectionTitle("成本概览"))
        val row1 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val today = metricCard("TODAY", "今日消费")
        todayValue = today.second
        row1.addView(today.first, weighted(end = 6))
        val yesterday = metricCard("YESTERDAY", "昨日消费")
        yesterdayValue = yesterday.second
        row1.addView(yesterday.first, weighted(start = 6))
        root.addView(row1)

        val row2 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val seven = metricCard("7 DAYS", "近 7 日")
        sevenValue = seven.second
        row2.addView(seven.first, weighted(end = 6))
        val perReq = metricCard("PER REQUEST", "每次请求")
        perRequestValue = perReq.second
        row2.addView(perReq.first, weighted(start = 6))
        root.addView(row2, matchWrap().apply { topMargin = dp(12) })

        val efficiency = softPanel().apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(16), dp(14), dp(16), dp(14))
        }
        val million = smallMetric("每 100 万 Token")
        perMillionValue = million.second
        efficiency.addView(million.first, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        val avg = smallMetric("7 日均值")
        avg7Value = avg.second
        efficiency.addView(avg.first, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        val peak = smallMetric("高峰时段")
        peakValue = peak.second
        efficiency.addView(peak.first, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        root.addView(efficiency, matchWrap().apply { topMargin = dp(12) })

        root.addView(sectionTitle("近 7 日趋势"))
        val chartPanel = softPanel().apply { setPadding(dp(12), dp(14), dp(12), dp(10)) }
        chart = InteractiveSpendBarChart(this)
        chartPanel.addView(chart, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(190)))
        root.addView(chartPanel, matchWrap())

        root.addView(sectionTitle("花在哪里"))
        val modelPanel = softPanel().apply { setPadding(dp(16), dp(14), dp(16), dp(14)) }
        modelPanel.addView(TextView(this).apply {
            text = "近 7 日模型成本排名"
            textSize = 13f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(textPrimary())
        })
        modelsContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(8), 0, 0)
        }
        modelPanel.addView(modelsContainer)
        root.addView(modelPanel, matchWrap())

        groupsSection = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        groupsSection.addView(sectionTitle("分组成本"))
        val groupPanel = softPanel().apply { setPadding(dp(16), dp(14), dp(16), dp(14)) }
        groupsContainer = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        groupPanel.addView(groupsContainer)
        groupsSection.addView(groupPanel, matchWrap())
        root.addView(groupsSection)

        root.addView(sectionTitle("分析结论"))
        insightsText = TextView(this).apply {
            text = "正在分析…"
            textSize = 12.5f
            setTextColor(textSecondary())
            setLineSpacing(dp(4).toFloat(), 1f)
            setPadding(dp(16), dp(15), dp(16), dp(15))
            background = panelBackground()
            elevation = dp(5).toFloat()
        }
        root.addView(insightsText, matchWrap())

        root.addView(TextView(this).apply {
            text = "消费历史与分析结果仅保存在本机应用私有空间。站点支持明细接口时直接读取模型/分组统计；不支持时自动退回本地历史快照。"
            textSize = 10.5f
            setTextColor(textMuted())
            setPadding(dp(4), dp(14), dp(4), 0)
        })

        scroll.addView(root)
        return scroll
    }

    private fun render(report: AnalyticsReport) {
        sourcePill.text = report.sourceLabel
        total30Value.text = report.thirtyDayTotal?.let(::money) ?: "--"
        projectionText.text = report.projected30Days?.let { "按近 7 日均值，未来 30 天约 ${money(it)}" }
            ?: "历史数据不足，继续使用后会形成消费节奏"
        todayValue.text = report.todayCost?.let(::money) ?: "--"
        yesterdayValue.text = report.yesterdayCost?.let(::money) ?: "--"
        sevenValue.text = report.sevenDayTotal?.let(::money) ?: "--"
        perRequestValue.text = report.costPerRequest?.let(::money) ?: "--"
        perMillionValue.text = report.costPerMillionTokens?.let(::money) ?: "--"
        avg7Value.text = report.sevenDayAverage?.let(::money) ?: "--"
        peakValue.text = report.peakHour?.let {
            String.format(Locale.US, "%02d–%02d", it, (it + 1) % 24)
        } ?: "--"
        chart.setData(report.daily)

        modelsContainer.removeAllViews()
        if (report.models.isEmpty()) {
            modelsContainer.addView(emptyHint(if (report.remoteDetailed) "暂无模型消费" else "当前站点未返回模型明细；趋势分析仍正常工作。"))
        } else {
            report.models.forEachIndexed { index, item -> modelsContainer.addView(rankRow(index + 1, item.name, item.cost, item.share, item.requests, item.tokens)) }
        }

        groupsContainer.removeAllViews()
        groupsSection.visibility = if (report.groups.isEmpty()) View.GONE else View.VISIBLE
        report.groups.forEachIndexed { index, item ->
            groupsContainer.addView(rankRow(index + 1, item.name, item.cost, item.share, item.requests, item.tokens))
        }

        insightsText.text = report.insights.joinToString("\n\n") { "• $it" }
    }

    private fun rankRow(rank: Int, name: String, cost: Double, share: Double, requests: Long, tokens: Long): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(9), 0, dp(9))
        }
        val top = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        top.addView(TextView(this).apply {
            text = "$rank  $name"
            textSize = 12f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(textPrimary())
            maxLines = 1
        }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        top.addView(TextView(this).apply {
            text = "${money(cost)} · ${percent(share)}"
            textSize = 11.5f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(accentDark())
        })
        row.addView(top)
        row.addView(TextView(this).apply {
            text = "$requests 次 · ${TrayNotification.tokens(tokens)} Token"
            textSize = 10f
            setTextColor(textMuted())
            setPadding(dp(24), dp(4), 0, 0)
        })
        val track = LinearLayout(this).apply {
            background = GradientDrawable().apply {
                setColor(LuMingTheme.divider(this@AnalysisActivity))
                cornerRadius = dp(999).toFloat()
            }
        }
        val fill = View(this).apply {
            background = GradientDrawable().apply {
                setColor(accentColor())
                cornerRadius = dp(999).toFloat()
            }
        }
        track.addView(fill, LinearLayout.LayoutParams(0, dp(5), share.toFloat().coerceAtLeast(0.02f)))
        track.addView(View(this), LinearLayout.LayoutParams(0, dp(5), (1.0 - share).toFloat().coerceAtLeast(0f)))
        row.addView(track, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(5)).apply {
            topMargin = dp(7)
            leftMargin = dp(24)
        })
        return row
    }

    private fun metricCard(micro: String, label: String): Pair<LinearLayout, TextView> {
        val panel = softPanel().apply { setPadding(dp(15), dp(14), dp(15), dp(14)); minimumHeight = dp(104) }
        panel.addView(TextView(this).apply {
            text = micro
            textSize = 8.3f
            letterSpacing = 0.08f
            setTextColor(textMuted())
        })
        val value = TextView(this).apply {
            text = "--"
            textSize = 22f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(textPrimary())
            setPadding(0, dp(7), 0, dp(3))
        }
        panel.addView(value)
        panel.addView(TextView(this).apply { text = label; textSize = 11f; setTextColor(textSecondary()) })
        return panel to value
    }

    private fun smallMetric(label: String): Pair<LinearLayout, TextView> {
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL }
        box.addView(TextView(this).apply { text = label; textSize = 9.2f; setTextColor(textMuted()) })
        val value = TextView(this).apply {
            text = "--"
            textSize = 14f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(textPrimary())
            setPadding(0, dp(5), 0, 0)
        }
        box.addView(value)
        return box to value
    }

    private fun emptyHint(textValue: String) = TextView(this).apply {
        text = textValue
        textSize = 11f
        setTextColor(textMuted())
        setPadding(0, dp(8), 0, dp(4))
    }

    private fun labelText(textValue: String) = TextView(this).apply {
        text = textValue
        textSize = 12.5f
        setTypeface(typeface, Typeface.BOLD)
        setTextColor(textMuted())
    }

    private fun sectionTitle(textValue: String) = TextView(this).apply {
        text = textValue
        textSize = 13f
        setTypeface(typeface, Typeface.BOLD)
        setTextColor(textSecondary())
        setPadding(dp(3), dp(22), dp(3), dp(9))
    }

    private fun softPanel() = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        background = panelBackground()
        elevation = dp(7).toFloat()
    }

    private fun panelBackground() = GradientDrawable().apply {
        setColor(panelColor())
        cornerRadius = dp(24).toFloat()
        setStroke(dp(1), LuMingTheme.border(this@AnalysisActivity))
    }

    private fun pillBackground(color: Int) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = dp(999).toFloat()
    }

    private fun weighted(start: Int = 0, end: Int = 0) = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
        leftMargin = dp(start); rightMargin = dp(end)
    }

    private fun matchWrap() = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
    private fun bgColor() = LuMingTheme.bg(this)
    private fun panelColor() = LuMingTheme.panel(this)
    private fun accentColor() = LuMingTheme.accent(this)
    private fun accentDark() = LuMingTheme.accentDark(this)
    private fun textPrimary() = LuMingTheme.textPrimary(this)
    private fun textSecondary() = LuMingTheme.textSecondary(this)
    private fun textMuted() = LuMingTheme.textMuted(this)
    private fun money(value: Double) = String.format(Locale.US, "$%.4f", max(0.0, value)).trimEnd('0').trimEnd('.')
    private fun percent(value: Double) = String.format(Locale.US, "%.1f%%", value * 100.0)
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
}

class SpendBarChart(context: Context) : View(context) {
    private val barPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = LuMingTheme.accent(context) }
    private val missingPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = LuMingTheme.divider(context) }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = LuMingTheme.textMuted(context)
        textSize = 10f * resources.displayMetrics.scaledDensity
        textAlign = Paint.Align.CENTER
    }
    private val valuePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = LuMingTheme.textSecondary(context)
        textSize = 9f * resources.displayMetrics.scaledDensity
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
    }
    private var data: List<DailyCostPoint> = emptyList()

    fun setData(points: List<DailyCostPoint>) {
        data = points.takeLast(7)
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (data.isEmpty()) return
        val left = dp(6).toFloat()
        val right = width - dp(6).toFloat()
        val top = dp(18).toFloat()
        val bottom = height - dp(28).toFloat()
        val maxCost = max(0.000001, data.maxOfOrNull { it.cost } ?: 0.0)
        val slot = (right - left) / data.size
        val barWidth = slot * 0.52f

        data.forEachIndexed { index, point ->
            val center = left + slot * index + slot / 2f
            val ratio = (point.cost / maxCost).coerceIn(0.0, 1.0).toFloat()
            val barTop = bottom - (bottom - top) * ratio
            val paint = if (point.hasData) barPaint else missingPaint
            canvas.drawRoundRect(center - barWidth / 2, barTop, center + barWidth / 2, bottom, dp(6).toFloat(), dp(6).toFloat(), paint)
            canvas.drawText(point.label, center, height - dp(8).toFloat(), textPaint)
            if (point.hasData && point.cost > 0.0) {
                canvas.drawText(shortMoney(point.cost), center, (barTop - dp(5)).coerceAtLeast(dp(10).toFloat()), valuePaint)
            }
        }
    }

    private fun shortMoney(value: Double): String = when {
        value >= 10 -> String.format(Locale.US, "$%.1f", value)
        value >= 1 -> String.format(Locale.US, "$%.2f", value)
        else -> String.format(Locale.US, "$%.3f", value)
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
}
