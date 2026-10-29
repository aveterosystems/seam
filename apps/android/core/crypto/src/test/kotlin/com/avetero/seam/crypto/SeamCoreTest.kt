// SPDX-License-Identifier: AGPL-3.0-only
package com.avetero.seam.crypto

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
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

    @Test fun fileChunksRoundTripAndRejectReordering() {
        val k = newFileKey()
        val id = newFileId()
        val sealed = sealChunk(k, id, 0u, false, "chunk".toByteArray())
        assertArrayEquals("chunk".toByteArray(), openChunk(k, id, 0u, false, sealed))
        assertThrows(SeamException.Invalid::class.java) { openChunk(k, id, 1u, false, sealed) }
    }
}
