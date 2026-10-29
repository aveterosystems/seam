// SPDX-License-Identifier: AGPL-3.0-only
package com.avetero.seam.session

import com.avetero.seam.crypto.PeerInfo
import com.avetero.seam.crypto.SeamDevice
import com.avetero.seam.crypto.SeamException
import com.avetero.seam.protocol.v1.ClientFrame
import com.avetero.seam.protocol.v1.Control
import com.avetero.seam.protocol.v1.Delivered
import com.avetero.seam.protocol.v1.Envelope
import com.avetero.seam.protocol.v1.GetBundle
import com.avetero.seam.protocol.v1.Login
import com.avetero.seam.protocol.v1.Payload
import com.avetero.seam.protocol.v1.RemoveMember
import com.avetero.seam.protocol.v1.Unpair
import com.avetero.seam.protocol.v1.ServerFrame
import com.google.protobuf.ByteString
import java.util.Base64
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

sealed interface SessionState {
    data object Unpaired : SessionState
    /** Scanned; `safetyCode` appears once the request is sent, for the user to compare with the Mac. */
    data class Pairing(val macName: String, val safetyCode: String?) : SessionState
    data class Connecting(val macName: String) : SessionState
    data class Connected(val macName: String) : SessionState
    /** Paired, connection closed to save battery: it connects again to send, on screen on, or when charging (decision 0005). */
    data class Idle(val macName: String) : SessionState
    data class Failed(val reason: String) : SessionState
}

/**
 * The phone's link to its Mac: relay login, pairing, delivery, acks and reconnects. Everything
 * runs on [scope], which must be single-threaded, so state is only ever touched from one thread.
 */
