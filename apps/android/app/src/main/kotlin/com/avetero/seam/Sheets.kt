// SPDX-License-Identifier: AGPL-3.0-only
package com.avetero.seam

import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.activity.compose.PredictiveBackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.rememberTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.animation.core.animate
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.unit.Velocity
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.core.view.WindowCompat
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import com.avetero.seam.crypto.untilWords

/**
 * A modal surface in its own window (so TalkBack stays inside it and back closes it), animated
 * by us rather than the window: the scrim fades over 0.35 s, then [body] animates in. [body]
 * gets the open/closed state to animate on and a `close` that animates out before [onDismiss].
 */
@Composable
private fun Modal(onDismiss: () -> Unit, onClosing: () -> Unit = {}, body: @Composable BoxScope.(shown: androidx.compose.animation.core.Transition<Boolean>, close: () -> Unit) -> Unit) {
    val state = remember { MutableTransitionState(false) }
    LaunchedEffect(Unit) { state.targetState = true }
    LaunchedEffect(state.isIdle, state.currentState) { if (state.isIdle && !state.currentState && !state.targetState) onDismiss() }
    val close = {
        if (state.targetState) onClosing()
        state.targetState = false
    }
    val scrim = scrimColor
    val light = MaterialTheme.colorScheme.surface.luminance() > 0.5f
    Dialog(onDismissRequest = close, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        val view = LocalView.current
        SideEffect {
            (view.parent as? DialogWindowProvider)?.window?.let {
                it.setWindowAnimations(0)
                it.setDimAmount(0f)
                // Its own window has its own bars: keep their icons right for the theme
                WindowCompat.getInsetsController(it, view).apply { isAppearanceLightStatusBars = light; isAppearanceLightNavigationBars = light }
            }
            // Its own window: refuse taps that pass through another app's overlay (SR-32-03)
            view.filterTouchesWhenObscured = true
            view.rootView.filterTouchesWhenObscured = true
        }
        val t = rememberTransition(state, label = "modal")
        Box(Modifier.fillMaxSize()) {
            t.AnimatedVisibility({ it }, enter = fadeIn(Motion.ease(350)), exit = fadeOut(Motion.ease(350))) {
                // The status bar stays clear, as in the prototype
                Box(
                    Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.statusBars).background(scrim)
                        .clickable(remember { MutableInteractionSource() }, indication = null, onClick = close),
                )
            }
            body(t, close)
        }
    }
}

/** A card on a sheet: one step above the sheet's own colour, light or dark, so its edge shows. */
@Composable
internal fun raisedOnSheet(): Color = MaterialTheme.colorScheme.run { if (surface.luminance() > 0.5f) surfaceContainerHigh else surfaceContainerHighest }

/**
 * Every Seam sheet: rises from 105% below on the spring (0.5 s); a handle, a 22 sp title, a line
 * under it, then [content], its parts rising in one after another. Drag it down past half its
 * height, or fling it, to close it; let go sooner and it springs back. A back gesture shrinks it
 * toward its bottom edge as it goes, and closes it when the gesture completes. One 16 dp gutter for
 * every sheet's cards, buttons and chips (as grouped lists); its title and words 8 dp further in.
 * [content] adds no side padding of its own. [lead] (a 24 dp icon) sits before the title, 12 dp from it.
 */
