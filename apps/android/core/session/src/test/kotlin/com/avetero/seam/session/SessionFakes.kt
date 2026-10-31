// SPDX-License-Identifier: AGPL-3.0-only
package com.avetero.seam.session

import com.avetero.seam.crypto.LanChannel
import com.avetero.seam.crypto.SeamDevice
import com.avetero.seam.crypto.SeamException
import com.avetero.seam.protocol.v1.BundleResult
import com.avetero.seam.protocol.v1.ClientFrame
import com.avetero.seam.protocol.v1.Control
import com.avetero.seam.protocol.v1.Delivered
import com.avetero.seam.protocol.v1.Envelope
import com.avetero.seam.protocol.v1.PairAccept
import com.avetero.seam.protocol.v1.Payload
import com.avetero.seam.protocol.v1.ServerFrame
import com.google.protobuf.ByteString

internal class MemoryStore : StateStore {
    var blob: ByteArray? = null
    var quarantined: ByteArray? = null
    var failSaves = false
    val saves = mutableListOf<ByteArray>()
    var keyFailures = 0
    var keyError: () -> Exception = { StateUnreadableException(javax.crypto.AEADBadTagException()) }
    var failQuarantine = false
    override fun load() = blob
    override fun storageKey(): ByteArray {
        if (keyFailures > 0) { keyFailures--; throw keyError() }
        return ByteArray(32) { 3 }
    }
    override fun save(blob: ByteArray) {
        if (failSaves) throw java.io.IOException("disk full")
        this.blob = blob
        saves += blob
    }
    override fun quarantine() {
        if (failQuarantine) error("rename failed")
        quarantined = blob
        blob = null
    }
}

/**
 * Macs reachable by address: each answers with a real core LanChannel and handles the frames as
 * the Mac app does, pairing channels included (docs/tor/13-implementation-contract.md).
 */
internal open class FakeLan(var macs: Map<LanAddress, SeamDevice>) : Lan {
    var found: ((LanAddress) -> Unit)? = null
    var discovering = false
    var discoveries = 0
    /** Re-announced to every new discovery, as NSD does. */
    var adverts = listOf<LanAddress>()
    var opened = 0
    var open: MacEnd? = null
    val macReceived = mutableListOf<Payload>()
    val hellos = mutableListOf<Payload>()
    var delivered = 0
    /** Pairing channels that authenticated. */
    var pairings = 0
    /** Prekey bundles handed out on pairing channels. */
    var bundles = 0
    /** Every frame the phone sent on a pairing channel. */
    val pairingFrames = mutableListOf<ClientFrame.FrameCase>()
    /** Each payload's kind with the TTL it was sealed with. */
    val ttls = mutableListOf<Pair<Payload.BodyCase, Int>>()
    /** This many next connections drop before answering (a busy Mac, or one made to drop us). */
    var dropNext = 0
    /** This many next connections get one byte of the Mac's handshake flipped (a forged packet). */
    var corruptNext = 0
    /** Connections are accepted but the Mac never answers. */
    var silent = false
    /** The Mac shows its code and waits for the user: no PairAccept. */
    var withholdAccept = false
    /** Acks each envelope it handles (ServerFrame.delivered), as the Mac does. */
    var acks = true
    /** Envelopes that "never arrived": recorded, not opened, not acked (lost with a dying link). */
    var lose = false
    val lost = mutableListOf<Envelope>()
    /** Every envelope that arrived, as sent (duplicates included). */
    val envelopes = mutableListOf<Envelope>()

    override fun discover(found: (LanAddress) -> Unit, lost: (LanAddress) -> Unit) {
        discovering = true
        discoveries++
        this.found = found
        adverts.forEach(found)
    }
    override fun stopDiscovery() {
        discovering = false
    }
    fun announce(vararg a: LanAddress) = a.forEach { found!!(it) }

    /** The Mac at [address]; throws if nothing answers there. */
    open fun macAt(address: LanAddress): SeamDevice = macs.getValue(address)

    override fun connect(address: LanAddress, listener: LinkListener): Link {
        val mac = macAt(address)
        opened++
        if (silent) return object : Link {
            override fun send(frame: ByteArray) = true
            override fun close() = Unit
        }
        return MacEnd(mac, listener).also {
            open = it
            if (dropNext > 0) { dropNext--; it.drops = true }
            if (corruptNext > 0) { corruptNext--; it.corrupts = true }
        }
    }

    fun deliverToPhone(env: ByteArray) = open!!.deliverToPhone(env)
    fun drop() = open!!.drop()

