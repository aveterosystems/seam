// SPDX-License-Identifier: AGPL-3.0-only
package com.avetero.seam.session

import android.content.Context
import com.avetero.seam.crypto.PeerPath
import com.avetero.seam.protocol.v1.Transport

/**
 * The user's connection switches (decision 0006), and what the Mac last said it allows. Only the
 * switches are saved through a [SettingsStore]; the rest is kept sealed in the app data.
 */
data class ConnectionSettings(
    val wifi: Boolean = true,
    val relay: Boolean = true,
    /** From the Mac's latest Hello, as the core applied it. Null: everything (never heard, or an older Mac). Empty: nothing in common. */
    val macAllows: Set<Transport>? = null,
    /** The counter of the Hello [macAllows] came from: one not higher is ignored. */
    val macHelloSeq: Long = 0,
    /** The counter of the last Hello this phone sent. */
    val helloSeq: Long = 0,
    /** Paused until then (unix ms; Long.MAX_VALUE: until resumed); 0: not paused. Only the phone pauses. */
    val pausedUntil: Long = 0,
    /** Paths on at both ends, as the core decides from the switches and the Mac's Hello (decision 0006). Never saved. */
    val allowed: Set<PeerPath> = setOf(PeerPath.LAN, PeerPath.RELAY),
)

interface SettingsStore {
    fun load(): ConnectionSettings
    fun save(settings: ConnectionSettings)
}

/** Like [PrefsSettingsStore]: keeps only the switches (and whatever an older version left, until the first save). */
class MemorySettingsStore(private var settings: ConnectionSettings = ConnectionSettings()) : SettingsStore {
    override fun load() = settings
    override fun save(settings: ConnectionSettings) {
        this.settings = ConnectionSettings(settings.wifi, settings.relay, pausedUntil = settings.pausedUntil)
    }
}

/** The switches in SharedPreferences: the user's preferences. Older versions kept the Mac-facing state here too; load() still reads it once, for the move. */
class PrefsSettingsStore(context: Context) : SettingsStore {
    private val prefs = context.getSharedPreferences("connection", Context.MODE_PRIVATE)

    override fun load(): ConnectionSettings {
        val wifi = prefs.getBoolean("wifi", true)
        val relay = prefs.getBoolean("relay", true)
        val mac = prefs.getStringSet("macAllows", null)
            ?.mapNotNull { it.toIntOrNull()?.let(Transport::forNumber) }?.toSet()
        // Both off can only be a damaged file: fall back to the defaults rather than never connecting
        val macSeq = prefs.getLong("macHelloSeq", 0)
        val mySeq = prefs.getLong("helloSeq", 0)
        val paused = prefs.getLong("pausedUntil", 0)
        return if (!wifi && !relay) ConnectionSettings(macAllows = mac, macHelloSeq = macSeq, helloSeq = mySeq, pausedUntil = paused)
        else ConnectionSettings(wifi, relay, mac, macSeq, mySeq, paused)
    }

    override fun save(settings: ConnectionSettings) {
        prefs.edit()
            .putBoolean("wifi", settings.wifi)
            .putBoolean("relay", settings.relay)
            .putLong("pausedUntil", settings.pausedUntil)
            // The Mac-facing state now lives in the sealed app data (Session); these keys are only ever read, to migrate
            .remove("macAllows").remove("macHelloSeq").remove("helloSeq")
            .apply()
    }
}
