// SPDX-License-Identifier: AGPL-3.0-only
package com.avetero.seam

import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.AnimationSpec
import androidx.compose.ui.graphics.lerp
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.draw.shadow

// Seam's shared building blocks, drawn and timed as the approved prototype
// (docs/design/prototypes/android-app.html) draws them. Screens compose these; a new control
// extends one of them rather than drawing its own copy.

/** The one snackbar: a confirmation of something that happened elsewhere. */
@Stable
class Snacks {
    var text by mutableStateOf<String?>(null)
        private set
    var shown by mutableStateOf(false)
        private set
    internal var count by mutableIntStateOf(0)
        private set

    fun show(text: String) {
        this.text = text
        shown = true
        count++
    }

    internal fun hide() { shown = false }
}

val LocalSnacks = staticCompositionLocalOf { Snacks() }

@Composable
fun rememberSnack(): (String) -> Unit {
    val snacks = LocalSnacks.current
    return remember(snacks) { { snacks.show(it) } }
}

/**
 * Seam's one floating surface, for anything that hovers over a screen: the snackbar, the selection
 * toolbar, the camera caption. The theme's own colours (dark in dark mode, never Material's inverse)
 * and one lift, so they always match. A new floating bar uses this rather than drawing its own.
 */
@Composable
fun FloatingSurface(modifier: Modifier = Modifier, shape: Shape = RoundedCornerShape(12.dp), content: @Composable () -> Unit) {
    Box(
        modifier.shadow(12.dp, shape, ambientColor = Color.Black.copy(alpha = 0.3f), spotColor = Color.Black.copy(alpha = 0.3f))
            .background(MaterialTheme.colorScheme.surfaceContainerHighest, shape),
    ) { CompositionLocalProvider(LocalContentColor provides MaterialTheme.colorScheme.onSurface, content = content) }
}

/** The prototype's snackbar: rises 90 dp on the spring (0.45 s), stays 2.2 s, and TalkBack reads it. */
@Composable
fun SnackHost(snacks: Snacks, modifier: Modifier) {
    LaunchedEffect(snacks.count) { if (snacks.shown) { delay(2200); snacks.hide() } }
    val rise = with(LocalDensity.current) { 90.dp.roundToPx() }
    AnimatedVisibility(
        snacks.shown, modifier.padding(start = 12.dp, end = 12.dp, bottom = 26.dp),
        enter = slideInVertically(Motion.spring(450)) { rise } + fadeIn(tween(250, easing = Motion.Ease)),
        exit = slideOutVertically(Motion.spring(450)) { rise } + fadeOut(tween(250, easing = Motion.Ease)),
    ) {
        FloatingSurface(Modifier.fillMaxWidth()) {
            Text(
                snacks.text.orEmpty(), fontSize = 14.sp,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp).semantics { liveRegion = LiveRegionMode.Polite },
            )
        }
    }
}

/**
 * Text that changes in place (the prototype's `swap`): the old rises 8 dp and fades out
 * (0.15 s, ease-in), then the new rises 8 dp into the same slot on the spring (0.4 s).
 */
@Composable
fun Swap(text: String, modifier: Modifier = Modifier, content: @Composable (String) -> Unit = { Text(it) }) {
    val d = with(LocalDensity.current) { 8.dp.roundToPx() }
    val reduced = reducedMotion()
    AnimatedContent(
        text, modifier,
        transitionSpec = {
            val t = if (reduced) fadeIn(tween(400, 150)) togetherWith fadeOut(tween(150, easing = Motion.EaseIn))
            else (slideInVertically(Motion.spring(400, 150)) { d } + fadeIn(Motion.spring(400, 150))) togetherWith
                (slideOutVertically(tween(150, easing = Motion.EaseIn)) { -d } + fadeOut(tween(150, easing = Motion.EaseIn)))
            t using SizeTransform(clip = false) { _, _ -> Motion.spring(400) }
        },
        contentAlignment = Alignment.CenterStart,
        label = "swap",
    ) { content(it) }
}

