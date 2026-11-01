// SPDX-License-Identifier: AGPL-3.0-only
package com.avetero.seam

import com.avetero.seam.crypto.PeerPath
import com.avetero.seam.session.ConnectionSettings
import com.avetero.seam.session.SessionState
import java.time.LocalDateTime
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PauseTest {
    private val london = ZoneId.of("Europe/London")
    private fun at(text: String) = LocalDateTime.parse(text).atZone(london).toInstant().toEpochMilli()

    @Test fun eachChoiceEndsWhenItSays() {
        val now = at("2026-10-31T22:15:00")
        assertEquals(now + 1_800_000, PauseFor.HALF_HOUR.until(now, london))
        assertEquals(now + 3_600_000, PauseFor.HOUR.until(now, london))
        assertEquals(at("2026-11-01T08:00:00"), PauseFor.MORNING.until(now, london))
        assertEquals(Long.MAX_VALUE, PauseFor.RESUME.until(now, london))
    }

    @Test fun tomorrowMorningIsEightLocalTimeEvenAcrossAClockChange() {
        // The clocks went back overnight on 25 Oct 2026 in London
        assertEquals(at("2026-10-25T08:00:00"), PauseFor.MORNING.until(at("2026-10-24T23:30:00"), london))
    }

    @Test fun theLabelSaysWhenItEnds() {
        val now = at("2026-10-31T14:00:00")
        assertEquals("Paused until 15:00", pausedLabel(at("2026-10-31T15:00:00"), now, london))
        assertEquals("Paused until tomorrow 08:00", pausedLabel(at("2026-11-01T08:00:00"), now, london))
        assertEquals("Paused until you resume", pausedLabel(Long.MAX_VALUE, now, london))
    }

    @Test fun thePausedLookWinsUntilTheTimeComes() {
        val now = 1_000_000L
        val c = ConnectionSettings(pausedUntil = now + 60_000, allowed = setOf(PeerPath.LAN))
        val paused = LinkLook.of(SessionState.Idle("Mac"), null, c, now = now)
        assertEquals(LinkLook.Line.Broken, paused.line)
        assertFalse(paused.connected)
        assertEquals(pausedLabel(now + 60_000, now).replace("Paused until", "Paused · until"), paused.label)
        assertTrue(paused.paused)
        assertEquals("over: back to normal", LinkLook.Idle, LinkLook.of(SessionState.Idle("Mac"), null, c, now = now + 60_000))
    }

    @Test fun theSheetOffersThePrototypesFourChoicesInItsWords() {
        assertEquals(listOf("30 minutes", "1 hour", "Until tomorrow morning", "Until I resume"), PauseFor.entries.map { it.label })
    }
}
