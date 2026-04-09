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
         * Get the most recent foreground app by querying usage events
         * in the last 5 seconds.
         */
        fun getForegroundApp(context: Context): String? {
            if (!MainActivity.hasUsageAccess(context)) return null
            try {
                val usm = context.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
                val now = System.currentTimeMillis()
                val events = usm.queryEvents(now - 5000, now) ?: return null
                val ev = UsageEvents.Event()
                var lastApp: String? = null
                while (events.hasNextEvent()) {
                    events.getNextEvent(ev)
                    if (ev.eventType == UsageEvents.Event.ACTIVITY_RESUMED) {
                        lastApp = ev.packageName
                    }
                }
                if (lastApp != null) {
                    // Convert package to friendly label
                    return try {
                        val ai = context.packageManager.getApplicationInfo(lastApp, 0)
                        context.packageManager.getApplicationLabel(ai).toString()
                    } catch (_: Exception) {
                        lastApp
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "getForegroundApp failed: ${e.message}")
            }
            return null
        }
    }
}
