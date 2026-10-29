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
import com.avetero.seam.protocol.v1.Clipboard
import com.avetero.seam.protocol.v1.Payload
import com.avetero.seam.session.Feature
import com.avetero.seam.session.SessionApi

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
    private val rules = ClipRules()

    /** Something new was copied on the phone (from [AutoClipboard]). */
    fun copied(text: String?) {
        rules.outgoing(text)?.let(::send)
    }

    /** The user asked to send this (share sheet). False if there's nothing sendable. */
    fun sendNow(text: String?): Boolean = rules.forced(text)?.also(::send) != null

    /** Send what's on the clipboard now. Only works while Seam has focus (Android 10+). */
    fun sendCurrent(): Boolean = sendNow(readClip())

    /**
     * The clipboard's text, unless an app marked it sensitive (a password manager, a one-time code).
     * Plain text only: a URI clip (a copied file) is never opened (SR-4-07).
     */
    fun readClip(): String? = runCatching {
        val clip = clipboard.primaryClip ?: return null
        if (clip.itemCount == 0) return null
        if (clip.description.extras?.getBoolean(ClipDescription.EXTRA_IS_SENSITIVE) == true) return null
        clip.getItemAt(0).text?.toString()
    }.getOrNull()

    override fun receive(payload: Payload, fromPeer: String) {
        val text = payload.clipboard.text
        main.post {
            val accepted = rules.incoming(text) ?: return@post
            val clip = ClipData.newPlainText("From your Mac", accepted)
            if (Build.VERSION.SDK_INT >= 33) {
                clip.description.extras = PersistableBundle().apply { putBoolean(ClipDescription.EXTRA_IS_REMOTE_DEVICE, true) }
            }
            runCatching { clipboard.setPrimaryClip(clip) }
        }
    }

    private fun send(text: String) {
        session.send(Payload.newBuilder().setClipboard(Clipboard.newBuilder().setText(text).setCopiedAt(System.currentTimeMillis())).build())
    }
}
