// SPDX-License-Identifier: AGPL-3.0-only
package com.avetero.seam.feature.clipboard

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LogMatchTest {
    private val pkg = "com.avetero.seam.full"

    @Test fun matchesOurRefusal() {
        assertTrue(LogMatch.isOurDenial("E/ClipboardService( 1234): Denying clipboard access to com.avetero.seam.full, application is not in focus nor is it a system service for user 0", pkg))
    }

    @Test fun ignoresOtherAppsAndOtherLines() {
        assertFalse(LogMatch.isOurDenial("E/ClipboardService( 1234): Denying clipboard access to com.other.app, application is not in focus", pkg))
        assertFalse(LogMatch.isOurDenial("E/ClipboardService( 1234): clipboard access to com.avetero.seam.full granted", pkg))
    }
}
