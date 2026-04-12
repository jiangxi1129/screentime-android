package top.xixiclaire.screentime

import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.pm.PackageManager
import java.util.Calendar

/**
 * Reads today's foreground app usage via queryEvents().
 *
 * Design:
 * - ACTIVITY_RESUMED = entered foreground, ACTIVITY_PAUSED = left foreground.
 * - ACTIVITY_STOPPED is NOT used for timing — it fires for background services
 *   and system components that were never truly in the foreground, causing
 *   massive over-counting. WeChat mini-programs that skip PAUSED will lose
 *   some time, but that's far better than 500-min ghost entries.
 * - Cross-midnight: if the FIRST event for a package is PAUSED (no prior
 *   RESUMED today), the app was in foreground since before midnight. We count
 *   from midnight to that PAUSED. Only applies once per package, and only for
 *   PAUSED (not STOPPED).
 * - Consecutive RESUMED without PAUSED: close previous session defensively.
 * - Apps still in foreground at query time: count up to now.
 * - Filter out < 60 seconds.
 */
object UsageReader {

    data class AppUsage(val pkg: String, val label: String, val totalSec: Long)

    fun collect(ctx: Context): List<AppUsage> {
        val usm = ctx.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
        val pm = ctx.packageManager

        val cal = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        val start = cal.timeInMillis
        val end = System.currentTimeMillis()

        val totals = HashMap<String, Long>()
        val foregroundSince = HashMap<String, Long>()
        val seen = HashSet<String>()  // packages we've seen any event for

        val events = usm.queryEvents(start, end) ?: return emptyList()
        val ev = UsageEvents.Event()
        while (events.hasNextEvent()) {
            events.getNextEvent(ev)
            val pkg = ev.packageName ?: continue
            val ts = ev.timeStamp
            val firstSeen = pkg !in seen
            seen.add(pkg)

            when (ev.eventType) {
                UsageEvents.Event.ACTIVITY_RESUMED -> {
                    val prev = foregroundSince[pkg]
                    if (prev != null && ts > prev) {
                        totals[pkg] = (totals[pkg] ?: 0L) + (ts - prev)
                    }
                    foregroundSince[pkg] = if (ts < start) start else ts
                }

                UsageEvents.Event.ACTIVITY_PAUSED -> {
                    val from = foregroundSince.remove(pkg)
                    if (from != null && ts > from) {
                        totals[pkg] = (totals[pkg] ?: 0L) + (ts - from)
                    } else if (from == null && firstSeen) {
                        // Cross-midnight: app was in foreground since before 00:00
                        totals[pkg] = (totals[pkg] ?: 0L) + (ts - start)
                    }
                }

                // ACTIVITY_STOPPED: only close an existing tracked session.
                // Do NOT apply midnight fallback — STOPPED fires for tons of
                // background system components that were never in foreground.
                UsageEvents.Event.ACTIVITY_STOPPED -> {
                    val from = foregroundSince.remove(pkg)
                    if (from != null && ts > from) {
                        totals[pkg] = (totals[pkg] ?: 0L) + (ts - from)
                    }
                }
            }
        }

        for ((pkg, from) in foregroundSince) {
            if (end > from) {
                totals[pkg] = (totals[pkg] ?: 0L) + (end - from)
            }
        }

        // NOTE: queryAndAggregateUsageStats was removed in v2.9.
        // It returns bucket-level totals that include cross-bucket overlap,
        // causing 2-3x inflation. Events-based counting is the only reliable method.

        val result = ArrayList<AppUsage>()
        for ((pkg, ms) in totals) {
            val sec = ms / 1000
            if (sec < 60) continue
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
