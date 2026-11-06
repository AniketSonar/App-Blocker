package com.example.focusguard

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

/**
 * First-run setup. Three steps, each with a plain explanation of WHY it is needed.
 * Only step 1 is required; the others make blocking work reliably.
 */
@Composable
fun OnboardingScreen(
    usageOk: Boolean,
    serviceOk: Boolean,
    batteryOk: Boolean,
    notificationsOk: Boolean,
    onUsage: () -> Unit,
    onService: () -> Unit,
    onBattery: () -> Unit,
    onNotifications: () -> Unit,
    onFinish: () -> Unit
) {
    val doneCount = listOf(usageOk, serviceOk, batteryOk, notificationsOk).count { it }
    val muted = MaterialTheme.colorScheme.onSurfaceVariant

    Column(Modifier.fillMaxSize().systemBarsPadding()) {
        Column(
            Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                FgBreathing(MaterialTheme.colorScheme.primary, 150.dp)
            }
            FgSectionTitle("Welcome")
            Text("Let's set up FocusGuard", style = MaterialTheme.typography.headlineLarge)
            Text(
                "Four quick steps. Everything stays on your phone.",
                style = MaterialTheme.typography.bodyLarge,
                color = muted
            )

            SetupStep(
                number = 1,
                title = "See your usage",
                text = "Lets FocusGuard read how long each app is used, so your dashboard and goals are accurate.",
                done = usageOk,
                required = true,
                actionLabel = "Allow usage access",
                onAction = onUsage
            )
            SetupStep(
                number = 2,
                title = "Turn on blocking",
                text = "Lets FocusGuard notice which app just opened, so it can pause it at your limit. " +
                    "It never reads your screen, messages or passwords.",
                done = serviceOk,
                required = false,
                actionLabel = "Open accessibility settings",
                onAction = onService
            )
            SetupStep(
                number = 3,
                title = "Keep it running",
                text = "Some phones stop background apps to save battery. Set FocusGuard to unrestricted. " +
                    "On Xiaomi, Oppo, Realme, Vivo and Samsung also allow Autostart or background activity in App info.",
                done = batteryOk,
                required = false,
                actionLabel = "Open battery settings",
                onAction = onBattery
            )
            SetupStep(
                number = 4,
                title = "Gentle reminders",
                text = "A quiet message when you have been in one app for a while, before any pause screen appears.",
                done = notificationsOk,
                required = false,
                actionLabel = "Allow notifications",
                onAction = onNotifications
            )
        }

        Column(Modifier.padding(horizontal = 20.dp, vertical = 14.dp)) {
            FgPrimaryButton(
                if (doneCount == 4) "Start using FocusGuard" else "Continue",
                onFinish,
                enabled = usageOk
            )
            Spacer(Modifier.height(8.dp))
            Text(
                "$doneCount of 4 done. You can change all of this later.",
                style = MaterialTheme.typography.bodySmall,
                color = muted,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}

@Composable
private fun SetupStep(
    number: Int,
    title: String,
    text: String,
    done: Boolean,
    required: Boolean,
    actionLabel: String,
    onAction: () -> Unit
) {
    val primary = MaterialTheme.colorScheme.primary
    FgCard {
        Row(verticalAlignment = Alignment.Top) {
            Box(
                Modifier
                    .size(34.dp)
                    .clip(CircleShape)
                    .background(if (done) primary else androidx.compose.ui.graphics.Color.Transparent)
                    .border(1.5.dp, if (done) primary else MaterialTheme.colorScheme.outline, CircleShape),
                contentAlignment = Alignment.Center
            ) {
                if (done) {
                    Icon(
                        Icons.Filled.Check,
                        contentDescription = "Done",
                        tint = MaterialTheme.colorScheme.onPrimary,
                        modifier = Modifier.size(18.dp)
                    )
                } else {
                    Text("$number", style = MaterialTheme.typography.labelLarge)
                }
            }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(title, style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.width(8.dp))
                    if (!required) FgPill("Recommended", MaterialTheme.colorScheme.secondary)
                }
                Spacer(Modifier.height(6.dp))
                Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(12.dp))
                if (done) {
                    Text("Done", style = MaterialTheme.typography.labelLarge, color = FgProductive)
                } else {
                    Button(onClick = onAction, shape = RoundedCornerShape(14.dp)) { Text(actionLabel) }
                }
            }
        }
    }
}
