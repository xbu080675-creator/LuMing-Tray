package com.luming.tray

import android.app.Activity
import android.content.Context
import android.content.res.Configuration
import android.graphics.Color
import android.os.Build
import android.view.View
import android.view.WindowInsetsController

enum class LuMingThemeMode(val wire: String) {
    SYSTEM("system"), LIGHT("light"), DARK("dark");

    companion object {
        fun from(value: String?): LuMingThemeMode = entries.firstOrNull { it.wire == value } ?: SYSTEM
    }
}

/**
 * Lightweight theme preference + palette.
 *
 * 0.16.0 used an Application lifecycle callback that recursively recolored every View whenever
 * an Activity resumed. That was too invasive for a growing app and could touch arbitrary widget
 * drawables. 0.17.0 removes that global hook completely. Each native screen asks this palette for
 * colors explicitly, so opening the app never depends on a whole-view-tree mutation pass.
 */
object LuMingTheme {
    private const val PREFS = "luming_ui"
    private const val KEY_MODE = "theme_mode"

    fun mode(context: Context): LuMingThemeMode = LuMingThemeMode.from(
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_MODE, LuMingThemeMode.SYSTEM.wire)
    )

    fun setMode(context: Context, mode: LuMingThemeMode) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_MODE, mode.wire)
            .apply()
    }

    fun isDark(context: Context): Boolean = when (mode(context)) {
        LuMingThemeMode.DARK -> true
        LuMingThemeMode.LIGHT -> false
        LuMingThemeMode.SYSTEM ->
            (context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
    }

    fun applySystemBars(activity: Activity) {
        val dark = isDark(activity)
        val bg = bg(activity)
        activity.window.statusBarColor = bg
        activity.window.navigationBarColor = bg
        if (Build.VERSION.SDK_INT >= 30) {
            activity.window.insetsController?.setSystemBarsAppearance(
                if (dark) 0 else WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS,
                WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS
            )
        } else {
            @Suppress("DEPRECATION")
            activity.window.decorView.systemUiVisibility = if (dark) 0
            else View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
        }
    }

    fun bg(context: Context) = if (isDark(context)) Color.rgb(16, 21, 26) else Color.rgb(232, 239, 242)
    fun panel(context: Context) = if (isDark(context)) Color.rgb(26, 33, 40) else Color.rgb(242, 247, 249)
    fun panelAlt(context: Context) = if (isDark(context)) Color.rgb(31, 39, 46) else Color.rgb(239, 244, 246)
    fun input(context: Context) = if (isDark(context)) Color.rgb(23, 30, 37) else Color.rgb(235, 241, 244)
    fun border(context: Context) = if (isDark(context)) Color.rgb(55, 66, 75) else Color.rgb(221, 229, 233)
    fun divider(context: Context) = if (isDark(context)) Color.rgb(59, 69, 77) else Color.rgb(215, 224, 228)
    fun textPrimary(context: Context) = if (isDark(context)) Color.rgb(235, 240, 244) else Color.rgb(37, 47, 58)
    fun textSecondary(context: Context) = if (isDark(context)) Color.rgb(185, 195, 204) else Color.rgb(75, 88, 101)
    fun textMuted(context: Context) = if (isDark(context)) Color.rgb(137, 151, 164) else Color.rgb(118, 131, 143)
    fun hint(context: Context) = if (isDark(context)) Color.rgb(112, 126, 139) else Color.rgb(145, 154, 164)
    fun accent(context: Context) = if (isDark(context)) Color.rgb(58, 199, 165) else Color.rgb(25, 157, 130)
    fun accentDark(context: Context) = if (isDark(context)) Color.rgb(91, 205, 174) else Color.rgb(21, 125, 106)
    fun activeBg(context: Context) = if (isDark(context)) Color.rgb(21, 50, 44) else Color.rgb(225, 244, 239)
    fun activeBorder(context: Context) = if (isDark(context)) Color.rgb(43, 92, 79) else Color.rgb(199, 231, 222)
    fun positivePill(context: Context) = if (isDark(context)) Color.rgb(21, 52, 45) else Color.rgb(222, 243, 237)
    fun neutralPill(context: Context) = if (isDark(context)) Color.rgb(38, 46, 53) else Color.rgb(232, 237, 240)
}
