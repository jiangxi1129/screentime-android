package top.xixiclaire.screentime

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.os.Build
import android.util.Log

/**
 * Re-arms AlarmManager AND fires an immediate report+heartbeat when
 * connectivity is restored (e.g. Clash reconnected).
 *
 * On Android 7+ (API 24), CONNECTIVITY_CHANGE is NOT delivered to
 * manifest-registered receivers. So we also register dynamically from
 * MainActivity. The manifest entry still works on older devices.
 */
class ConnectivityReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (!MainActivity.hasUsageAccess(context)) return

        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        @Suppress("DEPRECATION")
        val active = cm.activeNetworkInfo
        @Suppress("DEPRECATION")
        val connected = active != null && active.isConnected

        if (connected) {
            Log.i(TAG, "network restored, re-arming alarm + immediate report")
            AlarmReceiver.scheduleNext(context)

            // Fire an immediate report+heartbeat in background
            Thread {
                try {
                    val apps = UsageReader.collect(context)
                    Reporter.send(context, apps)
                    val foreground = ScreenReceiver.getForegroundApp(context)
                    HeartbeatReporter.send(foreground, true, "android-${Build.MODEL}")
                    Log.i(TAG, "immediate report sent (${apps.size} apps)")
                } catch (e: Exception) {
                    Log.w(TAG, "immediate report failed: ${e.message}")
                }
            }.start()
        }
    }

    companion object {
        private const val TAG = "ScreentimeConnectivity"

        fun registerDynamic(context: Context): ConnectivityReceiver {
            val receiver = ConnectivityReceiver()
            val filter = IntentFilter(ConnectivityManager.CONNECTIVITY_ACTION)
            context.applicationContext.registerReceiver(receiver, filter)
            Log.i(TAG, "dynamic ConnectivityReceiver registered")
            return receiver
        }
    }
}
