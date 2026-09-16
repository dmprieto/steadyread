package dev.spike.autoscroll

import android.content.Context

/**
 * App-level settings, `MODE_PRIVATE` SharedPreferences (no INTERNET, on-device only).
 *
 * Proximity: whether the proximity entry trigger is enabled. **Opt-in (default off)** — a new,
 * deliberately-sensitive control (its accepted cost is that a brief zone crossing starts the scroll),
 * so a user who does not want it is never surprised by it. A phone no-touch user turns it on once at setup.
 *
 * Panel-collapse delay: the post-shade-dismiss wait before the notification-start press
 * ([AutoScrollService.startClearingPanels]). Overridable because host readiness is unobservable to this
 * app by construction (eventTypes=0, canRetrieveWindowContent=false), so it cannot be
 * auto-detected; the default works on the devices measured, and this override is the field
 * safety valve for a **caregiver or integrator**, not the target user. Defaults to
 * [AutoScrollService.PANEL_COLLAPSE_MS] — one source of truth.
 */
class SettingsStore(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    var proximityEnabled: Boolean
        get() = prefs.getBoolean(KEY_PROXIMITY, false)
        set(value) { prefs.edit().putBoolean(KEY_PROXIMITY, value).apply() }

    var panelCollapseMs: Long
        get() = prefs.getLong(KEY_PANEL_COLLAPSE_MS, AutoScrollService.PANEL_COLLAPSE_MS)
        set(value) { prefs.edit().putLong(KEY_PANEL_COLLAPSE_MS, value).apply() }

    /**
     * The rule-7 haptic acknowledgement (a short tick on each notification-action tap). **Default ON**
     * — unlike proximity, this is the acknowledgement the platform doesn't supply, not a new input, so
     * it is opt-out, not opt-in. A no-op anyway on hardware without a vibrator.
     */
    var hapticEnabled: Boolean
        get() = prefs.getBoolean(KEY_HAPTIC, true)
        set(value) { prefs.edit().putBoolean(KEY_HAPTIC, value).apply() }

    /**
     * Scrolling speed in dp/s, the discrete stepped control ([SettingsActivity.SPEED_STEPS]; default
     * [ScrollEngine.DEFAULT_SPEED_DP] — one source of truth for the default). A single dp/s cannot serve
     * varying content, so this is a user-facing control, not a fixed value. Stored as a float
     * (SharedPreferences has no double); the values are exact steps, so the round-trip is lossless here.
     */
    var speedDp: Double
        get() = prefs.getFloat(KEY_SPEED, ScrollEngine.DEFAULT_SPEED_DP.toFloat()).toDouble()
        set(value) { prefs.edit().putFloat(KEY_SPEED, value.toFloat()).apply() }

    companion object {
        private const val PREFS_NAME = "app_settings"
        private const val KEY_PROXIMITY = "proximity_enabled"
        private const val KEY_PANEL_COLLAPSE_MS = "panel_collapse_ms"
        private const val KEY_HAPTIC = "haptic_enabled"
        private const val KEY_SPEED = "speed_dp"
    }
}
