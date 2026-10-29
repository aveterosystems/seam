// SPDX-License-Identifier: AGPL-3.0-only
package com.avetero.seam

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.avetero.seam.feature.clipboard.AutoClipboard

/**
 * After the phone restarts: Seam starts again by itself, and, if automatic clipboard was set up,
 * asks once to be opened. Android asks again after every restart whether Seam may read the system
 * log, and only while Seam is in front, so the notification opens it (docs: android-limits.md,
 * spike results).
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        val app = context.applicationContext as SeamApplication
        if (!app.session.paired) return
        ConnectionService.start(context)
        if (AutoClipboard.available(context)) remind(context)
    }

    companion object {
        private const val CHANNEL = "reminders"
        const val NOTIFICATION_ID = 2

        private fun remind(context: Context) {
            val nm = context.getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(NotificationChannel(CHANNEL, "Reminders", NotificationManager.IMPORTANCE_LOW))
            val open = PendingIntent.getActivity(
                context, 0, Intent(context, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            nm.notify(
                NOTIFICATION_ID,
                Notification.Builder(context, CHANNEL)
                    .setSmallIcon(R.drawable.ic_seam_mark)
                    .setContentTitle("Tap to turn automatic clipboard back on")
                    .setContentText("After a restart, Android asks once more whether Seam may notice copies")
                    .setContentIntent(open)
                    .setAutoCancel(true)
                    .build(),
            )
        }

        /** Seam was opened: the reminder has done its job. */
        fun dismiss(context: Context) = context.getSystemService(NotificationManager::class.java).cancel(NOTIFICATION_ID)
    }
}
