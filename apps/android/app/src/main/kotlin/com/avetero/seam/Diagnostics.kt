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
    fun share(context: Context) {
        val text = diagnosticsExport(header(context))
        val send = Intent(Intent.ACTION_SEND)
            .setType("text/plain")
            .putExtra(Intent.EXTRA_SUBJECT, "Seam diagnostics")
            .putExtra(Intent.EXTRA_TEXT, text)
        context.startActivity(Intent.createChooser(send, "Share diagnostics").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    /** The app, Android's version and the kind of network, so the computer's export lines up with this one. */
    private fun header(context: Context): String =
        "Seam ${BuildConfig.VERSION_NAME} (${BuildConfig.FLAVOR}) on Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}), network: ${network(context)}"

    private fun network(context: Context): String {
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return "unknown"
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return "none"
        return when {
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "Wi‑Fi"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "mobile"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "wired"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) -> "VPN"
            else -> "other"
        }
    }
}
