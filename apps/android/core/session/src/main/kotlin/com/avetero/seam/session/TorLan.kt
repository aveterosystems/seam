// SPDX-License-Identifier: AGPL-3.0-only
package com.avetero.seam.session

import com.avetero.seam.crypto.TorConnectListener
import com.avetero.seam.crypto.TorInterface
import com.avetero.seam.crypto.TorState
import com.avetero.seam.crypto.TorStateListener
import com.avetero.seam.crypto.TorStream
import com.avetero.seam.crypto.TorStreamListener
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.job
import kotlinx.coroutines.launch

/** Where the phone dials through Tor: the Mac's onion address and this phone's discovery secret. */
class TorTarget(val onionAddress: String, val discoverySecret: ByteArray)

/** A [Lan] that dials one onion address: the session says which (null: none). */
interface TorDialer : Lan {
    var target: TorTarget?

    /** Tor on this phone is up: false while it starts, or when it couldn't. */
    val ready: Boolean get() = true
}

/**
 * The Tor path's plumbing, as a [Lan] so [LanPath] drives it like Same Wi‑Fi: "discovery" reports
 * the Mac's one onion address once Tor is ready, and a connection is a stream through Tor (Arti,
 * in process). Tor starts once, on first use, and is dormant whenever it has no stream open and
 * isn't looking for the Mac (no background traffic while Seam has nothing to say). A start that
 * fails while the Mac is still being looked for (pairing, or a connection wanted) is tried again
 * after [START_RETRY_MS]: nothing else would look again meanwhile (#92). Callbacks come on Tor's
 * threads; [scope] only times the retry.
 */
class TorLan(private val tor: TorInterface, private val scope: CoroutineScope) : TorDialer {
    @Volatile override var target: TorTarget? = null

    private val _state = MutableStateFlow<TorState>(TorState.Off)
    /** For the status line: starting, ready, or why Tor can't connect. */
    val state: StateFlow<TorState> = _state

    private val lock = Any()
    private var started = false
    private var found: ((LanAddress) -> Unit)? = null
    /** The retry waiting to fire; null once it has started (or none is due). */
    private var retry: Job? = null
    /** Goes up with each look and each stop: a retry serves only the look it was scheduled for. */
    private var looks = 0
    private var open = 0
    private var dormant: Boolean? = null

    override fun discover(found: (LanAddress) -> Unit, lost: (LanAddress) -> Unit) {
        val first = synchronized(lock) {
            this.found = found
            newLook()
            updateDormant()
            !started.also { started = true }
        }
        if (first) {
            tor.start(onState)
        } else if (_state.value is TorState.Ready) {
            announce()
        }
    }

    /** Hears Tor's states, on Tor's own threads. */
    private val onState = object : TorStateListener {
        override fun onState(state: TorState) {
            _state.value = state
            when (state) {
                is TorState.Ready -> {
                    // Tor ignores dormancy until it's ready: tell it again now
                    synchronized(lock) { dormant = null; updateDormant() }
                    announce()
                }
                // The next look tries again; while one is under way, so does a retry
                is TorState.Failed, is TorState.Blocked -> synchronized(lock) {
                    started = false
                    retryWhileLooking()
                }
                else -> Unit
            }
        }
    }

    override val ready: Boolean get() = _state.value is TorState.Ready

    /**
     * Called holding [lock]. At most one retry waits at a time. It only starts Tor again: the
     * look it serves is the one under way when it was scheduled, checked under [lock], so a look
     * that ended (or was replaced) in the meantime is never revived.
     */
    private fun retryWhileLooking() {
        if (found == null || retry != null) return
        val look = looks
        retry = scope.launch {
            delay(START_RETRY_MS)
            val go = synchronized(lock) {
                // From here a failure, even one reported mid-start, schedules the next; a stale
                // retry never frees a newer one's place
                if (retry === coroutineContext.job) retry = null
                (found != null && looks == look && !started).also { if (it) started = true }
            }
            // Outside the lock: Tor may report on another thread before `start` returns
            if (go) tor.start(onState)
        }
    }

    private fun announce() {
        val f = synchronized(lock) { found } ?: return
        val t = target ?: return
        f(LanAddress(t.onionAddress, PORT))
    }

    override fun stopDiscovery() = synchronized(lock) {
        found = null
        newLook()
        updateDormant()
    }

    override fun connect(address: LanAddress, listener: LinkListener): Link {
        val t = target
        // Only ever the address this phone was given now: one from an older pairing is never dialled
        if (t == null || t.onionAddress != address.host) throw IllegalStateException("no Tor target")
        synchronized(lock) {
            open++
            updateDormant()
        }
        val link = TorLink(listener)
        tor.connect(t.onionAddress, t.discoverySecret, object : TorConnectListener {
            override fun onOpen(stream: TorStream) = link.opened(stream)
            override fun onFailed() = link.finish("unreachable")
        })
        return link
    }

    /** Called holding [lock]: a look began or ended, so a retry due for the last one is dropped. */
    private fun newLook() {
        looks++
        retry?.cancel()
        retry = null
    }

    /** Called holding [lock]. Repeats nothing: Tor is only told when it changes. */
    private fun updateDormant() {
        val sleep = open == 0 && found == null
        if (!started && sleep) return // never started: nothing to put to sleep
        if (dormant == sleep) return
        dormant = sleep
        tor.setDormant(sleep)
    }

    /** A link whose stream may not be open yet: what's sent meanwhile goes once it is, in order. */
    private inner class TorLink(private val listener: LinkListener) : Link {
        private var stream: TorStream? = null
        private val waiting = mutableListOf<ByteArray>()
        private var accepting = true
        private val done = AtomicBoolean(false)

        fun opened(s: TorStream) {
            synchronized(this) {
                if (!accepting) return s.shutdown().also { s.close() } // closed while connecting: free it now, not at GC
                stream = s
                waiting.forEach { s.send(it) }
                waiting.clear()
            }
            s.listen(object : TorStreamListener {
                override fun onBytes(bytes: ByteArray) = listener.onFrame(bytes)
                override fun onClosed() = finish("closed")
            })
        }

        fun finish(reason: String) {
            synchronized(this) { accepting = false }
            if (!done.compareAndSet(false, true)) return
            synchronized(lock) {
                open--
                updateDormant()
            }
            listener.onClosed(reason)
        }

        override fun send(frame: ByteArray): Boolean = synchronized(this) {
            if (!accepting) return false
            stream?.send(frame) ?: waiting.add(frame)
        }

        override fun close() {
            val s = synchronized(this) {
                accepting = false
                stream
            }
            // An open stream reports its own close; one still connecting is done now
            if (s != null) s.shutdown() else finish("closed by us")
        }
    }

    internal companion object {
        /** Onion ports are virtual and the core always dials Seam's one; this only fills [LanAddress]. */
        private const val PORT = 1
        /** How soon a start that failed goes again while the Mac is being looked for. Bootstrapping offline fails fast. */
        const val START_RETRY_MS = 10_000L
    }
}
