// SPDX-License-Identifier: AGPL-3.0-only
package com.avetero.seam

import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.avetero.seam.feature.PairingScanner
import com.avetero.seam.feature.ScanResult
import com.avetero.seam.feature.clipboard.AutoClipboard
import com.avetero.seam.session.PairingQr
import com.avetero.seam.session.SessionState
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Draws behind the status and navigation bars (Android 15 does anyway); content keeps clear below
        enableEdgeToEdge()
        val app = application as SeamApplication
        val session = app.session
        if (session.paired) ConnectionService.start(this)
        BootReceiver.dismiss(this)

        setContent {
            val context = LocalContext.current
            val colors = if (isSystemInDarkTheme()) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
            MaterialTheme(colorScheme = colors) {
              Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
              Box(Modifier.safeDrawingPadding()) {
                val state by session.state.collectAsStateWithLifecycle()
                val scope = rememberCoroutineScope()
                val notice by session.notice.collectAsStateWithLifecycle()
                val fromMac by app.clipboard.fromMac.collectAsStateWithLifecycle()
                var scanError by remember { mutableStateOf<String?>(null) }
                var scanned by remember { mutableStateOf<PairingQr?>(null) }
                var settings by remember { mutableStateOf(false) }
                // Board A03 follows a pairing that finished on this screen
                var wasPairing by remember { mutableStateOf(false) }
                var setup by remember { mutableStateOf(false) }
                if (state is SessionState.Pairing) wasPairing = true
                else if (wasPairing) {
                    wasPairing = false
                    setup = state is SessionState.Connected || state is SessionState.Idle || state is SessionState.Connecting
                }
                val clipboardStatus = when {
                    AutoClipboard.available(this@MainActivity) -> ClipboardStatus.Automatic
                    BuildConfig.FLAVOR == "full" -> ClipboardStatus.NeedsSetup
                    else -> ClipboardStatus.Manual
                }
                val macName = (state as? SessionState.Connected)?.macName ?: (state as? SessionState.Idle)?.macName
                    ?: (state as? SessionState.Connecting)?.macName
                if (setup && macName != null) return@Box SetupScreen(macName, clipboardStatus) { setup = false }
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
                    )
                }
                SeamScreen(
                    state = state,
                    scanError = scanError ?: notice,
                    toConfirm = scanned,
                    onConfirm = { qr ->
                        scanned = null
                        session.pair(qr)
                        ConnectionService.start(this@MainActivity)
                    },
                    onCancelConfirm = { scanned = null },
                    fromMac = fromMac,
                    onPasteFromMac = { text ->
                        app.clipboard.putBack(text)
                        Toast.makeText(this@MainActivity, "On this phone's clipboard", Toast.LENGTH_SHORT).show()
                    },
                    onOpenSettings = { settings = true },
                    onCodesDontMatch = { session.abortPairing() },
                    onSendClipboard = {
                        // Seam has focus here, so Android allows the read
                        Toast.makeText(this@MainActivity, sentMessage(app, app.clipboard.sendCurrent()), Toast.LENGTH_SHORT).show()
                    },
                    onScan = {
                        scope.launch {
                            scanError = when (val r = PairingScanner.scan(this@MainActivity, BuildConfig.DEBUG)) {
                                is ScanResult.Code -> {
                                    scanned = r.qr // nothing changes until the user confirms who this is
                                    null
                                }
                                ScanResult.NotSeam -> "That isn't a Seam pairing code. Open Seam on your Mac to show one."
                                ScanResult.Cancelled -> null
                                is ScanResult.Unavailable -> "The QR scanner isn't available on this phone. It needs Google Play services."
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
