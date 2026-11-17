// SPDX-License-Identifier: AGPL-3.0-only
package com.avetero.seam.session

import com.avetero.seam.crypto.SeamDevice
import com.avetero.seam.crypto.TorBlock
import com.avetero.seam.crypto.TorConnectListener
import com.avetero.seam.crypto.TorInterface
import com.avetero.seam.crypto.TorService
import com.avetero.seam.crypto.TorState
import com.avetero.seam.crypto.TorStateListener
import com.avetero.seam.crypto.diagnosticsExport
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.Timeout

/**
 * Pairing through the real [TorLan] when this phone's own Tor fails to start: Arti is faked, so
 * its start can be blocked on cue, the way a slow or filtered mobile network blocks it.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class TorPairingTest {
    @get:Rule val timeout: Timeout = Timeout.seconds(30)

    /** Arti, as the session sees it: each start waits for the test to say how it went. */
    private class FakeArti : TorInterface {
        var starts = 0
        var started: TorStateListener? = null
        val dials = mutableListOf<String>()
        override fun start(listener: TorStateListener) { starts++; started = listener }
        override fun setDormant(dormant: Boolean) = Unit
        override fun connect(onionAddress: String, discoverySecret: ByteArray, listener: TorConnectListener) { dials += onionAddress }
        override fun host(serviceSecret: ByteArray, clients: List<ByteArray>, listener: TorConnectListener): TorService? = error("the phone never hosts")
        override fun state(): TorState = TorState.Off
    }

    private val arti = FakeArti()
    private val mac = SeamDevice.generate()
    private val qr = PairingQr("c".repeat(56) + ".onion", ByteArray(32) { 5 }, mac.startPairing().token, mac.identityKey(), mac.relayKey(), "MacBook Pro")
    private val scopes = mutableListOf<TestScope>()

    @After fun stop() = scopes.forEach { it.cancel() }

    private fun TestScope.pairing(): Session = Session(
        MemoryStore(), "Galaxy S25 Ultra", "SM-S938B",
        TestScope(StandardTestDispatcher(testScheduler)).also { scopes += it },
        now = { testScheduler.currentTime }, debugLog = null, lan = null, tor = TorLan(arti),
        settingsStore = MemorySettingsStore(), pendingStore = MemoryPendingStore(),
    ).also {
        it.pair(qr)
        runCurrent()
    }

    private fun blocked() = arti.started!!.onState(TorState.Blocked(TorBlock.FILTERED))

    @Test fun aFailedPairingIsInTheDiagnosticsExportWithoutTheCodesSecrets() = runTest {
        pairing()
        blocked()
        advanceTimeBy(PAIRING_GIVES_UP_MS)
        runCurrent()
        val out = diagnosticsExport("Seam test on Android")
        for (step in listOf("session pairing: started", "session tor: discovering", "session pairing failed: ")) {
            assertTrue("no \"$step\" in:\n$out", step in out)
        }
        val hex = { b: ByteArray -> b.joinToString("") { "%02x".format(it) } }
        val b64 = { b: ByteArray -> java.util.Base64.getEncoder().withoutPadding().encodeToString(b) }
        val url = { b: ByteArray -> java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(b) }
        for (secret in listOf(qr.discoverySecret, qr.token, qr.identityKey, qr.relayKey)) {
            for (e in listOf(hex(secret), b64(secret), url(secret))) assertFalse("a secret in:\n$out", e.take(12) in out)
        }
        for (word in listOf(qr.onionAddress.take(16), "MacBook Pro", "Galaxy S25 Ultra")) assertFalse("$word in:\n$out", word in out)
    }

    private companion object {
        const val PAIRING_GIVES_UP_MS = 3 * 60_000L + 1
    }
}
