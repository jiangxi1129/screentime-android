package top.xixiclaire.screentime

import android.app.AppOpsManager
import android.content.Context
import android.content.Intent
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

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
                    val now = System.currentTimeMillis()
                    getSharedPreferences(AlarmReceiver.PREFS, Context.MODE_PRIVATE)
                        .edit()
                        .putLong(AlarmReceiver.KEY_LAST_REPORT_MS, now)
                        .putBoolean(AlarmReceiver.KEY_LAST_REPORT_OK, ok)
                        .putInt(AlarmReceiver.KEY_LAST_REPORT_COUNT, report.size)
                        .apply()
                    runOnUiThread {
                        refresh(
                            extra = if (ok) "✅ 立刻上报成功 (${report.size} 个 app)"
                            else "❌ 立刻上报失败，看日志"
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
        refresh()
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

        val sb = StringBuilder()
        sb.append("服务器: ").append(Reporter.SERVER_URL).append("\n")
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
