package top.xixiclaire.screentime

import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.pm.PackageManager
import java.util.Calendar

/**
 * Reads today's foreground app usage from UsageStatsManager.
 * Filters out apps with less than 60 seconds of foreground time.
 */
object UsageReader {

    data class AppUsage(val pkg: String, val label: String, val totalSec: Long)

    fun collect(ctx: Context): List<AppUsage> {
        val usm = ctx.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
        val pm = ctx.packageManager

        // Today midnight → now
        val cal = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        val start = cal.timeInMillis
        val end = System.currentTimeMillis()

        // INTERVAL_BEST gives us the most accurate per-day data
        val stats = usm.queryUsageStats(UsageStatsManager.INTERVAL_BEST, start, end)
            ?: return emptyList()

        // Some devices return duplicates — sum by package name
        val totals = HashMap<String, Long>()
        for (s in stats) {
            if (s.totalTimeInForeground <= 0) continue
            val key = s.packageName ?: continue
            totals[key] = (totals[key] ?: 0L) + s.totalTimeInForeground
        }

        val result = ArrayList<AppUsage>()
        for ((pkg, ms) in totals) {
            val sec = ms / 1000
            if (sec < 60) continue   // filter < 1 minute
            val label = try {
                val ai = pm.getApplicationInfo(pkg, 0)
                pm.getApplicationLabel(ai).toString()
            } catch (e: PackageManager.NameNotFoundException) {
                pkg
            }
            result.add(AppUsage(pkg, label, sec))
        }
        result.sortByDescending { it.totalSec }
        return result
    }
}
