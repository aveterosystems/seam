// SPDX-License-Identifier: AGPL-3.0-only
package com.avetero.seam

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.os.Build
import android.os.PowerManager
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject

/** A newer release on GitHub, and where its APK and checksums are. */
data class Release(val version: String, val apkUrl: String, val apkName: String, val sumsUrl: String)

/** The parts of the updater that don't need Android: the release feed, versions, checksums. */
object UpdateFeed {
    const val LATEST = "https://api.github.com/repos/aveterosystems/seam/releases/latest"
    private val VERSION = Regex("""^v(\d+)\.(\d+)\.(\d+)$""")
    private const val DOWNLOADS = "https://github.com/aveterosystems/seam/releases/download/"
    /** Largest release JSON or SHA256SUMS Seam reads, and largest APK it downloads (SR-17-06). */
    const val MAX_TEXT = 1L shl 20
    const val MAX_APK = 150L shl 20

    /** GitHub's "latest release" JSON, if it's a newer, published version with both files. */
    fun newer(json: String, current: String): Release? = runCatching {
        val r = JSONObject(json)
        if (r.optBoolean("draft") || r.optBoolean("prerelease")) return null
        val tag = r.getString("tag_name")
        if (!isNewer(tag, current)) return null
        val version = tag.removePrefix("v")
        val assets = r.getJSONArray("assets")
        // Only this repo's download for exactly this tag and file name (SR-17-16)
        fun url(name: String) = (0 until assets.length()).map { assets.getJSONObject(it) }
            .firstOrNull { it.getString("name") == name }?.getString("browser_download_url")
            ?.takeIf { it == "$DOWNLOADS$tag/$name" }
        val apk = "Seam-$version-android.apk"
        Release(version, url(apk) ?: return null, apk, url("SHA256SUMS") ?: return null)
    }.getOrNull()

    /** True if tag `vX.Y.Z` is above [current] `X.Y.Z`. */
    fun isNewer(tag: String, current: String): Boolean {
        val a = VERSION.matchEntire(tag)?.groupValues?.drop(1)?.map { it.toIntOrNull() ?: return false } ?: return false
        val b = VERSION.matchEntire("v$current")?.groupValues?.drop(1)?.map { it.toIntOrNull() ?: return false } ?: return false
        return a.zip(b).firstOrNull { (x, y) -> x != y }?.let { (x, y) -> x > y } ?: false
    }

    /** The SHA-256 for [name] in a `sha256sum` file. */
    fun sumFor(sums: String, name: String): String? = sums.lineSequence()
        .map { it.trim().split(Regex("\\s+\\*?"), limit = 2) }
        .firstOrNull { it.size == 2 && it[1] == name && it[0].matches(Regex("[0-9a-f]{64}")) }?.get(0)

    fun sha256(file: File): String = MessageDigest.getInstance("SHA-256").let { md ->
        file.inputStream().use { input ->
            val buf = ByteArray(64 * 1024)
            while (true) { val n = input.read(buf); if (n < 0) break; md.update(buf, 0, n) }
        }
        md.digest().joinToString("") { "%02x".format(it) }
    }
}

/**
 * Updates for the GitHub build (`full`): as often as the user picks (a day by default), with the screen on, Seam asks GitHub for the
 * latest release, and if it's newer, offers it. Nothing is checked until the phone is paired and the
 * user has answered whether to check (SR-17-04). An update is installed only after its checksum
 * matches the release's SHA256SUMS and its only signing certificate is Seam's pinned one; Android then
 * checks the same key signed the installed app. Tapping Update is the user's yes: once Seam installed
 * itself before, Android installs without asking again; otherwise it shows its confirm screen. The Play build is updated by Play,
 * and debug builds never update.
 */
class Updater(private val app: Context, private val paired: () -> Boolean) {
    val available = BuildConfig.FLAVOR == "full" && !BuildConfig.DEBUG

    private val prefs = app.getSharedPreferences("updates", Context.MODE_PRIVATE)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val http = OkHttpClient.Builder().callTimeout(5, TimeUnit.MINUTES).build()
    private var installing = false

    private val _offer = MutableStateFlow(storedOffer())
    /** A newer release, once found; kept across restarts (SR-17-13). */
    val offer: StateFlow<Release?> = _offer
    private val _status = MutableStateFlow<String?>(null)
    /** What the updater is doing, or why it stopped, for Settings and Home. */
    val status: StateFlow<String?> = _status
    private val _asked = MutableStateFlow(prefs.getBoolean("asked", false))
    /** Whether the user has answered "check for updates automatically?" (Home asks once). */
    val asked: StateFlow<Boolean> = _asked

    var automatic: Boolean
        get() = prefs.getBoolean("automatic", true)
        set(value) { prefs.edit().putBoolean("automatic", value).apply() }

