package com.luming.tray

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.view.MotionEvent
import android.view.View
import java.util.Locale
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sin

internal class InteractiveSpendBarChart(context: Context) : View(context) {
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
    private val selectionPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = LuMingTheme.accentDark(context)
        style = Paint.Style.STROKE
        strokeWidth = dp(2).toFloat()
    }
    private val markerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = LuMingTheme.panel(context)
        style = Paint.Style.FILL
    }
    private var data: List<DailyCostPoint> = emptyList()
    private var selectedIndex = -1

    init { isClickable = true }

    fun setData(points: List<DailyCostPoint>) {
        data = points.takeLast(7)
        selectedIndex = -1
        invalidate()
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (data.isEmpty()) return false
        return when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> true
            MotionEvent.ACTION_UP -> {
                performClick()
                selectAt(event.x, event.y)
                true
            }
            MotionEvent.ACTION_CANCEL -> true
            else -> true
        }
    }

    private fun selectAt(x: Float, y: Float) {
        val left = dp(6).toFloat()
        val right = width - dp(6).toFloat()
        val bottom = height - dp(28).toFloat()
        if (x < left || x > right || y < 0f || y > bottom + dp(24)) {
            selectedIndex = -1
            invalidate()
            return
        }
        val slot = (right - left) / data.size.coerceAtLeast(1)
        val index = ((x - left) / slot).toInt().coerceIn(0, data.lastIndex)
        selectedIndex = if (selectedIndex == index) -1 else index
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
            if (index == selectedIndex) {
                canvas.drawRoundRect(
                    center - barWidth / 2 - dp(3), barTop - dp(3),
                    center + barWidth / 2 + dp(3), bottom + dp(3),
                    dp(8).toFloat(), dp(8).toFloat(), selectionPaint
                )
            }
        }

        data.getOrNull(selectedIndex)?.let { point ->
            val center = left + slot * selectedIndex + slot / 2f
            val ratio = (point.cost / maxCost).coerceIn(0.0, 1.0).toFloat()
            val barTop = bottom - (bottom - top) * ratio
            canvas.drawCircle(center, barTop, dp(5).toFloat(), markerPaint)
            canvas.drawCircle(center, barTop, dp(5).toFloat(), selectionPaint)
            ChartTooltip.draw(
                canvas, context, center, barTop, point.label,
                if (point.hasData) {
                    listOf(ChartTooltipRow("消费", fullMoney(point.cost), barPaint.color, true))
                } else {
                    listOf(ChartTooltipRow("状态", "暂无数据", missingPaint.color, true))
                },
                width, height
            )
        }
    }

    private fun shortMoney(value: Double): String = when {
        value >= 10 -> String.format(Locale.US, "$%.1f", value)
        value >= 1 -> String.format(Locale.US, "$%.2f", value)
        else -> String.format(Locale.US, "$%.3f", value)
    }

    private fun fullMoney(value: Double): String =
        String.format(Locale.US, "$%.6f", value).trimEnd('0').trimEnd('.')

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
}

