// SPDX-License-Identifier: AGPL-3.0-only
package com.avetero.seam.feature.clipboard

import android.app.Activity

/** Invisible: gains focus for a frame so [AutoClipboard] can read the clipboard, then closes. */
class FocusActivity : Activity() {
    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (!hasFocus) return
        AutoClipboard.deliver(this)
        finish()
        @Suppress("DEPRECATION") overridePendingTransition(0, 0)
    }
}
