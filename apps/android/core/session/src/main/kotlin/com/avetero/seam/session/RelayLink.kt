// SPDX-License-Identifier: AGPL-3.0-only
package com.avetero.seam.session

import java.util.concurrent.TimeUnit
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import okio.ByteString.Companion.toByteString

/** One open WebSocket to the relay. Frames are protobuf ClientFrame / ServerFrame bytes. */
interface RelayLink {
    fun send(frame: ByteArray): Boolean
    fun close()
}

interface RelayListener {
    fun onFrame(frame: ByteArray)
    fun onClosed(reason: String)
}

fun interface RelayConnector {
    fun connect(url: String, listener: RelayListener): RelayLink
}

class OkHttpRelayConnector(
    private val client: OkHttpClient = OkHttpClient.Builder().pingInterval(30, TimeUnit.SECONDS).build(),
) : RelayConnector {
    override fun connect(url: String, listener: RelayListener): RelayLink {
        val ws = client.newWebSocket(
            Request.Builder().url(url).build(),
            object : WebSocketListener() {
                override fun onMessage(webSocket: WebSocket, bytes: ByteString) = listener.onFrame(bytes.toByteArray())
                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) = listener.onClosed("closed $code")
                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) =
                    listener.onClosed(t.javaClass.simpleName)
            },
        )
        return object : RelayLink {
            override fun send(frame: ByteArray) = ws.send(frame.toByteString())
            override fun close() {
                ws.close(1000, null)
            }
        }
    }
}
