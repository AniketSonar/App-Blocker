package com.example.focusguard

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

/**
 * The "pause" screen. It does not block you. It makes you wait and answer one question:
 * "Why are you here right now?" Your answer goes to the event log (useful ML data).
 *
 * Two kinds:
 *  - normal: after a long sitting (the session ladder), 15 seconds
 *  - risk:   "smart pause" when the ML model expects a long sitting, 8 seconds
 */
class FrictionActivity : ComponentActivity() {

    companion object {
        const val EXTRA_PKG = "pkg"
        const val EXTRA_SESSION_MIN = "session_min"
        const val EXTRA_RISK = "risk"
        private const val COUNTDOWN_SECONDS = 15
        private const val RISK_COUNTDOWN_SECONDS = 8
    }

    private var pkg = ""
    private var sessionMin = 0L
    private var risk = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        pkg = intent.getStringExtra(EXTRA_PKG).orEmpty()
        sessionMin = intent.getLongExtra(EXTRA_SESSION_MIN, 0L)
        risk = intent.getBooleanExtra(EXTRA_RISK, false)
        val appName = try {
            packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString()
        } catch (e: Exception) {
            "this app"
        }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() = leave()
        })

        setContent {
            FocusGuardTheme {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    FrictionScreen(
                        appName = appName,
                        sessionMin = sessionMin,
                        risk = risk,
                        totalSeconds = if (risk) RISK_COUNTDOWN_SECONDS else COUNTDOWN_SECONDS,
                        onLeave = { leave() },
                        onContinue = { reason ->
                            EventLog.log(
                                this, if (risk) "risk_pause_continue" else "friction_continue",
                                pkg, sessionMin, reason
                            )
                            finish()      // back to the app underneath
                        }
                    )
                }
            }
        }
    }

    override fun onStop() {
        super.onStop()
        finish()
    }

    private fun leave() {
        EventLog.log(this, if (risk) "risk_pause_leave" else "friction_leave", pkg, sessionMin)
        startActivity(
            Intent(Intent.ACTION_MAIN)
                .addCategory(Intent.CATEGORY_HOME)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
        finish()
    }
}

@Composable
private fun FrictionScreen(
    appName: String,
    sessionMin: Long,
    risk: Boolean,
    totalSeconds: Int,
    onLeave: () -> Unit,
    onContinue: (String) -> Unit
) {
    var left by remember { mutableStateOf(totalSeconds) }
    var reason by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) {
        while (left > 0) {
            delay(1_000)
            left--
        }
    }
    val reasons = listOf("Work or study", "Relaxing on purpose", "Just habit")
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    val primary = MaterialTheme.colorScheme.primary

    Column(
        Modifier
            .fillMaxSize()
            .systemBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 28.dp, vertical = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(Modifier.height(8.dp))
        Box(contentAlignment = Alignment.Center) {
            FgBreathing(primary, 190.dp)
            Text(
                if (left > 0) "$left" else "\u2713",
                style = MaterialTheme.typography.displaySmall,
                color = primary
            )
        }
        Spacer(Modifier.height(16.dp))
        FgSectionTitle(if (risk) "Quick check" else "Pause")
        Spacer(Modifier.height(8.dp))
        Text(
            if (risk) "Before you scroll" else "Pause for a moment",
            style = MaterialTheme.typography.headlineMedium,
            textAlign = TextAlign.Center
        )
        Spacer(Modifier.height(10.dp))
        Text(
            if (risk) "Your own history says long sessions in $appName are likely around now."
            else "You have been on $appName for $sessionMin minutes in a row.",
            style = MaterialTheme.typography.bodyLarge,
            color = muted,
            textAlign = TextAlign.Center
        )
        Spacer(Modifier.height(24.dp))
        Text("Why are you here right now?", style = MaterialTheme.typography.titleSmall)
        Spacer(Modifier.height(10.dp))
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            reasons.forEach { option ->
                FgChoiceRow(option, selected = reason == option) { reason = option }
            }
        }
        Spacer(Modifier.height(26.dp))
        FgPrimaryButton("Not now, go home", onLeave)
        Spacer(Modifier.height(6.dp))
        TextButton(
            onClick = { reason?.let(onContinue) },
            enabled = left == 0 && reason != null
        ) {
            Text(
                when {
                    left > 0 -> "Continue in ${left}s"
                    reason == null -> "Pick a reason to continue"
                    else -> "Continue anyway"
                }
            )
        }
    }
}
