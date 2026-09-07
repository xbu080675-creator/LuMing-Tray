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


data class UsageExplorerFilter(
    val days: Int = 7,
    val apiKeyId: Long? = null,
    val groupId: Long? = null,
    val model: String? = null
)

data class UsageBreakdownItem(
    val name: String,
    val requests: Long,
    val tokens: Long,
    val actualCost: Double
)

data class UsageTrendPoint(
    val label: String,
    val inputTokens: Long,
    val outputTokens: Long,
    val cacheCreationTokens: Long,
    val cacheReadTokens: Long,
    val totalTokens: Long,
    val actualCost: Double,
    val requests: Long
)

data class UsageLogItem(
    val id: Long,
    val apiKeyId: Long,
    val apiKeyName: String,
    val model: String,
    val groupName: String,
    val platform: String,
    val requestType: String,
    val stream: Boolean?,
    val inputTokens: Long,
    val outputTokens: Long,
    val cacheCreationTokens: Long,
    val cacheReadTokens: Long,
    val totalTokens: Long,
    val actualCost: Double,
    val durationMs: Long,
    val createdAt: String,
    val status: String
) {
    val cacheHitRate: Double?
        get() {
            val denom = inputTokens + cacheCreationTokens + cacheReadTokens
            if (denom <= 0L) return null
            return cacheReadTokens.toDouble() / denom.toDouble()
        }
}

data class UsageExplorerReport(
    val filter: UsageExplorerFilter,
    val keys: List<ManagedApiKey>,
    val groups: List<ApiKeyGroup>,
    val modelOptions: List<String>,
    val models: List<UsageBreakdownItem>,
    val groupsBreakdown: List<UsageBreakdownItem>,
    val platforms: List<UsageBreakdownItem>,
    val trend: List<UsageTrendPoint>,
    val logs: List<UsageLogItem>,
    val message: String
)

