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
import com.avetero.seam.protocol.v1.Notification
import com.avetero.seam.protocol.v1.PairAccept
import com.avetero.seam.protocol.v1.Payload
import com.avetero.seam.protocol.v1.RelayError
import com.avetero.seam.protocol.v1.ServerFrame
import com.avetero.seam.protocol.v1.Status
import com.google.protobuf.ByteString
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
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
    val saves = mutableListOf<ByteArray>()
    var keyFailures = 0
    var keyError: () -> Exception = { StateUnreadableException(javax.crypto.AEADBadTagException()) }
    var failQuarantine = false
    override fun load() = blob
    override fun storageKey(): ByteArray {
        if (keyFailures > 0) { keyFailures--; throw keyError() }
        return ByteArray(32) { 3 }
    }
    override fun save(blob: ByteArray) {
        if (failSaves) throw java.io.IOException("disk full")
        this.blob = blob
        saves += blob
    }
    override fun quarantine() {
        if (failQuarantine) error("rename failed")
        quarantined = blob
        blob = null
    }
}

/** A relay with a real Mac (the Rust core) behind it, answering the way server/src/group.ts does. */
private class FakeRelay(var mac: SeamDevice) : RelayConnector {
    val logins = mutableListOf<ClientFrame>()
    val macReceived = mutableListOf<Payload>()
    val sealedTtls = mutableListOf<Int>()
    val removed = mutableListOf<ByteArray>()
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
                sealedTtls += f.send.ttlS
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
            ClientFrame.FrameCase.REMOVE_MEMBER -> removed += f.removeMember.deviceKey.toByteArray()
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

