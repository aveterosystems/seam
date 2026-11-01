// SPDX-License-Identifier: AGPL-3.0-only
package com.avetero.seam

import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ButtonGroup
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.avetero.seam.feature.MacStatus
import com.avetero.seam.feature.clipboard.FromMac
import com.avetero.seam.feature.notifications.NotificationsFeature
import com.avetero.seam.session.SessionState
import kotlinx.coroutines.delay

/** What Home says after Send clipboard: on the button when it went, in a snackbar when it waits or can't. */
data class SendFeedback(val label: String? = null, val snack: String? = null)

fun sendFeedback(sent: Boolean, state: SessionState, macName: String, offOnMac: Boolean = false): SendFeedback = when {
    offOnMac -> SendFeedback(snack = "Your Mac has Sync the clipboard turned off")
    !sent -> SendFeedback(snack = "Nothing to send: the clipboard is empty, too big, or holds something private")
    state is SessionState.Connected -> SendFeedback(label = "✓ Sent to Mac")
    // Queued, and the connection is starting: not sent yet, so it doesn't say so (SR-8-04)
    state is SessionState.Idle -> SendFeedback(label = "Sending")
    else -> SendFeedback(snack = "Will send when $macName is back")
}

/**
 * "Mac battery 64%" while connected or paused (as last sent), "—" while it's out of reach, and
 * nothing at all before the Mac has ever sent it.
 */
fun macBattery(look: LinkLook, mac: MacStatus?): String? = when {
    mac == null -> null
    look.connected || look.paused -> "${mac.batteryPct}%"
    else -> "—"
}

/** Board A01, as the approved prototype draws it: the Mac, how it's connected, and the two things to do. */
@Composable
internal fun Home(
    macName: String,
    look: LinkLook,
    notice: String?,
    mac: MacStatus?,
    onSend: () -> SendFeedback,
    fromMac: FromMac?,
    now: Long,
    onPaste: (String) -> Boolean,
    onOpenSettings: () -> Unit,
    onOpenNotifications: () -> Unit,
    notifications: NotificationsFeature?,
    clipboard: ClipboardStatus,
    onPause: (PauseFor) -> Unit,
    onResume: () -> Unit,
    onReconnect: () -> Unit,
    banner: @Composable () -> Unit,
) {
    var details by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(start = 16.dp, end = 16.dp, bottom = 40.dp)) {
        Row(Modifier.fillMaxWidth().height(56.dp).padding(start = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(painterResource(R.drawable.ic_seam_mark), contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(28.dp))
            Spacer(Modifier.width(8.dp))
            Text("Seam", fontSize = 22.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-0.2).sp, modifier = Modifier.weight(1f))
            IconButton(onClick = onOpenSettings, modifier = Modifier.size(48.dp)) {
                Icon(painterResource(R.drawable.ic_settings), contentDescription = "Settings", tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(22.dp))
            }
        }
        banner()
        Column(Modifier.fillMaxWidth().padding(top = 2.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Hero(macName, look)
            Text(
                macName, fontSize = 30.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-0.8).sp,
                textAlign = TextAlign.Center, modifier = Modifier.padding(top = 4.dp, bottom = 8.dp),
            )
            StatusChip(look, open = details) { details = !details }
            // The chip's details: Reconnect, for when you know it's stuck. Folds away once tapped
            // Not while paused: nothing connects then, so Reconnect would do nothing
            Unfold(details && !look.paused) {
                Card("Connection", "Seems stuck? Try again now", MaterialTheme.colorScheme.surfaceContainerHigh, kicker = MaterialTheme.colorScheme.outline) {
                    Pill("Reconnect", { details = false; onReconnect() })
                }
            }
            Meta(macBattery(look, mac), mac?.let { it.batteryPct <= 15 && !it.charging } == true && look.connected)
            if (notice != null) Text(notice, color = MaterialTheme.colorScheme.error, textAlign = TextAlign.Center, modifier = Modifier.padding(bottom = 12.dp))
        }
        Actions(look.paused, onSend, onPause, onResume)
        FromMacSlot(fromMac, now, onPaste)
        Section(null) {
            if (notifications != null) NotificationsItem(notifications, "Notifications on Mac", home = true, onOpen = onOpenNotifications)
            AutoClipboardItem(clipboard, home = true)
        }
    }
}

/**
 * "● Connected · Same Wi‑Fi ⓘ" in a 32 dp chip, tertiary while paused. While connected the dot
 * sends out a ring: 1 → 2.8× while fading from 60%, ease-out over the first 70% of 2.4 s. A tap
 * unfolds the connection's details underneath.
 */
