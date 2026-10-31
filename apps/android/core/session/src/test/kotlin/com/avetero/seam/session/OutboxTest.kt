// SPDX-License-Identifier: AGPL-3.0-only
package com.avetero.seam.session

import com.avetero.seam.protocol.v1.Clipboard
import com.avetero.seam.protocol.v1.Notification
import com.avetero.seam.protocol.v1.Payload
import com.avetero.seam.protocol.v1.Status
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OutboxTest {
    private var clock = 0L
    private val outbox = Outbox(MemoryPendingStore()) { clock }

    private fun clip(t: String) = Payload.newBuilder().setClipboard(Clipboard.newBuilder().setText(t)).build()
    private fun status(pct: Int) = Payload.newBuilder().setStatus(Status.newBuilder().setBatteryPct(pct)).build()
    private fun note(n: Int) = Payload.newBuilder().setNotification(Notification.newBuilder().setTitle("$n")).build()

    private fun drain(): List<Payload> = generateSequence { outbox.next()?.also { outbox.removeNext() } }.toList()

    @Test fun aNewerClipboardReplacesTheQueuedOne() {
        outbox.add(clip("old"))
        outbox.add(note(1))
        outbox.add(clip("new"))
        assertEquals(listOf(note(1), clip("new")), drain())
    }

    @Test fun theOldestGoesOnceTheCapIsReached() {
        repeat(Outbox.MAX_OUTBOX + 1) { outbox.add(note(it)) }
        val left = drain()
        assertEquals(Outbox.MAX_OUTBOX, left.size)
        assertEquals(note(1), left.first())
    }

    @Test fun anExpiredClipboardIsDroppedAndNoLongerWantsAConnection() {
        outbox.add(clip("stale"))
        assertTrue(outbox.wantsConnection())
        clock += 600_001
        assertFalse(outbox.wantsConnection())
        assertNull(outbox.next())
    }

    @Test fun aBatteryStatusNeverWantsAConnectionOfItsOwn() {
        outbox.add(status(50))
        assertFalse(outbox.wantsConnection())
        assertEquals(status(50), outbox.next())
    }
}
