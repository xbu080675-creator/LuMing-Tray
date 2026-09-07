package com.luming.tray

import android.app.Activity
import android.app.Application
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsetsController
import android.webkit.WebView
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView

class LuMingApp : Application(), Application.ActivityLifecycleCallbacks {
    override fun onCreate() {
        super.onCreate()
        registerActivityLifecycleCallbacks(this)
    }

    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {
        LuMingTheme.prepareWindow(activity)
    }

    override fun onActivityResumed(activity: Activity) {
        if (activity is MainActivity) DashboardFeatureInjector.ensure(activity)
        LuMingTheme.apply(activity)
    }

    override fun onActivityStarted(activity: Activity) = Unit
    override fun onActivityPaused(activity: Activity) = Unit
    override fun onActivityStopped(activity: Activity) = Unit
    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
    override fun onActivityDestroyed(activity: Activity) = Unit
}

enum class LuMingThemeMode(val wire: String) {
    SYSTEM("system"), LIGHT("light"), DARK("dark");

    companion object {
        fun from(value: String?): LuMingThemeMode = entries.firstOrNull { it.wire == value } ?: SYSTEM
    }
}

object LuMingTheme {
    private const val PREFS = "luming_ui"
    private const val KEY_MODE = "theme_mode"

    private val lightToDark = mapOf(
        Color.rgb(232, 239, 242) to Color.rgb(16, 21, 26),
        Color.rgb(242, 247, 249) to Color.rgb(26, 33, 40),
        Color.rgb(239, 244, 246) to Color.rgb(31, 39, 46),
        Color.rgb(235, 241, 244) to Color.rgb(23, 30, 37),
        Color.rgb(225, 244, 239) to Color.rgb(21, 50, 44),
        Color.rgb(223, 242, 237) to Color.rgb(21, 50, 44),
        Color.rgb(222, 243, 237) to Color.rgb(21, 52, 45),
        Color.rgb(232, 237, 240) to Color.rgb(38, 46, 53),
        Color.rgb(215, 224, 228) to Color.rgb(59, 69, 77),
        Color.rgb(218, 227, 231) to Color.rgb(57, 67, 75),
        Color.rgb(221, 229, 233) to Color.rgb(60, 70, 78),
        Color.rgb(199, 231, 222) to Color.rgb(43, 92, 79),
        Color.rgb(37, 47, 58) to Color.rgb(235, 240, 244),
        Color.rgb(75, 88, 101) to Color.rgb(185, 195, 204),
        Color.rgb(118, 131, 143) to Color.rgb(137, 151, 164),
        Color.rgb(145, 154, 164) to Color.rgb(112, 126, 139),
        Color.rgb(25, 157, 130) to Color.rgb(58, 199, 165),
        Color.rgb(21, 125, 106) to Color.rgb(91, 205, 174)
    )
    private val darkToLight = lightToDark.entries.associate { (k, v) -> v to k }

