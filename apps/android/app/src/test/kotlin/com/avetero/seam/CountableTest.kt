// SPDX-License-Identifier: AGPL-3.0-only
package com.avetero.seam

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CountableTest {
    @Test fun countsWholeNumbersAndKeepsTheirUnit() {
        assertEquals(64 to "%", countable("64%"))
        assertEquals(140 to " GB", countable("140 GB"))
        assertEquals(7 to "", countable("7"))
        assertNull(countable("1.5 TB"))
        assertNull(countable("—"))
    }
}
