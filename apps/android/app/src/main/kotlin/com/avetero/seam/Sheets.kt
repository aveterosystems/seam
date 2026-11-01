// SPDX-License-Identifier: AGPL-3.0-only
package com.avetero.seam

import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.animation.AnimatedVisibility
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.luminance
import androidx.core.view.WindowCompat
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import kotlinx.coroutines.delay

/**
 * A modal surface in its own window (so TalkBack stays inside it and back closes it), animated
 * by us rather than the window: the scrim fades over 0.35 s, then [body] animates in. [body]
 * gets the open/closed state to animate on and a `close` that animates out before [onDismiss].
 */
@Composable
private fun Modal(onDismiss: () -> Unit, body: @Composable BoxScope.(shown: androidx.compose.animation.core.Transition<Boolean>, close: () -> Unit) -> Unit) {
    val state = remember { MutableTransitionState(false) }
    LaunchedEffect(Unit) { state.targetState = true }
    LaunchedEffect(state.isIdle, state.currentState) { if (state.isIdle && !state.currentState && !state.targetState) onDismiss() }
    val close = { state.targetState = false }
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

/** Every Seam sheet: rises from 105% below on the spring (0.5 s); a handle, a 22 sp title, a line under it, then [content]. */
@Composable
internal fun SeamSheet(
    title: String,
    body: String?,
    onDismiss: () -> Unit,
    content: @Composable ColumnScope.(close: () -> Unit) -> Unit,
) = Modal(onDismiss) { t, close ->
    t.AnimatedVisibility(
        { it }, Modifier.align(Alignment.BottomCenter),
        enter = slideInVertically(Motion.spring(500)) { it * 105 / 100 },
        exit = slideOutVertically(Motion.spring(500)) { it * 105 / 100 },
    ) {
        Column(
            Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceContainerLow, RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp))
                .semantics { paneTitle = title }
                .navigationBarsPadding().padding(start = 16.dp, end = 16.dp, top = 10.dp, bottom = 18.dp),
        ) {
            Box(
                Modifier.padding(top = 4.dp, bottom = 16.dp).size(32.dp, 4.dp).align(Alignment.CenterHorizontally)
                    .background(MaterialTheme.colorScheme.outline.copy(alpha = 0.5f), RoundedCornerShape(2.dp)),
            )
            Text(title, fontSize = 22.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(start = 8.dp, end = 8.dp, bottom = 4.dp))
            if (body != null) {
                Text(body, fontSize = 13.5.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(start = 8.dp, end = 8.dp, bottom = 16.dp))
            }
            content(close)
        }
    }
}

/** A dialog: scales up from 0.85 on the spring (0.45 s) as it fades in (0.2 s). Words centred, actions at the end. */
@Composable
internal fun SeamDialog(
    title: String,
    text: String,
    onDismiss: () -> Unit,
    icon: Painter? = null,
    actions: @Composable (close: () -> Unit) -> Unit,
) = Modal(onDismiss) { t, close ->
    t.AnimatedVisibility(
        { it }, Modifier.align(Alignment.Center).padding(horizontal = 24.dp),
        enter = scaleIn(Motion.spring(450), 0.85f) + fadeIn(tween(200, easing = Motion.Ease)),
        exit = scaleOut(Motion.spring(450), 0.85f) + fadeOut(tween(200, easing = Motion.Ease)),
    ) {
        Column(
            Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceContainerHigh, RoundedCornerShape(28.dp))
                .semantics { paneTitle = title }.padding(24.dp),
        ) {
            if (icon != null) Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(26.dp).align(Alignment.CenterHorizontally))
            Text(
                title, fontSize = 22.sp, fontWeight = FontWeight.Medium, textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(top = if (icon != null) 12.dp else 0.dp, bottom = 10.dp),
            )
            Text(text, fontSize = 14.sp, lineHeight = 20.3.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(bottom = 20.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp, Alignment.End)) { actions(close) }
        }
    }
}

/**
 * Pick one, as grouped cards: the picked one turns primaryContainer (its radio fills, when
 * [radio]), and after [pickMs] the choice is made, so the sheet closes on the answer seen.
 */
@Composable
internal fun <T> Choices(
    options: List<T>,
    current: T?,
    label: (T) -> String,
    detail: (T) -> String? = { null },
    radio: Boolean = true,
    pickMs: Long = 320,
    onPick: (T) -> Unit,
) {
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
                label(o), detail(o), highlighted = on, plain = true, role = Role.RadioButton, onClick = { picked = o },
                start = if (radio) ({ Radio(on) }) else null,
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
        Choices(PauseFor.entries, null, { it.label }, radio = false, pickMs = 260) {
            onPause(it)
            close()
        }
    }
