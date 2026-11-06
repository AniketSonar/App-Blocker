package com.example.focusguard

import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import androidx.compose.ui.graphics.asImageBitmap
import androidx.core.graphics.drawable.toBitmap
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

/** Reads usage numbers from Android and turns them into a DashboardState. */
class UsageRepository(private val context: Context) {

    private val usm =
        context.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
    private val pm = context.packageManager

    // Only apps that have a launcher icon (hides system background packages).
    @Suppress("DEPRECATION")
    private val launchable: Set<String> by lazy {
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        pm.queryIntentActivities(intent, 0).map { it.activityInfo.packageName }.toSet()
    }

    // Apps we already know. Everything else is guessed from its Play Store category.
    private val known: Map<String, AppCategory> = mapOf(
        // Distracting
        "com.instagram.android" to AppCategory.DISTRACTING,
        "com.zhiliaoapp.musically" to AppCategory.DISTRACTING,
        "com.facebook.katana" to AppCategory.DISTRACTING,
        "com.twitter.android" to AppCategory.DISTRACTING,
        "com.snapchat.android" to AppCategory.DISTRACTING,
        "com.reddit.frontpage" to AppCategory.DISTRACTING,
        "com.pinterest" to AppCategory.DISTRACTING,
        // Productive (never counted against the user)
        "us.zoom.videomeetings" to AppCategory.PRODUCTIVE,
        "com.google.android.apps.meetings" to AppCategory.PRODUCTIVE,
        "com.microsoft.teams" to AppCategory.PRODUCTIVE,
        "com.google.android.apps.docs" to AppCategory.PRODUCTIVE,
        "com.google.android.apps.docs.editors.docs" to AppCategory.PRODUCTIVE,
        "com.google.android.calendar" to AppCategory.PRODUCTIVE,
        "com.slack" to AppCategory.PRODUCTIVE,
        "com.google.android.gm" to AppCategory.PRODUCTIVE,
        // Dual-use: can be work or leisure
        "com.google.android.youtube" to AppCategory.NEUTRAL,
        "com.whatsapp" to AppCategory.NEUTRAL,
        "com.linkedin.android" to AppCategory.NEUTRAL,
        "com.android.chrome" to AppCategory.NEUTRAL
    )

    fun loadDashboard(): DashboardState {
        val dayFmt = SimpleDateFormat("EEE", Locale.getDefault())
        val week = ArrayList<DayTotal>()
        var today: Map<String, Long> = emptyMap()

        // Oldest day first, today last.
        for (daysAgo in 6 downTo 0) {
            val start = startOfDay(daysAgo)
            val end = if (daysAgo == 0) System.currentTimeMillis() else startOfDay(daysAgo - 1)
            val usage = usageBetween(start, end)
            week.add(DayTotal(dayFmt.format(Date(start)), usage.values.sum()))
            if (daysAgo == 0) today = usage
        }

        var productive = 0L
        var neutral = 0L
        var distracting = 0L
        for ((pkg, ms) in today) {
            when (categoryOf(pkg)) {
                AppCategory.PRODUCTIVE -> productive += ms
                AppCategory.NEUTRAL -> neutral += ms
                AppCategory.DISTRACTING -> distracting += ms
            }
        }
        val total = productive + neutral + distracting

        val topApps = today.entries
            .sortedByDescending { it.value }
            .take(30)
            .map { (pkg, ms) ->
                AppUsage(
                    packageName = pkg,
                    name = labelOf(pkg),
                    usageMs = ms,
                    category = categoryOf(pkg),
                    icon = iconOf(pkg)
                )
            }

        return DashboardState(
            loading = false,
            totalMs = total,
            productiveMs = productive,
            neutralMs = neutral,
            distractingMs = distracting,
            attentionScore = attentionScore(total, productive, distracting),
            topApps = topApps,
            week = week
        )
    }

    /** How long this app has been on screen so far today (used by the blocker). */
    fun usageTodayMs(pkg: String): Long =
        usageBetween(startOfDay(0), System.currentTimeMillis())[pkg] ?: 0L

