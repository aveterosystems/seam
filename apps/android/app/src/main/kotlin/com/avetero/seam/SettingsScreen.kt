// SPDX-License-Identifier: AGPL-3.0-only
package com.avetero.seam

import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.avetero.seam.feature.notifications.NotificationsFeature
import com.avetero.seam.session.ConnectionSettings
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** The small print under the connection switches: what the current pair of them means. */
fun connectionFootnote(wifi: Boolean, tor: Boolean): String = when {
    wifi && tor -> "One always stays on. Encryption is the same on both."
    wifi -> "Only on your Mac's network. Turn on Tor to reach it anywhere."
    else -> "Through Tor even at home. Same Wi‑Fi is faster for big files."
}

/** "Paired 14 September 2026". */
fun pairedLabel(at: Long, zone: ZoneId = ZoneId.systemDefault()): String =
    "Paired " + Instant.ofEpochMilli(at).atZone(zone).format(DateTimeFormatter.ofPattern("d MMMM yyyy"))

/** Board A06 as the approved prototype lays it out, with every setting the app has. */
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
    onSwitches: (wifi: Boolean, tor: Boolean) -> Unit = { _, _ -> },
    pairedAt: Long? = null,
    sendSensitive: Boolean = false,
    onSendSensitive: (Boolean) -> Unit = {},
) {
    var confirmForget by remember { mutableStateOf(false) }
    var neverSendSensitive by remember { mutableStateOf(!sendSensitive) }
    val uri = LocalUriHandler.current
    SubScreen("Settings", onBack) {
        Section("On your Mac") {
            if (notifications != null) NotificationsItem(notifications, "Notifications", home = false, onOpen = onOpenNotifications)
            AutoClipboardItem(clipboard)
            SwitchItem(
                "Never send passwords",
                if (neverSendSensitive) "Passwords and card numbers stay on this phone" else "Passwords and card numbers go to your Mac too",
                checked = neverSendSensitive, onChange = { neverSendSensitive = it; onSendSensitive(!it) },
                lead = painterResource(R.drawable.ic_key),
            )
        }
        // The last one on can't be turned off: Seam would have no way to reach the Mac
        Section("Connection", footnote = connectionFootnote(connection.wifi, connection.tor)) {
            SwitchItem(
                "Same Wi‑Fi", "Straight to your Mac at home or work",
                checked = connection.wifi, onChange = { onSwitches(it, connection.tor) },
                lead = painterResource(R.drawable.ic_wifi), enabled = !connection.wifi || connection.tor,
            )
            SwitchItem(
                "Internet, through Tor", "Reaches your Mac anywhere, hides where you are",
                checked = connection.tor, onChange = { onSwitches(connection.wifi, it) },
                lead = painterResource(R.drawable.ic_globe), enabled = !connection.tor || connection.wifi,
            )
        }
        Section("Privacy & security") { EncryptedItem(code) }
        if (updater?.available == true) UpdatesSection(updater, version)
        if (macName != null) {
            Section("Your Mac") {
                Item(macName, pairedAt?.let { pairedLabel(it) }, lead = painterResource(R.drawable.ic_laptop)) {
                    Pill("Forget", { confirmForget = true }, PillKind.Error)
                }
            }
        }
        Section("About") {
            if (updater?.available != true) Item("Seam $version", "Updates come from the Play Store", lead = painterResource(R.drawable.ic_seam_mark))
            Item("Open source", "AGPL-3.0 · github.com/aveterosystems/seam", lead = painterResource(R.drawable.ic_info), onClick = {
                uri.openUri("https://github.com/aveterosystems/seam")
            })
        }
    }
    if (confirmForget && macName != null) {
        SeamDialog(
            "Forget $macName?",
            "This phone stops syncing and forgets its keys. If this phone is online, $macName forgets it too; if not, forget this phone on $macName as well. You can pair again any time.",
            onDismiss = { confirmForget = false },
            icon = painterResource(R.drawable.ic_laptop_off),
        ) { close ->
            Pill("Cancel", close, PillKind.Text)
            Pill("Forget", { close(); onForgetMac() }, PillKind.Error)
        }
    }
}

/** The safety code from pairing, blurred until tapped (0.4 s); the screen is also kept out of screenshots (SR-11-04). */
@Composable
private fun EncryptedItem(code: String?) {
    var shown by remember { mutableStateOf(false) }
    val blur by animateDpAsState(if (shown) 0.dp else 6.dp, Motion.ease(400), label = "code")
    if (code == null) {
        Item("End-to-end encrypted", "Keys made on this phone, sealed by its security chip", lead = painterResource(R.drawable.ic_shield))
        return
    }
    Item("End-to-end encrypted", "Keys checked when you paired · tap to show the code", lead = painterResource(R.drawable.ic_shield), onClick = { shown = !shown }) {
        Text(
            code.chunked(3).joinToString(" "), fontFamily = FontFamily.Monospace, fontSize = 15.sp, letterSpacing = 2.sp,
            modifier = Modifier.blur(blur).semantics { contentDescription = if (shown) "Code $code" else "Code hidden" },
        )
    }
}

/** "✓ Up to date" for the updater's "Seam is up to date"; its other words as they are. */
fun checkLabel(status: String?): String = when (status) {
    null -> "Tap to check now"
    "Seam is up to date" -> "✓ Up to date"
    else -> status
}

@Composable
private fun UpdatesSection(updater: Updater, version: String) {
    val status by updater.status.collectAsState()
    var automatic by remember { mutableStateOf(updater.automatic) }
    var interval by remember { mutableStateOf(updater.interval) }
    var chooseInterval by remember { mutableStateOf(false) }
    // SR-17-14: the grant outlives the update
    Section("Updates", footnote = "After updating, you can turn off Install unknown apps for Seam in Android's settings. Seam asks again next time.") {
        SwitchItem(
            checkTitle(interval),
            "GitHub sees this phone's IP when it checks",
            automatic, { automatic = it; updater.answer(it) }, lead = painterResource(R.drawable.ic_download),
        )
        if (automatic) Item("How often", intervalLabel(interval), lead = painterResource(R.drawable.ic_clock), onClick = { chooseInterval = true })
        Item("Seam ${version.substringBefore(' ')}", checkLabel(status), lead = painterResource(R.drawable.ic_info), onClick = { updater.checkNow() })
    }
    if (chooseInterval) {
        SeamSheet("Check for updates", null, onDismiss = { chooseInterval = false }) { close ->
            Choices(Updater.INTERVALS, interval, ::intervalLabel) {
                interval = it
                updater.interval = it
                close()
            }
        }
    }
}

/** The switch says how often, as the prototype's "Check daily" does. */
private fun checkTitle(ms: Long) = when (ms) {
    Updater.INTERVALS[0] -> "Check every 30 minutes"
    Updater.INTERVALS[1] -> "Check hourly"
    Updater.INTERVALS[2] -> "Check daily"
    else -> "Check weekly"
}

private fun intervalLabel(ms: Long) = when (ms) {
    Updater.INTERVALS[0] -> "Every 30 minutes"
    Updater.INTERVALS[1] -> "Every hour"
    Updater.INTERVALS[2] -> "Every day"
    else -> "Every week"
}
