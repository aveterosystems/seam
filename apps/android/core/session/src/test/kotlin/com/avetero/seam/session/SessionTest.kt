// SPDX-License-Identifier: AGPL-3.0-only
package com.avetero.seam.session

import com.avetero.seam.crypto.SeamDevice
import com.avetero.seam.protocol.v1.BundleResult
import com.avetero.seam.protocol.v1.Challenge
import com.avetero.seam.protocol.v1.ClientFrame
import com.avetero.seam.protocol.v1.Control
import com.avetero.seam.protocol.v1.Envelope
import com.avetero.seam.protocol.v1.LoginResult
import com.avetero.seam.protocol.v1.PairAccept
import com.avetero.seam.protocol.v1.Payload
import com.avetero.seam.protocol.v1.RelayError
import com.avetero.seam.protocol.v1.ServerFrame
import com.avetero.seam.protocol.v1.Status
import com.google.protobuf.ByteString
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

private class MemoryStore : StateStore {
    override val storageKey = ByteArray(32) { 3 }
    var blob: ByteArray? = null
    override fun load() = blob
    override fun save(blob: ByteArray) {
        this.blob = blob
    }
}

private class MemoryPrefs : SessionPrefs {
    override var relayUrl: String? = null
    override var groupId: ByteArray? = null
}

/** A relay with a real Mac (the Rust core) behind it, answering the way server/src/group.ts does. */
private class FakeRelay(val mac: SeamDevice, private val token: ByteArray) : RelayConnector {
    val logins = mutableListOf<ClientFrame>()
    val macReceived = mutableListOf<Payload>()
    var delivered = 0
    var refuseToken = false
    var listener: RelayListener? = null
    var urls = mutableListOf<String>()

    override fun connect(url: String, listener: RelayListener): RelayLink {
        urls += url
        this.listener = listener
        reply(ServerFrame.newBuilder().setChallenge(Challenge.newBuilder().setNonce(ByteString.copyFrom(ByteArray(32) { 1 }))))
        return object : RelayLink {
            override fun send(frame: ByteArray): Boolean {
                handle(ClientFrame.parseFrom(frame))
                return true
            }
            override fun close() = Unit
        }
    }

    private fun handle(f: ClientFrame) {
        when (f.frameCase) {
            ClientFrame.FrameCase.LOGIN -> {
                logins += f
                if (refuseToken && !f.login.joinToken.isEmpty) {
                    reply(ServerFrame.newBuilder().setError(RelayError.newBuilder().setCode("bad_token")))
                } else {
                    reply(ServerFrame.newBuilder().setLoginResult(LoginResult.newBuilder().setOk(true)))
                }
            }
            ClientFrame.FrameCase.GET_BUNDLE -> reply(
                ServerFrame.newBuilder().setBundle(
                    BundleResult.newBuilder().setDeviceKey(f.getBundle.deviceKey)
                        .setBundle(ByteString.copyFrom(mac.prekeyBundles(1u).first())),
                ),
            )
            ClientFrame.FrameCase.SEND -> {
                val env = f.send.toByteArray()
                if (mac.peers().isEmpty()) {
                    val request = mac.acceptPairing(env)
                    val accept = Payload.newBuilder().setControl(Control.newBuilder().setPairAccept(PairAccept.getDefaultInstance())).build()
                    deliverToPhone(mac.seal(request.peerId, accept.toByteArray(), 0u))
                } else {
                    macReceived += Payload.parseFrom(mac.open(env).payload)
                }
            }
            ClientFrame.FrameCase.DELIVERED -> delivered++
            else -> Unit
        }
    }

    fun deliverToPhone(env: ByteArray) = reply(ServerFrame.newBuilder().setDeliver(Envelope.parseFrom(env)))
    fun drop() = listener!!.onClosed("dropped")
    private fun reply(f: ServerFrame.Builder) = listener!!.onFrame(f.build().toByteArray())
}

@OptIn(ExperimentalCoroutinesApi::class)
class SessionTest {
    private val mac = SeamDevice.generate()
    private val offer = mac.startPairing()
    private val qr = PairingQr("ws://relay.test", ByteArray(16) { 9 }, offer.token, mac.identityKey(), mac.relayKey(), "MacBook Pro")
    private val store = MemoryStore()
    private val prefs = MemoryPrefs()
    private val relay = FakeRelay(mac, offer.token)

    private fun TestScope.session(connector: RelayConnector = relay) = Session(
        store, prefs, connector, "Galaxy S25 Ultra", "SM-S938B",
        TestScope(StandardTestDispatcher(testScheduler)), wait = {},
    )

    private fun status(pct: Int) = Payload.newBuilder().setStatus(Status.newBuilder().setBatteryPct(pct).setCharging(true)).build()

