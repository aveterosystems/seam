// SPDX-License-Identifier: AGPL-3.0-only
package com.avetero.seam.session

import com.avetero.seam.crypto.PeerInfo
import com.avetero.seam.crypto.PeerPath
import com.avetero.seam.crypto.SeamDevice
import com.avetero.seam.crypto.SeamException
import com.avetero.seam.protocol.v1.ClientFrame
import com.avetero.seam.protocol.v1.Control
import com.avetero.seam.protocol.v1.Delivered
import com.avetero.seam.protocol.v1.Envelope
import com.avetero.seam.protocol.v1.Hello
import com.avetero.seam.protocol.v1.Payload
import com.avetero.seam.protocol.v1.Unpair
import com.avetero.seam.protocol.v1.ServerFrame
import com.avetero.seam.protocol.v1.Transport
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

/** How the last pairing ended (SR-11-06: only a new Mac gets the setup screens). */
enum class PairingOutcome { NewMac, StillOnPreviousMac, NoMac }

/**
 * The phone's link to its Mac: pairing, delivery, acks and reconnects, over one channel carried on
 * Same Wi‑Fi or through Tor (docs/tor/13-implementation-contract.md). Everything runs on [scope],
 * which must be single-threaded, so state is only ever touched from one thread.
 */
class Session(
    private val store: StateStore,
    private val myName: String,
    private val myModel: String,
    private val scope: CoroutineScope,
    private val now: () -> Long = System::currentTimeMillis,
    /** Debug builds only: the connection manager's decisions, never content, keys or addresses. Null: not built at all. */
    private val trace: ((String) -> Unit)? = null,
    /** The Same Wi‑Fi dialer; null: none. */
    private val lan: Lan? = null,
    /** The Tor dialer; null: none. */
    private val tor: TorDialer? = null,
    private val settingsStore: SettingsStore = MemorySettingsStore(),
    /** Direct-link envelopes the Mac hasn't acked yet, kept across process deaths. */
    private val pendingStore: PendingStore = MemoryPendingStore(),
    private val appVersion: String = "",
) : SessionApi {
    private val _state = MutableStateFlow<SessionState>(SessionState.Unpaired)
    val state: StateFlow<SessionState> = _state

    /** The connection switches, and what the Mac allows. */
    val settings: StateFlow<ConnectionSettings> get() = switches.settings

    /** Which path messages to the Mac take right now; null while none is up. */
    private val _route = MutableStateFlow<Transport?>(null)
    val route: StateFlow<Transport?> = _route

    /** What the CPU must do for the idle check to run on time (rule 8); the app's service carries it out. */
    private val _cpu = MutableStateFlow<CpuPlan>(CpuPlan.None)
    val cpu: StateFlow<CpuPlan> = _cpu

    /** A one-off message for the user, e.g. why a new pairing failed while the old one carries on. */
    private val _notice = MutableStateFlow<String?>(null)
    val notice: StateFlow<String?> = _notice

    /** Null before any pairing ends in this run, and while one is in progress. */
    val pairingOutcome: StateFlow<PairingOutcome?> get() = pairing.outcome

    /** Set when saved state couldn't be opened for a reason that may pass: nothing is saved over it. */
    private var storageBlocked = false
    private val device: SeamDevice = openDevice()

    private val switches = Switches(device, settingsStore, appVersion).apply { load() }

    private val features = mutableListOf<Feature>()
    private val outbox = Outbox(pendingStore, now)

    init {
        if (mac != null) outbox.restore()
        if (!storageBlocked) outbox.save() // drops what expired, or what belongs to no Mac; never while state couldn't be opened
    }

    // Decision 0005: the connection is held only while it's cheap or needed
    private val wake = Wakefulness(scope, now, onChanged = { updateCpu() }) { reconsider(connect = false) }

    init {
        scope.launch { _state.collect { updateCpu() } }
    }

    private val pairing = Pairing(device, myName, myModel, now, persist = { persist() }, send = { sendEnvelope(it) }, fail = { fail(it) })

    private val events = object : PathEvents {
        override fun onUp(path: Path) = pathUp(path)
        override fun onDown(path: Path) = pathClosed(path)
        override fun onFrame(path: Path, frame: ServerFrame) = frame(path, frame)
        override fun onFailed(path: Path, reason: String) = fail(reason)
    }
    /** Same Wi‑Fi. Null: no dialer. */
    private val direct = lan?.let {
        LanPath(it, device, scope, now, trace, peer = { mac?.id }, wanted = { switches.wifi && pathWanted(PeerPath.LAN) }, events = events, pairing = { pairing.qr })
    }
    /** Through Tor, to the Mac's onion service. Null: no dialer. */
    private val onion = tor?.let {
        LanPath(
            it, device, scope, now, trace, peer = { mac?.id }, wanted = { switches.tor && pathWanted(PeerPath.TOR) }, events = events,
            pairing = { pairing.qr }, localOnly = false, authMs = LanPath.TOR_AUTH_MS, name = "tor",
        )
    }
    private val paths: List<Path> get() = listOfNotNull(direct, onion)

    /** While pairing, the path whose pairing channel authenticated first; the other stops trying. */
    private var pairingVia: Path? = null
    /** The pairing channel after our PairAccept ack: the Mac closes it once the ack is in (or the grace ends). */
    private var closingPairing: Path? = null
    private var pairingDeadline: Job? = null
    /** Paths that authenticated and haven't gone down since: only their drop can lose an unacked envelope. */
    private val upPaths = mutableSetOf<Path>()

    /** Both paths try at once while pairing, until one wins (contract: first channel to authenticate). */
    private fun pathWanted(p: PeerPath): Boolean {
        val via = pairingVia
        if (via != null && pathOf(via) != p) return false
        return wanted()
    }

    private fun pathOf(p: Path): PeerPath = if (p === direct) PeerPath.LAN else PeerPath.TOR

    private val mac: PeerInfo? get() = device.peers().firstOrNull { it.confirmed }

    init {
        // A relay-era pairing: its app data (a relay group) no longer reads, so there's no address to dial
        if (mac != null && !storageBlocked && device.appData()?.onionAddress.isNullOrEmpty()) {
            forget()
            _notice.value = PAIR_AGAIN
        }
        syncTor()
    }

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
        wake.setScreenOn(on)
        reconsider(connect = on)
    }

    /** Plugged in or unplugged. While charging the connection stays open: power doesn't matter. */
    fun setCharging(on: Boolean) = scope.launch {
        wake.setCharging(on)
        reconsider(connect = on)
    }

    /**
     * Whether a connection is worth having now: pairing, screen on, charging, something urgent to
     * send, or recent activity. Otherwise it costs radio wake-ups for nothing.
     */
    private fun wanted(): Boolean =
        // Paused: only what was already waiting (the pause notice with it) may open a connection, and
        // a direct link stays until the Mac confirms it, within the activity window: cut off
        // earlier, the notice would be sent again later, costing the connection a pause saves
        if (paused()) pairing.active || outbox.wantsConnection() || (outbox.awaitingAck() && wake.activeFor() > 0)
        else wake.wanted { pairing.active || outbox.wantsConnection() }

    /** Paused now: [pause] was called and its time hasn't come. A timed pause ends by itself. */
    private fun paused(): Boolean = switches.settings.value.pausedUntil > now()

    /**
     * Pauses Seam until [untilMs] (unix ms; Long.MAX_VALUE: until [resume]). Only the phone pauses.
     * The Mac is told in a Hello, with whatever was already waiting, in one connection; then this
     * phone makes no connections and captures nothing until the pause ends. A past time does nothing.
     */
    fun pause(untilMs: Long) = scope.launch {
        if (mac == null || pairing.active || untilMs <= now()) return@launch
        switches.savePause(untilMs)
        queueHello()
        reconsider()
        if (canSend() || canSendHello()) flush()
    }

    /** Ends a pause now and tells the Mac. One that already ended by itself needs no message. */
    fun resume() = scope.launch {
        val wasPaused = paused()
        switches.savePause(0)
        if (!wasPaused || mac == null) return@launch
        queueHello()
        reconsider()
        if (canSend() || canSendHello()) flush()
    }

    /**
     * Close the connection if nothing wants it any more; with [connect], also open it if something
     * does. A failed session is left as it is: the user sees why and acts.
     */
    private fun reconsider(connect: Boolean = true) {
        val m = mac ?: return
        trace?.invoke(
            "reconsider connect=$connect screen=${wake.screenOn} charging=${wake.charging} urgent=${outbox.urgentCount()} " +
                "activeFor=${wake.activeFor()}ms tor=${onion?.active}/${onion?.up} lan=${direct?.active}/${direct?.up} pairing=${pairing.active} " +
                "state=${_state.value::class.simpleName}",
        )
        if (pairing.active || storageBlocked || _state.value is SessionState.Failed) return
        if (wanted()) {
            if (connect) {
                if (paths.none { it.up }) _state.value = SessionState.Connecting(m.name)
                openLinks()
            }
            wake.keepWatching()
        } else if (paths.any { it.active }) {
            sleep(m)
        }
    }

    /**
     * The CPU woke for a [CpuPlan.WakeAt]: look again now. Timers that were due while it slept
     * haven't run, so this re-arms the idle check from the clock and updates [cpu].
     */
    fun checkIdle() = scope.launch {
        reconsider(connect = false)
        updateCpu()
    }

    private fun updateCpu() {
        val at = wake.idleCheckAt()
        val open = _state.value.let { it is SessionState.Connected || it is SessionState.Connecting }
        _cpu.value = when {
            at == null || !open -> CpuPlan.None
            at - now() <= Wakefulness.CPU_WAKE_BEFORE_MS -> CpuPlan.Hold(maxOf(at, now()) + Wakefulness.CPU_HOLD_MS - Wakefulness.CPU_WAKE_BEFORE_MS)
            else -> CpuPlan.WakeAt(at - Wakefulness.CPU_WAKE_BEFORE_MS)
        }
    }

    /** Closes the connection on purpose: no reconnect until something wants one. */
    private fun sleep(m: PeerInfo) {
        trace?.invoke("sleep: closing the idle connection")
        closePaths()
        _state.value = SessionState.Idle(m.name)
    }

    /** Paired with a Mac (saved state), whatever the connection is doing. */
    val paired: Boolean get() = mac != null

    /** The code both screens showed when pairing, for re-checking in Settings. */
    fun macCode(): String? = mac?.let { runCatching { device.safetyCode(it.id) }.getOrNull() }

    /** Pair with the Mac from its scanned (and user-confirmed) QR code. The current Mac stays until the new one accepts. */
    fun pair(qr: PairingQr) = scope.launch {
        if (storageBlocked) return@launch // pairing would replace the state we couldn't open
        closePaths() // links to the current Mac would only get in the way
        switches.savePause(0) // a new pairing starts unpaused (SR-32-02); a kept Mac hears it in its next Hello
        outbox.clearUnsent() // what the current Mac never acked is kept: it still gets it if this pairing fails
        pairing.start(qr)
        pairingVia = null
        syncTor()
        _notice.value = null
        _state.value = SessionState.Pairing(qr.macName, null)
        pairingDeadline?.cancel()
        pairingDeadline = scope.launch {
            delay(PAIRING_MS) // a fresh onion address can take minutes to publish; longer is a Mac that isn't there
            if (pairing.qr === qr && pairingVia == null) fail(MAC_NOT_FOUND)
        }
        openLinks() // this phone's own switches decide which carriers try
    }

    /**
     * "Forget this Mac": tells the Mac (sealed), and forgets it here. While offline only the last
     * step happens; the Mac can still unpair from its side.
     */
    fun forgetMac() = scope.launch {
        val m = mac ?: return@launch
        // A farewell, like a Hello: any open path this phone's own switches allow, never one they have off
        val via = listOfNotNull(direct?.takeIf { it.up && switches.wifi }, onion?.takeIf { it.up && switches.tor })
        val farewell = mutableListOf<Pair<Path, ClientFrame>>()
        if (via.isNotEmpty() && !storageBlocked) {
            val unpair = Payload.newBuilder().setControl(Control.newBuilder().setUnpair(Unpair.getDefaultInstance())).build()
            // Not saved before sending, unlike other messages: the Mac's keys are deleted in the same
            // save as the forget, so a crash can't reuse them
            runCatching {
                val frame = ClientFrame.newBuilder().setSend(Envelope.parseFrom(device.seal(m.id, unpair.toByteArray(), 0u))).build()
                // Every open path: one may be dropping as we leave; the Mac drops the duplicate
                via.forEach { farewell += it to frame }
            }
        }
        forget(farewell)
    }

    /**
     * Back to unpaired: no peers, no onion address, nothing queued. Only says so once that's saved;
     * otherwise a restart would load the old pairing, so the user is told it didn't work (SR-7-01).
     */
    private fun forget(farewell: List<Pair<Path, ClientFrame>> = emptyList()) {
        pairing.clear()
        outbox.clearAll()
        device.peers().forEach { device.removePeer(it.id) }
        device.clearAppData()
        switches.savePause(0) // a pause belongs to this pairing; a new Mac never inherits it (SR-32-02)
        features.forEach { runCatching { it.reset() } } // a feature bug never blocks forgetting
        switches.syncPeer()
        val saved = runCatching { persist() }.isSuccess
        // The Mac is told only once forgetting it is saved: never a farewell for something that didn't happen (SR-7-01, SR-29-12)
        if (saved) farewell.forEach { (path, frame) -> path.send(frame) }
        closePaths() // gracefully: the farewell is written first
        syncTor()
        _state.value = if (saved) SessionState.Unpaired else SessionState.Failed(FORGET_FAILED)
    }

    /**
     * "Codes don't match": stop pairing from the phone. Nothing new is trusted (the Mac stays
     * unconfirmed here until its PairAccept), and an existing Mac stays paired.
     */
    fun abortPairing() = scope.launch {
        if (pairing.active) return@launch fail(CODES_DONT_MATCH)
        // The Mac's accept won the race with the tap: the user said the codes differ, so undo it
        if (!pairing.undoable()) return@launch
        forgetMac().join()
        _notice.value = UNPAIRED_CODES_DONT_MATCH
    }

    /** Something escaped a handler: show it instead of crashing (wired to the scope's exception handler). */
    fun internalError() {
        scope.launch { fail("Something went wrong. Reopen Seam to reconnect.") }
    }

    override fun send(payload: Payload) {
        scope.launch {
            if (mac == null || pairing.active) return@launch
            if (paused() && payload.bodyCase != Payload.BodyCase.CONTROL) return@launch // nothing is captured while paused
            outbox.add(payload)
            if (canSend()) flush() else if (payload.bodyCase !in Outbox.LAZY) reconsider() // a copy connects; a battery update waits
        }
    }

    /** A path came up. While pairing, the first one asks for the new Mac's keys; otherwise the Mac is reachable. */
    private fun pathUp(path: Path) {
        wake.touch()
        val request = pairing.request()
        if (request == null) {
            upPaths += path
            return becameConnected()
        }
        if (pairingVia != null) return path.stop() // lost the race: one pairing channel is enough
        pairingVia = path
        paths.filter { it !== path }.forEach { it.stop() }
        trace?.invoke("pairing: channel up")
        path.send(request)
    }

    private fun frame(path: Path, frame: ServerFrame) {
        when (frame.frameCase) {
            ServerFrame.FrameCase.BUNDLE -> if (pairing.active && path === pairingVia) pairing.keysArrived(frame.bundle.bundle.toByteArray())?.let { _state.value = it }
            // While pairing, only the pairing channel speaks for the new Mac
            ServerFrame.FrameCase.DELIVER -> if (!pairing.active || path === pairingVia) deliver(frame.deliver, path)
            // The Mac's ack, on whichever path. An unknown id: nothing to remove, nothing written
            ServerFrame.FrameCase.DELIVERED -> if (!pairing.active) {
                outbox.acked(frame.delivered.msgId)
                if (paused()) reconsider(connect = false) // the last thing the Mac had to confirm
            }
            else -> Unit
        }
    }

    /** [via] is the path it came on: it's acked on that one. */
    private fun deliver(env: Envelope, via: Path) {
        wake.touch()
        val opened = try {
            device.open(env.toByteArray())
        } catch (_: Exception) {
            // Already handled, or can never be opened (SR-2-62: whatever the error): holding it only redelivers it
            return ack(env, via)
        }
        val payload = runCatching { Payload.parseFrom(opened.payload) }.getOrNull()
        // A PairAccept switches Macs in memory first, so the one save below holds either the old
        // pairing or the new one, never both (SR-2-63)
        val accepted = payload != null && pairing.accept(opened.peerId, payload)
        if (accepted) {
            outbox.clearAll() // anything unacked was for the old Mac
            switches.syncPeer()
        }
        // The Mac's Hello is applied before the save, so its counter and paths are durable before the ack
        val hello = payload != null && !accepted && payload.isHello() && !pairing.active && opened.peerId == mac?.id
        if (hello) macHello(payload!!.control.hello)
        val saved = runCatching { persist() }.isSuccess // durable before acking: the Mac forgets what we ack
        if (accepted) {
            if (!saved) return fail(SAVE_FAILED)
            pairing.accepted() // only once it's saved: a restart keeps it
            ack(env, via)
            // The Mac undoes the pairing unless this ack arrives: let the Mac close the channel once it
            // has it, rather than closing first and maybe cutting the ack off (a fallback if it doesn't)
            closingPairing = via
            pairingDeadline?.cancel()
            pairingDeadline = scope.launch {
                delay(PAIRED_GRACE_MS)
                if (closingPairing === via) paired()
            }
            return
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

    private fun ack(env: Envelope, via: Path) {
        via.send(ClientFrame.newBuilder().setDelivered(Delivered.newBuilder().setMsgId(env.msgId)).build())
    }

    private fun handleOpened(peerId: String, payload: Payload) {
        if (pairing.active) return // nothing but the PairAccept counts until the pairing finishes
        if (peerId != mac?.id) return
        // The Mac forgot this phone: forget it too, and stop
        if (payload.bodyCase == Payload.BodyCase.CONTROL && payload.control.bodyCase == Control.BodyCase.UNPAIR) return forget()
        if (payload.isHello()) return // applied in deliver(), before the save
        features.filter { payload.bodyCase in it.handles }.forEach { it.receive(payload, peerId) }
    }

    /** A path to the Mac came up. Features hear about it only when the first one does. */
    private fun becameConnected() {
        val m = mac ?: return
        val first = _state.value !is SessionState.Connected
        _state.value = SessionState.Connected(m.name)
        updateRoute()
        queueHello()
        if (first) features.forEach { runCatching { it.onConnected(this) } } // a feature bug never takes the connection down
        flush()
        openLinks() // e.g. Tor came up first: Same Wi‑Fi, if it's here, is preferred
    }

    /** The PairAccept is saved and acked: the pairing channel closes, and the Mac is dialled as usual. */
    private fun paired() {
        val m = mac ?: return
        pairingDeadline?.cancel()
        pairingVia = null
        closingPairing = null
        closePaths()
        syncTor()
        _state.value = SessionState.Connecting(m.name)
        openLinks()
    }

    private fun flush() {
        flushQueued()
        if (paused()) reconsider(connect = false) // the pause notice is out: close
    }

    private fun flushQueued() {
        val m = mac ?: return
        // No path both ends allow: only a Hello may go (over our own paths), so the Mac can learn ours
        val shared = canSend()
        if (!shared && (!canSendHello() || !outbox.helloFirst())) return
        // What a dropped direct link never confirmed goes first, as it was sealed
        if (shared && !outbox.resendAll { sendSealed(it) }) return
        while (true) {
            val next = outbox.next(helloOnly = !shared) ?: return
            val env = try {
                val e = device.seal(m.id, next.toByteArray(), Outbox.ttlFor(next))
                persist() // the ratchet moved: save before anything leaves, or a crash could reuse a key
                e
            } catch (e: SeamException) {
                trace?.invoke("seal failed, dropped: ${next.bodyCase} $e")
                outbox.removeNext()
                continue
            } catch (_: Exception) {
                return fail(SAVE_FAILED)
            }
            outbox.removeNext()
            trace?.invoke("flush: ${next.bodyCase} ${next.serializedSize} bytes")
            if (!sendEnvelope(env, hello = next.isHello())) return
            wake.touchAfter(next)
        }
    }

    /** A path went down (or an attempt failed): what it never confirmed goes on the next path. */
    private fun pathClosed(path: Path) {
        val wasUp = upPaths.remove(path)
        if (path === closingPairing) return paired() // the Mac has our ack and closed the pairing channel
        if (pairing.active) {
            if (path === pairingVia) fail("Lost the connection while pairing. Try again.")
            return
        }
        if (wasUp) outbox.linkDropped()
        pathDown()
        if (wasUp && canSend()) flush() // e.g. the other path is up
    }

    private fun fail(reason: String) {
        closePaths() // ignore anything the old links still deliver
        outbox.clearUnsent()
        pairingDeadline?.cancel()
        pairingVia = null
        val abandoned = pairing.abandon()
        syncTor()
        if (abandoned) {
            val m = mac
            if (m != null) { // the existing pairing is untouched: carry on with it
                _notice.value = reason
                _state.value = SessionState.Connecting(m.name)
                return openLinks() // its unacked envelopes are kept and go on the next path
            }
        }
        outbox.clearAll()
        _state.value = SessionState.Failed(reason)
    }

    /** Over Same Wi‑Fi when it's up and allowed at both ends, else Tor; while pairing, only the pairing channel. */
    private fun sendEnvelope(env: ByteArray, hello: Boolean = false): Boolean = sendSealed(Envelope.parseFrom(env), hello)

    /**
     * A [hello] may use any open path this phone allows, so a change of switches always reaches the
     * Mac (protocol/README.md, "Which paths may be used"); everything else needs both ends.
     */
    private fun sendSealed(e: Envelope, hello: Boolean = false): Boolean {
        val frame = ClientFrame.newBuilder().setSend(e).build()
        // While pairing only the pairing channel counts: the new Mac's wishes aren't known
        if (pairing.active) return pairingVia?.takeIf { it.up }?.send(frame) == true
        val via = route(hello)
        trace?.invoke("send: ${e.serializedSize} bytes via ${if (via === direct) "lan" else "tor"}")
        if (via == null) return false
        // Recorded before it's handed to the link
        if (!outbox.sentDirect(e)) trace?.invoke("pending: save failed")
        return via.send(frame) // a failed write stays recorded, and goes again when the link drops
    }

    /**
     * The path to the Mac now, by the core's rules (decision 0006): one up and allowed at both ends,
     * or for a [hello] any up path this phone's own switches allow. Null while pairing.
     */
    private fun route(hello: Boolean = false): Path? {
        if (pairing.active || closingPairing != null) return null // nothing rides on a pairing channel
        val up = listOfNotNull(PeerPath.LAN.takeIf { direct?.up == true }, PeerPath.TOR.takeIf { onion?.up == true })
        return when (device.route(switches.enabled(), up, hello)) {
            PeerPath.LAN -> direct
            PeerPath.TOR -> onion
            null -> null
        }
    }

    /** Whether a message to the Mac can go out now, on some path allowed at both ends. */
    private fun canSend(): Boolean = route() != null

    /** Whether a Hello can go now: any open path this phone's own switches allow. */
    private fun canSendHello(): Boolean = route(hello = true) != null

    private fun updateRoute() {
        _route.value = when (route()) {
            null -> null
            direct -> Transport.TRANSPORT_LAN
            else -> Transport.TRANSPORT_TOR
        }
    }

    /** After a path went down: still Connected while another is up, otherwise trying again or idle. */
    private fun pathDown() {
        val m = mac ?: return
        updateRoute()
        if (pairing.active || _state.value is SessionState.Failed || _state.value is SessionState.Idle) return
        if (_route.value != null) return
        _state.value = if (wanted()) SessionState.Connecting(m.name) else SessionState.Idle(m.name)
    }

    /**
     * Opens whichever paths this phone's own switches allow and aren't open yet. Never the Mac's
     * remembered list: a stale one would leave no path for the Hello that updates it.
     */
    private fun openLinks() {
        if (switches.wifi) direct?.start()
        if (switches.tor) onion?.start()
    }

    /** Tells the Tor dialer where to go: the Mac on the code while pairing, else the paired Mac. */
    private fun syncTor() {
        val t = tor ?: return
        val qr = pairing.qr
        val d = if (mac != null) device.appData() else null
        t.target = when {
            qr != null -> TorTarget(qr.onionAddress, qr.discoverySecret)
            d != null && d.onionAddress.isNotEmpty() -> TorTarget(d.onionAddress, d.torSecret)
            else -> null
        }
    }

    // MARK: switches and Hello

    /** The user's connection switches. At least one stays on; the Mac is told before anything closes. */
    fun setSwitches(wifi: Boolean, tor: Boolean) = scope.launch {
        if (!wifi && !tor) return@launch
        if (switches.wifi == wifi && switches.tor == tor) return@launch
        if (mac != null && !pairing.active) {
            queueHello(wifi, tor)
            if (canSend() || canSendHello()) flush() // over the old paths, so the Mac stops using one we just turned off
        }
        switches.save(wifi, tor)
        applySettings()
    }

    private fun macHello(h: Hello) {
        if (!switches.macHello(h)) {
            trace?.invoke("hello: not newer than the last one, ignored")
            return
        }
        scope.launch { applySettings() } // after this Hello is acked on the path it came on
    }

    /**
     * Closes paths this phone's own switches turned off, opens ones they allow if wanted, and sends
     * what can go now. The Mac's list only decides what's sent where, never what's open.
     */
    private fun applySettings() {
        if (pairing.active || mac == null) return
        if (!switches.tor && onion?.active == true) {
            trace?.invoke("tor switched off: closing")
            stopPath(onion)
        }
        if (!switches.wifi) direct?.let { stopPath(it) }
        pathDown()
        reconsider(connect = true)
        if (canSend() || canSendHello()) flush()
    }

    /** Only the latest Hello matters: it replaces any still queued. */
    private fun queueHello(wifi: Boolean = switches.wifi, tor: Boolean = switches.tor) =
        outbox.replaceHello(switches.nextHello(wifi, tor))

    /** Closes both paths and stops looking for the Mac. */
    private fun closePaths() = paths.forEach { stopPath(it) }

    /** Closes one path. Anything the Mac hadn't confirmed on it may be lost: it goes again on the next path. */
    private fun stopPath(p: Path) {
        p.stop()
        if (upPaths.remove(p)) outbox.linkDropped()
        updateRoute()
    }

    private fun persist() {
        check(!storageBlocked) { "storage couldn't be opened" }
        store.save(device.exportState(store.storageKey()))
    }

    private companion object {
        const val RESET = "Seam's secure storage on this phone was reset. Pair again."
        const val UNPAIRED_CODES_DONT_MATCH = "Unpaired: your Mac confirmed just as you said the codes didn't match. Pair again if it's your Mac."
        const val CODES_DONT_MATCH = "Nothing was paired, because the codes didn't match. If you're sure it's your Mac, show a new code on it and scan again."
        const val FORGET_FAILED = "Couldn't finish forgetting your Mac: Seam couldn't save on this phone. Free up some space, reopen Seam and try again."
        const val UNAVAILABLE = "Seam couldn't open its secure storage just now. Close Seam and open it again: your pairing is kept."
        const val MAC_NOT_FOUND = "Couldn't reach your Mac. Keep its pairing code open, check both are online, and scan again."
        const val PAIR_AGAIN = "Seam now connects through Tor instead of its relay. Update Seam on your Mac, then pair again."
        /** After acking the PairAccept: time for the ack to get through before we close ourselves. */
        const val PAIRED_GRACE_MS = 3_000L
        /** Contract: Tor retries for up to 3 minutes while a fresh address publishes. */
        const val PAIRING_MS = 3 * 60_000L
    }
}

internal const val SAVE_FAILED = "Couldn't save Seam's data on this phone. Free up some space and reopen Seam."