    fun mode(context: Context): LuMingThemeMode = LuMingThemeMode.from(
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_MODE, LuMingThemeMode.SYSTEM.wire)
    )

    fun setMode(context: Context, mode: LuMingThemeMode) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY_MODE, mode.wire).apply()
    }

    fun isDark(context: Context): Boolean = when (mode(context)) {
        LuMingThemeMode.DARK -> true
        LuMingThemeMode.LIGHT -> false
        LuMingThemeMode.SYSTEM -> (context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
    }

    fun prepareWindow(activity: Activity) {
        val dark = isDark(activity)
        activity.window.statusBarColor = if (dark) Color.rgb(16, 21, 26) else Color.rgb(232, 239, 242)
        activity.window.navigationBarColor = if (dark) Color.rgb(16, 21, 26) else Color.rgb(232, 239, 242)
        if (android.os.Build.VERSION.SDK_INT >= 30) {
            activity.window.insetsController?.setSystemBarsAppearance(
                if (dark) 0 else WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS,
                WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS
            )
        } else {
            @Suppress("DEPRECATION")
            activity.window.decorView.systemUiVisibility = if (dark) 0 else View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
        }
    }

    fun apply(activity: Activity) {
        val dark = isDark(activity)
        prepareWindow(activity)
        val root = activity.findViewById<ViewGroup>(android.R.id.content) ?: return
        recolor(root, dark)
    }

    private fun recolor(view: View, dark: Boolean) {
        if (view is WebView) return

        mapBackground(view, dark)
        if (view is TextView) {
            view.setTextColor(mapColor(view.currentTextColor, dark))
            if (view is EditText) {
                view.setHintTextColor(mapColor(view.currentHintTextColor, dark))
            }
        }
        if (view is ViewGroup) {
            for (i in 0 until view.childCount) recolor(view.getChildAt(i), dark)
        }
    }

    private fun mapBackground(view: View, dark: Boolean) {
        when (val bg = view.background) {
            is ColorDrawable -> bg.color = mapColor(bg.color, dark)
            is GradientDrawable -> bg.color?.defaultColor?.let { bg.setColor(mapColor(it, dark)) }
        }
    }

    private fun mapColor(color: Int, dark: Boolean): Int {
        val alpha = Color.alpha(color)
        val opaque = Color.rgb(Color.red(color), Color.green(color), Color.blue(color))
        val mapped = if (dark) lightToDark[opaque] else darkToLight[opaque]
        return if (mapped == null) color else Color.argb(alpha, Color.red(mapped), Color.green(mapped), Color.blue(mapped))
    }
}

object DashboardFeatureInjector {
    private const val TAG = "luming_account_entry_016"

    fun ensure(activity: MainActivity) {
        val content = activity.findViewById<ViewGroup>(android.R.id.content) ?: return
        if (findTag(content, TAG) != null) {
            updateFooter(content)
            return
        }
        val root = findDashboardRoot(content) ?: return
        val anchor = (0 until root.childCount).firstOrNull { index ->
            (root.getChildAt(index) as? TextView)?.text?.toString() == "余额预警"
        } ?: root.childCount

        val button = Button(activity).apply {
            tag = TAG
            text = "账户中心 · 个人 / 安全 / 外观"
            isAllCaps = false
            textSize = 12.5f
            setTextColor(Color.rgb(75, 88, 101))
            background = GradientDrawable().apply {
                setColor(Color.rgb(239, 244, 246))
                cornerRadius = dp(activity, 17).toFloat()
                setStroke(dp(activity, 1), Color.rgb(221, 229, 233))
            }
            elevation = dp(activity, 4).toFloat()
            stateListAnimator = null
            minHeight = 0
            minimumHeight = 0
            setPadding(dp(activity, 10), 0, dp(activity, 10), 0)
            setOnClickListener { activity.startActivity(Intent(activity, AccountActivity::class.java)) }
        }
        root.addView(button, anchor, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(activity, 54)).apply {
            topMargin = dp(activity, 10)
        })
        updateFooter(content)
    }

    private fun findDashboardRoot(view: View): LinearLayout? {
        if (view is LinearLayout) {
            val hasBalance = (0 until view.childCount).any { (view.getChildAt(it) as? TextView)?.text?.toString() == "余额预警" }
            if (hasBalance) return view
        }
        if (view is ViewGroup) {
            for (i in 0 until view.childCount) findDashboardRoot(view.getChildAt(i))?.let { return it }
        }
        return null
    }

    private fun findTag(view: View, tag: String): View? {
        if (view.tag == tag) return view
        if (view is ViewGroup) {
            for (i in 0 until view.childCount) findTag(view.getChildAt(i), tag)?.let { return it }
        }
        return null
    }

    private fun updateFooter(view: View) {
        if (view is TextView && view.text?.toString()?.startsWith("LuMing Tray 0.15.0") == true) {
            view.text = "LuMing Tray 0.16.0 · Account Center / Dark Mode"
        }
        if (view is ViewGroup) for (i in 0 until view.childCount) updateFooter(view.getChildAt(i))
    }

    private fun dp(context: Context, value: Int) = (value * context.resources.displayMetrics.density).toInt()
}
