// SPDX-License-Identifier: AGPL-3.0-only
package com.avetero.seam.session

import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import okio.ByteString.Companion.toByteString

/** Opens one WebSocket to the relay. Frames are protobuf ClientFrame / ServerFrame bytes. */
fun interface RelayConnector {
    fun connect(url: String, listener: LinkListener): Link
}

class OkHttpRelayConnector(
    // Decision 0005: as rare as the network allows. The relay kept a silent connection open 15+
    // minutes (measured 29 Oct 2026); 2 minutes is set by how soon a dropped link should be noticed
    // while it's held (screen on or charging), and stays under common mobile-network idle timeouts.
    // OkHttp also waits one interval for the pong, so a silently dead link is noticed within about
    // 4 minutes while held (SR-8-05); nothing is lost meanwhile, the relay queues it.
    private val client: OkHttpClient = OkHttpClient.Builder().pingInterval(2, TimeUnit.MINUTES).build(),
) : RelayConnector {
    override fun connect(url: String, listener: LinkListener): Link {
        val closed = AtomicBoolean(false)
        fun closeOnce(reason: String) {
            if (closed.compareAndSet(false, true)) listener.onClosed(reason)
        }
        val ws = client.newWebSocket(
            Request.Builder().url(url).build(),
            object : WebSocketListener() {
                override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                    // Nothing legitimate is bigger than an envelope plus framing
                    if (bytes.size <= MAX_FRAME) listener.onFrame(bytes.toByteArray())
                }
                // A relay-initiated close: answer it and reconnect now, not at the next failed ping
                override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                    webSocket.close(1000, null)
                    closeOnce("closing $code")
                }
                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) = closeOnce("closed $code")
                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) =
                    closeOnce(t.javaClass.simpleName)
            },
        )
        return object : Link {
            override fun send(frame: ByteArray) = ws.send(frame.toByteString())
            override fun close() {
                ws.close(1000, null)
            }
        }
    }

    private companion object {
        const val MAX_FRAME = 1_200_000
    }
}
