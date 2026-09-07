package com.luming.tray

import android.content.Context
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import java.util.UUID
import java.util.concurrent.TimeUnit


data class ApiKeyGroup(
    val id: Long,
    val name: String,
    val platform: String,
    val rateMultiplier: Double,
    val effectiveRateMultiplier: Double,
    val exclusive: Boolean,
    val subscriptionType: String
)

data class ApiKeyUsageSummary(
    val todayActualCost: Double = 0.0,
    val totalActualCost: Double = 0.0
)

data class ManagedApiKey(
    val id: Long,
    val name: String,
    val groupId: Long?,
    val groupName: String?,
    val groupPlatform: String?,
    val groupRateMultiplier: Double?,
    val status: String,
    val quota: Double,
    val quotaUsed: Double,
    val currentConcurrency: Int,
    val rateLimit5h: Double,
    val rateLimit1d: Double,
    val rateLimit7d: Double,
    val usage5h: Double,
    val usage1d: Double,
    val usage7d: Double,
    val lastUsedAt: String?,
    val expiresAt: String?,
    val createdAt: String?,
    val ipWhitelist: List<String>,
    val ipBlacklist: List<String>
)

data class ApiKeyManagerReport(
    val keys: List<ManagedApiKey>,
    val groups: List<ApiKeyGroup>,
    val usage: Map<Long, ApiKeyUsageSummary>,
    val endpoint: String,
    val message: String
)

data class ApiKeyCreateInput(
    val name: String,
    val groupId: Long?,
    val quota: Double,
    val expiresInDays: Int?,
    val rateLimit5h: Double,
    val rateLimit1d: Double,
    val rateLimit7d: Double,
    val ipWhitelist: List<String>,
    val ipBlacklist: List<String>
)

data class ApiKeyUpdateInput(
    val name: String,
    val groupId: Long?,
    val quota: Double,
    val expiresAt: String?,
    val rateLimit5h: Double,
    val rateLimit1d: Double,
    val rateLimit7d: Double,
    val ipWhitelist: List<String>,
    val ipBlacklist: List<String>
)

data class ApiKeyOperationResult(
    val success: Boolean,
    val message: String,
    val secret: String? = null
)

/**
 * Native client for the user-side Sub2API API-key settings page.
 *
 * List responses can contain a full key, but LuMing deliberately ignores that field. The key
 * exists in memory only after an authenticated copy request or immediately after creation.
 */
object ApiKeyManagerClient {
    private val jsonType = "application/json; charset=utf-8".toMediaType()
    private val client = OkHttpClient.Builder()
        .connectTimeout(12, TimeUnit.SECONDS)
        .readTimeout(18, TimeUnit.SECONDS)
        .callTimeout(30, TimeUnit.SECONDS)
        .build()

    fun loadBlocking(context: Context): ApiKeyManagerReport {
        val session = session(context) ?: return ApiKeyManagerReport(
            keys = emptyList(),
            groups = emptyList(),
            usage = emptyMap(),
            endpoint = TrayStore.loadConfig(context).baseUrl,
            message = "尚未完成网页登录授权"
        )
        val (root, token, endpoint) = session
        val groups = fetchGroups(root, token)
        val groupMap = groups.associateBy { it.id }
        val keys = fetchAllKeys(root, token).map { key ->
            val group = key.groupId?.let(groupMap::get)
            if (group == null) key else key.copy(
                groupName = key.groupName ?: group.name,
                groupPlatform = key.groupPlatform ?: group.platform,
                groupRateMultiplier = key.groupRateMultiplier ?: group.effectiveRateMultiplier
            )
        }
        val usage = fetchBatchUsage(root, token, keys.map { it.id })
        val active = keys.count { it.status.equals("active", ignoreCase = true) }
        val message = if (keys.isEmpty()) "当前账号还没有 API Key" else "${keys.size} 把 Key · $active 把启用"
        return ApiKeyManagerReport(keys, groups, usage, endpoint, message)
    }

