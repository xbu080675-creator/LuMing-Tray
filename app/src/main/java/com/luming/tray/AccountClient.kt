package com.luming.tray

import android.content.Context
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import java.util.concurrent.TimeUnit


data class AccountBinding(
    val provider: String,
    val bound: Boolean,
    val displayName: String?,
    val subjectHint: String?,
    val canUnbind: Boolean
)

data class AccountPasskey(
    val id: Long,
    val name: String,
    val createdAt: String?,
    val lastUsedAt: String?,
    val backup: Boolean
)

data class AccountProfile(
    val id: Long,
    val username: String,
    val email: String,
    val avatarUrl: String?,
    val role: String,
    val balance: Double,
    val frozenBalance: Double,
    val concurrency: Int,
    val rpmLimit: Int,
    val status: String,
    val balanceNotifyEnabled: Boolean,
    val balanceNotifyThreshold: Double?,
    val createdAt: String?,
    val lastActiveAt: String?,
    val bindings: List<AccountBinding>,
    val totpEnabled: Boolean?,
    val passkeys: List<AccountPasskey>
)

data class AccountOperationResult(
    val success: Boolean,
    val message: String,
    val value: JSONObject? = null
)

data class TotpBeginResult(
    val success: Boolean,
    val message: String,
    val verificationMethod: String? = null,
    val secret: String? = null,
    val qrCodeUrl: String? = null,
    val setupToken: String? = null
)

object AccountClient {
    private val jsonType = "application/json; charset=utf-8".toMediaType()
    private val client = OkHttpClient.Builder()
        .connectTimeout(12, TimeUnit.SECONDS)
        .readTimeout(18, TimeUnit.SECONDS)
        .callTimeout(30, TimeUnit.SECONDS)
        .build()

    fun loadBlocking(context: Context): Pair<AccountProfile?, String> {
        val session = session(context) ?: return null to "请先完成网页登录授权"
        val profileResult = request(session, "GET", "/api/v1/user/profile")
        if (!profileResult.success) return null to profileResult.message
        val p = profileResult.value as? JSONObject ?: return null to "站点没有返回账户资料"

        val totp = request(session, "GET", "/api/v1/user/totp/status").value as? JSONObject
        val passkeysValue = request(session, "GET", "/api/v1/user/passkeys").value
        val passkeysArray = when (passkeysValue) {
            is JSONArray -> passkeysValue
            is JSONObject -> passkeysValue.optJSONArray("items") ?: passkeysValue.optJSONArray("passkeys") ?: JSONArray()
            else -> JSONArray()
        }

        val passkeys = buildList {
            for (i in 0 until passkeysArray.length()) {
                val item = passkeysArray.optJSONObject(i) ?: continue
                add(
                    AccountPasskey(
                        id = item.optLong("id", 0L),
                        name = item.optString("name", "Passkey"),
                        createdAt = item.stringOrNull("created_at"),
                        lastUsedAt = item.stringOrNull("last_used_at"),
                        backup = item.optBoolean("backup", false)
                    )
                )
            }
        }

        val bindingsRoot = p.optJSONObject("auth_bindings") ?: p.optJSONObject("identity_bindings")
        val providers = listOf("email", "github", "google", "linuxdo", "oidc", "wechat", "dingtalk")
        val bindings = providers.mapNotNull { provider ->
            val raw = bindingsRoot?.opt(provider) ?: return@mapNotNull null
            when (raw) {
                is Boolean -> AccountBinding(provider, raw, null, null, false)
                is JSONObject -> AccountBinding(
                    provider = provider,
                    bound = raw.optBoolean("bound", true),
                    displayName = raw.stringOrNull("display_name") ?: raw.stringOrNull("provider_label"),
                    subjectHint = raw.stringOrNull("subject_hint"),
                    canUnbind = raw.optBoolean("can_unbind", false)
                )
                else -> null
            }
        }

        return AccountProfile(
            id = p.optLong("id", 0L),
            username = p.optString("username", ""),
            email = p.optString("email", ""),
            avatarUrl = p.stringOrNull("avatar_url"),
            role = p.optString("role", "user"),
            balance = p.optDouble("balance", 0.0),
            frozenBalance = p.optDouble("frozen_balance", 0.0),
            concurrency = p.optInt("concurrency", 0),
            rpmLimit = p.optInt("rpm_limit", 0),
            status = p.optString("status", "active"),
            balanceNotifyEnabled = p.optBoolean("balance_notify_enabled", true),
            balanceNotifyThreshold = p.doubleOrNull("balance_notify_threshold"),
            createdAt = p.stringOrNull("created_at"),
            lastActiveAt = p.stringOrNull("last_active_at"),
            bindings = bindings,
            totpEnabled = totp?.let { it.optBoolean("enabled", false) },
            passkeys = passkeys
        ) to "账户资料已同步"
    }

    fun updateUsernameBlocking(context: Context, username: String): AccountOperationResult {
        val session = session(context) ?: return AccountOperationResult(false, "网页登录授权已失效")
        return request(session, "PUT", "/api/v1/user", JSONObject().put("username", username.trim())).toOperation("个人资料已更新")
    }

    fun updateBalanceNotifyBlocking(context: Context, enabled: Boolean, threshold: Double?): AccountOperationResult {
        val session = session(context) ?: return AccountOperationResult(false, "网页登录授权已失效")
        val body = JSONObject().put("balance_notify_enabled", enabled)
        if (threshold == null) body.put("balance_notify_threshold", JSONObject.NULL)
        else body.put("balance_notify_threshold", threshold.coerceAtLeast(0.0))
        return request(session, "PUT", "/api/v1/user", body).toOperation("站点余额邮件提醒已更新")
    }

