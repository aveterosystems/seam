// SPDX-License-Identifier: AGPL-3.0-only
package com.avetero.seam.session

import com.avetero.seam.crypto.AppData
import com.avetero.seam.crypto.SeamDevice
import com.avetero.seam.protocol.v1.ClientFrame
import com.avetero.seam.protocol.v1.Control
import com.avetero.seam.protocol.v1.GetBundle
import com.avetero.seam.protocol.v1.Payload
import com.google.protobuf.ByteString
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Pairing with a new Mac from its scanned QR code: over the pairing channel, asks for its keys,
 * starts the pairing, and switches Macs on its PairAccept. The current Mac stays until the new one accepts. The session
 * owns the paths, the overall state and when to save; this decides what pairing does with them.
 * Touched only from the session's thread.
 */
internal class Pairing(
    private val device: SeamDevice,
    private val myName: String,
    private val myModel: String,
    private val now: () -> Long,
    /** Saves the device state; throws if it couldn't. */
    private val persist: () -> Unit,
    /** Sends a sealed envelope (over the pairing channel). */
    private val send: (ByteArray) -> Unit,
    /** Ends the session's attempt with a reason for the user; calls [abandon]. */
    private val fail: (String) -> Unit,
) {
    /** The code being paired with; null when not pairing. */
    var qr: PairingQr? = null
        private set
    /** When the last pairing was confirmed by its Mac, for a "Codes don't match" tap that just missed it (SR-11-07). */
    private var pairedAt: Long? = null

    /** Null before any pairing ends in this run, and while one is in progress. */
    private val _outcome = MutableStateFlow<PairingOutcome?>(null)
    val outcome: StateFlow<PairingOutcome?> = _outcome

    val active: Boolean get() = qr != null
    /** The new Mac answered on the pairing channel: it took our code. */
    var keysHeard = false
        private set

    fun start(qr: PairingQr) {
        this.qr = qr
        keysHeard = false
        _outcome.value = null
    }

    /** Once the pairing channel is up: ask for the new Mac's keys. Null when not pairing. */
    fun request(): ClientFrame? =
        qr?.let { ClientFrame.newBuilder().setGetBundle(GetBundle.newBuilder().setDeviceKey(ByteString.copyFrom(it.relayKey))).build() }

    /** The new Mac's keys arrived: the pairing request goes out. Returns the state to show, or null if it failed. */
    fun keysArrived(bundle: ByteArray): SessionState.Pairing? {
        val p = qr ?: return null
        keysHeard = true
        if (bundle.isEmpty()) return null.also { fail("Your Mac hasn't finished setting up. Try its pairing code again.") }
        try {
            val start = device.pairWith(p.identityKey, p.relayKey, p.macName, bundle, p.token, myName, myModel)
            if (runCatching { persist() }.isFailure) return null.also { fail(SAVE_FAILED) } // SR-2-64: nothing leaves unsaved
            send(start.envelope)
            return SessionState.Pairing(p.macName, device.safetyCode(start.peerId))
        } catch (_: Exception) {
            fail(CODE_REFUSED)
            return null
        }
    }

    /**
     * True if this was the pairing Mac's PairAccept: the old Mac is dropped and the new onion address
     * and discovery secret set, in memory only; the session saves it, then calls [accepted].
     */
    fun accept(peerId: String, payload: Payload): Boolean {
        val p = qr ?: return false
        val accepted = payload.bodyCase == Payload.BodyCase.CONTROL && payload.control.bodyCase == Control.BodyCase.PAIR_ACCEPT
        val fromPairingMac = device.peers().firstOrNull { it.id == peerId }?.relayKey?.contentEquals(p.relayKey) == true
        if (!accepted || !fromPairingMac) return false
        device.peers().filter { it.id != peerId }.forEach { device.removePeer(it.id) } // the old Mac, now replaced
        // A new Mac: fresh app data, nothing heard from it and no Hello sent to it
        device.setAppData(AppData(p.discoverySecret, p.onionAddress, ByteArray(0), 0u, 0u, null))
        qr = null
        pairedAt = now()
        return true
    }

    /** The PairAccept is saved: only now is it a new Mac, so a restart keeps it (SR-11-06). */
    fun accepted() {
        _outcome.value = PairingOutcome.NewMac
    }

    /**
     * Stops a pairing in progress: the unconfirmed Mac is dropped, and the existing one (if any) is
     * untouched. False if none was in progress.
     */
    fun abandon(): Boolean {
        if (qr == null) return false
        qr = null
        device.peers().filter { !it.confirmed }.forEach { device.removePeer(it.id) }
        runCatching { persist() }
        _outcome.value = if (device.peers().any { it.confirmed }) PairingOutcome.StillOnPreviousMac else PairingOutcome.NoMac
        return true
    }

    /** The Mac is forgotten: any pairing in progress goes with it. */
    fun clear() {
        qr = null
    }

    /** "Codes don't match" just after the Mac confirmed: true once, within [ABORT_GRACE_MS], to undo it (SR-11-07). */
    fun undoable(): Boolean {
        val at = pairedAt ?: return false
        if (now() - at > ABORT_GRACE_MS) return false
        pairedAt = null
        return true
    }

    private companion object {
        /** How long after the Mac confirms a "Codes don't match" tap still undoes the pairing. */
        const val ABORT_GRACE_MS = 5_000L
    }
}
