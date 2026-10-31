// SPDX-License-Identifier: AGPL-3.0-only
package com.avetero.seam

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * The notification buttons that need a screen. Not exported: only Seam's own notification starts it.
 * - Send clipboard: Android lets an app read the clipboard only while it's on screen, so this
 *   reads it the moment its (invisible) window has focus, sends it, and closes.
 * - Pause…: the three ways to pause, in a sheet over whatever was open.
 */
class NotificationActionActivity : ComponentActivity() {
    private var sent = false

    @OptIn(ExperimentalMaterial3Api::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val app = application as SeamApplication
        if (!app.session.paired) return done("Pair Seam with your Mac first.")
        if (intent.action != ACTION_PAUSE) return // Send clipboard: waits for focus, below
        setContent {
            SeamTheme {
                ModalBottomSheet(onDismissRequest = { finish() }) {
                    // The sheet has its own window: refuse taps that pass through another app's overlay (SR-11-02)
                    val view = LocalView.current
                    SideEffect { view.filterTouchesWhenObscured = true; view.rootView.filterTouchesWhenObscured = true }
                    Column(
                        Modifier.fillMaxWidth().padding(horizontal = 24.dp).padding(bottom = 16.dp).navigationBarsPadding(),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Text("Pause Seam", fontSize = 22.sp)
                        Text(
                            "Nothing syncs, and this phone makes no connections, until it ends.",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(bottom = 8.dp),
                        )
                        PauseFor.entries.forEach { choice ->
                            TextButton(onClick = {
                                val now = System.currentTimeMillis()
                                val until = choice.until(now)
                                app.session.pause(until)
                                done(pausedLabel(until, now))
                            }) { Text(choice.label) }
                        }
                    }
                }
            }
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (!hasFocus || sent || intent.action != ACTION_SEND_CLIPBOARD || isFinishing) return
        sent = true // once, even if focus comes and goes
        val app = application as SeamApplication
        done(sentMessage(app, app.clipboard.sendCurrent()))
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
