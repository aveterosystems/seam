// SPDX-License-Identifier: AGPL-3.0-only
package com.avetero.seam

import android.app.AlarmManager
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
import com.avetero.seam.feature.clipboard.ClipboardFeature
import com.avetero.seam.session.CpuPlan
import com.avetero.seam.session.SessionState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
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
        startForeground(1, notification("Starting"), ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
        val app = application as SeamApplication
        val session = app.session
        AutoClipboard.start(this, session.feature<ClipboardFeature>()) // full builds, once the Mac has granted log access
        // The session connects on screen on and stays connected while charging
        session.setScreenOn(getSystemService(PowerManager::class.java).isInteractive)
        session.setCharging(getSystemService(BatteryManager::class.java).isCharging)
        registerReceiver(power, IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_POWER_CONNECTED)
            addAction(Intent.ACTION_POWER_DISCONNECTED)
        })
        scope.launch { session.cpu.collect(::followCpu) }
        scope.launch {
            combine(session.state, session.settings.map { it.pausedUntil }.distinctUntilChanged(), ::Pair).collectLatest { (state, pausedUntil) ->
                SeamTile.refresh(this@ConnectionService)
                val now = System.currentTimeMillis()
                val paused = pausedUntil > now
                when (state) {
                    is SessionState.Connected, is SessionState.Connecting, is SessionState.Idle -> {
                        val name = (state as? SessionState.Connected)?.macName ?: (state as? SessionState.Connecting)?.macName
                            ?: (state as SessionState.Idle).macName
                        val text = when {
                            paused -> pausedLabel(pausedUntil, now)
                            state is SessionState.Connected -> "Connected to $name"
                            state is SessionState.Connecting -> "Reconnecting to $name"
                            else -> "Paired with $name"
                        }
                        nm.notify(1, notification(text, *if (paused) arrayOf(resumeAction, settingsAction) else running))
                        // A timed pause ends by itself: show that when it does (display only, so a late tick is fine)
                        if (paused && pausedUntil != Long.MAX_VALUE) {
                            delay(pausedUntil - now)
                            nm.notify(1, notification("Paired with $name", *running))
                            SeamTile.refresh(this@ConnectionService)
                        }
                    }
                    is SessionState.Pairing -> nm.notify(1, notification("Pairing with ${state.macName}"))
                    SessionState.Unpaired, is SessionState.Failed -> stopSelf()
                }
            }
        }
    }

    // Android shows at most three. Paused, a clipboard send would go nowhere, so it's not offered
    private val running by lazy { arrayOf(screenAction("Send clipboard", NotificationActionActivity.ACTION_SEND_CLIPBOARD), screenAction("Pause", NotificationActionActivity.ACTION_PAUSE), settingsAction) }
    private val resumeAction by lazy { action("Resume", ACTION_RESUME) }
    private val settingsAction by lazy {
        val open = Intent(this, MainActivity::class.java).putExtra(MainActivity.EXTRA_OPEN_SETTINGS, true)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        Notification.Action.Builder(null, "Settings", PendingIntent.getActivity(this, 2, open, PendingIntent.FLAG_IMMUTABLE))
            .setAuthenticationRequired(true).build()
    }

    /** Done by the service itself: no screen needed. */
    private fun action(title: String, intentAction: String): Notification.Action {
        val intent = PendingIntent.getService(this, intentAction.hashCode(), Intent(this, ConnectionService::class.java).setAction(intentAction), PendingIntent.FLAG_IMMUTABLE)
        // Unlock first: a locked phone's holder can't pause, resume or send its clipboard (SR-32-04)
        return Notification.Action.Builder(null, title, intent).setAuthenticationRequired(true).build()
    }

    /** Needs a screen: reading the clipboard, or showing the pause choices. */
    private fun screenAction(title: String, intentAction: String): Notification.Action {
        val intent = PendingIntent.getActivity(this, intentAction.hashCode(), NotificationActionActivity.intent(this, intentAction), PendingIntent.FLAG_IMMUTABLE)
        // Unlock first: a locked phone's holder can't pause, resume or send its clipboard (SR-32-04)
        return Notification.Action.Builder(null, title, intent).setAuthenticationRequired(true).build()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // The alarm from a CpuPlan.WakeAt: the CPU is awake now, so let the session look again
        val session = (application as SeamApplication).session
        when (intent?.action) {
            ACTION_CHECK_IDLE -> {
                // The alarm keeps the CPU up only while it's delivered: hold it until the session's
                // own plan takes over (SR-31-02), which replaces or releases this
                idleWake.acquire(ALARM_HANDOFF_MS)
                session.checkIdle()
            }
            // From the notification: only the phone pauses and resumes
            ACTION_RESUME -> session.resume()
        }
        return START_STICKY
    }
    override fun onBind(intent: Intent?) = null

    /**
     * With the screen off Android suspends the CPU within seconds, and the session's idle check
     * would then wait for an unrelated wake-up, leaving the connection open (rule 8, SR-8-02). The
     * session says what the CPU must do; this only carries it out. A check due within a minute
     * holds the CPU awake until it has run. One further off lets the CPU sleep and sets an alarm
     * to wake it a minute before: a five-minute reply window never holds the CPU for five minutes.
     */
    private fun followCpu(plan: CpuPlan) {
        val alarms = getSystemService(AlarmManager::class.java)
        alarms.cancel(idleCheckAlarm)
        when (plan) {
            CpuPlan.None -> if (idleWake.isHeld) idleWake.release()
            // The session never asks for more than a minute and a bit; at least a second so it runs
            is CpuPlan.Hold -> idleWake.acquire((plan.until - System.currentTimeMillis()).coerceAtLeast(1_000))
            is CpuPlan.WakeAt -> {
                if (idleWake.isHeld) idleWake.release()
                // Inexact but allowed in Doze; no exact-alarm permission needed. Deep Doze can defer it by
                // minutes, by which time Doze has cut the network anyway (SR-31-04). Session time is wall-clock time
                alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, plan.at, idleCheckAlarm)
            }
        }
    }

    private val idleCheckAlarm by lazy {
        val intent = Intent(this, ConnectionService::class.java).setAction(ACTION_CHECK_IDLE)
        PendingIntent.getService(this, 1, intent, PendingIntent.FLAG_IMMUTABLE)
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
        }
    }

    override fun onDestroy() {
        runCatching { unregisterReceiver(power) }
        if (idleWake.isHeld) idleWake.release()
        getSystemService(AlarmManager::class.java).cancel(idleCheckAlarm)
        AutoClipboard.stop(this)
        scope.cancel()
        super.onDestroy()
    }

    private fun notification(text: String, vararg actions: Notification.Action): Notification {
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_seam_mark)
            .setContentTitle("Seam")
            .setContentText(text)
            .setContentIntent(open)
            .setOngoing(true)
            .apply { actions.forEach(::addAction) }
            .build()
    }

    companion object {
        private const val CHANNEL = "connection"
        private const val ACTION_CHECK_IDLE = "com.avetero.seam.CHECK_IDLE"
        private const val ACTION_RESUME = "com.avetero.seam.RESUME"
        /** Long enough for the session to look again and publish its plan. */
        private const val ALARM_HANDOFF_MS = 10_000L

        fun start(context: Context) = context.startForegroundService(Intent(context, ConnectionService::class.java))
    }
}
