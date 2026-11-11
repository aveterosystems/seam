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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.LineBreak
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.avetero.seam.session.PairingQr
import com.avetero.seam.session.SessionState

// Board A02: the viewfinder, then the house sheet over it: confirm who this is, then the code to compare.

/** The first step: confirm this is your Mac. */
@Composable
internal fun Confirm(qr: PairingQr, onConfirm: (PairingQr) -> Unit, close: () -> Unit) {
    val armed = armedAfterDelay()
    Step("Only if it's your Mac, the one in front of you") {
        BigButton("Pair", { onConfirm(qr) }, enabled = armed)
        Pill("Cancel", close, PillKind.Text, modifier = Modifier.fillMaxWidth())
    }
}

/** After the request is sent: waiting in words and travelling dots, then the code to compare. Pairing finishes when the Mac confirms. */
@Composable
internal fun Pairing(state: SessionState.Pairing, close: () -> Unit) {
    val code = state.safetyCode
    if (code == null) {
        Step("Keep Seam open on ${state.macName}") { WaitingDots() }
    } else {
        Step("Make sure ${state.macName} shows the same code, then confirm there") {
            Text(
                // Tabular digits so the two halves line up with the Mac's; an en space groups them
                code.chunked(3).joinToString("\u2002"),
                style = MaterialTheme.typography.displayMedium.copy(fontFeatureSettings = "tnum"), textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp).semantics { contentDescription = spokenCode(code) },
            )
            Pill("Codes don't match", close, PillKind.Text, modifier = Modifier.fillMaxWidth())
        }
    }
}

/** One step of the pairing sheet: its line in the house sheet's body style, then its parts. */
@Composable
private fun Step(line: String, parts: @Composable () -> Unit) {
    // Its controls on the sheet's 16 dp gutter; its line keeps the title's 8 dp text inset
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(line, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(start = 8.dp, end = 8.dp, bottom = 4.dp))
        parts()
    }
}

/**
 * Board A02: the viewfinder, and the house sheet rising over it once a code is read. [camera] is
 * the live view; it stays on behind the sheet until the pairing screen closes. While [cameraOn] is
 * false the viewfinder holds only what [camera] shows (CameraNeeded): no corners, no tip; nor once a
 * code is read, when the corners close in and fade. [step] is
 * what the sheet shows; a new one swaps in place (catalogue 3), and closing the sheet is [onBack].
 */
@Composable
internal fun PairScreen(
    camera: @Composable () -> Unit,
    onBack: () -> Unit,
    caption: String?,
    cameraOn: Boolean = true,
    title: String? = null,
    step: Any? = null,
    body: @Composable (step: Any, close: () -> Unit) -> Unit = { _, _ -> },
) {
    androidx.activity.compose.BackHandler(onBack = onBack)
    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface)) {
        Row(Modifier.fillMaxWidth().height(64.dp).padding(start = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack, modifier = Modifier.size(48.dp)) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            // Not the 34 sp large title of Settings: the camera needs the height, and with no scroll there's nothing to fold it into
            Text("Pair with your Mac", style = MaterialTheme.typography.headlineSmall)
        }
        Box(Modifier.fillMaxWidth().weight(1f).background(MaterialTheme.colorScheme.surfaceContainerHighest)) {
            Box(Modifier.matchParentSize()) { camera() }
            // Once a code is read, scanning is done: the corners close in on it and fade as the sheet rises, the scan's visible beat
            val reduced = reducedMotion()
            androidx.compose.animation.AnimatedVisibility(
                cameraOn && step == null, Modifier.align(Alignment.Center),
                enter = fadeIn(Motion.ease(250)),
                exit = if (reduced) fadeOut(Motion.ease(250)) else scaleOut(Motion.spring(300), targetScale = 0.88f) + fadeOut(Motion.ease(250)),
                label = "viewfinder",
            ) { Frame() }
            if (cameraOn && step == null && caption != null) {
                // On the shared floating surface, so it reads over whatever the camera sees, in either theme
                FloatingSurface(Modifier.align(Alignment.BottomCenter).padding(start = 24.dp, end = 24.dp, bottom = 32.dp)) {
                    Text(caption, textAlign = TextAlign.Center, modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp))
                }
            }
        }
    }
    // Codes don't match (or any close while pairing) aborts at the tap: the Mac's accept can't land during the exit
    val pairing = step is SessionState.Pairing
    if (title != null && step != null) SeamSheet(title, null, onDismiss = { if (!pairing) onBack() }, onClosing = { if (pairing) onBack() }) { close ->
        AnimatedContent(
            step, Modifier.fillMaxWidth(),
            // The value swap's timing (Swap): the old fades out fast, the new fades in as the sheet resizes on the spring
            transitionSpec = {
                fadeIn(Motion.ease(400, 150)) togetherWith fadeOut(tween(150, easing = Motion.EaseIn)) using
                    SizeTransform(clip = false) { _, _ -> Motion.spring(400) }
            },
            // Confirm, waiting, the code: a Pairing state that only changes in other ways stays put
            contentKey = { (it as? SessionState.Pairing)?.let { p -> p.safetyCode != null } },
            label = "pair step",
        ) { body(it, close) }
        Row(Modifier.padding(start = 8.dp, end = 8.dp, top = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.Lock, contentDescription = null, modifier = Modifier.size(14.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.width(6.dp))
            Text("Keys never leave this phone, sealed by its security chip", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
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

/** In the viewfinder until the camera is allowed: a calm empty state on the page's own surface. */
@Composable
fun CameraNeeded(blocked: Boolean, onAllow: () -> Unit) = EmptyState(
    R.drawable.ic_camera_off,
    if (blocked) "Camera is off for Seam" else "Seam needs the camera",
    if (blocked) "Without it, Seam can't read your Mac's code. Turn it on in Settings\u00A0›\u00A0Permissions\u00A0›\u00A0Camera, then come back."
    else "Only to read your Mac's code. No pictures leave this phone.",
    // Scrolls in a short window (landscape, large text), so the action is always reachable
    Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface).verticalScroll(androidx.compose.foundation.rememberScrollState()).padding(vertical = 32.dp),
) { Pill(if (blocked) "Open Settings" else "Allow camera", onAllow, PillKind.Filled) }

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
