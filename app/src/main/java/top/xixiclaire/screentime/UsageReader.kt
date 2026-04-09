package top.xixiclaire.screentime

import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.pm.PackageManager
import java.util.Calendar

/**
 * Reads today's foreground app usage from UsageStatsManager via queryEvents().
 *
 * Key design decisions:
 * - We track ACTIVITY_RESUMED as "entered foreground" and ACTIVITY_PAUSED or
 *   ACTIVITY_STOPPED as "left foreground". WeChat's floating windows and
 *   mini-programs often skip PAUSED and go straight to STOPPED, so we must
 *   handle both.
 * - If we see two consecutive RESUMED events for the same package without an
 *   intervening PAUSED/STOPPED, we close the first session at the second
 *   RESUMED timestamp (defensive, handles edge cases).
 * - Anything still in foreground at query time counts up to now.
 *
 * Filters out apps with less than 60 seconds of foreground time.
 */
object UsageReader {

    data class AppUsage(val pkg: String, val label: String, val totalSec: Long)

    fun collect(ctx: Context): List<AppUsage> {
        val usm = ctx.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
        val pm = ctx.packageManager

        // Today midnight (device timezone) -> now
        val cal = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        val start = cal.timeInMillis
        val end = System.currentTimeMillis()

        val totals = HashMap<String, Long>()          // package -> ms accumulated today
        val foregroundSince = HashMap<String, Long>()  // package -> ms when it entered foreground

        val events = usm.queryEvents(start, end) ?: return emptyList()
        val ev = UsageEvents.Event()
        while (events.hasNextEvent()) {
            events.getNextEvent(ev)
            val pkg = ev.packageName ?: continue
            val ts = ev.timeStamp

            when (ev.eventType) {
                // Entered foreground
                UsageEvents.Event.ACTIVITY_RESUMED -> {
                    // If already in foreground (consecutive RESUMED without PAUSED),
                    // close the previous session first
                    val prev = foregroundSince[pkg]
                    if (prev != null && ts > prev) {
                        totals[pkg] = (totals[pkg] ?: 0L) + (ts - prev)
                    }
                    foregroundSince[pkg] = if (ts < start) start else ts
                }

                // Left foreground — PAUSED is the standard signal
                UsageEvents.Event.ACTIVITY_PAUSED -> {
                    val from = foregroundSince.remove(pkg)
                    if (from != null && ts > from) {
                        totals[pkg] = (totals[pkg] ?: 0L) + (ts - from)
                    }
                }

                // Left foreground — STOPPED is the fallback signal.
                // WeChat floating windows, mini-programs, and vivo split-screen
                // often skip PAUSED and emit STOPPED directly.
                UsageEvents.Event.ACTIVITY_STOPPED -> {
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
