// SPDX-License-Identifier: AGPL-3.0-only
package com.avetero.seam

import androidx.compose.ui.text.style.TextIndent
import androidx.annotation.DrawableRes
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.indication
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.InlineTextContent
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.foundation.text.appendInlineContent
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ButtonGroup
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.layout
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.text
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.Placeholder
import androidx.compose.ui.text.PlaceholderVerticalAlign
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineBreak
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.avetero.seam.crypto.DeviceStatusLook
import com.avetero.seam.crypto.LinkLook
import com.avetero.seam.crypto.encryptedWords
import com.avetero.seam.feature.MacStatus
import com.avetero.seam.feature.clipboard.FromMac
import com.avetero.seam.feature.notifications.NotificationsFeature
import com.avetero.seam.protocol.v1.Status
import com.avetero.seam.session.SessionState
import kotlinx.coroutines.delay

/** What Home says after Send clipboard: on the button when it went, in a snackbar when it waits or can't. [done]: it really went (green, the confirm tick). */
data class SendFeedback(val label: String? = null, val snack: String? = null, val done: Boolean = false)

fun sendFeedback(sent: Boolean, state: SessionState, macName: String, offOnMac: Boolean = false): SendFeedback = when {
    offOnMac -> SendFeedback(snack = SendWords.syncOff(macName))
    !sent -> SendFeedback(snack = SendWords.NOTHING)
    state is SessionState.Connected -> SendFeedback(label = SendWords.SENT, done = true)
    // Queued, and the connection is starting: not sent yet, so it doesn't say so (SR-8-04)
    state is SessionState.Idle -> SendFeedback(label = SendWords.SENDING)
    else -> SendFeedback(snack = SendWords.queued(macName))
}