    private fun TestScope.session(
        connector: RelayConnector = relay,
        wait: suspend (Long) -> Unit = {},
        trace: ((String) -> Unit)? = null,
        now: () -> Long = { testScheduler.currentTime },
    ) = Session(
        store, connector, "Galaxy S25 Ultra", "SM-S938B",
        TestScope(StandardTestDispatcher(testScheduler)), wait = wait, now = now, trace = trace,
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

    @Test fun sr_2_57_aPassingKeystoreErrorKeepsThePairing() = runTest {
        paired()
        val saved = store.blob
        store.keyFailures = 1
        store.keyError = { java.security.ProviderException("keystore not ready") }
        val s = session()
        advanceUntilIdle()
        assertEquals(SessionState.Failed("Seam couldn't open its secure storage just now. Close Seam and open it again: your pairing is kept."), s.state.value)
        assertNull("nothing moved aside", store.quarantined)
        assertArrayEquals("nothing saved over it", saved, store.blob)
        s.pair(qrFor(mac))
        advanceUntilIdle()
        assertArrayEquals("pairing can't replace it either", saved, store.blob)

        // Next launch, the Keystore works again and the Mac is still there
        val again = session()
        again.start()
        advanceUntilIdle()
        assertEquals(SessionState.Connected("MacBook Pro"), again.state.value)
    }

    @Test fun sr_2_57_stateThatCantBeMovedAsideIsNeverOverwritten() = runTest {
        paired()
        val saved = store.blob
        store.keyFailures = 1
        store.failQuarantine = true
        val s = session()
        advanceUntilIdle()
        assertEquals(SessionState.Failed("Seam couldn't open its secure storage just now. Close Seam and open it again: your pairing is kept."), s.state.value)
        assertArrayEquals(saved, store.blob)
    }

    @Test fun sr_2_59_aMessageWhoseSaveFailsIsStillHandledAndNotAcked() = runTest {
        val s = session()
        val received = mutableListOf<Int>()
        s.register(object : Feature {
            override val handles = setOf(Payload.BodyCase.STATUS)
            override fun receive(payload: Payload, fromPeer: String) { received += payload.status.batteryPct }
        })
        s.pair(qr)
        advanceUntilIdle()
        val acked = relay.delivered
        store.failSaves = true
        relay.deliverToPhone(mac.seal(mac.peers().single().id, status(9).toByteArray(), 0u))
        advanceUntilIdle()
        assertEquals("handled, not lost", listOf(9), received)
        assertEquals("not acked, so a restart from the saved state opens it again", acked, relay.delivered)
    }

    @Test fun sr_2_61_aPhoneTheRelayNoLongerKnowsIsToldToPairAgain() = runTest {
        val s = paired()
        repeat(5) { // MAX_REFUSALS
            relay.error("not_member")
            runCurrent() // reconnects at once, before the connection counts as stable
        }
        advanceUntilIdle()
        assertEquals(SessionState.Failed("The relay didn't recognise this phone. Pair again."), s.state.value)
    }

    @Test fun sr_2_61_theBackoffGrowsWhileTheRelayKeepsDroppingUs() = runTest {
        val delays = mutableListOf<Long>()
        val s = session(wait = { delays += it })
        s.pair(qr)
        advanceUntilIdle()
        repeat(3) {
            relay.error("storage") // accepted the login, then dropped us
            runCurrent()
        }
        assertEquals(3, delays.size)
        assertTrue("each wait at least ~doubles: $delays", delays.zipWithNext().all { (a, b) -> b >= a * 3 / 2 })
    }

    @Test fun sr_2_62_aFeatureThatThrowsOnConnectDoesNotTakeTheConnectionDown() = runTest {
        val s = session()
        s.register(object : Feature {
            override fun onConnected(session: SessionApi) = error("feature bug")
        })
        s.register(object : Feature {
            override fun onConnected(session: SessionApi) = session.send(status(55))
        })
        s.pair(qr)
        advanceUntilIdle()
        assertEquals(SessionState.Connected("MacBook Pro"), s.state.value)
        assertEquals("the next feature still ran, and its message went out", 55, relay.macReceived.single().status.batteryPct)
    }

    @Test fun sr_2_63_noSavedStateEverHoldsTwoMacs() = runTest {
        val s = paired()
        val before = store.saves.size
        val newMac = SeamDevice.generate()
        relay.mac = newMac
        s.pair(qrFor(newMac, "Mac mini"))
        advanceUntilIdle()
        assertEquals(SessionState.Connected("Mac mini"), s.state.value)
        for (blob in store.saves.drop(before)) {
            val peers = SeamDevice.restore(blob, ByteArray(32) { 3 }).peers()
            assertTrue("a saved state with ${peers.size} Macs", peers.size <= 1)
        }
    }

    @Test fun sr_2_64_aPairRequestIsNeverSentUnsaved() = runTest {
        store.failSaves = true
        val s = session()
        s.pair(qr)
        advanceUntilIdle()
        assertTrue("the Mac never got a PairRequest", mac.peers().isEmpty())
        assertTrue((s.state.value as SessionState.Failed).reason.startsWith("Couldn't save"))
    }

    @Test fun clipboardWaitsAtMostTenMinutesAndOnlyTheLatestIsQueued() = runTest {
        val s = paired()
        relay.drop() // runs before the sends below, so they wait in the outbox for the reconnect
        val clip = { t: String -> Payload.newBuilder().setClipboard(com.avetero.seam.protocol.v1.Clipboard.newBuilder().setText(t)).build() }
        relay.sealedTtls.clear()
        s.send(clip("old"))
        s.send(clip("new"))
        advanceUntilIdle()
        assertEquals(listOf("new"), relay.macReceived.filter { it.hasClipboard() }.map { it.clipboard.text })
        assertEquals(600, relay.sealedTtls.last())
    }

    @Test fun sr_4_04_aClipboardQueuedTooLongIsDroppedNotSentLate() = runTest {
        val s = session(wait = { kotlinx.coroutines.delay(11 * 60_000L) }) // the relay comes back after 11 minutes
        s.pair(qr)
        advanceUntilIdle()
        relay.drop()
        s.send(Payload.newBuilder().setClipboard(com.avetero.seam.protocol.v1.Clipboard.newBuilder().setText("stale")).build())
        advanceUntilIdle()
        assertEquals(SessionState.Connected("MacBook Pro"), s.state.value)
        assertTrue("never sent late", relay.macReceived.none { it.hasClipboard() })
    }

    @Test fun forgettingTheMacTellsItLeavesTheGroupAndForgetsIt() = runTest {
        val s = paired()
        val phoneKey = relay.logins.first().login.deviceKey.toByteArray()
        s.forgetMac()
        advanceUntilIdle()
        assertEquals(SessionState.Unpaired, s.state.value)
        assertTrue("the Mac was told", relay.macReceived.any { it.hasControl() && it.control.hasUnpair() })
        assertArrayEquals("the phone left the group", phoneKey, relay.removed.single())
        assertTrue("nothing saved still pairs", SeamDevice.restore(store.blob!!, ByteArray(32) { 3 }).peers().isEmpty())
        val again = session()
        again.start()
        advanceUntilIdle()
        assertEquals("a restart stays unpaired", SessionState.Unpaired, again.state.value)
    }

    @Test fun sr_7_01_aForgetThatCantBeSavedSaysSoAndSendsNothing() = runTest {
        val s = paired()
        store.failSaves = true
        s.forgetMac()
        advanceUntilIdle()
        assertTrue((s.state.value as SessionState.Failed).reason.startsWith("Couldn't finish forgetting"))
        assertTrue("the Mac wasn't told something that didn't happen", relay.macReceived.none { it.hasControl() && it.control.hasUnpair() })
        assertTrue("and the phone didn't leave the group", relay.removed.isEmpty())
    }

    @Test fun anUnpairIsIgnoredWhilePairingWithAnotherMac() = runTest {
        val s = paired()
        val newMac = SeamDevice.generate()
        relay.mac = newMac
        relay.withholdAccept = true // the new Mac hasn't accepted yet
        s.pair(qrFor(newMac, "Mac mini"))
        advanceUntilIdle()
        val unpair = Payload.newBuilder().setControl(Control.newBuilder().setUnpair(com.avetero.seam.protocol.v1.Unpair.getDefaultInstance())).build()
        relay.deliverToPhone(mac.seal(mac.peers().single().id, unpair.toByteArray(), 0u))
        advanceUntilIdle()
        assertTrue("still pairing, nothing forgotten", s.state.value is SessionState.Pairing)
    }

    @Test fun whenTheMacUnpairsThePhoneForgetsIt() = runTest {
        val s = paired()
        val unpair = Payload.newBuilder().setControl(Control.newBuilder().setUnpair(com.avetero.seam.protocol.v1.Unpair.getDefaultInstance())).build()
        relay.deliverToPhone(mac.seal(mac.peers().single().id, unpair.toByteArray(), 0u))
        advanceUntilIdle()
        assertEquals(SessionState.Unpaired, s.state.value)
        assertTrue(SeamDevice.restore(store.blob!!, ByteArray(32) { 3 }).peers().isEmpty())
    }

    private fun clip(t: String) = Payload.newBuilder().setClipboard(com.avetero.seam.protocol.v1.Clipboard.newBuilder().setText(t)).build()

    @Test fun d0005_screenOffAndIdleClosesTheConnection() = runTest {
        val s = paired()
        val logins = relay.logins.size
        s.setScreenOn(false)
        advanceUntilIdle() // a minute with nothing to carry
        assertEquals(SessionState.Idle("MacBook Pro"), s.state.value)
        assertEquals("and it doesn't reconnect by itself", logins, relay.logins.size)
    }

    @Test fun d0005_aCopyWhileIdleConnectsSendsAndSleepsAgain() = runTest {
        val s = paired()
        s.setScreenOn(false)
        advanceUntilIdle()
        val logins = relay.logins.size
        s.send(clip("sent from an idle phone"))
        advanceUntilIdle()
        assertEquals(logins + 1, relay.logins.size)
        assertTrue(relay.macReceived.any { it.hasClipboard() && it.clipboard.text == "sent from an idle phone" })
        assertEquals(SessionState.Idle("MacBook Pro"), s.state.value)
    }

    @Test fun aNotificationYouCanReplyToKeepsTheConnectionForTheReply() = runTest {
        val s = paired()
        s.setScreenOn(false)
        advanceUntilIdle()
        s.send(Payload.newBuilder().setNotification(Notification.newBuilder().setKey("k").setCanReply(true)).build())
        advanceTimeBy(2 * 60_000L) // past the usual one-minute idle
        assertEquals("still there for a reply after the usual minute", SessionState.Connected("MacBook Pro"), s.state.value)
        advanceUntilIdle()
        assertEquals("but not for ever", SessionState.Idle("MacBook Pro"), s.state.value)
    }

    @Test fun d0005_aBatteryChangeWaitsForTheNextConnection() = runTest {
        val s = paired()
        s.setScreenOn(false)
        advanceUntilIdle()
        val logins = relay.logins.size
        s.send(status(42))
        advanceUntilIdle()
        assertEquals("no radio wake-up for a battery change", logins, relay.logins.size)
        s.setScreenOn(true) // the user picks the phone up
        advanceUntilIdle()
        assertEquals(logins + 1, relay.logins.size)
        assertEquals(42, relay.macReceived.last { it.hasStatus() }.status.batteryPct)
        assertEquals(SessionState.Connected("MacBook Pro"), s.state.value)
    }

    @Test fun sr_8_01_aCopyThatCantGoDoesNotSpinAndGivesUpWhenStale() = runTest {
        var silent = false
        // Connects, but the relay never answers: the copy can't go
        val flaky = RelayConnector { url, listener ->
            if (!silent) relay.connect(url, listener)
            else object : RelayLink {
                override fun send(frame: ByteArray) = true
                override fun close() = Unit
            }
        }
        var decisions = 0
        val s = session(flaky, trace = { if (++decisions > 500) error("busy loop: $decisions decisions") })
        s.pair(qr)
        advanceUntilIdle()
        silent = true
        s.setScreenOn(false)
        advanceUntilIdle() // goes idle
        s.send(clip("can't go"))
        advanceTimeBy(30 * 60_000L) // half an hour of a silent relay
        assertTrue("bounded, not a loop: $decisions", decisions < 50)
        relay.drop() // let the stuck link close
        advanceUntilIdle()
        assertTrue("and a stale copy stops keeping it awake", s.state.value is SessionState.Idle || s.state.value is SessionState.Connecting)
    }

    @Test fun d0005_chargingKeepsTheConnection() = runTest {
        val s = paired()
        s.setCharging(true)
        s.setScreenOn(false)
        advanceUntilIdle()
        assertEquals(SessionState.Connected("MacBook Pro"), s.state.value)
        s.setCharging(false)
        advanceUntilIdle()
        assertEquals(SessionState.Idle("MacBook Pro"), s.state.value)
    }

    @Test fun d0005_aRestartWithTheScreenOffStaysIdle() = runTest {
        paired()
        val logins = relay.logins.size
        val restarted = session()
        restarted.setScreenOn(false)
        advanceTimeBy(61_000)
        restarted.start()
        advanceUntilIdle()
        assertEquals(SessionState.Idle("MacBook Pro"), restarted.state.value)
        assertEquals(logins, relay.logins.size)
    }

    @Test fun codesDontMatchStopsPairingAndTrustsNothing() = runTest {
        relay.withholdAccept = true // the Mac shows its code and waits for the user
        val s = session()
        s.pair(qr)
        advanceUntilIdle()
        assertTrue((s.state.value as SessionState.Pairing).safetyCode != null)
        s.abortPairing()
        advanceUntilIdle()
        assertTrue((s.state.value as SessionState.Failed).reason.startsWith("Nothing was paired"))
        // SR-11-16: checked on the saved state whatever was written, and on a restart
        if (store.blob != null) assertTrue("no Mac saved", SeamDevice.restore(store.blob!!, ByteArray(32) { 3 }).peers().none { it.confirmed })
        val restarted = session()
        restarted.start()
        advanceUntilIdle()
        assertEquals(SessionState.Unpaired, restarted.state.value)
    }

    @Test fun sr_11_07_codesDontMatchJustAfterTheMacAcceptedUndoesIt() = runTest {
        val s = session(now = { 1_000_000L }) // the tap lands the moment the accept does
        s.pair(qr)
        advanceUntilIdle()
        // the Mac's accept arrived first
        assertTrue("paired", SeamDevice.restore(store.blob!!, ByteArray(32) { 3 }).peers().any { it.confirmed })
        s.abortPairing()
        advanceUntilIdle()
        assertEquals(SessionState.Unpaired, s.state.value)
        assertTrue(s.notice.value!!.startsWith("Unpaired: your Mac confirmed"))
        assertTrue("nothing trusted", SeamDevice.restore(store.blob!!, ByteArray(32) { 3 }).peers().isEmpty())
    }

    @Test fun sr_11_07_aLateCodesDontMatchTapChangesNothing() = runTest {
        var clock = 1_000_000L
        val s = session(now = { clock })
        s.pair(qr)
        advanceUntilIdle()
        clock += 5_001 // past ABORT_GRACE_MS
        s.abortPairing()
        advanceUntilIdle()
        assertTrue(s.state.value !is SessionState.Unpaired && s.state.value !is SessionState.Failed)
        assertTrue("still paired", SeamDevice.restore(store.blob!!, ByteArray(32) { 3 }).peers().any { it.confirmed })
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
