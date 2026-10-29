// SPDX-License-Identifier: AGPL-3.0-only
package com.avetero.seam.session

import com.avetero.seam.crypto.SeamDevice
import com.avetero.seam.crypto.SeamException
import com.avetero.seam.protocol.v1.ClientFrame
import com.avetero.seam.protocol.v1.Control
import com.avetero.seam.protocol.v1.Delivered
import com.avetero.seam.protocol.v1.Envelope
import com.avetero.seam.protocol.v1.GetBundle
import com.avetero.seam.protocol.v1.Login
import com.avetero.seam.protocol.v1.Payload
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
    data class Failed(val reason: String) : SessionState
}

/**
 * The phone's link to its Mac: relay login, pairing, delivery, acks and reconnects. Everything
 * runs on [scope], which must be single-threaded, so state is only ever touched from one thread.
 */
class Session(
    private val store: StateStore,
    private val prefs: SessionPrefs,
    private val connector: RelayConnector,
    private val myName: String,
    private val myModel: String,
    private val scope: CoroutineScope,
    private val wait: suspend (Long) -> Unit = { delay(it) },
) : SessionApi {
    private val device: SeamDevice = store.load()
        ?.let { runCatching { SeamDevice.restore(it, store.storageKey) }.getOrNull() }
        ?: SeamDevice.generate().also { store.save(it.exportState(store.storageKey)) }

    private val _state = MutableStateFlow<SessionState>(SessionState.Unpaired)
    val state: StateFlow<SessionState> = _state

    private val features = mutableListOf<Feature>()
    private val backoff = Backoff()
    // shortcut: unsent envelopes are kept in memory only; persist the outbox when features need it
    private val outbox = ArrayDeque<ByteArray>()
    private var link: RelayLink? = null
    private var generation = 0
    private var loggedIn = false
    private var pairing: PairingQr? = null
    private var reconnect: Job? = null

    private val mac get() = device.peers().firstOrNull()

    fun register(feature: Feature) {
        features += feature
    }

    fun start() = scope.launch {
        val m = mac ?: return@launch
        _state.value = SessionState.Connecting(m.name)
        connect()
    }

    /** Pair with the Mac from its scanned QR code. Replaces any earlier pairing. */
    fun pair(qr: PairingQr) = scope.launch {
        mac?.let { device.removePeer(it.id) }
        prefs.relayUrl = qr.relayUrl
        prefs.groupId = qr.groupId
        pairing = qr
        _state.value = SessionState.Pairing(qr.macName, null)
        connect()
    }

    override fun send(payload: Payload) {
        scope.launch {
            val m = mac ?: return@launch
            if (pairing != null) return@launch
            val env = device.seal(m.id, payload.toByteArray(), 0u)
            persist()
            if (!(loggedIn && sendEnvelope(env))) outbox.addLast(env)
        }
    }

    private fun connect() {
        reconnect?.cancel()
        link?.close()
        val url = prefs.relayUrl ?: return
        val group = prefs.groupId ?: return
        loggedIn = false
        val mine = ++generation
        val query = Base64.getUrlEncoder().withoutPadding().encodeToString(group)
        link = connector.connect("$url/v1/connect?group=$query", object : RelayListener {
            override fun onFrame(frame: ByteArray) {
                scope.launch { if (mine == generation) handle(frame) }
            }
            override fun onClosed(reason: String) {
                scope.launch { if (mine == generation) closed() }
            }
        })
    }

    private fun handle(bytes: ByteArray) {
        val frame = runCatching { ServerFrame.parseFrom(bytes) }.getOrNull() ?: return
        when (frame.frameCase) {
            ServerFrame.FrameCase.CHALLENGE -> login(frame.challenge.nonce.toByteArray())
            ServerFrame.FrameCase.LOGIN_RESULT -> if (frame.loginResult.ok) loggedInOk() else fail("The relay refused this phone")
            ServerFrame.FrameCase.BUNDLE -> bundleArrived(frame.bundle.bundle.toByteArray())
            ServerFrame.FrameCase.DELIVER -> deliver(frame.deliver)
            ServerFrame.FrameCase.ERROR -> if (pairing != null || !loggedIn) fail(explain(frame.error.code))
            else -> Unit
        }
    }

    private fun login(nonce: ByteArray) {
        val group = prefs.groupId ?: return
        val login = Login.newBuilder()
            .setGroupId(group.bs())
            .setDeviceKey(device.relayKey().bs())
            .setSignature(device.signRelayLogin(nonce, group).bs())
        pairing?.let { login.setJoinToken(it.token.bs()) }
        sendFrame(ClientFrame.newBuilder().setLogin(login).build())
    }

    private fun loggedInOk() {
        loggedIn = true
        backoff.reset()
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
            persist()
            sendEnvelope(start.envelope)
            _state.value = SessionState.Pairing(p.macName, device.safetyCode(start.peerId))
        } catch (e: SeamException) {
            fail("That pairing code didn't work. Show a new one on your Mac and scan again.")
        }
    }

    private fun deliver(env: Envelope) {
        try {
            val opened = device.open(env.toByteArray())
            persist()
            val payload = Payload.parseFrom(opened.payload)
            val accepted = payload.bodyCase == Payload.BodyCase.CONTROL &&
                payload.control.bodyCase == Control.BodyCase.PAIR_ACCEPT
            if (accepted && pairing != null) {
                pairing = null
                becameConnected()
            } else {
                features.filter { payload.bodyCase in it.handles }.forEach { it.receive(payload, opened.peerId) }
            }
        } catch (_: SeamException) {
            // Duplicate: already handled. Anything else is dropped (never logged with content).
        }
        sendFrame(ClientFrame.newBuilder().setDelivered(Delivered.newBuilder().setMsgId(env.msgId)).build())
    }

    private fun becameConnected() {
        val m = mac ?: return
        _state.value = SessionState.Connected(m.name)
        while (outbox.isNotEmpty() && sendEnvelope(outbox.first())) outbox.removeFirst()
        features.forEach { it.onConnected(this) }
    }

    private fun closed() {
        loggedIn = false
        link = null
        if (pairing != null) return fail("Lost the connection while pairing. Try again.")
        val m = mac ?: return
        _state.value = SessionState.Connecting(m.name)
        reconnect = scope.launch {
            wait(backoff.next())
            connect()
        }
    }

    private fun fail(reason: String) {
        if (pairing != null) {
            mac?.let { device.removePeer(it.id) }
            persist()
            pairing = null
        }
        _state.value = SessionState.Failed(reason)
        generation++ // ignore anything the old socket still delivers
        link?.close()
        link = null
    }

    private fun sendEnvelope(env: ByteArray) =
        sendFrame(ClientFrame.newBuilder().setSend(Envelope.parseFrom(env)).build())

    private fun sendFrame(frame: ClientFrame) = link?.send(frame.toByteArray()) == true

    private fun persist() = store.save(device.exportState(store.storageKey))

    private fun explain(code: String) = when (code) {
        "bad_token" -> "That pairing code has expired or was already used. Show a new one on your Mac."
        "bad_login" -> "The relay didn't recognise this phone. Pair again."
        else -> "The relay refused the connection ($code)."
    }
}

private fun ByteArray.bs(): ByteString = ByteString.copyFrom(this)
