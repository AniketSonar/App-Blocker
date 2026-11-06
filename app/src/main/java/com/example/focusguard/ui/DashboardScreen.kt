package com.example.focusguard

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

@Composable
fun DashboardScreen(
    state: DashboardState,
    onRefresh: () -> Unit,
    protectionOn: Boolean,
    onFixProtection: () -> Unit
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize().statusBarsPadding(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        item { Header(onRefresh) }

        if (!protectionOn) item { ProtectionBanner(onFixProtection) }

        if (state.loading) {
            item {
                Box(Modifier.fillMaxWidth().padding(56.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            }
        } else {
            item { HeroCard(state) }
            item { WeekCard(state.week) }
            item { TopAppsCard(state.topApps) }
            item { Spacer(Modifier.height(8.dp)) }
        }
    }
}

private fun greeting(): String {
    val hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
    return when {
        hour < 5 -> "Late night"
        hour < 12 -> "Good morning"
        hour < 17 -> "Good afternoon"
        hour < 22 -> "Good evening"
        else -> "Good night"
    }
}

@Composable
private fun Header(onRefresh: () -> Unit) {
    val date = SimpleDateFormat("EEEE, d MMM", Locale.getDefault()).format(Date())
    Row(Modifier.fillMaxWidth().padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            FgSectionTitle(date)
            Spacer(Modifier.height(2.dp))
            Text(greeting(), style = MaterialTheme.typography.headlineMedium)
        }
        IconButton(
            onClick = onRefresh,
            modifier = Modifier
                .size(44.dp)
                .border(1.dp, MaterialTheme.colorScheme.outlineVariant, CircleShape)
        ) {
            Icon(Icons.Filled.Refresh, contentDescription = "Refresh", tint = MaterialTheme.colorScheme.onSurface)
        }
    }
}

@Composable
private fun ProtectionBanner(onFix: () -> Unit) {
    FgCard(
        onClick = onFix,
        containerColor = FgDistracting.copy(alpha = 0.10f),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 14.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            FgDot(FgDistracting, 10.dp)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text("Blocking is off", style = MaterialTheme.typography.titleSmall)
                Text(
                    "Turn on the FocusGuard accessibility service so your limits can work.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(Modifier.width(8.dp))
            Text("Fix", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
        }
    }
}

// ---- the big card at the top ------------------------------------------------------------------------
@Composable
private fun HeroCard(state: DashboardState) {
    val onHero = MaterialTheme.colorScheme.onPrimaryContainer
    val parts = listOf(
        AppCategory.PRODUCTIVE to state.productiveMs,
        AppCategory.NEUTRAL to state.neutralMs,
        AppCategory.DISTRACTING to state.distractingMs
    )
    val yesterday = state.week.getOrNull(state.week.size - 2)

    Card(
        shape = RoundedCornerShape(30.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer,
            contentColor = onHero
        ),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(22.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(
                        "SCREEN TIME TODAY",
                        style = MaterialTheme.typography.labelSmall,
                        color = onHero.copy(alpha = 0.7f)
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(formatDuration(state.totalMs), style = MaterialTheme.typography.displayMedium, color = onHero)
                    if (yesterday != null && yesterday.totalMs > 0) {
                        Spacer(Modifier.height(2.dp))
                        Text(
                            "Yesterday " + formatDuration(yesterday.totalMs),
                            style = MaterialTheme.typography.bodySmall,
                            color = onHero.copy(alpha = 0.72f)
                        )
                    }
                }
                FgScoreRing(
                    score = state.attentionScore,
                    ringColor = onHero,
                    trackColor = onHero.copy(alpha = 0.18f),
                    textColor = onHero
                )
            }

            Spacer(Modifier.height(22.dp))
            FgSegmentBar(parts.map { (category, ms) -> category.color() to ms })
            Spacer(Modifier.height(14.dp))

            Row(Modifier.fillMaxWidth()) {
                parts.forEach { (category, ms) ->
                    Column(Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            FgDot(category.color())
                            Spacer(Modifier.width(6.dp))
                            Text(
                                category.label,
                                style = MaterialTheme.typography.labelMedium,
                                color = onHero.copy(alpha = 0.75f)
                            )
                        }
                        Spacer(Modifier.height(2.dp))
                        Text(formatDuration(ms), style = MaterialTheme.typography.titleMedium, color = onHero)
                    }
                }
            }
        }
    }
}

