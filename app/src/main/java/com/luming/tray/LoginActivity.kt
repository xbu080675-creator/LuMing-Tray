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
            text = "在下方网页正常登录。看到控制台后，点底部“完成登录并刷新”。App 不读取或保存你的账号密码。"
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
            settings.userAgentString = settings.userAgentString + " LuMing-Tray/0.3"
            webChromeClient = WebChromeClient()
            webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView?, url: String?) {
                    super.onPageFinished(view, url)
                    statusText.text = if (url.isNullOrBlank()) {
                        "网页已加载"
                    } else {
                        "当前页面：${url.take(80)}"
                    }
                }
            }
        }

        val cookieManager = CookieManager.getInstance()
        cookieManager.setAcceptCookie(true)
        if (Build.VERSION.SDK_INT >= 21) {
            cookieManager.setAcceptThirdPartyCookies(webView, true)
        }

        root.addView(webView, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            0,
            1f
        ))

        root.addView(Button(this).apply {
            text = "完成登录并刷新"
            isAllCaps = false
            gravity = Gravity.CENTER
            setOnClickListener { captureCookieAndRefresh() }
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

    private fun captureCookieAndRefresh() {
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

        if (cookie.isBlank()) {
            statusText.text = "没有检测到登录 Cookie。请先完成网页登录，再重试。"
            return
        }

        TrayStore.saveConfig(this, config.copy(consoleCookie = cookie))
        statusText.text = "已获取登录会话，正在读取统计数据…"

        UsageClient.refresh(this) { result ->
            if (result.success) {
                Toast.makeText(this, "登录会话已保存，统计刷新成功", Toast.LENGTH_SHORT).show()
                setResult(RESULT_OK)
                finish()
            } else {
                statusText.text = "已获取 Cookie，但统计读取失败：${result.message}"
            }
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
