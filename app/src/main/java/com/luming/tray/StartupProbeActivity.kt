package com.luming.tray

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.LinearLayout
import android.widget.TextView

class StartupProbeActivity : Activity() {
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var status: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(48), dp(24), dp(24))
            setBackgroundColor(Color.rgb(232, 239, 242))
        }
        root.addView(TextView(this).apply {
            text = "LuMing Tray"
            textSize = 28f
            setTextColor(Color.rgb(37, 47, 58))
        })
        status = TextView(this).apply {
            text = "0.17.6.1 · 启动回归模式\n\n首页已经成功创建。"
            textSize = 15f
            setTextColor(Color.rgb(75, 88, 101))
            setPadding(0, dp(18), 0, 0)
        }
        root.addView(status)
        setContentView(root)
        handler.postDelayed({ restoreRuntime() }, 1200L)
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    private fun restoreRuntime() {
        runCatching {
            sendBroadcast(
                Intent("com.luming.tray.action.RESTORE_RUNTIME")
                    .setClassName(packageName, "$packageName.BootReceiver")
            )
            status.append("\n后台恢复请求已发送。")
        }.onFailure {
            status.append("\n后台恢复失败，但首页仍保持运行。")
        }
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
}
