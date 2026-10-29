// SPDX-License-Identifier: AGPL-3.0-only
package com.avetero.seam.session

import kotlin.random.Random

/** Reconnect delays: 1 s doubling to 60 s, each with up to 20% jitter so devices don't sync up. */
class Backoff(private val random: Random = Random.Default) {
    private var attempt = 0

    fun next(): Long {
        val base = (1000L shl attempt.coerceAtMost(6)).coerceAtMost(60_000L)
        attempt++
        return base + (base * 0.2 * random.nextDouble()).toLong()
    }

    fun reset() {
        attempt = 0
    }
}
