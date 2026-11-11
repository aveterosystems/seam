// SPDX-License-Identifier: AGPL-3.0-only
package com.avetero.seam

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.graphics.shapes.CornerRounding
import androidx.graphics.shapes.Cubic
import androidx.graphics.shapes.Morph
import androidx.graphics.shapes.RoundedPolygon
import androidx.graphics.shapes.circle
import androidx.graphics.shapes.star
import com.avetero.seam.crypto.LinkLook
import com.avetero.seam.crypto.LookLine

/**
 * The hero's shape on a 240 grid: one of Material Expressive's shapes scaled by [radius].
 * Connected it's the 9-sided cookie, turning slowly; paused, the finer, still 12-sided cookie in
 * tertiary; otherwise a plain still grey circle. Changing look morphs one shape into the other on
 * a spring. The devices reach 108.2 from the centre, so the circle and each cookie's valleys
 * (0.82 of [radius] for the 9-sided, 0.83 for the 12-sided) clear them.
 */
data class CookieLook(val radius: Float, val form: Form, val fill: Fill, val turning: Boolean) {
    enum class Form(val polygon: RoundedPolygon) {
        // MaterialShapes.Cookie9Sided and Cookie12Sided before they're normalised, so they stay centred as they turn
        // (paused stops at any angle; twelve scallops look deliberate wherever it rests)
        Cookie(RoundedPolygon.star(9, innerRadius = .8f, rounding = CornerRounding(.5f))),
        FineCookie(RoundedPolygon.star(12, innerRadius = .8f, rounding = CornerRounding(.5f))),
        Circle(RoundedPolygon.circle(9)),
    }

    enum class Fill { Live, Paused, Off }

    companion object {
        /** Before there's a Mac: the teal cookie, still, so the first screen looks alive but not connected. */
        val Welcome = CookieLook(133f, Form.Cookie, Fill.Live, turning = false)
    }
}

fun LinkLook.cookie(): CookieLook = when {
    pausedHere -> CookieLook(132f, CookieLook.Form.FineCookie, CookieLook.Fill.Paused, turning = false)
    connected -> CookieLook(133f, CookieLook.Form.Cookie, CookieLook.Fill.Live, turning = true)
    else -> CookieLook(114f, CookieLook.Form.Circle, CookieLook.Fill.Off, turning = false)
}

/**
 * Board A01's hero: the cookie with the Mac and this phone joined by stitches
 * (design-system.md, "The stitch"; motion.md entry 10): solid lines that draw themselves in on
 * the same Wi‑Fi, dashes flowing along through Tor, grey dots travelling to the Mac while
 * connecting, broken grey and still when not connected.
 */
@Composable
fun Hero(look: LinkLook, size: Dp = 196.dp, cookie: CookieLook = look.cookie()) {
    val c = cookie
    val reduced = reducedMotion()
    // The prototype animates the path on its bouncy curve (0.8 s) and the fill over 0.6 s
    val radius by animateFloatAsState(c.radius, Motion.bouncy(800), label = "radius")
    // Morphs from the last shape to this one; interrupted, it starts from whichever end it was nearer
    var forms by remember { mutableStateOf(c.form to c.form) }
    val morphed = remember { Animatable(1f) }
    LaunchedEffect(c.form) {
        if (c.form == forms.second) return@LaunchedEffect
        forms = (if (morphed.value < .5f) forms.first else forms.second) to c.form
        morphed.snapTo(0f)
        morphed.animateTo(1f, Motion.bouncy(800))
    }
    val scheme = MaterialTheme.colorScheme
    val fill = pick(
        c.fill == CookieLook.Fill.Off,
        pick(c.fill == CookieLook.Fill.Paused, scheme.primaryContainer, scheme.tertiaryContainer, Motion.ease(600)),
        scheme.surfaceContainerHigh, Motion.ease(600),
    )
    // Turns only while connected, and stops where it is when the link drops rather than snapping upright
    val angle = remember { Animatable(0f) }
    if (c.turning && !reduced) LaunchedEffect(Unit) {
        while (true) angle.animateTo(angle.value + 360f, tween(Motion.TURN_MS, easing = LinearEasing))
    }
    // The devices fade from teal to grey when the link drops (motion.md entry 10)
    val ink = pick(
        c.fill == CookieLook.Fill.Off,
        pick(c.fill == CookieLook.Fill.Paused, scheme.onPrimaryContainer, scheme.onTertiaryContainer, Motion.ease(500)),
        scheme.onSurfaceVariant, Motion.ease(500),
    )
    // A picture: the name and status chip below say it in words
    Box(Modifier.size(size).clearAndSetSemantics {}) {
        Canvas(Modifier.size(size)) {
            val k = this.size.width / 240f
            rotate(angle.value) { drawPath(cookiePath(k, radius, morph(forms).asCubics(morphed.value)), fill) }
            // The screen is the cookie's own colour nudged toward the ink: the page colour read as a hole, a black slab in dark
            translateDevices(k) { drawDevices(k, ink, fill, lerp(fill, ink, 0.12f)) }
        }
        Crossfade(look.line, animationSpec = Motion.ease(400), label = "stitch") { line ->
            Stitches(line, size, reduced, scheme.primary, ink.copy(alpha = 0.35f))
        }
    }
}

