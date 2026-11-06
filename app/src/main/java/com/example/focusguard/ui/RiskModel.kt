package com.example.focusguard

import android.content.Context
import org.json.JSONObject
import kotlin.math.abs
import kotlin.math.exp

/**
 * The on-device "doomscroll risk" model: the chance that the sitting that is starting
 * now will last 20+ minutes.
 *
 * It is a logistic regression, which is only a list of numbers (weights), so no extra library
 * is needed. The weights are learned on your computer by train_risk_model.py and saved in
 * app/src/main/assets/risk_model.json.
 *
 * Without that file the app uses a hand-set "prior" that never interrupts you. It only logs.
 */
class RiskModel private constructor(
    val version: String,
    val trained: Boolean,
    private val mean: DoubleArray,
    private val std: DoubleArray,
    private val weights: DoubleArray,
    private val bias: Double,
    val threshold: Double
) {
    fun predict(features: Map<String, Double>): Double =
        predictArray(DoubleArray(FEATURES.size) { features[FEATURES[it]] ?: 0.0 })

    fun predictArray(x: DoubleArray): Double {
        var z = bias
        for (i in x.indices) z += weights[i] * (x[i] - mean[i]) / std[i]
        return 1.0 / (1.0 + exp(-z))
    }

    companion object {
        /**
         * THE CONTRACT. Same names and same ORDER as FEATURES in train_risk_model.py.
         * If you change this list, change the Python list and retrain.
         */
        val FEATURES = listOf(
            "hour_sin", "hour_cos", "dow_sin", "dow_cos", "is_distracting",
            "min_since_last_session", "apps_last_10min",
            "sessions_so_far_today", "distracting_min_so_far_today"
        )

        fun load(context: Context): RiskModel {
            return try {
                val text = context.assets.open("risk_model.json").bufferedReader().use { it.readText() }
                parse(JSONObject(text)) ?: prior()
            } catch (e: Exception) {
                prior()        // no file yet (normal at the start) or the file could not be read
            }
        }

        /** Hand-set starting guess. threshold > 1 means it can never trigger a pause. */
        fun prior() = RiskModel(
            version = "prior-v0",
            trained = false,
            mean = DoubleArray(FEATURES.size),
            std = DoubleArray(FEATURES.size) { 1.0 },
            weights = doubleArrayOf(0.0, 0.5, 0.0, 0.0, 0.9, 0.0, 0.0, 0.0, 0.01),
            bias = -1.8,
            threshold = 1.1
        )

        /** Returns null if the file does not match this app (wrong features, bad numbers, failed self-test). */
        private fun parse(j: JSONObject): RiskModel? {
            val names = j.getJSONArray("features")
            if (names.length() != FEATURES.size) return null
            for (i in FEATURES.indices) if (names.getString(i) != FEATURES[i]) return null

            fun numbers(key: String): DoubleArray {
                val a = j.getJSONArray(key)
                return DoubleArray(a.length()) { a.getDouble(it) }
            }
            val mean = numbers("mean")
            val std = numbers("std")
            val weights = numbers("weights")
            if (mean.size != FEATURES.size || std.size != FEATURES.size || weights.size != FEATURES.size) return null
            if (std.any { it <= 0.0 }) return null

            val model = RiskModel(
                version = j.getString("version"),
                trained = j.optBoolean("trained", false),
                mean = mean, std = std, weights = weights,
                bias = j.getDouble("bias"),
                threshold = j.getDouble("threshold")
            )

            // Self-test: the file contains one example computed in Python. The phone must get the same answer.
            val check = j.optJSONObject("check")
            if (check != null) {
                val x = check.getJSONArray("x")
                if (x.length() != FEATURES.size) return null
                val got = model.predictArray(DoubleArray(x.length()) { x.getDouble(it) })
                if (abs(got - check.getDouble("p")) > 1e-3) return null
            }
            return model
        }
    }
}
