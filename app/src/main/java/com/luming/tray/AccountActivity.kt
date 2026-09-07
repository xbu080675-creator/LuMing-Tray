package com.luming.tray

import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PersistableBundle
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.WindowInsets
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.FragmentActivity
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

class AccountActivity : FragmentActivity() {
    private lateinit var statusPill: TextView
    private lateinit var profileBox: LinearLayout
    private lateinit var securityBox: LinearLayout
    private lateinit var serverNotifyBox: LinearLayout
    private lateinit var themeSystem: Button
    private lateinit var themeLight: Button
    private lateinit var themeDark: Button
    private var profile: AccountProfile? = null
    private var loading = false

    override fun onCreate(savedInstanceState: Bundle?) {
        LuMingTheme.applyActivityTheme(this)
        super.onCreate(savedInstanceState)
        LuMingTheme.applySystemBars(this)
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        setContentView(buildUi())
        loadData()
    }

    private fun buildUi(): View {
        val scroll = ScrollView(this).apply {
            setBackgroundColor(bgColor())
            overScrollMode = View.OVER_SCROLL_NEVER
        }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(16), dp(18), dp(34))
            setOnApplyWindowInsetsListener { view, insets ->
                val top = if (Build.VERSION.SDK_INT >= 30) insets.getInsets(WindowInsets.Type.statusBars()).top else {
                    @Suppress("DEPRECATION") insets.systemWindowInsetTop
                }
                val bottom = if (Build.VERSION.SDK_INT >= 30) insets.getInsets(WindowInsets.Type.navigationBars()).bottom else {
                    @Suppress("DEPRECATION") insets.systemWindowInsetBottom
                }
                view.setPadding(dp(18), dp(12) + top, dp(18), dp(24) + bottom)
                insets
            }
        }

        val header = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        header.addView(TextView(this).apply {
            text = "‹"; textSize = 34f; gravity = Gravity.CENTER; setTextColor(textPrimary()); setOnClickListener { finish() }
        }, LinearLayout.LayoutParams(dp(44), dp(48)))
        val title = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        title.addView(TextView(this).apply {
            text = "账户中心"; textSize = 25f; setTypeface(typeface, Typeface.BOLD); setTextColor(textPrimary())
        })
        title.addView(TextView(this).apply {
            text = "PROFILE · SECURITY · APPEARANCE"; textSize = 9.5f; letterSpacing = 0.08f; setTextColor(textMuted())
        })
        header.addView(title, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        statusPill = TextView(this).apply {
            text = "同步中…"; textSize = 10f; gravity = Gravity.CENTER; setTextColor(accentDark())
            setPadding(dp(10), dp(6), dp(10), dp(6)); background = pillBackground(LuMingTheme.positivePill(this@AccountActivity))
        }
        header.addView(statusPill)
        root.addView(header)

        root.addView(sectionTitle("个人资料"))
        profileBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(profileBox)

        root.addView(sectionTitle("站点账户设置"))
        serverNotifyBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(serverNotifyBox)

        root.addView(sectionTitle("安全与登录"))
        securityBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(securityBox)

        root.addView(sectionTitle("应用外观"))
        val themePanel = softPanel().apply { setPadding(dp(16), dp(15), dp(16), dp(15)) }
        themePanel.addView(TextView(this).apply {
            text = "显示模式"; textSize = 14f; setTypeface(typeface, Typeface.BOLD); setTextColor(textPrimary())
        })
        themePanel.addView(TextView(this).apply {
            text = "系统跟随 / 浅色 / 深色。切换后整个 LuMing 原生界面立即生效。"; textSize = 10.5f; setTextColor(textMuted()); setPadding(0, dp(4), 0, dp(10))
        })
        val themeRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        themeSystem = smallButton("跟随系统") { changeTheme(LuMingThemeMode.SYSTEM) }
        themeLight = smallButton("浅色") { changeTheme(LuMingThemeMode.LIGHT) }
        themeDark = smallButton("深色") { changeTheme(LuMingThemeMode.DARK) }
        themeRow.addView(themeSystem, weighted(end = 4)); themeRow.addView(themeLight, weighted(start = 2, end = 2)); themeRow.addView(themeDark, weighted(start = 4))
        themePanel.addView(themeRow)
        root.addView(themePanel)
        updateThemeButtons()

        root.addView(TextView(this).apply {
            text = "LuMing Tray 0.16.0 · Account Center / Dark Mode"
            textSize = 10.5f; gravity = Gravity.CENTER; setTextColor(textMuted()); setPadding(0, dp(24), 0, 0)
        })

        scroll.addView(root)
        return scroll
    }

    private fun loadData() {
        if (loading) return
        loading = true
        statusPill.text = "同步中…"
        Thread {
            val (data, message) = AccountClient.loadBlocking(applicationContext)
            runOnUiThread {
                loading = false
                profile = data
                statusPill.text = if (data == null) "未授权" else "已同步"
                renderProfile(data, message)
            }
        }.start()
    }

    private fun renderProfile(p: AccountProfile?, message: String) {
        profileBox.removeAllViews(); serverNotifyBox.removeAllViews(); securityBox.removeAllViews()
        if (p == null) {
            val panel = softPanel().apply { setPadding(dp(16), dp(16), dp(16), dp(16)) }
            panel.addView(info(message))
            panel.addView(actionButton("网页登录授权") { startActivity(Intent(this, LoginActivity::class.java)) }, matchHeight(48).apply { topMargin = dp(12) })
            profileBox.addView(panel)
            return
        }

        val hero = softPanel().apply { setPadding(dp(18), dp(16), dp(18), dp(16)) }
        val top = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val avatar = TextView(this).apply {
            text = p.username.trim().take(1).ifBlank { "U" }.uppercase(Locale.getDefault())
            textSize = 20f; setTypeface(typeface, Typeface.BOLD); gravity = Gravity.CENTER; setTextColor(Color.WHITE)
            background = GradientDrawable().apply { setColor(accentColor()); shape = GradientDrawable.OVAL }
        }
        top.addView(avatar, LinearLayout.LayoutParams(dp(54), dp(54)))
        val names = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(12), 0, 0, 0) }
        names.addView(TextView(this).apply { text = p.username; textSize = 18f; setTypeface(typeface, Typeface.BOLD); setTextColor(textPrimary()) })
        names.addView(TextView(this).apply { text = p.email; textSize = 11f; setTextColor(textMuted()); setPadding(0, dp(3), 0, 0) })
        top.addView(names, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        top.addView(statusChip(if (p.status == "active") "正常" else p.status, p.status == "active"))
        hero.addView(top)
        hero.addView(info("用户 #${p.id} · ${if (p.role == "admin") "管理员" else "用户"} · 注册 ${shortDate(p.createdAt)}").apply { setPadding(0, dp(12), 0, 0) })

        val metrics = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, dp(13), 0, 0) }
        metrics.addView(metric("余额", money(p.balance)), LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        metrics.addView(metric("并发", p.concurrency.toString()), LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        metrics.addView(metric("RPM 上限", if (p.rpmLimit <= 0) "不限" else p.rpmLimit.toString()), LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        hero.addView(metrics)
        val editRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        editRow.addView(smallButton("修改用户名") { openUsernameDialog(p) }, weighted(end = 5))
        editRow.addView(smallButton("刷新资料") { loadData() }, weighted(start = 5))
        hero.addView(editRow, matchWrap().apply { topMargin = dp(14) })
        profileBox.addView(hero)

        val notifyPanel = softPanel().apply { setPadding(dp(16), dp(15), dp(16), dp(15)) }
        notifyPanel.addView(TextView(this).apply { text = "站点余额邮件提醒"; textSize = 14f; setTypeface(typeface, Typeface.BOLD); setTextColor(textPrimary()) })
        notifyPanel.addView(info("这是站点自身的邮件提醒，与 LuMing 本地系统通知互不冲突。").apply { setPadding(0, dp(4), 0, dp(11)) })
        val notifyRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        notifyRow.addView(info(if (p.balanceNotifyEnabled) "已开启 · 阈值 ${p.balanceNotifyThreshold?.let(::money) ?: "跟随站点默认"}" else "已关闭"), LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        notifyRow.addView(smallButton(if (p.balanceNotifyEnabled) "设置" else "开启") { openServerNotifyDialog(p) }, LinearLayout.LayoutParams(dp(110), dp(44)))
        notifyPanel.addView(notifyRow)
        serverNotifyBox.addView(notifyPanel)

        val securityPanel = softPanel().apply { setPadding(dp(16), dp(15), dp(16), dp(15)) }
        securityPanel.addView(securityRow("密码", "修改站点登录密码", "修改") { authenticate("修改站点密码") { openPasswordDialog() } })
        securityPanel.addView(divider())
        val totpText = when (p.totpEnabled) {
            true -> "已启用 TOTP 双重验证"
            false -> "未启用 TOTP"
            null -> "当前站点暂未提供双重验证功能"
        }
        val totpAction = when (p.totpEnabled) {
            true -> "管理"
            false -> "启用"
            null -> "暂不支持"
        }
        securityPanel.addView(securityRow("双重验证", totpText, totpAction, enabled = p.totpEnabled != null) {
            authenticate("管理双重验证") { if (p.totpEnabled == true) beginTotpDisable() else beginTotpSetup() }
        })
        securityPanel.addView(divider())
        securityPanel.addView(securityRow("Passkey", if (p.passkeys.isEmpty()) "未登记 Passkey" else "${p.passkeys.size} 个 Passkey", "高级管理") {
            startActivity(Intent(this, AccountWebActivity::class.java))
        })
        if (p.bindings.isNotEmpty()) {
            securityPanel.addView(divider())
            val bound = p.bindings.filter { it.bound }
            securityPanel.addView(securityRow("登录绑定", if (bound.isEmpty()) "暂无第三方绑定" else bound.joinToString(" · ") { providerLabel(it.provider) }, "管理") {
                startActivity(Intent(this, AccountWebActivity::class.java))
            })
        }
        securityBox.addView(securityPanel)
    }

    private fun openUsernameDialog(p: AccountProfile) {
        val input = editField("用户名").apply { setText(p.username); selectAll() }
        AlertDialog.Builder(this).setTitle("修改用户名").setView(wrap(input)).setNegativeButton("取消", null)
            .setPositiveButton("保存") { _, _ ->
                val value = input.text.toString().trim()
                if (value.isNotBlank()) runOperation { AccountClient.updateUsernameBlocking(applicationContext, value) }
            }.show()
    }

    private fun openServerNotifyDialog(p: AccountProfile) {
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(18), dp(6), dp(18), 0) }
        val threshold = editField("阈值 USD，例如 0.50").apply {
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
            setText(p.balanceNotifyThreshold?.toString().orEmpty())
        }
        box.addView(info("当前：${if (p.balanceNotifyEnabled) "开启" else "关闭"}")); box.addView(threshold, matchHeight(52).apply { topMargin = dp(10) })
        AlertDialog.Builder(this).setTitle("站点余额邮件提醒").setView(box).setNeutralButton(if (p.balanceNotifyEnabled) "关闭提醒" else null) { _, _ ->
            runOperation { AccountClient.updateBalanceNotifyBlocking(applicationContext, false, p.balanceNotifyThreshold) }
        }.setNegativeButton("取消", null).setPositiveButton("开启并保存") { _, _ ->
            runOperation { AccountClient.updateBalanceNotifyBlocking(applicationContext, true, threshold.text.toString().trim().toDoubleOrNull()) }
        }.show()
    }

    private fun openPasswordDialog() {
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(18), dp(6), dp(18), 0) }
        val old = passwordField("当前密码")
        val next = passwordField("新密码（至少 8 位）")
        val confirm = passwordField("确认新密码")
        box.addView(old, matchHeight(52)); box.addView(next, matchHeight(52).apply { topMargin = dp(8) }); box.addView(confirm, matchHeight(52).apply { topMargin = dp(8) })
        val dialog = AlertDialog.Builder(this).setTitle("修改密码").setView(box).setNegativeButton("取消", null).setPositiveButton("修改", null).create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val n = next.text.toString(); val c = confirm.text.toString()
                when {
                    n.length < 8 -> next.error = "至少 8 位"
                    n != c -> confirm.error = "两次输入不一致"
                    old.text.isNullOrBlank() -> old.error = "请输入当前密码"
                    else -> {
                        dialog.dismiss(); runOperation { AccountClient.changePasswordBlocking(applicationContext, old.text.toString(), n) }
                    }
                }
            }
        }
        dialog.show()
    }

    private fun beginTotpSetup() {
        Thread {
            val methodResult = AccountClient.totpVerificationMethodBlocking(applicationContext)
            val method = methodResult.value?.optString("method", "password") ?: "password"
            runOnUiThread {
                if (!methodResult.success) { toast(methodResult.message); return@runOnUiThread }
                if (method == "email") showTotpProofDialog("email", sendCodeFirst = true) else showTotpProofDialog("password", sendCodeFirst = false)
            }
        }.start()
    }

    private fun showTotpProofDialog(method: String, sendCodeFirst: Boolean) {
        val input = if (method == "email") editField("邮箱验证码 6 位").apply { inputType = InputType.TYPE_CLASS_NUMBER }
        else passwordField("当前密码")
        if (sendCodeFirst) Thread {
            val r = AccountClient.sendTotpEmailCodeBlocking(applicationContext)
            runOnUiThread { toast(r.message) }
        }.start()
        AlertDialog.Builder(this).setTitle(if (method == "email") "验证邮箱" else "验证当前密码").setView(wrap(input)).setNegativeButton("取消", null)
            .setPositiveButton("下一步") { _, _ ->
                val proof = input.text.toString().trim()
                Thread {
                    val result = AccountClient.beginTotpSetupBlocking(applicationContext, method, proof)
                    runOnUiThread { if (!result.success) toast(result.message) else showTotpSecret(result) }
                }.start()
            }.show()
    }

    private fun showTotpSecret(result: TotpBeginResult) {
        val token = result.setupToken ?: return toast("站点没有返回 setup token")
        val secret = result.secret.orEmpty()
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(18), dp(4), dp(18), 0) }
        box.addView(info("把下面的密钥加入 Google Authenticator / Microsoft Authenticator 等验证器，然后输入生成的 6 位验证码。"))
        box.addView(TextView(this).apply {
            text = secret; textSize = 13f; setTypeface(Typeface.MONOSPACE, Typeface.BOLD); setTextColor(textPrimary()); setPadding(0, dp(12), 0, dp(8)); setTextIsSelectable(true)
        })
        val code = editField("验证器 6 位验证码").apply { inputType = InputType.TYPE_CLASS_NUMBER }
        box.addView(code, matchHeight(52))
        val dialog = AlertDialog.Builder(this).setTitle("配置双重验证").setView(box)
            .setNeutralButton("打开验证器") { _, _ -> result.qrCodeUrl?.let(::openAuthenticator) ?: copySensitive(secret) }
            .setNegativeButton("取消", null).setPositiveButton("启用", null).create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val value = code.text.toString().trim()
                if (value.length != 6) { code.error = "请输入 6 位验证码"; return@setOnClickListener }
                dialog.dismiss()
                runOperation { AccountClient.enableTotpBlocking(applicationContext, token, value) }
            }
        }
        dialog.show()
    }

    private fun beginTotpDisable() {
        Thread {
            val methodResult = AccountClient.totpVerificationMethodBlocking(applicationContext)
            val method = methodResult.value?.optString("method", "password") ?: "password"
            if (method == "email") AccountClient.sendTotpEmailCodeBlocking(applicationContext)
            runOnUiThread {
                if (!methodResult.success) { toast(methodResult.message); return@runOnUiThread }
                val input = if (method == "email") editField("邮箱验证码 6 位").apply { inputType = InputType.TYPE_CLASS_NUMBER } else passwordField("当前密码")
                AlertDialog.Builder(this).setTitle("关闭双重验证？").setMessage("关闭后账户安全性会降低。")
                    .setView(wrap(input)).setNegativeButton("取消", null).setPositiveButton("确认关闭") { _, _ ->
                        runOperation { AccountClient.disableTotpBlocking(applicationContext, method, input.text.toString().trim()) }
                    }.show()
            }
        }.start()
    }

    private fun changeTheme(mode: LuMingThemeMode) {
        LuMingTheme.setMode(this, mode)
        recreate()
    }

    private fun updateThemeButtons() {
        val mode = LuMingTheme.mode(this)
        styleTheme(themeSystem, mode == LuMingThemeMode.SYSTEM)
        styleTheme(themeLight, mode == LuMingThemeMode.LIGHT)
        styleTheme(themeDark, mode == LuMingThemeMode.DARK)
    }

    private fun styleTheme(button: Button, active: Boolean) {
        button.background = GradientDrawable().apply {
            setColor(if (active) LuMingTheme.activeBg(this@AccountActivity) else LuMingTheme.panelAlt(this@AccountActivity))
            cornerRadius = dp(15).toFloat()
            setStroke(dp(1), if (active) LuMingTheme.activeBorder(this@AccountActivity) else LuMingTheme.border(this@AccountActivity))
        }
        button.setTextColor(if (active) accentDark() else textSecondary())
    }

    private fun authenticate(reason: String, action: () -> Unit) {
        SystemAuthGate.authenticate(this, reason, action) { toast(it) }
    }

    private fun runOperation(block: () -> AccountOperationResult) {
        statusPill.text = "处理中…"
        Thread {
            val result = block()
            runOnUiThread {
                toast(result.message)
                if (result.success) loadData() else statusPill.text = "操作失败"
            }
        }.start()
    }

    private fun openAuthenticator(uri: String) {
        runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(uri))) }
            .onFailure { toast("没有找到可处理验证器链接的应用，可手动复制密钥") }
    }

    private fun copySensitive(value: String) {
        val clipboard = getSystemService(ClipboardManager::class.java)
        val clip = ClipData.newPlainText("TOTP Secret", value)
        if (Build.VERSION.SDK_INT >= 33) {
            clip.description.extras = PersistableBundle().apply { putBoolean(ClipDescription.EXTRA_IS_SENSITIVE, true) }
        }
        clipboard.setPrimaryClip(clip)
        Handler(Looper.getMainLooper()).postDelayed({
            val current = clipboard.primaryClip?.getItemAt(0)?.coerceToText(this)?.toString()
            if (current == value) clipboard.clearPrimaryClip()
        }, 45_000L)
        toast("密钥已复制，45 秒后清理剪贴板")
    }

    private fun securityRow(title: String, subtitle: String, action: String, enabled: Boolean = true, onClick: () -> Unit): View {
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(0, dp(8), 0, dp(8)) }
        val text = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        text.addView(TextView(this).apply { this.text = title; textSize = 13f; setTypeface(typeface, Typeface.BOLD); setTextColor(textPrimary()) })
        text.addView(info(subtitle).apply { setPadding(0, dp(3), 0, 0) })
        row.addView(text, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        row.addView(smallButton(action, onClick).apply {
            isEnabled = enabled
            alpha = if (enabled) 1f else 0.52f
        }, LinearLayout.LayoutParams(dp(105), dp(42)))
        return row
    }

    private fun metric(label: String, value: String): View = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL
        addView(TextView(this@AccountActivity).apply { text = value; textSize = 15f; setTypeface(typeface, Typeface.BOLD); setTextColor(textPrimary()) })
        addView(TextView(this@AccountActivity).apply { text = label; textSize = 9.5f; setTextColor(textMuted()); setPadding(0, dp(3), 0, 0) })
    }

    private fun statusChip(textValue: String, good: Boolean) = TextView(this).apply {
        text = textValue
        textSize = 10f
        gravity = Gravity.CENTER
        setTextColor(if (good) accentDark() else LuMingTheme.dangerText(this@AccountActivity))
        setPadding(dp(10), dp(5), dp(10), dp(5))
        background = pillBackground(if (good) LuMingTheme.positivePill(this@AccountActivity) else LuMingTheme.dangerPill(this@AccountActivity))
    }

    private fun divider() = View(this).apply {
        setBackgroundColor(LuMingTheme.divider(this@AccountActivity))
    }.also { it.layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(1)) }
    private fun info(value: String) = TextView(this).apply { text = value; textSize = 10.8f; setTextColor(textMuted()) }
    private fun sectionTitle(value: String) = TextView(this).apply { text = value; textSize = 13f; setTypeface(typeface, Typeface.BOLD); setTextColor(textSecondary()); setPadding(dp(3), dp(22), dp(3), dp(9)) }

    private fun actionButton(label: String, onClick: () -> Unit) = Button(this).apply {
        text = label; isAllCaps = false; textSize = 12.5f; setTypeface(typeface, Typeface.BOLD); setTextColor(textSecondary());
        background = buttonBackground(false); elevation = dp(4).toFloat(); stateListAnimator = null; minHeight = 0; minimumHeight = 0; setOnClickListener { onClick() }
    }
    private fun smallButton(label: String, onClick: () -> Unit) = actionButton(label, onClick).apply { textSize = 11.5f }
    private fun editField(hintValue: String) = EditText(this).apply {
        hint = hintValue; textSize = 13f; setSingleLine(true); setPadding(dp(14), 0, dp(14), 0); setTextColor(textPrimary()); setHintTextColor(LuMingTheme.hint(this@AccountActivity)); background = inputBackground()
    }
    private fun passwordField(hintValue: String) = editField(hintValue).apply { inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD }
    private fun wrap(view: View) = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(18), dp(6), dp(18), 0); addView(view, matchHeight(52)) }
    private fun softPanel() = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; background = panelBackground(); elevation = dp(7).toFloat() }
    private fun panelBackground() = GradientDrawable().apply { setColor(panelColor()); cornerRadius = dp(24).toFloat(); setStroke(dp(1), LuMingTheme.border(this@AccountActivity)) }
    private fun buttonBackground(active: Boolean) = GradientDrawable().apply { setColor(if (active) LuMingTheme.activeBg(this@AccountActivity) else LuMingTheme.panelAlt(this@AccountActivity)); cornerRadius = dp(17).toFloat(); setStroke(dp(1), if (active) LuMingTheme.activeBorder(this@AccountActivity) else LuMingTheme.border(this@AccountActivity)) }
    private fun inputBackground() = GradientDrawable().apply { setColor(LuMingTheme.input(this@AccountActivity)); cornerRadius = dp(17).toFloat(); setStroke(dp(1), LuMingTheme.border(this@AccountActivity)) }
    private fun pillBackground(color: Int) = GradientDrawable().apply { setColor(color); cornerRadius = dp(999).toFloat() }
    private fun weighted(start: Int = 0, end: Int = 0) = LinearLayout.LayoutParams(0, dp(44), 1f).apply { leftMargin = dp(start); rightMargin = dp(end) }
    private fun matchWrap() = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
    private fun matchHeight(height: Int) = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(height))

    private fun providerLabel(value: String) = when (value) {
        "github" -> "GitHub"; "google" -> "Google"; "linuxdo" -> "LinuxDo"; "wechat" -> "微信"; "dingtalk" -> "钉钉"; "oidc" -> "OIDC"; "email" -> "邮箱"; else -> value
    }
    private fun money(value: Double) = String.format(Locale.US, "$%.4f", value).trimEnd('0').trimEnd('.')
    private fun shortDate(value: String?): String = value?.take(10) ?: "--"
    private fun toast(value: String) = Toast.makeText(this, value, Toast.LENGTH_SHORT).show()
    private fun bgColor() = LuMingTheme.bg(this)
    private fun panelColor() = LuMingTheme.panel(this)
    private fun accentColor() = LuMingTheme.accent(this)
    private fun accentDark() = LuMingTheme.accentDark(this)
    private fun textPrimary() = LuMingTheme.textPrimary(this)
    private fun textSecondary() = LuMingTheme.textSecondary(this)
    private fun textMuted() = LuMingTheme.textMuted(this)
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
}
