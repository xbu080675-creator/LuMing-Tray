package com.luming.tray

import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlin.math.max

data class RefreshResult(
    val success: Boolean,
    val stats: UsageStats?,
    val message: String
)

private data class QuotaInfo(
    val quotaPerUnit: Double = 500_000.0,
    val displayInCurrency: Boolean? = null,
    val displayType: String? = null
)

private data class Sub2Snapshot(
    val balance: Double? = null,
    val todayCost: Double? = null,
    val requests: Long? = null,
    val totalTokens: Long? = null,
    val inputTokens: Long? = null,
    val outputTokens: Long? = null,
    val avgResponseSeconds: Double? = null,
    val rpm: Double? = null,
    val tpm: Double? = null
)

private data class RefreshedWebAuth(
    val accessToken: String,
    val refreshToken: String,
    val expiresAt: Long
)

object UsageClient {
    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()
    private val client = OkHttpClient.Builder()
        .connectTimeout(12, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .callTimeout(20, TimeUnit.SECONDS)
        .build()

    fun refresh(context: Context, callback: (RefreshResult) -> Unit) {
        Thread {
            val result = refreshBlocking(context)
            Handler(Looper.getMainLooper()).post { callback(result) }
        }.start()
    }

    fun refreshBlocking(context: Context): RefreshResult {
        var config = TrayStore.loadConfig(context)
        if (
            config.apiKey.isBlank() &&
            config.accessToken.isBlank() &&
            config.consoleCookie.isBlank() &&
            config.webAuthToken.isBlank()
        ) {
            return RefreshResult(false, TrayStore.loadStats(context), "请先网页登录，或填写 API Key / Access Token")
        }

        val root = siteRoot(config.baseUrl)
        val apiBase = apiBase(config.baseUrl)
        val notes = mutableListOf<String>()
        val quotaInfo = fetchQuotaInfo(root)

        var balance: Double? = null
        var todayCost: Double? = null
        var requests: Long? = null
        var totalTokens: Long? = null
        var inputTokens: Long? = null
        var outputTokens: Long? = null
        var avgSeconds: Double? = null
        var rpm: Double? = null
        var tpm: Double? = null
        var sub2Matched = false

        // LuMing 当前网页与 Sub2API 前端高度一致：网页登录凭据实际放在
        // localStorage 的 auth_token / refresh_token，而不是只靠 Cookie。
        var webToken = config.webAuthToken.trim()
        if (webToken.isNotBlank()) {
            val expiringSoon = config.webTokenExpiresAt > 0L &&
                config.webTokenExpiresAt <= System.currentTimeMillis() + 60_000L
            if (expiringSoon && config.webRefreshToken.isNotBlank()) {
                refreshSub2Token(root, config.webRefreshToken)?.let { refreshed ->
                    config = config.copy(
                        webAuthToken = refreshed.accessToken,
                        webRefreshToken = refreshed.refreshToken,
                        webTokenExpiresAt = refreshed.expiresAt
                    )
                    TrayStore.saveConfig(context, config)
                    webToken = refreshed.accessToken
                    notes += "网页登录令牌已续期"
                }
            }

            var sub2 = fetchSub2Snapshot(root, webToken)
            if (sub2 == null && config.webRefreshToken.isNotBlank()) {
                refreshSub2Token(root, config.webRefreshToken)?.let { refreshed ->
                    config = config.copy(
                        webAuthToken = refreshed.accessToken,
                        webRefreshToken = refreshed.refreshToken,
                        webTokenExpiresAt = refreshed.expiresAt
                    )
                    TrayStore.saveConfig(context, config)
                    webToken = refreshed.accessToken
                    sub2 = fetchSub2Snapshot(root, webToken)
                    if (sub2 != null) notes += "网页登录令牌已恢复"
                }
            }

            if (sub2 != null) {
                sub2Matched = true
                balance = sub2.balance
                todayCost = sub2.todayCost
                requests = sub2.requests
                totalTokens = sub2.totalTokens
                inputTokens = sub2.inputTokens
                outputTokens = sub2.outputTokens
                avgSeconds = sub2.avgResponseSeconds
                rpm = sub2.rpm
                tpm = sub2.tpm
                notes += "Sub2API 仪表盘 OK"
            }
        }

        // 兼容 One API / New API 系站点；仅在 Sub2API 路径没有命中时尝试。
        val authHeader = config.accessToken
            .removePrefix("Bearer ")
            .trim()
            .takeIf { it.isNotBlank() }
        val cookie = config.consoleCookie.trim().takeIf { it.isNotBlank() }

        if (!sub2Matched && (authHeader != null || cookie != null)) {
            val authName = if (cookie != null) "网页登录" else "Access Token"

            requestJson("$root/api/user/self", authHeader, cookie)?.let { response ->
                if (response.optBoolean("success", false)) {
                    val data = response.optJSONObject("data")
                    val rawBalance = data?.number("quota", "Quota")
                    balance = quotaToMoney(rawBalance, quotaInfo)
                    notes += "${authName}认证 OK"
                    notes += "余额 OK"
                } else {
                    notes += "${authName}：${response.messageOr("未授权")}"
                }
            } ?: run {
                notes += "${authName}接口不可用"
            }

            val dashboard = requestJson("$root/api/user/dashboard", authHeader, cookie)
            if (dashboard?.optBoolean("success", false) == true) {
                val today = todayString()
                val array = dashboard.optJSONArray("data") ?: JSONArray()
                var dayQuota = 0.0
                var dayRequests = 0L
                var dayInput = 0L
                var dayOutput = 0L
                var matched = false

                for (i in 0 until array.length()) {
                    val item = array.optJSONObject(i) ?: continue
                    val day = item.string("day", "Day") ?: continue
                    if (day != today) continue
                    matched = true
                    dayQuota += item.number("quota", "Quota") ?: 0.0
                    dayRequests += item.longNumber("request_count", "RequestCount") ?: 0L
                    dayInput += item.longNumber("prompt_tokens", "PromptTokens") ?: 0L
                    dayOutput += item.longNumber("completion_tokens", "CompletionTokens") ?: 0L
                }

                if (matched) {
                    todayCost = quotaToMoney(dayQuota, quotaInfo)
                    requests = dayRequests
                    inputTokens = dayInput
                    outputTokens = dayOutput
                    totalTokens = dayInput + dayOutput
                    notes += "今日统计 OK"
                } else {
                    todayCost = 0.0
                    requests = 0
                    inputTokens = 0
                    outputTokens = 0
                    totalTokens = 0
                    notes += "今日暂无调用"
                }
            } else if (dashboard != null) {
                notes += "统计接口：${dashboard.messageOr("无数据")}"
            }

            val logStats = fetchTodayLogs(root, authHeader, cookie, quotaInfo)
            if (logStats != null) {
                avgSeconds = logStats.avgResponseSeconds
                if (requests == null) {
                    requests = logStats.requests
                    todayCost = logStats.todayCost
                    inputTokens = logStats.inputTokens
                    outputTokens = logStats.outputTokens
                    totalTokens = logStats.inputTokens + logStats.outputTokens
                    notes += "日志统计 OK"
                }
            }
        }

        if (config.apiKey.isNotBlank()) {
            val apiKey = config.apiKey.removePrefix("Bearer ").trim()
            val subscription = requestJson(
                "$apiBase/dashboard/billing/subscription",
                "Bearer $apiKey",
                null
            )
            val usage = requestJson(
                "$apiBase/dashboard/billing/usage?start_date=2000-01-01&end_date=2099-12-31",
                "Bearer $apiKey",
                null
            )

            if (balance == null && subscription != null) {
                val hardLimit = subscription.number(
                    "hard_limit_usd",
                    "system_hard_limit_usd",
                    "soft_limit_usd"
                )
                val totalUsageCents = usage?.number("total_usage")
                if (hardLimit != null) {
                    balance = if (totalUsageCents != null) {
                        max(0.0, hardLimit - totalUsageCents / 100.0)
                    } else {
                        hardLimit
                    }
                    notes += "API Key 计费 OK"
                }
            }
        }

        val hasUsefulData = balance != null || requests != null || totalTokens != null ||
            inputTokens != null || outputTokens != null
        val previous = TrayStore.loadStats(context)
        if (!hasUsefulData) {
            val msg = notes.distinct().joinToString(" · ").ifBlank { "未识别到兼容的统计接口" }
            TrayStore.saveLastMessage(context, msg)
            return RefreshResult(false, previous, msg)
        }

        val stats = UsageStats(
            balance = balance ?: previous?.balance,
            todayCost = todayCost ?: previous?.todayCost,
            requests = requests ?: previous?.requests,
            totalTokens = totalTokens ?: when {
                inputTokens != null || outputTokens != null -> (inputTokens ?: 0L) + (outputTokens ?: 0L)
                else -> previous?.totalTokens
            },
            inputTokens = inputTokens ?: previous?.inputTokens,
            outputTokens = outputTokens ?: previous?.outputTokens,
            avgResponseSeconds = avgSeconds ?: previous?.avgResponseSeconds,
            rpm = rpm ?: previous?.rpm,
            tpm = tpm ?: previous?.tpm,
            updatedAt = System.currentTimeMillis()
        )
        TrayStore.saveStats(context, stats)

        val msg = notes.distinct().joinToString(" · ").ifBlank { "刷新成功" }
        TrayStore.saveLastMessage(context, msg)
        TrayNotification.show(context, stats)
        return RefreshResult(true, stats, msg)
    }

    private fun fetchSub2Snapshot(root: String, token: String): Sub2Snapshot? {
        val authorization = "Bearer $token"
        val me = unwrapApiData(requestJson("$root/api/v1/auth/me", authorization, null))
        val dashboard = unwrapApiData(
            requestJson("$root/api/v1/usage/dashboard/stats", authorization, null)
        )

        if (me == null && dashboard == null) return null

        return Sub2Snapshot(
            balance = me?.number("balance"),
            todayCost = dashboard?.number("today_actual_cost", "today_cost"),
            requests = dashboard?.longNumber("today_requests"),
            totalTokens = dashboard?.longNumber("today_tokens"),
            inputTokens = dashboard?.longNumber("today_input_tokens"),
            outputTokens = dashboard?.longNumber("today_output_tokens"),
            avgResponseSeconds = dashboard?.number("average_duration_ms")?.div(1000.0),
            rpm = dashboard?.number("rpm"),
            tpm = dashboard?.number("tpm")
        )
    }

    private fun refreshSub2Token(root: String, refreshToken: String): RefreshedWebAuth? {
        val response = postJson(
            "$root/api/v1/auth/refresh",
            JSONObject().put("refresh_token", refreshToken)
        ) ?: return null
        val data = unwrapApiData(response) ?: return null
        val access = data.string("access_token") ?: return null
        val rotatedRefresh = data.string("refresh_token") ?: refreshToken
        val expiresIn = data.longNumber("expires_in") ?: 3600L
        return RefreshedWebAuth(
            accessToken = access,
            refreshToken = rotatedRefresh,
            expiresAt = System.currentTimeMillis() + expiresIn * 1000L
        )
    }

    private fun unwrapApiData(response: JSONObject?): JSONObject? {
        response ?: return null
        if (response.has("code")) {
            if (response.optInt("code", -1) != 0) return null
            return response.optJSONObject("data")
        }
        return response.optJSONObject("data") ?: response
    }

    private fun fetchQuotaInfo(root: String): QuotaInfo {
        val response = requestJson("$root/api/status", null, null) ?: return QuotaInfo()
        val data = response.optJSONObject("data") ?: response
        return QuotaInfo(
            quotaPerUnit = data.number("quota_per_unit", "QuotaPerUnit")
                ?.takeIf { it > 0.0 } ?: 500_000.0,
            displayInCurrency = when {
                data.has("display_in_currency") -> data.optBoolean("display_in_currency")
                data.has("DisplayInCurrency") -> data.optBoolean("DisplayInCurrency")
                else -> null
            },
            displayType = data.string("quota_display_type", "QuotaDisplayType")
        )
    }

    private data class LogSummary(
        val requests: Long,
        val todayCost: Double,
        val inputTokens: Long,
        val outputTokens: Long,
        val avgResponseSeconds: Double?
    )

    private fun fetchTodayLogs(
        root: String,
        authorization: String?,
        cookie: String?,
        quotaInfo: QuotaInfo
    ): LogSummary? {
        val calendar = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        val start = calendar.timeInMillis / 1000
        val end = System.currentTimeMillis() / 1000

        var count = 0L
        var quota = 0.0
        var input = 0L
        var output = 0L
        var elapsed = 0.0
        var elapsedCount = 0L
        var gotAnyResponse = false

        for (page in 0 until 10) {
            val url = "$root/api/log/self/?p=$page&type=2&start_timestamp=$start&end_timestamp=$end"
            val response = requestJson(url, authorization, cookie) ?: break
            gotAnyResponse = true
            if (!response.optBoolean("success", false)) break
            val data = response.optJSONArray("data") ?: break
            if (data.length() == 0) break

            for (i in 0 until data.length()) {
                val item = data.optJSONObject(i) ?: continue
                val createdAt = item.longNumber("created_at", "CreatedAt")
                if (createdAt != null && createdAt < start) continue
                count++
                quota += item.number("quota", "Quota") ?: 0.0
                input += item.longNumber("prompt_tokens", "PromptTokens") ?: 0L
                output += item.longNumber("completion_tokens", "CompletionTokens") ?: 0L

                val rawElapsed = item.number("elapsed_time", "ElapsedTime")
                if (rawElapsed != null && rawElapsed >= 0) {
                    elapsed += if (rawElapsed > 10_000) rawElapsed / 1000.0 else rawElapsed
                    elapsedCount++
                }
            }
            if (data.length() < 10) break
        }

        if (!gotAnyResponse) return null
        return LogSummary(
            requests = count,
            todayCost = quotaToMoney(quota, quotaInfo) ?: 0.0,
            inputTokens = input,
            outputTokens = output,
            avgResponseSeconds = if (elapsedCount > 0) elapsed / elapsedCount else null
        )
    }

    private fun quotaToMoney(raw: Double?, info: QuotaInfo): Double? {
        raw ?: return null
        if (raw == 0.0) return 0.0

        val displayType = info.displayType?.uppercase(Locale.US)
        val isCurrency = info.displayInCurrency == true ||
            displayType == "USD" ||
            displayType == "CURRENCY"

        return if ((isCurrency || info.quotaPerUnit > 1.0) && raw >= 1_000.0) {
            raw / info.quotaPerUnit
        } else {
            raw
        }
    }

    private fun requestJson(url: String, authorization: String?, cookie: String?): JSONObject? {
        return try {
            val builder = Request.Builder()
                .url(url)
                .get()
                .header("Accept", "application/json")
                .header("User-Agent", "LuMing-Tray/0.4")
            if (!authorization.isNullOrBlank()) {
                builder.header("Authorization", authorization)
            }
            if (!cookie.isNullOrBlank()) {
                builder.header("Cookie", cookie)
            }
            client.newCall(builder.build()).execute().use { response ->
                val body = response.body?.string().orEmpty()
                if (body.isBlank()) return null
                JSONObject(body)
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun postJson(url: String, payload: JSONObject): JSONObject? {
        return try {
            val request = Request.Builder()
                .url(url)
                .post(payload.toString().toRequestBody(jsonMediaType))
                .header("Accept", "application/json")
                .header("User-Agent", "LuMing-Tray/0.4")
                .build()
            client.newCall(request).execute().use { response ->
                val body = response.body?.string().orEmpty()
                if (body.isBlank()) return null
                JSONObject(body)
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun siteRoot(baseUrl: String): String {
        val clean = baseUrl.trim().trimEnd('/')
        return if (clean.endsWith("/v1")) clean.dropLast(3).trimEnd('/') else clean
    }

    private fun apiBase(baseUrl: String): String {
        val root = siteRoot(baseUrl)
        return "$root/v1"
    }

    private fun todayString(): String =
        SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())

    private fun JSONObject.number(vararg keys: String): Double? {
        for (key in keys) {
            if (!has(key) || isNull(key)) continue
            val value = opt(key)
            when (value) {
                is Number -> return value.toDouble()
                is String -> value.toDoubleOrNull()?.let { return it }
            }
        }
        return null
    }

    private fun JSONObject.longNumber(vararg keys: String): Long? =
        number(*keys)?.toLong()

    private fun JSONObject.string(vararg keys: String): String? {
        for (key in keys) {
            if (!has(key) || isNull(key)) continue
            val value = optString(key, "").trim()
            if (value.isNotEmpty()) return value
        }
        return null
    }

    private fun JSONObject.messageOr(fallback: String): String =
        string("message", "error")?.take(72) ?: fallback
}

class UsageWorker(
    appContext: Context,
    params: WorkerParameters
) : Worker(appContext, params) {
    override fun doWork(): Result {
        val config = TrayStore.loadConfig(applicationContext)
        if (
            config.apiKey.isBlank() &&
            config.accessToken.isBlank() &&
            config.consoleCookie.isBlank() &&
            config.webAuthToken.isBlank()
        ) {
            return Result.success()
        }
        UsageClient.refreshBlocking(applicationContext)
        return Result.success()
    }
}

object TrayScheduler {
    private const val WORK_NAME = "luming_usage_refresh"

    fun ensure(context: Context) {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()

        val request = PeriodicWorkRequestBuilder<UsageWorker>(30, TimeUnit.MINUTES)
            .setConstraints(constraints)
            .build()

        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            WORK_NAME,
            ExistingPeriodicWorkPolicy.UPDATE,
            request
        )
    }
}
