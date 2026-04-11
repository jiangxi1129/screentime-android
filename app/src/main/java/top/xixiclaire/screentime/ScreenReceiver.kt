package top.xixiclaire.screentime

import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log

/**
 * Listens for SCREEN_ON and SCREEN_OFF broadcasts.
 * On each event, sends a heartbeat with the current foreground app (if screen on)
 * or null (if screen off).
 *
 * Must be registered dynamically in MainActivity because SCREEN_ON/OFF
 * cannot be registered in the manifest (Android restriction since API 26).
 */
class ScreenReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val screenOn = intent.action == Intent.ACTION_SCREEN_ON
        Log.i(TAG, "screen ${if (screenOn) "ON" else "OFF"}")

        val currentApp = if (screenOn) getForegroundApp(context) else null
        val source = "android-${Build.MODEL}"

        // Send heartbeat in background thread (network on main = crash)
        Thread {
            HeartbeatReporter.send(currentApp, screenOn, source)
        }.start()
    }

    companion object {
        private const val TAG = "ScreentimeScreenRx"

        /**
         * Get the current foreground app by finding the last RESUMED without
         * a subsequent PAUSED. Looks at the last 6 hours so apps used
         * continuously for hours are still detected.
         */
        fun getForegroundApp(context: Context): String? {
            if (!MainActivity.hasUsageAccess(context)) return null
            try {
                val usm = context.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
                val now = System.currentTimeMillis()
                val events = usm.queryEvents(now - 6 * 3600_000L, now) ?: return null
                val ev = UsageEvents.Event()
                var lastResumedPkg: String? = null
                var lastResumedTs = 0L
                val lastResumed = HashMap<String, Long>()
                val lastPaused = HashMap<String, Long>()
                while (events.hasNextEvent()) {
                    events.getNextEvent(ev)
                    val pkg = ev.packageName ?: continue
                    when (ev.eventType) {
                        UsageEvents.Event.ACTIVITY_RESUMED -> {
                            lastResumed[pkg] = ev.timeStamp
                            if (ev.timeStamp >= lastResumedTs) {
                                lastResumedTs = ev.timeStamp
                                lastResumedPkg = pkg
                            }
                        }
                        UsageEvents.Event.ACTIVITY_PAUSED,
                        UsageEvents.Event.ACTIVITY_STOPPED -> {
                            lastPaused[pkg] = ev.timeStamp
                        }
                    }
                }
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
                Log.w(TAG, "getForegroundApp failed: ${e.message}")
            }
            return null
        }
    }
}
