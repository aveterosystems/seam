// SPDX-License-Identifier: AGPL-3.0-only
package com.avetero.seam

import androidx.compose.runtime.getValue
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** The prototype's `pill` kinds: tonal (default), filled, text, error text, and done (green, after it worked). */
enum class PillKind { Tonal, Filled, Text, Error, Done }

/**
 * A 40 dp pill whose corners tighten to 10 dp while pressed (0.3 s spring), colours easing over
 * 0.3 s when its kind changes ("Paste" → "✓ Pasted"). The label swaps in place.
 */
@Composable
internal fun Pill(text: String, onClick: () -> Unit, kind: PillKind = PillKind.Tonal, enabled: Boolean = true, modifier: Modifier = Modifier) {
    val s = MaterialTheme.colorScheme
    val (bg, fg) = when (kind) {
        PillKind.Filled -> s.primary to s.onPrimary
        PillKind.Text -> Color.Transparent to s.primary
        PillKind.Error -> Color.Transparent to s.error
        else -> s.secondaryContainer to s.onSecondaryContainer
    }
    // Only Tonal → Done changes in place ("Paste" → "✓ Pasted"). The prototype has white on
    // green; dark theme's light green needs dark text to be read
    val done = kind == PillKind.Done
    val container = pick(done, bg, okColor, Motion.ease(300))
    val content = pick(done, fg, s.onPrimary, Motion.ease(300))
    Button(
        onClick = onClick, enabled = enabled,
        shapes = ButtonDefaults.shapes(shape = CircleShape, pressedShape = RoundedCornerShape(10.dp)),
        colors = ButtonDefaults.buttonColors(container, content, container, content),
        contentPadding = PaddingValues(horizontal = 18.dp),
        modifier = modifier.height(40.dp),
    ) { Swap(text) { Text(it, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 0.sp, maxLines = 1) } }
}

/** A screen's one main action (pairing, set-up): a full-width 56 dp pill whose corners tighten to 16 dp while pressed. */
@Composable
internal fun BigButton(text: String, onClick: () -> Unit, enabled: Boolean = true) = Button(
    onClick = onClick, enabled = enabled,
    shapes = ButtonDefaults.shapes(shape = CircleShape, pressedShape = RoundedCornerShape(16.dp)),
    modifier = Modifier.fillMaxWidth().height(56.dp),
) { Text(text, fontSize = 16.sp, fontWeight = FontWeight.SemiBold) }
