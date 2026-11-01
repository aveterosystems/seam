// SPDX-License-Identifier: AGPL-3.0-only
package com.avetero.seam

import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.activity.compose.PredictiveBackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/** The app's screens above Home. Pairing and set-up aren't here: the session's state decides those. */
enum class Page { Home, Settings, Notifications }

/**
 * The page stack, moving as the prototype's views do. Forward: the new page slides in from 30%
 * right as the old drifts 18% left (0.5 s spring), both fading over 0.3 s. Back, by gesture or
 * the arrow: the page first "peeks" (scales to 0.9, leans 6% right, rounds to 32 dp; under the
 * finger, or over 0.2 s), then leaves to the right as the one behind it slides back. With
 * animations off, both are a crossfade. [content] gets the page and the animated way back.
 */
@Composable
fun Pages(stack: List<Page>, onPop: () -> Unit, content: @Composable (Page, back: () -> Unit) -> Unit) {
    val reduced = reducedMotion()
    val peek = remember { Animatable(0f) }
    var peeking by remember { mutableStateOf<Page?>(null) }
    val scope = rememberCoroutineScope()
    fun leave() {
        onPop()
        scope.launch { peek.animateTo(0f, Motion.spring(500)) }
    }
    val back: () -> Unit = {
        if (stack.size > 1) scope.launch {
            peeking = stack.last()
            peek.animateTo(1f, Motion.emphasized(190))
            leave()
        }
    }
    PredictiveBackHandler(enabled = stack.size > 1) { events ->
        peeking = stack.last()
        try {
            events.collect { peek.snapTo(it.progress) }
            leave()
        } catch (e: CancellationException) {
            scope.launch { peek.animateTo(0f, Motion.emphasized(200)) }
            throw e
        }
    }
    AnimatedContent(
        stack, contentKey = { it.last() }, label = "pages",
        transitionSpec = {
            val forward = targetState.size >= initialState.size
            val fade = Motion.ease<Float>(300)
            when {
                reduced -> fadeIn(fade) togetherWith fadeOut(fade)
                forward -> (slideInHorizontally(Motion.spring(500)) { it * 3 / 10 } + fadeIn(fade)) togetherWith
                    (slideOutHorizontally(Motion.spring(500)) { -it * 18 / 100 } + fadeOut(fade))
                else -> ContentTransform(
                    slideInHorizontally(Motion.spring(500)) { -it * 18 / 100 } + fadeIn(fade),
                    slideOutHorizontally(Motion.spring(500)) { it * 3 / 10 } + fadeOut(fade),
                    targetContentZIndex = -1f, // the leaving page stays on top as it goes
                )
            }
        },
    ) { s ->
        val page = s.last()
        Box(
            Modifier.fillMaxSize().graphicsLayer {
                if (page == peeking && !reduced) {
                    val p = peek.value
                    scaleX = 1 - 0.1f * p
                    scaleY = 1 - 0.1f * p
                    translationX = size.width * 0.06f * p
                    shape = RoundedCornerShape(32.dp * p)
                    clip = p > 0f
                }
            },
        ) { content(page, back) }
    }
}

/**
 * A screen under Home: back, then a 34 sp title that shrinks to 0.6 and fades as the first 60 dp
 * scroll by, when a 20 sp title fades in beside the arrow (0.2 s). [actions] sit at the bar's
 * end ("Select"); [overlay] draws over the content, pinned (the selection toolbar).
 */
@Composable
internal fun SubScreen(
    title: String,
    onBack: () -> Unit,
    actions: @Composable RowScope.() -> Unit = {},
    overlay: @Composable BoxScope.() -> Unit = {},
    content: @Composable ColumnScope.() -> Unit,
) {
    val scroll = rememberScrollState()
    val range = with(LocalDensity.current) { 60.dp.toPx() }
    val k = (scroll.value / range).coerceIn(0f, 1f)
    val mini by animateFloatAsState(if (k >= 1f) 1f else 0f, Motion.ease(200), label = "mini")
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
        Box(Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize()) {
                Column(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface).padding(start = 4.dp, end = 4.dp, top = 6.dp, bottom = 10.dp)) {
                    Row(Modifier.fillMaxWidth().height(48.dp), verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClick = onBack, modifier = Modifier.size(48.dp)) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(22.dp))
                        }
                        Text(title, fontSize = 20.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(start = 2.dp).graphicsLayer { alpha = mini })
                        Spacer(Modifier.weight(1f))
                        actions()
                    }
                    Text(
                        title, fontSize = 34.sp, lineHeight = 37.4.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-0.6).sp, maxLines = 1,
                        modifier = Modifier.semantics { heading() }.padding(start = 16.dp, end = 16.dp, top = (6 * (1 - k)).dp).height((44 * (1 - k)).dp)
                            .graphicsLayer {
                                transformOrigin = TransformOrigin(0f, 1f)
                                scaleX = 1 - 0.4f * k
                                scaleY = 1 - 0.4f * k
                                alpha = (1 - 1.4f * k).coerceAtLeast(0f)
                            },
                    )
                }
                Column(
                    Modifier.fillMaxSize().verticalScroll(scroll).padding(start = 16.dp, end = 16.dp, bottom = 40.dp),
                    verticalArrangement = Arrangement.spacedBy(18.dp),
                    content = content,
                )
            }
            overlay()
        }
    }
}
