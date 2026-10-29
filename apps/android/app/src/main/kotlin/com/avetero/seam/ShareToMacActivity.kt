// SPDX-License-Identifier: AGPL-3.0-only
package com.avetero.seam

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import com.avetero.seam.session.SessionState

/** "Send to Mac" in the share sheet: puts the shared text on the Mac's clipboard. */
class ShareToMacActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val app = application as SeamApplication
        val text = intent.takeIf { it.action == Intent.ACTION_SEND }?.getStringExtra(Intent.EXTRA_TEXT)
        val message = when {
            app.session.state.value == SessionState.Unpaired -> "Pair Seam with your Mac first."
            app.clipboard.sendNow(text) -> "Sent to your Mac's clipboard"
            else -> "Nothing to send"
        }
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
        finish()
    }
}
