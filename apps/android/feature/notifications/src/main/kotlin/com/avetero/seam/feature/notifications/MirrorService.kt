// SPDX-License-Identifier: AGPL-3.0-only
package com.avetero.seam.feature.notifications

import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification

/** Bound by the system once the user grants notification access. Hands everything to [NotificationsFeature]. */
class MirrorService : NotificationListenerService() {
    override fun onListenerConnected() {
        active = this
        NotificationsFeature.instance?.listenerConnected(this)
    }

    override fun onListenerDisconnected() {
        if (active === this) active = null
    }

    override fun onDestroy() {
        if (active === this) active = null
        super.onDestroy()
    }

    override fun onNotificationPosted(sbn: StatusBarNotification, rankingMap: RankingMap) {
        NotificationsFeature.instance?.posted(sbn, rankingMap)
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification, rankingMap: RankingMap, reason: Int) {
        NotificationsFeature.instance?.removed(sbn.key)
    }

    companion object {
        /** The bound listener, for acting on the phone's notifications. Main thread. */
        @Volatile internal var active: MirrorService? = null
    }
}
