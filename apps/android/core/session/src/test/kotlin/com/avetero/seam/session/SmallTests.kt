// SPDX-License-Identifier: AGPL-3.0-only
package com.avetero.seam.session

import com.avetero.seam.protocol.v1.Envelope
import com.google.protobuf.ByteString
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

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
    private val onion = "a".repeat(55) + "7.onion"
    private fun url(v: String = "2", o: String = onion, d: String = b64(32), i: String = b64(33)) =
        "seam://pair?v=$v&o=$o&d=$d&t=${b64(32)}&i=$i&k=${b64(32)}&n=MacBook%20Pro"

    @Test fun parsesAValidCode() {
        val qr = PairingQr.parse(url())!!
        assertEquals(onion, qr.onionAddress)
        assertEquals("MacBook Pro", qr.macName)
        assertEquals(32, qr.discoverySecret.size)
        assertEquals(33, qr.identityKey.size)
    }

    @Test fun refusesAnythingElse() {
        assertNull(PairingQr.parse("https://example.com"))
        assertNull(PairingQr.parse(url(v = "3")))
        assertNull(PairingQr.parse(url(d = b64(31))))
        assertNull(PairingQr.parse(url(i = "not*base64")))
        assertNull(PairingQr.parse(url().replace("&k=", "&x=")))
        assertNull(PairingQr.parse(url().replace("&d=", "&x=")))
    }

    @Test fun onlyAnOnionAddressIsEverDialled() {
        for (o in listOf("", "relay.avetero.com", "a".repeat(56), "A".repeat(56) + ".onion", "a".repeat(55) + "1.onion",
            "a".repeat(55) + ".onion", onion + ".evil.com", "evil.com%2F" + onion, "a".repeat(600) + ".onion")) {
            assertNull(o, PairingQr.parse(url(o = o)))
        }
    }

    @Test fun aRelayEraCodeIsRefusedAndRecognised() {
        val v1 = "seam://pair?v=1&r=wss%3A%2F%2Frelay.avetero.com&g=${b64(16)}&t=${b64(32)}&i=${b64(33)}&k=${b64(32)}&n=Mac"
        assertNull(PairingQr.parse(v1))
        assertTrue(PairingQr.isOldVersion(v1))
        assertTrue(!PairingQr.isOldVersion(url()))
        assertTrue(!PairingQr.isOldVersion("https://example.com/?v=1"))
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
