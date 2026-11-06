package com.example.focusguard

import android.accessibilityservice.AccessibilityService
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.view.accessibility.AccessibilityEvent
import android.view.inputmethod.InputMethodManager
import android.widget.Toast
import androidx.core.content.ContextCompat
import kotlin.random.Random

/**
 * The guard at the door.
 *
 * It tracks SESSIONS: one continuous sitting in an app (gaps shorter than 2 minutes
 * count as the same sitting). For apps with a "session guard" it escalates:
 *   nudge (small message) -> friction (pause screen) -> block + cooldown.
 * It also enforces the daily limit. Productive apps (Zoom, Meet, Docs...) are never touched.
 */
class AppMonitorService : AccessibilityService() {

    private class Session(var start: Long, var lastActive: Long) {
        var nudged = false
        var frictionShown = false
    }

    private lateinit var usage: UsageRepository
    private lateinit var risk: RiskTracker
    // All the real work runs on this background thread, so the phone never waits for FocusGuard.
    // Everything below (sessions, timers, logging) is only ever touched from this one thread.
    private val worker = HandlerThread("focusguard-worker").apply { start() }
    private val handler = Handler(worker.looper)
    private val mainHandler = Handler(Looper.getMainLooper())   // only for things that need the main thread
    private val sessions = HashMap<String, Session>()
    private var currentPkg: String? = null
    private var lastOverlayAt = 0L
    private var receiverRegistered = false

    private val recheck = Runnable { currentPkg?.let { evaluate(it) } }
    private val flushRisk = Runnable { risk.flushExpired() }

