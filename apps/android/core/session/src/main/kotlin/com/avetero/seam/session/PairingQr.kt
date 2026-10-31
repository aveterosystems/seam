// SPDX-License-Identifier: AGPL-3.0-only
package com.avetero.seam.session

import java.net.URI
import java.net.URLDecoder
import java.util.Base64

/** The Mac's pairing QR code, version 2 (docs/tor/13-implementation-contract.md). */
class PairingQr(
    /** The Mac's new onion service for this pairing. */
    val onionAddress: String,
    /** This phone's restricted-discovery secret for that service: without it the address can't be reached. */
    val discoverySecret: ByteArray,
    val token: ByteArray,
    val identityKey: ByteArray,
    /** The Mac's channel key: pins the pairing channel to this Mac. */
    val relayKey: ByteArray,
    val macName: String,
) {
    companion object {
        /**
         * Null for anything that isn't a valid v2 Seam pairing code: every value checked for size and
         * form, so a crafted code can't crash the app or point it anywhere but an onion service.
         */
        fun parse(text: String): PairingQr? = runCatching {
            val q = query(text) ?: return null
            if (q["v"] != "2") return null
            val b64 = { key: String, size: Int ->
                Base64.getUrlDecoder().decode(q.getValue(key)).also { require(it.size == size) { "$key size" } }
            }
            PairingQr(
                onionAddress = q.getValue("o").takeIf { ONION.matches(it) } ?: return null,
                discoverySecret = b64("d", 32),
                token = b64("t", 32),
                identityKey = b64("i", 33),
                relayKey = b64("k", 32),
                macName = q["n"].orEmpty().take(128).ifBlank { "Mac" },
            )
        }.getOrNull()

        /** A pairing code from a Mac still on the relay version: it needs updating first. */
        fun isOldVersion(text: String): Boolean = runCatching { query(text)?.get("v") == "1" }.getOrDefault(false)

        private fun query(text: String): Map<String, String>? {
            if (text.length > 4096) return null
            val uri = URI(text)
            if (uri.scheme != "seam" || uri.authority != "pair") return null
            return uri.rawQuery.orEmpty().split("&").mapNotNull {
                val i = it.indexOf('=')
                if (i <= 0) null else it.substring(0, i) to URLDecoder.decode(it.substring(i + 1), "UTF-8")
            }.toMap()
        }

        /** A v3 onion address: 56 base32 characters. */
        private val ONION = Regex("[a-z2-7]{56}\\.onion")
    }
}
