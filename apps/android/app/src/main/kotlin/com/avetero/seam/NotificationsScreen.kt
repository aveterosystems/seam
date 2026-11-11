// SPDX-License-Identifier: AGPL-3.0-only
package com.avetero.seam

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialExpressiveTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.toggleableState
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.style.LineBreak
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import android.util.LruCache
import androidx.core.graphics.drawable.toBitmap
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.avetero.seam.crypto.notificationModeExplanation
import com.avetero.seam.crypto.notificationModeWord
import com.avetero.seam.crypto.notificationsPhoneCount
import com.avetero.seam.crypto.senderOnlyFootnote
import com.avetero.seam.feature.notifications.AppEntry
import com.avetero.seam.feature.notifications.AppMode
import com.avetero.seam.feature.notifications.NotificationPrefs
import com.avetero.seam.feature.notifications.NotificationsFeature
import com.avetero.seam.feature.notifications.toProto
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

// The words are the shared looks' (seam-looks), from the settings as the phone sends them
private fun NotificationPrefs.bytes() = toProto { null }.toByteArray()

/** The feature's one line, on Home, in Settings and on its own card: "On · 6 of 10 apps", "Paused · 6 of 10 apps" while the phone is paused, or "Off". */
fun notificationsLine(prefs: NotificationPrefs, paused: Boolean = false): String =
    if (prefs.enabled) "${if (paused) "Paused" else AppMode.On.label} · ${notificationsPhoneCount(prefs.bytes())}" else AppMode.Off.label

// A no-break space, so "Sender only" never wraps mid-name inside a sentence; the toolbar's own label may wrap
private fun String.unbroken() = replace("Sender only", "Sender\u00A0only")

internal val AppMode.label: String get() = notificationModeWord(toProto().number)

/** The Mac has paused notifications: true until [NotificationPrefs.pausedUntil], then false at that moment, so an open screen updates. */
@Composable
internal fun rememberNotificationsPaused(prefs: NotificationPrefs): Boolean {
    val until = prefs.pausedUntil
    var paused by remember(until) { mutableStateOf(prefs.paused(System.currentTimeMillis())) }
    LaunchedEffect(until) {
        delay(until - System.currentTimeMillis())
        paused = false
    }
    return paused
}

