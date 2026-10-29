// SPDX-License-Identifier: AGPL-3.0-only
package com.avetero.seam.session

import com.avetero.seam.crypto.SeamDevice
import com.avetero.seam.crypto.SeamException
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
    var blob: ByteArray? = null
    var quarantined: ByteArray? = null
    var failSaves = false
    var keyFailures = 0
    override fun load() = blob
    override fun storageKey(): ByteArray {
        if (keyFailures > 0) { keyFailures--; error("keystore key lost") }
        return ByteArray(32) { 3 }
    }
    override fun save(blob: ByteArray) {
        if (failSaves) throw java.io.IOException("disk full")
        this.blob = blob
    }
    override fun quarantine() {
        quarantined = blob
        blob = null
    }
}

/** A relay with a real Mac (the Rust core) behind it, answering the way server/src/group.ts does. */
private class FakeRelay(var mac: SeamDevice) : RelayConnector {
    val logins = mutableListOf<ClientFrame>()
    val macReceived = mutableListOf<Payload>()
    var delivered = 0
    var refuseToken = false
    var withholdAccept = false
    var refuseNextLogin: String? = null
    var throwOnConnect = false
    var listener: RelayListener? = null
    val urls = mutableListOf<String>()

    override fun connect(url: String, listener: RelayListener): RelayLink {
        if (throwOnConnect) throw IllegalArgumentException("bad url")
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
                val refusal = refuseNextLogin ?: if (refuseToken && !f.login.joinToken.isEmpty) "bad_token" else null
                refuseNextLogin = null
                if (refusal != null) reply(ServerFrame.newBuilder().setError(RelayError.newBuilder().setCode(refusal)))
                else reply(ServerFrame.newBuilder().setLoginResult(LoginResult.newBuilder().setOk(true)))
            }
            ClientFrame.FrameCase.GET_BUNDLE -> reply(
                ServerFrame.newBuilder().setBundle(
                    BundleResult.newBuilder().setDeviceKey(f.getBundle.deviceKey)
                        .setBundle(ByteString.copyFrom(mac.prekeyBundles(1u).first())),
                ),
            )
            ClientFrame.FrameCase.SEND -> {
                val env = f.send.toByteArray()
                try {
                    macReceived += Payload.parseFrom(mac.open(env).payload)
                } catch (_: SeamException.NotPaired) {
                    val request = mac.acceptPairing(env)
                    if (withholdAccept) return
                    mac.confirmPairing(request.peerId) // the user confirmed the code
                    val accept = Payload.newBuilder().setControl(Control.newBuilder().setPairAccept(PairAccept.getDefaultInstance())).build()
                    deliverToPhone(mac.seal(request.peerId, accept.toByteArray(), 0u))
                }
            }
            ClientFrame.FrameCase.DELIVERED -> delivered++
            else -> Unit
        }
    }

    fun deliverToPhone(env: ByteArray) = reply(ServerFrame.newBuilder().setDeliver(Envelope.parseFrom(env)))
    fun drop() = listener!!.onClosed("dropped")
    fun error(code: String) = reply(ServerFrame.newBuilder().setError(RelayError.newBuilder().setCode(code)))
    private fun reply(f: ServerFrame.Builder) = listener!!.onFrame(f.build().toByteArray())
}

private fun qrFor(mac: SeamDevice, name: String = "MacBook Pro") =
    PairingQr("ws://relay.test", ByteArray(16) { 9 }, mac.startPairing().token, mac.identityKey(), mac.relayKey(), name)

@OptIn(ExperimentalCoroutinesApi::class)
class SessionTest {
    private val mac = SeamDevice.generate()
    private val qr = qrFor(mac)
    private val store = MemoryStore()
    private val relay = FakeRelay(mac)

    private fun TestScope.session(connector: RelayConnector = relay) = Session(
        store, connector, "Galaxy S25 Ultra", "SM-S938B",
        TestScope(StandardTestDispatcher(testScheduler)), wait = {},
    )

    private fun status(pct: Int) = Payload.newBuilder().setStatus(Status.newBuilder().setBatteryPct(pct).setCharging(true)).build()

