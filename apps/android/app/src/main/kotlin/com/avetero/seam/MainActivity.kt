// SPDX-License-Identifier: AGPL-3.0-only
package com.avetero.seam

import android.Manifest
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
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
        requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        val app = application as SeamApplication
        val session = app.session
        if (session.state.value != SessionState.Unpaired) ConnectionService.start(this)

        setContent {
            val context = LocalContext.current
            val colors = if (isSystemInDarkTheme()) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
            MaterialTheme(colorScheme = colors) {
                val state by session.state.collectAsStateWithLifecycle()
                val scope = rememberCoroutineScope()
                val notice by session.notice.collectAsStateWithLifecycle()
                var scanError by remember { mutableStateOf<String?>(null) }
                var scanned by remember { mutableStateOf<PairingQr?>(null) }
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
                    clipboard = when {
                        AutoClipboard.available(this@MainActivity) -> ClipboardStatus.Automatic
                        BuildConfig.FLAVOR == "full" -> ClipboardStatus.NeedsSetup
                        else -> ClipboardStatus.Manual
                    },
                    onSendClipboard = {
                        // Seam has focus here, so Android allows the read
                        val sent = app.clipboard.sendCurrent()
                        val text = if (sent) "Sent to your Mac's clipboard" else "Nothing to send: the clipboard is empty or holds something private"
                        Toast.makeText(this@MainActivity, text, Toast.LENGTH_SHORT).show()
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
