// SPDX-License-Identifier: AGPL-3.0-only
package com.avetero.seam

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.avetero.seam.crypto.LinkLook
import com.avetero.seam.feature.clipboard.ClipPreview
import com.avetero.seam.feature.clipboard.ClipboardFeature
import com.avetero.seam.session.SessionState
import kotlinx.coroutines.delay

/**
 * "Send to Mac" in the share sheet (board A04). Any app can start this, so nothing goes until the
 * user has seen the text and tapped Send (SR-4-01).
 */
class ShareToMacActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val app = application as SeamApplication
        val text = intent.takeIf { it.action == Intent.ACTION_SEND }?.getStringExtra(Intent.EXTRA_TEXT)
        val at = app.session.state.value
        val macName = macName(at)
            // Not paired: open Seam, which starts with pairing, rather than a toast that leads nowhere
            ?: return startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)).also { finish() }
        if (text.isNullOrEmpty()) return toast("Nothing to send")
        setContent {
            SeamTheme {
                val state by app.session.state.collectAsStateWithLifecycle()
                val route by app.session.route.collectAsStateWithLifecycle()
                val connection by app.session.settings.collectAsStateWithLifecycle()
                val tor by app.tor.state.collectAsStateWithLifecycle()
                val clipboard = app.session.feature<ClipboardFeature>()
                // Known before the tap, so the sheet opens already saying no
                val refused = if (clipboard.offOnMac) SendWords.syncOff(macName) else null
                ShareSheet(macName, text, Looks.link(state, route, connection, tor), refused, onClose = { finish() }) {
                    val sent = clipboard.sendNow(text)
                    val f = sendFeedback(sent, app.session.state.value, macName, clipboard.offOnMac)
                    // Queued counts as gone: it says so on the button and closes; only a real refusal stays in the sheet
                    if (sent && !clipboard.offOnMac) f.copy(label = f.label ?: f.snack, snack = null) else f
                }
            }
        }
    }

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
        finish()
    }
}

private fun macName(at: SessionState): String? =
    (at as? SessionState.Connected)?.macName ?: (at as? SessionState.Idle)?.macName ?: (at as? SessionState.Connecting)?.macName

/**
 * Board A04's sheet: the Mac's link in the status chip's words, the text, and Send. [onSend]'s
 * label goes on the button ("✓ Sent to Mac", "Will send when MacBook Pro is back"), then the sheet
 * closes; its snack is why it couldn't go, said in the sheet until it's closed. [refused] opens it
 * already saying why, when that's known before the tap.
 */
@Composable
internal fun ShareSheet(macName: String, text: String, look: LinkLook, refused: String?, onClose: () -> Unit, onSend: () -> SendFeedback) {
    var problem by remember { mutableStateOf(refused) }
    // Refused, the link's words ("will send when it's back") would promise what won't happen; the error card says why
    SeamSheet(if (problem == null) SendWords.sendTo(macName) else SendWords.cantSendTo(macName), null, onDismiss = onClose) { close ->
        val armed = armedAfterDelay()
        var confirm by remember { mutableStateOf<SendFeedback?>(null) }
        // The button says it went, then the sheet closes (motion.md, catalogue 8)
        LaunchedEffect(confirm) { if (confirm != null) { delay(Motion.CONFIRM_MS); close() } }
        val more = ClipPreview.notShown(text)
        // Refused, the card is first: the room the chip had keeps it off the title, as in the other sheets
        // Cards on the sheet's 16 dp gutter, as every sheet's; the chip and the words under the card on the title's 24 dp edge
        Column(Modifier.padding(top = if (problem == null) 0.dp else 8.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            // 16 dp under the title and above the text, an even rhythm
            // 48 dp short of the width, as on Home, so at large text it wraps to two even lines well before the edge
            if (problem == null) Box(Modifier.padding(start = 8.dp, top = 8.dp, bottom = 4.dp, end = 48.dp)) { StatusChip(look, explains = false, background = raisedOnSheet(), textAlign = TextAlign.Start) }
            Surface(color = raisedOnSheet(), shape = RoundedCornerShape(24.dp), modifier = Modifier.fillMaxWidth()) {
                Text(ClipPreview.preview(text), style = MaterialTheme.typography.bodyLarge, maxLines = 8, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(16.dp))
            }
            if (more > 0) Text("And %,d more characters, not shown".format(more), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 8.dp))
            if (problem == null) Text("To its clipboard, end-to-end encrypted", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 8.dp))
            Unfold(problem != null) {
                // A calm row like the preview's, the icon alone in error: a slab of red read as alarm in dark
                Surface(color = raisedOnSheet(), contentColor = MaterialTheme.colorScheme.onSurface, shape = RoundedCornerShape(24.dp), modifier = Modifier.fillMaxWidth()) {
                    Row(Modifier.padding(16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Icon(painterResource(R.drawable.ic_info), contentDescription = null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(20.dp))
                        Text(problem.orEmpty(), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
                    }
                }
            }
            // After a refusal, sending again would only be refused again: Send goes and Close is the one action
            Unfold(problem == null) {
                BigButton(
                    confirm?.label ?: if (more > 0) "Send all" else "Send",
                    onClick = onClick@{
                        if (confirm != null) return@onClick // sent once; the confirmation is showing
                        val f = onSend()
                        if (f.label != null) confirm = f
                        problem = f.snack
                    },
                    enabled = armed,
                    // Green, with the confirm tick, only when it really went; "Sending" isn't a success yet (SR-8-04)
                    done = confirm?.done == true,
                )
            }
            // The one action, so the main action's 56 dp; tonal, since it only dismisses
            if (problem != null) BigButton("Close", close, tonal = true)
            else Pill("Cancel", close, PillKind.Text, modifier = Modifier.fillMaxWidth())
        }
    }
}

/** The notification's Send clipboard, in Home's words: "Sent" only when it can go now; otherwise it's waiting (SR-4-04). */
fun sentMessage(app: SeamApplication, sent: Boolean): String =
    sendFeedback(sent, app.session.state.value, macName(app.session.state.value) ?: "your Mac", app.session.feature<ClipboardFeature>().offOnMac)
        .let { it.snack ?: it.label.orEmpty() }
