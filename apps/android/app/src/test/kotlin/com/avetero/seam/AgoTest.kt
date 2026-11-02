// SPDX-License-Identifier: AGPL-3.0-only
package com.avetero.seam

import java.time.LocalDateTime
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Test

// The words are the shared looks' (tested in core/seam-looks); this checks the phone's clock fills them in
class AgoTest {
    @Test fun saysHowLongAgoInPlainWords() {
        val london = SystemTime(ZoneId.of("Europe/London"))
        val now = LocalDateTime.parse("2026-11-02T14:00:00").atZone(ZoneId.of("Europe/London")).toInstant().toEpochMilli()
        assertEquals("just now", Looks.ago(now - 59_000, now, london))
        assertEquals("5 min ago", Looks.ago(now - 5 * 60_000L - 30_000, now, london))
        assertEquals("today at 12:00", Looks.ago(now - 2 * 3_600_000L, now, london))
        assertEquals("yesterday at 14:00", Looks.ago(now - 24 * 3_600_000L, now, london))
    }
}
