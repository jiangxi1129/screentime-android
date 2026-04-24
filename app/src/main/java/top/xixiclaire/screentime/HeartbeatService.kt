package top.xixiclaire.screentime

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.content.ContextCompat
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Foreground service that sends a heartbeat every HEARTBEAT_INTERVAL_MS.
 *
 * Why a foreground service? On vivo/OPPO/Xiaomi even whitelisted background
 * AlarmManager chains get throttled. A foreground service with a persistent
 * notification is the only reliable way to keep a ~60s heartbeat alive.
 *
 * This is heartbeat-only (lightweight). The heavier /report call continues
 * to run via AlarmReceiver every 2 minutes.
 */
class HeartbeatService : Service() {

    private val running = AtomicBoolean(false)
    private var worker: Thread? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (running.compareAndSet(false, true)) {
            startForegroundCompat()
            worker = Thread(::loop, "screentime-heartbeat").apply { isDaemon = true; start() }
            Log.i(TAG, "service started")
        }
        return START_STICKY
    }

    /**
     * Start foreground with a service type that matches what we *actually*
     * have permission for. Android 14 (API 34) throws SecurityException if
     * the declared type includes `location` but ACCESS_FINE_LOCATION is not
     * granted — which is exactly what happens on first launch before the
     * user taps "授权位置权限". So we only claim the location type when the
     * runtime permission is already granted.
     */
    private fun startForegroundCompat() {
        val notif = buildNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            var type = ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            val hasFine = ContextCompat.checkSelfPermission(
                this, Manifest.permission.ACCESS_FINE_LOCATION
            ) == PackageManager.PERMISSION_GRANTED
            val hasCoarse = ContextCompat.checkSelfPermission(
                this, Manifest.permission.ACCESS_COARSE_LOCATION
            ) == PackageManager.PERMISSION_GRANTED
            if (hasFine || hasCoarse) {
                type = type or ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
            }
            try {
                startForeground(NOTIF_ID, notif, type)
            } catch (e: Exception) {
                // Last resort: fall back to type-less call so the heartbeat at
                // least survives even if the compat call somehow rejects us
                Log.w(TAG, "typed startForeground failed (${e.message}); falling back")
                startForeground(NOTIF_ID, notif)
            }
        } else {
            startForeground(NOTIF_ID, notif)
        }
    }

    override fun onDestroy() {
        running.set(false)
        worker?.interrupt()
        Log.i(TAG, "service destroyed")
        super.onDestroy()
    }

    private fun loop() {
        var tick = 0
        while (running.get()) {
            try {
                sendOne()
            } catch (t: Throwable) {
                Log.w(TAG, "heartbeat iter failed: ${t.message}")
            }
            // Location lives on a coarser cadence — every LOCATION_EVERY_N_TICKS
            // heartbeats (~5 min) to keep GPS duty-cycle low. Runs on this
            // worker thread so it does not interfere with the heartbeat cadence
            // if it takes its 10-s fetch timeout.
            if (tick % LOCATION_EVERY_N_TICKS == 0) {
                try {
                    LocationReporter.fetchAndSend(this, trigger = "heartbeat")
                } catch (t: Throwable) {
                    Log.w(TAG, "location iter failed: ${t.message}")
                }
            }
            tick++
            try {
                Thread.sleep(HEARTBEAT_INTERVAL_MS)
            } catch (_: InterruptedException) {
                break
            }
        }
    }

    private fun sendOne() {
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        val screenOn = pm.isInteractive
        val foregroundApp = if (screenOn) ScreenReceiver.getForegroundApp(this) else null
        val source = "android-${Build.MODEL}-fg"
        HeartbeatReporter.send(foregroundApp, screenOn, source)
    }

    private fun buildNotification(): Notification {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel(
                CHANNEL_ID,
                "Screentime 追踪",
                NotificationManager.IMPORTANCE_MIN,
            ).apply {
                description = "后台心跳，保持统计实时"
                setShowBadge(false)
            }
            nm.createNotificationChannel(ch)
        }

        val pi = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }
        return builder
            .setContentTitle("Screentime 正在追踪")
            .setContentText("每分钟一次心跳")
            .setSmallIcon(android.R.drawable.ic_menu_recent_history)
            .setOngoing(true)
            .setContentIntent(pi)
            .setPriority(Notification.PRIORITY_MIN)
            .build()
    }

    companion object {
        private const val TAG = "ScreentimeHBService"
        const val CHANNEL_ID = "screentime_heartbeat"
        const val NOTIF_ID = 9102
        const val HEARTBEAT_INTERVAL_MS = 60_000L  // 1 min
        const val LOCATION_EVERY_N_TICKS = 5       // 5 min between location fetches

        fun start(ctx: Context) {
            val intent = Intent(ctx, HeartbeatService::class.java)
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    ctx.startForegroundService(intent)
                } else {
                    ctx.startService(intent)
                }
            } catch (e: Exception) {
                Log.w(TAG, "start failed: ${e.message}")
            }
        }

        fun stop(ctx: Context) {
            try {
                ctx.stopService(Intent(ctx, HeartbeatService::class.java))
            } catch (_: Exception) { }
        }
    }
}
