// SPDX-License-Identifier: AGPL-3.0-only
package com.avetero.seam.session

import com.avetero.seam.crypto.PeerPath
import com.avetero.seam.crypto.SeamDevice
import com.avetero.seam.protocol.v1.ClientFrame
import com.avetero.seam.protocol.v1.Control
import com.avetero.seam.protocol.v1.Envelope
import com.avetero.seam.protocol.v1.Hello
import com.avetero.seam.protocol.v1.Notification
import com.avetero.seam.protocol.v1.Payload
import com.avetero.seam.protocol.v1.Status
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

/** The session through a fake Tor and a fake Same Wi‑Fi, each with a real Mac (the Rust core) behind it. */
@OptIn(ExperimentalCoroutinesApi::class)
class SessionTest {
    private val tor = FakeTor()
    private var onions = 0
    private val mac = SeamDevice.generate()
    private val qr = qrFor(mac)
    private val store = MemoryStore()
    private val pending = MemoryPendingStore()

    /** The Mac's pairing code, its new onion service hosted for this phone. */
    private fun qrFor(mac: SeamDevice, name: String = "MacBook Pro"): PairingQr {
        val onion = ('a' + onions++).toString().repeat(56) + ".onion"
        tor.services[onion] = mac
        return PairingQr(onion, ByteArray(32) { 5 }, mac.startPairing().token, mac.identityKey(), mac.relayKey(), name)
    }

    /** A code whose token the Mac no longer accepts (expired, or replaced by a newer one). */
    private fun expired(q: PairingQr) = PairingQr(q.onionAddress, q.discoverySecret, ByteArray(32) { 1 }, q.identityKey, q.relayKey, q.macName)

    private fun TestScope.session(
        trace: ((String) -> Unit)? = null,
        now: () -> Long = { testScheduler.currentTime },
        lan: Lan? = null,
        dialer: TorDialer? = tor,
        settings: SettingsStore = MemorySettingsStore(),
    ) = Session(
        store, "Galaxy S25 Ultra", "SM-S938B",
        TestScope(StandardTestDispatcher(testScheduler)), now = now, trace = trace, lan = lan, tor = dialer, settingsStore = settings, pendingStore = pending,
    )

    private fun status(pct: Int) = Payload.newBuilder().setStatus(Status.newBuilder().setBatteryPct(pct).setCharging(true)).build()
    private fun clip(t: String) = Payload.newBuilder().setClipboard(com.avetero.seam.protocol.v1.Clipboard.newBuilder().setText(t)).build()
    private val unpair = Payload.newBuilder().setControl(Control.newBuilder().setUnpair(com.avetero.seam.protocol.v1.Unpair.getDefaultInstance())).build()

    private fun TestScope.paired(): Session = session().also {
        it.pair(qr)
        advanceUntilIdle()
    }

    // MARK: pairing over the channel (docs/tor/13-implementation-contract.md)

    @Test fun pairsThroughTorThenConnectsNormally() = runTest {
        val s = paired()
        assertEquals(SessionState.Connected("MacBook Pro"), s.state.value)
        assertEquals("Galaxy S25 Ultra", mac.peers().single().name)
        assertEquals("one pairing channel", 1, tor.pairings)
        assertTrue("the PairAccept was acked", tor.delivered >= 1)
        assertTrue("only ever the code's address", tor.dialled.all { it == qr.onionAddress })
        assertEquals(Transport.TRANSPORT_TOR, s.route.value)
        assertEquals("then a normal channel: pairing channel and this one", 2, tor.opened)
    }

    @Test fun pairsOverSameWifi() = runTest {
        val lan = FakeLan(mapOf(home to mac)).apply { adverts = listOf(home) }
        tor.services.clear() // the onion address isn't published yet
        val s = session(lan = lan)
        s.pair(qr)
        advanceUntilIdle()
        assertEquals(SessionState.Connected("MacBook Pro"), s.state.value)
        assertEquals(1, lan.pairings)
        assertEquals(0, tor.pairings)
        assertEquals(Transport.TRANSPORT_LAN, s.route.value)
    }

    @Test fun pairingTriesBothAndOnlyOneChannelPairs() = runTest {
        val lan = FakeLan(mapOf(home to mac)).apply { adverts = listOf(home) }
        val s = session(lan = lan)
        s.pair(qr)
        advanceUntilIdle()
        assertEquals(SessionState.Connected("MacBook Pro"), s.state.value)
        assertEquals("one pairing request", 1, mac.peers().size)
        assertEquals("the keys were asked for once", 1, lan.bundles + tor.bundles)
        assertEquals("Same Wi‑Fi preferred once paired", Transport.TRANSPORT_LAN, s.route.value)
    }

    @Test fun someoneElsesMacOnTheNetworkCantTakeThePairing() = runTest {
        val lan = FakeLan(mapOf(neighbour to SeamDevice.generate().also { it.startPairing() })).apply { adverts = listOf(neighbour) }
        val s = session(lan = lan)
        s.pair(qr)
        advanceUntilIdle()
        assertEquals("their Mac fails the pinned key", 0, lan.pairings)
        assertEquals(SessionState.Connected("MacBook Pro"), s.state.value)
        assertEquals(1, tor.pairings)
    }

