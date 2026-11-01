// SPDX-License-Identifier: AGPL-3.0-only
package com.avetero.seam

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import com.avetero.seam.feature.clipboard.ClipboardFeature

/**
 * The notification buttons that need a screen. Not exported: only Seam's own notification starts it.
 * - Send clipboard: Android lets an app read the clipboard only while it's on screen, so this
 *   reads it the moment its (invisible) window has focus, sends it, and closes.
 * - Pause…: the ways to pause, in the same sheet as the home screen's, over whatever was open.
 */
class NotificationActionActivity : ComponentActivity() {
    private var sent = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val app = application as SeamApplication
        if (!app.session.paired) return done("Pair Seam with your Mac first.")
        if (intent.action != ACTION_PAUSE) return // Send clipboard: waits for focus, below
        setContent {
            SeamTheme {
                PauseSheet(onDismiss = { finish() }) { choice ->
                    val now = System.currentTimeMillis()
                    val until = choice.until(now)
                    app.session.pause(until)
                    done(pausedLabel(until, now))
                }
            }
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (!hasFocus || sent || intent.action != ACTION_SEND_CLIPBOARD || isFinishing) return
        sent = true // once, even if focus comes and goes
        val app = application as SeamApplication
        done(sentMessage(app, app.session.feature<ClipboardFeature>().sendCurrent()))
    }

    private fun done(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
        finish()
    }

    companion object {
        const val ACTION_SEND_CLIPBOARD = "com.avetero.seam.SEND_CLIPBOARD"
        const val ACTION_PAUSE = "com.avetero.seam.PAUSE_CHOICES"

        fun intent(context: Context, action: String): Intent =
            Intent(context, NotificationActionActivity::class.java).setAction(action)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION)
    }
}
