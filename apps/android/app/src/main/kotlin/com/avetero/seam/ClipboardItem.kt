// SPDX-License-Identifier: AGPL-3.0-only
package com.avetero.seam

import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import com.avetero.seam.feature.clipboard.CopyWatcher

/** Phone → Mac clipboard: automatic (full build, granted), possible after setup (full), one tap (Play), or off on the Mac. */
enum class ClipboardStatus { Automatic, NeedsSetup, Manual, OffOnMac }

/** The row's line: on Home, what happens to a copy; in Settings, also how it was set up. */
fun ClipboardStatus.subtitle(viaAccessibility: Boolean, home: Boolean): String = when (this) {
    ClipboardStatus.Automatic -> when {
        home -> "Copies reach your Mac by themselves"
        viaAccessibility -> "On · through Accessibility"
        else -> "On · set up from your Mac"
    }
    ClipboardStatus.NeedsSetup -> "Off · tap Send clipboard after copying"
    ClipboardStatus.Manual -> "Tap Send clipboard after copying, or share to Send to Mac"
    ClipboardStatus.OffOnMac -> "Off on your Mac · turn on Sync the clipboard there"
}

/**
 * "Automatic clipboard", on Home and in Settings: primaryContainer while on. The phone can't
 * switch it by itself (it's granted from the Mac, or through Accessibility), so the switch and
 * the row both open the explanation of how.
 */
@Composable
internal fun AutoClipboardItem(clipboard: ClipboardStatus, home: Boolean = false) {
    val context = LocalContext.current
    val viaAccessibility = clipboard == ClipboardStatus.Automatic && CopyWatcher.enabled(context)
    var explain by remember { mutableStateOf(false) }
    val on = clipboard == ClipboardStatus.Automatic
    Item(
        title = "Automatic clipboard",
        subtitle = clipboard.subtitle(viaAccessibility, home),
        lead = painterResource(R.drawable.ic_send),
        highlighted = on,
        role = if (clipboard == ClipboardStatus.Manual) Role.Button else Role.Switch,
        onClick = { explain = true },
    ) {
        if (clipboard != ClipboardStatus.Manual) SeamSwitch(on, { explain = true })
    }
    if (explain) ClipboardDialog(clipboard, viaAccessibility) { explain = false }
}

@Composable
private fun ClipboardDialog(clipboard: ClipboardStatus, viaAccessibility: Boolean, onDismiss: () -> Unit) {
    val context = LocalContext.current
    SeamDialog(
        "Automatic clipboard",
        when (clipboard) {
            ClipboardStatus.Automatic -> if (viaAccessibility) {
                "What you copy here goes to your Mac by itself. To turn it off, turn off Seam in Android's Accessibility settings."
            } else {
                "What you copy here goes to your Mac by itself. To turn it off, turn off Sync the clipboard in Seam on your Mac."
            }
            ClipboardStatus.NeedsSetup -> "Optional. Turn on Seam in Android's Accessibility settings, and what you copy goes to your Mac by itself. " +
                "Android warns that accessibility apps can see your screen: Seam only notices when you copy something, and skips anything apps mark as sensitive unless you allow it in Settings.\n\n" +
                "If Android says Restricted setting: App info › ⋮ › Allow restricted settings, then try again.\n\n" +
                "Without it, tap Send clipboard on the home screen, or share text to Send to Mac."
            ClipboardStatus.Manual -> "The Play Store build can't send copies by itself. Tap Send clipboard on the home screen, or share text to Send to Mac."
            ClipboardStatus.OffOnMac -> "Your Mac has Sync the clipboard turned off, so nothing you copy goes to it and nothing comes back. Turn it on in Seam on your Mac."
        },
        onDismiss,
    ) { close ->
        val canOpen = clipboard == ClipboardStatus.NeedsSetup || viaAccessibility
        if (canOpen) Pill(if (viaAccessibility) "OK" else "Not now", close, PillKind.Text)
        if (canOpen) Pill("Open Accessibility", { close(); openCopyWatcherSettings(context) }, PillKind.Text)
        else Pill("OK", close, PillKind.Text)
    }
}
