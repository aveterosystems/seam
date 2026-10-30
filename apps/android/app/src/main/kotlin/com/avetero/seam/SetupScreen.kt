// SPDX-License-Identifier: AGPL-3.0-only
package com.avetero.seam

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.LifecycleResumeEffect
import com.avetero.seam.feature.clipboard.CopyWatcher
import com.avetero.seam.feature.notifications.NotificationsFeature

/** Board A03: right after pairing, the permissions that make Seam work, each skippable. */
@Composable
fun SetupScreen(macName: String, clipboard: ClipboardStatus, onDone: () -> Unit) {
    BackHandler(onBack = onDone)
    val context = LocalContext.current
    fun granted() = context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
    var notifications by remember { mutableStateOf(granted()) }
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { notifications = it }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 24.dp)) {
        Column(Modifier.padding(horizontal = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Step 2 of 2", fontSize = 14.sp, fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.primary)
            Text("Paired. Now let Seam do its job", fontSize = 32.sp, lineHeight = 40.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-0.5).sp)
            Text(
                "Seam only asks for what it uses with $macName. You can change these later in Android's settings.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.padding(12.dp))
        Section("Allow") {
            Item(
                title = "Notifications",
                subtitle = if (notifications) "Allowed" else "Shows when Seam is syncing, and reminds you after a restart",
                lead = rememberVectorPainter(Icons.Filled.Notifications),
            ) {
                if (notifications) Done() else FilledTonalButton(onClick = { ask.launch(Manifest.permission.POST_NOTIFICATIONS) }) { Text("Allow") }
            }
            NotificationAccessItem()
            Item(
                title = "Automatic clipboard",
                subtitle = when (clipboard) {
                    ClipboardStatus.Automatic -> "On"
                    ClipboardStatus.NeedsSetup -> "Optional: what you copy goes to your Mac by itself. Turn on Seam in Accessibility. If Android says Restricted setting: App info › ⋮ › Allow restricted settings"
                    ClipboardStatus.Manual -> "Not in this build. Tap Send clipboard, or share to Send to Mac"
                },
                lead = painterResource(R.drawable.ic_clipboard),
            ) {
                when (clipboard) {
                    ClipboardStatus.Automatic -> Done()
                    ClipboardStatus.NeedsSetup -> FilledTonalButton(onClick = { openCopyWatcherSettings(context) }) { Text("Allow") }
                    ClipboardStatus.Manual -> {}
                }
            }
        }
        Text(
            "Mac copies reach this phone without any of these.",
            fontSize = 12.sp, color = MaterialTheme.colorScheme.outline,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 12.dp),
        )
        Spacer(Modifier.weight(1f))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, androidx.compose.ui.Alignment.End)) {
            if (!notifications) TextButton(onClick = onDone) { Text("Skip for now") }
            Button(onClick = onDone) { Text("Done") }
        }
    }
}

/** Android's Accessibility settings, where the user turns on Seam ([CopyWatcher]). */
internal fun openCopyWatcherSettings(context: Context) {
    runCatching { context.startActivity(CopyWatcher.settingsIntent(context)) }
}

/** Board A03 / A06 "Notifications on your Mac": opens Android's access screen, re-checked on return. */
@Composable
internal fun NotificationAccessItem() {
    val context = LocalContext.current
    var granted by remember { mutableStateOf(NotificationsFeature.accessGranted(context)) }
    LifecycleResumeEffect(Unit) {
        granted = NotificationsFeature.accessGranted(context)
        onPauseOrDispose {}
    }
    val open = { context.startActivity(NotificationsFeature.accessIntent(context)) }
    Item(
        title = "Notifications on your Mac",
        // Sideloaded installs hit Android's "Restricted setting" block first; say how past it
        subtitle = if (granted) "On" else "Off. If Android says Restricted setting: App info › ⋮ › Allow restricted settings",
        lead = painterResource(R.drawable.ic_laptop),
        onClick = if (granted) null else open,
    ) {
        if (granted) Done() else FilledTonalButton(onClick = open) { Text("Allow") }
    }
}