    inner class MacEnd(val mac: SeamDevice, val listener: LinkListener) : Link {
        val ch: LanChannel? = runCatching { LanChannel.responder(mac) }.getOrNull()
        var closed = false
        var drops = false
        var corrupts = false
        private var authed = false
        override fun send(frame: ByteArray): Boolean {
            if (closed) return false
            if (drops || ch == null) return true.also { refuse("dropped") }
            val out = try {
                ch.receive(frame)
            } catch (_: Exception) {
                refuse("refused")
                return true
            }
            if (out.send.isNotEmpty()) {
                if (corrupts) out.send[out.send.size - 1] = (out.send.last().toInt() xor 1).toByte()
                listener.onFrame(out.send)
            }
            if (out.authenticated != null && !authed) {
                authed = true
                if (ch.isPairing()) pairings++
            }
            for (f in out.frames) {
                if (closed) return true
                val c = ClientFrame.parseFrom(f)
                if (ch.isPairing()) pairingFrame(c) else frame(c)
            }
            return true
        }

        private fun frame(c: ClientFrame) {
            when (c.frameCase) {
                ClientFrame.FrameCase.SEND -> {
                    if (lose) {
                        lost += c.send
                        return
                    }
                    envelopes += c.send
                    val opened = try {
                        mac.open(c.send.toByteArray())
                    } catch (_: SeamException.Duplicate) {
                        if (acks) delivered(c.send.msgId) // the Mac acks a duplicate too, and drops it
                        return
                    }
                    val p = Payload.parseFrom(opened.payload)
                    ttls += p.bodyCase to c.send.ttlS
                    if (p.hasControl() && p.control.hasHello()) hellos += p else macReceived += p
                    if (acks) delivered(c.send.msgId)
                }
                ClientFrame.FrameCase.DELIVERED -> delivered++
                else -> Unit
            }
        }

        /** Only the pairing exchange; anything else closes the channel, as the Mac does. */
        private fun pairingFrame(c: ClientFrame) {
            pairingFrames += c.frameCase
            when (c.frameCase) {
                ClientFrame.FrameCase.GET_BUNDLE -> {
                    bundles++
                    reply(
                        ServerFrame.newBuilder().setBundle(
                            BundleResult.newBuilder().setDeviceKey(c.getBundle.deviceKey).setBundle(ByteString.copyFrom(mac.prekeyBundles(1u).first())),
                        ),
                    )
                }
                ClientFrame.FrameCase.SEND -> {
                    val request = try {
                        mac.acceptPairing(c.send.toByteArray())
                    } catch (_: Exception) {
                        return refuse("bad pairing request")
                    }
                    delivered(c.send.msgId)
                    if (withholdAccept) return
                    mac.confirmPairing(request.peerId) // the user confirmed the code
                    deliverToPhone(mac.seal(request.peerId, ACCEPT.toByteArray(), 0u))
                }
                ClientFrame.FrameCase.DELIVERED -> delivered++
                else -> refuse("not a pairing frame")
            }
        }

        private fun refuse(reason: String) {
            closed = true
            listener.onClosed(reason)
        }

        override fun close() {
            closed = true
        }
        private fun reply(f: ServerFrame.Builder) = listener.onFrame(ch!!.send(f.build().toByteArray()))
        fun deliverToPhone(env: ByteArray) = reply(ServerFrame.newBuilder().setDeliver(Envelope.parseFrom(env)))
        fun drop() = listener.onClosed("dropped")
        fun delivered(id: ByteString) = reply(ServerFrame.newBuilder().setDelivered(Delivered.newBuilder().setMsgId(id)))
    }

    companion object {
        val ACCEPT: Payload = Payload.newBuilder().setControl(Control.newBuilder().setPairAccept(PairAccept.getDefaultInstance())).build()
    }
}

/**
 * Onion services through a fake Tor: each Mac reachable at the address on its pairing code. Tor is
 * "ready" at once unless [ready] is false; an address nobody hosts is unreachable.
 */
internal class FakeTor : FakeLan(emptyMap()), TorDialer {
    override var target: TorTarget? = null
    var ready = true
    /** Onion address → the Mac hosting it. */
    val services = mutableMapOf<String, SeamDevice>()
    /** Every address dialled, in order. */
    val dialled = mutableListOf<String>()

    override fun discover(found: (LanAddress) -> Unit, lost: (LanAddress) -> Unit) {
        discovering = true
        discoveries++
        this.found = found
        if (ready) announce()
    }

    /** Tor became ready: the target is reported, as TorLan does. */
    fun announce() {
        val t = target ?: return
        found?.invoke(LanAddress(t.onionAddress, 1))
    }

    override fun macAt(address: LanAddress): SeamDevice = services.getValue(address.host)

    /** As TorLan: an address nobody hosts gives a link that closes ("unreachable"), never a throw. */
    override fun connect(address: LanAddress, listener: LinkListener): Link {
        dialled += address.host
        if (address.host in services) return super.connect(address, listener)
        opened++
        listener.onClosed("unreachable")
        return object : Link {
            override fun send(frame: ByteArray) = false
            override fun close() = Unit
        }
    }
}
