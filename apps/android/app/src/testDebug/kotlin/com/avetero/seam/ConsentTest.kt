// SPDX-License-Identifier: AGPL-3.0-only
package com.avetero.seam

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performSemanticsAction
import com.avetero.seam.session.SessionState
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The two taps that decide what reaches the Mac: rejecting a pairing, and sending a share. Debug only: the compose rule's activity is declared there. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class ConsentTest {
    @get:Rule val rule = createComposeRule()

    /** Steps and idles in turns, so a sheet's own window composes and runs its effects. */
    private fun settle(ms: Long = 3000) = repeat((ms / 100).toInt()) {
        rule.mainClock.advanceTimeBy(100)
        rule.waitForIdle()
    }

    private fun tap(text: String) = rule.onNodeWithText(text).performSemanticsAction(SemanticsActions.OnClick)

    @Test fun codesDontMatchAbortsAtTheTapNotAfterTheSheetCloses() {
        var aborts = 0
        rule.mainClock.autoAdvance = false
        rule.setContent {
            SeamTheme { SeamScreen(SessionState.Pairing("MacBook Pro", "482913"), null, null, {}, {}, {}, onCodesDontMatch = { aborts++ }) }
        }
        settle()
        tap("Codes don't match")
        // No frame has run: the Mac's accept can't land between the tap and the abort
        assertEquals(1, aborts)
        settle()
        assertEquals("the exit doesn't abort a second time", 1, aborts)
    }

    @Test fun shareSendsOnlyOnSendAndOnlyOnce() {
        var sends = 0
        var shown by mutableStateOf(true)
        rule.mainClock.autoAdvance = false
        rule.setContent {
            SeamTheme {
                if (shown) ShareSheet("MacBook Pro", "hello", Looks.sameWifi, null, { shown = false }) {
                    sends++
                    SendFeedback(label = "✓ Sent to Mac", done = true)
                }
            }
        }
        settle()
        tap("Cancel")
        settle()
        assertEquals("opening and cancelling send nothing", 0, sends)

        shown = true
        settle() // past the half second Send waits before it takes a tap
        tap("Send")
        settle(200) // the button now says it went; a second tap on it does nothing
        tap("✓ Sent to Mac")
        assertEquals(1, sends)
    }
}
