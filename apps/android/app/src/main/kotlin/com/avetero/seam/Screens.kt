// SPDX-License-Identifier: AGPL-3.0-only
package com.avetero.seam

import androidx.annotation.DrawableRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.LineBreak
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.avetero.seam.crypto.LinkLook
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
    /** The sheet of the Mac's resources is open: ask it for a fresh reading. */
    onRequestMac: () -> Unit = {},
    notifications: NotificationsFeature? = null,
    clipboard: ClipboardStatus = ClipboardStatus.Manual,
    onCodesDontMatch: () -> Unit = {},
    scanning: Boolean = false,
    /** The camera is allowed; while it isn't, the viewfinder shows only why (no corners, no tip). */
    cameraOn: Boolean = true,
    onCloseScan: () -> Unit = {},
    camera: @Composable (reading: Boolean) -> Unit = {},
    update: Release? = null,
    updateStatus: String? = null,
    onUpdate: (Release) -> Unit = {},
    askUpdates: Boolean = false,
    onAnswerUpdates: (Boolean) -> Unit = {},
    /** How the link to the Mac looks: the hero's stitches and the status line. */
    look: LinkLook = Looks.offline,
    onPause: (PauseFor) -> Unit = {},
    onResume: () -> Unit = {},
) {
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
        // One call site, so the sheet slides up once and then changes in place (confirm, then the code)
        if (state is SessionState.Pairing || toConfirm != null || scanning) {
            val step: Any? = if (state is SessionState.Pairing) state else toConfirm
            val macName = (state as? SessionState.Pairing)?.macName ?: toConfirm?.macName
            val back = when {
                state is SessionState.Pairing -> onCodesDontMatch
                toConfirm != null -> onCancelConfirm
                else -> onCloseScan
            }
            return@Surface PairScreen(
                // Board A02: the live view and frame stay behind the sheet; reading pauses
                camera = { camera(step == null) },
                onBack = back,
                caption = scanError ?: "Point at the code on your Mac",
                cameraOn = cameraOn,
                // The sheet's title says the step: confirm, waiting, then the code
                title = macName?.let {
                    when {
                        state !is SessionState.Pairing -> "Pair with $it?"
                        state.safetyCode == null -> "Waiting for $it"
                        else -> "Check the code"
                    }
                },
                step = step,
            ) { s, close ->
                when (s) {
                    is SessionState.Pairing -> Pairing(s, close)
                    is PairingQr -> Confirm(s, onConfirm, close)
                }
            }
        }
        val home = state is SessionState.Connected || state is SessionState.Idle || state is SessionState.Connecting
        if (home && toConfirm == null) {
            val name = (state as? SessionState.Connected)?.macName ?: (state as? SessionState.Idle)?.macName
                ?: (state as SessionState.Connecting).macName
            return@Surface Home(
                name, look, scanError, mac, onSendClipboard, fromMac, now, onPasteFromMac, onOpenSettings, onOpenNotifications,
                notifications, clipboard, onPause, onResume,
                onRequestMac = onRequestMac,
                banner = {
                    Unfold(askUpdates) { AskUpdatesCard(onAnswerUpdates) }
                    val last = remember { mutableStateOf(update) }
                    if (update != null) last.value = update
                    Unfold(update != null) { last.value?.let { UpdateCard(it, updateStatus, onUpdate) } }
                },
            )
        }
        when (state) {
            is SessionState.Failed -> Intro("Pairing didn't work", state.reason, "Scan again", scanError, onScan)
            else -> Intro(
                title = "Pair with your Mac",
                body = "Open Seam on your Mac and scan the code it shows. That's the whole setup.",
                action = "Scan code",
                error = scanError,
                onAction = onScan,
            )
        }
    }
}

