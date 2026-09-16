package dev.spike.autoscroll

import android.accessibilityservice.AccessibilityService
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ComponentName
import android.content.Intent
import android.graphics.drawable.Icon
import android.os.Build
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.view.accessibility.AccessibilityEvent

/**
 * Subscribes to nothing, reads nothing, talks to nobody. Its entire job is to
 * own a [ScrollEngine] and expose start/stop through a notification.
 *
 * onAccessibilityEvent() is dead code by construction: eventTypes is 0 both by
 * omission in res/xml/autoscroll_service.xml and explicitly in
 * [onServiceConnected]. The node tree is never touched -- and cannot be, since
 * the service declares canRetrieveWindowContent="false".
 *
 * **Control surface.** One channel: [ControlReceiver] -- not exported, no
 * extras, driven by the notification. (The spike also carried a
 * `DebugControlReceiver` with adb tuning extras in a debug-only source set; the
 * reading port drops that source set, so the release control path is the only
 * control path in this repo.)
 *
 * Nothing here registers a receiver at runtime. The spike did, with
 * RECEIVER_EXPORTED so `adb shell am broadcast` could reach it, which also let
 * any installed app start and stop the scroll and set `repress=true` to defeat
 * touch-to-stop. Both channels are manifest-declared now, so what each build
 * exposes is visible in `aapt2 dump xmltree` rather than buried in a code path.
 */
class AutoScrollService : AccessibilityService() {

    private lateinit var engine: ScrollEngine
    private val panelHandler = android.os.Handler(android.os.Looper.getMainLooper())

    /**
     * The proximity entry trigger. Null when the device has no proximity sensor. Own-sensor only —
     * no permission, no effect on `capabilities=32` or the ratchet. See [ProximityControl].
     */
    private var proximity: ProximityControl? = null

    /**
     * The anti-restart debounce. A touch-to-stop cancellation flips the notification back to "Start"; but
     * the shade-pull that fired the cancel can carry a still-reaching finger into a reflex tap on that
     * button, restarting the scroll — the trap once seen 4/4. Rather than drop the button (which left a
     * confusing button-less "Stopped." state and, every page-turn, made a *deliberate* restart cost an
     * extra tap — a recurring friction in real reading use), the button stays visible and the Start
     * *command* is ignored for [START_DEBOUNCE_MS] after the cancel: a sub-second reflex tap lands on
     * nothing, while a deliberate restart (seconds later, after turning the page) goes through. Hazard and
     * intent are indistinguishable except by timing — the engine cannot tell a content touch from a
     * shade-pull without window-state events (eventTypes=0). `uptimeMillis` deadline; 0 = not active.
     * Volatile: written on the engine's cancel callback thread, read on the main thread at paint/command time.
     */
    @Volatile
    private var restartDebouncedUntil = 0L

    /**
     * The device vibrator for the rule-7 haptic acknowledgement ([tick]), feature-detected: null on
     * hardware with no vibrator (e.g. a tablet), where the tick is a clean no-op — the same
     * feature-detect-and-degrade posture as [ProximityControl].
     */
    private val vibrator: Vibrator? by lazy {
        val v = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            getSystemService(VibratorManager::class.java)?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            getSystemService(Vibrator::class.java)
        }
        v?.takeIf { it.hasVibrator() }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()

        // Belt and braces over the manifest default. Nothing is subscribed to.
        val info = serviceInfo
        if (info != null) {
            info.eventTypes = 0
            info.notificationTimeout = 0
            serviceInfo = info
        }

        engine = ScrollEngine(this).also {
            it.onStateChanged = { postNotification() }
            it.onCancelledByTouch = { beginRestartDebounce() }
        }
        live = this

        // Proximity entry trigger. Direct toggle, no panel dismissal (a cover is not from the
        // shade). Unavailable, and a silent no-op, on hardware with no proximity sensor. Armed only
        // when the user has opted in (SettingsStore, off by default).
        proximity = ProximityControl(
            context = this,
            onTrigger = { toggleFromProximity() },
            onObstruction = { stopFromProximityObstruction() },
        )
        applySpeedSetting()
        applyProximitySetting()

