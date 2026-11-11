// SPDX-License-Identifier: AGPL-3.0-only
package com.avetero.seam

import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.res.painterResource
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.animateIntAsState
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
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
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
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.LineBreak
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.draw.shadow
import androidx.compose.material3.LocalTextStyle
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.Measurable
import androidx.compose.ui.layout.layoutId
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.Placeholder
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.layout.layout
import kotlin.math.ceil
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback

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

    /** The trailing button's words ("Undo"), or none. */
    var action by mutableStateOf<String?>(null)
        private set
    internal var onAction: (() -> Unit)? = null
        private set

    fun show(text: String, action: String? = null, onAction: (() -> Unit)? = null) {
        this.text = text
        this.action = action
        this.onAction = onAction
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
fun FloatingSurface(modifier: Modifier = Modifier, shape: Shape = RoundedCornerShape(12.dp), color: Color = MaterialTheme.colorScheme.surfaceContainerHighest, content: @Composable () -> Unit) {
    Box(
        modifier.shadow(12.dp, shape, ambientColor = Color.Black.copy(alpha = 0.3f), spotColor = Color.Black.copy(alpha = 0.3f))
            .background(color, shape),
    ) { CompositionLocalProvider(LocalContentColor provides MaterialTheme.colorScheme.onSurface, content = content) }
}

/** The prototype's snackbar: rises 90 dp on the spring (0.45 s), or only fades with animations off; stays 2.2 s, and TalkBack reads it. */
@Composable
fun SnackHost(snacks: Snacks, modifier: Modifier) {
    // One with a button stays 4 s, long enough to reach it
    LaunchedEffect(snacks.count) { if (snacks.shown) { delay(if (snacks.action != null) 4000 else 2200); snacks.hide() } }
    val rise = with(LocalDensity.current) { 90.dp.roundToPx() }
    val reduced = reducedMotion()
    AnimatedVisibility(
        snacks.shown, modifier.padding(start = 12.dp, end = 12.dp, bottom = 26.dp),
        enter = if (reduced) fadeIn(tween(250, easing = Motion.Ease)) else slideInVertically(Motion.spring(450)) { rise } + fadeIn(tween(250, easing = Motion.Ease)),
        exit = if (reduced) fadeOut(tween(250, easing = Motion.Ease)) else slideOutVertically(Motion.spring(450)) { rise } + fadeOut(tween(250, easing = Motion.Ease)),
    ) {
        FloatingSurface(Modifier.fillMaxWidth()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    snacks.text.orEmpty(),
                    modifier = Modifier.weight(1f).padding(horizontal = 16.dp, vertical = 14.dp).semantics { liveRegion = LiveRegionMode.Polite },
                )
                val action = snacks.action
                if (action != null) Pill(action, { snacks.onAction?.invoke(); snacks.hide() }, PillKind.Text, modifier = Modifier.padding(end = 4.dp))
            }
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
            else (slideInVertically(Motion.spring(400, 150)) { d } + fadeIn(Motion.ease(400, 150))) togetherWith
                (slideOutVertically(tween(150, easing = Motion.EaseIn)) { -d } + fadeOut(tween(150, easing = Motion.EaseIn)))
            t using SizeTransform(clip = false) { _, _ -> Motion.spring(400) }
        },
        contentAlignment = Alignment.CenterStart,
        label = "swap",
    ) { content(it) }
}

/**
 * A number that counts from its old value to the new one on `settle` (catalogue 4), in tabular
 * digits so its width stays put. [format] dresses it ("64%"); [content] draws it, and a `Text`
 * there gets the tabular digits unless it passes its own `style`.
 */
@Composable
fun Count(value: Int, modifier: Modifier = Modifier, format: (Int) -> String = { it.toString() }, content: @Composable (String) -> Unit = { Text(it) }) {
    val shown by animateIntAsState(value, Motion.settle(), label = "count")
    CompositionLocalProvider(LocalTextStyle provides LocalTextStyle.current.copy(fontFeatureSettings = "tnum")) {
        Box(modifier) { content(format(shown)) }
    }
}

/**
 * A card that unfolds in its slot (the prototype's `cx`): height on the spring (0.5 s), fading in
 * 0.35 s, rising 16 dp. With reduced motion it only fades.
 */
