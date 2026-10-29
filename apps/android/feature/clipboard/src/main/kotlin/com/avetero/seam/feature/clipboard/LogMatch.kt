// SPDX-License-Identifier: AGPL-3.0-only
package com.avetero.seam.feature.clipboard

/**
 * Android 10+ refuses background clipboard reads. When another app copies, ClipboardService
 * checks every registered listener and logs a refusal for ours. That line is only a signal that
 * something was copied: its text is never used.
 *
 * Any app can write a line that says the same thing, so it counts only when the system wrote it
 * (SR-4-02). AutoClipboard reads `logcat -v threadtime -v uid`, whose lines look like
 * "10-29 14:03:01.123 system  1534  2210 E ClipboardService: Denying clipboard access to com.avetero.seam.full, ..."
 * with the writer's uid (name or number) in the third column.
 */
internal object LogMatch {
    private val systemUids = setOf("system", "1000")

    fun isOurDenial(line: String, pkg: String): Boolean {
        val cols = line.trim().split(Regex("\\s+"), limit = 7)
        if (cols.size < 7 || cols[2] !in systemUids || !cols[6].startsWith("ClipboardService")) return false
        val target = Regex("clipboard access to ${Regex.escape(pkg)}(?![\\w.])", RegexOption.IGNORE_CASE)
        return target.containsMatchIn(cols[6]) && cols[6].contains("deny", ignoreCase = true)
    }
}
