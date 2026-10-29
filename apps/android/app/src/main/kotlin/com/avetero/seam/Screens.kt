// SPDX-License-Identifier: AGPL-3.0-only
package com.avetero.seam

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.avetero.seam.feature.clipboard.ClipRules
import com.avetero.seam.feature.clipboard.FromMac
import com.avetero.seam.session.PairingQr
import com.avetero.seam.session.SessionState

/** One screen per session state (design boards A01 Home and A02 Pairing, simplified for M1). */
@Composable
fun SeamScreen(
    state: SessionState,
    scanError: String?,
    toConfirm: PairingQr?,
    onConfirm: (PairingQr) -> Unit,
    onCancelConfirm: () -> Unit,
    onScan: () -> Unit,
    clipboard: ClipboardStatus = ClipboardStatus.Manual,
    onSendClipboard: () -> Unit = {},
    fromMac: FromMac? = null,
    now: Long = System.currentTimeMillis(),
) {
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
        Column(
            Modifier.fillMaxSize().padding(horizontal = 28.dp, vertical = 48.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
        ) {
            if (toConfirm != null) return@Column Confirm(toConfirm, onConfirm, onCancelConfirm)
            when (state) {
                SessionState.Unpaired -> Intro(
                    title = "Pair with your Mac",
                    body = "Open Seam on your Mac and scan the code it shows. That's all the setup there is.",
                    action = "Scan pairing code",
                    error = scanError,
                    onAction = onScan,
                )
                is SessionState.Pairing -> Pairing(state)
                is SessionState.Connecting -> Linked(state.macName, connected = false, notice = scanError, clipboard, onSendClipboard, fromMac, now)
                is SessionState.Connected -> Linked(state.macName, connected = true, notice = scanError, clipboard, onSendClipboard, fromMac, now)
                is SessionState.Failed -> Intro("Pairing didn't work", state.reason, "Scan again", scanError, onScan)
            }
        }
    }
}

@Composable
private fun Intro(title: String, body: String, action: String, error: String?, onAction: () -> Unit) {
    MacBadge()
    Text(title, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center)
    Text(body, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
    Spacer(Modifier.height(8.dp))
    Button(onClick = onAction, modifier = Modifier.fillMaxWidth().height(56.dp)) { Text(action, fontSize = 16.sp) }
    if (error != null) Text(error, color = MaterialTheme.colorScheme.error, textAlign = TextAlign.Center)
}

@Composable
private fun Confirm(qr: PairingQr, onConfirm: (PairingQr) -> Unit, onCancel: () -> Unit) {
    MacBadge()
    Text("Pair with ${qr.macName}?", style = MaterialTheme.typography.headlineSmall, textAlign = TextAlign.Center)
    Text("Through ${qr.relayHost}", color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
    Spacer(Modifier.height(8.dp))
    Button(onClick = { onConfirm(qr) }, modifier = Modifier.fillMaxWidth().height(56.dp)) { Text("Pair", fontSize = 16.sp) }
    TextButton(onClick = onCancel, modifier = Modifier.fillMaxWidth()) { Text("Cancel") }
}

@Composable
private fun Pairing(state: SessionState.Pairing) {
    MacBadge()
    Text("Pairing with ${state.macName}", style = MaterialTheme.typography.headlineSmall, textAlign = TextAlign.Center)
    val code = state.safetyCode
    if (code == null) {
        CircularProgressIndicator()
    } else {
        Text("Check your Mac shows the same code", color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(code, fontFamily = FontFamily.Monospace, fontSize = 40.sp, letterSpacing = 4.sp, fontWeight = FontWeight.Medium)
        Text("Then confirm on your Mac.", color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun Linked(
    macName: String,
    connected: Boolean,
    notice: String?,
    clipboard: ClipboardStatus,
    onSendClipboard: () -> Unit,
    fromMac: FromMac?,
    now: Long,
) {
    MacBadge()
    Text(macName, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.SemiBold)
    Text(
        if (connected) "● Connected · end-to-end encrypted" else "Reconnecting…",
        color = if (connected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
    )
    if (notice != null) Text(notice, color = MaterialTheme.colorScheme.error, textAlign = TextAlign.Center)
    Text(
        "Your battery shows in your Mac's menu bar. What you copy on your Mac is on this phone's clipboard too.",
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center,
    )
    if (fromMac != null) FromMacCard(fromMac, now)
    Spacer(Modifier.height(8.dp))
    Button(onClick = onSendClipboard, modifier = Modifier.fillMaxWidth().height(56.dp)) { Text("Send clipboard to Mac", fontSize = 16.sp) }
    Text(
        when (clipboard) {
            ClipboardStatus.Automatic -> "Automatic: what you copy here goes to your Mac by itself."
            ClipboardStatus.NeedsSetup -> "To send copies automatically, choose Turn On Automatic Clipboard in Seam's menu on your Mac."
            ClipboardStatus.Manual -> "Or share any text to Send to Mac."
        },
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center,
    )
}

/** Board A01's bottom card: what last came from the Mac, since Android's "Copied" pop-up can't say. */
@Composable
private fun FromMacCard(clip: FromMac, now: Long) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("Copied on your Mac · ${ago(now - clip.at)}", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
            Text(ClipRules.preview(clip.text), maxLines = 3, overflow = TextOverflow.Ellipsis)
            Text("It's on this phone's clipboard: paste it anywhere.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** "just now", "5 min ago", "2 h ago". */
internal fun ago(ms: Long): String = when {
    ms < 60_000 -> "just now"
    ms < 3_600_000 -> "${ms / 60_000} min ago"
    else -> "${ms / 3_600_000} h ago"
}

/** Phone → Mac: automatic (full build, granted), possible after setup (full build), or one tap (Play build). */
enum class ClipboardStatus { Automatic, NeedsSetup, Manual }

/** The laptop-and-phone mark on a soft circle (stand-in for board A01's scalloped hero). */
@Composable
private fun MacBadge() {
    Box(
        Modifier.size(140.dp).background(MaterialTheme.colorScheme.primaryContainer, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier.size(width = 64.dp, height = 42.dp)
                .background(MaterialTheme.colorScheme.onPrimaryContainer, RoundedCornerShape(6.dp)),
        )
    }
}
