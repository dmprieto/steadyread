package dev.spike.autoscroll

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.accessibilityservice.GestureDescription.StrokeDescription
import android.graphics.Path
import android.hardware.display.DisplayManager
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.util.DisplayMetrics
import android.view.Display
import android.view.ViewConfiguration
import kotlin.math.min

/**
 * The virtual finger.
 *
 * One [StrokeDescription] is opened with willContinue = true and then extended
 * with continueStroke() segment after segment. The chain is driven from
 * [AccessibilityService.GestureResultCallback.onCompleted], NOT from a fixed
 * timer: dispatchGesture() refuses to accept a gesture while one is in flight,
 * so a free-running Handler tick would just drop segments. Real cadence is
 * therefore (segment duration + one IPC round trip), and that round trip is the
 * seam we are here to measure.
 *
 * What actually bounds a press is TRAVEL, not GestureDescription's 60s cap.
 * Segments are sub-second, so the 60s ceiling is never approached; the finger
 * runs out of the 25%..75% band after 0.5 * screenHeight and has to re-grip. At
 * 12 dp/s that is roughly every 32 seconds, at 100 dp/s roughly every 4. A
 * single unbroken press has been held for 68 seconds at 4 dp/s, so the limit is
 * geometry, not the platform.
 *
 * Velocity is a profile, not a constant. A constant-velocity slow drag never
 * clears touch slop before the host app's long-press timer fires, so every
 * press opens with a fast lead-in kick that clears slop plus a margin, then
 * ramps down to the target speed. Slop distance is discarded by the host app
 * either way, so covering it in 120ms instead of 670ms costs nothing.
 *
 * ---------------------------------------------------------------------------
 * REJECTED DESIGN: never catch up for dead time.
 *
 * A re-grip costs ~460ms during which nothing moves. It is tempting to repay
 * that debt by running faster for a moment afterwards so average throughput
 * matches the requested dp/s. Do not. This is a deliberate decision, not an
 * oversight, and the evidence against it is direct:
 *
 * The lead-in margin used to be 4dp. An observer reading a PDF called the result
 * "noticeable... could bother a different type of user". Dropping it to 1dp
 * changed the verdict to "an overall improvement", with the ~460ms pause itself
 * completely unchanged. The pause was never the problem; the velocity
 * discontinuity after it was.
 *
 * (The sizes originally quoted here -- 2.8x cruise falling to 0.7x -- came from a
 * metric that measured only the margin term and ignored the ramp. Measured across
 * the whole lead-in: 3.98x falling to 2.55x. The change was real and the verdict
 * stands; the magnitudes were wrong, and the lead-in remains an overshoot rather
 * than the ease-in it was described as.)
 *
 * Catching up would reintroduce exactly that discontinuity on top of an overshoot
 * that is already there: 460ms of debt at 12 dp/s is 5.5dp to repay, against a
 * lead-in already running ~2.5x cruise. For someone reading, predictable velocity
 * is worth more than accurate average throughput. Lost time stays lost.
 * ---------------------------------------------------------------------------
 */
class ScrollEngine(private val service: AccessibilityService) {

    private enum class Phase { IDLE, KICK, RAMP, CRUISE, DECEL, HOLD }

    // ---- adb-tunable ------------------------------------------------------
    @Volatile
    var speedDp: Double = DEFAULT_SPEED_DP

    @Volatile
    var segmentMs: Long = DEFAULT_SEGMENT_MS

    /** Off = pure constant velocity, i.e. the long-press failure mode, on demand. */
    @Volatile
    var leadInEnabled: Boolean = true

    /**
     * Default false: every cancelled gesture ends the run, whatever caused it.
     *
     * REJECTED DESIGN: distinguishing a user touch from an app switch.
     *
     * Both arrive as an identical onCancelled with no distinguishing payload.
     * Telling them apart requires subscribing to window-state events, which carry
     * the foreground package name -- forfeiting eventTypes = 0, the strongest
     * privacy claim this project has. Trading a structural guarantee for a
     * convenience is the wrong trade. The age-cap re-grip is unaffected either
     * way: it is a willContinue=false stroke that completes into onCompleted, a
     * lift rather than a cancellation.
     *
     * Stopping on everything is not a compromise, it is the control surface.
     * Touching the screen stops the scroll, on every device, in every app, with
     * zero permissions and no UI. For an app that drives someone else's screen
     * that is the safety property, and it works while the phone is held.
     *
     * The cost lands on restart, not stop: a user who cannot reliably touch the
     * screen now needs a hands-free way back in after every app switch. Nothing
     * in this spike answers that.
     *
     * Open question, decided by measurement not argument: how often do spurious
     * cancellations fire during ordinary reading (IME, toasts, shade peeks)? Rare
     * makes this a feature; frequent makes it a limitations-page entry. See the
     * CANCEL log line.
     *
     * Set true only to measure cancellation frequency without the first one
     * ending the session. Do not ship it true.
     */
    @Volatile
    var repressOnCancel: Boolean = false

    /**
     * Extra distance beyond measured slop that the lead-in kick covers, in dp.
     *
     * The host app discards `slop` of the kick, so this is the kick's contribution
     * to visible motion -- but it is NOT the whole lurch, and treating it as such
     * was a measurement error that stood for some time. The RAMP that follows
     * starts at kickPxPerS() and carries ~90% of the visible motion at 1dp. See
     * DEFAULT_SLOP_MARGIN_DP and the LEADIN log line, which now reports both terms.
     *
     * Smaller is smoother; too small risks not clearing slop at all in hosts whose
     * effective slop exceeds scaledTouchSlop (WebViews, nested scroll containers),
     * and the failure mode there is a long-press, far worse than a lurch. Sweep it,
     * don't guess it -- and read the honest metric, not the old one.
     */
    @Volatile
    var slopMarginDp: Double = DEFAULT_SLOP_MARGIN_DP

