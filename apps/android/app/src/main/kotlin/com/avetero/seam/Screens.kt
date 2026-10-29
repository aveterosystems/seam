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
import androidx.compose.material.icons.automirrored.filled.ArrowBack
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.animation.animateContentSize
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
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
    onCodesDontMatch: () -> Unit = {},
    scanning: Boolean = false,
    onCloseScan: () -> Unit = {},
    camera: @Composable (reading: Boolean) -> Unit = {},
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
                Box(Modifier.size(8.dp).background(if (link == Link.Connecting) MaterialTheme.colorScheme.outline else okColor, CircleShape))
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
            if (fromMac != null && fromMac.fresh(now)) FromMacCard(fromMac, now, onPaste)
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
    SheetHead("Pair with ${qr.macName}?", "Only if it's your Mac, the one in front of you")
    Text(
        if (qr.officialRelay) "Through ${qr.relayHost}" else "Through ${qr.relayHost}: not Seam's relay. Only pair if you run it.",
        fontSize = 14.sp, textAlign = TextAlign.Center,
        color = if (qr.officialRelay) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error,
    )
    val armed = armedAfterDelay()
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(onClick = { onConfirm(qr) }, enabled = armed, modifier = Modifier.fillMaxWidth().height(56.dp)) { Text("Pair", fontSize = 16.sp) }
        TextButton(onClick = onCancel, modifier = Modifier.fillMaxWidth().height(56.dp)) { Text("Cancel", fontSize = 16.sp) }
    }
}

/** After the request is sent: the code to compare. Pairing finishes when the Mac confirms. */
@Composable
private fun Pairing(state: SessionState.Pairing, onCodesDontMatch: () -> Unit) {
    val code = state.safetyCode
    if (code == null) {
        SheetHead("Pairing with ${state.macName}…", "Sending this phone's keys to your Mac")
        CircularProgressIndicator(Modifier.padding(16.dp))
    } else {
        SheetHead("Pair with ${state.macName}?", "Check your Mac shows the same code, then confirm on it")
        Text(
            code.chunked(3).joinToString(" ").replace("  ", " "),
            fontFamily = FontFamily.Monospace, fontSize = 34.sp, letterSpacing = 4.sp, fontWeight = FontWeight.Medium,
            modifier = Modifier.semantics { contentDescription = "Code $code" },
        )
        TextButton(onClick = onCodesDontMatch, modifier = Modifier.fillMaxWidth().height(56.dp)) { Text("Codes don't match", fontSize = 16.sp) }
    }
}

/**
 * Board A02: dark, the viewfinder, and the sheet rising over it once a code is read. [camera] is
 * the live view; it stays on behind the sheet until the pairing screen closes.
 */