/**
 * "Mac battery 64%" while connected or paused (as last sent), "—" while it's out of reach, and
 * nothing at all before the Mac has ever sent it.
 */

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
    banner: @Composable () -> Unit,
    /** The sheet of the Mac's resources is open: ask it for a fresh reading. */
    onRequestMac: () -> Unit = {},
) {
    val macLook = mac?.let { Looks.mac(it, look) }
    var macOpen by remember { mutableStateOf(false) }
    if (macOpen) MacSheet(macLook, mac?.status, mac?.at, now, onRequestMac) { macOpen = false }
    BoxWithConstraints(Modifier.fillMaxSize()) {
    // Tall screens: the hero grows a little, and the room left over splits evenly above it and below everything
    val heroSize = (196.dp + (maxHeight - 760.dp) * 0.3f).coerceIn(196.dp, 236.dp)
    val room = constraints.maxHeight
    Layout(
        modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(start = 16.dp, end = 16.dp, bottom = 40.dp),
        content = {
        Column {
        Row(Modifier.fillMaxWidth().height(56.dp).padding(start = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(painterResource(R.drawable.ic_seam_mark), contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(28.dp))
            Spacer(Modifier.width(8.dp))
            Text("Seam", style = MaterialTheme.typography.headlineSmall.copy(letterSpacing = (-0.2).sp), modifier = Modifier.weight(1f))
            IconButton(onClick = onOpenSettings, modifier = Modifier.size(48.dp)) {
                Icon(painterResource(R.drawable.ic_settings), contentDescription = "Settings", tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(22.dp))
            }
        }
        banner()
        }
        Column(Modifier.fillMaxWidth().padding(top = 2.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Hero(look, heroSize)
            Text(
                macName, style = MaterialTheme.typography.displaySmall,
                textAlign = TextAlign.Center, modifier = Modifier.padding(top = 4.dp).semantics { heading() },
            )
            // 24 dp in from the page edge, so a long status wraps to two balanced lines before it runs edge to edge
            Box(Modifier.padding(horizontal = 24.dp)) { StatusChip(look) }
            Meta(macLook, low = macLook?.low == true && look.connected) { macOpen = true }
            if (notice != null) Text(notice, color = MaterialTheme.colorScheme.error, textAlign = TextAlign.Center, modifier = Modifier.padding(bottom = 12.dp))
        }
        val offOnMac = clipboard == ClipboardStatus.OffOnMac
        Box { Actions(look.pausedHere, offOnMac, onSend, onPause, onResume) }
        Box {
            // Why Send is greyed, in words, right under it: centred under the centred buttons, in balanced lines
            Unfold(offOnMac && !look.pausedHere) {
                Text(
                    SendWords.SYNC_OFF_SEND, style = MaterialTheme.typography.bodySmall.copy(lineBreak = LineBreak.Heading),
                    color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().padding(start = 24.dp, end = 24.dp, top = 8.dp),
                )
            }
        }
        Spacer(Modifier.height(18.dp))
        Box { FromMacSlot(fromMac, now, onPaste) }
        Box {
            Section(null) {
                // Paused, the rows say so too: the chip's words, not their usual "On"
                val pause = look.short.takeIf { look.pausedHere }
                if (notifications != null) NotificationsItem(notifications, paused = pause != null, onOpen = onOpenNotifications)
                AutoClipboardItem(clipboard, pause)
            }
        }
        },
    ) { blocks, c ->
        // The room above the hero comes from everything but the footnote and the clip card: what's left splits evenly above and below.
        // Those two only eat the room below, so the hero holds its place as they unfold; past that, the page scrolls.
        val placed = blocks.map { it.measure(c.copy(minHeight = 0, maxHeight = Constraints.Infinity)) }
        val unfolding = placed[3].height + placed[5].height
        val stable = placed.sumOf { it.height } - unfolding
        val free = room - 40.dp.roundToPx() - stable
        val top = (free / 2).coerceAtLeast(0)
        layout(c.maxWidth, maxOf(stable + free, stable + top + unfolding)) {
            var y = 0
            placed.forEachIndexed { i, p -> if (i == 1) y += top; p.place(0, y); y += p.height }
        }
    }
    }
}

/**
 * "● Connected" and the path's icon in a 32 dp chip (at least: two centred lines at large text) with 8 dp of tap area above and below, tertiary while paused; a tap names the path. While connected the dot
 * sends out a ring: 1 → 2.8× while fading from 60%, ease-out over the first 70% of 2.4 s.
 */
/** The status dot's colour: green connected, tertiary paused, grey otherwise; Home's chip and the share sheet. */
@Composable
internal fun statusDot(look: LinkLook): Color {
    val scheme = MaterialTheme.colorScheme
    return pick(look.pausedHere, pick(look.connected, scheme.outline, okColor, Motion.ease(400)), scheme.tertiary, Motion.ease(400))
}

@Composable
internal fun StatusChip(look: LinkLook, explains: Boolean = true, background: Color = MaterialTheme.colorScheme.surfaceContainerHigh, textAlign: TextAlign = TextAlign.Center) {
    val scheme = MaterialTheme.colorScheme
    val bg = pick(look.pausedHere, background, scheme.tertiaryContainer, Motion.ease(400))
    val ink = pick(look.pausedHere, scheme.onSurfaceVariant, scheme.onTertiaryContainer, Motion.ease(400))
    val dot = statusDot(look)
    val ring = remember { Animatable(0f) }
    if (look.connected && !reducedMotion()) LaunchedEffect(Unit) {
        while (true) {
            ring.snapTo(0f)
            ring.animateTo(1f, tween(Motion.PULSE_MS * 7 / 10, easing = Motion.EaseOut))
            delay(Motion.PULSE_MS * 3L / 10)
        }
    } else LaunchedEffect(Unit) { ring.snapTo(0f) }
    val snack = rememberSnack()
    val detail = look.detail?.takeIf { explains } // the share sheet has no snackbar to explain in
    Row(
        // TalkBack reads the whole line, and again when it changes
        Modifier.semantics(mergeDescendants = true) { contentDescription = look.sentence; liveRegion = LiveRegionMode.Polite }
            .tapArea(PaddingValues(vertical = if (explains) 8.dp else 0.dp), RoundedCornerShape(16.dp), "Which connection", detail?.let { { snack(it) } })
            .heightIn(min = 32.dp).background(bg)
            .padding(horizontal = 14.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // The dot sits in the words, so a status that wraps keeps it at the start of the first line
        val dotPlace = with(LocalDensity.current) { Placeholder(15.dp.toSp(), 8.dp.toSp(), PlaceholderVerticalAlign.TextCenter) }
        val dotSlot = mapOf("dot" to InlineTextContent(dotPlace) {
            // The slot is the dot and its 7 dp gap; the dot keeps the slot's start
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.CenterStart) {
                Box(Modifier.size(8.dp).drawBehind {
                    val p = ring.value
                    if (p > 0f) drawCircle(dot.copy(alpha = 0.6f * (1 - p)), radius = size.minDimension / 2 * (1 + 1.8f * p))
                    drawCircle(dot)
                })
            }
        })
        // Start-aligned (the share sheet), a second line starts under the words, not under the dot
        val indent = with(LocalDensity.current) { 15.dp.toSp() }
        val style = MaterialTheme.typography.labelMedium.copy(
            lineBreak = LineBreak.Heading,
            textIndent = if (textAlign == TextAlign.Start) TextIndent(restLine = indent) else null,
        )
        Swap(look.short, Modifier.weight(1f, fill = false)) {
            val words = buildAnnotatedString { appendInlineContent("dot"); append(it) }
            Text(
                words, inlineContent = dotSlot, style = style, color = ink, textAlign = textAlign, maxLines = 2,
                // Two lines hug the longer one, so the chip doesn't stay full width
                modifier = Modifier.hugLines(words, style, 2, listOf(AnnotatedString.Range(dotPlace, 0, 1))),
            )
        }
        val icon = look.icon?.drawable
        if (look.connected && icon != null) {
            Spacer(Modifier.width(6.dp))
            Crossfade(icon, label = "path") { Icon(painterResource(it), null, Modifier.size(textDp(15)), tint = ink) }
        }
    }
}

/**
 * The shield and "Encrypted" (tap: what end-to-end encrypted means), a thin rule, then the Mac's battery and storage as icons
 * with short values and a chevron (tap: everything, in a sheet), no container under the status chip: 14 sp, 14 dp apart, numbers counting in tabular
 * digits, the battery amber at 15% or less while not charging, ending "as of 14:02" while the reading is old, centred under the row (or the stats, stacked) when the line would pass 85% of the width.
 * When it can't fit on one line, "Encrypted" goes centred above the stats, without the rule. Each tap
 * area reaches 48 dp downward, never into the 21 dp between them.
 */
@Composable
private fun Meta(mac: DeviceStatusLook?, low: Boolean, onOpen: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val style = MaterialTheme.typography.labelMedium.copy(fontFeatureSettings = "tnum")
    val snack = rememberSnack()
    val encrypted = encryptedWords(PhoneNames)
    // Two taps on one line: a 1 x 16 dp rule between "Encrypted" and the stats, 10 dp each side, so the chevron reads as the stats' alone.
    // Both reach 16 dp below (to 48 dp), so their centres stay level; not outward, which would cost the old reading's time its room at 360 dp.
    // Too wide for one line (large text), "Encrypted" sits centred over the stats and their chevron, with no rule; the stats never split from each other
    // The old reading's time goes under the stats whenever the whole line would pass 85% of the width, not only once it can't fit
    val measurer = rememberTextMeasurer()
    BoxWithConstraints {
    val density = LocalDensity.current
    val stats = mac?.let { listOfNotNull(it.battery, it.storage).ifEmpty { listOfNotNull(it.cpu) } }.orEmpty()
    // Widths as laid out below: paddings, 15 sp glyphs with 4 dp beside each, 14 dp between stats, the 20 sp chevron
    val (widths, time) = with(density) {
        fun w(text: String) = measurer.measure(text, style).size.width.toDp()
        val glyph = 15.sp.toDp() + 4.dp
        val encryptedW = 12.dp + 10.dp + glyph + w("Encrypted")
        val statsW = 10.dp + 6.dp + 20.sp.toDp() + stats.fold(0.dp) { sum, it -> sum + glyph + w(it) } + 14.dp * (stats.size - 1).coerceAtLeast(0)
        (encryptedW to statsW) to (mac?.meta?.let { 14.dp + w(it) } ?: 0.dp)
    }
    val line = widths.first + 1.dp + widths.second
    val stack = mac != null && line > maxWidth
    // Stacked, the time only has to share the stats' own line
    val wrapTime = mac?.meta != null && mac.stale && (if (stack) widths.second else line) + time > maxWidth * 0.85f
    val encryptedTap: @Composable () -> Unit = {
        Row(
            // Stacked, it reaches 8 dp up and none down, so it sits close over the stats and the gap above stays near 1x
            Modifier.tapArea(if (stack) PaddingValues(top = 8.dp) else PaddingValues(bottom = 16.dp), RoundedCornerShape(16.dp), "What this means") { snack(encrypted) }
                .heightIn(min = 32.dp).padding(start = 12.dp, end = if (mac == null || stack) 12.dp else 10.dp, top = 7.dp, bottom = 7.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(painterResource(R.drawable.ic_shield), null, Modifier.size(textDp(15)), tint = scheme.onSurfaceVariant)
            // TalkBack reads the whole sentence, not just the word
            Text("Encrypted", style = style, color = scheme.onSurfaceVariant, modifier = Modifier.clearAndSetSemantics { contentDescription = encrypted })
        }
    }
    if (mac == null) { encryptedTap(); return@BoxWithConstraints }
    // Out of touch, the last reading says how old it is ("as of 14:02"), on screen and to TalkBack; it keeps onSurfaceVariant,
    // since outline at 14 sp falls under 4.5:1
    val ink = scheme.onSurfaceVariant
    val battery = pick(low, ink, warnColor, Motion.ease(400))
    val storage = pick(mac.storageLow && !mac.stale, ink, warnColor, Motion.ease(400))
    val statsTap: @Composable () -> Unit = {
            Row(
                Modifier.tapArea(PaddingValues(bottom = 16.dp), RoundedCornerShape(16.dp), "Show your Mac's battery, storage, CPU and memory", onOpen)
                    .heightIn(min = 32.dp).padding(start = if (stack) 12.dp else 10.dp, end = 6.dp, top = 7.dp, bottom = 7.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // Weighted so the chevron keeps its room; the stats wrap only when they can't fit at all
                Column(Modifier.weight(1f, fill = false), horizontalAlignment = Alignment.CenterHorizontally) {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(14.dp), itemVerticalAlignment = Alignment.CenterVertically) {
                        mac.battery?.let { MetaStat(mac.batteryIcon?.drawable, it, style, battery) }
                        mac.storage?.let { MetaStat(R.drawable.ic_storage, it, style, storage) }
                        if (mac.battery == null && mac.storage == null) mac.cpu?.let { MetaStat(R.drawable.ic_cpu, it, style, ink) }
                        if (mac.stale && !wrapTime) mac.meta?.let { Text(it, style = style, color = ink) }
                    }
                    // Under the stats, centred like everything else on Home; on one line, it's drawn under the whole row instead and only read here
                    if (wrapTime && stack) Text(mac.meta.orEmpty(), style = style, color = ink)
                    if (wrapTime && !stack) Box(Modifier.semantics { text = AnnotatedString(mac.meta.orEmpty()) })
                }
                // Says the stats open something, now there's no pill behind them; the glyph's own inset makes up the end padding
                // Fixed: the Row squeezes its last child at large text, which shrank the glyph while the stats grew
                // On the stats' first line, not halfway down two: a slot one line tall (at least the 18 dp "Encrypted" centres in), the glyph overhanging it
                val lineHeight = with(LocalDensity.current) { style.lineHeight.toDp() }
                Box(Modifier.align(Alignment.Top).height(maxOf(lineHeight, 18.dp)), contentAlignment = Alignment.Center) {
                    Box(Modifier.requiredSize(textDp(20))) { Chevron(size = textDp(20)) }
                }
            }
    }
    if (stack) Column(horizontalAlignment = Alignment.CenterHorizontally) { encryptedTap(); statsTap() }
    else Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Row(verticalAlignment = Alignment.Top) {
            encryptedTap()
            Box(Modifier.padding(top = 9.dp).size(1.dp, 16.dp).background(scheme.outlineVariant))
            statsTap()
        }
        // Centred under the whole row, not the stats alone; drawn 23 dp up over the taps' padding and reach, snug under the stats, its slot keeping that room above the button as before; and a tap on it opens the stats too
        if (wrapTime) Text(
            mac.meta.orEmpty(), style = style, color = ink,
            modifier = Modifier.clearAndSetSemantics {}.pointerInput(onOpen) { detectTapGestures { onOpen() } }.layout { m, c ->
                val p = m.measure(c)
                val up = 23.dp.roundToPx()
                layout(p.width, p.height) { p.place(0, -up) }
            },
        )
    }
    }
}

@Composable
private fun MetaStat(@DrawableRes icon: Int?, value: String, style: TextStyle, color: Color) {
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
        if (icon != null) Icon(painterResource(icon), null, Modifier.size(textDp(15)), tint = color)
        Counted(value) { Text(it, style = style, color = color) }
    }
}

/** [value] counting its leading number to each new one ([countable]); anything else swaps in place. */
@Composable
internal fun Counted(value: String, modifier: Modifier = Modifier, content: @Composable (String) -> Unit) {
    val count = countable(value)
    if (count == null) Swap(value, modifier, content)
    // A new unit or tail ("999 GB" to "1 TB") starts afresh rather than counting across it
    else key(count.second) { Count(count.first, modifier, format = { "$it${count.second}" }, content = content) }
}

/** [size] sp as dp: an icon beside text grows with the font size. */
@Composable
internal fun textDp(size: Int): Dp = with(LocalDensity.current) { size.sp.toDp() }

/** "64%" → 64 and "%", "140 GB" → 140 and " GB"; null for anything that can't count ("1.5 TB"). */
internal fun countable(value: String): Pair<Int, String>? =
    Regex("""(\d{1,9})([^\d.,].*)?""").matchEntire(value)?.let { it.groupValues[1].toInt() to it.groupValues[2] }

/** A tap area padded out by [hit] beyond the visible part, whose ripple stays on [shape]; no tap when [onClick] is null. */
@Composable
private fun Modifier.tapArea(hit: PaddingValues, shape: Shape, label: String, onClick: (() -> Unit)?): Modifier {
    val press = remember { MutableInteractionSource() }
    val tap = if (onClick == null) Modifier else Modifier.clickable(press, null, onClickLabel = label, onClick = onClick)
    return then(tap).padding(hit).clip(shape).indication(press, ripple())
}

/** The Mac's battery (with its power and time left or to full), storage, CPU and memory in full, asked for afresh every 10 s while it's on screen. */
@Composable
private fun MacSheet(mac: DeviceStatusLook?, status: Status?, at: Long?, now: Long, onRequest: () -> Unit, onDismiss: () -> Unit) {
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(lifecycle) {
        // Only while Seam is in view: a sheet left open behind another app asks for nothing
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (true) {
                onRequest()
                delay(10_000)
            }
        }
    }
    val scheme = MaterialTheme.colorScheme
    // Not the Mac's name: it's already in large type just behind the sheet
    SeamSheet("Mac status", at?.let { "Updated ${Looks.ago(it, now)}" }, onDismiss) {
        // Values to read, not buttons: one block, rows split by thin rules rather than each on its own tappable-looking surface
        val rows = buildList<@Composable () -> Unit> {
            // "64% · Charging at 30 W · full in 1 hr 10 min" when the Mac sends its power, else "64%, charging"
            mac?.batteryDetail?.let { detail ->
                val line = mac.power?.let { listOfNotNull(mac.battery, it).joinToString(" · ") } ?: detail
                add { Gauge(mac.percent.toInt() / 100f, if (mac.low) warnColor else scheme.primary) { Item("Battery", line, start = readOnly(mac.batteryIcon?.drawable ?: R.drawable.ic_battery_100), vertical = 8.dp, countSubtitle = true) } }
            }
            mac?.storageDetail?.let {
                add { Gauge(status?.run { 1f - storageFreeBytes.toFloat() / storageTotalBytes }, if (mac.storageLow) warnColor else scheme.primary) { Item("Storage", it, start = readOnly(R.drawable.ic_storage), vertical = 8.dp, countSubtitle = true) } }
            }
            mac?.cpu?.let {
                add { Gauge(status?.cpuPct?.let { pct -> pct / 100f }) { Item("CPU", "$it in use", start = readOnly(R.drawable.ic_cpu), vertical = 8.dp, countSubtitle = true) } }
            }
            mac?.memoryDetail?.let {
                add { Gauge(status?.run { memoryUsedBytes.toFloat() / memoryTotalBytes }) { Item("Memory", it, start = readOnly(R.drawable.ic_memory), vertical = 8.dp, countSubtitle = true) } }
            }
            if (mac?.cpu == null) add { Item("CPU and memory", "Update Seam on your Mac to see them", start = readOnly(R.drawable.ic_cpu), vertical = 8.dp) }
        }
        Column(Modifier.clip(RoundedCornerShape(24.dp)).background(rowColor())) {
            rows.forEachIndexed { i, row ->
                // Inset to the words, as the gauges are
                if (i > 0) HorizontalDivider(Modifier.padding(start = 54.dp, end = 16.dp), color = scheme.outlineVariant)
                row()
            }
        }
    }
}

