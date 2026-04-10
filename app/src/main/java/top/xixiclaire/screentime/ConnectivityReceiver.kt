package top.xixiclaire.screentime

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Build
import android.util.Log

/**
 * Monitors network connectivity using the modern NetworkCallback API.
 * When connectivity is restored, re-arms AlarmManager and fires an
 * immediate report + heartbeat.
 *
 * Replaces the old BroadcastReceiver approach which used deprecated
 * activeNetworkInfo (returns null on Android 10+).
 */
class ConnectivityReceiver(private val context: Context) {

    private val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    private var registered = false

    private val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            Log.i(TAG, "network available, re-arming alarm + immediate report")
            if (!MainActivity.hasUsageAccess(context)) return

            AlarmReceiver.scheduleNext(context)

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

        override fun onLost(network: Network) {
            Log.i(TAG, "network lost")
        }
    }

    fun register() {
        if (registered) return
        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()
        cm.registerNetworkCallback(request, callback)
        registered = true
        Log.i(TAG, "NetworkCallback registered")
    }

    fun unregister() {
        if (!registered) return
        try {
            cm.unregisterNetworkCallback(callback)
        } catch (_: Exception) { }
        registered = false
        Log.i(TAG, "NetworkCallback unregistered")
    }

    companion object {
        private const val TAG = "ScreentimeConnectivity"
    }
}
