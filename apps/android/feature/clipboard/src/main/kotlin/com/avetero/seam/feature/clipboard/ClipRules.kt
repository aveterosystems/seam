// SPDX-License-Identifier: AGPL-3.0-only
package com.avetero.seam.feature.clipboard

/**
 * What crosses between the two clipboards. The same rules run on the Mac (ClipRules.swift):
 * nothing empty or oversized, the same copy not sent twice in quick succession, and never an echo
 * of what the other side just sent, so a copy can't bounce back and forth.
 */
class ClipRules(private val maxBytes: Int = MAX_BYTES) {
    private var lastSent: String? = null
    private var lastSentAt = 0L
    private var lastReceived: String? = null

    /**
     * The text to send for something copied here, or null to skip it. A repeat counts as a duplicate
     * only within [REPEAT_MS], so a copy that failed to send goes if the user copies it again (SR-4-10).
     */
    fun outgoing(text: String?, now: Long = System.currentTimeMillis()): String? {
        if (!fits(text) || text == lastReceived) return null
        if (text == lastSent && now - lastSentAt < REPEAT_MS) return null
        lastSent = text
        lastSentAt = now
        lastReceived = null
        return text
    }

    /** Something the user explicitly sent (share sheet, Send button): only the size rules apply. */
    fun forced(text: String?, now: Long = System.currentTimeMillis()): String? {
        if (!fits(text)) return null
        lastSent = text
        lastSentAt = now
        return text
    }

    /** Text from the other side to put on this clipboard, or null to ignore it. */
    fun incoming(text: String): String? {
        if (!fits(text)) return null
        lastReceived = text
        lastSent = null
        return text
    }

    private fun fits(text: String?): Boolean = !text.isNullOrEmpty() && text.toByteArray().size <= maxBytes

    companion object {
        /** Fits the relay's 256 KB padding bucket with room for the envelope. */
        const val MAX_BYTES = 200_000
        const val REPEAT_MS = 2_000L

        /** What the share confirmation shows: enough to recognise it, never a wall of text. */
        fun preview(text: String): String = if (text.length <= 300) text else text.take(300) + "…"
    }
}
