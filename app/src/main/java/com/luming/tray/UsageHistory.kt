package com.luming.tray

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import kotlin.math.max


data class DailyCostPoint(
    val date: String,
    val label: String,
    val cost: Double,
    val requests: Long,
    val tokens: Long,
    val hasData: Boolean = true
)

data class LocalCostAnalysis(
    val daily: List<DailyCostPoint>,
    val todayCost: Double?,
    val yesterdayCost: Double?,
    val sevenDayTotal: Double?,
    val sevenDayAverage: Double?,
    val thirtyDayTotal: Double?,
    val projected30Days: Double?,
    val costPerRequest: Double?,
    val costPerMillionTokens: Double?,
    val peakHour: Int?,
    val recordedDays: Int,
    val insights: List<String>
)

/** Local, app-private history used when the provider does not expose historical analytics. */
object UsageHistory {
    private const val DB_NAME = "luming_usage_history.db"
    private const val DB_VERSION = 1
    private const val SAMPLE_WINDOW_MS = 5 * 60 * 1000L
    private const val RETENTION_DAYS = 120

    private class Helper(context: Context) : SQLiteOpenHelper(context, DB_NAME, null, DB_VERSION) {
        override fun onCreate(db: SQLiteDatabase) {
            db.execSQL(
                """
                CREATE TABLE snapshots (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    ts INTEGER NOT NULL,
                    day TEXT NOT NULL,
                    balance REAL,
                    today_cost REAL,
                    requests INTEGER,
                    total_tokens INTEGER,
                    input_tokens INTEGER,
                    output_tokens INTEGER,
                    avg_response REAL
                )
                """.trimIndent()
            )
            db.execSQL("CREATE INDEX idx_snapshots_ts ON snapshots(ts)")
            db.execSQL("CREATE INDEX idx_snapshots_day ON snapshots(day)")
        }

        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
    }

    @Volatile private var helper: Helper? = null

    private fun db(context: Context): SQLiteDatabase {
        val current = helper ?: synchronized(this) {
            helper ?: Helper(context.applicationContext).also { helper = it }
        }
        return current.writableDatabase
    }

    @Synchronized
    fun recordSnapshot(context: Context, stats: UsageStats) {
        if (stats.todayCost == null && stats.requests == null && stats.totalTokens == null) return

        val database = db(context)
        val now = stats.updatedAt.takeIf { it > 0L } ?: System.currentTimeMillis()
        val day = dayString(now)
        var lastId: Long? = null
        var lastTs = 0L
        var lastDay: String? = null

        database.rawQuery(
            "SELECT id, ts, day FROM snapshots ORDER BY ts DESC LIMIT 1",
            null
        ).use { cursor ->
            if (cursor.moveToFirst()) {
                lastId = cursor.getLong(0)
                lastTs = cursor.getLong(1)
                lastDay = cursor.getString(2)
            }
        }

        val values = ContentValues().apply {
            put("ts", now)
            put("day", day)
            stats.balance?.let { put("balance", it) } ?: putNull("balance")
            stats.todayCost?.let { put("today_cost", it) } ?: putNull("today_cost")
            stats.requests?.let { put("requests", it) } ?: putNull("requests")
            stats.totalTokens?.let { put("total_tokens", it) } ?: putNull("total_tokens")
            stats.inputTokens?.let { put("input_tokens", it) } ?: putNull("input_tokens")
            stats.outputTokens?.let { put("output_tokens", it) } ?: putNull("output_tokens")
            stats.avgResponseSeconds?.let { put("avg_response", it) } ?: putNull("avg_response")
        }

        if (lastId != null && lastDay == day && now - lastTs < SAMPLE_WINDOW_MS) {
            database.update("snapshots", values, "id=?", arrayOf(lastId.toString()))
        } else {
            database.insert("snapshots", null, values)
        }

        val cutoff = now - RETENTION_DAYS * 24L * 60L * 60L * 1000L
        database.delete("snapshots", "ts < ?", arrayOf(cutoff.toString()))
    }

