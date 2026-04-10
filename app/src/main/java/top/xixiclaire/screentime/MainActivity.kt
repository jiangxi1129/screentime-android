package top.xixiclaire.screentime

import android.app.AppOpsManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private lateinit var statusText: TextView
    private var screenReceiver: ScreenReceiver? = null
    // ConnectivityReceiver is now a global singleton, no instance needed

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        try {
            buildUi()
            registerScreenReceiver()
            ConnectivityReceiver.ensureRegistered(applicationContext)
        } catch (t: Throwable) {
            // Last-resort visible error so we don't silently crash
            val tv = TextView(this).apply {
                text = "启动失败:\n${t.javaClass.simpleName}: ${t.message}\n\n${t.stackTraceToString().take(800)}"
                textSize = 12f
                setPadding(40, 40, 40, 40)
            }
            setContentView(ScrollView(this).apply { addView(tv) })
        }
    }

    private fun buildUi() {
        val pad = (resources.displayMetrics.density * 24).toInt()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
        }

        val title = TextView(this).apply {
            text = "Screentime Reporter"
            textSize = 22f
            gravity = Gravity.CENTER
        }
        root.addView(title)

        root.addView(spacer(8))

        statusText = TextView(this).apply {
            textSize = 14f
            setLineSpacing(0f, 1.3f)
        }
        root.addView(statusText)

        root.addView(spacer(12))

        root.addView(Button(this).apply {
            text = "1. 授权使用情况访问"
            setOnClickListener {
                startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
            }
        })

        root.addView(Button(this).apply {
            text = "2. 启动定时上报 (5分钟一次)"
            setOnClickListener {
                if (!hasUsageAccess(this@MainActivity)) {
                    refresh(extra = "❌ 请先点按钮 1 授权权限")
                    return@setOnClickListener
                }
                AlarmReceiver.scheduleNext(applicationContext)
                refresh(extra = "✅ 定时已启动，5 分钟后第一次上报")
            }
        })

        root.addView(Button(this).apply {
            text = "3. 立刻上报一次 (测试)"
            setOnClickListener {
                Thread {
                    val report = UsageReader.collect(applicationContext)
                    val ok = Reporter.send(applicationContext, report)
                    val err = Reporter.lastError
                    val now = System.currentTimeMillis()
                    getSharedPreferences(AlarmReceiver.PREFS, Context.MODE_PRIVATE)
                        .edit()
                        .putLong(AlarmReceiver.KEY_LAST_REPORT_MS, now)
                        .putBoolean(AlarmReceiver.KEY_LAST_REPORT_OK, ok)
                        .putInt(AlarmReceiver.KEY_LAST_REPORT_COUNT, report.size)
                        .putString(AlarmReceiver.KEY_LAST_ERROR, if (ok) null else err)
                        .apply()
                    val via = Reporter.lastEndpoint
                    runOnUiThread {
                        refresh(
                            extra = if (ok)
                                "✅ 立刻上报成功 (${report.size} 个 app)\n通道: ${via ?: "?"}"
                            else
                                "❌ 失败: ${err ?: "unknown"}"
                        )
                    }
                }.start()
            }
        })

        root.addView(Button(this).apply {
            text = "4. 加入电池白名单 (vivo必做)"
            setOnClickListener {
                try {
                    startActivity(
                        Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
                            .setData(Uri.parse("package:$packageName"))
                    )
                } catch (e: Exception) {
                    startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
                }
            }
        })

        root.addView(Button(this).apply {
            text = "5. 停止定时上报"
            setOnClickListener {
                AlarmReceiver.cancel(applicationContext)
                refresh(extra = "已停止")
            }
        })

        val scroll = ScrollView(this).apply { addView(root) }
        setContentView(scroll)

        refresh()
    }

    override fun onResume() {
        super.onResume()
        try {
            refresh()
            // Auto-recover: if alarm should be running but last report was >10 min ago,
            // re-arm the alarm (vivo may have killed it)
            autoRecoverAlarm()
        } catch (_: Throwable) { }
    }

    private fun autoRecoverAlarm() {
        if (!hasUsageAccess(this)) return
        val prefs = getSharedPreferences(AlarmReceiver.PREFS, Context.MODE_PRIVATE)
        val lastMs = prefs.getLong(AlarmReceiver.KEY_LAST_REPORT_MS, 0L)
        val ageMs = System.currentTimeMillis() - lastMs
        // If last report was >10 minutes ago, re-arm alarm + do immediate report
        if (lastMs > 0 && ageMs > 10 * 60 * 1000) {
            AlarmReceiver.scheduleNext(applicationContext)
            ConnectivityReceiver.ensureRegistered(applicationContext)
            Thread {
                try {
                    val apps = UsageReader.collect(applicationContext)
                    val ok = Reporter.send(applicationContext, apps)
                    val now = System.currentTimeMillis()
                    prefs.edit()
                        .putLong(AlarmReceiver.KEY_LAST_REPORT_MS, now)
                        .putBoolean(AlarmReceiver.KEY_LAST_REPORT_OK, ok)
                        .putInt(AlarmReceiver.KEY_LAST_REPORT_COUNT, apps.size)
                        .putString(AlarmReceiver.KEY_LAST_ERROR, if (ok) null else Reporter.lastError)
                        .apply()
                    runOnUiThread { refresh(extra = if (ok) "✅ 自动恢复上报成功" else "❌ 恢复失败: ${Reporter.lastError}") }
                } catch (_: Exception) { }
            }.start()
        }
    }

    override fun onDestroy() {
        screenReceiver?.let {
            try { unregisterReceiver(it) } catch (_: Throwable) { }
        }
        // Don't unregister ConnectivityReceiver — it's a global singleton
        // that should survive Activity destruction
        super.onDestroy()
    }

    private fun registerScreenReceiver() {
        screenReceiver = ScreenReceiver()
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_SCREEN_OFF)
        }
        registerReceiver(screenReceiver, filter)
    }

    private fun spacer(dp: Int): TextView = TextView(this).apply {
        text = ""
        textSize = dp.toFloat()
    }

    private fun refresh(extra: String? = null) {
        val granted = hasUsageAccess(this)
        val scheduled = AlarmReceiver.isScheduled(this)
        val prefs = getSharedPreferences(AlarmReceiver.PREFS, Context.MODE_PRIVATE)
        val lastMs = prefs.getLong(AlarmReceiver.KEY_LAST_REPORT_MS, 0L)
        val lastOk = prefs.getBoolean(AlarmReceiver.KEY_LAST_REPORT_OK, false)
        val lastCount = prefs.getInt(AlarmReceiver.KEY_LAST_REPORT_COUNT, 0)
        val lastError = prefs.getString(AlarmReceiver.KEY_LAST_ERROR, null)

        val sb = StringBuilder()
        sb.append("通道: ").append(Reporter.ENDPOINTS.size).append(" 个候选 (HTTP优先,HTTPS兜底)\n")
        sb.append("权限: ").append(if (granted) "✅ 已授权" else "❌ 未授权").append("\n")
        sb.append("定时: ").append(if (scheduled) "✅ 已排定 (5分钟周期)" else "未启动").append("\n")
        if (lastMs > 0) {
            val time = SimpleDateFormat("MM-dd HH:mm:ss", Locale.US).format(Date(lastMs))
            val ageMin = ((System.currentTimeMillis() - lastMs) / 60_000)
            sb.append("上次上报: ")
                .append(if (lastOk) "✅" else "❌")
                .append(" ").append(time)
                .append(" (").append(ageMin).append(" 分钟前, ")
                .append(lastCount).append(" 个 app)")
                .append("\n")
            if (!lastOk && lastError != null) {
                sb.append("错误: ").append(lastError).append("\n")
            }
        } else {
            sb.append("上次上报: 从未\n")
        }
        if (extra != null) sb.append("\n").append(extra)
        statusText.text = sb.toString()
    }

    companion object {
        fun hasUsageAccess(ctx: Context): Boolean {
            val ops = ctx.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
            val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ops.unsafeCheckOpNoThrow(
                    AppOpsManager.OPSTR_GET_USAGE_STATS,
                    android.os.Process.myUid(),
                    ctx.packageName,
                )
            } else {
                @Suppress("DEPRECATION")
                ops.checkOpNoThrow(
                    AppOpsManager.OPSTR_GET_USAGE_STATS,
                    android.os.Process.myUid(),
                    ctx.packageName,
                )
            }
            return mode == AppOpsManager.MODE_ALLOWED
        }
    }
}
