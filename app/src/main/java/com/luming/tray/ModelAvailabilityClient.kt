package com.luming.tray

import android.content.Context
import android.os.Handler
import android.os.Looper
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import java.util.Locale
import java.util.concurrent.TimeUnit


data class AvailableGroup(
    val name: String,
    val rateMultiplier: Double,
    val exclusive: Boolean,
    val subscriptionType: String
)

data class AvailablePlatformSection(
    val platform: String,
    val groups: List<AvailableGroup>,
    val models: List<String>
)

data class AvailableChannel(
    val name: String,
    val description: String,
    val platforms: List<AvailablePlatformSection>
)

data class AvailabilityTimelinePoint(
    val status: String,
    val latencyMs: Long?,
    val pingLatencyMs: Long?
)

data class ModelMonitor(
    val id: Long,
    val name: String,
    val provider: String,
    val groupName: String,
    val model: String,
    val status: String,
    val latencyMs: Long?,
    val pingLatencyMs: Long?,
    val availability7d: Double,
    val availability15d: Double?,
    val availability30d: Double?,
    val avgLatency7dMs: Long?,
    val timeline: List<AvailabilityTimelinePoint>
) {
    fun availability(days: Int): Double? = when (days) {
        15 -> availability15d ?: availability7d
        30 -> availability30d ?: availability15d ?: availability7d
        else -> availability7d
    }
}

data class ModelAvailabilityReport(
    val channels: List<AvailableChannel>,
    val monitors: List<ModelMonitor>,
    val message: String,
    val refreshedAt: Long = System.currentTimeMillis()
)

/**
 * Reads the same two authenticated Sub2API user endpoints shown by the web dashboard:
 * 1) /api/v1/channels/available       — channel/group/model catalog
 * 2) /api/v1/channel-monitors         — current health + recent timeline
 *    /api/v1/channel-monitors/:id/status — 7/15/30 day availability detail
 *
 * No HTML scraping is used. The existing encrypted Web login token is reused in memory.
 */
object ModelAvailabilityClient {
    private val client = OkHttpClient.Builder()
        .connectTimeout(12, TimeUnit.SECONDS)
        .readTimeout(18, TimeUnit.SECONDS)
        .callTimeout(30, TimeUnit.SECONDS)
        .build()

    fun load(context: Context, callback: (ModelAvailabilityReport) -> Unit) {
        Thread {
            val report = loadBlocking(context.applicationContext)
            Handler(Looper.getMainLooper()).post { callback(report) }
        }.start()
    }

    private fun loadBlocking(context: Context): ModelAvailabilityReport {
        // Let the existing auth path refresh an expiring Sub2API token first.
        UsageClient.refreshBlocking(context)
        val config = TrayStore.loadConfig(context)
        val token = config.webAuthToken.trim()
        if (token.isBlank()) {
            return ModelAvailabilityReport(
                channels = emptyList(),
                monitors = emptyList(),
                message = "尚未完成网页登录授权"
            )
        }

        val root = siteRoot(config.baseUrl)
        val channels = requestValue("$root/api/v1/channels/available", token)
            ?.let(::parseChannels)
            .orEmpty()

        val monitorList = requestValue("$root/api/v1/channel-monitors", token)
            ?.let(::parseMonitorList)
            .orEmpty()

        val monitors = monitorList.map { base ->
            val detail = requestObject("$root/api/v1/channel-monitors/${base.id}/status", token)
            mergeDetail(base, detail)
        }

        val parts = mutableListOf<String>()
        if (channels.isNotEmpty()) parts += "${channels.size} 个渠道"
        if (monitors.isNotEmpty()) parts += "${monitors.size} 路可用性监控"
        val message = if (parts.isEmpty()) {
            "站点没有返回可用渠道或可用性监控数据"
        } else {
            parts.joinToString(" · ")
        }

        return ModelAvailabilityReport(channels, monitors, message)
    }

