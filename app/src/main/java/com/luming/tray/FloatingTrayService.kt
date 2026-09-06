package com.luming.tray

import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.math.roundToInt

class FloatingTrayService : Service() {
    private lateinit var windowManager: WindowManager
    private var rootView: View? = null
    private var params: WindowManager.LayoutParams? = null
    private var balanceText: TextView? = null
    private var detailText: TextView? = null
    private val handler = Handler(Looper.getMainLooper())
    private var gestureActive = false
    private var lastRenderedSignature: String? = null
    private var lastWindowUpdateAt = 0L
    private var pendingWindowUpdate: Runnable? = null

    private val refreshRunnable = object : Runnable {
        override fun run() {
            if (!gestureActive) renderStats()
            handler.postDelayed(this, 1_000L)
        }
    }

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val config = TrayStore.loadConfig(this)
        if (!config.floatingEnabled || !Settings.canDrawOverlays(this)) {
            stopSelf()
            return START_NOT_STICKY
        }

        if (rootView == null) {
            showOverlay(config)
        }
        renderStats(force = true)
        return START_STICKY
    }

    override fun onDestroy() {
        handler.removeCallbacks(refreshRunnable)
        cancelPendingWindowUpdate()
        rootView?.let {
            try {
                windowManager.removeView(it)
            } catch (_: Exception) {
            }
        }
        rootView = null
        params = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun showOverlay(config: TrayConfig) {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(8), dp(10), dp(7))
            background = GradientDrawable().apply {
                setColor(Color.argb(242, 255, 255, 255))
                cornerRadius = dp(16).toFloat()
                setStroke(dp(1), Color.argb(170, 205, 214, 220))
            }
            elevation = dp(8).toFloat()
            setLayerType(View.LAYER_TYPE_HARDWARE, null)
        }

        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        val title = TextView(this).apply {
            text = "LuMing"
            textSize = 13f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(Color.rgb(35, 43, 55))
            setPadding(0, 0, dp(8), dp(2))
        }
        header.addView(
            title,
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        )

        val close = TextView(this).apply {
            text = "×"
            textSize = 18f
            gravity = Gravity.CENTER
            setTextColor(Color.rgb(100, 108, 118))
            setPadding(dp(7), 0, dp(2), 0)
            setOnClickListener {
                val old = TrayStore.loadConfig(this@FloatingTrayService)
                TrayStore.saveConfig(this@FloatingTrayService, old.copy(floatingEnabled = false))
                stopSelf()
            }
        }
        header.addView(close)
        card.addView(header)

        balanceText = TextView(this).apply {
            textSize = 21f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(Color.rgb(18, 155, 120))
            setPadding(0, dp(1), 0, 0)
            setOnClickListener { openMain() }
        }
        card.addView(balanceText)

        detailText = TextView(this).apply {
            textSize = 11.5f
            setTextColor(Color.rgb(72, 82, 94))
            setPadding(0, dp(2), 0, 0)
            setOnClickListener { openMain() }
        }
        card.addView(detailText)

        val footer = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        footer.addView(TextView(this).apply {
            text = "拖标题移动"
            textSize = 9.5f
            setTextColor(Color.rgb(130, 138, 148))
        }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))

        val resize = TextView(this).apply {
            text = "↘"
            textSize = 18f
            gravity = Gravity.CENTER
            setTextColor(Color.rgb(72, 82, 94))
            setPadding(dp(8), 0, 0, 0)
        }
        footer.addView(resize)
        card.addView(footer)

        val width = dp(config.floatingWidthDp.coerceIn(MIN_WIDTH_DP, MAX_WIDTH_DP))
        val height = dp(config.floatingHeightDp.coerceIn(MIN_HEIGHT_DP, MAX_HEIGHT_DP))
        val lp = WindowManager.LayoutParams(
            width,
            height,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = config.floatingX
            y = config.floatingY
            windowAnimations = 0
        }

        clampPosition(lp)
        params = lp
        rootView = card

        attachDrag(header, lp)
        attachResize(resize, lp)

        try {
            windowManager.addView(card, lp)
            handler.removeCallbacks(refreshRunnable)
            handler.post(refreshRunnable)
        } catch (_: Exception) {
            rootView = null
            params = null
            stopSelf()
        }
    }

    private fun attachDrag(handle: View, lp: WindowManager.LayoutParams) {
        var lastRawX = 0f
        var lastRawY = 0f

        handle.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    gestureActive = true
                    lastRawX = event.rawX
                    lastRawY = event.rawY
                    true
                }

                MotionEvent.ACTION_MOVE -> {
                    val dx = (event.rawX - lastRawX).roundToInt()
                    val dy = (event.rawY - lastRawY).roundToInt()
                    lastRawX = event.rawX
                    lastRawY = event.rawY

                    if (dx != 0 || dy != 0) {
                        lp.x += dx
                        lp.y += dy
                        clampPosition(lp)
                        requestWindowUpdate(lp)
                    }
                    true
                }

                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    gestureActive = false
                    flushWindowUpdate(lp)
                    persistGeometry(lp)
                    true
                }

                else -> false
            }
        }
    }

    private fun attachResize(handle: View, lp: WindowManager.LayoutParams) {
        var lastRawX = 0f
        var lastRawY = 0f

        handle.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    gestureActive = true
                    lastRawX = event.rawX
                    lastRawY = event.rawY
                    true
                }

                MotionEvent.ACTION_MOVE -> {
                    val dx = (event.rawX - lastRawX).roundToInt()
                    val dy = (event.rawY - lastRawY).roundToInt()
                    lastRawX = event.rawX
                    lastRawY = event.rawY

                    val maxWidth = resources.displayMetrics.widthPixels
                    val maxHeight = resources.displayMetrics.heightPixels
                    lp.width = (lp.width + dx)
                        .coerceIn(dp(MIN_WIDTH_DP), maxOf(dp(MIN_WIDTH_DP), maxWidth))
                    lp.height = (lp.height + dy)
                        .coerceIn(dp(MIN_HEIGHT_DP), maxOf(dp(MIN_HEIGHT_DP), maxHeight))
                    clampPosition(lp)
                    requestWindowUpdate(lp)
                    true
                }

                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    gestureActive = false
                    flushWindowUpdate(lp)
                    persistGeometry(lp)
                    true
                }

                else -> false
            }
        }
    }

    /**
     * OEM overlay windows can visibly trail the finger if every high-frequency MOVE event
     * becomes a WindowManager transaction. Keep an immediate leading-edge update, then cap
     * the remaining transactions to roughly one per 8 ms while always applying the newest
     * coordinates. This avoids both Binder queues and the extra frame of latency caused by
     * postOnAnimation on some high-refresh-rate phones.
     */
    private fun requestWindowUpdate(lp: WindowManager.LayoutParams) {
        val now = SystemClock.uptimeMillis()
        val elapsed = now - lastWindowUpdateAt
        if (elapsed >= WINDOW_UPDATE_INTERVAL_MS && pendingWindowUpdate == null) {
            lastWindowUpdateAt = now
            applyWindowUpdate(lp)
            return
        }

        if (pendingWindowUpdate != null) return
        val delay = (WINDOW_UPDATE_INTERVAL_MS - elapsed).coerceAtLeast(1L)
        val runnable = Runnable {
            pendingWindowUpdate = null
            lastWindowUpdateAt = SystemClock.uptimeMillis()
            applyWindowUpdate(lp)
        }
        pendingWindowUpdate = runnable
        handler.postDelayed(runnable, delay)
    }

    private fun flushWindowUpdate(lp: WindowManager.LayoutParams) {
        cancelPendingWindowUpdate()
        lastWindowUpdateAt = SystemClock.uptimeMillis()
        applyWindowUpdate(lp)
    }

    private fun cancelPendingWindowUpdate() {
        pendingWindowUpdate?.let(handler::removeCallbacks)
        pendingWindowUpdate = null
    }

    private fun applyWindowUpdate(lp: WindowManager.LayoutParams) {
        val view = rootView ?: return
        try {
            windowManager.updateViewLayout(view, lp)
        } catch (_: Exception) {
        }
    }

    private fun renderStats(force: Boolean = false) {
        val stats = TrayStore.loadStats(this)
        val config = TrayStore.loadConfig(this)
        if (!config.floatingEnabled) {
            stopSelf()
            return
        }

        val signature = if (stats == null) {
            "null:${config.balanceAlertThreshold}"
        } else {
            listOf(
                stats.balance,
                stats.todayCost,
                stats.totalTokens,
                stats.requests,
                stats.rpm,
                stats.tpm,
                config.balanceAlertThreshold
            ).joinToString("|")
        }
        if (!force && signature == lastRenderedSignature) return
        lastRenderedSignature = signature

        if (stats == null) {
            balanceText?.text = "余额 --"
            detailText?.text = "等待首次统计数据"
            return
        }

        val balance = stats.balance
        balanceText?.text = "余额 ${balance?.let(TrayNotification::money) ?: "--"}"
        balanceText?.setTextColor(
            when {
                balance == null -> Color.rgb(35, 43, 55)
                balance <= 0.0 -> Color.rgb(210, 54, 68)
                balance <= config.balanceAlertThreshold -> Color.rgb(218, 133, 30)
                else -> Color.rgb(18, 155, 120)
            }
        )

        detailText?.text = buildString {
            append("今日 ${stats.todayCost?.let(TrayNotification::money) ?: "--"}")
            append(" · ${stats.totalTokens?.let(TrayNotification::tokens) ?: "--"} Token")
            append("\n${stats.requests ?: "--"} 次")
            append(" · ${stats.rpm?.let(TrayNotification::rate) ?: "--"} RPM")
            append(" · ${stats.tpm?.let(TrayNotification::rate) ?: "--"} TPM")
        }
    }

    private fun openMain() {
        startActivity(
            Intent(this, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            }
        )
    }

    private fun clampPosition(lp: WindowManager.LayoutParams) {
        val screenWidth = resources.displayMetrics.widthPixels
        val screenHeight = resources.displayMetrics.heightPixels
        lp.x = lp.x.coerceIn(0, maxOf(0, screenWidth - lp.width))
        lp.y = lp.y.coerceIn(0, maxOf(0, screenHeight - lp.height))
    }

    private fun persistGeometry(lp: WindowManager.LayoutParams) {
        val old = TrayStore.loadConfig(this)
        TrayStore.saveConfig(
            this,
            old.copy(
                floatingWidthDp = pxToDp(lp.width).coerceIn(MIN_WIDTH_DP, MAX_WIDTH_DP),
                floatingHeightDp = pxToDp(lp.height).coerceIn(MIN_HEIGHT_DP, MAX_HEIGHT_DP),
                floatingX = lp.x,
                floatingY = lp.y
            )
        )
    }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).roundToInt()

    private fun pxToDp(value: Int): Int =
        (value / resources.displayMetrics.density).roundToInt()

    companion object {
        private const val MIN_WIDTH_DP = 150
        private const val MAX_WIDTH_DP = 420
        private const val MIN_HEIGHT_DP = 80
        private const val MAX_HEIGHT_DP = 300
        private const val WINDOW_UPDATE_INTERVAL_MS = 8L

        fun start(context: Context) {
            val config = TrayStore.loadConfig(context)
            if (!config.floatingEnabled || !Settings.canDrawOverlays(context)) return
            try {
                context.startService(Intent(context, FloatingTrayService::class.java))
            } catch (_: Exception) {
            }
        }

        fun stop(context: Context) {
            try {
                context.stopService(Intent(context, FloatingTrayService::class.java))
            } catch (_: Exception) {
            }
        }
    }
}
