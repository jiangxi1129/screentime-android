package top.xixiclaire.screentime

import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.pm.PackageManager
import java.util.Calendar

/**
 * Reads today's foreground app usage from UsageStatsManager.
 *
 * IMPORTANT: queryUsageStats() returns daily buckets where totalTimeInForeground
 * is the FULL bucket value, even if the bucket starts before our query range.
 * This caused yesterday's usage to be counted as today's. We instead use
 * queryEvents() to manually compute foreground intervals strictly within
 * [todayMidnight, now].
 *
 * Filters out apps with less than 60 seconds of foreground time.
 */
object UsageReader {

    data class AppUsage(val pkg: String, val label: String, val totalSec: Long)

    fun collect(ctx: Context): List<AppUsage> {
        val usm = ctx.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
        val pm = ctx.packageManager

        // Today midnight (device timezone) → now
        val cal = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        val start = cal.timeInMillis
        val end = System.currentTimeMillis()

        val totals = HashMap<String, Long>()      // package → ms accumulated today
        val foregroundSince = HashMap<String, Long>() // package → ms when it entered foreground

        val events = usm.queryEvents(start, end) ?: return emptyList()
        val ev = UsageEvents.Event()
        while (events.hasNextEvent()) {
            events.getNextEvent(ev)
            val pkg = ev.packageName ?: continue
            val ts = ev.timeStamp
            when (ev.eventType) {
                UsageEvents.Event.ACTIVITY_RESUMED, // = MOVE_TO_FOREGROUND on older APIs
                UsageEvents.Event.MOVE_TO_FOREGROUND -> {
                    // Clamp to start in case event is exactly at boundary
                    foregroundSince[pkg] = if (ts < start) start else ts
                }
                UsageEvents.Event.ACTIVITY_PAUSED, // = MOVE_TO_BACKGROUND on older APIs
                UsageEvents.Event.MOVE_TO_BACKGROUND -> {
                    val from = foregroundSince.remove(pkg)
                    if (from != null && ts > from) {
                        totals[pkg] = (totals[pkg] ?: 0L) + (ts - from)
                    }
                }
            }
        }
        // Anything still in foreground at the end of the window: count up to now
        for ((pkg, from) in foregroundSince) {
            if (end > from) {
                totals[pkg] = (totals[pkg] ?: 0L) + (end - from)
            }
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
