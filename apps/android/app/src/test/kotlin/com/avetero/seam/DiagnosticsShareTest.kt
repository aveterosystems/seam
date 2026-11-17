// SPDX-License-Identifier: AGPL-3.0-only
package com.avetero.seam

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** What goes into the share sheet fits through Binder, however full the recorder is. */
class DiagnosticsShareTest {
    private val head = "Seam diagnostics\nSeam 0.5.1 (full) on Android 15 (API 35), network: mobile\nexported 2026-11-17T17:00:00.000Z"

    @Test fun aSmallExportIsSharedWhole() {
        val text = "$head\n2026-11-17T16:59:00.000Z tor bootstrapping\n"
        assertEquals(text, Diagnostics.fitForShare(text))
    }

    @Test fun aFullRecorderKeepsTheHeaderAndTheNewestEventsWithinTheLimit() {
        // The recorder's worst case: 2,000 events of 400 characters, 1 MB as UTF-16
        val events = (1..2_000).map { n -> "2026-11-17T16:00:00.000Z arti " + "x".repeat(370) + " #$n" }
        val text = head + "\n" + events.joinToString("\n") + "\n"
        val shared = Diagnostics.fitForShare(text)
        assertTrue("${shared.length} chars", shared.length <= Diagnostics.MAX_SHARE_CHARS)
        assertTrue("the header stays", shared.startsWith("$head\n"))
        assertTrue("says what was left out", "older lines left out" in shared)
        assertTrue("the newest stays", shared.trimEnd().endsWith(" #2000"))
        val whole = events.toSet()
        assertTrue("no event is cut in half", shared.lines().filter { " arti " in it }.all { it in whole })
    }
}
