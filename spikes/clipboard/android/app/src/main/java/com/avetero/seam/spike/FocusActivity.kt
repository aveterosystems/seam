package com.avetero.seam.spike

import android.app.Activity

/** Invisible activity: gains focus for a frame, reads the clipboard and closes. */
class FocusActivity : Activity() {
    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (!hasFocus) return
        Grab.deliver(this, "activity")
        finish()
        @Suppress("DEPRECATION") overridePendingTransition(0, 0)
    }
}
