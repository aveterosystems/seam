// SPDX-License-Identifier: AGPL-3.0-only
package com.avetero.seam

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.background
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.avetero.seam.feature.clipboard.CopyWatcher
import com.avetero.seam.feature.notifications.NotificationsFeature
import com.avetero.seam.session.ConnectionSettings

/** Phone → Mac clipboard: automatic (full build, granted), possible after setup (full), or one tap (Play). */
enum class ClipboardStatus { Automatic, NeedsSetup, Manual }

/** Board A06, with what exists today: automatic clipboard, privacy, the Mac, about. */
@Composable
fun SettingsScreen(
    macName: String?,
    clipboard: ClipboardStatus,
    code: String?,
    version: String,
    onForgetMac: () -> Unit,
    onBack: () -> Unit,
    updater: Updater? = null,
    notifications: NotificationsFeature? = null,
    onOpenNotifications: () -> Unit = {},
    connection: ConnectionSettings = ConnectionSettings(),
    look: LinkLook = LinkLook.Reconnecting,
    onSwitches: (wifi: Boolean, relay: Boolean) -> Unit = { _, _ -> },
) {
    var explainClipboard by remember { mutableStateOf(false) }
    var confirmForget by remember { mutableStateOf(false) }
    val uri = LocalUriHandler.current
    val context = LocalContext.current
    val viaAccessibility = clipboard == ClipboardStatus.Automatic && CopyWatcher.enabled(context)
    SubScreen("Settings", onBack) {
        Section("On your Mac") {
            if (notifications != null) NotificationsItem(notifications, onOpenNotifications)
            val on = clipboard == ClipboardStatus.Automatic
            Item(
                title = "Automatic clipboard",
                subtitle = when (clipboard) {
                    ClipboardStatus.Automatic -> if (viaAccessibility) "On · through Accessibility" else "On · set up from your Mac"
                    ClipboardStatus.NeedsSetup -> "Off · Send clipboard sends copies yourself"
                    ClipboardStatus.Manual -> "One tap: Send clipboard, or share to Send to Mac"
                },
                highlighted = on,
                onClick = { explainClipboard = true },
            ) {
                if (clipboard != ClipboardStatus.Manual) Switch(checked = on, onCheckedChange = { explainClipboard = true })
            }
            Item("Mac → phone", "Always automatic. A Mac copy waits up to 10 minutes for this phone")
        }
        Section("Connection") {
            Item("Status", look.sentence)
            // The last one on can't be turned off: Seam would have no way to reach the Mac
            SwitchItem(
                "Same Wi‑Fi", "Straight to your Mac on this network. Nothing leaves it",
                checked = connection.wifi, onChange = { onSwitches(it, connection.relay) }, enabled = !connection.wifi || connection.relay,
            )
            SwitchItem(
                "Relay", "Through Seam's relay over the internet, so it works anywhere. Also needed to pair",
                checked = connection.relay, onChange = { onSwitches(connection.wifi, it) }, enabled = !connection.relay || connection.wifi,
            )
            Item("Encryption", "End-to-end on every path: neither the relay nor your network can read anything")
        }
        Section("Privacy & security") {
            Item(
                title = "End-to-end encrypted",
                subtitle = if (code != null) "Keys verified when you paired · " else "Keys made on this phone, sealed by its security chip",
                mono = code,
                lead = rememberVectorPainter(Icons.Filled.Lock),
            )
            SwitchItem("Never sync passwords", "Skips anything apps mark as sensitive", checked = true, onChange = {}, enabled = false)
        }
        if (macName != null) {
            Section("Mac") {
                Item(title = macName, subtitle = "Paired", lead = painterResource(R.drawable.ic_laptop)) {
                    TextButton(onClick = { confirmForget = true }) { Text("Forget", color = MaterialTheme.colorScheme.error) }
                }
            }
        }
        if (updater?.available == true) {
            val status by updater.status.collectAsState()
            var automatic by remember { mutableStateOf(updater.automatic) }
            var interval by remember { mutableStateOf(updater.interval) }
            var chooseInterval by remember { mutableStateOf(false) }
            Section("Updates") {
                SwitchItem("Check for updates automatically", "Seam asks GitHub for a newer release. GitHub sees this phone's IP address and Seam's version when it does.", automatic, {
                    automatic = it
                    updater.answer(it)
                })
                if (automatic) Item("How often", intervalLabel(interval), onClick = { chooseInterval = true })
                Item("Check now", status ?: "Installs only updates signed by Seam", onClick = { updater.checkNow() })
                // SR-17-14: the grant outlives the update
                Item("After updating", "You can turn off Install unknown apps for Seam in Android's settings; Seam asks again next time")
            }
            if (chooseInterval) IntervalSheet(interval, onDismiss = { chooseInterval = false }) {
                chooseInterval = false
                interval = it
                updater.interval = it
            }
        }
        Section("About") {
            Item("Seam", "Version $version", lead = painterResource(R.drawable.ic_seam_mark))
            Item("Open source", "AGPL-3.0 · github.com/aveterosystems/seam", lead = rememberVectorPainter(Icons.Filled.Info), onClick = {
                uri.openUri("https://github.com/aveterosystems/seam")
            })
        }
    }
    if (explainClipboard) {
        AlertDialog(
            onDismissRequest = { explainClipboard = false },
            title = { Text("Automatic clipboard") },
            text = {
                Text(
                    when (clipboard) {
                        ClipboardStatus.Automatic -> if (viaAccessibility) {
                            "What you copy here goes to your Mac by itself. To turn it off, turn off Seam in Android's Accessibility settings."
                        } else {
                            "What you copy here goes to your Mac by itself. To turn it off, choose Clipboard Sync off in Seam on your Mac."
                        }
                        ClipboardStatus.NeedsSetup -> "Optional. Turn on Seam in Android's Accessibility settings, and what you copy goes to your Mac by itself. " +
                            "Android warns that accessibility apps can see your screen: Seam only notices when you copy something, and skips anything apps mark as sensitive.\n\n" +
                            "If Android says Restricted setting: App info › ⋮ › Allow restricted settings, then try again.\n\n" +
                            "Without it, tap Send clipboard on the home screen, or share text to Send to Mac."
                        ClipboardStatus.Manual -> "The Play Store build can't send copies by itself. Tap Send clipboard on the home screen, or share text to Send to Mac."
                    },
                )
            },
            confirmButton = {
                if (clipboard == ClipboardStatus.Manual || (clipboard == ClipboardStatus.Automatic && !viaAccessibility)) {
                    TextButton(onClick = { explainClipboard = false }) { Text("OK") }
                } else {
                    TextButton(onClick = {
                        explainClipboard = false
                        openCopyWatcherSettings(context)
                    }) { Text("Open Accessibility") }
                }
            },
            dismissButton = if (clipboard == ClipboardStatus.NeedsSetup || viaAccessibility) {
                { TextButton(onClick = { explainClipboard = false }) { Text(if (viaAccessibility) "OK" else "Not now") } }
            } else null,
        )
    }
    if (confirmForget && macName != null) {
        AlertDialog(
            onDismissRequest = { confirmForget = false },
            title = { Text("Forget $macName?") },
            text = { Text("This phone stops syncing with $macName and forgets its keys. If this phone is online, $macName forgets it too; if not, forget this phone on $macName as well. You can pair again any time.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmForget = false
                    onForgetMac()
                }) { Text("Forget", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { confirmForget = false }) { Text("Cancel") } },
        )
    }
}

private fun intervalLabel(ms: Long) = when (ms) {
    Updater.INTERVALS[0] -> "Every 30 minutes"
    Updater.INTERVALS[1] -> "Every hour"
    Updater.INTERVALS[2] -> "Every day"
    else -> "Every week"
}

/** How often to check for updates: a sheet in the same grouped style as Settings. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun IntervalSheet(current: Long, onDismiss: () -> Unit, onPick: (Long) -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = MaterialTheme.colorScheme.surface) {
        Column(Modifier.padding(start = 16.dp, end = 16.dp, bottom = 32.dp)) {
            Section("Check for updates") {
                Updater.INTERVALS.forEach { ms ->
                    Item(intervalLabel(ms), highlighted = ms == current, onClick = { onPick(ms) }) {
                        if (ms == current) Icon(Icons.Filled.Check, contentDescription = "Selected", tint = MaterialTheme.colorScheme.onPrimaryContainer)
                    }
                }
            }
        }
    }
}