/** A Mac status row's icon: plain, no disc, so the rows read as values to look at rather than buttons. */
private fun readOnly(@DrawableRes icon: Int): @Composable () -> Unit = {
    Icon(painterResource(icon), null, Modifier.size(24.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
}

/**
 * A row of the Mac's sheet with M3's 4 dp linear indicator under its words (round ends, a gap, the stop dot),
 * filling to [fraction] (used, or charged) on `settle` when the sheet opens. The bar only draws: the row's words already say the value.
 */
@Composable
private fun Gauge(fraction: Float?, color: Color = MaterialTheme.colorScheme.primary, item: @Composable () -> Unit) {
    val target = fraction?.takeIf { it.isFinite() }?.coerceIn(0f, 1f)
    val fill = remember { Animatable(0f) }
    val reduced = reducedMotion()
    LaunchedEffect(target) { if (target != null) { if (reduced) fill.snapTo(target) else fill.animateTo(target, Motion.settle()) } }
    Column {
        item()
        if (target != null) LinearProgressIndicator(
            progress = { fill.value },
            // Under the words: past the row's 16 dp, the 24 dp icon and its 14 dp gap
            // 16 dp below: about the space above the row's title, so the block's last gauge doesn't sit tighter than its first title
            modifier = Modifier.padding(start = 54.dp, end = 16.dp, bottom = 16.dp).fillMaxWidth().clearAndSetSemantics {},
            color = color,
            trackColor = wellColor(),
            strokeCap = StrokeCap.Round,
        )
    }
}

/**
 * Send clipboard and Pause as a button group, 6 dp apart, at 4 : 1. Pressing one widens it
 * (the other's share drops to 0.8, or Send's to 3.2) on the spring (0.45 s), and its corners
 * tighten from 30 to 16 dp (0.35 s). Paused, Pause and the gap shrink to nothing as Pause fades (0.3 s), and
 * Send becomes Resume.
 */
@Composable
private fun Actions(paused: Boolean, offOnMac: Boolean, onSend: () -> SendFeedback, onPause: (PauseFor) -> Unit, onResume: () -> Unit) {
    val snack = rememberSnack()
    val haptic = LocalHapticFeedback.current
    var confirm by remember { mutableStateOf<SendFeedback?>(null) }
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
    val label = when { paused -> "Resume"; else -> confirm?.label ?: "Send clipboard" }
    // Green only when it really went ("Sending" isn't a success yet, SR-8-04); greyed while the Mac has clipboard sync off
    val enabled = paused || !offOnMac
    val done = !paused && confirm?.done == true
    val container = pick(done, pick(enabled, scheme.onSurface.copy(alpha = 0.1f), scheme.primary, Motion.ease(300)), okColor, Motion.ease(300))
    val content = pick(enabled, scheme.onSurface.copy(alpha = 0.38f), scheme.onPrimary, Motion.ease(300))
    BoxWithConstraints(Modifier.fillMaxWidth()) {
    // The gap folds with Pause, so Resume reaches the edge
    val gap = pausePad / 2
    val free = maxWidth.value - gap - 12f - pausePad
    val sendWidth = 12f + free * sendGrow / (sendGrow + pauseGrow)
    val pauseWidth = pausePad + free * pauseGrow / (sendGrow + pauseGrow)
    ButtonGroup(overflowIndicator = {}, modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(gap.dp)) {
        customItem({
            Button(
                onClick = {
                    if (paused) { onResume(); haptic.performHapticFeedback(HapticFeedbackType.Confirm); snack("Seam is back on") }
                    else onSend().let { f ->
                        if (f.label != null) confirm = f
                        if (f.done) haptic.performHapticFeedback(HapticFeedbackType.Confirm)
                        f.snack?.let(snack)
                    }
                },
                enabled = enabled,
                shape = RoundedCornerShape(sendCorner),
                colors = ButtonDefaults.buttonColors(container, content, container, content),
                interactionSource = sendSource,
                contentPadding = PaddingValues(horizontal = 16.dp),
                modifier = Modifier.weight(sendWidth).height(60.dp),
            ) {
                Icon(painterResource(if (paused) R.drawable.ic_play else R.drawable.ic_copy), contentDescription = null, modifier = Modifier.size(textDp(20)))
                Spacer(Modifier.width(9.dp))
                // Very large text steps down to fit one line, so the 4 : 1 group keeps its height
                Swap(label) { Text(it, style = MaterialTheme.typography.labelLarge, maxLines = 1, autoSize = TextAutoSize.StepBased(minFontSize = 12.sp, maxFontSize = MaterialTheme.typography.labelLarge.fontSize)) }
            }
        }, {})
        customItem({
            Button(
                onClick = { choosing = true },
                enabled = !paused,
                shape = RoundedCornerShape(pauseCorner),
                colors = ButtonDefaults.buttonColors(scheme.primaryContainer, scheme.onPrimaryContainer, scheme.primaryContainer, scheme.onPrimaryContainer),
                interactionSource = pauseSource,
                contentPadding = PaddingValues(0.dp),
                modifier = Modifier.weight(pauseWidth.coerceAtLeast(0.001f)).height(60.dp).alpha(pauseAlpha)
                    .then(if (paused) Modifier.clearAndSetSemantics {} else Modifier),
            ) { Icon(painterResource(R.drawable.ic_pause), contentDescription = "Pause", modifier = Modifier.size(textDp(20))) }
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
    val haptic = LocalHapticFeedback.current
    var pasted by remember { mutableStateOf<Long?>(null) }
    var gone by remember { mutableStateOf<Long?>(null) }
    val shown = fromMac?.takeIf { it.fresh(now) && it.at != gone }
    val last = remember { mutableStateOf(shown) }
    if (shown != null) last.value = shown
    LaunchedEffect(pasted) { pasted?.let { delay(900); gone = it } }
    // Always there, so TalkBack hears the card arrive; it reads the copy, not the ticking "just now"
    Box(Modifier.semantics(mergeDescendants = true) {
        liveRegion = LiveRegionMode.Polite
        if (shown != null) contentDescription = "Copied on your Mac: ${shown.text}"
    }) {
    Unfold(shown != null) {
        val clip = last.value ?: return@Unfold
        val done = pasted == clip.at
        Card("Copied on your Mac · ${Looks.ago(clip.at, now)}", clip.text, MaterialTheme.colorScheme.secondaryContainer, MaterialTheme.colorScheme.onSecondaryContainer) {
            Pill(if (done) "✓ Pasted" else "Paste", { if (onPaste(clip.text)) { pasted = clip.at } else snack("Couldn't put it on the clipboard") }, if (done) PillKind.Done else PillKind.OnCard, enabled = !done)
        }
    }
    }
}

/** Home's cards: secondaryContainer, set apart from the list's grey rows, with an [PillKind.OnCard] action; 28 dp corners, a 12 sp sentence-case kicker, one 15 sp line, an action at the end, anything under them. */
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
                // Untracked, as the small print around it: labelSmall's tracking read as a different voice
                Text(title, style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Medium), color = kicker)
                Text(value, style = MaterialTheme.typography.bodyLarge, color = ink, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 3.dp))
            }
            action()
        }
        below?.invoke()
    }
}
