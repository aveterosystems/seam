// SPDX-License-Identifier: AGPL-3.0-only
package com.avetero.seam

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.unit.dp
import com.avetero.seam.crypto.connectionsFootnote
import com.avetero.seam.crypto.nightlyWarning
import com.avetero.seam.crypto.nightlyWarningTitle
import com.avetero.seam.feature.notifications.NotificationsFeature
import com.avetero.seam.session.ConnectionSettings
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

/** The small print under the connection switches: what the current pair of them means. */
fun connectionFootnote(wifi: Boolean, tor: Boolean): String = connectionsFootnote(wifi, tor, PhoneNames)

/** "Paired 14 Sept 2026", in the phone's own date order: short enough to stay on one line beside Forget. */
fun pairedLabel(at: Long, zone: ZoneId = ZoneId.systemDefault(), locale: Locale = Locale.getDefault()): String =
    "Paired " + Instant.ofEpochMilli(at).atZone(zone).format(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(locale))

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
    /** Null: no Mac to reconnect to. */
    onReconnect: (() -> Unit)? = null,
    reconnecting: Boolean = false,
    /** While paused, Home's chip words ("Paused · until 05:22"); the rows say the same. */
    pause: String? = null,
) {
    var confirmForget by remember { mutableStateOf(false) }
    var keepPasswords by remember { mutableStateOf(!sendSensitive) }
    val uri = LocalUriHandler.current
    SubScreen("Settings", onBack) {
        Section("On your Mac") {
            if (notifications != null) NotificationsItem(notifications, paused = pause != null, onOpen = onOpenNotifications)
            AutoClipboardItem(clipboard, pause)
        }
        // The last one on can't be turned off: Seam would have no way to reach the Mac
        Section("Connection", footnote = connectionFootnote(connection.wifi, connection.tor)) {
            SwitchItem(
                "Same Wi‑Fi", "Direct, at home or work",
                checked = connection.wifi, onChange = { onSwitches(it, connection.tor) },
                lead = painterResource(R.drawable.ic_wifi), enabled = !connection.wifi || connection.tor,
            )
            SwitchItem(
                "Through Tor", "From anywhere, privately",
                checked = connection.tor, onChange = { onSwitches(connection.wifi, it) },
                lead = painterResource(R.drawable.ic_globe), enabled = !connection.tor || connection.wifi,
            )
            // For when you know it's stuck. Not while paused: nothing connects then
            if (onReconnect != null && connection.pausedUntil <= System.currentTimeMillis()) Item(
                "Reconnect", if (reconnecting) "Reconnecting" else "If Seam seems stuck, try again now",
                lead = painterResource(R.drawable.ic_refresh), onClick = onReconnect,
            )
        }
        Section("Privacy & security") {
            EncryptedItem(code)
            // On keeps them here: the same preference as before, worded as what it protects
            SwitchItem(
                // At 360 dp it wraps beside the switch; the row title's balanced breaks split it evenly
                "Keep passwords on this phone",
                if (keepPasswords) "Never sent to your Mac" else "Sent to your Mac too",
                checked = keepPasswords, onChange = { keepPasswords = it; onSendSensitive(!it) },
                lead = painterResource(R.drawable.ic_key),
            )
        }
        if (updater?.available == true) UpdatesSection(updater, version)
        if (macName != null) {
            Section("Your Mac") {
                Item(macName, pairedAt?.let { pairedLabel(it) }, lead = painterResource(R.drawable.ic_laptop)) {
                    Pill("Forget", { confirmForget = true }, PillKind.Error)
                }
            }
        }
        Section("About") {
            // Debug builds never update (Updater.available), whichever flavour
            if (updater?.available != true) Item("Seam $version", if (BuildConfig.DEBUG) "Debug build · updates off" else "Updates come from the Play Store", lead = painterResource(R.drawable.ic_seam_mark))
            Item("Open source", "AGPL-3.0 · aveterosystems/seam", lead = painterResource(R.drawable.ic_open_in_new), onClick = {
                // Leaves the app for the browser: the lead says so, so no chevron
                uri.openUri("https://github.com/aveterosystems/seam")
            })
        }
    }
    if (confirmForget && macName != null) {
        SeamDialog(
            "Forget $macName?",
            "This phone stops syncing and deletes its keys. $macName forgets it too when it's next online. You can pair again any time.",
            onDismiss = { confirmForget = false },
            icon = painterResource(R.drawable.ic_laptop_off),
        ) { close ->
            Pill("Cancel", close, PillKind.Text)
            Pill("Forget", { close(); onForgetMac() }, PillKind.Destructive)
        }
    }
}

