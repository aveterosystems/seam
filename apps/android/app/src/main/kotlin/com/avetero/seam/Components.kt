// SPDX-License-Identifier: AGPL-3.0-only
package com.avetero.seam

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// Seam's shared building blocks (boards A01–A06). Screens compose these; a new control extends
// one of them rather than drawing its own copy.

/**
 * A screen under Home: back button, the large hero title, then scrolling sections. [overlay]
 * draws over the content, pinned (a bottom action bar).
 */
@Composable
internal fun SubScreen(
    title: String,
    onBack: () -> Unit,
    overlay: @Composable BoxScope.() -> Unit = {},
    content: @Composable ColumnScope.() -> Unit,
) {
    BackHandler(onBack = onBack)
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
        Box(Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                Row(Modifier.fillMaxWidth().height(64.dp).padding(start = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onBack, modifier = Modifier.size(48.dp)) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
                Text(
                    title, fontSize = 36.sp, lineHeight = 44.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-0.6).sp,
                    modifier = Modifier.padding(start = 20.dp, end = 20.dp, bottom = 12.dp),
                )
                Column(Modifier.padding(start = 16.dp, end = 16.dp, bottom = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp), content = content)
            }
            overlay()
        }
    }
}

/** A row's "done" tick (a permission granted, a step finished). */
@Composable
internal fun Done() = Icon(Icons.Filled.CheckCircle, contentDescription = "Done", tint = MaterialTheme.colorScheme.primary)

/** The small print under a group. */
@Composable
internal fun Footnote(text: String) =
    Text(text, fontSize = 12.sp, color = MaterialTheme.colorScheme.outline, modifier = Modifier.padding(horizontal = 8.dp))

/**
 * A titled group of [Item]s. Optional: a line under the title, an action on the right of it
 * ("Select"), and something between the title and the rows (a search field).
 */
@Composable
internal fun Section(
    title: String,
    subtitle: String? = null,
    action: (@Composable () -> Unit)? = null,
    above: (@Composable () -> Unit)? = null,
    items: @Composable () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(Modifier.fillMaxWidth().padding(start = 4.dp, top = 8.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(title, fontSize = 14.sp, fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.primary)
                if (subtitle != null) Text(subtitle, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            action?.invoke()
        }
        above?.invoke()
        Column(verticalArrangement = Arrangement.spacedBy(2.dp), modifier = Modifier.clip24()) { items() }
    }
}

/**
 * One row of an Android 16-style grouped list: each row is its own surface, 4 dp corners inside
 * the group; the group clips its outer corners to 24 dp.
 */
@Composable
internal fun Item(
    title: String,
    subtitle: String? = null,
    mono: String? = null,
    lead: Painter? = null,
    /** An app's own icon: shown as is, not tinted into a circle. */
    leadIsImage: Boolean = false,
    highlighted: Boolean = false,
    onClick: (() -> Unit)? = null,
    trailing: @Composable () -> Unit = {},
) {
    val color = if (highlighted) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerLow
    val content = if (highlighted) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface
    val body: @Composable () -> Unit = {
        Row(Modifier.fillMaxWidth().heightIn(min = 64.dp).padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            if (lead != null && leadIsImage) {
                Image(lead, contentDescription = null, modifier = Modifier.size(40.dp))
                Spacer(Modifier.size(16.dp))
            } else if (lead != null) {
                Box(
                    Modifier.size(40.dp).clipCircle(MaterialTheme.colorScheme.secondaryContainer),
                    contentAlignment = Alignment.Center,
                ) { Icon(lead, contentDescription = null, tint = MaterialTheme.colorScheme.onSecondaryContainer, modifier = Modifier.size(20.dp)) }
                Spacer(Modifier.size(16.dp))
            }
            Column(Modifier.weight(1f)) {
                Text(title, fontSize = 16.sp, lineHeight = 24.sp)
                if (subtitle != null || mono != null) {
                    Row {
                        if (subtitle != null) Text(subtitle, fontSize = 14.sp, lineHeight = 20.sp, color = if (highlighted) content.copy(alpha = 0.85f) else MaterialTheme.colorScheme.onSurfaceVariant)
                        if (mono != null) Text(mono, fontSize = 14.sp, lineHeight = 20.sp, fontFamily = FontFamily.Monospace, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            trailing()
        }
    }
    if (onClick != null) {
        Surface(onClick = onClick, color = color, contentColor = content, shape = RoundedCornerShape(4.dp)) { body() }
    } else {
        Surface(color = color, contentColor = content, shape = RoundedCornerShape(4.dp)) { body() }
    }
}

private fun Modifier.clip24() = this.then(Modifier.clip(RoundedCornerShape(24.dp)))
private fun Modifier.clipCircle(c: Color) = this.then(Modifier.clip(CircleShape).background(c))

/** An [Item] that is a switch: the whole row toggles it. [highlightWhenOn] marks a feature's main switch. */
@Composable
internal fun SwitchItem(
    title: String,
    subtitle: String?,
    checked: Boolean,
    onChange: (Boolean) -> Unit,
    highlightWhenOn: Boolean = false,
    enabled: Boolean = true,
) = Item(title, subtitle, highlighted = highlightWhenOn && checked, onClick = if (enabled) ({ onChange(!checked) }) else null) {
    Switch(checked = checked, onCheckedChange = if (enabled) onChange else null, enabled = enabled)
}
