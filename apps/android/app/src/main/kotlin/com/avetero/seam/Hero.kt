// SPDX-License-Identifier: AGPL-3.0-only
package com.avetero.seam

import androidx.compose.runtime.getValue
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.cos
import kotlin.math.sin

/**
 * The hero's shape on a 240 grid: r = [radius] + [scallop]·cos(9t). Connected it's the Expressive
 * 9-scallop "cookie", turning slowly; paused, a softer cookie in tertiary; otherwise a plain
 * still grey circle. Changing look springs the numbers, so the shape morphs from one to the other.
 */
data class CookieLook(val radius: Float, val scallop: Float, val fill: Fill, val turning: Boolean) {
    enum class Fill { Live, Paused, Off }
}

fun LinkLook.cookie(): CookieLook = when {
    paused -> CookieLook(104f, 5f, CookieLook.Fill.Paused, turning = false)
    connected -> CookieLook(110f, 9f, CookieLook.Fill.Live, turning = true)
    else -> CookieLook(100f, 0f, CookieLook.Fill.Off, turning = false)
}

/**
 * Board A01's hero: the cookie with the Mac and this phone joined by stitches
 * (design-system.md, "The stitch"; motion.md entry 10): solid lines that draw themselves in on
 * the same Wi‑Fi, dashes flowing along through Tor, broken grey and still when not connected.
 */
@Composable
fun Hero(macName: String, look: LinkLook, size: Dp = 196.dp) {
    val c = look.cookie()
    val reduced = reducedMotion()
    // The prototype animates the path on its bouncy curve (0.8 s) and the fill over 0.6 s
    val radius by animateFloatAsState(c.radius, Motion.bouncy(800), label = "radius")
    val scallop by animateFloatAsState(c.scallop, Motion.bouncy(800), label = "scallop")
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
    val onPc = scheme.onPrimaryContainer
    Box(Modifier.size(size).semantics { contentDescription = "$macName and this phone" }) {
        Canvas(Modifier.size(size)) {
            val k = this.size.width / 240f
            rotate(angle.value) { drawPath(cookiePath(k, radius, scallop), fill) }
            translate20x50(k) { drawDevices(k, onPc, scheme.primaryContainer, scheme.surface) }
        }
        Crossfade(look.line, animationSpec = Motion.ease(400), label = "stitch") { line ->
            Stitches(line, size, reduced, scheme.primary, onPc.copy(alpha = 0.35f))
        }
    }
}

@Composable
private fun Stitches(line: LinkLook.Line, size: Dp, reduced: Boolean, teal: Color, grey: Color) {
    // Same Wi‑Fi draws each line left to right, 80 ms apart, as the link forms or the screen opens
    val drawn = remember { List(3) { Animatable(if (line == LinkLook.Line.Solid && !reduced) 0f else 1f) } }
    if (line == LinkLook.Line.Solid && !reduced) {
        drawn.forEachIndexed { i, a -> LaunchedEffect(a) { a.animateTo(1f, Motion.emphasized(700, 80 * i)) } }
    }
    // Tor's dashes flow along, 11 units every 0.9 s
    val phase = remember { Animatable(0f) }
    if (line == LinkLook.Line.Dashed && !reduced) LaunchedEffect(Unit) {
        while (true) { phase.snapTo(0f); phase.animateTo(11f, tween(Motion.DASH_FLOW_MS, easing = LinearEasing)) }
    }
    Canvas(Modifier.size(size)) {
        val k = this.size.width / 240f
        translate20x50(k) {
            listOf(44f, 58f, 72f).forEachIndexed { i, y ->
                val (color, effect, to) = when (line) {
                    LinkLook.Line.Solid -> Triple(teal, null, 122f + 24f * drawn[i].value)
                    LinkLook.Line.Dashed -> Triple(teal, PathEffect.dashPathEffect(floatArrayOf(5 * k, 6 * k), -phase.value * k), 146f)
                    LinkLook.Line.Dotted -> Triple(grey, PathEffect.dashPathEffect(floatArrayOf(0.1f * k, 6 * k)), 146f)
                    LinkLook.Line.Broken -> Triple(grey, PathEffect.dashPathEffect(floatArrayOf(4 * k, 7 * k)), 146f)
                }
                drawLine(color, Offset(122 * k, y * k), Offset(to * k, y * k), 5 * k, StrokeCap.Round, effect)
            }
        }
    }
}

/** The prototype draws its devices at (20, 50) on the 240 grid. */
private inline fun DrawScope.translate20x50(k: Float, block: DrawScope.() -> Unit) = translate(20 * k, 50 * k, block)

private fun DrawScope.drawDevices(k: Float, ink: Color, pc: Color, screen: Color) {
    fun p(x: Float, y: Float) = Offset(x * k, y * k)
    drawRoundRect(screen, p(8f, 22f), Size(104 * k, 70 * k), CornerRadius(9 * k))
    drawRoundRect(ink, p(8f, 22f), Size(104 * k, 70 * k), CornerRadius(9 * k), style = Stroke(5 * k))
    drawLine(ink, p(0f, 104f), p(120f, 104f), 6 * k, StrokeCap.Round)
    drawRoundRect(ink, p(152f, 14f), Size(44 * k, 90 * k), CornerRadius(11 * k))
    drawLine(pc, p(168f, 95f), p(180f, 95f), 4 * k, StrokeCap.Round)
}

/** r = R + a·cos(9t) around (120, 120), scaled by [k]; the same point count at any R and a, so it morphs. */
private fun cookiePath(k: Float, r0: Float, a: Float) = Path().apply {
    for (i in 0..120) {
        val t = i / 120.0 * Math.PI * 2
        val r = r0 + a * cos(9 * t)
        val x = ((120 + r * cos(t)) * k).toFloat()
        val y = ((120 + r * sin(t)) * k).toFloat()
        if (i == 0) moveTo(x, y) else lineTo(x, y)
    }
    close()
}
