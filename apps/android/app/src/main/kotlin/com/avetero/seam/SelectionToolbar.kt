// SPDX-License-Identifier: AGPL-3.0-only
package com.avetero.seam

import androidx.compose.runtime.getValue
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.shadow
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.avetero.seam.feature.notifications.AppMode

/**
 * Selection mode's floating toolbar, drawn as the prototype's (30 dp corners, 6 dp padding) in the theme's own colours, so it's dark
 * in dark mode (not Material's inverse, which turns it light there): how many are picked, and
 * the three modes for all of them at once. It rises 120 dp from below on the spring (0.5 s)
 * while selecting, and drops away after.
 */
@Composable
internal fun BulkToolbar(visible: Boolean, count: Int, modifier: Modifier, onClose: () -> Unit, onMode: (AppMode) -> Unit) {
    val rise = with(LocalDensity.current) { 120.dp.roundToPx() }
    val ink = MaterialTheme.colorScheme.onSurface
    AnimatedVisibility(
        visible, modifier.padding(bottom = 28.dp),
        enter = slideInVertically(Motion.spring(500)) { rise }, exit = slideOutVertically(Motion.spring(500)) { rise },
    ) {
        Row(
            Modifier.shadow(12.dp, RoundedCornerShape(30.dp), ambientColor = Color.Black.copy(alpha = 0.3f), spotColor = Color.Black.copy(alpha = 0.3f))
                .background(MaterialTheme.colorScheme.surfaceContainerHighest, RoundedCornerShape(30.dp)).padding(6.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Swap("$count selected", Modifier.padding(start = 12.dp, end = 10.dp)) {
                Text(it, color = ink, fontWeight = FontWeight.SemiBold, style = TextStyle(fontSize = 14.sp, fontFeatureSettings = "tnum"), maxLines = 1)
            }
            AppMode.entries.forEach { m -> ToolbarButton(m.label, ink) { onMode(m) } }
            ToolbarButton("✕", ink, "Stop selecting", onClose)
        }
    }
}

/** 40 dp, 13.5 sp, its corners tightening from 20 to 10 dp while pressed (0.3 s spring). */
@Composable
private fun ToolbarButton(text: String, ink: Color, description: String? = null, onClick: () -> Unit) {
    val source = remember { MutableInteractionSource() }
    val down by source.collectIsPressedAsState()
    val corner by animateDpAsState(if (down) 10.dp else 20.dp, Motion.spring(300), label = "corner")
    TextButton(
        onClick = onClick, shape = RoundedCornerShape(corner), interactionSource = source,
        contentPadding = PaddingValues(horizontal = 13.dp), modifier = Modifier.height(40.dp)
            .then(if (description != null) Modifier.semantics { contentDescription = description } else Modifier),
    ) { Text(text, color = ink, fontSize = 13.5.sp, fontWeight = FontWeight.SemiBold, maxLines = 1) }
}
