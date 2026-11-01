// SPDX-License-Identifier: AGPL-3.0-only
package com.avetero.seam

import android.app.Application
import android.os.Build
import android.provider.Settings
import com.avetero.seam.feature.StatusFeature
import com.avetero.seam.feature.clipboard.ClipboardFeature
import com.avetero.seam.feature.notifications.NotificationsFeature
import com.avetero.seam.session.SessionState
import com.avetero.seam.session.PairingOutcome
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import com.avetero.seam.session.FilePendingStore
import com.avetero.seam.session.KeystoreStateStore
import com.avetero.seam.session.NsdLan
import com.avetero.seam.session.PrefsSettingsStore
import com.avetero.seam.crypto.Tor
import com.avetero.seam.session.TorLan
import com.avetero.seam.session.Session
import java.io.File
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob

/** Owns the one Session for the process; the UI and ConnectionService just watch it. */
class SeamApplication : Application() {
    lateinit var session: Session
        private set
    lateinit var clipboard: ClipboardFeature
        private set

    /** The phone's status for the Mac, and the Mac's battery for the home screen. */
    lateinit var status: StatusFeature
        private set
    lateinit var updater: Updater
        private set
    lateinit var notifications: NotificationsFeature
        private set
    /** When pairing with this Mac finished (unix ms), for Settings; null when unknown or unpaired. */
    val pairedAt: StateFlow<Long?> get() = _pairedAt
    private val _pairedAt = MutableStateFlow<Long?>(null)

    /** Tor, one per process: started on first use, dormant while there's nothing to send. */
    lateinit var tor: TorLan
        private set

    @OptIn(ExperimentalCoroutinesApi::class)
    override fun onCreate() {
        super.onCreate()
        val name = Settings.Global.getString(contentResolver, Settings.Global.DEVICE_NAME) ?: Build.MODEL
        val dir = File(filesDir, "seam")
        tor = TorLan(Tor(dir.path))
        session = Session(
            store = KeystoreStateStore(dir),
            tor = tor,
            myName = name,
            myModel = Build.MODEL,
            // One thread for all session state: no locks, no races. Nothing that escapes a handler
            // may crash the process; the session shows it instead.
            trace = if (BuildConfig.DEBUG) { line -> android.util.Log.d("SeamSession", line) } else null,
            lan = NsdLan(this),
            settingsStore = PrefsSettingsStore(this),
            pendingStore = FilePendingStore(File(noBackupFilesDir, "seam")),
            appVersion = BuildConfig.VERSION_NAME,
            scope = CoroutineScope(
                SupervisorJob() + Dispatchers.Default.limitedParallelism(1) +
                    CoroutineExceptionHandler { _, _ -> if (::session.isInitialized) session.internalError() },
            ),
        )
        status = StatusFeature(this).also(session::register)
        clipboard = ClipboardFeature(this, session).also(session::register)
        notifications = NotificationsFeature(this, session).also(session::register)
        session.start()
        // Checks only once paired and the user has answered the updates question (SR-17-04)
        updater = Updater(this) { session.paired }.also { it.start() }
        val ui = getSharedPreferences("ui", MODE_PRIVATE)
        _pairedAt.value = ui.getLong(PAIRED_AT, 0).takeIf { it > 0 }
        CoroutineScope(Dispatchers.Main).launch {
            session.pairingOutcome.collect {
                if (it != PairingOutcome.NewMac) return@collect
                _pairedAt.value = System.currentTimeMillis()
                ui.edit().putLong(PAIRED_AT, _pairedAt.value!!).apply()
            }
        }
        // Forgetting the Mac forgets its last copy too (SR-11-09), and when it was paired
        CoroutineScope(Dispatchers.Main).launch {
            session.state.collect {
                if (it != SessionState.Unpaired) return@collect
                clipboard.clearFromMac()
                _pairedAt.value = null
                ui.edit().remove(PAIRED_AT).apply()
            }
        }
    }

    private companion object {
        const val PAIRED_AT = "paired_at"
    }
}
