// SPDX-License-Identifier: AGPL-3.0-only
package com.avetero.seam.session

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/** A Seam service seen on the local network: maybe our Mac, maybe someone else's. */
data class LanAddress(val host: String, val port: Int)

/**
 * The same-Wi‑Fi path's plumbing: finding Macs, and a plain byte stream to one. It carries only
 * what the core's LanChannel produces, so everything on it is already encrypted and authenticated.
 * Reuses [RelayLink]/[RelayListener] as the byte pipe: here a "frame" is just bytes read or written.
 */
interface Lan {
    /** Start looking; [found] and [lost] may be called on any thread, and repeatedly. */
    fun discover(found: (LanAddress) -> Unit, lost: (LanAddress) -> Unit)
    fun stopDiscovery()
    /** That service refused us: free its place, so it doesn't count toward any cap. */
    fun forget(address: LanAddress) {}
    fun connect(address: LanAddress, listener: RelayListener): RelayLink
}

/** NSD (mDNS) discovery of `_seam._tcp` and raw TCP: no HTTP, so Android's cleartext policy doesn't apply. */
class NsdLan(context: Context) : Lan {
    private val nsd = context.getSystemService(NsdManager::class.java)
    private val connectivity = context.getSystemService(ConnectivityManager::class.java)
    /** The current discovery; resolves started for an older one are dropped. */
    @Volatile private var discovery: NsdManager.DiscoveryListener? = null
    private val resolver = Executors.newSingleThreadExecutor()
    /** Closers of every open link, so losing Wi‑Fi closes them at once. */
    private val open = java.util.concurrent.ConcurrentHashMap.newKeySet<(String) -> Unit>()
    /** The current discovery's services by name. */
    private val known = mutableMapOf<String, LanAddress>()
    /**
     * Names being resolved or resolved to a local address, so each is resolved once. Bounded only so a
     * flood can't queue unbounded work: Session caps what it tries, and refused services don't count there.
     */
    private val seen = mutableSetOf<String>()

    // A link whose Wi‑Fi went away would otherwise look open until TCP gives up, minutes later
    // A Mac that vanishes while the Wi‑Fi stays up (asleep, crashed) is only noticed when TCP gives up; nothing is
    // lost meanwhile, since Session keeps what the Mac hasn't acked and resends it, it just arrives late
    private val wifiWatch by lazy {
        val request = NetworkRequest.Builder().addTransportType(NetworkCapabilities.TRANSPORT_WIFI).build()
        runCatching {
            connectivity.registerNetworkCallback(request, object : ConnectivityManager.NetworkCallback() {
                override fun onLost(network: Network) = open.toList().forEach { it("wifi lost") }
            })
        }
    }

    override fun discover(found: (LanAddress) -> Unit, lost: (LanAddress) -> Unit) {
        if (discovery != null) return
        synchronized(known) { known.clear() }
        synchronized(seen) { seen.clear() } // a fresh discovery hears everything again
        lateinit var listener: NsdManager.DiscoveryListener
        listener = object : NsdManager.DiscoveryListener {
            override fun onServiceFound(info: NsdServiceInfo) {
                if (discovery !== listener) return // from a discovery we've stopped
                val fresh = synchronized(seen) { info.serviceName !in seen && seen.size < MAX_SERVICES && seen.add(info.serviceName) }
                if (!fresh) return
                resolve(info, listener, unresolved = { synchronized(seen) { seen.remove(info.serviceName) } }) { a ->
                    if (!isLocalNetwork(a.host)) {
                        synchronized(seen) { seen.remove(info.serviceName) } // never tried, so it holds no place
                        return@resolve
                    }
                    synchronized(known) { known[info.serviceName] = a }
                    found(a)
                }
            }
            override fun onServiceLost(info: NsdServiceInfo) {
                if (discovery !== listener) return
                synchronized(seen) { seen.remove(info.serviceName) }
                synchronized(known) { known.remove(info.serviceName) }?.let(lost)
            }
            override fun onDiscoveryStarted(serviceType: String) = Unit
            override fun onDiscoveryStopped(serviceType: String) = Unit
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) { discovery = null }
            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) = Unit
        }
        discovery = listener
        runCatching { nsd.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, listener) }.onFailure { discovery = null }
    }

    override fun forget(address: LanAddress) {
        val names = synchronized(known) { known.filterValues { it == address }.keys.toList().onEach(known::remove) }
        synchronized(seen) { seen.removeAll(names.toSet()) }
    }

    override fun stopDiscovery() {
        val l = discovery ?: return
        discovery = null
        runCatching { nsd.stopServiceDiscovery(l) }
    }

    // shortcut: resolveService is deprecated on API 34+ but still works there; move to registerServiceInfoCallback when minSdk reaches 34
    @Suppress("DEPRECATION")
    private fun resolve(info: NsdServiceInfo, owner: NsdManager.DiscoveryListener, unresolved: () -> Unit, done: (LanAddress) -> Unit) = resolver.execute {
        if (discovery !== owner) return@execute // discovery stopped (or restarted) since this was queued
        // NSD resolves one service at a time: wait for each before the next
        val finished = java.util.concurrent.CountDownLatch(1)
        nsd.resolveService(info, object : NsdManager.ResolveListener {
            override fun onResolveFailed(info: NsdServiceInfo, errorCode: Int) {
                unresolved() // its place is free again
                finished.countDown()
            }
            override fun onServiceResolved(info: NsdServiceInfo) {
                if (discovery === owner) info.host?.hostAddress?.let { done(LanAddress(it, info.port)) }
                finished.countDown()
            }
        })
        finished.await(RESOLVE_TIMEOUT_S, java.util.concurrent.TimeUnit.SECONDS)
    }

    override fun connect(address: LanAddress, listener: RelayListener): RelayLink {
        wifiWatch
        return connectTcp(address, listener, open)
    }

    private companion object {
        const val SERVICE_TYPE = "_seam._tcp"
        const val RESOLVE_TIMEOUT_S = 10L
        const val MAX_SERVICES = 64
    }
}

