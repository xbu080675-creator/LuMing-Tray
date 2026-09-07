package com.luming.tray

import android.content.Context
import android.os.Handler
import android.os.Looper
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.TimeUnit
import kotlin.math.max


data class ModelSpend(
    val name: String,
    val cost: Double,
    val requests: Long,
    val tokens: Long,
    val share: Double
)

data class GroupSpend(
    val name: String,
    val cost: Double,
    val requests: Long,
    val tokens: Long,
    val share: Double
)

data class AnalyticsReport(
    val sourceLabel: String,
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
    val models: List<ModelSpend>,
    val groups: List<GroupSpend>,
    val insights: List<String>,
    val remoteDetailed: Boolean
)

object CostAnalyticsClient {
    private val client = OkHttpClient.Builder()
        .connectTimeout(12, TimeUnit.SECONDS)
        .readTimeout(18, TimeUnit.SECONDS)
        .callTimeout(25, TimeUnit.SECONDS)
        .build()

    fun load(context: Context, callback: (AnalyticsReport) -> Unit) {
        Thread {
            val report = loadBlocking(context.applicationContext)
            Handler(Looper.getMainLooper()).post { callback(report) }
        }.start()
    }

    private fun loadBlocking(context: Context): AnalyticsReport {
        // Refresh through the existing auth path first, so expiring Sub2API tokens are rotated.
        UsageClient.refreshBlocking(context)
        val current = TrayStore.loadStats(context)
        current?.let { UsageHistory.recordSnapshot(context, it) }
        val local = UsageHistory.analyze(context)
        val config = TrayStore.loadConfig(context)
        val token = config.webAuthToken.trim()
        if (token.isBlank()) return fromLocal(local)

        val root = siteRoot(config.baseUrl)
        val endDate = dateString(System.currentTimeMillis())
        val start30 = dateString(daysAgo(29))
        val start7 = dateString(daysAgo(6))
        val timezone = TimeZone.getDefault().id

        val trend30 = fetchTrend(root, token, start30, endDate, "day", timezone)
        val hourly7 = fetchTrend(root, token, start7, endDate, "hour", timezone)

        val snapshot = requestData(
            url(
                "$root/api/v1/usage/dashboard/snapshot-v2",
                mapOf(
                    "start_date" to start7,
                    "end_date" to endDate,
                    "granularity" to "day",
                    "include_trend" to "false",
                    "include_model_stats" to "true",
                    "include_group_stats" to "true",
                    "timezone" to timezone
                )
            ),
            token
        )

        var models = snapshot?.optJSONArray("models")?.let(::parseModels).orEmpty()
        val groups = snapshot?.optJSONArray("groups")?.let(::parseGroups).orEmpty()
        if (models.isEmpty()) {
            models = requestData(
                url(
                    "$root/api/v1/usage/dashboard/models",
                    mapOf("start_date" to start7, "end_date" to endDate, "timezone" to timezone)
                ),
                token
            )?.optJSONArray("models")?.let(::parseModels).orEmpty()
        }

        if (trend30.isEmpty() && models.isEmpty() && groups.isEmpty()) {
            return fromLocal(local)
        }

        val byDate = trend30.groupBy { it.date.take(10) }.mapValues { (_, points) ->
            RemoteTrend(
                date = points.first().date.take(10),
                requests = points.sumOf { it.requests },
                tokens = points.sumOf { it.tokens },
                cost = points.sumOf { it.cost }
            )
        }
        val now = System.currentTimeMillis()
        val daily7 = (-6..0).map { offset ->
            val ts = calendarDay(now, offset)
            val day = dateString(ts)
            val point = byDate[day]
            DailyCostPoint(
                date = day,
                label = SimpleDateFormat("MM/dd", Locale.getDefault()).format(Date(ts)),
                cost = point?.cost ?: 0.0,
                requests = point?.requests ?: 0L,
                tokens = point?.tokens ?: 0L,
                hasData = point != null
            )
        }
        val daily30 = (-29..0).mapNotNull { offset -> byDate[dateString(calendarDay(now, offset))] }
        val todayDay = dateString(now)
        val yesterdayDay = dateString(calendarDay(now, -1))
        val today = byDate[todayDay]
        val yesterday = byDate[yesterdayDay]
        val known7 = daily7.filter { it.hasData }
        val sevenTotal = known7.takeIf { it.isNotEmpty() }?.sumOf { it.cost }
        val sevenAvg = known7.takeIf { it.isNotEmpty() }?.map { it.cost }?.average()
        val thirtyTotal = daily30.takeIf { it.isNotEmpty() }?.sumOf { it.cost }
        val projected = sevenAvg?.times(30.0)

        val todayCost = today?.cost ?: current?.todayCost ?: local.todayCost
        val todayRequests = today?.requests?.takeIf { it > 0 } ?: current?.requests ?: 0L
        val todayTokens = today?.tokens?.takeIf { it > 0 } ?: current?.totalTokens ?: 0L
        val costPerRequest = if (todayCost != null && todayRequests > 0) todayCost / todayRequests else null
        val costPerMillion = if (todayCost != null && todayTokens > 0) todayCost * 1_000_000.0 / todayTokens else null

        val hourTotals = DoubleArray(24)
        hourly7.forEach { point ->
            parseHour(point.date)?.let { hour -> hourTotals[hour] += point.cost }
        }
        val remotePeak = hourTotals.indices.maxByOrNull { hourTotals[it] }
            ?.takeIf { hourTotals[it] > 0.0 }
        val peakHour = remotePeak ?: local.peakHour

        val modelTotal = models.sumOf { it.cost }
        val modelSpend = models.sortedByDescending { it.cost }.take(8).map {
            ModelSpend(
                name = it.name,
                cost = it.cost,
                requests = it.requests,
                tokens = it.tokens,
                share = if (modelTotal > 0.0) it.cost / modelTotal else 0.0
            )
        }
        val groupTotal = groups.sumOf { it.cost }
        val groupSpend = groups.sortedByDescending { it.cost }.take(6).map {
            GroupSpend(
                name = it.name,
                cost = it.cost,
                requests = it.requests,
                tokens = it.tokens,
                share = if (groupTotal > 0.0) it.cost / groupTotal else 0.0
            )
        }

        val insights = mutableListOf<String>()
        val previousDays = (-7..-1).mapNotNull { byDate[dateString(calendarDay(now, it))] }
        val priorAvg = previousDays.takeIf { it.isNotEmpty() }?.map { it.cost }?.average()
        if (todayCost != null && priorAvg != null && priorAvg > 0.0) {
            val ratio = todayCost / priorAvg
            when {
                ratio >= 1.35 -> insights += "截至目前，今天消费比前 7 个有记录日的日均高 ${((ratio - 1.0) * 100).toInt()}%。"
                ratio <= 0.75 -> insights += "截至目前，今天消费比前 7 个有记录日的日均低 ${((1.0 - ratio) * 100).toInt()}%。"
            }
        }
        modelSpend.firstOrNull()?.let { top ->
            insights += "近 7 天花费最多的是 ${top.name}：${money(top.cost)}，占模型消费 ${percent(top.share)}。"
            if (top.requests > 0) {
                insights += "${top.name} 平均每次请求约 ${money(top.cost / top.requests)}。"
            }
        }
        groupSpend.firstOrNull()?.let { top ->
            insights += "分组中 ${top.name} 的实际扣费最高：${money(top.cost)}（${percent(top.share)}）。"
        }
        peakHour?.let {
            insights += "最近 7 天 ${String.format(Locale.US, "%02d:00–%02d:00", it, (it + 1) % 24)} 是消费最集中的时段。"
        }
        if (costPerMillion != null) insights += "今天每 100 万 Token 的实际成本约 ${money(costPerMillion)}。"
        if (insights.isEmpty()) insights += "数据量还不够形成明确异常结论，继续使用后会自动积累。"

        return AnalyticsReport(
            sourceLabel = if (models.isNotEmpty()) "站点明细 + 本地历史" else "站点趋势 + 本地历史",
            daily = if (daily7.any { it.hasData }) daily7 else local.daily,
            todayCost = todayCost,
            yesterdayCost = yesterday?.cost ?: local.yesterdayCost,
            sevenDayTotal = sevenTotal ?: local.sevenDayTotal,
            sevenDayAverage = sevenAvg ?: local.sevenDayAverage,
            thirtyDayTotal = thirtyTotal ?: local.thirtyDayTotal,
            projected30Days = projected ?: local.projected30Days,
            costPerRequest = costPerRequest ?: local.costPerRequest,
            costPerMillionTokens = costPerMillion ?: local.costPerMillionTokens,
            peakHour = peakHour,
            models = modelSpend,
            groups = groupSpend,
            insights = insights,
            remoteDetailed = models.isNotEmpty()
        )
    }

