// SPDX-License-Identifier: AGPL-3.0-only
package com.avetero.seam

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.compose.runtime.SideEffect
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.compose.LifecycleResumeEffect
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.ui.Modifier
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.avetero.seam.feature.QrViewfinder
import com.avetero.seam.feature.ScanResult
import com.avetero.seam.feature.clipboard.AutoClipboard
import com.avetero.seam.session.PairingQr
import com.avetero.seam.session.PairingOutcome
import com.avetero.seam.session.SessionState

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Draws behind the status and navigation bars (Android 15 does anyway); content keeps clear below
        enableEdgeToEdge()
        // Pair, Codes don't match and Forget refuse taps through another app's overlay (SR-11-02)
        window.decorView.filterTouchesWhenObscured = true
        // Recents shows a blank card, not the last Mac copy or the safety code (SR-11-04)
        if (Build.VERSION.SDK_INT >= 33) setRecentsScreenshotEnabled(false)
        val app = application as SeamApplication
        val session = app.session
        if (session.paired) ConnectionService.start(this)
        BootReceiver.dismiss(this)

        setContent {
            SeamTheme {
              Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
              Box(Modifier.safeDrawingPadding()) {
                val state by session.state.collectAsStateWithLifecycle()
                val notice by session.notice.collectAsStateWithLifecycle()
                val connection by session.settings.collectAsStateWithLifecycle()
                val route by session.route.collectAsStateWithLifecycle()
                val look = LinkLook.of(state, route, connection)
                var relayToPair by remember { mutableStateOf<PairingQr?>(null) }
                val fromMac by app.clipboard.fromMac.collectAsStateWithLifecycle()
                val updateOffer by app.updater.offer.collectAsStateWithLifecycle()
                val updateStatus by app.updater.status.collectAsStateWithLifecycle()
                val updatesAsked by app.updater.asked.collectAsStateWithLifecycle()
                var scanError by remember { mutableStateOf<String?>(null) }
                // Debug builds only: a pairing code passed at launch (device tests on an emulator, which
                // has no camera to scan with). It still goes through the same confirm and safety code
                var scanned by remember {
                    mutableStateOf(if (BuildConfig.DEBUG) intent?.dataString?.let { PairingQr.parse(it, allowCleartext = true) } else null)
                }
                var settings by remember { mutableStateOf(false) }
                var notificationSettings by remember { mutableStateOf(false) }
                var scanning by remember { mutableStateOf(false) }
                if (state is SessionState.Pairing) scanned = null
                fun granted() = checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
                var cameraAllowed by remember { mutableStateOf(granted()) }
                var cameraAsked by remember { mutableStateOf(false) }
                val askCamera = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
                    cameraAllowed = it
                    cameraAsked = true
                }
                var resumed by remember { mutableStateOf(0) }
                // Back from Android's settings with the camera allowed, or Seam's accessibility switch turned on
                LifecycleResumeEffect(Unit) {
                    cameraAllowed = granted()
                    resumed++
                    onPauseOrDispose {}
                }
                // Board A03 follows a pairing that finished on this screen with a new Mac; Session
                // decides that, so a failed re-pair back to the old Mac never shows it (SR-11-06)
                var wasPairing by remember { mutableStateOf(false) }
                var setup by remember { mutableStateOf(false) }
                if (state is SessionState.Pairing) {
                    wasPairing = true
                } else if (wasPairing) {
                    wasPairing = false
                    scanning = false // the pairing screen closes when pairing ends, either way
                    setup = session.pairingOutcome.value == PairingOutcome.NewMac
                }
                val clipboardStatus = resumed.let { when {
                    AutoClipboard.available(this@MainActivity) -> ClipboardStatus.Automatic
                    BuildConfig.FLAVOR == "full" -> ClipboardStatus.NeedsSetup
                    else -> ClipboardStatus.Manual
                } }
                val macName = (state as? SessionState.Connected)?.macName ?: (state as? SessionState.Idle)?.macName
                    ?: (state as? SessionState.Connecting)?.macName
                // Screenshots, recordings and casting go blank while a Mac copy or a safety code shows (SR-11-04)
                val sensitive = settings || scanned != null || state is SessionState.Pairing ||
                    (macName != null && fromMac?.fresh(System.currentTimeMillis()) == true)
                SideEffect {
                    if (sensitive) window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
                    else window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
                }
                if (setup && macName != null) return@Box SetupScreen(macName, clipboardStatus) { setup = false }
                if (settings && notificationSettings) {
                    return@Box NotificationsScreen(app.notifications) { notificationSettings = false }
                }
                if (settings) {
                    return@Box SettingsScreen(
                        macName = macName,
                        clipboard = clipboardStatus,
                        code = session.macCode(),
                        version = "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE}, ${BuildConfig.FLAVOR})",
                        onForgetMac = {
                            settings = false
                            session.forgetMac()
                        },
                        onBack = { settings = false },
                        updater = app.updater,
                        notifications = app.notifications,
                        onOpenNotifications = { notificationSettings = true },
                        connection = connection,
                        look = look,
                        onSwitches = { wifi, relay -> session.setSwitches(wifi, relay) },
                    )
                }
                SeamScreen(
                    state = state,
                    scanError = scanError ?: notice,
                    toConfirm = scanned,
                    onConfirm = { qr ->
                        // Pairing goes through the relay for now: ask before turning it back on
                        if (!connection.relay) {
                            scanned = null
                            relayToPair = qr
                        } else {
                            // scanned clears once the session says Pairing, so the sheet never drops out in between
                            session.pair(qr)
                            ConnectionService.start(this@MainActivity)
                        }
                    },
                    onCancelConfirm = {
                        scanned = null
                        scanning = false
                    },
                    fromMac = fromMac,
                    onPasteFromMac = { text ->
                        val put = app.clipboard.putBack(text)
                        Toast.makeText(this@MainActivity, if (put) "On this phone's clipboard" else "Couldn't put it on the clipboard", Toast.LENGTH_SHORT).show()
                    },
                    onOpenSettings = { settings = true },
                    onCodesDontMatch = { session.abortPairing() },
                    update = updateOffer,
                    updateStatus = updateStatus,
                    onUpdate = { app.updater.install(it) },
                    askUpdates = app.updater.available && !updatesAsked,
                    onAnswerUpdates = { app.updater.answer(it) },
                    look = look,
                    onSendClipboard = {
                        // Seam has focus here, so Android allows the read
                        Toast.makeText(this@MainActivity, sentMessage(app, app.clipboard.sendCurrent()), Toast.LENGTH_SHORT).show()
                    },
                    onScan = {
                        scanError = null
                        scanning = true
                        if (!cameraAllowed) askCamera.launch(Manifest.permission.CAMERA)
                    },
                    scanning = scanning,
                    onCloseScan = {
                        scanning = false
                        scanError = null
                    },
                    camera = { reading ->
                        if (cameraAllowed) {
                            QrViewfinder(active = reading, allowCleartext = BuildConfig.DEBUG, modifier = Modifier.fillMaxSize(), onResult = { r ->
                                when (r) {
                                    is ScanResult.Code -> {
                                        scanned = r.qr // nothing changes until the user confirms who this is
                                        scanError = null
                                    }
                                    ScanResult.NotSeam -> scanError = "That isn't a Seam pairing code. Open Seam on your Mac to show one."
                                }
                            })
                        } else {
                            CameraNeeded(asked = cameraAsked) {
                                if (cameraAsked && !shouldShowRequestPermissionRationale(Manifest.permission.CAMERA)) {
                                    startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null)))
                                } else {
                                    askCamera.launch(Manifest.permission.CAMERA)
                                }
                            }
                        }
                    },
                )
                relayToPair?.let { qr ->
                    AlertDialog(
                        onDismissRequest = { relayToPair = null },
                        title = { Text("Turn on Relay to pair?") },
                        text = { Text("Pairing goes through Seam's relay for now. Everything stays end-to-end encrypted. You can turn Relay off again in Settings › Connection once you've paired.") },
                        confirmButton = {
                            TextButton(onClick = {
                                relayToPair = null
                                session.setSwitches(connection.wifi, relay = true)
                                session.pair(qr)
                                ConnectionService.start(this@MainActivity)
                            }) { Text("Turn On and Pair") }
                        },
                        dismissButton = { TextButton(onClick = { relayToPair = null }) { Text("Cancel") } },
                    )
                }
              }
              }
            }
        }
    }
}
