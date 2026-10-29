// SPDX-License-Identifier: AGPL-3.0-only
package com.avetero.seam.crypto

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

/** Calls the Rust core through the generated Kotlin bindings, the way the Android app will. */
class SeamCoreTest {
    private fun paired(): Pair<SeamDevice, SeamDevice> {
        val mac = SeamDevice("mac")
        val phone = SeamDevice("phone")
        mac.pinPeer("phone", phone.identityKey())
        phone.pinPeer("mac", mac.identityKey())
        return mac to phone
    }

    @Test fun messagesFlowBothWays() {
        val (mac, phone) = paired()
        phone.startSession("mac", mac.prekeyBundle())

        val sealed = phone.encrypt("mac", "from Kotlin".toByteArray())
        assertEquals(3, sealed.kind.toInt()) // PreKeySignalMessage: carries the session setup
        assertArrayEquals("from Kotlin".toByteArray(), mac.decrypt("phone", sealed))

        val reply = mac.encrypt("phone", "back".toByteArray())
        assertArrayEquals("back".toByteArray(), phone.decrypt("mac", reply))
    }

    @Test fun impostorIsRefusedWithTheRightError() {
        val (_, phone) = paired()
        val impostor = SeamDevice("mac")
        assertThrows(SeamException.UntrustedIdentity::class.java) {
            phone.startSession("mac", impostor.prekeyBundle())
        }
    }

    @Test fun unpairedPeerIsRefused() {
        val phone = SeamDevice("phone")
        val stranger = SeamDevice("stranger")
        assertThrows(SeamException.NotPaired::class.java) {
            phone.startSession("stranger", stranger.prekeyBundle())
        }
    }

    @Test fun fileChunksRoundTripAndRejectReordering() {
        val key = newFileKey()
        val id = newFileId()
        val sealed = sealChunk(key, id, 0u, false, "chunk".toByteArray())
        assertArrayEquals("chunk".toByteArray(), openChunk(key, id, 0u, false, sealed))
        assertThrows(SeamException.Invalid::class.java) { openChunk(key, id, 1u, false, sealed) }
    }
}
