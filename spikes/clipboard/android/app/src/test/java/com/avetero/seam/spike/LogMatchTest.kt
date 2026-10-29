package com.avetero.seam.spike

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LogMatchTest {
    private val pkg = "com.avetero.seam.spike"

    @Test fun matchesTheRefusalForOurPackage() {
        assertTrue(LogMatch.isOurDenial(
            "E/ClipboardService( 1650): Denying clipboard access to com.avetero.seam.spike, application is not in focus nor is it a system service for user 0",
            pkg))
    }

    @Test fun ignoresOtherPackagesAndOtherLines() {
        assertFalse(LogMatch.isOurDenial("E/ClipboardService( 1650): Denying clipboard access to com.other.app, application is not in focus", pkg))
        assertFalse(LogMatch.isOurDenial("I/ClipboardService( 1650): clipboard access to com.avetero.seam.spike granted", pkg))
    }
}
