package com.example.focusguard

import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import java.util.Calendar
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * Computes, at the moment a sitting starts, the numbers the risk model needs.
 * The names and meaning match RiskModel.FEATURES and train_risk_model.py.
 * Only the PAST is used (finished app usage today), never the sitting that is just starting.
 */
object LiveFeatures {
    private const val GAP_MS = 2 * 60_000L
    private val trackableCache = HashMap<String, Boolean>()

    private class Interval(val pkg: String, val start: Long, val end: Long)
    private class Sitting(val pkg: String, val start: Long, var end: Long)

    fun compute(context: Context, usage: UsageRepository, pkg: String): Map<String, Double> {
        val now = System.currentTimeMillis()
        val cal = Calendar.getInstance()
        val hour = cal.get(Calendar.HOUR_OF_DAY)
        val dow = cal.get(Calendar.DAY_OF_WEEK)          // 1 = Sunday ... 7 = Saturday
        cal.set(Calendar.HOUR_OF_DAY, 0)
        cal.set(Calendar.MINUTE, 0)
        cal.set(Calendar.SECOND, 0)
        cal.set(Calendar.MILLISECOND, 0)
        val dayStart = cal.timeInMillis

        val intervals = finishedIntervals(context, dayStart, now)

        // Same app, gap under 2 minutes = one sitting.
        val open = HashMap<String, Sitting>()
        val sittings = ArrayList<Sitting>()
        for (iv in intervals) {
            val s = open[iv.pkg]
            if (s != null && iv.start - s.end <= GAP_MS) {
                if (iv.end > s.end) s.end = iv.end
            } else {
                val fresh = Sitting(iv.pkg, iv.start, iv.end)
                sittings.add(fresh)
                open[iv.pkg] = fresh
            }
        }

        val sessionsToday = sittings.count { usage.categoryOf(it.pkg) != AppCategory.PRODUCTIVE }
        val lastEnd = intervals.maxOfOrNull { it.end }
        val minSinceLast = if (lastEnd == null) 240.0 else minOf(240.0, (now - lastEnd) / 60_000.0)
        val appsLast10 = intervals.filter { it.end >= now - 10 * 60_000L }.map { it.pkg }.distinct().size
        val distractingMs = intervals
            .filter { usage.categoryOf(it.pkg) == AppCategory.DISTRACTING }
            .sumOf { it.end - it.start }

        return mapOf(
            "hour_sin" to sin(2 * PI * hour / 24),
            "hour_cos" to cos(2 * PI * hour / 24),
            "dow_sin" to sin(2 * PI * dow / 7),
            "dow_cos" to cos(2 * PI * dow / 7),
            "is_distracting" to if (usage.categoryOf(pkg) == AppCategory.DISTRACTING) 1.0 else 0.0,
            "min_since_last_session" to minSinceLast,
            "apps_last_10min" to appsLast10.toDouble(),
            "sessions_so_far_today" to sessionsToday.toDouble(),
            "distracting_min_so_far_today" to distractingMs / 60_000.0
        )
    }

    /** Only intervals that already ended, so the app that is opening right now is not counted. */
    private fun finishedIntervals(context: Context, from: Long, to: Long): List<Interval> {
        val usm = context.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
        val events = usm.queryEvents(from, to)
        val e = UsageEvents.Event()
        val resumed = HashMap<String, Long>()
        val out = ArrayList<Interval>()
        while (events.hasNextEvent()) {
            events.getNextEvent(e)
            val key = e.packageName + "/" + (e.className ?: "")
            when (e.eventType) {
                UsageEvents.Event.ACTIVITY_RESUMED -> resumed[key] = e.timeStamp
                UsageEvents.Event.ACTIVITY_PAUSED,
                UsageEvents.Event.ACTIVITY_STOPPED -> {
                    val start = resumed.remove(key)
                    if (start != null && e.timeStamp - start >= 1_000 && isTrackable(context, e.packageName)) {
                        out.add(Interval(e.packageName, start, e.timeStamp))
                    }
                }
            }
        }
        out.sortBy { it.start }
        return out
    }

    private fun isTrackable(context: Context, pkg: String): Boolean =
        trackableCache.getOrPut(pkg) {
            pkg != context.packageName && context.packageManager.getLaunchIntentForPackage(pkg) != null
        }
}
