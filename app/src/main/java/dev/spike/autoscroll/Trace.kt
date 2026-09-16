package dev.spike.autoscroll

import android.util.Log
import java.util.Locale

/**
 * Single logcat tag so the whole run reads as one stream:
 *
 *     adb logcat -s AutoScroll:V
 *
 * Every line starts with a fixed-width event name so the stream can be grepped
 * per event type (TICK, SEAM, REGRIP, GEST, ...). See README "Reading the logs".
 */
const val TAG = "AutoScroll"

object Trace {
    fun i(event: String, msg: String) = Log.i(TAG, line(event, msg))
    fun w(event: String, msg: String) = Log.w(TAG, line(event, msg))
    fun e(event: String, msg: String, t: Throwable? = null) =
        if (t == null) Log.e(TAG, line(event, msg)) else Log.e(TAG, line(event, msg), t)

    private fun line(event: String, msg: String) = String.format(Locale.US, "%-7s %s", event, msg)
}

/** Fixed-precision formatting so columns line up when eyeballing a long run. */
fun Double.f(decimals: Int = 2): String = String.format(Locale.US, "%.${decimals}f", this)

fun Float.f(decimals: Int = 2): String = String.format(Locale.US, "%.${decimals}f", this)

/** Signed, for jitter. */
fun Long.signed(): String = if (this >= 0) "+$this" else "$this"
