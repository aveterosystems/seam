// SPDX-License-Identifier: AGPL-3.0-only
package com.avetero.seam.feature.clipboard

import android.accessibilityservice.AccessibilityService
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.res.Resources
import android.provider.Settings
import android.view.accessibility.AccessibilityEvent
import android.widget.Toast

/**
 * The no-adb way to notice copies (`full` builds; Play doesn't allow accessibility for this): the
 * user turns Seam on in Android's Accessibility settings, and Android's "Copied" confirmation, or a
 * tap on a Copy button, tells [AutoClipboard] to read the clipboard. Seam only checks whether an event
 * is one of those; it never reads the screen, and no event's text is kept or sent. A wrong guess costs
 * one focus grab: [AutoClipboard] sends only a clip copied in the last few seconds.
 */
class CopyWatcher : AccessibilityService() {
    override fun onServiceConnected() {
        instance = this
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        val copied = when (event.eventType) {
            AccessibilityEvent.TYPE_VIEW_CLICKED -> event.packageName != packageName &&
                CopyMatch.isCopy(event.text + event.contentDescription, Resources.getSystem().getString(android.R.string.copy))
            // Android's own confirmation after any copy, however it was made: Samsung's "Copied." toast
            AccessibilityEvent.TYPE_NOTIFICATION_STATE_CHANGED -> event.packageName == SYSTEM_UI && event.className == Toast::class.java.name
            // shortcut: the Android 13+ clipboard overlay (Pixel) is matched by its class name, unverified on a Pixel
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> event.packageName == SYSTEM_UI && event.className?.contains("clipboard", ignoreCase = true) == true
            else -> false
        }
        if (copied) AutoClipboard.copySeen(this)
    }

    override fun onInterrupt() {}

    override fun onDestroy() {
        if (instance === this) instance = null
        super.onDestroy()
    }

    companion object {
        private const val SYSTEM_UI = "com.android.systemui"

        /** The running service, whose window token lets [AutoClipboard] take focus without the overlay permission. */
        @Volatile var instance: CopyWatcher? = null
            private set

        fun enabled(context: Context): Boolean {
            val me = ComponentName(context, CopyWatcher::class.java)
            return Settings.Secure.getString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES).orEmpty()
                .split(':').any { ComponentName.unflattenFromString(it) == me }
        }

        /**
         * Android's Accessibility list (apps can't open one service's page). The extra asks Settings to
         * scroll to and highlight Seam; phones that don't know it just show the list.
         */
        fun settingsIntent(context: Context): Intent =
            Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                .putExtra(":settings:fragment_args_key", ComponentName(context, CopyWatcher::class.java).flattenToString())
    }
}

/** Whether a tapped control is a Copy button: "Copy", or "Copy link" and the like, in the phone's language. */
object CopyMatch {
    fun isCopy(labels: List<CharSequence?>, word: String): Boolean = labels.any { label ->
        val t = label?.toString()?.trim() ?: return@any false
        t.equals(word, ignoreCase = true) || t.startsWith("$word ", ignoreCase = true)
    }
}