@Composable
private fun StatusChip(look: LinkLook, open: Boolean, onToggle: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val bg = pick(look.paused, scheme.surfaceContainerLow, scheme.tertiaryContainer, Motion.ease(400))
    val ink = pick(look.paused, scheme.onSurfaceVariant, scheme.onTertiaryContainer, Motion.ease(400))
    val dot = pick(look.connected, scheme.outline, okColor, Motion.ease(400))
    val ring = remember { Animatable(0f) }
    if (look.connected && !reducedMotion()) LaunchedEffect(Unit) {
        while (true) {
            ring.snapTo(0f)
            ring.animateTo(1f, tween(Motion.PULSE_MS * 7 / 10, easing = Motion.EaseOut))
            delay(Motion.PULSE_MS * 3L / 10)
        }
    } else LaunchedEffect(Unit) { ring.snapTo(0f) }
    Row(
        Modifier.height(32.dp).clip(RoundedCornerShape(16.dp)).background(bg)
            .clickable(onClickLabel = if (open) "Hide connection details" else "Show connection details", role = Role.Button, onClick = onToggle)
            .padding(horizontal = 14.dp)
            // TalkBack reads the whole line, and again when it changes
            .semantics(mergeDescendants = true) { contentDescription = look.sentence; liveRegion = LiveRegionMode.Polite },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(8.dp).drawBehind {
            val p = ring.value
            if (p > 0f) drawCircle(dot.copy(alpha = 0.6f * (1 - p)), radius = size.minDimension / 2 * (1 + 1.8f * p))
            drawCircle(dot)
        })
        Spacer(Modifier.width(7.dp))
        Swap(look.sentence) { Text(it, fontSize = 13.5.sp, fontWeight = FontWeight.Medium, color = ink, maxLines = 1) }
        Spacer(Modifier.width(6.dp))
        Icon(painterResource(R.drawable.ic_info), contentDescription = null, tint = ink, modifier = Modifier.size(16.dp))
    }
}

