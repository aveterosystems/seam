// SPDX-License-Identifier: AGPL-3.0-only
package com.avetero.seam.session

import com.avetero.seam.crypto.SeamDevice
import com.avetero.seam.protocol.v1.Control
import com.avetero.seam.protocol.v1.PairAccept
import com.avetero.seam.protocol.v1.Payload
import com.avetero.seam.protocol.v1.ServerFrame
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The Same Wi‑Fi path on its own, with a fake byte pipe: cases that would need long setup through Session. */
@OptIn(ExperimentalCoroutinesApi::class)
class LanPathTest {
    /** A service that accepts the connection and never answers the handshake. */
    private class SilentLan : Lan {
        /** Announced to every discovery, as NSD does. */
        val adverts = listOf(LanAddress("192.168.1.20", 50001))
        var opened = 0
        var aborted = 0
        var discoveries = 0
        override fun discover(found: (LanAddress) -> Unit, lost: (LanAddress) -> Unit) {
            discoveries++
            adverts.forEach(found)
        }
        override fun stopDiscovery() = Unit
        override fun connect(address: LanAddress, listener: LinkListener): Link {
            opened++
            return object : Link {
                override fun send(frame: ByteArray) = true
                override fun close() = Unit
                override fun abort() {
                    aborted++
                }
            }
        }
    }

    private class Events : PathEvents {
        var ups = 0
        var downs = 0
        override fun onUp(path: Path) { ups++ }
        override fun onDown(path: Path) { downs++ }
        override fun onFrame(path: Path, frame: ServerFrame) = Unit
        override fun onFailed(path: Path, reason: String) = Unit
    }

    @Test fun aHandshakeThatNeverFinishesIsAbandonedAfterTenSecondsAndTriedAgainLater() = runTest {
        val mac = SeamDevice.generate()
        val phone = SeamDevice.generate()
        val start = phone.pairWith(mac.identityKey(), mac.relayKey(), "Mac", mac.prekeyBundles(1u).first(), mac.startPairing().token, "Phone", "X")
        val request = mac.acceptPairing(start.envelope)
        mac.confirmPairing(request.peerId)
        val accept = Payload.newBuilder().setControl(Control.newBuilder().setPairAccept(PairAccept.getDefaultInstance())).build()
        phone.open(mac.seal(request.peerId, accept.toByteArray(), 0u)) // the phone now trusts its Mac
        val lan = SilentLan()
        val events = Events()
        val path = LanPath(lan, phone, this, { testScheduler.currentTime }, null, peer = { start.peerId }, wanted = { true }, events = events)
        path.start()
        runCurrent()
        assertEquals(1, lan.opened)
        advanceTimeBy(9_999)
        runCurrent()
        assertEquals("still waiting for the proof", 0, events.downs)
        advanceTimeBy(2)
        runCurrent()
        assertEquals(1, events.downs)
        assertEquals("never proved itself: cut at once", 1, lan.aborted)
        assertTrue(!path.up && events.ups == 0)
        advanceTimeBy(30_001) // the short retry: it may be our own Mac, busy
        runCurrent()
        assertEquals(2, lan.opened)
        path.stop()
    }

    @Test fun sr_29_09_neverLooksForTheMacWhenNoConnectionIsWanted() = runTest {
        val lan = SilentLan()
        val path = LanPath(lan, SeamDevice.generate(), this, { testScheduler.currentTime }, null, peer = { "mac" }, wanted = { false }, events = Events())
        path.start() // e.g. the reconnect timer after a link dropped with the screen off
        runCurrent()
        assertEquals("rule 8: no network discovery running for nothing", 0, lan.discoveries)
        assertTrue(!path.active)
    }
}
