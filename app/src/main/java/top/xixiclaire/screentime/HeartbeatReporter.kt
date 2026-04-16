package top.xixiclaire.screentime

import android.util.Log
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Sends a lightweight heartbeat POST to the server with current phone state.
 * Used for real-time status: current app, screen on/off.
 *
 * Tries HTTP 17080 (raw IP, GFW bypass) first, then HTTPS fallback.
 */
object HeartbeatReporter {

    private const val TAG = "ScreentimeHeartbeat"

    private val ENDPOINTS = listOf(
        "http://129.226.82.136:17080/api/screentime/heartbeat",
        "https://xixiclaire.top/api/screentime/heartbeat",
    )

    fun send(currentApp: String?, screenOn: Boolean, source: String): Boolean {
        val body = JSONObject().apply {
            put("current_app", currentApp ?: JSONObject.NULL)
            put("screen_on", screenOn)
            put("source", source)
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
                    Log.d(TAG, "heartbeat ok via $endpoint: app=$currentApp screen=$screenOn")
                    return true
                }
            } catch (e: Exception) {
                Log.w(TAG, "heartbeat failed on $endpoint: ${e.message}")
            }
        }
        Log.e(TAG, "heartbeat failed on all endpoints")
        return false
    }
}
