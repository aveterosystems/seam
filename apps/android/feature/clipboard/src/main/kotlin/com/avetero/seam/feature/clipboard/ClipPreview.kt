// SPDX-License-Identifier: AGPL-3.0-only
package com.avetero.seam.feature.clipboard

/** What the share confirmation shows. The rules for what crosses are the core's (ClipRules). */
object ClipPreview {
    const val PREVIEW_CHARS = 300

    /**
     * Enough to recognise it, never a wall of text. Invisible characters (bidi overrides,
     * zero-width, controls) and long blank runs are shown, so the visible part can't hide what else
     * goes (SR-11-03).
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
