// SPDX-License-Identifier: AGPL-3.0-only
package com.avetero.seam

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import com.avetero.seam.feature.clipboard.ClipRules
import com.avetero.seam.session.SessionState

/**
 * "Send to Mac" in the share sheet. Any app can start this, so nothing goes until the user has
 * seen the text and tapped Send (SR-4-01).
 */
class ShareToMacActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val app = application as SeamApplication
        val text = intent.takeIf { it.action == Intent.ACTION_SEND }?.getStringExtra(Intent.EXTRA_TEXT)
        when {
            app.session.state.value == SessionState.Unpaired -> return done("Pair Seam with your Mac first.")
            text.isNullOrEmpty() -> return done("Nothing to send")
        }
        AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle("Send to your Mac's clipboard?")
            .setMessage(ClipRules.preview(text!!))
            .setPositiveButton("Send") { _, _ -> done(sentMessage(app, app.clipboard.sendNow(text))) }
            .setNegativeButton("Cancel") { _, _ -> finish() }
            .setOnCancelListener { finish() }
            .show()
    }

    private fun done(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
        finish()
    }
}

/** "Sent" only when it can go now; otherwise say it's waiting (SR-4-04). */
fun sentMessage(app: SeamApplication, sent: Boolean): String = when {
    !sent -> "Nothing to send: the clipboard is empty, too big, or holds something private"
    app.session.state.value is SessionState.Connected || app.session.state.value is SessionState.Idle -> "Sent to your Mac's clipboard"
    else -> "It'll go to your Mac when your phone reconnects (within 10 minutes)"
}
