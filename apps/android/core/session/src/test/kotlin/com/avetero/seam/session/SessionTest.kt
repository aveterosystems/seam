// SPDX-License-Identifier: AGPL-3.0-only
package com.avetero.seam.session

import com.avetero.seam.crypto.LanChannel
import com.avetero.seam.crypto.PeerPath
import com.avetero.seam.crypto.SeamDevice
import com.avetero.seam.crypto.SeamException
import com.avetero.seam.protocol.v1.BundleResult
import com.avetero.seam.protocol.v1.Challenge
import com.avetero.seam.protocol.v1.ClientFrame
import com.avetero.seam.protocol.v1.Control
import com.avetero.seam.protocol.v1.Delivered
import com.avetero.seam.protocol.v1.Envelope
import com.avetero.seam.protocol.v1.LoginResult
import com.avetero.seam.protocol.v1.Notification
import com.avetero.seam.protocol.v1.PairAccept
import com.avetero.seam.protocol.v1.Payload
import com.avetero.seam.protocol.v1.RelayError
import com.avetero.seam.protocol.v1.ServerFrame
import com.avetero.seam.protocol.v1.Status
import com.avetero.seam.protocol.v1.Hello
import com.avetero.seam.protocol.v1.Transport
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
    /** Every envelope sent through it, as sent. */
    val envelopes = mutableListOf<Envelope>()
    /** Hellos are kept apart: every connection sends one. */
    val hellos = mutableListOf<Payload>()
    val sealedTtls = mutableListOf<Int>()
    val removed = mutableListOf<ByteArray>()
    var delivered = 0
    var refuseToken = false
    var withholdAccept = false
    var refuseNextLogin: String? = null
    var throwOnConnect = false
    var listener: LinkListener? = null
    val urls = mutableListOf<String>()

    override fun connect(url: String, listener: LinkListener): Link {
        if (throwOnConnect) throw IllegalArgumentException("bad url")
        urls += url
        this.listener = listener
        reply(ServerFrame.newBuilder().setChallenge(Challenge.newBuilder().setNonce(ByteString.copyFrom(ByteArray(32) { 1 }))))
        return object : Link {
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
                envelopes += f.send
                val env = f.send.toByteArray()
                try {
                    val p = Payload.parseFrom(mac.open(env).payload)
                    if (p.hasControl() && p.control.hasHello()) hellos += p else macReceived += p
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
    fun listenerFrame(f: ServerFrame) = listener!!.onFrame(f.toByteArray())
    fun drop() = listener!!.onClosed("dropped")
    fun error(code: String) = reply(ServerFrame.newBuilder().setError(RelayError.newBuilder().setCode(code)))
    private fun reply(f: ServerFrame.Builder) = listener!!.onFrame(f.build().toByteArray())
}

/** Macs on the "network": each address answers with a real core LanChannel, as the Mac app does. */
private class FakeLan(var macs: Map<LanAddress, SeamDevice>) : Lan {
    var found: ((LanAddress) -> Unit)? = null
    var discovering = false
    var discoveries = 0
    /** Re-announced to every new discovery, as NSD does. */
    var adverts = listOf<LanAddress>()
    var opened = 0
    var open: MacEnd? = null
    val macReceived = mutableListOf<Payload>()
    val hellos = mutableListOf<Payload>()
    var delivered = 0
    /** This many next connections drop before answering (a busy Mac, or one made to drop us). */
    var dropNext = 0
    /** This many next connections get one byte of the Mac's handshake flipped (a forged packet). */
    var corruptNext = 0

    override fun discover(found: (LanAddress) -> Unit, lost: (LanAddress) -> Unit) {
        discovering = true
        discoveries++
        this.found = found
        adverts.forEach(found)
    }
    override fun stopDiscovery() {
        discovering = false
    }
    fun announce(vararg a: LanAddress) = a.forEach { found!!(it) }

    /** Acks each envelope it handles (ServerFrame.delivered), as the Mac does. */
    var acks = true
    /** Envelopes that "never arrived": recorded, not opened, not acked (lost with a dying link). */
    var lose = false
    val lost = mutableListOf<Envelope>()
    /** Every envelope that arrived, as sent (duplicates included). */
    val envelopes = mutableListOf<Envelope>()

    inner class MacEnd(val mac: SeamDevice, val listener: LinkListener) : Link {
        val ch = LanChannel.responder(mac)
        var closed = false
        var drops = false
        var corrupts = false
        override fun send(frame: ByteArray): Boolean {
            if (closed) return false
            if (drops) return true.also { closed = true; listener.onClosed("dropped") }
            val out = try {
                ch.receive(frame)
            } catch (_: Exception) {
                closed = true
                listener.onClosed("refused")
                return true
            }
            if (out.send.isNotEmpty()) {
                if (corrupts) out.send[out.send.size - 1] = (out.send.last().toInt() xor 1).toByte()
                listener.onFrame(out.send)
            }
            for (f in out.frames) {
                val c = ClientFrame.parseFrom(f)
                when (c.frameCase) {
                    ClientFrame.FrameCase.SEND -> {
                        if (lose) {
                            lost += c.send
                            continue
                        }
                        envelopes += c.send
                        val opened = try {
                            mac.open(c.send.toByteArray())
                        } catch (_: SeamException.Duplicate) {
                            if (acks) delivered(c.send.msgId) // the Mac acks a duplicate too, and drops it
                            continue
                        }
                        val p = Payload.parseFrom(opened.payload)
                        if (p.hasControl() && p.control.hasHello()) hellos += p else macReceived += p
                        if (acks) delivered(c.send.msgId)
                    }
                    ClientFrame.FrameCase.DELIVERED -> delivered++
                    else -> Unit
                }
            }
            return true
        }
        override fun close() {
            closed = true
        }
        fun deliverToPhone(env: ByteArray) =
            listener.onFrame(ch.send(ServerFrame.newBuilder().setDeliver(Envelope.parseFrom(env)).build().toByteArray()))
        fun drop() = listener.onClosed("dropped")
        fun delivered(id: ByteString) =
            listener.onFrame(ch.send(ServerFrame.newBuilder().setDelivered(Delivered.newBuilder().setMsgId(id)).build().toByteArray()))
    }

    override fun connect(address: LanAddress, listener: LinkListener): Link {
        opened++
        return MacEnd(macs.getValue(address), listener).also {
            open = it
            if (dropNext > 0) { dropNext--; it.drops = true }
            if (corruptNext > 0) { corruptNext--; it.corrupts = true }
        }
    }
}

private fun qrFor(mac: SeamDevice, name: String = "MacBook Pro") =
    PairingQr("ws://relay.test", ByteArray(16) { 9 }, mac.startPairing().token, mac.identityKey(), mac.relayKey(), name)

@OptIn(ExperimentalCoroutinesApi::class)
class SessionTest {
    private val mac = SeamDevice.generate()
    private val qr = qrFor(mac)
    private val store = MemoryStore()
    private val relay = FakeRelay(mac)
    private val pending = MemoryPendingStore()

    private fun TestScope.session(
        connector: RelayConnector = relay,
        wait: suspend (Long) -> Unit = {},
        trace: ((String) -> Unit)? = null,
        now: () -> Long = { testScheduler.currentTime },
        lan: Lan? = null,
        settings: SettingsStore = MemorySettingsStore(),
    ) = Session(
        store, connector, "Galaxy S25 Ultra", "SM-S938B",
        TestScope(StandardTestDispatcher(testScheduler)), wait = wait, now = now, trace = trace, lan = lan, settingsStore = settings, pendingStore = pending,
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
        val saved = SeamDevice.restore(store.blob!!, ByteArray(32) { 3 })
        assertTrue("nothing saved still pairs", saved.peers().isEmpty())
        assertNull("nor keeps the old relay group", saved.appData())
        val again = session()
        again.start()
        advanceUntilIdle()
        assertEquals("a restart stays unpaired", SessionState.Unpaired, again.state.value)
    }

    @Test fun forgettingTheMacResetsEveryFeature() = runTest {
        val s = paired()
        var resets = 0
        s.register(object : Feature {
            override fun reset() { resets++ }
        })
        s.forgetMac()
        advanceUntilIdle()
        assertEquals(1, resets)
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

    // Rule 8: Android suspends the CPU seconds after the screen goes off, and the session's idle
    // timer doesn't run while it's suspended. The session says when the CPU must be awake so the
    // idle check runs on time; the service only carries it out.
    @Test fun rule8_anIdleCheckDueWithinAMinuteHoldsTheCpuUntilJustAfterIt() = runTest {
        val s = paired()
        s.setScreenOn(false)
        advanceUntilIdle()
        s.send(clip("copied with the screen off")) // connects, sends, then a one-minute window
        runCurrent()
        val hold = s.cpu.value as CpuPlan.Hold
        assertTrue("until just after the window, never longer: ${hold.until - testScheduler.currentTime}", hold.until - testScheduler.currentTime in 1..Wakefulness.CPU_HOLD_MS)
        advanceUntilIdle()
        assertEquals(SessionState.Idle("MacBook Pro"), s.state.value)
        assertEquals("closed: nothing to hold", CpuPlan.None, s.cpu.value)
    }

    @Test fun rule8_aReplyWindowLetsTheCpuSleepAndWakesItNearTheEnd() = runTest {
        val s = paired()
        s.setScreenOn(false)
        advanceUntilIdle()
        s.send(Payload.newBuilder().setNotification(Notification.newBuilder().setKey("k").setCanReply(true)).build())
        runCurrent()
        val end = testScheduler.currentTime + Wakefulness.REPLY_WINDOW_MS
        val wake = s.cpu.value as CpuPlan.WakeAt
        assertTrue("no five-minute hold: wake shortly before the end", wake.at in end - Wakefulness.CPU_WAKE_BEFORE_MS - 2_000..end - Wakefulness.CPU_WAKE_BEFORE_MS + 2_000)
        // The alarm fires: from here the check is due within a minute, so hold until it runs
        advanceTimeBy(wake.at - testScheduler.currentTime)
        s.checkIdle()
        runCurrent()
        assertTrue(s.cpu.value is CpuPlan.Hold)
        advanceUntilIdle()
        assertEquals(SessionState.Idle("MacBook Pro"), s.state.value)
        assertEquals(CpuPlan.None, s.cpu.value)
    }

    @Test fun rule8_noCpuHoldWhileTheScreenIsOnOrCharging() = runTest {
        val s = paired()
        runCurrent()
        assertEquals("screen on", CpuPlan.None, s.cpu.value)
        s.setScreenOn(false)
        s.setCharging(true)
        runCurrent()
        assertEquals("charging", CpuPlan.None, s.cpu.value)
    }

    // Pause: only the phone pauses and resumes. Paused, it tells the Mac once, then makes no
    // connections at all (no relay, no Wi‑Fi search) and captures nothing, until it ends or resumes.
    /** The pause each Hello told the Mac, in order (the pause rides on the Hello, SR-32-01). */
    private fun pausesSent() = relay.hellos.map { it.control.hello.pausedUntilMs }

    @Test fun pausingTellsTheMacThenGoesSilent() = runTest {
        val s = paired()
        val until = testScheduler.currentTime + 3_600_000
        s.pause(until)
        advanceUntilIdle()
        assertEquals("the Mac is told when it ends", until, pausesSent().last())
        assertEquals(SessionState.Idle("MacBook Pro"), s.state.value)
        assertEquals(until, s.settings.value.pausedUntil)
        val logins = relay.logins.size
        s.setScreenOn(false)
        s.setScreenOn(true) // picking the phone up doesn't connect while paused
        s.setCharging(true) // nor does charging
        s.send(clip("copied while paused"))
        advanceTimeBy(30 * 60_000L)
        assertEquals("no connection at all", logins, relay.logins.size)
        assertTrue("nothing captured", relay.macReceived.none { it.hasClipboard() && it.clipboard.text == "copied while paused" })
        assertEquals(CpuPlan.None, s.cpu.value)
    }

    @Test fun pausingWhileIdleConnectsOnceJustToTellTheMac() = runTest {
        val s = paired()
        s.setScreenOn(false)
        advanceUntilIdle()
        val logins = relay.logins.size
        s.pause(Long.MAX_VALUE)
        advanceUntilIdle()
        assertEquals(logins + 1, relay.logins.size)
        assertEquals(Long.MAX_VALUE, pausesSent().last())
        assertEquals("and closes again straight away", SessionState.Idle("MacBook Pro"), s.state.value)
    }

    @Test fun aTimedPauseEndsByItselfWithNoMessage() = runTest {
        val s = paired()
        s.pause(testScheduler.currentTime + 60_000)
        advanceUntilIdle()
        advanceTimeBy(61_000)
        s.setScreenOn(false)
        s.setScreenOn(true)
        advanceUntilIdle()
        assertEquals(SessionState.Connected("MacBook Pro"), s.state.value)
        assertTrue("the Mac ends it at the same time by the clock", pausesSent().none { it > testScheduler.currentTime && it != Long.MAX_VALUE })
        s.send(clip("after the pause"))
        advanceUntilIdle()
        assertTrue(relay.macReceived.any { it.hasClipboard() && it.clipboard.text == "after the pause" })
    }

    @Test fun resumingTellsTheMacAndReconnects() = runTest {
        val s = paired()
        s.pause(Long.MAX_VALUE)
        advanceUntilIdle()
        s.resume()
        advanceUntilIdle()
        assertEquals(listOf(Long.MAX_VALUE, 0L), pausesSent().dropWhile { it == 0L }.take(2))
        assertEquals(0L, s.settings.value.pausedUntil)
        assertEquals(SessionState.Connected("MacBook Pro"), s.state.value)
        s.send(clip("after resume"))
        advanceUntilIdle()
        assertTrue(relay.macReceived.any { it.hasClipboard() && it.clipboard.text == "after resume" })
    }

    @Test fun aPauseSurvivesARestart() = runTest {
        val settings = MemorySettingsStore()
        val s = session(settings = settings).also { it.pair(qr); advanceUntilIdle() }
        s.pause(Long.MAX_VALUE)
        advanceUntilIdle()
        val logins = relay.logins.size
        val restarted = session(settings = settings)
        restarted.start()
        restarted.setScreenOn(true)
        advanceUntilIdle()
        assertEquals(Long.MAX_VALUE, restarted.settings.value.pausedUntil)
        assertEquals("still silent", logins, relay.logins.size)
    }

    @Test fun onSameWifiThePauseWaitsForTheMacsAckThenNeverReconnects() = runTest {
        val lan = FakeLan(mapOf(home to mac))
        val s = pairedWithLan(lan)
        lan.announce(home)
        advanceUntilIdle()
        s.pause(Long.MAX_VALUE)
        advanceUntilIdle()
        assertTrue(lan.hellos.any { it.control.hello.pausedUntilMs == Long.MAX_VALUE })
        assertEquals(SessionState.Idle("MacBook Pro"), s.state.value)
        val (opened, logins) = lan.opened to relay.logins.size
        s.setScreenOn(false)
        s.setScreenOn(true)
        lan.announce(home)
        advanceTimeBy(10 * 60_000L)
        assertEquals("acked, so nothing to resend: no link opened again", opened, lan.opened)
        assertEquals(logins, relay.logins.size)
    }

    @Test fun thePauseNoticeIsntCutOffBeforeTheMacConfirmsIt() = runTest {
        val lan = FakeLan(mapOf(home to mac))
        val s = pairedWithLan(lan)
        lan.announce(home)
        advanceUntilIdle()
        lan.acks = false // the Mac's confirmation is still on its way
        s.pause(Long.MAX_VALUE)
        advanceTimeBy(5_000)
        assertTrue("still open for the ack", lan.open?.closed == false)
        advanceTimeBy(2 * 60_000L)
        assertEquals("but not for ever", SessionState.Idle("MacBook Pro"), s.state.value)
    }

    @Test fun sr_32_02_forgettingTheMacEndsThePause() = runTest {
        val s = paired()
        s.pause(Long.MAX_VALUE)
        advanceUntilIdle()
        s.forgetMac()
        advanceUntilIdle()
        assertEquals("a new Mac never inherits a pause", 0L, s.settings.value.pausedUntil)
    }

    @Test fun aPauseInThePastIsIgnored() = runTest {
        val s = paired()
        s.pause(testScheduler.currentTime - 1)
        advanceUntilIdle()
        assertTrue(pausesSent().all { it == 0L })
        assertEquals(0L, s.settings.value.pausedUntil)
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
            else object : Link {
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

    // MARK: pairing outcome (SR-11-06: the setup screens follow only a new Mac)

    @Test fun sr_11_06_pairingAMacReportsANewMac() = runTest {
        val s = session()
        assertNull(s.pairingOutcome.value)
        s.pair(qr)
        advanceUntilIdle()
        assertEquals(PairingOutcome.NewMac, s.pairingOutcome.value)
        s.pair(qrFor(SeamDevice.generate().also { relay.mac = it }, "Mac mini")) // replacing it is a new Mac too
        advanceUntilIdle()
        assertEquals(SessionState.Connected("Mac mini"), s.state.value)
        assertEquals(PairingOutcome.NewMac, s.pairingOutcome.value)
    }

    @Test fun sr_11_06_anAcceptThatCantBeSavedIsNotANewMac() = runTest {
        relay.withholdAccept = true
        val s = session()
        s.pair(qr)
        advanceUntilIdle()
        store.failSaves = true
        val phoneId = mac.peers().single().id
        mac.confirmPairing(phoneId)
        val accept = Payload.newBuilder().setControl(Control.newBuilder().setPairAccept(PairAccept.getDefaultInstance())).build()
        relay.deliverToPhone(mac.seal(phoneId, accept.toByteArray(), 0u))
        advanceUntilIdle()
        assertTrue(s.state.value is SessionState.Failed)
        assertTrue("no setup screens over a failure", s.pairingOutcome.value != PairingOutcome.NewMac)
    }

    @Test fun sr_11_06_aFailedPairingWhilePairedReportsStillOnThePreviousMac() = runTest {
        val s = paired()
        relay.refuseToken = true
        s.pair(qrFor(SeamDevice.generate(), "Someone else's Mac"))
        advanceUntilIdle()
        assertEquals(PairingOutcome.StillOnPreviousMac, s.pairingOutcome.value)
    }

    @Test fun sr_11_06_aFailedFirstPairingReportsNoMac() = runTest {
        relay.refuseToken = true
        val s = session()
        s.pair(qr)
        advanceUntilIdle()
        assertEquals(PairingOutcome.NoMac, s.pairingOutcome.value)
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

    // MARK: same Wi‑Fi (protocol/README.md, decision 0006)

    private val home = LanAddress("192.168.1.20", 50001)
    private val neighbour = LanAddress("192.168.1.30", 50002)

    private fun TestScope.pairedWithLan(lan: FakeLan, settings: SettingsStore = MemorySettingsStore()): Session =
        session(lan = lan, settings = settings).also {
            it.pair(qr)
            advanceUntilIdle()
        }

    /** Someone else's Mac, paired with their own phone. */
    private fun strangerMac(): SeamDevice {
        val other = SeamDevice.generate()
        val theirPhone = SeamDevice.generate()
        val offer = other.startPairing()
        val start = theirPhone.pairWith(other.identityKey(), other.relayKey(), "Theirs", other.prekeyBundles(1u).first(), offer.token, "Phone", "X")
        other.confirmPairing(other.acceptPairing(start.envelope).peerId)
        return other
    }

    private var macSeq = 0L

    private fun macHello(vararg allowed: Transport, seq: Long = ++macSeq): ByteArray {
        val hello = Payload.newBuilder().setControl(
            Control.newBuilder().setHello(Hello.newBuilder().setSeq(seq).addAllAllowedTransports(allowed.toList())),
        ).build()
        return mac.seal(mac.peers().single().id, hello.toByteArray(), 0u)
    }

    @Test fun onTheSameWifiMessagesGoDirectAndTheRelayStaysAsTheMailbox() = runTest {
        val lan = FakeLan(mapOf(home to mac))
        val s = pairedWithLan(lan)
        assertTrue("looks for the Mac once paired", lan.discovering)
        lan.announce(home)
        advanceUntilIdle()
        assertEquals(Transport.TRANSPORT_LAN, s.route.value)
        assertTrue("stops multicasting once connected", !lan.discovering)
        s.send(status(70))
        advanceUntilIdle()
        assertEquals(70, lan.macReceived.single().status.batteryPct)
        assertTrue("not through the relay", relay.macReceived.none { it.hasStatus() })
        assertTrue("the relay connection stays: it's the mailbox", relay.listener != null && relay.logins.isNotEmpty())
        assertEquals(listOf(Transport.TRANSPORT_LAN, Transport.TRANSPORT_RELAY), lan.hellos.last().control.hello.allowedTransportsList)
    }

    @Test fun someoneElsesMacOnTheNetworkIsRefusedAndOursIsFound() = runTest {
        val lan = FakeLan(mapOf(neighbour to strangerMac(), home to mac))
        val s = pairedWithLan(lan)
        lan.announce(neighbour, home)
        advanceUntilIdle()
        assertEquals(2, lan.opened)
        assertEquals(Transport.TRANSPORT_LAN, s.route.value)
    }

    @Test fun aDirectDeliveryIsAckedDirectly() = runTest {
        val lan = FakeLan(mapOf(home to mac))
        val s = pairedWithLan(lan)
        val received = mutableListOf<Payload>()
        s.register(object : Feature {
            override val handles = setOf(Payload.BodyCase.STATUS)
            override fun receive(payload: Payload, fromPeer: String) { received += payload }
        })
        lan.announce(home)
        advanceUntilIdle()
        val relayAcks = relay.delivered
        lan.open!!.deliverToPhone(mac.seal(mac.peers().single().id, status(12).toByteArray(), 0u))
        advanceUntilIdle()
        assertEquals(12, received.single().status.batteryPct)
        assertEquals(1, lan.delivered)
        assertEquals(relayAcks, relay.delivered)
    }

    @Test fun whenTheDirectLinkDropsMessagesGoThroughTheRelay() = runTest {
        val lan = FakeLan(mapOf(home to mac))
        val s = pairedWithLan(lan)
        lan.announce(home)
        advanceUntilIdle()
        lan.open!!.drop()
        runCurrent()
        assertEquals(SessionState.Connected("MacBook Pro"), s.state.value)
        assertEquals(Transport.TRANSPORT_RELAY, s.route.value)
        advanceTimeBy(1_300)
        runCurrent()
        assertTrue("looks for the Mac again, after a short wait", lan.discovering)
        s.send(status(33))
        advanceUntilIdle()
        assertEquals(33, relay.macReceived.single().status.batteryPct)
    }

    @Test fun relayOffNeverUsesTheRelayAndMessagesWaitForWifi() = runTest {
        val lan = FakeLan(mapOf(home to mac))
        val s = pairedWithLan(lan)
        s.setSwitches(wifi = true, relay = false)
        advanceUntilIdle()
        assertEquals("the Mac was told first, over the relay", listOf(Transport.TRANSPORT_LAN), relay.hellos.last().control.hello.allowedTransportsList)
        assertEquals(null, s.route.value)
        val logins = relay.logins.size
        s.send(status(5))
        advanceUntilIdle()
        assertTrue("waits rather than using the relay", relay.macReceived.none { it.hasStatus() })
        assertEquals(logins, relay.logins.size)
        lan.announce(home)
        advanceUntilIdle()
        assertEquals(5, lan.macReceived.single().status.batteryPct)
    }

    @Test fun theLastSwitchCantBeTurnedOff() = runTest {
        val s = pairedWithLan(FakeLan(emptyMap()))
        s.setSwitches(wifi = false, relay = true)
        s.setSwitches(wifi = false, relay = false)
        advanceUntilIdle()
        assertEquals(false to true, s.settings.value.wifi to s.settings.value.relay)
    }

    @Test fun onlyPathsTheMacAllowsAreUsed() = runTest {
        val lan = FakeLan(mapOf(home to mac))
        val settings = MemorySettingsStore()
        val s = pairedWithLan(lan, settings)
        lan.announce(home)
        advanceUntilIdle()
        relay.deliverToPhone(macHello(Transport.TRANSPORT_RELAY)) // the Mac turned Same Wi‑Fi off
        advanceUntilIdle()
        assertEquals(setOf(Transport.TRANSPORT_RELAY), s.settings.value.macAllows)
        assertEquals("the core's answer for the status line", setOf(PeerPath.RELAY), s.settings.value.allowed)
        assertTrue("our own switch still allows it: the link stays, for the Mac's next Hello", !lan.open!!.closed)
        assertEquals(Transport.TRANSPORT_RELAY, s.route.value)
        s.send(status(9))
        advanceUntilIdle()
        assertEquals(9, relay.macReceived.single().status.batteryPct)
        assertTrue(lan.macReceived.isEmpty())
    }

    @Test fun aStaleMacListNeverLocksThePhoneOut() = runTest {
        // On a device: the phone remembered the Mac's [Same Wi‑Fi]; the Mac then went relay-only
        val s = pairedWithLan(FakeLan(emptyMap())) // no Mac on this network
        relay.deliverToPhone(macHello(Transport.TRANSPORT_LAN))
        advanceUntilIdle()
        assertTrue("the relay stays open: it's our own switch that counts", relay.listener != null)
        s.send(clip("waiting"))
        advanceUntilIdle()
        assertTrue("but nothing is sent where the Mac said no", relay.macReceived.none { it.hasClipboard() })
        // After a restart the phone still connects to the relay, despite the stale list
        val logins = relay.logins.size
        val restarted = session(lan = FakeLan(emptyMap()))
        restarted.start()
        advanceUntilIdle()
        assertTrue(relay.logins.size > logins)
        restarted.send(clip("waiting"))
        advanceUntilIdle()
        relay.deliverToPhone(macHello(Transport.TRANSPORT_RELAY)) // the Mac's new choice gets through
        advanceUntilIdle()
        assertEquals(Transport.TRANSPORT_RELAY, restarted.route.value)
        assertEquals("waiting", relay.macReceived.last { it.hasClipboard() }.clipboard.text)
    }

    @Test fun aHelloUsesOurOwnPathsEvenWhereTheMacSaidNo() = runTest {
        val s = pairedWithLan(FakeLan(emptyMap()))
        relay.deliverToPhone(macHello(Transport.TRANSPORT_LAN)) // the Mac excludes the relay
        advanceUntilIdle()
        val hellos = relay.hellos.size
        s.setSwitches(wifi = false, relay = true) // now no path both ends allow
        advanceUntilIdle()
        assertTrue("the Hello still went, through the relay", relay.hellos.size > hellos)
        assertEquals(listOf(Transport.TRANSPORT_RELAY), relay.hellos.last().control.hello.allowedTransportsList)
        s.send(clip("never"))
        s.send(status(3))
        advanceUntilIdle()
        assertTrue("a normal payload never does", relay.macReceived.none { it.hasClipboard() || it.hasStatus() })
        assertEquals(null, s.route.value)
    }

    @Test fun noSharedPathMeansNothingIsSent() = runTest {
        val lan = FakeLan(mapOf(home to mac))
        val s = pairedWithLan(lan)
        relay.deliverToPhone(macHello(Transport.TRANSPORT_LAN))
        advanceUntilIdle()
        s.setSwitches(wifi = false, relay = true)
        advanceUntilIdle()
        s.send(status(1))
        advanceUntilIdle()
        assertEquals(null, s.route.value)
        assertTrue(relay.macReceived.none { it.hasStatus() })
    }

    @Test fun sleepClosesBothPaths() = runTest {
        val lan = FakeLan(mapOf(home to mac))
        val s = pairedWithLan(lan)
        lan.announce(home)
        advanceUntilIdle()
        s.setScreenOn(false)
        advanceTimeBy(61_000)
        runCurrent()
        assertEquals(SessionState.Idle("MacBook Pro"), s.state.value)
        assertTrue(lan.open!!.closed)
        assertTrue(!lan.discovering)
        assertEquals(null, s.route.value)
    }

    @Test fun pairingNeedsTheRelayForNow() = runTest {
        val s = session(settings = MemorySettingsStore(ConnectionSettings(wifi = true, relay = false)))
        s.pair(qr)
        advanceUntilIdle()
        assertEquals(SessionState.Unpaired, s.state.value)
        assertTrue(s.notice.value!!.contains("Relay"))
        assertTrue(relay.logins.isEmpty())
    }

    @Test fun aNewMacStartsWithNothingHeard() = runTest {
        val s = pairedWithLan(FakeLan(emptyMap()))
        relay.deliverToPhone(macHello(Transport.TRANSPORT_LAN))
        advanceUntilIdle()
        assertEquals(setOf(Transport.TRANSPORT_LAN), s.settings.value.macAllows)
        s.pair(qrFor(SeamDevice.generate().also { relay.mac = it }, "Mac mini"))
        advanceUntilIdle()
        assertEquals(SessionState.Connected("Mac mini"), s.state.value)
        assertEquals(null, s.settings.value.macAllows)
        assertEquals(0L, s.settings.value.macHelloSeq)
    }

    @Test fun aDelayedOldHelloNeverUndoesANewerOne() = runTest {
        val s = pairedWithLan(FakeLan(emptyMap()))
        val both = setOf(Transport.TRANSPORT_LAN, Transport.TRANSPORT_RELAY)
        relay.deliverToPhone(macHello(Transport.TRANSPORT_LAN, Transport.TRANSPORT_RELAY, seq = 5))
        advanceUntilIdle()
        // The relay held older ones back and released them late; a sender that never counts sends 0
        for (seq in listOf(4L, 5L, 0L)) {
            relay.deliverToPhone(macHello(Transport.TRANSPORT_RELAY, seq = seq))
            advanceUntilIdle()
            assertEquals("seq $seq ignored", both, s.settings.value.macAllows)
        }
        assertEquals(5L, s.settings.value.macHelloSeq)
        relay.deliverToPhone(macHello(Transport.TRANSPORT_RELAY, seq = 6))
        advanceUntilIdle()
        assertEquals(setOf(Transport.TRANSPORT_RELAY), s.settings.value.macAllows)
    }

    @Test fun aHelloNamingATransportThisBuildDoesntKnowIsSafe() = runTest {
        val s = pairedWithLan(FakeLan(emptyMap()))
        val hello = Payload.newBuilder().setControl(
            Control.newBuilder().setHello(
                Hello.newBuilder().setSeq(1).addAllowedTransports(Transport.TRANSPORT_RELAY).addAllowedTransportsValue(9),
            ),
        ).build()
        relay.deliverToPhone(mac.seal(mac.peers().single().id, hello.toByteArray(), 0u))
        advanceUntilIdle()
        assertEquals("a newer Mac's transport is dropped, never stored", setOf(Transport.TRANSPORT_RELAY), s.settings.value.macAllows)
        assertTrue(s.state.value.toString(), s.state.value !is SessionState.Failed)
    }

    @Test fun anOlderMacsEmptyListAllowsEverythingThisPhoneAllows() = runTest {
        // Versions from before the switches send no list: the same rule as the Mac (spec 01)
        val lan = FakeLan(mapOf(home to mac))
        val s = pairedWithLan(lan)
        relay.deliverToPhone(macHello())
        advanceUntilIdle()
        assertNull(s.settings.value.macAllows)
        assertEquals(Transport.TRANSPORT_RELAY, s.route.value)
        lan.announce(home)
        advanceUntilIdle()
        assertEquals(Transport.TRANSPORT_LAN, s.route.value)
    }

    @Test fun aListOfOnlyTransportsThisBuildCantUseMeansNothingInCommon() = runTest {
        val s = pairedWithLan(FakeLan(emptyMap()))
        val hello = Payload.newBuilder().setControl(
            Control.newBuilder().setHello(Hello.newBuilder().setSeq(1).addAllowedTransportsValue(9)),
        ).build()
        relay.deliverToPhone(mac.seal(mac.peers().single().id, hello.toByteArray(), 0u))
        advanceUntilIdle()
        assertEquals(emptySet<Transport>(), s.settings.value.macAllows)
        assertEquals(null, s.route.value)
        s.send(status(4))
        advanceUntilIdle()
        assertTrue(relay.macReceived.none { it.hasStatus() })
    }

    @Test fun whereOurMacLastProvedItselfIsNeverSkippedForLong() = runTest {
        // NN is anonymous: an attacker who answers first can make our Mac's address look foreign
        val lan = FakeLan(mapOf(home to mac))
        val s = pairedWithLan(lan)
        lan.adverts = listOf(home)
        lan.announce(home)
        advanceUntilIdle()
        assertEquals(Transport.TRANSPORT_LAN, s.route.value)
        lan.macs = mapOf(home to strangerMac()) // the attacker wins the next race at the same address
        lan.open!!.drop()
        advanceTimeBy(2_000)
        runCurrent()
        val tried = lan.opened
        assertTrue("reconnected and was refused", tried >= 2)
        lan.macs = mapOf(home to mac)
        advanceTimeBy(40_000)
        runCurrent()
        advanceUntilIdle()
        assertEquals(Transport.TRANSPORT_LAN, s.route.value)
    }

    @Test fun eachHelloThePhoneSendsCountsUp() = runTest {
        val settings = MemorySettingsStore()
        val s = pairedWithLan(FakeLan(emptyMap()), settings)
        s.setSwitches(wifi = false, relay = true)
        advanceUntilIdle()
        s.setSwitches(wifi = true, relay = true)
        advanceUntilIdle()
        val seqs = relay.hellos.map { it.control.hello.seq }
        assertTrue(seqs.toString(), seqs.size >= 3 && seqs.first() == 1L && seqs.zipWithNext().all { (a, b) -> b > a })
        assertEquals(seqs.last(), s.settings.value.helloSeq)
        s.forgetMac()
        advanceUntilIdle()
        assertEquals(0L, s.settings.value.helloSeq)
        assertEquals(0L, s.settings.value.macHelloSeq)
    }

    @Test fun aRestartKeepsTheHelloCountersAndTheMacsChoice() = runTest {
        val s = pairedWithLan(FakeLan(emptyMap()))
        relay.deliverToPhone(macHello(Transport.TRANSPORT_RELAY, seq = 7))
        advanceUntilIdle()
        val sent = s.settings.value.helloSeq
        assertTrue(sent >= 1)
        val restarted = session(settings = MemorySettingsStore()) // nothing about the Mac in preferences
        assertEquals(setOf(Transport.TRANSPORT_RELAY), restarted.settings.value.macAllows)
        assertEquals(7L, restarted.settings.value.macHelloSeq)
        assertEquals(sent, restarted.settings.value.helloSeq)
        restarted.start()
        advanceUntilIdle()
        assertEquals("never reuses a counter", sent + 1, relay.hellos.last().control.hello.seq)
    }

    @Test fun oldPreferencesMoveIntoTheSealedState() = runTest {
        paired()
        val old = MemorySettingsStore(ConnectionSettings(wifi = true, relay = true, macAllows = setOf(Transport.TRANSPORT_LAN), macHelloSeq = 4, helloSeq = 9))
        val saves = store.saves.size
        val s = session(settings = old)
        assertEquals(setOf(Transport.TRANSPORT_LAN), s.settings.value.macAllows)
        assertEquals(4L, s.settings.value.macHelloSeq)
        assertEquals(9L, s.settings.value.helloSeq)
        assertTrue("saved sealed", store.saves.size > saves)
        assertEquals("and gone from preferences", ConnectionSettings(), old.load())
        val again = session(settings = old)
        assertEquals(9L, again.settings.value.helloSeq)
    }

    @Test fun oldPreferencesWithOnlyAnUnusablePathMeanNothingInCommon() = runTest {
        paired()
        val old = MemorySettingsStore(ConnectionSettings(wifi = true, relay = true, macAllows = setOf(Transport.TRANSPORT_BLUETOOTH), macHelloSeq = 4, helloSeq = 9))
        val s = session(settings = old)
        assertEquals(emptySet<Transport>(), s.settings.value.macAllows)
    }

    /** Connected over Same Wi‑Fi, with the relay up too. */
    private fun TestScope.onWifi(lan: FakeLan, now: () -> Long = { testScheduler.currentTime }): Session =
        session(lan = lan, now = now).also {
            it.pair(qr)
            advanceUntilIdle()
            lan.announce(home)
            advanceUntilIdle()
            assertEquals(Transport.TRANSPORT_LAN, it.route.value)
        }

    @Test fun whatTheMacNeverAckedGoesThroughTheRelayAsItWasSealed() = runTest {
        val lan = FakeLan(mapOf(home to mac))
        val s = onWifi(lan)
        lan.lose = true // the link is dying: what's written now never arrives
        s.send(status(41))
        advanceUntilIdle()
        val sent = lan.lost.single { it.msgId !in relay.envelopes.map(Envelope::getMsgId) }
        val saves = store.saves.size
        lan.open!!.drop()
        runCurrent()
        val again = relay.envelopes.last()
        assertEquals("the same envelope, not a new seal", sent, again)
        assertEquals("nothing sealed (or saved) again", saves, store.saves.size)
        assertEquals(41, relay.macReceived.single { it.hasStatus() }.status.batteryPct)
    }

    @Test fun aFailedNewPairingKeepsWhatTheCurrentMacNeverAcked() = runTest {
        val lan = FakeLan(mapOf(home to mac))
        val s = onWifi(lan)
        lan.lose = true
        s.send(status(47))
        advanceUntilIdle()
        val sent = lan.lost.last()
        relay.refuseToken = true
        s.pair(qrFor(SeamDevice.generate(), "Someone else's Mac"))
        advanceUntilIdle()
        assertEquals(SessionState.Connected("MacBook Pro"), s.state.value)
        assertTrue("still owed to the current Mac", relay.envelopes.any { it == sent } || pending.load().contains(sent))
        assertEquals(47, relay.macReceived.single { it.hasStatus() }.status.batteryPct)
    }

    @Test fun aRestartSendsWhatTheMacNeverAckedUnchanged() = runTest {
        val lan = FakeLan(mapOf(home to mac))
        val s = onWifi(lan)
        lan.lose = true
        s.send(status(44))
        advanceUntilIdle()
        val sent = lan.lost.last()
        assertEquals("recorded before it went", listOf(sent), pending.load())
        // Android kills the process; a new Session starts over the same saved state
        val restarted = session()
        restarted.start()
        advanceUntilIdle()
        assertTrue("the same envelope, sealed once", relay.envelopes.any { it == sent })
        assertEquals(44, relay.macReceived.single { it.hasStatus() }.status.batteryPct)
        assertTrue("through the relay, which queues it: nothing left", pending.load().isEmpty())
    }

    @Test fun forgettingTheMacClearsWhatWasPending() = runTest {
        val lan = FakeLan(mapOf(home to mac))
        val s = onWifi(lan)
        lan.lose = true
        s.send(status(45))
        advanceUntilIdle()
        assertEquals(1, pending.load().size)
        s.forgetMac()
        advanceUntilIdle()
        assertTrue(pending.load().isEmpty())
    }

    @Test fun forgettingOnWifiTellsTheMacBothWays() = runTest {
        val lan = FakeLan(mapOf(home to mac))
        val s = onWifi(lan)
        s.forgetMac()
        advanceUntilIdle()
        assertTrue("through the relay, which queues it for the Mac", relay.macReceived.last().control.hasUnpair())
        val sealed = relay.envelopes.last()
        assertTrue("the same sealed Unpair over the direct link too", lan.envelopes.last() == sealed)
        assertEquals("then it leaves the relay group", 1, relay.removed.size)
        assertEquals(SessionState.Unpaired, s.state.value)
    }

    @Test fun aFarewellNeverUsesAPathThisPhoneTurnedOff() = runTest {
        val lan = FakeLan(mapOf(home to mac))
        val s = onWifi(lan)
        s.setSwitches(wifi = true, relay = false)
        advanceUntilIdle()
        val relayEnvelopes = relay.envelopes.size
        s.forgetMac()
        advanceUntilIdle()
        assertEquals("nothing through the relay", relayEnvelopes, relay.envelopes.size)
        assertTrue(relay.removed.isEmpty())
        assertTrue(lan.macReceived.any { it.hasControl() && it.control.hasUnpair() })
    }

    @Test fun anAckFromTheMacClearsIt() = runTest {
        val lan = FakeLan(mapOf(home to mac))
        val s = onWifi(lan)
        s.send(status(42))
        advanceUntilIdle()
        assertEquals(42, lan.macReceived.single().status.batteryPct)
        assertTrue("acked: gone from the file too", pending.load().isEmpty())
        val before = relay.envelopes.size
        lan.open!!.drop()
        runCurrent()
        assertEquals("acked: nothing to send again", before, relay.envelopes.size)
    }

    @Test fun anAckForAnUnknownIdOrFromTheRelayClearsNothing() = runTest {
        val lan = FakeLan(mapOf(home to mac))
        val s = onWifi(lan)
        lan.lose = true
        s.send(status(43))
        advanceUntilIdle()
        val sent = lan.lost.last()
        lan.open!!.delivered(ByteString.copyFrom(ByteArray(16) { 7 })) // not one we sent
        relay.listenerFrame(ServerFrame.newBuilder().setDelivered(Delivered.newBuilder().setMsgId(sent.msgId)).build()) // the relay never sends this
        advanceUntilIdle()
        lan.open!!.drop()
        runCurrent()
        assertEquals(sent, relay.envelopes.last())
    }

    @Test fun anUnackedEnvelopePastItsTtlIsDroppedNotSentLate() = runTest {
        var offset = 0L
        val lan = FakeLan(mapOf(home to mac))
        val s = onWifi(lan, now = { System.currentTimeMillis() + offset }) // sent_at is wall-clock time
        lan.lose = true
        s.send(Payload.newBuilder().setClipboard(com.avetero.seam.protocol.v1.Clipboard.newBuilder().setText("old")).build())
        advanceUntilIdle()
        offset = 601_000 // a clipboard lives 10 minutes
        lan.open!!.drop()
        runCurrent()
        assertTrue(relay.macReceived.none { it.hasClipboard() })
    }

    @Test fun aCrowdOfFakeMacsCantHideTheRealOne() = runTest {
        val fakes = (1..10).map { LanAddress("192.168.1.${100 + it}", 50000 + it) }
        val stranger = strangerMac()
        val lan = FakeLan(fakes.associateWith { stranger } + (home to mac))
        val s = pairedWithLan(lan)
        lan.adverts = fakes + home // the real Mac is announced last, past the cap
        lan.announce(*lan.adverts.toTypedArray())
        repeat(50) { runCurrent() } // no time passes: only what's already due
        assertEquals("only the first 8 are tried", 8, lan.opened)
        assertEquals("not found yet: still on the relay", Transport.TRANSPORT_RELAY, s.route.value)
        advanceTimeBy(31_000) // every one refused: discovery starts again, without counting them
        runCurrent()
        advanceUntilIdle()
        assertEquals(2, lan.discoveries)
        assertEquals(Transport.TRANSPORT_LAN, s.route.value)
        assertEquals("each fake tried once, then our Mac", 11, lan.opened)
    }

    @Test fun ourMacThatDroppedOneAttemptIsTriedAgainSoon() = runTest {
        // A timeout or drop may be our own Mac (busy, or flooded): only a failed proof skips for long
        val lan = FakeLan(mapOf(home to mac))
        val s = pairedWithLan(lan)
        lan.adverts = listOf(home)
        lan.dropNext = 1
        lan.announce(home)
        runCurrent()
        assertEquals(Transport.TRANSPORT_RELAY, s.route.value)
        advanceTimeBy(31_000)
        runCurrent()
        advanceUntilIdle()
        assertEquals(2, lan.opened)
        assertEquals(Transport.TRANSPORT_LAN, s.route.value)
    }

    @Test fun aForgedPacketOnOurMacsHandshakeOnlyDelaysIt() = runTest {
        // Damaged bytes aren't proof it's someone else's Mac: retried after the short wait, not skipped for 10 minutes
        val lan = FakeLan(mapOf(home to mac))
        val s = pairedWithLan(lan)
        lan.adverts = listOf(home)
        lan.corruptNext = 1
        lan.announce(home)
        runCurrent()
        assertEquals(Transport.TRANSPORT_RELAY, s.route.value)
        advanceTimeBy(31_000)
        runCurrent()
        advanceUntilIdle()
        assertEquals(2, lan.opened)
        assertEquals(Transport.TRANSPORT_LAN, s.route.value)
    }

    @Test fun someoneElsesMacIsSkippedForLong() = runTest {
        val lan = FakeLan(mapOf(neighbour to strangerMac()))
        pairedWithLan(lan)
        lan.adverts = listOf(neighbour)
        lan.announce(neighbour)
        runCurrent()
        advanceTimeBy(5 * 60_000)
        runCurrent()
        assertEquals("a valid proof from another key isn't retried within 10 minutes", 1, lan.opened)
    }

    @Test fun aDirectLinkThatKeepsDroppingBacksOff() = runTest {
        val lan = FakeLan(mapOf(home to mac))
        pairedWithLan(lan)
        // Each drop right after connecting (an attacker resetting TCP): the wait doubles
        for (wait in listOf(1_000L, 2_000L, 4_000L)) {
            lan.announce(home)
            runCurrent()
            lan.open!!.drop()
            runCurrent()
            advanceTimeBy(wait - 1)
            runCurrent()
            assertTrue("still waiting before ${wait}ms", !lan.discovering)
            advanceTimeBy(wait * 2 / 10 + 2) // the jitter is at most 20%
            runCurrent()
            assertTrue("looks again after ${wait}ms", lan.discovering)
        }
        assertEquals(3, lan.opened)
        // A link that stays up 30 s earns the quick reconnect back
        lan.announce(home)
        runCurrent()
        advanceTimeBy(31_000)
        runCurrent()
        lan.open!!.drop()
        runCurrent()
        advanceTimeBy(1_201)
        runCurrent()
        assertTrue(lan.discovering)
    }

    @Test fun onlyAddressesInsideTheLocalNetworkAreTried() = runTest {
        val public = LanAddress("8.8.8.8", 50001)
        val loopback = LanAddress("127.0.0.1", 50001)
        val lan = FakeLan(mapOf(public to mac, loopback to mac, home to mac))
        val s = pairedWithLan(lan)
        lan.announce(public, loopback)
        advanceUntilIdle()
        assertEquals(0, lan.opened)
        lan.announce(home)
        advanceUntilIdle()
        assertEquals(Transport.TRANSPORT_LAN, s.route.value)
    }
}

class FilePendingStoreTest {
    private val dir = kotlin.io.path.createTempDirectory("seam-pending").toFile()
    private fun env(id: Int) = Envelope.newBuilder().setVersion(1).setMsgId(ByteString.copyFrom(ByteArray(16) { id.toByte() }))
        .setCiphertext(ByteString.copyFrom(ByteArray(300) { 7 })).setTtlS(600).build()

    @Test fun roundTripsInOrderAndClears() {
        val store = FilePendingStore(dir)
        store.save(listOf(env(1), env(2)))
        assertEquals(listOf(env(1), env(2)), FilePendingStore(dir).load())
        store.save(emptyList())
        assertTrue(store.load().isEmpty())
        assertTrue(!java.io.File(dir, "pending.bin").exists())
    }

    @Test fun aCorruptOrForeignFileReadsAsEmpty() {
        val f = java.io.File(dir, "pending.bin")
        for (bytes in listOf(byteArrayOf(), byteArrayOf(9), byteArrayOf(1, 0, 0, 0, 50, 1, 2), byteArrayOf(1, 0x7f, -1, -1, -1), byteArrayOf(1, 0, 0, 0, 2, -1, -1))) {
            f.writeBytes(bytes)
            assertTrue(bytes.contentToString(), FilePendingStore(dir).load().isEmpty())
        }
    }
}

class TcpLinkTest {
    @Test fun aFrameSentRightBeforeCloseIsStillWritten() {
        java.net.ServerSocket(0, 1, java.net.InetAddress.getLoopbackAddress()).use { server ->
            val got = java.util.concurrent.CompletableFuture<ByteArray>()
            Thread {
                server.accept().use { got.complete(it.getInputStream().readBytes()) } // until the phone's side ends
            }.start()
            val closed = java.util.concurrent.CountDownLatch(1)
            val link = connectTcp(
                LanAddress("127.0.0.1", server.localPort),
                object : LinkListener {
                    override fun onFrame(frame: ByteArray) = Unit
                    override fun onClosed(reason: String) = closed.countDown()
                },
                allowed = { true }, // loopback, for the test only
            )
            val farewell = ByteArray(200_000) { (it % 251).toByte() }
            assertTrue(link.send(farewell))
            link.close() // straight away, as forget() does
            assertTrue("no sends after close", !link.send(byteArrayOf(1)))
            assertArrayEquals(farewell, got.get(5, java.util.concurrent.TimeUnit.SECONDS))
            assertTrue(closed.await(5, java.util.concurrent.TimeUnit.SECONDS))
        }
    }
}

class LocalNetworkTest {
    @Test fun acceptsOnlyPrivateAndLinkLocalLiterals() {
        listOf("10.0.0.5", "172.16.0.1", "172.31.255.255", "192.168.1.20", "169.254.3.4", "fe80::1", "fe80::1%wlan0", "fd12:3456::1", "::ffff:192.168.1.2")
            .forEach { assertTrue(it, isLocalNetwork(it)) }
        listOf("127.0.0.1", "::1", "8.8.8.8", "172.32.0.1", "192.169.0.1", "0.0.0.0", "224.0.0.251", "ff02::fb", "2001:db8::1",
            "999.1.1.1", "mac.local", "example.com", "", "10.0.0", "::ffff:8.8.8.8")
            .forEach { assertTrue(it, !isLocalNetwork(it)) }
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
