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
            text = "在下方网页正常登录。看到控制台后，点底部“完成登录并读取数据”。App 不读取或保存你的账号密码。"
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
            settings.userAgentString = settings.userAgentString + " LuMing-Tray/0.4"
            webChromeClient = WebChromeClient()
            webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView?, url: String?) {
                    super.onPageFinished(view, url)
                    statusText.text = when {
                        url.isNullOrBlank() -> "网页已加载"
                        url.contains("/dashboard") -> "已进入控制台，可以点底部按钮读取数据。"
                        else -> "当前页面：${url.take(80)}"
                    }
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
            text = "完成登录并读取数据"
            isAllCaps = false
            gravity = Gravity.CENTER
            setOnClickListener { captureSessionAndRefresh() }
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

    private fun captureSessionAndRefresh() {
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

            if (authToken.isBlank() && cookie.isBlank()) {
                statusText.text = "没有检测到网页登录会话。请确认已经进入控制台，再重试。"
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
                "已获取网页登录令牌，正在读取仪表盘统计…"
            } else {
                "已获取 Cookie，正在尝试读取统计…"
            }

            UsageClient.refresh(this) { result ->
                if (result.success) {
                    Toast.makeText(this, "网页登录已保存，统计刷新成功", Toast.LENGTH_SHORT).show()
                    setResult(RESULT_OK)
                    finish()
                    return@refresh
                }

                // 最后一层兜底：既然 WebView 已经把仪表盘渲染出来，就直接读取页面文字。
                // 这不是 OCR，不需要截图，也不会读取提示词或模型响应正文。
                val pageStats = parseDashboardText(bodyText)
                if (pageStats != null) {
                    TrayStore.saveStats(this, pageStats)
                    TrayStore.saveLastMessage(this, "网页仪表盘直读 OK（接口兜底）")
                    TrayNotification.show(this, pageStats)
                    Toast.makeText(this, "已从网页仪表盘读取统计", Toast.LENGTH_SHORT).show()
                    setResult(RESULT_OK)
                    finish()
                } else {
                    statusText.text = "网页登录成功，但统计读取失败：${result.message}"
                }
            }
        }
    }

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
