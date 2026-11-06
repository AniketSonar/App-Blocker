package com.example.focusguard

import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

// ---- small data classes (all names start with "Ins" so they never clash with other files) ----------
class InsSession(val pkg: String, val category: AppCategory, val startMs: Long, val endMs: Long) {
    val minutes: Float get() = (endMs - startMs) / 60_000f
}

class InsDay(
    val dayStartMs: Long,
    val totalMin: Int,
    val productiveMin: Int,
    val neutralMin: Int,
    val distractingMin: Int,
    val distractingSessions: Int,
    val longestMin: Int,
    val longestPkg: String,
    val lateNightMin: Int,
    val hourly: IntArray           // distracting minutes in each hour of the day (24 values)
) {
    fun label(): String = SimpleDateFormat("EEE", Locale.getDefault()).format(Date(dayStartMs))

    fun toCsv(): String = (
        listOf<Any>(
            dayStartMs, totalMin, productiveMin, neutralMin, distractingMin,
            distractingSessions, longestMin, longestPkg.ifEmpty { "none" }, lateNightMin
        ) + hourly.toList()
        ).joinToString(",")

    companion object {
        fun header(): String =
            (listOf(
                "day_start_ms", "total_min", "productive_min", "neutral_min", "distracting_min",
                "distracting_sessions", "longest_min", "longest_pkg", "late_night_min"
            ) + (0..23).map { "h" + it.toString().padStart(2, '0') }).joinToString(",")

        fun fromCsv(line: String): InsDay? {
            val p = line.split(",")
            if (p.size < 33) return null
            return try {
                InsDay(
                    p[0].toLong(), p[1].toInt(), p[2].toInt(), p[3].toInt(), p[4].toInt(),
                    p[5].toInt(), p[6].toInt(), p[7], p[8].toInt(),
                    IntArray(24) { p[9 + it].toInt() }
                )
            } catch (e: Exception) {
                null
            }
        }
    }
}

class InsBar(val label: String, val minutes: Int)
class InsSessionInfo(val appName: String, val minutes: Int)

fun insHourLabel(h: Int): String {
    val h12 = if (h % 12 == 0) 12 else h % 12
    return "$h12 " + if (h < 12) "am" else "pm"
}

// ---- goal: starts as simple statistics, can be replaced by a learned model later -------------------------------
class InsGoalState(val targetMin: Int, val reason: String, val calibrating: Boolean, val daysOfData: Int)

private object InsGoal {
    private const val PREFS = "focusguard_ins_goal"
    private const val WEEK_MS = 7L * 24 * 60 * 60 * 1000
    private const val MIN_DAYS = 3
    private const val DEFAULT_GOAL_MIN = 120
    private const val FLOOR_MIN = 30

    fun refresh(context: Context, completedDays: List<InsDay>): InsGoalState {
        val history = completedDays.filter { it.totalMin > 0 }.map { it.distractingMin }
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val current = prefs.getInt("goal_min", -1).takeIf { it >= 0 }
        val setAt = prefs.getLong("set_at", 0L)
        val now = System.currentTimeMillis()

        // A goal lasts a week, so it does not change under your feet.
        if (current != null && now - setAt < WEEK_MS) {
            return InsGoalState(current, prefs.getString("reason", "").orEmpty(), false, history.size)
        }

        if (history.size < MIN_DAYS) {
            return InsGoalState(
                current ?: DEFAULT_GOAL_MIN,
                "Starter goal. FocusGuard needs $MIN_DAYS days of data to personalise it (it has ${history.size}).",
                true, history.size
            )
        }

        val baseline = ewma(clip(history.takeLast(14)))
        val weekAvg = history.takeLast(7).average()
        val target: Int
        val reason: String
        if (current == null) {
            target = round5(maxOf(FLOOR_MIN.toDouble(), baseline * 0.90))
            reason = "Your first goal is 10% below your recent average of ${baseline.roundToInt()} min a day."
        } else if (weekAvg <= current) {
            target = round5(maxOf(FLOOR_MIN.toDouble(), weekAvg * 0.90))
            reason = "You met last week's goal, so it tightens by 10%."
        } else if (weekAvg <= current * 1.15) {
            target = current
            reason = "You were close last week, so the goal stays the same."
        } else {
            target = round5(maxOf(FLOOR_MIN.toDouble(), minOf(weekAvg * 0.95, current * 1.5)))
            reason = "Last week was tough, so the goal is eased to something reachable."
        }

        prefs.edit().putInt("goal_min", target).putLong("set_at", now).putString("reason", reason).apply()
        return InsGoalState(target, reason, false, history.size)
    }

