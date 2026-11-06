package com.example.focusguard

import android.app.Application
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Everything the Insights tab shows. Filled by InsightsEngine. */
data class InsightsState(
    val loading: Boolean = true,
    val unlocksToday: Int = 0,
    val goal: InsGoalState? = null,
    val todayDistractingMin: Int = 0,
    val streakDays: Int = 0,
    val weekBars: List<InsBar> = emptyList(),
    val weekChangePct: Int? = null,
    val hourlyAvg: List<Float> = emptyList(),
    val peakHour: Int? = null,
    val longestToday: InsSessionInfo? = null,
    val longestWeek: InsSessionInfo? = null,
    val longSessionsWeek: Int = 0,
    val lateNightAvgMin: Int = 0,
    val observations: List<String> = emptyList(),
    val daysStored: Int = 0,
    val loggedSessions: Int = 0,
    val modelInstalled: Boolean = false
)

class InsightsViewModel(app: Application) : AndroidViewModel(app) {
    private val _state = MutableStateFlow(InsightsState())
    val state: StateFlow<InsightsState> = _state.asStateFlow()

    fun refresh() {
        viewModelScope.launch(Dispatchers.IO) {
            _state.value = try {
                InsightsEngine.build(getApplication<Application>())
            } catch (e: Exception) {
                InsightsState(loading = false)
            }
        }
    }
}

private const val MODEL_TARGET_SESSIONS = 150