    /**
     * Lead-in kick duration in ms. THE DOMINANT LEVER on re-grip smoothness —
     * see DEFAULT_LEAD_IN_MS.
     *
     * Clamped at every press against the device's measured long-press timeout:
     * the kick exists to clear slop before the host app fires long-press, so a
     * lead-in that outruns that deadline turns the fix into the failure mode. The
     * clamp uses LEAD_IN_SAFETY_FRACTION of the measured timeout, and a request
     * above it is honoured up to the limit and logged, not silently applied.
     */
    @Volatile
    var leadInMs: Long = DEFAULT_LEAD_IN_MS

    /**
     * Age cap in force. Manual override only -- see MAX_PRESS_MS.
     *
     * There used to be a self-calibrating subsystem here: known-good/known-bad
     * bounds, confirmation across clustered observations, upward probing,
     * SharedPreferences persistence. It was deleted, because the thing it was
     * built to track turned out not to exist. Chain age does not cause
     * cancellations; sub-pixel segments do, and those are handled directly. A
     * fixed high backstop plus CANCEL logging covers the remaining risk at a
     * fraction of the complexity, and CANCEL is what would reveal decay if it
     * ever does show up on hardware we have not met.
     */
    @Volatile
    var currentCapMs: Long = MAX_PRESS_MS

    /**
     * Correct commanded displacement for the gap between a segment's nominal
     * duration and how long it actually takes. Toggleable for before/after.
     */
    @Volatile
    var rateCorrect: Boolean = true

    /**
     * Low-pass filtered actual/nominal segment duration, the correction factor
     * applied to cruise displacement.
     *
     * Measured bias on a Moto G54: +43% delivered at 4 dp/s, +21% at 6, +2% at
     * 12, -13% at 100. Short paths complete early, long ones run over, and
     * displacement was computed from the nominal duration while the loop advanced
     * at the actual one.
     *
     * Filtered rather than instantaneous on purpose. The distortion is systematic
     * -- reproducible across presses and monotonic in speed -- so a filtered
     * estimate captures it in full and the correction behaves as calibration,
     * moving over seconds and unable to produce a visible step. Feeding raw
     * last-segment elapsed would inject whatever part of that timing is noise
     * straight into on-screen velocity, trading a constant offset for visible
     * jitter. Smoothness is the property this whole spike exists to protect.
     */
    @Volatile
    var timingRatio: Double = 1.0

    // ---- device facts, re-read at every press (handles rotation) ----------
    private var density = 1f
    private var screenW = 0
    private var screenH = 0
    private var slopPx = 0f
    private var longPressTimeoutMs = 0
    private var minFlingPx = 0f

    /** [leadInMs] after clamping against this device's long-press timeout. */
    private var effectiveLeadInMs = DEFAULT_LEAD_IN_MS
    private var startY = 0.0
    private var endY = 0.0
    private var xPos = 0f

    // ---- chain state (only ever touched on [handler]'s thread) ------------
    private val thread = HandlerThread("autoscroll-tick").apply { start() }
    private val handler = Handler(thread.looper)

    @Volatile
    private var running = false

    private var phase = Phase.IDLE
    private var lastStroke: StrokeDescription? = null

    /**
     * Commanded Y of the virtual finger, in device px, as a Double that is never
     * rounded per tick. At 12 dp/s on a 2.75x screen a 100ms segment is 3.3px;
     * at 6 dp/s with 32ms segments it is 0.5px. Rounding either of those per
     * tick stair-steps or stalls outright. Only the *rendering* of this value
     * into the Path is float, and each segment's start float is derived from the
     * same Double as the previous segment's end float, so the chain stays
     * pixel-continuous without the accumulator ever being quantised.
     */
    private var yPos = 0.0

    private var phaseElapsed = 0.0
    private var cumulativePx = 0.0
    private var decelFromVelocity = 0.0

    private var seq = 0L
    private var pressCount = 0
    private var regripCount = 0
    private var cancelCount = 0

    private var dispatchedAt = 0L
    private var completedAt = 0L
    private var lastActualMs = 0L
    private var lastCompletedPhase = Phase.IDLE
    private var ratioSamples = 1
    private var ratioLearnedAtSpeed = 0.0
    private var lastSegmentMs = 0L
    private var pressStartedAt = 0L

    /** Set when motion starts being disturbed (decel) so the re-grip dead time is measurable end to end. */
    private var disturbStartedAt = 0L
    private var stopRequestedAt = 0L
    private var endReason = ""

    val isRunning: Boolean get() = running

    // ---- public control ---------------------------------------------------

    fun start(reason: String) = handler.post {
        if (running) {
            Trace.i("START", "ignored, already running reason=$reason")
            return@post
        }
        running = true
        stopRequestedAt = 0L
        endReason = ""
        timingRatio = 1.0
        ratioSamples = 1
        ratioLearnedAtSpeed = speedDp
        lastActualMs = 0L
        lastCompletedPhase = Phase.IDLE
        regripCount = 0
        cancelCount = 0
        cumulativePx = 0.0
        seq = 0
        pressCount = 0
        disturbStartedAt = 0L
        Trace.i("START", "reason=$reason ${configSummary()}")
        // Every *other* transition out of a run repaints -- stop(), abortChain()
        // and the lift paths all call this. Entering one did not, so the
        // notification stayed on its stopped title and its Start action for the
        // whole life of the service, and the Stop action was never rendered on a
        // device at all. Found 20 Aug 2026 on the SM-P200: `dumpsys notification`
        // 10s into a confirmed run (118 ticks, zero cancels) still reported
        // actions={[0] "Start"} and title "Auto-scroll ready".
        onStateChanged?.invoke()
        beginPress("start")
    }