@Composable
internal fun SeamSheet(
    title: String,
    body: String?,
    onDismiss: () -> Unit,
    lead: (@Composable () -> Unit)? = null,
    /** Runs the moment the sheet starts to close, not after its exit like [onDismiss]: for a choice that can't wait. */
    onClosing: () -> Unit = {},
    content: @Composable ColumnScope.(close: () -> Unit) -> Unit,
) = Modal(onDismiss, onClosing) { t, close ->
    val reduced = reducedMotion()
    val scheme = MaterialTheme.colorScheme
    // In dark, Low is as dark as the scrimmed screen behind it (the camera): one step up keeps its edge
    val surface = if (scheme.surface.luminance() > 0.5f) scheme.surfaceContainerLow else scheme.surfaceContainerHigh
    t.AnimatedVisibility(
        { it }, Modifier.align(Alignment.BottomCenter),
        enter = if (reduced) fadeIn(Motion.ease(350)) else slideInVertically(Motion.spring(500)) { it * 105 / 100 },
        exit = if (reduced) fadeOut(Motion.ease(350)) else slideOutVertically(Motion.spring(500)) { it * 105 / 100 },
    ) {
        val scope = rememberCoroutineScope()
        var drag by remember { mutableFloatStateOf(0f) } // pulled down, in px
        val back = remember { Animatable(0f) } // the back gesture's progress
        var height by remember { mutableIntStateOf(0) }
        val fling = with(LocalDensity.current) { 400.dp.toPx() }
        // Its words scroll when they don't fit (large text, a short screen). A drag pulls the sheet
        // down only once they're at the top, and pushing up takes back a pull before scrolling them
        val pull = remember {
            object : NestedScrollConnection {
                override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                    if (available.y >= 0 || drag <= 0f) return Offset.Zero
                    val used = maxOf(available.y, -drag)
                    drag += used
                    return Offset(0f, used)
                }

                override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
                    if (source != NestedScrollSource.UserInput || available.y <= 0) return Offset.Zero
                    drag += available.y
                    return Offset(0f, available.y)
                }

                override suspend fun onPreFling(available: Velocity): Velocity {
                    if (drag <= 0f) return Velocity.Zero
                    if (drag > height / 2f || available.y > fling) close()
                    else animate(drag, 0f, available.y, Motion.spring(500)) { v, _ -> drag = v }
                    return available
                }
            }
        }
        PredictiveBackHandler { events ->
            try {
                events.collect { back.snapTo(it.progress) }
                close()
            } catch (e: CancellationException) {
                scope.launch { back.animateTo(0f, Motion.spring(300)) }
                throw e
            }
        }
        Column(
            // Below the status bar, however tall its words grow
            Modifier.windowInsetsPadding(WindowInsets.statusBars).padding(top = 8.dp).fillMaxWidth().onSizeChanged { height = it.height }
                .graphicsLayer {
                    translationY = drag
                    // As Material's sheet: narrower more than shorter, held at its bottom edge
                    scaleX = 1 - 0.1f * back.value
                    scaleY = 1 - 0.05f * back.value
                    transformOrigin = TransformOrigin(0.5f, 1f)
                }
                .nestedScroll(pull)
                .background(surface, RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp))
                .semantics { paneTitle = title }
                .verticalScroll(rememberScrollState())
                .navigationBarsPadding().padding(start = 16.dp, end = 16.dp, top = 10.dp, bottom = 18.dp),
        ) {
            // As Material's sheet: TalkBack can close it from the handle
            Box(
                Modifier.padding(top = 4.dp, bottom = 16.dp).size(32.dp, 4.dp).align(Alignment.CenterHorizontally)
                    .semantics { contentDescription = "Close"; onClick(label = "Close") { close(); true } }
                    .background(MaterialTheme.colorScheme.outline.copy(alpha = 0.5f), RoundedCornerShape(2.dp)),
            )
            Rise(reduced) {
                // A sheet that changes step in place (pairing) swaps its title too
                Row(Modifier.padding(start = 8.dp, end = 8.dp, bottom = 4.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    lead?.invoke()
                    Swap(title, Modifier.weight(1f, fill = false)) { Text(it, style = MaterialTheme.typography.headlineSmall, modifier = Modifier.semantics { heading() }) }
                }
                if (body != null) {
                    Text(body, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(start = 8.dp, end = 8.dp, bottom = 16.dp))
                }
                // Its rows rise one step above the sheet, not the screen
                CompositionLocalProvider(LocalOnSheet provides true) { content(close) }
            }
        }
    }
}

/**
 * Catalogue 1's sections, stacked as a column: each rises 6 dp and fades in on the spring, 35 ms
 * after the one above, from 60 ms. Parts past the fifth start with it, so all have landed by about
 * 350 ms however long the sheet is.
 */
@Composable
private fun Rise(reduced: Boolean, content: @Composable () -> Unit) {
    // Reduced motion: everything is in place at once, fading with the sheet
    val clock = remember { Animatable(if (reduced) RISE_END else 0f) } // ms since the sheet opened
    LaunchedEffect(Unit) { clock.animateTo(RISE_END, tween(RISE_END.toInt(), easing = LinearEasing)) }
    val lift = with(LocalDensity.current) { 6.dp.toPx() }
    Layout(content) { measurables, constraints ->
        val parts = measurables.map { it.measure(constraints.copy(minHeight = 0)) }
        layout(constraints.maxWidth, parts.sumOf { it.height }) {
            var y = 0
            parts.forEachIndexed { i, part ->
                part.placeRelativeWithLayer(0, y) {
                    val f = ((clock.value - 60 - 35 * minOf(i, 4)) / RISE_MS).coerceIn(0f, 1f)
                    alpha = Motion.Ease.transform(f)
                    translationY = lift * (1 - Motion.Spring.transform(f))
                }
                y += part.height
            }
        }
    }
}

// One section's rise, on the prototype's spring curve (it's settled by 45% of this)
private const val RISE_MS = 280f
private const val RISE_END = 60 + 35 * 4 + RISE_MS