    /** How often to check, in ms: one of [INTERVALS]. */
    var interval: Long
        get() = prefs.getLong("interval", DAY_MS).takeIf { it in INTERVALS } ?: DAY_MS
        set(value) { prefs.edit().putLong("interval", value).apply() }

    /** The user's answer to Home's one-time question. */
    fun answer(checkAutomatically: Boolean) {
        automatic = checkAutomatically
        prefs.edit().putBoolean("asked", true).apply()
        _asked.value = true
    }

    /** Checks when [interval] has passed since the last successful check, while the screen is on. */
    fun start() {
        if (!available) return
        val power = app.getSystemService(PowerManager::class.java)
        scope.launch {
            while (true) {
                val due = System.currentTimeMillis() - prefs.getLong("lastCheck", 0) >= interval
                if (due && automatic && _asked.value && paired() && power.isInteractive) check(notify = true)
                delay(HALF_HOUR_MS) // the shortest interval; a failed check is tried again then
            }
        }
    }

    fun checkNow() {
        if (available) scope.launch { check(notify = false) }
    }

    private suspend fun check(notify: Boolean) {
        _status.value = "Checking…"
        val json = runCatching { withContext(Dispatchers.IO) { getText(UpdateFeed.LATEST, github = true) } }.getOrNull()
        if (json == null) {
            _status.value = "Couldn't reach GitHub. Seam will try again later."
            return
        }
        prefs.edit().putLong("lastCheck", System.currentTimeMillis()).apply()
        val release = UpdateFeed.newer(json, BuildConfig.VERSION_NAME)
        setOffer(release)
        _status.value = if (release == null) "Seam is up to date" else "Seam ${release.version} is available"
        // One notification per version, not one a day (SR-17-13)
        if (release != null && notify && prefs.getString("notified", null) != release.version) {
            notifyOffer(release)
            prefs.edit().putString("notified", release.version).apply()
        }
    }