    fun stop(reason: String) = handler.post {
        if (!running) {
            Trace.i("STOP", "ignored, not running reason=$reason")
            return@post
        }
        running = false
        stopRequestedAt = SystemClock.uptimeMillis()
        endReason = "stop:$reason"
        Trace.i("STOP", "requested reason=$reason phase=$phase - finger lifts after current segment + decel/hold")
        // The in-flight segment cannot be cancelled; the pre-flight check in
        // planAndDispatch() picks !running up on the next completion.
        if (lastStroke == null) {
            // Nothing in flight (waiting out a post-cancel re-press). The finger
            // is already up, so there is nothing to decelerate or lift.
            phase = Phase.IDLE
            Trace.i("STOP", "complete latencyMs=0 (no gesture in flight) ${statsSummary()}")
            onStateChanged?.invoke()
        }
    }

    fun logConfig(source: String) = Trace.i("CFG", "source=$source ${configSummary()}")

    private fun configSummary() =
        "speed=${speedDp.f(1)}dp/s segment=${segmentMs}ms " +
            "leadIn=${if (leadInEnabled) "on" else "off"} " +
            "leadInMs=${leadInMs}(eff=${clampedLeadInMs()}) " +
            "slopMargin=${slopMarginDp.f(1)}dp " +
            "rateCorrect=${if (rateCorrect) "on" else "off"} " +
            "repressOnCancel=${if (repressOnCancel) "on" else "off"}"

    fun shutdown() {
        running = false
        thread.quitSafely()
    }

    // ---- press lifecycle --------------------------------------------------

    private fun beginPress(reason: String) {
        refreshDeviceFacts()

        yPos = startY
        xPos = screenW / 2f
        phaseElapsed = 0.0
        lastStroke = null
        phase = if (leadInEnabled) Phase.KICK else Phase.CRUISE
        pressCount++
        pressStartedAt = SystemClock.uptimeMillis()
        completedAt = 0L

        Trace.i(
            "PRESS",
            "n=$pressCount reason=$reason x=${xPos.f(1)} y=${yPos.f(1)} " +
                "screen=${screenW}x$screenH density=${density.f(2)} " +
                "band=[${endY.f(0)}..${startY.f(0)}]px travel=${((startY - endY) / density).f(1)}dp " +
                "slop=${(slopPx / density).f(1)}dp longPress=${longPressTimeoutMs}ms " +
                "minFling=${(minFlingPx / density).f(1)}dp/s " +
                "travelBudget=${travelBudgetSeconds().f(1)}s ageCap=${currentCapMs / 1000}s " +
                "binding=${if (travelBudgetSeconds() * 1000 < currentCapMs) "travel" else "age"}"
        )
        if (!leadInEnabled) {
            Trace.w(
                "PRESS",
                "leadIn=off - constant velocity from touch-down. Expect the host app to fire " +
                    "long-press: ${(speedDp * longPressTimeoutMs / 1000.0).f(1)}dp covered in " +
                    "${longPressTimeoutMs}ms vs ${(slopPx / density).f(1)}dp of slop."
            )
        }
        val startsAtCruise = phase == Phase.CRUISE
        planAndDispatch()
        // With the lead-in disabled there is no RAMP->CRUISE transition to hang the
        // re-grip measurement off, so close it out here instead.
        if (startsAtCruise) onCruiseReached()
    }

    private fun refreshDeviceFacts() {
        val dm = DisplayMetrics()
        val display = service.getSystemService(DisplayManager::class.java)
            .getDisplay(Display.DEFAULT_DISPLAY)
        // getRealMetrics is deprecated but is the only call that works unchanged
        // from API 26 to 37 on a non-UI (Service) context and reports the full
        // display, which is the coordinate space dispatchGesture() expects.
        @Suppress("DEPRECATION")
        display.getRealMetrics(dm)

        density = dm.density
        screenW = dm.widthPixels
        screenH = dm.heightPixels

        // Read, never hardcode: OEM builds and the user's own accessibility
        // settings both move these, and a device with a 300ms long-press would
        // silently invalidate a baked-in 500ms assumption.
        slopPx = ViewConfiguration.get(service).scaledTouchSlop.toFloat()
        longPressTimeoutMs = ViewConfiguration.getLongPressTimeout()
        minFlingPx = ViewConfiguration.get(service).scaledMinimumFlingVelocity.toFloat()

        // The kick must finish clearing slop before the host's long-press timer
        // fires, so the lead-in is bounded by a measured device property rather
        // than a constant. Recomputed per press: the timeout is user-configurable
        // in accessibility settings and can change under us.
        val requested = leadInMs
        effectiveLeadInMs = clampedLeadInMs()
        if (effectiveLeadInMs != requested) {
            Trace.w(
                "LEADIN",
                "requested ${requested}ms clamped to ${effectiveLeadInMs}ms " +
                    "(long-press timeout ${longPressTimeoutMs}ms x $LEAD_IN_SAFETY_FRACTION safety; " +
                    "floor ${MIN_LEAD_IN_MS}ms)"
            )
        }

        startY = screenH * BAND_BOTTOM_FRAC
        endY = screenH * BAND_TOP_FRAC
    }

    /**
     * [leadInMs] clamped against this device's measured long-press timeout.
     *
     * Computed on demand rather than read from a field, because the field is only
     * refreshed at press time: reporting it in a config line logged on a
     * *broadcast* showed the previous press's value, which read as the clamp
     * silently ignoring a request. Same class of error as the mislabelled lurch
     * metric — a display that quietly misleads a later reading.
     *
     * Guards against being called before the first press, when longPressTimeoutMs
     * is still 0 and a naive coerceIn(40, 0) would throw.
     */
    private fun clampedLeadInMs(): Long {
        if (longPressTimeoutMs <= 0) return leadInMs.coerceAtLeast(MIN_LEAD_IN_MS)
        val maxSafe = (longPressTimeoutMs * LEAD_IN_SAFETY_FRACTION).toLong()
            .coerceAtLeast(MIN_LEAD_IN_MS)
        return leadInMs.coerceIn(MIN_LEAD_IN_MS, maxSafe)
    }

