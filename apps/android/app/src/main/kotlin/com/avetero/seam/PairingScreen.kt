// SPDX-License-Identifier: AGPL-3.0-only
package com.avetero.seam

import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.animation.animateContentSize
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.avetero.seam.session.PairingQr
import com.avetero.seam.session.SessionState

// Board A02: the viewfinder, the confirm sheet and the code to compare.

@Composable
internal fun Confirm(qr: PairingQr, onConfirm: (PairingQr) -> Unit, onCancel: () -> Unit) {
    SheetHead("Pair with ${qr.macName}?", "Only if it's your Mac, the one in front of you")
    val armed = armedAfterDelay()
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        BigButton("Pair", { onConfirm(qr) }, enabled = armed)
        TextButton(onClick = onCancel, modifier = Modifier.fillMaxWidth().height(56.dp)) { Text("Cancel", fontSize = 16.sp) }
    }
}

/** After the request is sent: the code to compare. Pairing finishes when the Mac confirms. */
@Composable
internal fun Pairing(state: SessionState.Pairing, onCodesDontMatch: () -> Unit) {
    val code = state.safetyCode
    if (code == null) {
        SheetHead("Pairing with ${state.macName}", "Sending this phone's keys to your Mac")
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
internal fun PairScreen(
    camera: (@Composable () -> Unit)?,
    onBack: () -> Unit,
    caption: String? = null,
    sheet: (@Composable () -> Unit)? = null,
) {
    androidx.activity.compose.BackHandler(onBack = onBack)
    // Slides up once, the first time a sheet is shown; the viewfinder behind it never resizes
    val shown = remember { androidx.compose.animation.core.MutableTransitionState(false) }
    shown.targetState = sheet != null
    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface)) {
        Row(Modifier.fillMaxWidth().height(64.dp).padding(start = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack, modifier = Modifier.size(48.dp)) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Text("Pair with a Mac", fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
        }
        Box(Modifier.fillMaxWidth().weight(1f).background(MaterialTheme.colorScheme.surfaceContainerHighest)) {
            if (camera != null) Box(Modifier.matchParentSize()) { camera() }
            Box(Modifier.align(Alignment.Center)) { Frame() }
            if (sheet == null && caption != null) {
                Text(
                    // A snackbar-style pill, so it reads over whatever the camera sees, in either theme
                    caption, color = MaterialTheme.colorScheme.inverseOnSurface, textAlign = TextAlign.Center, fontSize = 14.sp,
                    modifier = Modifier.align(Alignment.BottomCenter).padding(start = 24.dp, end = 24.dp, bottom = 32.dp)
                        .background(MaterialTheme.colorScheme.inverseSurface, RoundedCornerShape(12.dp)).padding(horizontal = 16.dp, vertical = 14.dp),
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
    val teal = MaterialTheme.colorScheme.primary
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
        drawPath(path, teal, style = stroke)
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
            color = MaterialTheme.colorScheme.onSurface, textAlign = TextAlign.Center,
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
