package com.luming.tray

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import kotlin.math.max

internal data class ChartTooltipRow(
    val label: String,
    val value: String,
    val color: Int? = null,
    val emphasized: Boolean = false
)

internal object ChartTooltip {
    fun draw(
        canvas: Canvas,
        context: Context,
        anchorX: Float,
        anchorY: Float,
        title: String,
        rows: List<ChartTooltipRow>,
        viewWidth: Int,
        viewHeight: Int
    ) {
        if (viewWidth <= 0 || viewHeight <= 0 || rows.isEmpty()) return

        val density = context.resources.displayMetrics.density
        val scaled = context.resources.displayMetrics.scaledDensity
        fun dp(value: Float) = value * density

        val titlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = LuMingTheme.textPrimary(context)
            textSize = 11.5f * scaled
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }
        val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = LuMingTheme.textSecondary(context)
            textSize = 10.5f * scaled
        }
        val valuePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = LuMingTheme.textPrimary(context)
            textSize = 10.5f * scaled
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            textAlign = Paint.Align.RIGHT
        }

        val padX = dp(12f)
        val padY = dp(10f)
        val rowGap = dp(6f)
        val dotSpace = dp(13f)
        val minWidth = dp(142f)
        val maxWidth = (viewWidth.toFloat() - dp(8f)).coerceAtLeast(dp(110f))

        var contentWidth = titlePaint.measureText(title)
        rows.forEach { row ->
            val dot = if (row.color != null) dotSpace else 0f
            contentWidth = max(contentWidth, dot + labelPaint.measureText(row.label) + dp(16f) + valuePaint.measureText(row.value))
        }
        val cardWidth = (contentWidth + padX * 2f).coerceIn(minWidth.coerceAtMost(maxWidth), maxWidth)
        val titleHeight = titlePaint.fontMetrics.run { bottom - top }
        val rowHeight = max(
            labelPaint.fontMetrics.run { bottom - top },
            valuePaint.fontMetrics.run { bottom - top }
        )
        val cardHeight = padY * 2f + titleHeight + dp(5f) + rows.size * rowHeight + (rows.size - 1).coerceAtLeast(0) * rowGap

        val margin = dp(4f)
        var left = anchorX - cardWidth / 2f
        left = left.coerceIn(margin, (viewWidth - cardWidth - margin).coerceAtLeast(margin))
        var top = anchorY - cardHeight - dp(14f)
        if (top < margin) top = anchorY + dp(14f)
        top = top.coerceIn(margin, (viewHeight - cardHeight - margin).coerceAtLeast(margin))
        val rect = RectF(left, top, left + cardWidth, top + cardHeight)

        val shadow = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(if (LuMingTheme.isDark(context)) 95 else 35, 0, 0, 0)
        }
        val background = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = LuMingTheme.panel(context) }
        val border = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = LuMingTheme.border(context)
            style = Paint.Style.STROKE
            strokeWidth = dp(1f)
        }
        val radius = dp(13f)
        canvas.drawRoundRect(RectF(rect.left, rect.top + dp(2f), rect.right, rect.bottom + dp(2f)), radius, radius, shadow)
        canvas.drawRoundRect(rect, radius, radius, background)
        canvas.drawRoundRect(rect, radius, radius, border)

        val usable = cardWidth - padX * 2f
        val titleText = fit(title, titlePaint, usable)
        var baseline = rect.top + padY - titlePaint.fontMetrics.top
        canvas.drawText(titleText, rect.left + padX, baseline, titlePaint)
        baseline += dp(5f) + rowHeight

        rows.forEachIndexed { index, row ->
            labelPaint.typeface = if (row.emphasized) Typeface.create(Typeface.DEFAULT, Typeface.BOLD) else Typeface.DEFAULT
            labelPaint.color = if (row.emphasized) LuMingTheme.textPrimary(context) else LuMingTheme.textSecondary(context)
            valuePaint.color = if (row.emphasized) LuMingTheme.accentDark(context) else LuMingTheme.textPrimary(context)

            var labelX = rect.left + padX
            if (row.color != null) {
                Paint(Paint.ANTI_ALIAS_FLAG).also { dotPaint ->
                    dotPaint.color = row.color
                    canvas.drawCircle(labelX + dp(3.5f), baseline + labelPaint.fontMetrics.ascent / 2f, dp(3.5f), dotPaint)
                }
                labelX += dotSpace
            }
            val valueWidth = valuePaint.measureText(row.value)
            val labelMax = (rect.right - padX - valueWidth - dp(12f) - labelX).coerceAtLeast(dp(20f))
            canvas.drawText(fit(row.label, labelPaint, labelMax), labelX, baseline, labelPaint)
            canvas.drawText(row.value, rect.right - padX, baseline, valuePaint)

            if (index < rows.lastIndex) baseline += rowHeight + rowGap
        }
    }

    private fun fit(value: String, paint: Paint, maxWidth: Float): String {
        if (maxWidth <= 0f) return ""
        if (paint.measureText(value) <= maxWidth) return value
        val suffix = "…"
        var end = value.length
        while (end > 1 && paint.measureText(value.substring(0, end) + suffix) > maxWidth) end--
        return value.substring(0, end.coerceAtLeast(1)) + suffix
    }
}
