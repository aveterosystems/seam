// SPDX-License-Identifier: AGPL-3.0-only
package com.avetero.seam.feature.clipboard

import android.Manifest
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.PixelFormat
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import kotlin.concurrent.thread

/**
 * Phone → Mac with no tap, the method proven by the clipboard spike (docs/platforms/android-limits.md):
 * every copy makes Android log that it refused our clipboard listener; on that line Seam takes focus
 * for one frame (a 1 px overlay, or an invisible activity) so the read is allowed.
 *
 * Needs READ_LOGS and "draw over other apps", which only `full` builds declare and which the Mac
 * grants once over adb. Without both, [start] does nothing.
 */
object AutoClipboard {
    private val main = Handler(Looper.getMainLooper())
    private var feature: ClipboardFeature? = null
    private var watcher: Thread? = null
    @Volatile private var logcat: Process? = null
    @Volatile private var running = false
    /** Bumped by every start and stop, so an old watcher thread can tell it's been replaced (SR-4-08). */
    @Volatile private var generation = 0
    /** elapsedRealtime when a copy was seen; 0 when nothing is pending. */
    private var pendingSince = 0L

    // Never fires in the background, but registering it is what makes ClipboardService log a refusal on every copy
    private val listener = ClipboardManager.OnPrimaryClipChangedListener {}

    fun available(context: Context): Boolean =
        context.checkSelfPermission(Manifest.permission.READ_LOGS) == PackageManager.PERMISSION_GRANTED &&
            Settings.canDrawOverlays(context)

    fun start(context: Context, feature: ClipboardFeature) {
        val app = context.applicationContext
        if (running || !available(app)) return
        running = true
        this.feature = feature
        val mine = ++generation
        app.getSystemService(ClipboardManager::class.java).addPrimaryClipChangedListener(listener)
        watcher = thread(name = "seam-clipwatch", isDaemon = true) { watch(app, mine) }
    }

    fun stop(context: Context) {
        if (!running) return
        running = false
        generation++
        context.applicationContext.getSystemService(ClipboardManager::class.java).removePrimaryClipChangedListener(listener)
        logcat?.destroy()
        main.removeCallbacksAndMessages(null) // no grab still in flight can send after stop
        pendingSince = 0L
        watcher?.join(500)
        watcher = null
    }

    private fun watch(app: Context, mine: Int) {
        val current = { running && mine == generation }
        while (current()) {
            runCatching {
                // -T 1: only new lines. Only ClipboardService errors, nothing else from the log is read.
                // -v uid: who wrote each line, so only the system's own refusal counts (SR-4-02).
                val p = ProcessBuilder("logcat", "-T", "1", "-v", "threadtime", "-v", "uid", "ClipboardService:E", "*:S")
                    .redirectErrorStream(true).start()
                logcat = p
                if (!current()) return p.destroy() // stopped while starting
                p.inputStream.bufferedReader().forEachLine { line ->
                    if (current() && LogMatch.isOurDenial(line, app.packageName)) main.post { grab(app) }
                }
                p.waitFor()
            }
            if (current()) Thread.sleep(1000)
        }
    }

    private fun grab(app: Context) {
        if (!running) return
        val now = SystemClock.elapsedRealtime()
        if (pendingSince != 0L && now - pendingSince < 1500) return // one grab at a time
        pendingSince = now
        val wm = app.getSystemService(WindowManager::class.java)
        val view = object : View(app) {
            override fun onWindowFocusChanged(hasFocus: Boolean) {
                if (hasFocus) deliver(app)
            }
        }
        val lp = WindowManager.LayoutParams(
            1, 1, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL, PixelFormat.TRANSLUCENT,
        ).apply { gravity = Gravity.TOP or Gravity.START }
        val added = runCatching { wm.addView(view, lp) }.isSuccess
        // No focus yet: the invisible activity, while the overlay is still up (Android 15 only lets
        // overlay apps start activities from the background while one is visible)
        main.postDelayed({
            if (pendingSince != 0L) {
                runCatching {
                    app.startActivity(Intent(app, FocusActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION))
                }
            }
        }, 350)
        main.postDelayed({ if (added) runCatching { wm.removeView(view) } }, 700)
        main.postDelayed({ pendingSince = 0L }, 2000)
    }

    internal fun deliver(context: Context) {
        if (!running || pendingSince == 0L) return
        pendingSince = 0L
        val f = feature ?: return
        f.copied(f.readClip())
    }
}
