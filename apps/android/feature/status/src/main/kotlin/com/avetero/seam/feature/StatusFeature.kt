// SPDX-License-Identifier: AGPL-3.0-only
package com.avetero.seam.feature

import android.app.NotificationManager
import android.app.usage.StorageStatsManager
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
import android.os.StatFs
import android.os.SystemClock
import android.os.storage.StorageManager
import com.avetero.seam.protocol.v1.Payload
import com.avetero.seam.protocol.v1.Status
import com.avetero.seam.protocol.v1.StatusRequest
import com.avetero.seam.session.Feature
import com.avetero.seam.session.SessionApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.concurrent.Executors
import kotlin.math.abs
import kotlin.math.roundToInt

/** The Mac's status as it last sent it (battery, storage, CPU, memory), and when (ms). The shared looks read it. */
data class MacStatus(val status: Status, val at: Long)

/**
 * Sends the phone's battery, whether it's on Wi‑Fi or mobile data, Do Not Disturb and free storage
 * to the Mac: on connect, when any changes, and when the Mac asks (its battery details are open). Storage, the battery's power
 * and its times ride along; they never send by themselves. Keeps the Mac's status when the Mac sends it, and asks for a fresh one while its sheet is open.
 * The Wi‑Fi's name isn't sent: Android only gives it to apps with location access.
 */
class StatusFeature(context: Context) : Feature {
    override val handles = setOf(Payload.BodyCase.STATUS, Payload.BodyCase.STATUS_REQUEST)

    private val _mac = MutableStateFlow<MacStatus?>(null)
    val mac: StateFlow<MacStatus?> = _mac

    // What decides a send. Storage isn't in it: it rides along with the latest reading, never sends by itself
    private data class Sent(val pct: Int, val charging: Boolean, val network: String, val dnd: Boolean)

    private var session: SessionApi? = null
    private var lastSent: Sent? = null
    private val app = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private val connectivity = app.getSystemService(ConnectivityManager::class.java)
    private val notificationManager = app.getSystemService(NotificationManager::class.java)
    private val batteryManager = app.getSystemService(BatteryManager::class.java)
    private val drain = Drain()

    // Storage, as the phone's own Settings shows it (256 GB, free including cache it can clear).
    // Asking can take seconds, so it's read off the main thread at most once a minute, and the
    // status sends whatever was last read (0 until then, which the Mac treats as not sent). Each
    // connection's first completed reading sends once; later ones wait for the next battery,
    // network or DND change, so storage never keeps the connection awake (rule 8).
    private val storageStats = app.getSystemService(StorageStatsManager::class.java)
    private val worker = Executors.newSingleThreadExecutor()
    private var storage = 0L to 0L // free (rounded), total
    private var storageReadAt = 0L
    private var storageRead = false

    private fun refreshStorage() {
        val now = SystemClock.elapsedRealtime()
        if (storageReadAt != 0L && now - storageReadAt < 60_000) return
        storageReadAt = now
        worker.execute {
            val fs = StatFs(app.filesDir.path)
            val total = runCatching { storageStats.getTotalBytes(StorageManager.UUID_DEFAULT) }.getOrElse { fs.totalBytes }
            val free = runCatching { storageStats.getFreeBytes(StorageManager.UUID_DEFAULT) }.getOrElse { fs.availableBytes }
            main.post {
                storage = roundedFree(free) to total
                if (!storageRead) {
                    storageRead = true
                    update(force = true)
                }
            }
        }
    }

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
        main.post {
            // A fresh storage reading for this connection, sent once when it's in
            storageRead = false
            storageReadAt = 0L
            update(force = true)
        }
    }

    override fun receive(payload: Payload, fromPeer: String) {
        if (payload.hasStatusRequest()) main.post { update(force = true) } else _mac.value = MacStatus(payload.status, System.currentTimeMillis())
    }

    /** The sheet of the Mac's resources is open: ask for a fresh reading. An older Mac ignores it. */
    fun requestMac() {
        session?.send(Payload.newBuilder().setStatusRequest(StatusRequest.getDefaultInstance()).build())
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
        refreshStorage()
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
        // Every level change comes through here, connected or not, so the drain rate is measured all along
        val left = drain.minutesLeft(SystemClock.elapsedRealtime(), now.pct, now.charging)
        if (!force && lastSent == now) return
        val s = session ?: return
        lastSent = now
        val status = Status.newBuilder().setBatteryPct(now.pct).setCharging(now.charging).setNetwork(now.network).setDnd(now.dnd)
            .setStorageFreeBytes(storage.first).setStorageTotalBytes(storage.second)
        powerMw(batteryManager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW), intent.getIntExtra(BatteryManager.EXTRA_VOLTAGE, -1))
            ?.let { status.setPowerMw(it) }
        if (now.charging) {
            // -1 while Android is still working it out, 0 when full
            batteryManager.computeChargeTimeRemaining().takeIf { it > 0 }?.let { status.setMinutesToFull(((it + 59_999) / 60_000).toInt()) }
        } else {
            left?.let { status.setMinutesLeft(it) }
        }
        s.send(Payload.newBuilder().setStatus(status).build())
    }
}

private const val STEP = 100_000_000L

/** Free space to the nearest 100 MB: precise enough to show, and battery ticks don't resend it. */
internal fun roundedFree(bytes: Long) = (bytes + STEP / 2) / STEP * STEP

/**
 * The battery's power in mW from Android's current (µA) and voltage (mV), or null when the phone doesn't say. Makers
 * disagree on the current's sign and some report mA: under 10 000 is read as mA, since no phone running Seam draws
 * under 10 mA.
 */
internal fun powerMw(current: Int, voltageMv: Int): Int? {
    if (current == Int.MIN_VALUE || current == 0 || voltageMv <= 0) return null
    val microAmps = abs(current.toLong()).let { if (it < 10_000) it * 1000 else it }
    return (microAmps * voltageMv / 1_000_000).toInt().takeIf { it > 0 }
}

/**
 * Time left on battery, from how fast the level has fallen since it was unplugged (or since Seam started): Android
 * keeps its own estimate from apps. Nothing until it has fallen 3 points, so a single tick isn't taken for a rate.
 */
internal class Drain {
    private var from: Pair<Long, Int>? = null // (elapsed ms, level) when it went on battery

    fun minutesLeft(nowMs: Long, pct: Int, charging: Boolean): Int? {
        if (charging) {
            from = null
            return null
        }
        val (t0, p0) = from ?: (nowMs to pct).also { from = it }
        if (pct > p0) { // it rose without charging being seen: start again
            from = nowMs to pct
            return null
        }
        val fallen = p0 - pct
        if (fallen < 3) return null
        return (pct * (nowMs - t0) / 60_000.0 / fallen).roundToInt()
    }
}
