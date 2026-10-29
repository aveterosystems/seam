// SPDX-License-Identifier: AGPL-3.0-only
package com.avetero.seam.feature

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import com.avetero.seam.protocol.v1.Payload
import com.avetero.seam.protocol.v1.Status
import com.avetero.seam.session.Feature
import com.avetero.seam.session.SessionApi

/** Sends the phone's battery level and charging state to the Mac: on connect, and when either changes. */
class StatusFeature(context: Context) : Feature {
    private var session: SessionApi? = null
    private var lastSent: Pair<Int, Boolean>? = null

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) = update(intent, force = false)
    }

    // ACTION_BATTERY_CHANGED is sticky: registering returns the current state straight away
    private val app = context.applicationContext
    private var latest: Intent? = app.registerReceiver(receiver, IntentFilter(Intent.ACTION_BATTERY_CHANGED))

    override fun onConnected(session: SessionApi) {
        this.session = session
        latest?.let { update(it, force = true) }
    }

    private fun update(intent: Intent, force: Boolean) {
        latest = intent
        val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
        if (level < 0 || scale <= 0) return
        val pct = level * 100 / scale
        val charging = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1).let {
            it == BatteryManager.BATTERY_STATUS_CHARGING || it == BatteryManager.BATTERY_STATUS_FULL
        }
        if (!force && lastSent == pct to charging) return
        val s = session ?: return
        lastSent = pct to charging
        s.send(Payload.newBuilder().setStatus(Status.newBuilder().setBatteryPct(pct).setCharging(charging)).build())
    }
}
