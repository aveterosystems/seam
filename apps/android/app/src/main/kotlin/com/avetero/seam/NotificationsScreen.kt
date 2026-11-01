// SPDX-License-Identifier: AGPL-3.0-only
package com.avetero.seam

import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.selection.toggleable
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.graphics.drawable.toBitmap
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.avetero.seam.feature.notifications.AppEntry
import com.avetero.seam.feature.notifications.AppMode
import com.avetero.seam.feature.notifications.NotificationsFeature
import java.text.DateFormat
import java.util.Date

/** Home's line: "On · 6 apps", or "Off". */
fun notificationsShort(enabled: Boolean, apps: List<AppEntry>): String =
    if (enabled) "On · ${apps.count { it.mode != AppMode.Off }} apps" else "Off"

/** Settings' line: "6 of 10 apps · Title only for Monzo", the apps named when there are one or two. */
fun notificationsLong(enabled: Boolean, apps: List<AppEntry>): String {
    val titleOnly = apps.filter { it.mode == AppMode.TitleOnly }.map { it.name }
    val names = when (titleOnly.size) {
        0 -> null
        1, 2 -> "Title only for " + titleOnly.joinToString(" and ")
        else -> "Title only for ${titleOnly.size} apps"
    }
    return listOfNotNull("Off".takeIf { !enabled }, appCount(apps), names).joinToString(" · ")
}

/** "6 of 10 apps": the master card's line. */
fun appCount(apps: List<AppEntry>) = "${apps.count { it.mode != AppMode.Off }} of ${apps.size} apps"

internal val AppMode.label get() = when (this) {
    AppMode.On -> "On"
    AppMode.TitleOnly -> "Title only"
    AppMode.Off -> "Off"
}

/** Settings › Notifications: what reaches the Mac, app by app. The Mac's pane edits the same settings. */
@Composable
fun NotificationsScreen(feature: NotificationsFeature, onBack: () -> Unit) {
    val prefs by feature.prefs.collectAsStateWithLifecycle()
    val snack = rememberSnack()
    var query by remember { mutableStateOf("") }
    var selecting by remember { mutableStateOf(false) }
    var selected by remember { mutableStateOf(setOf<String>()) }
    var open by remember { mutableStateOf<String?>(null) }
    val access = rememberNotificationAccess()
    fun matches(a: AppEntry) = query.isBlank() || a.name.contains(query.trim(), ignoreCase = true)
    fun stopSelecting() {
        selecting = false
        selected = emptySet()
    }
    BackHandler(enabled = selecting, onBack = ::stopSelecting)
    SubScreen(
        "Notifications", onBack,
        actions = {
            if (prefs.apps.isNotEmpty()) {
                if (selecting) {
                    val shown = prefs.apps.filter(::matches).map { it.id }.toSet()
                    val all = shown.isNotEmpty() && selected.containsAll(shown)
                    Pill(if (all) "None" else "All", { selected = if (all) selected - shown else selected + shown }, PillKind.Text)
                }
                Pill(if (selecting) "Done" else "Select", { if (selecting) stopSelecting() else selecting = true }, PillKind.Text, modifier = Modifier.padding(end = 6.dp))
            }
        },
        overlay = {
            BulkToolbar(selecting, selected.size, Modifier.align(Alignment.BottomCenter), onClose = ::stopSelecting) { mode ->
                if (selected.isEmpty()) return@BulkToolbar snack("Tap apps to select them")
                feature.setMode(selected, mode)
                snack("${selected.size} ${if (selected.size == 1) "app" else "apps"} set to ${mode.label}")
                stopSelecting()
            }
        },
    ) {
        if (!access) Section(null) { NotificationAccessItem(access) }
        Column(Modifier.padding(top = 4.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        MasterCard(prefs.enabled, appCount(prefs.apps), feature::setEnabled)
        // Off, the rest stays visible but can't be changed; the card's switch says why
        val dim by animateFloatAsState(if (prefs.enabled) 1f else 0.4f, Motion.ease(400), label = "dim")
        Column(Modifier.alpha(dim), verticalArrangement = Arrangement.spacedBy(18.dp)) {
            val on = prefs.enabled
            if (on && prefs.paused(System.currentTimeMillis())) {
                val until = DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(prefs.pausedUntil))
                Section(null) {
                    Item("Paused until $until", "Paused from your Mac. Nothing goes over until then") {
                        Pill("Resume", { feature.pause(0) })
                    }
                }
            }
            Section(null) {
                SwitchItem("Dismiss on both", "Clear one here or there, it's gone from both", prefs.dismissBoth, feature::setDismissBoth, enabled = on)
                SwitchItem("Quiet while I'm using this phone", "Your Mac stays quiet while it's unlocked in your hand", prefs.quietWhileUsingPhone, feature::setQuietWhileUsingPhone, enabled = on)
            }
            Section(
                null, footnote = "Title only shows who it's from, never what it says. New apps start off.",
                above = if (prefs.apps.isEmpty()) null else ({ SearchField(query) { query = it } }),
            ) {
                if (prefs.apps.isEmpty()) Item("No apps yet", "Apps that can notify show here once notification access is on")
                prefs.apps.forEach { a ->
                    AnimatedVisibility(
                        matches(a),
                        enter = expandVertically(Motion.spring(400)) + fadeIn(Motion.ease(250)),
                        exit = shrinkVertically(Motion.spring(400)) + fadeOut(Motion.ease(250)),
                    ) {
                        val picked = a.id in selected
                        val toggle = { selected = if (picked) selected - a.id else selected + a.id }
                        Item(
                            a.name, lead = appIcon(a.id), leadIsImage = true, selected = picked, vertical = 10.dp,
                            onClick = if (!on) null else ({ if (selecting) toggle() else open = a.id }),
                            onLongClick = if (!on) null else ({ selecting = true; toggle() }),
                        ) { ModeChip(a.mode) }
                    }
                }
            }
            Spacer(Modifier.height(60.dp)) // room to scroll the last rows above the toolbar
        }
        }
    }
    prefs.apps.firstOrNull { it.id == open }?.let { a ->
        SeamSheet(a.name, "What reaches your Mac", onDismiss = { open = null }) { close ->
            Choices(AppMode.entries, a.mode, { it.label }, { it.explain(a.canReply) }) {
                feature.setMode(setOf(a.id), it)
                close()
            }
        }
    }
}

