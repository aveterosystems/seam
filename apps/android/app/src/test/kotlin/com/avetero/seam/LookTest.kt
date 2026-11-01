// SPDX-License-Identifier: AGPL-3.0-only
package com.avetero.seam

import com.avetero.seam.feature.MacStatus
import com.avetero.seam.feature.notifications.AppEntry
import com.avetero.seam.feature.notifications.AppMode
import com.avetero.seam.session.SessionState
import java.time.LocalDateTime
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The words and shapes each state turns into, as the approved prototype shows them. */
class LookTest {
    @Test fun theCookieTurnsWhileConnectedSoftensWhenPausedAndIsAPlainCircleOtherwise() {
        assertEquals(CookieLook(110f, 9f, CookieLook.Fill.Live, turning = true), LinkLook.SameWifi.cookie())
        assertEquals(CookieLook(110f, 9f, CookieLook.Fill.Live, turning = true), LinkLook.ThroughTor.cookie())
        assertEquals(CookieLook(104f, 5f, CookieLook.Fill.Paused, turning = false), LinkLook.paused(Long.MAX_VALUE, 0).cookie())
        assertEquals(CookieLook(100f, 0f, CookieLook.Fill.Off, turning = false), LinkLook.Offline.cookie())
        assertEquals(CookieLook(100f, 0f, CookieLook.Fill.Off, turning = false), LinkLook.Idle.cookie())
    }

    @Test fun offlineSaysItWillSendWhenTheMacIsBack() {
        assertEquals("Offline · will send when it's back", LinkLook.Offline.sentence)
        assertEquals("Paused · until you resume", LinkLook.paused(Long.MAX_VALUE, 0).sentence)
    }

    @Test fun sendClipboardConfirmsOnlyWhatReallyHappened() {
        assertEquals(SendFeedback(label = "✓ Sent to Mac"), sendFeedback(true, SessionState.Connected("MacBook Pro"), "MacBook Pro"))
        assertEquals(SendFeedback(label = "Sending"), sendFeedback(true, SessionState.Idle("MacBook Pro"), "MacBook Pro"))
        assertEquals(SendFeedback(snack = "Will send when MacBook Pro is back"), sendFeedback(true, SessionState.Connecting("MacBook Pro"), "MacBook Pro"))
        assertNull(sendFeedback(false, SessionState.Connected("MacBook Pro"), "MacBook Pro").label)
        assertEquals(SendFeedback(snack = "Your Mac has Sync the clipboard turned off"), sendFeedback(false, SessionState.Connected("MacBook Pro"), "MacBook Pro", offOnMac = true))
    }

    @Test fun theMacsBatteryShowsWhileReachableADashWhenNotAndNothingBeforeItsKnown() {
        val mac = MacStatus(64, false, 0)
        assertEquals("64%", macBattery(LinkLook.SameWifi, mac))
        assertEquals("64%", macBattery(LinkLook.paused(Long.MAX_VALUE, 0), mac))
        assertEquals("—", macBattery(LinkLook.Offline, mac))
        assertNull(macBattery(LinkLook.SameWifi, null))
    }

    @Test fun notificationLinesCountTheAppsThatAreOn() {
        val apps = listOf(
            AppEntry("a", "WhatsApp", AppMode.On), AppEntry("b", "Monzo", AppMode.TitleOnly),
            AppEntry("c", "Uber", AppMode.Off), AppEntry("d", "Gmail", AppMode.TitleOnly),
        )
        assertEquals("On · 3 apps", notificationsShort(true, apps))
        assertEquals("Off", notificationsShort(false, apps))
        assertEquals("3 of 4 apps · Title only for Monzo and Gmail", notificationsLong(true, apps))
        assertEquals("Off · 3 of 4 apps · Title only for Monzo and Gmail", notificationsLong(false, apps))
        assertEquals("1 of 1 apps", notificationsLong(true, apps.take(1)))
        assertEquals("3 of 4 apps", appCount(apps))
    }

    @Test fun connectionFootnoteExplainsTheSwitches() {
        assertEquals("One always stays on. Encryption is the same on both.", connectionFootnote(true, true))
        assertEquals("Only on your Mac's network. Turn on Tor to reach it anywhere.", connectionFootnote(true, false))
        assertEquals("Through Tor even at home. Same Wi‑Fi is faster for big files.", connectionFootnote(false, true))
    }

    @Test fun settingsWords() {
        val london = ZoneId.of("Europe/London")
        val at = LocalDateTime.parse("2026-09-14T10:00:00").atZone(london).toInstant().toEpochMilli()
        assertEquals("Paired 14 September 2026", pairedLabel(at, london))
        assertEquals("Tap to check now", checkLabel(null))
        assertEquals("✓ Up to date", checkLabel("Seam is up to date"))
        assertEquals("Checking", checkLabel("Checking"))
        assertEquals("Copies reach your Mac by themselves", ClipboardStatus.Automatic.subtitle(viaAccessibility = false, home = true))
        assertEquals("On · set up from your Mac", ClipboardStatus.Automatic.subtitle(viaAccessibility = false, home = false))
        assertEquals("Off · tap Send clipboard after copying", ClipboardStatus.NeedsSetup.subtitle(viaAccessibility = false, home = true))
        assertEquals("Off on your Mac · turn on Sync the clipboard there", ClipboardStatus.OffOnMac.subtitle(viaAccessibility = false, home = true))
    }

    @Test fun cssLinearFollowsTheStopsAndSpreadsTheUnplacedOnes() {
        val e = CssLinear("0, 1 50%, 0.5")
        assertEquals(0f, e.transform(0f), 1e-6f)
        assertEquals(0.5f, e.transform(0.25f), 1e-6f)
        assertEquals(1f, e.transform(0.5f), 1e-6f)
        assertEquals(0.75f, e.transform(0.75f), 1e-6f)
        // Unplaced stops sit evenly between placed ones: 0, .5 at 25%, 1 at 50%
        assertEquals(0.5f, CssLinear("0,.5,1 50%,1").transform(0.25f), 1e-6f)
        // The prototype's spring overshoots to 1.077 at 25%, and ends at 1
        assertEquals(1.077f, Motion.Spring.transform(0.25f), 1e-4f)
        assertEquals(1f, Motion.Spring.transform(1f), 0f)
        // The bouncy curve first reaches 1 at the 12th stop (about 27%) and never goes above it
        assertTrue((0..100).all { Motion.Bouncy.transform(it / 100f) <= 1f })
        assertEquals(1f, Motion.Bouncy.transform(0.727f), 1e-3f)
    }
}