    private fun TestScope.paired(): Session = session().also {
        it.pair(qr)
        advanceUntilIdle()
    }

    @Test fun pairsShowsTheSafetyCodeThenConnects() = runTest {
        val s = paired()
        assertEquals(SessionState.Connected("MacBook Pro"), s.state.value)
        assertArrayEquals(qr.token, relay.logins.single().login.joinToken.toByteArray())
        assertEquals("Galaxy S25 Ultra", mac.peers().single().name)
        assertEquals("ws://relay.test/v1/connect?group=CQkJCQkJCQkJCQkJCQkJCQ", relay.urls.single())
        assertTrue(relay.delivered >= 1) // the PairAccept was acked
        assertTrue("the phone never asks the relay to create a group", !relay.logins.single().login.createGroup)
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
        val s = paired()
        relay.drop()
        advanceUntilIdle()
        assertEquals(SessionState.Connected("MacBook Pro"), s.state.value)
        assertEquals(2, relay.logins.size)
        assertTrue("second login is a normal one", relay.logins[1].login.joinToken.isEmpty)
    }

    @Test fun survivesARestartWithoutRePairing() = runTest {
        paired()
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
        val s = paired()
        relay.listener!!.onFrame(byteArrayOf(1, 2, 3, 4))
        relay.deliverToPhone(Envelope.newBuilder().setVersion(1).setMsgId(ByteString.copyFrom(ByteArray(16))).build().toByteArray())
        advanceUntilIdle()
        assertEquals(SessionState.Connected("MacBook Pro"), s.state.value)
    }

    @Test fun sr_2_02_aConnectThatThrowsFailsCleanly() = runTest {
        relay.throwOnConnect = true
        val s = session()
        s.pair(qr)
        advanceUntilIdle()
        assertEquals(SessionState.Failed("That relay address doesn't work."), s.state.value)
    }

    @Test fun sr_2_03_aFeatureThatThrowsDoesNotCrashAndTheMessageIsAcked() = runTest {
        val s = session()
        s.register(object : Feature {
            override val handles = setOf(Payload.BodyCase.STATUS)
            override fun receive(payload: Payload, fromPeer: String) = error("feature bug")
        })
        s.pair(qr)
        advanceUntilIdle()
        val acked = relay.delivered
        relay.deliverToPhone(mac.seal(mac.peers().single().id, status(1).toByteArray(), 0u))
        advanceUntilIdle()
        assertEquals(acked + 1, relay.delivered)
        assertEquals(SessionState.Connected("MacBook Pro"), s.state.value)
    }

    @Test fun sr_2_03_aSaveFailureIsShownAndTheMessageIsNotAcked() = runTest {
        val s = paired()
        val acked = relay.delivered
        store.failSaves = true
        relay.deliverToPhone(mac.seal(mac.peers().single().id, status(1).toByteArray(), 0u))
        advanceUntilIdle()
        assertEquals("not acked, so the relay keeps it", acked, relay.delivered)
        assertTrue((s.state.value as SessionState.Failed).reason.startsWith("Couldn't save"))
    }

    @Test fun sr_2_04_unreadableStateIsMovedAsideNotOverwritten() = runTest {
        store.blob = byteArrayOf(1, 2, 3)
        val s = session()
        assertEquals(SessionState.Failed("Seam's secure storage on this phone was reset. Pair again."), s.state.value)
        assertArrayEquals(byteArrayOf(1, 2, 3), store.quarantined)
    }

    @Test fun sr_2_04_aLostKeystoreKeyIsReportedNotACrash() = runTest {
        paired()
        val saved = store.blob
        store.keyFailures = 1
        val s = session()
        assertTrue(s.state.value is SessionState.Failed)
        assertArrayEquals(saved, store.quarantined)
    }

    @Test fun sr_2_11_nothingIsSentIfSavingFails() = runTest {
        val s = paired()
        store.failSaves = true
        s.send(status(77))
        advanceUntilIdle()
        assertTrue(relay.macReceived.isEmpty())
    }