    /** Which app is on screen right now (looks at the last few minutes of events). */
    fun currentForegroundPackage(): String? {
        val end = System.currentTimeMillis()
        val events = usm.queryEvents(end - 10 * 60_000L, end)
        val e = UsageEvents.Event()
        var current: String? = null
        while (events.hasNextEvent()) {
            events.getNextEvent(e)
            when (e.eventType) {
                UsageEvents.Event.ACTIVITY_RESUMED -> current = e.packageName
                UsageEvents.Event.ACTIVITY_PAUSED -> if (e.packageName == current) current = null
            }
        }
        return current
    }

    // ---- helpers -------------------------------------------------------

    /**
     * Adds up foreground time from raw events, clipped to [start, end].
     * (queryAndAggregateUsageStats can return numbers from outside the window,
     * which caused impossible totals like 37h in one day.)
     */
    private fun usageBetween(start: Long, end: Long): Map<String, Long> {
        val events = usm.queryEvents(start, end)
        val event = UsageEvents.Event()
        val totals = HashMap<String, Long>()
        val resumedAt = HashMap<String, Long>()   // "package/activity" -> time it came on screen

        while (events.hasNextEvent()) {
            events.getNextEvent(event)
            val key = event.packageName + "/" + (event.className ?: "")
            when (event.eventType) {
                UsageEvents.Event.ACTIVITY_RESUMED -> resumedAt[key] = event.timeStamp
                UsageEvents.Event.ACTIVITY_PAUSED,
                UsageEvents.Event.ACTIVITY_STOPPED -> {
                    val from = resumedAt.remove(key)
                    if (from != null) {
                        totals[event.packageName] =
                            (totals[event.packageName] ?: 0L) + (event.timeStamp - from)
                    }
                }
            }
        }
        // Apps still on screen when the window ends (for today: right now).
        for ((key, from) in resumedAt) {
            val pkg = key.substringBefore("/")
            totals[pkg] = (totals[pkg] ?: 0L) + (end - from)
        }

        return totals.filter { (pkg, ms) ->
            ms > 0 && pkg in launchable && pkg != context.packageName
        }
    }

    private fun startOfDay(daysAgo: Int): Long {
        val cal = Calendar.getInstance()
        cal.add(Calendar.DAY_OF_YEAR, -daysAgo)
        cal.set(Calendar.HOUR_OF_DAY, 0)
        cal.set(Calendar.MINUTE, 0)
        cal.set(Calendar.SECOND, 0)
        cal.set(Calendar.MILLISECOND, 0)
        return cal.timeInMillis
    }

    fun categoryOf(pkg: String): AppCategory {
        known[pkg]?.let { return it }
        return try {
            when (pm.getApplicationInfo(pkg, 0).category) {
                ApplicationInfo.CATEGORY_GAME,
                ApplicationInfo.CATEGORY_SOCIAL -> AppCategory.DISTRACTING
                ApplicationInfo.CATEGORY_PRODUCTIVITY -> AppCategory.PRODUCTIVE
                else -> AppCategory.NEUTRAL
            }
        } catch (e: PackageManager.NameNotFoundException) {
            AppCategory.NEUTRAL
        }
    }

    private fun labelOf(pkg: String): String = try {
        pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
    } catch (e: PackageManager.NameNotFoundException) {
        pkg
    }

    private fun iconOf(pkg: String) = try {
        pm.getApplicationIcon(pkg).toBitmap(96, 96).asImageBitmap()
    } catch (e: Exception) {
        null
    }

    /**
     * Simple starter score from 0 to 100. Productive time (Zoom, Docs...) is ignored,
     * so a long meeting day does not hurt the score. You will replace this with ML later.
     */
    private fun attentionScore(total: Long, productive: Long, distracting: Long): Int {
        val nonProductive = total - productive
        if (nonProductive <= 0) return 100
        val distractingShare = distracting.toFloat() / nonProductive
        val hours = (nonProductive / 3_600_000f).coerceAtMost(8f)
        return (100f - distractingShare * 60f - (hours / 8f) * 40f).roundToInt().coerceIn(0, 100)
    }
}