// SPDX-License-Identifier: AGPL-3.0-only
package com.avetero.seam

import androidx.annotation.DrawableRes
import com.avetero.seam.crypto.LinkInput
import com.avetero.seam.crypto.LinkLook
import com.avetero.seam.crypto.LinkReconnect
import com.avetero.seam.crypto.LookIcon
import com.avetero.seam.crypto.LookNames
import com.avetero.seam.crypto.LookSide
import com.avetero.seam.crypto.PeerPath
import com.avetero.seam.crypto.TimeWords
import com.avetero.seam.crypto.TorState
import com.avetero.seam.crypto.agoWords
import com.avetero.seam.crypto.linkLook
import com.avetero.seam.protocol.v1.Transport
import com.avetero.seam.session.ConnectionSettings
import com.avetero.seam.session.SessionState
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit

// What each state shows is decided once, in Rust, for every app (seam-looks, decision 0015). This
// file only gives it this phone's names and time words, turns the session's state into the link's
// input, and maps each icon meaning to a drawable (docs/design/design-system.md, "Icons").

/** How the phone names itself and the Mac. */
val PhoneNames = LookNames(LookSide.PHONE, "this phone", "your Mac")

/** The phone's clock and calendar; [zone] for tests, otherwise the phone's own at each use. */
class SystemTime(private val zone: ZoneId? = null) : TimeWords {
    private fun at(ms: Long) = Instant.ofEpochMilli(ms).atZone(zone ?: ZoneId.systemDefault())
    override fun time(ms: Long): String = at(ms).format(DateTimeFormatter.ofPattern("HH:mm"))
    override fun date(ms: Long): String = at(ms).format(DateTimeFormatter.ofPattern("d MMM"))
    override fun daysBetween(ms: Long, nowMs: Long): Int = ChronoUnit.DAYS.between(at(ms).toLocalDate(), at(nowMs).toLocalDate()).toInt()
}

object Looks {
    /** The one place the session's state becomes the link's look. */
    fun link(state: SessionState, route: Transport?, c: ConnectionSettings, tor: TorState = TorState.Off, now: Long = System.currentTimeMillis()): LinkLook =
        linkLook(input(state, route, c, tor), PhoneNames, null, now, SystemTime())

    fun input(state: SessionState, route: Transport?, c: ConnectionSettings, tor: TorState): LinkInput = LinkInput(
        route = when (route) {
            Transport.TRANSPORT_LAN -> PeerPath.LAN
            Transport.TRANSPORT_TOR -> PeerPath.TOR
            else -> null
        },
        lanAllowed = PeerPath.LAN in c.allowed,
        torAllowed = PeerPath.TOR in c.allowed,
        tor = tor,
        pausedUntilMs = c.pausedUntil.takeIf { it > 0 },
        pausedHere = true,
        reconnect = LinkReconnect.ASKED.takeIf { state is SessionState.Connecting && state.reconnecting },
        idle = state is SessionState.Idle,
    )

    /** "12 min ago", "today at 14:02". */
    fun ago(at: Long, now: Long = System.currentTimeMillis(), time: TimeWords = SystemTime()): String = agoWords(at, now, "just now", time)

    // Fixed looks, for screens shown before there's a session, the gallery and tests
    private fun sample(route: PeerPath? = null, idle: Boolean = false, pausedUntil: Long? = null, now: Long = 0) = linkLook(
        LinkInput(route, lanAllowed = true, torAllowed = true, tor = TorState.Ready, pausedUntilMs = pausedUntil, pausedHere = true, reconnect = null, idle = idle),
        PhoneNames, null, now, SystemTime(),
    )
    val sameWifi: LinkLook get() = sample(route = PeerPath.LAN)
    val throughTor: LinkLook get() = sample(route = PeerPath.TOR)
    val offline: LinkLook get() = sample()
    val idle: LinkLook get() = sample(idle = true)
    fun paused(until: Long, now: Long): LinkLook = sample(pausedUntil = until, now = now)
}

/** This meaning's drawable, for the meanings the phone draws. */
@get:DrawableRes
val LookIcon.drawable: Int?
    get() = when (this) {
        LookIcon.SameWifi, LookIcon.Wifi -> R.drawable.ic_wifi
        LookIcon.ThroughTor -> R.drawable.ic_globe
        LookIcon.Paused -> R.drawable.ic_pause
        else -> null
    }