@Composable
private fun Stitches(line: LookLine, size: Dp, reduced: Boolean, teal: Color, grey: Color) {
    // Same Wi‑Fi draws each line left to right, 80 ms apart, as the link forms or the screen opens
    val drawn = remember { List(3) { Animatable(if (line == LookLine.SOLID && !reduced) 0f else 1f) } }
    if (line == LookLine.SOLID && !reduced) {
        drawn.forEachIndexed { i, a -> LaunchedEffect(a) { a.animateTo(1f, Motion.emphasized(700, 80 * i)) } }
    }
    // Tor's dashes flow along, 11 units every 0.9 s; connecting, the dots travel from this phone to the Mac at the same pace
    val phase = flowPhase((line == LookLine.DASHED || line == LookLine.DOTTED) && !reduced)
    Canvas(Modifier.size(size)) {
        val k = this.size.width / 240f
        translateDevices(k) {
            listOf(44f, 58f, 72f).forEachIndexed { i, y ->
                val (color, effect, to) = when (line) {
                    LookLine.SOLID -> Triple(teal, null, 122f + 24f * drawn[i].value)
                    LookLine.DASHED -> Triple(teal, PathEffect.dashPathEffect(floatArrayOf(5 * k, 6 * k), -phase.value * k), 146f)
                    // A 5.5-unit period, so the 11-unit loop repeats seamlessly
                    LookLine.DOTTED -> Triple(grey, dots(5.5f * k, phase.value * k), 146f)
                    // Snapped, not dashed: two solid halves over the 24-unit line with a clear 5-unit gap past the round caps, so it never reads as grey Tor
                    LookLine.BROKEN -> Triple(grey, PathEffect.dashPathEffect(floatArrayOf(7 * k, 10 * k)), 146f)
                }
                drawLine(color, Offset(122 * k, y * k), Offset(to * k, y * k), 5 * k, StrokeCap.Round, effect)
            }
        }
    }
}

/**
 * The connecting stitch on its own, small (motion.md entry 10): three grey dots travelling along a
 * row at the hero's pace, for a wait with nothing else moving. Still under reduced motion.
 */
@Composable
fun WaitingDots(modifier: Modifier = Modifier) {
    val phase = flowPhase(!reducedMotion())
    val grey = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.35f)
    // 11 dp apart, so one loop of the flow moves each dot onto the next
    Canvas(modifier.size(40.dp, 6.dp)) {
        val y = size.height / 2
        drawLine(grey, Offset(3.dp.toPx(), y), Offset(37.dp.toPx(), y), size.height, StrokeCap.Round, dots(11.dp.toPx(), -phase.value.dp.toPx()))
    }
}

/** 0 to 11 every [Motion.DASH_FLOW_MS], looping while [running]: the stitch's flow (Tor's dashes, connecting's dots). */
@Composable
private fun flowPhase(running: Boolean): Animatable<Float, AnimationVector1D> {
    val phase = remember { Animatable(0f) }
    if (running) LaunchedEffect(Unit) {
        while (true) { phase.snapTo(0f); phase.animateTo(11f, tween(Motion.DASH_FLOW_MS, easing = LinearEasing)) }
    }
    return phase
}

/** Round dots [period] apart (on a round-capped line), shifted by [phase]. */
private fun dots(period: Float, phase: Float) = PathEffect.dashPathEffect(floatArrayOf(0.1f, period - 0.1f), phase)

/**
 * The devices span x -3..196, y 14..107 (base and phone included), so (24, 59) puts that box's
 * centre on the cookie's (120, 120), half a unit inboard so the base's far corner stays 108.2 out.
 */
private inline fun DrawScope.translateDevices(k: Float, block: DrawScope.() -> Unit) = translate(24 * k, 59 * k, block)

private fun DrawScope.drawDevices(k: Float, ink: Color, pc: Color, screen: Color) {
    fun p(x: Float, y: Float) = Offset(x * k, y * k)
    drawRoundRect(screen, p(8f, 22f), Size(104 * k, 70 * k), CornerRadius(9 * k))
    drawRoundRect(ink, p(8f, 22f), Size(104 * k, 70 * k), CornerRadius(9 * k), style = Stroke(5 * k))
    drawLine(ink, p(0f, 104f), p(120f, 104f), 6 * k, StrokeCap.Round)
    drawRoundRect(ink, p(152f, 14f), Size(44 * k, 90 * k), CornerRadius(11 * k))
    drawLine(pc, p(168f, 95f), p(180f, 95f), 4 * k, StrokeCap.Round)
}

/** One morph per pair of shapes (Morph matches their differing point counts), built once on first use. */
private val morphs = HashMap<Pair<CookieLook.Form, CookieLook.Form>, Morph>()

private fun morph(forms: Pair<CookieLook.Form, CookieLook.Form>) = morphs.getOrPut(forms) { Morph(forms.first.polygon, forms.second.polygon) }

/** The shape's [cubics] (radius 1 around the origin), scaled to [r] around (120, 120) and by [k]. */
private fun cookiePath(k: Float, r: Float, cubics: List<Cubic>) = Path().apply {
    val s = r * k
    val o = 120 * k
    cubics.forEachIndexed { i, b ->
        if (i == 0) moveTo(o + b.anchor0X * s, o + b.anchor0Y * s)
        cubicTo(o + b.control0X * s, o + b.control0Y * s, o + b.control1X * s, o + b.control1Y * s, o + b.anchor1X * s, o + b.anchor1Y * s)
    }
    close()
}
