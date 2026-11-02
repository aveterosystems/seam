// SPDX-License-Identifier: AGPL-3.0-only
package com.avetero.seam

import com.avetero.seam.crypto.untilWords
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** The ways to pause Seam. Only the phone pauses; the Mac is told and waits. */
enum class PauseFor(val label: String) {
    HALF_HOUR("30 minutes"),
    HOUR("1 hour"),
    MORNING("Until tomorrow morning"),
    RESUME("Until I resume");

    /** When a pause chosen at [now] ends (unix ms); Long.MAX_VALUE: until resumed. */
    fun until(now: Long, zone: ZoneId = ZoneId.systemDefault()): Long = when (this) {
        HALF_HOUR -> now + 30 * 60_000L
        HOUR -> now + 60 * 60_000L
        MORNING -> Instant.ofEpochMilli(now).atZone(zone).toLocalDate().plusDays(1)
            .atTime(MORNING_AT).atZone(zone).toInstant().toEpochMilli()
        RESUME -> Long.MAX_VALUE
    }

    companion object {
        val MORNING_AT: LocalTime = LocalTime.of(8, 0)
    }
}

/** "Paused until 15:30", "Paused until tomorrow 08:00", or "Paused until you resume". */
fun pausedLabel(until: Long, now: Long, zone: ZoneId = ZoneId.systemDefault()): String = "Paused " + untilWords(until, now, SystemTime(zone))
