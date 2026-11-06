package com.example.focusguard

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

// ---- list of apps the user can limit --------------------------------------------------------
data class InstalledApp(
    val packageName: String,
    val name: String,
    val icon: ImageBitmap?,
    val category: AppCategory
)

object InstalledApps {
    private var cache: List<InstalledApp>? = null

    /** Apps we never let the user limit, so they cannot lock themselves out. */
    fun protectedPackages(context: Context): Set<String> {
        val homeIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        @Suppress("DEPRECATION")
        val home = context.packageManager
            .resolveActivity(homeIntent, PackageManager.MATCH_DEFAULT_ONLY)
            ?.activityInfo?.packageName
        return setOfNotNull(context.packageName, home, "com.android.settings", "com.android.systemui")
    }

    @Suppress("DEPRECATION")
    fun load(context: Context): List<InstalledApp> {
        cache?.let { return it }
        val pm = context.packageManager
        val repo = UsageRepository(context.applicationContext)
        val launcherIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val blocked = protectedPackages(context)

        val list = pm.queryIntentActivities(launcherIntent, 0)
            .map { it.activityInfo.packageName }
            .distinct()
            .filter { it !in blocked }
            .map { pkg ->
                val name = try {
                    pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
                } catch (e: Exception) {
                    pkg
                }
                val icon = try {
                    pm.getApplicationIcon(pkg).toBitmap(96, 96).asImageBitmap()
                } catch (e: Exception) {
                    null
                }
                InstalledApp(pkg, name, icon, repo.categoryOf(pkg))
            }
            .sortedBy { it.name.lowercase() }
        cache = list
        return list
    }
}

