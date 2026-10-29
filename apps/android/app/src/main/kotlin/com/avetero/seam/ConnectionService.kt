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
        screenOn = getSystemService(PowerManager::class.java).isInteractive
        charging = getSystemService(BatteryManager::class.java).isCharging
        session.setScreenOn(screenOn)
        session.setCharging(charging)
        registerReceiver(power, IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_POWER_CONNECTED)
            addAction(Intent.ACTION_POWER_DISCONNECTED)
        })
        scope.launch {
            session.state.collect { state ->
                updateWake(state)
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

    private var screenOn = true
    private var charging = false

    /**
     * With the screen off Android suspends the CPU within seconds, and the session's one-minute idle
     * check would then wait for an unrelated wake-up, leaving the connection open. So while a
     * connection is open with the screen off on battery, stay awake just long enough for that check,
     * and let go the moment the session goes Idle (SR-8-02).
     */
    private fun updateWake(state: SessionState) {
        val needed = !screenOn && !charging && (state is SessionState.Connected || state is SessionState.Connecting)
        if (needed) idleWake.acquire(IDLE_CHECK_MS) else if (idleWake.isHeld) idleWake.release()
    }

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
                Intent.ACTION_SCREEN_OFF -> session.setScreenOn(false)
                Intent.ACTION_POWER_CONNECTED -> session.setCharging(true)
                Intent.ACTION_POWER_DISCONNECTED -> session.setCharging(false)
            }
            when (intent.action) {
                Intent.ACTION_SCREEN_ON, Intent.ACTION_SCREEN_OFF -> screenOn = intent.action == Intent.ACTION_SCREEN_ON
                else -> charging = intent.action == Intent.ACTION_POWER_CONNECTED
            }
            updateWake(session.state.value)
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
            .setSmallIcon(R.drawable.ic_seam_mark)
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