internal class InteractiveDonutChartView(context: Context) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.BUTT
    }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = LuMingTheme.textPrimary(context)
        textAlign = Paint.Align.CENTER
        typeface = Typeface.DEFAULT_BOLD
    }
    private val markerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private var items: List<UsageBreakdownItem> = emptyList()
    private var selectedIndex = -1
    private val colors = intArrayOf(
        Color.rgb(62, 132, 238), Color.rgb(32, 181, 134), Color.rgb(246, 166, 35),
        Color.rgb(155, 111, 221), Color.rgb(229, 83, 94), Color.rgb(63, 174, 188), Color.rgb(120, 146, 164)
    )

    init { isClickable = true }

    fun setData(value: List<UsageBreakdownItem>) {
        items = value.filter { it.tokens > 0L }
        selectedIndex = -1
        invalidate()
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (items.isEmpty()) return false
        return when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> true
            MotionEvent.ACTION_UP -> {
                performClick()
                selectAt(event.x, event.y)
                true
            }
            MotionEvent.ACTION_CANCEL -> true
            else -> true
        }
    }

    private fun selectAt(x: Float, y: Float) {
        val size = minOf(width, height) * 0.62f
        val radius = size / 2f
        val centerX = width / 2f
        val centerY = height / 2f
        val baseStroke = max(22f, size * 0.18f)
        val distance = hypot((x - centerX).toDouble(), (y - centerY).toDouble()).toFloat()
        val tolerance = dp(10).toFloat()
        if (distance < radius - baseStroke / 2f - tolerance || distance > radius + baseStroke / 2f + tolerance) {
            selectedIndex = -1
            invalidate()
            return
        }

        val angle = ((Math.toDegrees(atan2((y - centerY).toDouble(), (x - centerX).toDouble())) + 450.0) % 360.0).toFloat()
        val total = items.sumOf { it.tokens }.coerceAtLeast(1L).toFloat()
        var start = 0f
        var hit = -1
        for (index in items.indices) {
            val sweep = 360f * items[index].tokens.toFloat() / total
            if (angle >= start && angle < start + sweep) {
                hit = index
                break
            }
            start += sweep
        }
        selectedIndex = if (hit < 0 || selectedIndex == hit) -1 else hit
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val totalLong = items.sumOf { it.tokens }
        if (totalLong <= 0L) return
        val total = totalLong.toFloat()
        val size = minOf(width, height) * 0.62f
        val left = (width - size) / 2f
        val top = (height - size) / 2f
        val rect = RectF(left, top, left + size, top + size)
        val radius = size / 2f
        val baseStroke = max(22f, size * 0.18f)
        var start = -90f

        items.forEachIndexed { index, item ->
            val sweep = 360f * item.tokens.toFloat() / total
            paint.strokeWidth = if (index == selectedIndex) baseStroke * 1.10f else baseStroke
            paint.color = colors[index % colors.size]
            canvas.drawArc(rect, start, sweep, false, paint)
            start += sweep
        }

        textPaint.textSize = size * 0.13f
        canvas.drawText(TrayNotification.tokens(totalLong), width / 2f, height / 2f + textPaint.textSize * 0.35f, textPaint)

        items.getOrNull(selectedIndex)?.let { item ->
            var sliceStart = -90f
            for (i in 0 until selectedIndex) {
                sliceStart += 360f * items[i].tokens.toFloat() / total
            }
            val sweep = 360f * item.tokens.toFloat() / total
            val theta = Math.toRadians((sliceStart + sweep / 2f).toDouble())
            val anchorRadius = radius + baseStroke * 0.52f
            val anchorX = width / 2f + cos(theta).toFloat() * anchorRadius
            val anchorY = height / 2f + sin(theta).toFloat() * anchorRadius
            markerPaint.color = LuMingTheme.panel(context)
            canvas.drawCircle(anchorX, anchorY, dp(5).toFloat(), markerPaint)
            markerPaint.color = colors[selectedIndex % colors.size]
            canvas.drawCircle(anchorX, anchorY, dp(3).toFloat(), markerPaint)

            val share = item.tokens.toDouble() / totalLong.toDouble()
            ChartTooltip.draw(
                canvas, context, anchorX, anchorY, item.name,
                listOf(
                    ChartTooltipRow("Token", TrayNotification.tokens(item.tokens), colors[selectedIndex % colors.size], true),
                    ChartTooltipRow("占比", String.format(Locale.US, "%.1f%%", share * 100.0))
                ),
                width, height
            )
        }
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
}