    private fun parseChannels(value: Any): List<AvailableChannel> {
        val array = when (value) {
            is JSONArray -> value
            is JSONObject -> value.optJSONArray("items") ?: value.optJSONArray("channels") ?: return emptyList()
            else -> return emptyList()
        }
        val out = mutableListOf<AvailableChannel>()
        for (i in 0 until array.length()) {
            val channel = array.optJSONObject(i) ?: continue
            val sections = mutableListOf<AvailablePlatformSection>()
            val platforms = channel.optJSONArray("platforms") ?: JSONArray()
            for (p in 0 until platforms.length()) {
                val section = platforms.optJSONObject(p) ?: continue
                val groups = mutableListOf<AvailableGroup>()
                val groupArray = section.optJSONArray("groups") ?: JSONArray()
                for (g in 0 until groupArray.length()) {
                    val group = groupArray.optJSONObject(g) ?: continue
                    groups += AvailableGroup(
                        name = group.optString("name", "未命名分组"),
                        rateMultiplier = group.optDouble("rate_multiplier", 1.0),
                        exclusive = group.optBoolean("is_exclusive", false),
                        subscriptionType = group.optString("subscription_type", "standard")
                    )
                }

                val models = mutableListOf<String>()
                val modelArray = section.optJSONArray("supported_models") ?: JSONArray()
                for (m in 0 until modelArray.length()) {
                    val model = modelArray.optJSONObject(m)
                    val name = model?.optString("name")?.trim().orEmpty()
                    if (name.isNotEmpty()) models += name
                }
                sections += AvailablePlatformSection(
                    platform = section.optString("platform", "unknown"),
                    groups = groups,
                    models = models
                )
            }
            out += AvailableChannel(
                name = channel.optString("name", "未命名渠道"),
                description = channel.optString("description", ""),
                platforms = sections
            )
        }
        return out
    }

    private fun parseMonitorList(value: Any): List<ModelMonitor> {
        val root = value as? JSONObject ?: return emptyList()
        val items = root.optJSONArray("items") ?: return emptyList()
        val out = mutableListOf<ModelMonitor>()
        for (i in 0 until items.length()) {
            val item = items.optJSONObject(i) ?: continue
            val timeline = mutableListOf<AvailabilityTimelinePoint>()
            val timelineArray = item.optJSONArray("timeline") ?: JSONArray()
            for (t in 0 until timelineArray.length()) {
                val point = timelineArray.optJSONObject(t) ?: continue
                timeline += AvailabilityTimelinePoint(
                    status = point.optString("status", "unknown"),
                    latencyMs = point.longOrNull("latency_ms"),
                    pingLatencyMs = point.longOrNull("ping_latency_ms")
                )
            }
            out += ModelMonitor(
                id = item.optLong("id", 0L),
                name = item.optString("name", "未命名监控"),
                provider = item.optString("provider", "unknown"),
                groupName = item.optString("group_name", ""),
                model = item.optString("primary_model", ""),
                status = item.optString("primary_status", "unknown"),
                latencyMs = item.longOrNull("primary_latency_ms"),
                pingLatencyMs = item.longOrNull("primary_ping_latency_ms"),
                availability7d = item.optDouble("availability_7d", 0.0),
                availability15d = null,
                availability30d = null,
                avgLatency7dMs = null,
                timeline = timeline.takeLast(60)
            )
        }
        return out
    }

    private fun mergeDetail(base: ModelMonitor, detail: JSONObject?): ModelMonitor {
        if (detail == null) return base
        val models = detail.optJSONArray("models") ?: return base
        var selected: JSONObject? = null
        for (i in 0 until models.length()) {
            val model = models.optJSONObject(i) ?: continue
            if (model.optString("model") == base.model) {
                selected = model
                break
            }
            if (selected == null) selected = model
        }
        val model = selected ?: return base
        return base.copy(
            availability7d = model.optDouble("availability_7d", base.availability7d),
            availability15d = model.doubleOrNull("availability_15d"),
            availability30d = model.doubleOrNull("availability_30d"),
            avgLatency7dMs = model.longOrNull("avg_latency_7d_ms")
        )
    }

    private fun requestObject(url: String, token: String): JSONObject? =
        requestValue(url, token) as? JSONObject

    private fun requestValue(url: String, token: String): Any? {
        val request = Request.Builder()
            .url(url)
            .header("Authorization", "Bearer $token")
            .header("Accept", "application/json")
            .build()
        return try {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return null
                val body = response.body?.string()?.trim().orEmpty()
                if (body.isEmpty()) return null
                val parsed = JSONTokener(body).nextValue()
                unwrapData(parsed)
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun unwrapData(value: Any?): Any? {
        val obj = value as? JSONObject ?: return value
        if (!obj.has("data")) return obj
        val data = obj.opt("data")
        return if (data == null || data == JSONObject.NULL) obj else data
    }

    private fun siteRoot(baseUrl: String): String {
        val clean = baseUrl.trim().trimEnd('/')
        return when {
            clean.endsWith("/v1") -> clean.removeSuffix("/v1")
            clean.endsWith("/api/v1") -> clean.removeSuffix("/api/v1")
            else -> clean
        }
    }

    private fun JSONObject.longOrNull(key: String): Long? =
        if (!has(key) || isNull(key)) null else optLong(key)

    private fun JSONObject.doubleOrNull(key: String): Double? =
        if (!has(key) || isNull(key)) null else optDouble(key)

    fun rateLabel(value: Double): String =
        String.format(Locale.US, "%.3fx", value).trimEnd('0').trimEnd('.') + if (value == 0.0) "x" else ""
}
