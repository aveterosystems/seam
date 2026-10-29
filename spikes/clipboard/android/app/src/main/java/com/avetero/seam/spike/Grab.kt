package com.avetero.seam.spike

import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.os.SystemClock

/** Reads the clipboard once Seam briefly has focus, then hands it to the Sender. */
object Grab {
    /** elapsedRealtime when the copy was detected; 0 when nothing is pending. */
    @Volatile var pendingSince = 0L
    private var lastSent: String? = null

    fun start(): Boolean {
        val now = SystemClock.elapsedRealtime()
        if (pendingSince != 0L && now - pendingSince < 1500) return false
        pendingSince = now
        return true
    }

    fun deliver(ctx: Context, method: String) {
        val since = pendingSince
        if (since == 0L) return
        pendingSince = 0L
        val clip = ctx.getSystemService(ClipboardManager::class.java).primaryClip
        val ms = SystemClock.elapsedRealtime() - since
        if (clip == null || clip.itemCount == 0) {
            Sender.log("Focus via $method after $ms ms, but the clipboard read came back empty")
            return
        }
        // Never sync what apps mark as sensitive (passwords, codes from password managers)
        if (clip.description.extras?.getBoolean(ClipDescription.EXTRA_IS_SENSITIVE) == true) {
            Sender.log("Skipped a clip marked sensitive")
            return
        }
        val text = clip.getItemAt(0).coerceToText(ctx)?.toString().orEmpty()
        if (text.isEmpty() || text == lastSent) return
        lastSent = text
        Sender.clip(method, ms, text)
        Sender.log("Read ${text.length} chars via $method in $ms ms")
    }

    /** Gave up waiting for focus. */
    fun fail(why: String) {
        if (pendingSince == 0L) return
        pendingSince = 0L
        Sender.log("Couldn't read the clipboard: $why")
    }
}
