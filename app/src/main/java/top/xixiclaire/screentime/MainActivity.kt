package top.xixiclaire.screentime

import android.app.AppOpsManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit

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

        val spacer1 = TextView(this).apply { text = "" ; textSize = 8f }
        root.addView(spacer1)

        statusText = TextView(this).apply {
            textSize = 14f
            setLineSpacing(0f, 1.3f)
        }
        root.addView(statusText)

        val spacer2 = TextView(this).apply { text = "" ; textSize = 12f }
        root.addView(spacer2)

        val btnPerm = Button(this).apply {
            text = "1. 授权使用情况访问"
            setOnClickListener {
                startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
            }
        }
        root.addView(btnPerm)

        val btnSchedule = Button(this).apply {
            text = "2. 启动定时上报 (15分钟一次)"
            setOnClickListener { schedule() ; refresh() }
        }
        root.addView(btnSchedule)

        val btnTestNow = Button(this).apply {
            text = "3. 立刻上报一次 (测试)"
            setOnClickListener {
                Thread {
                    val report = UsageReader.collect(applicationContext)
                    val ok = Reporter.send(applicationContext, report)
                    runOnUiThread { refresh(extra = if (ok) "立刻上报: 成功 (${report.size} 个 app)" else "立刻上报: 失败") }
                }.start()
            }
        }
        root.addView(btnTestNow)

        val btnBatteryWhitelist = Button(this).apply {
            text = "4. 加入电池白名单 (vivo必做)"
            setOnClickListener {
                try {
                    startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                        data = android.net.Uri.parse("package:$packageName")
                    })
                } catch (e: Exception) {
                    startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
                }
            }
        }
        root.addView(btnBatteryWhitelist)

        val scroll = ScrollView(this).apply { addView(root) }
        setContentView(scroll)

        refresh()
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun refresh(extra: String? = null) {
        val granted = hasUsageAccess(this)
        val sb = StringBuilder()
        sb.append("服务器: ").append(Reporter.SERVER_URL).append("\n")
        sb.append("权限状态: ").append(if (granted) "✅ 已授权" else "❌ 未授权").append("\n")
        val info = WorkManager.getInstance(this).getWorkInfosForUniqueWork(WORK_NAME).get()
        sb.append("定时任务: ").append(if (info.isNotEmpty() && !info[0].state.isFinished) "✅ 运行中" else "未启动").append("\n")
        if (extra != null) sb.append("\n").append(extra).append("\n")
        statusText.text = sb.toString()
    }

    private fun schedule() {
        val req = PeriodicWorkRequestBuilder<ReportWorker>(15, TimeUnit.MINUTES)
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build()
            )
            .build()
        WorkManager.getInstance(this).enqueueUniquePeriodicWork(
            WORK_NAME,
            ExistingPeriodicWorkPolicy.UPDATE,
            req,
        )
    }

    companion object {
        const val WORK_NAME = "screentime_report"

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
