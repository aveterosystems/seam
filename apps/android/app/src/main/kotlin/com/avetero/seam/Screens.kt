// SPDX-License-Identifier: AGPL-3.0-only
package com.avetero.seam

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.avetero.seam.feature.clipboard.FromMac
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
    onSendClipboard: () -> Unit = {},
    fromMac: FromMac? = null,
    now: Long = System.currentTimeMillis(),
    onPasteFromMac: (String) -> Unit = {},
    onOpenSettings: () -> Unit = {},
) {
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
        val home = when (state) {
            is SessionState.Connected -> Link.Connected
            is SessionState.Idle -> Link.Idle
            is SessionState.Connecting -> Link.Connecting
            else -> null
        }
        if (home != null && toConfirm == null) {
            val name = (state as? SessionState.Connected)?.macName ?: (state as? SessionState.Idle)?.macName
                ?: (state as SessionState.Connecting).macName
            return@Surface Home(name, home, scanError, onSendClipboard, fromMac, now, onPasteFromMac, onOpenSettings)
        }
        Column(
            Modifier.fillMaxSize().padding(horizontal = 28.dp, vertical = 48.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
        ) {
            if (toConfirm != null) return@Column Confirm(toConfirm, onConfirm, onCancelConfirm)
            when (state) {
                is SessionState.Pairing -> Pairing(state)
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

/** How the link to the Mac stands right now. */
enum class Link { Connected, Idle, Connecting }

/** Board A01: calm and nearly empty. Only what's built: the Clipboard action, and what came from the Mac. */
@Composable
private fun Home(
    macName: String,
    link: Link,
    notice: String?,
    onSendClipboard: () -> Unit,
    fromMac: FromMac?,
    now: Long,
    onPaste: (String) -> Unit,
    onOpenSettings: () -> Unit,
) {
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().height(64.dp).padding(start = 16.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(painterResource(R.drawable.ic_seam_mark), contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(8.dp))
            Text("Seam", fontSize = 20.sp, fontWeight = FontWeight.Bold, letterSpacing = (-0.3).sp, modifier = Modifier.weight(1f))
            IconButton(onClick = onOpenSettings, modifier = Modifier.size(48.dp)) {
                Icon(Icons.Filled.Settings, contentDescription = "Settings", tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Column(
            Modifier.fillMaxSize().padding(start = 24.dp, end = 24.dp, top = 8.dp, bottom = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Hero(macName, linked = link != Link.Connecting)
            Spacer(Modifier.height(4.dp))
            Text(macName, fontSize = 36.sp, lineHeight = 44.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-0.6).sp, textAlign = TextAlign.Center)
            Spacer(Modifier.height(6.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(8.dp).background(if (link == Link.Connecting) MaterialTheme.colorScheme.outline else OK, CircleShape))
                Spacer(Modifier.width(8.dp))
                Text(
                    when (link) {
                        Link.Connected -> "Connected · Through Seam relay"
                        Link.Idle -> "Paired · connects when there's something to sync"
                        Link.Connecting -> "Reconnecting…"
                    },
                    fontSize = 15.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.height(6.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.Lock, contentDescription = null, modifier = Modifier.size(14.dp), tint = MaterialTheme.colorScheme.outline)
                Spacer(Modifier.width(6.dp))
                Text("End-to-end encrypted", fontSize = 13.sp, color = MaterialTheme.colorScheme.outline)
            }
            if (notice != null) {
                Spacer(Modifier.height(8.dp))
                Text(notice, color = MaterialTheme.colorScheme.error, textAlign = TextAlign.Center)
            }
            Spacer(Modifier.height(32.dp))
            RoundAction("Clipboard", R.drawable.ic_clipboard, primary = true, onClick = onSendClipboard)
            Spacer(Modifier.weight(1f))
            if (fromMac != null) FromMacCard(fromMac, now, onPaste)
        }
    }
}

/** A 68 dp round action; the primary one is a 22 dp rounded square (board A01). */
@Composable
private fun RoundAction(label: String, icon: Int, primary: Boolean, onClick: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Surface(
            onClick = onClick,
            shape = if (primary) RoundedCornerShape(22.dp) else CircleShape,
            color = if (primary) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceContainerHigh,
            contentColor = if (primary) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.size(68.dp).semantics { role = Role.Button; contentDescription = "Send clipboard to Mac" },
        ) {
            Box(contentAlignment = Alignment.Center) { Icon(painterResource(icon), contentDescription = null, modifier = Modifier.size(26.dp)) }
        }
        Text(label, fontSize = 12.sp, fontWeight = FontWeight.Medium)
    }
}

/** Board A01's bottom card: what last came from the Mac, since Android's "Copied" pop-up can't say. */
@Composable
private fun FromMacCard(clip: FromMac, now: Long, onPaste: (String) -> Unit) {
    Surface(color = MaterialTheme.colorScheme.surfaceContainer, shape = RoundedCornerShape(24.dp), modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(start = 18.dp, top = 14.dp, bottom = 14.dp, end = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Copied on your Mac · ${ago(now - clip.at)}", fontSize = 12.sp, fontWeight = FontWeight.Medium, letterSpacing = 0.4.sp, color = MaterialTheme.colorScheme.outline)
                Text(clip.text, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 2.dp))
            }
            Spacer(Modifier.width(12.dp))
            FilledTonalButton(onClick = { onPaste(clip.text) }) { Text("Paste") }
        }
    }
}

/** "just now", "5 min ago", "2 h ago". */
internal fun ago(ms: Long): String = when {
    ms < 60_000 -> "just now"
    ms < 3_600_000 -> "${ms / 60_000} min ago"
    else -> "${ms / 3_600_000} h ago"
}

/** The status dot's colour: the design's `ok` green, not a theme role. */
private val OK = androidx.compose.ui.graphics.Color(0xFF1C7C3A)

@Composable
private fun Intro(title: String, body: String, action: String, error: String?, onAction: () -> Unit) {
    Hero("your Mac", linked = false, size = 220.dp)
    Text(title, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center)
    Text(body, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
    Spacer(Modifier.height(8.dp))
    Button(onClick = onAction, modifier = Modifier.fillMaxWidth().height(56.dp)) { Text(action, fontSize = 16.sp) }
    if (error != null) Text(error, color = MaterialTheme.colorScheme.error, textAlign = TextAlign.Center)
}

@Composable
private fun Confirm(qr: PairingQr, onConfirm: (PairingQr) -> Unit, onCancel: () -> Unit) {
    Hero("your Mac", linked = false, size = 220.dp)
    Text("Pair with ${qr.macName}?", style = MaterialTheme.typography.headlineSmall, textAlign = TextAlign.Center)
    Text("Through ${qr.relayHost}", color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
    Spacer(Modifier.height(8.dp))
    Button(onClick = { onConfirm(qr) }, modifier = Modifier.fillMaxWidth().height(56.dp)) { Text("Pair", fontSize = 16.sp) }
    TextButton(onClick = onCancel, modifier = Modifier.fillMaxWidth()) { Text("Cancel") }
}

@Composable
private fun Pairing(state: SessionState.Pairing) {
    Hero("your Mac", linked = false, size = 220.dp)
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