    @Test fun aPairingChannelCarriesOnlyThePairingExchange() = runTest {
        val s = paired()
        s.send(status(1)) // queued for the old Mac as the new pairing starts
        tor.withholdAccept = true
        tor.pairingFrames.clear()
        s.pair(qrFor(SeamDevice.generate(), "Mac mini"))
        s.send(status(2))
        s.setSwitches(wifi = false, tor = true)
        advanceUntilIdle()
        assertTrue(s.state.value is SessionState.Pairing)
        val allowed = setOf(ClientFrame.FrameCase.GET_BUNDLE, ClientFrame.FrameCase.SEND, ClientFrame.FrameCase.DELIVERED)
        assertTrue(tor.pairingFrames.toString(), tor.pairingFrames.isNotEmpty() && tor.pairingFrames.all { it in allowed })
        assertEquals("only the pairing request was sent on it", 1, tor.pairingFrames.count { it == ClientFrame.FrameCase.SEND })
    }

    @Test fun aPairingThatFindsNoMacGivesUpAfterThreeMinutes() = runTest {
        tor.services.clear()
        val s = session()
        s.pair(qr)
        advanceTimeBy(150_000)
        runCurrent()
        assertTrue("still trying while the address publishes", s.state.value is SessionState.Pairing)
        assertTrue("retried", tor.dialled.size >= 3)
        advanceUntilIdle()
        assertTrue((s.state.value as SessionState.Failed).reason.startsWith("Couldn't reach your Mac"))
    }

    @Test fun aRelayEraPairingAsksToPairAgain() = runTest {
        paired()
        // What an older version left: a paired Mac, app data the core no longer reads (none)
        val old = SeamDevice.restore(store.blob!!, ByteArray(32) { 3 }).apply { clearAppData() }
        store.blob = old.exportState(ByteArray(32) { 3 })
        val s = session()
        s.start()
        advanceUntilIdle()
        assertEquals(SessionState.Unpaired, s.state.value)
        assertTrue(s.notice.value!!.contains("pair again"))
        assertTrue(SeamDevice.restore(store.blob!!, ByteArray(32) { 3 }).peers().isEmpty())
    }

    // MARK: delivery

    @Test fun sendsBatteryStatusToTheMacOnceConnected() = runTest {
        val s = session()
        s.send(status(10)) // before pairing: nowhere to go, dropped
        s.pair(qr)
        advanceUntilIdle()
        s.send(status(82))
        advanceUntilIdle()
        assertEquals(82, tor.macReceived.single().status.batteryPct)
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
        assertEquals(55, tor.macReceived.single().status.batteryPct)
    }

    @Test fun reconnectsAfterADropWithoutPairingAgain() = runTest {
        val s = paired()
        tor.drop()
        advanceUntilIdle()
        assertEquals(SessionState.Connected("MacBook Pro"), s.state.value)
        assertEquals(3, tor.opened)
        assertEquals("a normal channel, never a pairing one", 1, tor.pairings)
    }

    @Test fun survivesARestartWithoutRePairing() = runTest {
        paired()
        val restarted = session()
        restarted.start()
        advanceUntilIdle()
        assertEquals(SessionState.Connected("MacBook Pro"), restarted.state.value)
        restarted.send(status(40))
        advanceUntilIdle()
        assertEquals(40, tor.macReceived.single().status.batteryPct)
    }

    @Test fun expiredCodeFailsCleanlyAndLeavesNoHalfPairing() = runTest {
        val s = session()
        s.pair(expired(qr))
        advanceUntilIdle()
        assertTrue(s.state.value is SessionState.Failed)
        val restarted = session()
        restarted.start()
        advanceUntilIdle()
        assertEquals(SessionState.Unpaired, restarted.state.value)
    }

    @Test fun anEnvelopeThatCantBeOpenedIsIgnored() = runTest {
        val s = paired()
        tor.deliverToPhone(Envelope.newBuilder().setVersion(1).setMsgId(ByteString.copyFrom(ByteArray(16))).build().toByteArray())
        advanceUntilIdle()
        assertEquals(SessionState.Connected("MacBook Pro"), s.state.value)
    }

    @Test fun sr_2_03_aFeatureThatThrowsDoesNotCrashAndTheMessageIsAcked() = runTest {
        val s = session()
        s.register(object : Feature {
            override val handles = setOf(Payload.BodyCase.STATUS)
            override fun receive(payload: Payload, fromPeer: String) = error("feature bug")
        })
        s.pair(qr)
        advanceUntilIdle()
        val acked = tor.delivered
        tor.deliverToPhone(mac.seal(mac.peers().single().id, status(1).toByteArray(), 0u))
        advanceUntilIdle()
        assertEquals(acked + 1, tor.delivered)
        assertEquals(SessionState.Connected("MacBook Pro"), s.state.value)
    }

    @Test fun sr_2_03_aSaveFailureIsShownAndTheMessageIsNotAcked() = runTest {
        val s = paired()
        val acked = tor.delivered
        store.failSaves = true
        tor.deliverToPhone(mac.seal(mac.peers().single().id, status(1).toByteArray(), 0u))
        advanceUntilIdle()
        assertEquals("not acked, so the Mac keeps it", acked, tor.delivered)
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
        val acked = tor.delivered
        store.failSaves = true
        tor.deliverToPhone(mac.seal(mac.peers().single().id, status(9).toByteArray(), 0u))
        advanceUntilIdle()
        assertEquals("handled, not lost", listOf(9), received)
        assertEquals("not acked, so a restart from the saved state opens it again", acked, tor.delivered)
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
        assertEquals("the next feature still ran, and its message went out", 55, tor.macReceived.single().status.batteryPct)
    }

