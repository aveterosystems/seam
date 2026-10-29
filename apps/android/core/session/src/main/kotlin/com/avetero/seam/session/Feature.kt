// SPDX-License-Identifier: AGPL-3.0-only
package com.avetero.seam.session

import com.avetero.seam.protocol.v1.Payload

/** What a feature can do with the session. */
interface SessionApi {
    /** Seal a payload for the paired Mac and send it (or hold it until connected). */
    fun send(payload: Payload)
}

/** A self-contained capability (docs: architecture/app-internals.md). Registered once at startup. */
interface Feature {
    /** The payload cases this feature handles when they arrive from the Mac. */
    val handles: Set<Payload.BodyCase> get() = emptySet()

    /** Called each time the Mac becomes reachable. */
    fun onConnected(session: SessionApi) {}

    fun receive(payload: Payload, fromPeer: String) {}
}
