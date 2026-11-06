package com.example.focusguard

import android.content.Context
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Tracks daily counters such as screen unlocks for ML and insights.
 */
object DayCounters {
    private const val PREFS = "focusguard_counters"
    private const val KEY_DATE = "unlock_date"
    private const val KEY_UNLOCKS = "unlock_count"

    @Synchronized
    fun incrementUnlocks(context: Context) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val today = SimpleDateFormat("yyyyMMdd", Locale.US).format(Date())
        val savedDate = prefs.getString(KEY_DATE, "")
        val count = if (savedDate == today) prefs.getInt(KEY_UNLOCKS, 0) else 0
        prefs.edit().apply {
            putString(KEY_DATE, today)
            putInt(KEY_UNLOCKS, count + 1)
        }.apply()
    }

    @Synchronized
    fun getUnlocks(context: Context): Int {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val today = SimpleDateFormat("yyyyMMdd", Locale.US).format(Date())
        val savedDate = prefs.getString(KEY_DATE, "")
        return if (savedDate == today) prefs.getInt(KEY_UNLOCKS, 0) else 0
    }
}