/** Settings › Mac notifications: what reaches the Mac, app by app. The Mac's pane edits the same settings. */
@Composable
fun NotificationsScreen(feature: NotificationsFeature, onBack: () -> Unit) {
    val prefs by feature.prefs.collectAsStateWithLifecycle()
    val snacks = LocalSnacks.current
    var query by remember { mutableStateOf("") }
    var selecting by remember { mutableStateOf(false) }
    var selected by remember { mutableStateOf(setOf<String>()) }
    var open by remember { mutableStateOf<String?>(null) }
    val access = rememberNotificationAccess()
    val macPaused = rememberNotificationsPaused(prefs)
    fun matches(a: AppEntry) = query.isBlank() || a.name.contains(query.trim(), ignoreCase = true)
    fun stopSelecting() {
        selecting = false
        selected = emptySet()
    }
    BackHandler(enabled = selecting, onBack = ::stopSelecting)
    // Without access nothing can go over, whatever the switch was left at
    val on = prefs.enabled && access
    LaunchedEffect(on) { if (!on) stopSelecting() }
    // Off, the rest stays visible but can't be changed; the card's switch says why
    val dim by animateFloatAsState(if (on) 1f else 0.4f, Motion.ease(400), label = "dim")
    val dimmed = Modifier.graphicsLayer { alpha = dim }
    // While picking, only the list is live: everything above it dims too, the contextual bar saying why
    val live = on && !selecting
    // Light's mint card loses its words at 40%, so picking dims a little less there
    val pickDim = if (MaterialTheme.colorScheme.surface.luminance() > 0.5f) 0.55f else 0.4f
    val still by animateFloatAsState(if (live) 1f else if (selecting) pickDim else 0.4f, Motion.ease(400), label = "still")
    val stilled = Modifier.graphicsLayer { alpha = still }
    val picking by animateFloatAsState(if (selecting) pickDim else 1f, Motion.ease(400), label = "picking")
    // Without access the card's title and switch dim, but its reason stays readable
    val locked by animateFloatAsState(if (access) 1f else 0.4f, Motion.ease(400), label = "locked")
    val haptic = LocalHapticFeedback.current
    // The app rows sit 2 dp apart as one group; everything else keeps the page's 18 dp
    val apart = Modifier.padding(top = 16.dp)
    // Without access everything under the card goes: the access row says why
    val shown = if (access) prefs.apps.filter(::matches) else emptyList()
    val ids = shown.map { it.id }.toSet()
    // Rows filtered out stay drawn for their 420 ms exit (catalogue 6), then drop
    var drawn by remember { mutableStateOf(ids) }
    LaunchedEffect(ids) { delay(420); drawn = ids }
    val reduced = reducedMotion()
    val density = LocalDensity.current
    val slide = with(density) { 30.dp.roundToPx() }
    // The toolbar as drawn, its 28 dp float included (0 once it's gone), so the last rows can scroll clear of it at any text size.
    // The page already sits above the navigation bar (safeDrawingPadding), so its inset isn't added again
    var toolbar by remember { mutableIntStateOf(0) }
    val room by animateDpAsState(if (selecting) with(density) { toolbar.toDp() } else 0.dp, Motion.spring(500), label = "room")
    LazySubScreen(
        "Mac notifications", onBack, gap = 2.dp,
        selection = if (selecting) Selection(selected.size, ::stopSelecting) else null,
        actions = {
            if (selecting) {
                val all = ids.isNotEmpty() && selected.containsAll(ids)
                Pill(if (all) "None" else "All", { selected = if (all) selected - ids else selected + ids }, PillKind.Text, modifier = Modifier.padding(end = 6.dp))
            } else if (prefs.apps.isNotEmpty() && on) {
                Pill("Select", { selecting = true }, PillKind.Text, modifier = Modifier.padding(end = 6.dp))
            }
        },
        overlay = {
            BulkToolbar(selecting, selected.size, Modifier.align(Alignment.BottomCenter).onSizeChanged { toolbar = it.height }) { mode ->
                // Each picked app's mode before, for Undo
                val before = prefs.apps.filter { it.id in selected }.groupBy({ it.mode }, { it.id })
                feature.setMode(selected, mode)
                haptic.performHapticFeedback(HapticFeedbackType.Confirm)
                snacks.show("${selected.size} ${if (selected.size == 1) "app" else "apps"} set to ${mode.label.unbroken()}", "Undo") {
                    before.forEach { (m, ids) -> feature.setMode(ids.toSet(), m) }
                }
                stopSelecting()
            }
        },
    ) {
        if (!access) item("access") { Box(apart) { Section(null) { NotificationAccessItem(access) } } }
        item("master") {
            Box(apart.graphicsLayer { alpha = picking }) {
                MasterCard(on, if (access) notificationsLine(prefs, macPaused) else "Turn on access above first", access && !selecting, { locked }, feature::setEnabled,
                    // Light, picking: the card eases to the surface so only the toolbar stays mint; dark's dim already does it
                    quiet = selecting && MaterialTheme.colorScheme.surface.luminance() > 0.5f)
            }
        }
        if (!access) item("why") {
            EmptyState(
                R.drawable.ic_visibility_off, "Seam can't see your notifications yet",
                "Seam only reads notifications to show them on your Mac. Nothing is kept.",
                // Close under the card, so the card and its reason read as one
                Modifier.fillMaxWidth().padding(top = 24.dp),
            )
        }
        if (on && macPaused) item("paused") {
            val until = SystemTime().time(prefs.pausedUntil)
            Box(apart.then(stilled)) {
                Section(null) {
                    Item("Paused until $until", "Paused from your Mac. Nothing goes over until then") {
                        Pill("Resume", { feature.pause(0) }, enabled = !selecting)
                    }
                }
            }
        }
        item("switches") {
            Unfold(access) {
                Box(apart.then(stilled)) {
                    Section(null) {
                        SwitchItem("Dismiss on both", "Clear on one, gone on both", prefs.dismissBoth, feature::setDismissBoth, lead = painterResource(R.drawable.ic_dismiss_both), enabled = live)
                        SwitchItem("Quiet while in use", "Silent on your Mac while you use this phone", prefs.quietWhileUsingPhone, feature::setQuietWhileUsingPhone, lead = painterResource(R.drawable.ic_bedtime), enabled = live)
                    }
                }
            }
        }
        if (prefs.apps.isEmpty()) item("empty") {
            Unfold(access) { Box(apart.then(dimmed)) { Section(null) { Item("No apps yet", "Apps that can notify show here once notification access is on") } } }
        } else item("search") {
            Unfold(access) { Box(apart.then(stilled).padding(bottom = 10.dp)) { SearchField(query, enabled = live) { query = it } } }
        }
        // Each row is its own item, so a long list composes only what's on screen; filtering moves them (catalogue 5, 6)
        items(prefs.apps.filter { it.id in ids || it.id in drawn }, key = { it.id }) { a ->
            val outer = 24.dp
            val first = a.id == shown.firstOrNull()?.id
            val last = a.id == shown.lastOrNull()?.id
            val shape = RoundedCornerShape(
                topStart = if (first) outer else 4.dp, topEnd = if (first) outer else 4.dp,
                bottomStart = if (last) outer else 4.dp, bottomEnd = if (last) outer else 4.dp,
            )
            val picked = a.id in selected
            AnimatedVisibility(
                a.id in ids,
                Modifier.animateItem(fadeInSpec = Motion.ease(250), placementSpec = Motion.spring(400), fadeOutSpec = Motion.ease(250)),
                enter = if (reduced) fadeIn(Motion.ease(250)) else expandVertically(Motion.spring(420)) + fadeIn(Motion.ease(250)),
                exit = if (reduced) fadeOut(Motion.ease(250))
                else slideOutHorizontally(Motion.spring(420)) { slide } + fadeOut(Motion.ease(250)) + shrinkVertically(Motion.spring(420)),
            ) {
                Box(dimmed.clip(shape)) {
                    val toggle = { selected = if (picked) selected - a.id else selected + a.id }
                    Item(
                        a.name, vertical = 10.dp, selected = picked,
                        onClick = if (!on) null else ({ if (selecting) toggle() else open = a.id }),
                        onLongClick = if (!on) null else ({ selecting = true; toggle() }),
                        role = if (selecting) Role.Checkbox else Role.Button,
                        start = { AppLead(a.id, picked, selecting) },
                    ) { ModeChip(a.mode, picked) }
                }
            }
        }
        if (access && prefs.apps.isNotEmpty() && shown.isEmpty()) item("none") {
            Box(dimmed) { Section(null) { Item("No apps match “${query.trim()}”") } }
        }
        item("footnote") { Unfold(access) { Box(Modifier.padding(top = 6.dp).then(dimmed)) { Footnote(senderOnlyFootnote().unbroken() + " New apps start off.") } } }
        item("room") { Spacer(Modifier.height(room)) }
    }
    prefs.apps.firstOrNull { it.id == open }?.let { a ->
        SeamSheet(a.name, "What reaches your Mac", onDismiss = { open = null }, lead = { SheetAppIcon(a.id) }) { close ->
            Choices(AppMode.entries, a.mode, { it.label }, { it.explain(a.canReply) }) {
                feature.setMode(setOf(a.id), it)
                close()
            }
        }
    }
}

