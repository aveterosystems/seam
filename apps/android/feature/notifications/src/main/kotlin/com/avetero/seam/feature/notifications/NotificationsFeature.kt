// SPDX-License-Identifier: AGPL-3.0-only
package com.avetero.seam.feature.notifications

import android.Manifest
import android.app.KeyguardManager
import android.app.Notification
import android.app.NotificationManager
import android.app.RemoteInput
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.service.notification.NotificationListenerService.Ranking
import android.service.notification.NotificationListenerService.RankingMap
import android.service.notification.StatusBarNotification
import com.avetero.seam.protocol.v1.NotificationAction
import com.avetero.seam.protocol.v1.NotificationButton
import com.avetero.seam.protocol.v1.NotificationSettingsChange
import com.avetero.seam.protocol.v1.Payload
import com.avetero.seam.session.Feature
import com.avetero.seam.session.SessionApi
import kotlinx.coroutines.flow.StateFlow
import java.io.ByteArrayOutputStream
import com.avetero.seam.protocol.v1.Notification as Mirrored

/**
 * The phone's notifications on the Mac: new ones go over, and a dismiss, reply or button press on
 * either side happens on both. The phone keeps the settings ([prefs]) and filters before anything
 * leaves it; the Mac sees and edits them. Listener callbacks and actions run on the main thread.
 */
class NotificationsFeature(context: Context, private val session: SessionApi) : Feature {
    override val handles = setOf(Payload.BodyCase.NOTIFICATION_ACTION, Payload.BodyCase.NOTIFICATION_SETTINGS_CHANGE)

    private val app = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private val rules = NotificationRules(app.packageName)
    private val labels = HashMap<String, String>()
    private val store = PrefsStore(app)
    /** Icons the Mac asked for, sent with the next settings. */
    private val iconsWanted = HashSet<String>()

    /** For the settings screen. */
    val prefs: StateFlow<NotificationPrefs> = store.state

    fun setEnabled(on: Boolean) = change { it.copy(enabled = on) }
    fun setDismissBoth(on: Boolean) = change { it.copy(dismissBoth = on) }
    fun setQuietWhileUsingPhone(on: Boolean) = change { it.copy(quietWhileUsingPhone = on) }
    /** Until [until] (unix ms); 0 resumes. */
    fun pause(until: Long) = change { it.copy(pausedUntil = until) }
    fun setMode(apps: Set<String>, mode: AppMode) = change { it.withMode(apps, mode) }

    private fun change(f: (NotificationPrefs) -> NotificationPrefs) {
        if (store.update(f)) sendSettings()
    }

    init {
        instance = this
    }

    /**
     * Lists every app that can show notifications, so the settings have them all from the start
     * (not only those that posted since): apps allowed to notify, plus whatever is showing now.
     */
    internal fun listenerConnected(service: MirrorService) {
        val pm = app.packageManager
        val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val allowed = pm.queryIntentActivities(launcher, 0).map { it.activityInfo.applicationInfo }.filter {
            // Before Android 13 apps notified without asking, so older apps count unless denied by the user
            it.targetSdkVersion < 33 || pm.checkPermission(Manifest.permission.POST_NOTIFICATIONS, it.packageName) == PackageManager.PERMISSION_GRANTED
        }.map { it.packageName }
        val showing = runCatching { service.activeNotifications }.getOrNull().orEmpty().filter { !it.isOngoing }.map { it.packageName }
        val apps = (allowed + showing).distinct().filter { it != app.packageName }
        change { p -> apps.fold(p) { acc, pkg -> acc.seen(pkg, label(pkg), canReply = false) } }
    }