    fun createBlocking(context: Context, input: ApiKeyCreateInput): ApiKeyOperationResult {
        val session = session(context) ?: return ApiKeyOperationResult(false, "网页登录授权已失效")
        val (root, token) = session
        val payload = JSONObject().put("name", input.name.trim())
        if (input.groupId != null) payload.put("group_id", input.groupId) else payload.put("group_id", JSONObject.NULL)
        if (input.quota > 0.0) payload.put("quota", input.quota)
        input.expiresInDays?.takeIf { it > 0 }?.let { payload.put("expires_in_days", it) }
        if (input.rateLimit5h > 0.0) payload.put("rate_limit_5h", input.rateLimit5h)
        if (input.rateLimit1d > 0.0) payload.put("rate_limit_1d", input.rateLimit1d)
        if (input.rateLimit7d > 0.0) payload.put("rate_limit_7d", input.rateLimit7d)
        if (input.ipWhitelist.isNotEmpty()) payload.put("ip_whitelist", JSONArray(input.ipWhitelist))
        if (input.ipBlacklist.isNotEmpty()) payload.put("ip_blacklist", JSONArray(input.ipBlacklist))

        val response = request(
            url = "$root/api/v1/keys",
            token = token,
            method = "POST",
            body = payload,
            idempotencyKey = UUID.randomUUID().toString()
        )
        if (!response.success) return ApiKeyOperationResult(false, response.message)
        val secret = (response.value as? JSONObject)?.optString("key")?.takeIf { it.isNotBlank() }
        return ApiKeyOperationResult(true, "API Key 已创建", secret)
    }

    fun updateBlocking(context: Context, id: Long, input: ApiKeyUpdateInput): ApiKeyOperationResult {
        val session = session(context) ?: return ApiKeyOperationResult(false, "网页登录授权已失效")
        val (root, token) = session
        val payload = JSONObject()
            .put("name", input.name.trim())
            .put("quota", input.quota.coerceAtLeast(0.0))
            .put("rate_limit_5h", input.rateLimit5h.coerceAtLeast(0.0))
            .put("rate_limit_1d", input.rateLimit1d.coerceAtLeast(0.0))
            .put("rate_limit_7d", input.rateLimit7d.coerceAtLeast(0.0))
            .put("ip_whitelist", JSONArray(input.ipWhitelist))
            .put("ip_blacklist", JSONArray(input.ipBlacklist))
        if (input.groupId != null) payload.put("group_id", input.groupId) else payload.put("group_id", JSONObject.NULL)
        input.expiresAt?.let { payload.put("expires_at", it) }

        val response = request("$root/api/v1/keys/$id", token, "PUT", payload)
        return ApiKeyOperationResult(response.success, if (response.success) "API Key 设置已保存" else response.message)
    }

    fun toggleBlocking(context: Context, id: Long, active: Boolean): ApiKeyOperationResult {
        val session = session(context) ?: return ApiKeyOperationResult(false, "网页登录授权已失效")
        val (root, token) = session
        val response = request(
            "$root/api/v1/keys/$id",
            token,
            "PUT",
            JSONObject().put("status", if (active) "active" else "inactive")
        )
        return ApiKeyOperationResult(
            response.success,
            if (response.success) (if (active) "API Key 已启用" else "API Key 已停用") else response.message
        )
    }

    fun resetQuotaBlocking(context: Context, id: Long): ApiKeyOperationResult {
        val session = session(context) ?: return ApiKeyOperationResult(false, "网页登录授权已失效")
        val (root, token) = session
        val response = request(
            "$root/api/v1/keys/$id",
            token,
            "PUT",
            JSONObject().put("reset_quota", true).put("reset_rate_limit_usage", true)
        )
        return ApiKeyOperationResult(response.success, if (response.success) "Key 用量计数已重置" else response.message)
    }

    fun deleteBlocking(context: Context, id: Long): ApiKeyOperationResult {
        val session = session(context) ?: return ApiKeyOperationResult(false, "网页登录授权已失效")
        val (root, token) = session
        val response = request("$root/api/v1/keys/$id", token, "DELETE", null)
        return ApiKeyOperationResult(response.success, if (response.success) "API Key 已删除" else response.message)
    }

