// SPDX-License-Identifier: AGPL-3.0-only
package com.avetero.seam.feature.notifications

import com.avetero.seam.protocol.v1.NotificationApp
import com.avetero.seam.protocol.v1.NotificationMode
import com.avetero.seam.protocol.v1.NotificationSettingsChange
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class NotificationPrefsTest {
    @Test fun appsAreListedOnceByNameAndRememberReplies() {
        val p = NotificationPrefs()
            .seen("com.whatsapp", "WhatsApp", canReply = false)
            .seen("com.android.chrome", "Chrome", canReply = false)
            .seen("com.whatsapp", "WhatsApp", canReply = true)
        assertEquals(listOf("Chrome", "WhatsApp"), p.apps.map { it.name })
        assertTrue(p.apps.last().canReply)
        assertSame("nothing new: the same object, so nothing is saved or sent", p, p.seen("com.whatsapp", "WhatsApp", canReply = false))
    }

    @Test fun appsStartOffAndCanBeChangedTogether() {
        val p = NotificationPrefs().seen("a", "A", false).seen("b", "B", false).seen("c", "C", false)
        assertEquals("nothing leaves the phone until the user turns an app on", AppMode.Off, p.mode("a"))
        assertEquals(AppMode.Off, p.mode("never seen"))
        val bulk = p.withMode(setOf("a", "c"), AppMode.On)
        assertEquals(listOf(AppMode.On, AppMode.Off, AppMode.On), bulk.apps.map { it.mode })
    }

    @Test fun appliesOnlyWhatTheMacChanged() {
        val p = NotificationPrefs().seen("a", "A", false).seen("b", "B", false).withMode(setOf("a", "b"), AppMode.On)
        val changed = p.applying(
            NotificationSettingsChange.newBuilder()
                .setQuietWhileUsingPhone(true)
                .addApps(NotificationApp.newBuilder().setAppId("b").setMode(NotificationMode.NOTIFICATION_MODE_OFF))
                .addApps(NotificationApp.newBuilder().setAppId("unknown").setMode(NotificationMode.NOTIFICATION_MODE_OFF))
                .build(),
        )
        assertEquals(true, changed.quietWhileUsingPhone)
        assertEquals("unset stays as it was", true, changed.enabled)
        assertEquals(AppMode.On, changed.mode("a"))
        assertEquals(AppMode.Off, changed.mode("b"))
        assertEquals(listOf("a", "b"), changed.apps.map { it.id })
    }

    @Test fun pausesUntilATimeAndTheMacCanResume() {
        val p = NotificationPrefs().applying(NotificationSettingsChange.newBuilder().setPausedUntil(1_000).build())
        assertTrue(p.paused(999))
        assertEquals("over by itself", false, p.paused(1_000))
        assertEquals(false, p.applying(NotificationSettingsChange.newBuilder().setPausedUntil(0).build()).paused(0))
    }

    @Test fun survivesSaving() {
        val p = NotificationPrefs(quietWhileUsingPhone = true, pausedUntil = 42).seen("a", "A", true).withMode(setOf("a"), AppMode.TitleOnly)
        assertEquals(p, NotificationPrefs.fromJson(p.toJson()))
        assertEquals("unreadable falls back to defaults", NotificationPrefs(), NotificationPrefs.fromJson("{"))
    }
}