    private fun travelBudgetSeconds(): Double {
        val v = targetPxPerS()
        return if (v <= 0.0) 0.0 else (startY - endY) / v
    }

    // ---- velocity profile -------------------------------------------------

    private fun targetPxPerS() = speedDp * density

    /** Clears slop + margin inside effectiveLeadInMs. Downward-scrolling finger moves up. */
    private fun kickPxPerS() = (slopPx + slopMarginDp * density) / (effectiveLeadInMs / 1000.0)

    private fun velocityAt(p: Phase, tMs: Double): Double = when (p) {
        Phase.KICK -> kickPxPerS()
        Phase.RAMP -> {
            val f = (tMs / RAMP_MS).coerceIn(0.0, 1.0)
            kickPxPerS() + (targetPxPerS() - kickPxPerS()) * f
        }
        Phase.CRUISE -> targetPxPerS()
        Phase.DECEL -> decelFromVelocity * (1.0 - (tMs / DECEL_MS).coerceIn(0.0, 1.0))
        Phase.HOLD, Phase.IDLE -> 0.0
    }

    /** Midpoint rule at 1ms - exact for the piecewise-linear profile above. */
    private fun integrate(p: Phase, fromMs: Double, durationMs: Long): Double {
        var d = 0.0
        var t = fromMs
        val end = fromMs + durationMs
        while (t < end) {
            val step = min(1.0, end - t)
            if (step <= 0.0) break
            d += velocityAt(p, t + step / 2.0) * (step / 1000.0)
            t += step
        }
        return d
    }

    /**
     * Commanded displacement for a segment, rate-corrected.
     *
     * The correction applies to CRUISE only. KICK and RAMP exist to cross the
     * host app's touch-slop threshold inside the long-press window -- a distance
     * problem, not a rate problem -- and scaling them by 0.7 would eat headroom
     * that measurement showed is already only ~2x. DECEL and HOLD are about
     * staying under the fling threshold, likewise not rate-accuracy.
     */
    private fun displacementFor(p: Phase, fromMs: Double, durationMs: Long): Double {
        val raw = integrate(p, fromMs, durationMs)
        return if (rateCorrect && p == Phase.CRUISE) raw * timingRatio else raw
    }

    /**
     * Feed one completed CRUISE segment into the filtered cycle/nominal estimate.
     *
     * cycleMs is DISPATCH-TO-DISPATCH, not the segment's own completion time. The
     * first version used completion time alone and left a residual of almost
     * exactly -(seam / cycle) at every speed measured: -3.7% at 4 dp/s against a
     * ~3ms seam on a ~78ms segment, -2.3% at 12 dp/s, -1.1% at 6. The loop cannot
     * run faster than dispatch-to-dispatch, so the seam belongs in the
     * denominator; omitting it makes the correction a seam too small every time.
     *
     * Only CRUISE segments count. The profile phases run different durations by
     * design and would pollute the estimate with structure that is not timing
     * error at all.
     */
    private fun updateTimingRatio(cycleMs: Long, nominalMs: Long) {
        if (nominalMs <= 0L || cycleMs <= 0L) return

        // The ratio is speed-dependent -- 1.005 at 12 dp/s against 1.107 at
        // 100 dp/s on the same device -- so a change of commanded speed
        // invalidates the estimate. Keep the current value as a prior (still a
        // better guess than 1.0) but re-open the warm-up gain so it re-converges
        // in ~20 segments instead of ~20 seconds.
        //
        // Consequence worth remembering if a speed slider ever ships: shipped
        // per-device defaults would need a curve over speed, not one number.
        // For set-and-forget at a fixed 12 dp/s it never comes up.
        if (speedDp != ratioLearnedAtSpeed) {
            ratioLearnedAtSpeed = speedDp
            ratioSamples = 1
        }

        val sample = (cycleMs.toDouble() / nominalMs.toDouble())
            .coerceIn(RATE_RATIO_MIN, RATE_RATIO_MAX)

        // Warm-up gain, decaying into the steady-state constant at about n=20.
        // Without it the filter starts at 1.0 and spends its first ~20 segments
        // mis-rated -- which is only ever the FIRST press of a run, since
        // timingRatio persists across re-grips. Measured cost of that transient
        // at 100 dp/s: press 1 delivered -4.5%, presses 3-6 within 0.2%. At
        // 12 dp/s it is ~2 seconds, landing exactly when the user is forming an
        // impression of whether the speed setting is right.
        //
        // The counter starts at 1, not 0, so the first sample gets gain 0.5
        // rather than 1.0. At gain 1.0 the sample is adopted wholesale and the
        // filter is not filtering at the one moment it is least entitled to
        // trust its input: the first cruise cycle sits immediately after the
        // transition out of RAMP, so it is structurally the most likely segment
        // to be atypical, and the 0.60..1.50 clamp bounds the damage only to a
        // visible speed error rather than a safe one. Costs 0.1s of convergence
        // -- 0.2s measured becomes ~0.3s, both far under noticeability.
        //
        // General form, worth applying anywhere else this pattern appears: a
        // filter that adopts its first sample wholesale is not a filter yet.
        val alpha = maxOf(RATE_FILTER_ALPHA, 1.0 / (ratioSamples + 1.0))
        ratioSamples++

        timingRatio = (timingRatio + alpha * (sample - timingRatio))
            .coerceIn(RATE_RATIO_MIN, RATE_RATIO_MAX)
    }

