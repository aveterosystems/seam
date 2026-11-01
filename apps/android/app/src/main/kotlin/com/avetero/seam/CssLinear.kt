// SPDX-License-Identifier: AGPL-3.0-only
package com.avetero.seam

import androidx.compose.animation.core.Easing

/**
 * CSS's `linear()` easing, so the app moves on exactly the curves the approved prototype uses
 * (its `--spring` and `--bouncy`). Stops are "value" or "value pos%"; stops without a position
 * are spread evenly between their neighbours, the first defaulting to 0% and the last to 100%.
 */
class CssLinear(spec: String) : Easing {
    private val xs: FloatArray
    private val ys: FloatArray

    init {
        val stops = spec.split(',').map { it.trim().split(Regex("\\s+")) }
        ys = FloatArray(stops.size) { stops[it][0].toFloat() }
        val pos = Array(stops.size) { i -> stops[i].getOrNull(1)?.removeSuffix("%")?.toFloat()?.div(100f) }
        if (pos[0] == null) pos[0] = 0f
        if (pos.last() == null) pos[pos.lastIndex] = 1f
        // A position never goes back: clamp to the largest one before it
        var max = 0f
        for (i in pos.indices) pos[i]?.let { max = maxOf(max, it); pos[i] = max }
        var i = 0
        while (i < pos.size) {
            if (pos[i] != null) { i++; continue }
            val from = i - 1
            var to = i
            while (pos[to] == null) to++
            for (k in from + 1 until to) pos[k] = pos[from]!! + (pos[to]!! - pos[from]!!) * (k - from) / (to - from)
            i = to
        }
        xs = FloatArray(pos.size) { pos[it]!! }
    }

    override fun transform(fraction: Float): Float {
        if (fraction <= 0f) return ys[0]
        if (fraction >= 1f) return ys.last()
        var i = 1
        while (xs[i] < fraction) i++
        val span = xs[i] - xs[i - 1]
        return if (span == 0f) ys[i] else ys[i - 1] + (ys[i] - ys[i - 1]) * (fraction - xs[i - 1]) / span
    }
}
