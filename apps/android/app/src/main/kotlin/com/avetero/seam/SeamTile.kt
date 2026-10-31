// SPDX-License-Identifier: AGPL-3.0-only
package com.avetero.seam

import android.content.ComponentName
import android.content.Context
import android.graphics.drawable.Icon
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService

/**
 * The Quick Settings tile: pause Seam (until you resume) or resume it, without opening the app.
 * Active means Seam is running; tapping pauses or resumes. Only the phone pauses.
 */
class SeamTile : TileService() {
    override fun onStartListening() {
        val tile = qsTile ?: return
        val session = (application as SeamApplication).session
        val now = System.currentTimeMillis()
        val until = session.settings.value.pausedUntil
        val paused = until > now
        tile.label = "Seam"
        tile.icon = Icon.createWithResource(this, R.drawable.ic_seam_mark)
        if (!session.paired) {
            tile.state = Tile.STATE_UNAVAILABLE
            tile.subtitle = "Not paired"
        } else {
            tile.state = if (paused) Tile.STATE_INACTIVE else Tile.STATE_ACTIVE
            tile.subtitle = if (paused) pausedLabel(until, now).removePrefix("Paused ") else "On"
        }
        tile.updateTile()
    }

    override fun onClick() {
        val session = (application as SeamApplication).session
        if (!session.paired) return
        if (session.settings.value.pausedUntil > System.currentTimeMillis()) session.resume()
        else session.pause(PauseFor.RESUME.until(System.currentTimeMillis()))
        // The session applies it on its own thread; the connection service refreshes the tile then
    }

    companion object {
        /** Asks Android to redraw the tile, e.g. after a pause changed elsewhere. */
        fun refresh(context: Context) = requestListeningState(context, ComponentName(context, SeamTile::class.java))
    }
}
