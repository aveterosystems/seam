// SPDX-License-Identifier: AGPL-3.0-only
package com.avetero.seam.feature.clipboard

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LogMatchTest {
    private val pkg = "com.avetero.seam.full"
    private fun line(uid: String, msg: String = "Denying clipboard access to com.avetero.seam.full, application is not in focus nor is it a system service for user 0") =
        "10-29 14:03:01.123 $uid  1534  2210 E ClipboardService: $msg"

    @Test fun matchesTheSystemsRefusal() {
        assertTrue(LogMatch.isOurDenial(line("system"), pkg))
        assertTrue(LogMatch.isOurDenial(line("1000"), pkg))
    }

    @Test fun sr_4_02_ignoresTheSameLineWrittenByAnyOtherApp() {
        assertFalse(LogMatch.isOurDenial(line("u0_a245"), pkg))
        assertFalse(LogMatch.isOurDenial(line("10245"), pkg))
        assertFalse("no uid column at all", LogMatch.isOurDenial("E/ClipboardService( 1234): Denying clipboard access to com.avetero.seam.full", pkg))
    }

    @Test fun ignoresOtherAppsAndOtherLines() {
        assertFalse(LogMatch.isOurDenial(line("system", "Denying clipboard access to com.other.app, application is not in focus"), pkg))
        assertFalse(LogMatch.isOurDenial(line("system", "Denying clipboard access to com.avetero.seam.full.evil, not in focus"), pkg))
        assertFalse(LogMatch.isOurDenial(line("system", "clipboard access to com.avetero.seam.full granted"), pkg))
    }
}
