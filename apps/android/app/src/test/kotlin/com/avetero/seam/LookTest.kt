// SPDX-License-Identifier: AGPL-3.0-only
package com.avetero.seam

import com.avetero.seam.feature.MacStatus
import com.avetero.seam.feature.notifications.AppEntry
import com.avetero.seam.feature.notifications.AppMode
import com.avetero.seam.feature.notifications.NotificationPrefs
import com.avetero.seam.protocol.v1.Transport
import com.avetero.seam.session.ConnectionSettings
import com.avetero.seam.session.SessionState
import java.time.LocalDateTime
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The words and shapes each state turns into, as the approved prototype shows them. */
class LookTest {
    // The one thing the phone decides itself: which path the session's transport is
    @Test fun connectedTransportsMapToTheirLooks() {
        val up = SessionState.Connected("Mac")
        assertEquals("Connected · Same Wi‑Fi", Looks.link(up, Transport.TRANSPORT_LAN, ConnectionSettings()).sentence)
        assertEquals("Connected · Through Tor", Looks.link(up, Transport.TRANSPORT_TOR, ConnectionSettings()).sentence)
    }

    @Test fun theCookieTurnsWhileConnectedSoftensWhenPausedAndIsAPlainCircleOtherwise() {
        assertEquals(CookieLook(110f, 9f, CookieLook.Fill.Live, turning = true), Looks.sameWifi.cookie())
        assertEquals(CookieLook(110f, 9f, CookieLook.Fill.Live, turning = true), Looks.throughTor.cookie())
        assertEquals(CookieLook(104f, 5f, CookieLook.Fill.Paused, turning = false), Looks.paused(Long.MAX_VALUE, 0).cookie())
        assertEquals(CookieLook(100f, 0f, CookieLook.Fill.Off, turning = false), Looks.offline.cookie())
        assertEquals(CookieLook(100f, 0f, CookieLook.Fill.Off, turning = false), Looks.idle.cookie())
    }

    @Test fun offlineSaysItWillSendWhenTheMacIsBack() {
        assertEquals("Offline · will send when your Mac is back", Looks.offline.sentence)
        assertEquals("Paused · until you resume", Looks.paused(Long.MAX_VALUE, 0).sentence)
    }

    @Test fun sendClipboardConfirmsOnlyWhatReallyHappened() {
        assertEquals(SendFeedback(label = "✓ Sent to Mac"), sendFeedback(true, SessionState.Connected("MacBook Pro"), "MacBook Pro"))
        assertEquals(SendFeedback(label = "Sending"), sendFeedback(true, SessionState.Idle("MacBook Pro"), "MacBook Pro"))
        assertEquals(SendFeedback(snack = "Will send when MacBook Pro is back"), sendFeedback(true, SessionState.Connecting("MacBook Pro"), "MacBook Pro"))
        assertNull(sendFeedback(false, SessionState.Connected("MacBook Pro"), "MacBook Pro").label)
        assertEquals(SendFeedback(snack = "Your Mac has Sync the clipboard turned off"), sendFeedback(false, SessionState.Connected("MacBook Pro"), "MacBook Pro", offOnMac = true))
    }

    @Test fun theMacsStatusComesFromTheSharedLooks() {
        val mac = MacStatus(com.avetero.seam.protocol.v1.Status.newBuilder().setBatteryPct(64).setCpuPct(12).setMemoryUsedBytes(9L shl 30).setMemoryTotalBytes(24L shl 30).build(), 0)
        val now = Looks.mac(mac, Looks.sameWifi)!!
        assertEquals("64%" to "12%", now.battery to now.cpu)
        assertEquals("9 GB of 24 GB in use", now.memoryDetail)
        assertEquals(R.drawable.ic_battery_50, now.batteryIcon?.drawable)
        assertTrue("out of reach, it's from before", Looks.mac(mac, Looks.offline)!!.stale)
        assertTrue("paused can't refresh it either", Looks.mac(mac, Looks.paused(Long.MAX_VALUE, 0))!!.stale)
    }

    @Test fun notificationLinesCountTheAppsThatAreOn() {
        val apps = listOf(
            AppEntry("a", "WhatsApp", AppMode.On), AppEntry("b", "Monzo", AppMode.TitleOnly),
            AppEntry("c", "Uber", AppMode.Off), AppEntry("d", "Gmail", AppMode.TitleOnly),
        )
        val on = NotificationPrefs(enabled = true, apps = apps)
        assertEquals("On · 3 apps", notificationsShort(on))
        assertEquals("Off", notificationsShort(on.copy(enabled = false)))
        assertEquals("3 of 4 apps · Sender only for Monzo and Gmail", notificationsLong(on))
        assertEquals("Off · 3 of 4 apps · Sender only for Monzo and Gmail", notificationsLong(on.copy(enabled = false)))
        assertEquals("1 of 1 apps", notificationsLong(on.copy(apps = apps.take(1))))
        assertEquals("3 of 4 apps", appCount(on))
        assertEquals("Sender only", AppMode.TitleOnly.label)
    }

    @Test fun connectionFootnoteExplainsTheSwitches() {
        assertEquals("One always stays on. The encryption is the same on both.", connectionFootnote(true, true))
        assertEquals("Only on the same network as your Mac. Turn on Tor to reach it anywhere.", connectionFootnote(true, false))
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
