// SPDX-License-Identifier: AGPL-3.0-only
package com.avetero.seam

import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.avetero.seam.feature.MacStatus
import com.avetero.seam.feature.clipboard.FromMac
import com.avetero.seam.feature.notifications.NotificationsFeature
import com.avetero.seam.session.PairingQr
import com.avetero.seam.session.SessionState

/** One screen per session state: board A01 Home when paired, A02 Pairing otherwise. */
@Composable
fun SeamScreen(
    state: SessionState,
    scanError: String?,
    toConfirm: PairingQr?,
    onConfirm: (PairingQr) -> Unit,
    onCancelConfirm: () -> Unit,
    onScan: () -> Unit,
    onSendClipboard: () -> SendFeedback = { SendFeedback() },
    fromMac: FromMac? = null,
    now: Long = System.currentTimeMillis(),
    onPasteFromMac: (String) -> Boolean = { true },
    onOpenSettings: () -> Unit = {},
    onOpenNotifications: () -> Unit = {},
    mac: MacStatus? = null,
    notifications: NotificationsFeature? = null,
    clipboard: ClipboardStatus = ClipboardStatus.Manual,
    onCodesDontMatch: () -> Unit = {},
    scanning: Boolean = false,
    onCloseScan: () -> Unit = {},
    camera: @Composable (reading: Boolean) -> Unit = {},
    update: Release? = null,
    updateStatus: String? = null,
    onUpdate: (Release) -> Unit = {},
    askUpdates: Boolean = false,
    onAnswerUpdates: (Boolean) -> Unit = {},
    /** How the link to the Mac looks: the hero's stitches and the status line. */
    look: LinkLook = LinkLook.Offline,
    onPause: (PauseFor) -> Unit = {},
    onResume: () -> Unit = {},
    onReconnect: () -> Unit = {},
) {
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
        // One call site, so the sheet slides up once and then changes in place (confirm, then the code)
        if (state is SessionState.Pairing || toConfirm != null || scanning) {
            val sheet: (@Composable () -> Unit)? = when {
                state is SessionState.Pairing -> ({ Pairing(state, onCodesDontMatch) })
                toConfirm != null -> ({ Confirm(toConfirm, onConfirm, onCancelConfirm) })
                else -> null
            }
            val back = when {
                state is SessionState.Pairing -> onCodesDontMatch
                toConfirm != null -> onCancelConfirm
                else -> onCloseScan
            }
            return@Surface PairScreen(
                // Board A02: the live view and frame stay behind the sheet; reading pauses
                camera = { camera(sheet == null) },
                onBack = back,
                caption = scanError ?: "Point at the code on your Mac",
                sheet = sheet,
            )
        }
        val home = state is SessionState.Connected || state is SessionState.Idle || state is SessionState.Connecting
        if (home && toConfirm == null) {
            val name = (state as? SessionState.Connected)?.macName ?: (state as? SessionState.Idle)?.macName
                ?: (state as SessionState.Connecting).macName
            return@Surface Home(
                name, look, scanError, mac, onSendClipboard, fromMac, now, onPasteFromMac, onOpenSettings, onOpenNotifications,
                notifications, clipboard, onPause, onResume, onReconnect,
                banner = {
                    Unfold(askUpdates) { AskUpdatesCard(onAnswerUpdates) }
                    val last = remember { mutableStateOf(update) }
                    if (update != null) last.value = update
                    Unfold(update != null) { last.value?.let { UpdateCard(it, updateStatus, onUpdate) } }
                },
            )
        }
        Column(
            Modifier.fillMaxSize().padding(horizontal = 28.dp, vertical = 48.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
        ) {
            when (state) {
                is SessionState.Failed -> Intro("Pairing didn't work", state.reason, "Scan again", scanError, onScan)
                else -> Intro(
                    title = "Pair with your Mac",
                    body = "Open Seam on your Mac and scan the code it shows. That's all the setup there is.",
                    action = "Pair with a Mac",
                    error = scanError,
                    onAction = onScan,
                )
            }
        }
    }
}


@Composable
private fun Intro(title: String, body: String, action: String, error: String?, onAction: () -> Unit) {
    Hero("your Mac", LinkLook.Idle)
    Text(title, fontSize = 30.sp, lineHeight = 36.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-0.8).sp, textAlign = TextAlign.Center)
    Text(body, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
    Spacer(Modifier.height(8.dp))
    BigButton(action, onAction)
    if (error != null) Text(error, color = MaterialTheme.colorScheme.error, textAlign = TextAlign.Center)
}

/**
 * A newer Seam is out (GitHub build): Update downloads it, checks it, and asks Android to install
 * it; the button says "Installing" meanwhile, and anything that goes wrong comes up as a snackbar.
 */
@Composable
private fun UpdateCard(release: Release, status: String?, onUpdate: (Release) -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val snack = rememberSnack()
    var tapped by remember { mutableStateOf(false) }
    val busy = tapped && (status == "Installing" || status?.startsWith("Downloading") == true)
    LaunchedEffect(status) { if (tapped && !busy && status != null) snack(status) }
    Card(
        "Update", "Seam ${release.version} is ready to install", scheme.tertiaryContainer, scheme.onTertiaryContainer,
        // The release's own notes (CHANGELOG.md), which the Update card has always shown
        below = if (release.changes.isEmpty()) null else ({ Text(release.changes, fontSize = 13.5.sp, color = scheme.onTertiaryContainer.copy(alpha = 0.8f)) }),
    ) { Pill(if (busy) "Installing" else "Update", { tapped = true; onUpdate(release) }, PillKind.Filled) }
}

/** Asked once, before Seam ever contacts GitHub (SR-17-04). The answer can be changed in Settings. */
@Composable
private fun AskUpdatesCard(onAnswer: (Boolean) -> Unit) {
    Card(
        "Updates", "Check for updates automatically?", MaterialTheme.colorScheme.surfaceContainerHigh,
        below = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "Once a day Seam asks GitHub for a newer version, and offers it here. GitHub sees this phone's IP address and Seam's version when it does.",
                    fontSize = 13.5.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp, Alignment.End)) {
                    Pill("Not now", { onAnswer(false) }, PillKind.Text)
                    Pill("Check daily", { onAnswer(true) }, PillKind.Filled)
                }
            }
        },
    ) {}
}
