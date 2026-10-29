// SPDX-License-Identifier: AGPL-3.0-only
package com.avetero.seam.session

import java.net.URI
import java.net.URLDecoder
import java.util.Base64
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/** The Mac's pairing QR code (format: protocol/README.md, "The pairing QR code"). */
class PairingQr(
    val relayUrl: String,
    val groupId: ByteArray,
    val token: ByteArray,
    val identityKey: ByteArray,
    val relayKey: ByteArray,
    val macName: String,
) {
    /** For the confirm screen: the host the phone is about to trust with its traffic. */
    val relayHost: String get() = relayUrl.toHttpUrlOrNull(cleartext = relayUrl.startsWith("ws://"))?.host ?: relayUrl

    companion object {
        /**
         * Null for anything that isn't a valid v1 Seam pairing code. The relay must be `wss://`
         * (`ws://` only when [allowCleartext], i.e. debug builds) with no credentials, query or
         * fragment, so a crafted code can't crash the app or downgrade to plaintext.
         */
        fun parse(text: String, allowCleartext: Boolean = false): PairingQr? = runCatching {
            if (text.length > 4096) return null
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
            PairingQr(
                relayUrl = validRelay(q.getValue("r"), allowCleartext) ?: return null,
                groupId = b64("g", 16),
                token = b64("t", 32),
                identityKey = b64("i", 33),
                relayKey = b64("k", 32),
                macName = q["n"].orEmpty().take(128).ifBlank { "Mac" },
            )
        }.getOrNull()

        private fun validRelay(raw: String, allowCleartext: Boolean): String? {
            if (raw.length > 512) return null
            if (!raw.startsWith("wss://") && !(allowCleartext && raw.startsWith("ws://"))) return null
            val url = raw.toHttpUrlOrNull(cleartext = raw.startsWith("ws://")) ?: return null
            if (url.username.isNotEmpty() || url.password.isNotEmpty() || url.query != null || url.fragment != null) return null
            val s = url.toString()
            return (if (url.isHttps) "wss://" + s.removePrefix("https://") else "ws://" + s.removePrefix("http://")).trimEnd('/')
        }

        private fun String.toHttpUrlOrNull(cleartext: Boolean) =
            replaceFirst(if (cleartext) "ws://" else "wss://", if (cleartext) "http://" else "https://").toHttpUrlOrNull()
    }
}
