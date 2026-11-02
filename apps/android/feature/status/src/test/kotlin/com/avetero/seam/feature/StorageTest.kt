// SPDX-License-Identifier: AGPL-3.0-only
package com.avetero.seam.feature

import org.junit.Assert.assertEquals
import org.junit.Test

class StorageTest {
    @Test
    fun freeSpaceIsSentToTheNearestHundredMegabytes() {
        assertEquals(112_000_000_000, roundedFree(112_049_999_999))
        assertEquals(112_100_000_000, roundedFree(112_050_000_000))
        assertEquals(0, roundedFree(0))
    }
}