    private fun segmentDurationFor(p: Phase): Long = when (p) {
        // One segment. Velocity is constant within a stroke no matter how many
        // points the Path has -- the framework re-parameterises by arc length --
        // so a profile can only be expressed across segments, never inside one.
        Phase.KICK -> effectiveLeadInMs
        Phase.RAMP -> min(PROFILE_SEGMENT_MS, (RAMP_MS - phaseElapsed).toLong().coerceAtLeast(1L))
        Phase.DECEL -> min(PROFILE_SEGMENT_MS, (DECEL_MS - phaseElapsed).toLong().coerceAtLeast(1L))
        Phase.HOLD -> HOLD_MS
        else -> segmentMs.coerceIn(MIN_SEGMENT_MS, MAX_SEGMENT_MS)
    }

    // ---- the chain --------------------------------------------------------

    private fun planAndDispatch() {
        if (phase == Phase.IDLE) return

        // Pre-flight: motion phases can be pre-empted by a stop request or by
        // running out of band. Decel/hold always run to completion so the finger
        // never lifts while moving.
        if (phase == Phase.KICK || phase == Phase.RAMP || phase == Phase.CRUISE) {
            val ageMs = SystemClock.uptimeMillis() - pressStartedAt
            if (!running) {
                enterDecel("stop")
            } else if (yPos - lookaheadPx() <= endY) {
                enterDecel("travel")
            } else if (ageMs >= currentCapMs) {
                enterDecel("age")
            }
        }

        var dur = segmentDurationFor(phase)
        val maxDur = GestureDescription.getMaxGestureDuration()
        if (dur >= maxDur) {
            Trace.w("SEG", "duration ${dur}ms >= max ${maxDur}ms, clamping")
            dur = maxDur - 1
        }

        val yStartF = yPos.toFloat()
        var target: Double
        var yEndF: Float

        if (phase == Phase.HOLD) {
            // Finger must sit still to let the host app's VelocityTracker decay,
            // otherwise the lift reads as a fling above ~50 dp/s. A truly
            // zero-length Path is rejected by StrokeDescription, so hold is
            // 0.5px over HOLD_MS -- about 1.8 dp/s, two orders below any fling
            // threshold, and effectively motionless on screen.
            yEndF = yStartF - HOLD_EPSILON_PX
            target = yEndF.toDouble()
        } else {
            var dy = displacementFor(phase, phaseElapsed, dur)
            target = yPos - dy
            if (target < endY - OVERSHOOT_ALLOWANCE_PX) target = endY - OVERSHOOT_ALLOWANCE_PX
            yEndF = target.toFloat()

            // Undersized segments are the thing an aged chain cancels, so hold a
            // hard floor on commanded displacement and buy it with a longer
            // segment rather than by rounding the accumulator. (Originally this
            // only guarded exact float equality, which is orders of magnitude
            // below where the platform actually starts dropping segments.)
            var widened = 0
            while ((yStartF - yEndF) < MIN_SEGMENT_PX && widened < 4) {
                dur *= 2
                dy = displacementFor(phase, phaseElapsed, dur)
                target = yPos - dy
                yEndF = target.toFloat()
                widened++
            }
            if (widened > 0) {
                Trace.i(
                    "SEG",
                    "seq=$seq segment under the ${MIN_SEGMENT_PX}px floor, widened ${widened}x " +
                        "to ${dur}ms dy=${dy.f(3)}px"
                )
            }
            if (yEndF == yStartF) {
                yEndF = yStartF - MIN_PATH_PX
                target = yEndF.toDouble()
                Trace.w("SEG", "seq=$seq still degenerate after widening, nudged ${MIN_PATH_PX}px")
            }
        }

        val willContinue = phase != Phase.HOLD

        val path = Path().apply {
            moveTo(xPos, yStartF)
            lineTo(xPos, yEndF)
        }

        val stroke = try {
            lastStroke?.continueStroke(path, 0L, dur, willContinue)
                ?: StrokeDescription(path, 0L, dur, willContinue)
        } catch (t: IllegalArgumentException) {
            Trace.e("SEG", "seq=$seq stroke rejected y=${yStartF.f(2)}->${yEndF.f(2)} dur=${dur}ms", t)
            abortChain("stroke-rejected")
            return
        }

        val gesture = GestureDescription.Builder().addStroke(stroke).build()

        val now = SystemClock.uptimeMillis()
        if (completedAt != 0L) {
            val gap = now - completedAt
            // The cycle just closed. lastSegmentMs still holds the previous
            // segment's nominal duration -- it is overwritten further down.
            if (lastCompletedPhase == Phase.CRUISE) {
                updateTimingRatio(lastActualMs + gap, lastSegmentMs)
            }
            Trace.i(
                "SEAM",
                "seq=$seq phase=$phase gap=${gap}ms" + if (gap > SEAM_FLAG_MS) "  <-- SEAM" else ""
            )
        }

        seq++
        dispatchedAt = now
        lastSegmentMs = dur

        val accepted = service.dispatchGesture(gesture, callback, handler)
        if (!accepted) {
            Trace.e("SEG", "seq=$seq dispatchGesture returned false (gesture already in flight?)")
            abortChain("dispatch-refused")
            return
        }

        lastStroke = stroke
        cumulativePx += (yStartF - yEndF).toDouble()
        yPos = target
    }

    /** One cruise segment plus the distance decel+hold still need. */
    private fun lookaheadPx(): Double =
        targetPxPerS() * (segmentMs / 1000.0) +
            targetPxPerS() * (DECEL_MS / 2000.0) +
            HOLD_EPSILON_PX

