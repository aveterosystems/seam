// SPDX-License-Identifier: AGPL-3.0-only
package com.avetero.seam.feature

import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class QrDecoderTest {
    /** A camera frame: padded rows (rowStride > width), the code drawn dark on light. */
    private fun frame(text: String?, width: Int = 640, height: Int = 480, stride: Int = 704): ByteArray {
        val luma = ByteArray(stride * height) { 0xE0.toByte() }
        if (text != null) {
            val m = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, 400, 400)
            for (y in 0 until m.height) for (x in 0 until m.width) if (m[x, y]) luma[(y + 40) * stride + x + 120] = 0x10
        }
        return luma
    }

    @Test fun readsAQrCodeFromAPaddedFrame() {
        val code = "seam://pair?v=1&test=" + "x".repeat(200)
        assertEquals(code, QrDecoder.decode(frame(code), 704, 640, 480))
    }

    @Test fun anEmptyFrameReadsNothing() {
        assertNull(QrDecoder.decode(frame(null), 704, 640, 480))
    }
}
