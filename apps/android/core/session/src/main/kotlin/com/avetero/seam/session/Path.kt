// SPDX-License-Identifier: AGPL-3.0-only
package com.avetero.seam.session

import com.avetero.seam.protocol.v1.ClientFrame
import com.avetero.seam.protocol.v1.ServerFrame

/**
 * One way to reach the Mac (the relay, or Same Wi‑Fi). The path owns connecting, logging in,
 * backing off and giving up; Session owns when a connection is wanted (decision 0005) and what
 * goes over it. Runs on Session's single-threaded scope.
 */
internal interface Path {
    /** Connected and proven: messages can go. */
    val up: Boolean

    /** Up, connecting, looking, or waiting to try again: something [stop] would close. */
    val active: Boolean

    /** Connect, unless already up, connecting or waiting to try again. */
    fun start()

    /** Close on purpose: no events and no retries until the next [start]. */
    fun stop()

    fun send(frame: ClientFrame): Boolean
}

/** What a [Path] reports to Session. */
internal interface PathEvents {
    fun onUp(path: Path)

    /** A connection, or an attempt at one, ended. The path tries again by itself while it's wanted. */
    fun onDown(path: Path)

    /** A frame from the Mac or the relay that the path doesn't handle itself. */
    fun onFrame(path: Path, frame: ServerFrame)

    /** Gave up for good; [reason] is a fixed text for the user. */
    fun onFailed(path: Path, reason: String)
}

/** A plain byte pipe under a path: a WebSocket to the relay, or a TCP socket on Same Wi‑Fi. */
interface Link {
    fun send(frame: ByteArray): Boolean
    fun close()
    /** Closes at once, dropping anything still queued: for a peer that never proved who it is. */
    fun abort() = close()
}

interface LinkListener {
    fun onFrame(frame: ByteArray)
    fun onClosed(reason: String)
}

/** A connection that has lasted this long earns a quick reconnect again (SR-2-61). */
internal const val STABLE_MS = 30_000L
