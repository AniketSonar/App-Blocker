package com.example.focusguard

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

class BlockActivity : ComponentActivity() {

    companion object {
        const val EXTRA_PKG = "pkg"
        const val EXTRA_MESSAGE = "message"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val pkg = intent.getStringExtra(EXTRA_PKG).orEmpty()
        val message = intent.getStringExtra(EXTRA_MESSAGE).orEmpty()
        val appName = try {
            packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString()
        } catch (e: Exception) {
            "This app"
        }

        // The Back button must not return to the blocked app.
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() = goHome()
        })

        setContent {
            FocusGuardTheme {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    BlockScreen(appName, message, onHome = { goHome() })
                }
            }
        }
    }

    // If the user leaves this screen in any way, close it. The guard shows it
    // again if they open the blocked app again.
    override fun onStop() {
        super.onStop()
        finish()
    }

    private fun goHome() {
        startActivity(
            Intent(Intent.ACTION_MAIN)
                .addCategory(Intent.CATEGORY_HOME)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
        finish()
    }
}

@Composable
private fun BlockScreen(appName: String, message: String, onHome: () -> Unit) {
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    val primary = MaterialTheme.colorScheme.primary

    Column(
        Modifier.fillMaxSize().systemBarsPadding().padding(horizontal = 28.dp, vertical = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(Modifier.weight(1f))
        Box(contentAlignment = Alignment.Center) {
            FgBreathing(primary, 230.dp)
            FgPauseGlyph(primary, 34.dp)
        }
        Spacer(Modifier.height(28.dp))
        FgSectionTitle("Take a break")
        Spacer(Modifier.height(10.dp))
        Text(
            "$appName is paused",
            style = MaterialTheme.typography.headlineMedium,
            textAlign = TextAlign.Center
        )
        Spacer(Modifier.height(14.dp))
        Text(message, style = MaterialTheme.typography.bodyLarge, color = muted, textAlign = TextAlign.Center)
        Spacer(Modifier.height(8.dp))
        Text(
            "Stretch, drink some water, or look away from the screen.",
            style = MaterialTheme.typography.bodyMedium,
            color = muted,
            textAlign = TextAlign.Center
        )
        Spacer(Modifier.weight(1f))
        FgPrimaryButton("Back to home screen", onHome)
    }
}
