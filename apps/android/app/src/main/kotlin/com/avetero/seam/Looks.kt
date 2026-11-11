// SPDX-License-Identifier: AGPL-3.0-only
package com.avetero.seam

import androidx.annotation.DrawableRes
import com.avetero.seam.crypto.DeviceStatusLook
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
import com.avetero.seam.crypto.deviceStatusLook
import com.avetero.seam.crypto.linkLook
import com.avetero.seam.feature.MacStatus
import com.avetero.seam.protocol.v1.Transport
import com.avetero.seam.session.ConnectionSettings
import com.avetero.seam.session.SessionState
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Locale

// What each state shows is decided once, in Rust, for every app (seam-looks, decision 0015). This
// file only gives it this phone's names and time words, turns the session's state into the link's
// input, and maps each icon meaning to a drawable (docs/design/design-system.md, "Icons").

/** How the phone names itself and the Mac. */
val PhoneNames = LookNames(LookSide.PHONE, "this phone", "your Mac")

/** The phone's clock and calendar; [zone], [hours24] and [locale] for tests, otherwise the phone's own at each use. */
class SystemTime(
    private val zone: ZoneId? = null,
    private val hours24: Boolean = is24Hour(),
    private val locale: Locale = Locale.getDefault(),
) : TimeWords {
    private fun at(ms: Long) = Instant.ofEpochMilli(ms).atZone(zone ?: ZoneId.systemDefault())
    override fun time(ms: Long): String = at(ms).format(DateTimeFormatter.ofPattern(if (hours24) "HH:mm" else "h:mm a", locale))
    override fun date(ms: Long): String = at(ms).format(DateTimeFormatter.ofPattern("d MMM", locale))
    override fun daysBetween(ms: Long, nowMs: Long): Int = ChronoUnit.DAYS.between(at(ms).toLocalDate(), at(nowMs).toLocalDate()).toInt()

    companion object {
        /** The phone's 12/24-hour setting, read at each use; the app sets it, JVM tests keep 24-hour. */
        @Volatile var is24Hour: () -> Boolean = { true }
    }
}

/**
 * What sending to the Mac says, on every surface: Home's button and snackbar, the share sheet,
 * the notification's toast, the clipboard rows. One wording per outcome, with the Mac's name.
 */
object SendWords {
    const val SENT = "✓ Sent to Mac"
    const val SENDING = "Sending"
    const val NOTHING = "Nothing to send: the clipboard is empty, too big, or holds something private"
    /** The clipboard rows' line, where the Mac's name would crowd it. */
    const val SYNC_OFF_SHORT = "Off · sync is off on your Mac"
    /** Under Home's greyed Send: why, where the Automatic clipboard row already says sync is off. */
    const val SYNC_OFF_SEND = "Send needs clipboard sync on your Mac"
    fun syncOff(mac: String) = "$mac has clipboard sync off. Turn it on in Seam on your Mac."
    fun queued(mac: String) = "Will send when $mac is back"
    fun sendTo(mac: String) = "Send to $mac"
    fun cantSendTo(mac: String) = "Can't send to $mac"
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

    /** The Mac's battery, storage, CPU and memory; "as of" when it came unless connected (paused can't refresh it). */
    fun mac(mac: MacStatus, link: LinkLook): DeviceStatusLook? =
        deviceStatusLook(mac.status.toByteArray(), link.connected, mac.at, false, SystemTime())

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
        is LookIcon.Battery -> if (charging) R.drawable.ic_battery_charging else when (level.toInt()) {
            100 -> R.drawable.ic_battery_100
            75 -> R.drawable.ic_battery_75
            50 -> R.drawable.ic_battery_50
            25 -> R.drawable.ic_battery_25
            else -> R.drawable.ic_battery_0
        }
        LookIcon.Storage -> R.drawable.ic_storage
        LookIcon.Cpu -> R.drawable.ic_cpu
        LookIcon.Memory -> R.drawable.ic_memory
        else -> null
    }
