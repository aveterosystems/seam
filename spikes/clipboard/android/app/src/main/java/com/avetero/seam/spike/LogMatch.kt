package com.avetero.seam.spike

/**
 * Android 10+ refuses background clipboard reads. When another app copies, ClipboardService
 * checks every registered listener and logs a refusal for ours, e.g.
 * "E/ClipboardService( 1234): Denying clipboard access to com.avetero.seam.spike, application is not in focus..."
 * That refusal line is our signal that something new was copied.
 */
object LogMatch {
    fun isOurDenial(line: String, pkg: String): Boolean =
        line.contains("clipboard access to $pkg", ignoreCase = true) &&
            line.contains("deny", ignoreCase = true)
}
