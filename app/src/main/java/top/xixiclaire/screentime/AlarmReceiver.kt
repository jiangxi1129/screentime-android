package top.xixiclaire.screentime

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.PowerManager
import android.util.Log

/**
 * Wakes up every 5 minutes via AlarmManager, reads UsageStats, POSTs to the
 * server, and re-schedules the next alarm. No persistent notification needed.
 *
 * On vivo / OPPO / Xiaomi this approach is much more reliable than WorkManager
 * but still requires the user to add the app to "auto-start whitelist"
 * (自启动管理) AND "battery whitelist" (电池/省电管理).
 *
 * If the user force-stops the app, alarms are cancelled — opening MainActivity
 * once will re-arm them.
 */
class AlarmReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        Log.i(TAG, "alarm fired action=${intent.action}")
        // Acquire a brief wakelock so the device doesn't doze mid-POST
        val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        val wl = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "screentime:alarm")
        var reportOk = false
        try {
            wl.acquire(30_000L)
            reportOk = doWork(context)
        } catch (t: Throwable) {
            Log.e(TAG, "work failed", t)
        } finally {
            // If report failed (network down), retry in 1 minute instead of 5
            scheduleNext(context, if (reportOk) INTERVAL_MS else RETRY_MS)
            if (wl.isHeld) wl.release()
        }
    }

    /** @return true if report was sent successfully */
    private fun doWork(context: Context): Boolean {
        if (!MainActivity.hasUsageAccess(context)) {
            Log.w(TAG, "usage access not granted, skipping")
            return false
        }
        // Re-register network callback in case Activity was killed by vivo
        ConnectivityReceiver.ensureRegistered(context)
        val apps = UsageReader.collect(context)
        val ok = Reporter.send(context, apps)
        val err = Reporter.lastError
        val now = System.currentTimeMillis()
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putLong(KEY_LAST_REPORT_MS, now)
            .putBoolean(KEY_LAST_REPORT_OK, ok)
            .putInt(KEY_LAST_REPORT_COUNT, apps.size)
            .putString(KEY_LAST_ERROR, if (ok) null else err)
            .apply()
        Log.i(TAG, "report ${if (ok) "ok" else "fail"} (${apps.size} apps) err=$err")

        // Send heartbeat as fallback (in case ScreenReceiver was killed by vivo)
        try {
            val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
            val screenOn = pm.isInteractive  // true if screen is on
            val foregroundApp = if (screenOn) getForegroundAppWide(context) else null
            val hbOk = HeartbeatReporter.send(foregroundApp, screenOn, "android-${android.os.Build.MODEL}")
            Log.i(TAG, "heartbeat ${if (hbOk) "ok" else "fail"}: app=$foregroundApp screen=$screenOn")
        } catch (e: Exception) {
            Log.w(TAG, "heartbeat error: ${e.message}")
        }
        return ok
    }

    /** Query foreground app using a wider window (last 60s) for reliability. */
    private fun getForegroundAppWide(context: Context): String? {
        try {
            val usm = context.getSystemService(Context.USAGE_STATS_SERVICE)
                as android.app.usage.UsageStatsManager
            val now = System.currentTimeMillis()
            val events = usm.queryEvents(now - 60_000, now) ?: return null
            val ev = android.app.usage.UsageEvents.Event()
            var lastApp: String? = null
            while (events.hasNextEvent()) {
                events.getNextEvent(ev)
                if (ev.eventType == android.app.usage.UsageEvents.Event.ACTIVITY_RESUMED) {
                    lastApp = ev.packageName
                }
            }
            if (lastApp != null) {
                return try {
                    val ai = context.packageManager.getApplicationInfo(lastApp, 0)
                    context.packageManager.getApplicationLabel(ai).toString()
                } catch (_: Exception) { lastApp }
            }
        } catch (e: Exception) {
            Log.w(TAG, "getForegroundAppWide: ${e.message}")
        }
        return null
    }

    companion object {
        private const val TAG = "ScreentimeAlarm"
        const val PREFS = "screentime_state"
        const val KEY_LAST_REPORT_MS = "last_report_ms"
        const val KEY_LAST_REPORT_OK = "last_report_ok"
        const val KEY_LAST_REPORT_COUNT = "last_report_count"
        const val KEY_LAST_ERROR = "last_error"
        const val ACTION_REPORT = "top.xixiclaire.screentime.REPORT"
        private const val REQUEST_CODE = 9101
        const val INTERVAL_MS = 5L * 60 * 1000   // 5 minutes
        private const val RETRY_MS = 60L * 1000   // 1 minute (on failure)

        private fun pendingIntent(ctx: Context, flags: Int): PendingIntent? {
            val intent = Intent(ctx, AlarmReceiver::class.java).apply {
                action = ACTION_REPORT
            }
            return PendingIntent.getBroadcast(ctx, REQUEST_CODE, intent, flags)
        }

        /** Schedule the next single-shot alarm. Default 5 min, or custom interval. */
        fun scheduleNext(ctx: Context, intervalMs: Long = INTERVAL_MS) {
            val am = ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            val pi = pendingIntent(
                ctx,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            ) ?: return
            val triggerAt = System.currentTimeMillis() + intervalMs
            try {
                am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pi)
                Log.i(TAG, "next alarm scheduled in ${intervalMs / 1000}s")
            } catch (e: SecurityException) {
                am.set(AlarmManager.RTC_WAKEUP, triggerAt, pi)
            }
        }

        /** Cancel any pending alarm. */
        fun cancel(ctx: Context) {
            val am = ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            val pi = pendingIntent(
                ctx,
                PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE,
            )
            if (pi != null) {
                am.cancel(pi)
                pi.cancel()
                Log.i(TAG, "alarm cancelled")
            }
        }

        /** Is there a pending alarm right now? */
        fun isScheduled(ctx: Context): Boolean {
            val pi = pendingIntent(
                ctx,
                PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE,
            )
            return pi != null
        }
    }
}
