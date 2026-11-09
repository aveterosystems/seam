// SPDX-License-Identifier: AGPL-3.0-only
package com.avetero.seam.feature

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PowerTest {
    @Test
    fun powerReadsEitherSignAndEitherUnit() {
        assertEquals(1900, powerMw(-500_000, 3800)) // µA, discharging as negative
        assertEquals(1900, powerMw(500_000, 3800))
        assertEquals(1900, powerMw(500, 3800)) // a maker reporting mA
        assertNull(powerMw(Int.MIN_VALUE, 3800)) // not supported
        assertNull(powerMw(0, 3800))
        assertNull(powerMw(500_000, -1))
    }

    @Test
    fun timeLeftWaitsForThreePointsThenFollowsTheRate() {
        val d = Drain()
        val min = 60_000L
        assertNull(d.minutesLeft(0, 80, charging = false))
        assertNull(d.minutesLeft(20 * min, 78, charging = false))
        // 3 points in 30 min: 10 min a point, 77 points left
        assertEquals(770, d.minutesLeft(30 * min, 77, charging = false))
        assertNull(d.minutesLeft(31 * min, 77, charging = true))
        assertNull("unplugged again: a fresh start", d.minutesLeft(40 * min, 90, charging = false))
        assertNull(d.minutesLeft(50 * min, 88, charging = false))
    }
}