    fun fetchSecretBlocking(context: Context, id: Long): ApiKeyOperationResult {
        val session = session(context) ?: return ApiKeyOperationResult(false, "网页登录授权已失效")
        val (root, token) = session
        val response = request("$root/api/v1/keys/$id", token, "GET", null)
        if (!response.success) return ApiKeyOperationResult(false, response.message)
        val secret = (response.value as? JSONObject)?.optString("key")?.takeIf { it.isNotBlank() }
            ?: return ApiKeyOperationResult(false, "站点没有返回 Key 原文")
        return ApiKeyOperationResult(true, "API Key 已读取", secret)
    }

    private class Session(val root: String, val token: String, val endpoint: String) {
        operator fun component1() = root
        operator fun component2() = token
        operator fun component3() = endpoint
    }

    private fun session(context: Context): Session? {
        UsageClient.refreshBlocking(context)
        val config = TrayStore.loadConfig(context)
        val token = config.webAuthToken.trim()
        if (token.isBlank()) return null
        val root = siteRoot(config.baseUrl)
        return Session(root, token, config.baseUrl.trim().trimEnd('/'))
    }

    private fun fetchGroups(root: String, token: String): List<ApiKeyGroup> {
        val groupResult = request("$root/api/v1/groups/available", token, "GET", null)
        val array = groupResult.value as? JSONArray ?: return emptyList()
        val ratesObj = request("$root/api/v1/groups/rates", token, "GET", null).value as? JSONObject
        val result = mutableListOf<ApiKeyGroup>()
        for (i in 0 until array.length()) {
            val item = array.optJSONObject(i) ?: continue
            val id = item.optLong("id", 0L)
            if (id <= 0L) continue
            val baseRate = item.optDouble("rate_multiplier", 1.0)
            val override = ratesObj?.doubleOrNull(id.toString())
            result += ApiKeyGroup(
                id = id,
                name = item.optString("name", "分组 #$id"),
                platform = item.optString("platform", "unknown"),
                rateMultiplier = baseRate,
                effectiveRateMultiplier = override ?: baseRate,
                exclusive = item.optBoolean("is_exclusive", false),
                subscriptionType = item.optString("subscription_type", "standard")
            )
        }
        return result.sortedWith(compareBy<ApiKeyGroup> { it.platform }.thenBy { it.name })
    }

    private fun fetchAllKeys(root: String, token: String): List<ManagedApiKey> {
        val out = mutableListOf<ManagedApiKey>()
        var page = 1
        var pages = 1
        do {
            val url = "$root/api/v1/keys?page=$page&page_size=100&sort_by=created_at&sort_order=desc"
            val value = request(url, token, "GET", null).value as? JSONObject ?: break
            val items = value.optJSONArray("items") ?: JSONArray()
            for (i in 0 until items.length()) {
                items.optJSONObject(i)?.let(::parseKey)?.let(out::add)
            }
            pages = value.optInt("pages", 1).coerceAtLeast(1)
            page++
        } while (page <= pages && page <= 20)
        return out
    }

    private fun parseKey(item: JSONObject): ManagedApiKey? {
        val id = item.optLong("id", 0L)
        if (id <= 0L) return null
        val group = item.optJSONObject("group")
        return ManagedApiKey(
            id = id,
            name = item.optString("name", "Key #$id"),
            groupId = item.longOrNull("group_id"),
            groupName = group?.optString("name")?.takeIf { it.isNotBlank() },
            groupPlatform = group?.optString("platform")?.takeIf { it.isNotBlank() },
            groupRateMultiplier = group?.doubleOrNull("rate_multiplier"),
            status = item.optString("status", "inactive"),
            quota = item.optDouble("quota", 0.0),
            quotaUsed = item.optDouble("quota_used", 0.0),
            currentConcurrency = item.optInt("current_concurrency", 0),
            rateLimit5h = item.optDouble("rate_limit_5h", 0.0),
            rateLimit1d = item.optDouble("rate_limit_1d", 0.0),
            rateLimit7d = item.optDouble("rate_limit_7d", 0.0),
            usage5h = item.optDouble("usage_5h", 0.0),
            usage1d = item.optDouble("usage_1d", 0.0),
            usage7d = item.optDouble("usage_7d", 0.0),
            lastUsedAt = item.stringOrNull("last_used_at"),
            expiresAt = item.stringOrNull("expires_at"),
            createdAt = item.stringOrNull("created_at"),
            ipWhitelist = item.stringArray("ip_whitelist"),
            ipBlacklist = item.stringArray("ip_blacklist")
        )
    }

