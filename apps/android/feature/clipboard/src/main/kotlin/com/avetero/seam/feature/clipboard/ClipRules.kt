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

    /** The Mac was forgotten: nothing it sent counts as an echo any more. */
    fun reset() {
        lastSent = null
        lastSentAt = 0L
        lastReceived = null
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

        const val PREVIEW_CHARS = 300

        /**
         * What the share confirmation shows: enough to recognise it, never a wall of text. Invisible
         * characters (bidi overrides, zero-width, controls) and long blank runs are shown, so the
         * visible part can't hide what else goes (SR-11-03).
         */
        fun preview(text: String): String {
            val shown = text.take(PREVIEW_CHARS)
                .replace(Regex("[\\u0000-\\u0008\\u000B-\\u001F\\u007F\\u200B-\\u200F\\u202A-\\u202E\\u2060-\\u2069\\uFEFF]")) {
                    "⟨U+%04X⟩".format(it.value[0].code)
                }
                .replace(Regex("[ \\t]{4,}|\\n{3,}")) { if (it.value[0] == '\n') "\n⟨${it.value.length} line breaks⟩\n" else " ⟨${it.value.length} spaces⟩ " }
            return if (text.length <= PREVIEW_CHARS) shown else "$shown…"
        }

        /** How many characters the preview leaves out: shown on the sheet, so a long share isn't a surprise. */
        fun notShown(text: String): Int = (text.length - PREVIEW_CHARS).coerceAtLeast(0)
    }
}