    // Keyboards and the notification shade also create windows; ignore them.
    private val ignored: Set<String> by lazy {
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        val keyboards = imm.enabledInputMethodList.map { it.packageName }
        (keyboards + "com.android.systemui").toSet()
    }

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                Intent.ACTION_SCREEN_OFF -> handler.post { leaveCurrent() }
                Intent.ACTION_USER_PRESENT -> {
                    DayCounters.incrementUnlocks(applicationContext)   // data for ML + insights
                    handler.postDelayed({
                        val pkg = usage.currentForegroundPackage()
                        if (pkg != null && pkg != packageName && pkg !in ignored) onForeground(pkg)
                    }, 700)
                }
            }
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        usage = UsageRepository(applicationContext)
        risk = RiskTracker(applicationContext, usage)     // collects the ML data
        if (!receiverRegistered) {
            val filter = IntentFilter().apply {
                addAction(Intent.ACTION_SCREEN_OFF)
                addAction(Intent.ACTION_USER_PRESENT)
            }
            ContextCompat.registerReceiver(this, screenReceiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
            receiverRegistered = true
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null || event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        val pkg = event.packageName?.toString() ?: return
        val screen = event.className?.toString().orEmpty()
        handler.post { handleWindowChange(pkg, screen) }      // return to the system immediately
    }

    private fun handleWindowChange(pkg: String, screen: String) {
        if (pkg == packageName) {
            if (screen.endsWith("BlockActivity") || screen.endsWith("FrictionActivity")) return
            leaveCurrent()          // the user opened FocusGuard itself: they left the other app
            return
        }
        if (pkg in ignored) return
        onForeground(pkg)
    }

    /** The user left the current app (screen off, or opened FocusGuard). */
    private fun leaveCurrent() {
        handler.removeCallbacks(recheck)
        currentPkg?.let { sessions[it]?.lastActive = SystemClock.elapsedRealtime() }
        currentPkg = null
        risk.onLeave()
        handler.removeCallbacks(flushRisk)
        handler.postDelayed(flushRisk, 150_000)     // close the sitting's record after 2.5 minutes away
    }

    /** An app came to the front. Decide if this is a continuing or a new session. */
    private fun onForeground(pkg: String) {
        val now = SystemClock.elapsedRealtime()
        val prev = currentPkg
        if (prev != null && prev != pkg) sessions[prev]?.lastActive = now
        if (prev != pkg) {
            val old = sessions[pkg]
            if (old != null && now - old.lastActive > SESSION_GAP_MS) sessions.remove(pkg)
        }
        currentPkg = pkg

        val riskResult = risk.onForeground(pkg)     // logs the model's prediction (ML data)
        evaluate(pkg)                               // daily limit + session ladder
        if (riskResult != null) maybeRiskPause(pkg, riskResult)
    }

    /**
     * Smart pause: only when a TRAINED model says a long session is likely, the user has switched
     * the feature on, and the app has rules. 20% of high-risk cases are deliberately NOT interrupted
     * (holdout group). Comparing the two groups later shows whether the pause really helps.
     */
    private fun maybeRiskPause(pkg: String, r: RiskResult) {
        if (!r.model.trained || r.probability < r.model.threshold) return
        if (!RulesStore.isEnabled(this) || !RulesStore.isSmartPauseEnabled(this)) return
        if (RulesStore.getSessionPreset(this, pkg) == null && RulesStore.getLimitMin(this, pkg) == null) return

        if (Random.nextFloat() < HOLDOUT_SHARE) {
            EventLog.log(this, "risk_decision", pkg, 0, "start_ms=${r.startMs};shown=0;holdout=1")
            return
        }
        if (showFriction(pkg, 0, riskPause = true)) {
            EventLog.log(this, "risk_decision", pkg, 0, "start_ms=${r.startMs};shown=1;holdout=0")
        }
    }

    private fun evaluate(pkg: String) {
        handler.removeCallbacks(recheck)
        if (!RulesStore.isEnabled(this)) return
        if (usage.categoryOf(pkg) == AppCategory.PRODUCTIVE) return   // Zoom, Meet, Docs: never touched

        val dailyMin = RulesStore.getLimitMin(this, pkg)
        val preset = RulesStore.getSessionPreset(this, pkg)
        if (dailyMin == null && preset == null) return

        val name = labelOf(pkg)
        var nextCheckMs = Long.MAX_VALUE

        // 1) Cooling down after a session block?
        val cooldownLeftMs = RulesStore.getCooldownUntil(this, pkg) - System.currentTimeMillis()
        if (cooldownLeftMs > 0) {
            val minutes = (cooldownLeftMs + 59_999) / 60_000
            EventLog.log(this, "block_cooldown", pkg)
            showBlock(pkg, "Break time. You can open $name again in about $minutes min.")
            return
        }

        // 2) Daily limit used up?
        if (dailyMin != null) {
            val limitMs = dailyMin * 60_000L
            val usedMs = usage.usageTodayMs(pkg)
            if (usedMs >= limitMs) {
                EventLog.log(this, "block_daily", pkg)
                showBlock(
                    pkg,
                    if (dailyMin == 0) "You set this app to be fully blocked."
                    else "You used it for ${formatDuration(usedMs)} today. " +
                            "Your daily limit is ${formatDuration(limitMs)}."
                )
                return
            }
            nextCheckMs = minOf(nextCheckMs, limitMs - usedMs)
        }

        // 3) Session ladder
        if (preset != null) {
            val now = SystemClock.elapsedRealtime()
            val session = sessions.getOrPut(pkg) { Session(now, now) }
            val elapsedMs = now - session.start
            val elapsedMin = elapsedMs / 60_000
            val nudgeMs = preset.nudgeMin * 60_000L
            val frictionMs = preset.frictionMin * 60_000L
            val blockMs = preset.blockMin * 60_000L

            if (elapsedMs >= blockMs) {
                RulesStore.setCooldownUntil(this, pkg, System.currentTimeMillis() + preset.cooldownMin * 60_000L)
                sessions.remove(pkg)
                EventLog.log(this, "block_session", pkg, elapsedMin)
                showBlock(
                    pkg,
                    "You have been on $name for $elapsedMin minutes in a row. " +
                            "Take a ${preset.cooldownMin} minute break."
                )
                return
            }
            if (elapsedMs >= frictionMs) {
                if (!session.frictionShown && showFriction(pkg, elapsedMin)) {
                    session.frictionShown = true
                    session.nudged = true
                    EventLog.log(this, "friction_shown", pkg, elapsedMin)
                }
            } else if (elapsedMs >= nudgeMs && !session.nudged) {
                session.nudged = true
                EventLog.log(this, "nudge", pkg, elapsedMin)
                // A real notification; if notifications are off, fall back to the small message.
                if (!Notifier.nudge(this, name, elapsedMin, preset.frictionMin)) {
                    mainHandler.post {
                        Toast.makeText(this, "You have been on $name for $elapsedMin minutes", Toast.LENGTH_LONG).show()
                    }
                }
            }

            // Wake up at the next threshold that has not been reached yet.
            val upcoming = listOf(nudgeMs, frictionMs, blockMs).map { it - elapsedMs }.filter { it > 0 }.minOrNull()
            if (upcoming != null) nextCheckMs = minOf(nextCheckMs, upcoming)
        }

        if (nextCheckMs != Long.MAX_VALUE) handler.postDelayed(recheck, nextCheckMs + 300)
    }

    // ---- screens ---------------------------------------------------------------------
    private fun overlayAllowed(): Boolean {
        val now = SystemClock.elapsedRealtime()
        if (now - lastOverlayAt < 1500) return false     // avoid opening two screens at once
        lastOverlayAt = now
        return true
    }

    private fun showBlock(pkg: String, message: String) {
        if (!overlayAllowed()) return
        startActivity(
            Intent(this, BlockActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION)
                putExtra(BlockActivity.EXTRA_PKG, pkg)
                putExtra(BlockActivity.EXTRA_MESSAGE, message)
            }
        )
    }

    private fun showFriction(pkg: String, sessionMin: Long, riskPause: Boolean = false): Boolean {
        if (!overlayAllowed()) return false
        startActivity(
            Intent(this, FrictionActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION)
                putExtra(FrictionActivity.EXTRA_PKG, pkg)
                putExtra(FrictionActivity.EXTRA_SESSION_MIN, sessionMin)
                putExtra(FrictionActivity.EXTRA_RISK, riskPause)
            }
        )
        return true
    }

    private fun labelOf(pkg: String): String = try {
        packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString()
    } catch (e: Exception) {
        pkg
    }

    override fun onInterrupt() {}

    override fun onDestroy() {
        handler.removeCallbacks(recheck)
        handler.removeCallbacks(flushRisk)
        worker.quitSafely()
        if (receiverRegistered) {
            unregisterReceiver(screenReceiver)
            receiverRegistered = false
        }
        super.onDestroy()
    }

    companion object {
        private const val SESSION_GAP_MS = 2 * 60_000L
        private const val HOLDOUT_SHARE = 0.2f      // share of high-risk cases left alone on purpose

        /** Is the user's accessibility switch for FocusGuard turned on? */
        fun isEnabled(context: Context): Boolean {
            val setting = Settings.Secure.getString(
                context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
            ) ?: return false
            val component = ComponentName(context, AppMonitorService::class.java)
            return setting.split(':').any {
                it.equals(component.flattenToString(), ignoreCase = true) ||
                        it.equals(component.flattenToShortString(), ignoreCase = true)
            }
        }
    }
}