package top.xixiclaire.screentime

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.util.Log

/**
 * Re-arms the AlarmManager schedule whenever connectivity changes.
 *
 * Why this is needed: on vivo/OPPO, toggling VPN (Clash) or WiFi triggers a
 * connectivity change that can cause the previous alarm's PendingIntent to
 * become stale or the process to be killed. By re-scheduling on every
 * CONNECTIVITY_CHANGE event, we ensure reporting resumes automatically after
 * network recovery without the user having to re-open the app.
 */
class ConnectivityReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (!MainActivity.hasUsageAccess(context)) return

        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val active = cm.activeNetworkInfo
        val connected = active != null && active.isConnected

        if (connected) {
            Log.i(TAG, "network connected, re-arming alarm")
            AlarmReceiver.scheduleNext(context)
        }
    }

    companion object {
        private const val TAG = "ScreentimeConnectivity"
    }
}
