// SPDX-License-Identifier: AGPL-3.0-only
package com.avetero.seam.session

import com.avetero.seam.crypto.LanChannel
import com.avetero.seam.crypto.SeamDevice
import com.avetero.seam.crypto.SeamException
import com.avetero.seam.protocol.v1.ClientFrame
import com.avetero.seam.protocol.v1.ServerFrame
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * A direct path to the Mac over any [Lan] dialer: Same Wi‑Fi (NSD and TCP) or Tor (the Mac's onion
 * service). Finds candidates, proves each is our Mac through the core's LanChannel (Noise and both
 * proofs), and skips ones that refused us for a while. At most one connection attempt at a time.
 * While pairing ([pairing] non-null) it opens a pairing channel instead: the Mac pinned from the QR
 * code, the phone proving it scanned it.
 */
internal class LanPath(
    private val lan: Lan,
    private val device: SeamDevice,
    private val scope: CoroutineScope,
    private val now: () -> Long,
    private val trace: ((String) -> Unit)?,
    /** The paired Mac's id; null: nobody to look for. */
    private val peer: () -> String?,
    /** Whether to keep trying: this phone's switch is on and something needs a connection (decision 0005). */
    private val wanted: () -> Boolean,
    private val events: PathEvents,
    /** The QR code being paired with, if any: then the channel is a pairing one. */
    private val pairing: () -> PairingQr? = { null },
    /** Same Wi‑Fi: only addresses inside the local network. Tor dials its one onion address. */
    private val localOnly: Boolean = true,
    /** How long a connection may take to prove itself: milliseconds locally, much longer through Tor. */
    private val authMs: Long = AUTH_MS,
    /** "lan" or "tor", for the trace. */
    private val name: String = "lan",
) : Path {
    private var link: Link? = null
    private var channel: LanChannel? = null
    private var generation = 0
    private var authed = false
    private var deadline: Job? = null
    private var retry: Job? = null
    /** After an authenticated link drops: backs off, so a reset-every-second attacker can't spin us (cf. SR-8-01). */
    private val backoff = Backoff()
    private var reconnect: Job? = null
    private var stable: Job? = null
    private var discovering = false
    private val found = linkedSetOf<LanAddress>()
    /** Services that failed to authenticate, and until when each is skipped. Kept across discovery restarts (so a crowd of fakes can't hide the Mac), forgotten after [REFUSED_MS]. */
    private val refused = mutableMapOf<LanAddress, Long>()
    /** Where our Mac last authenticated (memory only): never skipped for long. */
    private var lastGood: LanAddress? = null

    override val up get() = authed
    override val active get() = link != null || discovering || retry?.isActive == true || reconnect?.isActive == true

    override fun start() {
        if (link != null || authed || (peer() == null && pairing() == null) || reconnect?.isActive == true || !wanted()) return // rule 8 (SR-29-09)
        if (discovering) return tryNext()
        discovering = true
        found.clear()
        trace?.invoke("$name: discovering")
        lan.discover(
            found = { a ->
                scope.launch {
                    // An advert can name any address: only ever connect within the local network, and to a few
                    // Refused ones don't count toward the cap, so fakes can't crowd out the Mac
                    if (discovering && (!localOnly || isLocalNetwork(a.host)) && !isRefused(a) && found.size < MAX_FOUND) {
                        found += a
                        tryNext()
                    }
                }
            },
            lost = { a -> scope.launch { found.remove(a) } },
        )
    }

    override fun stop() {
        drop()
        stopDiscovery()
        retry?.cancel()
        reconnect?.cancel()
        found.clear()
        refused.clear()
    }

    override fun send(frame: ClientFrame): Boolean {
        val ch = channel ?: return false
        if (!authed) return false
        val bytes = try {
            ch.send(frame.toByteArray())
        } catch (_: Exception) {
            return false
        }
        return link?.send(bytes) == true
    }

    private fun tryNext() {
        if (link != null || authed || !wanted()) return
        val next = found.firstOrNull()
        if (next == null) {
            if (refused.isNotEmpty()) retryLater() // every Mac here refused us or was unreachable; more may be waiting past the cap
            return
        }
        connect(next)
    }

    private fun connect(a: LanAddress) {
        val qr = pairing()
        val ch = try {
            if (qr != null) LanChannel.pairingInitiator(device, qr.relayKey, qr.token) else LanChannel.initiator(device, peer() ?: return)
        } catch (_: Exception) {
            return
        }
        val mine = ++generation
        trace?.invoke("$name: connecting")
        val listener = object : LinkListener {
            override fun onFrame(frame: ByteArray) {
                scope.launch { if (mine == generation) received(frame, a) }
            }
            override fun onClosed(reason: String) {
                scope.launch {
                    trace?.invoke("$name closed: $reason")
                    if (mine == generation) closed(a)
                }
            }
        }
        channel = ch
        link = runCatching { lan.connect(a, listener) }.getOrNull() ?: return run { channel = null; refuse(a) }
        link?.send(ch.start())
        deadline?.cancel()
        deadline = scope.launch {
            delay(authMs)
            if (mine == generation && !authed) {
                trace?.invoke("$name: no proof in time")
                closed(a)
            }
        }
    }

    private fun received(bytes: ByteArray, a: LanAddress) {
        val ch = channel ?: return
        val out = try {
            ch.receive(bytes)
        } catch (e: Exception) {
            trace?.invoke("$name: channel failed")
            // Only a valid proof from another key is someone else's Mac; damaged bytes may be a
            // forged packet on our own Mac's connection, so that's just the short retry
            return closed(a, notOurMac = e is SeamException.UntrustedIdentity)
        }
        if (out.send.isNotEmpty()) link?.send(out.send)
        if (out.authenticated != null) {
            // The core only accepts our Mac (or, pairing, the Mac on the code); never trust anything else
            val expected = if (pairing() != null) PAIRING_PEER else peer()
            if (out.authenticated != expected) return closed(a, notOurMac = true)
            authed = true
            lastGood = a
            deadline?.cancel()
            stopDiscovery() // found it: no need to keep multicasting
            stable?.cancel()
            stable = scope.launch {
                delay(STABLE_MS)
                backoff.reset() // only a link that stayed up earns a quick reconnect
            }
            trace?.invoke("$name: authenticated")
            events.onUp(this)
        }
        for (f in out.frames) {
            if (channel !== ch) return // closed while handling an earlier frame (an Unpair, a failed save)
            val frame = runCatching { ServerFrame.parseFrom(f) }.getOrNull() ?: continue
            events.onFrame(this, frame)
        }
    }

    /** The link ended: try the next service if it never authenticated, or look again if it did. */
    private fun closed(a: LanAddress, notOurMac: Boolean = false) {
        val wasUp = authed
        drop()
        val mine = generation
        events.onDown(this)
        if (mine != generation) return // stopped meanwhile
        if (wasUp) {
            reconnect?.cancel()
            reconnect = scope.launch {
                delay(backoff.next())
                reconnect = null // this job is still active while it runs: let start() through
                start()
            }
            return
        }
        // Only a failed proof earns the long skip: a timeout or a dropped socket may be our own Mac
        // (busy, or made to drop us by a flood), and skipping it for long would hide it
        // Never the long skip where our Mac last proved itself: NN is anonymous, so whoever answers
        // first (an attacker winning the race) can make any address look foreign
        refuse(a, if (notOurMac && a != lastGood) REFUSED_MS else RETRY_MS)
        tryNext()
    }

    /** Closes our side; its callbacks are ignored from here on. */
    private fun drop() {
        generation++
        deadline?.cancel()
        stable?.cancel()
        // Only a proven Mac gets a graceful close: anything else (a stranger, a failed proof) is cut at once
        if (authed) link?.close() else link?.abort()
        link = null
        channel = null
        authed = false
    }

    private fun stopDiscovery() {
        if (!discovering) return
        discovering = false
        lan.stopDiscovery()
    }

    private fun refuse(a: LanAddress, forMs: Long = RETRY_MS) {
        refused[a] = now() + forMs
        found.remove(a)
        lan.forget(a)
    }

    private fun isRefused(a: LanAddress) = refused[a]?.let { now() < it } == true

    /** Looks again from scratch: services past the cap are only reported to a fresh discovery. */
    private fun retryLater() {
        if (retry?.isActive == true) return
        retry = scope.launch {
            delay(RETRY_MS)
            refused.entries.removeAll { now() >= it.value }
            stopDiscovery()
            start()
        }
    }

    companion object {
        /** The handshake and both proofs take milliseconds; anything slower isn't worth waiting for. */
        const val AUTH_MS = 10_000L
        /** Through Tor: the rendezvous alone can take tens of seconds, a fresh address a minute or more. */
        const val TOR_AUTH_MS = 90_000L
        /** What the core's LanChannel reports for a pairing channel. */
        const val PAIRING_PEER = "pairing"
        /** How soon to try Macs that refused or were unreachable again (one may have restarted). */
        private const val RETRY_MS = 30_000L
        /** More Seam services than this on one network is a flood, not a home. */
        private const val MAX_FOUND = 8
        /** How long a service that failed its proof (someone else's Mac) is skipped: a Mac that restarted gets a new port anyway. */
        private const val REFUSED_MS = 10 * 60_000L
    }
}
