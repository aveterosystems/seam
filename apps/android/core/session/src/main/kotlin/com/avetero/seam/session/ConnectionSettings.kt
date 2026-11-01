// SPDX-License-Identifier: AGPL-3.0-only
package com.avetero.seam.session

import android.content.Context
import com.avetero.seam.crypto.PeerPath
import com.avetero.seam.protocol.v1.Transport

/**
 * The user's connection switches, and what the Mac last said it allows. Only the
 * switches are saved through a [SettingsStore]; the rest is kept sealed in the app data.
 */
data class ConnectionSettings(
    val wifi: Boolean = true,
    /** Internet (Tor). */
    val tor: Boolean = true,
    /** From the Mac's latest Hello, as the core applied it. Null: everything (never heard, or an older Mac). Empty: nothing in common. */
    val macAllows: Set<Transport>? = null,
    /** The counter of the Hello [macAllows] came from: one not higher is ignored. */
    val macHelloSeq: Long = 0,
    /** The counter of the last Hello this phone sent. */
    val helloSeq: Long = 0,
    /** Paused until then (unix ms; Long.MAX_VALUE: until resumed); 0: not paused. Only the phone pauses. */
    val pausedUntil: Long = 0,
    /** Paths on at both ends, as the core decides from the switches and the Mac's Hello. Never saved. */
    val allowed: Set<PeerPath> = setOf(PeerPath.LAN, PeerPath.TOR),
)

interface SettingsStore {
    fun load(): ConnectionSettings
    fun save(settings: ConnectionSettings)
}

/** Like [PrefsSettingsStore]: keeps only the switches and the pause. */
class MemorySettingsStore(private var settings: ConnectionSettings = ConnectionSettings()) : SettingsStore {
    override fun load() = settings
    override fun save(settings: ConnectionSettings) {
        this.settings = ConnectionSettings(settings.wifi, settings.tor, pausedUntil = settings.pausedUntil)
    }
}

/** The switches and the pause in SharedPreferences: the user's preferences. */
class PrefsSettingsStore(context: Context) : SettingsStore {
    private val prefs = context.getSharedPreferences("connection", Context.MODE_PRIVATE)

    override fun load(): ConnectionSettings {
        val wifi = prefs.getBoolean("wifi", true)
        val tor = prefs.getBoolean("tor", true)
        val paused = prefs.getLong("pausedUntil", 0)
        // Both off can only be a damaged file: fall back to the defaults rather than never connecting
        return if (!wifi && !tor) ConnectionSettings(pausedUntil = paused) else ConnectionSettings(wifi, tor, pausedUntil = paused)
    }

    override fun save(settings: ConnectionSettings) {
        prefs.edit()
            .putBoolean("wifi", settings.wifi)
            .putBoolean("tor", settings.tor)
            .putLong("pausedUntil", settings.pausedUntil)
            // Left by relay-era versions: the relay switch, and Mac-facing state that now lives in the sealed app data
            .remove("relay").remove("macAllows").remove("macHelloSeq").remove("helloSeq")
            .apply()
    }
}