    @Test fun sr_2_63_noSavedStateEverHoldsTwoMacs() = runTest {
        val s = paired()
        val before = store.saves.size
        val newMac = SeamDevice.generate()
        val next = qrFor(newMac, "Mac mini")
        s.pair(next)
        advanceUntilIdle()
        assertEquals(SessionState.Connected("Mac mini"), s.state.value)
        assertEquals("dials the new Mac's address from now on", next.onionAddress, tor.dialled.last())
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
        tor.drop() // runs before the sends below, so they wait in the outbox for the reconnect
        s.send(clip("old"))
        s.send(clip("new"))
        advanceUntilIdle()
        assertEquals(listOf("new"), tor.macReceived.filter { it.hasClipboard() }.map { it.clipboard.text })
        assertEquals(600, tor.ttls.last { it.first == Payload.BodyCase.CLIPBOARD }.second)
    }

    @Test fun sr_4_04_aClipboardQueuedTooLongIsDroppedNotSentLate() = runTest {
        val s = paired()
        tor.services.remove(qr.onionAddress) // the Mac is unreachable for 11 minutes
        tor.drop()
        s.send(clip("stale"))
        advanceTimeBy(11 * 60_000L)
        tor.services[qr.onionAddress] = mac
        advanceUntilIdle()
        assertEquals(SessionState.Connected("MacBook Pro"), s.state.value)
        assertTrue("never sent late", tor.macReceived.none { it.hasClipboard() })
    }

    @Test fun sr_2_20_theOutboxKeepsOnlyTheLatestStatus() = runTest {
        val s = paired()
        tor.services.remove(qr.onionAddress) // offline for a moment
        tor.drop()
        repeat(5) { s.send(status(it)) }
        runCurrent()
        tor.services[qr.onionAddress] = mac
        advanceUntilIdle()
        assertEquals(listOf(4), tor.macReceived.map { it.status.batteryPct })
    }

    // MARK: forgetting

    @Test fun forgettingTheMacTellsItAndForgetsIt() = runTest {
        val s = paired()
        s.forgetMac()
        advanceUntilIdle()
        assertEquals(SessionState.Unpaired, s.state.value)
        assertTrue("the Mac was told", tor.macReceived.any { it.hasControl() && it.control.hasUnpair() })
        val saved = SeamDevice.restore(store.blob!!, ByteArray(32) { 3 })
        assertTrue("nothing saved still pairs", saved.peers().isEmpty())
        assertNull("nor keeps the Mac's address", saved.appData())
        assertNull("and Tor has nowhere to go", tor.target)
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
        assertTrue("the Mac wasn't told something that didn't happen", tor.macReceived.none { it.hasControl() && it.control.hasUnpair() })
    }

    @Test fun anUnpairIsIgnoredWhilePairingWithAnotherMac() = runTest {
        val s = paired()
        val newMac = SeamDevice.generate()
        tor.withholdAccept = true // the new Mac hasn't accepted yet
        s.pair(qrFor(newMac, "Mac mini"))
        advanceUntilIdle()
        tor.deliverToPhone(mac.seal(mac.peers().single().id, unpair.toByteArray(), 0u))
        advanceUntilIdle()
        assertTrue("still pairing, nothing forgotten", s.state.value is SessionState.Pairing)
    }

    @Test fun whenTheMacUnpairsThePhoneForgetsIt() = runTest {
        val s = paired()
        tor.deliverToPhone(mac.seal(mac.peers().single().id, unpair.toByteArray(), 0u))
        advanceUntilIdle()
        assertEquals(SessionState.Unpaired, s.state.value)
        assertTrue(SeamDevice.restore(store.blob!!, ByteArray(32) { 3 }).peers().isEmpty())
    }

    // MARK: decision 0005: the connection is held only while it's cheap or needed

    @Test fun d0005_screenOffAndIdleClosesTheConnection() = runTest {
        val s = paired()
        val opened = tor.opened
        s.setScreenOn(false)
        advanceUntilIdle() // a minute with nothing to carry
        assertEquals(SessionState.Idle("MacBook Pro"), s.state.value)
        assertEquals("and it doesn't reconnect by itself", opened, tor.opened)
    }

