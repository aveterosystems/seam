// SPDX-License-Identifier: AGPL-3.0-only
package com.avetero.seam.session

import com.avetero.seam.crypto.SeamDevice
import com.avetero.seam.protocol.v1.ClientFrame
import com.avetero.seam.protocol.v1.Login
import com.avetero.seam.protocol.v1.ServerFrame
import com.google.protobuf.ByteString
import java.util.Base64
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Where to log in: the paired Mac's relay group, or while pairing the new Mac's, with its join token. */
internal class RelayAccount(val url: String, val groupId: ByteArray, val joinToken: ByteArray?)

/**
 * The relay path: one WebSocket, the signed-challenge login, reconnects with growing waits, and
 * giving up on a relay that keeps refusing this phone.
 */
internal class RelayPath(
    private val connector: RelayConnector,
    private val device: SeamDevice,
    private val scope: CoroutineScope,
    private val wait: suspend (Long) -> Unit,
    private val trace: ((String) -> Unit)?,
    /** Null: nowhere to log in. */
    private val account: () -> RelayAccount?,
    /** Whether a drop is worth a reconnect: something still needs the connection (decision 0005). */
    private val wanted: () -> Boolean,
    private val events: PathEvents,
) : Path {
    private var link: Link? = null
    private var generation = 0
    private var loggedIn = false
    private var reconnect: Job? = null
    /** Resets the backoff once a connection has lasted [STABLE_MS] (SR-2-61). */
    private var stable: Job? = null
    private val backoff = Backoff()
    /** Logins the relay refused in a row because it doesn't know this phone. */
    private var refusals = 0

    override val up get() = loggedIn
    override val active get() = link != null || reconnect?.isActive == true

    override fun start() {
        if (link == null && reconnect?.isActive != true) connect()
    }

    /** Connects again from scratch, e.g. with a new pairing's join token. */
    fun restart() {
        stop()
        connect()
    }

    override fun stop() {
        stable?.cancel()
        reconnect?.cancel()
        generation++ // so this socket's close callback doesn't count as a drop
        link?.close()
        link = null
        loggedIn = false
    }

    override fun send(frame: ClientFrame) = link?.send(frame.toByteArray()) == true

    private fun connect() {
        reconnect?.cancel()
        link?.close()
        link = null
        loggedIn = false
        val a = account() ?: return
        val mine = ++generation
        val query = Base64.getUrlEncoder().withoutPadding().encodeToString(a.groupId)
        val listener = object : LinkListener {
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
            connector.connect("${a.url}/v1/connect?group=$query", listener)
        } catch (_: Exception) {
            return events.onFailed(this, "That relay address doesn't work.")
        }
    }

    private fun handle(bytes: ByteArray) {
        val frame = runCatching { ServerFrame.parseFrom(bytes) }.getOrNull() ?: return
        trace?.invoke("relay frame: ${frame.frameCase}${if (frame.hasError()) " " + frame.error.code else ""}")
        when (frame.frameCase) {
            ServerFrame.FrameCase.CHALLENGE -> login(frame.challenge.nonce.toByteArray())
            ServerFrame.FrameCase.LOGIN_RESULT -> if (frame.loginResult.ok) loggedInOk() else refused("bad_login")
            ServerFrame.FrameCase.ERROR -> refused(frame.error.code)
            else -> events.onFrame(this, frame)
        }
    }

    private fun login(nonce: ByteArray) {
        val a = account() ?: return
        val login = Login.newBuilder()
            .setGroupId(ByteString.copyFrom(a.groupId))
            .setDeviceKey(ByteString.copyFrom(device.relayKey()))
            .setSignature(ByteString.copyFrom(device.signRelayLogin(nonce, a.groupId)))
        a.joinToken?.let { login.setJoinToken(ByteString.copyFrom(it)) }
        send(ClientFrame.newBuilder().setLogin(login).build())
    }

    private fun loggedInOk() {
        loggedIn = true
        stable?.cancel()
        stable = scope.launch {
            delay(STABLE_MS)
            backoff.reset()
            refusals = 0
        }
        events.onUp(this)
    }

    /** Pairing-time refusals end the pairing; a paired phone just reconnects with backoff. */
    private fun refused(code: String) {
        if (account()?.joinToken != null) return events.onFailed(this, explain(code))
        if (loggedIn && code !in FATAL_AFTER_LOGIN) return
        if (code in NOT_RECOGNISED && ++refusals >= MAX_REFUSALS) return events.onFailed(this, explain("bad_login"))
        generation++ // so this socket's own close callback doesn't reconnect a second time
        link?.close()
        closed()
    }

    private fun closed() {
        stable?.cancel()
        loggedIn = false
        link = null
        val mine = generation
        events.onDown(this)
        // Stopped meanwhile (a failed pairing), or dropped while nothing needs it: stay down (decision 0005)
        if (mine != generation || !wanted()) return
        reconnect = scope.launch {
            wait(backoff.next())
            connect()
        }
    }

    private companion object {
        const val MAX_REFUSALS = 5
        // After login these mean the socket is unusable; others (queue_full, too_large…) are per-message
        val FATAL_AFTER_LOGIN = setOf("bad_login", "not_member", "storage")
        val NOT_RECOGNISED = setOf("bad_login", "not_member")

        // Fixed texts only: the relay's own strings are never shown
        fun explain(code: String) = when (code) {
            "bad_token" -> "That pairing code has expired or was already used. Show a new one on your Mac."
            "bad_login" -> "The relay didn't recognise this phone. Pair again."
            "group_full" -> "Your Mac already has as many devices as it can pair."
            "rate_limited" -> "Too many attempts. Wait a minute and try again."
            else -> "The relay refused the connection."
        }
    }
}
