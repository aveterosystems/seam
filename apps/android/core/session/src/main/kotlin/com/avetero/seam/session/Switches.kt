// SPDX-License-Identifier: AGPL-3.0-only
package com.avetero.seam.session

import com.avetero.seam.crypto.PeerPath
import com.avetero.seam.crypto.SeamDevice
import com.avetero.seam.protocol.v1.Control
import com.avetero.seam.protocol.v1.Hello
import com.avetero.seam.protocol.v1.Payload
import com.avetero.seam.protocol.v1.Transport
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * The user's connection switches (decision 0006), what the Mac allows, and the Hello counters both
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
    val relay: Boolean get() = _settings.value.relay

    /**
     * Reads the switches, once the device is open. With [canSave], first moves the Mac-facing state
     * out of an older version's preferences into the app data, saved by [persist].
     */
    fun load(canSave: Boolean, persist: () -> Unit) {
        val prefs = store.load()
        val d = device.appData()
        // One-time move of the Mac-facing state out of the old preferences into the sealed app data
        val inPrefs = prefs.helloSeq != 0L || prefs.macHelloSeq != 0L || prefs.macAllows != null
        if (d != null && inPrefs && canSave) {
            // Whichever is newer wins, so a move that was cut short can never step a counter back
            val mac = prefs.macHelloSeq.toULong() > d.helloSeqApplied || (d.peerAllowed == null && prefs.macAllows != null)
            d.helloSeqSent = maxOf(d.helloSeqSent, prefs.helloSeq.toULong())
            if (mac) {
                d.helloSeqApplied = prefs.macHelloSeq.toULong()
                // An empty list was an older Mac's Hello: everything allowed, as the core reads it now
                d.peerAllowed = prefs.macAllows?.takeIf { it.isNotEmpty() }?.mapNotNull { it.toPath() }
            }
            device.setAppData(d)
            // The old keys go only once the move is saved; until then the next start tries again
            if (runCatching { persist() }.isSuccess) runCatching { store.save(prefs) }
        }
        _settings.value = ConnectionSettings(prefs.wifi, prefs.relay, pausedUntil = prefs.pausedUntil)
        syncPeer()
    }

    /** The switches: user preferences, saved apart from the keys. */
    fun save(wifi: Boolean, relay: Boolean) {
        // From the current value, not one taken before a Hello was queued: that moved the counter on
        val s = _settings.value.copy(wifi = wifi, relay = relay).let { it.copy(allowed = allowedFor(it)) }
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
    fun nextHello(wifi: Boolean = this.wifi, relay: Boolean = this.relay): Payload? {
        val next = device.nextHello(enabled(wifi, relay)) ?: return null
        syncPeer()
        val hello = Hello.newBuilder().setAppVersion(appVersion).setPlatform("android").setSeq(next.seq.toLong())
            .addAllAllowedTransportsValue(next.allowedTransports)
            .setPausedUntilMs(_settings.value.pausedUntil) // the Mac learns the pause in order (SR-32-01)
        return Payload.newBuilder().setControl(Control.newBuilder().setHello(hello)).build()
    }

    /** The Mac's Hello. The relay can hold an old one and release it later: the core applies only a higher counter. False if ignored. */
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
    private fun allowedFor(s: ConnectionSettings) = device.allowedPaths(enabled(s.wifi, s.relay)).toSet()

    /** The switches as the core's path list: the one place they're translated. */
    fun enabled(wifi: Boolean = this.wifi, relay: Boolean = this.relay): List<PeerPath> =
        listOfNotNull(PeerPath.LAN.takeIf { wifi }, PeerPath.RELAY.takeIf { relay })
}

private fun Transport.toPath() = when (this) {
    Transport.TRANSPORT_LAN -> PeerPath.LAN
    Transport.TRANSPORT_RELAY -> PeerPath.RELAY
    else -> null
}

private fun PeerPath.toTransport() = if (this == PeerPath.LAN) Transport.TRANSPORT_LAN else Transport.TRANSPORT_RELAY
