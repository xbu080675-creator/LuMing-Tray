package com.luming.tray

import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
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
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import androidx.fragment.app.FragmentActivity
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

class ApiKeyManagementActivity : FragmentActivity() {
    private lateinit var content: LinearLayout
    private lateinit var statusPill: TextView
    private lateinit var summaryText: TextView
    private lateinit var endpointText: TextView
    private lateinit var refreshButton: Button
    private lateinit var createButton: Button

    private var report: ApiKeyManagerReport? = null
    private var loading = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = bgColor()
        window.navigationBarColor = bgColor()
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
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
            setPadding(dp(18), dp(16), dp(18), dp(36))
            setOnApplyWindowInsetsListener { view, insets ->
                val top = if (Build.VERSION.SDK_INT >= 30) {
                    insets.getInsets(WindowInsets.Type.statusBars()).top
                } else {
                    @Suppress("DEPRECATION") insets.systemWindowInsetTop
                }
                val bottom = if (Build.VERSION.SDK_INT >= 30) {
                    insets.getInsets(WindowInsets.Type.navigationBars()).bottom
                } else {
                    @Suppress("DEPRECATION") insets.systemWindowInsetBottom
                }
                view.setPadding(dp(18), dp(12) + top, dp(18), dp(24) + bottom)
                insets
            }
        }

        root.addView(buildHeader())
        root.addView(buildEndpointPanel(), matchWrap().apply { topMargin = dp(14) })

        summaryText = TextView(this).apply {
            text = "正在读取 API Key…"
            textSize = 12f
            setTextColor(textSecondary())
            setPadding(dp(4), dp(14), dp(4), dp(8))
        }
        root.addView(summaryText)

        val actions = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        refreshButton = actionButton("刷新") { loadData() }
        createButton = actionButton("创建 API Key") { openCreateDialog() }
        actions.addView(refreshButton, weighted(end = 6))
        actions.addView(createButton, weighted(start = 6))
        root.addView(actions)

        root.addView(TextView(this).apply {
            text = "Key 原文默认不回显。复制、创建、修改、启停、重置与删除都需要系统身份验证。"
            textSize = 10.5f
            setTextColor(textMuted())
            setPadding(dp(4), dp(10), dp(4), dp(12))
        })

        content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(content, matchWrap())

        root.addView(TextView(this).apply {
            text = "LuMing Tray 0.14.0 · Native API Management"
            textSize = 10.5f
            gravity = Gravity.CENTER
            setTextColor(textMuted())
            setPadding(0, dp(24), 0, 0)
        })

        scroll.addView(root)
        return scroll
    }

    private fun buildHeader(): View {
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        header.addView(TextView(this).apply {
            text = "‹"
            textSize = 34f
            gravity = Gravity.CENTER
            setTextColor(textPrimary())
            setOnClickListener { finish() }
        }, LinearLayout.LayoutParams(dp(44), dp(48)))

        val titleBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        titleBox.addView(TextView(this).apply {
            text = "API 管理"
            textSize = 25f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(textPrimary())
        })
        titleBox.addView(TextView(this).apply {
            text = "KEYS · GROUPS · LIMITS"
            textSize = 10f
            letterSpacing = 0.12f
            setTextColor(textMuted())
        })
        header.addView(titleBox, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))

        statusPill = TextView(this).apply {
            text = "读取中…"
            textSize = 10f
            gravity = Gravity.CENTER
            setTextColor(accentDark())
            setPadding(dp(10), dp(6), dp(10), dp(6))
            background = pillBackground(Color.rgb(222, 243, 237))
        }
        header.addView(statusPill)
        return header
    }

    private fun buildEndpointPanel(): View {
        val panel = softPanel().apply { setPadding(dp(16), dp(14), dp(16), dp(14)) }
        panel.addView(TextView(this).apply {
            text = "API ENDPOINT"
            textSize = 9.5f
            letterSpacing = 0.09f
            setTextColor(textMuted())
        })
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        endpointText = TextView(this).apply {
            text = TrayStore.loadConfig(this@ApiKeyManagementActivity).baseUrl
            textSize = 13f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(textPrimary())
            setPadding(0, dp(7), dp(8), 0)
        }
        row.addView(endpointText, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        row.addView(actionButton("复制地址") {
            copyPlain(endpointText.text.toString(), "API Endpoint")
            toastStatus("API Endpoint 已复制")
        }, LinearLayout.LayoutParams(dp(100), dp(42)))
        panel.addView(row)
        return panel
    }

    private fun loadData() {
        if (loading) return
        loading = true
        refreshButton.isEnabled = false
        createButton.isEnabled = false
        statusPill.text = "同步中…"
        Thread {
            val result = ApiKeyManagerClient.loadBlocking(applicationContext)
            runOnUiThread {
                report = result
                loading = false
                refreshButton.isEnabled = true
                createButton.isEnabled = TrayStore.loadConfig(this).webAuthToken.isNotBlank()
                endpointText.text = result.endpoint
                statusPill.text = if (TrayStore.loadConfig(this).webAuthToken.isNotBlank()) "已授权" else "未授权"
                render(result)
            }
        }.start()
    }

    private fun render(current: ApiKeyManagerReport) {
        content.removeAllViews()
        val today = current.usage.values.sumOf { it.todayActualCost }
        val total = current.usage.values.sumOf { it.totalActualCost }
        summaryText.text = "${current.message} · 今日 ${money(today)} · 累计 ${money(total)}"

        if (current.keys.isEmpty()) {
            content.addView(emptyPanel(
                if (TrayStore.loadConfig(this).webAuthToken.isBlank()) {
                    "请先在主界面完成网页登录授权。"
                } else {
                    "当前账号还没有 API Key。\n点上方「创建 API Key」即可新建。"
                }
            ))
            return
        }

        current.keys.forEachIndexed { index, key ->
            content.addView(keyCard(key, current), matchWrap().apply {
                if (index > 0) topMargin = dp(12)
            })
        }
    }

    private fun keyCard(key: ManagedApiKey, current: ApiKeyManagerReport): View {
        val card = softPanel().apply { setPadding(dp(16), dp(15), dp(16), dp(15)) }
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val nameBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        nameBox.addView(TextView(this).apply {
            text = key.name
            textSize = 16f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(textPrimary())
        })
        nameBox.addView(TextView(this).apply {
            text = "🔒 Key 原文已隐藏 · #${key.id}"
            textSize = 10.5f
            setTextColor(textMuted())
            setPadding(0, dp(3), 0, 0)
        })
        header.addView(nameBox, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))

        val active = key.status.equals("active", ignoreCase = true)
        header.addView(TextView(this).apply {
            text = if (active) "启用" else "停用"
            textSize = 10.5f
            gravity = Gravity.CENTER
            setTextColor(if (active) accentDark() else Color.rgb(158, 90, 85))
            background = pillBackground(if (active) Color.rgb(222, 243, 237) else Color.rgb(247, 231, 229))
            setPadding(dp(10), dp(5), dp(10), dp(5))
        })
        card.addView(header)

        card.addView(infoText(buildString {
            append(key.groupName ?: "未绑定分组")
            key.groupPlatform?.let { append(" · $it") }
            key.groupRateMultiplier?.let { append(" · ${rate(it)}") }
        }).apply { setPadding(0, dp(10), 0, 0) })

        val usage = current.usage[key.id] ?: ApiKeyUsageSummary()
        val metrics = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(11), 0, 0)
        }
        metrics.addView(metric("今日", money(usage.todayActualCost)), LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        metrics.addView(metric("累计", money(usage.totalActualCost)), LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        metrics.addView(metric("并发", key.currentConcurrency.toString()), LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        card.addView(metrics)

        val quotaText = if (key.quota > 0.0) {
            "配额 ${money(key.quotaUsed)} / ${money(key.quota)}"
        } else {
            "配额：不限"
        }
        card.addView(infoText(quotaText).apply { setPadding(0, dp(10), 0, 0) })

        val limits = mutableListOf<String>()
        if (key.rateLimit5h > 0) limits += "5h ${money(key.usage5h)}/${money(key.rateLimit5h)}"
        if (key.rateLimit1d > 0) limits += "1d ${money(key.usage1d)}/${money(key.rateLimit1d)}"
        if (key.rateLimit7d > 0) limits += "7d ${money(key.usage7d)}/${money(key.rateLimit7d)}"
        if (limits.isNotEmpty()) {
            card.addView(infoText("窗口 · ${limits.joinToString(" · ")}").apply { setPadding(0, dp(5), 0, 0) })
        }

        val times = mutableListOf<String>()
        key.lastUsedAt?.let { times += "最后使用 ${shortDateTime(it)}" }
        key.expiresAt?.let { times += "到期 ${shortDateTime(it)}" }
        if (times.isNotEmpty()) card.addView(infoText(times.joinToString(" · ")).apply { setPadding(0, dp(5), 0, 0) })
        if (key.ipWhitelist.isNotEmpty() || key.ipBlacklist.isNotEmpty()) {
            card.addView(infoText("IP 限制 · 白 ${key.ipWhitelist.size} / 黑 ${key.ipBlacklist.size}").apply { setPadding(0, dp(5), 0, 0) })
        }

        val row1 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        row1.addView(smallButton("复制 Key") { copyKey(key) }, weighted(end = 5))
        row1.addView(smallButton("编辑") { editKey(key, current.groups) }, weighted(start = 5))
        card.addView(row1, matchWrap().apply { topMargin = dp(13) })

        val row2 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        row2.addView(smallButton(if (active) "停用" else "启用") { toggleKey(key, !active) }, weighted(end = 5))
        row2.addView(smallButton("更多") { openMoreMenu(key) }, weighted(start = 5))
        card.addView(row2, matchWrap().apply { topMargin = dp(8) })
        return card
    }

    private fun copyKey(key: ManagedApiKey) {
        authenticate("复制「${key.name}」的 API Key") {
            runOperation(
                "正在安全读取 Key…",
                { ApiKeyManagerClient.fetchSecretBlocking(applicationContext, key.id) }
            ) { result ->
                val secret = result.secret
                if (!result.success || secret.isNullOrBlank()) {
                    toastStatus(result.message)
                } else {
                    copySensitive(secret)
                    toastStatus("API Key 已复制，45 秒后自动清理剪贴板")
                }
            }
        }
    }

    private fun toggleKey(key: ManagedApiKey, active: Boolean) {
        authenticate(if (active) "启用「${key.name}」" else "停用「${key.name}」") {
            runOperation(
                if (active) "正在启用…" else "正在停用…",
                { ApiKeyManagerClient.toggleBlocking(applicationContext, key.id, active) }
            ) { result ->
                toastStatus(result.message)
                if (result.success) loadData()
            }
        }
    }

    private fun openMoreMenu(key: ManagedApiKey) {
        AlertDialog.Builder(this)
            .setTitle(key.name)
            .setItems(arrayOf("重置 Key 用量计数", "删除 API Key")) { _, which ->
                if (which == 0) confirmReset(key) else confirmDelete(key)
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun confirmReset(key: ManagedApiKey) {
        AlertDialog.Builder(this)
            .setTitle("重置用量？")
            .setMessage("会重置这把 Key 的配额和限速窗口用量计数，不会删除 Key。")
            .setNegativeButton("取消", null)
            .setPositiveButton("继续") { _, _ ->
                authenticate("重置「${key.name}」的用量计数") {
                    runOperation(
                        "正在重置…",
                        { ApiKeyManagerClient.resetQuotaBlocking(applicationContext, key.id) }
                    ) { result ->
                        toastStatus(result.message)
                        if (result.success) loadData()
                    }
                }
            }
            .show()
    }

    private fun confirmDelete(key: ManagedApiKey) {
        AlertDialog.Builder(this)
            .setTitle("删除 API Key？")
            .setMessage("「${key.name}」删除后无法恢复，使用它的客户端会立即失效。")
            .setNegativeButton("取消", null)
            .setPositiveButton("删除") { _, _ ->
                authenticate("删除「${key.name}」") {
                    runOperation(
                        "正在删除…",
                        { ApiKeyManagerClient.deleteBlocking(applicationContext, key.id) }
                    ) { result ->
                        toastStatus(result.message)
                        if (result.success) loadData()
                    }
                }
            }
            .show()
    }

    private fun openCreateDialog() {
        val current = report ?: return
        if (TrayStore.loadConfig(this).webAuthToken.isBlank()) {
            toastStatus("请先完成网页登录授权")
            return
        }
        authenticate("创建新的 API Key") { showCreateForm(current.groups) }
    }

    private fun showCreateForm(groups: List<ApiKeyGroup>) {
        val form = formContainer()
        val name = formField("例如 SillyTavern")
        val group = groupSpinner(groups, null)
        val quota = numberField("总配额 USD，0 = 不限")
        val expires = numberField("有效天数，留空 = 永久")
        val limit5h = numberField("5 小时额度 USD，0 = 不限")
        val limit1d = numberField("1 天额度 USD，0 = 不限")
        val limit7d = numberField("7 天额度 USD，0 = 不限")
        val whitelist = multiField("IP 白名单，可空；逗号或换行分隔")
        val blacklist = multiField("IP 黑名单，可空；逗号或换行分隔")

        form.addView(fieldLabel("名称")); form.addView(name, matchHeight(52))
        form.addView(fieldLabel("分组")); form.addView(group, matchHeight(52))
        form.addView(fieldLabel("配额与有效期")); form.addView(quota, matchHeight(52)); form.addView(expires, matchHeight(52).apply { topMargin = dp(7) })
        form.addView(fieldLabel("消费窗口限制")); form.addView(limit5h, matchHeight(52)); form.addView(limit1d, matchHeight(52).apply { topMargin = dp(7) }); form.addView(limit7d, matchHeight(52).apply { topMargin = dp(7) })
        form.addView(fieldLabel("IP 限制")); form.addView(whitelist, matchHeight(72)); form.addView(blacklist, matchHeight(72).apply { topMargin = dp(7) })

        val dialog = AlertDialog.Builder(this)
            .setTitle("创建 API Key")
            .setView(wrapForm(form))
            .setNegativeButton("取消", null)
            .setPositiveButton("创建", null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val keyName = name.text.toString().trim()
                if (keyName.isBlank()) {
                    name.error = "请输入名称"
                    return@setOnClickListener
                }
                val input = ApiKeyCreateInput(
                    name = keyName,
                    groupId = selectedGroup(group, groups)?.id,
                    quota = nonNegative(quota),
                    expiresInDays = expires.text.toString().trim().toIntOrNull()?.takeIf { it > 0 },
                    rateLimit5h = nonNegative(limit5h),
                    rateLimit1d = nonNegative(limit1d),
                    rateLimit7d = nonNegative(limit7d),
                    ipWhitelist = parseIps(whitelist.text.toString()),
                    ipBlacklist = parseIps(blacklist.text.toString())
                )
                dialog.dismiss()
                runOperation(
                    "正在创建 API Key…",
                    { ApiKeyManagerClient.createBlocking(applicationContext, input) }
                ) { result ->
                    if (!result.success) {
                        toastStatus(result.message)
                    } else {
                        result.secret?.takeIf { it.isNotBlank() }?.let(::showCreatedKeyDialog)
                            ?: toastStatus("API Key 已创建，但站点没有返回 Key 原文")
                        loadData()
                    }
                }
            }
        }
        dialog.show()
    }

    private fun showCreatedKeyDialog(secret: String) {
        AlertDialog.Builder(this)
            .setTitle("API Key 已创建")
            .setMessage("LuMing 不会在列表回显 Key 原文，也不会把这把站点 Key 另存到本机配置。现在可以安全复制一次。")
            .setNegativeButton("关闭", null)
            .setPositiveButton("复制新 Key") { _, _ ->
                copySensitive(secret)
                toastStatus("新 API Key 已复制，45 秒后自动清理剪贴板")
            }
            .show()
    }

    private fun editKey(key: ManagedApiKey, groups: List<ApiKeyGroup>) {
        authenticate("修改「${key.name}」的 API 设置") { showEditForm(key, groups) }
    }

    private fun showEditForm(key: ManagedApiKey, groups: List<ApiKeyGroup>) {
        val form = formContainer()
        val name = formField("名称").apply { setText(key.name) }
        val group = groupSpinner(groups, key.groupId)
        val quota = numberField("总配额 USD，0 = 不限").apply { setText(trimNumber(key.quota)) }
        val expires = formField("到期日 YYYY-MM-DD；留空 = 永久").apply { setText(key.expiresAt?.take(10).orEmpty()) }
        val limit5h = numberField("5 小时额度 USD，0 = 不限").apply { setText(trimNumber(key.rateLimit5h)) }
        val limit1d = numberField("1 天额度 USD，0 = 不限").apply { setText(trimNumber(key.rateLimit1d)) }
        val limit7d = numberField("7 天额度 USD，0 = 不限").apply { setText(trimNumber(key.rateLimit7d)) }
        val whitelist = multiField("IP 白名单").apply { setText(key.ipWhitelist.joinToString("\n")) }
        val blacklist = multiField("IP 黑名单").apply { setText(key.ipBlacklist.joinToString("\n")) }

        form.addView(fieldLabel("名称")); form.addView(name, matchHeight(52))
        form.addView(fieldLabel("分组")); form.addView(group, matchHeight(52))
        form.addView(fieldLabel("配额与到期")); form.addView(quota, matchHeight(52)); form.addView(expires, matchHeight(52).apply { topMargin = dp(7) })
        form.addView(fieldLabel("消费窗口限制")); form.addView(limit5h, matchHeight(52)); form.addView(limit1d, matchHeight(52).apply { topMargin = dp(7) }); form.addView(limit7d, matchHeight(52).apply { topMargin = dp(7) })
        form.addView(fieldLabel("IP 限制")); form.addView(whitelist, matchHeight(72)); form.addView(blacklist, matchHeight(72).apply { topMargin = dp(7) })

        val dialog = AlertDialog.Builder(this)
            .setTitle("编辑 API Key")
            .setView(wrapForm(form))
            .setNegativeButton("取消", null)
            .setPositiveButton("保存", null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val keyName = name.text.toString().trim()
                if (keyName.isBlank()) {
                    name.error = "请输入名称"
                    return@setOnClickListener
                }
                val rawExpiry = expires.text.toString().trim()
                val expiry = if (rawExpiry.isBlank()) "" else toRfc3339(rawExpiry)
                if (rawExpiry.isNotBlank() && expiry == null) {
                    expires.error = "格式应为 YYYY-MM-DD"
                    return@setOnClickListener
                }
                val input = ApiKeyUpdateInput(
                    name = keyName,
                    groupId = selectedGroup(group, groups)?.id,
                    quota = nonNegative(quota),
                    expiresAt = expiry,
                    rateLimit5h = nonNegative(limit5h),
                    rateLimit1d = nonNegative(limit1d),
                    rateLimit7d = nonNegative(limit7d),
                    ipWhitelist = parseIps(whitelist.text.toString()),
                    ipBlacklist = parseIps(blacklist.text.toString())
                )
                dialog.dismiss()
                runOperation(
                    "正在保存设置…",
                    { ApiKeyManagerClient.updateBlocking(applicationContext, key.id, input) }
                ) { result ->
                    toastStatus(result.message)
                    if (result.success) loadData()
                }
            }
        }
        dialog.show()
    }

    private fun authenticate(reason: String, onSuccess: () -> Unit) {
        SystemAuthGate.authenticate(
            activity = this,
            reason = reason,
            onSuccess = onSuccess,
            onFailure = { message -> toastStatus("身份验证未完成：$message") }
        )
    }

    private fun runOperation(
        message: String,
        block: () -> ApiKeyOperationResult,
        onDone: (ApiKeyOperationResult) -> Unit
    ) {
        statusPill.text = message
        Thread {
            val result = block()
            runOnUiThread {
                statusPill.text = if (TrayStore.loadConfig(this).webAuthToken.isNotBlank()) "已授权" else "未授权"
                onDone(result)
            }
        }.start()
    }

    private fun groupSpinner(groups: List<ApiKeyGroup>, selectedId: Long?): Spinner {
        val spinner = Spinner(this)
        val labels = mutableListOf("不指定分组（自动路由）")
        labels += groups.map { group ->
            buildString {
                append(group.name)
                append(" · ${group.platform} · ${rate(group.effectiveRateMultiplier)}")
                if (group.exclusive) append(" · 专属")
            }
        }
        spinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, labels)
        val index = groups.indexOfFirst { it.id == selectedId }
        spinner.setSelection(if (index >= 0) index + 1 else 0)
        spinner.background = inputBackground()
        spinner.setPadding(dp(12), 0, dp(12), 0)
        return spinner
    }

    private fun selectedGroup(spinner: Spinner, groups: List<ApiKeyGroup>): ApiKeyGroup? =
        groups.getOrNull(spinner.selectedItemPosition - 1)

    private fun copySensitive(value: String) {
        val clipboard = getSystemService(ClipboardManager::class.java)
        val clip = ClipData.newPlainText("LuMing API Key", value)
        if (Build.VERSION.SDK_INT >= 33) {
            clip.description.extras = PersistableBundle().apply {
                putBoolean(ClipDescription.EXTRA_IS_SENSITIVE, true)
            }
        }
        clipboard.setPrimaryClip(clip)
        Handler(Looper.getMainLooper()).postDelayed({
            val current = runCatching {
                clipboard.primaryClip?.getItemAt(0)?.coerceToText(this)?.toString()
            }.getOrNull()
            if (current == value) {
                if (Build.VERSION.SDK_INT >= 28) clipboard.clearPrimaryClip()
                else clipboard.setPrimaryClip(ClipData.newPlainText("", ""))
            }
        }, 45_000L)
    }

    private fun copyPlain(value: String, label: String) {
        getSystemService(ClipboardManager::class.java)
            .setPrimaryClip(ClipData.newPlainText(label, value))
    }

    private fun formContainer() = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(4), dp(4), dp(4), dp(12))
    }

    private fun wrapForm(form: View) = ScrollView(this).apply {
        setPadding(dp(16), 0, dp(16), 0)
        addView(form)
    }

    private fun formField(hintText: String) = EditText(this).apply {
        hint = hintText
        textSize = 13f
        setTextColor(textPrimary())
        setHintTextColor(textMuted())
        setSingleLine(true)
        setPadding(dp(13), 0, dp(13), 0)
        background = inputBackground()
    }

    private fun numberField(hintText: String) = formField(hintText).apply {
        inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
    }

    private fun multiField(hintText: String) = EditText(this).apply {
        hint = hintText
        textSize = 12.5f
        setTextColor(textPrimary())
        setHintTextColor(textMuted())
        gravity = Gravity.TOP
        setPadding(dp(13), dp(10), dp(13), dp(10))
        background = inputBackground()
    }

    private fun fieldLabel(value: String) = TextView(this).apply {
        text = value
        textSize = 11f
        setTypeface(typeface, Typeface.BOLD)
        setTextColor(textMuted())
        setPadding(dp(2), dp(14), 0, dp(6))
    }

    private fun nonNegative(field: EditText): Double =
        field.text.toString().trim().toDoubleOrNull()?.coerceAtLeast(0.0) ?: 0.0

    private fun parseIps(raw: String): List<String> = raw
        .split(',', '\n', ';')
        .map { it.trim() }
        .filter { it.isNotBlank() }
        .distinct()

    private fun toRfc3339(date: String): String? = runCatching {
        LocalDate.parse(date, DateTimeFormatter.ISO_LOCAL_DATE)
            .atTime(23, 59, 59)
            .atZone(ZoneId.systemDefault())
            .toOffsetDateTime()
            .format(DateTimeFormatter.ISO_OFFSET_DATE_TIME)
    }.getOrNull()

    private fun toastStatus(message: String) {
        summaryText.text = message
    }

    private fun metric(label: String, value: String): View = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        addView(TextView(this@ApiKeyManagementActivity).apply {
            text = label
            textSize = 9.5f
            setTextColor(textMuted())
        })
        addView(TextView(this@ApiKeyManagementActivity).apply {
            text = value
            textSize = 15f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(textPrimary())
            setPadding(0, dp(4), 0, 0)
        })
    }

    private fun infoText(value: String) = TextView(this).apply {
        text = value
        textSize = 11f
        setTextColor(textSecondary())
    }

    private fun emptyPanel(message: String) = TextView(this).apply {
        text = message
        textSize = 12f
        setTextColor(textSecondary())
        gravity = Gravity.CENTER
        setPadding(dp(18), dp(28), dp(18), dp(28))
        background = panelBackground()
    }

    private fun actionButton(label: String, onClick: () -> Unit) = Button(this).apply {
        text = label
        isAllCaps = false
        textSize = 12f
        setTypeface(typeface, Typeface.BOLD)
        setTextColor(textSecondary())
        minHeight = 0
        minimumHeight = 0
        background = buttonBackground()
        stateListAnimator = null
        setOnClickListener { onClick() }
    }

    private fun smallButton(label: String, onClick: () -> Unit) = actionButton(label, onClick).apply {
        textSize = 11.5f
        minimumHeight = dp(44)
    }

    private fun softPanel() = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        background = panelBackground()
        elevation = dp(7).toFloat()
    }

    private fun panelBackground() = GradientDrawable().apply {
        setColor(panelColor())
        cornerRadius = dp(24).toFloat()
        setStroke(dp(1), Color.argb(210, 255, 255, 255))
    }

    private fun buttonBackground() = GradientDrawable().apply {
        setColor(Color.rgb(239, 244, 246))
        cornerRadius = dp(16).toFloat()
        setStroke(dp(1), Color.rgb(221, 229, 233))
    }

    private fun inputBackground() = GradientDrawable().apply {
        setColor(Color.rgb(235, 241, 244))
        cornerRadius = dp(15).toFloat()
        setStroke(dp(1), Color.rgb(218, 227, 231))
    }

    private fun pillBackground(color: Int) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = dp(999).toFloat()
    }

    private fun weighted(start: Int = 0, end: Int = 0) = LinearLayout.LayoutParams(0, dp(50), 1f).apply {
        leftMargin = dp(start)
        rightMargin = dp(end)
    }

    private fun matchWrap() = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
    private fun matchHeight(height: Int) = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(height))
    private fun bgColor() = Color.rgb(232, 239, 242)
    private fun panelColor() = Color.rgb(242, 247, 249)
    private fun accentDark() = Color.rgb(21, 125, 106)
    private fun textPrimary() = Color.rgb(37, 47, 58)
    private fun textSecondary() = Color.rgb(75, 88, 101)
    private fun textMuted() = Color.rgb(118, 131, 143)
    private fun money(value: Double) = String.format(Locale.US, "$%.4f", value).trimEnd('0').trimEnd('.')
    private fun rate(value: Double) = String.format(Locale.US, "%.3f", value).trimEnd('0').trimEnd('.') + "x"
    private fun trimNumber(value: Double) = if (value == 0.0) "0" else String.format(Locale.US, "%.4f", value).trimEnd('0').trimEnd('.')
    private fun shortDateTime(value: String) = value.replace('T', ' ').take(16)
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
}