    internal fun posted(sbn: StatusBarNotification, rankingMap: RankingMap) {
        val n = sbn.notification
        val extras = n.extras
        val ranking = Ranking()
        val posted = Posted(
            key = sbn.key,
            app = sbn.packageName,
            title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty(),
            text = (extras.getCharSequence(Notification.EXTRA_BIG_TEXT) ?: extras.getCharSequence(Notification.EXTRA_TEXT))?.toString().orEmpty(),
            ongoing = sbn.isOngoing || n.flags and Notification.FLAG_FOREGROUND_SERVICE != 0,
            groupSummary = n.flags and Notification.FLAG_GROUP_SUMMARY != 0,
            media = extras.getString(Notification.EXTRA_TEMPLATE)?.contains("MediaStyle") == true,
            silent = rankingMap.getRanking(sbn.key, ranking) && ranking.importance < NotificationManager.IMPORTANCE_DEFAULT,
            secret = n.visibility == Notification.VISIBILITY_SECRET,
            alertOnce = n.flags and Notification.FLAG_ONLY_ALERT_ONCE != 0,
        )
        if (!rules.news(posted)) return
        val reply = replyAction(n)
        change { it.seen(sbn.packageName, label(sbn.packageName), canReply = reply != null) }
        val prefs = store.state.value
        if (!prefs.enabled || prefs.paused(System.currentTimeMillis()) || (prefs.quietWhileUsingPhone && phoneInUse())) return
        val mirror = rules.decide(posted, prefs.mode(sbn.packageName))
        if (mirror == Mirror.Skip) return
        val out = Mirrored.newBuilder()
            .setKey(sbn.key)
            .setAppId(sbn.packageName)
            .setAppName(label(sbn.packageName))
            .setTitle(posted.title.take(MAX_TITLE))
            .setText(if (mirror == Mirror.Full) posted.text.take(MAX_TEXT) else "")
            .setPostedAt(sbn.postTime)
            .setCanReply(reply != null)
            .setConversationId(n.shortcutId.orEmpty())
        // Button titles can carry content ("Copy 482913"): Title only sends none (SR-19-02)
        if (mirror == Mirror.Full) n.actions.orEmpty().forEachIndexed { i, a ->
            if (a !== reply && !a.title.isNullOrBlank() && out.buttonsCount < MAX_BUTTONS) {
                out.addButtons(NotificationButton.newBuilder().setId(i.toString()).setTitle(a.title.toString()))
            }
        }
        session.send(Payload.newBuilder().setNotification(out).build())
    }

    internal fun removed(key: String) {
        if (!rules.removed(key) || !store.state.value.dismissBoth) return
        session.send(Payload.newBuilder().setNotificationAction(NotificationAction.newBuilder().setKey(key).setDismissed(true)).build())
    }

    override fun reset() {
        main.post { iconsWanted.clear() } // what the old Mac asked for
    }

    override fun onConnected(session: SessionApi) {
        main.post { sendSettings() } // so the Mac's pane always shows what the phone does
    }

    override fun receive(payload: Payload, fromPeer: String) {
        when (payload.bodyCase) {
            Payload.BodyCase.NOTIFICATION_ACTION -> payload.notificationAction.let { main.post { act(it) } }
            Payload.BodyCase.NOTIFICATION_SETTINGS_CHANGE -> payload.notificationSettingsChange.let { main.post { changedOnMac(it) } }
            else -> Unit
        }
    }

    private fun changedOnMac(c: NotificationSettingsChange) {
        val listed = store.state.value.apps.map { it.id }.toSet()
        iconsWanted += c.needIconsList.filter { it in listed }.take(MAX_ICONS)
        store.update { it.applying(c) }
        sendSettings() // always: it confirms the change, and carries any icons asked for
    }

    private fun sendSettings() {
        val proto = store.state.value.toProto { if (it in iconsWanted) icon(it) else null }
        iconsWanted.clear()
        session.send(Payload.newBuilder().setNotificationSettings(proto).build())
    }

    /** Unlocked with the screen on: someone is looking at the phone itself. */
    private fun phoneInUse(): Boolean =
        app.getSystemService(PowerManager::class.java).isInteractive && !app.getSystemService(KeyguardManager::class.java).isKeyguardLocked

