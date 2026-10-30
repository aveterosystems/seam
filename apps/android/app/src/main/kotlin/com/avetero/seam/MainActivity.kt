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
import androidx.compose.material3.Surface
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
                val fromMac by app.clipboard.fromMac.collectAsStateWithLifecycle()
                val updateOffer by app.updater.offer.collectAsStateWithLifecycle()
                val updateStatus by app.updater.status.collectAsStateWithLifecycle()
                val updatesAsked by app.updater.asked.collectAsStateWithLifecycle()
                var scanError by remember { mutableStateOf<String?>(null) }
                var scanned by remember { mutableStateOf<PairingQr?>(null) }
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
                // Back from Android's settings with the camera allowed
                LifecycleResumeEffect(Unit) {
                    cameraAllowed = granted()
                    onPauseOrDispose {}
                }
                // Board A03 follows a pairing that finished on this screen with a new Mac; a failed
                // re-pair falls back to the old Mac, which isn't a new pairing (SR-11-06)
                var pairingFrom by remember { mutableStateOf<String?>(null) }
                var wasPairing by remember { mutableStateOf(false) }
                var setup by remember { mutableStateOf(false) }
                if (state is SessionState.Pairing) {
                    if (!wasPairing) pairingFrom = session.macCode()
                    wasPairing = true
                } else if (wasPairing) {
                    wasPairing = false
                    scanning = false // the pairing screen closes when pairing ends, either way
                    setup = isNewPairing(pairingFrom, session.macCode(), state)
                }
                val clipboardStatus = when {
                    AutoClipboard.available(this@MainActivity) -> ClipboardStatus.Automatic
                    BuildConfig.FLAVOR == "full" -> ClipboardStatus.NeedsSetup
                    else -> ClipboardStatus.Manual
                }
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
                    )
                }
                SeamScreen(
                    state = state,
                    scanError = scanError ?: notice,
                    toConfirm = scanned,
                    onConfirm = { qr ->
                        // scanned clears once the session says Pairing, so the sheet never drops out in between
                        session.pair(qr)
                        ConnectionService.start(this@MainActivity)
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
              }
              }
            }
        }
    }
}

/** Whether pairing just ended with a different Mac than before (SR-11-06). Codes identify a Mac. */
internal fun isNewPairing(codeBefore: String?, codeAfter: String?, state: SessionState): Boolean =
    codeAfter != null && codeAfter != codeBefore &&
        (state is SessionState.Connected || state is SessionState.Idle || state is SessionState.Connecting)
