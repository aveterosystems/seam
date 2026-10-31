// SPDX-License-Identifier: AGPL-3.0-only
package com.avetero.seam

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.cos
import kotlin.math.sin

/**
 * Board A01's hero: the Expressive "cookie" (a circle with 9 soft scallops) in primaryContainer,
 * with the Mac and this phone joined by stitches, drawn from a [LinkLook.Line]
 * (design-system.md, "The stitch"): solid teal on the same Wi‑Fi, dashed teal through Tor,
 * dotted grey when only nearby, broken grey when not connected.
 */
@Composable
fun Hero(macName: String, line: LinkLook.Line, size: Dp = 300.dp) {
    val pc = MaterialTheme.colorScheme.primaryContainer
    val onPc = MaterialTheme.colorScheme.onPrimaryContainer
    val primary = MaterialTheme.colorScheme.primary
    val surface = MaterialTheme.colorScheme.surface
    Box(Modifier.size(size), contentAlignment = Alignment.Center) {
        Canvas(Modifier.size(size)) { drawPath(cookie(this.size.width), pc) }
        // The board's 200×140 drawing
        Canvas(Modifier.size(size * 2 / 3, size * 7 / 15).semantics { contentDescription = "$macName and this phone" }) {
            val k = this.size.width / 200f
            fun p(x: Float, y: Float) = Offset(x * k, y * k)
            drawRoundRect(surface, p(6f, 18f), Size(104 * k, 70 * k), CornerRadius(8 * k))
            drawRoundRect(onPc, p(6f, 18f), Size(104 * k, 70 * k), CornerRadius(8 * k), style = Stroke(5 * k))
            drawLine(onPc, p(0f, 100f), p(118f, 100f), 6 * k, StrokeCap.Round)
            drawRoundRect(onPc, p(150f, 10f), Size(44 * k, 90 * k), CornerRadius(10 * k))
            drawLine(pc, p(166f, 90f), p(178f, 90f), 4 * k, StrokeCap.Round)
            val grey = onPc.copy(alpha = 0.35f)
            val (color, dash) = when (line) {
                LinkLook.Line.Solid -> primary to null
                LinkLook.Line.Dashed -> primary to floatArrayOf(5 * k, 7 * k)
                LinkLook.Line.Dotted -> grey to floatArrayOf(0.1f * k, 6 * k)
                LinkLook.Line.Broken -> grey to floatArrayOf(6 * k, 10 * k)
            }
            for (y in listOf(40f, 54f, 68f)) {
                drawLine(color, p(120f, y), p(142f, y), 5 * k, StrokeCap.Round, dash?.let { PathEffect.dashPathEffect(it) })
            }
        }
    }
}

/** The board's formula: r = R + a·cos(9t), R = 138, a = 9 on a 300 grid. */
private fun cookie(side: Float): Path {
    val s = side / 300f
    return Path().apply {
        for (i in 0..180) {
            val t = i / 180.0 * Math.PI * 2
            val r = (138 + 9 * cos(9 * t)) * s
            val x = (150 * s + r * cos(t)).toFloat()
            val y = (150 * s + r * sin(t)).toFloat()
            if (i == 0) moveTo(x, y) else lineTo(x, y)
        }
        close()
    }
}
