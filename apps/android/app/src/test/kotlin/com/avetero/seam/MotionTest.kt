// SPDX-License-Identifier: AGPL-3.0-only
package com.avetero.seam

import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.animation.core.VectorConverter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The spring fitted to the prototype's `--spring`, and its delay. */
class MotionTest {
    private val from = AnimationVector1D(0f)
    private val to = AnimationVector1D(1f)
    private val still = AnimationVector1D(0f)

    @Test
    fun peaksNearAQuarterWithAboutEightPercentOvershoot() {
        val s = Motion.spring<Float>(400).vectorize(Float.VectorConverter)
        val (peakMs, peak) = (0..400).map { it to s.getValueFromNanos(it * 1_000_000L, from, to, still).value }.maxBy { it.second }
        assertTrue("peak at $peakMs ms", peakMs in 80..120)
        assertEquals(1.077f, peak, 0.01f)
    }

    @Test
    fun aDelayHoldsTheStartThenRunsTheSameSpring() {
        val plain = Motion.spring<Float>(400).vectorize(Float.VectorConverter)
        val late = Motion.spring<Float>(400, delay = 150).vectorize(Float.VectorConverter)
        assertEquals(0f, late.getValueFromNanos(149_000_000L, from, to, still).value, 0f)
        assertEquals(
            plain.getValueFromNanos(50_000_000L, from, to, still).value,
            late.getValueFromNanos(200_000_000L, from, to, still).value, 1e-6f,
        )
        assertEquals(plain.getDurationNanos(from, to, still) + 150_000_000L, late.getDurationNanos(from, to, still))
    }
}
