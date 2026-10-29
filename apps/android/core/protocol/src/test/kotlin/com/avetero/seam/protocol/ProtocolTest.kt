package com.avetero.seam.protocol

import com.google.protobuf.ByteString
import org.junit.Assert.assertEquals
import org.junit.Test
import com.avetero.seam.protocol.v1.Clipboard
import com.avetero.seam.protocol.v1.Inner
import com.avetero.seam.protocol.v1.Payload
import com.avetero.seam.protocol.v1.clipboard
import com.avetero.seam.protocol.v1.inner
import com.avetero.seam.protocol.v1.payload

class ProtocolTest {
    @Test fun decodesTheGoldenVector() {
        val bytes = java.io.File(System.getProperty("seam.testdata"), "inner_clipboard.bin").readBytes()
        val msg = Inner.parseFrom(bytes)
        assertEquals(ByteString.copyFrom(ByteArray(16) { it.toByte() }), msg.msgId)
        assertEquals(1_793_266_000_000, msg.sentAt)
        assertEquals("ZK4 9QP · café", msg.payload.clipboard.text)
        assertEquals(1_793_265_999_000, msg.payload.clipboard.copiedAt)
        assertEquals(bytes.toList(), msg.toByteArray().toList()) // re-encodes to the same bytes
    }

    @Test fun innerRoundTripsThroughBytes() {
        val msg = inner {
            msgId = ByteString.copyFrom(ByteArray(16) { it.toByte() })
            sentAt = 1_793_266_000_000
            payload = payload { clipboard = clipboard { text = "ZK4 9QP"; copiedAt = 1 } }
        }
        val back = Inner.parseFrom(msg.toByteArray())
        assertEquals(Payload.BodyCase.CLIPBOARD, back.payload.bodyCase)
        assertEquals("ZK4 9QP", back.payload.clipboard.text)
        assertEquals(msg.msgId, back.msgId)
    }

    @Test fun fieldsFromANewerAppDontBreakParsing() {
        // A newer peer adds field 99 to Clipboard; this build must still read the rest
        val newer = Clipboard.newBuilder().setText("hi").build().toByteArray() +
            byteArrayOf(0x98.toByte(), 0x06, 0x01) // tag (99 << 3 | varint) = 792 as a 2-byte varint, value 1
        val parsed = Clipboard.parseFrom(newer)
        assertEquals("hi", parsed.text)
        // and passes it on untouched if this build re-sends the message
        assertEquals(newer.size, parsed.toByteArray().size)
    }
}
