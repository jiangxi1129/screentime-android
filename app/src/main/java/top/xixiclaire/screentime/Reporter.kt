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
    const val SERVER_URL = "https://xixiclaire.top/api/screentime/report"
    private const val TAG = "ScreentimeReporter"

    fun send(ctx: Context, apps: List<UsageReader.AppUsage>): Boolean {
        if (apps.isEmpty()) {
            Log.i(TAG, "no apps to report")
            return true
        }
        return try {
            val today = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
            val appsJson = JSONObject()
            for (a in apps) {
                appsJson.put(a.pkg, JSONObject().apply {
                    put("label", a.label)
                    put("total_sec", a.totalSec)
                })
            }
            val body = JSONObject().apply {
                put("source", "android-${Build.MODEL}")
                put("date", today)
                put("apps", appsJson)
            }.toString()

            val url = URL(SERVER_URL)
            val conn = url.openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.connectTimeout = 10_000
            conn.readTimeout = 15_000
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }

            val code = conn.responseCode
            val resp = (if (code in 200..299) conn.inputStream else conn.errorStream)
                ?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: ""
            Log.i(TAG, "POST /report → $code $resp")
            conn.disconnect()
            code in 200..299
        } catch (e: Exception) {
            Log.e(TAG, "report failed", e)
            false
        }
    }
}