    fun changePasswordBlocking(context: Context, oldPassword: String, newPassword: String): AccountOperationResult {
        val session = session(context) ?: return AccountOperationResult(false, "网页登录授权已失效")
        val body = JSONObject().put("old_password", oldPassword).put("new_password", newPassword)
        return request(session, "PUT", "/api/v1/user/password", body).toOperation("密码修改成功")
    }

    fun totpVerificationMethodBlocking(context: Context): AccountOperationResult {
        val session = session(context) ?: return AccountOperationResult(false, "网页登录授权已失效")
        return request(session, "GET", "/api/v1/user/totp/verification-method").toOperation("OK")
    }

    fun sendTotpEmailCodeBlocking(context: Context): AccountOperationResult {
        val session = session(context) ?: return AccountOperationResult(false, "网页登录授权已失效")
        return request(session, "POST", "/api/v1/user/totp/send-code", JSONObject()).toOperation("验证码已发送")
    }

    fun beginTotpSetupBlocking(context: Context, verificationMethod: String, proof: String): TotpBeginResult {
        val session = session(context) ?: return TotpBeginResult(false, "网页登录授权已失效")
        val body = JSONObject().put(if (verificationMethod == "email") "email_code" else "password", proof)
        val result = request(session, "POST", "/api/v1/user/totp/setup", body)
        if (!result.success) return TotpBeginResult(false, result.message)
        val data = result.value as? JSONObject ?: return TotpBeginResult(false, "站点没有返回 TOTP 配置")
        return TotpBeginResult(
            success = true,
            message = "TOTP 配置已生成",
            verificationMethod = verificationMethod,
            secret = data.optString("secret").takeIf { it.isNotBlank() },
            qrCodeUrl = data.optString("qr_code_url").takeIf { it.isNotBlank() },
            setupToken = data.optString("setup_token").takeIf { it.isNotBlank() }
        )
    }

    fun enableTotpBlocking(context: Context, setupToken: String, code: String): AccountOperationResult {
        val session = session(context) ?: return AccountOperationResult(false, "网页登录授权已失效")
        val body = JSONObject().put("setup_token", setupToken).put("totp_code", code)
        return request(session, "POST", "/api/v1/user/totp/enable", body).toOperation("双重验证已启用")
    }

    fun disableTotpBlocking(context: Context, verificationMethod: String, proof: String): AccountOperationResult {
        val session = session(context) ?: return AccountOperationResult(false, "网页登录授权已失效")
        val body = JSONObject().put(if (verificationMethod == "email") "email_code" else "password", proof)
        return request(session, "POST", "/api/v1/user/totp/disable", body).toOperation("双重验证已关闭")
    }

    private data class Session(val root: String, val token: String)
    private data class HttpResult(val success: Boolean, val value: Any?, val message: String)

    private fun session(context: Context): Session? {
        UsageClient.refreshBlocking(context)
        val config = TrayStore.loadConfig(context)
        val token = config.webAuthToken.trim()
        if (token.isBlank()) return null
        return Session(siteRoot(config.baseUrl), token)
    }

    private fun request(session: Session, method: String, path: String, body: JSONObject? = null): HttpResult {
        val builder = Request.Builder()
            .url(session.root + path)
            .header("Authorization", "Bearer ${session.token}")
            .header("Accept", "application/json")

        when (method) {
            "POST" -> builder.post((body ?: JSONObject()).toString().toRequestBody(jsonType))
            "PUT" -> builder.put((body ?: JSONObject()).toString().toRequestBody(jsonType))
            else -> builder.get()
        }

        return try {
            client.newCall(builder.build()).execute().use { response ->
                val raw = response.body?.string()?.trim().orEmpty()
                val parsed = if (raw.isBlank()) null else runCatching { JSONTokener(raw).nextValue() }.getOrNull()
                val message = when (parsed) {
                    is JSONObject -> parsed.optString("message").ifBlank { parsed.optString("detail").ifBlank { "HTTP ${response.code}" } }
                    else -> "HTTP ${response.code}"
                }
                if (!response.isSuccessful) return@use HttpResult(false, null, message)
                if (parsed is JSONObject && parsed.has("code") && parsed.optInt("code", 0) != 0) {
                    return@use HttpResult(false, null, message)
                }
                HttpResult(true, unwrap(parsed), message.ifBlank { "OK" })
            }
        } catch (e: Exception) {
            HttpResult(false, null, e.message ?: "网络请求失败")
        }
    }

    private fun unwrap(value: Any?): Any? {
        val obj = value as? JSONObject ?: return value
        if (!obj.has("data")) return obj
        val data = obj.opt("data")
        return if (data == null || data == JSONObject.NULL) obj else data
    }

    private fun HttpResult.toOperation(successMessage: String): AccountOperationResult =
        AccountOperationResult(success, if (success) successMessage else message, value as? JSONObject)

    private fun siteRoot(baseUrl: String): String {
        val clean = baseUrl.trim().trimEnd('/')
        return when {
            clean.endsWith("/api/v1") -> clean.removeSuffix("/api/v1")
            clean.endsWith("/v1") -> clean.removeSuffix("/v1")
            else -> clean
        }
    }

    private fun JSONObject.stringOrNull(key: String): String? =
        if (!has(key) || isNull(key)) null else optString(key).takeIf { it.isNotBlank() }

    private fun JSONObject.doubleOrNull(key: String): Double? =
        if (!has(key) || isNull(key)) null else optDouble(key)
}