/**
 * The safety code from pairing: tapping the row swaps it in for the words under the title, the eye at
 * the end saying which way the tap goes; the screen is also kept out of screenshots (SR-11-04).
 */
@Composable
private fun EncryptedItem(code: String?) {
    var shown by remember { mutableStateOf(false) }
    val subtitle = when {
        code == null -> "Keys made on this phone, sealed by its security chip"
        shown -> "Code ${code.chunked(3).joinToString(" ")}"
        else -> "Tap to see the pairing code"
    }
    val title = "End-to-end encrypted"
    if (code == null) return Item(title, subtitle, lead = painterResource(R.drawable.ic_shield))
    // The row's own semantics, so TalkBack reads the code digit by digit rather than as one number
    Box(Modifier.clearAndSetSemantics {
        contentDescription = "$title, ${if (shown) spokenCode(code) else subtitle}"
        role = Role.Button
        onClick(if (shown) "Hide code" else "Show code") { shown = !shown; true }
    }) {
        Item(title, subtitle, lead = painterResource(R.drawable.ic_shield), onClick = { shown = !shown }) {
            Icon(
                painterResource(if (shown) R.drawable.ic_visibility_off else R.drawable.ic_visibility), contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(20.dp),
            )
        }
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
    var nightly by remember { mutableStateOf(updater.nightly) }
    var confirmNightly by remember { mutableStateOf(false) }
    var chooseInterval by remember { mutableStateOf(false) }
    // SR-17-14: the grant outlives the update
    Section("Updates", footnote = "After updating, you can turn off Install unknown apps for Seam in Android's settings. Seam asks again next time.") {
        SwitchItem(
            checkTitle(interval),
            "GitHub sees this phone's IP when it checks",
            automatic, { automatic = it; updater.answer(it) }, lead = painterResource(R.drawable.ic_download),
        )
        if (automatic) Item("How often", intervalLabel(interval), lead = painterResource(R.drawable.ic_clock), onClick = { chooseInterval = true })
        SwitchItem(
            "Nightly builds", "The newest work each day, less tested. Off waits for the next release",
            // Turning on asks first: nightlies are less tested (the same words as on the Mac)
            nightly, { on -> if (on) confirmNightly = true else { nightly = false; updater.nightly = false } }, lead = painterResource(R.drawable.ic_refresh),
        )
        Item("Seam ${version.substringBefore(' ')}", checkLabel(status), lead = painterResource(R.drawable.ic_info), onClick = { updater.checkNow() })
    }
    if (confirmNightly) {
        SeamDialog(nightlyWarningTitle(), nightlyWarning(), onDismiss = { confirmNightly = false }, icon = painterResource(R.drawable.ic_refresh)) { close ->
            Pill("Cancel", close, PillKind.Text)
            Pill("Turn on", { close(); nightly = true; updater.nightly = true; updater.checkNow() })
        }
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

/** "482913" as TalkBack should say it: "Code 4 8 2, 9 1 3", digit by digit in its two groups. */
internal fun spokenCode(code: String): String = "Code " + code.chunked(3).joinToString(", ") { it.toList().joinToString(" ") }