/** A card that unfolds in its slot (the prototype's `cx`): height on the spring (0.5 s), fading in 0.35 s, rising 16 dp. */
@Composable
fun Unfold(visible: Boolean, content: @Composable () -> Unit) {
    val d = with(LocalDensity.current) { 16.dp.roundToPx() }
    AnimatedVisibility(
        visible,
        enter = expandVertically(Motion.spring(500)) + fadeIn(Motion.ease(350)) + slideInVertically(Motion.spring(500)) { d },
        exit = shrinkVertically(Motion.spring(500)) + fadeOut(Motion.ease(350)) + slideOutVertically(Motion.spring(500)) { d },
    ) { content() }
}

/**
 * [on] while [state] holds, else [off], easing between them when the state changes. The two
 * colours are read fresh every time, so a theme change moves with the rest of the screen rather
 * than chasing it.
 */
@Composable
internal fun pick(state: Boolean, off: Color, on: Color, spec: AnimationSpec<Float>): Color {
    val f by animateFloatAsState(if (state) 1f else 0f, spec, label = "pick")
    return lerp(off, on, f)
}

/** A row's "done" tick (a permission granted, a step finished). */
@Composable
internal fun Done() = Icon(Icons.Filled.CheckCircle, contentDescription = "Done", tint = MaterialTheme.colorScheme.primary)

/** The small print under a group: 12.5 sp, 10 dp under it. */
@Composable
internal fun Footnote(text: String) = Swap(text, Modifier.padding(start = 16.dp, end = 16.dp, top = 2.dp)) {
    Text(it, fontSize = 12.5.sp, lineHeight = 17.5.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

/**
 * A titled group of [Item]s, Android 16 style: 24 dp outer corners, 2 dp between rows. Optional:
 * an action right of the title, something between the title and the rows (a search field, 12 dp
 * above them), and the small print under them.
 */
@Composable
internal fun Section(
    title: String?,
    footnote: String? = null,
    action: (@Composable () -> Unit)? = null,
    above: (@Composable () -> Unit)? = null,
    items: @Composable () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (title != null || action != null) {
            Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(title.orEmpty(), fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.primary, modifier = Modifier.weight(1f))
                action?.invoke()
            }
        }
        if (above != null) Box(Modifier.padding(bottom = 4.dp)) { above() }
        Column(verticalArrangement = Arrangement.spacedBy(2.dp), modifier = Modifier.clip(RoundedCornerShape(24.dp))) { items() }
        if (footnote != null) Footnote(footnote)
    }
}

