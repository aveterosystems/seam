// SPDX-License-Identifier: AGPL-3.0-only
package com.avetero.seam

import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import com.avetero.seam.crypto.diagnosticsExport

/**
 * "Share diagnostics": what Seam did lately on this phone, as text in the share sheet, so the
 * user decides where it goes (specs/diagnostics.md). It's kept in memory only and holds no
 * address, key or name; nothing leaves the phone unless the user sends it.
 */
internal object Diagnostics {
    /**
     * Characters at most in the share intent. Strings travel as UTF-16 through Binder, whose
     * buffer (1 MB) the whole transaction shares, and a full recorder (512 KB of events) would
     * nearly fill it alone: what doesn't fit is the oldest events.
     */
    const val MAX_SHARE_CHARS = 100_000

    /** The export's header lines (its marker, the app and exported-at) always stay. */
    private const val HEADER_LINES = 3

    fun share(context: Context) {
        val text = fitForShare(diagnosticsExport(header(context)))
        val send = Intent(Intent.ACTION_SEND)
            .setType("text/plain")
            .putExtra(Intent.EXTRA_SUBJECT, "Seam diagnostics")
            .putExtra(Intent.EXTRA_TEXT, text)
        context.startActivity(Intent.createChooser(send, "Share diagnostics").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    /** [text] whole if it fits in [MAX_SHARE_CHARS]; else its header, a note, and the newest whole lines that fit. */
    fun fitForShare(text: String): String {
        if (text.length <= MAX_SHARE_CHARS) return text
        val lines = text.trimEnd('\n').split('\n')
        val head = lines.take(HEADER_LINES)
        val events = lines.drop(HEADER_LINES)
        // The note's length depends on the count: leave room for the longest it can be
        var room = MAX_SHARE_CHARS - head.sumOf { it.length + 1 } - note(events.size).length - 1
        val kept = ArrayDeque<String>()
        for (line in events.asReversed()) {
            if (line.length + 1 > room) break
            kept.addFirst(line)
            room -= line.length + 1
        }
        return (head + note(events.size - kept.size) + kept).joinToString("\n", postfix = "\n")
    }

    private fun note(leftOut: Int) = "… $leftOut older lines left out, to fit the share sheet"

    /** The app, Android's version and the kind of network, so the computer's export lines up with this one. */
    private fun header(context: Context): String =
        "Seam ${BuildConfig.VERSION_NAME} (${BuildConfig.FLAVOR}) on Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}), network: ${network(context)}"

    private fun network(context: Context): String {
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return "unknown"
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return "none"
        return when {
            // A VPN runs over another transport, and both are set: name the VPN as well
            caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) -> "VPN over " + underlying(caps)
            else -> underlying(caps)
        }
    }

    private fun underlying(caps: NetworkCapabilities): String = when {
        caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "Wi‑Fi"
        caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "mobile"
        caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "wired"
        else -> "other"
    }
}
