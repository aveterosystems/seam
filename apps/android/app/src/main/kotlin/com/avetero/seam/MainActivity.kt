// SPDX-License-Identifier: AGPL-3.0-only
package com.avetero.seam

import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
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
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.produceState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.avetero.seam.feature.QrViewfinder
import com.avetero.seam.feature.ScanResult
import com.avetero.seam.feature.StatusFeature
import com.avetero.seam.feature.clipboard.ClipboardFeature
import com.avetero.seam.feature.clipboard.CLIPBOARD_CAPABILITY
import com.avetero.seam.feature.notifications.NotificationsFeature
import com.avetero.seam.feature.clipboard.AutoClipboard
import com.avetero.seam.session.PairingQr
import com.avetero.seam.session.PairingOutcome
import com.avetero.seam.session.SessionState
import kotlinx.coroutines.delay

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
        val clipboard = session.feature<ClipboardFeature>()
        val notifications = session.feature<NotificationsFeature>()
        val status = session.feature<StatusFeature>()
        if (session.paired) ConnectionService.start(this)
        BootReceiver.dismiss(this)

        setContent {
            SeamTheme {
              val snacks = remember { Snacks() }
              CompositionLocalProvider(LocalSnacks provides snacks) {
              Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
              Box(Modifier.fillMaxSize()) {
              Box(Modifier.safeDrawingPadding()) {
                val state by session.state.collectAsStateWithLifecycle()
                val notice by session.notice.collectAsStateWithLifecycle()
                val connection by session.settings.collectAsStateWithLifecycle()
                val route by session.route.collectAsStateWithLifecycle()
                // Ticks once when a timed pause ends, so the screen comes back without a tap
                val now by produceState(System.currentTimeMillis(), connection.pausedUntil) {
                    val until = connection.pausedUntil
                    if (until > value && until != Long.MAX_VALUE) {
                        delay(until - value)
                        value = System.currentTimeMillis()
                    }
                }
                val tor by app.tor.state.collectAsStateWithLifecycle()
                val look = LinkLook.of(state, route, connection, tor, now)
                val fromMac by clipboard.fromMac.collectAsStateWithLifecycle()
                val macStatus by status.mac.collectAsStateWithLifecycle()
                val pairedAt by app.pairedAt.collectAsStateWithLifecycle()
                val updateOffer by app.updater.offer.collectAsStateWithLifecycle()
                val updateStatus by app.updater.status.collectAsStateWithLifecycle()
                val updatesAsked by app.updater.asked.collectAsStateWithLifecycle()
                var scanError by remember { mutableStateOf<String?>(null) }
                // Debug builds only: a pairing code passed at launch (device tests on an emulator, which
                // has no camera to scan with). It still goes through the same confirm and safety code
                var scanned by remember {
                    mutableStateOf(if (BuildConfig.DEBUG) intent?.dataString?.let { PairingQr.parse(it) } else null)
                }
                // The notification's Settings button opens straight there
                var pages by remember {
                    mutableStateOf(listOf(Page.Home) + if (intent?.getBooleanExtra(EXTRA_OPEN_SETTINGS, false) == true) listOf(Page.Settings) else emptyList())
                }
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
                    !connection.macTakes(CLIPBOARD_CAPABILITY) -> ClipboardStatus.OffOnMac
                    AutoClipboard.available(this@MainActivity) -> ClipboardStatus.Automatic
                    BuildConfig.FLAVOR == "full" -> ClipboardStatus.NeedsSetup
                    else -> ClipboardStatus.Manual
                } }
                val macName = (state as? SessionState.Connected)?.macName ?: (state as? SessionState.Idle)?.macName
                    ?: (state as? SessionState.Connecting)?.macName
                // Screenshots, recordings and casting go blank while a Mac copy or a safety code shows (SR-11-04)
                val sensitive = pages.last() != Page.Home || scanned != null || state is SessionState.Pairing ||
                    (macName != null && fromMac?.fresh(System.currentTimeMillis()) == true)
                SideEffect {
                    if (sensitive) window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
                    else window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
                }
                if (setup && macName != null) return@Box SetupScreen(macName, clipboardStatus) { setup = false }
                Pages(pages, onPop = { pages = pages.dropLast(1) }) { page, back ->
                when (page) {
                Page.Notifications -> NotificationsScreen(notifications, onBack = back)
                Page.Settings -> SettingsScreen(
                    macName = macName,
                    clipboard = clipboardStatus,
                    code = session.macCode(),
                    version = "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE}, ${BuildConfig.FLAVOR})",
                    onForgetMac = {
                        pages = listOf(Page.Home)
                        session.forgetMac()
                    },
                    onBack = back,
                    updater = app.updater,
                    notifications = notifications,
                    onOpenNotifications = { pages = pages + Page.Notifications },
                    connection = connection,
                    onSwitches = { wifi, tor -> session.setSwitches(wifi, tor) },
                    pairedAt = pairedAt,
                    sendSensitive = clipboard.sendSensitive,
                    onSendSensitive = { clipboard.sendSensitive = it },
                    onReconnect = if (macName != null) ({ session.reconnect() }) else null,
                    reconnecting = (state as? SessionState.Connecting)?.reconnecting == true,
                )
                Page.Home ->                 SeamScreen(
                    state = state,
                    scanError = scanError ?: notice,
                    toConfirm = scanned,
                    onConfirm = { qr ->
                        // Pairing tries Same Wi‑Fi and Tor at once, whichever switches are on.
                        // scanned clears once the session says Pairing, so the sheet never drops out in between
                        session.pair(qr)
                        ConnectionService.start(this@MainActivity)
                    },
                    onCancelConfirm = {
                        scanned = null
                        scanning = false
                    },
                    fromMac = fromMac,
                    onPasteFromMac = { text -> clipboard.putBack(text) },
                    onOpenSettings = { pages = pages + Page.Settings },
                    onOpenNotifications = { pages = pages + Page.Notifications },
                    mac = macStatus,
                    notifications = notifications,
                    clipboard = clipboardStatus,
                    onCodesDontMatch = { session.abortPairing() },
                    update = updateOffer,
                    updateStatus = updateStatus,
                    onUpdate = { app.updater.install(it) },
                    askUpdates = app.updater.available && !updatesAsked,
                    onAnswerUpdates = { app.updater.answer(it) },
                    look = look,
                    onPause = { session.pause(it.until(System.currentTimeMillis())) },
                    onResume = { session.resume() },
                    // Seam has focus here, so Android allows the read
                    onSendClipboard = { sendFeedback(clipboard.sendCurrent(), session.state.value, macName ?: "your Mac", clipboard.offOnMac) },
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
                            QrViewfinder(active = reading, modifier = Modifier.fillMaxSize(), onResult = { r ->
                                when (r) {
                                    is ScanResult.Code -> {
                                        scanned = r.qr // nothing changes until the user confirms who this is
                                        scanError = null
                                    }
                                    ScanResult.NotSeam -> scanError = "That isn't a Seam pairing code. Open Seam on your Mac to show one."
                                    ScanResult.OldMac -> scanError = "That code is from an older Seam. Update Seam on your Mac, then show the new code."
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
              SnackHost(snacks, Modifier.align(Alignment.BottomCenter).safeDrawingPadding())
              }
              }
              }
            }
        }
    }

    companion object {
        /** Opens Settings straight away (the notification's Settings button). */
        const val EXTRA_OPEN_SETTINGS = "com.avetero.seam.OPEN_SETTINGS"
    }
}