/** "Encrypted   Mac battery 64%": 12.5 sp, 14 dp apart, the number in tabular digits, amber at 15% or less while not charging. */
@Composable
private fun Meta(battery: String?, low: Boolean) {
    val scheme = MaterialTheme.colorScheme
    val style = TextStyle(fontSize = 12.5.sp, fontFeatureSettings = "tnum")
    Row(Modifier.padding(top = 10.dp, bottom = 18.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        Text("Encrypted", style = style, fontWeight = FontWeight.Medium, color = scheme.onSurfaceVariant)
        if (battery != null) {
            val color = pick(low, scheme.onSurfaceVariant, warnColor, Motion.ease(400))
            Row(Modifier.semantics(mergeDescendants = true) {}) {
                Text("Mac battery ", style = style, color = scheme.outline)
                Swap(battery) { Text(it, style = style, fontWeight = FontWeight.Medium, color = color) }
            }
        }
    }
}

/**
 * Send clipboard and Pause as a button group, 6 dp apart, at 4 : 1. Pressing one widens it
 * (the other's share drops to 0.8, or Send's to 3.2) on the spring (0.45 s), and its corners
 * tighten from 30 to 16 dp (0.35 s). Paused, Pause shrinks to nothing and fades (0.3 s), and
 * Send becomes Resume.
 */
@Composable
private fun Actions(paused: Boolean, onSend: () -> SendFeedback, onPause: (PauseFor) -> Unit, onResume: () -> Unit) {
    val snack = rememberSnack()
    var confirm by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(confirm) { if (confirm != null) { delay(Motion.CONFIRM_MS); confirm = null } }
    var choosing by remember { mutableStateOf(false) }
    val scheme = MaterialTheme.colorScheme
    val sendSource = remember { MutableInteractionSource() }
    val pauseSource = remember { MutableInteractionSource() }
    val sendDown by sendSource.collectIsPressedAsState()
    val pauseDown by pauseSource.collectIsPressedAsState()
    // CSS flex as the prototype renders it: each button's 12 dp padding sits outside the
    // 4 : 1 share (pressed 4 : 0.8, or 3.2 : 1). Paused, Pause's share and padding go to 0
    val sendGrow by animateFloatAsState(if (pauseDown && !paused) 3.2f else 4f, Motion.spring(450), label = "sendGrow")
    val pauseGrow by animateFloatAsState(if (paused) 0f else if (sendDown) 0.8f else 1f, Motion.spring(450), label = "pauseGrow")
    val pausePad by animateFloatAsState(if (paused) 0f else 12f, Motion.ease(300), label = "pausePad")
    val pauseAlpha by animateFloatAsState(if (paused) 0f else 1f, Motion.ease(300), label = "pauseAlpha")
    val sendCorner by animateDpAsState(if (sendDown) 16.dp else 30.dp, Motion.spring(350), label = "sendCorner")
    val pauseCorner by animateDpAsState(if (pauseDown) 16.dp else 30.dp, Motion.spring(350), label = "pauseCorner")
    val label = when { paused -> "Resume"; confirm != null -> confirm!!; else -> "Send clipboard" }
    BoxWithConstraints(Modifier.fillMaxWidth().padding(bottom = 18.dp)) {
    val free = maxWidth.value - 6f - 12f - pausePad
    val sendWidth = 12f + free * sendGrow / (sendGrow + pauseGrow)
    val pauseWidth = pausePad + free * pauseGrow / (sendGrow + pauseGrow)
    ButtonGroup(overflowIndicator = {}, modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        customItem({
            Button(
                onClick = {
                    if (paused) { onResume(); snack("Seam is back on") }
                    else onSend().let { f -> f.label?.let { confirm = it }; f.snack?.let(snack) }
                },
                shape = RoundedCornerShape(sendCorner),
                colors = ButtonDefaults.buttonColors(scheme.primary, scheme.onPrimary),
                interactionSource = sendSource,
                contentPadding = PaddingValues(horizontal = 16.dp),
                modifier = Modifier.weight(sendWidth).height(60.dp),
            ) {
                Icon(painterResource(if (paused) R.drawable.ic_play else R.drawable.ic_copy), contentDescription = null, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(9.dp))
                Swap(label) { Text(it, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 0.sp, maxLines = 1) }
            }
        }, {})
        customItem({
            Button(
                onClick = { choosing = true },
                enabled = !paused,
                shape = RoundedCornerShape(pauseCorner),
                colors = ButtonDefaults.buttonColors(scheme.secondaryContainer, scheme.onSecondaryContainer, scheme.secondaryContainer, scheme.onSecondaryContainer),
                interactionSource = pauseSource,
                contentPadding = PaddingValues(0.dp),
                modifier = Modifier.weight(pauseWidth.coerceAtLeast(0.001f)).height(60.dp).alpha(pauseAlpha)
                    .then(if (paused) Modifier.clearAndSetSemantics {} else Modifier),
            ) { Icon(painterResource(R.drawable.ic_pause), contentDescription = "Pause", modifier = Modifier.size(20.dp)) }
        }, {})
    }
    }
    if (choosing) PauseSheet(onDismiss = { choosing = false }, onPause = onPause)
}

/**
 * "Copied on your Mac": what last came over, since Android's own "Copied" pop-up can't say.
 * Paste turns green and says "✓ Pasted"; 0.9 s later the card folds away.
 */
@Composable
private fun FromMacSlot(fromMac: FromMac?, now: Long, onPaste: (String) -> Boolean) {
    val snack = rememberSnack()
    var pasted by remember { mutableStateOf<Long?>(null) }
    var gone by remember { mutableStateOf<Long?>(null) }
    val shown = fromMac?.takeIf { it.fresh(now) && it.at != gone }
    val last = remember { mutableStateOf(shown) }
    if (shown != null) last.value = shown
    LaunchedEffect(pasted) { pasted?.let { delay(900); gone = it } }
    Unfold(shown != null) {
        val clip = last.value ?: return@Unfold
        val done = pasted == clip.at
        Card("Copied on your Mac · ${ago(now - clip.at)}", clip.text, MaterialTheme.colorScheme.surfaceContainerHigh, kicker = MaterialTheme.colorScheme.outline) {
            Pill(if (done) "✓ Pasted" else "Paste", { if (onPaste(clip.text)) pasted = clip.at else snack("Couldn't put it on the clipboard") }, if (done) PillKind.Done else PillKind.Tonal, enabled = !done)
        }
    }
}

/** "just now", "5 min ago", "2 h ago". */
internal fun ago(ms: Long): String = when {
    ms < 60_000 -> "just now"
    ms < 3_600_000 -> "${ms / 60_000} min ago"
    else -> "${ms / 3_600_000} h ago"
}

/** Home's cards: 28 dp corners, a 12 sp capitalised kicker, one 15 sp line, an action at the end, anything under them. */
@Composable
internal fun Card(
    title: String,
    value: String,
    container: Color,
    ink: Color = MaterialTheme.colorScheme.onSurface,
    kicker: Color = ink.copy(alpha = 0.8f),
    below: (@Composable () -> Unit)? = null,
    action: @Composable () -> Unit,
) {
    Column(
        // 12 dp top and bottom, not the prototype's 16: the 40 dp pill keeps a 48 dp touch target, so the card stays the same height
        Modifier.fillMaxWidth().padding(bottom = 12.dp).background(container, RoundedCornerShape(28.dp)).padding(start = 20.dp, end = 16.dp, top = 12.dp, bottom = 12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Column(Modifier.weight(1f)) {
                Text(title.uppercase(), fontSize = 12.sp, fontWeight = FontWeight.Medium, letterSpacing = 0.4.sp, color = kicker)
                Text(value, fontSize = 15.sp, color = ink, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 3.dp))
            }
            action()
        }
        below?.invoke()
    }
}
