// SPDX-License-Identifier: AGPL-3.0-only
package com.avetero.seam.feature.notifications

/** A posted notification as plain values, so the rules are testable without Android. */
data class Posted(
    val key: String,
    val app: String,
    val title: String,
    val text: String,
    /** Ongoing or a foreground service: a status, not news. */
    val ongoing: Boolean = false,
    /** A group's summary: its children carry the content. */
    val groupSummary: Boolean = false,
    /** Media controls (a later feature, not a banner). */
    val media: Boolean = false,
    /** Below default importance: the phone itself doesn't make a sound or show a banner. */
    val silent: Boolean = false,
    /** The app hides it entirely on the lock screen: the Mac gets the title only. */
    val secret: Boolean = false,
    /** The app asked for updates to this notification not to alert again. */
    val alertOnce: Boolean = false,
)

enum class Mirror { Skip, Full, TitleOnly }

/** Which notifications go to the Mac, and which removals the Mac needs to hear about. Main thread only. */
class NotificationRules(private val ownApp: String) {
    // What each mirrored key last sent: a re-post with the same words is an update the Mac already shows
    private val mirrored = object : LinkedHashMap<String, String>() {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>) = size > MAX_TRACKED
    }

    /** Worth a banner at all, whatever the settings: not a status, not Seam's own, not empty. */
    fun news(p: Posted): Boolean =
        p.app != ownApp && !p.ongoing && !p.groupSummary && !p.media && !p.silent && (p.title.isNotBlank() || p.text.isNotBlank())

    fun decide(p: Posted, mode: AppMode = AppMode.On): Mirror {
        if (!news(p) || mode == AppMode.Off) return Mirror.Skip
        val words = p.title + "\u0000" + p.text
        val before = mirrored[p.key]
        if (before == words || (before != null && p.alertOnce)) return Mirror.Skip
        mirrored[p.key] = words
        return if (p.secret || mode == AppMode.TitleOnly) Mirror.TitleOnly else Mirror.Full
    }

    /** It left the phone: true if the Mac has it and should drop it too. */
    fun removed(key: String): Boolean = mirrored.remove(key) != null

    companion object {
        const val MAX_TRACKED = 500
    }
}
