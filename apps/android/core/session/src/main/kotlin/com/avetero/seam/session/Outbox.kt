// SPDX-License-Identifier: AGPL-3.0-only
package com.avetero.seam.session

import com.avetero.seam.protocol.v1.Control
import com.avetero.seam.protocol.v1.Envelope
import com.avetero.seam.protocol.v1.Payload
import com.google.protobuf.ByteString

/**
 * What's waiting for the Mac: payloads not sealed yet, and envelopes sent directly that the Mac
 * hasn't acked. Payloads stay unsealed until they can go, so a newer one can replace them and the
 * session can see whether any is worth a connection (decision 0008). The session decides when to
 * flush and does the sealing and sending; this keeps the order, the expiry and the pending file.
 * Touched only from the session's thread.
 */
internal class Outbox(private val store: PendingStore, private val now: () -> Long) {
    /** Unsealed and bounded: sealed only when it can be sent, right after a durable save. */
    private val queue = ArrayDeque<Queued>()

    /**
     * Sent over the direct link and not yet acked by the Mac (ServerFrame.delivered), oldest first.
     * Already sealed and saved: resent as they are, never re-sealed (that would move the ratchet).
     */
    private val lanUnacked = LinkedHashMap<ByteString, Envelope>()
    /** From a direct link that dropped before the Mac acked them: sent first on the next allowed path. */
    private val resend = ArrayDeque<Envelope>()

    /** When it was queued: a Clipboard or Status older than its TTL is dropped, not sent late (SR-4-04). */
    private class Queued(val payload: Payload, val at: Long)

    /** Android kills background processes routinely: what the Mac never acked goes again on the next path. */
    fun restore() {
        resend.addAll(store.load().filter { !expired(it) }.takeLast(MAX_OUTBOX))
    }

    /** Only the latest Status and Clipboard matter; everything else is capped. */
    fun add(payload: Payload) {
        if (payload.bodyCase in LATEST_ONLY) queue.removeAll { it.payload.bodyCase == payload.bodyCase }
        if (queue.size >= MAX_OUTBOX) queue.removeFirst()
        queue.addLast(Queued(payload, now()))
    }

    /** Only the latest Hello matters: it replaces any still queued. Null: just drops the old one. */
    fun replaceHello(hello: Payload?) {
        queue.removeAll { it.payload.isHello() }
        if (queue.size >= MAX_OUTBOX) queue.removeFirst()
        if (hello != null) queue.addLast(Queued(hello, now()))
    }

    /** Moves the queued Hello to the front, for when only a Hello may go. False if there's none. */
    fun helloFirst(): Boolean {
        val hello = queue.firstOrNull { it.payload.isHello() } ?: return false
        queue.remove(hello)
        queue.addFirst(hello)
        return true
    }

    /**
     * The next payload to seal, dropping any past its TTL on the way; null when none is waiting,
     * or with [helloOnly] when the next isn't a Hello. It stays queued until [removeNext].
     */
    fun next(helloOnly: Boolean = false): Payload? {
        while (true) {
            val q = queue.firstOrNull() ?: return null
            if (helloOnly && !q.payload.isHello()) return null
            if (!expired(q)) return q.payload
            queue.removeFirst()
        }
    }

    fun removeNext() {
        queue.removeFirst()
    }

    /** Whether anything waiting is worth a connection of its own (decision 0005). */
    fun wantsConnection(): Boolean =
        queue.any { it.payload.bodyCase !in LAZY && !expired(it) } || resend.any { !expired(it) }

    /** Sent over the direct link and not yet confirmed by the Mac. */
    fun awaitingAck(): Boolean = lanUnacked.isNotEmpty()

    /** Queued payloads that would connect, expired or not: for the trace only. */
    fun urgentCount(): Int = queue.count { it.payload.bodyCase !in LAZY }

    /**
     * What a dropped direct link never confirmed goes first, as it was sealed, through [send] until
     * one doesn't go. True once none is left.
     */
    fun resendAll(send: (Envelope) -> Boolean): Boolean {
        if (resend.isEmpty()) return true
        while (resend.isNotEmpty()) {
            val e = resend.first()
            if (expired(e)) {
                resend.removeFirst()
                continue
            }
            if (!send(e)) break
            resend.removeFirst()
        }
        save() // recorded again for the link they went on
        return resend.isEmpty()
    }

    /**
     * Recorded before it's handed to the direct link. If the disk refuses, it's still kept in memory
     * and sent: only a process death in that state would lose it. False if it couldn't be saved.
     */
    fun sentDirect(e: Envelope): Boolean {
        lanUnacked[e.msgId] = e
        // Same bound as the queue: the oldest unconfirmed one goes first
        while (lanUnacked.size + resend.size > MAX_OUTBOX) {
            if (resend.isNotEmpty()) resend.removeFirst() else lanUnacked.remove(lanUnacked.keys.first())
        }
        return save()
    }

    /** The Mac acked it on the direct link. An unknown id: nothing to remove, nothing written. */
    fun acked(msgId: ByteString) {
        if (lanUnacked.remove(msgId) != null) save()
    }

    /** Anything the Mac hadn't confirmed may be lost with the direct link: send it again on the next path. */
    fun linkDropped() {
        resend.addAll(lanUnacked.values)
        lanUnacked.clear()
    }

    /** Drops the unsealed payloads; what the Mac never acked is kept. */
    fun clearUnsent() = queue.clear()

    /** Drops everything, including the pending file. */
    fun clearAll() {
        queue.clear()
        resend.clear()
        lanUnacked.clear()
        save()
    }

    /** What the Mac hasn't acked, oldest first, to the pending file. False if it couldn't be saved. */
    fun save(): Boolean =
        runCatching { store.save((resend + lanUnacked.values).distinctBy { it.msgId }) }.isSuccess

    /** Past its TTL: dropped at the next flush, and no reason to keep trying to connect (SR-8-01). */
    private fun expired(q: Queued): Boolean {
        val ttl = ttlFor(q.payload)
        return ttl != 0u && now() - q.at > ttl.toLong() * 1000
    }

    /** By the envelope's own sent_at and ttl, as the Mac would drop it. */
    private fun expired(e: Envelope) = now() - e.sentAt > e.ttlS.toLong() * 1000

    companion object {
        const val MAX_OUTBOX = 100
        val LATEST_ONLY = setOf(Payload.BodyCase.STATUS, Payload.BodyCase.CLIPBOARD, Payload.BodyCase.NOTIFICATION_SETTINGS)
        /** Sent on the next connection, never worth one of their own (a battery change). */
        val LAZY = setOf(Payload.BodyCase.STATUS)

        /** How long it may wait to be delivered (docs/architecture/protocol.md): a stale clipboard is worse than none. */
        fun ttlFor(payload: Payload): UInt = when (payload.bodyCase) {
            Payload.BodyCase.CLIPBOARD -> 600u
            Payload.BodyCase.STATUS -> 120u
            Payload.BodyCase.NOTIFICATION_SETTINGS -> 600u // sent again on every connection
            Payload.BodyCase.NOTIFICATION -> 3600u // a Mac that was away gets the last hour, not a flood
            Payload.BodyCase.CONTROL -> if (payload.control.hasHello()) 600u else 0u // sent again on every connection
            else -> 0u // the core's default
        }
    }
}

internal fun Payload.isHello() = bodyCase == Payload.BodyCase.CONTROL && control.bodyCase == Control.BodyCase.HELLO
