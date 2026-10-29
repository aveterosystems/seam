// SPDX-License-Identifier: AGPL-3.0-only
package com.avetero.seam.feature.clipboard

/**
 * What crosses between the two clipboards. The same rules run on the Mac (ClipRules.swift):
 * nothing empty or oversized, no duplicates, and never an echo of what the other side just sent,
 * so a copy can't bounce back and forth.
 */
class ClipRules(private val maxBytes: Int = MAX_BYTES) {
    private var lastSent: String? = null
    private var lastReceived: String? = null

    /** The text to send for something copied here, or null to skip it. */
    fun outgoing(text: String?): String? {
        if (text.isNullOrEmpty() || text.toByteArray().size > maxBytes) return null
        if (text == lastReceived || text == lastSent) return null
        lastSent = text
        lastReceived = null
        return text
    }

    /** Something the user explicitly sent (share sheet, Send button): only the size rules apply. */
    fun forced(text: String?): String? {
        if (text.isNullOrEmpty() || text.toByteArray().size > maxBytes) return null
        lastSent = text
        return text
    }

    /** Text from the other side to put on this clipboard, or null to ignore it. */
    fun incoming(text: String): String? {
        if (text.isEmpty() || text.toByteArray().size > maxBytes) return null
        lastReceived = text
        lastSent = null
        return text
    }

    companion object {
        /** Fits the relay's 256 KB padding bucket with room for the envelope. */
        const val MAX_BYTES = 200_000
    }
}
