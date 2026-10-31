// SPDX-License-Identifier: AGPL-3.0-only
package com.avetero.seam.feature

import com.avetero.seam.protocol.v1.Status
import org.junit.Assert.assertEquals
import org.junit.Test

class MacStatusTest {
    @Test
    fun theMacsBatteryIsKeptWithWhenItCame() {
        assertEquals(MacStatus(64, true, 5), MacStatus.of(Status.newBuilder().setBatteryPct(64).setCharging(true).build(), at = 5))
    }

    @Test
    fun nonsenseNeverShowsOverAHundred() {
        assertEquals(100, MacStatus.of(Status.newBuilder().setBatteryPct(250).build(), 0).batteryPct)
        // uint32 4 000 000 000 arrives as a negative Int
        assertEquals(100, MacStatus.of(Status.newBuilder().setBatteryPct(-294_967_296).build(), 0).batteryPct)
    }
}
