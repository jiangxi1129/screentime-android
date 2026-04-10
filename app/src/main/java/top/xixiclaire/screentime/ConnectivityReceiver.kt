package top.xixiclaire.screentime

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Build
import android.util.Log

/**
 * Global singleton that monitors network connectivity using NetworkCallback.
 * When connectivity is restored (e.g. Clash VPN reconnected), fires an
 * immediate report + heartbeat.
 *
 * Registered from both MainActivity (on open) and AlarmReceiver (every 5 min),
 * so it survives Activity being killed by vivo.
 */
object ConnectivityReceiver {

    private const val TAG = "ScreentimeConnectivity"
    @Volatile private var registered = false
    private var callback: ConnectivityManager.NetworkCallback? = null

    /**
     * Ensure the NetworkCallback is registered. Safe to call multiple times —
     * only the first call actually registers.
     */
    fun ensureRegistered(context: Context) {
        if (registered) return
        synchronized(this) {
            if (registered) return
            try {
                val cm = context.applicationContext
                    .getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
                val cb = object : ConnectivityManager.NetworkCallback() {
                    override fun onAvailable(network: Network) {
                        Log.i(TAG, "network available, immediate report")
                        val ctx = context.applicationContext
                        if (!MainActivity.hasUsageAccess(ctx)) return

                        AlarmReceiver.scheduleNext(ctx)

                        Thread {
                            try {
                                val apps = UsageReader.collect(ctx)
                                Reporter.send(ctx, apps)
                                val foreground = ScreenReceiver.getForegroundApp(ctx)
                                HeartbeatReporter.send(
                                    foreground, true,
                                    "android-${Build.MODEL}"
                                )
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
                val request = NetworkRequest.Builder()
                    .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                    .build()
                cm.registerNetworkCallback(request, cb)
                callback = cb
                registered = true
                Log.i(TAG, "NetworkCallback registered (global singleton)")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to register NetworkCallback: ${e.message}")
            }
        }
    }

    fun unregister(context: Context) {
        if (!registered) return
        synchronized(this) {
            if (!registered) return
            try {
                val cm = context.applicationContext
                    .getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
                callback?.let { cm.unregisterNetworkCallback(it) }
            } catch (_: Exception) { }
            callback = null
            registered = false
            Log.i(TAG, "NetworkCallback unregistered")
        }
    }
}
