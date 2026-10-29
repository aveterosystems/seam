package com.avetero.seam.spike

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.PixelFormat
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import kotlin.concurrent.thread

/**
 * Watches the system log for the clipboard refusal that every copy triggers, then grabs focus
 * for a moment (1 px overlay, falling back to an invisible activity) so the read is allowed.
 */
class ClipService : Service() {
    private val main = Handler(Looper.getMainLooper())

    // Never fires in the background on Android 10+, but registering it is what makes
    // ClipboardService log a refusal for us on every copy.
    private val listener = ClipboardManager.OnPrimaryClipChangedListener {
        Sender.log("Clip listener fired (only expected while Seam is in front)")
    }

    override fun onCreate() {
        super.onCreate()
        startForeground(1, notification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        getSystemService(ClipboardManager::class.java).addPrimaryClipChangedListener(listener)
        thread(name = "sender", isDaemon = true) { Sender.loop() }
        thread(name = "logwatch", isDaemon = true) { watchLog() }
        Sender.log("Service started")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int) = START_STICKY
    override fun onBind(intent: Intent?) = null

    private fun watchLog() {
        while (true) {
            try {
                // -T 1: only new lines. Without READ_LOGS this silently shows just our own process.
                val p = ProcessBuilder("logcat", "-T", "1", "-v", "brief", "ClipboardService:E", "*:S")
                    .redirectErrorStream(true).start()
                Sender.log("Log watcher running")
                p.inputStream.bufferedReader().forEachLine { line ->
                    if (LogMatch.isOurDenial(line, packageName)) main.post { grab() }
                }
                Sender.log("Log watcher stopped (exit ${p.waitFor()}), restarting")
            } catch (e: Exception) {
                Sender.log("Log watcher failed: ${e.message}")
            }
            Thread.sleep(1000)
        }
    }

    private fun grab() {
        if (!Grab.start()) return
        val wm = getSystemService(WindowManager::class.java)
        val view = object : View(this) {
            override fun onWindowFocusChanged(hasFocus: Boolean) {
                if (hasFocus) Grab.deliver(context, "overlay")
            }
        }
        val lp = WindowManager.LayoutParams(
            1, 1,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT,
        ).apply { gravity = Gravity.TOP or Gravity.START }
        val added = runCatching { wm.addView(view, lp) }
            .onFailure { Sender.log("Overlay refused: ${it.message} (is 'draw over other apps' granted?)") }
            .isSuccess
        // Overlay didn't get focus in time: launch the invisible activity while the overlay is still up,
        // because Android 15 only lets overlay apps start activities from the background while one is visible.
        main.postDelayed({
            if (Grab.pendingSince != 0L) {
                runCatching {
                    startActivity(Intent(this, FocusActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION))
                }.onFailure { Sender.log("Activity launch refused: ${it.message}") }
            }
        }, 350)
        main.postDelayed({ if (added) runCatching { wm.removeView(view) } }, 700)
        main.postDelayed({ Grab.fail("no focus within 2 s") }, 2000)
    }

    private fun notification(): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel("sync", "Clipboard sync", NotificationManager.IMPORTANCE_MIN))
        return Notification.Builder(this, "sync")
            .setContentTitle("Seam clipboard test")
            .setContentText("Sending what you copy to your Mac")
            .setSmallIcon(android.R.drawable.ic_menu_share)
            .setOngoing(true)
            .build()
    }
}