// ---- last 7 days ----------------------------------------------------------------------------------------
@Composable
private fun WeekCard(week: List<DayTotal>) {
    val max = (week.maxOfOrNull { it.totalMs } ?: 0L).coerceAtLeast(1L)
    val withData = week.filter { it.totalMs > 0 }
    val average = if (withData.isEmpty()) 0L else withData.map { it.totalMs }.average().toLong()
    val muted = MaterialTheme.colorScheme.onSurfaceVariant

    FgCard {
        Row(verticalAlignment = Alignment.Bottom) {
            Column(Modifier.weight(1f)) {
                FgSectionTitle("Last 7 days")
                Spacer(Modifier.height(2.dp))
                Text("Daily average", style = MaterialTheme.typography.bodySmall, color = muted)
            }
            Text(formatDuration(average), style = MaterialTheme.typography.headlineSmall)
        }
        Spacer(Modifier.height(18.dp))
        Row(
            Modifier.fillMaxWidth().height(150.dp),
            verticalAlignment = Alignment.Bottom,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            week.forEachIndexed { index, day ->
                val isToday = index == week.lastIndex
                val barHeight = maxOf(4f, 84f * day.totalMs / max).dp
                Column(
                    Modifier.weight(1f),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Bottom
                ) {
                    Text(
                        formatDuration(day.totalMs),
                        fontSize = 10.sp,
                        textAlign = TextAlign.Center,
                        maxLines = 1,
                        color = if (isToday) MaterialTheme.colorScheme.onSurface else muted,
                        fontWeight = if (isToday) FontWeight.SemiBold else FontWeight.Normal
                    )
                    Spacer(Modifier.height(6.dp))
                    Box(
                        Modifier
                            .width(26.dp)
                            .height(barHeight)
                            .clip(RoundedCornerShape(9.dp))
                            .background(
                                if (isToday) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.outlineVariant
                            )
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        day.label,
                        style = MaterialTheme.typography.labelMedium,
                        color = if (isToday) MaterialTheme.colorScheme.onSurface else muted,
                        fontWeight = if (isToday) FontWeight.Bold else FontWeight.Medium
                    )
                }
            }
        }
    }
}

// ---- most used apps -------------------------------------------------------------------------------------
@Composable
private fun TopAppsCard(apps: List<AppUsage>) {
    var expanded by remember { mutableStateOf(false) }
    val shown = if (expanded) apps else apps.take(6)
    val maxMs = (apps.firstOrNull()?.usageMs ?: 1L).coerceAtLeast(1L)

    FgCard {
        FgSectionTitle("Most used today")
        Spacer(Modifier.height(14.dp))
        if (apps.isEmpty()) {
            Text(
                "No app usage recorded yet today. Use your phone, then tap refresh.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        shown.forEachIndexed { index, app ->
            if (index > 0) Spacer(Modifier.height(16.dp))
            AppRow(app, maxMs)
        }
        if (apps.size > 6) {
            Spacer(Modifier.height(6.dp))
            TextButton(onClick = { expanded = !expanded }) {
                Text(if (expanded) "Show less" else "Show ${apps.size - 6} more")
            }
        }
    }
}

@Composable
private fun AppRow(app: AppUsage, maxMs: Long) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        FgAppIcon(app.icon, app.category.color(), 44.dp)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    app.name,
                    Modifier.weight(1f),
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(Modifier.width(8.dp))
                Text(formatDuration(app.usageMs), style = MaterialTheme.typography.titleSmall)
            }
            Spacer(Modifier.height(7.dp))
            FgThinBar(app.usageMs.toFloat() / maxMs, app.category.color(), Modifier.fillMaxWidth())
            Spacer(Modifier.height(5.dp))
            Text(
                app.category.label,
                style = MaterialTheme.typography.labelMedium,
                color = app.category.color()
            )
        }
    }
}