/**
 * A raw TCP link: connects and writes on one thread (in order), reads on another. [close] is
 * graceful: what [RelayLink.send] already accepted is written first (at most [DRAIN_MS]), so "send,
 * then close" never drops the last frame. Errors close at once. [open] tracks the abrupt closers,
 * so losing Wi‑Fi can close every link. [allowed] guards the address (tests use loopback).
 */
internal fun connectTcp(
    address: LanAddress,
    listener: RelayListener,
    open: MutableSet<(String) -> Unit> = java.util.concurrent.ConcurrentHashMap.newKeySet(),
    allowed: (String) -> Boolean = ::isLocalNetwork,
): RelayLink {
    val socket = Socket()
    val accepting = AtomicBoolean(true)
    val finished = AtomicBoolean(false)
    val writer = Executors.newSingleThreadExecutor()
    lateinit var closer: (String) -> Unit
    fun closeOnce(reason: String) {
        accepting.set(false)
        if (!finished.compareAndSet(false, true)) return
        open.remove(closer)
        runCatching { socket.close() }
        writer.shutdownNow()
        listener.onClosed(reason)
    }
    closer = ::closeOnce
    open += closer
    // The connect runs first on the writer, so anything sent before it completes waits behind it
    writer.execute {
        try {
            // Checked again here: Session filters too, but this is where a socket is actually opened
            if (!allowed(address.host)) throw IOException("not a local address")
            socket.keepAlive = true
            socket.tcpNoDelay = true
            socket.connect(InetSocketAddress(address.host, address.port), CONNECT_TIMEOUT_MS)
        } catch (e: IOException) {
            return@execute closeOnce(e.javaClass.simpleName)
        }
        Thread {
            val buf = ByteArray(64 * 1024)
            try {
                val input = socket.getInputStream()
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    listener.onFrame(buf.copyOf(n))
                }
                closeOnce("closed")
            } catch (e: IOException) {
                closeOnce(e.javaClass.simpleName)
            }
        }.apply { isDaemon = true; name = "seam-lan-read" }.start()
    }
    return object : RelayLink {
        override fun send(frame: ByteArray): Boolean {
            if (!accepting.get()) return false
            return runCatching {
                writer.execute {
                    try {
                        socket.getOutputStream().apply { write(frame); flush() }
                    } catch (e: IOException) {
                        closeOnce(e.javaClass.simpleName)
                    }
                }
            }.isSuccess
        }

        override fun abort() = closeOnce("aborted")

        override fun close() {
            if (!accepting.compareAndSet(true, false)) return
            // Behind everything already queued: tell the Mac we're done sending, then let the writer finish
            runCatching { writer.execute { runCatching { socket.shutdownOutput() } } }
            writer.shutdown()
            Thread {
                runCatching { writer.awaitTermination(DRAIN_MS, java.util.concurrent.TimeUnit.MILLISECONDS) }
                closeOnce("closed by us")
            }.apply { isDaemon = true; name = "seam-lan-close" }.start()
        }
    }
}