/** A dialog: scales up from 0.85 on the spring (0.45 s) as it fades in (0.2 s). Words centred, actions at the end. */
@Composable
internal fun SeamDialog(
    title: String,
    text: String,
    onDismiss: () -> Unit,
    icon: Painter? = null,
    actions: @Composable (close: () -> Unit) -> Unit,
) = Modal(onDismiss) { t, close ->
    val fade = tween<Float>(200, easing = Motion.Ease)
    val reduced = reducedMotion()
    t.AnimatedVisibility(
        { it }, Modifier.align(Alignment.Center).padding(horizontal = 24.dp),
        enter = if (reduced) fadeIn(fade) else scaleIn(Motion.spring(450), 0.85f) + fadeIn(fade),
        exit = if (reduced) fadeOut(fade) else scaleOut(Motion.spring(450), 0.85f) + fadeOut(fade),
    ) {
        Column(
            Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceContainerHigh, RoundedCornerShape(28.dp))
                .semantics { paneTitle = title }.padding(24.dp),
        ) {
            if (icon != null) Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(26.dp).align(Alignment.CenterHorizontally))
            Text(
                title, style = MaterialTheme.typography.headlineSmall, textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(top = if (icon != null) 12.dp else 0.dp, bottom = 10.dp),
            )
            Text(text, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(bottom = 20.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp, Alignment.End)) { actions(close) }
        }
    }
}

/**
 * Pick one, as grouped cards: with [radio], the picked one's radio fills on a quiet secondaryContainer (one
 * emphasis); else it turns primaryContainer, [icon] leading it in the rows' 40 dp circle, with the system's
 * confirm tick, and after [pickMs] the choice is made, so the sheet closes on the answer seen.
 */
@Composable
internal fun <T> Choices(
    options: List<T>,
    current: T?,
    label: (T) -> String,
    detail: (T) -> String? = { null },
    radio: Boolean = true,
    pickMs: Long = 320,
    icon: ((T) -> Int)? = null,
    onPick: (T) -> Unit,
) {
    val haptic = LocalHapticFeedback.current
    var picked by remember { mutableStateOf(current) }
    LaunchedEffect(picked) {
        val p = picked
        if (p != null && p != current) {
            delay(pickMs)
            onPick(p)
        }
    }
    Section(null) {
        options.forEach { o ->
            val on = o == picked
            Item(
                label(o), detail(o), highlighted = on && !radio, selected = on && radio, plain = true, role = Role.RadioButton, checked = on,
                onClick = {
                    if (o != picked) haptic.performHapticFeedback(HapticFeedbackType.Confirm)
                    picked = o
                },
                start = if (radio) ({ Radio(on) }) else null,
                lead = if (radio) null else icon?.let { painterResource(it(o)) },
            )
        }
    }
}

/** The prototype's radio: a 2 dp ring that turns teal (0.3 s) and a dot that bounces in (0.35 s). */
@Composable
private fun Radio(on: Boolean) {
    val ring = pick(on, MaterialTheme.colorScheme.outline, MaterialTheme.colorScheme.primary, Motion.ease(300))
    val dot by animateFloatAsState(if (on) 1f else 0f, Motion.bouncy(350), label = "dot")
    val teal = MaterialTheme.colorScheme.primary
    Canvas(Modifier.size(20.dp)) {
        val w = 2.dp.toPx()
        drawCircle(ring, radius = (size.minDimension - w) / 2, style = Stroke(w))
        drawCircle(teal, radius = 5.dp.toPx() * dot)
    }
}

/** "Pause Seam": how long. The home screen's Pause and the notification's Pause both open this. */
@Composable
fun PauseSheet(onDismiss: () -> Unit, onPause: (PauseFor) -> Unit) =
    SeamSheet("Pause Seam", "Nothing syncs either way until it ends. Your Mac shows that you paused.", onDismiss) { close ->
        val now = remember { System.currentTimeMillis() }
        // When each ends ("Until 15:12"); the morning's own label already says "tomorrow"
        val ends = { p: PauseFor ->
            val until = p.until(now)
            when (p) {
                PauseFor.RESUME -> "No end time"
                PauseFor.MORNING -> "At " + SystemTime().time(until)
                else -> untilWords(until, now, SystemTime()).replaceFirstChar { it.uppercase() }
            }
        }
        Choices(PauseFor.entries, null, { it.label }, ends, radio = false, pickMs = 260,
            icon = {
                when (it) {
                    PauseFor.HALF_HOUR -> R.drawable.ic_clock_half
                    PauseFor.HOUR -> R.drawable.ic_clock
                    PauseFor.MORNING -> R.drawable.ic_sunrise
                    PauseFor.RESUME -> R.drawable.ic_pause_line
                }
            }) {
            onPause(it)
            close()
        }
    }