// ---- screen ------------------------------------------------------------------------------------------
@Composable
fun RulesScreen(serviceOn: Boolean, onOpenAccessibility: () -> Unit) {
    val context = LocalContext.current

    var apps by remember { mutableStateOf<List<InstalledApp>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var rules by remember { mutableStateOf(RulesStore.allRules(context)) }
    var presets by remember { mutableStateOf(RulesStore.allSessionPresets(context)) }
    var enabled by remember { mutableStateOf(RulesStore.isEnabled(context)) }
    var smartPause by remember { mutableStateOf(RulesStore.isSmartPauseEnabled(context)) }
    var picking by remember { mutableStateOf<InstalledApp?>(null) }
    var query by remember { mutableStateOf("") }

    LaunchedEffect(Unit) {
        apps = withContext(Dispatchers.IO) { InstalledApps.load(context) }
        loading = false
    }

    val filtered = remember(apps, query) {
        if (query.isBlank()) apps else apps.filter { it.name.contains(query.trim(), ignoreCase = true) }
    }
    val withRules = filtered.filter { it.packageName in rules || it.packageName in presets }
    val others = filtered.filter { it.packageName !in rules && it.packageName !in presets }

    LazyColumn(
        modifier = Modifier.fillMaxSize().statusBarsPadding(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        item {
            Column(Modifier.padding(top = 4.dp)) {
                FgSectionTitle("Rules")
                Spacer(Modifier.height(2.dp))
                Text("Limits", style = MaterialTheme.typography.headlineMedium)
            }
        }
        item { ProtectionCard(serviceOn, onOpenAccessibility) }
        item {
            FgCard(contentPadding = PaddingValues(horizontal = 18.dp, vertical = 6.dp)) {
                SwitchRow(
                    title = "Blocking",
                    description = if (enabled) "Your limits are being enforced" else "Paused: nothing will be blocked",
                    checked = enabled
                ) {
                    enabled = it
                    RulesStore.setEnabled(context, it)
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                SwitchRow(
                    title = "Smart pause (beta)",
                    description = "When your own history says a long session is likely, show a short pause as the app opens. " +
                        "Works only after a trained model is added, and only for apps with rules.",
                    checked = smartPause
                ) {
                    smartPause = it
                    RulesStore.setSmartPause(context, it)
                }
            }
        }
        item {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                placeholder = { Text("Search apps") },
                singleLine = true,
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier.fillMaxWidth()
            )
        }

        if (loading) {
            item {
                Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            }
        } else {
            if (withRules.isNotEmpty()) {
                item { FgSectionTitle("Your rules (${withRules.size})") }
                items(withRules, key = { "rule_" + it.packageName }) { app ->
                    RuleRow(app, rules[app.packageName], presets[app.packageName]) { picking = app }
                }
            }
            item { FgSectionTitle(if (withRules.isEmpty()) "All apps" else "Other apps") }
            if (others.isEmpty()) {
                item {
                    Text(
                        "No apps match your search.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            items(others, key = { "app_" + it.packageName }) { app ->
                RuleRow(app, rules[app.packageName], presets[app.packageName]) { picking = app }
            }
            item { Spacer(Modifier.height(8.dp)) }
        }
    }

    picking?.let { app ->
        RuleDialog(
            appName = app.name,
            dailyMin = rules[app.packageName],
            preset = presets[app.packageName],
            onDaily = { minutes ->
                if (minutes == null) RulesStore.remove(context, app.packageName)
                else RulesStore.setLimitMin(context, app.packageName, minutes)
                rules = RulesStore.allRules(context)
            },
            onPreset = { preset ->
                RulesStore.setSessionPreset(context, app.packageName, preset)
                presets = RulesStore.allSessionPresets(context)
            },
            onDismiss = { picking = null }
        )
    }
}

@Composable
private fun ProtectionCard(serviceOn: Boolean, onOpenAccessibility: () -> Unit) {
    FgCard(
        containerColor = if (serviceOn) MaterialTheme.colorScheme.surface else FgDistracting.copy(alpha = 0.10f)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            FgDot(if (serviceOn) FgProductive else FgDistracting, 10.dp)
            Spacer(Modifier.width(10.dp))
            Text(
                if (serviceOn) "Protection is on" else "Protection is off",
                style = MaterialTheme.typography.titleSmall
            )
        }
        if (!serviceOn) {
            Spacer(Modifier.height(8.dp))
            Text(
                "Limits only work after you switch on the FocusGuard accessibility service. " +
                    "It only checks which app is open. It never reads your screen, messages or passwords.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(12.dp))
            Button(onClick = onOpenAccessibility, shape = RoundedCornerShape(14.dp)) {
                Text("Open accessibility settings")
            }
        } else {
            Spacer(Modifier.height(2.dp))
            Text(
                "FocusGuard is watching your rules in the background.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun SwitchRow(title: String, description: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.height(2.dp))
            Text(
                description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Spacer(Modifier.width(14.dp))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun RuleRow(app: InstalledApp, dailyMin: Int?, preset: SessionPreset?, onClick: () -> Unit) {
    val productive = app.category == AppCategory.PRODUCTIVE
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .clickable(enabled = !productive, onClick = onClick)
            .padding(vertical = 8.dp, horizontal = 2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        FgAppIcon(app.icon, app.category.color(), 46.dp)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(
                app.name,
                style = MaterialTheme.typography.titleSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(Modifier.height(5.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                when {
                    productive -> FgPill("Productive \u00B7 never limited", FgProductive)
                    dailyMin == null && preset == null -> Text(
                        "No rules",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    else -> {
                        if (dailyMin != null) {
                            FgPill(
                                if (dailyMin == 0) "Blocked completely"
                                else "Daily \u00B7 " + formatDuration(dailyMin * 60_000L),
                                MaterialTheme.colorScheme.primary
                            )
                        }
                        if (preset != null) {
                            FgPill("Session \u00B7 " + preset.label, MaterialTheme.colorScheme.primary)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun RuleDialog(
    appName: String,
    dailyMin: Int?,
    preset: SessionPreset?,
    onDaily: (Int?) -> Unit,              // null = no daily limit
    onPreset: (SessionPreset?) -> Unit,   // null = session guard off
    onDismiss: () -> Unit
) {
    val dailyOptions = listOf(
        "No daily limit" to null,
        "1 minute (for testing)" to 1,
        "15 minutes" to 15,
        "30 minutes" to 30,
        "1 hour" to 60,
        "2 hours" to 120,
        "Block completely" to 0
    )
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(28.dp),
        title = { Text(appName, style = MaterialTheme.typography.headlineSmall) },
        text = {
            Column(
                Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                FgSectionTitle("Daily limit (total time per day)")
                dailyOptions.forEach { (label, minutes) ->
                    FgChoiceRow(label, selected = dailyMin == minutes) { onDaily(minutes) }
                }
                Spacer(Modifier.height(8.dp))
                FgSectionTitle("Session guard (time in one sitting)")
                FgChoiceRow("Off", selected = preset == null) { onPreset(null) }
                SessionPreset.values().forEach { p ->
                    FgChoiceRow(
                        "${p.label}: nudge ${p.nudgeMin}, pause ${p.frictionMin}, block ${p.blockMin} min",
                        selected = preset == p
                    ) { onPreset(p) }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Done") } }
    )
}