private const val CONNECT_TIMEOUT_MS = 5_000
/** How long a graceful close waits for queued writes: a farewell is small; a stuck socket isn't worth more. */
private const val DRAIN_MS = 2_000L

/**
 * Whether [host] is an address literal inside a local network: RFC 1918, IPv4 link-local, IPv6
 * link-local or unique local. Never loopback, public, multicast or a name (which would need DNS).
 */
fun isLocalNetwork(host: String): Boolean {
    val ip = host.substringBefore('%') // an IPv6 zone ("%wlan0") says which interface, not where
    val v4 = Regex("""^(\d{1,3})\.(\d{1,3})\.(\d{1,3})\.(\d{1,3})$""").matchEntire(ip)
    val bytes = when {
        v4 != null -> v4.groupValues.drop(1).map { it.toInt() }.takeIf { b -> b.all { it <= 255 } } ?: return false
        ':' in ip && ip.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' || it == ':' || it == '.' } -> {
            // A literal: no DNS. IPv4-mapped (::ffff:a.b.c.d) comes back as IPv4
            val a = runCatching { InetAddress.getByName(ip) }.getOrNull() ?: return false
            a.address.map { it.toInt() and 0xff }
        }
        else -> return false
    }
    return when (bytes.size) {
        4 -> bytes[0] == 10 || (bytes[0] == 172 && bytes[1] in 16..31) || (bytes[0] == 192 && bytes[1] == 168) ||
            (bytes[0] == 169 && bytes[1] == 254)
        16 -> (bytes[0] == 0xfe && (bytes[1] and 0xc0) == 0x80) || (bytes[0] and 0xfe) == 0xfc
        else -> false
    }
}

/**
 * The user's connection switches (decision 0006), and what the Mac last said it allows. Only the
 * switches are saved through a [SettingsStore]; the rest is kept sealed in the app data.
 */
data class ConnectionSettings(
    val wifi: Boolean = true,
    val relay: Boolean = true,
    /** From the Mac's latest Hello; null until it sends one (older Macs never do): everything allowed. */
    val macAllows: Set<com.avetero.seam.protocol.v1.Transport>? = null,
    /** The counter of the Hello [macAllows] came from: one not higher is ignored. */
    val macHelloSeq: Long = 0,
    /** The counter of the last Hello this phone sent. */
    val helloSeq: Long = 0,
)

interface SettingsStore {
    fun load(): ConnectionSettings
    fun save(settings: ConnectionSettings)
}

/** Like [PrefsSettingsStore]: keeps only the switches (and whatever an older version left, until the first save). */
class MemorySettingsStore(private var settings: ConnectionSettings = ConnectionSettings()) : SettingsStore {
    override fun load() = settings
    override fun save(settings: ConnectionSettings) {
        this.settings = ConnectionSettings(settings.wifi, settings.relay)
    }
}

/** The switches in SharedPreferences: the user's preferences. Older versions kept the Mac-facing state here too; load() still reads it once, for the move. */
class PrefsSettingsStore(context: Context) : SettingsStore {
    private val prefs = context.getSharedPreferences("connection", Context.MODE_PRIVATE)

    override fun load(): ConnectionSettings {
        val wifi = prefs.getBoolean("wifi", true)
        val relay = prefs.getBoolean("relay", true)
        val mac = prefs.getStringSet("macAllows", null)
            ?.mapNotNull { it.toIntOrNull()?.let(com.avetero.seam.protocol.v1.Transport::forNumber) }?.toSet()
        // Both off can only be a damaged file: fall back to the defaults rather than never connecting
        val macSeq = prefs.getLong("macHelloSeq", 0)
        val mySeq = prefs.getLong("helloSeq", 0)
        return if (!wifi && !relay) ConnectionSettings(macAllows = mac, macHelloSeq = macSeq, helloSeq = mySeq)
        else ConnectionSettings(wifi, relay, mac, macSeq, mySeq)
    }

    override fun save(settings: ConnectionSettings) {
        prefs.edit()
            .putBoolean("wifi", settings.wifi)
            .putBoolean("relay", settings.relay)
            // The Mac-facing state now lives in the sealed app data (Session); these keys are only ever read, to migrate
            .remove("macAllows").remove("macHelloSeq").remove("helloSeq")
            .apply()
    }
}
