package dev.spike.autoscroll

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/**
 * The app's one activity and its launcher entry: a plain settings screen. The reading app is otherwise
 * driven from Settings > Accessibility and its own notification, so it would otherwise be activity-less.
 * This screen exists so a real (non-developer) user has somewhere to land: the enable step is the first
 * thing they meet, and it is the only home for the proximity opt-in.
 *
 * Three elements:
 *  1. Proximity opt-in toggle. The feature-detect-and-degrade is the only evidence the degrade path
 *     works, so it is kept as-is rather than rewritten (disabled with a note where there is no sensor,
 *     e.g. the SM-P200).
 *  2. An accessibility-service enable path — a button into system accessibility settings. Enabling the
 *     service is the first step a real user meets.
 *  3. A privacy-policy row, wired but inert — disabled, the URL a placeholder constant, until a policy
 *     is actually hosted. Turning it on is one change: set isEnabled true and launch the URL.
 *
 * UI is built programmatically: the app ships no layouts. Deliberately plain — this screen wants its
 * own accessibility-focused design pass.
 */
class SettingsActivity : Activity() {

    private lateinit var container: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = getString(R.string.app_name)
        container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val p = dp(16)
            setPadding(p, p, p, p)
        }
        val scroll = ScrollView(this).apply { addView(container) }
        setContentView(scroll)
        applyContentInsets(scroll)
        build()
        maybeRequestNotificationPermission()
    }

    /**
     * Own the window insets for this screen instead of relying on the edge-to-edge opt-out.
     *
     * Apps targeting SDK 35+ draw edge-to-edge. A theme opt-out (windowOptOutEdgeToEdgeEnforcement)
     * suppressed that on Android 15, but the opt-out is IGNORED on Android 16 (SDK 36+): the content
     * drew under the system bars and the top of this screen — the proximity toggle — was clipped off
     * (found on Android 16 in use). The opt-out is removed; instead the content is padded by the
     * system-bar insets on every edge-to-edge platform (SDK 35+), so Android 15 and Android 16 take the
     * same path and the fix is verifiable on Android 15 hardware. Framework APIs only; guarded so nothing
     * runs below SDK 35, where the framework still insets the content itself.
     */
    private fun applyContentInsets(content: View) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.VANILLA_ICE_CREAM) return
        window.setDecorFitsSystemWindows(false)
        content.setOnApplyWindowInsetsListener { v, insets ->
            val bars = insets.getInsets(WindowInsets.Type.systemBars())
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }
    }

    /**
     * The notification is a primary control surface, and POST_NOTIFICATIONS is denied by default on
     * Android 13+. Request it here so a real user meets the prompt (the same one a Play user would) rather
     * than the control notification silently never appearing. Framework API — no AndroidX dependency.
     */
    private fun maybeRequestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQ_POST_NOTIFICATIONS)
        }
    }

    private fun build() {
        container.removeAllViews()
        addProximityToggle()
        addSpeedControl()
        addAccessibilitySection()
        addAboutSection()
    }

    /**
     * The proximity entry-trigger opt-in (SettingsStore, off by default). Relocated from the spike's
     * PairingActivity; the feature-detect-and-degrade moves verbatim. Disabled with a note on hardware
     * that has no proximity sensor.
     */
    private fun addProximityToggle() {
        addHeader(getString(R.string.settings_header))
        val settings = SettingsStore(this)
        val hasProximity = getSystemService(android.hardware.SensorManager::class.java)
            ?.getDefaultSensor(android.hardware.Sensor.TYPE_PROXIMITY) != null

        container.addView(android.widget.Switch(this).apply {
            text = getString(R.string.proximity_toggle)
            textSize = 16f
            isEnabled = hasProximity
            // Set state BEFORE attaching the listener so this programmatic set does not fire it.
            isChecked = hasProximity && settings.proximityEnabled
            val v = dp(8)
            setPadding(0, v, 0, v)
            setOnCheckedChangeListener { _, checked ->
                settings.proximityEnabled = checked
                // Arm/disarm now if the service is bound; otherwise the persisted setting is applied
                // at the next onServiceConnected.
                AutoScrollService.live?.refreshProximity()
            }
        })
        if (!hasProximity) addBody(getString(R.string.proximity_unavailable))
    }

    /**
     * The scrolling-speed control. Discrete steps ([SPEED_STEPS]), **not a slider** — standing rule 1, and a
     * slider on a mounted device is what the edge slider was deferred over. Radio buttons so a choice is one
     * latched tap reachable by a user who cannot drag. A single dp/s cannot serve varying content, so the
     * pace is the user's to set. Applied live when the service is bound; persisted either way.
     */
    private fun addSpeedControl() {
        addBody(getString(R.string.speed_header))
        val settings = SettingsStore(this)
        val current = settings.speedDp
        val labels = intArrayOf(
            R.string.speed_slow, R.string.speed_normal, R.string.speed_fast,
        )
        val group = android.widget.RadioGroup(this)
        val buttons = SPEED_STEPS.mapIndexed { i, stepDp ->
            android.widget.RadioButton(this).apply {
                id = View.generateViewId()
                text = getString(labels[i])
                textSize = 16f
                val v = dp(8)
                setPadding(0, v, 0, v)
                // Set state BEFORE the listener is attached (below) so this does not fire it.
                isChecked = kotlin.math.abs(stepDp - current) < 0.001
            }.also { group.addView(it) }
        }
        group.setOnCheckedChangeListener { _, checkedId ->
            val idx = buttons.indexOfFirst { it.id == checkedId }
            if (idx in SPEED_STEPS.indices) {
                settings.speedDp = SPEED_STEPS[idx]
                AutoScrollService.live?.refreshSpeed()
            }
        }
        container.addView(group)
    }

    private fun addAccessibilitySection() {
        addHeader(getString(R.string.a11y_header))
        addBody(getString(R.string.a11y_enable_body))
        container.addView(button(getString(R.string.a11y_enable_button)) {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        })
        addBody(getString(R.string.a11y_control_hint))
    }

    /**
     * The privacy-policy row, wired but inert. Disabled and non-launching until
     * a policy is hosted; [PRIVACY_POLICY_URL] is a placeholder, deliberately not a live link.
     */
    private fun addAboutSection() {
        addHeader(getString(R.string.about_header))
        container.addView(
            button(getString(R.string.privacy_policy_label)) {
                // Inert until a policy is hosted; see PRIVACY_POLICY_URL.
            }.apply { isEnabled = false }
        )
    }

    private fun addHeader(text: String) {
        container.addView(TextView(this).apply {
            this.text = text
            textSize = 14f
            setTypeface(typeface, Typeface.BOLD)
            setPadding(0, dp(16), 0, dp(4))
        })
    }

    private fun addBody(text: String) {
        container.addView(TextView(this).apply {
            this.text = text
            textSize = 14f
        })
    }

    private fun button(text: String, onClick: () -> Unit): Button =
        Button(this).apply {
            this.text = text
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
            )
            setOnClickListener { onClick() }
        }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    companion object {
        private const val REQ_POST_NOTIFICATIONS = 1

        /**
         * The discrete scrolling-speed steps, dp/s: Slow / Normal / Fast. **Three steps chosen for
         * perceptibility, verified on device.** Five finer steps (6/8/12/16/20) were built and tried first;
         * their adjacent 25–50% differences were **too subtle to see in use**, which reads as a *broken*
         * control — it turns "the speed that suited me" into "the setting does nothing" and makes the beta
         * signal uninterpretable. Three coarse steps (each ~1.7–2×) each visibly do something.
         *
         * **A stronger basis than the five, not a reduction:** 6 and 12 sit on measured rate-correction
         * sweep points (the filter was swept at 4/6/12/100 dp/s) and only 20 is interpolated — versus three
         * of five interpolated before. The anchor 12 is derived from a words-per-minute × line-height
         * reading-rate model (not a value tried and liked), and one reader used it comfortably for ~1.5
         * chapters.
         *
         * **Accepted cost, with its remedy:** the 2× gap between 6 and 12 means someone for whom 12 is
         * slightly too fast drops to half speed with nothing between. That is real — and it is exactly what
         * the beta's "where do people settle" observation catches ("Normal was close but not quite" → add an
         * intermediate step). The labels carry the model: choosing Fast says 20 suited them. Default is
         * [ScrollEngine.DEFAULT_SPEED_DP] (= 12.0), the middle step.
         */
        val SPEED_STEPS = listOf(6.0, 12.0, 20.0)

        /**
         * Placeholder — no privacy policy is hosted yet. The About row stays disabled and
         * non-launching until this is a real URL. Intentionally not a live link.
         */
        private const val PRIVACY_POLICY_URL = ""
    }
}
