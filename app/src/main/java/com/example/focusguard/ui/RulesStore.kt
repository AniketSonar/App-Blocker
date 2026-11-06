package com.example.focusguard

import android.content.Context

/**
 * "Session guard" presets: how long one continuous sitting in an app may last.
 * nudge = small message, friction = pause screen, block = app closed for a cooldown.
 */
enum class SessionPreset(
    val id: String,
    val label: String,
    val nudgeMin: Int,
    val frictionMin: Int,
    val blockMin: Int,
    val cooldownMin: Int
) {
    GENTLE("gentle", "Gentle", 10, 20, 30, 15),
    STRICT("strict", "Strict", 5, 10, 15, 30),
    TEST("test", "Test", 1, 2, 3, 1);

    companion object {
        fun fromId(id: String?): SessionPreset? = values().firstOrNull { it.id == id }
    }
}

/** Saves the user's rules on the phone (SharedPreferences, no extra dependency). */
object RulesStore {
    private const val PREFS = "focusguard_rules"
    private const val LIMIT_PREFIX = "limit_"
    private const val SESSION_PREFIX = "session_"
    private const val COOLDOWN_PREFIX = "cooldown_"
    private const val KEY_ENABLED = "blocking_enabled"

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    // ---- master switch -----------------------------------------------------------
    fun isEnabled(context: Context): Boolean = prefs(context).getBoolean(KEY_ENABLED, true)

    fun setEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_ENABLED, enabled).apply()
    }

    // ---- daily limit (minutes per day, 0 = always blocked) ---------------------------
    fun getLimitMin(context: Context, pkg: String): Int? {
        val value = prefs(context).getInt(LIMIT_PREFIX + pkg, -1)
        return if (value >= 0) value else null
    }

    fun setLimitMin(context: Context, pkg: String, minutes: Int) {
        prefs(context).edit().putInt(LIMIT_PREFIX + pkg, minutes).apply()
    }

    fun remove(context: Context, pkg: String) {
        prefs(context).edit().remove(LIMIT_PREFIX + pkg).apply()
    }

    fun allRules(context: Context): Map<String, Int> {
        val result = HashMap<String, Int>()
        for ((key, value) in prefs(context).all) {
            if (key.startsWith(LIMIT_PREFIX) && value is Int) {
                result[key.removePrefix(LIMIT_PREFIX)] = value
            }
        }
        return result
    }

    // ---- session guard ------------------------------------------------------------------
    fun getSessionPreset(context: Context, pkg: String): SessionPreset? =
        SessionPreset.fromId(prefs(context).getString(SESSION_PREFIX + pkg, null))

    fun setSessionPreset(context: Context, pkg: String, preset: SessionPreset?) {
        val editor = prefs(context).edit()
        if (preset == null) editor.remove(SESSION_PREFIX + pkg)
        else editor.putString(SESSION_PREFIX + pkg, preset.id)
        editor.apply()
    }

    fun allSessionPresets(context: Context): Map<String, SessionPreset> {
        val result = HashMap<String, SessionPreset>()
        for ((key, value) in prefs(context).all) {
            if (key.startsWith(SESSION_PREFIX) && value is String) {
                SessionPreset.fromId(value)?.let { result[key.removePrefix(SESSION_PREFIX)] = it }
            }
        }
        return result
    }

    // ---- smart pause (uses the ML model, only when a trained model exists) ------------------
    private const val KEY_SMART_PAUSE = "smart_pause"

    fun isSmartPauseEnabled(context: Context): Boolean = prefs(context).getBoolean(KEY_SMART_PAUSE, true)

    fun setSmartPause(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_SMART_PAUSE, enabled).apply()
    }

    // ---- first-run setup finished? ---------------------------------------------------------------
    private const val KEY_SETUP_DONE = "setup_done_v2"   // new key: shows the 4-step setup once more

    fun isSetupDone(context: Context): Boolean = prefs(context).getBoolean(KEY_SETUP_DONE, false)

    fun setSetupDone(context: Context, done: Boolean) {
        prefs(context).edit().putBoolean(KEY_SETUP_DONE, done).apply()
    }

    // ---- notification permission: Android only shows its pop-up once --------------------------
    private const val KEY_NOTIF_ASKED = "notification_asked"

    fun wasNotificationAsked(context: Context): Boolean = prefs(context).getBoolean(KEY_NOTIF_ASKED, false)

    fun setNotificationAsked(context: Context) {
        prefs(context).edit().putBoolean(KEY_NOTIF_ASKED, true).apply()
    }

    // ---- cooldown after a session block (wall-clock time in ms) -----------------------
    fun getCooldownUntil(context: Context, pkg: String): Long =
        prefs(context).getLong(COOLDOWN_PREFIX + pkg, 0L)

    fun setCooldownUntil(context: Context, pkg: String, untilMs: Long) {
        prefs(context).edit().putLong(COOLDOWN_PREFIX + pkg, untilMs).apply()
    }
}
