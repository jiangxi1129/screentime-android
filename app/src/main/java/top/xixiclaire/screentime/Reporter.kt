package top.xixiclaire.screentime

import android.content.Context
import android.os.Build
import android.util.Log
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Posts collected app usage to the screentime server.
 * Endpoint expects:
 *   {"source": "...", "date": "YYYY-MM-DD", "apps": {pkg: {label, total_sec}}}
 */
object Reporter {
    /**
     * Endpoint list tried in order. First one wins.
     * - HTTP on a weird port via raw IP avoids SNI-based GFW blocking
     *   (the server must be configured to listen there — see screentime_server.py)
     * - HTTPS via domain is the fallback for when HTTP bypass is unavailable,
     *   but requires a working proxy (Clash) on mainland China networks.
     */
    val ENDPOINTS = listOf(
        "http://129.226.82.136:17080/api/screentime/report",
        "https://xixiclaire.top/api/screentime/report",
    )
    const val SERVER_URL = "https://xixiclaire.top/api/screentime/report" // legacy
    private const val TAG = "ScreentimeReporter"

    /** Last error message, or null on success. Exposed so the UI can show it. */
    @Volatile var lastError: String? = null
        private set

    /** Which endpoint succeeded last time, for display in the UI. */
    @Volatile var lastEndpoint: String? = null
        private set

    fun send(ctx: Context, apps: List<UsageReader.AppUsage>): Boolean {
        if (apps.isEmpty()) {
            Log.i(TAG, "no apps to report")
            lastError = "empty app list"
            return true
        }
        val body = buildBody(apps)
        val errors = mutableListOf<String>()
        for (endpoint in ENDPOINTS) {
            val result = tryPost(endpoint, body)
            if (result == null) {
                lastError = null
                lastEndpoint = endpoint
                return true
            }
            errors += "${shortName(endpoint)}: $result"
        }
        lastError = errors.joinToString(" | ")
        return false
    }

    private fun buildBody(apps: List<UsageReader.AppUsage>): String {
        val today = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
        val appsJson = JSONObject()
        for (a in apps) {
            appsJson.put(a.pkg, JSONObject().apply {
                put("label", a.label)
                put("total_sec", a.totalSec)
            })
        }
        return JSONObject().apply {
            put("source", "android-${Build.MODEL}")
            put("date", today)
            put("apps", appsJson)
        }.toString()
    }

    /** POST [body] to [endpoint]. Returns null on success, error message on failure. */
    private fun tryPost(endpoint: String, body: String): String? {
        return try {
            val url = URL(endpoint)
            val conn = url.openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.connectTimeout = 8_000
            conn.readTimeout = 12_000
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            // Preserve Host header so server's virtual-host routing still works on IP-based URLs
            if (endpoint.contains("129.226.82.136")) {
                conn.setRequestProperty("Host", "xixiclaire.top")
            }
            conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            val code = conn.responseCode
            val resp = (if (code in 200..299) conn.inputStream else conn.errorStream)
                ?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: ""
            Log.i(TAG, "POST $endpoint → $code $resp")
            conn.disconnect()
            if (code in 200..299) null else "HTTP $code"
        } catch (e: Exception) {
            Log.e(TAG, "POST $endpoint failed", e)
            "${e.javaClass.simpleName}: ${e.message?.take(80) ?: ""}"
        }
    }

    private fun shortName(endpoint: String): String = when {
        endpoint.startsWith("http://") && endpoint.contains("17080") -> "http17080"
        endpoint.startsWith("https://") -> "https"
        else -> endpoint.take(30)
    }
}
