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
    private val GITHUB_DOWNLOAD = Regex("""^https://github\.com/aveterosystems/seam/releases/download/v\d+\.\d+\.\d+/[A-Za-z0-9._-]+$""")

    /** GitHub's "latest release" JSON, if it's a newer, published version with both files. */
    fun newer(json: String, current: String): Release? = runCatching {
        val r = JSONObject(json)
        if (r.optBoolean("draft") || r.optBoolean("prerelease")) return null
        val tag = r.getString("tag_name")
        if (!isNewer(tag, current)) return null
        val version = tag.removePrefix("v")
        val assets = r.getJSONArray("assets")
        fun url(name: String) = (0 until assets.length()).map { assets.getJSONObject(it) }
            .firstOrNull { it.getString("name") == name }?.getString("browser_download_url")
            ?.takeIf { GITHUB_DOWNLOAD.matches(it) }
        val apk = "Seam-$version-android.apk"
        Release(version, url(apk) ?: return null, apk, url("SHA256SUMS") ?: return null)
    }.getOrNull()

    /** True if tag `vX.Y.Z` is above [current] `X.Y.Z`. */
    fun isNewer(tag: String, current: String): Boolean {
        val a = VERSION.matchEntire(tag)?.groupValues?.drop(1)?.map { it.toInt() } ?: return false
        val b = VERSION.matchEntire("v$current")?.groupValues?.drop(1)?.map { it.toInt() } ?: return false
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
 * Updates for the GitHub build (`full`): once a day Seam asks GitHub for the latest release, and if
 * it's newer, offers it. An update is installed only after its checksum matches the release's
 * SHA256SUMS and its signing certificate is Seam's pinned one; Android then checks the same key
 * signed the installed app. The Play build gets its updates from Play, and debug builds never update.
 */
class Updater(private val app: Context) {
    val available = BuildConfig.FLAVOR == "full" && !BuildConfig.DEBUG

    private val prefs = app.getSharedPreferences("updates", Context.MODE_PRIVATE)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val http = OkHttpClient.Builder().callTimeout(5, TimeUnit.MINUTES).build()

    private val _offer = MutableStateFlow<Release?>(null)
    /** A newer release, once found. */
    val offer: StateFlow<Release?> = _offer
    private val _status = MutableStateFlow<String?>(null)
    /** What the updater is doing, or why it stopped, for Settings and Home. */
    val status: StateFlow<String?> = _status

    var automatic: Boolean
        get() = prefs.getBoolean("automatic", true)
        set(value) { prefs.edit().putBoolean("automatic", value).apply() }

    /** Checks when a day has passed since the last check, for as long as the app runs. */
    fun start() {
        if (!available) return
        scope.launch {
            while (true) {
                if (automatic && System.currentTimeMillis() - prefs.getLong("lastCheck", 0) >= DAY_MS) check(notify = true)
                delay(HOUR_MS)
            }
        }
    }

    fun checkNow() {
        if (available) scope.launch { check(notify = false) }
    }

    private suspend fun check(notify: Boolean) {
        _status.value = "Checking…"
        val release = runCatching {
            withContext(Dispatchers.IO) {
                http.newCall(Request.Builder().url(UpdateFeed.LATEST).header("Accept", "application/vnd.github+json").build())
                    .execute().use { if (it.isSuccessful) it.body?.string() else null }
            }
        }.getOrNull()?.let { UpdateFeed.newer(it, BuildConfig.VERSION_NAME) }
        prefs.edit().putLong("lastCheck", System.currentTimeMillis()).apply()
        _offer.value = release
        _status.value = if (release == null) "Seam is up to date" else "Seam ${release.version} is available"
        if (release != null && notify) notifyOffer(release)
    }

    /** Downloads, checks and hands the update to Android's installer, which asks the user. */
    fun install(release: Release) {
        if (!available) return
        if (!app.packageManager.canRequestPackageInstalls()) {
            _status.value = "Allow Seam to install updates, then tap Update again"
            app.startActivity(
                Intent(android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, android.net.Uri.parse("package:${app.packageName}"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
            return
        }
        scope.launch {
            _status.value = "Downloading Seam ${release.version}…"
            val dir = File(app.cacheDir, "updates").apply { deleteRecursively(); mkdirs() }
            val apk = File(dir, release.apkName)
            val problem = runCatching { withContext(Dispatchers.IO) { fetchAndCheck(release, apk) } }
                .getOrElse { "The download didn't finish. Try again." }
            if (problem != null) {
                dir.deleteRecursively()
                _status.value = problem
                return@launch
            }
            _status.value = "Installing…"
            runCatching { withContext(Dispatchers.IO) { commit(apk) } }.onFailure { _status.value = "Android couldn't start the update." }
            dir.deleteRecursively()
        }
    }

    /** Null if the APK is the release's and signed by Seam's key; otherwise why not. */
    private fun fetchAndCheck(release: Release, apk: File): String? {
        val sums = http.newCall(Request.Builder().url(release.sumsUrl).build()).execute().use { if (it.isSuccessful) it.body?.string() else null }
            ?: return "Couldn't get the release's checksums."
        val expected = UpdateFeed.sumFor(sums, release.apkName) ?: return "The release has no checksum for the app."
        http.newCall(Request.Builder().url(release.apkUrl).build()).execute().use { r ->
            if (!r.isSuccessful) return "The download didn't finish. Try again."
            apk.outputStream().use { out -> r.body!!.byteStream().copyTo(out) }
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

    private fun commit(apk: File) {
        val installer = app.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(app.packageName)
            if (Build.VERSION.SDK_INT >= 31) setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_REQUIRED)
        }
        val id = installer.createSession(params)
        installer.openSession(id).use { session ->
            session.openWrite("seam.apk", 0, apk.length()).use { out -> apk.inputStream().use { it.copyTo(out) }; session.fsync(out) }
            // Mutable: the installer fills in the status extras. Explicit, to Seam's own receiver.
            val done = PendingIntent.getBroadcast(
                app, id, Intent(app, InstallResult::class.java).setPackage(app.packageName),
                PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            session.commit(done.intentSender)
        }
    }

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
        private const val DAY_MS = 24 * 3_600_000L
        private const val HOUR_MS = 3_600_000L
    }
}

/** The installer's answer: shows Android's confirm screen when it asks for one. */
class InstallResult : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.getIntExtra(PackageInstaller.EXTRA_STATUS, -1) != PackageInstaller.STATUS_PENDING_USER_ACTION) return
        @Suppress("DEPRECATION")
        val confirm = (if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java) else intent.getParcelableExtra(Intent.EXTRA_INTENT))
            ?: return
        context.startActivity(confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}
