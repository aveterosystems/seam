// SPDX-License-Identifier: AGPL-3.0-only
package com.avetero.seam

import org.junit.Assert.assertEquals
import org.junit.Test

class AgoTest {
    @Test fun saysHowLongAgoInPlainWords() {
        assertEquals("just now", ago(59_000))
        assertEquals("5 min ago", ago(5 * 60_000L + 30_000))
        assertEquals("2 h ago", ago(2 * 3_600_000L + 1))
    }
}