@Composable
fun Unfold(visible: Boolean, content: @Composable () -> Unit) {
    val d = with(LocalDensity.current) { 16.dp.roundToPx() }
    val reduced = reducedMotion()
    AnimatedVisibility(
        visible,
        enter = if (reduced) fadeIn(Motion.ease(350))
        else expandVertically(Motion.spring(500)) + fadeIn(Motion.ease(350)) + slideInVertically(Motion.spring(500)) { d },
        exit = if (reduced) fadeOut(Motion.ease(350))
        else shrinkVertically(Motion.spring(500)) + fadeOut(Motion.ease(350)) + slideOutVertically(Motion.spring(500)) { d },
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

/** The small print under a group: 12 sp, 10 dp under it; centred on a centred page. */
@Composable
internal fun Footnote(text: String, textAlign: TextAlign = TextAlign.Start) = Swap(text, Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 2.dp)) {
    Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = textAlign, modifier = Modifier.fillMaxWidth())
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
                Text(title.orEmpty(), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary, modifier = Modifier.weight(1f).semantics { heading() })
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
 * while pressed or picked (0.3 s spring). [highlighted] is a feature's main switch when on
 * (primaryContainer, and its icon morphs from a circle to a 14 dp square); [selected] is a
 * picked row in selection mode, filled secondaryContainer. [plain] is a sheet's option: no minimum height, the detail at 75%.
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
    /** When this changes, the lead icon bounces (a switch row's state: catalogue 2). */
    bounce: Any? = null,
    /** A switch's or radio's state ([role] says which), so TalkBack reads the row as that control. */
    checked: Boolean? = null,
    /** The subtitle's leading number counts to a new value (catalogue 4) instead of swapping. */
    countSubtitle: Boolean = false,
    start: (@Composable () -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
) {
    val scheme = MaterialTheme.colorScheme
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val corner by animateDpAsState(if (pressed || selected) 16.dp else 4.dp, Motion.spring(300), label = "corner")
    val spec = Motion.ease<Float>(if (plain) 300 else 350)
    val base = pick(highlighted, rowColor(), scheme.primaryContainer, spec)
    // Selected is secondaryContainer: a clean, quieter teal than a highlighted row's primaryContainer (12% primary read muddy
    // grey), and picked rows' plain chips (NotificationsScreen's modeColors) stay 1.3:1 off it in light and dark
    val color = pick(selected, base, scheme.secondaryContainer, spec)
    val ink = pick(highlighted, scheme.onSurface, scheme.onPrimaryContainer, spec)
    val clickable = when {
        checked != null && role == Role.RadioButton -> Modifier.selectable(checked, interaction, ripple(), enabled = onClick != null, role = role) { onClick?.invoke() }
        checked != null -> Modifier.toggleable(checked, interaction, ripple(), enabled = onClick != null, role = role) { onClick?.invoke() }
        onClick == null -> Modifier
        else -> Modifier.combinedClickable(interactionSource = interaction, indication = ripple(), role = role, onClick = onClick, onLongClick = onLongClick)
    }
    // Balanced lines: large text wraps "Seam's alerts / on this phone", never a lone last word
    val titleStyle = MaterialTheme.typography.titleMedium.copy(lineBreak = LineBreak.Heading)
    val measurer = rememberTextMeasurer()
    // Whether the button went under the words, set as the words measure: the Row places the lead
    // after measuring them, so the lead reads it without waiting a frame
    val dropped = remember { BooleanArray(1) }
    val leadAlign = remember { Alignment.Vertical { size, space -> if (dropped[0]) 0 else (space - size) / 2 } }
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
                Box(Modifier.align(leadAlign)) { Lead(lead, leadIsImage, highlighted, selected, bounce) }
                Spacer(Modifier.size(14.dp))
            }
            Layout(
                content = {
                    Column {
                        Text(title, style = titleStyle)
                        if (subtitle != null || mono != null) {
                            val sub = if (highlighted || plain) ink.copy(alpha = 0.75f) else scheme.onSurfaceVariant
                            val style = MaterialTheme.typography.bodyMedium.copy(lineBreak = LineBreak.Heading)
                            Row(Modifier.padding(top = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                                if (subtitle != null) {
                                    if (countSubtitle) Counted(subtitle, Modifier.weight(1f, fill = false)) { Text(it, style = style.copy(fontFeatureSettings = "tnum"), color = sub) }
                                    else Swap(subtitle, Modifier.weight(1f, fill = false)) { Text(it, style = style, color = sub) }
                                }
                                if (mono != null) Text(mono, style = style, fontFamily = FontFamily.Monospace, color = sub)
                            }
                        }
                    }
                    if (trailing != null) CompositionLocalProvider(LocalTrailing provides true) { trailing() }
                },
                modifier = Modifier.weight(1f),
            ) { measurables, constraints ->
                val width = constraints.maxWidth
                val free = Constraints(maxWidth = width, maxHeight = constraints.maxHeight)
                val ends = measurables.drop(1).map { it to it.measure(free) }
                // The prototype's 14 dp gap before the switch, chip or button at the end; words-only
                // buttons shift out by their 12 dp padding so their last letter lines up with the switches
                val shift = { m: Measurable -> if (m.layoutId == BARE_PILL) 12.dp.roundToPx() else 0 }
                val endWidth = ends.sumOf { (m, p) -> p.width - shift(m) }
                val gap = if (ends.isEmpty()) 0 else 14.dp.roundToPx()
                // A button would squeeze the title onto two lines: it goes under the words instead
                val below = ends.any { (m, _) -> m.layoutId == PILL || m.layoutId == BARE_PILL } &&
                    measurer.measure(title, titleStyle, maxLines = 1).size.width + gap + endWidth > width
                dropped[0] = below
                val text = measurables[0].measure(free.copy(maxWidth = if (below) width else (width - gap - endWidth).coerceAtLeast(0)))
                val endHeight = ends.maxOfOrNull { it.second.height } ?: 0
                val top = 8.dp.roundToPx()
                val height = if (below) text.height + top + endHeight else maxOf(text.height, endHeight)
                layout(width, height) {
                    if (below) {
                        text.placeRelative(0, 0)
                        // Start-aligned with the words: a words-only button shifts back by its padding
                        var x = 0
                        ends.forEach { (m, p) -> p.placeRelative(x - shift(m), text.height + top); x += p.width }
                    } else {
                        text.placeRelative(0, (height - text.height) / 2)
                        var x = width - endWidth
                        ends.forEach { (m, p) -> p.placeRelative(x, (height - p.height) / 2); x += p.width - shift(m) }
                    }
                }
            }
        }
    }
}

