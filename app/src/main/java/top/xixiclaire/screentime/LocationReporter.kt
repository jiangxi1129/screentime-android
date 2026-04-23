package top.xixiclaire.screentime

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.BatteryManager
import android.os.Build
import android.os.Looper
import android.util.Log
import androidx.core.content.ContextCompat
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Fetches a GPS/network location + battery level and POSTs them to the
 * /api/screentime/location endpoint. Blocking (use off the main thread).
 *
 * Strategy:
 *   1. Try getLastKnownLocation from GPS/Network/Passive. If any fix is
 *      less than 5 min old, use it (fast, zero GPS power).
 *   2. Otherwise requestLocationUpdates with a 10 s timeout.
 *
 * Requires ACCESS_FINE_LOCATION or ACCESS_COARSE_LOCATION. If missing,
 * returns false silently — MainActivity is responsible for requesting.
 */
object LocationReporter {

    private const val TAG = "ScreentimeLocation"
    private const val FETCH_TIMEOUT_MS = 10_000L
    private const val FRESH_THRESHOLD_MS = 5L * 60_000  // 5 min

    private val ENDPOINTS = listOf(
        "http://129.226.82.136:17080/api/screentime/location",
        "https://xixiclaire.top/api/screentime/location",
    )

    /** Blocking. Returns true if the POST landed. */
    fun fetchAndSend(ctx: Context, trigger: String = "heartbeat"): Boolean {
        if (!hasPermission(ctx)) {
            Log.d(TAG, "skip: no location permission")
            return false
        }
        val loc = fetchLocation(ctx)
        if (loc == null) {
            Log.w(TAG, "no location available")
            return false
        }
        val battery = readBatteryLevel(ctx)
        val charging = readCharging(ctx)
        return post(loc, battery, charging, trigger)
    }

    private fun hasPermission(ctx: Context): Boolean {
        val fine = ContextCompat.checkSelfPermission(
            ctx, Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        val coarse = ContextCompat.checkSelfPermission(
            ctx, Manifest.permission.ACCESS_COARSE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        return fine || coarse
    }

    private fun fetchLocation(ctx: Context): Location? {
        val lm = ctx.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
            ?: return null

        val now = System.currentTimeMillis()
        val providers = listOf(
            LocationManager.GPS_PROVIDER,
            LocationManager.NETWORK_PROVIDER,
            LocationManager.PASSIVE_PROVIDER,
        )

        // 1) Fast path: any recent cached fix
        var best: Location? = null
        for (p in providers) {
            val last = try { lm.getLastKnownLocation(p) } catch (_: SecurityException) { null }
            if (last != null && (now - last.time) < FRESH_THRESHOLD_MS) {
                if (best == null || last.accuracy < best.accuracy) best = last
            }
        }
        if (best != null) return best

        // 2) Slow path: request a single update
        return requestSingleLocation(lm)
    }

    private fun requestSingleLocation(lm: LocationManager): Location? {
        val slot = arrayOfNulls<Location>(1)
        val latch = CountDownLatch(1)
        val listener = object : LocationListener {
            override fun onLocationChanged(location: Location) {
                slot[0] = location
                latch.countDown()
            }
            // No-op for deprecated overloads on older APIs
            override fun onProviderEnabled(provider: String) {}
            override fun onProviderDisabled(provider: String) {}
            override fun onStatusChanged(provider: String?, status: Int, extras: android.os.Bundle?) {}
        }

        val provider = when {
            lm.isProviderEnabled(LocationManager.GPS_PROVIDER) -> LocationManager.GPS_PROVIDER
            lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER) -> LocationManager.NETWORK_PROVIDER
            else -> return null
        }

        try {
            lm.requestLocationUpdates(provider, 0L, 0f, listener, Looper.getMainLooper())
        } catch (e: SecurityException) {
            Log.w(TAG, "requestLocationUpdates denied: ${e.message}")
            return null
        } catch (e: Exception) {
            Log.w(TAG, "requestLocationUpdates failed: ${e.message}")
            return null
        }

        try {
            latch.await(FETCH_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        } catch (_: InterruptedException) { /* fall through */ }

        try { lm.removeUpdates(listener) } catch (_: Exception) { }
        return slot[0]
    }

    private fun readBatteryLevel(ctx: Context): Int {
        val bm = ctx.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager ?: return -1
        return try {
            bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        } catch (_: Exception) { -1 }
    }

    private fun readCharging(ctx: Context): Boolean {
        val bm = ctx.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager ?: return false
        val status = try {
            bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_STATUS)
        } catch (_: Exception) { return false }
        return status == BatteryManager.BATTERY_STATUS_CHARGING ||
            status == BatteryManager.BATTERY_STATUS_FULL
    }

    private fun post(loc: Location, battery: Int, charging: Boolean, trigger: String): Boolean {
        val body = JSONObject().apply {
            put("latitude", loc.latitude)
            put("longitude", loc.longitude)
            put("accuracy_m", loc.accuracy.toDouble())
            put("timestamp_ms", loc.time)
            put("provider", loc.provider ?: JSONObject.NULL)
            put("battery_level", if (battery in 0..100) battery else JSONObject.NULL)
            put("charging", charging)
            put("trigger", trigger)
            put("source", "android-${Build.MODEL}")
        }.toString()

        for (endpoint in ENDPOINTS) {
            try {
                val conn = URL(endpoint).openConnection() as HttpURLConnection
                conn.requestMethod = "POST"
                conn.connectTimeout = 8_000
                conn.readTimeout = 12_000
                conn.doOutput = true
                conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                if (endpoint.contains("129.226.82.136")) {
                    conn.setRequestProperty("Host", "xixiclaire.top")
                }
                conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
                val code = conn.responseCode
                conn.disconnect()
                if (code in 200..299) {
                    Log.d(TAG, "location ok via $endpoint: lat=${loc.latitude} lng=${loc.longitude} batt=$battery trig=$trigger")
                    return true
                } else {
                    Log.w(TAG, "location http $code on $endpoint")
                }
            } catch (e: Exception) {
                Log.w(TAG, "location failed on $endpoint: ${e.message}")
            }
        }
        Log.e(TAG, "location failed on all endpoints")
        return false
    }
}