internal class InteractiveTokenTrendView(context: Context) : View(context) {
    private var points: List<UsageTrendPoint> = emptyList()
    private var selectedPoint = -1
    private var selectedSeries = -1
    private val axisPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = LuMingTheme.divider(context)
        strokeWidth = 1.5f
    }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = LuMingTheme.textMuted(context)
        textSize = 23f
    }
    private val seriesPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 4f
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val guidePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = LuMingTheme.border(context)
        strokeWidth = resources.displayMetrics.density
    }
    private val pointPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val haloPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = LuMingTheme.panel(context)
    }
    private val colors = intArrayOf(
        Color.rgb(62, 132, 238), Color.rgb(34, 174, 133),
        Color.rgb(242, 162, 39), Color.rgb(154, 109, 218)
    )
    private val seriesNames = arrayOf("输入", "输出", "Cache Read", "Cache Create")
    private val selectors: List<(UsageTrendPoint) -> Long> = listOf(
        { it.inputTokens }, { it.outputTokens }, { it.cacheReadTokens }, { it.cacheCreationTokens }
    )

    init { isClickable = true }

    fun setData(value: List<UsageTrendPoint>) {
        points = value
        selectedPoint = -1
        selectedSeries = -1
        invalidate()
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (points.isEmpty()) return false
        return when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> true
            MotionEvent.ACTION_UP -> {
                performClick()
                selectAt(event.x, event.y)
                true
            }
            MotionEvent.ACTION_CANCEL -> true
            else -> true
        }
    }

    private fun selectAt(touchX: Float, touchY: Float) {
        val left = 18f
        val right = width - 18f
        val top = 18f
        val bottom = height - 42f
        val tolerance = 28f * resources.displayMetrics.density
        if (touchX < left - tolerance || touchX > right + tolerance || touchY < top - tolerance || touchY > bottom + tolerance) {
            clearSelection()
            return
        }

        val maxValue = points.maxOf {
            max(max(it.inputTokens, it.outputTokens), max(it.cacheReadTokens, it.cacheCreationTokens))
        }.coerceAtLeast(1L)
        val count = points.size
        val position = if (count <= 1) 0f else {
            ((touchX - left) / (right - left) * (count - 1)).coerceIn(0f, (count - 1).toFloat())
        }
        val a = position.toInt().coerceIn(0, count - 1)
        val b = (a + 1).coerceAtMost(count - 1)
        val fraction = (position - a).coerceIn(0f, 1f)
        fun y(value: Double) = bottom - (bottom - top) * value.toFloat() / maxValue.toFloat()

        var nearestSeries = 0
        var nearestDistance = Float.MAX_VALUE
        selectors.forEachIndexed { series, selector ->
            val startValue = selector(points[a]).toDouble()
            val endValue = selector(points[b]).toDouble()
            val interpolated = startValue + (endValue - startValue) * fraction
            val distance = abs(touchY - y(interpolated))
            if (distance < nearestDistance) {
                nearestDistance = distance
                nearestSeries = series
            }
        }

        if (nearestDistance > tolerance) {
            clearSelection()
            return
        }

        val pointIndex = position.roundToInt().coerceIn(0, count - 1)
        if (selectedPoint == pointIndex && selectedSeries == nearestSeries) {
            clearSelection()
        } else {
            selectedPoint = pointIndex
            selectedSeries = nearestSeries
            invalidate()
        }
    }

    private fun clearSelection() {
        selectedPoint = -1
        selectedSeries = -1
        invalidate()
    }

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

        val maxValue = points.maxOf {
            max(max(it.inputTokens, it.outputTokens), max(it.cacheReadTokens, it.cacheCreationTokens))
        }.coerceAtLeast(1L)
        val count = points.size
        fun x(index: Int) = if (count <= 1) (left + right) / 2f else left + (right - left) * index / (count - 1).toFloat()
        fun y(value: Long) = bottom - (bottom - top) * value.toFloat() / maxValue.toFloat()

        selectors.forEachIndexed { series, selector ->
            seriesPaint.color = colors[series]
            for (i in 1 until count) {
                canvas.drawLine(x(i - 1), y(selector(points[i - 1])), x(i), y(selector(points[i])), seriesPaint)
            }
        }

        labelPaint.textAlign = Paint.Align.LEFT
        labelPaint.textSize = 22f
        canvas.drawText(short(points.first().label), left, height - 12f, labelPaint)
        if (count > 1) {
            labelPaint.textAlign = Paint.Align.RIGHT
            canvas.drawText(short(points.last().label), right, height - 12f, labelPaint)
        }

        if (selectedPoint in points.indices && selectedSeries in selectors.indices) {
            val selectedX = x(selectedPoint)
            canvas.drawLine(selectedX, top, selectedX, bottom, guidePaint)
            selectors.forEachIndexed { series, selector ->
                val selectedY = y(selector(points[selectedPoint]))
                val haloRadius = if (series == selectedSeries) dp(7).toFloat() else dp(5).toFloat()
                val pointRadius = if (series == selectedSeries) dp(4).toFloat() else dp(3).toFloat()
                canvas.drawCircle(selectedX, selectedY, haloRadius, haloPaint)
                pointPaint.color = colors[series]
                canvas.drawCircle(selectedX, selectedY, pointRadius, pointPaint)
            }

            val anchorY = y(selectors[selectedSeries](points[selectedPoint]))
            ChartTooltip.draw(
                canvas, context, selectedX, anchorY, points[selectedPoint].label,
                selectors.mapIndexed { series, selector ->
                    ChartTooltipRow(
                        seriesNames[series],
                        TrayNotification.tokens(selector(points[selectedPoint])),
                        colors[series],
                        series == selectedSeries
                    )
                },
                width, height
            )
        }
    }

    private fun short(value: String): String {
        if (value.length >= 10) return value.take(10).substring(5)
        return value.take(8)
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
}