    /** Downloads, checks and hands the update to Android's installer, which asks the user. */
    fun install(release: Release) {
        if (!available || installing) return // one at a time (SR-17-07)
        if (!app.packageManager.canRequestPackageInstalls()) {
            _status.value = "Allow Seam to install updates, then tap Update again"
            app.startActivity(
                Intent(android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, android.net.Uri.parse("package:${app.packageName}"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
            return
        }
        installing = true
        scope.launch {
            try {
                _status.value = "Downloading Seam ${release.version}…"
                val dir = File(app.cacheDir, "updates")
                val problem = withContext(Dispatchers.IO) {
                    dir.deleteRecursively(); dir.mkdirs()
                    runCatching { fetchAndCheck(release, File(dir, release.apkName)) }.getOrElse { "The download didn't finish. Try again." }
                }
                if (problem != null) {
                    _status.value = problem
                } else {
                    _status.value = "Installing…"
                    val started = withContext(Dispatchers.IO) { runCatching { commit(File(dir, release.apkName)) }.isSuccess }
                    if (!started) _status.value = "Android couldn't start the update."
                }
                withContext(Dispatchers.IO) { dir.deleteRecursively() }
            } finally {
                installing = false
            }
        }
    }


    /** The installer's outcome for Seam's session, from [InstallResult]. */
    internal fun installFinished(status: Int) {
        _status.value = when (status) {
            PackageInstaller.STATUS_SUCCESS -> "Updated"
            PackageInstaller.STATUS_FAILURE_ABORTED -> "Update cancelled"
            else -> "Android didn't install the update"
        }
    }

    /** Null if the APK is the release's and signed by Seam's key; otherwise why not. */
    private fun fetchAndCheck(release: Release, apk: File): String? {
        val sums = getText(release.sumsUrl) ?: return "Couldn't get the release's checksums."
        val expected = UpdateFeed.sumFor(sums, release.apkName) ?: return "The release has no checksum for the app."
        http.newCall(Request.Builder().url(release.apkUrl).build()).execute().use { r ->
            if (!r.isSuccessful) return "The download didn't finish. Try again."
            val body = r.body ?: return "The download didn't finish. Try again."
            if (body.contentLength() > UpdateFeed.MAX_APK) return "The download is too large. Nothing was installed."
            body.byteStream().use { input ->
                apk.outputStream().use { out ->
                    val buf = ByteArray(64 * 1024)
                    var total = 0L
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        total += n
                        if (total > UpdateFeed.MAX_APK) return "The download is too large. Nothing was installed."
                        out.write(buf, 0, n)
                    }
                }
            }
        }
        if (UpdateFeed.sha256(apk) != expected) return "The download doesn't match the release. Nothing was installed."
        val info = app.packageManager.getPackageArchiveInfo(apk.path, PackageManager.GET_SIGNING_CERTIFICATES)
            ?: return "The download isn't an app. Nothing was installed."
        if (info.packageName != app.packageName) return "The download is a different app. Nothing was installed."
        val signers = info.signingInfo?.apkContentsSigners.orEmpty().map {
            MessageDigest.getInstance("SHA-256").digest(it.toByteArray()).joinToString("") { b -> "%02x".format(b) }
        }
        if (signers != listOf(BuildConfig.RELEASE_CERT_SHA256)) return "The update isn't signed by Seam. Nothing was installed."
        if (info.longVersionCode <= BuildConfig.VERSION_CODE) return "That version isn't newer. Nothing was installed."
        return null
    }

    /** A small text file (release JSON, SHA256SUMS), refused if over [UpdateFeed.MAX_TEXT] bytes. */
    private fun getText(url: String, github: Boolean = false): String? {
        val request = Request.Builder().url(url).apply { if (github) header("Accept", "application/vnd.github+json") }.build()
        http.newCall(request).execute().use { r ->
            if (!r.isSuccessful) return null
            val source = r.body?.source() ?: return null
            // request(n) is true when at least n bytes arrive: more than the limit means refuse
            if (source.request(UpdateFeed.MAX_TEXT + 1)) return null
            return source.buffer.readUtf8()
        }
    }

    private fun commit(apk: File) {
        val installer = app.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(app.packageName)
            // The user tapped Update. Android still asks if Seam isn't the app's installer of record yet
            setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
        }
        val id = installer.createSession(params)
        try {
            installer.openSession(id).use { session ->
                session.openWrite("seam.apk", 0, apk.length()).use { out ->
                    apk.inputStream().use { it.copyTo(out) }
                    session.fsync(out)
                }
                prefs.edit().putInt("session", id).commit()
                // Mutable: the installer fills in the status extras. Explicit, to Seam's own receiver.
                val done = PendingIntent.getBroadcast(
                    app, id, Intent(app, InstallResult::class.java).setPackage(app.packageName),
                    PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                )
                session.commit(done.intentSender)
            }
        } catch (e: Exception) {
            runCatching { installer.abandonSession(id) } // never leave a half-written session (SR-17-07)
            throw e
        }
    }

    /** The session Seam last committed, so [InstallResult] answers only for that one. */
    internal val sessionId: Int get() = prefs.getInt("session", -1)

    private fun setOffer(release: Release?) {
        _offer.value = release
        val edit = prefs.edit()
        if (release == null) edit.remove("offer")
        else edit.putString("offer", listOf(release.version, release.apkUrl, release.apkName, release.sumsUrl).joinToString("\n"))
        edit.apply()
    }

    /** The stored offer, if it's still newer than this build (it isn't, once updated). */
    private fun storedOffer(): Release? = prefs.getString("offer", null)?.split("\n")?.takeIf { it.size == 4 }
        ?.let { (v, url, name, sums) -> Release(v, url, name, sums) }
        ?.takeIf { UpdateFeed.isNewer("v${it.version}", BuildConfig.VERSION_NAME) }

    private fun notifyOffer(release: Release) {
        val nm = app.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Updates", NotificationManager.IMPORTANCE_LOW))
        val open = PendingIntent.getActivity(
            app, 1, Intent(app, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        nm.notify(
            NOTIFICATION_ID,
            Notification.Builder(app, CHANNEL)
                .setSmallIcon(R.drawable.ic_seam_mark)
                .setContentTitle("Seam ${release.version} is available")
                .setContentText("Tap to open Seam and update")
                .setContentIntent(open)
                .setAutoCancel(true)
                .build(),
        )
    }

    companion object {
        private const val CHANNEL = "updates"
        private const val NOTIFICATION_ID = 3
        private const val HALF_HOUR_MS = 1_800_000L
        private const val DAY_MS = 24 * 3_600_000L
        /** The check intervals Settings offers, shortest first. */
        val INTERVALS = listOf(HALF_HOUR_MS, 2 * HALF_HOUR_MS, DAY_MS, 7 * DAY_MS)
    }
}

/**
 * The installer's answer to the session Seam committed: shows Android's confirm screen when asked,
 * and reports the outcome. Only the system's installer holds this receiver's PendingIntent.
 */
class InstallResult : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val updater = (context.applicationContext as SeamApplication).updater
        // Only the session Seam created (SR-17-08)
        if (intent.getIntExtra(PackageInstaller.EXTRA_SESSION_ID, -2) != updater.sessionId) return
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
        if (status != PackageInstaller.STATUS_PENDING_USER_ACTION) return updater.installFinished(status)
        @Suppress("DEPRECATION")
        val confirm = (if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java) else intent.getParcelableExtra(Intent.EXTRA_INTENT))
            ?: return
        // Only the installer's own confirm screen, never another intent
        if (confirm.action != "android.content.pm.action.CONFIRM_INSTALL") return
        context.startActivity(confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}
