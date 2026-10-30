// SPDX-License-Identifier: AGPL-3.0-only
package com.avetero.seam.feature.clipboard

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CopyMatchTest {
    @Test fun copyButtonsAndMenuItems() {
        assertTrue(CopyMatch.isCopy(listOf("Copy"), "Copy"))
        assertTrue(CopyMatch.isCopy(listOf(" copy "), "Copy"))
        assertTrue(CopyMatch.isCopy(listOf("Copy link address"), "Copy"))
        assertTrue(CopyMatch.isCopy(listOf(null, "Copy"), "Copy"))
        assertTrue(CopyMatch.isCopy(listOf("Kopieren"), "Kopieren"))
    }

    @Test fun notOtherTaps() {
        assertFalse(CopyMatch.isCopy(listOf("Copyright"), "Copy"))
        assertFalse(CopyMatch.isCopy(listOf("Paste"), "Copy"))
        assertFalse(CopyMatch.isCopy(listOf("Make a copy"), "Copy"))
        assertFalse(CopyMatch.isCopy(listOf(null), "Copy"))
        assertFalse(CopyMatch.isCopy(emptyList(), "Copy"))
    }
}
