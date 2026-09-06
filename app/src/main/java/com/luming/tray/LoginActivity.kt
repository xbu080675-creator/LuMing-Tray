package com.luming.tray

import android.app.Activity
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.WindowInsets
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale
import kotlin.math.roundToLong

class LoginActivity : Activity() {
    private lateinit var webView: WebView
    private lateinit var statusText: TextView
    private var captureInProgress = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(buildUi())
        loadLoginPage()
    }

    private fun buildUi(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.rgb(244, 250, 250))
            setPadding(dp(12), dp(12), dp(12), dp(12))
            setOnApplyWindowInsetsListener { view, insets ->
                val top = if (Build.VERSION.SDK_INT >= 30) {
                    insets.getInsets(WindowInsets.Type.statusBars()).top
                } else {
                    @Suppress("DEPRECATION")
                    insets.systemWindowInsetTop
                }
                val bottom = if (Build.VERSION.SDK_INT >= 30) {
                    insets.getInsets(WindowInsets.Type.navigationBars()).bottom
                } else {
                    @Suppress("DEPRECATION")
                    insets.systemWindowInsetBottom
                }
                view.setPadding(dp(12), dp(10) + top, dp(12), dp(10) + bottom)
                insets
            }
        }

        root.addView(TextView(this).apply {
            text = "LuMing 网页登录"
            textSize = 22f
            setTextColor(Color.rgb(30, 38, 50))
        })

        root.addView(TextView(this).apply {
            text = "正常登录即可。进入控制台后 App 会自动读取并保存统计授权，不需要再手动点刷新。下方按钮只作为异常时的备用。"
            textSize = 12.5f
            setTextColor(Color.rgb(92, 102, 115))
            setPadding(0, dp(6), 0, dp(8))
        })

        statusText = TextView(this).apply {
            text = "等待网页登录…"
            textSize = 12.5f
            setTextColor(Color.rgb(86, 96, 110))
            setPadding(0, 0, 0, dp(6))
        }
        root.addView(statusText)

        webView = WebView(this).apply {
            setBackgroundColor(Color.WHITE)
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.databaseEnabled = true
            settings.userAgentString = settings.userAgentString + " LuMing-Tray/0.5"
            webChromeClient = WebChromeClient()
            webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView?, url: String?) {
                    super.onPageFinished(view, url)
                    statusText.text = when {
                        url.isNullOrBlank() -> "网页已加载，等待登录…"
                        url.contains("/login") -> "请正常登录，成功后会自动读取。"
                        else -> "网页已进入登录后页面，正在自动识别统计授权…"
                    }
                    scheduleAutoCapture()
                }
            }
        }

        val cookieManager = CookieManager.getInstance()
        cookieManager.setAcceptCookie(true)
        if (Build.VERSION.SDK_INT >= 21) {
            cookieManager.setAcceptThirdPartyCookies(webView, true)
        }

        root.addView(
            webView,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                1f
            )
        )

        root.addView(Button(this).apply {
            text = "手动读取（备用）"
            isAllCaps = false
            gravity = Gravity.CENTER
            setOnClickListener { captureSessionAndRefresh(autoTriggered = false) }
        }, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            dp(52)
        ).apply { topMargin = dp(8) })

        return root
    }

    private fun loadLoginPage() {
        val root = siteRoot(TrayStore.loadConfig(this).baseUrl)
        webView.loadUrl("$root/login")
    }

    private fun scheduleAutoCapture() {
        webView.postDelayed({
            if (!isFinishing && !captureInProgress) {
                captureSessionAndRefresh(autoTriggered = true)
            }
        }, 1000L)
    }

    private fun captureSessionAndRefresh(autoTriggered: Boolean) {
        if (captureInProgress) return
        captureInProgress = true

        val config = TrayStore.loadConfig(this)
        val root = siteRoot(config.baseUrl)
        val cookieManager = CookieManager.getInstance()
        cookieManager.flush()

        val rawCookies = mutableListOf<String>()
        cookieManager.getCookie(root)?.takeIf { it.isNotBlank() }?.let(rawCookies::add)
        webView.url?.let { current ->
            cookieManager.getCookie(current)?.takeIf { it.isNotBlank() }?.let(rawCookies::add)
        }
        val cookie = rawCookies
            .flatMap { it.split(';') }
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .distinct()
            .joinToString("; ")

        val script = """
            (function() {
              return JSON.stringify({
                auth_token: localStorage.getItem('auth_token') || '',
                refresh_token: localStorage.getItem('refresh_token') || '',
                token_expires_at: localStorage.getItem('token_expires_at') || '',
                auth_user: localStorage.getItem('auth_user') || '',
                body_text: document.body ? document.body.innerText : ''
              });
            })();
        """.trimIndent()

        webView.evaluateJavascript(script) { raw ->
            val decoded = decodeJavascriptString(raw)
            val payload = try {
                decoded?.let(::JSONObject)
            } catch (_: Exception) {
                null
            }

            val authToken = payload?.optString("auth_token").orEmpty().trim()
            val refreshToken = payload?.optString("refresh_token").orEmpty().trim()
            val expiresAt = payload?.optString("token_expires_at")
                ?.trim()
                ?.toLongOrNull() ?: 0L
            val bodyText = payload?.optString("body_text").orEmpty()
            val dashboardVisible = looksLikeDashboard(bodyText)

            if (autoTriggered && authToken.isBlank() && !dashboardVisible) {
                captureInProgress = false
                return@evaluateJavascript
            }

            if (authToken.isBlank() && cookie.isBlank()) {
                captureInProgress = false
                if (!autoTriggered) {
                    statusText.text = "没有检测到网页登录会话。请确认已经登录，再重试。"
                }
                return@evaluateJavascript
            }

            TrayStore.saveConfig(
                this,
                config.copy(
                    consoleCookie = cookie,
                    webAuthToken = authToken,
                    webRefreshToken = refreshToken,
                    webTokenExpiresAt = expiresAt
                )
            )

            statusText.text = if (authToken.isNotBlank()) {
                "登录成功，正在自动读取仪表盘统计…"
            } else {
                "已进入仪表盘，正在直接读取页面统计…"
            }

            UsageClient.refresh(this) { result ->
                if (result.success) {
                    Toast.makeText(this, "已自动接管统计，后续无需手动刷新", Toast.LENGTH_SHORT).show()
                    setResult(RESULT_OK)
                    finish()
                    return@refresh
                }

                val pageStats = parseDashboardText(bodyText)
                if (pageStats != null) {
                    TrayStore.saveStats(this, pageStats)
                    TrayStore.saveLastMessage(this, "网页仪表盘自动直读 OK")
                    TrayNotification.show(this, pageStats)
                    Toast.makeText(this, "已自动读取仪表盘统计", Toast.LENGTH_SHORT).show()
                    setResult(RESULT_OK)
                    finish()
                } else {
                    captureInProgress = false
                    statusText.text = "网页登录成功，但自动读取失败：${result.message}"
                }
            }
        }
    }

    private fun looksLikeDashboard(text: String): Boolean =
        text.contains("今日请求") && (text.contains("今日消费") || text.contains("今日 Token"))

    private fun parseDashboardText(text: String): UsageStats? {
        if (text.isBlank()) return null
        val normalized = text.replace('\u00A0', ' ')

        val balance = firstNumber(normalized, "余额")
        val todayCost = Regex("今日消费\\s*[^0-9]*([0-9]+(?:\\.[0-9]+)?)")
            .find(normalized)?.groupValues?.getOrNull(1)?.toDoubleOrNull()
        val requests = Regex("今日请求\\s*([0-9,]+)")
            .find(normalized)?.groupValues?.getOrNull(1)?.replace(",", "")?.toLongOrNull()
        val totalTokens = Regex("今日\\s*Token\\s*([0-9]+(?:\\.[0-9]+)?\\s*[KMB]?)", RegexOption.IGNORE_CASE)
            .find(normalized)?.groupValues?.getOrNull(1)?.let(::parseCompactLong)
        val inputTokens = Regex("输入[:：]?\\s*([0-9]+(?:\\.[0-9]+)?\\s*[KMB]?)", RegexOption.IGNORE_CASE)
            .find(normalized)?.groupValues?.getOrNull(1)?.let(::parseCompactLong)
        val outputTokens = Regex("输出[:：]?\\s*([0-9]+(?:\\.[0-9]+)?\\s*[KMB]?)", RegexOption.IGNORE_CASE)
            .find(normalized)?.groupValues?.getOrNull(1)?.let(::parseCompactLong)
        val performance = Regex(
            "性能指标\\s*([0-9]+(?:\\.[0-9]+)?\\s*[KMB]?)\\s*RPM\\s*([0-9]+(?:\\.[0-9]+)?\\s*[KMB]?)\\s*TPM",
            RegexOption.IGNORE_CASE
        ).find(normalized)
        val rpm = performance?.groupValues?.getOrNull(1)?.let(::parseCompactDouble)
        val tpm = performance?.groupValues?.getOrNull(2)?.let(::parseCompactDouble)
        val avgSeconds = Regex("平均响应\\s*([0-9]+(?:\\.[0-9]+)?)\\s*s", RegexOption.IGNORE_CASE)
            .find(normalized)?.groupValues?.getOrNull(1)?.toDoubleOrNull()

        val useful = balance != null || todayCost != null || requests != null || totalTokens != null
        if (!useful) return null

        return UsageStats(
            balance = balance,
            todayCost = todayCost,
            requests = requests,
            totalTokens = totalTokens,
            inputTokens = inputTokens,
            outputTokens = outputTokens,
            avgResponseSeconds = avgSeconds,
            rpm = rpm,
            tpm = tpm,
            updatedAt = System.currentTimeMillis()
        )
    }

    private fun firstNumber(text: String, label: String): Double? =
        Regex("${Regex.escape(label)}\\s*[^0-9]*([0-9]+(?:\\.[0-9]+)?)")
            .find(text)?.groupValues?.getOrNull(1)?.toDoubleOrNull()

    private fun parseCompactLong(value: String): Long? =
        parseCompactDouble(value)?.roundToLong()

    private fun parseCompactDouble(value: String): Double? {
        val clean = value.trim().replace(" ", "").uppercase(Locale.US)
        val multiplier = when {
            clean.endsWith("K") -> 1_000.0
            clean.endsWith("M") -> 1_000_000.0
            clean.endsWith("B") -> 1_000_000_000.0
            else -> 1.0
        }
        val number = clean.trimEnd('K', 'M', 'B').toDoubleOrNull() ?: return null
        return number * multiplier
    }

    private fun decodeJavascriptString(raw: String?): String? {
        if (raw.isNullOrBlank() || raw == "null") return null
        return try {
            val array = JSONArray("[$raw]")
            if (array.isNull(0)) null else array.getString(0)
        } catch (_: Exception) {
            null
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (::webView.isInitialized && webView.canGoBack()) {
            webView.goBack()
        } else {
            super.onBackPressed()
        }
    }

    override fun onDestroy() {
        if (::webView.isInitialized) {
            webView.stopLoading()
            webView.destroy()
        }
        super.onDestroy()
    }

    private fun siteRoot(baseUrl: String): String {
        val clean = baseUrl.trim().trimEnd('/')
        return if (clean.endsWith("/v1")) clean.dropLast(3).trimEnd('/') else clean
    }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()
}
