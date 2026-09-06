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
import okhttp3.OkHttpClient
import okhttp3.Request
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

object UsageClient {
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
        val config = TrayStore.loadConfig(context)
        if (config.apiKey.isBlank() && config.accessToken.isBlank()) {
            return RefreshResult(false, TrayStore.loadStats(context), "请先填写 API Key 或控制台 Access Token")
        }

        val root = siteRoot(config.baseUrl)
        val apiBase = apiBase(config.baseUrl)
        val notes = mutableListOf<String>()
        val quotaInfo = fetchQuotaInfo(root)

        var balance: Double? = null
        var todayCost: Double? = null
        var requests: Long? = null
        var inputTokens: Long? = null
        var outputTokens: Long? = null
        var avgSeconds: Double? = null

        if (config.accessToken.isNotBlank()) {
            val accessToken = config.accessToken.removePrefix("Bearer ").trim()

            requestJson("$root/api/user/self", accessToken)?.let { response ->
                if (response.optBoolean("success", false)) {
                    val data = response.optJSONObject("data")
                    val rawBalance = data?.number("quota", "Quota")
                    balance = quotaToMoney(rawBalance, quotaInfo)
                    notes += "余额 OK"
                } else {
                    notes += "余额接口：${response.messageOr("未授权")}"
                }
            } ?: run { notes += "余额接口不可用" }

            val dashboard = requestJson("$root/api/user/dashboard", accessToken)
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
                    notes += "今日统计 OK"
                } else {
                    todayCost = 0.0
                    requests = 0
                    inputTokens = 0
                    outputTokens = 0
                    notes += "今日暂无调用"
                }
            } else if (dashboard != null) {
                notes += "统计接口：${dashboard.messageOr("无数据")}"
            }

            val logStats = fetchTodayLogs(root, accessToken, quotaInfo)
            if (logStats != null) {
                avgSeconds = logStats.avgResponseSeconds

                // 只有 dashboard 没拿到时才用日志兜底，避免分页上限影响准确计数。
                if (requests == null) {
                    requests = logStats.requests
                    todayCost = logStats.todayCost
                    inputTokens = logStats.inputTokens
                    outputTokens = logStats.outputTokens
                    notes += "日志统计 OK"
                }
            }
        }

        // 仅有模型 API Key 时，尝试 OpenAI 兼容的 billing 接口。
        // 它通常只能可靠补余额，今日请求/Token 仍优先使用控制台 Access Token。
        if (config.apiKey.isNotBlank()) {
            val apiKey = config.apiKey.removePrefix("Bearer ").trim()
            val subscription = requestJson(
                "$apiBase/dashboard/billing/subscription",
                "Bearer $apiKey"
            )
            val usage = requestJson(
                "$apiBase/dashboard/billing/usage?start_date=2000-01-01&end_date=2099-12-31",
                "Bearer $apiKey"
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

        val hasUsefulData = balance != null || requests != null || inputTokens != null || outputTokens != null
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
            totalTokens = when {
                inputTokens != null || outputTokens != null -> (inputTokens ?: 0L) + (outputTokens ?: 0L)
                else -> previous?.totalTokens
            },
            inputTokens = inputTokens ?: previous?.inputTokens,
            outputTokens = outputTokens ?: previous?.outputTokens,
            avgResponseSeconds = avgSeconds ?: previous?.avgResponseSeconds,
            updatedAt = System.currentTimeMillis()
        )
        TrayStore.saveStats(context, stats)

        val msg = notes.distinct().joinToString(" · ").ifBlank { "刷新成功" }
        TrayStore.saveLastMessage(context, msg)
        TrayNotification.show(context, stats)
        return RefreshResult(true, stats, msg)
    }

    private fun fetchQuotaInfo(root: String): QuotaInfo {
        val response = requestJson("$root/api/status", null) ?: return QuotaInfo()
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

    private fun fetchTodayLogs(root: String, accessToken: String, quotaInfo: QuotaInfo): LogSummary? {
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

        // 常见 One API / New API 每页 10 条。最多取 100 条，
        // 对日常个人用量足够；若超过则 dashboard 的请求/Token 仍是主数据源。
        for (page in 0 until 10) {
            val url = "$root/api/log/self/?p=$page&type=2&start_timestamp=$start&end_timestamp=$end"
            val response = requestJson(url, accessToken) ?: break
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
                    // one-api-pro 的 elapsed_time 通常是秒；若某分支返回毫秒，做数量级兼容。
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

        // 不同分支有的返回原始 quota，有的已经返回金额。
        // 小数值优先视为已转换金额；大值按 quota_per_unit 换算。
        return if ((isCurrency || info.quotaPerUnit > 1.0) && raw >= 1_000.0) {
            raw / info.quotaPerUnit
        } else {
            raw
        }
    }

    private fun requestJson(url: String, authorization: String?): JSONObject? {
        return try {
            val builder = Request.Builder()
                .url(url)
                .get()
                .header("Accept", "application/json")
                .header("User-Agent", "LuMing-Tray/0.2")
            if (!authorization.isNullOrBlank()) {
                builder.header("Authorization", authorization)
            }
            client.newCall(builder.build()).execute().use { response ->
                val body = response.body?.string().orEmpty()
                if (!response.isSuccessful || body.isBlank()) return null
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
        string("message", "error")?.take(48) ?: fallback
}

class UsageWorker(
    appContext: Context,
    params: WorkerParameters
) : Worker(appContext, params) {
    override fun doWork(): Result {
        val config = TrayStore.loadConfig(applicationContext)
        if (config.apiKey.isBlank() && config.accessToken.isBlank()) return Result.success()
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
