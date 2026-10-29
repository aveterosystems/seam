// SPDX-License-Identifier: AGPL-3.0-only
package com.avetero.seam

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.BroadcastReceiver
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.os.BatteryManager
import android.os.PowerManager
import com.avetero.seam.feature.clipboard.AutoClipboard
import com.avetero.seam.session.SessionState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Keeps the process alive while paired (for the screen and charging signals the Session needs, and
 * automatic clipboard), with a quiet, minimum-priority notification Android requires. Battery-friendly wake-ups replace this later. Also hosts automatic clipboard.
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
        // Decision 0005: the session connects on screen on and stays connected while charging
        session.setScreenOn(getSystemService(PowerManager::class.java).isInteractive)
        session.setCharging(getSystemService(BatteryManager::class.java).isCharging)
        registerReceiver(power, IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_POWER_CONNECTED)
            addAction(Intent.ACTION_POWER_DISCONNECTED)
        })
        scope.launch {
            session.state.collect { state ->
                when (state) {
                    is SessionState.Connected -> nm.notify(1, notification("Connected to ${state.macName}"))
                    is SessionState.Connecting -> nm.notify(1, notification("Reconnecting to ${state.macName}…"))
                    is SessionState.Idle -> nm.notify(1, notification("Paired with ${state.macName}"))
                    is SessionState.Pairing -> nm.notify(1, notification("Pairing with ${state.macName}…"))
                    SessionState.Unpaired, is SessionState.Failed -> stopSelf()
                }
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int) = START_STICKY
    override fun onBind(intent: Intent?) = null

    private val idleWake by lazy {
        getSystemService(PowerManager::class.java).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "seam:idle-check").apply {
            setReferenceCounted(false)
        }
    }

    private val power = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val session = (application as SeamApplication).session
            when (intent.action) {
                Intent.ACTION_SCREEN_ON -> session.setScreenOn(true)
                Intent.ACTION_SCREEN_OFF -> {
                    // With the screen off Android suspends the CPU within seconds, and the session's
                    // one-minute idle check would only run at the next unrelated wake-up, leaving the
                    // connection open. Stay awake just long enough for it, then let go by itself.
                    idleWake.acquire(IDLE_CHECK_MS)
                    session.setScreenOn(false)
                }
                Intent.ACTION_POWER_CONNECTED -> session.setCharging(true)
                Intent.ACTION_POWER_DISCONNECTED -> session.setCharging(false)
            }
        }
    }

    override fun onDestroy() {
        runCatching { unregisterReceiver(power) }
        if (idleWake.isHeld) idleWake.release()
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
        /** The session's one-minute idle window (Session.IDLE_MS) plus a little margin. */
        private const val IDLE_CHECK_MS = 65_000L

        fun start(context: Context) = context.startForegroundService(Intent(context, ConnectionService::class.java))
    }
}
