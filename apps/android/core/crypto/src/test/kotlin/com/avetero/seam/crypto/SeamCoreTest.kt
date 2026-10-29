// SPDX-License-Identifier: AGPL-3.0-only
package com.avetero.seam.crypto

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** Calls the Rust core through the generated Kotlin bindings, the way the Android app will. */
class SeamCoreTest {
    private val key = ByteArray(32) { 7 }

    /** A Mac and phone paired through the real flow: QR offer, bundle, PairRequest, accept. */
    private fun paired(): Pair<SeamDevice, SeamDevice> {
        val mac = SeamDevice.generate()
        val phone = SeamDevice.generate()
        val offer = mac.startPairing()
        val start = phone.pairWith(
            mac.identityKey(), mac.relayKey(), "Mac", mac.prekeyBundles(1u).first(),
            offer.token, "Galaxy S25 Ultra", "SM-S938B",
        )
        val request = mac.acceptPairing(start.envelope)
        assertEquals(phone.id(), request.peerId)
        assertEquals(request.safetyCode, phone.safetyCode(mac.id()))
        mac.confirmPairing(request.peerId)
        phone.open(mac.seal(request.peerId, byteArrayOf(0x0a, 0x02, 0x32, 0x00), 0u)) // PairAccept confirms the Mac
        assertTrue(phone.peers().single().confirmed)
        return mac to phone
    }

    @Test fun pairsThenTalksAfterARestart() {
        val (mac0, phone0) = paired()
        val mac = SeamDevice.restore(mac0.exportState(key), key)
        val phone = SeamDevice.restore(phone0.exportState(key), key)

        val status = byteArrayOf(0x92.toByte(), 0x03, 0x02, 0x08, 82) // Payload{status{battery_pct: 82}}
        val opened = mac.open(phone.seal(mac.id(), status, 0u))
        assertArrayEquals(status, opened.payload)
        assertEquals(phone.id(), opened.peerId)
        assertEquals("Galaxy S25 Ultra", mac.peers().single().name)
    }

    @Test fun wrongPairingCodeIsRefused() {
        val mac = SeamDevice.generate()
        val phone = SeamDevice.generate()
        mac.startPairing()
        val start = phone.pairWith(
            mac.identityKey(), mac.relayKey(), "Mac", mac.prekeyBundles(1u).first(),
            ByteArray(32), "Phone", "Model",
        )
        assertThrows(SeamException.PairingFailed::class.java) { mac.acceptPairing(start.envelope) }
    }

    @Test fun unknownSenderAndBadStateAreRefused() {
        val (mac, _) = paired()
        val stranger = SeamDevice.generate()
        val env = stranger.pairWith(
            mac.identityKey(), mac.relayKey(), "Mac", mac.prekeyBundles(1u).first(),
            ByteArray(32), "x", "y",
        ).envelope
        assertThrows(SeamException.NotPaired::class.java) { mac.open(env) }
        assertThrows(SeamException.BadState::class.java) { SeamDevice.restore(byteArrayOf(1, 2, 3), key) }
    }

    @Test fun fileChunksRoundTripInOrderAndRefuseTruncation() {
        val sealer = FileSealer()
        val receiver = FileReceiver(sealer.key(), sealer.fileId())
        val full = ByteArray(chunkDataSize().toInt()) { 1 }
        val c0 = sealer.sealNext(full, false)
        val c1 = sealer.sealNext("tail".toByteArray(), true)
        assertThrows(SeamException.Invalid::class.java) { sealer.sealNext("again".toByteArray(), true) }
        assertThrows(SeamException.Invalid::class.java) { FileReceiver(sealer.key(), sealer.fileId()).openNext(c1, true) }
        assertArrayEquals(full, receiver.openNext(c0, false))
        assertThrows(SeamException.Invalid::class.java) { receiver.finish() }
        assertArrayEquals("tail".toByteArray(), receiver.openNext(c1, true))
        receiver.finish()
    }
}