    private fun fromLocal(local: LocalCostAnalysis): AnalyticsReport = AnalyticsReport(
        sourceLabel = "本机历史快照",
        daily = local.daily,
        todayCost = local.todayCost,
        yesterdayCost = local.yesterdayCost,
        sevenDayTotal = local.sevenDayTotal,
        sevenDayAverage = local.sevenDayAverage,
        thirtyDayTotal = local.thirtyDayTotal,
        projected30Days = local.projected30Days,
        costPerRequest = local.costPerRequest,
        costPerMillionTokens = local.costPerMillionTokens,
        peakHour = local.peakHour,
        models = emptyList(),
        groups = emptyList(),
        insights = local.insights,
        remoteDetailed = false
    )

    private data class RemoteTrend(val date: String, val requests: Long, val tokens: Long, val cost: Double)
    private data class RemoteModel(val name: String, val requests: Long, val tokens: Long, val cost: Double)
    private data class RemoteGroup(val name: String, val requests: Long, val tokens: Long, val cost: Double)

    private fun fetchTrend(
        root: String,
        token: String,
        startDate: String,
        endDate: String,
        granularity: String,
        timezone: String
    ): List<RemoteTrend> {
        val data = requestData(
            url(
                "$root/api/v1/usage/dashboard/trend",
                mapOf(
                    "start_date" to startDate,
                    "end_date" to endDate,
                    "granularity" to granularity,
                    "timezone" to timezone
                )
            ),
            token
        ) ?: return emptyList()
        val array = data.optJSONArray("trend") ?: return emptyList()
        val result = mutableListOf<RemoteTrend>()
        for (i in 0 until array.length()) {
            val item = array.optJSONObject(i) ?: continue
            result += RemoteTrend(
                date = item.optString("date"),
                requests = item.optLong("requests", 0L),
                tokens = item.optLong("total_tokens", 0L),
                cost = item.optDouble("actual_cost", item.optDouble("cost", 0.0))
            )
        }
        return result
    }

