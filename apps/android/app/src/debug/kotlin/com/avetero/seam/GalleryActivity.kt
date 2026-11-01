// SPDX-License-Identifier: AGPL-3.0-only
package com.avetero.seam

import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.avetero.seam.feature.MacStatus
import com.avetero.seam.feature.clipboard.FromMac
import com.avetero.seam.feature.notifications.NotificationsFeature
import com.avetero.seam.protocol.v1.Payload
import com.avetero.seam.session.SessionApi
import com.avetero.seam.session.ConnectionSettings
import com.avetero.seam.session.SessionState

/**
 * Debug builds only: the real screens with the prototype's sample data, so each state can be put
 * beside docs/design/prototypes/android-app.html. Nothing here talks to a Mac.
 * `am start -n com.avetero.seam.full/com.avetero.seam.GalleryActivity --es link wifi|tor|offline|paused
 *  --es page home|settings|notifications --ez clip true --ez update true`; it follows the system theme
 */
class GalleryActivity : ComponentActivity() {
    private companion object {
        /** The prototype's ten apps and their modes. */
        val SAMPLE_APPS = """{"enabled":true,"dismissBoth":true,"quiet":false,"pausedUntil":0,"apps":[""" +
            listOf("Calendar" to "On", "Duolingo" to "Off", "Gmail" to "TitleOnly", "Instagram" to "Off", "Messages" to "On",
                "Monzo" to "TitleOnly", "Slack" to "On", "Spotify" to "Off", "Uber" to "Off", "WhatsApp" to "On")
                .joinToString(",") { (n, m) -> """{"id":"demo.${n.lowercase()}","name":"$n","mode":"$m","reply":false}""" } + "]}"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // Stays on while it's being photographed; only this window, never the phone's settings
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val app = application as SeamApplication
        // The sample apps live in their own preferences, so the real notification settings are never touched
        val sandbox = object : ContextWrapper(applicationContext) {
            override fun getApplicationContext(): Context = this
            override fun getSharedPreferences(name: String, mode: Int): SharedPreferences = super.getSharedPreferences("gallery_$name", mode)
        }
        sandbox.getSharedPreferences("notifications", MODE_PRIVATE).edit().putString("prefs", SAMPLE_APPS).commit()
        val notifications = NotificationsFeature(sandbox, object : SessionApi { override fun send(payload: Payload) {} })
        val now = System.currentTimeMillis()
        val clipboard = intent.getStringExtra("clipboard")?.let(ClipboardStatus::valueOf) ?: ClipboardStatus.Automatic
        val start = when (intent.getStringExtra("page")) {
            "settings" -> listOf(Page.Home, Page.Settings)
            "notifications" -> listOf(Page.Home, Page.Notifications)
            else -> listOf(Page.Home)
        }
        setContent {
            SeamTheme {
                val snacks = remember { Snacks() }
                var link by remember { mutableStateOf(intent.getStringExtra("link") ?: "wifi") }
                var pages by remember { mutableStateOf(start) }
                var wifi by remember { mutableStateOf(true) }
                var tor by remember { mutableStateOf(true) }
                val look = when (link) {
                    "tor" -> LinkLook.ThroughTor
                    "offline" -> LinkLook.Reconnecting
                    "paused" -> LinkLook.paused(now + 30 * 60_000L, now)
                    else -> LinkLook.SameWifi
                }
                CompositionLocalProvider(LocalSnacks provides snacks) {
                    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
                        Box(Modifier.fillMaxSize()) {
                            Box(Modifier.safeDrawingPadding()) {
                                Pages(pages, onPop = { pages = pages.dropLast(1) }) { page, back ->
                                    when (page) {
                                        Page.Notifications -> NotificationsScreen(notifications, back)
                                        Page.Settings -> SettingsScreen(
                                            "MacBook Pro", clipboard, "482913", "0.2.3 (7, full)", {}, back,
                                            app.updater, notifications, { pages = pages + Page.Notifications },
                                            ConnectionSettings(wifi = wifi, tor = tor), { w, t -> wifi = w; tor = t },
                                            pairedAt = 1_789_380_000_000L,
                                        )
                                        Page.Home -> SeamScreen(
                                            state = SessionState.Connected("MacBook Pro"), scanError = null, toConfirm = null,
                                            onConfirm = {}, onCancelConfirm = {}, onScan = {},
                                            onSendClipboard = { if (look.connected) SendFeedback(label = "✓ Sent to Mac") else SendFeedback(snack = "Will send when MacBook Pro is back") },
                                            fromMac = if (intent.getBooleanExtra("clip", false)) FromMac("harbour-lights-42", now) else null,
                                            now = now, onPasteFromMac = { true },
                                            onOpenSettings = { pages = pages + Page.Settings },
                                            onOpenNotifications = { pages = pages + Page.Notifications },
                                            mac = MacStatus(64, false, now), notifications = notifications,
                                            clipboard = clipboard,
                                            update = if (intent.getBooleanExtra("update", false)) Release("0.3", "", "", "") else null,
                                            look = look, onPause = { link = "paused" }, onResume = { link = "wifi" },
                                        )
                                    }
                                }
                            }
                            SnackHost(snacks, Modifier.align(Alignment.BottomCenter).safeDrawingPadding())
                        }
                    }
                }
            }
        }
    }
}
