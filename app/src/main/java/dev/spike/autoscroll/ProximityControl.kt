package dev.spike.autoscroll

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock

/**
 * Proximity **entry trigger** — the app reads its own proximity sensor to start/stop the scroll,
 * for phone users who cannot reliably touch the screen. An entry trigger with **no dwell**.
 *
 * **Privacy.** `TYPE_PROXIMITY` needs no permission and is not an accessibility capability, so this
 * touches neither `capabilities=32`, the declaration ratchet, nor `eventTypes=0`. The app talks only
 * to its own sensor — no window, no external party.
 *
 * A cover is a deterministic user input *in this session*, like pressing the notification — not
 * autonomous initiation. The app decides nothing; a physical zone crossing does.
 *
 * **Mechanism.** A far→near transition — crossing into the ~2 cm zone — toggles the scroll, pressed
 * **directly** (no panel dismissal: a cover does not arrive from the shade, and firing a dismiss with
 * no panel open reaches the app the user is reading — see [AutoScrollService.startClearingPanels]).
 * There is no dwell gate: incidental motion never reaches the ~2 cm zone, so *entry itself* is the
 * discriminator. The accepted cost is a more sensitive trigger — a brief crossing starts the scroll —
 * recoverable by touch-to-stop and bounded for the sustained case by the timeout below.
 *
 * **The stuck-near timeout, this trigger's guard.** A blanket settling on the device is a *sustained*
 * near: it would start the scroll and then jam the control, because the sensor stays near and no new
 * entry can fire to stop it — a permanent false trigger on exactly the person who cannot reach the
 * device to clear it. If near persists past [STUCK_NEAR_MS] — well beyond any deliberate cover — it is
 * treated as an **obstruction, not a command**: the scroll is stopped and the sensor ignored until it
 * clears.
 *
 * [STUCK_NEAR_MS] is chosen long enough that a deliberate cover never hits it, short enough that a
 * stuck sensor does not strand.
 */
class ProximityControl(
    context: Context,
    /** Toggle the scroll (start if stopped, stop if running). */
    private val onTrigger: () -> Unit,
    /** A sustained near turned out to be an obstruction — stop any running scroll. */
    private val onObstruction: () -> Unit,
) : SensorEventListener {

    private val sensorManager = context.getSystemService(SensorManager::class.java)
    private val sensor: Sensor? = sensorManager?.getDefaultSensor(Sensor.TYPE_PROXIMITY)
    private val handler = Handler(Looper.getMainLooper())
    private val maxRange = sensor?.maximumRange ?: 5f

    private var armed = false
    private var near = false
    private var obstructed = false
    private var entryUptimeMs = 0L

    /**
     * An ON_CHANGE sensor delivers its current value on registration. That first reading is a
     * baseline, not a transition — acting on it would fire a spurious toggle if the sensor happened
     * to be covered at service-connect (a face-down phone, a hand in the way). Primed on the first
     * event.
     */
    private var primed = false

    /** Idempotent: arming an already-armed control is a no-op, so it is safe to call on a settings toggle. */
    fun start() {
        if (armed) return
        val s = sensor
        if (s == null) {
            // Tablet-class hardware (e.g. the SM-P200) has no proximity sensor; the entry trigger
            // is simply unavailable there (this is a phone feature).
            Trace.i(TAG, "no TYPE_PROXIMITY sensor — entry trigger unavailable on this device")
            return
        }
        primed = false
        // FASTEST to give a fast cover the best chance of being sampled (a fast in-and-out
        // may otherwise be too brief to register). Proximity is ON_CHANGE, so this reports on
        // change rather than continuously; the rate is a hint, not a power drain.
        sensorManager?.registerListener(this, s, SensorManager.SENSOR_DELAY_FASTEST)
        armed = true
        Trace.i(TAG, "armed (maxRange=$maxRange, stuckNearMs=$STUCK_NEAR_MS)")
    }

    fun stop() {
        if (!armed && sensor != null) return
        sensorManager?.unregisterListener(this)
        handler.removeCallbacksAndMessages(null)
        near = false
        obstructed = false
        armed = false
    }

    override fun onSensorChanged(event: SensorEvent) {
        val isNear = event.values[0] < maxRange
        if (!primed) {
            primed = true
            near = isNear
            Trace.i(TAG, "baseline ${if (isNear) "near" else "far"} (not a trigger)")
            return
        }
        if (isNear == near) return
        near = isNear
        if (isNear) onNear() else onFar()
    }

    private fun onNear() {
        entryUptimeMs = SystemClock.uptimeMillis()
        // A new entry can only follow a far, which clears `obstructed`, so this is defensive.
        if (obstructed) return

        onTrigger()
        val latency = SystemClock.uptimeMillis() - entryUptimeMs
        Trace.i(TAG, "entry -> toggle (action latency ${latency}ms)")

        handler.removeCallbacks(stuckNearRunnable)
        handler.postDelayed(stuckNearRunnable, STUCK_NEAR_MS)
    }

    private fun onFar() {
        handler.removeCallbacks(stuckNearRunnable)
        if (obstructed) {
            obstructed = false
            Trace.i(TAG, "obstruction cleared — returned to far, trigger re-armed")
        }
    }

    /** Fires when a near has outlasted any plausible cover. */
    private val stuckNearRunnable = Runnable {
        if (!near) return@Runnable
        obstructed = true
        val heldMs = SystemClock.uptimeMillis() - entryUptimeMs
        // Functional log line: did something settle on the device? Not diagnostic.
        Trace.i(
            TAG,
            "obstruction — near persisted ${heldMs}ms (> ${STUCK_NEAR_MS}ms): stopping, ignoring " +
                "until it clears [did something settle on the device during use?]"
        )
        onObstruction()
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    companion object {
        private const val TAG = "PROX"

        /**
         * Stuck-near timeout. Provisional 5 s, biased **generous** on purpose: a settled obstruction
         * lasts indefinitely so any timeout catches it, whereas a *too-short* timeout misfires on a
         * deliberate cover held a little long — which is exactly what a slow, deliberate user does. The
         * only cost of generous is a rare unwanted scroll running a few seconds longer before it stops
         * (recoverable, and the scroll is slow). Able-bodied covers were ~0.4–2.0 s; the slower upper
         * bound for the target user is unmeasured.
         */
        const val STUCK_NEAR_MS = 5000L
    }
}
