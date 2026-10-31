// SPDX-License-Identifier: AGPL-3.0-only
package com.avetero.seam.feature

import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.BatteryManager
import android.os.Handler
import android.os.Looper
import com.avetero.seam.protocol.v1.Payload
import com.avetero.seam.protocol.v1.Status
import com.avetero.seam.session.Feature
import com.avetero.seam.session.SessionApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** The Mac's battery as it last sent it, and when (ms), for the home screen. */
data class MacStatus(val batteryPct: Int, val charging: Boolean, val at: Long) {
    companion object {
        /** A uint32 arrives as a signed Int: never negative and never over 100 on screen. */
        fun of(s: Status, at: Long) = MacStatus(s.batteryPct.toUInt().coerceAtMost(100u).toInt(), s.charging, at)
    }
}

/**
 * Sends the phone's battery, whether it's on Wi‑Fi or mobile data, and Do Not Disturb to the Mac:
 * on connect, and when any changes. Keeps the Mac's battery when the Mac sends it.
 * The Wi‑Fi's name isn't sent: Android only gives it to apps with location access.
 */
class StatusFeature(context: Context) : Feature {
    override val handles = setOf(Payload.BodyCase.STATUS)

    private val _mac = MutableStateFlow<MacStatus?>(null)
    val mac: StateFlow<MacStatus?> = _mac

    private data class Sent(val pct: Int, val charging: Boolean, val network: String, val dnd: Boolean)

    private var session: SessionApi? = null
    private var lastSent: Sent? = null
    private val app = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private val connectivity = app.getSystemService(ConnectivityManager::class.java)
    private val notificationManager = app.getSystemService(NotificationManager::class.java)

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == Intent.ACTION_BATTERY_CHANGED) latest = intent
            update(force = false)
        }
    }

    // ACTION_BATTERY_CHANGED is sticky: registering returns the current state straight away
    private var latest: Intent? = app.registerReceiver(receiver, IntentFilter(Intent.ACTION_BATTERY_CHANGED))

    init {
        app.registerReceiver(receiver, IntentFilter(NotificationManager.ACTION_INTERRUPTION_FILTER_CHANGED))
        connectivity.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
            override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) { main.post { update(force = false) } }
            override fun onLost(network: Network) { main.post { update(force = false) } }
        })
    }

    override fun onConnected(session: SessionApi) {
        this.session = session
        main.post { update(force = true) }
    }

    override fun receive(payload: Payload, fromPeer: String) {
        _mac.value = MacStatus.of(payload.status, System.currentTimeMillis())
    }

    override fun reset() {
        _mac.value = null
    }

    private fun network(): String {
        val caps = connectivity.getNetworkCapabilities(connectivity.activeNetwork) ?: return ""
        return when {
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "wifi"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "cellular"
            else -> ""
        }
    }

    private fun dnd() = notificationManager.currentInterruptionFilter.let {
        it != NotificationManager.INTERRUPTION_FILTER_ALL && it != NotificationManager.INTERRUPTION_FILTER_UNKNOWN
    }

    private fun update(force: Boolean) {
        val intent = latest ?: return
        val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
        if (level < 0 || scale <= 0) return
        val now = Sent(
            pct = level * 100 / scale,
            charging = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1).let {
                it == BatteryManager.BATTERY_STATUS_CHARGING || it == BatteryManager.BATTERY_STATUS_FULL
            },
            network = network(),
            dnd = dnd(),
        )
        if (!force && lastSent == now) return
        val s = session ?: return
        lastSent = now
        s.send(
            Payload.newBuilder().setStatus(
                Status.newBuilder().setBatteryPct(now.pct).setCharging(now.charging).setNetwork(now.network).setDnd(now.dnd),
            ).build(),
        )
    }
}