@Composable
private fun PairScreen(
    camera: (@Composable () -> Unit)?,
    onBack: () -> Unit,
    caption: String? = null,
    sheet: (@Composable () -> Unit)? = null,
) {
    androidx.activity.compose.BackHandler(onBack = onBack)
    // Slides up once, the first time a sheet is shown; the viewfinder behind it never resizes
    val shown = remember { androidx.compose.animation.core.MutableTransitionState(false) }
    shown.targetState = sheet != null
    Column(Modifier.fillMaxSize().background(Color(0xFF0B0F0E))) {
        Row(Modifier.fillMaxWidth().height(64.dp).padding(start = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack, modifier = Modifier.size(48.dp)) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = Color.White)
            }
            Text("Pair with a Mac", color = Color.White, fontSize = 22.sp)
        }
        Box(Modifier.fillMaxWidth().weight(1f).background(Color(0xFF1C2422))) {
            if (camera != null) Box(Modifier.matchParentSize()) { camera() }
            Box(Modifier.align(Alignment.Center)) { Frame() }
            if (sheet == null && caption != null) {
                Text(
                    caption, color = Color(0xFFDDE4E1), textAlign = TextAlign.Center,
                    modifier = Modifier.align(Alignment.BottomCenter).padding(start = 24.dp, end = 24.dp, bottom = 32.dp),
                )
            }
            androidx.compose.animation.AnimatedVisibility(
                visibleState = shown,
                enter = androidx.compose.animation.slideInVertically { it } + androidx.compose.animation.fadeIn(),
                modifier = Modifier.align(Alignment.BottomCenter),
            ) {
                Surface(
                    color = MaterialTheme.colorScheme.surfaceContainerLow,
                    shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(
                        Modifier.padding(start = 24.dp, end = 24.dp, bottom = 24.dp).animateContentSize(),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(16.dp),
                    ) {
                        Box(Modifier.padding(top = 22.dp).size(width = 32.dp, height = 4.dp).background(MaterialTheme.colorScheme.outline.copy(alpha = 0.6f), RoundedCornerShape(2.dp)))
                        Box(
                            Modifier.size(64.dp).background(MaterialTheme.colorScheme.primaryContainer, RoundedCornerShape(22.dp)),
                            contentAlignment = Alignment.Center,
                        ) { Icon(painterResource(R.drawable.ic_laptop), contentDescription = null, tint = MaterialTheme.colorScheme.onPrimaryContainer, modifier = Modifier.size(34.dp)) }
                        sheet?.invoke()
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Filled.Lock, contentDescription = null, modifier = Modifier.size(14.dp), tint = MaterialTheme.colorScheme.outline)
                            Spacer(Modifier.width(6.dp))
                            Text("Keys never leave this phone, sealed by its security chip", fontSize = 12.sp, lineHeight = 16.sp, color = MaterialTheme.colorScheme.outline)
                        }
                    }
                }
            }
        }
    }
}

/** The board's 240 dp viewfinder corners. */
@Composable
private fun Frame() {
    androidx.compose.foundation.Canvas(Modifier.size(240.dp)) {
        val w = size.width
        val u = w / 240f
        val stroke = androidx.compose.ui.graphics.drawscope.Stroke(width = 6 * u, cap = androidx.compose.ui.graphics.StrokeCap.Round)
        val path = androidx.compose.ui.graphics.Path()
        fun corner(x0: Float, y0: Float, cx: Float, cy: Float, x1: Float, y1: Float) {
            path.moveTo(x0 * u, y0 * u); path.quadraticTo(cx * u, cy * u, x1 * u, y1 * u)
        }
        corner(4f, 48f, 4f, 4f, 48f, 4f)
        corner(192f, 4f, 236f, 4f, 236f, 48f)
        corner(236f, 192f, 236f, 236f, 192f, 236f)
        corner(48f, 236f, 4f, 236f, 4f, 192f)
        drawPath(path, Color(0xFF9FF2E0), style = stroke)
    }
}

@Composable
private fun SheetHead(title: String, subtitle: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(title, fontSize = 24.sp, lineHeight = 32.sp, textAlign = TextAlign.Center)
        Text(subtitle, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
    }
}

/** In the viewfinder until the camera is allowed. */
@Composable
fun CameraNeeded(asked: Boolean, onAllow: () -> Unit) {
    Column(
        Modifier.fillMaxSize().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
    ) {
        Text(
            "Seam uses the camera only on this screen, to read the code on your Mac. It reads the code on this phone and sends no pictures anywhere.",
            color = Color(0xFFDDE4E1), textAlign = TextAlign.Center,
        )
        Button(onClick = onAllow) { Text(if (asked) "Allow in Settings" else "Allow camera") }
    }
}

/**
 * False for the first half second, then true: a button that takes a tap only once it has been on
 * screen long enough to be seen, so a tap meant for something else can't land on it (SR-11-02).
 */
@Composable
fun armedAfterDelay(ms: Long = ARM_MS): Boolean {
    var armed by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }
    androidx.compose.runtime.LaunchedEffect(Unit) {
        kotlinx.coroutines.delay(ms)
        armed = true
    }
    return armed
}

const val ARM_MS = 500L
