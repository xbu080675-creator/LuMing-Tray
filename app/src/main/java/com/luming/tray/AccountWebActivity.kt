package com.luming.tray

import android.annotation.SuppressLint
import android.os.Bundle
import android.view.WindowManager
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.ComponentActivity
import org.json.JSONObject

class AccountWebActivity : ComponentActivity() {
    private lateinit var webView: WebView
    private var sessionInjected = false

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        val config = TrayStore.loadConfig(this)
        val root = siteRoot(config.baseUrl)

        webView = WebView(this).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.databaseEnabled = true
            settings.setSupportZoom(true)
            webChromeClient = WebChromeClient()
            webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView, url: String) {
                    super.onPageFinished(view, url)
                    if (!sessionInjected && url.startsWith(root)) {
                        sessionInjected = true
                        val auth = JSONObject.quote(config.webAuthToken)
                        val refresh = JSONObject.quote(config.webRefreshToken)
                        val expires = config.webTokenExpiresAt
                        view.evaluateJavascript(
                            """
                            (function(){
                              try {
                                if ($auth && $auth.length > 2) localStorage.setItem('auth_token', $auth);
                                if ($refresh && $refresh.length > 2) localStorage.setItem('refresh_token', $refresh);
                                if ($expires > 0) localStorage.setItem('token_expires_at', String($expires));
                              } catch(e) {}
                            })();
                            """.trimIndent()
                        ) { view.reload() }
                    }
                }
            }
        }
        CookieManager.getInstance().setAcceptCookie(true)
        setContentView(webView)
        webView.loadUrl("$root/profile")
    }

    override fun onBackPressed() {
        if (::webView.isInitialized && webView.canGoBack()) webView.goBack() else super.onBackPressed()
    }

    override fun onDestroy() {
        if (::webView.isInitialized) webView.destroy()
        super.onDestroy()
    }

    private fun siteRoot(baseUrl: String): String {
        val clean = baseUrl.trim().trimEnd('/')
        return when {
            clean.endsWith("/api/v1") -> clean.removeSuffix("/api/v1")
            clean.endsWith("/v1") -> clean.removeSuffix("/v1")
            else -> clean
        }
    }
}
