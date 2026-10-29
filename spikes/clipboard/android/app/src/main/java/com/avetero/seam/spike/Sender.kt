package com.avetero.seam.spike

import android.util.Base64
import android.util.Log
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.LinkedBlockingQueue

/**
 * Sends lines to the Mac helper. The helper runs `adb reverse`, so 127.0.0.1 on the phone is the Mac.
 * shortcut: plaintext over the adb tunnel, no pairing or encryption; the real app uses the Seam protocol.
 * Line format: "clip\t<method>\t<detect-to-read ms>\t<base64 text>" or "log\t<base64 text>".
 */
object Sender {
    const val PORT = 47800
    private val queue = LinkedBlockingQueue<String>(500)
    private val recent = ArrayDeque<String>()

    @Volatile var connected = false
        private set

    val recentLog: List<String> get() = synchronized(recent) { recent.toList() }

    fun clip(method: String, ms: Long, text: String) {
        queue.offer("clip\t$method\t$ms\t${b64(text)}")
    }

    fun log(msg: String) {
        Log.i("SeamSpike", msg)
        synchronized(recent) { recent.addFirst(msg); while (recent.size > 30) recent.removeLast() }
        queue.offer("log\t${b64(msg)}")
    }

    fun loop() {
        while (true) {
            try {
                Socket().use { s ->
                    s.connect(InetSocketAddress("127.0.0.1", PORT), 2000)
                    connected = true
                    val out = s.getOutputStream().bufferedWriter()
                    while (true) {
                        val line = queue.take()
                        out.write(line); out.write("\n"); out.flush()
                    }
                }
            } catch (e: Exception) {
                connected = false
                Thread.sleep(2000)
            }
        }
    }

    private fun b64(s: String) = Base64.encodeToString(s.toByteArray(), Base64.NO_WRAP)
}