/**
 * One row of a grouped list: its own surface, 4 dp corners inside the group, rounding to 16 dp
 * while pressed (0.3 s spring). [highlighted] is a feature's main switch when on
 * (primaryContainer, and its icon morphs from a circle to a 14 dp square); [selected] is a
 * picked row in selection mode. [plain] is a sheet's option: 14 sp words, the detail at 75%.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun Item(
    title: String,
    subtitle: String? = null,
    mono: String? = null,
    lead: Painter? = null,
    /** An app's own icon: shown as is, not tinted into a circle. */
    leadIsImage: Boolean = false,
    highlighted: Boolean = false,
    selected: Boolean = false,
    onClick: (() -> Unit)? = null,
    onLongClick: (() -> Unit)? = null,
    role: Role = Role.Button,
    vertical: Dp = 14.dp,
    plain: Boolean = false,
    start: (@Composable () -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
) {
    val scheme = MaterialTheme.colorScheme
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val corner by animateDpAsState(if (pressed) 16.dp else 4.dp, Motion.spring(300), label = "corner")
    val spec = Motion.ease<Float>(if (plain) 300 else 350)
    val color = pick(selected, pick(highlighted, scheme.surfaceContainer, scheme.primaryContainer, spec), scheme.secondaryContainer, spec)
    val ink = pick(highlighted, scheme.onSurface, scheme.onPrimaryContainer, spec)
    val clickable = if (onClick == null) Modifier else Modifier.combinedClickable(
        interactionSource = interaction, indication = ripple(), role = role, onClick = onClick, onLongClick = onLongClick,
    )
    Surface(color = color, contentColor = ink, shape = RoundedCornerShape(corner), modifier = Modifier.fillMaxWidth()) {
        Row(
            // As the prototype's rows: at least 44 dp inside the padding (72 dp, or 64 for app rows)
            // A sheet's option has no minimum beyond the 48 dp touch height
            clickable.fillMaxWidth().heightIn(min = if (plain) 48.dp else 44.dp + vertical * 2).padding(horizontal = 16.dp, vertical = vertical),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (start != null) {
                start()
                Spacer(Modifier.size(14.dp))
            }
            if (lead != null) {
                Lead(lead, leadIsImage, highlighted, selected)
                Spacer(Modifier.size(14.dp))
            }
            Column(Modifier.weight(1f)) {
                if (plain) Text(title, fontSize = 14.sp)
                else Text(title, fontSize = 16.sp, fontWeight = FontWeight.Medium)
                if (subtitle != null || mono != null) {
                    val sub = if (highlighted || plain) ink.copy(alpha = 0.75f) else scheme.onSurfaceVariant
                    val size = if (plain) 12.5.sp else 13.5.sp
                    Row(Modifier.padding(top = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                        if (subtitle != null) Swap(subtitle, Modifier.weight(1f, fill = false)) { Text(it, fontSize = size, lineHeight = size * 1.35f, color = sub) }
                        if (mono != null) Text(mono, fontSize = size, fontFamily = FontFamily.Monospace, color = sub)
                    }
                }
            }
            // The prototype's 14 dp gap before the switch, chip or button at the end
            if (trailing != null) Box(Modifier.padding(start = 14.dp)) { trailing() }
        }
    }
}

@Composable
private fun Lead(lead: Painter, isImage: Boolean, highlighted: Boolean, selected: Boolean) {
    val scheme = MaterialTheme.colorScheme
    if (isImage) {
        // An app icon rounds into a circle and shrinks to 0.88 when picked (0.4 s spring)
        val corner by animateDpAsState(if (selected) 20.dp else 12.dp, Motion.spring(400), label = "icon")
        val scale by animateFloatAsState(if (selected) 0.88f else 1f, Motion.spring(400), label = "scale")
        Image(lead, contentDescription = null, modifier = Modifier.size(40.dp).scale(scale).clip(RoundedCornerShape(corner)))
        return
    }
    val corner by animateDpAsState(if (highlighted) 14.dp else 20.dp, Motion.spring(400), label = "icon")
    val bg = pick(highlighted, scheme.surfaceContainerHighest, scheme.primary, Motion.ease(400))
    val tint = pick(highlighted, scheme.onSurface, scheme.onPrimary, Motion.ease(400))
    Box(Modifier.size(40.dp).clip(RoundedCornerShape(corner)).background(bg), contentAlignment = Alignment.Center) {
        Icon(lead, contentDescription = null, tint = tint, modifier = Modifier.size(20.dp))
    }
}

/**
 * An [Item] that is a switch: the whole row toggles it. [highlightWhenOn] marks a feature's main
 * switch. [enabled] false needs its reason in words nearby, since the switch dims.
 */
@Composable
internal fun SwitchItem(
    title: String,
    subtitle: String?,
    checked: Boolean,
    onChange: (Boolean) -> Unit,
    lead: Painter? = null,
    highlightWhenOn: Boolean = false,
    enabled: Boolean = true,
) = Item(title, subtitle, lead = lead, highlighted = highlightWhenOn && checked, role = Role.Switch, onClick = if (enabled) ({ onChange(!checked) }) else null) {
    SeamSwitch(checked, if (enabled) onChange else null, enabled)
}

/** Material's switch with a tick in its thumb when on, as the prototype draws it. */
@Composable
internal fun SeamSwitch(checked: Boolean, onChange: ((Boolean) -> Unit)?, enabled: Boolean = true, scale: Float = 1f) = Switch(
    checked = checked, onCheckedChange = onChange, enabled = enabled, modifier = Modifier.scale(scale),
    thumbContent = if (checked) ({ Icon(Icons.Filled.Check, contentDescription = null, modifier = Modifier.size(SwitchDefaults.IconSize)) }) else null,
)

/** A small label chip ("On", "Title only"): 12.5 sp, 9 dp corners; its colour says the same as its words. */
@Composable
internal fun Chip(text: String, bg: Color, fg: Color) {
    Box(Modifier.clip(RoundedCornerShape(9.dp)).background(bg).padding(horizontal = 11.dp, vertical = 5.dp)) {
        Swap(text) { Text(it, fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold, color = fg, maxLines = 1) }
    }
}