    @Test fun d0005_aCopyWhileIdleConnectsSendsAndSleepsAgain() = runTest {
        val s = paired()
        s.setScreenOn(false)
        advanceUntilIdle()
        val opened = tor.opened
        s.send(clip("sent from an idle phone"))
        advanceUntilIdle()
        assertEquals(opened + 1, tor.opened)
        assertTrue(tor.macReceived.any { it.hasClipboard() && it.clipboard.text == "sent from an idle phone" })
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
    // connections at all (no Tor, no Wi‑Fi search) and captures nothing, until it ends or resumes.
    /** The pause each Hello told the Mac, in order (the pause rides on the Hello, SR-32-01). */
    private fun pausesSent() = tor.hellos.map { it.control.hello.pausedUntilMs }

    @Test fun pausingTellsTheMacThenGoesSilent() = runTest {
        val s = paired()
        val until = testScheduler.currentTime + 3_600_000
        s.pause(until)
        advanceUntilIdle()
        assertEquals("the Mac is told when it ends", until, pausesSent().last())
        assertEquals(SessionState.Idle("MacBook Pro"), s.state.value)
        assertEquals(until, s.settings.value.pausedUntil)
        val opened = tor.opened
        s.setScreenOn(false)
        s.setScreenOn(true) // picking the phone up doesn't connect while paused
        s.setCharging(true) // nor does charging
        s.send(clip("copied while paused"))
        advanceTimeBy(30 * 60_000L)
        assertEquals("no connection at all", opened, tor.opened)
        assertTrue("nothing captured", tor.macReceived.none { it.hasClipboard() && it.clipboard.text == "copied while paused" })
        assertEquals(CpuPlan.None, s.cpu.value)
    }

    @Test fun pausingWhileIdleConnectsOnceJustToTellTheMac() = runTest {
        val s = paired()
        s.setScreenOn(false)
        advanceUntilIdle()
        val opened = tor.opened
        s.pause(Long.MAX_VALUE)
        advanceUntilIdle()
        assertEquals(opened + 1, tor.opened)
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
        assertTrue(tor.macReceived.any { it.hasClipboard() && it.clipboard.text == "after the pause" })
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
        assertTrue(tor.macReceived.any { it.hasClipboard() && it.clipboard.text == "after resume" })
    }

    @Test fun aPauseSurvivesARestart() = runTest {
        val settings = MemorySettingsStore()
        val s = session(settings = settings).also { it.pair(qr); advanceUntilIdle() }
        s.pause(Long.MAX_VALUE)
        advanceUntilIdle()
        val opened = tor.opened
        val restarted = session(settings = settings)
        restarted.start()
        restarted.setScreenOn(true)
        advanceUntilIdle()
        assertEquals(Long.MAX_VALUE, restarted.settings.value.pausedUntil)
        assertEquals("still silent", opened, tor.opened)
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
        val (opened, viaTor) = lan.opened to tor.opened
        s.setScreenOn(false)
        s.setScreenOn(true)
        lan.announce(home)
        advanceTimeBy(10 * 60_000L)
        assertEquals("acked, so nothing to resend: no link opened again", opened, lan.opened)
        assertEquals(viaTor, tor.opened)
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
        val opened = tor.opened
        s.send(status(42))
        advanceUntilIdle()
        assertEquals("no radio wake-up for a battery change", opened, tor.opened)
        s.setScreenOn(true) // the user picks the phone up
        advanceUntilIdle()
        assertEquals(opened + 1, tor.opened)
        assertEquals(42, tor.macReceived.last { it.hasStatus() }.status.batteryPct)
        assertEquals(SessionState.Connected("MacBook Pro"), s.state.value)
    }

    @Test fun sr_8_01_aCopyThatCantGoDoesNotSpinAndGivesUpWhenStale() = runTest {
        var decisions = 0
        val s = session(trace = { if (++decisions > 2_000) error("busy loop: $decisions decisions") })
        s.pair(qr)
        advanceUntilIdle()
        tor.silent = true // connects, but the Mac never answers: the copy can't go
        s.setScreenOn(false)
        advanceUntilIdle() // goes idle
        val before = decisions
        s.send(clip("can't go"))
        advanceTimeBy(30 * 60_000L) // half an hour of a silent Mac
        // Each attempt waits out the Tor proof timeout, then the retry wait: a few a minute at most
        assertTrue("bounded, not a loop: ${decisions - before}", decisions - before < 200)
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
        val opened = tor.opened
        val restarted = session()
        restarted.setScreenOn(false)
        advanceTimeBy(61_000)
        restarted.start()
        advanceUntilIdle()
        assertEquals(SessionState.Idle("MacBook Pro"), restarted.state.value)
        assertEquals(opened, tor.opened)
    }

    // MARK: codes and outcomes

    @Test fun codesDontMatchStopsPairingAndTrustsNothing() = runTest {
        tor.withholdAccept = true // the Mac shows its code and waits for the user
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
        assertTrue(tor.macReceived.isEmpty())
    }

    @Test fun sr_2_18_aFailedRePairKeepsTheExistingMac() = runTest {
        val s = paired()
        s.pair(expired(qrFor(SeamDevice.generate(), "Someone else's Mac")))
        advanceUntilIdle()
        assertEquals(SessionState.Connected("MacBook Pro"), s.state.value)
        assertTrue(s.notice.value!!.startsWith("Couldn't reach your Mac"))
        assertEquals("back to the current Mac's address", qr.onionAddress, tor.target?.onionAddress)
        s.send(status(66))
        advanceUntilIdle()
        assertEquals(66, tor.macReceived.single().status.batteryPct)
    }

    // MARK: pairing outcome (SR-11-06: the setup screens follow only a new Mac)

    @Test fun sr_11_06_pairingAMacReportsANewMac() = runTest {
        val s = session()
        assertNull(s.pairingOutcome.value)
        s.pair(qr)
        advanceUntilIdle()
        assertEquals(PairingOutcome.NewMac, s.pairingOutcome.value)
        s.pair(qrFor(SeamDevice.generate(), "Mac mini")) // replacing it is a new Mac too
        advanceUntilIdle()
        assertEquals(SessionState.Connected("Mac mini"), s.state.value)
        assertEquals(PairingOutcome.NewMac, s.pairingOutcome.value)
    }

    @Test fun sr_11_06_anAcceptThatCantBeSavedIsNotANewMac() = runTest {
        tor.withholdAccept = true
        val s = session()
        s.pair(qr)
        advanceUntilIdle()
        store.failSaves = true
        val phoneId = mac.peers().single().id
        mac.confirmPairing(phoneId)
        tor.deliverToPhone(mac.seal(phoneId, FakeLan.ACCEPT.toByteArray(), 0u))
        advanceUntilIdle()
        assertTrue(s.state.value is SessionState.Failed)
        assertTrue("no setup screens over a failure", s.pairingOutcome.value != PairingOutcome.NewMac)
    }

    @Test fun sr_11_06_aFailedPairingWhilePairedReportsStillOnThePreviousMac() = runTest {
        val s = paired()
        s.pair(expired(qrFor(SeamDevice.generate(), "Someone else's Mac")))
        advanceUntilIdle()
        assertEquals(PairingOutcome.StillOnPreviousMac, s.pairingOutcome.value)
    }

    @Test fun sr_11_06_aFailedFirstPairingReportsNoMac() = runTest {
        val s = session()
        s.pair(expired(qr))
        advanceUntilIdle()
        assertEquals(PairingOutcome.NoMac, s.pairingOutcome.value)
    }

    @Test fun sr_2_18_aRestartMidPairingForgetsTheHalfPairedMac() = runTest {
        tor.withholdAccept = true
        val s = session()
        s.pair(qr)
        advanceUntilIdle()
        assertTrue(s.state.value is SessionState.Pairing)
        val restarted = session()
        restarted.start()
        advanceUntilIdle()
        assertEquals(SessionState.Unpaired, restarted.state.value)
    }

    // MARK: same Wi‑Fi and the switches (decision 0006)

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

    @Test fun onTheSameWifiMessagesGoDirectAndTorStaysOpen() = runTest {
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
        assertTrue("not through Tor", tor.macReceived.none { it.hasStatus() })
        assertTrue("Tor stays, so losing Wi‑Fi is seamless", tor.open?.closed == false)
        assertEquals(listOf(Transport.TRANSPORT_LAN, Transport.TRANSPORT_TOR), lan.hellos.last().control.hello.allowedTransportsList)
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
        val torAcks = tor.delivered
        lan.open!!.deliverToPhone(mac.seal(mac.peers().single().id, status(12).toByteArray(), 0u))
        advanceUntilIdle()
        assertEquals(12, received.single().status.batteryPct)
        assertEquals(1, lan.delivered)
        assertEquals(torAcks, tor.delivered)
    }

    @Test fun whenTheDirectLinkDropsMessagesGoThroughTor() = runTest {
        val lan = FakeLan(mapOf(home to mac))
        val s = pairedWithLan(lan)
        lan.announce(home)
        advanceUntilIdle()
        lan.open!!.drop()
        runCurrent()
        assertEquals(SessionState.Connected("MacBook Pro"), s.state.value)
        assertEquals(Transport.TRANSPORT_TOR, s.route.value)
        advanceTimeBy(1_300)
        runCurrent()
        assertTrue("looks for the Mac again, after a short wait", lan.discovering)
        s.send(status(33))
        advanceUntilIdle()
        assertEquals(33, tor.macReceived.single().status.batteryPct)
    }

    @Test fun torOffNeverUsesTorAndMessagesWaitForWifi() = runTest {
        val lan = FakeLan(mapOf(home to mac))
        val s = pairedWithLan(lan)
        s.setSwitches(wifi = true, tor = false)
        advanceUntilIdle()
        assertEquals("the Mac was told first, through Tor", listOf(Transport.TRANSPORT_LAN), tor.hellos.last().control.hello.allowedTransportsList)
        assertEquals(null, s.route.value)
        assertTrue("Tor closed", tor.open!!.closed)
        val opened = tor.opened
        s.send(status(5))
        advanceUntilIdle()
        assertTrue("waits rather than using Tor", tor.macReceived.none { it.hasStatus() })
        assertEquals(opened, tor.opened)
        lan.announce(home)
        advanceUntilIdle()
        assertEquals(5, lan.macReceived.single().status.batteryPct)
    }

    @Test fun theLastSwitchCantBeTurnedOff() = runTest {
        val s = pairedWithLan(FakeLan(emptyMap()))
        s.setSwitches(wifi = false, tor = true)
        s.setSwitches(wifi = false, tor = false)
        advanceUntilIdle()
        assertEquals(false to true, s.settings.value.wifi to s.settings.value.tor)
    }

    @Test fun onlyPathsTheMacAllowsAreUsed() = runTest {
        val lan = FakeLan(mapOf(home to mac))
        val settings = MemorySettingsStore()
        val s = pairedWithLan(lan, settings)
        lan.announce(home)
        advanceUntilIdle()
        tor.deliverToPhone(macHello(Transport.TRANSPORT_TOR)) // the Mac turned Same Wi‑Fi off
        advanceUntilIdle()
        assertEquals(setOf(Transport.TRANSPORT_TOR), s.settings.value.macAllows)
        assertEquals("the core's answer for the status line", setOf(PeerPath.TOR), s.settings.value.allowed)
        assertTrue("our own switch still allows it: the link stays, for the Mac's next Hello", !lan.open!!.closed)
        assertEquals(Transport.TRANSPORT_TOR, s.route.value)
        s.send(status(9))
        advanceUntilIdle()
        assertEquals(9, tor.macReceived.single().status.batteryPct)
        assertTrue(lan.macReceived.isEmpty())
    }

    @Test fun aStaleMacListNeverLocksThePhoneOut() = runTest {
        // On a device: the phone remembered the Mac's [Same Wi‑Fi]; the Mac then went Tor-only
        val s = pairedWithLan(FakeLan(emptyMap())) // no Mac on this network
        tor.deliverToPhone(macHello(Transport.TRANSPORT_LAN))
        advanceUntilIdle()
        assertTrue("Tor stays open: it's our own switch that counts", tor.open?.closed == false)
        s.send(clip("waiting"))
        advanceUntilIdle()
        assertTrue("but nothing is sent where the Mac said no", tor.macReceived.none { it.hasClipboard() })
        // After a restart the phone still connects through Tor, despite the stale list
        val opened = tor.opened
        val restarted = session(lan = FakeLan(emptyMap()))
        restarted.start()
        advanceUntilIdle()
        assertTrue(tor.opened > opened)
        restarted.send(clip("waiting"))
        advanceUntilIdle()
        tor.deliverToPhone(macHello(Transport.TRANSPORT_TOR)) // the Mac's new choice gets through
        advanceUntilIdle()
        assertEquals(Transport.TRANSPORT_TOR, restarted.route.value)
        assertEquals("waiting", tor.macReceived.last { it.hasClipboard() }.clipboard.text)
    }

    @Test fun aHelloUsesOurOwnPathsEvenWhereTheMacSaidNo() = runTest {
        val s = pairedWithLan(FakeLan(emptyMap()))
        tor.deliverToPhone(macHello(Transport.TRANSPORT_LAN)) // the Mac excludes Tor
        advanceUntilIdle()
        val hellos = tor.hellos.size
        s.setSwitches(wifi = false, tor = true) // now no path both ends allow
        advanceUntilIdle()
        assertTrue("the Hello still went, through Tor", tor.hellos.size > hellos)
        assertEquals(listOf(Transport.TRANSPORT_TOR), tor.hellos.last().control.hello.allowedTransportsList)
        s.send(clip("never"))
        s.send(status(3))
        advanceUntilIdle()
        assertTrue("a normal payload never does", tor.macReceived.none { it.hasClipboard() || it.hasStatus() })
        assertEquals(null, s.route.value)
    }

    @Test fun noSharedPathMeansNothingIsSent() = runTest {
        val lan = FakeLan(mapOf(home to mac))
        val s = pairedWithLan(lan)
        tor.deliverToPhone(macHello(Transport.TRANSPORT_LAN))
        advanceUntilIdle()
        s.setSwitches(wifi = false, tor = true)
        advanceUntilIdle()
        s.send(status(1))
        advanceUntilIdle()
        assertEquals(null, s.route.value)
        assertTrue(tor.macReceived.none { it.hasStatus() })
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
        assertTrue(tor.open!!.closed)
        assertTrue(!lan.discovering && !tor.discovering)
        assertEquals(null, s.route.value)
    }

    @Test fun aNewMacStartsWithNothingHeard() = runTest {
        val s = pairedWithLan(FakeLan(emptyMap()))
        tor.deliverToPhone(macHello(Transport.TRANSPORT_LAN))
        advanceUntilIdle()
        assertEquals(setOf(Transport.TRANSPORT_LAN), s.settings.value.macAllows)
        s.pair(qrFor(SeamDevice.generate(), "Mac mini"))
        advanceUntilIdle()
        assertEquals(SessionState.Connected("Mac mini"), s.state.value)
        assertEquals(null, s.settings.value.macAllows)
        assertEquals(0L, s.settings.value.macHelloSeq)
    }

    @Test fun aDelayedOldHelloNeverUndoesANewerOne() = runTest {
        val s = pairedWithLan(FakeLan(emptyMap()))
        val both = setOf(Transport.TRANSPORT_LAN, Transport.TRANSPORT_TOR)
        tor.deliverToPhone(macHello(Transport.TRANSPORT_LAN, Transport.TRANSPORT_TOR, seq = 5))
        advanceUntilIdle()
        // Older ones arriving late; a sender that never counts sends 0
        for (seq in listOf(4L, 5L, 0L)) {
            tor.deliverToPhone(macHello(Transport.TRANSPORT_TOR, seq = seq))
            advanceUntilIdle()
            assertEquals("seq $seq ignored", both, s.settings.value.macAllows)
        }
        assertEquals(5L, s.settings.value.macHelloSeq)
        tor.deliverToPhone(macHello(Transport.TRANSPORT_TOR, seq = 6))
        advanceUntilIdle()
        assertEquals(setOf(Transport.TRANSPORT_TOR), s.settings.value.macAllows)
    }

    @Test fun aHelloNamingATransportThisBuildDoesntKnowIsSafe() = runTest {
        val s = pairedWithLan(FakeLan(emptyMap()))
        val hello = Payload.newBuilder().setControl(
            Control.newBuilder().setHello(
                Hello.newBuilder().setSeq(1).addAllowedTransports(Transport.TRANSPORT_TOR).addAllowedTransportsValue(9),
            ),
        ).build()
        tor.deliverToPhone(mac.seal(mac.peers().single().id, hello.toByteArray(), 0u))
        advanceUntilIdle()
        assertEquals("a newer Mac's transport is dropped, never stored", setOf(Transport.TRANSPORT_TOR), s.settings.value.macAllows)
        assertTrue(s.state.value.toString(), s.state.value !is SessionState.Failed)
    }

    @Test fun theRetiredRelayMeansNothingInAHello() = runTest {
        val s = pairedWithLan(FakeLan(emptyMap()))
        tor.deliverToPhone(macHello(Transport.TRANSPORT_RELAY))
        advanceUntilIdle()
        assertEquals(emptySet<Transport>(), s.settings.value.macAllows)
        assertEquals(null, s.route.value)
    }

    @Test fun anOlderMacsEmptyListAllowsEverythingThisPhoneAllows() = runTest {
        // Versions from before the switches send no list: the same rule as the Mac (spec 01)
        val lan = FakeLan(mapOf(home to mac))
        val s = pairedWithLan(lan)
        tor.deliverToPhone(macHello())
        advanceUntilIdle()
        assertNull(s.settings.value.macAllows)
        assertEquals(Transport.TRANSPORT_TOR, s.route.value)
        lan.announce(home)
        advanceUntilIdle()
        assertEquals(Transport.TRANSPORT_LAN, s.route.value)
    }

    @Test fun aListOfOnlyTransportsThisBuildCantUseMeansNothingInCommon() = runTest {
        val s = pairedWithLan(FakeLan(emptyMap()))
        val hello = Payload.newBuilder().setControl(
            Control.newBuilder().setHello(Hello.newBuilder().setSeq(1).addAllowedTransportsValue(9)),
        ).build()
        tor.deliverToPhone(mac.seal(mac.peers().single().id, hello.toByteArray(), 0u))
        advanceUntilIdle()
        assertEquals(emptySet<Transport>(), s.settings.value.macAllows)
        assertEquals(null, s.route.value)
        s.send(status(4))
        advanceUntilIdle()
        assertTrue(tor.macReceived.none { it.hasStatus() })
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
        s.setSwitches(wifi = false, tor = true)
        advanceUntilIdle()
        s.setSwitches(wifi = true, tor = true)
        advanceUntilIdle()
        val seqs = tor.hellos.map { it.control.hello.seq }
        assertTrue(seqs.toString(), seqs.size >= 3 && seqs.first() == 1L && seqs.zipWithNext().all { (a, b) -> b > a })
        assertEquals(seqs.last(), s.settings.value.helloSeq)
        s.forgetMac()
        advanceUntilIdle()
        assertEquals(0L, s.settings.value.helloSeq)
        assertEquals(0L, s.settings.value.macHelloSeq)
    }

    @Test fun aRestartKeepsTheHelloCountersAndTheMacsChoice() = runTest {
        val s = pairedWithLan(FakeLan(emptyMap()))
        tor.deliverToPhone(macHello(Transport.TRANSPORT_TOR, seq = 7))
        advanceUntilIdle()
        val sent = s.settings.value.helloSeq
        assertTrue(sent >= 1)
        val restarted = session(settings = MemorySettingsStore()) // nothing about the Mac in preferences
        assertEquals(setOf(Transport.TRANSPORT_TOR), restarted.settings.value.macAllows)
        assertEquals(7L, restarted.settings.value.macHelloSeq)
        assertEquals(sent, restarted.settings.value.helloSeq)
        restarted.start()
        advanceUntilIdle()
        assertEquals("never reuses a counter", sent + 1, tor.hellos.last().control.hello.seq)
    }

    /** Connected over Same Wi‑Fi, with Tor up too. */
    private fun TestScope.onWifi(lan: FakeLan, now: () -> Long = { testScheduler.currentTime }): Session =
        session(lan = lan, now = now).also {
            it.pair(qr)
            advanceUntilIdle()
            lan.announce(home)
            advanceUntilIdle()
            assertEquals(Transport.TRANSPORT_LAN, it.route.value)
        }

    @Test fun whatTheMacNeverAckedGoesThroughTorAsItWasSealed() = runTest {
        val lan = FakeLan(mapOf(home to mac))
        val s = onWifi(lan)
        lan.lose = true // the link is dying: what's written now never arrives
        s.send(status(41))
        advanceUntilIdle()
        val sent = lan.lost.single { it.msgId !in tor.envelopes.map(Envelope::getMsgId) }
        val saves = store.saves.size
        lan.open!!.drop()
        runCurrent()
        val again = tor.envelopes.last()
        assertEquals("the same envelope, not a new seal", sent, again)
        assertEquals("nothing sealed (or saved) again", saves, store.saves.size)
        assertEquals(41, tor.macReceived.single { it.hasStatus() }.status.batteryPct)
    }

    @Test fun aFailedTorAttemptResendsNothing() = runTest {
        val lan = FakeLan(mapOf(home to mac))
        val s = onWifi(lan)
        lan.lose = true
        s.send(status(46))
        advanceUntilIdle()
        tor.services.remove(qr.onionAddress)
        tor.drop() // Tor was up: its drop resends, once
        advanceTimeBy(5 * 60_000L) // then many failed Tor attempts
        runCurrent()
        assertTrue(tor.dialled.size > 3)
        assertEquals("an attempt that never came up loses nothing, so resends nothing", 2, lan.lost.count { it.msgId == lan.lost.last().msgId })
    }

    @Test fun aFailedNewPairingKeepsWhatTheCurrentMacNeverAcked() = runTest {
        val lan = FakeLan(mapOf(home to mac))
        val s = onWifi(lan)
        lan.lose = true
        s.send(status(47))
        advanceUntilIdle()
        val sent = lan.lost.last()
        s.pair(expired(qrFor(SeamDevice.generate(), "Someone else's Mac")))
        advanceUntilIdle()
        assertEquals(SessionState.Connected("MacBook Pro"), s.state.value)
        assertTrue("still owed to the current Mac", tor.envelopes.any { it == sent } || pending.load().contains(sent))
        assertEquals(47, tor.macReceived.single { it.hasStatus() }.status.batteryPct)
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
        assertTrue("the same envelope, sealed once", tor.envelopes.any { it == sent })
        assertEquals(44, tor.macReceived.single { it.hasStatus() }.status.batteryPct)
        assertTrue("acked through Tor: nothing left", pending.load().isEmpty())
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
        assertTrue(lan.macReceived.last().control.hasUnpair())
        assertTrue("the same sealed Unpair through Tor too, in case Wi‑Fi is dropping", lan.envelopes.last() == tor.envelopes.last())
        assertEquals(SessionState.Unpaired, s.state.value)
    }

    @Test fun aFarewellNeverUsesAPathThisPhoneTurnedOff() = runTest {
        val lan = FakeLan(mapOf(home to mac))
        val s = onWifi(lan)
        s.setSwitches(wifi = true, tor = false)
        advanceUntilIdle()
        val torEnvelopes = tor.envelopes.size
        s.forgetMac()
        advanceUntilIdle()
        assertEquals("nothing through Tor", torEnvelopes, tor.envelopes.size)
        assertTrue(lan.macReceived.any { it.hasControl() && it.control.hasUnpair() })
    }

    @Test fun anAckFromTheMacClearsIt() = runTest {
        val lan = FakeLan(mapOf(home to mac))
        val s = onWifi(lan)
        s.send(status(42))
        advanceUntilIdle()
        assertEquals(42, lan.macReceived.single().status.batteryPct)
        assertTrue("acked: gone from the file too", pending.load().isEmpty())
        val before = tor.envelopes.size
        lan.open!!.drop()
        runCurrent()
        assertEquals("acked: nothing to send again", before, tor.envelopes.size)
    }

    @Test fun anAckForAnUnknownIdClearsNothing() = runTest {
        val lan = FakeLan(mapOf(home to mac))
        val s = onWifi(lan)
        lan.lose = true
        s.send(status(43))
        advanceUntilIdle()
        val sent = lan.lost.last()
        lan.open!!.delivered(ByteString.copyFrom(ByteArray(16) { 7 })) // not one we sent
        advanceUntilIdle()
        lan.open!!.drop()
        runCurrent()
        assertEquals(sent, tor.envelopes.last())
    }

    @Test fun anUnackedEnvelopePastItsTtlIsDroppedNotSentLate() = runTest {
        var offset = 0L
        val lan = FakeLan(mapOf(home to mac))
        val s = onWifi(lan, now = { System.currentTimeMillis() + offset }) // sent_at is wall-clock time
        lan.lose = true
        s.send(clip("old"))
        advanceUntilIdle()
        offset = 601_000 // a clipboard lives 10 minutes
        lan.open!!.drop()
        runCurrent()
        assertTrue(tor.macReceived.none { it.hasClipboard() })
    }

    @Test fun aCrowdOfFakeMacsCantHideTheRealOne() = runTest {
        val fakes = (1..10).map { LanAddress("192.168.1.${100 + it}", 50000 + it) }
        val stranger = strangerMac()
        val lan = FakeLan(fakes.associateWith { stranger } + (home to mac))
        val s = pairedWithLan(lan)
        val discoveries = lan.discoveries
        lan.adverts = fakes + home // the real Mac is announced last, past the cap
        lan.announce(*lan.adverts.toTypedArray())
        repeat(50) { runCurrent() } // no time passes: only what's already due
        assertEquals("only the first 8 are tried", 8, lan.opened)
        assertEquals("not found yet: still through Tor", Transport.TRANSPORT_TOR, s.route.value)
        advanceTimeBy(31_000) // every one refused: discovery starts again, without counting them
        runCurrent()
        advanceUntilIdle()
        assertEquals(discoveries + 1, lan.discoveries)
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
        assertEquals(Transport.TRANSPORT_TOR, s.route.value)
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
        assertEquals(Transport.TRANSPORT_TOR, s.route.value)
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