    /** One crazy day should not distort the baseline: cap values at 2x the median. */
    private fun clip(values: List<Int>): List<Double> {
        val median = values.sorted()[values.size / 2].toDouble()
        val cap = if (median > 0) median * 2 else Double.MAX_VALUE
        return values.map { minOf(it.toDouble(), cap) }
    }

    private fun ewma(values: List<Double>, alpha: Double = 0.35): Double {
        var s = values.first()
        for (v in values.drop(1)) s = alpha * v + (1 - alpha) * s
        return s
    }

    private fun round5(x: Double): Int = (x / 5.0).roundToInt() * 5
}

// ---- the engine --------------------------------------------------------------------------------------------------------
object InsightsEngine {
    private const val GAP_MS = 2 * 60_000L
    private const val DAILY_FILE = "insights_daily.csv"
    const val EVENTS_FILE = "events.csv"
    const val DAILY_EXPORT_FILE = DAILY_FILE

    private class Interval(val pkg: String, val start: Long, val end: Long)
    private class Draft(val pkg: String, val start: Long, var end: Long)

    fun build(context: Context): InsightsState {
        val ctx = context.applicationContext
        val repo = UsageRepository(ctx)
        val categories = HashMap<String, AppCategory>()
        fun cat(pkg: String): AppCategory = categories.getOrPut(pkg) { repo.categoryOf(pkg) }

        val now = System.currentTimeMillis()
        val dayCount = 14
        val intervals = readIntervals(ctx, startOfDay(dayCount - 1), now)
        val sessions = buildSessions(intervals) { cat(it) }
        val fresh = buildDays(intervals, sessions, { cat(it) }, dayCount, now)

        val today = fresh.last()
        val todayStart = today.dayStartMs

        // Android forgets usage after about a week, so we keep our own copy of every finished day.
        saveDays(ctx, fresh.filter { it.dayStartMs < todayStart })
        val completed = loadDays(ctx).filter { it.dayStartMs < todayStart }
        val goal = InsGoal.refresh(ctx, completed)

        // Days in a row at or under the goal.
        var streak = 0
        for (d in completed.reversed()) {
            if (d.totalMin > 0 && d.distractingMin <= goal.targetMin) streak++ else break
        }

        val recent = (completed + today).takeLast(7)
        val weekBars = recent.map { InsBar(it.label(), it.distractingMin) }
        val weekChange = if (completed.size >= 14) {
            val last = completed.takeLast(7).sumOf { it.distractingMin }
            val before = completed.takeLast(14).take(7).sumOf { it.distractingMin }
            if (before > 0) ((last - before) * 100f / before).roundToInt() else null
        } else null

        val withData = (completed + today).filter { it.totalMin > 0 }
        val hourlyAvg = List(24) { h ->
            if (withData.isEmpty()) 0f else withData.map { it.hourly[h] }.average().toFloat()
        }
        val peak = hourlyAvg.indices.maxByOrNull { hourlyAvg[it] }?.takeIf { hourlyAvg[it] >= 1f }

        val weekStart = fresh[fresh.size - 7].dayStartMs
        val distracting = sessions.filter { it.category == AppCategory.DISTRACTING }
        fun info(s: InsSession?) = s?.let { InsSessionInfo(labelOf(ctx, it.pkg), it.minutes.roundToInt()) }
        val longestToday = info(distracting.filter { it.startMs >= todayStart }.maxByOrNull { it.endMs - it.startMs })
        val longestWeek = info(distracting.filter { it.startMs >= weekStart }.maxByOrNull { it.endMs - it.startMs })
        val longSessions = distracting.count { it.startMs >= weekStart && it.minutes >= 20f }

        val lateNight = recent.filter { it.totalMin > 0 }.map { it.lateNightMin }.average()
            .let { if (it.isNaN()) 0 else it.roundToInt() }

        val unlocks = try { DayCounters.getUnlocks(ctx) } catch (e: Exception) { 0 }

        return InsightsState(
            loading = false,
            unlocksToday = unlocks,
            goal = goal,
            todayDistractingMin = today.distractingMin,
            streakDays = streak,
            weekBars = weekBars,
            weekChangePct = weekChange,
            hourlyAvg = hourlyAvg,
            peakHour = peak,
            longestToday = longestToday,
            longestWeek = longestWeek,
            longSessionsWeek = longSessions,
            lateNightAvgMin = lateNight,
            observations = observations(weekChange, peak, hourlyAvg, lateNight, longSessions, streak, unlocks),
            daysStored = completed.size,
            loggedSessions = countLoggedSessions(ctx),
            modelInstalled = hasModelFile(ctx)
        )
    }