/** "Show on your Mac": the feature's main switch, a big primaryContainer card while on. */
@Composable
private fun MasterCard(on: Boolean, summary: String, enabled: Boolean, locked: () -> Float, onChange: (Boolean) -> Unit, quiet: Boolean = false) {
    val scheme = MaterialTheme.colorScheme
    val bg = pick(on && !quiet, scheme.surfaceContainerHigh, scheme.primaryContainer, Motion.ease(400))
    val ink = pick(on && !quiet, scheme.onSurface, scheme.onPrimaryContainer, Motion.ease(400))
    val haptic = LocalHapticFeedback.current
    Row(
        Modifier.fillMaxWidth().background(bg, RoundedCornerShape(28.dp))
            .clip(RoundedCornerShape(28.dp)).toggleable(value = on, enabled = enabled, role = Role.Switch) {
                haptic.performHapticFeedback(if (it) HapticFeedbackType.ToggleOn else HapticFeedbackType.ToggleOff); onChange(it)
            }
            // The same 16 dp start as every row below it
            .padding(horizontal = 16.dp, vertical = 20.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text("Show on your Mac", style = MaterialTheme.typography.titleLarge, color = ink, modifier = Modifier.graphicsLayer { alpha = locked() })
            Swap(summary) {
                Text(it, color = ink.copy(alpha = 0.8f), style = MaterialTheme.typography.bodyMedium.copy(fontFeatureSettings = "tnum"))
            }
        }
        // Material's own disabled-off colours, composited over this card rather than the page (its default), so the outline shows
        MaterialExpressiveTheme(colorScheme = MaterialTheme.colorScheme.copy(surface = bg)) { SeamSwitch(on, null, enabled) }
    }
}

