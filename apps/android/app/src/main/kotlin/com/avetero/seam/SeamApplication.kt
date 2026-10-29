// SPDX-License-Identifier: AGPL-3.0-only
package com.avetero.seam

import android.app.Application
import android.os.Build
import android.provider.Settings
import com.avetero.seam.feature.StatusFeature
import com.avetero.seam.session.KeystoreStateStore
import com.avetero.seam.session.OkHttpRelayConnector
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

    @OptIn(ExperimentalCoroutinesApi::class)
    override fun onCreate() {
        super.onCreate()
        val name = Settings.Global.getString(contentResolver, Settings.Global.DEVICE_NAME) ?: Build.MODEL
        session = Session(
            store = KeystoreStateStore(File(filesDir, "seam")),
            connector = OkHttpRelayConnector(),
            myName = name,
            myModel = Build.MODEL,
            // One thread for all session state: no locks, no races. Nothing that escapes a handler
            // may crash the process; the session shows it instead.
            scope = CoroutineScope(
                SupervisorJob() + Dispatchers.Default.limitedParallelism(1) +
                    CoroutineExceptionHandler { _, _ -> if (::session.isInitialized) session.internalError() },
            ),
        )
        session.register(StatusFeature(this))
        session.start()
    }
}
