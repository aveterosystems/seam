// SPDX-License-Identifier: AGPL-3.0-only
package com.avetero.seam

import android.app.Application
import android.content.Context
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.longClick
import androidx.test.core.app.ApplicationProvider
import com.avetero.seam.feature.MacStatus
import com.avetero.seam.feature.clipboard.FromMac
import com.avetero.seam.feature.notifications.NotificationsFeature
import com.avetero.seam.session.ConnectionSettings
import com.avetero.seam.session.SessionState
import com.github.takahirom.roborazzi.captureScreenRoboImage
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.BeforeClass
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Renders every screen state with the prototype's sample data, light and dark, at 360 dp, to
 * put beside docs/design/prototypes/android-app.html. Runs only with `-Pscreens`.
 */
@RunWith(ParameterizedRobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w360dp-h800dp-xxhdpi", application = Application::class)
class ScreensTest(private val dark: Boolean) {
    companion object {
        @JvmStatic @ParameterizedRobolectricTestRunner.Parameters(name = "dark={0}")
        fun themes() = listOf(arrayOf<Any>(false), arrayOf<Any>(true))

        // Skips before the compose rule starts its activity, which only debug builds declare
        @JvmStatic @BeforeClass fun onlyWithScreens() = assumeTrue(System.getProperty("seam.screens") != null)

        /** The prototype's ten apps and their modes. */
        val SAMPLE_APPS = """{"enabled":true,"dismissBoth":true,"quiet":false,"pausedUntil":0,"apps":[""" +
            listOf("Calendar" to "On", "Duolingo" to "Off", "Gmail" to "TitleOnly", "Instagram" to "Off", "Messages" to "On",
                "Monzo" to "TitleOnly", "Slack" to "On", "Spotify" to "Off", "Uber" to "Off", "WhatsApp" to "On")
                .joinToString(",") { (n, m) -> """{"id":"demo.${n.lowercase()}","name":"$n","mode":"$m","reply":false}""" } + "]}"
    }

    @get:Rule val rule = createComposeRule()
    private val out = System.getProperty("seam.screens")
    private val now = System.currentTimeMillis()
    private lateinit var notifications: NotificationsFeature

    @Before fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.getSharedPreferences("notifications", Context.MODE_PRIVATE).edit().putString("prefs", SAMPLE_APPS).commit()
        notifications = NotificationsFeature(context, object : com.avetero.seam.session.SessionApi { override fun send(payload: com.avetero.seam.protocol.v1.Payload) {} })
        rule.mainClock.autoAdvance = false // the hero turns forever: time moves only when told
    }

    private fun shot(name: String, settle: Long = 2500) {
        rule.mainClock.advanceTimeBy(settle)
        captureScreenRoboImage("$out/$name${if (dark) "-dark" else ""}.png")
    }

    private fun show(content: @Composable () -> Unit) {
        if (dark) RuntimeEnvironment.setQualifiers("+night")
        rule.setContent {
            SeamTheme {
                val snacks = remember { Snacks() }
                CompositionLocalProvider(LocalSnacks provides snacks) {
                    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
                        Box(Modifier.fillMaxSize()) {
                            content()
                            SnackHost(snacks, Modifier.align(Alignment.BottomCenter))
                        }
                    }
                }
            }
        }
    }

    @Composable
    private fun home(look: LinkLook, clip: Boolean = false, update: Boolean = false) = SeamScreen(
        state = SessionState.Connected("MacBook Pro"), scanError = null, toConfirm = null,
        onConfirm = {}, onCancelConfirm = {}, onScan = {},
        onSendClipboard = { SendFeedback(label = "✓ Sent to Mac") },
        fromMac = if (clip) FromMac("harbour-lights-42", now) else null, now = now,
        mac = MacStatus(64, false, now), notifications = notifications, clipboard = ClipboardStatus.Automatic,
        update = if (update) Release("0.3", "", "", "") else null, look = look,
    )

    @Composable
    private fun settings() = SettingsScreen(
        "MacBook Pro", ClipboardStatus.Automatic, "482913", "0.2.3 (7, full)", {}, {},
        notifications = notifications, connection = ConnectionSettings(), pairedAt = 1_789_380_000_000L,
    )

    @Test fun homeWifi() { show { home(LinkLook.SameWifi) }; shot("home-wifi") }
    @Test fun homeTor() { show { home(LinkLook.ThroughTor) }; shot("home-tor") }
    @Test fun homeOffline() { show { home(LinkLook.Reconnecting) }; shot("home-offline") }
    @Test fun homePaused() { show { home(LinkLook.paused(now + 30 * 60_000L, now)) }; shot("home-paused") }
    @Test fun homeClip() { show { home(LinkLook.SameWifi, clip = true) }; shot("home-clip") }
    @Test fun homeUpdate() { show { home(LinkLook.SameWifi, update = true) }; shot("home-update") }

    @Test fun pauseSheet() {
        show { home(LinkLook.SameWifi) }
        rule.mainClock.advanceTimeBy(500)
        rule.onNodeWithContentDescription("Pause").performClick()
        shot("pause-sheet")
    }

    @Test fun settingsTop() { show { settings() }; shot("settings") }

    @Test fun settingsScrolled() {
        show { settings() }
        rule.mainClock.advanceTimeBy(500)
        rule.onNodeWithText("End-to-end encrypted").performScrollTo()
        shot("settings-scrolled")
    }

    @Test fun forgetDialog() {
        show { settings() }
        rule.mainClock.advanceTimeBy(500)
        rule.onNodeWithText("Forget").performClick()
        shot("forget")
    }

    @Test fun notificationsList() { show { NotificationsScreen(notifications) {} }; shot("notifications") }

    @Test fun modeSheet() {
        show { NotificationsScreen(notifications) {} }
        rule.mainClock.advanceTimeBy(500)
        rule.onNodeWithText("Calendar").performClick()
        shot("mode-sheet")
    }

    @Test fun selecting() {
        show { NotificationsScreen(notifications) {} }
        rule.mainClock.advanceTimeBy(500)
        rule.onNodeWithText("Calendar").performTouchInput { longClick() }
        rule.mainClock.advanceTimeBy(300)
        rule.onNodeWithText("Gmail").performClick()
        shot("selecting")
    }

}