    private fun enterDecel(reason: String) {
        decelFromVelocity = velocityAt(phase, phaseElapsed)
        endReason = if (endReason.startsWith("stop")) endReason else reason
        disturbStartedAt = SystemClock.uptimeMillis()
        phaseElapsed = 0.0

        // Below the host app's own fling threshold there is nothing to decelerate:
        // releasing here cannot produce a fling. Skipping matters because a decel
        // ramp from a low speed commands sub-pixel segments (0.46px at 12 dp/s,
        // 0.69px at 6 dp/s), and an aged chain cancels those -- measured on a
        // Moto G54, cancels at 48s and 62s of chain age, clean at 36s.
        if (decelFromVelocity < minFlingPx) {
            phase = Phase.HOLD
            Trace.i(
                "DECEL",
                "reason=$reason SKIPPED - v=${decelFromVelocity.f(1)}px/s is already under " +
                    "the ${minFlingPx.f(1)}px/s fling threshold; straight to hold=${HOLD_MS}ms then lift"
            )
            return
        }

        phase = Phase.DECEL
        Trace.i(
            "DECEL",
            "reason=$reason from=${decelFromVelocity.f(1)}px/s y=${yPos.f(1)} " +
                "over=${DECEL_MS}ms then hold=${HOLD_MS}ms then lift"
        )
    }

    private fun abortChain(reason: String) {
        lastStroke = null
        phase = Phase.IDLE
        running = false
        Trace.e("ABORT", "reason=$reason ${statsSummary()}")
        onStateChanged?.invoke()
    }

    private val callback = object : AccessibilityService.GestureResultCallback() {
        override fun onCompleted(gestureDescription: GestureDescription?) {
            completedAt = SystemClock.uptimeMillis()
            val actual = completedAt - dispatchedAt
            val jitter = actual - lastSegmentMs
            val v = velocityAt(phase, phaseElapsed)
            lastActualMs = actual
            lastCompletedPhase = phase
            Trace.i(
                "TICK",
                "seq=$seq phase=$phase target=${lastSegmentMs}ms actual=${actual}ms " +
                    "jitter=${jitter.signed()}ms ratio=${timingRatio.f(3)} " +
                    "v=${v.f(1)}px/s (${(v / density).f(1)}dp/s) " +
                    "y=${yPos.f(2)} cum=${(cumulativePx / density).f(2)}dp" +
                    if (kotlin.math.abs(jitter) > JITTER_FLAG_MS) "  <-- JITTER" else ""
            )
            advance()
        }

        override fun onCancelled(gestureDescription: GestureDescription?) {
            completedAt = SystemClock.uptimeMillis()
            cancelCount++
            val pressAgeMs = completedAt - pressStartedAt
            val segElapsedMs = completedAt - dispatchedAt

            // One greppable line carrying everything needed to tell an age-related
            // cancellation from a spurious one: cluster the ages, and check whether
            // they correlate with position or with the phase instead.
            Trace.w(
                "CANCEL",
                "n=$cancelCount pressAge=${pressAgeMs}ms phase=$phase seq=$seq " +
                    "segElapsed=${segElapsedMs}ms segTarget=${lastSegmentMs}ms " +
                    "y=${yPos.f(1)} yFrac=${(yPos / screenH).f(3)} " +
                    "press=$pressCount regrips=$regripCount cap=${currentCapMs}ms " +
                    "${configSummary()} | " +
                    if (repressOnCancel) "re-pressing in ${REPRESS_DELAY_MS}ms" else "ending the run"
            )
            lastStroke = null
            if (running && repressOnCancel) {
                handler.postDelayed({ if (running) beginPress("after-cancel") }, REPRESS_DELAY_MS)
            } else {
                running = false
                phase = Phase.IDLE
                Trace.i("STOP", "ended by cancellation (repress=off) ${statsSummary()}")
                onCancelledByTouch?.invoke()
                onStateChanged?.invoke()
            }
        }
    }

    private fun advance() {
        when (phase) {
            Phase.KICK -> {
                // Visible motion across the WHOLE lead-in, not just the kick.
                //
                // The earlier version of this line reported only the margin term over
                // the lead-in duration and called the result the "visible lurch". That was wrong by
                // roughly 4x: it ignored the RAMP, which starts at kickPxPerS() and decays
                // to cruise, and which carries ~90% of the visible motion at margin 1dp.
                // The margin constant was tuned against that broken measure.
                //
                // The host discards `slop` of the kick, so the kick contributes only the
                // margin; the ramp contributes all of its displacement.
                val visibleKickDp = slopMarginDp
                val visibleRampDp = integrate(Phase.RAMP, 0.0, RAMP_MS) / density
                val visibleTotalDp = visibleKickDp + visibleRampDp
                val leadInSeconds = (effectiveLeadInMs + RAMP_MS) / 1000.0
                val meanDpS = visibleTotalDp / leadInSeconds
                Trace.i(
                    "LEADIN",
                    "cleared slop: commanded=${((slopPx + slopMarginDp * density) / density).f(1)}dp " +
                        "in ${effectiveLeadInMs}ms at ${(kickPxPerS() / density).f(1)}dp/s " +
                        "(host app discards ~${(slopPx / density).f(1)}dp of it), " +
                        "long-press timer is ${longPressTimeoutMs}ms | " +
                        "VISIBLE kick=${visibleKickDp.f(1)}dp + ramp=${visibleRampDp.f(1)}dp " +
                        "= ${visibleTotalDp.f(1)}dp over ${effectiveLeadInMs + RAMP_MS}ms, " +
                        "mean=${meanDpS.f(1)}dp/s=${(meanDpS / speedDp).f(2)}x cruise, " +
                        "peak=${(kickPxPerS() / density / speedDp).f(1)}x cruise, " +
                        "ramp=${(100.0 * visibleRampDp / visibleTotalDp).f(0)}% of it"
                )
                phase = Phase.RAMP
                phaseElapsed = 0.0
            }

            Phase.RAMP -> {
                phaseElapsed += lastSegmentMs
                if (phaseElapsed >= RAMP_MS) {
                    phase = Phase.CRUISE
                    phaseElapsed = 0.0
                    onCruiseReached()
                }
            }

            Phase.CRUISE -> phaseElapsed += lastSegmentMs

            Phase.DECEL -> {
                phaseElapsed += lastSegmentMs
                if (phaseElapsed >= DECEL_MS) {
                    phase = Phase.HOLD
                    phaseElapsed = 0.0
                }
            }

            // The HOLD segment carried willContinue = false, so completing it is
            // the lift. Nothing is in flight now.
            Phase.HOLD -> {
                onLifted()
                return
            }

            Phase.IDLE -> return
        }
        planAndDispatch()
    }

