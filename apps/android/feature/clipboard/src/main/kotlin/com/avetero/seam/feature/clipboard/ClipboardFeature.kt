// SPDX-License-Identifier: AGPL-3.0-only
package com.avetero.seam.feature.clipboard

import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PersistableBundle
import com.avetero.seam.crypto.ClipRules
import com.avetero.seam.protocol.v1.Clipboard
import com.avetero.seam.protocol.v1.Payload
import com.avetero.seam.session.Feature
import com.avetero.seam.session.SessionApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Clipboard both ways. Mac → phone is always automatic. Phone → Mac is automatic in `full` builds
 * once the Mac has granted log access ([AutoClipboard]), and one tap everywhere else (the Send
 * button and the share sheet). Everything runs on the main thread.
 */
class ClipboardFeature(context: Context, private val session: SessionApi) : Feature {
    override val handles = setOf(Payload.BodyCase.CLIPBOARD)

    private val app = context.applicationContext
    private val clipboard = app.getSystemService(ClipboardManager::class.java)
    private val main = Handler(Looper.getMainLooper())
    /** The core's rules: no echoes, no quick repeats, nothing empty or oversized. */
    private val rules = ClipRules()

    /** The last clip from the Mac, for the home screen: Android's own "Copied" pop-up can't say where it came from. */
    private val _fromMac = MutableStateFlow<FromMac?>(null)
    val fromMac: StateFlow<FromMac?> = _fromMac

    override fun reset() {
        // On the main thread like every other use of rules, after any clip already posted (SR-29-08)
        main.post {
            rules.reset()
            _fromMac.value = null // the old Mac's last clip leaves the home screen
        }
    }

    /** Something new was copied on the phone (from [AutoClipboard]). */
    fun copied(text: String?) {
        rules.outgoing(text, System.currentTimeMillis())?.let(::send)
    }

    /** The user asked to send this (share sheet). False if there's nothing sendable. */
    fun sendNow(text: String?): Boolean = rules.forced(text, System.currentTimeMillis())?.also(::send) != null

    /** Send what's on the clipboard now. Only works while Seam has focus (Android 10+). */
    fun sendCurrent(): Boolean = sendNow(readClip())

    /**
     * The clipboard's text, unless an app marked it sensitive (a password manager, a one-time code).
     * Plain text only: a URI clip (a copied file) is never opened (SR-4-07).
     */
    fun readClip(): String? = readClip(fresh = false)

    /** [readClip], and with [fresh] only a clip copied in the last [FRESH_MS], so a false alarm never resends an old one. */
    fun readClip(fresh: Boolean): String? = runCatching {
        val clip = clipboard.primaryClip ?: return null
        if (clip.itemCount == 0) return null
        if (fresh && System.currentTimeMillis() - clip.description.timestamp !in 0..FRESH_MS) return null
        if (clip.description.extras?.getBoolean(ClipDescription.EXTRA_IS_SENSITIVE) == true) return null
        clip.getItemAt(0).text?.toString()
    }.getOrNull()

    /**
     * Home's Paste: puts what came from the Mac back on this phone's clipboard (main thread), marked
     * as from another device like [receive] (SR-11-10). False if it couldn't.
     */
    fun putBack(text: String): Boolean {
        val accepted = rules.incoming(text) ?: return false // and never send it back to the Mac
        return runCatching { clipboard.setPrimaryClip(fromMacClip(accepted)) }.isSuccess
    }

    /** Forget the last Mac copy (the Mac was forgotten): it mustn't show on Home for the next Mac (SR-11-09). */
    fun clearFromMac() {
        _fromMac.value = null
    }

    private fun fromMacClip(text: String): ClipData = ClipData.newPlainText("From your Mac", text).apply {
        if (Build.VERSION.SDK_INT >= 33) {
            description.extras = PersistableBundle().apply { putBoolean(ClipDescription.EXTRA_IS_REMOTE_DEVICE, true) }
        }
    }

    override fun receive(payload: Payload, fromPeer: String) {
        val text = payload.clipboard.text
        main.post {
            val accepted = rules.incoming(text) ?: return@post
            if (runCatching { clipboard.setPrimaryClip(fromMacClip(accepted)) }.isSuccess) _fromMac.value = FromMac(accepted, System.currentTimeMillis())
        }
    }

    private fun send(text: String) {
        session.send(Payload.newBuilder().setClipboard(Clipboard.newBuilder().setText(text).setCopiedAt(System.currentTimeMillis())).build())
    }
}

/** How recent an automatically noticed copy must be. */
const val FRESH_MS = 5_000L

/** A clip that arrived from the Mac, and when (unix ms). */
data class FromMac(val text: String, val at: Long) {
    /** Home shows it for as long as a copy may wait to be sent, then stops (SR-11-09). */
    fun fresh(now: Long): Boolean = now - at in 0 until SHOWN_MS

    companion object {
        const val SHOWN_MS = 10 * 60_000L
    }
}