    @Test fun pairsShowsTheSafetyCodeThenConnects() = runTest {
        val s = session()
        s.pair(qr)
        advanceUntilIdle()
        assertEquals(SessionState.Connected("MacBook Pro"), s.state.value)
        assertArrayEquals(offer.token, relay.logins.single().login.joinToken.toByteArray())
        assertEquals("Galaxy S25 Ultra", mac.peers().single().name)
        assertEquals("ws://relay.test/v1/connect?group=CQkJCQkJCQkJCQkJCQkJCQ", relay.urls.single())
        assertTrue(relay.delivered >= 1) // the PairAccept was acked
    }

    @Test fun sendsBatteryStatusToTheMacOnceConnected() = runTest {
        val s = session()
        s.send(status(10)) // before pairing: nowhere to go, dropped
        s.pair(qr)
        advanceUntilIdle()
        s.send(status(82))
        advanceUntilIdle()
        assertEquals(82, relay.macReceived.single().status.batteryPct)
    }

    @Test fun featuresAreToldWhenConnected() = runTest {
        val s = session()
        var connects = 0
        s.register(object : Feature {
            override fun onConnected(session: SessionApi) {
                connects++
                session.send(status(55))
            }
        })
        s.pair(qr)
        advanceUntilIdle()
        assertEquals(1, connects)
        assertEquals(55, relay.macReceived.single().status.batteryPct)
    }

    @Test fun reconnectsAfterADropWithoutTheJoinToken() = runTest {
        val s = session()
        s.pair(qr)
        advanceUntilIdle()
        relay.drop()
        advanceUntilIdle()
        assertEquals(SessionState.Connected("MacBook Pro"), s.state.value)
        assertEquals(2, relay.logins.size)
        assertTrue("second login is a normal one", relay.logins[1].login.joinToken.isEmpty)
    }

    @Test fun survivesARestartWithoutRePairing() = runTest {
        session().pair(qr)
        advanceUntilIdle()
        val restarted = session()
        restarted.start()
        advanceUntilIdle()
        assertEquals(SessionState.Connected("MacBook Pro"), restarted.state.value)
        restarted.send(status(40))
        advanceUntilIdle()
        assertEquals(40, relay.macReceived.single().status.batteryPct)
    }

    @Test fun expiredCodeFailsCleanlyAndLeavesNoHalfPairing() = runTest {
        relay.refuseToken = true
        val s = session()
        s.pair(qr)
        advanceUntilIdle()
        assertTrue(s.state.value is SessionState.Failed)
        val restarted = session()
        restarted.start()
        advanceUntilIdle()
        assertEquals(SessionState.Unpaired, restarted.state.value)
    }

    @Test fun garbageFromTheRelayIsIgnored() = runTest {
        val s = session()
        s.pair(qr)
        advanceUntilIdle()
        relay.listener!!.onFrame(byteArrayOf(1, 2, 3, 4))
        relay.deliverToPhone(Envelope.newBuilder().setVersion(1).setMsgId(ByteString.copyFrom(ByteArray(16))).build().toByteArray())
        advanceUntilIdle()
        assertEquals(SessionState.Connected("MacBook Pro"), s.state.value)
    }
}

class PairingQrTest {
    private fun b64(n: Int, v: Int = 1) = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(n) { v.toByte() })
    private fun url(v: String = "1", r: String = "ws%3A%2F%2F192.168.1.20%3A8787", g: String = b64(16), i: String = b64(33)) =
        "seam://pair?v=$v&r=$r&g=$g&t=${b64(32)}&i=$i&k=${b64(32)}&n=MacBook%20Pro"

    @Test fun parsesAValidCode() {
        val qr = PairingQr.parse(url())!!
        assertEquals("ws://192.168.1.20:8787", qr.relayUrl)
        assertEquals("MacBook Pro", qr.macName)
        assertEquals(16, qr.groupId.size)
        assertEquals(33, qr.identityKey.size)
    }

    @Test fun refusesAnythingElse() {
        assertNull(PairingQr.parse("https://example.com"))
        assertNull(PairingQr.parse(url(v = "2")))
        assertNull(PairingQr.parse(url(g = b64(15))))
        assertNull(PairingQr.parse(url(i = "not*base64")))
        assertNull(PairingQr.parse(url(r = "http%3A%2F%2Fevil")))
        assertNull(PairingQr.parse(url().replace("&k=", "&x=")))
    }
}

class BackoffTest {
    @Test fun doublesFromOneSecondToAMinuteThenResets() {
        val b = Backoff(kotlin.random.Random(0))
        val delays = List(9) { b.next() }
        listOf(1_000L, 2_000, 4_000, 8_000, 16_000, 32_000, 60_000, 60_000, 60_000).zip(delays).forEach { (base, d) ->
            assertTrue("$d within 20% above $base", d in base..(base * 12 / 10))
        }
        b.reset()
        assertTrue(b.next() < 1_300)
    }
}
