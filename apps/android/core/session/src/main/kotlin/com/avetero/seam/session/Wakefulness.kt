// SPDX-License-Identifier: AGPL-3.0-only
package com.avetero.seam.session

import com.avetero.seam.protocol.v1.Payload
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Whether a connection is worth having now (decision 0005, CONTRIBUTING rule 8): it's held only
 * while it's cheap (screen on, charging) or needed (activity in the last [IDLE_MS], or whatever the
 * session says it needs). This decides; the session opens and closes the links. When an activity
 * window ends it calls [onWindowEnd] on [scope], the session's single thread.
 */
internal class Wakefulness(
    private val scope: CoroutineScope,
    private val now: () -> Long,
    private val onWindowEnd: () -> Unit,
) {
    var screenOn = true
        private set
    var charging = false
        private set
    /** The connection is wanted until then: [IDLE_MS] after the last thing it carried, longer for a reply window. */
    private var activeUntil = now() + IDLE_MS
    private var idle: Job? = null

    /** On: a fresh activity window, so a Mac copy is there before you can paste. */
    fun setScreenOn(on: Boolean) {
        screenOn = on
        if (on) touch()
    }

    fun setCharging(on: Boolean) {
        charging = on
    }

    /** Wanted if the session [needs] it, or it's cheap, or there was activity in the last [IDLE_MS]. */
    fun wanted(needs: () -> Boolean): Boolean = screenOn || charging || needs() || now() < activeUntil

    /** How long the activity window has left, for the trace. */
    fun activeFor(): Long = activeUntil - now()

    fun touch(windowMs: Long = IDLE_MS) {
        activeUntil = maxOf(activeUntil, now() + windowMs)
        scheduleIdleCheck()
    }

    /** After carrying [payload]: a reply window if the Mac may answer it. */
    fun touchAfter(payload: Payload) = touch(windowFor(payload))

    /**
     * Look again when the activity window ends, if it hasn't. A queued copy that can't go yet is
     * retried by the reconnect backoff, never by a timer: rescheduling for 0 ms spins (SR-8-01)
     */
    fun keepWatching() {
        if (!screenOn && !charging && activeUntil > now()) scheduleIdleCheck()
    }

    private fun scheduleIdleCheck() {
        idle?.cancel()
        idle = scope.launch {
            delay((activeUntil - now()).coerceAtLeast(0))
            onWindowEnd() // the end of an activity window is only ever a reason to close
        }
    }

    companion object {
        /** How long a connection stays after the last thing it carried, when nothing else wants it. */
        const val IDLE_MS = 60_000L
        /** After a notification you can reply to: long enough that a reply from the Mac needs no push. */
        const val REPLY_WINDOW_MS = 5 * 60_000L

        fun windowFor(payload: Payload): Long =
            if (payload.hasNotification() && payload.notification.canReply) REPLY_WINDOW_MS else IDLE_MS
    }
}
