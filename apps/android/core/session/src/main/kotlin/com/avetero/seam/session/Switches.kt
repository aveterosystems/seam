// SPDX-License-Identifier: AGPL-3.0-only
package com.avetero.seam.session

import com.avetero.seam.crypto.PeerPath
import com.avetero.seam.crypto.SeamDevice
import com.avetero.seam.protocol.v1.Control
import com.avetero.seam.protocol.v1.Hello
import com.avetero.seam.protocol.v1.OpenLinks
import com.avetero.seam.protocol.v1.Payload
import com.avetero.seam.protocol.v1.Transport
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * The user's connection switches, what the Mac allows, and the Hello counters both
 * ways. The switches are preferences in [store]; the Mac-facing state is the core's, kept in the
 * sealed app data. The session decides which paths to open and what to send where.
 */
internal class Switches(
    private val device: SeamDevice,
    private val store: SettingsStore,
    private val appVersion: String,
) {
    private val _settings = MutableStateFlow(ConnectionSettings())
    /** The connection switches, and what the Mac allows. */
    val settings: StateFlow<ConnectionSettings> = _settings

    val wifi: Boolean get() = _settings.value.wifi
    val tor: Boolean get() = _settings.value.tor

    /** Reads the switches, once the device is open. */
    fun load() {
        val prefs = store.load()
        _settings.value = ConnectionSettings(prefs.wifi, prefs.tor, pausedUntil = prefs.pausedUntil)
        syncPeer()
    }

    /** The switches: user preferences, saved apart from the keys. */
    fun save(wifi: Boolean, tor: Boolean) {
        // From the current value, not one taken before a Hello was queued: that moved the counter on
        val s = _settings.value.copy(wifi = wifi, tor = tor).let { it.copy(allowed = allowedFor(it)) }
        _settings.value = s
        runCatching { store.save(s) } // only preferences: the switches still apply this run
    }

    /** Paused until then (0: not paused); a preference like the switches, saved apart from the keys. */
    fun savePause(until: Long) {
        val s = _settings.value.copy(pausedUntil = until)
        _settings.value = s
        runCatching { store.save(s) }
    }

    /**
     * The next Hello telling the Mac these switches, counted up in the app data now; the session
     * seals it and persists both in one write before anything is sent. Null if there's no Mac to tell.
     */
    /** [open]: the links this phone has open to the Mac now, so the Mac can close any it holds that died. */
    fun nextHello(wifi: Boolean = this.wifi, tor: Boolean = this.tor, open: List<Transport> = emptyList()): Payload? {
        val next = device.nextHello(enabled(wifi, tor)) ?: return null
        syncPeer()
        val hello = Hello.newBuilder().setAppVersion(appVersion).setPlatform("android").setSeq(next.seq.toLong())
            .addAllAllowedTransportsValue(next.allowedTransports)
            .setPausedUntilMs(_settings.value.pausedUntil) // the Mac learns the pause in order (SR-32-01)
            .setOpenLinks(OpenLinks.newBuilder().addAllTransports(open))
        return Payload.newBuilder().setControl(Control.newBuilder().setHello(hello)).build()
    }

    /** The Mac's Hello. A path can deliver an old one late: the core applies only a higher counter. False if ignored. */
    fun macHello(h: Hello): Boolean {
        if (!device.applyHello(h.seq.toULong(), h.allowedTransportsValueList)) return false
        syncPeer()
        return true
    }

    /**
     * Shows the Mac-facing state the core keeps in the sealed app data (saved with the keys by the
     * next persist(), never through preferences).
     */
    fun syncPeer() {
        val d = device.appData()
        val s = _settings.value
        _settings.value = s.copy(
            macAllows = d?.peerAllowed?.map { it.toTransport() }?.toSet(),
            macHelloSeq = d?.helloSeqApplied?.toLong() ?: 0,
            helloSeq = d?.helloSeqSent?.toLong() ?: 0,
            allowed = allowedFor(s),
        )
    }

    /** The core's answer, so the phone never re-derives "on at both ends" itself. */
    private fun allowedFor(s: ConnectionSettings) = device.allowedPaths(enabled(s.wifi, s.tor)).toSet()

    /** The switches as the core's path list: the one place they're translated. */
    fun enabled(wifi: Boolean = this.wifi, tor: Boolean = this.tor): List<PeerPath> =
        listOfNotNull(PeerPath.LAN.takeIf { wifi }, PeerPath.TOR.takeIf { tor })
}

private fun PeerPath.toTransport() = if (this == PeerPath.LAN) Transport.TRANSPORT_LAN else Transport.TRANSPORT_TOR
