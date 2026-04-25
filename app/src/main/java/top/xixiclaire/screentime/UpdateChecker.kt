package top.xixiclaire.screentime

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.util.Log
import androidx.core.content.FileProvider
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * Checks GitHub Releases for a newer APK, downloads it, and posts a
 * notification that — when tapped — opens the system installer.
 *
 * Android does NOT allow regular apps to silently install other apps.
 * This is the shortest legal flow: one extra tap on a notification, one
 * tap on the system install prompt.
 *
 * Rate-limited to once every CHECK_INTERVAL_MS (6h) via SharedPreferences,
 * so it's safe to call from every AlarmReceiver tick.
 */
object UpdateChecker {

    private const val TAG = "ScreentimeUpdate"
    private const val REPO = "jiangxi1129/screentime-android"
    private const val LATEST_URL = "https://api.github.com/repos/$REPO/releases/latest"

    private const val PREFS = "screentime_update"
    private const val KEY_LAST_CHECK = "last_check_ms"
    private const val KEY_NOTIFIED_VERSION = "notified_version"

    const val CHANNEL_ID = "screentime_update"
    private const val NOTIF_ID = 9103
    private const val CHECK_INTERVAL_MS = 1L * 3600 * 1000  // 1h (was 6h)

    /** Entry point: call from AlarmReceiver / MainActivity. Safe to call often. */
    fun checkAndMaybeNotify(ctx: Context, force: Boolean = false) {
        val prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val last = prefs.getLong(KEY_LAST_CHECK, 0L)
        if (!force && System.currentTimeMillis() - last < CHECK_INTERVAL_MS) {
            return
        }

        Thread {
            try {
                val info = fetchLatest() ?: return@Thread
                prefs.edit().putLong(KEY_LAST_CHECK, System.currentTimeMillis()).apply()

                val currentVc = currentVersionCode(ctx)
                Log.i(TAG, "latest=${info.versionCode} current=$currentVc name=${info.tagName}")
                if (info.versionCode <= currentVc) return@Thread

                // Don't re-download/re-notify for the same version
                val alreadyNotified = prefs.getInt(KEY_NOTIFIED_VERSION, 0)
                if (alreadyNotified == info.versionCode) {
                    // Re-show notification if the downloaded APK still exists
                    val cached = apkFile(ctx, info.versionCode)
                    if (cached.exists() && cached.length() > 0) {
                        showNotification(ctx, cached, info)
                    }
                    return@Thread
                }

                val apk = downloadApk(ctx, info) ?: return@Thread
                prefs.edit().putInt(KEY_NOTIFIED_VERSION, info.versionCode).apply()
                showNotification(ctx, apk, info)
            } catch (t: Throwable) {
                Log.w(TAG, "check failed: ${t.message}")
            }
        }.start()
    }

    private data class LatestInfo(
        val tagName: String,
        val versionCode: Int,
        val apkUrl: String,
        val apkName: String,
    )

    private fun fetchLatest(): LatestInfo? {
        val conn = URL(LATEST_URL).openConnection() as HttpURLConnection
        conn.connectTimeout = 8_000
        conn.readTimeout = 15_000
        conn.setRequestProperty("Accept", "application/vnd.github+json")
        conn.setRequestProperty("User-Agent", "screentime-android")
        try {
            if (conn.responseCode !in 200..299) return null
            val body = conn.inputStream.bufferedReader().use { it.readText() }
            val json = JSONObject(body)
            val tag = json.optString("tag_name", "")
            // Tag format: "build-N"  → extract N as versionCode
            val vc = Regex("""build-(\d+)""").find(tag)?.groupValues?.get(1)?.toIntOrNull()
                ?: return null
            val assets = json.optJSONArray("assets") ?: return null
            for (i in 0 until assets.length()) {
                val a = assets.getJSONObject(i)
                val name = a.optString("name", "")
                if (name.endsWith(".apk")) {
                    return LatestInfo(
                        tagName = tag,
                        versionCode = vc,
                        apkUrl = a.optString("browser_download_url", ""),
                        apkName = name,
                    )
                }
            }
            return null
        } finally {
            conn.disconnect()
        }
    }

    private fun currentVersionCode(ctx: Context): Int {
        return try {
            val pi = ctx.packageManager.getPackageInfo(ctx.packageName, 0)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                pi.longVersionCode.toInt()
            } else {
                @Suppress("DEPRECATION")
                pi.versionCode
            }
        } catch (_: PackageManager.NameNotFoundException) {
            0
        }
    }

    private fun apkFile(ctx: Context, versionCode: Int): File {
        val dir = File(ctx.cacheDir, "updates")
        if (!dir.exists()) dir.mkdirs()
        return File(dir, "screentime-$versionCode.apk")
    }

    private fun downloadApk(ctx: Context, info: LatestInfo): File? {
        val out = apkFile(ctx, info.versionCode)
        // Clean older cached APKs so cache doesn't grow unbounded
        out.parentFile?.listFiles()?.forEach {
            if (it != out) it.delete()
        }

        val conn = URL(info.apkUrl).openConnection() as HttpURLConnection
        conn.connectTimeout = 8_000
        conn.readTimeout = 60_000
        conn.setRequestProperty("User-Agent", "screentime-android")
        conn.instanceFollowRedirects = true
        try {
            if (conn.responseCode !in 200..299) {
                Log.w(TAG, "download http ${conn.responseCode}")
                return null
            }
            FileOutputStream(out).use { fos ->
                conn.inputStream.use { it.copyTo(fos) }
            }
            Log.i(TAG, "downloaded ${out.length()} bytes -> ${out.absolutePath}")
            return out
        } finally {
            conn.disconnect()
        }
    }

    private fun showNotification(ctx: Context, apk: File, info: LatestInfo) {
        val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel(
                CHANNEL_ID,
                "Screentime 更新",
                NotificationManager.IMPORTANCE_DEFAULT,
            ).apply { description = "发现新版本时提醒" }
            nm.createNotificationChannel(ch)
        }

        val uri = FileProvider.getUriForFile(
            ctx,
            "${ctx.packageName}.updater.fileprovider",
            apk,
        )
        val installIntent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION
        }
        val pi = PendingIntent.getActivity(
            ctx, 0, installIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(ctx, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(ctx)
        }
        val notif = builder
            .setContentTitle("Screentime 新版本 ${info.tagName}")
            .setContentText("点一下开始安装")
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentIntent(pi)
            .setAutoCancel(true)
            .build()

        nm.notify(NOTIF_ID, notif)
    }
}
