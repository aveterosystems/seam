// SPDX-License-Identifier: AGPL-3.0-only
package com.avetero.seam.feature.notifications

import android.content.Context
import com.avetero.seam.protocol.v1.NotificationApp
import com.avetero.seam.protocol.v1.NotificationMode
import com.avetero.seam.protocol.v1.NotificationSettingsChange
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONArray
import org.json.JSONObject
import com.avetero.seam.protocol.v1.NotificationSettings as SettingsProto

enum class AppMode { On, TitleOnly, Off }

/** One app that can notify, and what reaches the Mac from it. Off until the user turns it on. */
data class AppEntry(val id: String, val name: String, val mode: AppMode = AppMode.Off, val canReply: Boolean = false)

/** The phone's notification settings: the phone filters by these before anything leaves it. */
data class NotificationPrefs(
    val enabled: Boolean = true,
    val dismissBoth: Boolean = true,
    val quietWhileUsingPhone: Boolean = false,
    val apps: List<AppEntry> = emptyList(),
    /** Unix ms: nothing goes to the Mac before then. 0 when not paused. */
    val pausedUntil: Long = 0,
) {
    fun paused(now: Long) = now < pausedUntil

    fun mode(app: String): AppMode = apps.firstOrNull { it.id == app }?.mode ?: AppMode.Off

    /** An app posted something worth mirroring: list it, or note it can reply. */
    fun seen(id: String, name: String, canReply: Boolean): NotificationPrefs {
        val old = apps.firstOrNull { it.id == id }
        val new = (old ?: AppEntry(id, name)).let { it.copy(name = name, canReply = it.canReply || canReply) }
        if (new == old) return this
        return copy(apps = (apps.filter { it.id != id } + new).sortedBy { it.name.lowercase() })
    }

    fun withMode(ids: Set<String>, mode: AppMode) = copy(apps = apps.map { if (it.id in ids) it.copy(mode = mode) else it })

    /** A change made on the Mac. Apps the phone doesn't list are ignored. */
    fun applying(c: NotificationSettingsChange): NotificationPrefs {
        var p = this
        if (c.hasEnabled()) p = p.copy(enabled = c.enabled)
        if (c.hasDismissBoth()) p = p.copy(dismissBoth = c.dismissBoth)
        if (c.hasQuietWhileUsingPhone()) p = p.copy(quietWhileUsingPhone = c.quietWhileUsingPhone)
        if (c.hasPausedUntil()) p = p.copy(pausedUntil = c.pausedUntil.coerceAtLeast(0))
        for (a in c.appsList) a.mode.toMode()?.let { p = p.withMode(setOf(a.appId), it) }
        return p
    }

    fun toProto(icon: (String) -> ByteArray?): SettingsProto = SettingsProto.newBuilder()
        .setEnabled(enabled)
        .setDismissBoth(dismissBoth)
        .setQuietWhileUsingPhone(quietWhileUsingPhone)
        .setPausedUntil(pausedUntil)
        .addAllApps(apps.map { a ->
            NotificationApp.newBuilder().setAppId(a.id).setAppName(a.name).setMode(a.mode.toProto()).setCanReply(a.canReply)
                .apply { icon(a.id)?.let { setIconPng(com.google.protobuf.ByteString.copyFrom(it)) } }
                .build()
        })
        .build()

    fun toJson(): String = JSONObject()
        .put("enabled", enabled).put("dismissBoth", dismissBoth).put("quiet", quietWhileUsingPhone).put("pausedUntil", pausedUntil)
        .put("apps", JSONArray(apps.map { JSONObject().put("id", it.id).put("name", it.name).put("mode", it.mode.name).put("reply", it.canReply) }))
        .toString()

    companion object {
        fun fromJson(s: String): NotificationPrefs = runCatching {
            val o = JSONObject(s)
            val apps = o.getJSONArray("apps")
            NotificationPrefs(
                enabled = o.getBoolean("enabled"),
                dismissBoth = o.getBoolean("dismissBoth"),
                quietWhileUsingPhone = o.getBoolean("quiet"),
                pausedUntil = o.optLong("pausedUntil", 0),
                apps = (0 until apps.length()).map { i ->
                    val a = apps.getJSONObject(i)
                    AppEntry(a.getString("id"), a.getString("name"), AppMode.valueOf(a.getString("mode")), a.getBoolean("reply"))
                },
            )
        }.getOrDefault(NotificationPrefs())
    }
}

private fun AppMode.toProto() = when (this) {
    AppMode.On -> NotificationMode.NOTIFICATION_MODE_ON
    AppMode.TitleOnly -> NotificationMode.NOTIFICATION_MODE_TITLE_ONLY
    AppMode.Off -> NotificationMode.NOTIFICATION_MODE_OFF
}

private fun NotificationMode.toMode() = when (this) {
    NotificationMode.NOTIFICATION_MODE_ON -> AppMode.On
    NotificationMode.NOTIFICATION_MODE_TITLE_ONLY -> AppMode.TitleOnly
    NotificationMode.NOTIFICATION_MODE_OFF -> AppMode.Off
    else -> null
}

/** Saved in the app's private storage; the UI watches [state]. Main thread. */
internal class PrefsStore(context: Context) {
    private val prefs = context.getSharedPreferences("notifications", Context.MODE_PRIVATE)
    private val _state = MutableStateFlow(prefs.getString(KEY, null)?.let(NotificationPrefs::fromJson) ?: NotificationPrefs())
    val state: StateFlow<NotificationPrefs> = _state

    /** True if it changed. */
    fun update(f: (NotificationPrefs) -> NotificationPrefs): Boolean {
        val new = f(_state.value)
        if (new == _state.value) return false
        prefs.edit().putString(KEY, new.toJson()).apply()
        _state.value = new
        return true
    }

    private companion object {
        const val KEY = "prefs"
    }
}
