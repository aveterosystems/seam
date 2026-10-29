// SPDX-License-Identifier: AGPL-3.0-only
package com.avetero.seam

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.background
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Phone → Mac clipboard: automatic (full build, granted), possible after setup (full), or one tap (Play). */
enum class ClipboardStatus { Automatic, NeedsSetup, Manual }

/** Board A06, with what exists today: automatic clipboard, privacy, the Mac, about. */
@Composable
fun SettingsScreen(
    macName: String?,
    clipboard: ClipboardStatus,
    code: String?,
    version: String,
    onForgetMac: () -> Unit,
    onBack: () -> Unit,
    updater: Updater? = null,
) {
    BackHandler(onBack = onBack)
    var explainClipboard by remember { mutableStateOf(false) }
    var confirmForget by remember { mutableStateOf(false) }
    val uri = LocalUriHandler.current
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
            Row(Modifier.fillMaxWidth().height(64.dp).padding(start = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack, modifier = Modifier.size(48.dp)) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                }
            }
            Text(
                "Settings", fontSize = 36.sp, lineHeight = 44.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-0.6).sp,
                modifier = Modifier.padding(start = 20.dp, end = 20.dp, bottom = 12.dp),
            )
            Column(Modifier.padding(start = 16.dp, end = 16.dp, bottom = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Section("On your Mac") {
                    val on = clipboard == ClipboardStatus.Automatic
                    Item(
                        title = "Automatic clipboard",
                        subtitle = when (clipboard) {
                            ClipboardStatus.Automatic -> "On · set up from your Mac"
                            ClipboardStatus.NeedsSetup -> "Off · set up from your Mac"
                            ClipboardStatus.Manual -> "One tap: Send clipboard, or share to Send to Mac"
                        },
                        highlighted = on,
                        onClick = { explainClipboard = true },
                    ) {
                        if (clipboard != ClipboardStatus.Manual) Switch(checked = on, onCheckedChange = { explainClipboard = true })
                    }
                    Item("Mac → phone", "Always automatic. A Mac copy waits up to 10 minutes for this phone")
                }
                Section("Privacy & security") {
                    Item(
                        title = "End-to-end encrypted",
                        subtitle = if (code != null) "Keys verified when you paired · " else "Keys made on this phone, sealed by its security chip",
                        mono = code,
                        lead = rememberVectorPainter(Icons.Filled.Lock),
                    )
                    Item("Never sync passwords", "Skips anything apps mark as sensitive") {
                        Switch(checked = true, onCheckedChange = null, enabled = false)
                    }
                }
                if (macName != null) {
                    Section("Mac") {
                        Item(title = macName, subtitle = "Paired", lead = painterResource(R.drawable.ic_laptop)) {
                            TextButton(onClick = { confirmForget = true }) { Text("Forget", color = MaterialTheme.colorScheme.error) }
                        }
                    }
                }
                if (updater?.available == true) {
                    val status by updater.status.collectAsState()
                    var automatic by remember { mutableStateOf(updater.automatic) }
                    Section("Updates") {
                        Item("Check for updates automatically", "Once a day, Seam asks GitHub for a newer release. GitHub sees this phone's IP address when it does.") {
                            Switch(checked = automatic, onCheckedChange = { automatic = it; updater.automatic = it })
                        }
                        Item("Check now", status ?: "Installs only updates signed by Seam", onClick = { updater.checkNow() })
                    }
                }
                Section("About") {
                    Item("Seam", "Version $version", lead = painterResource(R.drawable.ic_seam_mark))
                    Item("Open source", "AGPL-3.0 · github.com/aveterosystems/seam", lead = rememberVectorPainter(Icons.Filled.Info), onClick = {
                        uri.openUri("https://github.com/aveterosystems/seam")
                    })
                }
            }
        }
    }
    if (explainClipboard) {
        AlertDialog(
            onDismissRequest = { explainClipboard = false },
            title = { Text("Automatic clipboard") },
            text = {
                Text(
                    when (clipboard) {
                        ClipboardStatus.Automatic -> "What you copy here goes to your Mac by itself. To turn it off, choose Clipboard Sync off in Seam on your Mac."
                        ClipboardStatus.NeedsSetup -> "Android only lets your Mac turn this on. Connect this phone to your Mac, then in Seam on the Mac choose Settings › Clipboard › Turn On."
                        ClipboardStatus.Manual -> "The Play Store build can't send copies by itself. Tap Send clipboard on the home screen, or share text to Send to Mac."
                    },
                )
            },
            confirmButton = { TextButton(onClick = { explainClipboard = false }) { Text("OK") } },
        )
    }
    if (confirmForget && macName != null) {
        AlertDialog(
            onDismissRequest = { confirmForget = false },
            title = { Text("Forget $macName?") },
            text = { Text("This phone stops syncing with $macName and forgets its keys. If this phone is online, $macName forgets it too; if not, forget this phone on $macName as well. You can pair again any time.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmForget = false
                    onForgetMac()
                }) { Text("Forget", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { confirmForget = false }) { Text("Cancel") } },
        )
    }
}

@Composable
internal fun Section(title: String, items: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(title, fontSize = 14.sp, fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(start = 4.dp, top = 8.dp, bottom = 4.dp))
        Column(verticalArrangement = Arrangement.spacedBy(2.dp), modifier = Modifier.clip24()) { items() }
    }
}

/**
 * One row of an Android 16-style grouped list: each row is its own surface, 4 dp corners inside
 * the group; the group clips its outer corners to 24 dp.
 */
@Composable
internal fun Item(
    title: String,
    subtitle: String? = null,
    mono: String? = null,
    lead: Painter? = null,
    highlighted: Boolean = false,
    onClick: (() -> Unit)? = null,
    trailing: @Composable () -> Unit = {},
) {
    val color = if (highlighted) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerLow
    val content = if (highlighted) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface
    val body: @Composable () -> Unit = {
        Row(Modifier.fillMaxWidth().heightIn(min = 64.dp).padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            if (lead != null) {
                Box(
                    Modifier.size(40.dp).clipCircle(MaterialTheme.colorScheme.secondaryContainer),
                    contentAlignment = Alignment.Center,
                ) { Icon(lead, contentDescription = null, tint = MaterialTheme.colorScheme.onSecondaryContainer, modifier = Modifier.size(20.dp)) }
                androidx.compose.foundation.layout.Spacer(Modifier.size(16.dp))
            }
            Column(Modifier.weight(1f)) {
                Text(title, fontSize = 16.sp, lineHeight = 24.sp)
                if (subtitle != null || mono != null) {
                    Row {
                        if (subtitle != null) Text(subtitle, fontSize = 14.sp, lineHeight = 20.sp, color = if (highlighted) content.copy(alpha = 0.85f) else MaterialTheme.colorScheme.onSurfaceVariant)
                        if (mono != null) Text(mono, fontSize = 14.sp, lineHeight = 20.sp, fontFamily = FontFamily.Monospace, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            trailing()
        }
    }
    if (onClick != null) {
        Surface(onClick = onClick, color = color, contentColor = content, shape = RoundedCornerShape(4.dp)) { body() }
    } else {
        Surface(color = color, contentColor = content, shape = RoundedCornerShape(4.dp)) { body() }
    }
}

private fun Modifier.clip24() = this.then(Modifier.clip(RoundedCornerShape(24.dp)))
private fun Modifier.clipCircle(c: Color) = this.then(Modifier.clip(CircleShape).background(c))