    // ---- plain-language observations (rules today, can become model-driven later) ----------------------------
    private fun observations(
        weekChange: Int?, peak: Int?, hourlyAvg: List<Float>, lateNight: Int,
        longSessions: Int, streak: Int, unlocks: Int
    ): List<String> {
        val out = ArrayList<String>()
        if (weekChange != null && weekChange <= -5) {
            out.add("Your distracting time is down ${-weekChange}% compared with the week before. Keep going.")
        } else if (weekChange != null && weekChange >= 10) {
            out.add("Your distracting time is up $weekChange% compared with the week before.")
        }
        if (streak >= 3) out.add("You have stayed under your goal $streak days in a row.")
        if (peak != null && hourlyAvg[peak] >= 5f) {
            out.add(
                "Your most distracting time is ${insHourLabel(peak)} to ${insHourLabel((peak + 1) % 24)}. " +
                    "A session guard on your top app can help in that hour."
            )
        }
        if (lateNight >= 15) {
            out.add("You use your phone about $lateNight minutes a day between midnight and 5 am. Late-night use costs sleep.")
        }
        if (longSessions >= 3) {
            out.add("$longSessions sittings in distracting apps lasted 20+ minutes this week. The Gentle session guard pauses those.")
        }
        if (unlocks >= 100) out.add("You unlocked your phone $unlocks times today. Try leaving it out of reach for an hour.")
        return out.take(4)
    }

    // ---- ML data collection progress ---------------------------------------------------------------------------
    private fun countLoggedSessions(context: Context): Int {
        val f = File(context.filesDir, EVENTS_FILE)
        if (!f.exists()) return 0
        return try {
            f.useLines { lines -> lines.count { it.contains(",session_end,") } }
        } catch (e: Exception) {
            0
        }
    }

    private fun hasModelFile(context: Context): Boolean = try {
        context.assets.open("risk_model.json").use { true }
    } catch (e: Exception) {
        false
    }

    // ---- reading Android's usage events ---------------------------------------------------------------------------------
    private fun readIntervals(context: Context, from: Long, to: Long): List<Interval> {
        val usm = context.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
        val pm = context.packageManager
        val trackable = HashMap<String, Boolean>()
        fun isTrackable(pkg: String) = trackable.getOrPut(pkg) {
            pkg != context.packageName && pm.getLaunchIntentForPackage(pkg) != null
        }

        val events = usm.queryEvents(from, to)
        val e = UsageEvents.Event()
        val resumed = HashMap<String, Long>()
        val out = ArrayList<Interval>()

        while (events.hasNextEvent()) {
            events.getNextEvent(e)
            val pkg = e.packageName
            val key = pkg + "/" + (e.className ?: "")
            when (e.eventType) {
                UsageEvents.Event.ACTIVITY_RESUMED -> resumed[key] = e.timeStamp
                UsageEvents.Event.ACTIVITY_PAUSED,
                UsageEvents.Event.ACTIVITY_STOPPED -> {
                    val start = resumed.remove(key)
                    if (start != null && e.timeStamp - start >= 1_000 && isTrackable(pkg)) {
                        out.add(Interval(pkg, start, e.timeStamp))
                    }
                }
            }
        }
        for ((key, start) in resumed) {              // still on screen right now
            val pkg = key.substringBefore("/")
            if (to - start >= 1_000 && isTrackable(pkg)) out.add(Interval(pkg, start, to))
        }
        out.sortBy { it.start }
        return out
    }

    /** Same app, gap under 2 minutes = one sitting. */
    private fun buildSessions(intervals: List<Interval>, cat: (String) -> AppCategory): List<InsSession> {
        val open = HashMap<String, Draft>()
        val drafts = ArrayList<Draft>()
        for (iv in intervals) {
            val d = open[iv.pkg]
            if (d != null && iv.start - d.end <= GAP_MS) {
                if (iv.end > d.end) d.end = iv.end
            } else {
                val fresh = Draft(iv.pkg, iv.start, iv.end)
                drafts.add(fresh)
                open[iv.pkg] = fresh
            }
        }
        return drafts.sortedBy { it.start }.map { InsSession(it.pkg, cat(it.pkg), it.start, it.end) }
    }