    /** The app's launcher icon as a small PNG, for the Mac's banners and settings. */
    private fun icon(pkg: String): ByteArray? = runCatching {
        val d = app.packageManager.getApplicationIcon(pkg)
        val bitmap = Bitmap.createBitmap(ICON_PX, ICON_PX, Bitmap.Config.ARGB_8888)
        d.setBounds(0, 0, ICON_PX, ICON_PX)
        d.draw(Canvas(bitmap))
        ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
    }.getOrNull()

    private fun act(a: NotificationAction) {
        val service = MirrorService.active ?: return
        val sbn = runCatching { service.getActiveNotifications(arrayOf(a.key)) }.getOrNull()?.firstOrNull() ?: return // already gone
        // Only what the Mac was sent, from an app still on: never an app the user kept off the Mac (SR-19-01)
        val prefs = store.state.value
        if (!rules.mirrored(a.key) || !prefs.enabled || prefs.mode(sbn.packageName) == AppMode.Off) return
        val n = sbn.notification
        when (a.actionCase) {
            NotificationAction.ActionCase.DISMISSED -> {
                if (!store.state.value.dismissBoth) return
                rules.removed(a.key) // so its removal isn't echoed back to the Mac
                service.cancelNotification(a.key)
            }
            NotificationAction.ActionCase.REPLY_TEXT -> replyAction(n)?.let { reply(it, a.replyText) }
            NotificationAction.ActionCase.BUTTON_ID ->
                n.actions?.getOrNull(a.buttonId.toIntOrNull() ?: -1)?.let { runCatching { it.actionIntent.send() } }
            else -> Unit
        }
    }

    /** Fires the app's own reply action, as if typed into the phone's notification shade. */
    private fun reply(action: Notification.Action, text: String) {
        val input = action.remoteInputs.first { it.allowFreeFormInput }
        val intent = Intent().addFlags(Intent.FLAG_RECEIVER_FOREGROUND)
        RemoteInput.addResultsToIntent(arrayOf(input), intent, Bundle().apply { putCharSequence(input.resultKey, text) })
        RemoteInput.setResultsSource(intent, RemoteInput.SOURCE_FREE_FORM_INPUT)
        runCatching { action.actionIntent.send(app, 0, intent) }
    }

    private fun replyAction(n: Notification): Notification.Action? {
        val candidates = n.actions.orEmpty().filter { a -> a.remoteInputs.orEmpty().any { it.allowFreeFormInput } }
        return candidates.firstOrNull { it.semanticAction == Notification.Action.SEMANTIC_ACTION_REPLY } ?: candidates.firstOrNull()
    }

    private fun label(pkg: String): String = labels.getOrPut(pkg) {
        runCatching { app.packageManager.run { getApplicationLabel(getApplicationInfo(pkg, 0)).toString() } }.getOrDefault(pkg)
    }

    companion object {
        @Volatile internal var instance: NotificationsFeature? = null
        private const val MAX_TITLE = 200
        private const val MAX_TEXT = 4000
        private const val MAX_BUTTONS = 3
        private const val ICON_PX = 64
        /** Icons per settings message: keeps each message well under the size limit. */
        private const val MAX_ICONS = 40

        fun accessGranted(context: Context): Boolean =
            context.getSystemService(NotificationManager::class.java)
                .isNotificationListenerAccessGranted(ComponentName(context, MirrorService::class.java))

        /** Android's screen for granting Seam notification access. */
        fun accessIntent(context: Context): Intent =
            Intent(Settings.ACTION_NOTIFICATION_LISTENER_DETAIL_SETTINGS)
                .putExtra(Settings.EXTRA_NOTIFICATION_LISTENER_COMPONENT_NAME, ComponentName(context, MirrorService::class.java).flattenToString())
    }
}
