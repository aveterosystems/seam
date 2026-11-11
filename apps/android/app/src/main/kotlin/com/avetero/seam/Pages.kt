// SPDX-License-Identifier: AGPL-3.0-only
package com.avetero.seam

import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.activity.compose.PredictiveBackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
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
 * A screen under Home: back, then a 34 sp title that scrolls with the page, fading as the first
 * 60 dp go by; past them a 20 sp title fades in beside the arrow (0.2 s) and the bar turns
 * surfaceContainer, so it reads as a bar over the content. [actions] sit at the bar's end
 * ("Select"); [overlay] draws over the content, pinned (the selection toolbar).
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
    PageFrame(title, onBack, actions, overlay, null, scrolled = { scroll.value.toFloat() }) { fade ->
        Column(Modifier.fillMaxSize().verticalScroll(scroll).padding(start = 16.dp, end = 16.dp, bottom = 40.dp)) {
            LargeTitle(title, fade)
            Column(verticalArrangement = Arrangement.spacedBy(18.dp), content = content)
        }
    }
}

/**
 * Picking items on a page turns its bar contextual, as Android's are: ✕ ("Cancel selection") in place of back, "[count] selected"
 * as the title (shown even before scrolling), and the bar a tone up.
 */
internal class Selection(val count: Int, val onClose: () -> Unit)

/**
 * [SubScreen] for a long list: the large title is the list's first item, so it scrolls the same way.
 * [gap] is between items; a screen whose rows group tightly passes a small one and spaces the rest itself.
 */
@Composable
internal fun LazySubScreen(
    title: String,
    onBack: () -> Unit,
    actions: @Composable RowScope.() -> Unit = {},
    overlay: @Composable BoxScope.() -> Unit = {},
    state: LazyListState = rememberLazyListState(),
    gap: Dp = 18.dp,
    selection: Selection? = null,
    content: LazyListScope.() -> Unit,
) = PageFrame(
    title, onBack, actions, overlay, selection,
    // Past the title item counts as fully scrolled
    scrolled = { if (state.firstVisibleItemIndex > 0) Float.MAX_VALUE else state.firstVisibleItemScrollOffset.toFloat() },
) { fade ->
    LazyColumn(
        Modifier.fillMaxSize(), state,
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 40.dp),
        verticalArrangement = Arrangement.spacedBy(gap),
    ) {
        // The list's gap stands in for the title's own 10. Selecting, the bar says what the page is, so the title folds away (catalogue 7)
        item(key = "title") {
            AnimatedVisibility(
                selection == null,
                enter = expandVertically(Motion.spring(420)) + fadeIn(Motion.ease(200)),
                exit = shrinkVertically(Motion.spring(420)) + fadeOut(Motion.ease(200)),
            ) { LargeTitle(title, fade, bottom = 0.dp) }
        }
        content()
    }
}

/** The bar, and what's under it; [scrolled] is how far the page has scrolled, in px. */
@Composable
private fun PageFrame(
    title: String,
    onBack: () -> Unit,
    actions: @Composable RowScope.() -> Unit,
    overlay: @Composable BoxScope.() -> Unit,
    selection: Selection?,
    scrolled: () -> Float,
    body: @Composable (fade: () -> Float) -> Unit,
) {
    val range = with(LocalDensity.current) { 60.dp.toPx() }
    // Read in layers and derived state only, so scrolling doesn't recompose the page
    val fade = { (scrolled() / range).coerceIn(0f, 1f) }
    val past by remember { derivedStateOf { fade() >= 1f } }
    val selecting = selection != null
    val mini by animateFloatAsState(if (past || selecting) 1f else 0f, Motion.ease(200), label = "mini")
    val scheme = MaterialTheme.colorScheme
    val bar = pick(selecting, pick(past, scheme.surface, scheme.surfaceContainer, Motion.ease(200)), scheme.surfaceContainerHigh, Motion.ease(200))
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
        Box(Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize()) {
                Row(
                    Modifier.fillMaxWidth().background(bar).padding(start = 4.dp, end = 4.dp, top = 6.dp).height(48.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(onClick = selection?.onClose ?: onBack, modifier = Modifier.size(48.dp)) {
                        Crossfade(selecting, animationSpec = Motion.ease(200), label = "nav") { close ->
                            Icon(
                                if (close) Icons.Filled.Close else Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = if (close) "Cancel selection" else "Back",
                                tint = scheme.onSurfaceVariant, modifier = Modifier.size(22.dp),
                            )
                        }
                    }
                    // The title swaps for the count once; after that only the number counts (catalogue 4).
                    // The last count stays so "N selected" still reads while it swaps back out
                    val count = remember { mutableIntStateOf(0) }.apply { selection?.let { intValue = it.count } }.intValue
                    val style = MaterialTheme.typography.titleLarge
                    Swap(if (selecting) "" else title, Modifier.weight(1f).padding(start = 2.dp).graphicsLayer { alpha = mini }) {
                        if (it.isNotEmpty()) Text(it, style = style, maxLines = 1)
                        else Row(Modifier.clearAndSetSemantics { contentDescription = "$count selected"; liveRegion = LiveRegionMode.Polite }) {
                            Count(count) { n -> Text(n, style = style.copy(fontFeatureSettings = "tnum"), maxLines = 1) }
                            Text(" selected", style = style, maxLines = 1)
                        }
                    }
                    actions()
                }
                body(fade)
            }
            overlay()
        }
    }
}

@Composable
private fun LargeTitle(title: String, fade: () -> Float, bottom: Dp = 10.dp) = Text(
    title, style = MaterialTheme.typography.displayMedium,
    modifier = Modifier.semantics { heading() }.padding(start = 4.dp, top = 6.dp, bottom = bottom)
        .graphicsLayer { alpha = (1 - 1.4f * fade()).coerceAtLeast(0f) },
)