/**
 * A mode's colours, background then ink, for the row's chip: On teal, Sender only outlined (no fill, so it can't pass for On's mint), Off grey (surfaceDim in light). Tertiary is Paused's.
 * On a picked row (secondaryContainer) the fills would sink into the tint, so each goes a plain surface with its colour in the words
 * instead: surfaceContainerLowest (white) in light, surfaceContainerHighest in dark, where the near-black Lowest read as a hole.
 */
@Composable
private fun modeColors(mode: AppMode, picked: Boolean): Pair<Color, Color> {
    val s = MaterialTheme.colorScheme
    val spec = Motion.ease<Float>(300)
    val on = mode == AppMode.On
    val title = mode == AppMode.TitleOnly
    // Light's Highest sat 1.06:1 on its row; Dim reads as a chip there
    val off = if (s.surface.luminance() > 0.5f) s.surfaceDim else s.surfaceContainerHighest
    val bg = pick(title, pick(on, off, s.primaryContainer, spec), s.surface.copy(alpha = 0f), spec)
    val ink = pick(title, pick(on, s.onSurfaceVariant, s.onPrimaryContainer, spec), s.onSurfaceVariant, spec)
    val accent = pick(title, pick(on, s.onSurfaceVariant, s.primary, spec), s.secondary, spec)
    // Final decision (round 13), don't flip: picked chips are Lowest in light, Highest in dark, coloured words in both
    val plain = if (s.surface.luminance() > 0.5f) s.surfaceContainerLowest else s.surfaceContainerHighest
    return pick(picked, bg, plain, spec) to pick(picked, ink, accent, spec)
}

@Composable
private fun ModeChip(mode: AppMode, picked: Boolean) {
    val (bg, ink) = modeColors(mode, picked)
    val edge = pick(mode == AppMode.TitleOnly, MaterialTheme.colorScheme.outline.copy(alpha = 0f), MaterialTheme.colorScheme.outline, Motion.ease(300))
    Box(Modifier.border(1.dp, edge, CircleShape)) { Chip(mode.label, bg, ink, minText = AppMode.TitleOnly.label) }
}

/**
 * An app row's lead: the app's own icon (a generic app glyph until it loads or if it has none, never Seam's own mark). Picked while selecting, a
 * filled teal tick takes its place, the icon shrinking away as the tick springs in (0.3 s), as Gmail and Files do; with
 * animations off they only crossfade. TalkBack reads it as a checkbox while selecting.
 */
