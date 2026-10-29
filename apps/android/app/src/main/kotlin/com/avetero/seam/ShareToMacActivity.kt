// SPDX-License-Identifier: AGPL-3.0-only
package com.avetero.seam

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.avetero.seam.feature.clipboard.ClipRules
import com.avetero.seam.session.SessionState

/**
 * "Send to Mac" in the share sheet (board A04). Any app can start this, so nothing goes until the
 * user has seen the text and tapped Send (SR-4-01).
 */
class ShareToMacActivity : ComponentActivity() {
    @OptIn(ExperimentalMaterial3Api::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val app = application as SeamApplication
        val text = intent.takeIf { it.action == Intent.ACTION_SEND }?.getStringExtra(Intent.EXTRA_TEXT)
        val state = app.session.state.value
        val macName = (state as? SessionState.Connected)?.macName ?: (state as? SessionState.Idle)?.macName
            ?: (state as? SessionState.Connecting)?.macName
        when {
            macName == null -> return done("Pair Seam with your Mac first.")
            text.isNullOrEmpty() -> return done("Nothing to send")
        }
        setContent {
            val colors = if (isSystemInDarkTheme()) dynamicDarkColorScheme(this) else dynamicLightColorScheme(this)
            MaterialTheme(colorScheme = colors) {
                ModalBottomSheet(onDismissRequest = { finish() }) {
                    Column(
                        Modifier.padding(horizontal = 24.dp).padding(bottom = 16.dp).navigationBarsPadding(),
                        verticalArrangement = Arrangement.spacedBy(16.dp),
                    ) {
                        Text(
                            ClipRules.preview(text!!), maxLines = 4, overflow = TextOverflow.Ellipsis,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Surface(color = MaterialTheme.colorScheme.primaryContainer, shape = RoundedCornerShape(24.dp)) {
                            Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                                Box(Modifier.size(40.dp), contentAlignment = Alignment.Center) {
                                    Icon(painterResource(R.drawable.ic_laptop), contentDescription = null)
                                }
                                Spacer(Modifier.width(12.dp))
                                Column {
                                    Text("Sending to $macName", fontSize = 16.sp)
                                    Text("Its clipboard, end-to-end encrypted", fontSize = 14.sp)
                                }
                            }
                        }
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) {
                            TextButton(onClick = { finish() }) { Text("Cancel") }
                            Button(onClick = { done(sentMessage(app, app.clipboard.sendNow(text))) }) { Text("Send") }
                        }
                    }
                }
            }
        }
    }

    private fun done(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
        finish()
    }
}

/** "Sent" only when it can go now; otherwise say it's waiting (SR-4-04). */
fun sentMessage(app: SeamApplication, sent: Boolean): String = when {
    !sent -> "Nothing to send: the clipboard is empty, too big, or holds something private"
    app.session.state.value is SessionState.Connected -> "Sent to your Mac's clipboard"
    // Queued and a connection starting, not sent yet (SR-8-04)
    app.session.state.value is SessionState.Idle -> "Sending to your Mac's clipboard…"
    else -> "It'll go to your Mac when your phone reconnects (within 10 minutes)"
}
