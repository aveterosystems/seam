// SPDX-License-Identifier: AGPL-3.0-only
package com.avetero.seam.session

import android.content.Context
import android.util.Base64

/** SessionPrefs in SharedPreferences. Nothing here is secret; the keys are in the sealed state. */
class AndroidSessionPrefs(context: Context) : SessionPrefs {
    private val prefs = context.getSharedPreferences("seam.session", Context.MODE_PRIVATE)

    override var relayUrl: String?
        get() = prefs.getString("relay_url", null)
        set(value) = prefs.edit().putString("relay_url", value).apply()

    override var groupId: ByteArray?
        get() = prefs.getString("group_id", null)?.let { Base64.decode(it, Base64.NO_WRAP) }
        set(value) = prefs.edit().putString("group_id", value?.let { Base64.encodeToString(it, Base64.NO_WRAP) }).apply()
}