    private fun buildDays(
        intervals: List<Interval>,
        sessions: List<InsSession>,
        cat: (String) -> AppCategory,
        dayCount: Int,
        now: Long
    ): List<InsDay> {
        val out = ArrayList<InsDay>()
        for (daysAgo in (dayCount - 1) downTo 0) {
            val dayStart = startOfDay(daysAgo)
            val dayEnd = if (daysAgo == 0) now else startOfDay(daysAgo - 1)

            var productive = 0L
            var neutral = 0L
            var distracting = 0L
            var lateNight = 0L
            val hourly = LongArray(24)

            for (iv in intervals) {
                if (iv.end <= dayStart || iv.start >= dayEnd) continue
                val category = cat(iv.pkg)
                var t = maxOf(iv.start, dayStart)
                val end = minOf(iv.end, dayEnd)
                while (t < end) {                          // split at hour boundaries
                    val segEnd = minOf(end, nextHour(t))
                    val ms = segEnd - t
                    val h = hourOf(t)
                    when (category) {
                        AppCategory.PRODUCTIVE -> productive += ms
                        AppCategory.NEUTRAL -> neutral += ms
                        AppCategory.DISTRACTING -> {
                            distracting += ms
                            hourly[h] += ms
                        }
                    }
                    if (category != AppCategory.PRODUCTIVE && h < 5) lateNight += ms
                    t = segEnd
                }
            }

            val daySessions = sessions.filter {
                it.category == AppCategory.DISTRACTING && it.startMs >= dayStart && it.startMs < dayEnd
            }
            val longest = daySessions.maxByOrNull { it.endMs - it.startMs }

            out.add(
                InsDay(
                    dayStartMs = dayStart,
                    totalMin = toMin(productive + neutral + distracting),
                    productiveMin = toMin(productive),
                    neutralMin = toMin(neutral),
                    distractingMin = toMin(distracting),
                    distractingSessions = daySessions.size,
                    longestMin = longest?.minutes?.roundToInt() ?: 0,
                    longestPkg = longest?.pkg ?: "",
                    lateNightMin = toMin(lateNight),
                    hourly = IntArray(24) { toMin(hourly[it]) }
                )
            )
        }
        return out
    }

    // ---- saving our own copy of finished days ----------------------------------------------------------------------------
    @Synchronized
    private fun loadDays(context: Context): List<InsDay> {
        val f = File(context.filesDir, DAILY_FILE)
        if (!f.exists()) return emptyList()
        return f.readLines().drop(1).mapNotNull { InsDay.fromCsv(it) }.sortedBy { it.dayStartMs }
    }

    /** A saved day is only replaced by a more complete version of itself. */
    @Synchronized
    private fun saveDays(context: Context, finished: List<InsDay>) {
        val map = LinkedHashMap<Long, InsDay>()
        loadDays(context).forEach { map[it.dayStartMs] = it }
        for (fresh in finished) {
            if (fresh.totalMin <= 0) continue            // no data (probably purged by Android)
            val old = map[fresh.dayStartMs]
            if (old == null || fresh.totalMin >= old.totalMin) map[fresh.dayStartMs] = fresh
        }
        val rows = map.values.sortedBy { it.dayStartMs }
        File(context.filesDir, DAILY_FILE).writeText(
            InsDay.header() + "\n" + rows.joinToString("") { it.toCsv() + "\n" }
        )
    }

    // ---- small helpers ------------------------------------------------------------------------------------------------------
    private fun labelOf(context: Context, pkg: String): String = try {
        context.packageManager.getApplicationLabel(context.packageManager.getApplicationInfo(pkg, 0)).toString()
    } catch (e: Exception) {
        pkg
    }

    private fun toMin(ms: Long): Int = ((ms + 30_000) / 60_000).toInt()

    private fun startOfDay(daysAgo: Int): Long {
        val cal = Calendar.getInstance()
        cal.add(Calendar.DAY_OF_YEAR, -daysAgo)
        cal.set(Calendar.HOUR_OF_DAY, 0)
        cal.set(Calendar.MINUTE, 0)
        cal.set(Calendar.SECOND, 0)
        cal.set(Calendar.MILLISECOND, 0)
        return cal.timeInMillis
    }

    private fun nextHour(t: Long): Long {
        val cal = Calendar.getInstance()
        cal.timeInMillis = t
        cal.set(Calendar.MINUTE, 0)
        cal.set(Calendar.SECOND, 0)
        cal.set(Calendar.MILLISECOND, 0)
        cal.add(Calendar.HOUR_OF_DAY, 1)
        return cal.timeInMillis
    }

    private fun hourOf(t: Long): Int {
        val cal = Calendar.getInstance()
        cal.timeInMillis = t
        return cal.get(Calendar.HOUR_OF_DAY)
    }
}