    private fun onCruiseReached() {
        if (disturbStartedAt != 0L) {
            val dead = SystemClock.uptimeMillis() - disturbStartedAt
            Trace.i(
                "REGRIP",
                "n=$regripCount deadMs=$dead (decel -> lift -> reposition -> press -> lead-in -> cruise) " +
                    "slopLostDp=${(slopPx / density).f(1)} nextBudget=${travelBudgetSeconds().f(1)}s"
            )
            disturbStartedAt = 0L
        }
    }

    private fun onLifted() {
        lastStroke = null
        val held = completedAt - pressStartedAt
        Trace.i(
            "LIFT",
            "n=$pressCount reason=$endReason heldMs=$held yFinal=${yPos.f(1)} " +
                "(band floor ${endY.f(1)}) ${statsSummary()}"
        )

        if (!running) {
            phase = Phase.IDLE
            if (stopRequestedAt != 0L) {
                Trace.i(
                    "STOP",
                    "complete latencyMs=${completedAt - stopRequestedAt} " +
                        "(remaining segment + ${DECEL_MS}ms decel + ${HOLD_MS}ms hold)"
                )
            }
            onStateChanged?.invoke()
            return
        }

        regripCount++
        beginPress("regrip")
    }

    private fun statsSummary() =
        "presses=$pressCount regrips=$regripCount cancels=$cancelCount cum=${(cumulativePx / density).f(2)}dp"

    /** Fired on the tick thread whenever the notification needs to change. */
    var onStateChanged: (() -> Unit)? = null

    /**
     * Fired when a run ends because a real touch cancelled the gesture (touch-to-stop, repress off) —
     * i.e. the user touched the screen. Distinct from [onStateChanged], which fires on any state
     * change. The service uses it to open the non-actionable window so the touch that stopped the
     * scroll cannot double as the tap that restarts it. Fires just before [onStateChanged].
     */
    var onCancelledByTouch: (() -> Unit)? = null