class Session(
    private val store: StateStore,
    private val connector: RelayConnector,
    private val myName: String,
    private val myModel: String,
    private val scope: CoroutineScope,
    private val wait: suspend (Long) -> Unit = { delay(it) },
    private val now: () -> Long = System::currentTimeMillis,
    /** Debug builds only: the connection manager's decisions, never content, keys or addresses. Null: not built at all. */
    private val trace: ((String) -> Unit)? = null,
) : SessionApi {
    private val _state = MutableStateFlow<SessionState>(SessionState.Unpaired)
    val state: StateFlow<SessionState> = _state

    /** A one-off message for the user, e.g. why a new pairing failed while the old one carries on. */
    private val _notice = MutableStateFlow<String?>(null)
    val notice: StateFlow<String?> = _notice

    /** Set when saved state couldn't be opened for a reason that may pass: nothing is saved over it. */
    private var storageBlocked = false
    private var device: SeamDevice = openDevice()

    private val features = mutableListOf<Feature>()
    private val backoff = Backoff()
    /** Unsealed and bounded: sealed only when it can be sent, right after a durable save. */
    private val outbox = ArrayDeque<Queued>()

    /** When it was queued: a Clipboard or Status older than its TTL is dropped, not sent late (SR-4-04). */
    private class Queued(val payload: Payload, val at: Long)
    private var link: RelayLink? = null
    private var generation = 0
    private var loggedIn = false
    private var pairing: PairingQr? = null
    /** When the last pairing was confirmed by its Mac, for a "Codes don't match" tap that just missed it (SR-11-07). */
    private var pairedAt: Long? = null
    private var reconnect: Job? = null
    /** Resets the backoff once a connection has lasted [STABLE_MS] (SR-2-61). */
    private var stable: Job? = null
    /** Logins the relay refused in a row because it doesn't know this phone. */
    private var refusals = 0

    // Decision 0005: the connection is held only while it's cheap or needed
    private var screenOn = true
    private var charging = false
    private var lastActivity = now()
    private var idle: Job? = null

    private val mac: PeerInfo? get() = device.peers().firstOrNull { it.confirmed }
    private val target: RelayTarget?
        get() = pairing?.let { RelayTarget(it.relayUrl, it.groupId) } ?: RelayTarget.decode(device.appData())

    /**
     * Never generates over saved state it couldn't open. Only state that can never be opened (its
     * key is gone, or the core rejects it) is moved aside; any other error (a Keystore that isn't
     * ready, a read error) leaves the files alone and blocks every save until Seam restarts (SR-2-57).
     */
    private fun openDevice(): SeamDevice {
        try {
            val blob = store.load()
            if (blob != null) return SeamDevice.restore(blob, store.storageKey())
        } catch (e: Exception) {
            val permanent = e is StateUnreadableException || e is SeamException
            if (!permanent || runCatching { store.quarantine() }.isFailure) {
                storageBlocked = true
                _state.value = SessionState.Failed(UNAVAILABLE)
                return SeamDevice.generate() // in memory only: persist() refuses to save it
            }
            _state.value = SessionState.Failed(RESET)
        }
        // A failed first save is retried by the next persist()
        return SeamDevice.generate().also { d -> runCatching { store.save(d.exportState(store.storageKey())) } }
    }

    fun register(feature: Feature) {
        features += feature
    }

    fun start() = scope.launch {
        val m = mac ?: return@launch
        if (_state.value == SessionState.Failed(RESET)) return@launch
        if (!wanted()) return@launch run { _state.value = SessionState.Idle(m.name) }
        _state.value = SessionState.Connecting(m.name)
        connect()
    }

    /** The screen turned on or off. On: connect, so a Mac copy is there before you can paste. */
    fun setScreenOn(on: Boolean) = scope.launch {
        screenOn = on
        if (on) touch()
        reconsider(connect = on)
    }

    /** Plugged in or unplugged. While charging the connection stays open: power doesn't matter. */
    fun setCharging(on: Boolean) = scope.launch {
        charging = on
        reconsider(connect = on)
    }

    /**
     * Whether a connection is worth having now: pairing, screen on, charging, something urgent to
     * send, or activity in the last [IDLE_MS]. Otherwise it costs radio wake-ups for nothing.
     */
    private fun wanted(): Boolean =
        pairing != null || screenOn || charging || outbox.any { it.payload.bodyCase !in LAZY && !expired(it) } ||
            now() - lastActivity < IDLE_MS

    /** Past its TTL: dropped at the next flush, and no reason to keep trying to connect (SR-8-01). */
    private fun expired(q: Queued): Boolean {
        val ttl = ttlFor(q.payload)
        return ttl != 0u && now() - q.at > ttl.toLong() * 1000
    }

    private fun touch() {
        lastActivity = now()
        scheduleIdleCheck()
    }

    private fun scheduleIdleCheck() {
        idle?.cancel()
        idle = scope.launch {
            delay((lastActivity + IDLE_MS - now()).coerceAtLeast(0))
            reconsider(connect = false) // the end of an activity window is only ever a reason to close
        }
    }

    /**
     * Close the connection if nothing wants it any more; with [connect], also open it if something
     * does. A failed session is left as it is: the user sees why and acts.
     */
    private fun reconsider(connect: Boolean = true) {
        val m = mac ?: return
        trace?.invoke(
            "reconsider connect=$connect screen=$screenOn charging=$charging urgent=${outbox.count { it.payload.bodyCase !in LAZY }} " +
                "sinceActivity=${now() - lastActivity}ms link=${link != null} pairing=${pairing != null} state=${_state.value::class.simpleName}",
        )
        if (pairing != null || storageBlocked || _state.value is SessionState.Failed) return
        if (wanted()) {
            if (connect && link == null && reconnect?.isActive != true) {
                _state.value = SessionState.Connecting(m.name)
                connect()
            }
            // Look again when the activity window ends, if it hasn't. A queued copy that can't go yet is
            // retried by the reconnect backoff, never by a timer: rescheduling for 0 ms spins (SR-8-01)
            if (!screenOn && !charging && lastActivity + IDLE_MS > now()) scheduleIdleCheck()
        } else if (link != null || reconnect?.isActive == true) {
            sleep(m)
        }
    }

    /** Closes the connection on purpose: no reconnect until something wants one. */
    private fun sleep(m: PeerInfo) {
        trace?.invoke("sleep: closing the idle connection")
        stable?.cancel()
        reconnect?.cancel()
        generation++ // so this socket's close callback doesn't count as a drop
        link?.close()
        link = null
        loggedIn = false
        _state.value = SessionState.Idle(m.name)
    }

    /** Paired with a Mac (saved state), whatever the connection is doing. */
    val paired: Boolean get() = mac != null

    /** The code both screens showed when pairing, for re-checking in Settings. */
    fun macCode(): String? = mac?.let { runCatching { device.safetyCode(it.id) }.getOrNull() }

    /** Pair with the Mac from its scanned (and user-confirmed) QR code. The current Mac stays until the new one accepts. */
    fun pair(qr: PairingQr) = scope.launch {
        if (storageBlocked) return@launch // pairing would replace the state we couldn't open
        outbox.clear()
        pairing = qr
        _notice.value = null
        _state.value = SessionState.Pairing(qr.macName, null)
        connect()
    }

    /**
     * "Forget this Mac": tells the Mac (sealed), leaves the relay group, and forgets it here. While
     * offline only the last step happens; the Mac can still unpair from its side.
     */
    fun forgetMac() = scope.launch {
        val m = mac ?: return@launch
        if (loggedIn && !storageBlocked) {
            val unpair = Payload.newBuilder().setControl(Control.newBuilder().setUnpair(Unpair.getDefaultInstance())).build()
            runCatching {
                val env = device.seal(m.id, unpair.toByteArray(), 0u)
                persist()
                sendEnvelope(env)
                // Leaving removes only this phone's own queue: the Unpair waits in the Mac's
                sendFrame(ClientFrame.newBuilder().setRemoveMember(RemoveMember.newBuilder().setDeviceKey(device.relayKey().bs())).build())
            }
        }
        forget()
    }

    /**
     * Back to unpaired: no peers, no relay target, nothing queued. Only says so once that's saved;
     * otherwise a restart would load the old pairing, so the user is told it didn't work (SR-7-01).
     */
    private fun forget() {
        stable?.cancel()
        reconnect?.cancel()
        generation++ // ignore anything the old socket still delivers
        link?.close()
        link = null
        loggedIn = false
        pairing = null
        outbox.clear()
        device.peers().forEach { device.removePeer(it.id) }
        device.setAppData(ByteArray(0))
        _state.value = if (runCatching { persist() }.isSuccess) SessionState.Unpaired else SessionState.Failed(FORGET_FAILED)
    }

    /**
     * "Codes don't match": stop pairing from the phone. Nothing new is trusted (the Mac stays
     * unconfirmed here until its PairAccept), and an existing Mac stays paired.
     */
    fun abortPairing() = scope.launch {
        if (pairing != null) return@launch fail(CODES_DONT_MATCH)
        // The Mac's accept won the race with the tap: the user said the codes differ, so undo it
        val at = pairedAt ?: return@launch
        if (now() - at > ABORT_GRACE_MS) return@launch
        pairedAt = null
        forgetMac().join()
        _notice.value = UNPAIRED_CODES_DONT_MATCH
    }

    /** Something escaped a handler: show it instead of crashing (wired to the scope's exception handler). */
    fun internalError() {
        scope.launch { fail("Something went wrong. Reopen Seam to reconnect.") }
    }

    override fun send(payload: Payload) {
        scope.launch {
            if (mac == null || pairing != null) return@launch
            // Only the latest Status and Clipboard matter; everything else is capped
            if (payload.bodyCase in LATEST_ONLY) outbox.removeAll { it.payload.bodyCase == payload.bodyCase }
            if (outbox.size >= MAX_OUTBOX) outbox.removeFirst()
            outbox.addLast(Queued(payload, now()))
            if (loggedIn) flush() else if (payload.bodyCase !in LAZY) reconsider() // a copy connects; a battery update waits
        }
    }

    private fun connect() {
        reconnect?.cancel()
        link?.close()
        link = null
        val t = target ?: return
        loggedIn = false
        val mine = ++generation
        val query = Base64.getUrlEncoder().withoutPadding().encodeToString(t.groupId)
        val listener = object : RelayListener {
            override fun onFrame(frame: ByteArray) {
                scope.launch { if (mine == generation) handle(frame) }
            }
            override fun onClosed(reason: String) {
                scope.launch { if (mine == generation) closed() }
            }
        }
        link = try {
            connector.connect("${t.url}/v1/connect?group=$query", listener)
        } catch (_: Exception) {
            return fail("That relay address doesn't work.")
        }
    }

    private fun handle(bytes: ByteArray) {
        val frame = runCatching { ServerFrame.parseFrom(bytes) }.getOrNull() ?: return
        when (frame.frameCase) {
            ServerFrame.FrameCase.CHALLENGE -> login(frame.challenge.nonce.toByteArray())
            ServerFrame.FrameCase.LOGIN_RESULT -> if (frame.loginResult.ok) loggedInOk() else relayRefused("bad_login")
            ServerFrame.FrameCase.BUNDLE -> bundleArrived(frame.bundle.bundle.toByteArray())
            ServerFrame.FrameCase.DELIVER -> deliver(frame.deliver)
            ServerFrame.FrameCase.ERROR -> relayRefused(frame.error.code)
            else -> Unit
        }
    }

    /** Pairing-time refusals end the pairing; a paired phone just reconnects with backoff. */
    private fun relayRefused(code: String) {
        if (pairing != null) return fail(explain(code))
        if (loggedIn && code !in FATAL_AFTER_LOGIN) return
        if (code in NOT_RECOGNISED && ++refusals >= MAX_REFUSALS) return fail(explain("bad_login"))
        generation++ // so this socket's own close callback doesn't reconnect a second time
        link?.close()
        closed()
    }

    private fun login(nonce: ByteArray) {
        val t = target ?: return
        val login = Login.newBuilder()
            .setGroupId(t.groupId.bs())
            .setDeviceKey(device.relayKey().bs())
            .setSignature(device.signRelayLogin(nonce, t.groupId).bs())
        pairing?.let { login.setJoinToken(it.token.bs()) }
        sendFrame(ClientFrame.newBuilder().setLogin(login).build())
    }

    private fun loggedInOk() {
        loggedIn = true
        touch()
        stable?.cancel()
        stable = scope.launch {
            delay(STABLE_MS)
            backoff.reset()
            refusals = 0
        }
        val p = pairing
        if (p != null) {
            sendFrame(ClientFrame.newBuilder().setGetBundle(GetBundle.newBuilder().setDeviceKey(p.relayKey.bs())).build())
        } else {
            becameConnected()
        }
    }

    private fun bundleArrived(bundle: ByteArray) {
        val p = pairing ?: return
        if (bundle.isEmpty()) return fail("Your Mac hasn't finished setting up. Try its pairing code again.")
        try {
            val start = device.pairWith(p.identityKey, p.relayKey, p.macName, bundle, p.token, myName, myModel)
            if (runCatching { persist() }.isFailure) return fail(SAVE_FAILED) // SR-2-64: nothing leaves unsaved
            sendEnvelope(start.envelope)
            _state.value = SessionState.Pairing(p.macName, device.safetyCode(start.peerId))
        } catch (_: Exception) {
            fail("That pairing code didn't work. Show a new one on your Mac and scan again.")
        }
    }

    private fun deliver(env: Envelope) {
        touch()
        val opened = try {
            device.open(env.toByteArray())
        } catch (_: Exception) {
            // Already handled, or can never be opened (SR-2-62: whatever the error): holding it only redelivers it
            return ack(env)
        }
        val payload = runCatching { Payload.parseFrom(opened.payload) }.getOrNull()
        // A PairAccept switches Macs in memory first, so the one save below holds either the old
        // pairing or the new one, never both (SR-2-63)
        val accepted = payload != null && acceptPairing(opened.peerId, payload)
        val saved = runCatching { persist() }.isSuccess // durable before acking: the relay deletes what we ack
        if (accepted) {
            if (!saved) return fail(SAVE_FAILED)
            becameConnected()
            return ack(env)
        }
        // Handled even if the save failed: the ratchet has moved, so a redelivery in this run would
        // open as a duplicate and be lost (SR-2-59). Not acked then, so after a restart the saved
        // state opens it again.
        if (payload != null) {
            try {
                handleOpened(opened.peerId, payload)
            } catch (_: Exception) {
                // a feature bug: dropped, never a crash
            }
        }
        if (!saved) return fail(SAVE_FAILED)
        ack(env)
    }

    private fun ack(env: Envelope) {
        sendFrame(ClientFrame.newBuilder().setDelivered(Delivered.newBuilder().setMsgId(env.msgId)).build())
    }

    private fun handleOpened(peerId: String, payload: Payload) {
        if (pairing != null) return // nothing but the PairAccept counts until the pairing finishes
        if (peerId != mac?.id) return
        // The Mac forgot this phone: forget it too, and stop
        if (payload.bodyCase == Payload.BodyCase.CONTROL && payload.control.bodyCase == Control.BodyCase.UNPAIR) return forget()
        features.filter { payload.bodyCase in it.handles }.forEach { it.receive(payload, peerId) }
    }

    /** True if this was the pairing Mac's PairAccept: the old Mac is dropped and the new relay target set. */
    private fun acceptPairing(peerId: String, payload: Payload): Boolean {
        val p = pairing ?: return false
        val accepted = payload.bodyCase == Payload.BodyCase.CONTROL && payload.control.bodyCase == Control.BodyCase.PAIR_ACCEPT
        val fromPairingMac = device.peers().firstOrNull { it.id == peerId }?.relayKey?.contentEquals(p.relayKey) == true
        if (!accepted || !fromPairingMac) return false
        device.peers().filter { it.id != peerId }.forEach { device.removePeer(it.id) } // the old Mac, now replaced
        device.setAppData(RelayTarget(p.relayUrl, p.groupId).encode())
        pairing = null
        pairedAt = now()
        return true
    }

    private fun becameConnected() {
        val m = mac ?: return
        _state.value = SessionState.Connected(m.name)
        features.forEach { runCatching { it.onConnected(this) } } // a feature bug never takes the connection down
        flush()
    }

    private fun flush() {
        val m = mac ?: return
        while (outbox.isNotEmpty()) {
            val env = try {
                val next = outbox.first()
                val ttl = ttlFor(next.payload)
                if (expired(next)) {
                    outbox.removeFirst()
                    continue
                }
                val e = device.seal(m.id, next.payload.toByteArray(), ttl)
                persist() // the ratchet moved: save before anything leaves, or a crash could reuse a key
                e
            } catch (_: SeamException) {
                outbox.removeFirst()
                continue
            } catch (_: Exception) {
                return fail(SAVE_FAILED)
            }
            outbox.removeFirst()
            if (!sendEnvelope(env)) return
            touch()
        }
    }

    private fun closed() {
        stable?.cancel()
        loggedIn = false
        link = null
        if (pairing != null) return fail("Lost the connection while pairing. Try again.")
        val m = mac ?: return
        // Dropped while nothing needs it: stay down instead of reconnecting (decision 0005)
        if (!wanted()) return run { _state.value = SessionState.Idle(m.name) }
        _state.value = SessionState.Connecting(m.name)
        reconnect = scope.launch {
            wait(backoff.next())
            connect()
        }
    }

    private fun fail(reason: String) {
        stable?.cancel()
        generation++ // ignore anything the old socket still delivers
        link?.close()
        link = null
        loggedIn = false
        outbox.clear()
        if (pairing != null) {
            pairing = null
            device.peers().filter { !it.confirmed }.forEach { device.removePeer(it.id) }
            runCatching { persist() }
            if (mac != null) { // the existing pairing is untouched: carry on with it
                _notice.value = reason
                _state.value = SessionState.Connecting(mac!!.name)
                return connect()
            }
        }
        _state.value = SessionState.Failed(reason)
    }

    private fun sendEnvelope(env: ByteArray) =
        sendFrame(ClientFrame.newBuilder().setSend(Envelope.parseFrom(env)).build())

    private fun sendFrame(frame: ClientFrame) = link?.send(frame.toByteArray()) == true

    private fun persist() {
        check(!storageBlocked) { "storage couldn't be opened" }
        store.save(device.exportState(store.storageKey()))
    }

    // Fixed texts only: the relay's own strings are never shown
    private fun explain(code: String) = when (code) {
        "bad_token" -> "That pairing code has expired or was already used. Show a new one on your Mac."
        "bad_login" -> "The relay didn't recognise this phone. Pair again."
        "group_full" -> "Your Mac already has as many devices as it can pair."
        "rate_limited" -> "Too many attempts. Wait a minute and try again."
        else -> "The relay refused the connection."
    }

    private companion object {
        const val MAX_OUTBOX = 100
        const val SAVE_FAILED = "Couldn't save Seam's data on this phone. Free up some space and reopen Seam."
        const val RESET = "Seam's secure storage on this phone was reset. Pair again."
        /** How long after the Mac confirms a "Codes don't match" tap still undoes the pairing. */
        const val ABORT_GRACE_MS = 5_000L
        const val UNPAIRED_CODES_DONT_MATCH = "Unpaired: your Mac confirmed just as you said the codes didn't match. Pair again if it's your Mac."
        const val CODES_DONT_MATCH = "Nothing was paired, because the codes didn't match. If you're sure it's your Mac, show a new code on it and scan again."
        const val FORGET_FAILED = "Couldn't finish forgetting your Mac: Seam couldn't save on this phone. Free up some space, reopen Seam and try again."
        const val UNAVAILABLE = "Seam couldn't open its secure storage just now. Close Seam and open it again: your pairing is kept."
        // After login these mean the socket is unusable; others (queue_full, too_large…) are per-message
        val FATAL_AFTER_LOGIN = setOf("bad_login", "not_member", "storage")
        val NOT_RECOGNISED = setOf("bad_login", "not_member")
        val LATEST_ONLY = setOf(Payload.BodyCase.STATUS, Payload.BodyCase.CLIPBOARD)
        /** Sent on the next connection, never worth one of their own (a battery change). */
        val LAZY = setOf(Payload.BodyCase.STATUS)
        /** How long a connection stays after the last thing it carried, when nothing else wants it. */
        const val IDLE_MS = 60_000L

        /** How long the relay may hold it (docs/architecture/protocol.md): a stale clipboard is worse than none. */
        fun ttlFor(payload: Payload): UInt = when (payload.bodyCase) {
            Payload.BodyCase.CLIPBOARD -> 600u
            Payload.BodyCase.STATUS -> 120u
            else -> 0u // the core's default
        }
        const val MAX_REFUSALS = 5
        const val STABLE_MS = 30_000L
    }
}

private fun ByteArray.bs(): ByteString = ByteString.copyFrom(this)
