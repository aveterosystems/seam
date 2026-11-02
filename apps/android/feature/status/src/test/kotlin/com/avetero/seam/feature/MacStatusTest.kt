// SPDX-License-Identifier: AGPL-3.0-only
package com.avetero.seam.feature

import com.avetero.seam.protocol.v1.Status
import org.junit.Assert.assertEquals
import org.junit.Test

// How it's shown (and kept within 0–100) is the shared looks' (core/seam-looks)
class MacStatusTest {
    @Test
    fun theMacsStatusIsKeptWhole() {
        val s = Status.newBuilder().setBatteryPct(64).setCharging(true).setCpuPct(12).setMemoryTotalBytes(16L shl 30).build()
        assertEquals(s, MacStatus(s, at = 5).status)
    }
}
