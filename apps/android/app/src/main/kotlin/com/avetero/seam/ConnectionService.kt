// SPDX-License-Identifier: AGPL-3.0-only
package com.avetero.seam

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import com.avetero.seam.feature.clipboard.AutoClipboard
import com.avetero.seam.session.SessionState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Keeps the process (and so the Session's relay connection) alive while paired, with a quiet,
 * minimum-priority notification Android requires. Battery-friendly wake-ups replace this later. Also hosts automatic clipboard.
 */
class ConnectionService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    override fun onCreate() {
        super.onCreate()
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Connection to your Mac", NotificationManager.IMPORTANCE_MIN))
        startForeground(1, notification("Starting…"), ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
        val app = application as SeamApplication
        val session = app.session
        AutoClipboard.start(this, app.clipboard) // full builds, once the Mac has granted log access
        scope.launch {
            session.state.collect { state ->
                when (state) {
                    is SessionState.Connected -> nm.notify(1, notification("Connected to ${state.macName}"))
                    is SessionState.Connecting -> nm.notify(1, notification("Reconnecting to ${state.macName}…"))
                    is SessionState.Pairing -> nm.notify(1, notification("Pairing with ${state.macName}…"))
                    SessionState.Unpaired, is SessionState.Failed -> stopSelf()
                }
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int) = START_STICKY
    override fun onBind(intent: Intent?) = null

    override fun onDestroy() {
        AutoClipboard.stop(this)
        scope.cancel()
        super.onDestroy()
    }

    private fun notification(text: String): Notification {
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_menu_share)
            .setContentTitle("Seam")
            .setContentText(text)
            .setContentIntent(open)
            .setOngoing(true)
            .build()
    }

    companion object {
        private const val CHANNEL = "connection"

        fun start(context: Context) = context.startForegroundService(Intent(context, ConnectionService::class.java))
    }
}
