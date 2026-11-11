// SPDX-License-Identifier: AGPL-3.0-only
package com.avetero.seam

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CornerSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.avetero.seam.feature.notifications.AppMode

/**
 * Selection mode's floating toolbar, M3 Expressive's vibrant kind: the shared [FloatingSurface]'s lift (30 dp corners), filled
 * primaryContainer so it stands off the grey rows without an edge, 16 dp from the screen's edges and 12 dp inside (16 dp above the caption).
 * A caption ("Set 2 apps to", labelLarge at medium weight) over the three modes for every picked app at once, all one style so none reads as
 * already chosen, all three one width and one height; under large text (1.3x and up) "Sender only" shows as "Sender", and a label still too
 * long wraps to two lines rather than shrinking. The count and the way
 * out are the page's contextual bar ([Selection]). It rises 120 dp from below on the spring (0.5 s) while selecting, and drops
 * away after; with animations off it fades. The caption lines up with the buttons' edge.
 */
@Composable
internal fun BulkToolbar(visible: Boolean, count: Int, modifier: Modifier, onMode: (AppMode) -> Unit) {
    val rise = with(LocalDensity.current) { 120.dp.roundToPx() }
    val apps = "$count ${if (count == 1) "app" else "apps"}"
    val reduced = reducedMotion()
    AnimatedVisibility(
        visible, modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, bottom = 28.dp),
        enter = if (reduced) fadeIn(Motion.ease(300)) else slideInVertically(Motion.spring(500)) { rise },
        exit = if (reduced) fadeOut(Motion.ease(300)) else slideOutVertically(Motion.spring(500)) { rise },
    ) {
        val shape = RoundedCornerShape(30.dp)
        FloatingSurface(Modifier.fillMaxWidth(), shape = shape, color = MaterialTheme.colorScheme.primaryContainer) {
            Column(
                Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, top = 16.dp, bottom = 12.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(
                    if (count == 0) "Pick apps to change" else "Set $apps to",
                    style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Medium), color = MaterialTheme.colorScheme.onPrimaryContainer,
                )
                // One height for all three, so a label that wraps under large text doesn't leave the others short
                Row(Modifier.height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                    val last = AppMode.entries.lastIndex
                    // Final decision (round 13), don't flip: equal widths always, labels wrap to two lines, no autoshrink
                    AppMode.entries.forEachIndexed { i, m ->
                        ToolbarButton(m, "Set $apps to ${m.label}", count > 0, segment(first = i == 0, last = i == last), Modifier.weight(1f)) { onMode(m) }
                    }
                }
            }
        }
    }
}

/** A connected group's segment: 18 dp at the group's outer ends (the toolbar's 30 less its 12 dp inset, so they nest), 4 dp where it meets its neighbour. */
private fun segment(first: Boolean, last: Boolean): RoundedCornerShape {
    val round = CornerSize(18.dp)
    val inner = CornerSize(4.dp)
    return RoundedCornerShape(if (first) round else inner, if (last) round else inner, if (last) round else inner, if (first) round else inner)
}

/**
 * One segment of a connected group, 2 dp from the next: at least 40 dp, white on the toolbar in light (#FFFFFF on #9FF2E0, 1.29:1; words 17:1),
 * a tonal step up in dark (16% onPrimaryContainer: #196A60 on #005047, 1.46:1; words 5:1), titleSmall in onPrimaryContainer (wrapping to two lines when
 * its third of the row won't fit), its corners going to 10 dp while pressed, as a [Pill]'s do. With nothing picked it fades
 * (0.3 s) to a bare 4% step and 38% words, and can't be tapped. TalkBack hears the whole action, "Set 2 apps to On".
 */
@Composable
private fun ToolbarButton(mode: AppMode, action: String, enabled: Boolean, shape: RoundedCornerShape, modifier: Modifier, onClick: () -> Unit) {
    val s = MaterialTheme.colorScheme
    val tone = { a: Float -> s.onPrimaryContainer.copy(alpha = a).compositeOver(s.primaryContainer) }
    // Light, a darker teal on teal sank into the toolbar; white stands off it by hue as well as tone
    val on = if (s.surface.luminance() > 0.5f) s.surfaceContainerLowest else tone(0.16f)
    val bg = pick(enabled, tone(0.04f), on, Motion.ease(300))
    // TalkBack still hears the full action; the short word keeps three buttons on one line at large text
    val label = if (mode == AppMode.TitleOnly && LocalDensity.current.fontScale >= 1.3f) "Sender" else mode.label
    val fg = pick(enabled, tone(0.38f), s.onPrimaryContainer, Motion.ease(300))
    Button(
        onClick = onClick, enabled = enabled,
        shapes = ButtonDefaults.shapes(shape = shape, pressedShape = RoundedCornerShape(10.dp)),
        colors = ButtonDefaults.buttonColors(bg, fg, bg, fg),
        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 6.dp),
        modifier = modifier.fillMaxHeight().heightIn(min = 40.dp).semantics { contentDescription = action },
    ) {
        // Cleared, so TalkBack reads the button's action once and not the label after it
        Text(label, style = MaterialTheme.typography.titleSmall, textAlign = TextAlign.Center, maxLines = 2, modifier = Modifier.clearAndSetSemantics {})
    }
}
