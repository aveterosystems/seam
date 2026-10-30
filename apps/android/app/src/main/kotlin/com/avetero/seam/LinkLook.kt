// SPDX-License-Identifier: AGPL-3.0-only
package com.avetero.seam

import androidx.annotation.DrawableRes
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
        val Relay = LinkLook(Line.Dashed, "Through the relay", true, R.drawable.ic_cloud)
        val Idle = LinkLook(Line.Broken, "Paired · connects when there's something to sync", false)
        val Reconnecting = LinkLook(Line.Broken, "Reconnecting…", false)
        val WaitingOnWifi = LinkLook(Line.Broken, "Waiting for your Mac on the same Wi‑Fi", false)
        val NoSharedPath = LinkLook(Line.Broken, "Your Mac and this phone share no connection type. Turn on one your Mac allows too", false)

        /** The one place the session's state becomes a look. */
        fun of(state: SessionState, route: Transport?, c: ConnectionSettings): LinkLook {
            fun both(t: Transport) = (if (t == Transport.TRANSPORT_LAN) c.wifi else c.relay) && (c.macAllows?.contains(t) ?: true)
            val wifi = both(Transport.TRANSPORT_LAN)
            val relay = both(Transport.TRANSPORT_RELAY)
            return when {
                route == Transport.TRANSPORT_LAN -> SameWifi
                route == Transport.TRANSPORT_RELAY -> Relay
                !wifi && !relay -> NoSharedPath
                state is SessionState.Idle -> Idle
                wifi && !relay -> WaitingOnWifi
                else -> Reconnecting
            }
        }
    }
}
