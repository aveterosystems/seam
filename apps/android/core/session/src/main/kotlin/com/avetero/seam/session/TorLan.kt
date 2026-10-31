// SPDX-License-Identifier: AGPL-3.0-only
package com.avetero.seam.session

import com.avetero.seam.crypto.TorConnectListener
import com.avetero.seam.crypto.TorInterface
import com.avetero.seam.crypto.TorState
import com.avetero.seam.crypto.TorStateListener
import com.avetero.seam.crypto.TorStream
import com.avetero.seam.crypto.TorStreamListener
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** Where the phone dials through Tor: the Mac's onion address and this phone's discovery secret. */
class TorTarget(val onionAddress: String, val discoverySecret: ByteArray)

/** A [Lan] that dials one onion address: the session says which (null: none). */
interface TorDialer : Lan {
    var target: TorTarget?
}

/**
 * The Tor path's plumbing, as a [Lan] so [LanPath] drives it like Same Wi‑Fi: "discovery" reports
 * the Mac's one onion address once Tor is ready, and a connection is a stream through Tor (Arti,
 * in process). Tor starts once, on first use, and is dormant whenever it has no stream open and
 * isn't looking for the Mac (decision 0005: no background traffic while Seam has nothing to say).
 * Callbacks come on Tor's threads.
 */
class TorLan(private val tor: TorInterface) : TorDialer {
    @Volatile override var target: TorTarget? = null

    private val _state = MutableStateFlow<TorState>(TorState.Off)
    /** For the status line: starting, ready, or why Tor can't connect. */
    val state: StateFlow<TorState> = _state

    private val lock = Any()
    private var started = false
    private var found: ((LanAddress) -> Unit)? = null
    private var open = 0
    private var dormant: Boolean? = null

    override fun discover(found: (LanAddress) -> Unit, lost: (LanAddress) -> Unit) {
        val first = synchronized(lock) {
            this.found = found
            updateDormant()
            !started.also { started = true }
        }
        if (first) {
            tor.start(object : TorStateListener {
                override fun onState(state: TorState) {
                    _state.value = state
                    when (state) {
                        is TorState.Ready -> {
                            // Tor ignores dormancy until it's ready: tell it again now
                            synchronized(lock) { dormant = null; updateDormant() }
                            announce()
                        }
                        is TorState.Failed, is TorState.Blocked -> synchronized(lock) { started = false } // the next look tries again
                        else -> Unit
                    }
                }
            })
        } else if (_state.value is TorState.Ready) {
            announce()
        }
    }

    private fun announce() {
        val f = synchronized(lock) { found } ?: return
        val t = target ?: return
        f(LanAddress(t.onionAddress, PORT))
    }

    override fun stopDiscovery() = synchronized(lock) {
        found = null
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

    private companion object {
        /** Onion ports are virtual and the core always dials Seam's one; this only fills [LanAddress]. */
        const val PORT = 1
    }
}
