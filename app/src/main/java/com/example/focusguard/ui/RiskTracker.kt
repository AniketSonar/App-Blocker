package com.example.focusguard

import android.content.Context
import android.os.SystemClock
import java.util.Locale

class RiskResult(val startMs: Long, val probability: Double, val model: RiskModel)

/**
 * Collects the ML data, for EVERY non-productive app (not only the ones with rules):
 *   "risk_start"  when a sitting begins: the exact numbers given to the model + its prediction
 *   "session_end" when the sitting is over: how long it really lasted (the answer)
 * Both go to events.csv, joined by start_ms. train_risk_model.py turns them into a model.
 * Logging what the phone actually used is what keeps training and real use identical.
 */
class RiskTracker(private val context: Context, private val usage: UsageRepository) {

    val model: RiskModel = RiskModel.load(context)

    private class Sitting(val pkg: String, val startWall: Long, val startElapsed: Long) {
        var lastActive: Long = startElapsed
    }

    private val sittings = HashMap<String, Sitting>()
    private var currentPkg: String? = null

    /** Call whenever an app comes to the front. Returns a result only when a NEW sitting starts. */
    fun onForeground(pkg: String): RiskResult? {
        val now = SystemClock.elapsedRealtime()
        val prev = currentPkg
        if (prev != null && prev != pkg) sittings[prev]?.lastActive = now
        currentPkg = pkg
        flushExpired()

        if (usage.categoryOf(pkg) == AppCategory.PRODUCTIVE) return null

        val existing = sittings[pkg]
        if (existing != null) {
            if (prev == pkg) return null                              // still the same sitting
            if (now - existing.lastActive <= GAP_MS) return null      // came back within 2 minutes
            finish(existing)                                          // the old one is over
        }

        val wall = System.currentTimeMillis()
        sittings[pkg] = Sitting(pkg, wall, now)

        val features = LiveFeatures.compute(context, usage, pkg)
        val p = model.predict(features)
        EventLog.log(context, "risk_start", pkg, 0, describe(features, p, wall))
        return RiskResult(wall, p, model)
    }

    /** The user left the current app (screen off, or opened FocusGuard). */
    fun onLeave() {
        val now = SystemClock.elapsedRealtime()
        currentPkg?.let { sittings[it]?.lastActive = now }
        currentPkg = null
    }

    /** Writes "session_end" for sittings that were left more than 2 minutes ago. */
    fun flushExpired() {
        val now = SystemClock.elapsedRealtime()
        val iterator = sittings.entries.iterator()
        while (iterator.hasNext()) {
            val s = iterator.next().value
            if (s.pkg != currentPkg && now - s.lastActive > GAP_MS) {
                finish(s)
                iterator.remove()
            }
        }
    }

    private fun finish(s: Sitting) {
        val durSec = ((s.lastActive - s.startElapsed) / 1000).coerceAtLeast(0)
        EventLog.log(context, "session_end", s.pkg, durSec / 60, "start_ms=${s.startWall};dur_sec=$durSec")
    }

    private fun describe(features: Map<String, Double>, p: Double, startWall: Long): String =
        "start_ms=$startWall;p=${fmt(p)};model=${model.version};trained=${if (model.trained) 1 else 0};" +
            RiskModel.FEATURES.joinToString(";") { "$it=${fmt(features[it] ?: 0.0)}" }

    private fun fmt(v: Double) = String.format(Locale.US, "%.4f", v)

    companion object {
        private const val GAP_MS = 2 * 60_000L
    }
}