// What a [Pill] in an [Item]'s trailing slot tags itself with, so the row can move it under its words
internal const val PILL = "pill"
internal const val BARE_PILL = "barePill"

/** True inside a [SeamSheet], where rows rise one step above the sheet instead of the screen. */
internal val LocalOnSheet = staticCompositionLocalOf { false }

/** A row's surface: [raisedOnSheet] on a sheet, else surfaceContainer. */
@Composable
internal fun rowColor(): Color = if (LocalOnSheet.current) raisedOnSheet() else MaterialTheme.colorScheme.surfaceContainer

/** What sits sunk into a row (the lead's circle, a gauge's track): one step off the row, light or dark. */
@Composable
internal fun wellColor(): Color =
    if (LocalOnSheet.current) MaterialTheme.colorScheme.onSurface.copy(alpha = 0.1f).compositeOver(raisedOnSheet())
    else MaterialTheme.colorScheme.surfaceContainerHighest

/** True inside an [Item]'s trailing slot, where a [Pill] tags itself ([PILL], [BARE_PILL]) for the row to place. */
internal val LocalTrailing = staticCompositionLocalOf { false }

@Composable
private fun Lead(lead: Painter, isImage: Boolean, highlighted: Boolean, selected: Boolean, bounce: Any?) {
    val scheme = MaterialTheme.colorScheme
    // The symbol dips to 0.7 (as fast as text leaves its slot), then springs back
    val pop = remember { Animatable(1f) }
    var seen by remember { mutableStateOf(bounce) }
    val reduced = reducedMotion()
    LaunchedEffect(bounce) {
        if (bounce == seen) return@LaunchedEffect
        seen = bounce
        if (reduced) return@LaunchedEffect
        pop.animateTo(0.7f, tween(150, easing = Motion.EaseIn))
        pop.animateTo(1f, Motion.spring(300))
    }
    if (isImage) {
        // An app icon rounds into a circle and shrinks to 0.88 when picked (0.4 s spring)
        val corner by animateDpAsState(if (selected) 20.dp else 12.dp, Motion.spring(400), label = "icon")
        val scale by animateFloatAsState(if (selected) 0.88f else 1f, Motion.spring(400), label = "scale")
        Image(lead, contentDescription = null, modifier = Modifier.size(40.dp).scale(scale).clip(RoundedCornerShape(corner)))
        return
    }
    val corner by animateDpAsState(if (highlighted) 14.dp else 20.dp, Motion.spring(400), label = "icon")
    val bg = pick(highlighted, wellColor(), scheme.primary, Motion.ease(400))
    val tint = pick(highlighted, scheme.onSurface, scheme.onPrimary, Motion.ease(400))
    Box(Modifier.size(40.dp).clip(RoundedCornerShape(corner)).background(bg), contentAlignment = Alignment.Center) {
        Icon(lead, contentDescription = null, tint = tint, modifier = Modifier.size(20.dp).graphicsLayer { scaleX = pop.value; scaleY = pop.value })
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
) {
    val toggle = withToggleHaptic(onChange)
    Item(title, subtitle, lead = lead, highlighted = highlightWhenOn && checked, role = Role.Switch, onClick = if (enabled) ({ toggle(!checked) }) else null, bounce = checked, checked = checked) {
        // The row is the one toggle TalkBack stops on; the switch only shows it
        SeamSwitch(checked, null, enabled)
    }
}

/** [onChange] with the system's toggle tick, on or off. */
@Composable
private fun withToggleHaptic(onChange: (Boolean) -> Unit): (Boolean) -> Unit {
    val haptic = LocalHapticFeedback.current
    return { on ->
        haptic.performHapticFeedback(if (on) HapticFeedbackType.ToggleOn else HapticFeedbackType.ToggleOff)
        onChange(on)
    }
}

/**
 * Material's switch with a tick in its thumb when on, as the prototype draws it. Disabled, it keeps
 * its normal colours: every disabled switch sits in a dimmed group, and Material's greys on top read wrong.
 */
@Composable
internal fun SeamSwitch(checked: Boolean, onChange: ((Boolean) -> Unit)?, enabled: Boolean = true) = Switch(
    checked = checked, onCheckedChange = onChange?.let { withToggleHaptic(it) }, enabled = enabled,
    colors = MaterialTheme.colorScheme.let {
        SwitchDefaults.colors(
            disabledCheckedTrackColor = it.primary, disabledCheckedThumbColor = it.onPrimary, disabledCheckedIconColor = it.onPrimaryContainer,
            // Off keeps its edge too; Material's disabled-off is a near-white pill with none
            disabledUncheckedBorderColor = it.outline, disabledUncheckedTrackColor = it.surfaceContainerHighest, disabledUncheckedThumbColor = it.outline,
        )
    },
    thumbContent = if (checked) ({ Icon(Icons.Filled.Check, contentDescription = null, modifier = Modifier.size(SwitchDefaults.IconSize)) }) else null,
)

/** A small label chip ("On", "Sender only"): 12 sp, fully round like every other pill; its colour says the same as its words. */
@Composable
internal fun Chip(text: String, bg: Color, fg: Color, minText: String? = null) {
    // [minText]: as wide as those words at least, so a column of chips keeps one start edge
    val min = minText?.let { with(LocalDensity.current) { rememberTextMeasurer().measure(it, MaterialTheme.typography.labelSmall).size.width.toDp() } } ?: 0.dp
    Box(Modifier.clip(CircleShape).background(bg).padding(horizontal = 11.dp, vertical = 5.dp).widthIn(min = min), contentAlignment = Alignment.Center) {
        Swap(text) { Text(it, style = MaterialTheme.typography.labelSmall, color = fg, maxLines = 1) }
    }
}

/** A row's trailing "opens something" arrow. */
@Composable
internal fun Chevron(size: Dp = 20.dp) = Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(size))

