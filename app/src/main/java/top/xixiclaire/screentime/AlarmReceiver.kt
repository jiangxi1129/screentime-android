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
        // CRITICAL: schedule the next alarm IMMEDIATELY before doing any work.
        // If the receiver is killed mid-execution (vivo, doze, etc.), the alarm
        // chain still survives. The work itself runs on a background thread via
        // goAsync() so we don't block onReceive's ~10-second budget.
        scheduleNext(context, INTERVAL_MS)

        val pendingResult = goAsync()
        Thread {
            val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
            val wl = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "screentime:alarm")
            var reportOk = false
            try {
                wl.acquire(60_000L)
                reportOk = doWork(context)
            } catch (t: Throwable) {
                Log.e(TAG, "work failed", t)
            } finally {
                // Adjust next alarm based on success — overwrites the
                // pre-scheduled one with appropriate retry interval
                val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                if (reportOk) {
                    prefs.edit().putInt(KEY_CONSECUTIVE_FAILS, 0).apply()
                    // Already scheduled at INTERVAL_MS above — keep it
                } else {
                    val fails = prefs.getInt(KEY_CONSECUTIVE_FAILS, 0) + 1
                    prefs.edit().putInt(KEY_CONSECUTIVE_FAILS, fails).apply()
                    val retryMs = when {
                        fails <= 3  -> RETRY_FAST_MS   // 1 min — just disconnected
                        fails <= 6  -> INTERVAL_MS      // 5 min — normal pace
                        else        -> RETRY_SLOW_MS    // 10 min — long offline
                    }
                    Log.i(TAG, "fail #$fails, next retry in ${retryMs / 1000}s")
                    scheduleNext(context, retryMs)
                }
                if (wl.isHeld) wl.release()
                pendingResult.finish()
            }
        }.start()
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

    /** Query the current foreground app by finding the last RESUMED without a
     *  subsequent PAUSED. Looks at the last 6 hours so apps used continuously
     *  for a long time are still detected.
     *
     *  Ignores the Screentime app's own package so that opening the app to
     *  check status doesn't pollute current_app. */
    private fun getForegroundAppWide(context: Context): String? {
        try {
            val usm = context.getSystemService(Context.USAGE_STATS_SERVICE)
                as android.app.usage.UsageStatsManager
            val selfPkg = context.packageName
            val now = System.currentTimeMillis()
            val events = usm.queryEvents(now - 6 * 3600_000L, now) ?: return null
            val ev = android.app.usage.UsageEvents.Event()
            var lastResumedPkg: String? = null
            var lastResumedTs = 0L
            // Track per-package last RESUMED and PAUSED timestamps
            val lastResumed = HashMap<String, Long>()
            val lastPaused = HashMap<String, Long>()
            while (events.hasNextEvent()) {
                events.getNextEvent(ev)
                val pkg = ev.packageName ?: continue
                if (pkg == selfPkg) continue  // never report ourselves
                when (ev.eventType) {
                    android.app.usage.UsageEvents.Event.ACTIVITY_RESUMED -> {
                        lastResumed[pkg] = ev.timeStamp
                        if (ev.timeStamp >= lastResumedTs) {
                            lastResumedTs = ev.timeStamp
                            lastResumedPkg = pkg
                        }
                    }
                    android.app.usage.UsageEvents.Event.ACTIVITY_PAUSED,
                    android.app.usage.UsageEvents.Event.ACTIVITY_STOPPED -> {
                        lastPaused[pkg] = ev.timeStamp
                    }
                }
            }
            // Current foreground = pkg whose lastResumed > lastPaused
            // Prefer the most recently RESUMED package
            if (lastResumedPkg != null) {
                val resumedTs = lastResumed[lastResumedPkg] ?: 0L
                val pausedTs = lastPaused[lastResumedPkg] ?: 0L
                if (resumedTs > pausedTs) {
                    return try {
                        val ai = context.packageManager.getApplicationInfo(lastResumedPkg!!, 0)
                        context.packageManager.getApplicationLabel(ai).toString()
                    } catch (_: Exception) { lastResumedPkg }
                }
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
        const val KEY_CONSECUTIVE_FAILS = "consecutive_fails"
        private const val REQUEST_CODE = 9101
        const val INTERVAL_MS  = 2L * 60 * 1000   // 2 min — normal
        private const val RETRY_FAST_MS = 60L * 1000   // 1 min — just went offline
        private const val RETRY_SLOW_MS = 10L * 60 * 1000 // 10 min — long offline

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
