// SPDX-License-Identifier: AGPL-3.0-only
package com.avetero.seam.session

import com.avetero.seam.crypto.LanChannel
import com.avetero.seam.crypto.PeerInfo
import com.avetero.seam.crypto.SeamDevice
import com.avetero.seam.crypto.SeamException
import com.avetero.seam.protocol.v1.ClientFrame
import com.avetero.seam.protocol.v1.Control
import com.avetero.seam.protocol.v1.Delivered
import com.avetero.seam.protocol.v1.Envelope
import com.avetero.seam.protocol.v1.GetBundle
import com.avetero.seam.protocol.v1.Hello
import com.avetero.seam.protocol.v1.Login
import com.avetero.seam.protocol.v1.Payload
import com.avetero.seam.protocol.v1.RemoveMember
import com.avetero.seam.protocol.v1.Unpair
import com.avetero.seam.protocol.v1.ServerFrame
import com.avetero.seam.protocol.v1.Transport
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
 * The phone's link to its Mac: relay login, pairing, delivery, acks and reconnects, over the relay
 * and, on the same Wi‑Fi, directly (protocol/README.md). Everything runs on [scope], which must be
 * single-threaded, so state is only ever touched from one thread.
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
    /** The same-Wi‑Fi path; null: relay only. */
    private val lan: Lan? = null,
    private val settingsStore: SettingsStore = MemorySettingsStore(),
    /** Direct-link envelopes the Mac hasn't acked yet, kept across process deaths. */
    private val pendingStore: PendingStore = MemoryPendingStore(),
    private val appVersion: String = "",
) : SessionApi {
    private val _state = MutableStateFlow<SessionState>(SessionState.Unpaired)
    val state: StateFlow<SessionState> = _state

    /** Set from the switches (prefs) and the sealed app data once the device is open (init below). */
    private val _settings = MutableStateFlow(ConnectionSettings())
    /** The connection switches, and what the Mac allows. */
    val settings: StateFlow<ConnectionSettings> = _settings

    /** Which path messages to the Mac take right now; null while none is up. */
    private val _route = MutableStateFlow<Transport?>(null)
    val route: StateFlow<Transport?> = _route

    /** A one-off message for the user, e.g. why a new pairing failed while the old one carries on. */
    private val _notice = MutableStateFlow<String?>(null)
    val notice: StateFlow<String?> = _notice

    /** Set when saved state couldn't be opened for a reason that may pass: nothing is saved over it. */
    private var storageBlocked = false
    private var device: SeamDevice = openDevice()

    init {
        val prefs = settingsStore.load()
        var t = RelayTarget.decode(device.appData())
        // One-time move of the Mac-facing state out of the old preferences into the sealed app data
        val inPrefs = prefs.helloSeq != 0L || prefs.macHelloSeq != 0L || prefs.macAllows != null
        if (t != null && inPrefs && !storageBlocked) {
            // Whichever is newer wins, so a move that was cut short can never step a counter back
            val mac = java.lang.Long.compareUnsigned(prefs.macHelloSeq, t.macHelloSeq) > 0 || (t.macAllows == null && prefs.macAllows != null)
            t = t.copy(
                helloSeq = if (java.lang.Long.compareUnsigned(prefs.helloSeq, t.helloSeq) > 0) prefs.helloSeq else t.helloSeq,
                macHelloSeq = if (mac) prefs.macHelloSeq else t.macHelloSeq,
                macAllows = if (mac) prefs.macAllows else t.macAllows,
            )
            device.setAppData(t.encode())
            // The old keys go only once the move is saved; until then the next start tries again
            if (runCatching { persist() }.isSuccess) runCatching { settingsStore.save(prefs) }
        }
        _settings.value = ConnectionSettings(prefs.wifi, prefs.relay, t?.macAllows, t?.macHelloSeq ?: 0, t?.helloSeq ?: 0)
    }

    private val features = mutableListOf<Feature>()
    private val backoff = Backoff()
    /** Unsealed and bounded: sealed only when it can be sent, right after a durable save. */
    private val outbox = ArrayDeque<Queued>()

    /**
     * Sent over the direct link and not yet acked by the Mac (ServerFrame.delivered), oldest first.
     * Already sealed and saved: resent as they are, never re-sealed (that would move the ratchet).
     */
    private val lanUnacked = LinkedHashMap<ByteString, Envelope>()
    /** From a direct link that dropped before the Mac acked them: sent first on the next allowed path. */
    private val resend = ArrayDeque<Envelope>()

    init {
        // Android kills background processes routinely: what the Mac never acked goes again on the next path
        if (mac != null) resend.addAll(pendingStore.load().filter { !expired(it) }.takeLast(MAX_OUTBOX))
        if (!storageBlocked) savePending() // drops what expired, or what belongs to no Mac; never while state couldn't be opened
    }

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

    // The same-Wi‑Fi path: at most one connection attempt at a time, tried against each Seam service found
    private var lanLink: RelayLink? = null
    private var lanChannel: LanChannel? = null
    private var lanGeneration = 0
    private var lanAuthed = false
    private var lanDeadline: Job? = null
    private var lanRetry: Job? = null
    /** After an authenticated link drops: backs off, so a reset-every-second attacker can't spin us (cf. SR-8-01). */
    private val lanBackoff = Backoff()
    private var lanReconnect: Job? = null
    private var lanStable: Job? = null
    private var discovering = false
    private val lanFound = linkedSetOf<LanAddress>()
    /** Services that failed to authenticate since discovery started: someone else's Mac, most likely. */
    /** Until when each is skipped. Kept across discovery restarts (so a crowd of fakes can't hide the Mac), forgotten after [LAN_REFUSED_MS]. */
    private val lanRefused = mutableMapOf<LanAddress, Long>()
    /** Where our Mac last authenticated (memory only): never skipped for long. */
    private var lanLastGood: LanAddress? = null

    // Decision 0005: the connection is held only while it's cheap or needed
    private var screenOn = true
    private var charging = false
    /** The connection is wanted until then: [IDLE_MS] after the last thing it carried, longer for a reply window. */
    private var activeUntil = now() + IDLE_MS
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
        openLinks()
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
            resend.any { !expired(it) } ||
            now() < activeUntil

    /** Past its TTL: dropped at the next flush, and no reason to keep trying to connect (SR-8-01). */
    private fun expired(q: Queued): Boolean {
        val ttl = ttlFor(q.payload)
        return ttl != 0u && now() - q.at > ttl.toLong() * 1000
    }

    private fun touch(windowMs: Long = IDLE_MS) {
        activeUntil = maxOf(activeUntil, now() + windowMs)
        scheduleIdleCheck()
    }

    private fun scheduleIdleCheck() {
        idle?.cancel()
        idle = scope.launch {
            delay((activeUntil - now()).coerceAtLeast(0))
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
                "activeFor=${activeUntil - now()}ms link=${link != null} lan=${lanLink != null}/$lanAuthed pairing=${pairing != null} " +
                "state=${_state.value::class.simpleName}",
        )
        if (pairing != null || storageBlocked || _state.value is SessionState.Failed) return
        if (wanted()) {
            if (connect) {
                if (!loggedIn && !lanAuthed && link == null && reconnect?.isActive != true) _state.value = SessionState.Connecting(m.name)
                openLinks()
            }
            // Look again when the activity window ends, if it hasn't. A queued copy that can't go yet is
            // retried by the reconnect backoff, never by a timer: rescheduling for 0 ms spins (SR-8-01)
            if (!screenOn && !charging && activeUntil > now()) scheduleIdleCheck()
        } else if (link != null || reconnect?.isActive == true || lanLink != null || discovering || lanRetry?.isActive == true || lanReconnect?.isActive == true) {
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
        closeLan()
        _state.value = SessionState.Idle(m.name)
    }

    /** Paired with a Mac (saved state), whatever the connection is doing. */
    val paired: Boolean get() = mac != null

    /** The code both screens showed when pairing, for re-checking in Settings. */
    fun macCode(): String? = mac?.let { runCatching { device.safetyCode(it.id) }.getOrNull() }

    /** Pair with the Mac from its scanned (and user-confirmed) QR code. The current Mac stays until the new one accepts. */
    fun pair(qr: PairingQr) = scope.launch {
        if (storageBlocked) return@launch // pairing would replace the state we couldn't open
        if (!_settings.value.relay) return@launch run { _notice.value = RELAY_NEEDED_TO_PAIR } // pairing over Wi‑Fi comes later
        closeLan() // a direct link to the current Mac would only get in the way
        outbox.clear() // what the current Mac never acked is kept: it still gets it if this pairing fails
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
        // A farewell, like a Hello: any open path this phone's own switches allow, never one they have off
        val viaRelay = loggedIn && _settings.value.relay
        val viaLan = lanAuthed && _settings.value.wifi
        if ((viaRelay || viaLan) && !storageBlocked) {
            val unpair = Payload.newBuilder().setControl(Control.newBuilder().setUnpair(Unpair.getDefaultInstance())).build()
            runCatching {
                val frame = ClientFrame.newBuilder().setSend(Envelope.parseFrom(device.seal(m.id, unpair.toByteArray(), 0u))).build()
                persist()
                // Both: the relay queues it durably for the Mac, the direct link is instant; the Mac drops the duplicate
                if (viaRelay) sendFrame(frame)
                if (viaLan) sendLan(frame)
                // Leaving removes only this phone's own queue: the Unpair waits in the Mac's
                if (viaRelay) sendFrame(ClientFrame.newBuilder().setRemoveMember(RemoveMember.newBuilder().setDeviceKey(device.relayKey().bs())).build())
            }
        }
        forget() // closes both links gracefully: what was just sent is written first
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
        closeLan()
        pairing = null
        clearQueues()
        _settings.value = _settings.value.copy(macAllows = null, macHelloSeq = 0, helloSeq = 0) // app data is cleared below
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
            if (canSend()) flush() else if (payload.bodyCase !in LAZY) reconsider() // a copy connects; a battery update waits
        }
    }

    private fun connect() {
        reconnect?.cancel()
        link?.close()
        link = null
        val t = target ?: return
        loggedIn = false
        if (!_settings.value.relay) return // our own switch only: the Mac's next Hello may arrive this way
        val mine = ++generation
        val query = Base64.getUrlEncoder().withoutPadding().encodeToString(t.groupId)
        val listener = object : RelayListener {
            override fun onFrame(frame: ByteArray) {
                scope.launch { if (mine == generation) handle(frame) }
            }
            override fun onClosed(reason: String) {
                scope.launch {
                    trace?.invoke("relay closed: $reason")
                    if (mine == generation) closed()
                }
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
        trace?.invoke("relay frame: ${frame.frameCase}${if (frame.hasError()) " " + frame.error.code else ""}")
        when (frame.frameCase) {
            ServerFrame.FrameCase.CHALLENGE -> login(frame.challenge.nonce.toByteArray())
            ServerFrame.FrameCase.LOGIN_RESULT -> if (frame.loginResult.ok) loggedInOk() else relayRefused("bad_login")
            ServerFrame.FrameCase.BUNDLE -> bundleArrived(frame.bundle.bundle.toByteArray())
            ServerFrame.FrameCase.DELIVER -> deliver(frame.deliver, Transport.TRANSPORT_RELAY)
            ServerFrame.FrameCase.DELIVERED -> Unit // only the Mac sends it, and only on the direct link
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

    /** [via] is the path it came on: it's acked on that one. */
    private fun deliver(env: Envelope, via: Transport) {
        touch()
        val opened = try {
            device.open(env.toByteArray())
        } catch (_: Exception) {
            // Already handled, or can never be opened (SR-2-62: whatever the error): holding it only redelivers it
            return ack(env, via)
        }
        val payload = runCatching { Payload.parseFrom(opened.payload) }.getOrNull()
        // A PairAccept switches Macs in memory first, so the one save below holds either the old
        // pairing or the new one, never both (SR-2-63)
        val accepted = payload != null && acceptPairing(opened.peerId, payload)
        // The Mac's Hello is applied before the save, so its counter and paths are durable before the ack
        val hello = payload != null && !accepted && payload.isHello() && pairing == null && opened.peerId == mac?.id
        if (hello) macHello(payload!!.control.hello)
        val saved = runCatching { persist() }.isSuccess // durable before acking: the relay deletes what we ack
        if (accepted) {
            if (!saved) return fail(SAVE_FAILED)
            becameConnected()
            return ack(env, via)
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
        ack(env, via)
    }

    private fun ack(env: Envelope, via: Transport) {
        val frame = ClientFrame.newBuilder().setDelivered(Delivered.newBuilder().setMsgId(env.msgId)).build()
        if (via == Transport.TRANSPORT_LAN) sendLan(frame) else sendFrame(frame)
    }

    private fun handleOpened(peerId: String, payload: Payload) {
        if (pairing != null) return // nothing but the PairAccept counts until the pairing finishes
        if (peerId != mac?.id) return
        // The Mac forgot this phone: forget it too, and stop
        if (payload.bodyCase == Payload.BodyCase.CONTROL && payload.control.bodyCase == Control.BodyCase.UNPAIR) return forget()
        if (payload.isHello()) return // applied in deliver(), before the save
        features.filter { payload.bodyCase in it.handles }.forEach { it.receive(payload, peerId) }
    }

    /** True if this was the pairing Mac's PairAccept: the old Mac is dropped and the new relay target set. */
    private fun acceptPairing(peerId: String, payload: Payload): Boolean {
        val p = pairing ?: return false
        val accepted = payload.bodyCase == Payload.BodyCase.CONTROL && payload.control.bodyCase == Control.BodyCase.PAIR_ACCEPT
        val fromPairingMac = device.peers().firstOrNull { it.id == peerId }?.relayKey?.contentEquals(p.relayKey) == true
        if (!accepted || !fromPairingMac) return false
        device.peers().filter { it.id != peerId }.forEach { device.removePeer(it.id) } // the old Mac, now replaced
        clearQueues() // anything unacked was for the old Mac
        device.setAppData(RelayTarget(p.relayUrl, p.groupId).encode())
        // A new Mac: the fresh app data above has nothing heard from it, and no Hello sent to it
        _settings.value = _settings.value.copy(macAllows = null, macHelloSeq = 0, helloSeq = 0)
        pairing = null
        pairedAt = now()
        return true
    }

    /** A path to the Mac came up. Features hear about it only when the first one does. */
    private fun becameConnected() {
        val m = mac ?: return
        val first = _state.value !is SessionState.Connected
        _state.value = SessionState.Connected(m.name)
        updateRoute()
        queueHello(myTransports(_settings.value))
        if (first) features.forEach { runCatching { it.onConnected(this) } } // a feature bug never takes the connection down
        flush()
        if (_settings.value.wifi) startLan() // e.g. right after pairing, or the relay came up first
    }

    private fun flush() {
        val m = mac ?: return
        // No path both ends allow: only a Hello may go (over our own paths), so the Mac can learn ours
        val shared = canSend()
        if (!shared) {
            val hello = outbox.firstOrNull { it.payload.isHello() } ?: return
            if (!canSendHello()) return
            outbox.remove(hello)
            outbox.addFirst(hello)
        }
        // What a dropped direct link never confirmed goes first, as it was sealed
        if (shared && resend.isNotEmpty()) {
            while (resend.isNotEmpty()) {
                val e = resend.first()
                if (expired(e)) {
                    resend.removeFirst()
                    continue
                }
                if (!sendSealed(e)) break
                resend.removeFirst()
            }
            savePending() // through the relay (which queues them) or recorded again for this link
            if (resend.isNotEmpty()) return
        }
        while (outbox.isNotEmpty()) {
            if (!shared && !outbox.first().payload.isHello()) return
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
            } catch (e: SeamException) {
                trace?.invoke("seal failed, dropped: ${outbox.first().payload.bodyCase} $e")
                outbox.removeFirst()
                continue
            } catch (_: Exception) {
                return fail(SAVE_FAILED)
            }
            val sent = outbox.removeFirst()
            trace?.invoke("flush: ${sent.payload.bodyCase} ${sent.payload.serializedSize} bytes")
            if (!sendEnvelope(env, hello = sent.payload.isHello())) return
            touch(windowFor(sent.payload))
        }
    }

    private fun closed() {
        stable?.cancel()
        loggedIn = false
        link = null
        if (pairing != null) return fail("Lost the connection while pairing. Try again.")
        if (mac == null) return
        pathDown()
        // Dropped while nothing needs it: stay down instead of reconnecting (decision 0005)
        if (!wanted() || !_settings.value.relay) return
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
        closeLan()
        outbox.clear()
        if (pairing != null) {
            pairing = null
            device.peers().filter { !it.confirmed }.forEach { device.removePeer(it.id) }
            runCatching { persist() }
            if (mac != null) { // the existing pairing is untouched: carry on with it
                _notice.value = reason
                _state.value = SessionState.Connecting(mac!!.name)
                return openLinks() // its unacked envelopes are kept and go on the next path
            }
        }
        clearQueues()
        _state.value = SessionState.Failed(reason)
    }

    /** Over the direct link when it's up and allowed at both ends, else the relay; while pairing, only the relay. */
    private fun sendEnvelope(env: ByteArray, hello: Boolean = false): Boolean = sendSealed(Envelope.parseFrom(env), hello)

    /**
     * A [hello] may use any open path this phone allows, so a change of switches always reaches the
     * Mac (protocol/README.md, "Which paths may be used"); everything else needs both ends.
     */
    private fun sendSealed(e: Envelope, hello: Boolean = false): Boolean {
        val frame = ClientFrame.newBuilder().setSend(e).build()
        val lanOk = if (hello) _settings.value.wifi else allows(Transport.TRANSPORT_LAN)
        val relayOk = if (hello) _settings.value.relay else relayAllowed()
        val viaLan = pairing == null && lanAuthed && lanOk
        trace?.invoke("send: ${e.serializedSize} bytes via ${if (viaLan) "lan" else "relay"}")
        if (!viaLan) return relayOk && sendFrame(frame) // the relay queues it: nothing to keep
        lanUnacked[e.msgId] = e
        // Same bound as the outbox: the oldest unconfirmed one goes first
        while (lanUnacked.size + resend.size > MAX_OUTBOX) {
            if (resend.isNotEmpty()) resend.removeFirst() else lanUnacked.remove(lanUnacked.keys.first())
        }
        // Recorded before it's handed to the link. If the disk refuses, it's still kept in memory and
        // sent: only a process death in that state would lose it
        if (!savePending()) trace?.invoke("pending: save failed")
        return sendLan(frame) // a failed write stays recorded, and goes again when the link drops
    }

    /** What the Mac hasn't acked, oldest first, to the pending file. False if it couldn't be saved. */
    private fun savePending(): Boolean =
        runCatching { pendingStore.save((resend + lanUnacked.values).distinctBy { it.msgId }) }.isSuccess

    /** By the envelope's own sent_at and ttl, as the relay would drop it. */
    private fun expired(e: Envelope) = now() - e.sentAt > e.ttlS.toLong() * 1000

    private fun clearQueues() {
        outbox.clear()
        resend.clear()
        lanUnacked.clear()
        savePending()
    }

    /** To the relay only. */
    private fun sendFrame(frame: ClientFrame) = link?.send(frame.toByteArray()) == true

    private fun sendLan(frame: ClientFrame): Boolean {
        val ch = lanChannel ?: return false
        if (!lanAuthed) return false
        val bytes = try {
            ch.send(frame.toByteArray())
        } catch (_: Exception) {
            return false
        }
        return lanLink?.send(bytes) == true
    }

    /** Whether a message to the Mac can go out now, on some path allowed at both ends. */
    private fun canSend(): Boolean =
        pairing == null && ((lanAuthed && allows(Transport.TRANSPORT_LAN)) || (loggedIn && relayAllowed()))

    /** Whether a Hello can go now: any open path this phone's own switches allow. */
    private fun canSendHello(): Boolean =
        pairing == null && ((lanAuthed && _settings.value.wifi) || (loggedIn && _settings.value.relay))

    /** Allowed by this phone's switch and by the Mac's last Hello (a Mac that never sent one allows everything). */
    private fun allows(t: Transport): Boolean {
        val s = _settings.value
        val mine = if (t == Transport.TRANSPORT_LAN) s.wifi else s.relay
        return mine && (s.macAllows?.contains(t) ?: true)
    }

    /** While pairing, the new Mac's wishes aren't known: only this phone's switch counts. */
    private fun relayAllowed() = if (pairing != null) _settings.value.relay else allows(Transport.TRANSPORT_RELAY)

    private fun updateRoute() {
        _route.value = when {
            pairing != null -> null
            lanAuthed && allows(Transport.TRANSPORT_LAN) -> Transport.TRANSPORT_LAN
            loggedIn && relayAllowed() -> Transport.TRANSPORT_RELAY
            else -> null
        }
    }

    /** After a path went down: still Connected while another is up, otherwise trying again or idle. */
    private fun pathDown() {
        val m = mac ?: return
        updateRoute()
        if (pairing != null || _state.value is SessionState.Failed || _state.value is SessionState.Idle) return
        if (_route.value != null) return
        _state.value = if (wanted()) SessionState.Connecting(m.name) else SessionState.Idle(m.name)
    }

    /**
     * Opens whichever paths this phone's own switches allow and aren't open yet. Never the Mac's
     * remembered list: a stale one would leave no path for the Hello that updates it.
     */
    private fun openLinks() {
        if (_settings.value.relay && link == null && reconnect?.isActive != true) connect()
        if (pairing == null && _settings.value.wifi) startLan()
    }

    // MARK: switches and Hello

    /** The user's connection switches. At least one stays on; the Mac is told before anything closes. */
    fun setSwitches(wifi: Boolean, relay: Boolean) = scope.launch {
        if (!wifi && !relay) return@launch
        val old = _settings.value
        if (old.wifi == wifi && old.relay == relay) return@launch
        val new = old.copy(wifi = wifi, relay = relay)
        if (mac != null && pairing == null) {
            queueHello(myTransports(new))
            if (canSend() || canSendHello()) flush() // over the old paths, so the Mac stops using one we just turned off
        }
        saveSettings(_settings.value.copy(wifi = wifi, relay = relay)) // not `new`: queueHello moved the counter on
        applySettings()
    }

    private fun macHello(h: Hello) {
        // The relay can hold an old Hello and release it later: only a higher counter counts (0: none, ignored)
        if (h.seq == 0L || java.lang.Long.compareUnsigned(h.seq, _settings.value.macHelloSeq) <= 0) {
            trace?.invoke("hello: not newer than the last one, ignored")
            return
        }
        // Only transports this build knows: a newer Mac's (UNRECOGNIZED) can't be stored or allowed
        val allows = h.allowedTransportsList.filter { it != Transport.TRANSPORT_UNSPECIFIED && it != Transport.UNRECOGNIZED }.toSet()
        updatePeer { it.copy(macAllows = allows, macHelloSeq = h.seq) }
        scope.launch { applySettings() } // after this Hello is acked on the path it came on
    }

    /**
     * Closes paths this phone's own switches turned off, opens ones they allow if wanted, and sends
     * what can go now. The Mac's list only decides what's sent where, never what's open.
     */
    private fun applySettings() {
        if (pairing != null || mac == null) return
        if (!_settings.value.relay && (link != null || reconnect?.isActive == true)) {
            trace?.invoke("relay switched off: closing")
            stable?.cancel()
            reconnect?.cancel()
            generation++
            link?.close()
            link = null
            loggedIn = false
        }
        if (!_settings.value.wifi) closeLan()
        pathDown()
        reconsider(connect = true)
        if (canSend() || canSendHello()) flush()
    }

    /** The switches: user preferences, saved apart from the keys. */
    private fun saveSettings(s: ConnectionSettings) {
        _settings.value = s
        runCatching { settingsStore.save(s) } // only preferences: the switches still apply this run
    }

    /**
     * The Mac-facing state lives in the sealed app data, next to the relay target, so the next
     * persist() saves it together with the keys (never through preferences).
     */
    private fun updatePeer(change: (RelayTarget) -> RelayTarget): Boolean {
        val t = RelayTarget.decode(device.appData()) ?: return false
        val n = change(t)
        device.setAppData(n.encode())
        _settings.value = _settings.value.copy(macAllows = n.macAllows, macHelloSeq = n.macHelloSeq, helloSeq = n.helloSeq)
        return true
    }

    private fun myTransports(s: ConnectionSettings) = buildList {
        if (s.wifi) add(Transport.TRANSPORT_LAN)
        if (s.relay) add(Transport.TRANSPORT_RELAY)
    }

    /** Only the latest Hello matters: it replaces any still queued. */
    private fun queueHello(allowed: List<Transport>) {
        outbox.removeAll { it.payload.isHello() }
        if (outbox.size >= MAX_OUTBOX) outbox.removeFirst()
        // Bumped in the app data now; flush() seals it and persists both in one write before anything is sent
        if (!updatePeer { it.copy(helloSeq = it.helloSeq + 1) }) return
        val seq = _settings.value.helloSeq
        val hello = Hello.newBuilder().setAppVersion(appVersion).setPlatform("android").setSeq(seq).addAllAllowedTransports(allowed)
        outbox.addLast(Queued(Payload.newBuilder().setControl(Control.newBuilder().setHello(hello)).build(), now()))
    }

    // MARK: same Wi‑Fi

    private fun startLan() {
        if (lan == null || lanLink != null || lanAuthed || mac == null || lanReconnect?.isActive == true) return
        if (discovering) return tryNextLan()
        discovering = true
        lanFound.clear()
        trace?.invoke("lan: discovering")
        lan.discover(
            found = { a ->
                scope.launch {
                    // An advert can name any address: only ever connect within the local network, and to a few
                    // Refused ones don't count toward the cap, so fakes can't crowd out the Mac
                    if (discovering && isLocalNetwork(a.host) && !refused(a) && lanFound.size < MAX_LAN_FOUND) {
                        lanFound += a
                        tryNextLan()
                    }
                }
            },
            lost = { a -> scope.launch { lanFound.remove(a) } },
        )
    }

    private fun tryNextLan() {
        if (lanLink != null || lanAuthed || pairing != null || !_settings.value.wifi || !wanted()) return
        val next = lanFound.firstOrNull()
        if (next == null) {
            if (lanRefused.isNotEmpty()) retryLanLater() // every Mac here refused us or was unreachable; more may be waiting past the cap
            return
        }
        connectLan(next)
    }

    private fun connectLan(a: LanAddress) {
        val m = mac ?: return
        val l = lan ?: return
        val ch = try {
            LanChannel.initiator(device, m.id)
        } catch (_: Exception) {
            return
        }
        val mine = ++lanGeneration
        trace?.invoke("lan: connecting")
        val listener = object : RelayListener {
            override fun onFrame(frame: ByteArray) {
                scope.launch { if (mine == lanGeneration) lanBytes(frame, a) }
            }
            override fun onClosed(reason: String) {
                scope.launch {
                    trace?.invoke("lan closed: $reason")
                    if (mine == lanGeneration) lanClosed(a)
                }
            }
        }
        lanChannel = ch
        lanLink = runCatching { l.connect(a, listener) }.getOrNull() ?: return run { lanChannel = null; refuse(a) }
        lanLink?.send(ch.start())
        lanDeadline?.cancel()
        lanDeadline = scope.launch {
            delay(LAN_AUTH_MS)
            if (mine == lanGeneration && !lanAuthed) {
                trace?.invoke("lan: no proof in time")
                lanClosed(a)
            }
        }
    }

    private fun lanBytes(bytes: ByteArray, a: LanAddress) {
        val ch = lanChannel ?: return
        val out = try {
            ch.receive(bytes)
        } catch (e: Exception) {
            trace?.invoke("lan: channel failed")
            // Only a valid proof from another key is someone else's Mac; damaged bytes may be a
            // forged packet on our own Mac's connection, so that's just the short retry
            return lanClosed(a, notOurMac = e is SeamException.UntrustedIdentity)
        }
        if (out.send.isNotEmpty()) lanLink?.send(out.send)
        if (out.authenticated != null) {
            if (out.authenticated != mac?.id) return lanClosed(a, notOurMac = true) // the core only accepts our Mac; never trust anything else
            lanAuthed = true
            lanLastGood = a
            lanDeadline?.cancel()
            stopDiscovery() // found it: no need to keep multicasting
            lanStable?.cancel()
            lanStable = scope.launch {
                delay(STABLE_MS)
                lanBackoff.reset() // only a link that stayed up earns a quick reconnect
            }
            trace?.invoke("lan: authenticated")
            touch()
            becameConnected()
        }
        for (f in out.frames) {
            if (lanChannel !== ch) return // closed while handling an earlier frame (an Unpair, a failed save)
            val frame = runCatching { ServerFrame.parseFrom(f) }.getOrNull() ?: continue
            when (frame.frameCase) {
                ServerFrame.FrameCase.DELIVER -> deliver(frame.deliver, Transport.TRANSPORT_LAN)
                // An unknown id: nothing to remove, nothing written
                ServerFrame.FrameCase.DELIVERED -> if (lanUnacked.remove(frame.delivered.msgId) != null) savePending()
                else -> Unit
            }
        }
    }

    /** The direct link ended: try the next service if it never authenticated, or look again if it did. */
    private fun lanClosed(a: LanAddress, notOurMac: Boolean = false) {
        val wasUp = lanAuthed
        dropLan()
        pathDown()
        if (canSend()) flush() // e.g. the relay is up: what the link never confirmed goes now
        if (wasUp) {
            lanReconnect?.cancel()
            lanReconnect = scope.launch {
                delay(lanBackoff.next())
                lanReconnect = null // this job is still active while it runs: let startLan through
                startLan()
            }
            return
        }
        // Only a failed proof earns the long skip: a timeout or a dropped socket may be our own Mac
        // (busy, or made to drop us by a flood), and skipping it for long would hide it
        // Never the long skip where our Mac last proved itself: NN is anonymous, so whoever answers
        // first (an attacker winning the race) can make any address look foreign
        refuse(a, if (notOurMac && a != lanLastGood) LAN_REFUSED_MS else LAN_RETRY_MS)
        tryNextLan()
    }

    /** Closes our side; its callbacks are ignored from here on. */
    private fun dropLan() {
        // Anything the Mac hadn't confirmed may be lost with the link: send it again on the next path
        resend.addAll(lanUnacked.values)
        lanUnacked.clear()
        lanGeneration++
        lanDeadline?.cancel()
        lanStable?.cancel()
        // Only a proven Mac gets a graceful close: anything else (a stranger, a failed proof) is cut at once
        if (lanAuthed) lanLink?.close() else lanLink?.abort()
        lanLink = null
        lanChannel = null
        lanAuthed = false
        updateRoute()
    }

    private fun stopDiscovery() {
        if (!discovering) return
        discovering = false
        lan?.stopDiscovery()
    }

    private fun closeLan() {
        dropLan()
        stopDiscovery()
        lanRetry?.cancel()
        lanReconnect?.cancel()
        lanFound.clear()
        lanRefused.clear()
    }

    private fun refuse(a: LanAddress, forMs: Long = LAN_RETRY_MS) {
        lanRefused[a] = now() + forMs
        lanFound.remove(a)
        lan?.forget(a)
    }

    private fun refused(a: LanAddress) = lanRefused[a]?.let { now() < it } == true

    /** Looks again from scratch: services past the cap are only reported to a fresh discovery. */
    private fun retryLanLater() {
        if (lanRetry?.isActive == true) return
        lanRetry = scope.launch {
            delay(LAN_RETRY_MS)
            lanRefused.entries.removeAll { now() >= it.value }
            stopDiscovery()
            startLan()
        }
    }

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
        val LATEST_ONLY = setOf(Payload.BodyCase.STATUS, Payload.BodyCase.CLIPBOARD, Payload.BodyCase.NOTIFICATION_SETTINGS)
        /** Sent on the next connection, never worth one of their own (a battery change). */
        val LAZY = setOf(Payload.BodyCase.STATUS)
        /** How long a connection stays after the last thing it carried, when nothing else wants it. */
        const val IDLE_MS = 60_000L
        /** After a notification you can reply to: long enough that a reply from the Mac needs no push. */
        const val REPLY_WINDOW_MS = 5 * 60_000L

        fun windowFor(payload: Payload): Long =
            if (payload.hasNotification() && payload.notification.canReply) REPLY_WINDOW_MS else IDLE_MS

        /** How long the relay may hold it (docs/architecture/protocol.md): a stale clipboard is worse than none. */
        fun ttlFor(payload: Payload): UInt = when (payload.bodyCase) {
            Payload.BodyCase.CLIPBOARD -> 600u
            Payload.BodyCase.STATUS -> 120u
            Payload.BodyCase.NOTIFICATION_SETTINGS -> 600u // sent again on every connection
            Payload.BodyCase.NOTIFICATION -> 3600u // a Mac that was away gets the last hour, not a flood
            Payload.BodyCase.CONTROL -> if (payload.control.hasHello()) 600u else 0u // sent again on every connection
            else -> 0u // the core's default
        }
        const val MAX_REFUSALS = 5
        /** The handshake and both proofs take milliseconds; anything slower isn't worth waiting for. */
        const val LAN_AUTH_MS = 10_000L
        /** How soon to try Macs that refused or were unreachable again (one may have restarted). */
        const val LAN_RETRY_MS = 30_000L
        /** More Seam services than this on one network is a flood, not a home. */
        const val MAX_LAN_FOUND = 8
        /** How long a service that failed its proof (someone else's Mac) is skipped: a Mac that restarted gets a new port anyway. */
        const val LAN_REFUSED_MS = 10 * 60_000L
        const val RELAY_NEEDED_TO_PAIR = "Pairing needs the relay for now. Turn on Relay in Settings › Connection, then scan again."
        const val STABLE_MS = 30_000L
    }
}

private fun ByteArray.bs(): ByteString = ByteString.copyFrom(this)

private fun Payload.isHello() = bodyCase == Payload.BodyCase.CONTROL && control.bodyCase == Control.BodyCase.HELLO