    private fun parseModels(array: JSONArray): List<RemoteModel> {
        val result = mutableListOf<RemoteModel>()
        for (i in 0 until array.length()) {
            val item = array.optJSONObject(i) ?: continue
            val name = item.optString("model").ifBlank { "未知模型" }
            result += RemoteModel(
                name = name,
                requests = item.optLong("requests", 0L),
                tokens = item.optLong("total_tokens", 0L),
                cost = item.optDouble("actual_cost", item.optDouble("cost", 0.0))
            )
        }
        return result
    }

    private fun parseGroups(array: JSONArray): List<RemoteGroup> {
        val result = mutableListOf<RemoteGroup>()
        for (i in 0 until array.length()) {
            val item = array.optJSONObject(i) ?: continue
            val name = item.optString("group_name").ifBlank { "分组 ${item.optLong("group_id")}" }
            result += RemoteGroup(
                name = name,
                requests = item.optLong("requests", 0L),
                tokens = item.optLong("total_tokens", 0L),
                cost = item.optDouble("actual_cost", item.optDouble("cost", 0.0))
            )
        }
        return result
    }

    private fun requestData(url: String, token: String): JSONObject? {
        return try {
            val request = Request.Builder()
                .url(url)
                .get()
                .header("Accept", "application/json")
                .header("Authorization", "Bearer $token")
                .header("User-Agent", "LuMing-Tray/0.10")
                .build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return null
                val raw = response.body?.string().orEmpty()
                if (raw.isBlank()) return null
                val json = JSONObject(raw)
                if (json.has("code")) {
                    if (json.optInt("code", -1) != 0) return null
                    json.optJSONObject("data")
                } else {
                    json.optJSONObject("data") ?: json
                }
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun url(base: String, params: Map<String, String>): String =
        base + "?" + params.entries.joinToString("&") {
            "${URLEncoder.encode(it.key, "UTF-8")}=${URLEncoder.encode(it.value, "UTF-8")}"
        }

    private fun siteRoot(baseUrl: String): String {
        val clean = baseUrl.trim().trimEnd('/')
        return if (clean.endsWith("/v1")) clean.dropLast(3).trimEnd('/') else clean
    }

    private fun daysAgo(days: Int): Long = Calendar.getInstance().apply {
        add(Calendar.DAY_OF_YEAR, -days)
    }.timeInMillis

    private fun calendarDay(now: Long, offset: Int): Long = Calendar.getInstance().apply {
        timeInMillis = now
        add(Calendar.DAY_OF_YEAR, offset)
    }.timeInMillis

    private fun dateString(timestamp: Long): String =
        SimpleDateFormat("yyyy-MM-dd", Locale.US).apply { timeZone = TimeZone.getDefault() }.format(Date(timestamp))

    private fun parseHour(value: String): Int? {
        val match = Regex("(?:T|\\s)(\\d{2}):").find(value) ?: return null
        return match.groupValues.getOrNull(1)?.toIntOrNull()?.takeIf { it in 0..23 }
    }

    private fun money(value: Double): String =
        String.format(Locale.US, "$%.4f", max(0.0, value)).trimEnd('0').trimEnd('.')

    private fun percent(value: Double): String = String.format(Locale.US, "%.1f%%", value * 100.0)
}
