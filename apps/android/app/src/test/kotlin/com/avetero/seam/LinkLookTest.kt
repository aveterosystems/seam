// SPDX-License-Identifier: AGPL-3.0-only
package com.avetero.seam

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
        val relay = LinkLook.of(up, Transport.TRANSPORT_RELAY, all)
        assertEquals(LinkLook.Line.Dashed, relay.line)
        assertEquals(R.drawable.ic_cloud, relay.icon)
    }

    @Test fun notConnectedIsBrokenAndSaysWhy() {
        val wifiOnly = ConnectionSettings(wifi = true, relay = false)
        assertEquals(LinkLook.WaitingOnWifi, LinkLook.of(SessionState.Connecting("Mac"), null, wifiOnly))
        assertEquals(LinkLook.NoSharedPath, LinkLook.of(up, null, wifiOnly.copy(macAllows = setOf(Transport.TRANSPORT_RELAY))))
        assertEquals(LinkLook.Idle, LinkLook.of(SessionState.Idle("Mac"), null, all))
        assertEquals(LinkLook.Reconnecting, LinkLook.of(SessionState.Connecting("Mac"), null, all))
        for (l in listOf(LinkLook.WaitingOnWifi, LinkLook.NoSharedPath, LinkLook.Idle, LinkLook.Reconnecting)) {
            assertEquals(LinkLook.Line.Broken, l.line)
            assertFalse(l.connected)
            assertNull(l.icon)
            assertEquals(l.label, l.sentence)
        }
    }
}