object UsageExplorerClient {
    private val client = OkHttpClient.Builder()
        .connectTimeout(12, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .callTimeout(30, TimeUnit.SECONDS)
        .build()

    fun load(context: Context, filter: UsageExplorerFilter, callback: (UsageExplorerReport) -> Unit) {
        Thread {
            val result = loadBlocking(context.applicationContext, filter)
            Handler(Looper.getMainLooper()).post { callback(result) }
        }.start()
    }

    fun loadBlocking(context: Context, filter: UsageExplorerFilter): UsageExplorerReport {
        UsageClient.refreshBlocking(context)
        val config = TrayStore.loadConfig(context)
        val token = config.webAuthToken.trim()
        val metadata = ApiKeyManagerClient.loadBlocking(context)
        if (token.isBlank()) {
            return UsageExplorerReport(
                filter, metadata.keys, metadata.groups, emptyList(), emptyList(), emptyList(), emptyList(), emptyList(), emptyList(),
                "请先完成网页登录授权"
            )
        }

        val root = siteRoot(config.baseUrl)
        val days = filter.days.coerceIn(1, 30)
        val start = dateString(daysAgo(days - 1))
        val end = dateString(System.currentTimeMillis())
        val timezone = TimeZone.getDefault().id
        val baseParams = mutableMapOf(
            "start_date" to start,
            "end_date" to end,
            "timezone" to timezone
        )
        filter.apiKeyId?.let { baseParams["api_key_id"] = it.toString() }
        filter.groupId?.let { baseParams["group_id"] = it.toString() }
        filter.model?.takeIf { it.isNotBlank() }?.let { baseParams["model"] = it }

        val snapshotParams = baseParams.toMutableMap().apply {
            put("granularity", if (days <= 2) "hour" else "day")
            put("include_trend", "true")
            put("include_model_stats", "true")
            put("include_group_stats", "true")
        }
        val snapshot = getObject(url("$root/api/v1/usage/dashboard/snapshot-v2", snapshotParams), token)

        var trend = parseTrend(snapshot?.optJSONArray("trend"))
        var models = parseModels(snapshot?.optJSONArray("models"))
        var groups = parseGroups(snapshot?.optJSONArray("groups"))

        if (trend.isEmpty()) {
            trend = parseTrend(getObject(url("$root/api/v1/usage/dashboard/trend", baseParams + ("granularity" to if (days <= 2) "hour" else "day")), token)?.optJSONArray("trend"))
        }
        if (models.isEmpty()) {
            models = parseModels(getObject(url("$root/api/v1/usage/dashboard/models", baseParams), token)?.optJSONArray("models"))
        }

        val platforms = parsePlatforms(getObject("$root/api/v1/usage/dashboard/stats", token)?.optJSONArray("by_platform"), days)
        val keyMap = metadata.keys.associateBy { it.id }
        val groupMap = metadata.groups.associateBy { it.id }
        val logsParams = baseParams.toMutableMap().apply {
            put("page", "1")
            put("page_size", "30")
            put("sort_by", "created_at")
            put("sort_order", "desc")
        }
        val logsObj = getObject(url("$root/api/v1/usage", logsParams), token)
        val logs = parseLogs(logsObj?.optJSONArray("items"), keyMap, groupMap)

        val modelOptions = buildSet {
            models.forEach { if (it.name.isNotBlank()) add(it.name) }
            logs.forEach { if (it.model.isNotBlank() && it.model != "未知模型") add(it.model) }
        }.sorted()

        val message = buildString {
            append("近 $days 天")
            if (filter.apiKeyId != null || filter.groupId != null || !filter.model.isNullOrBlank()) append(" · 已筛选")
            append(" · ${logs.size} 条最近请求")
        }
        return UsageExplorerReport(filter, metadata.keys, metadata.groups, modelOptions, models, groups, platforms, trend, logs, message)
    }

    private fun parseTrend(array: JSONArray?): List<UsageTrendPoint> {
        if (array == null) return emptyList()
        val out = mutableListOf<UsageTrendPoint>()
        for (i in 0 until array.length()) {
            val item = array.optJSONObject(i) ?: continue
            val input = item.optLong("input_tokens", 0L)
            val output = item.optLong("output_tokens", 0L)
            val cacheCreate = item.optLong("cache_creation_tokens", item.optLong("cache_write_tokens", 0L))
            val cacheRead = item.optLong("cache_read_tokens", 0L)
            val total = item.optLong("total_tokens", input + output + cacheCreate + cacheRead)
            out += UsageTrendPoint(
                label = item.optString("date").ifBlank { item.optString("time") },
                inputTokens = input,
                outputTokens = output,
                cacheCreationTokens = cacheCreate,
                cacheReadTokens = cacheRead,
                totalTokens = total,
                actualCost = item.optDouble("actual_cost", item.optDouble("cost", 0.0)),
                requests = item.optLong("requests", item.optLong("total_requests", 0L))
            )
        }
        return out
    }

    private fun parseModels(array: JSONArray?): List<UsageBreakdownItem> {
        if (array == null) return emptyList()
        val out = mutableListOf<UsageBreakdownItem>()
        for (i in 0 until array.length()) {
            val item = array.optJSONObject(i) ?: continue
            out += UsageBreakdownItem(
                name = item.optString("model").ifBlank { "未知模型" },
                requests = item.optLong("requests", 0L),
                tokens = item.optLong("total_tokens", 0L),
                actualCost = item.optDouble("actual_cost", item.optDouble("cost", 0.0))
            )
        }
        return out.sortedByDescending { it.tokens }.take(8)
    }

    private fun parseGroups(array: JSONArray?): List<UsageBreakdownItem> {
        if (array == null) return emptyList()
        val out = mutableListOf<UsageBreakdownItem>()
        for (i in 0 until array.length()) {
            val item = array.optJSONObject(i) ?: continue
            out += UsageBreakdownItem(
                name = item.optString("group_name").ifBlank { "分组 ${item.optLong("group_id", 0L)}" },
                requests = item.optLong("requests", 0L),
                tokens = item.optLong("total_tokens", 0L),
                actualCost = item.optDouble("actual_cost", item.optDouble("cost", 0.0))
            )
        }
        return out.sortedByDescending { it.tokens }.take(8)
    }

    private fun parsePlatforms(array: JSONArray?, days: Int): List<UsageBreakdownItem> {
        if (array == null) return emptyList()
        val out = mutableListOf<UsageBreakdownItem>()
        for (i in 0 until array.length()) {
            val item = array.optJSONObject(i) ?: continue
            val useToday = days <= 1
            out += UsageBreakdownItem(
                name = item.optString("platform").ifBlank { "unknown" },
                requests = item.optLong(if (useToday) "today_requests" else "total_requests", 0L),
                tokens = item.optLong(if (useToday) "today_tokens" else "total_tokens", 0L),
                actualCost = item.optDouble(if (useToday) "today_actual_cost" else "total_actual_cost", 0.0)
            )
        }
        return out.sortedByDescending { it.tokens }
    }

    private fun parseLogs(array: JSONArray?, keyMap: Map<Long, ManagedApiKey>, groupMap: Map<Long, ApiKeyGroup>): List<UsageLogItem> {
        if (array == null) return emptyList()
        val out = mutableListOf<UsageLogItem>()
        for (i in 0 until array.length()) {
            val item = array.optJSONObject(i) ?: continue
            val keyId = item.optLong("api_key_id", 0L)
            val groupId = item.optLong("group_id", 0L)
            val groupObj = item.optJSONObject("group")
            val keyObj = item.optJSONObject("api_key")
            val input = item.optLong("input_tokens", 0L)
            val output = item.optLong("output_tokens", 0L)
            val cacheCreate = item.optLong("cache_creation_tokens", item.optLong("cache_write_tokens", 0L))
            val cacheRead = item.optLong("cache_read_tokens", 0L)
            val total = item.optLong("total_tokens", input + output + cacheCreate + cacheRead)
            val duration = item.optLong("duration_ms", item.optLong("duration", item.optLong("latency_ms", 0L)))
            val stream = if (item.has("stream") && !item.isNull("stream")) item.optBoolean("stream") else null
            out += UsageLogItem(
                id = item.optLong("id", 0L),
                apiKeyId = keyId,
                apiKeyName = keyObj?.optString("name")?.takeIf { it.isNotBlank() } ?: keyMap[keyId]?.name ?: if (keyId > 0) "Key #$keyId" else "--",
                model = item.optString("model").ifBlank { item.optString("requested_model").ifBlank { "未知模型" } },
                groupName = groupObj?.optString("name")?.takeIf { it.isNotBlank() }
                    ?: item.optString("group_name").takeIf { it.isNotBlank() }
                    ?: groupMap[groupId]?.name ?: if (groupId > 0) "分组 #$groupId" else "无分组",
                platform = item.optString("platform").ifBlank { groupObj?.optString("platform") ?: groupMap[groupId]?.platform ?: "--" },
                requestType = item.optString("request_type").ifBlank { item.optString("type").ifBlank { "API" } },
                stream = stream,
                inputTokens = input,
                outputTokens = output,
                cacheCreationTokens = cacheCreate,
                cacheReadTokens = cacheRead,
                totalTokens = total,
                actualCost = item.optDouble("actual_cost", item.optDouble("cost", 0.0)),
                durationMs = duration,
                createdAt = item.optString("created_at").ifBlank { item.optString("time") },
                status = item.optString("status").ifBlank { item.optString("response_status").ifBlank { "success" } }
            )
        }
        return out
    }

    private fun getObject(url: String, token: String): JSONObject? {
        return try {
            val request = Request.Builder()
                .url(url)
                .get()
                .header("Accept", "application/json")
                .header("Authorization", "Bearer $token")
                .header("User-Agent", "LuMing-Tray/0.15")
                .build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return null
                val raw = response.body?.string().orEmpty()
                if (raw.isBlank()) return null
                val json = JSONObject(raw)
                if (json.has("code")) {
                    if (json.optInt("code", -1) != 0) return null
                    json.optJSONObject("data") ?: json
                } else json.optJSONObject("data") ?: json
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun url(base: String, params: Map<String, String>): String {
        if (params.isEmpty()) return base
        return base + "?" + params.entries.joinToString("&") {
            "${URLEncoder.encode(it.key, "UTF-8")}=${URLEncoder.encode(it.value, "UTF-8")}"
        }
    }

    private fun siteRoot(baseUrl: String): String {
        val clean = baseUrl.trim().trimEnd('/')
        return when {
            clean.endsWith("/api/v1") -> clean.removeSuffix("/api/v1")
            clean.endsWith("/v1") -> clean.removeSuffix("/v1")
            else -> clean
        }
    }

    private fun daysAgo(days: Int): Long = Calendar.getInstance().apply {
        add(Calendar.DAY_OF_YEAR, -days)
    }.timeInMillis

    private fun dateString(timestamp: Long): String =
        SimpleDateFormat("yyyy-MM-dd", Locale.US).apply { timeZone = TimeZone.getDefault() }.format(Date(timestamp))
}