/** "Show on your Mac": the feature's main switch, a big primaryContainer card while on. */
@Composable
private fun MasterCard(on: Boolean, summary: String, onChange: (Boolean) -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val bg = pick(on, scheme.surfaceContainerHigh, scheme.primaryContainer, Motion.ease(400))
    val ink = pick(on, scheme.onSurface, scheme.onPrimaryContainer, Motion.ease(400))
    Row(
        Modifier.fillMaxWidth().background(bg, RoundedCornerShape(28.dp))
            .clip(RoundedCornerShape(28.dp)).toggleable(on, role = Role.Switch, onValueChange = onChange)
            .padding(20.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text("Show on your Mac", fontSize = 20.sp, fontWeight = FontWeight.SemiBold, color = ink)
            Swap(summary) {
                Text(it, fontSize = 13.5.sp, color = ink.copy(alpha = 0.8f), style = TextStyle(fontFeatureSettings = "tnum"))
            }
        }
        SeamSwitch(on, null, scale = 1.1f)
    }
}

@Composable
private fun ModeChip(mode: AppMode) {
    val s = MaterialTheme.colorScheme
    val spec = Motion.ease<Float>(300)
    val on = mode == AppMode.On
    val title = mode == AppMode.TitleOnly
    Chip(
        mode.label,
        pick(title, pick(on, s.surfaceContainerHighest, s.primaryContainer, spec), s.tertiaryContainer, spec),
        pick(title, pick(on, s.onSurfaceVariant, s.onPrimaryContainer, spec), s.onTertiaryContainer, spec),
    )
}

/** The app list's filter: a filled pill that draws a teal ring while typing. */
@Composable
private fun SearchField(query: String, onChange: (String) -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val source = remember { MutableInteractionSource() }
    val focused by source.collectIsFocusedAsState()
    val ring = pick(focused, scheme.surfaceContainerHigh, scheme.primary, Motion.ease(200))
    BasicTextField(
        query, onChange, singleLine = true, interactionSource = source,
        textStyle = TextStyle(fontSize = 15.sp, color = scheme.onSurface),
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
                androidx.compose.foundation.layout.Box(Modifier.weight(1f)) {
                    if (query.isEmpty()) Text("Search apps", fontSize = 15.sp, color = scheme.onSurfaceVariant)
                    field()
                }
            }
        },
    )
}

private fun AppMode.explain(canReply: Boolean) = when (this) {
    AppMode.On -> if (canReply) "Everything, and reply from the Mac" else "Everything"
    AppMode.TitleOnly -> "Who it's from, never what it says"
    AppMode.Off -> "Stays on this phone"
}

@Composable
private fun appIcon(pkg: String): Painter {
    val context = LocalContext.current
    val fallback = painterResource(R.drawable.ic_seam_mark)
    return remember(pkg) {
        runCatching { BitmapPainter(context.packageManager.getApplicationIcon(pkg).toBitmap(120, 120).asImageBitmap()) }.getOrNull()
    } ?: fallback
}

/** The row that opens this screen, on Home and in Settings: what reaches the Mac, in one line. */
@Composable
internal fun NotificationsItem(feature: NotificationsFeature, title: String, home: Boolean, onOpen: () -> Unit) {
    val prefs by feature.prefs.collectAsStateWithLifecycle()
    val line = if (home) notificationsShort(prefs.enabled, prefs.apps) else notificationsLong(prefs.enabled, prefs.apps)
    Item(title, line, lead = painterResource(R.drawable.ic_bell), onClick = onOpen) {
        Text("›", fontSize = 14.sp, color = MaterialTheme.colorScheme.outline)
    }
}
