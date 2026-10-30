// SPDX-License-Identifier: AGPL-3.0-only
package com.avetero.seam.feature.notifications

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NotificationRulesTest {
    private val msg = Posted(key = "k", app = "com.whatsapp", title = "Alice", text = "On my way")

    @Test fun mirrorsAMessageOnceAndItsUpdates() {
        val r = NotificationRules("com.avetero.seam")
        assertEquals(Mirror.Full, r.decide(msg))
        assertEquals("a re-post with the same words", Mirror.Skip, r.decide(msg))
        assertEquals("a new message on the same key", Mirror.Full, r.decide(msg.copy(text = "Here")))
    }

    @Test fun anUpdateTheAppMarkedAlertOnceIsNotABannerAgain() {
        val r = NotificationRules("x")
        assertEquals(Mirror.Full, r.decide(msg.copy(alertOnce = true)))
        assertEquals("e.g. the app adding your own reply", Mirror.Skip, r.decide(msg.copy(text = "You: ok", alertOnce = true)))
    }

    @Test fun skipsWhatIsNotNews() {
        val r = NotificationRules("com.avetero.seam")
        assertEquals(Mirror.Skip, r.decide(msg.copy(app = "com.avetero.seam")))
        assertEquals(Mirror.Skip, r.decide(msg.copy(ongoing = true)))
        assertEquals(Mirror.Skip, r.decide(msg.copy(groupSummary = true)))
        assertEquals(Mirror.Skip, r.decide(msg.copy(media = true)))
        assertEquals(Mirror.Skip, r.decide(msg.copy(silent = true)))
        assertEquals(Mirror.Skip, r.decide(msg.copy(title = " ", text = "")))
    }

    @Test fun secretOnTheLockScreenIsTitleOnly() {
        assertEquals(Mirror.TitleOnly, NotificationRules("x").decide(msg.copy(secret = true)))
    }

    @Test fun followsTheAppsSetting() {
        assertEquals(Mirror.Skip, NotificationRules("x").decide(msg, AppMode.Off))
        assertEquals(Mirror.TitleOnly, NotificationRules("x").decide(msg, AppMode.TitleOnly))
    }

    @Test fun onlyMirroredRemovalsReachTheMac() {
        val r = NotificationRules("x")
        assertFalse(r.removed("k"))
        r.decide(msg)
        assertTrue(r.removed("k"))
        assertFalse("once", r.removed("k"))
        assertEquals("and it can come back", Mirror.Full, r.decide(msg))
    }
}
