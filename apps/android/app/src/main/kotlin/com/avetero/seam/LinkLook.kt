// SPDX-License-Identifier: AGPL-3.0-only
package com.avetero.seam

import androidx.annotation.DrawableRes
import com.avetero.seam.crypto.PeerPath
import com.avetero.seam.crypto.TorBlock
import com.avetero.seam.crypto.TorState
import com.avetero.seam.protocol.v1.Transport
import com.avetero.seam.session.ConnectionSettings
import com.avetero.seam.session.SessionState

/**
 * How the link to the Mac is shown, everywhere it's shown (docs/design/design-system.md, "The
 * stitch"). The line, the icon and the words always come from here, together, so no screen
 * decides any of them on its own.
 */
data class LinkLook(val line: Line, val label: String, val connected: Boolean, @DrawableRes val icon: Int? = null) {
    enum class Line { Solid, Dashed, Dotted, Broken }

    /** The whole line in words, for TalkBack and wherever there's no room for the icon. */
    val sentence: String get() = if (connected) "Connected · $label" else label

    companion object {
        val SameWifi = LinkLook(Line.Solid, "Same Wi‑Fi", true, R.drawable.ic_wifi)
        val ThroughTor = LinkLook(Line.Dashed, "Through Tor", true, R.drawable.ic_globe)
        val Idle = LinkLook(Line.Broken, "Paired · connects when there's something to sync", false)
        val Reconnecting = LinkLook(Line.Broken, "Reconnecting…", false)
        val WaitingOnWifi = LinkLook(Line.Broken, "Waiting for your Mac on the same Wi‑Fi", false)
        val ConnectingTor = LinkLook(Line.Broken, "Connecting through Tor…", false)
        val TorBlocked = LinkLook(Line.Broken, "Tor is blocked on this network", false)
        val TorClock = LinkLook(Line.Broken, "Tor needs the correct time. Check your clock", false)
        val TorFailed = LinkLook(Line.Broken, "Tor couldn't start on this phone", false)
        val NoSharedPath = LinkLook(Line.Broken, "Your Mac and this phone share no connection type. Turn on one your Mac allows too", false)

        /** Paused on this phone: nothing syncs, nothing connects, until [until]. */
        fun paused(until: Long, now: Long) = LinkLook(Line.Broken, pausedLabel(until, now), false)

        /** The one place the session's state becomes a look. */
        fun of(
            state: SessionState,
            route: Transport?,
            c: ConnectionSettings,
            tor: TorState = TorState.Off,
            now: Long = System.currentTimeMillis(),
        ): LinkLook {
            if (c.pausedUntil > now) return paused(c.pausedUntil, now)
            val wifi = PeerPath.LAN in c.allowed
            val onion = PeerPath.TOR in c.allowed
            return when {
                route == Transport.TRANSPORT_LAN -> SameWifi
                route == Transport.TRANSPORT_TOR -> ThroughTor
                !wifi && !onion -> NoSharedPath
                // Never a silent fallback: when Tor can't connect, say why (decision 0004)
                onion && tor is TorState.Blocked && tor.reason == TorBlock.CLOCK_SKEWED -> TorClock
                onion && tor is TorState.Blocked && tor.reason != TorBlock.OFFLINE -> TorBlocked
                onion && tor is TorState.Failed -> TorFailed
                state is SessionState.Idle -> Idle
                wifi && !onion -> WaitingOnWifi
                onion && tor is TorState.Starting -> ConnectingTor
                else -> Reconnecting
            }
        }
    }
}
