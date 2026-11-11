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
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.test.core.app.ApplicationProvider
import com.avetero.seam.crypto.LinkLook
import com.avetero.seam.feature.MacStatus
import com.avetero.seam.feature.clipboard.FromMac
import com.avetero.seam.feature.notifications.NotificationsFeature
import com.avetero.seam.protocol.v1.Status
import com.avetero.seam.session.ConnectionSettings
import com.avetero.seam.session.PairingQr
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
import org.robolectric.Shadows.shadowOf
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
        access(true) // the usual case; notificationsNoAccess draws the other
    }

    private fun access(granted: Boolean) {
        val context = ApplicationProvider.getApplicationContext<Context>()
        shadowOf(context.getSystemService(android.app.NotificationManager::class.java))
            .setNotificationListenerAccessGranted(android.content.ComponentName(context, com.avetero.seam.feature.notifications.MirrorService::class.java), granted)
    }

    private fun shot(name: String, settle: Long = 2500) {
        rule.mainClock.advanceTimeBy(settle)
        captureScreenRoboImage("$out/$name${if (dark) "-dark" else ""}.png")
    }

    /** [fontScale] above 1 shows what large text clips. */
    private fun show(fontScale: Float = 1f, content: @Composable () -> Unit) {
        if (dark) RuntimeEnvironment.setQualifiers("+night")
        RuntimeEnvironment.setFontScale(fontScale)
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

    /**
     * Opens a sheet or dialog with [node]'s click action, which works off screen too. A modal is
     * its own window, which attaches and composes only when the looper idles; without that idle
     * before the clock moves, the capture catches it at its first frame, still invisible.
     */
    private fun open(node: SemanticsNodeInteraction, name: String) {
        rule.mainClock.advanceTimeBy(500)
        node.performSemanticsAction(SemanticsActions.OnClick)
        settle()
        shot(name, settle = 0)
    }

    /**
     * Steps and idles in turns: a modal's own window composes, starts its effects and draws only when
     * the looper idles, and a screen with nothing else animating gives the clock no frame to run them on.
     */
    private fun settle() = repeat(30) {
        rule.mainClock.advanceTimeBy(100)
        rule.waitForIdle()
    }

    /** Long-presses Calendar, then taps Gmail, as semantics actions: a gesture's timeout never fires on the paused clock. */
    private fun select() {
        rule.mainClock.advanceTimeBy(500)
        rule.onNodeWithText("Calendar").performSemanticsAction(SemanticsActions.OnLongClick)
        settle()
        rule.onNodeWithText("Gmail").performSemanticsAction(SemanticsActions.OnClick)
        settle()
    }

    @Composable
    private fun pair(state: SessionState = SessionState.Unpaired, toConfirm: PairingQr? = null, scanning: Boolean = false, cameraOn: Boolean = true, camera: @Composable (Boolean) -> Unit = {}) =
        SeamScreen(state, null, toConfirm, {}, {}, {}, scanning = scanning, cameraOn = cameraOn, camera = camera)

    @Composable
    private fun home(look: LinkLook, clip: Boolean = false, update: Boolean = false, clipboard: ClipboardStatus = ClipboardStatus.Automatic) = SeamScreen(
        state = SessionState.Connected("MacBook Pro"), scanError = null, toConfirm = null,
        onConfirm = {}, onCancelConfirm = {}, onScan = {},
        onSendClipboard = { SendFeedback(label = "✓ Sent to Mac", done = true) },
        fromMac = if (clip) FromMac("harbour-lights-42", now) else null, now = now,
        mac = MacStatus(Status.newBuilder().setBatteryPct(64).setPowerMw(9_500).setMinutesLeft(300).setStorageFreeBytes(140_000_000_000).setStorageTotalBytes(500_000_000_000).setCpuPct(12).setMemoryUsedBytes(9L shl 30).setMemoryTotalBytes(24L shl 30).build(), now), notifications = notifications, clipboard = clipboard,
        update = if (update) Release("0.3", "", "", "") else null, look = look,
    )

    @Composable
    private fun settings() = SettingsScreen(
        "MacBook Pro", ClipboardStatus.Automatic, "482913", "0.2.3 (7, full)", {}, {},
        notifications = notifications, connection = ConnectionSettings(), pairedAt = 1_789_380_000_000L,
    )

    @Test fun homeWifi() {
        show { home(Looks.sameWifi) }
        rule.onNodeWithContentDescription("End-to-end encrypted", substring = true).assertExists() // the shield speaks
        shot("home-wifi")
    }
    @Test fun macSheet() {
        show { home(Looks.sameWifi) }
        rule.mainClock.advanceTimeBy(500)
        rule.onNodeWithText("64%").performClick()
        rule.mainClock.advanceTimeBy(1000)
        rule.onNodeWithText("9 GB of 24 GB in use").assertExists() // the sheet, from the shared looks
        shot("mac-sheet")
    }
    @Test fun homeTor() { show { home(Looks.throughTor) }; shot("home-tor") }
    @Test fun homeOffline() { show { home(Looks.offline) }; shot("home-offline") }
    @Test fun homePaused() { show { home(Looks.paused(now + 30 * 60_000L, now)) }; shot("home-paused") }
    @Test fun homeClip() { show { home(Looks.sameWifi, clip = true) }; shot("home-clip") }
    @Test fun homeUpdate() { show { home(Looks.sameWifi, update = true) }; shot("home-update") }
    @Test fun homeClipboardOffOnMac() { show { home(Looks.sameWifi, clipboard = ClipboardStatus.OffOnMac) }; shot("home-clipboard-off-on-mac") }

    @Test fun pauseSheet() {
        show { home(Looks.sameWifi) }
        open(rule.onNodeWithContentDescription("Pause"), "pause-sheet")
    }

    @Test fun settingsTop() { show { settings() }; shot("settings") }

    @Test fun settingsScrolled() {
        show { settings() }
        rule.mainClock.advanceTimeBy(500)
        // One scroll, then the clock moves it: performScrollTo waits on an animation the paused clock never runs
        rule.onNode(hasScrollAction()).performSemanticsAction(SemanticsActions.ScrollBy) { it(0f, 1800f) }
        shot("settings-scrolled")
    }

    @Test fun forgetDialog() {
        show { settings() }
        open(rule.onNodeWithText("Forget"), "forget")
    }

    @Test fun notificationsList() { show { NotificationsScreen(notifications) {} }; shot("notifications") }

    @Test fun notificationsNoAccess() { access(false); show { NotificationsScreen(notifications) {} }; shot("notifications-no-access") }

    @Test fun modeSheet() {
        show { NotificationsScreen(notifications) {} }
        open(rule.onNodeWithText("Calendar"), "mode-sheet")
    }

    @Test fun selecting() {
        show { NotificationsScreen(notifications) {} }
        select()
        shot("selecting", settle = 0)
    }

    @Test fun pairIntro() { show { pair() }; shot("pair") }
    @Test fun pairCameraNeeded() { show { pair(scanning = true, cameraOn = false) { CameraNeeded(blocked = false) {} } }; shot("pair-camera-needed") }
    @Test fun pairCameraBlocked() { show { pair(scanning = true, cameraOn = false) { CameraNeeded(blocked = true) {} } }; shot("pair-camera-blocked") }
    @Test fun pairConfirm() {
        show { pair(toConfirm = PairingQr("example.onion", ByteArray(32), ByteArray(32), ByteArray(32), ByteArray(32), "MacBook Pro")) }
        shot("pair-confirm")
    }
    @Test fun pairCode() { show { pair(SessionState.Pairing("MacBook Pro", "482913")) }; shot("pair-code") }
    @Test fun setup() { show { SetupScreen("MacBook Pro", ClipboardStatus.NeedsSetup) {} }; shot("setup") }

    // Large text (1.3x), to show what clips
    @Test fun homeOfflineLargeText() { show(1.3f) { home(Looks.offline) }; shot("home-offline-1.3x") }
    @Test fun selectingLargeText() {
        show(1.3f) { NotificationsScreen(notifications) {} }
        select()
        shot("selecting-1.3x", settle = 0)
    }

    // Board A04: opened from a button, as the share sheet opens it over another app
    @Composable
    private fun share(look: LinkLook, refused: String? = null, onSend: () -> SendFeedback = { SendFeedback(label = "✓ Sent to Mac", done = true) }) {
        val shown = remember { androidx.compose.runtime.mutableStateOf(false) }
        Pill("Share", { shown.value = true })
        if (shown.value) ShareSheet("MacBook Pro", SAMPLE_SHARE, look, refused, { shown.value = false }, onSend)
    }

    @Test fun shareSheet() {
        show { share(Looks.sameWifi) }
        open(rule.onNodeWithText("Share"), "share-sheet")
    }

    @Test fun shareSheetOffline() {
        show { share(Looks.offline) }
        open(rule.onNodeWithText("Share"), "share-sheet-offline")
    }

    // Large text: the chip's two lines hug the longer one, as on Home
    @Test fun shareSheetLargeText() {
        show(1.3f) { share(Looks.offline) }
        open(rule.onNodeWithText("Share"), "share-sheet-1.3x")
    }

    // Clipboard sync off on the Mac is known before the tap: the sheet opens already saying no
    @Test fun shareSheetRefused() {
        show { share(Looks.sameWifi, refused = "MacBook Pro has clipboard sync off. Turn it on in Seam on your Mac.") }
        open(rule.onNodeWithText("Share"), "share-sheet-refused")
        rule.onNodeWithText("clipboard sync off", substring = true).assertExists() // the card is the one message
        rule.onNodeWithText("Can't send to MacBook Pro").assertExists() // the title doesn't offer what won't happen
        rule.onNodeWithText("To its clipboard", substring = true).assertDoesNotExist()
        rule.onNodeWithText("Close").assertExists()
        rule.onNodeWithText("Send").assertDoesNotExist()
    }
}

private const val SAMPLE_SHARE = "Meet at the north entrance at 7. Bring the tickets, I left mine at home."
