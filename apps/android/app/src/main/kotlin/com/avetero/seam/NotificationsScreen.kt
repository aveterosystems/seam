// SPDX-License-Identifier: AGPL-3.0-only
package com.avetero.seam

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.TextButton
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.text.DateFormat
import java.util.Date
import androidx.core.graphics.drawable.toBitmap
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.avetero.seam.feature.notifications.AppEntry
import com.avetero.seam.feature.notifications.AppMode
import com.avetero.seam.feature.notifications.NotificationsFeature

/** Settings › Notifications (board A06): what reaches the Mac, app by app. The Mac's pane edits the same settings. */
@Composable
fun NotificationsScreen(feature: NotificationsFeature, onBack: () -> Unit) {
    val prefs by feature.prefs.collectAsStateWithLifecycle()
    var query by remember { mutableStateOf("") }
    var selecting by remember { mutableStateOf(false) }
    var selected by remember { mutableStateOf(setOf<String>()) }
    val shown = prefs.apps.filter { query.isBlank() || it.name.contains(query.trim(), ignoreCase = true) }
    fun stopSelecting() {
        selecting = false
        selected = emptySet()
    }
    BackHandler(enabled = selecting, onBack = ::stopSelecting)
    SubScreen(
        "Notifications", onBack,
        overlay = {
            if (selecting) {
                BulkBar(
                    count = selected.size,
                    allSelected = shown.isNotEmpty() && shown.all { it.id in selected },
                    onSelectAll = { all -> selected = if (all) selected + shown.map { it.id } else selected - shown.map { it.id }.toSet() },
                    onMode = { mode ->
                        feature.setMode(selected, mode)
                        stopSelecting()
                    },
                    modifier = Modifier.align(Alignment.BottomCenter),
                )
            }
        },
    ) {
        Section("Access") { NotificationAccessItem() }
        Section("On your Mac") {
            if (prefs.enabled && prefs.paused(System.currentTimeMillis())) {
                val until = DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(prefs.pausedUntil))
                Item("Paused until $until", "Paused from your Mac. Nothing goes over until then") {
                    TextButton(onClick = { feature.pause(0) }) { Text("Resume") }
                }
            }
            SwitchItem("Show on your Mac", "Apps that are off never send anything", prefs.enabled, feature::setEnabled, highlightWhenOn = true)
            SwitchItem("Quiet while using this phone", "Nothing goes to the Mac while this phone is unlocked and on", prefs.quietWhileUsingPhone, feature::setQuietWhileUsingPhone)
            SwitchItem("Dismiss on both devices", "Clearing a notification on one clears it on the other", prefs.dismissBoth, feature::setDismissBoth)
        }
        Section(
            "Apps",
            subtitle = "${prefs.apps.count { it.mode != AppMode.Off }} of ${prefs.apps.size} on · each starts off",
            action = if (prefs.apps.isEmpty()) null else {
                { TextButton(onClick = { if (selecting) stopSelecting() else selecting = true }) { Text(if (selecting) "Done" else "Select") } }
            },
            above = if (prefs.apps.size <= 8) null else {
                {
                    OutlinedTextField(
                        value = query, onValueChange = { query = it }, singleLine = true,
                        placeholder = { Text("Search apps") },
                        leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                        shape = RoundedCornerShape(28.dp),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            },
        ) {
            if (prefs.apps.isEmpty()) Item("No apps yet", "Apps that can notify show here once notification access is on")
            shown.forEach { a ->
                if (selecting) {
                    val on = a.id in selected
                    val toggle = { selected = if (on) selected - a.id else selected + a.id }
                    Item(a.name, a.mode.label, lead = appIcon(a.id), leadIsImage = true, highlighted = on, onClick = toggle) {
                        Checkbox(checked = on, onCheckedChange = { toggle() })
                    }
                } else {
                    AppRow(a) { feature.setMode(setOf(a.id), it) }
                }
            }
        }
        Footnote("Messaging apps can be answered from the Mac. Title only sends who it's from, never what it says.")
        if (selecting) Spacer(Modifier.height(140.dp)) // room to scroll the last rows above the bar
    }
}

/** While selecting: how many, select all (of what the search shows), and the three settings for all of them at once. */
@Composable
private fun BulkBar(count: Int, allSelected: Boolean, onSelectAll: (Boolean) -> Unit, onMode: (AppMode) -> Unit, modifier: Modifier) {
    Surface(
        modifier = modifier.fillMaxWidth().navigationBarsPadding().padding(12.dp),
        shape = RoundedCornerShape(28.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        shadowElevation = 6.dp,
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(if (count == 0) "Select apps" else "$count selected", fontSize = 16.sp, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
                TextButton(onClick = { onSelectAll(!allSelected) }) { Text(if (allSelected) "Select none" else "Select all") }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                AppMode.entries.forEach { m ->
                    FilledTonalButton(onClick = { onMode(m) }, enabled = count > 0, modifier = Modifier.weight(1f)) { Text(m.label, maxLines = 1) }
                }
            }
        }
    }
}

@Composable
private fun AppRow(app: AppEntry, onMode: (AppMode) -> Unit) {
    var open by remember { mutableStateOf(false) }
    val icon = appIcon(app.id)
    val subtitle = listOfNotNull(app.mode.label, "Reply on Mac".takeIf { app.canReply && app.mode == AppMode.On }).joinToString(" · ")
    Item(app.name, subtitle, lead = icon, leadIsImage = true, onClick = { open = true })
    if (open) ModeSheet(app, icon, onDismiss = { open = false }) {
        open = false
        onMode(it)
    }
}

/** What reaches the Mac from one app: a sheet in the same grouped style as Settings. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ModeSheet(app: AppEntry, icon: Painter, onDismiss: () -> Unit, onMode: (AppMode) -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = MaterialTheme.colorScheme.surface) {
        Column(Modifier.padding(start = 16.dp, end = 16.dp, bottom = 32.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Row(Modifier.padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Image(icon, contentDescription = null, modifier = Modifier.size(48.dp))
                Spacer(Modifier.size(16.dp))
                Column {
                    Text(app.name, fontSize = 22.sp, lineHeight = 28.sp, fontWeight = FontWeight.SemiBold)
                    Text("What reaches your Mac", fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Section("Show") {
                AppMode.entries.forEach { m ->
                    Item(m.label, m.explain(app.canReply), highlighted = m == app.mode, onClick = { onMode(m) }) {
                        if (m == app.mode) Icon(Icons.Filled.Check, contentDescription = "Selected", tint = MaterialTheme.colorScheme.onPrimaryContainer)
                    }
                }
            }
        }
    }
}

private fun AppMode.explain(canReply: Boolean) = when (this) {
    AppMode.On -> if (canReply) "The whole notification, and you can reply from the Mac" else "The whole notification"
    AppMode.TitleOnly -> "Who it's from, never what it says"
    AppMode.Off -> "Nothing from this app leaves your phone"
}

private val AppMode.label get() = when (this) {
    AppMode.On -> "On"
    AppMode.TitleOnly -> "Title only"
    AppMode.Off -> "Off"
}

@Composable
private fun appIcon(pkg: String): Painter {
    val context = LocalContext.current
    val fallback = painterResource(R.drawable.ic_seam_mark)
    return remember(pkg) {
        runCatching { BitmapPainter(context.packageManager.getApplicationIcon(pkg).toBitmap(120, 120).asImageBitmap()) }.getOrNull()
    } ?: fallback
}

/** Settings' row for this screen: a summary of what reaches the Mac. */
@Composable
internal fun NotificationsItem(feature: NotificationsFeature, onOpen: () -> Unit) {
    val prefs by feature.prefs.collectAsStateWithLifecycle()
    val titleOnly = prefs.apps.count { it.mode == AppMode.TitleOnly }
    val on = prefs.apps.count { it.mode != AppMode.Off }
    Item(
        title = "Notifications",
        subtitle = when {
            !prefs.enabled -> "Off"
            prefs.apps.isEmpty() -> "On · apps show here after they post"
            else -> "$on apps" + if (titleOnly > 0) " · $titleOnly title only" else ""
        },
        lead = painterResource(R.drawable.ic_laptop),
        onClick = onOpen,
    )
}