/** A calm empty state: a glyph in a 96 dp tonal well, a title and a reason in words, and maybe one action. */
@Composable
internal fun EmptyState(icon: Int, title: String, body: String, modifier: Modifier = Modifier, action: (@Composable () -> Unit)? = null) = Column(
    modifier.padding(horizontal = 32.dp),
    horizontalAlignment = Alignment.CenterHorizontally,
    verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically),
) {
    val scheme = MaterialTheme.colorScheme
    Box(Modifier.size(96.dp).background(scheme.surfaceContainerHigh, CircleShape), contentAlignment = Alignment.Center) {
        Icon(painterResource(icon), contentDescription = null, modifier = Modifier.size(48.dp), tint = scheme.onSurfaceVariant)
    }
    Text(title, style = MaterialTheme.typography.headlineSmall, textAlign = TextAlign.Center)
    // Balanced lines, so a centred body never ends on one word; 24 dp further in than the title, so a long one stays a narrow block
    Text(body, style = MaterialTheme.typography.bodyMedium.copy(lineBreak = LineBreak.Heading), color = scheme.onSurfaceVariant, textAlign = TextAlign.Center, modifier = Modifier.padding(horizontal = 24.dp))
    if (action != null) { Spacer(Modifier.height(4.dp)); action() }
}

/**
 * Narrows text to its longest line: wrapped, a Text takes all the width it's offered, so a chip
 * around it would stay full width. Measures the same words in the same pass, so there's no jump.
 */
@Composable
internal fun Modifier.hugLines(text: AnnotatedString, style: TextStyle, maxLines: Int, placeholders: List<AnnotatedString.Range<Placeholder>> = emptyList()): Modifier {
    val measurer = rememberTextMeasurer()
    return layout { m, c ->
        val r = measurer.measure(text, style, maxLines = maxLines, placeholders = placeholders, constraints = Constraints(maxWidth = c.maxWidth))
        val w = ceil((0 until r.lineCount).maxOf { r.getLineRight(it) - r.getLineLeft(it) }).toInt().coerceIn(c.minWidth, c.maxWidth)
        val p = m.measure(c.copy(maxWidth = w))
        layout(p.width, p.height) { p.place(0, 0) }
    }
}
