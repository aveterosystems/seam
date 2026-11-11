// SPDX-License-Identifier: AGPL-3.0-only
package com.avetero.seam

import androidx.compose.material3.Icon
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import com.avetero.seam.feature.clipboard.CopyWatcher

/** Phone → Mac clipboard: automatic (full build, granted), possible after setup (full), one tap (Play), or off on the Mac. */
enum class ClipboardStatus { Automatic, NeedsSetup, Manual, OffOnMac }

/**
 * The row's line, the same on Home and in Settings: on or off, and what that means for a copy.
 * [pause]: the pause's words while the phone is paused, as the status chip says them ("Paused · until 05:22").
 */
fun ClipboardStatus.subtitle(pause: String? = null): String = when (this) {
    ClipboardStatus.Automatic -> pause ?: "On · sends your copies"
    ClipboardStatus.NeedsSetup -> "Off · tap Send clipboard after copying"
    ClipboardStatus.Manual -> "Off · tap Send clipboard after copying, or share to Send to Mac"
    ClipboardStatus.OffOnMac -> SendWords.SYNC_OFF_SHORT
}

/**
 * "Automatic clipboard", on Home and in Settings. The phone can't switch it by itself (it's
 * granted from the Mac, or through Accessibility), so it's a plain row whose line says on or off,
 * and a tap opens the explanation of how. Its clipboard icon is struck through while it's off.
 */
@Composable
internal fun AutoClipboardItem(clipboard: ClipboardStatus, pause: String? = null) {
    val context = LocalContext.current
    val viaAccessibility = clipboard == ClipboardStatus.Automatic && CopyWatcher.enabled(context)
    var explain by remember { mutableStateOf(false) }
    val on = clipboard == ClipboardStatus.Automatic
    Item(
        "Automatic clipboard", clipboard.subtitle(pause),
        lead = painterResource(if (on) R.drawable.ic_copy else R.drawable.ic_copy_off), bounce = on, onClick = { explain = true },
    ) {
        // It only explains (a dialog), so an info glyph, not a chevron that promises a page
        Icon(painterResource(R.drawable.ic_info), contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(20.dp))
    }
    if (explain) ClipboardDialog(clipboard, viaAccessibility) { explain = false }
}

@Composable
internal fun ClipboardDialog(clipboard: ClipboardStatus, viaAccessibility: Boolean, onDismiss: () -> Unit) {
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
            ClipboardStatus.OffOnMac -> "Clipboard sync is off on your Mac, so nothing you copy goes to it and nothing comes back. Turn it on in Seam on your Mac."
        },
        onDismiss,
    ) { close ->
        val canOpen = clipboard == ClipboardStatus.NeedsSetup || viaAccessibility
        if (canOpen) Pill(if (viaAccessibility) "OK" else "Not now", close, PillKind.Text)
        if (canOpen) Pill("Open Accessibility", { close(); openCopyWatcherSettings(context) }, PillKind.Text)
        else Pill("OK", close, PillKind.Text)
    }
}
