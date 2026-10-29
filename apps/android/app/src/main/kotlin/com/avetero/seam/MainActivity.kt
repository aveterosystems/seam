package com.avetero.seam

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.avetero.seam.crypto.SeamDevice

/** M0 placeholder: proves the Rust core loads on a real device. The real Home screen comes in M1. */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val status = runCatching { "Crypto core loaded · identity key ${SeamDevice("self").identityKey().size} bytes" }
            .getOrElse { "Crypto core failed to load: ${it.message}" }
        setContent {
            MaterialTheme {
                Surface(Modifier.fillMaxSize()) {
                    Column(Modifier.padding(24.dp)) {
                        Text("Seam", style = MaterialTheme.typography.headlineLarge)
                        Text(status)
                    }
                }
            }
        }
    }
}
