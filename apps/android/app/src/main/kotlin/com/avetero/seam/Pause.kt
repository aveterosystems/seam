// SPDX-License-Identifier: AGPL-3.0-only
package com.avetero.seam

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
fun pausedLabel(until: Long, now: Long, zone: ZoneId = ZoneId.systemDefault()): String {
    if (until == Long.MAX_VALUE) return "Paused until you resume"
    val end = Instant.ofEpochMilli(until).atZone(zone)
    val time = end.format(DateTimeFormatter.ofPattern("HH:mm"))
    val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
    return when (end.toLocalDate()) {
        today -> "Paused until $time"
        today.plusDays(1) -> "Paused until tomorrow $time"
        else -> "Paused until ${end.format(DateTimeFormatter.ofPattern("d MMM HH:mm"))}"
    }
}