    @Test fun sr_2_18_aFailedRePairKeepsTheExistingMac() = runTest {
        val s = paired()
        val other = SeamDevice.generate()
        relay.refuseToken = true
        s.pair(qrFor(other, "Someone else's Mac"))
        advanceUntilIdle()
        assertEquals(SessionState.Connected("MacBook Pro"), s.state.value)
        assertTrue(s.notice.value!!.contains("expired"))
        s.send(status(66))
        advanceUntilIdle()
        assertEquals(66, relay.macReceived.single().status.batteryPct)
    }

    @Test fun sr_2_18_aRestartMidPairingForgetsTheHalfPairedMac() = runTest {
        relay.withholdAccept = true
        val s = session()
        s.pair(qr)
        advanceUntilIdle()
        assertTrue(s.state.value is SessionState.Pairing)
        val restarted = session()
        restarted.start()
        advanceUntilIdle()
        assertEquals(SessionState.Unpaired, restarted.state.value)
    }

    @Test fun sr_2_19_aRelayErrorWhilePairedReconnectsInsteadOfFailing() = runTest {
        val s = paired()
        relay.refuseNextLogin = "storage"
        relay.drop()
        advanceUntilIdle()
        assertEquals(SessionState.Connected("MacBook Pro"), s.state.value)
        assertEquals(3, relay.logins.size) // pairing, refused, then the retry
    }

    @Test fun sr_2_19_relayErrorTextIsNeverShown() = runTest {
        relay.refuseNextLogin = "<script>evil</script>"
        val s = session()
        s.pair(qr)
        advanceUntilIdle()
        assertEquals(SessionState.Failed("The relay refused the connection."), s.state.value)
    }

    @Test fun sr_2_20_theOutboxKeepsOnlyTheLatestStatus() = runTest {
        val s = paired()
        relay.drop()
        relay.refuseNextLogin = "storage" // stay offline for a moment
        repeat(5) { s.send(status(it)) }
        advanceUntilIdle()
        assertEquals(listOf(4), relay.macReceived.map { it.status.batteryPct })
    }
}

class PairingQrTest {
    private fun b64(n: Int, v: Int = 1) = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(n) { v.toByte() })
    private fun url(v: String = "1", r: String = "wss%3A%2F%2Frelay.example.com", g: String = b64(16), i: String = b64(33)) =
        "seam://pair?v=$v&r=$r&g=$g&t=${b64(32)}&i=$i&k=${b64(32)}&n=MacBook%20Pro"

    @Test fun parsesAValidCode() {
        val qr = PairingQr.parse(url())!!
        assertEquals("wss://relay.example.com", qr.relayUrl)
        assertEquals("relay.example.com", qr.relayHost)
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

    @Test fun sr_2_02_badRelayUrlsAreRefusedNotCrashed() {
        for (r in listOf("wss%3A%2F%2F", "wss%3A%2F%2F%5Bx", "wss%3A%2F%2Fa%20b", "wss%3A%2F%2Fuser%3Apass%40relay.example.com",
            "wss%3A%2F%2Frelay.example.com%2F%3Fq%3D1", "wss%3A%2F%2Frelay.example.com%23frag", "wss%3A%2F%2F" + "a".repeat(600))) {
            assertNull(r, PairingQr.parse(url(r = r)))
        }
    }

    @Test fun sr_2_02_plaintextRelaysOnlyInDebugBuilds() {
        val local = url(r = "ws%3A%2F%2Flocalhost%3A8787")
        assertNull(PairingQr.parse(local))
        assertEquals("ws://localhost:8787", PairingQr.parse(local, allowCleartext = true)!!.relayUrl)
    }
}

class RelayTargetTest {
    @Test fun roundTripsAndRefusesGarbage() {
        val t = RelayTarget("wss://relay.example.com", ByteArray(16) { 4 })
        val back = RelayTarget.decode(t.encode())!!
        assertEquals(t.url, back.url)
        assertArrayEquals(t.groupId, back.groupId)
        assertNull(RelayTarget.decode(byteArrayOf()))
        assertNull(RelayTarget.decode(t.encode().copyOf(10)))
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
