package com.example.focusguard   // <- must match the first line of YOUR MainActivity.kt

import androidx.compose.ui.graphics.ImageBitmap

enum class AppCategory(val label: String) {
    PRODUCTIVE("Productive"),
    NEUTRAL("Neutral"),
    DISTRACTING("Distracting")
}

data class AppUsage(
    val packageName: String,
    val name: String,
    val usageMs: Long,
    val category: AppCategory,
    val icon: ImageBitmap?
)

data class DayTotal(val label: String, val totalMs: Long)

data class DashboardState(
    val loading: Boolean = true,
    val totalMs: Long = 0,
    val productiveMs: Long = 0,
    val neutralMs: Long = 0,
    val distractingMs: Long = 0,
    val attentionScore: Int = 100,
    val topApps: List<AppUsage> = emptyList(),
    val week: List<DayTotal> = emptyList()
)

/** 7_500_000 ms -> "2h 5m" */
fun formatDuration(ms: Long): String {
    val totalMin = ms / 60_000
    val h = totalMin / 60
    val m = totalMin % 60
    return when {
        h > 0 -> "${h}h ${m}m"
        totalMin > 0 -> "${m}m"
        ms > 0 -> "<1m"
        else -> "0m"
    }
}