@Composable
private fun AppLead(pkg: String, picked: Boolean, selecting: Boolean) {
    val s = MaterialTheme.colorScheme
    val icon = appIcon(pkg)
    val reduced = reducedMotion()
    val tick by animateFloatAsState(if (picked) 1f else 0f, Motion.spring(300), label = "tick")
    val fade by animateFloatAsState(if (picked) 1f else 0f, Motion.ease(300), label = "fade")
    val grow = { t: Float -> if (reduced) 1f else 0.6f + 0.4f * t }
    Box(
        Modifier.size(40.dp).semantics { if (selecting) toggleableState = ToggleableState(picked) },
        contentAlignment = Alignment.Center,
    ) {
        val face = Modifier.size(40.dp).graphicsLayer { alpha = 1f - fade; scaleX = grow(1f - tick); scaleY = scaleX }
        if (icon != null) Image(icon, contentDescription = null, modifier = face.clip(RoundedCornerShape(12.dp)))
        else Box(face.background(wellColor(), CircleShape), contentAlignment = Alignment.Center) {
            Icon(painterResource(R.drawable.ic_app), contentDescription = null, tint = s.onSurfaceVariant, modifier = Modifier.size(20.dp))
        }
        if (picked || fade > 0f) Box(
            Modifier.size(40.dp).graphicsLayer { alpha = fade; scaleX = grow(tick); scaleY = scaleX }.background(s.primary, CircleShape),
            contentAlignment = Alignment.Center,
        ) { Icon(Icons.Filled.Check, contentDescription = null, tint = s.onPrimary, modifier = Modifier.size(22.dp)) }
    }
}

/** The app's icon before its sheet's title, as its row shows it, at 24 dp. */
@Composable
private fun SheetAppIcon(pkg: String) {
    val icon = appIcon(pkg)
    if (icon != null) Image(icon, contentDescription = null, modifier = Modifier.size(24.dp).clip(RoundedCornerShape(7.dp)))
    else Icon(painterResource(R.drawable.ic_app), contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(24.dp))
}

/** The app list's filter: a filled pill that draws a teal ring while typing. */
@Composable
private fun SearchField(query: String, enabled: Boolean, onChange: (String) -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val source = remember { MutableInteractionSource() }
    val focused by source.collectIsFocusedAsState()
    val ring = pick(focused, scheme.surfaceContainerHigh, scheme.primary, Motion.ease(200))
    BasicTextField(
        query, onChange, enabled = enabled, singleLine = true, interactionSource = source,
        textStyle = MaterialTheme.typography.bodyLarge.copy(color = scheme.onSurface),
        cursorBrush = SolidColor(scheme.primary),
        modifier = Modifier.fillMaxWidth(),
        decorationBox = { field ->
            Row(
                Modifier.fillMaxWidth().height(52.dp).background(scheme.surfaceContainerHigh, RoundedCornerShape(26.dp))
                    .border(2.dp, ring, RoundedCornerShape(26.dp)).padding(horizontal = 18.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(painterResource(R.drawable.ic_search), contentDescription = null, tint = scheme.onSurfaceVariant, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(10.dp))
                Box(Modifier.weight(1f)) {
                    if (query.isEmpty()) Text("Search apps", style = MaterialTheme.typography.bodyLarge, color = scheme.onSurfaceVariant)
                    field()
                }
            }
        },
    )
}

private fun AppMode.explain(canReply: Boolean) = notificationModeExplanation(toProto().number, canReply, PhoneNames)

// Decoded icons by package, so a row scrolled back into view draws its icon at once rather than the placeholder
private val icons = LruCache<String, ImageBitmap>(200)

/** The app's own icon, from the cache or decoded off the main thread; null while it loads, or if the app has none. */
@Composable
private fun appIcon(pkg: String): Painter? {
    val context = LocalContext.current
    val bitmap = produceState(icons.get(pkg), pkg) {
        value = icons.get(pkg) ?: withContext(Dispatchers.IO) {
            runCatching { context.packageManager.getApplicationIcon(pkg).toBitmap(120, 120).asImageBitmap() }.getOrNull()
        }?.also { icons.put(pkg, it) }
    }.value
    return remember(bitmap) { bitmap?.let(::BitmapPainter) }
}

/** The row that opens this screen, on Home and in Settings: what reaches the Mac, in one line. One name for the feature everywhere. */
@Composable
internal fun NotificationsItem(feature: NotificationsFeature, title: String = "Mac notifications", paused: Boolean = false, onOpen: () -> Unit) {
    val prefs by feature.prefs.collectAsStateWithLifecycle()
    // Access revoked in Android's settings: the switch may still say on, but nothing can go over
    val line = if (prefs.enabled && !rememberNotificationAccess()) "Needs notification access"
        else notificationsLine(prefs, paused || rememberNotificationsPaused(prefs))
    Item(title, line, lead = painterResource(R.drawable.ic_bell), onClick = onOpen) {
        Chevron()
    }
}
