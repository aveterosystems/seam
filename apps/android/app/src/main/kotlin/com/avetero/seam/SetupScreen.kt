// SPDX-License-Identifier: AGPL-3.0-only
package com.avetero.seam

import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.content.pm.PackageManager
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import com.avetero.seam.feature.clipboard.CopyWatcher
import com.avetero.seam.feature.notifications.NotificationsFeature

/** Board A03: right after pairing, the permissions that make Seam work, each skippable. */
@Composable
fun SetupScreen(macName: String, clipboard: ClipboardStatus, onDone: () -> Unit) {
    BackHandler(onBack = onDone)
    val context = LocalContext.current
    val haptics = LocalHapticFeedback.current
    // This screen shows only when a pairing has just finished with a new Mac: that's the success to feel
    LaunchedEffect(Unit) { haptics.performHapticFeedback(HapticFeedbackType.Confirm) }
    fun granted() = context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
    var notifications by remember { mutableStateOf(granted()) }
    var denied by rememberSaveable { mutableStateOf(false) }
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { notifications = it; denied = !it }
    // Back from Android's settings, the row says what's true now
    LifecycleResumeEffect(Unit) {
        notifications = granted()
        onPauseOrDispose {}
    }
    val allowNotifications: () -> Unit = {
        val activity = context as? Activity
        // Denied twice, Android stops asking and the request returns false at once: only Settings can grant it
        if (denied && activity != null && !activity.shouldShowRequestPermissionRationale(Manifest.permission.POST_NOTIFICATIONS)) {
            runCatching { context.startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)) }
        } else {
            ask.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }
    val access = rememberNotificationAccess()
    var explainClipboard by remember { mutableStateOf(false) }
    val all = notifications && access && clipboard != ClipboardStatus.NeedsSetup
    FirstRunPage(
        // The pairing screen's welcome cookie, its stitches now drawing in: the two are joined
        hero = Looks.sameWifi,
        title = "You're paired",
        // No-break spaces keep the Mac's name on one line
        body = "Seam only asks for what it uses with ${macName.replace(' ', '\u00A0')}. You can change these later in Android's settings.",
        // Skipping is a text button, so it never competes with an Allow; once everything is allowed, Done is the one action
        // The footnote sits with the action, centred as the page: under the list it floated 40 dp off Skip, at the start of a centred page
        action = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Footnote("Mac copies reach this phone without any of these.", TextAlign.Center)
                if (all) BigButton("Done", onDone) else Pill("Skip for now", onDone, PillKind.Text, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp))
            }
        },
    ) {
        // The free height goes above the list, so the choices sit with the action that ends them; the cookie stays put
        Spacer(Modifier.weight(1f))
        Section(null) {
            Item(
                title = "Seam's alerts",
                subtitle = if (notifications) "Allowed" else "Sync status and nudges",
                lead = painterResource(R.drawable.ic_bell),
                onClick = if (notifications) null else allowNotifications,
            ) {
                if (notifications) Done() else AllowButton(allowNotifications)
            }
            NotificationAccessItem(access)
            Item(
                title = "Automatic clipboard",
                subtitle = when (clipboard) {
                    ClipboardStatus.Automatic -> "On"
                    ClipboardStatus.NeedsSetup -> "Optional · send what you copy without tapping"
                    ClipboardStatus.Manual -> "Not in this build. Tap Send clipboard, or share to Send to Mac"
                    ClipboardStatus.OffOnMac -> SendWords.SYNC_OFF_SHORT
                },
                lead = painterResource(R.drawable.ic_copy),
                highlighted = clipboard == ClipboardStatus.Automatic,
                onClick = if (clipboard == ClipboardStatus.NeedsSetup) ({ explainClipboard = true }) else null,
            ) {
                when (clipboard) {
                    ClipboardStatus.Automatic -> Done()
                    // Optional, so quieter than the Allow buttons. The dialog says how, and the Restricted setting way past Android, then opens Accessibility
                    ClipboardStatus.NeedsSetup -> Pill("Allow", { explainClipboard = true })
                    ClipboardStatus.Manual, ClipboardStatus.OffOnMac -> {}
                }
            }
        }
    }
    if (explainClipboard) ClipboardDialog(clipboard, viaAccessibility = false) { explainClipboard = false }
}

@Composable
private fun AllowButton(onClick: () -> Unit) = Pill("Allow", onClick, PillKind.Filled)

/** Android's Accessibility settings, where the user turns on Seam ([CopyWatcher]). */
internal fun openCopyWatcherSettings(context: Context) {
    runCatching { context.startActivity(CopyWatcher.settingsIntent(context)) }
}

/** Whether Seam may read notifications, re-checked each time the user comes back from Android's settings. */
@Composable
internal fun rememberNotificationAccess(): Boolean {
    val context = LocalContext.current
    var granted by remember { mutableStateOf(NotificationsFeature.accessGranted(context)) }
    LifecycleResumeEffect(Unit) {
        granted = NotificationsFeature.accessGranted(context)
        onPauseOrDispose {}
    }
    return granted
}

/**
 * Board A03 / A06 notification access ("Phone notifications"): opens Android's access screen. Back from there still without it,
 * a dialog gives the way past Android's "Restricted setting" block, which sideloaded installs hit first.
 */
@Composable
internal fun NotificationAccessItem(granted: Boolean) {
    val context = LocalContext.current
    var asked by remember { mutableStateOf(false) }
    var trouble by remember { mutableStateOf(false) }
    LifecycleResumeEffect(Unit) {
        if (asked && !NotificationsFeature.accessGranted(context)) trouble = true
        asked = false
        onPauseOrDispose {}
    }
    val open = {
        asked = true
        context.startActivity(NotificationsFeature.accessIntent(context))
    }
    Item(
        title = "Phone notifications",
        subtitle = if (granted) "Allowed" else "Sends them to your Mac",
        lead = painterResource(R.drawable.ic_phone_notification),
        onClick = if (granted) null else open,
    ) {
        if (granted) Done() else AllowButton(open)
    }
    if (trouble) {
        SeamDialog(
            "Phone notifications",
            "Still off. If Android said Restricted setting: App info › ⋮ › Allow restricted settings, then tap Allow again.",
            onDismiss = { trouble = false },
        ) { close ->
            Pill("Not now", close, PillKind.Text)
            Pill("App info", {
                close()
                runCatching { context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null))) }
            }, PillKind.Text)
        }
    }
}