        createChannel()
        postNotification()

        // Recorded per device, keyed on manufacturer + API
        // level. Timing behaviour is framework behaviour, so it is more likely a
        // property of the OS build than of the individual handset -- which
        // matters for the delivered-speed bias, since the same dp/s setting may
        // not read the same on two phones. Build fields only: no identifiers,
        // nothing that leaves the device, nothing that touches the node tree.
        Trace.i(
            "DEVICE",
            "manufacturer=${Build.MANUFACTURER} model=${Build.MODEL} device=${Build.DEVICE} " +
                "api=${Build.VERSION.SDK_INT} release=${Build.VERSION.RELEASE} build=${Build.ID}"
        )

        Trace.i(
            "SVC",
            "connected canRetrieveWindowContent=${serviceInfo?.canRetrieveWindowContent} " +
                "eventTypes=${serviceInfo?.eventTypes} " +
                "maxGestureDuration=${android.accessibilityservice.GestureDescription.getMaxGestureDuration()}ms " +
                "maxStrokeCount=${android.accessibilityservice.GestureDescription.getMaxStrokeCount()}"
        )
        engine.logConfig("boot")
    }

    /** Never called: eventTypes == 0. Present only because the base class is abstract. */
    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit

    override fun onInterrupt() {
        Trace.w("SVC", "onInterrupt - lifting")
        if (::engine.isInitialized) engine.stop("onInterrupt")
    }

    override fun onUnbind(intent: Intent?): Boolean {
        Trace.i("SVC", "onUnbind")
        teardown()
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        Trace.i("SVC", "onDestroy")
        teardown()
        super.onDestroy()
    }

    private fun teardown() {
        panelHandler.removeCallbacksAndMessages(null)
        proximity?.stop()
        proximity = null
        if (live === this) live = null
        if (::engine.isInitialized) {
            engine.stop("teardown")
            engine.shutdown()
        }
        notificationManager().cancel(NOTIF_ID)
    }

    // ---- control ----------------------------------------------------------

    /**
     * Entry points for [ControlReceiver], which is the app's own UI and nothing
     * else. `internal` rather than public: same-module callers only, so no
     * future exported component acquires them by accident.
     *
     * A start from here always clears system panels, because the notification
     * is the only sender -- see [startClearingPanels].
     */
    internal fun startFromOwnUi() {
        // Anti-restart debounce: a reflex Start tap carried by the shade-pull that just touch-stopped the
        // scroll arrives within [START_DEBOUNCE_MS] of the cancel; ignore it so it cannot restart. A
        // deliberate restart comes seconds later and goes through. See [beginRestartDebounce].
        if (restartDebounced()) {
            Trace.i("CTRL", "start ignored — within ${START_DEBOUNCE_MS}ms anti-restart debounce")
            return
        }
        tick(); startClearingPanels("notification")
    }

    internal fun stopFromOwnUi() { tick(); engine.stop("notification") }

    internal fun toggleFromOwnUi() {
        tick()
        if (engine.isRunning) engine.stop("toggle") else startClearingPanels("toggle")
    }

    /**
     * A short haptic tick acknowledging a notification-action tap. The notification path has a
     * ~1.5s collapse wait ([PANEL_COLLAPSE_MS]) that is silent to a user who cannot see the screen; this
     * confirms the tap registered — the platform supplies no acknowledgement, so the app must. It also
     * answers the no-op "Stop" case (a tap that does nothing otherwise tells the user nothing).
     * Opt-out ([SettingsStore.hapticEnabled], default on), a no-op where there is no vibrator.
     * Notification path only for now (its ~1.5s wait is where the acknowledgement matters most);
     * proximity is a candidate but not wired here.
     */
    private fun tick() {
        if (!SettingsStore(this).hapticEnabled) return
        val v = vibrator ?: return
        v.vibrate(VibrationEffect.createOneShot(HAPTIC_TICK_MS, HAPTIC_AMPLITUDE))
        Trace.i("HAPTIC", "tick (notification action ack)")
    }

    // ---- anti-restart debounce -------------------------------------------

    private fun restartDebounced() = SystemClock.uptimeMillis() < restartDebouncedUntil

    /**
     * A touch-to-stop cancellation just ended a run (from [ScrollEngine.onCancelledByTouch]). The
     * notification flips back to "Start" immediately, so a deliberate restart is one tap; but the Start
     * *command* is ignored for [START_DEBOUNCE_MS] ([startFromOwnUi] drops it while [restartDebounced]),
     * so the reflex tap carried by the shade-pull that fired the cancel lands on nothing. The re-post
     * below refreshes the text ("Stopped." → the idle prompt) when the window closes; the button is
     * present throughout. Idempotent.
     *
     * History: three earlier designs were rejected. Flipping to "Start" with no guard let the reflex tap
     * restart (the 4/4 trap). A no-op "Stop" button read as a contradiction on device. Dropping the
     * button for the window removed the reflex tap but left a button-less "Stopped." state and made every
     * deliberate restart (stop → turn page → restart) cost an extra tap — a recurring friction in real
     * reading use. Debouncing the command keeps the button and separates hazard from intent by timing,
     * the only signal available without window-state events (eventTypes=0).
     */
    private fun beginRestartDebounce() {
        restartDebouncedUntil = SystemClock.uptimeMillis() + START_DEBOUNCE_MS
        Trace.i("CTRL", "touch-cancel — Start debounced ${START_DEBOUNCE_MS}ms (anti-restart)")
        postNotification()
        panelHandler.postDelayed({ postNotification() }, START_DEBOUNCE_MS)
    }

    /**
     * Entry points for [ProximityControl] — the app's own proximity sensor. These press
     * **directly**, with no panel dismissal: a cover does not arrive from the shade (unlike a
     * notification-action start, which clears the shade first).
     *
     * [toggleFromProximity] is the entry trigger (a zone crossing); [stopFromProximityObstruction]
     * is the guard firing when a near outlasted any plausible cover (a settled obstruction), so a
     * stuck sensor cannot leave the scroll running for someone who cannot reach the device.
     */
    internal fun toggleFromProximity() {
        if (engine.isRunning) engine.stop("proximity") else engine.start("proximity")
    }

    internal fun stopFromProximityObstruction() {
        if (engine.isRunning) engine.stop("proximity-obstruction")
    }

    /**
     * Arm or disarm the proximity trigger to match the persisted opt-in setting. Called at connect
     * and by [SettingsActivity] when the user flips the toggle; a no-op if the service is not bound
     * (the setting is persisted, so the next [onServiceConnected] applies it). Idempotent.
     */
    internal fun refreshProximity() = applyProximitySetting()

    private fun applyProximitySetting() {
        val enabled = SettingsStore(this).proximityEnabled
        if (enabled) proximity?.start() else proximity?.stop()
        Trace.i("PROX", "setting ${if (enabled) "enabled -> armed" else "disabled -> off"}")
    }

    /**
     * Apply the persisted scrolling speed to the engine. Called at connect and by [SettingsActivity] when
     * the user picks a step; the engine keys its learned rate-correction ratio on `speedDp`, so a change
     * mid-run re-learns and settles cleanly. A no-op if the engine is not yet initialized (the setting is
     * persisted, so the next [onServiceConnected] applies it). Idempotent.
     */
    internal fun refreshSpeed() = applySpeedSetting()

    private fun applySpeedSetting() {
        if (!::engine.isInitialized) return
        val dp = SettingsStore(this).speedDp
        engine.speedDp = dp
        Trace.i("SPEED", "applied ${dp.f(1)}dp/s")
    }

    /**
     * Dismiss any system panel, then start once it has finished collapsing.
     *
     * The virtual finger presses on whatever window is topmost at press time. A
     * notification ACTION BUTTON does not collapse the shade -- only tapping the
     * notification body does -- so starting from the notification used to press
     * down on the shade and drag it instead of the app underneath. Reproduced on
     * an SM-P200 (mCurrentFocus stayed NotificationShade for 86 ticks with no
     * cancel) and independently observed on a Moto G54, so it is platform
     * behaviour, not an OEM quirk.
     *
     * The same applies to any control surface that is its own window: a
     * quick-settings tile would capture the finger identically. Stop is unaffected,
     * because stopping places no finger.
     *
     * performGlobalAction() needs no window content, so this costs nothing against
     * eventTypes = 0 or canRetrieveWindowContent = false.
     *
     * **Only ever reached from this app's own notification, and that gating is
     * now structural rather than an extra.** The spike carried an `src=notif`
     * extra because one exported receiver served both the notification and adb,
     * and dismissing panels on an adb start is actively harmful: on API < 31 the
     * fallback is GLOBAL_ACTION_BACK, and firing BACK with no panel open goes to
     * the foreground app, which navigates away or closes the document -- a worse
     * bug than the one being fixed. Two receivers, two behaviours, no extra to
     * read: [ControlReceiver] always clears the shade; the proximity path presses directly.
     */
    internal fun startClearingPanels(reason: String, collapseMs: Long = SettingsStore(this).panelCollapseMs) {
        val action = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            GLOBAL_ACTION_DISMISS_NOTIFICATION_SHADE
        } else {
            // No dedicated dismiss action before API 31. BACK collapses the shade
            // on every device tested, and is only reached here when the shade is
            // known to be open.
            GLOBAL_ACTION_BACK
        }
        val dismissed = performGlobalAction(action)
        Trace.i(
            "CTRL",
            "start requested ($reason) - dismissing system panels first " +
                "(action=${if (action == GLOBAL_ACTION_BACK) "BACK" else "DISMISS_SHADE"} " +
                "accepted=$dismissed), pressing in ${collapseMs}ms"
        )
        // The collapse is animated. Pressing during it still lands on the shade,
        // so wait it out rather than dispatching immediately. [collapseMs] is the
        // release constant PANEL_COLLAPSE_MS, with a per-device override in
        // [SettingsStore.panelCollapseMs] that no surface writes in this build.
        panelHandler.postDelayed({ engine.start(reason) }, collapseMs)
    }

    // ---- notification -----------------------------------------------------

    private fun notificationManager() = getSystemService(NotificationManager::class.java)

    private fun createChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.channel_name),
            NotificationManager.IMPORTANCE_LOW
        ).apply { setShowBadge(false) }
        notificationManager().createNotificationChannel(channel)
    }

    private fun postNotification() {
        val running = ::engine.isInitialized && engine.isRunning

        val builder = Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(if (running) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play)
            // No content title: Android already shows the app label ("Steady Read") as the notification
            // header, so a title would duplicate it. The text carries the state, matched to the button.
            .setContentText(
                getString(
                    when {
                        running -> R.string.notif_text_running
                        restartDebounced() -> R.string.notif_text_juststopped
                        else -> R.string.notif_text_stopped
                    }
                )
            )
            .setOngoing(true)
            .setShowWhen(false)

        // The action button is always present — running shows "Stop", stopped shows "Start". After a
        // touch-to-stop it flips to "Start" immediately, so a deliberate restart is one tap; the
        // reflex-tap hazard is handled by debouncing the Start *command* for [START_DEBOUNCE_MS]
        // ([beginRestartDebounce]), not by removing the button.
        builder.addAction(
            action(
                if (running) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play,
                getString(if (running) R.string.action_stop else R.string.action_start),
                if (running) ControlReceiver.ACTION_STOP else ControlReceiver.ACTION_START
            )
        )

        notificationManager().notify(NOTIF_ID, builder.build())
    }

    /**
     * The action intent carries a command and nothing else, and carries it in
     * the *action* rather than an extra. Explicit component, so the intent is
     * undeliverable anywhere but [ControlReceiver] -- which is
     * `android:exported="false"`, so only this uid can send it at all.
     *
     * No `putExtra` here and no `getExtra` there is the whole point: it is what
     * lets the ratchet assert "this build accepts no configuration" from the
     * artifact instead of listing flag names that keep going out of date.
     */
    private fun action(iconRes: Int, title: String, commandAction: String): Notification.Action {
        val intent = Intent(commandAction)
            .setComponent(ComponentName(this, ControlReceiver::class.java))
        val pending = PendingIntent.getBroadcast(
            this,
            commandAction.hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return Notification.Action.Builder(Icon.createWithResource(this, iconRes), title, pending).build()
    }

    companion object {
        /**
         * The connected service, for [ControlReceiver].
         * Null whenever the service is not bound -- which is most of the six
         * recorded "enabled but not working" states.
         *
         * A manifest-declared receiver is a separate object from the service, so
         * one of them has to be able to find the other. Runtime registration
         * made that free and cost an exported control surface.
         */
        @Volatile
        internal var live: AutoScrollService? = null

        /**
         * Default delay between dismissing a system panel and pressing the finger down; also the
         * default for the [SettingsStore.panelCollapseMs] override (one source of truth).
         *
         * Raised from 500 to 1500 ms. 500 ms is shorter than the shade
         * collapse-plus-resettle on the `DISMISS_SHADE` path, so the first synthetic press lands before
         * the host is ready to treat it as a scroll and is wasted — the scroll then stalls ~31s until
         * the next regrip. Measured edge on the Moto (`DISMISS_SHADE`): 500 stalls, 1000 works, on two
         * hosts (Chrome + Drive PDF); 1500 is ~50% headroom. The SM-P200 (`BACK` path) does not stall
         * even at 500. Host readiness is unobservable to this app by construction (eventTypes=0,
         * canRetrieveWindowContent=false), so this cannot be an automatic poll; it is a
         * fixed default with the per-device override for field outliers a caregiver/integrator raises.
         */
        const val PANEL_COLLAPSE_MS = 1500L

        /**
         * Anti-restart debounce: how long after a touch-to-stop cancellation a Start command from the
         * notification is ignored (see [beginRestartDebounce]), so a reflex tap carried by the shade-pull
         * that fired the cancel cannot restart the scroll. The button stays visible throughout; only the
         * command is debounced, so a deliberate restart after the window is a single tap.
         *
         * **1000ms is provisional and NOT calibrated.** The earlier 3000ms drop-the-button window was
         * chosen to be safe, from four cancel→tap gaps (3.8/3.7/3.2/2.6s) measured on the *broken* build
         * where the first press stalled 31s and the user pulled the shade repeatedly — a distribution that
         * no longer exists now the stall is fixed. Those numbers describe frustrated re-pulls, not a clean
         * reflex tap, so they do not set this value. 1000ms is a starting guess for a reflex follow-through
         * tap; real use (the CANCEL log plus start-command timing) gives the real number.
         */
        const val START_DEBOUNCE_MS = 1000L

        /**
         * The rule-7 haptic acknowledgement tick ([tick]): duration and amplitude. Bumped from
         * 25ms/`DEFAULT_AMPLITUDE` after on-device feedback that it was too subtle to feel reliably
         * during a tap. Max amplitude (255) and a longer pulse; provisional, may need per-device tuning
         * (motor strength varies). A confirmation, still not an alarm.
         */
        const val HAPTIC_TICK_MS = 60L
        const val HAPTIC_AMPLITUDE = 255

        private const val CHANNEL_ID = "autoscroll"
        private const val NOTIF_ID = 1
    }
}