/** The first-run screen: pairing's first step, in the page Setup (the next one) shares. */
@Composable
private fun Intro(title: String, body: String, action: String, error: String?, onAction: () -> Unit) =
    FirstRunPage(Looks.idle, title, body, action = { BigButton(action, onAction) }) {
        if (error != null) {
            Text(error, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp))
        }
        // Where the code is, in the Mac app's own words (SeamApp.swift opens it on first launch; PopoverView.swift's button after)
        // Guidance, not controls: no row cards, so nothing looks tappable. Centred as one block in the free band, a third of it
        // above and two below so it sits optically centred; the words above stay put, keeping the cookie at Setup's height
        Spacer(Modifier.weight(1f))
        Column(Modifier.align(Alignment.CenterHorizontally).padding(horizontal = 8.dp, vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Hint(R.drawable.ic_laptop, "Seam shows it on your Mac", "As soon as it first opens")
            // The filled mark reads heavier than the outline laptop at the same size
            Hint(R.drawable.ic_seam_mark, "Not showing?", "Seam in the menu bar › Pair your phone", iconSize = 18.dp)
        }
        Spacer(Modifier.weight(2f))
    }

@Composable
private fun Hint(@DrawableRes icon: Int, title: String, detail: String, iconSize: Dp = 20.dp) =
    Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        // A 20 dp box either way, so both icons centre on the same line and the words line up
        Box(Modifier.padding(top = 2.dp).size(20.dp), contentAlignment = Alignment.Center) {
            Icon(painterResource(icon), contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(iconSize))
        }
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(detail, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }

/**
 * Pairing's intro and Setup, one layout so the step between them doesn't jump: Home's mark and wordmark on top, the welcome
 * cookie, title and words at the same place under it, then [content], all scrolling above [action], which stays pinned to the bottom.
 * The scroll is at least the free height, so a weighted spacer in [content] can push what follows down to the action.
 */
@Composable
internal fun FirstRunPage(hero: LinkLook, title: String, body: String, action: @Composable () -> Unit, content: @Composable ColumnScope.() -> Unit = {}) {
    Column(Modifier.fillMaxSize().padding(start = 16.dp, end = 16.dp, bottom = 16.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
        // Home's top bar without Settings, so the app is named from the first screen
        Row(Modifier.fillMaxWidth().height(56.dp).padding(start = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(painterResource(R.drawable.ic_seam_mark), contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(28.dp))
            Spacer(Modifier.width(8.dp))
            Text("Seam", style = MaterialTheme.typography.headlineSmall.copy(letterSpacing = (-0.2).sp))
        }
        // Top-aligned, not centred: centred, the cookie would sit at a different height on each page
        BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
            Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).heightIn(min = maxHeight).padding(top = 16.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
                Column(Modifier.fillMaxWidth().padding(horizontal = 8.dp), Arrangement.spacedBy(12.dp), Alignment.CenterHorizontally) {
                    Hero(hero, size = 176.dp, cookie = CookieLook.Welcome)
                    Text(title, style = MaterialTheme.typography.displaySmall, textAlign = TextAlign.Center, modifier = Modifier.semantics { heading() })
                    // Narrower than the title, in even lines, so a long paragraph doesn't run edge to edge on either page
                    Text(
                        body, style = MaterialTheme.typography.bodyLarge.copy(lineBreak = LineBreak.Heading), color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center, modifier = Modifier.padding(horizontal = 24.dp),
                    )
                }
                content()
            }
        }
        action()
    }
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
        "New version", "Seam ${release.version} is ready", scheme.secondaryContainer, scheme.onSecondaryContainer,
        // The release's own notes (CHANGELOG.md), which the Update card has always shown
        below = if (release.changes.isEmpty()) null else ({ Text(release.changes, color = scheme.onSecondaryContainer.copy(alpha = 0.8f)) }),
    ) { Pill(if (busy) "Installing" else "Update", { tapped = true; onUpdate(release) }, PillKind.OnCard) }
}

/** Asked once, before Seam ever contacts GitHub (SR-17-04). The answer can be changed in Settings. */
@Composable
private fun AskUpdatesCard(onAnswer: (Boolean) -> Unit) {
    Card(
        "Updates", "Check for updates automatically?", MaterialTheme.colorScheme.secondaryContainer, MaterialTheme.colorScheme.onSecondaryContainer,
        below = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "Once a day Seam asks GitHub for a newer version, and offers it here. GitHub sees this phone's IP address and Seam's version when it does.",
                    color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.8f),
                )
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp, Alignment.End)) {
                    Pill("Not now", { onAnswer(false) }, PillKind.Text)
                    Pill("Check daily", { onAnswer(true) }, PillKind.OnCard)
                }
            }
        },
    ) {}
}
