// SPDX-License-Identifier: AGPL-3.0-only
package com.avetero.seam

import androidx.compose.runtime.getValue
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.layoutId
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.unit.dp

/** The prototype's `pill` kinds: tonal (default), filled, text, error text, on a card (a Home card's action), and done (green, after it worked). */
enum class PillKind { Tonal, Filled, Text, Error, Destructive, OnCard, Done }

/**
 * A 40 dp pill whose corners tighten to 10 dp while pressed (0.3 s spring), colours easing over
 * 0.3 s when its kind changes ("Paste" → "✓ Pasted"). The label swaps in place, and turning
 * Done gives the system's confirm tick, so a screen never adds its own for that.
 */
@Composable
internal fun Pill(text: String, onClick: () -> Unit, kind: PillKind = PillKind.Tonal, enabled: Boolean = true, modifier: Modifier = Modifier) {
    val s = MaterialTheme.colorScheme
    val (bg, fg) = when (kind) {
        PillKind.Filled -> s.primary to s.onPrimary
        PillKind.Text -> Color.Transparent to s.primary
        // Dark's errorContainer is a saturated red, the loudest thing on a page: there, a 24% tint of error under error words instead
        PillKind.Error -> if (s.surface.luminance() > 0.5f) s.errorContainer to s.onErrorContainer else s.error.copy(alpha = 0.24f) to s.error
        PillKind.Destructive -> s.error to s.onError
        // Home's one filled button is Send. Light: white on the card, primary words. Dark: a tone lighter than the card, not a dark hole in it
        PillKind.OnCard -> if (s.surface.luminance() > 0.5f) s.surfaceContainerLowest to s.primary
            else s.onSecondaryContainer.copy(alpha = 0.24f).compositeOver(s.secondaryContainer) to s.onSecondaryContainer
        else -> s.secondaryContainer to s.onSecondaryContainer
    }
    // A kind changes to Done in place ("Paste" → "✓ Pasted"), easing from the colours it had, not Tonal's.
    // The prototype has white on green; dark theme's light green needs dark text to be read
    val done = kind == PillKind.Done
    val before = remember { mutableStateOf(bg to fg) }
    if (!done) before.value = bg to fg
    val container = pick(done, before.value.first, okColor, Motion.ease(300))
    val content = pick(done, before.value.second, s.onPrimary, Motion.ease(300))
    ConfirmOnDone(done)
    // Words without a fill take M3's 12 dp text-button padding; an [Item] shifts them by it, so their
    // words line up with the switches and chevrons above and below, or with the row's words
    val bare = kind == PillKind.Text
    val pad = if (bare) 12.dp else 18.dp
    Button(
        onClick = onClick, enabled = enabled,
        shapes = ButtonDefaults.shapes(shape = CircleShape, pressedShape = RoundedCornerShape(10.dp)),
        colors = ButtonDefaults.buttonColors(container, content, container, content),
        contentPadding = PaddingValues(horizontal = pad),
        modifier = modifier.then(if (LocalTrailing.current) Modifier.layoutId(if (bare) BARE_PILL else PILL) else Modifier).heightIn(min = 40.dp),
    ) { Swap(text) { Text(it, style = MaterialTheme.typography.titleSmall, maxLines = 1) } }
}

/** The system's confirm tick, only on the change to done, not when something already done comes on screen. */
@Composable
private fun ConfirmOnDone(done: Boolean) {
    val haptic = LocalHapticFeedback.current
    val wasDone = remember { mutableStateOf(done) }
    LaunchedEffect(done) {
        if (done && !wasDone.value) haptic.performHapticFeedback(HapticFeedbackType.Confirm)
        wasDone.value = done
    }
}

/**
 * A screen's one main action (pairing, set-up, share): a full-width pill, 56 dp or taller when large
 * text wraps its label, whose corners tighten to 16 dp while pressed. Arming (disabled → enabled) and
 * done (green, "✓ Sent") ease over 0.3 s instead of snapping; the label swaps in place. [tonal] is
 * for a screen whose one action only dismisses.
 */
@Composable
internal fun BigButton(text: String, onClick: () -> Unit, enabled: Boolean = true, done: Boolean = false, tonal: Boolean = false) {
    val s = MaterialTheme.colorScheme
    // Light's secondaryContainer barely parts from a sheet (about 1.2:1), so tonal there is primaryContainer, as Home's Pause is
    val (on, onWords) = when {
        !tonal -> s.primary to s.onPrimary
        s.surface.luminance() > 0.5f -> s.primaryContainer to s.onPrimaryContainer
        else -> s.secondaryContainer to s.onSecondaryContainer
    }
    val container = pick(done, pick(enabled, s.onSurface.copy(alpha = 0.1f), on, Motion.ease(300)), okColor, Motion.ease(300))
    val content = pick(done, pick(enabled, s.onSurface.copy(alpha = 0.38f), onWords, Motion.ease(300)), s.onPrimary, Motion.ease(300))
    ConfirmOnDone(done)
    Button(
        onClick = onClick, enabled = enabled,
        shapes = ButtonDefaults.shapes(shape = CircleShape, pressedShape = RoundedCornerShape(16.dp)),
        colors = ButtonDefaults.buttonColors(container, content, container, content),
        modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
    ) { Swap(text) { Text(it, style = MaterialTheme.typography.labelLarge, textAlign = TextAlign.Center) } }
}
