// SPDX-License-Identifier: AGPL-3.0-only
package com.avetero.seam.session

import java.net.URI
import java.net.URLDecoder
import java.util.Base64

/** The Mac's pairing QR code (format: protocol/README.md, "The pairing QR code"). */
class PairingQr(
    val relayUrl: String,
    val groupId: ByteArray,
    val token: ByteArray,
    val identityKey: ByteArray,
    val relayKey: ByteArray,
    val macName: String,
) {
    companion object {
        /** Null for anything that isn't a valid v1 Seam pairing code. */
        fun parse(text: String): PairingQr? = runCatching {
            val uri = URI(text)
            if (uri.scheme != "seam" || uri.authority != "pair") return null
            val q = uri.rawQuery.orEmpty().split("&").mapNotNull {
                val i = it.indexOf('=')
                if (i <= 0) null else it.substring(0, i) to URLDecoder.decode(it.substring(i + 1), "UTF-8")
            }.toMap()
            if (q["v"] != "1") return null
            val b64 = { key: String, size: Int ->
                Base64.getUrlDecoder().decode(q.getValue(key)).also { require(it.size == size) { "$key size" } }
            }
            val relay = q.getValue("r")
            require(relay.startsWith("ws://") || relay.startsWith("wss://")) { "relay scheme" }
            PairingQr(
                relayUrl = relay.trimEnd('/'),
                groupId = b64("g", 16),
                token = b64("t", 32),
                identityKey = b64("i", 33),
                relayKey = b64("k", 32),
                macName = q["n"].orEmpty().ifBlank { "Mac" },
            )
        }.getOrNull()
    }
}