    private fun fetchBatchUsage(root: String, token: String, ids: List<Long>): Map<Long, ApiKeyUsageSummary> {
        if (ids.isEmpty()) return emptyMap()
        val out = mutableMapOf<Long, ApiKeyUsageSummary>()
        ids.chunked(100).forEach { chunk ->
            val payload = JSONObject().put("api_key_ids", JSONArray(chunk))
            val value = request("$root/api/v1/usage/dashboard/api-keys-usage", token, "POST", payload).value
            val rootObj = value as? JSONObject ?: return@forEach
            val stats = rootObj.optJSONObject("stats") ?: rootObj
            val names = stats.keys()
            while (names.hasNext()) {
                val key = names.next()
                val item = stats.optJSONObject(key) ?: continue
                val id = item.optLong("api_key_id", key.toLongOrNull() ?: 0L)
                if (id <= 0L) continue
                out[id] = ApiKeyUsageSummary(
                    todayActualCost = item.optDouble("today_actual_cost", 0.0),
                    totalActualCost = item.optDouble("total_actual_cost", 0.0)
                )
            }
        }
        return out
    }

    private data class HttpResult(val success: Boolean, val value: Any?, val message: String)

    private fun request(
        url: String,
        token: String,
        method: String,
        body: JSONObject?,
        idempotencyKey: String? = null
    ): HttpResult {
        val builder = Request.Builder()
            .url(url)
            .header("Authorization", "Bearer $token")
            .header("Accept", "application/json")
        if (!idempotencyKey.isNullOrBlank()) builder.header("Idempotency-Key", idempotencyKey)

        when (method) {
            "POST" -> builder.post((body ?: JSONObject()).toString().toRequestBody(jsonType))
            "PUT" -> builder.put((body ?: JSONObject()).toString().toRequestBody(jsonType))
            "DELETE" -> builder.delete()
            else -> builder.get()
        }

        return try {
            client.newCall(builder.build()).execute().use { response ->
                val raw = response.body?.string()?.trim().orEmpty()
                val parsed = if (raw.isBlank()) null else runCatching { JSONTokener(raw).nextValue() }.getOrNull()
                val message = when (parsed) {
                    is JSONObject -> parsed.optString("message").ifBlank {
                        parsed.optString("detail").ifBlank { "HTTP ${response.code}" }
                    }
                    else -> "HTTP ${response.code}"
                }
                if (!response.isSuccessful) return@use HttpResult(false, null, message)
                if (parsed is JSONObject && parsed.has("code") && parsed.optInt("code", 0) != 0) {
                    return@use HttpResult(false, null, message)
                }
                HttpResult(true, unwrapData(parsed), message.ifBlank { "OK" })
            }
        } catch (e: Exception) {
            HttpResult(false, null, e.message ?: "网络请求失败")
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
            clean.endsWith("/api/v1") -> clean.removeSuffix("/api/v1")
            clean.endsWith("/v1") -> clean.removeSuffix("/v1")
            else -> clean
        }
    }

    private fun JSONObject.longOrNull(key: String): Long? =
        if (!has(key) || isNull(key)) null else optLong(key)

    private fun JSONObject.doubleOrNull(key: String): Double? =
        if (!has(key) || isNull(key)) null else optDouble(key)

    private fun JSONObject.stringOrNull(key: String): String? =
        if (!has(key) || isNull(key)) null else optString(key).takeIf { it.isNotBlank() }

    private fun JSONObject.stringArray(key: String): List<String> {
        val array = optJSONArray(key) ?: return emptyList()
        return buildList {
            for (i in 0 until array.length()) {
                array.optString(i).trim().takeIf { it.isNotBlank() }?.let(::add)
            }
        }
    }
}