    companion object {
        const val DEFAULT_SPEED_DP = 12.0
        const val DEFAULT_SEGMENT_MS = 100L

        /**
         * 25%..75% of screen height. This was widened to 12%..88% mid-spike on the
         * theory that travel bounds a press, then narrowed back once measurement
         * showed both halves of that theory were wrong:
         *
         * 1. Travel stopped being the binding constraint. Chain decay forces a
         *    re-grip at MAX_PRESS_MS, and below ~20 dp/s that fires long before the
         *    band runs out -- at 12 dp/s a press used only 366dp of 593dp available.
         *    The extra travel bought nothing at reading speeds.
         * 2. The wide band is actively hazardous. At 88% height on a Vanity Fair
         *    article the finger landed directly on a sticky "GET DIGITAL ACCESS"
         *    footer. It scrolled anyway, but a sticky element that consumes its own
         *    touches -- carousel, bottom nav, floating button -- would swallow the
         *    drag entirely.
         *
         * Above ~20 dp/s travel does bind again and the narrower band costs
         * re-grip frequency. That trade is accepted: reading speeds are the point.
         *
         * Edge back-gestures activate within ~20-30dp of the left/right edges,
         * which a finger parked at x = 50% never touches. Vertical clearance of the
         * status bar and nav strip is still an assumption -- VERIFY PER DEVICE.
         */
        const val BAND_TOP_FRAC = 0.25
        const val BAND_BOTTOM_FRAC = 0.75

        /**
         * THE DOMINANT LEVER ON RE-GRIP SMOOTHNESS, and currently untuned.
         *
         * kickPxPerS() is (slop + margin) / LEAD_IN_MS, so this constant sets the
         * ramp's starting velocity and therefore ~90% of the visible motion after
         * a re-grip. The margin is a weak lever by comparison: at margin 0 the
         * kick still runs at slop/LEAD_IN_MS = 67 dp/s, 5.6x cruise, because the
         * slop clearance floor binds exactly where the problem is worst.
         *
         * There is roughly 3x unused headroom here. Slop must be cleared before
         * the host's long-press timer fires -- 400ms on a Moto G54, 500ms on an
         * SM-P200 -- and this currently clears it at 120ms. Stretching toward
         * ~350ms lowers kickPxPerS() proportionally: at 350ms and margin 1dp the
         * peak drops from 6.3x cruise to ~2.2x and the whole lead-in falls to
         * ~0.7x cruise, which is what the old broken metric wrongly claimed was
         * already true.
         *
         * The cost is re-grip dead time: LEAD_IN_MS + RAMP_MS is part of it, so
         * 120+200 becoming 350+200 grows deadMs from ~450ms to ~680ms. That is a
         * real trade, and the evidence points toward taking it -- an observer
         * judged the discontinuity, not the pause -- but it is untested. Measure
         * with the corrected LEADIN metric before changing it.
         */
        const val DEFAULT_LEAD_IN_MS = 120L

        /**
         * Hard floor. Below this the kick is a near-instantaneous jump, and the
         * segment would also risk falling under MIN_SEGMENT_PX at low speeds.
         */
        const val MIN_LEAD_IN_MS = 40L

        /**
         * Fraction of the device's measured long-press timeout the lead-in may
         * occupy. 0.75 leaves a quarter of the window as headroom: on a Moto G54
         * (400ms) that caps the lead-in at 300ms, on an SM-P200 (500ms) at 375ms.
         *
         * Deliberately conservative. The whole purpose of the kick is to clear
         * slop before long-press fires, and a lead-in that outruns the deadline
         * converts the mitigation into the failure it was written to prevent.
         */
        const val LEAD_IN_SAFETY_FRACTION = 0.75
        const val RAMP_MS = 200L
        /**
         * 1dp, not the 4dp originally guessed.
         *
         * THIS CONSTANT IS PERCEPTUAL, NOT LOAD-BEARING. It was introduced as a
         * safety margin -- extra distance past slop so the kick would definitely
         * clear it before long-press fired -- and measurement showed that is not
         * what it does. The kick alone does not clear slop; kick + ramp together
         * cover ~16dp inside 320ms against 8.1dp of slop, roughly 2x headroom,
         * comfortably inside the 400ms long-press deadline. Sweeping 4 / 2 / 1 / 0
         * in a WebView article and a PDF preview produced no long-press at ANY
         * value, including zero.
         *
                * What it actually influences is the visible motion after each re-grip —
         * but it is a WEAK lever, and the numbers this constant was originally
         * tuned against were wrong. The old LEADIN metric reported only the
         * margin term and called 1dp "0.7x cruise, an ease-in". Measured
         * properly across kick + ramp: 4dp is 3.98x cruise and 1dp is 2.55x,
         * with the ramp carrying ~90% of it at 1dp. The 4dp -> 1dp change was a
         * real improvement (3.98x -> 2.55x) and the observer verdict that
         * followed it stands, but the lead-in is still a ~2.5x overshoot, not an
         * ease-in.
         *
         * Margin alone cannot fix that: at margin 0 the kick still runs at
         * slop/LEAD_IN_MS = 67 dp/s, 5.6x cruise. The dominant term is
         * LEAD_IN_MS, not the margin — see the note on it. Tune this for feel,
         * not for safety.
         *
         * Caveat that keeps this empirical rather than proven:
         * ViewConfiguration.getLongPressTimeout() is user-configurable in
         * accessibility settings. This device reported 400ms; a user who has
         * shortened it is untested territory, and there the ramp's headroom
         * shrinks. The floor is unnecessary on this hardware, not unnecessary in
         * general.
         */
        const val DEFAULT_SLOP_MARGIN_DP = 1.0

        const val DECEL_MS = 100L
        const val HOLD_MS = 100L

        /** Sub-segment size while a profile is being expressed (kick/ramp/decel). */
        const val PROFILE_SEGMENT_MS = 50L

        const val MIN_SEGMENT_MS = 8L
        const val MAX_SEGMENT_MS = 2000L

        /**
         * Safety net, not a routine constraint. A press is torn down after this
         * long even with travel to spare.
         *
         * This was 30s, on the theory that chains decay with age -- cancellations
         * were observed at 48s and 62s against clean behaviour at 36s. THAT
         * THEORY WAS WRONG. Age was a correlate, not a cause. The real cause was
         * sub-pixel segments: the decel ramp at low speed commanded 0.46px and
         * 0.69px paths, and long chains were simply where slow ramps produced
         * them. Once decel is skipped below the fling threshold (see enterDecel)
         * and MIN_SEGMENT_PX floors displacement, the failures vanish.
         *
         * Controlled probing on a Moto G54 after those fixes: clean age-triggered
         * teardowns at 35s, 40s, 45s and 50s, and a single unbroken 68.07s press
         * that ended on travel with zero cancellations -- past both original
         * failure points. No decay observed anywhere.
         *
         * 120s is a backstop against hardware we have not met, not a target.
         * Travel binds long before it at every practical speed -- 32.5s at
         * 12 dp/s, 68s at 4 dp/s on a 1080x2400 screen -- so in normal operation
         * it never fires. Note this sits above the 68.07s actually demonstrated;
         * reaching it needs roughly 2 dp/s, which is below any plausible reading
         * speed and therefore untested territory. If a device does decay, the
         * CANCEL line is what will show it.
         */
        const val MAX_PRESS_MS = 120_000L

        /** Filter constant for timingRatio. ~2s to settle at 10 segments/s. */
        const val RATE_FILTER_ALPHA = 0.05

        /**
         * Clamp on the correction factor. Deliberately wider than the 0.7..1.4
         * that seemed natural: the measured ratio at 4 dp/s is 0.70, so a 0.7
         * floor would clip exactly the case this fix exists for. 0.60..1.50 keeps
         * the measured range interior while still stopping one pathological
         * completion from lurching.
         */
        const val RATE_RATIO_MIN = 0.60
        const val RATE_RATIO_MAX = 1.50

        /**
         * Floor on commanded displacement per segment. Sub-pixel segments are what
         * an aged chain rejects; 1px is above every observed failure (0.46px,
         * 0.69px) without being large enough to coarsen slow motion, since the
         * cost is paid in segment duration, not in position.
         */
        const val MIN_SEGMENT_PX = 1.0f

        const val HOLD_EPSILON_PX = 1.0f
        const val MIN_PATH_PX = 0.05f
        const val OVERSHOOT_ALLOWANCE_PX = 24.0

        const val JITTER_FLAG_MS = 16L
        const val SEAM_FLAG_MS = 16L

        const val REPRESS_DELAY_MS = 250L
    }
}