    fun analyze(context: Context, now: Long = System.currentTimeMillis()): LocalCostAnalysis {
        val start = now - 35L * 24L * 60L * 60L * 1000L
        val rows = mutableListOf<Row>()
        db(context).rawQuery(
            "SELECT ts, day, today_cost, requests, total_tokens FROM snapshots WHERE ts >= ? ORDER BY ts ASC",
            arrayOf(start.toString())
        ).use { cursor ->
            while (cursor.moveToNext()) {
                rows += Row(
                    ts = cursor.getLong(0),
                    day = cursor.getString(1),
                    cost = if (cursor.isNull(2)) null else cursor.getDouble(2),
                    requests = if (cursor.isNull(3)) null else cursor.getLong(3),
                    tokens = if (cursor.isNull(4)) null else cursor.getLong(4)
                )
            }
        }

        val latestByDay = linkedMapOf<String, Row>()
        rows.forEach { latestByDay[it.day] = it }
        val today = dayString(now)
        val yesterday = dayString(now - 24L * 60L * 60L * 1000L)

        fun dailyPoint(offset: Int): DailyCostPoint {
            val cal = Calendar.getInstance().apply {
                timeInMillis = now
                add(Calendar.DAY_OF_YEAR, offset)
            }
            val date = dayString(cal.timeInMillis)
            val row = latestByDay[date]
            return DailyCostPoint(
                date = date,
                label = SimpleDateFormat("MM/dd", Locale.getDefault()).format(Date(cal.timeInMillis)),
                cost = row?.cost ?: 0.0,
                requests = row?.requests ?: 0L,
                tokens = row?.tokens ?: 0L,
                hasData = row != null
            )
        }

        val daily7 = (-6..0).map(::dailyPoint)
        val daily30 = (-29..0).map(::dailyPoint)
        val known7 = daily7.filter { it.hasData }
        val known30 = daily30.filter { it.hasData }
        val todayRow = latestByDay[today]
        val yesterdayRow = latestByDay[yesterday]
        val sevenTotal = known7.takeIf { it.isNotEmpty() }?.sumOf { it.cost }
        val sevenAvg = known7.takeIf { it.isNotEmpty() }?.map { it.cost }?.average()
        val thirtyTotal = known30.takeIf { it.isNotEmpty() }?.sumOf { it.cost }
        val projected = sevenAvg?.times(30.0)

        val todayCost = todayRow?.cost
        val todayRequests = todayRow?.requests
        val todayTokens = todayRow?.tokens
        val costPerRequest = if (todayCost != null && todayRequests != null && todayRequests > 0) {
            todayCost / todayRequests
        } else null
        val costPerMillion = if (todayCost != null && todayTokens != null && todayTokens > 0) {
            todayCost * 1_000_000.0 / todayTokens
        } else null

        val hourBuckets = DoubleArray(24)
        var previous: Row? = null
        rows.filter { it.ts >= now - 7L * 24L * 60L * 60L * 1000L }.forEach { row ->
            val prev = previous
            if (prev != null && prev.day == row.day && prev.cost != null && row.cost != null) {
                val delta = row.cost - prev.cost
                if (delta >= 0.0) {
                    val cal = Calendar.getInstance().apply { timeInMillis = row.ts }
                    hourBuckets[cal.get(Calendar.HOUR_OF_DAY)] += delta
                }
            }
            previous = row
        }
        val peakHour = hourBuckets.indices.maxByOrNull { hourBuckets[it] }
            ?.takeIf { hourBuckets[it] > 0.0 }

        val insights = mutableListOf<String>()
        if (latestByDay.isEmpty()) {
            insights += "历史数据库刚开始积累；继续使用后会自动形成趋势。"
        } else {
            val prior = (-7..-1).map(::dailyPoint).filter { it.hasData }
            val priorAvg = prior.takeIf { it.isNotEmpty() }?.map { it.cost }?.average()
            if (todayCost != null && priorAvg != null && priorAvg > 0.0) {
                val ratio = todayCost / priorAvg
                when {
                    ratio >= 1.35 -> insights += "截至目前，今天消费比前 7 个有记录日的日均高 ${((ratio - 1.0) * 100).toInt()}%。"
                    ratio <= 0.75 -> insights += "截至目前，今天消费比前 7 个有记录日的日均低 ${((1.0 - ratio) * 100).toInt()}%。"
                }
            }
            peakHour?.let { insights += "最近 7 天本地记录中，${String.format(Locale.US, "%02d:00–%02d:00", it, (it + 1) % 24)} 是消费最集中的时段。" }
            if (costPerRequest != null) insights += "今天平均每次请求约 ${money(costPerRequest)}。"
            if (costPerMillion != null) insights += "今天每 100 万 Token 的实际成本约 ${money(costPerMillion)}。"
        }

        return LocalCostAnalysis(
            daily = daily7,
            todayCost = todayCost,
            yesterdayCost = yesterdayRow?.cost,
            sevenDayTotal = sevenTotal,
            sevenDayAverage = sevenAvg,
            thirtyDayTotal = thirtyTotal,
            projected30Days = projected,
            costPerRequest = costPerRequest,
            costPerMillionTokens = costPerMillion,
            peakHour = peakHour,
            recordedDays = latestByDay.size,
            insights = insights
        )
    }

    private data class Row(
        val ts: Long,
        val day: String,
        val cost: Double?,
        val requests: Long?,
        val tokens: Long?
    )

    private fun dayString(timestamp: Long): String =
        SimpleDateFormat("yyyy-MM-dd", Locale.US).apply {
            timeZone = TimeZone.getDefault()
        }.format(Date(timestamp))

    private fun money(value: Double): String =
        String.format(Locale.US, "$%.4f", max(0.0, value)).trimEnd('0').trimEnd('.')
}
