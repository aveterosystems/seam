package com.avetero.seam.spike

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.widget.ScrollView
import android.widget.TextView

/** One screen: what's granted, whether the Mac is connected, and the recent log. */
class MainActivity : Activity() {
    private val main = Handler(Looper.getMainLooper())
    private lateinit var text: TextView

    private val refresh = object : Runnable {
        override fun run() {
            val logs = checkSelfPermission(Manifest.permission.READ_LOGS) == PackageManager.PERMISSION_GRANTED
            val overlay = Settings.canDrawOverlays(this@MainActivity)
            text.text = buildString {
                appendLine("Read system logs: ${if (logs) "granted" else "NOT granted (run the Mac helper)"}")
                appendLine("Draw over apps: ${if (overlay) "granted" else "NOT granted (run the Mac helper)"}")
                appendLine("Mac: ${if (Sender.connected) "connected" else "not connected"}")
                appendLine()
                appendLine("Copy something in any app. It should land on your Mac.")
                appendLine()
                Sender.recentLog.forEach { appendLine(it) }
            }
            main.postDelayed(this, 1000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        text = TextView(this).apply { setPadding(48, 96, 48, 48); textSize = 15f }
        setContentView(ScrollView(this).apply { addView(text) })
        requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        // Started while we're in front, so Android 13+ can show its log-access prompt
        startForegroundService(Intent(this, ClipService::class.java))
    }

    override fun onResume() { super.onResume(); main.post(refresh) }
    override fun onPause() { super.onPause(); main.removeCallbacks(refresh) }
}
