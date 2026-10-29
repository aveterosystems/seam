// SPDX-License-Identifier: AGPL-3.0-only
package com.avetero.seam.feature.clipboard

/**
 * Android 10+ refuses background clipboard reads. When another app copies, ClipboardService
 * checks every registered listener and logs a refusal for ours, e.g.
 * "E/ClipboardService( 1234): Denying clipboard access to com.avetero.seam.full, application is not in focus..."
 * That line is only a signal that something was copied: its text is never used.
 */
internal object LogMatch {
    fun isOurDenial(line: String, pkg: String): Boolean =
        line.contains("clipboard access to $pkg", ignoreCase = true) && line.contains("deny", ignoreCase = true)
}
