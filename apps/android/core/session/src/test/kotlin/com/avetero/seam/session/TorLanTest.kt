// SPDX-License-Identifier: AGPL-3.0-only
package com.avetero.seam.session

import com.avetero.seam.crypto.NoHandle
import com.avetero.seam.crypto.TorBlock
import com.avetero.seam.crypto.TorConnectListener
import com.avetero.seam.crypto.TorInterface
import com.avetero.seam.crypto.TorService
import com.avetero.seam.crypto.TorState
import com.avetero.seam.crypto.TorStateListener
import com.avetero.seam.crypto.TorStream
import com.avetero.seam.crypto.TorStreamListener
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The phone's Tor dialer over a fake Arti: start once, dormant whenever nothing needs Tor. */
class TorLanTest {
    private class FakeStream : TorStream(NoHandle) {
        val sent = mutableListOf<ByteArray>()
        var listener: TorStreamListener? = null
        var shut = false
        override fun send(bytes: ByteArray) = !shut && sent.add(bytes)
        override fun listen(listener: TorStreamListener) { this.listener = listener }
        override fun shutdown() {
            shut = true
            listener?.onClosed()
        }
    }

    private class FakeArti : TorInterface {
        var starts = 0
        var state: TorStateListener? = null
        val dormant = mutableListOf<Boolean>()
        val dials = mutableListOf<Pair<String, ByteArray>>()
        var pending: TorConnectListener? = null
        override fun start(listener: TorStateListener) { starts++; state = listener }
        override fun setDormant(dormant: Boolean) { this.dormant += dormant }
        override fun connect(onionAddress: String, discoverySecret: ByteArray, listener: TorConnectListener) {
            dials += onionAddress to discoverySecret
            pending = listener
        }
        override fun host(serviceSecret: ByteArray, clients: List<ByteArray>, listener: TorConnectListener): TorService? = error("the phone never hosts")
        override fun state(): TorState = TorState.Off
    }

    private val onion = "b".repeat(56) + ".onion"
    private val arti = FakeArti()
    private val tor = TorLan(arti).apply { target = TorTarget(onion, ByteArray(32) { 4 }) }
    private val found = mutableListOf<LanAddress>()
    private val frames = mutableListOf<ByteArray>()
    private val closes = mutableListOf<String>()
    private val listener = object : LinkListener {
        override fun onFrame(frame: ByteArray) { frames += frame }
        override fun onClosed(reason: String) { closes += reason }
    }

    private fun lookForTheMac() = tor.discover({ found += it }, {})

    @Test fun startsOnceAndReportsTheMacWhenReady() {
        lookForTheMac()
        assertTrue("nothing to dial until Tor is ready", found.isEmpty())
        arti.state!!.onState(TorState.Starting(40u))
        arti.state!!.onState(TorState.Ready)
        assertEquals(listOf(LanAddress(onion, 1)), found)
        tor.stopDiscovery()
        lookForTheMac()
        assertEquals("one Tor per process", 1, arti.starts)
        assertEquals("ready already: reported at once", 2, found.size)
    }

    @Test fun dormantWheneverNoStreamIsOpenAndNothingIsLooking() {
        lookForTheMac()
        arti.state!!.onState(TorState.Ready)
        val link = tor.connect(found.single(), listener)
        tor.stopDiscovery() // found it, as LanPath does
        assertTrue("awake while a stream is open", arti.dormant.isEmpty() || arti.dormant.last() == false)
        val stream = FakeStream()
        arti.pending!!.onOpen(stream)
        link.close()
        assertEquals("dormant once the last stream closed", true, arti.dormant.last())
        assertEquals(listOf("closed"), closes)
        lookForTheMac()
        assertEquals("awake again to connect", false, arti.dormant.last())
        tor.stopDiscovery()
        assertEquals(true, arti.dormant.last())
    }

    @Test fun whatIsSentBeforeTheStreamOpensGoesInOrder() {
        lookForTheMac()
        arti.state!!.onState(TorState.Ready)
        val link = tor.connect(found.single(), listener)
        assertTrue(link.send(byteArrayOf(1)))
        assertTrue(link.send(byteArrayOf(2)))
        val stream = FakeStream()
        arti.pending!!.onOpen(stream)
        link.send(byteArrayOf(3))
        assertEquals(listOf(1, 2, 3), stream.sent.map { it.single().toInt() })
        stream.listener!!.onBytes(byteArrayOf(9))
        assertEquals(9, frames.single().single().toInt())
        assertEquals("dials with this phone's discovery secret", 4, arti.dials.single().second.first().toInt())
    }

    @Test fun anUnreachableMacClosesTheLinkAndSleeps() {
        lookForTheMac()
        arti.state!!.onState(TorState.Ready)
        tor.connect(found.single(), listener)
        tor.stopDiscovery()
        arti.pending!!.onFailed()
        assertEquals(listOf("unreachable"), closes)
        assertEquals(true, arti.dormant.last())
    }

    @Test fun aStreamThatOpensAfterWeGaveUpIsClosed() {
        lookForTheMac()
        arti.state!!.onState(TorState.Ready)
        val link = tor.connect(found.single(), listener)
        link.close()
        val late = FakeStream()
        arti.pending!!.onOpen(late)
        assertTrue(late.shut)
        assertEquals(1, closes.size)
    }

    @Test fun onlyTheCurrentAddressIsDialled() {
        lookForTheMac()
        arti.state!!.onState(TorState.Ready)
        val old = found.single()
        tor.target = TorTarget("c".repeat(56) + ".onion", ByteArray(32))
        assertTrue(runCatching { tor.connect(old, listener) }.isFailure)
        assertTrue(arti.dials.isEmpty())
    }

    @Test fun aBlockedTorIsShownWithItsReason() {
        lookForTheMac()
        arti.state!!.onState(TorState.Blocked(TorBlock.CLOCK_SKEWED))
        assertEquals(TorState.Blocked(TorBlock.CLOCK_SKEWED), tor.state.value)
        assertTrue("nothing to dial", found.isEmpty())
    }
}
