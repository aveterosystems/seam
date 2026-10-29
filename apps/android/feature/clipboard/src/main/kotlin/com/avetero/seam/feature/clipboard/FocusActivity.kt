// SPDX-License-Identifier: AGPL-3.0-only
package com.avetero.seam.feature.clipboard

import android.app.Activity
import android.os.Bundle
import android.os.Handler
import android.os.Looper

/** Invisible: gains focus for a frame so [AutoClipboard] can read the clipboard, then closes. */
class FocusActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // If focus never comes (lock screen, another window holds it), don't sit over the screen eating taps (SR-4-09)
        Handler(Looper.getMainLooper()).postDelayed({ if (!isFinishing) close() }, 1000)
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (!hasFocus) return
        AutoClipboard.deliver(this)
        close()
    }

    private fun close() {
        finish()
        @Suppress("DEPRECATION") overridePendingTransition(0, 0)
    }
}
