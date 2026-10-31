// SPDX-License-Identifier: AGPL-3.0-only
package com.avetero.seam

import com.avetero.seam.crypto.PeerPath
import com.avetero.seam.crypto.TorBlock
import com.avetero.seam.crypto.TorState
import com.avetero.seam.protocol.v1.Transport
import com.avetero.seam.session.ConnectionSettings
import com.avetero.seam.session.SessionState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class LinkLookTest {
    private val all = ConnectionSettings()
    private val up = SessionState.Connected("Mac")

    @Test fun eachPathHasItsOwnLineIconAndWords() {
        val wifi = LinkLook.of(up, Transport.TRANSPORT_LAN, all)
        assertEquals(LinkLook.SameWifi, wifi)
        assertEquals(LinkLook.Line.Solid, wifi.line)
        assertEquals(R.drawable.ic_wifi, wifi.icon)
        assertEquals("Connected · Same Wi‑Fi", wifi.sentence)
        val tor = LinkLook.of(up, Transport.TRANSPORT_TOR, all)
        assertEquals(LinkLook.Line.Dashed, tor.line)
        assertEquals(R.drawable.ic_globe, tor.icon)
        assertEquals("Connected · Through Tor", tor.sentence)
    }

    @Test fun notConnectedIsBrokenAndSaysWhy() {
        val wifiOnly = ConnectionSettings(wifi = true, tor = false, allowed = setOf(PeerPath.LAN))
        assertEquals(LinkLook.WaitingOnWifi, LinkLook.of(SessionState.Connecting("Mac"), null, wifiOnly))
        assertEquals(LinkLook.NoSharedPath, LinkLook.of(up, null, wifiOnly.copy(allowed = emptySet())))
        assertEquals(LinkLook.Idle, LinkLook.of(SessionState.Idle("Mac"), null, all))
        assertEquals(LinkLook.Reconnecting, LinkLook.of(SessionState.Connecting("Mac"), null, all))
        for (l in listOf(LinkLook.WaitingOnWifi, LinkLook.NoSharedPath, LinkLook.Idle, LinkLook.Reconnecting)) {
            assertEquals(LinkLook.Line.Broken, l.line)
            assertFalse(l.connected)
            assertNull(l.icon)
            assertEquals(l.label, l.sentence)
        }
    }

    @Test fun torSaysWhyItCantConnect() {
        val down = SessionState.Connecting("Mac")
        fun blocked(r: TorBlock) = LinkLook.of(down, null, all, TorState.Blocked(r))
        assertEquals(LinkLook.TorBlocked, blocked(TorBlock.FILTERED))
        assertEquals(LinkLook.TorClock, blocked(TorBlock.CLOCK_SKEWED))
        assertEquals(LinkLook.Reconnecting, blocked(TorBlock.OFFLINE))
        assertEquals(LinkLook.TorFailed, LinkLook.of(down, null, all, TorState.Failed))
        assertEquals(LinkLook.ConnectingTor, LinkLook.of(down, null, all, TorState.Starting(40u)))
        assertEquals("even while idle", LinkLook.TorBlocked, LinkLook.of(SessionState.Idle("Mac"), null, all, TorState.Blocked(TorBlock.FILTERED)))
        assertEquals("on Same Wi‑Fi Tor's state doesn't matter", LinkLook.SameWifi, LinkLook.of(up, Transport.TRANSPORT_LAN, all, TorState.Blocked(TorBlock.FILTERED)))
        val wifiOnly = ConnectionSettings(wifi = true, tor = false, allowed = setOf(PeerPath.LAN))
        assertEquals("Tor switched off: not its problem", LinkLook.WaitingOnWifi, LinkLook.of(down, null, wifiOnly, TorState.Blocked(TorBlock.FILTERED)))
    }
}
