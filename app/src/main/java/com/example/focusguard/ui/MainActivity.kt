package com.example.focusguard   // <- keep the package line your project already had

import android.Manifest
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.core.app.NotificationManagerCompat

class MainActivity : ComponentActivity() {

    private val vm: UsageViewModel by viewModels()
    private val insightsVm: InsightsViewModel by viewModels()

    private var usageOk by mutableStateOf(false)      // usage access granted?
    private var serviceOn by mutableStateOf(false)    // accessibility service enabled?
    private var batteryOk by mutableStateOf(false)    // battery optimisation switched off for us?
    private var setupDone by mutableStateOf(false)    // user finished the first-run setup?
    private var notificationsOk by mutableStateOf(true)   // notifications allowed?

    private val notificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            notificationsOk = granted
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()   // if this line shows red, just delete it

        setContent {
            FocusGuardTheme {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    if (!usageOk || !setupDone) {
                        OnboardingScreen(
                            usageOk = usageOk,
                            serviceOk = serviceOn,
                            batteryOk = batteryOk,
                            notificationsOk = notificationsOk,
                            onUsage = { open(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)) },
                            onService = { openAccessibility() },
                            onBattery = { openBatterySettings() },
                            onNotifications = { askNotifications() },
                            onFinish = {
                                RulesStore.setSetupDone(this@MainActivity, true)
                                setupDone = true
                            }
                        )
                    } else {
                        var tab by rememberSaveable { mutableStateOf(0) }
                        Column(Modifier.fillMaxSize()) {
                            Box(Modifier.weight(1f)) {
                                when (tab) {
                                    0 -> {
                                        val state by vm.state.collectAsState()
                                        DashboardScreen(
                                            state = state,
                                            onRefresh = { vm.refresh() },
                                            protectionOn = serviceOn,
                                            onFixProtection = { openAccessibility() }
                                        )
                                    }
                                    1 -> {
                                        val state by insightsVm.state.collectAsState()
                                        InsightsScreen(state = state)
                                    }
                                    else -> RulesScreen(
                                        serviceOn = serviceOn,
                                        onOpenAccessibility = { openAccessibility() }
                                    )
                                }
                            }
                            FgNavigationBar(selected = tab, onSelect = { tab = it })
                        }
                    }
                }
            }
        }
    }

    // Runs every time the user comes back to the app (for example from Settings).
    override fun onResume() {
        super.onResume()
        usageOk = UsagePermission.hasUsageAccess(this)
        serviceOn = AppMonitorService.isEnabled(this)
        batteryOk = (getSystemService(Context.POWER_SERVICE) as PowerManager)
            .isIgnoringBatteryOptimizations(packageName)
        setupDone = RulesStore.isSetupDone(this)
        notificationsOk = NotificationManagerCompat.from(this).areNotificationsEnabled()
        if (usageOk) {
            vm.refresh()
            insightsVm.refresh()
        }
    }

    private fun openAccessibility() = open(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))

    private fun askNotifications() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !RulesStore.wasNotificationAsked(this)) {
            RulesStore.setNotificationAsked(this)
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            // Android shows its pop-up only once, so after that we open the settings page.
            open(
                Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                    .putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
            )
        }
    }

    private fun openBatterySettings() {
        // Not every phone has the battery list screen, so fall back to this app's info page.
        open(
            Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS),
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName"))
        )
    }

    private fun open(intent: Intent, fallback: Intent? = null) {
        try {
            startActivity(intent)
        } catch (e: Exception) {
            if (fallback != null) {
                try {
                    startActivity(fallback)
                } catch (_: Exception) {
                }
            }
        }
    }
}

@Composable
private fun FgNavigationBar(selected: Int, onSelect: (Int) -> Unit) {
    val primary = MaterialTheme.colorScheme.primary
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    val items = listOf(
        "Dashboard" to FgGlyph.GRID,
        "Insights" to FgGlyph.BARS,
        "Limits" to FgGlyph.TIMER
    )
    Column {
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        NavigationBar(containerColor = MaterialTheme.colorScheme.surface, tonalElevation = 0.dp) {
            items.forEachIndexed { index, (label, glyph) ->
                NavigationBarItem(
                    selected = selected == index,
                    onClick = { onSelect(index) },
                    icon = { FgNavGlyph(glyph, if (selected == index) primary else muted) },
                    label = { Text(label, style = MaterialTheme.typography.labelMedium) },
                    colors = NavigationBarItemDefaults.colors(
                        indicatorColor = primary.copy(alpha = 0.12f),
                        selectedTextColor = primary,
                        unselectedTextColor = muted
                    )
                )
            }
        }
    }
}
