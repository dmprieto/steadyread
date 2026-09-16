package dev.spike.autoscroll

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * The only control channel present in a release build.
 *
 * Three properties, and each one is an assertion the declaration ratchet reads
 * back out of the built APK (see tools/declaration-ratchet.sh):
 *
 *  1. **Not exported.** Declared `android:exported="false"` in the manifest and
 *     given no `<intent-filter>`, so it is reachable only by an explicit intent
 *     from this app's own uid -- in practice, the notification's PendingIntents.
 *     This replaces the spike's runtime-registered RECEIVER_EXPORTED receiver,
 *     which any installed app could drive with no permission and no user
 *     interaction.
 *
 *  2. **It reads no extras.** Not "it validates its extras" -- it does not call
 *     any Intent extra accessor at all. The command is carried by the *action*,
 *     so there is no key/value surface to widen. That is what makes the ratchet
 *     assertion a property rather than a list of flag names: a release build
 *     accepts no configuration, so no configuration extra can weaken a safety
 *     property, whether or not anyone remembered to name it.
 *
 *     Why that matters, concretely. Two extras on the old exported receiver were
 *     safety-critical and only one of them was ever recognised as such:
 *     `repress=true` defeats touch-to-stop, which is the app's only safety
 *     property, and `leadin=false` plus `speed=6` together drop cruise below the
 *     long-press threshold and make the synthetic finger long-press whatever is
 *     under it. `repress` was named in the ratchet spec. `leadin` was not,
 *     because nobody knew until 19 Aug 2026 that it was a safety property.
 *     Enumerating flags keeps losing that race; removing the channel does not.
 *
 *  3. **It is the only control path that ships.** The spike also carried a
 *     DebugControlReceiver (adb tuning) in a debug-only source set; the reading
 *     port drops that source set, so this notification channel is the only
 *     control path in the repo -- release or otherwise.
 */
class ControlReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context?, intent: Intent?) {
        val service = AutoScrollService.live
        if (service == null) {
            // Reachable in normal use: the notification survives the service
            // being disabled or force-stopped, and the six "enabled but not
            // working" states all land here.
            Trace.w("CTRL", "dropped ${intent?.action} - service not connected")
            return
        }
        when (intent?.action) {
            ACTION_START -> service.startFromOwnUi()
            ACTION_STOP -> service.stopFromOwnUi()
            ACTION_TOGGLE -> service.toggleFromOwnUi()
            else -> Trace.w("CTRL", "unknown action=${intent?.action}")
        }
    }

    companion object {
        /**
         * Namespaced under `.internal.` as a signpost, not as a security
         * measure: secrecy of an action string protects nothing. What protects
         * this receiver is `android:exported="false"` plus the absence of an
         * intent filter, both of which the OS enforces.
         */
        const val ACTION_START = "dev.spike.autoscroll.internal.START"
        const val ACTION_STOP = "dev.spike.autoscroll.internal.STOP"
        const val ACTION_TOGGLE = "dev.spike.autoscroll.internal.TOGGLE"
    }
}