@Composable
fun InsightsScreen(state: InsightsState) {
    val goal = state.goal
    LazyColumn(
        modifier = Modifier.fillMaxSize().statusBarsPadding(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        item {
            val date = SimpleDateFormat("EEEE, d MMM", Locale.getDefault()).format(Date())
            Column(Modifier.padding(top = 4.dp)) {
                FgSectionTitle(date)
                Spacer(Modifier.height(2.dp))
                Text("Insights", style = MaterialTheme.typography.headlineMedium)
            }
        }

        if (state.loading) {
            item {
                Box(Modifier.fillMaxWidth().padding(56.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            }
        } else if (goal == null) {
            item {
                FgCard {
                    Text("No data yet", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "Make sure usage access is on, use your phone for a while, then open this tab again.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        } else {
            item { GoalCard(goal, state.todayDistractingMin, state.streakDays) }
            item { HighlightsRow(state) }
            if (state.observations.isNotEmpty()) item { NoticedCard(state.observations) }
            item { WeekCard(state, goal.targetMin) }
            item { PatternsCard(state) }
            item { SmartPauseCard(state) }
            item { Spacer(Modifier.height(8.dp)) }
        }
    }
}

// ---- goal ------------------------------------------------------------------------------------------------------------------
@Composable
private fun GoalCard(goal: InsGoalState, usedMin: Int, streak: Int) {
    val ratio = if (goal.targetMin > 0) usedMin.toFloat() / goal.targetMin else 0f
    val color = when {
        ratio >= 1f -> FgDistracting
        ratio >= 0.8f -> FgWarn
        else -> FgProductive
    }
    val muted = MaterialTheme.colorScheme.onSurfaceVariant

    FgCard {
        FgSectionTitle(if (goal.calibrating) "Starter goal" else "Daily goal")
        Spacer(Modifier.height(4.dp))
        Row(verticalAlignment = Alignment.Bottom) {
            Text(formatDuration(goal.targetMin * 60_000L), style = MaterialTheme.typography.displaySmall)
            Spacer(Modifier.width(10.dp))
            Text(
                "for distracting apps",
                style = MaterialTheme.typography.bodyMedium,
                color = muted,
                modifier = Modifier.padding(bottom = 8.dp)
            )
        }
        Spacer(Modifier.height(14.dp))
        FgThinBar(ratio, color, Modifier.fillMaxWidth())
        Spacer(Modifier.height(10.dp))
        Text(
            if (usedMin <= goal.targetMin)
                "Used ${formatDuration(usedMin * 60_000L)} today, ${formatDuration((goal.targetMin - usedMin) * 60_000L)} left"
            else
                "Over the goal by ${formatDuration((usedMin - goal.targetMin) * 60_000L)}",
            style = MaterialTheme.typography.titleSmall
        )
        if (streak > 0) {
            Spacer(Modifier.height(4.dp))
            Text(
                "$streak day" + (if (streak == 1) "" else "s") + " in a row under your goal",
                style = MaterialTheme.typography.bodySmall,
                color = FgProductive,
                fontWeight = FontWeight.SemiBold
            )
        }
        Spacer(Modifier.height(10.dp))
        Text(goal.reason, style = MaterialTheme.typography.bodySmall, color = muted)
    }
}

// ---- two small stat cards ---------------------------------------------------------------------------------------------------
@Composable
private fun HighlightsRow(state: InsightsState) {
    val longest = state.longestToday
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        StatCard(
            title = "Unlocks today",
            value = "${state.unlocksToday}",
            note = "times you picked up your phone",
            modifier = Modifier.weight(1f)
        )
        StatCard(
            title = "Longest today",
            value = if (longest == null) "None" else formatDuration(longest.minutes * 60_000L),
            note = if (longest == null) "no long sitting yet" else "in " + longest.appName,
            modifier = Modifier.weight(1f)
        )
    }
}

@Composable
private fun StatCard(title: String, value: String, note: String, modifier: Modifier) {
    FgCard(modifier = modifier, contentPadding = PaddingValues(16.dp)) {
        FgSectionTitle(title)
        Spacer(Modifier.height(6.dp))
        Text(value, style = MaterialTheme.typography.headlineMedium)
        Spacer(Modifier.height(4.dp))
        Text(
            note,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            minLines = 2,
            maxLines = 2
        )
    }
}

// ---- what we noticed -------------------------------------------------------------------------------------------------------------
@Composable
private fun NoticedCard(lines: List<String>) {
    FgCard {
        FgSectionTitle("What we noticed")
        Spacer(Modifier.height(12.dp))
        lines.forEachIndexed { index, line ->
            if (index > 0) Spacer(Modifier.height(12.dp))
            Row(verticalAlignment = Alignment.Top) {
                Box(Modifier.padding(top = 7.dp)) { FgDot(MaterialTheme.colorScheme.primary, 7.dp) }
                Spacer(Modifier.width(12.dp))
                Text(line, style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

// ---- last 7 days of distracting time ----------------------------------------------------------------------------------------
@Composable
private fun WeekCard(state: InsightsState, goalMin: Int) {
    val max = (state.weekBars.maxOfOrNull { it.minutes } ?: 0).coerceAtLeast(1)
    val muted = MaterialTheme.colorScheme.onSurfaceVariant

    FgCard {
        FgSectionTitle("Distracting apps, last 7 days")
        state.weekChangePct?.let { pct ->
            Spacer(Modifier.height(4.dp))
            Text(
                when {
                    pct < 0 -> "Down ${-pct}% compared with the week before"
                    pct > 0 -> "Up $pct% compared with the week before"
                    else -> "Same as the week before"
                },
                style = MaterialTheme.typography.bodySmall,
                color = if (pct <= 0) FgProductive else FgDistracting,
                fontWeight = FontWeight.SemiBold
            )
        }
        Spacer(Modifier.height(16.dp))
        Row(
            Modifier.fillMaxWidth().height(140.dp),
            verticalAlignment = Alignment.Bottom,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            state.weekBars.forEachIndexed { index, bar ->
                val isToday = index == state.weekBars.lastIndex
                val barHeight = maxOf(4f, 80f * bar.minutes / max).dp
                Column(
                    Modifier.weight(1f),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Bottom
                ) {
                    Text(
                        formatDuration(bar.minutes * 60_000L),
                        fontSize = 10.sp,
                        maxLines = 1,
                        textAlign = TextAlign.Center,
                        color = if (isToday) MaterialTheme.colorScheme.onSurface else muted
                    )
                    Spacer(Modifier.height(6.dp))
                    Box(
                        Modifier
                            .width(26.dp)
                            .height(barHeight)
                            .clip(RoundedCornerShape(9.dp))
                            .background(
                                when {
                                    goalMin > 0 && bar.minutes > goalMin -> FgDistracting
                                    isToday -> MaterialTheme.colorScheme.primary
                                    else -> MaterialTheme.colorScheme.outlineVariant
                                }
                            )
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        bar.label,
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = if (isToday) FontWeight.Bold else FontWeight.Medium,
                        color = if (isToday) MaterialTheme.colorScheme.onSurface else muted
                    )
                }
            }
        }
        Spacer(Modifier.height(6.dp))
        Text("Coral bars are days over your goal.", style = MaterialTheme.typography.bodySmall, color = muted)
    }
}

// ---- patterns ------------------------------------------------------------------------------------------------------------------------
@Composable
private fun PatternsCard(state: InsightsState) {
    FgCard {
        FgSectionTitle("Your patterns")
        Spacer(Modifier.height(10.dp))
        FactRow(
            "Most distracting time",
            state.peakHour?.let { insHourLabel(it) + " to " + insHourLabel((it + 1) % 24) } ?: "Not enough data yet"
        )
        FactRow(
            "Longest this week",
            state.longestWeek?.let { it.appName + ", " + formatDuration(it.minutes * 60_000L) } ?: "None yet"
        )
        FactRow("Sittings over 20 min this week", state.longSessionsWeek.toString())
        FactRow("Late-night use (12 am to 5 am)", formatDuration(state.lateNightAvgMin * 60_000L) + " a day")

        if (state.hourlyAvg.isNotEmpty()) {
            Spacer(Modifier.height(16.dp))
            Text(
                "Average distracting minutes by hour",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(8.dp))
            HourChart(state.hourlyAvg, state.peakHour)
        }
    }
}

@Composable
private fun FactRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 5.dp), verticalAlignment = Alignment.Top) {
        Text(
            label,
            Modifier.weight(1f),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.width(12.dp))
        Text(value, style = MaterialTheme.typography.titleSmall, textAlign = TextAlign.End)
    }
}

@Composable
private fun HourChart(values: List<Float>, peak: Int?) {
    val max = (values.maxOrNull() ?: 0f).coerceAtLeast(1f)
    Row(Modifier.fillMaxWidth().height(84.dp), verticalAlignment = Alignment.Bottom) {
        values.forEachIndexed { hour, v ->
            Box(
                Modifier
                    .weight(1f)
                    .padding(horizontal = 1.dp)
                    .height(maxOf(2f, 80f * v / max).dp)
                    .clip(RoundedCornerShape(topStart = 3.dp, topEnd = 3.dp))
                    .background(
                        if (hour == peak) FgDistracting else MaterialTheme.colorScheme.primary.copy(alpha = 0.55f)
                    )
            )
        }
    }
    Spacer(Modifier.height(4.dp))
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        listOf("12 am", "6 am", "12 pm", "6 pm", "11 pm").forEach {
            Text(it, fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

// ---- the ML part: how much data has been collected --------------------------------------------------------------------------
@Composable
private fun SmartPauseCard(state: InsightsState) {
    val context = LocalContext.current
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    val progress = state.loggedSessions.toFloat() / MODEL_TARGET_SESSIONS

    FgCard {
        FgSectionTitle("Smart pause")
        Spacer(Modifier.height(6.dp))
        Text(
            if (state.modelInstalled) "A trained model is installed" else "Learning your habits",
            style = MaterialTheme.typography.titleMedium
        )
        Spacer(Modifier.height(4.dp))
        Text(
            "${state.loggedSessions} of $MODEL_TARGET_SESSIONS finished sessions collected. " +
                "Around $MODEL_TARGET_SESSIONS are needed before a model can be trained on your computer.",
            style = MaterialTheme.typography.bodySmall,
            color = muted
        )
        Spacer(Modifier.height(12.dp))
        FgThinBar(progress, MaterialTheme.colorScheme.primary, Modifier.fillMaxWidth())
        Spacer(Modifier.height(14.dp))
        OutlinedButton(onClick = { InsExport.share(context) }, shape = RoundedCornerShape(14.dp)) {
            Text("Export data for training")
        }
        Spacer(Modifier.height(8.dp))
        Text(
            "${state.daysStored} finished days are saved on this phone. Nothing leaves your phone unless you share it.",
            style = MaterialTheme.typography.bodySmall,
            color = muted
        )
    }
}
